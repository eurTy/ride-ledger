package com.rideledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The service and the foreground both drive this scheduler on the main looper. */
final class SyncEngine {
    enum Phase { SYNCING, COMPLETE, PENDING }

    static final class Status {
        static final Status EMPTY = new Status(Phase.COMPLETE, 0);
        final Phase phase;
        final int pending;

        Status(Phase phase, int pending) {
            this.phase = phase;
            this.pending = pending;
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
    private final Dial dial;
    private final Consumer<Status> listener;
    private final Runnable dispatch = this::dispatch;
    private final Runnable retry = this::dispatch;
    private boolean running;
    private volatile long revision;
    private int incoming;
    private boolean scheduled;
    private boolean pendingDirty = true;
    private volatile Status status = new Status(Phase.PENDING, 0);

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
        prefix = trip.id + "/" + trip.team.fingerprint + "/" + device + "/";
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

    void lost(String key) {
        routes.remove(key);
        schedule();
    }

    void lost(PeerRoutePolicy.Channel channel) {
        routes.removeChannel(channel);
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

    void complete(PeerRoutePolicy.Endpoint endpoint, PeerRoutePolicy.Channel channel,
                  PeerProtocol.Result result, long captured) {
        if (endpoint != null) routes.syncSuccess(endpoint, captured);
        String peer = TeamProtocol.routeId(trip.team, result.remoteId);
        routes.received(peer, channel, captured);
        PeerRoutePolicy.Channel previous = lastChannel.put(peer, channel);
        if (previous != null && previous != channel) SyncLog.write(context, "CHANNEL_SWITCH", previous.name() + ">" + channel.name());
        LedgerStore.SyncPoint point = result.syncPoint;
        if (result.complete && result.authenticated && result.durable && point != null) {
            String key = prefix + result.remoteId;
            try {
                if (point.row > confirmedRow(key))
                    checkpoints.edit().putLong(key, point.row).putString(key + "/event", point.event).apply();
            } catch (RuntimeException error) { SyncLog.write(context, "CHECKPOINT_SAVE", error); }
            pendingDirty = true;
        }
        schedule();
    }

    void failed(PeerRoutePolicy.Endpoint endpoint) {
        if (endpoint != null) routes.syncFailure(endpoint);
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
        List<PeerRoutePolicy.Endpoint> ready = routes.readyForRevision(revision, "");
        for (PeerRoutePolicy.Endpoint endpoint : ready) dial.start(endpoint, revision);
        publish();
        long next = routes.nextSyncRetry(revision);
        if (!scheduled && next != Long.MAX_VALUE)
            main.postDelayed(retry, Math.max(1, next - SystemClock.elapsedRealtime()));
    }

    private void publish() {
        int count = status.pending;
        if (pendingDirty) try {
            count = pendingCount();
            pendingDirty = false;
        } catch (RuntimeException error) { SyncLog.write(context, "QUEUE_READ", error); }
        Phase phase = incoming + routes.syncing() > 0 ? Phase.SYNCING : count > 0 ? Phase.PENDING : Phase.COMPLETE;
        if (status.phase == phase && status.pending == count) return;
        status = new Status(phase, count);
        listener.accept(status);
    }

    private long confirmedRow(String key) {
        return store.confirmedRow(trip.id, checkpoints.getLong(key, 0), checkpoints.getString(key + "/event", null));
    }

    private int pendingCount() {
        List<String> peers = store.teammateDevices(trip, device);
        // Until the roster is complete, unseen teammates have not confirmed any local records.
        if (peers.size() + 1 < trip.people()) return store.countAfter(trip.id, 0);
        if (peers.isEmpty()) return 0;
        long confirmed = Long.MAX_VALUE;
        for (String peer : peers) confirmed = Math.min(confirmed, confirmedRow(prefix + peer));
        return store.countAfter(trip.id, confirmed);
    }

    void stop() {
        running = false;
        scheduled = false;
        main.removeCallbacks(dispatch);
        main.removeCallbacks(retry);
        routes.clear();
        lastChannel.clear();
        incoming = 0;
    }
}
