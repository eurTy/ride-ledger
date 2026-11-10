package com.rideledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/** The service and the foreground both drive this scheduler on the main looper. */
final class SyncEngine {
    enum Phase { SYNCING, COMPLETE, PENDING }

    static final class Status {
        static final Status EMPTY = new Status(Phase.COMPLETE, 0, -1);
        final Phase phase;
        final int pending;
        final long confirmedThrough;
        final List<PeerPresence.Link> links;
        final boolean searching;

        Status(Phase phase, int pending, long confirmedThrough) {
            this(phase, pending, confirmedThrough, Collections.emptyList(), false);
        }

        Status(Phase phase, int pending, long confirmedThrough, List<PeerPresence.Link> links, boolean searching) {
            this.phase = phase;
            this.pending = pending;
            this.confirmedThrough = confirmedThrough;
            this.links = Collections.unmodifiableList(links);
            this.searching = searching;
        }

        String label() {
            if (phase == Phase.SYNCING) return "同步中";
            return phase == Phase.COMPLETE ? "已同步" : "待同步 " + pending + " 条";
        }
    }

    interface Dial { void start(PeerRoutePolicy.Endpoint endpoint, long revision); }

    private final LedgerStore store;
    private final Context context;
    private final LedgerStore.Trip trip;
    private final String device;
    private final SharedPreferences checkpoints;
    private final String prefix;
    private final Handler main;
    private final PeerRoutePolicy routes = new PeerRoutePolicy(SystemClock::elapsedRealtime);
    private final Map<String, PeerRoutePolicy.Channel> lastChannel = new HashMap<>();
    private final Map<PeerConnection, String> activeSessions = new HashMap<>();
    private final PeerPresence presence = new PeerPresence();
    private long searchUntil;
    private final Dial dial;
    private final Consumer<Status> listener;
    private final Runnable dispatch = this::dispatch;
    private final Runnable retry = this::dispatch;
    private boolean running;
    private volatile long revision;
    private int incoming;
    private boolean scheduled;
    private boolean pendingDirty = true;
    private volatile Status status = new Status(Phase.PENDING, 0, -1);

    SyncEngine(Context context, LedgerStore store, LedgerStore.Trip trip, String device,
               Handler main, Dial dial, Consumer<Status> listener) {
        this.store = store;
        this.context = context.getApplicationContext();
        this.trip = trip;
        this.device = device;
        this.main = main;
        this.dial = dial;
        this.listener = listener;
        checkpoints = context.getSharedPreferences("peer_checkpoints", Context.MODE_PRIVATE);
        prefix = checkpointPrefix(trip, device);
    }

    void start() {
        running = true;
        request();
    }

    void request() {
        revision++;
        pendingDirty = true;
        routes.newSyncRequest();
        schedule();
    }

    void offer(PeerRoutePolicy.Endpoint endpoint) {
        if (routes.offer(endpoint)) schedule();
    }

    void rosterChanged() {
        pendingDirty = true;
        schedule();
    }

    void lost(String key) {
        routes.remove(key);
        presence.lost(key);
        schedule();
    }

    void lost(PeerRoutePolicy.Channel channel) {
        routes.removeChannel(channel);
        presence.lost(channel);
        schedule();
    }

    long revision() { return revision; }
    Status status() { return status; }
    boolean hasContact() {
        return incoming > 0 || !routes.onlinePeers().isEmpty() || routes.nextSyncRetry(revision) != Long.MAX_VALUE;
    }

    void incoming(boolean started) {
        incoming = Math.max(0, incoming + (started ? 1 : -1));
        schedule();
    }

    void reconnect() {
        searchUntil = SystemClock.elapsedRealtime() + 5000;
        request();
    }

    void authenticated(PeerConnection session, String peer, PeerRoutePolicy.Channel channel, String route, boolean visible) {
        activeSessions.put(session, TeamProtocol.routeId(trip.team, peer));
        if (!visible) return;
        try {
            org.json.JSONObject seat = store.seat(trip.id, peer);
            if (seat == null || !trip.team.fingerprint.equals(seat.optString("team"))) return;
            String member = seat.optString("member");
            if (trip.indexOf(member) < 0 || device.equals(peer)) return;
            presence.authenticated(session, member, channel, route);
            searchUntil = 0;
            schedule();
        } catch (org.json.JSONException | RuntimeException error) {
            SyncLog.write(context, SyncLog.Event.QUEUE_READ, error);
        }
    }

    /** Null means a failed session. An empty route denotes a verified inbound-only peer. */
    void sessionFinished(PeerConnection session, String verifiedRoute, boolean stored, long captured) {
        String source = activeSessions.remove(session);
        presence.finished(session, verifiedRoute, SystemClock.elapsedRealtime());
        if (stored) {
            boolean sourceHasRevision = verifiedRoute != null && source != null && captured == revision;
            revision++;
            pendingDirty = true;
            // Concurrent local writes or data from another peer may still need to go back to the source.
            if (sourceHasRevision) routes.markRelayedFrom(source, revision);
        }
        schedule();
    }

