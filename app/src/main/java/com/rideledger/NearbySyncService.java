package com.rideledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Keeps one selected ready trip reachable while its activity is not visible. */
public final class NearbySyncService extends Service {
    private static final String CHANNEL = "nearby_sync";
    private static final int NOTIFICATION = 14732;
    private static final String START = "com.rideledger.nearby.START";
    private static final String STOP = "com.rideledger.nearby.STOP";
    private static final String CLEAR = "com.rideledger.nearby.CLEAR";
    private static final String TRIP = "trip";
    private static NearbySyncService instance;
    private static String requestedTrip;
    private LedgerStore store;
    private LedgerStore.Trip trip;
    private PeerSync peer;
    private String receipt;
    private String ownedRequest;

    static void select(Context context, LedgerStore.Trip selected) {
        Intent intent = new Intent(context, NearbySyncService.class);
        if (selected == null || selected.team == null || selected.people() < 2 ||
                !LedgerStore.application(context).teamReady(selected) ||
                !SyncBackground.tripAllowed(context, selected.id)) {
            boolean pending = requestedTrip != null;
            requestedTrip = null;
            if (pending || instance != null) {
                // A queued foreground start must reach startForeground before it is stopped.
                try { context.startForegroundService(intent.setAction(CLEAR)); }
                catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.JOB_START, error); }
            }
            return;
        }
        if (selected.id.equals(requestedTrip)) return;
        requestedTrip = selected.id;
        intent.setAction(START).putExtra(TRIP, selected.id);
        try { context.startForegroundService(intent); }
        catch (RuntimeException error) {
            requestedTrip = null;
            if (instance != null) context.stopService(new Intent(context, NearbySyncService.class));
            SyncLog.write(context, SyncLog.Event.JOB_START, error);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        SyncBackground.attach(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            requestedTrip = null;
            SyncBackground.endSession(this, trip == null ? null : trip.id);
            SyncBleWake.stop(this);
            stopPeer();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || (!START.equals(intent.getAction()) && !CLEAR.equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String requested = intent.getStringExtra(TRIP);
        if (START.equals(intent.getAction()) && requested != null && requested.equals(requestedTrip))
            ownedRequest = requested;
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION, notification);
            if (CLEAR.equals(intent.getAction()) || requested == null || !requested.equals(requestedTrip)) {
                if (requestedTrip == null) stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            store = LedgerStore.application(this);
            LedgerStore.Trip selected = store.getTrip(requested);
            if (selected == null || selected.team == null || selected.people() < 2 ||
                    !store.teamReady(selected) || !SyncBackground.tripAllowed(this, selected.id)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (trip == null || !trip.id.equals(selected.id)) {
                stopPeer();
                receipt = null;
                updateNotification();
            }
            trip = selected;
            visibilityChanged();
        } catch (RuntimeException error) {
            SyncLog.write(this, SyncLog.Event.JOB_START, error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "附近同步", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 14732,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 14733,
                new Intent(this, NearbySyncService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_ride_ledger)
                .setContentTitle("附近同步进行中")
                .setContentText(receipt == null ? "后台保持附近连接，可能增加耗电。" : receipt)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "结束后台同步", stop).build()).build();
    }

    void visibilityChanged() {
        if (SyncBackground.isForeground()) { stopPeer(); return; }
        if (peer != null || trip == null || !trip.id.equals(requestedTrip) || store == null || !store.teamReady(trip) ||
                !SyncBackground.tripAllowed(this, trip.id)) return;
        try {
            SyncBackground.claimForService();
            peer = new PeerSync(this, store, this::received, true, trip, true, true);
            peer.start();
        } catch (RuntimeException error) {
            stopPeer();
            SyncLog.write(this, SyncLog.Event.JOB_START, error);
        }
    }

    String activeTrip() { return peer == null || trip == null ? null : trip.id; }

    static boolean deliverDiscovery(List<ScanResult> results) {
        NearbySyncService current = instance;
        if (current == null || current.peer == null || results == null || results.isEmpty()) return false;
        return current.peer.discoveredBluetooth(results);
    }

    private void received(int added) {
        if (added <= 0) return;
        receipt = "收到 " + added + " 条新记录 · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        try { if (manager != null) manager.notify(NOTIFICATION, notification()); }
        catch (RuntimeException error) { SyncLog.write(this, SyncLog.Event.JOB_START, error); }
    }

    private void stopPeer() {
        if (peer != null) { peer.stop(); peer = null; }
    }

    @Override public void onDestroy() {
        stopPeer();
        if (instance == this) {
            instance = null;
            if (ownedRequest != null && ownedRequest.equals(requestedTrip)) requestedTrip = null;
        }
        SyncBackground.detach(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
