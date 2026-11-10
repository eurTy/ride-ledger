package com.rideledger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Adapts the radio and socket lifecycles to the ledger scheduler and first enrollment. */
final class PeerSync {
    private static final long ENROLLMENT_TIMEOUT = 12000;
    interface Listener { void changed(int added); }
    private final LedgerStore store;
    private final Context context;
    private final LedgerStore.Trip team;
    private final String enrollmentPeer;
    private final String deviceId;
    private final Listener listener;
    private final boolean startRadios;
    private final boolean background;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRoutePolicy routes = new PeerRoutePolicy(SystemClock::elapsedRealtime);
    private final SyncEngine engine;
    private final Map<PeerConnection, PeerRoutePolicy.Channel> connections = new ConcurrentHashMap<>();
    private final PeerRadios radios;
    private volatile ThreadPoolExecutor workers;
    private volatile ThreadPoolExecutor incomingWorkers;
    private volatile ScheduledExecutorService timer;
    private volatile boolean running;
    private volatile boolean enrolled;
    private boolean initialHistory;
    private long lastProgress;
    private int pendingNotification;
    private boolean notificationScheduled;
    private final Runnable publishChange = this::publishChanges;

    private void publishChanges() {
        notificationScheduled = false;
        int count = pendingNotification;
        pendingNotification = 0;
        if (running) listener.changed(count);
    }
    private volatile int epoch;
    private long revision, lastBluetoothRefresh, discoveryStarted;
    private boolean pending = true;
    private volatile String summary = "本机账目已保存 · 等待同伴连接";
    private volatile String admissionError = "";

    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios) {
        this(context, store, listener, startRadios, null);
    }
    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios, LedgerStore.Trip team) {
        this(context, store, listener, startRadios, team, false);
    }
    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios, LedgerStore.Trip team,
             boolean background) {
        this.context = context.getApplicationContext();
        this.store = store; this.listener = listener; this.startRadios = startRadios;
        this.team = team;
        this.background = background;
        deviceId = PeerIdentity.deviceId(context);
        engine = team == null ? null : new SyncEngine(context, store, team, deviceId, main,
                (endpoint, captured) -> outgoing(endpoint, captured, true), state -> notifyChange(0));
        String discoveryId = team == null ? deviceId : TeamProtocol.routeId(team.team, deviceId);
        String scope = team == null ? "" : team.team.discoveryScope();
        enrollmentPeer = team == null ? "" : TeamProtocol.routeId(team.team, team.team.issuer);
        radios = new PeerRadios(context, team, deviceId, discoveryId, scope, new PeerRadios.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void lost(String key) { lose(key); }
            @Override public void lost(PeerRoutePolicy.Channel channel) { lose(channel); }
            @Override public void incoming(PeerConnection connection, PeerRoutePolicy.Channel channel, String address) {
                accept(connection, channel, address);
            }
            @Override public void changed() { notifyChange(0); }
        });
    }

    void start() {
        if (running) return; running = true; epoch++; pending = true;
        enrolled = team == null || store.teamReady(team);
        initialHistory = false;
        lastProgress = 0;
        discoveryStarted = SystemClock.elapsedRealtime();
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        // Reserve inbound capacity so four simultaneous dials cannot starve the matching receivers.
        if (team != null) incomingWorkers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        timer = Executors.newSingleThreadScheduledExecutor();
        if (engine != null && enrolled) engine.start();
        if (startRadios) radios.start(timer, background);
        if (engine == null || !enrolled) main.post(tick);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || engine != null && enrolled) return;
            long now = SystemClock.elapsedRealtime();
            if (startRadios && team == null && now - lastBluetoothRefresh >= 10000) {
                lastBluetoothRefresh = now;
                radios.refreshLegacyBluetooth();
            }
            boolean urgent;
            long current;
            synchronized (PeerSync.this) { urgent = pending; current = revision; }
            List<PeerRoutePolicy.Endpoint> ready;
            if (team == null) ready = routes.ready(urgent);
            else {
                int maximumRank = (int) Math.min(2, (now - discoveryStarted) / PeerRoutePolicy.ENROLLMENT_STAGE_MILLIS);
                ready = routes.readyForEnrollment(current, enrollmentPeer, maximumRank);
            }
            for (PeerRoutePolicy.Endpoint endpoint : ready) outgoing(endpoint, current, false);
            main.removeCallbacks(this);
            if (team == null) {
                main.postDelayed(this, 2000);
            } else {
                long wake = routes.nextEnrollmentWake(enrollmentPeer, discoveryStarted);
                if (wake != Long.MAX_VALUE) main.postDelayed(this, Math.max(1, wake - now));
            }
        }
    };
    void offer(PeerRoutePolicy.Endpoint endpoint) {
        onMain(epoch, () -> {
            if (engine != null) engine.offer(endpoint);
            if ((engine == null || !enrolled) && routes.offer(endpoint)) request(team != null);
        });
    }
    private void lose(String key) {
        onMain(epoch, () -> {
            routes.remove(key);
            if (team != null && !enrolled) request(true);
            if (engine != null) engine.lost(key);
        });
    }
    private void lose(PeerRoutePolicy.Channel channel) {
        onMain(epoch, () -> {
            routes.removeChannel(channel);
            closeChannel(channel);
            if (engine == null || !enrolled) request(false);
            if (engine != null) engine.lost(channel);
        });
    }
    /** Called only after SQLite accepted a local mutation; no separate fragile outbox is needed. */
    void syncNow() {
        onMain(epoch, () -> {
            if (engine == null) {
                request(false);
            } else if (enrolled) {
                engine.request();
                radios.announce();
            } else if (store.teamReady(team)) {
                enrolled = true;
                main.removeCallbacks(tick);
                main.removeCallbacks(runSoon);
                engine.start();
                notifyChange(0);
            } else {
                request(false);
            }
        });
    }

    void reconnect() {
        onMain(epoch, () -> {
            if (engine == null || !enrolled || !engine.reconnect()) return;
            if (startRadios) radios.rediscover();
        });
    }
    private void request(boolean unchangedRevision) {
        synchronized (this) { if (!unchangedRevision) revision++; pending = true; }
        if (running) { main.removeCallbacks(runSoon); main.postDelayed(runSoon, 150); }
    }
    private final Runnable runSoon = () -> { if (running) { main.removeCallbacks(tick); main.post(tick); } };
    private static final class Transfer {
        boolean stored;
        String verifiedRoute;
    }

    private void outgoing(PeerRoutePolicy.Endpoint endpoint, long captured, boolean ledgerSync) {
        final int generation = epoch;
        ThreadPoolExecutor pool = workers;
        if (!running || pool == null) return;
        try {
            pool.execute(() -> {
                PeerConnection connection = null;
                PeerProtocol legacy = null;
                Transfer transfer = new Transfer();
                try {
                    if (!running || epoch != generation) return;
                    connection = endpoint.dial.call();
                    ScheduledExecutorService watchdog = track(connection, endpoint.channel, generation);
                    if (watchdog == null) return;
                    boolean admission = team != null && !ledgerSync;
                    if (admission) connection.watch(watchdog, ENROLLMENT_TIMEOUT, ENROLLMENT_TIMEOUT);
                    else connection.watch(watchdog);
                    PeerProtocol.Listener callbacks = callbacks(connection, endpoint.key, endpoint.channel,
                            address(endpoint), generation, transfer, () -> { });
                    PeerConnection channel = connection;
                    if (team == null) legacy = new PeerProtocol(store, deviceId, callbacks);
                    PeerProtocol.Result result = legacy == null
                            ? teamProtocol(callbacks, generation, connection.listenerPort,
                                    admission ? () -> channel.watch(watchdog) : () -> { }).exchange(connection.wire(), endpoint.peerId)
                            : legacy.exchange(connection.wire(), endpoint.modern);
                    if (!running || epoch != generation) return;
                    if (!result.complete || !result.authenticated) throw new IllegalStateException("保存确认待重试");
                    PeerRoutePolicy.Endpoint reverse = callbackEndpoint(connection, result);
                    transfer.verifiedRoute = endpoint.key;
                    onMain(generation, () -> {
                        if (!ledgerSync) {
                            if (team == null) routes.remember(endpoint.key, result.remoteId, result.modern);
                            routes.success(endpoint.key, team == null ? -1 : captured);
                        }
                        if (reverse != null) engine.offer(reverse);
                        completed(ledgerSync ? endpoint : null, endpoint.channel, result, captured, ledgerSync);
                    });
                } catch (Exception error) {
                    int added = legacy == null ? 0 : legacy.progress().added;
                    onMain(generation, () -> {
                        if (ledgerSync) engine.failed(endpoint); else routes.failure(endpoint.key);
                        failed(endpoint.channel, error, added);
                    });
                } finally {
                    if (connection != null) {
                        PeerConnection closed = connection;
                        connections.remove(closed);
                        closed.close();
                        finishTransfer(generation, closed, transfer, ledgerSync ? captured : -1);
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            if (ledgerSync) engine.failed(endpoint); else routes.failure(endpoint.key);
            failed(endpoint.channel, error, 0);
        }
    }
    private String address(PeerRoutePolicy.Endpoint endpoint) {
        return endpoint.channel == PeerRoutePolicy.Channel.BLUETOOTH ? endpoint.key.substring("bluetooth:".length()) : "";
    }
    void accept(PeerConnection connection, PeerRoutePolicy.Channel channel, String address) {
        final int generation = epoch; final long captured;
        final boolean ledgerSync = engine != null && enrolled;
        synchronized (this) { captured = ledgerSync ? engine.revision() : revision; }
        ThreadPoolExecutor pool = team == null ? workers : incomingWorkers;
        ScheduledExecutorService watchdog = pool == null ? null : track(connection, channel, generation);
        if (watchdog == null) { connection.close(); return; }
        try {
            connection.watch(watchdog);
            pool.execute(() -> {
                PeerProtocol legacy = null;
                boolean[] counted = {false};
                Transfer transfer = new Transfer();
                try (PeerConnection peer = connection) {
                    if (!running || epoch != generation) return;
                    PeerProtocol.Listener callbacks = callbacks(peer, "", channel, address, generation, transfer, () -> {
                        if (ledgerSync && !counted[0]) {
                            counted[0] = true;
                            onMain(generation, () -> engine.incoming(true));
                        }
                    });
                    if (team == null) legacy = new PeerProtocol(store, deviceId, callbacks);
                    PeerProtocol.Result result = legacy == null ? teamProtocol(callbacks, generation, peer.listenerPort, () -> { }).accept(peer.wire())
                            : legacy.accept(peer.wire());
                    if (result == null || !running || epoch != generation) return;
                    if (!result.complete) throw new IllegalStateException("保存确认待重试");
                    if (result.authenticated) {
                        PeerRoutePolicy.Endpoint reverse = callbackEndpoint(peer, result);
                        transfer.verifiedRoute = reverse == null ? "" : reverse.key;
                        onMain(generation, () -> {
                            if (reverse != null) engine.offer(reverse);
                            completed(null, channel, result, captured, ledgerSync);
                        });
                    }
                } catch (Exception error) {
                    int added = legacy == null ? 0 : legacy.progress().added;
                    onMain(generation, () -> failed(channel, error, added));
                } finally {
                    connections.remove(connection);
                    finishTransfer(generation, connection, transfer, ledgerSync ? captured : -1);
                    if (counted[0]) onMain(generation, () -> engine.incoming(false));
                }
            });
        } catch (RejectedExecutionException error) {
            connections.remove(connection); connection.close();
            onMain(generation, () -> failed(channel, error, 0));
        }
    }

    private void finishTransfer(int generation, PeerConnection connection, Transfer transfer, long captured) {
        onMain(generation, () -> {
            if (engine != null) engine.sessionFinished(connection, transfer.verifiedRoute, transfer.stored, captured);
            else if (transfer.stored) request(false);
        });
    }
    private synchronized ScheduledExecutorService track(PeerConnection connection, PeerRoutePolicy.Channel channel, int generation) {
        if (!running || epoch != generation || timer == null) return null;
        connections.put(connection, channel);
        return timer;
    }
    private PeerRoutePolicy.Endpoint callbackEndpoint(PeerConnection connection, PeerProtocol.Result result) {
        return team == null ? null : connection.callbackEndpoint(TeamProtocol.routeId(team.team, result.remoteId), result.callbackPort);
    }
    private TeamProtocol teamProtocol(PeerProtocol.Listener callbacks, int generation, int listenerPort, Runnable admitted) throws Exception {
        return new TeamProtocol(store, team, Team.Identity.load(context), callbacks, () -> {
            admitted.run();
            onMain(generation, () -> {
                if (!enrolled) syncNow();
                else engine.rosterChanged();
                notifyChange(0);
            });
        }, listenerPort);
    }
    private PeerProtocol.Listener callbacks(PeerConnection connection, String key, PeerRoutePolicy.Channel channel,
                                            String address, int generation, Transfer transfer, Runnable authenticated) {
        return new PeerProtocol.Listener() {
            @Override public void authenticated(String peerId) {
                if (!running || epoch != generation) return;
                if (team == null && !key.isEmpty()) routes.remember(key, peerId, true);
                if (channel == PeerRoutePolicy.Channel.BLUETOOTH) radios.rememberBluetooth(address, peerId);
                if (engine != null)
                    onMain(generation, () -> engine.authenticated(connection, peerId, channel, key, !background));
                authenticated.run();
            }
            @Override public void stored(int added) {
                if (added > 0) transfer.stored = true;
                onMain(generation, () -> {
                    lastProgress = SystemClock.elapsedRealtime();
                    notifyChange(added);
                });
            }
        };
    }
    private void completed(PeerRoutePolicy.Endpoint endpoint, PeerRoutePolicy.Channel channel,
                           PeerProtocol.Result result, long captured, boolean ledgerSync) {
        if (engine != null) {
            if (result.durable && team.team.issuer.equals(result.remoteId)) initialHistory = true;
            if (enrolled) engine.complete(endpoint, channel, result, ledgerSync ? captured : -1);
            notifyChange(0);
            return;
        }
        synchronized (this) { if (captured == revision) pending = false; }
        summary = (channel == PeerRoutePolicy.Channel.WIFI ? "热点" :
                channel == PeerRoutePolicy.Channel.BLUETOOTH ? "蓝牙" : "热点") +
                (result.durable ? "已同步 · 同伴已确认保存" : "已交换 · 旧版设备暂不支持保存确认") +
                (result.added > 0 ? " · 收到 " + result.added + " 笔" : "");
        notifyChange(0);
    }
    private void failed(PeerRoutePolicy.Channel channel, Exception error, int received) {
        if (team != null) {
            SyncLog.write(context, SyncLog.Event.valueOf(channel.name()), error);
            if (!enrolled && error instanceof TeamProtocol.Admission) {
                summary = error.getMessage();
                admissionError = summary;
            }
        } else summary = "本机记录保留 · " + (received > 0 ? "已收到 " + received + " 笔，确认待重试" :
                channel == PeerRoutePolicy.Channel.WIFI && radios.legacyBluetoothEnabled() ? "热点待连接，自动尝试蓝牙" : "等待同伴，连接恢复后自动补传");
        if (engine == null || !enrolled) request(true);
        notifyChange(0);
    }
    private void onMain(int generation, Runnable action) {
        if (Looper.myLooper() == main.getLooper()) {
            if (running && generation == epoch) action.run();
        } else main.post(() -> { if (running && generation == epoch) action.run(); });
    }
    private void notifyChange(int added) {
        onMain(epoch, () -> {
            pendingNotification += added;
            if (!notificationScheduled) {
                notificationScheduled = true;
                main.postDelayed(publishChange, 50);
            }
        });
    }
    String status() { return engine != null && enrolled ? syncState().label() : summary; }
    SyncEngine.Status syncState() { return engine == null ? SyncEngine.Status.EMPTY : engine.status(); }
    void refreshBluetooth() { radios.refreshTeamBluetooth(); }
    boolean initialHistoryReceived() { return initialHistory; }
    boolean hasContact() { return engine != null && engine.hasContact(); }
    boolean transferring() { return !connections.isEmpty(); }
    long lastProgress() {
        long latest = lastProgress;
        for (PeerConnection connection : connections.keySet()) latest = Math.max(latest, connection.lastProgress());
        return latest;
    }
    String admissionError() { return enrolled ? "" : admissionError; }
    boolean enrollingNearby() { return !enrollmentPeer.isEmpty() && routes.busy(enrollmentPeer); }
    private void closeChannel(PeerRoutePolicy.Channel channel) {
        for (Map.Entry<PeerConnection, PeerRoutePolicy.Channel> item : connections.entrySet())
            if (item.getValue() == channel) item.getKey().close();
    }
    void stop() {
        synchronized (this) { running = false; epoch++; }
        main.removeCallbacks(tick); main.removeCallbacks(runSoon);
        main.removeCallbacks(publishChange);
        pendingNotification = 0;
        notificationScheduled = false;
        if (engine != null) engine.stop();
        radios.stop();
        for (PeerConnection connection : connections.keySet()) connection.close(); connections.clear();
        if (workers != null) workers.shutdownNow(); workers = null;
        if (incomingWorkers != null) incomingWorkers.shutdownNow(); incomingWorkers = null;
        if (timer != null) timer.shutdownNow(); timer = null;
        routes.clear();
    }
}
