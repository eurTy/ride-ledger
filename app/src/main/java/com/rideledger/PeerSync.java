package com.rideledger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Foreground two-phone coordinator. Both phones run exactly the same code and keep full local data. */
final class PeerSync {
    interface Listener { void changed(int added); }
    private final LedgerStore store;
    private final String deviceId;
    private final Listener listener;
    private final boolean startRadios;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRoutePolicy routes = new PeerRoutePolicy(SystemClock::elapsedRealtime);
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final Map<PeerConnection, PeerRoutePolicy.Channel> connections = new ConcurrentHashMap<>();
    final LanSync lan;
    final BluetoothSync bluetooth;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService timer;
    private volatile boolean running;
    private volatile int epoch;
    private long revision, lastBluetoothRefresh;
    private boolean pending = true;
    private volatile String summary = "本机账目已保存 · 等待同伴连接";

    PeerSync(Context context, LedgerStore store, Listener listener) {
        this(context, store, listener, true);
    }
    PeerSync(Context context, LedgerStore store, Listener listener, boolean startRadios) {
        this.store = store; this.listener = listener; this.startRadios = startRadios; deviceId = PeerIdentity.deviceId(context);
        lan = new LanSync(context, deviceId, new LanSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void lost(String key) { routes.remove(key); known.remove(key); }
            @Override public void incoming(PeerConnection connection, String address) { accept(connection, PeerRoutePolicy.Channel.WIFI, address); }
            @Override public void changed(String status) { notifyChange(0); }
            @Override public void networkChanged() {
                routes.removeChannel(PeerRoutePolicy.Channel.WIFI); known.removeIf(value -> value.startsWith("wifi:"));
                closeChannel(PeerRoutePolicy.Channel.WIFI); request(false);
            }
        });
        bluetooth = new BluetoothSync(context, new BluetoothSync.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { offer(endpoint); }
            @Override public void incoming(PeerConnection connection, String address) { accept(connection, PeerRoutePolicy.Channel.BLUETOOTH, address); }
            @Override public void changed() { notifyChange(0); }
            @Override public void reset() {
                routes.removeChannel(PeerRoutePolicy.Channel.BLUETOOTH); known.removeIf(value -> value.startsWith("bluetooth:"));
                closeChannel(PeerRoutePolicy.Channel.BLUETOOTH); request(false);
            }
        });
    }
    void start() {
        if (running) return; running = true; epoch++; pending = true;
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
        timer = Executors.newSingleThreadScheduledExecutor();
        if (startRadios) { lan.start(); bluetooth.start(); }
        main.post(tick);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (startRadios && now - lastBluetoothRefresh >= 10000) { lastBluetoothRefresh = now; bluetooth.refresh(); }
            boolean urgent;
            synchronized (PeerSync.this) { urgent = pending; }
            List<PeerRoutePolicy.Endpoint> ready = routes.ready(urgent);
            for (PeerRoutePolicy.Endpoint endpoint : ready) outgoing(endpoint);
            main.removeCallbacks(this); main.postDelayed(this, 2000);
        }
    };
    void offer(PeerRoutePolicy.Endpoint endpoint) {
        if (!running || known.size() >= 32 && !known.contains(endpoint.key)) return;
        routes.offer(endpoint);
        if (known.add(endpoint.key)) request(false);
    }
    /** Called only after SQLite accepted a local mutation; no separate fragile outbox is needed. */
    void syncNow() { request(false); }
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
                PeerConnection connection = null; PeerProtocol protocol = null;
                try {
                    connection = endpoint.dial.call();
                    if (!running || epoch != generation) return;
                    connections.put(connection, endpoint.channel); connection.watch(timer);
                    protocol = protocol(endpoint.key, endpoint.channel, address(endpoint), generation);
                    PeerProtocol.Result result = protocol.exchange(connection.wire(), endpoint.modern);
                    if (!running || epoch != generation) return;
                    if (!result.complete || !result.authenticated) throw new IllegalStateException("保存确认待重试");
                    routes.remember(endpoint.key, result.remoteId, result.modern); routes.success(endpoint.key);
                    completed(endpoint.channel, result, captured);
                } catch (Exception error) {
                    if (running && epoch == generation) {
                        routes.failure(endpoint.key); failed(endpoint.channel, protocol == null ? 0 : protocol.progress().added);
                    }
                } finally { if (connection != null) { connections.remove(connection); connection.close(); } }
            });
        } catch (RejectedExecutionException error) { routes.failure(endpoint.key); }
    }
    private String address(PeerRoutePolicy.Endpoint endpoint) {
        return endpoint.channel == PeerRoutePolicy.Channel.BLUETOOTH ? endpoint.key.substring("bluetooth:".length()) : "";
    }
    private void accept(PeerConnection connection, PeerRoutePolicy.Channel channel, String address) {
        final int generation = epoch; final long captured;
        synchronized (this) { captured = revision; }
        ThreadPoolExecutor pool = workers;
        if (!running || pool == null) { connection.close(); return; }
        connections.put(connection, channel);
        try {
            connection.watch(timer);
            pool.execute(() -> {
                PeerProtocol protocol = null;
                try (PeerConnection peer = connection) {
                    if (!running || epoch != generation) return;
                    protocol = protocol("", channel, address, generation);
                    PeerProtocol.Result result = protocol.accept(peer.wire());
                    if (!running || epoch != generation) return;
                    if (!result.complete) throw new IllegalStateException("保存确认待重试");
                    if (result.authenticated) completed(channel, result, captured);
                } catch (Exception error) {
                    if (running && epoch == generation) failed(channel, protocol == null ? 0 : protocol.progress().added);
                } finally { connections.remove(connection); }
            });
        } catch (RejectedExecutionException error) { connections.remove(connection); connection.close(); }
    }
    private PeerProtocol protocol(String key, PeerRoutePolicy.Channel channel, String address, int generation) throws Exception {
        return new PeerProtocol(store, deviceId, new PeerProtocol.Listener() {
            @Override public void authenticated(String peerId) {
                if (!running || epoch != generation) return;
                if (!key.isEmpty()) routes.remember(key, peerId, true);
                if (channel == PeerRoutePolicy.Channel.BLUETOOTH) bluetooth.rememberAuthenticated(address, peerId);
            }
            @Override public void stored(int added) {
                if (!running || epoch != generation) return;
                request(false); notifyChange(added);
            }
        });
    }
    private void completed(PeerRoutePolicy.Channel channel, PeerProtocol.Result result, long captured) {
        synchronized (this) { if (captured == revision) pending = false; }
        summary = (channel == PeerRoutePolicy.Channel.WIFI ? "热点" : "蓝牙") +
                (result.durable ? "已同步 · 同伴已确认保存" : "已交换 · 旧版设备暂不支持保存确认") +
                (result.added > 0 ? " · 收到 " + result.added + " 笔" : "");
        notifyChange(0);
    }
    private void failed(PeerRoutePolicy.Channel channel, int received) {
        summary = "本机记录保留 · " + (received > 0 ? "已收到 " + received + " 笔，确认待重试" :
                channel == PeerRoutePolicy.Channel.WIFI && bluetooth.enabled() ? "热点待连接，自动尝试蓝牙" : "等待同伴，连接恢复后自动补传");
        request(true); notifyChange(0);
    }
    private void notifyChange(int added) {
        final int generation = epoch;
        main.post(() -> { if (running && generation == epoch) listener.changed(added); });
    }
    String status() { return summary; }
    String details() { return summary + "\n" + lan.status() + "\n" + bluetooth.status(); }
    void connectManual(String address) { lan.connectManual(address); retryNow(); }
    private void closeChannel(PeerRoutePolicy.Channel channel) {
        for (Map.Entry<PeerConnection, PeerRoutePolicy.Channel> item : connections.entrySet())
            if (item.getValue() == channel) item.getKey().close();
    }
    void stop() {
        running = false; epoch++;
        main.removeCallbacks(tick); main.removeCallbacks(runSoon);
        lan.stop(); bluetooth.stop();
        for (PeerConnection connection : connections.keySet()) connection.close(); connections.clear();
        if (workers != null) workers.shutdownNow(); workers = null;
        if (timer != null) timer.shutdownNow(); timer = null;
        routes.clear(); known.clear();
    }
}
