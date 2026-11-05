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

/** Foreground coordinator. Every phone runs the same exchange and holds a full local ledger. */
final class PeerSync {
    interface Listener { void changed(int added); }
    private final LedgerStore store;
    private final Context context;
    private final LedgerStore.Trip team;
    private final String enrollmentPeer;
    private final String deviceId;
    private final Listener listener;
    private final boolean startRadios;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRoutePolicy routes = new PeerRoutePolicy(SystemClock::elapsedRealtime);
    private final Map<PeerConnection, PeerRoutePolicy.Channel> connections = new ConcurrentHashMap<>();
    final LanSync lan;
    final BluetoothSync bluetooth;
    private final TeamBle teamBle;
    private ThreadPoolExecutor workers;
    private ThreadPoolExecutor incomingWorkers;
    private ScheduledExecutorService timer;
    private volatile boolean running;
    private volatile boolean enrolled;
    private volatile int epoch;
    private long revision, lastBluetoothRefresh;
    private boolean pending = true;
    private volatile String summary = "本机账目已保存 · 等待同伴连接";

    PeerSync(Context context, LedgerStore store, Listener listener) {
        this(context, store, listener, true);
    }
    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios) {
        this(context, store, listener, startRadios, null);
    }
    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios, LedgerStore.Trip team) {
        this.context = context.getApplicationContext();
        this.store = store; this.listener = listener; this.startRadios = startRadios;
        this.team = team;
        deviceId = PeerIdentity.deviceId(context);
        String discoveryId = team == null ? deviceId : TeamProtocol.routeId(team.team, deviceId);
        String scope = team == null ? "" : team.team.discoveryScope();
        enrollmentPeer = team == null ? "" : TeamProtocol.routeId(team.team, team.team.issuer);
        lan = new LanSync(context, discoveryId, scope, new LanSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void lost(String key) { routes.remove(key); }
            @Override public void incoming(PeerConnection connection, String address) {
                PeerRoutePolicy.Channel channel = PeerRoutePolicy.Channel.WIFI;
                if (team != null) try { channel = lanChannel(address); } catch (Exception ignored) { }
                accept(connection, channel, address);
            }
            @Override public void changed(String status) { notifyChange(0); }
            @Override public void networkChanged() {
                routes.removeChannel(PeerRoutePolicy.Channel.WIFI);
                routes.removeChannel(PeerRoutePolicy.Channel.HOTSPOT);
                closeChannel(PeerRoutePolicy.Channel.WIFI); closeChannel(PeerRoutePolicy.Channel.HOTSPOT); request(false);
            }
        });
        bluetooth = team == null ? new BluetoothSync(context, new BluetoothSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void incoming(PeerConnection connection, String address) { accept(connection, PeerRoutePolicy.Channel.BLUETOOTH, address); }
            @Override public void changed() { notifyChange(0); }
            @Override public void reset() {
                routes.removeChannel(PeerRoutePolicy.Channel.BLUETOOTH);
                closeChannel(PeerRoutePolicy.Channel.BLUETOOTH); request(false);
            }
        }) : null;
        teamBle = team == null ? null : new TeamBle(context, team, deviceId, new TeamBle.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void lost(String key) { routes.remove(key); }
            @Override public void incoming(PeerConnection connection) { accept(connection, PeerRoutePolicy.Channel.BLUETOOTH, ""); }
            @Override public void reset() {
                routes.removeChannel(PeerRoutePolicy.Channel.BLUETOOTH);
                closeChannel(PeerRoutePolicy.Channel.BLUETOOTH);
            }
        });
    }
    private PeerRoutePolicy.Channel lanChannel(String address) throws Exception { return lan.channel(java.net.InetAddress.getByName(address)); }

    void start() {
        if (running) return; running = true; epoch++; pending = true;
        enrolled = team == null || store.teamReady(team);
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        // Reserve inbound capacity so four simultaneous dials cannot starve the matching receivers.
        if (team != null) incomingWorkers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
        timer = Executors.newSingleThreadScheduledExecutor();
        if (startRadios) {
            lan.start();
            if (teamBle != null) teamBle.start(timer);
            else bluetooth.start();
        }
        main.post(tick);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (startRadios && now - lastBluetoothRefresh >= (team == null ? 10000 : 5000)) {
                lastBluetoothRefresh = now;
                if (teamBle != null) teamBle.refresh(); else bluetooth.refresh();
            }
            boolean urgent;
            long current;
            synchronized (PeerSync.this) { urgent = pending; current = revision; }
            List<PeerRoutePolicy.Endpoint> ready = team == null ? routes.ready(urgent) :
                    routes.readyForRevision(current, enrolled ? "" : enrollmentPeer);
            for (PeerRoutePolicy.Endpoint endpoint : ready) outgoing(endpoint);
            main.removeCallbacks(this); main.postDelayed(this, 2000);
        }
    };
    void offer(PeerRoutePolicy.Endpoint endpoint) {
        if (running && routes.offer(endpoint)) request(team != null);
    }
    /** Called only after SQLite accepted a local mutation; no separate fragile outbox is needed. */
    void syncNow() {
        if (team != null && !enrolled) enrolled = store.teamReady(team);
        request(false);
    }
    void retryNow() { routes.resetBackoff(); request(false); }
    private void request(boolean unchangedRevision) {
        synchronized (this) { if (!unchangedRevision) revision++; pending = true; }
        if (running) { main.removeCallbacks(runSoon); main.postDelayed(runSoon, 150); }
    }
    private final Runnable runSoon = () -> { if (running) { main.removeCallbacks(tick); main.post(tick); } };
    private void outgoing(PeerRoutePolicy.Endpoint endpoint) {
        final int generation = epoch; final long captured;
        synchronized (this) { captured = revision; }
        ThreadPoolExecutor pool = workers;
        if (!running || pool == null) { routes.failure(endpoint.key); return; }
        try {
            pool.execute(() -> {
                PeerConnection connection = null;
                PeerProtocol legacy = null;
                try {
                    if (!running || epoch != generation) return;
                    connection = endpoint.dial.call();
                    if (!running || epoch != generation) return;
                    connections.put(connection, endpoint.channel); connection.watch(timer);
                    PeerProtocol.Listener callbacks = callbacks(endpoint.key, endpoint.channel, address(endpoint), generation);
                    if (team == null) legacy = new PeerProtocol(store, deviceId, callbacks);
                    PeerProtocol.Result result = legacy == null
                            ? teamProtocol(callbacks, generation).exchange(connection.wire(), endpoint.peerId)
                            : legacy.exchange(connection.wire(), endpoint.modern);
                    if (!running || epoch != generation) return;
                    if (!result.complete || !result.authenticated) throw new IllegalStateException("保存确认待重试");
                    if (team == null) routes.remember(endpoint.key, result.remoteId, result.modern);
                    routes.success(endpoint.key, team == null ? -1 : captured);
                    completed(endpoint.channel, result, captured);
                } catch (Exception error) {
                    if (running && epoch == generation) {
                        routes.failure(endpoint.key); failed(endpoint.channel, error, legacy == null ? 0 : legacy.progress().added);
                    }
                } finally { if (connection != null) { connections.remove(connection); connection.close(); } }
            });
        } catch (RejectedExecutionException error) { routes.failure(endpoint.key); }
    }
    private String address(PeerRoutePolicy.Endpoint endpoint) {
        return endpoint.channel == PeerRoutePolicy.Channel.BLUETOOTH ? endpoint.key.substring("bluetooth:".length()) : "";
    }
    void accept(PeerConnection connection, PeerRoutePolicy.Channel channel, String address) {
        final int generation = epoch; final long captured;
        synchronized (this) { captured = revision; }
        ThreadPoolExecutor pool = team == null ? workers : incomingWorkers;
        ScheduledExecutorService watchdog = timer;
        if (!running || pool == null || watchdog == null) { connection.close(); return; }
        connections.put(connection, channel);
        try {
            connection.watch(watchdog);
            pool.execute(() -> {
                PeerProtocol legacy = null;
                try (PeerConnection peer = connection) {
                    if (!running || epoch != generation) return;
                    PeerProtocol.Listener callbacks = callbacks("", channel, address, generation);
                    if (team == null) legacy = new PeerProtocol(store, deviceId, callbacks);
                    PeerProtocol.Result result = legacy == null ? teamProtocol(callbacks, generation).accept(peer.wire())
                            : legacy.accept(peer.wire());
                    if (!running || epoch != generation) return;
                    if (!result.complete) throw new IllegalStateException("保存确认待重试");
                    if (result.authenticated) {
                        if (team != null) routes.received(TeamProtocol.routeId(team.team, result.remoteId), channel, captured);
                        completed(channel, result, captured);
                    }
                } catch (Exception error) {
                    if (running && epoch == generation && team == null) failed(channel, error, legacy == null ? 0 : legacy.progress().added);
                } finally { connections.remove(connection); }
            });
        } catch (RejectedExecutionException error) { connections.remove(connection); connection.close(); }
    }
    private TeamProtocol teamProtocol(PeerProtocol.Listener callbacks, int generation) throws Exception {
        return new TeamProtocol(store, team, Team.Identity.load(context), callbacks, () -> {
            if (running && epoch == generation) { enrolled = true; syncNow(); notifyChange(0); }
        });
    }
    private PeerProtocol.Listener callbacks(String key, PeerRoutePolicy.Channel channel, String address, int generation) {
        return new PeerProtocol.Listener() {
            @Override public void authenticated(String peerId) {
                if (!running || epoch != generation) return;
                if (team == null && !key.isEmpty()) routes.remember(key, peerId, true);
                if (bluetooth != null && channel == PeerRoutePolicy.Channel.BLUETOOTH) bluetooth.rememberAuthenticated(address, peerId);
            }
            @Override public void stored(int added) {
                if (!running || epoch != generation) return;
                request(false); notifyChange(added);
            }
        };
    }
    private void completed(PeerRoutePolicy.Channel channel, PeerProtocol.Result result, long captured) {
        synchronized (this) { if (captured == revision) pending = false; }
        summary = (channel == PeerRoutePolicy.Channel.WIFI ? (team == null ? "热点" : "Wi-Fi") :
                channel == PeerRoutePolicy.Channel.BLUETOOTH ? "蓝牙" : "热点") +
                (result.durable ? "已同步 · 同伴已确认保存" : "已交换 · 旧版设备暂不支持保存确认") +
                (result.added > 0 ? " · 收到 " + result.added + " 笔" : "");
        notifyChange(0);
    }
    private void failed(PeerRoutePolicy.Channel channel, Exception error, int received) {
        if (team != null) {
            if (!enrolled && error instanceof TeamProtocol.Admission)
                summary = error.getMessage();
        } else summary = "本机记录保留 · " + (received > 0 ? "已收到 " + received + " 笔，确认待重试" :
                channel == PeerRoutePolicy.Channel.WIFI && bluetooth.enabled() ? "热点待连接，自动尝试蓝牙" : "等待同伴，连接恢复后自动补传");
        request(true); notifyChange(0);
    }
    private void notifyChange(int added) {
        final int generation = epoch;
        main.post(() -> { if (running && generation == epoch) listener.changed(added); });
    }
    String status() { return summary; }
    String details() { return team != null ? summary : summary + "\n" + lan.status() + "\n" + bluetooth.status(); }
    void connectManual(String address) { lan.connectManual(address); retryNow(); }
    private void closeChannel(PeerRoutePolicy.Channel channel) {
        for (Map.Entry<PeerConnection, PeerRoutePolicy.Channel> item : connections.entrySet())
            if (item.getValue() == channel) item.getKey().close();
    }
    void stop() {
        running = false; epoch++;
        main.removeCallbacks(tick); main.removeCallbacks(runSoon);
        lan.stop();
        if (teamBle != null) teamBle.stop(); else bluetooth.stop();
        for (PeerConnection connection : connections.keySet()) connection.close(); connections.clear();
        if (workers != null) workers.shutdownNow(); workers = null;
        if (incomingWorkers != null) incomingWorkers.shutdownNow(); incomingWorkers = null;
        if (timer != null) timer.shutdownNow(); timer = null;
        routes.clear();
    }
}
