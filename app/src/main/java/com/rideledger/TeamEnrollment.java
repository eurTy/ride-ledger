package com.rideledger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS only carries signed enrollment messages. Local and remote requests share the issuer's SQLite seats. */
final class TeamEnrollment {
    private static final String DOMAIN = "ride-ledger-team-enrollment-1";
    private static final String OWNER_DOMAIN = "ride-ledger-team-relay-owner-1";
    private static final int MAX_BYTES = 48 * 1024;
    private static final long NEARBY_ENROLLMENT_SECONDS = 5;
    private final LedgerStore store;
    private final LedgerStore.Trip trip;
    private final Team.Identity identity;
    private final String token;
    private final boolean issuer;

    TeamEnrollment(LedgerStore store, LedgerStore.Trip trip, Team.Identity identity) throws Exception {
        this.store = store;
        this.trip = trip;
        this.identity = identity;
        issuer = trip.team.issuer.equals(identity.deviceId);
        token = mac(DOMAIN + ":room:" + trip.team.fingerprint);
    }

    static ScheduledExecutorService start(Context context, LedgerStore store, LedgerStore.Trip trip,
                                          java.util.function.Consumer<String> changed) {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        long delay = trip.localPerson.equals(trip.member(0)) ? 0 : NEARBY_ENROLLMENT_SECONDS;
        worker.scheduleWithFixedDelay(new Runnable() {
            private TeamEnrollment enrollment;
            private String status = "";

            @Override public void run() {
                String next;
                try {
                    if (enrollment == null) enrollment = new TeamEnrollment(store, trip, Team.Identity.load(context));
                    next = enrollment.step();
                } catch (TeamProtocol.Admission denied) {
                    next = denied.getMessage();
                    worker.shutdown();
                } catch (Exception retry) {
                    next = "HTTPS 中转暂不可用，连接恢复后会重试。";
                }
                if (!next.equals(status)) {
                    status = next;
                    changed.accept(next);
                }
                if (enrollment != null && !enrollment.issuer && store.teamReady(trip)) worker.shutdown();
            }
        }, delay, 3, TimeUnit.SECONDS);
        return worker;
    }

    String step() throws Exception {
        if (issuer) {
            String proof = identity.sign(new JSONObject().put("domain", OWNER_DOMAIN).put("token", token));
            JSONArray pending = exchange(owner(proof, new JSONArray())).getJSONArray("pending");
            JSONArray answers = new JSONArray();
            for (int i = 0; i < pending.length(); i++) {
                JSONObject response = answer(pending.getJSONObject(i));
                if (response != null) answers.put(response);
            }
            if (answers.length() > 0) exchange(owner(proof, answers));
            return "HTTPS 登记已就绪";
        }
        if (store.teamReady(trip)) return "首次登记完成";
        String id = mac(DOMAIN + ":request:" + identity.deviceId + ":" + identity.key + ":" + trip.localPerson);
        JSONObject request = message(id, "request").put("device", identity.deviceId)
                .put("key", identity.key).put("member", trip.localPerson);
        JSONObject response = exchange(new JSONObject().put("action", "submit").put("id", id)
                .put("request", signAndSeal(request)));
        JSONObject sealed = response.optJSONObject("decision");
        if (sealed == null) return "等待建队手机联网并打开这趟旅行。";
        JSONObject decision = verified(sealed, id, "decision", trip.team.issuerKey);
        if (decision.has("reason")) {
            if (store.teamReady(trip)) return "首次登记完成";
            throw new TeamProtocol.Admission(decision.getString("reason"));
        }
        store.acceptSeat(trip, decision.getJSONObject("grant"), identity.deviceId, identity.key, trip.localPerson);
        return "首次登记完成";
    }

    private JSONObject answer(JSONObject entry) throws Exception {
        String id = entry.getString("id");
        JSONObject request;
        try { request = verified(entry.getJSONObject("request"), id, "request", null); }
        catch (Exception invalid) { return null; }
        String device = request.optString("device"), key = request.optString("key"), member = request.optString("member");
        if (!PeerIdentity.valid(device) || trip.team.issuer.equals(device) || trip.team.issuerKey.equals(key) ||
                trip.indexOf(member) < 1) return null;
        JSONObject decision = message(id, "decision");
        try { decision.put("grant", store.enroll(trip, identity, device, key, member)); }
        catch (TeamProtocol.Admission denied) { decision.put("reason", denied.getMessage()); }
        catch (IllegalArgumentException invalid) { return null; }
        return new JSONObject().put("id", id).put("decision", signAndSeal(decision));
    }

    private JSONObject message(String id, String kind) throws Exception {
        return new JSONObject().put("domain", DOMAIN).put("team", trip.team.fingerprint).put("id", id).put("kind", kind);
    }

    private JSONObject signAndSeal(JSONObject message) throws Exception {
        return SyncCodec.sealPeer(trip, message.put("signature", identity.sign(message)));
    }

    private JSONObject verified(JSONObject sealed, String id, String kind, String signer) throws Exception {
        JSONObject value = SyncCodec.openPeer(trip, sealed);
        String signature = value.getString("signature");
        value.remove("signature");
        if (!DOMAIN.equals(value.getString("domain")) || !id.equals(value.getString("id")) ||
                !kind.equals(value.getString("kind")) || !trip.team.fingerprint.equals(value.getString("team")) ||
                !Team.verify(signer == null ? value.getString("key") : signer, value, signature))
            throw new IllegalArgumentException("设备登记消息无效");
        return value;
    }

    private JSONObject owner(String proof, JSONArray answers) throws Exception {
        return new JSONObject().put("action", "issuer").put("proof", proof).put("answers", answers);
    }

    private String mac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Team.decode(trip.secret), "HmacSHA256"));
        return Team.encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JSONObject exchange(JSONObject payload) throws Exception {
        String endpoint = SyncEndpoint.normalize(trip.syncUrl, false);
        byte[] input = payload.put("issuerKey", trip.team.issuerKey).toString().getBytes(StandardCharsets.UTF_8);
        if (input.length > MAX_BYTES) throw new IllegalArgumentException("登记消息过大");
        HttpsURLConnection conn = (HttpsURLConnection) new URL(endpoint + "/team-enrollment").openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(input.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Trip-Token", token);
            try (OutputStream out = conn.getOutputStream()) { out.write(input); }
            if (conn.getResponseCode() != 200) throw new IllegalStateException("中转服务暂不可用");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_BYTES) throw new IllegalArgumentException("登记消息过大");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { conn.disconnect(); }
    }
}
