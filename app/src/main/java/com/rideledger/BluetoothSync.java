package com.rideledger;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.location.LocationManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Optional secure RFCOMM channel. Pairing and permission consent are handled by Android. */
final class BluetoothSync {
    interface Events {
        void found(PeerRoutePolicy.Endpoint endpoint);
        void incoming(PeerConnection connection, String address);
        void changed();
        void reset();
    }
    static final UUID SERVICE_UUID = UUID.fromString("5ccf780d-e4ee-4ebc-9b9d-5230d423c171");
    private final Context context;
    private final Events events;
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;
    private final Set<BluetoothSocket> sockets = ConcurrentHashMap.newKeySet();
    private final Map<String, BluetoothDevice> discovered = new ConcurrentHashMap<>();
    private volatile boolean active, accepting, discovering;
    private volatile BluetoothServerSocket server;
    private volatile String status = "蓝牙备用通道未开启";
    private long listenRetryAt;
    private int generation;
    private boolean receiverRegistered;
    private ScheduledExecutorService timer;
    private Runnable devicesListener;
    private final Handler main = new Handler(Looper.getMainLooper());

    BluetoothSync(Context context, Events events) {
        this.context = context.getApplicationContext(); this.events = events;
        prefs = context.getSharedPreferences("nearby", Context.MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    static String[] usePermissions(int sdk) {
        return sdk >= 31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT} : new String[0];
    }
    static String[] scanPermissions(int sdk) {
        return sdk >= 31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    static String[] discoverablePermissions(int sdk) {
        return sdk >= 31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE} : new String[0];
    }
    boolean has(String[] permissions) {
        for (String permission : permissions) if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    boolean supported() { return adapter != null; }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    boolean radioEnabled() {
        try { return adapter != null && has(usePermissions(Build.VERSION.SDK_INT)) && adapter.isEnabled(); }
        catch (SecurityException error) { return false; }
    }
    String status() { return status; }
    void setEnabled(boolean value) {
        prefs.edit().putBoolean("enabled", value).apply();
        if (!value) { stopListening(); events.reset(); }
        refresh();
    }
    void setDevicesListener(Runnable listener) { devicesListener = listener; }
    String selectedName() {
        String address = prefs.getString("address", "");
        if (address.isEmpty()) return "尚未选择同伴手机";
        try { return deviceLabel(adapter.getRemoteDevice(address)); }
        catch (Exception ignored) { return "已选择同伴手机"; }
    }
    String deviceLabel(BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name == null || name.trim().isEmpty() ? "未命名设备" : name) +
                    (device.getBondState() == BluetoothDevice.BOND_BONDED ? " · 已配对" : " · 待配对");
        } catch (SecurityException error) { return "蓝牙设备 · 待授权"; }
    }
    List<BluetoothDevice> devices() {
        Map<String, BluetoothDevice> values = new java.util.HashMap<>(discovered);
        if (adapter != null && has(usePermissions(Build.VERSION.SDK_INT))) {
            try { for (BluetoothDevice device : adapter.getBondedDevices()) values.put(device.getAddress(), device); }
            catch (SecurityException ignored) { }
        }
        List<BluetoothDevice> result = new ArrayList<>(values.values());
        result.sort(Comparator.comparing(this::deviceLabel)); return result;
    }
    void select(BluetoothDevice device) {
        if (!has(usePermissions(Build.VERSION.SDK_INT))) throw new IllegalStateException("请先允许连接附近设备");
        cancelDiscovery();
        String previous = prefs.getString("address", "");
        SharedPreferences.Editor edit = prefs.edit().putBoolean("enabled", true).putString("address", device.getAddress());
        if (!previous.equals(device.getAddress())) edit.remove("peer_id");
        edit.apply(); events.reset();
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            status = "请在两台手机上确认配对，完成后自动同步";
            if (!device.createBond()) throw new IllegalStateException("无法开始配对，请在系统蓝牙设置中完成配对");
        }
        refresh();
    }
    void forget() {
        prefs.edit().remove("address").remove("peer_id").apply(); events.reset();
        for (BluetoothSocket socket : sockets) close(socket);
        refresh();
    }
    void rememberAuthenticated(String address, String peerId) {
        if (!BluetoothAdapter.checkBluetoothAddress(address) || !PeerIdentity.valid(peerId) || !enabled()) return;
        String selected = prefs.getString("address", "");
        if (!selected.isEmpty() && !selected.equals(address)) return;
        prefs.edit().putString("address", address).putString("peer_id", peerId).apply();
        offerSelected();
    }
    void discover() {
        if (!radioEnabled()) throw new IllegalStateException("请先开启手机蓝牙");
        if (!has(scanPermissions(Build.VERSION.SDK_INT))) throw new IllegalStateException("请先允许查找附近设备");
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 30) {
            LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (location != null && !location.isLocationEnabled())
                throw new IllegalStateException("Android 10/11 查找新手机需要开启系统位置信息；已配对手机仍可直接连接");
        }
        cancelDiscovery(); discovered.clear();
        discovering = adapter.startDiscovery();
        if (!discovering) throw new IllegalStateException("暂时无法扫描，请稍后重试或选择已配对手机");
        status = "正在查找同伴，请在另一台手机允许被发现"; notifyChanged();
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    try { if (discovered.size() < 32) discovered.put(device.getAddress(), device); } catch (SecurityException ignoredError) { }
                }
                notifyChanged();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                discovering = false; refresh();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (!radioEnabled()) { stopListening(); events.reset(); }
                refresh();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                refresh();
            }
        }
    };
    synchronized void start() {
        if (active) return; active = true;
        listenRetryAt = 0;
        timer = Executors.newSingleThreadScheduledExecutor();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) { }
        refresh();
    }
    void refresh() {
        if (!enabled()) { status = "蓝牙备用通道未开启"; notifyChanged(); return; }
        if (adapter == null) { status = "此设备没有蓝牙，可使用热点同步"; notifyChanged(); return; }
        if (!has(usePermissions(Build.VERSION.SDK_INT))) { status = "蓝牙待授权，热点同步仍可用"; notifyChanged(); return; }
        if (!radioEnabled()) { status = "蓝牙已关闭，开启后会自动重连"; notifyChanged(); return; }
        if (!active) return;
        listen(); offerSelected();
        if (!discovering) {
            String address = prefs.getString("address", "");
            try {
                status = address.isEmpty() ? "蓝牙已就绪，请选择同伴或允许同伴发现" :
                        adapter.getRemoteDevice(address).getBondState() == BluetoothDevice.BOND_BONDED ? "蓝牙备用已就绪，断线后自动补传" :
                        "等待系统完成配对，之后自动同步";
            } catch (Exception error) { status = "请重新选择同伴手机"; }
        }
        notifyChanged();
    }
    private synchronized void listen() {
        if (accepting || server != null || SystemClock.elapsedRealtime() < listenRetryAt) return;
        accepting = true; final int epoch = ++generation;
        Thread thread = new Thread(() -> {
            BluetoothServerSocket listening = null;
            try {
                listening = adapter.listenUsingRfcommWithServiceRecord("RideLedger", SERVICE_UUID);
                synchronized (BluetoothSync.this) {
                    if (!active || !enabled() || epoch != generation) { listening.close(); return; }
                    server = listening;
                }
                while (active && enabled() && epoch == generation) {
                    BluetoothSocket socket = listening.accept();
                    if (!active || sockets.size() >= 4) { close(socket); continue; }
                    sockets.add(socket);
                    String address = socket.getRemoteDevice().getAddress();
                    events.incoming(connection(socket), address);
                }
            } catch (Exception error) {
                if (active && enabled() && epoch == generation) { status = "蓝牙监听待重试，账目已保存在本机"; notifyChanged(); }
            } finally {
                if (listening != null) try { listening.close(); } catch (Exception ignored) { }
                synchronized (BluetoothSync.this) {
                    if (epoch == generation) { server = null; accepting = false; listenRetryAt = SystemClock.elapsedRealtime() + 10000; }
                }
            }
        }, "RideLedger-Bluetooth-listen");
        thread.setDaemon(true); thread.start();
    }
    private void offerSelected() {
        if (!active || !enabled() || !radioEnabled()) return;
        String address = prefs.getString("address", "");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            events.found(new PeerRoutePolicy.Endpoint("bluetooth:" + address, PeerRoutePolicy.Channel.BLUETOOTH,
                    prefs.getString("peer_id", ""), true, () -> dial(device)));
        } catch (Exception ignored) { }
    }
    private PeerConnection dial(BluetoothDevice device) throws Exception {
        if (!active || !enabled() || !radioEnabled()) throw new IllegalStateException("蓝牙暂不可用");
        cancelDiscovery();
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID); sockets.add(socket);
        ScheduledExecutorService clock = timer;
        if (clock == null || clock.isShutdown()) { close(socket); throw new IllegalStateException("同步已暂停"); }
        ScheduledFuture<?> timeout = clock.schedule(() -> close(socket), 10, TimeUnit.SECONDS);
        try { socket.connect(); return connection(socket); }
        catch (Exception error) { sockets.remove(socket); close(socket); throw error; }
        finally { timeout.cancel(false); }
    }
    private PeerConnection connection(BluetoothSocket socket) throws Exception {
        return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, () -> sockets.remove(socket));
    }
    private void cancelDiscovery() {
        if (adapter == null || !has(scanPermissions(Build.VERSION.SDK_INT))) return;
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
        discovering = false;
    }
    private void notifyChanged() { main.post(() -> { events.changed(); if (devicesListener != null) devicesListener.run(); }); }
    private static void close(BluetoothSocket socket) { try { socket.close(); } catch (Exception ignored) { } }
    private synchronized void stopListening() {
        generation++; accepting = false;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        server = null;
        for (BluetoothSocket socket : sockets) close(socket); sockets.clear();
    }
    synchronized void stop() {
        active = false; cancelDiscovery(); stopListening();
        if (receiverRegistered) try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        receiverRegistered = false;
        if (timer != null) timer.shutdownNow(); timer = null;
        devicesListener = null;
    }
}
