package com.rideledger;

import android.app.Instrumentation;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** Run on an isolated device with Bluetooth enabled and the test package's permissions granted. */
final class TeamBleRecoveryChecks {
    private interface Check { void run() throws Exception; }

    static void run(Instrumentation instrumentation, Context context, LedgerStore.Trip trip) throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        TeamBle ble = new TeamBle(context, trip, PeerIdentity.deviceId(context), new TeamBle.Events() {
            @Override public void found(PeerRoutePolicy.Endpoint endpoint) { }
            @Override public void lost(String key) { }
            @Override public void incoming(PeerConnection connection) { connection.close(); }
            @Override public void reset() { }
        });
        try {
            onMain(instrumentation, () -> ble.start(timer, false, false));
            // Let the first scan run long enough for a manual refresh to register immediately.
            SystemClock.sleep(8500);
            onMain(instrumentation, () -> verifyRecovery(ble));
        } finally {
            instrumentation.runOnMainSync(ble::stop);
            timer.shutdownNow();
        }
    }

    private static void verifyRecovery(TeamBle ble) throws Exception {
        BluetoothLeScanner scanner = (BluetoothLeScanner) field("scanner").get(ble);
        ScanCallback stale = (ScanCallback) field("scan").get(ble);
        Object listener = field("server").get(ble);
        require(scanner != null && stale != null && listener != null, "BLE test device did not start scanning and listening");

        // Simulate the system dropping the scan while TeamBle still holds its callback.
        scanner.stopScan(stale);
        ble.rediscover();
        Object fresh = field("scan").get(ble);
        Object rebound = field("server").get(ble);
        require(fresh != null && fresh != stale, "refresh reused the stale scan callback");
        require(rebound != null && rebound != listener, "refresh reused the idle listener");

        Handler main = (Handler) field("main").get(ble);
        Runnable renew = (Runnable) field("renewScan").get(ble);
        Runnable retry = (Runnable) field("retryRadio").get(ble);
        require(main.hasCallbacks(renew), "a foreground scan has no expiration");
        renew.run();
        require(field("scan").get(ble) == null && main.hasCallbacks(retry),
                "scan expiration did not schedule a replacement");
        require(field("server").get(ble) == rebound, "periodic scan renewal interrupted the listener");

        Method failed = TeamBle.class.getDeclaredMethod("radioFailed", boolean.class, String.class);
        failed.setAccessible(true);
        verifyRetries(ble, main, retry, failed, true, field("retryScan"));
        verifyRetries(ble, main, retry, failed, false, field("retryAdvertise"));
    }

    private static void verifyRetries(TeamBle ble, Handler main, Runnable retry, Method failed, boolean scan,
                                      Field deadline) throws Exception {
        for (long delay : new long[]{5000, 10000, 20000, 40000, 60000, 60000}) {
            long before = SystemClock.elapsedRealtime();
            failed.invoke(ble, scan, "SimulatedFailure");
            long after = SystemClock.elapsedRealtime();
            long next = deadline.getLong(ble);
            require(main.hasCallbacks(retry) && next >= before + delay && next <= after + delay,
                    (scan ? "scan" : "advertising") + " lost its bounded retry");
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = TeamBle.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void onMain(Instrumentation instrumentation, Check check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { check.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
