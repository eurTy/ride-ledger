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
import android.os.SystemClock;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayDeque;

/** Brief discovery lease. Data is transferred only after the team handshake. */
public final class SyncJobService extends JobService {
    private static final long DISCOVERY_WINDOW = 12000, SOFT_WINDOW = 45000;
    private static final long PROGRESS_GRACE = 15000, TRIP_BUDGET = 120000, JOB_BUDGET = 180000;
    private static final int TRIPS_PER_JOB = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<LedgerStore.Trip> queue = new ArrayDeque<>();
    private LedgerStore store;
    private PeerSync peer;
    private JobParameters active;
    private long tripStarted, jobStarted;
    private final Runnable checkContact = () -> { if (peer != null && !peer.hasContact() && !peer.transferring()) advance(); };
    private final Runnable endWindow = this::checkWindow;

    @Override public boolean onStartJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 29 || !SyncBackground.acquire(this)) return false;
        try {
            SyncLog.maintain(this);
            SyncTriggerReceiver.install(getApplicationContext());
            if (!hasLocalNetwork() && !hasBluetooth()) { stop(); return false; }
            store = LedgerStore.application(this);
            List<LedgerStore.Trip> eligibleTrips = new ArrayList<>();
            for (LedgerStore.Trip trip : store.trips()) if (eligible(trip)) eligibleTrips.add(trip);
            if (eligibleTrips.isEmpty()) { stop(); return false; }
            android.content.SharedPreferences cursor = getSharedPreferences("background_sync", MODE_PRIVATE);
            LedgerStore.Trip trip = nextTrip(eligibleTrips, cursor.getString("last_attempt", ""));
            int first = eligibleTrips.indexOf(trip);
            for (int i = 0; i < Math.min(TRIPS_PER_JOB, eligibleTrips.size()); i++)
                queue.add(eligibleTrips.get((first + i) % eligibleTrips.size()));
            active = params;
            jobStarted = SystemClock.elapsedRealtime();
            startNext();
            return true;
        } catch (RuntimeException error) {
            SyncLog.write(this, SyncLog.Event.JOB_START, error);
            stop();
            return false;
        }
    }

    private void startNext() {
        LedgerStore.Trip trip = queue.removeFirst();
        tripStarted = SystemClock.elapsedRealtime();
        peer = new PeerSync(this, store, added -> { }, true, trip, true);
        // Record each attempted trip, so the next job continues after the last one it reached.
        getSharedPreferences("background_sync", MODE_PRIVATE).edit().putString("last_attempt", trip.id).apply();
        peer.start();
        long remaining = jobStarted + JOB_BUDGET - tripStarted;
        main.postDelayed(checkContact, Math.min(DISCOVERY_WINDOW, remaining));
        main.postDelayed(endWindow, Math.min(SOFT_WINDOW, remaining));
    }

    private void checkWindow() {
        if (peer == null) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = Math.min(jobStarted + JOB_BUDGET, tripStarted + TRIP_BUDGET);
        if (peer.transferring() && peer.lastProgress() > now - PROGRESS_GRACE && now < deadline) {
            main.postDelayed(endWindow, Math.min(PROGRESS_GRACE, deadline - now));
        } else advance();
    }

    private void advance() {
        stopPeer();
        if (queue.isEmpty() || SystemClock.elapsedRealtime() + DISCOVERY_WINDOW > jobStarted + JOB_BUDGET) {
            finish();
            return;
        }
        try { startNext(); }
        catch (RuntimeException error) {
            SyncLog.write(this, SyncLog.Event.JOB_START, error);
            finish();
        }
    }

    static LedgerStore.Trip nextTrip(List<LedgerStore.Trip> eligibleTrips, String lastAttempt) {
        eligibleTrips.sort(Comparator.comparing(trip -> trip.id));
        for (LedgerStore.Trip trip : eligibleTrips) if (trip.id.compareTo(lastAttempt) > 0) return trip;
        return eligibleTrips.get(0);
    }

    private boolean eligible(LedgerStore.Trip trip) {
        return trip != null && trip.team != null && trip.people() > 1 && store.teamReady(trip);
    }

    private boolean hasBluetooth() {
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            return TeamBle.hasPermissions(this) && manager != null &&
                    manager.getAdapter() != null && manager.getAdapter().isEnabled();
        } catch (RuntimeException error) { SyncLog.write(this, SyncLog.Event.JOB_BLE, error); return false; }
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
        } catch (Exception error) { SyncLog.write(this, SyncLog.Event.JOB_LAN, error); }
        return false;
    }

    void finish() {
        JobParameters params = active;
        stop();
        if (params != null) jobFinished(params, false);
    }

    private void stop() {
        active = null;
        stopPeer();
        queue.clear();
        store = null;
        SyncBackground.release(this);
    }

    private void stopPeer() {
        main.removeCallbacks(checkContact);
        main.removeCallbacks(endWindow);
        if (peer != null) { peer.stop(); peer = null; }
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) stop();
        return false;
    }

    @Override public void onDestroy() { stop(); super.onDestroy(); }
}
