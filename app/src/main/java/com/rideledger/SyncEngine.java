package com.rideledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** The service and the foreground both drive this scheduler on the main looper. */
final class SyncEngine {
    private static final long SEARCH_MILLIS = 5000;

    enum Phase { SYNCING, COMPLETE, PENDING }
    enum FailureStage { CONNECT, HANDSHAKE, LEDGER, CONFIRMATION, RECEIPTS }

    static final class Failure {
        final FailureStage stage;
        final PeerRoutePolicy.Channel channel;

        Failure(FailureStage stage, PeerRoutePolicy.Channel channel) {
            this.stage = stage;
            this.channel = channel;
        }
    }

    static final class PeerDetail {
        final String name;
        final PeerPresence.Link link;
        final boolean registered, connecting;
        final int pending;
        final long checkedAt;
        final PeerRoutePolicy.Channel checkedChannel;
        final Failure failure;

        PeerDetail(String name, boolean registered, boolean connecting, PeerPresence.Link link,
                   int pending, long checkedAt, PeerRoutePolicy.Channel checkedChannel, Failure failure) {
            this.name = name;
            this.registered = registered;
            this.connecting = connecting;
            this.link = link;
            this.pending = pending;
            this.checkedAt = checkedAt;
            this.checkedChannel = checkedChannel;
            this.failure = failure;
        }
    }

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
            return pending == 0 ? "本机已保存" : "待同伴确认 " + pending + " 条";
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
    private final PeerRoutePolicy routes;
    private final Map<String, PeerRoutePolicy.Channel> lastChannel = new HashMap<>();
    private final Map<PeerConnection, String> activeSessions = new HashMap<>();
    private final Map<String, Failure> lastFailures = new HashMap<>();
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
        this.routes = new PeerRoutePolicy(SystemClock::elapsedRealtime, TeamProtocol.routeId(trip.team, device));
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

    List<PeerDetail> details(LedgerStore.Trip current) throws JSONException {
        if (!trip.id.equals(current.id) || !trip.team.fingerprint.equals(current.team.fingerprint))
            return Collections.emptyList();
        Map<String, String> devices = new HashMap<>();
        JSONArray seats = store.teamSeats(trip.id);
        for (int i = 0; i < seats.length(); i++) {
            JSONObject seat = seats.getJSONObject(i);
            if (trip.team.fingerprint.equals(seat.optString("team")))
                devices.put(seat.getString("member"), seat.getString("device"));
        }
        Map<String, PeerPresence.Link> links = new HashMap<>();
        for (PeerPresence.Link link : status.links) links.put(link.member, link);
        Map<Long, Integer> pendingByPoint = new HashMap<>();
        List<PeerDetail> details = new ArrayList<>();
        for (int i = 0; i < current.people(); i++) {
            String member = current.member(i);
            if (member.equals(current.localPerson)) continue;
            String remote = devices.get(member);
            if (remote == null) {
                details.add(new PeerDetail(current.nameOf(member), false, false, null, 0, 0, null, null));
                continue;
            }
            String key = prefix + remote;
            long point = confirmedRow(store, checkpoints, trip.id, key);
            int pending = pendingByPoint.computeIfAbsent(point, row -> store.countAfter(trip.id, row));
            String route = TeamProtocol.routeId(trip.team, remote);
            details.add(new PeerDetail(current.nameOf(member), true, routes.busy(route), links.get(member),
                    pending, checkpoints.getLong(key + "/checked_at", 0), checkedChannel(key), lastFailures.get(route)));
        }
        return details;
    }

    private PeerRoutePolicy.Channel checkedChannel(String key) {
        String saved = checkpoints.getString(key + "/checked_channel", "");
        for (PeerRoutePolicy.Channel channel : PeerRoutePolicy.Channel.values())
            if (channel.name().equals(saved)) return channel;
        return null;
    }

    boolean hasContact() {
        return incoming > 0 || !routes.onlinePeers().isEmpty() || routes.hasShortSyncRetry(revision);
    }

    void incoming(boolean started) {
        incoming = Math.max(0, incoming + (started ? 1 : -1));
        schedule();
    }

    boolean reconnect() {
        long now = SystemClock.elapsedRealtime();
        if (!running || searchUntil > now) return false;
        searchUntil = now + SEARCH_MILLIS;
        request();
        updateStatus(status.phase, status.pending, status.confirmedThrough, status.links, true);
        return true;
    }

