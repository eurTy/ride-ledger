package com.rideledger;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;

final class SyncCodec {
    private SyncCodec() {}

    static JSONObject sealPeer(LedgerStore.Trip trip, JSONObject payload) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = peerCipher(Cipher.ENCRYPT_MODE, trip, iv);
        byte[] data = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("tripId", trip.id).put("iv", encode(iv)).put("data", encode(data));
    }

    static JSONObject openPeer(LedgerStore.Trip trip, JSONObject bundle) throws Exception {
        if (!trip.id.equals(bundle.getString("tripId"))) throw new IllegalArgumentException("旅行不一致");
        byte[] iv = Base64.decode(bundle.getString("iv"), Base64.URL_SAFE | Base64.NO_WRAP);
        byte[] data = Base64.decode(bundle.getString("data"), Base64.URL_SAFE | Base64.NO_WRAP);
        if (iv.length != 12 || data.length < 16 || data.length > 8_000_000)
            throw new IllegalArgumentException("加密数据大小无效");
        return new JSONObject(new String(peerCipher(Cipher.DECRYPT_MODE, trip, iv).doFinal(data), StandardCharsets.UTF_8));
    }

    static String receipt(LedgerStore.Trip trip, String session, String sender, String receiver, JSONArray hashes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(trip.secret, Base64.URL_SAFE | Base64.NO_WRAP), "HmacSHA256"));
        JSONObject value = new JSONObject().put("domain", "ride-ledger-durable-ack-v2").put("tripId", trip.id)
                .put("session", session).put("sender", sender).put("receiver", receiver).put("frames", hashes);
        return encode(mac.doFinal(PeerWire.canonical(value).getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static Cipher peerCipher(int mode, LedgerStore.Trip trip, byte[] iv) throws Exception {
        Cipher value = Cipher.getInstance("AES/GCM/NoPadding");
        value.init(mode, new SecretKeySpec(Base64.decode(trip.secret, Base64.URL_SAFE | Base64.NO_WRAP), "AES"), new GCMParameterSpec(128, iv));
        value.updateAAD(("ride-ledger-peer-v2:" + trip.id).getBytes(StandardCharsets.UTF_8));
        return value;
    }

    static JSONObject bundle(LedgerStore store, LedgerStore.Trip trip) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        JSONObject plain = new JSONObject().put("events", store.eventsJson(trip.id));
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, trip, iv);
        byte[] ciphertext = cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("tripId", trip.id)
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING))
                .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING));
    }

    static int mergeBundle(LedgerStore store, JSONObject bundle) throws Exception {
        LedgerStore.Trip trip = store.getTrip(bundle.getString("tripId"));
        if (trip == null) return 0;
        return store.merge(trip.id, legacyEvents(trip, bundle));
    }

    static JSONArray legacyEvents(LedgerStore.Trip trip, JSONObject bundle) throws Exception {
        byte[] iv = Base64.decode(bundle.getString("iv"), Base64.NO_WRAP | Base64.URL_SAFE);
        byte[] ciphertext = Base64.decode(bundle.getString("data"), Base64.NO_WRAP | Base64.URL_SAFE);
        if (iv.length != 12 || ciphertext.length > 8_000_000) throw new JSONException("同步数据大小不合法");
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, trip, iv);
        JSONObject plain = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        JSONArray events = plain.getJSONArray("events");
        if (events.length() > 10000) throw new JSONException("记录过多");
        return events;
    }

    private static Cipher cipher(int mode, LedgerStore.Trip trip, byte[] iv) throws Exception {
        byte[] key = Base64.decode(trip.secret, Base64.NO_WRAP | Base64.URL_SAFE);
        if (key.length != 32) throw new JSONException("旅行密钥不合法");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(trip.id.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