    void complete(PeerRoutePolicy.Endpoint endpoint, PeerRoutePolicy.Channel channel,
                  PeerProtocol.Result result, long captured) {
        if (endpoint != null) routes.syncSuccess(endpoint, captured);
        String peer = TeamProtocol.routeId(trip.team, result.remoteId);
        routes.received(peer, channel, captured);
        PeerRoutePolicy.Channel previous = lastChannel.put(peer, channel);
        if (previous != null && previous != channel) SyncLog.write(context, SyncLog.Event.CHANNEL_SWITCH, previous.name() + ">" + channel.name());
        LedgerStore.SyncPoint point = result.syncPoint;
        if (result.complete && result.authenticated && result.durable && point != null) {
            String key = prefix + result.remoteId;
            try {
                if (point.row > confirmedRow(store, checkpoints, trip.id, key))
                    checkpoints.edit().putLong(key, point.row).putString(key + "/event", point.event).apply();
            } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.CHECKPOINT_SAVE, error); }
            pendingDirty = true;
        }
        schedule();
    }

    void failed(PeerRoutePolicy.Endpoint endpoint) {
        if (endpoint != null) {
            routes.syncFailure(endpoint);
            presence.lost(endpoint.key);
        }
        schedule();
    }

    private void schedule() {
        if (!running || scheduled) return;
        main.removeCallbacks(retry);
        scheduled = true;
        main.postDelayed(dispatch, 150);
    }

    private void dispatch() {
        scheduled = false;
        if (!running) return;
        Set<String> activePeers = new HashSet<>(activeSessions.values());
        List<PeerRoutePolicy.Endpoint> ready = routes.readyForRevision(revision, "", activePeers);
        for (PeerRoutePolicy.Endpoint endpoint : ready) dial.start(endpoint, revision);
        publish();
        long next = Math.min(routes.nextSyncRetry(revision, activePeers), presence.nextExpiry());
        if (status.searching) next = Math.min(next, searchUntil);
        if (!scheduled && next != Long.MAX_VALUE)
            main.postDelayed(retry, Math.max(1, next - SystemClock.elapsedRealtime()));
    }

    private void publish() {
        int count = status.pending;
        long confirmed = status.confirmedThrough;
        if (pendingDirty) try {
            long through = confirmedThrough(store, checkpoints, trip, device);
            count = store.countAfter(trip.id, through);
            confirmed = through;
            pendingDirty = false;
        } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.QUEUE_READ, error); }
        Phase phase = incoming + routes.syncing() > 0 ? Phase.SYNCING : count > 0 ? Phase.PENDING : Phase.COMPLETE;
        long now = SystemClock.elapsedRealtime();
        List<PeerPresence.Link> links = presence.links(now);
        boolean searching = searchUntil > now;
        if (status.phase == phase && status.pending == count && status.confirmedThrough == confirmed &&
                status.searching == searching && status.links.equals(links)) return;
        status = new Status(phase, count, confirmed, links, searching);
        listener.accept(status);
    }

    private static String checkpointPrefix(LedgerStore.Trip trip, String device) {
        return trip.id + "/" + trip.team.fingerprint + "/" + device + "/";
    }

    private static long confirmedRow(LedgerStore store, SharedPreferences checkpoints, String tripId, String key) {
        return store.confirmedRow(tripId, checkpoints.getLong(key, 0), checkpoints.getString(key + "/event", null));
    }

    static long confirmedThrough(Context context, LedgerStore store, LedgerStore.Trip trip) {
        if (trip.team == null || trip.people() == 1) return Long.MAX_VALUE;
        return confirmedThrough(store, context.getSharedPreferences("peer_checkpoints", Context.MODE_PRIVATE),
                trip, PeerIdentity.deviceId(context));
    }

    private static long confirmedThrough(LedgerStore store, SharedPreferences checkpoints,
                                         LedgerStore.Trip trip, String device) {
        List<String> peers = store.teammateDevices(trip, device);
        // Until the roster is complete, unseen teammates have not confirmed any local records.
        if (peers.size() + 1 < trip.people()) return 0;
        String prefix = checkpointPrefix(trip, device);
        long confirmed = Long.MAX_VALUE;
        for (String peer : peers) confirmed = Math.min(confirmed, confirmedRow(store, checkpoints, trip.id, prefix + peer));
        return confirmed;
    }

    void stop() {
        running = false;
        scheduled = false;
        main.removeCallbacks(dispatch);
        main.removeCallbacks(retry);
        routes.clear();
        lastChannel.clear();
        activeSessions.clear();
        presence.clear();
        searchUntil = 0;
        incoming = 0;
    }
}
