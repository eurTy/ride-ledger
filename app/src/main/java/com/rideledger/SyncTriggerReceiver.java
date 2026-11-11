package com.rideledger;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;

/** Process-local radio events. The persisted periodic job covers a stopped process. */
final class SyncTriggerReceiver extends BroadcastReceiver {
    private static final String HOTSPOT_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static boolean installed, hotspot;

    static void install(Context context) {
        if (installed || Build.VERSION.SDK_INT < 29) return;
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(HOTSPOT_STATE);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            SyncTriggerReceiver receiver = new SyncTriggerReceiver();
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            installed = true;
        } catch (RuntimeException error) { SyncLog.write(context, SyncLog.Event.RADIO_RECEIVER, error); }
    }

    static boolean hotspotEnabled() { return hotspot; }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (HOTSPOT_STATE.equals(action)) hotspot = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) == 13;
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF)
            SyncBleWake.radioOff();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON &&
                !SyncBackground.isForeground()) SyncBleWake.start(context);
        if (!isInitialStickyBroadcast() && (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) ||
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON))
            SyncBackground.radioChanged(context);
    }
}