    void authenticated(PeerConnection session, String peer, PeerRoutePolicy.Channel channel, String route, boolean visible) {
        activeSessions.put(session, TeamProtocol.routeId(trip.team, peer));
        if (!visible) return;
        try {
            JSONObject seat = store.seat(trip.id, peer);
            if (seat == null || !trip.team.fingerprint.equals(seat.optString("team"))) return;
            String member = seat.optString("member");
            if (trip.indexOf(member) < 0 || device.equals(peer)) return;
            presence.authenticated(session, member, channel, route);
            searchUntil = 0;
            schedule();
        } catch (JSONException | RuntimeException error) {
            SyncLog.write(context, SyncLog.Event.QUEUE_READ, error);
        }
    }

    /** Null means a failed session. An empty route denotes a verified inbound-only peer. */
    void sessionFinished(PeerConnection session, PeerRoutePolicy.Channel channel,
                         String verifiedRoute, boolean stored, long captured) {
        String source = activeSessions.remove(session);
        presence.finished(session, verifiedRoute, SystemClock.elapsedRealtime());
        if (stored) {
            boolean sourceHasRevision = verifiedRoute != null && source != null && captured == revision;
            revision++;
            pendingDirty = true;
            // Concurrent local writes or data from another peer may still need to go back to the source.
            if (sourceHasRevision) routes.markRelayedFrom(source, channel, revision);
        }
        schedule();
    }

    void complete(PeerRoutePolicy.Endpoint endpoint, PeerRoutePolicy.Channel channel,
                  PeerProtocol.Result result, long captured) {
        String peer = TeamProtocol.routeId(trip.team, result.remoteId);
        if (result.receiptError == null) {
            if (endpoint != null) routes.syncSuccess(endpoint, captured);
            routes.received(peer, channel, captured);
        } else if (endpoint != null) routes.syncFailure(endpoint);
        PeerRoutePolicy.Channel previous = lastChannel.put(peer, channel);
        if (previous != null && previous != channel) SyncLog.write(context, SyncLog.Event.CHANNEL_SWITCH, previous.name() + ">" + channel.name());
        LedgerStore.SyncPoint point = result.syncPoint;
        if (result.complete && result.authenticated && result.durable) {
            String key = prefix + result.remoteId;
            try {
                long now = System.currentTimeMillis();
                SharedPreferences.Editor verified = checkpoints.edit().putLong(prefix + "last_checked_at", now)
                        .putLong(key + "/checked_at", now).putString(key + "/checked_channel", channel.name());
                if (point != null && point.row > confirmedRow(store, checkpoints, trip.id, key))
                    verified.putLong(key, point.row).putString(key + "/event", point.event);
                verified.apply();
            } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.CHECKPOINT_SAVE, error); }
            pendingDirty = true;
            if (result.receiptError == null) lastFailures.remove(peer);
            else lastFailures.put(peer, new Failure(FailureStage.RECEIPTS, channel));
        } else if (result.authenticated) {
            lastFailures.put(peer, new Failure(FailureStage.CONFIRMATION, channel));
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

    void recordFailure(PeerConnection session, PeerRoutePolicy.Endpoint endpoint,
                       PeerRoutePolicy.Channel channel, FailureStage stage) {
        String route = activeSessions.get(session);
        if (route == null && endpoint != null && !endpoint.peerId.isEmpty()) {
            try {
                for (String peer : store.teammateDevices(trip, device)) {
                    if (endpoint.peerId.equals(TeamProtocol.routeId(trip.team, peer))) {
                        route = endpoint.peerId;
                        break;
                    }
                }
            } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.QUEUE_READ, error); }
        }
        if (route != null) lastFailures.put(route, new Failure(stage, channel));
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
        updateStatus(phase, count, confirmed, links, searchUntil > now);
    }

    private void updateStatus(Phase phase, int count, long confirmed, List<PeerPresence.Link> links, boolean searching) {
        if (status.phase == phase && status.pending == count && status.confirmedThrough == confirmed &&
                status.searching == searching && status.links.equals(links)) return;
        status = new Status(phase, count, confirmed, links, searching);
        listener.accept(status);
    }

    private static String checkpointPrefix(LedgerStore.Trip trip, String device) {
        return trip.id + "/" + trip.team.fingerprint + "/" + device + "/";
    }

    static long lastCheckedAt(Context context, LedgerStore.Trip trip) {
        if (trip.team == null || trip.people() == 1) return 0;
        String key = checkpointPrefix(trip, PeerIdentity.deviceId(context)) + "last_checked_at";
        return context.getSharedPreferences("peer_checkpoints", Context.MODE_PRIVATE).getLong(key, 0);
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
        lastFailures.clear();
        activeSessions.clear();
        presence.clear();
        searchUntil = 0;
        incoming = 0;
    }
}
