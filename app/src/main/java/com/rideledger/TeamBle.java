package com.rideledger;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Scoped BLE advertisements carry an L2CAP port and a discovery hint. Authentication is in TeamProtocol. */
final class TeamBle {
    interface Events {
        void found(PeerRoutePolicy.Endpoint endpoint);
        void lost(String key);
        void incoming(PeerConnection connection);
        void reset();
    }

    private static final long ADVERTISEMENT_AGE = 35000;
    private final Context context;
    private final BluetoothAdapter adapter;
    private final ParcelUuid scope;
    private final String hint;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> seen = new HashMap<>();
    private final Set<BluetoothSocket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private volatile BluetoothServerSocket server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback advertisement;
    private ScanCallback scan;
    private long retryScan, retryAdvertise;
    private int scanFailures, advertiseFailures;
    private boolean background, receiverRegistered;
    private volatile ScheduledExecutorService timer;
    private final Runnable expire = this::refresh;
    private final Runnable retryRadio = this::refresh;
    private final BroadcastReceiver radio = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) return;
            scanFailures = advertiseFailures = 0;
            retryScan = retryAdvertise = 0;
            refresh();
        }
    };

    TeamBle(Context context, LedgerStore.Trip trip, String deviceId, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
        hint = TeamProtocol.routeHint(trip.team, deviceId);
        scope = new ParcelUuid(UUID.nameUUIDFromBytes(("ride-ledger-ble-1:" + trip.secret).getBytes(StandardCharsets.UTF_8)));
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    static String[] permissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static boolean hasPermissions(Context context) {
        for (String permission : permissions()) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    void start(ScheduledExecutorService timer, boolean background) {
        this.timer = timer;
        this.background = background;
        active = true;
        scanFailures = advertiseFailures = 0;
        retryScan = 0;
        retryAdvertise = 0;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(radio, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(radio, filter);
            receiverRegistered = true;
        } catch (RuntimeException error) { SyncLog.write(context, "BLE_RECEIVER", error); }
        refresh();
    }

    void refresh() {
        if (!active || Build.VERSION.SDK_INT < 29 || adapter == null) return;
        if (!radioEnabled()) {
            if (scan != null || server != null || !seen.isEmpty()) {
                resetRadio();
                events.reset();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long next = Long.MAX_VALUE;
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            long deadline = entry.getValue() + ADVERTISEMENT_AGE;
            if (now < deadline) { next = Math.min(next, deadline); continue; }
            events.lost(entry.getKey());
            iterator.remove();
        }
        main.removeCallbacks(expire);
        if (next != Long.MAX_VALUE) main.postDelayed(expire, next - now);
        if (scan == null && now >= retryScan && scanAllowed()) startScan();
        if (server == null && now >= retryAdvertise) startAdvertising();
    }

    static boolean canScan(Context context, boolean background) {
        return hasPermissions(context) && (!background || Build.VERSION.SDK_INT >= 31 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    private boolean scanAllowed() { return canScan(context, background); }

    private boolean radioEnabled() {
        try { return hasPermissions(context) && adapter.isEnabled(); }
        catch (SecurityException ignored) { return false; }
    }

    private void startScan() {
        retryScan = SystemClock.elapsedRealtime() + 15000;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { radioFailed(true, "Unavailable"); return; }
            scan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> { if (active && scan == this) found(result); });
                }
                @Override public void onScanFailed(int errorCode) {
                    main.post(() -> { if (scan == this) radioFailed(true, Integer.toString(errorCode)); });
                }
            };
            ScanFilter filter = new ScanFilter.Builder().setServiceData(scope, new byte[0]).build();
            scanner.startScan(Collections.singletonList(filter),
                    new ScanSettings.Builder().setScanMode(background ? ScanSettings.SCAN_MODE_LOW_POWER :
                            ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        } catch (Exception error) { radioFailed(true, error.getClass().getSimpleName()); }
    }

    private void found(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        byte[] data = record == null ? null : record.getServiceData(scope);
        if (data == null || data.length != 8 || !result.isConnectable()) return;
        StringBuilder value = new StringBuilder(12);
        for (int i = 2; i < data.length; i++) {
            value.append(Character.forDigit((data[i] >>> 4) & 15, 16)).append(Character.forDigit(data[i] & 15, 16));
        }
        String other = value.toString();
        int psm = (data[0] & 255) << 8 | (data[1] & 255);
        if (psm == 0 || hint.equals(other)) return;
        BluetoothDevice device = result.getDevice();
        String route = TeamProtocol.fromHint(other);
        PeerRoutePolicy.Endpoint endpoint = endpoint(device, psm, route);
        String key = endpoint.key;
        if (seen.size() >= 16 && !seen.containsKey(key)) return;
        boolean first = seen.isEmpty();
        if (seen.put(key, SystemClock.elapsedRealtime()) == null) {
            events.found(endpoint);
            if (first) main.postDelayed(expire, ADVERTISEMENT_AGE);
        }
    }
    private PeerRoutePolicy.Endpoint endpoint(BluetoothDevice device, int psm, String route) {
        String key = "bluetooth:" + route + ":" + device.getAddress() + ":" + psm;
        return new PeerRoutePolicy.Endpoint(key, PeerRoutePolicy.Channel.BLUETOOTH, route, true, () -> dial(device, psm));
    }

    private void startAdvertising() {
        retryAdvertise = SystemClock.elapsedRealtime() + 15000;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { radioFailed(false, "Unavailable"); return; }
            BluetoothServerSocket listening = adapter.listenUsingInsecureL2capChannel();
            server = listening;
            int psm = listening.getPsm();
            byte[] payload = new byte[8];
            payload[0] = (byte) (psm >>> 8);
            payload[1] = (byte) psm;
            for (int i = 0; i < 6; i++) payload[i + 2] = (byte) Integer.parseInt(hint.substring(i * 2, i * 2 + 2), 16);
            advertisement = new AdvertiseCallback() {
                @Override public void onStartFailure(int errorCode) {
                    main.post(() -> { if (advertisement == this) radioFailed(false, Integer.toString(errorCode)); });
                }
            };
            AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(background ? AdvertiseSettings.ADVERTISE_MODE_LOW_POWER :
                            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build();
            advertiser.startAdvertising(settings, new AdvertiseData.Builder().addServiceData(scope, payload).build(), advertisement);
            Thread acceptor = new Thread(() -> accept(listening), "RideLedger-team-BLE");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (Exception error) { radioFailed(false, error.getClass().getSimpleName()); }
    }

    private void accept(BluetoothServerSocket listening) {
        while (active && server == listening) {
            BluetoothSocket socket = null;
            try {
                socket = listening.accept();
                if (!active || server != listening || sockets.size() >= 8) {
                    close(socket);
                    continue;
                }
                sockets.add(socket);
                events.incoming(connection(socket));
            } catch (Exception error) {
                if (socket != null) sockets.remove(socket);
                close(socket);
                main.post(() -> { if (server == listening) radioFailed(false, error.getClass().getSimpleName()); });
                return;
            }
        }
    }

    private PeerConnection dial(BluetoothDevice device, int psm) throws Exception {
        ScheduledExecutorService worker = timer;
        if (!active || worker == null || !hasPermissions(context)) throw new IOException("蓝牙连接不可用");
        BluetoothSocket socket = device.createInsecureL2capChannel(psm);
        sockets.add(socket);
        try {
            ScheduledFuture<?> deadline = worker.schedule(() -> close(socket), 4500, TimeUnit.MILLISECONDS);
            try { socket.connect(); }
            finally { deadline.cancel(false); }
            if (!active) throw new IOException("蓝牙连接已停止");
            return connection(socket);
        } catch (Exception error) {
            sockets.remove(socket);
            close(socket);
            throw error;
        }
    }

    private PeerConnection connection(BluetoothSocket socket) throws Exception {
        BluetoothServerSocket listening = server;
        int port = listening == null ? 0 : listening.getPsm();
        BluetoothDevice remote = socket.getRemoteDevice();
        return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, () -> sockets.remove(socket),
                port, (peer, callback) -> endpoint(remote, callback, peer));
    }

    private void stopScan() {
        ScanCallback previous = scan;
        scan = null;
        if (previous != null && scanner != null) try { scanner.stopScan(previous); } catch (Exception ignored) { }
        scanner = null;
    }

    private void stopAdvertising() {
        AdvertiseCallback previous = advertisement;
        advertisement = null;
        if (previous != null && advertiser != null) try { advertiser.stopAdvertising(previous); } catch (Exception ignored) { }
        advertiser = null;
        BluetoothServerSocket listening = server;
        server = null;
        close(listening);
    }

    private void resetRadio() {
        main.removeCallbacks(expire);
        main.removeCallbacks(retryRadio);
        stopScan();
        stopAdvertising();
        for (BluetoothSocket socket : sockets) close(socket);
        sockets.clear();
        seen.clear();
    }

    void stop() {
        active = false;
        if (receiverRegistered) context.unregisterReceiver(radio);
        receiverRegistered = false;
        resetRadio();
        timer = null;
    }

    private void radioFailed(boolean scanning, String code) {
        if (scanning) stopScan(); else stopAdvertising();
        int failures = scanning ? ++scanFailures : ++advertiseFailures;
        SyncLog.write(context, scanning ? "BLE_SCAN" : "BLE_ADVERTISE", code);
        long retryAt = failures <= 3 ? SystemClock.elapsedRealtime() + (5000L << (failures - 1)) : Long.MAX_VALUE;
        if (scanning) retryScan = retryAt; else retryAdvertise = retryAt;
        main.removeCallbacks(retryRadio);
        long next = Math.min(scan == null && scanAllowed() ? retryScan : Long.MAX_VALUE,
                server == null ? retryAdvertise : Long.MAX_VALUE);
        if (active && next != Long.MAX_VALUE)
            main.postDelayed(retryRadio, Math.max(1, next - SystemClock.elapsedRealtime()));
    }

    private static void close(Closeable socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }
}
