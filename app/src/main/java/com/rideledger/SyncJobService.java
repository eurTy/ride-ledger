package com.rideledger;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.bluetooth.BluetoothManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/** Brief discovery lease. Data is transferred only after the team handshake. */
public final class SyncJobService extends JobService {
    private static final long DISCOVERY_WINDOW = 12000, MAX_WINDOW = 45000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LedgerStore store;
    private PeerSync peer;
    private JobParameters active;
    private final Runnable checkContact = () -> { if (peer != null && !peer.hasContact()) finish(); };
    private final Runnable endWindow = this::finish;

    @Override public boolean onStartJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 29 || !SyncBackground.acquire(this)) return false;
        try {
            SyncLog.maintain(this);
            SyncTriggerReceiver.install(getApplicationContext());
            if (!hasLocalNetwork() && !hasBluetooth()) { stop(); return false; }
            store = new LedgerStore(getApplicationContext());
            String id = getSharedPreferences("ui", MODE_PRIVATE).getString("last_trip", null);
            LedgerStore.Trip trip = id == null ? null : store.getTrip(id);
            if (!eligible(trip)) {
                for (LedgerStore.Trip candidate : store.trips()) {
                    if (eligible(candidate)) { trip = candidate; break; }
                }
            }
            if (!eligible(trip)) { stop(); return false; }
            active = params;
            peer = new PeerSync(this, store, added -> { }, true, trip, true);
            peer.start();
            main.postDelayed(checkContact, DISCOVERY_WINDOW);
            main.postDelayed(endWindow, MAX_WINDOW);
            return true;
        } catch (RuntimeException error) {
            SyncLog.write(this, "JOB_START", error);
            stop();
            return false;
        }
    }

    private boolean eligible(LedgerStore.Trip trip) {
        return trip != null && trip.team != null && trip.people() > 1 && store.teamReady(trip);
    }

    private boolean hasBluetooth() {
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            return TeamBle.hasPermissions(this) && manager != null &&
                    manager.getAdapter() != null && manager.getAdapter().isEnabled();
        } catch (RuntimeException error) { SyncLog.write(this, "JOB_BLE", error); return false; }
    }

    private boolean hasLocalNetwork() {
        if (SyncTriggerReceiver.hotspotEnabled()) return true;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null) for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities info = manager.getNetworkCapabilities(network);
            if (info != null && (info.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    info.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
        }
        // The tethering phone may not have an Android Network for its AP interface.
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface link = interfaces.nextElement();
                String name = link.getName().toLowerCase(Locale.ROOT);
                if (link.isUp() && (name.contains("wlan") || name.contains("softap") || name.startsWith("ap")) &&
                        link.getInterfaceAddresses().stream().anyMatch(address -> address.getBroadcast() != null)) return true;
            }
        } catch (Exception error) { SyncLog.write(this, "JOB_LAN", error); }
        return false;
    }

    void finish() {
        JobParameters params = active;
        stop();
        if (params != null) jobFinished(params, false);
    }

    private void stop() {
        active = null;
        main.removeCallbacks(checkContact);
        main.removeCallbacks(endWindow);
        if (peer != null) { peer.stop(); peer = null; }
        if (store != null) { store.close(); store = null; }
        SyncBackground.release(this);
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) stop();
        return false;
    }

    @Override public void onDestroy() { stop(); super.onDestroy(); }
}
