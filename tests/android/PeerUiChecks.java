package com.rideledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;

/** Runs the production screen in the separate test package, with a fictitious local trip. */
final class PeerUiChecks {
    static void run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (!"com.rideledger.synctest".equals(context.getPackageName())) throw new AssertionError("UI test package is not isolated");
        LedgerStore store = new LedgerStore(context);
        LedgerStore.Trip trip = store.createTrip("双通道界面检查", "骑手甲", "骑手乙");
        context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id).commit();
        // Fresh Android 12+ installations have not granted Nearby devices. Opening the app must still work.
        context.getSharedPreferences("nearby", 0).edit().putBoolean("enabled", true).commit();
        Activity activity = null;
        try {
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity first = activity;
            instrumentation.runOnMainSync(() -> {
                View root = first.getWindow().getDecorView();
                require(text(root, "快速记账") != null, "existing trip did not open quick entry");
                EditText amount = amount(root);
                require(amount != null, "amount input missing"); amount.setText("12.34");
                require(text(root, "保存这笔支出").performClick(), "save action missing");
            });
            instrumentation.waitForIdleSync();
            require(store.events(trip.id).size() == 1 && store.summary(trip.id).spent.get("CNY") == 1234L,
                    "offline quick entry failed while Bluetooth unavailable or unauthorized");
            instrumentation.runOnMainSync(() -> {
                require(text(first.getWindow().getDecorView(), "旅行").performClick(), "travel navigation missing");
                require(text(first.getWindow().getDecorView(), "蓝牙连接").performClick(), "Bluetooth settings entry missing");
                try {
                    Field status = MainActivity.class.getDeclaredField("bluetoothStatus"); status.setAccessible(true);
                    require(status.get(first) != null, "Bluetooth dialog did not open");
                    Field toggle = MainActivity.class.getDeclaredField("bluetoothToggle"); toggle.setAccessible(true);
                    require(((View) toggle.get(first)).performClick(), "Bluetooth disable action missing");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync();
            require(!context.getSharedPreferences("nearby", 0).getBoolean("enabled", true), "Bluetooth preference did not persist");
            instrumentation.runOnMainSync(first::finish); instrumentation.waitForIdleSync();
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity reopened = activity;
            instrumentation.runOnMainSync(() -> require(text(reopened.getWindow().getDecorView(), "快速记账") != null,
                    "reopening did not return directly to quick entry"));
            require(store.events(trip.id).size() == 1, "reopening lost or duplicated a local record");
        } finally {
            if (activity != null) { Activity closing = activity; instrumentation.runOnMainSync(closing::finish); instrumentation.waitForIdleSync(); }
            store.close();
        }
    }
    private static TextView text(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = text(((ViewGroup) view).getChildAt(i), expected); if (result != null) return result;
        }
        return null;
    }
    private static EditText amount(View view) {
        if (view instanceof EditText && "0.00".contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText result = amount(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
