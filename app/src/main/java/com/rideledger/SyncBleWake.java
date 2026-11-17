package com.rideledger;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Low-power, team-filtered presence notification; the job performs the actual handshake. */
public final class SyncBleWake extends BroadcastReceiver {
    private static final String MATCH = "com.rideledger.BLE_WAKE";
    private static boolean started;

    private static PendingIntent pending(Context context) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 14731,
                new Intent(context, SyncBleWake.class).setAction(MATCH), flags);
    }

    private static BluetoothLeScanner scanner(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        return adapter != null && adapter.isEnabled() ? adapter.getBluetoothLeScanner() : null;
    }

    static void stop(Context context) {
        started = false;
        if (Build.VERSION.SDK_INT < 29 || !TeamBle.hasPermissions(context)) return;
        try {
            BluetoothLeScanner scanner = scanner(context);
            if (scanner != null) scanner.stopScan(pending(context));
        } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.BLE_SCAN, error); }
    }

    static void start(Context context) {
        if (started || Build.VERSION.SDK_INT < 29 || !TeamBle.canScan(context, true)) return;
        try {
            BluetoothLeScanner scanner = scanner(context);
            if (scanner == null) return;
            LedgerStore store = LedgerStore.application(context);
            List<ScanFilter> filters = new ArrayList<>();
            for (LedgerStore.Trip trip : store.trips()) {
                if (trip != null && trip.team != null && trip.people() > 1 && store.teamReady(trip) &&
                        SyncBackground.tripAllowed(context, trip.id))
                    filters.add(new ScanFilter.Builder().setServiceData(TeamBle.scope(trip), new byte[0]).build());
            }
            // Never start an unfiltered background scan, including when no team is ready.
            if (filters.isEmpty()) return;
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).build();
            int error = scanner.startScan(filters, settings, pending(context));
            if (error == 0 || error == ScanCallback.SCAN_FAILED_ALREADY_STARTED) started = true;
            else SyncLog.write(context, SyncLog.Event.BLE_SCAN, "Wake " + error);
        } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.BLE_SCAN, error); }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SyncTriggerReceiver.install(context.getApplicationContext());
            start(context);
            SyncBackground.radioChanged(context);
        } else if (MATCH.equals(intent.getAction())) {
            int error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0);
            if (error != 0) {
                started = false;
                SyncLog.write(context, SyncLog.Event.BLE_SCAN, "Wake " + error);
            }
            else {
                List<ScanResult> results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
                if (results != null && !results.isEmpty() && !NearbySyncService.deliverDiscovery(results))
                    SyncBackground.radioChanged(context);
            }
        }
    }

    static void radioOff() { started = false; }
}
