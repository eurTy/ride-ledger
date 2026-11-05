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
import android.content.Context;
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
    private volatile ScheduledExecutorService timer;

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

    void start(ScheduledExecutorService timer) {
        this.timer = timer;
        active = true;
        retryScan = 0;
        retryAdvertise = 0;
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
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() < ADVERTISEMENT_AGE) continue;
            events.lost(entry.getKey());
            iterator.remove();
        }
        if (scan == null && now >= retryScan) startScan();
        if (server == null && now >= retryAdvertise) startAdvertising();
    }

    private boolean radioEnabled() {
        try { return hasPermissions(context) && adapter.isEnabled(); }
        catch (SecurityException ignored) { return false; }
    }

    private void startScan() {
        retryScan = SystemClock.elapsedRealtime() + 15000;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            scan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> { if (active && scan == this) found(result); });
                }
                @Override public void onScanFailed(int errorCode) {
                    main.post(() -> { if (scan == this) stopScan(); });
                }
            };
            ScanFilter filter = new ScanFilter.Builder().setServiceData(scope, new byte[0]).build();
            scanner.startScan(Collections.singletonList(filter),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        } catch (Exception ignored) { stopScan(); }
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
        String key = "bluetooth:" + route + ":" + device.getAddress() + ":" + psm;
        if (seen.size() >= 16 && !seen.containsKey(key)) return;
        if (seen.put(key, SystemClock.elapsedRealtime()) == null)
            events.found(new PeerRoutePolicy.Endpoint(key, PeerRoutePolicy.Channel.BLUETOOTH, route, true, () -> dial(device, psm)));
    }

    private void startAdvertising() {
        retryAdvertise = SystemClock.elapsedRealtime() + 15000;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;
            BluetoothServerSocket listening = adapter.listenUsingInsecureL2capChannel();
            server = listening;
            int psm = listening.getPsm();
            byte[] payload = new byte[8];
            payload[0] = (byte) (psm >>> 8);
            payload[1] = (byte) psm;
            for (int i = 0; i < 6; i++) payload[i + 2] = (byte) Integer.parseInt(hint.substring(i * 2, i * 2 + 2), 16);
            advertisement = new AdvertiseCallback() {
                @Override public void onStartFailure(int errorCode) {
                    main.post(() -> { if (advertisement == this) stopAdvertising(); });
                }
            };
            AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build();
            advertiser.startAdvertising(settings, new AdvertiseData.Builder().addServiceData(scope, payload).build(), advertisement);
            Thread acceptor = new Thread(() -> accept(listening), "RideLedger-team-BLE");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (Exception ignored) { stopAdvertising(); }
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
            } catch (Exception ignored) {
                if (socket != null) sockets.remove(socket);
                close(socket);
                main.post(() -> { if (server == listening) stopAdvertising(); });
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
        return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, () -> sockets.remove(socket));
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
        stopScan();
        stopAdvertising();
        for (BluetoothSocket socket : sockets) close(socket);
        sockets.clear();
        seen.clear();
    }

    void stop() {
        active = false;
        resetRadio();
        timer = null;
    }

    private static void close(Closeable socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }
}
