package com.rideledger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class PeerIdentity {
    private PeerIdentity() { }
    static synchronized String deviceId(Context context) {
        // Keep the original preferences key so upgrades preserve device identity and team seats.
        SharedPreferences prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE);
        String value = prefs.getString("device_id", "");
        if (!valid(value)) {
            value = UUID.randomUUID().toString();
            if (!prefs.edit().putString("device_id", value).commit())
                throw new IllegalStateException("无法保存设备身份");
        }
        return value;
    }
    static boolean valid(String value) {
        return value != null && value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");
    }
}
