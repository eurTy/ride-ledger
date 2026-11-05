package com.rideledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class CloudSync {
    interface Listener { void changed(String status, int newEvents); }
    private final LedgerStore store;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String deviceId;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile String status = "云同步未配置";

    CloudSync(Context context, LedgerStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
        prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE);
        deviceId = PeerIdentity.deviceId(context);
    }

    String endpoint() { return prefs.getString("endpoint", ""); }
    String effectiveEndpoint(LedgerStore.Trip trip) {
        if (trip.team != null) return "";
        return trip.syncUrl.isEmpty() ? endpoint() : trip.syncUrl;
    }
    String status() { return status; }

    void setEndpoint(String raw) {
        String endpoint = SyncEndpoint.normalize(raw, true);
        prefs.edit().putString("endpoint", endpoint).apply();
        publish(endpoint.isEmpty() ? "云同步未配置" : "云同步已配置", 0);
        syncNow();
    }

    void start() {
        if (running) return;
        running = true;
        syncNow();
        main.postDelayed(tick, 15000);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncNow();
            main.postDelayed(this, 15000);
        }
    };

    void syncNow() {
        if (!running || busy) return;
        busy = true;
        worker.execute(() -> {
            int[] total = {0};
            try {
                Exception failure = null;
                int attempted = 0;
                for (LedgerStore.Trip trip : store.trips()) {
                    if (effectiveEndpoint(trip).isEmpty()) continue;
                    attempted++;
                    try { syncTrip(trip, added -> total[0] += added); }
                    catch (Exception e) { if (failure == null) failure = e; }
                }
                if (failure != null) throw failure;
                publish(attempted == 0 ? "云同步未配置" : "云同步完成，新增 " + total[0] + " 笔", total[0]);
            } catch (Exception e) {
                publish("云同步待重试：" + e.getMessage() + "，已新增 " + total[0] + " 笔", total[0]);
            }
            finally { busy = false; }
        });
    }

    private void syncTrip(LedgerStore.Trip trip, IntConsumer onMerged) throws Exception {
        JSONObject body = new JSONObject().put("tripId", trip.id).put("deviceId", deviceId)
                .put("bundle", SyncCodec.bundle(store, trip));
        byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
        if (input.length > 12_000_000) throw new IllegalStateException("旅行记录超出初稿上限");
        HttpURLConnection conn = (HttpURLConnection) new URL(effectiveEndpoint(trip) + "/sync").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Trip-Token", token(trip));
            try (OutputStream out = conn.getOutputStream()) { out.write(input); }
            if (conn.getResponseCode() != 200) throw new IllegalStateException("服务返回 " + conn.getResponseCode());
            byte[] response;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 12_000_000) throw new IllegalStateException("云端记录过大");
                }
                response = buffer.toByteArray();
            }
            JSONArray snapshots = new JSONObject(new String(response, StandardCharsets.UTF_8)).getJSONArray("snapshots");
            Exception failure = null;
            for (int i = 0; i < snapshots.length(); i++) {
                try { onMerged.accept(SyncCodec.mergeBundle(store, snapshots.getJSONObject(i))); }
                catch (Exception e) { if (failure == null) failure = e; }
            }
            if (failure != null) throw failure;
        } finally { conn.disconnect(); }
    }

    private String token(LedgerStore.Trip trip) throws Exception {
        byte[] secret = Base64.decode(trip.secret, Base64.NO_WRAP | Base64.URL_SAFE);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] result = mac.doFinal(("ride-ledger-cloud-v1:" + trip.id).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(result, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private void publish(String next, int added) {
        status = next;
        main.post(() -> listener.changed(next, added));
    }
}
