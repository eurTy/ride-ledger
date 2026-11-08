package com.rideledger;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The signed seat allocation is used only for first-time admission, never for ledger updates. */
final class Team {
    static final int MAX_DEVICES = 5;
    static final String INVITE_PREFIX = "RLTEAM1.";
    static final String OPEN_INVITE_PREFIX = "RLTEAM2.";
    final String tripId;
    final String issuer;
    final String issuerKey;
    final List<String> names;
    final String fingerprint;
    final boolean openSeats;

    Team(String tripId, String issuer, String issuerKey, List<String> names) throws JSONException {
        this(tripId, issuer, issuerKey, names, false);
    }

    private Team(String tripId, String issuer, String issuerKey, List<String> names, boolean openSeats) throws JSONException {
        if (!PeerIdentity.valid(tripId) || !PeerIdentity.valid(issuer) || names.isEmpty() || names.size() > MAX_DEVICES)
            throw new IllegalArgumentException("小队资料无效");
        try { publicKey(issuerKey); }
        catch (Exception e) { throw new IllegalArgumentException("建队设备身份无效"); }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty() || trimmed.length() > 30 || !unique.add(trimmed.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("成员名字须各不相同，每个最多 30 字");
            normalized.add(trimmed);
        }
        this.tripId = tripId;
        this.issuer = issuer;
        this.issuerKey = issuerKey;
        this.names = Collections.unmodifiableList(normalized);
        this.openSeats = openSeats;
        try { fingerprint = PeerWire.hash(json()); }
        catch (Exception e) { throw new JSONException("无法验证小队资料"); }
    }

    JSONObject json() throws JSONException {
        JSONObject result = new JSONObject().put("format", openSeats ? 2 : 1).put("tripId", tripId)
                .put("issuer", issuer).put("issuerKey", issuerKey);
        return openSeats ? result.put("organizer", names.get(0)).put("capacity", size()) :
                result.put("names", new JSONArray(names));
    }

    static Team parse(JSONObject value) throws JSONException {
        int format = value.getInt("format");
        if (format == 2) return open(value.getString("tripId"), value.getString("issuer"),
                value.getString("issuerKey"), value.getString("organizer"), value.getInt("capacity"));
        if (format != 1) throw new IllegalArgumentException("小队版本不兼容");
        JSONArray members = value.getJSONArray("names");
        if (members.length() > MAX_DEVICES) throw new IllegalArgumentException("最多五台设备");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) names.add(members.getString(i));
        return new Team(value.getString("tripId"), value.getString("issuer"), value.getString("issuerKey"), names);
    }

    static Team open(String id, String issuer, String key, String organizer, int capacity) throws JSONException {
        if (capacity < 1 || capacity > MAX_DEVICES) throw new IllegalArgumentException("预期人数须为 1 至 5 人");
        List<String> slots = new ArrayList<>();
        slots.add(displayName(organizer));
        for (int i = 1; i < capacity; i++) {
            String placeholder = "席位 " + (i + 1);
            if (placeholder.equals(slots.get(0))) placeholder = "待加入 " + (i + 1);
            slots.add(placeholder);
        }
        return new Team(id, issuer, key, slots, true);
    }

    static String displayName(String raw) {
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 30 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请填写成员名称（最多 30 字）");
        return name;
    }

    static String invitePrefix(String value) {
        if (value.startsWith(OPEN_INVITE_PREFIX)) return OPEN_INVITE_PREFIX;
        return value.startsWith(INVITE_PREFIX) ? INVITE_PREFIX : "";
    }

    int size() { return names.size(); }
    String discoveryScope() { return fingerprint.substring(0, 12); }
    String member(int index) { return "m" + (index + 1); }
    int index(String member) {
        for (int i = 0; i < size(); i++) if (member(i).equals(member)) return i;
        return -1;
    }

    JSONObject shares(long amount, String payer, boolean personal) throws JSONException {
        JSONObject result = new JSONObject();
        if (personal) return result.put(payer, amount);
        // The order is in the pinned member list, so every device rounds the same way.
        for (int i = 0; i < size(); i++) result.put(member(i), amount / size() + (i < amount % size() ? 1 : 0));
        return result;
    }

    JSONObject grant(Identity signer, String device, String key, String member, int seat) throws Exception {
        return grant(signer, device, key, member, seat, "");
    }

    JSONObject grant(Identity signer, String device, String key, String member, int seat, String name) throws Exception {
        if (!issuer.equals(signer.deviceId) || !issuerKey.equals(signer.key))
            throw new IllegalStateException("首次登记需与建队设备连通");
        JSONObject value = grantBody(device, key, member, seat, name);
        return value.put("signature", signer.sign(value));
    }

    void verifyGrant(JSONObject value, String device, String key, String member) throws Exception {
        JSONObject expected = grantBody(device, key, member, value.getInt("seat"), value.optString("name"));
        String signature = value.getString("signature");
        if (!verify(issuerKey, expected, signature) ||
                !PeerWire.canonical(value).equals(PeerWire.canonical(expected.put("signature", signature))))
            throw new IllegalArgumentException("设备登记凭证无效");
    }

    private JSONObject grantBody(String device, String key, String member, int seat, String name) throws Exception {
        if (!PeerIdentity.valid(device) || seat < 0 || seat >= size() || index(member) != seat ||
                (seat == 0) != issuer.equals(device) || (seat == 0) != issuerKey.equals(key))
            throw new IllegalArgumentException("设备席位无效");
        publicKey(key);
        JSONObject result = new JSONObject().put("domain", "ride-ledger-team-seat-1").put("team", fingerprint)
                .put("device", device).put("key", key).put("member", member).put("seat", seat);
        if (openSeats) {
            String normalized = displayName(name);
            if (seat == 0 && !normalized.equals(names.get(0))) throw new IllegalArgumentException("建队者名称不一致");
            result.put("name", normalized);
        }
        return result;
    }

    static String encode(byte[] data) { return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    static byte[] decode(String data) { return Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP); }

    static PublicKey publicKey(String encoded) throws Exception {
        if (encoded.length() > 200) throw new IllegalArgumentException("设备公钥无效");
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(decode(encoded)));
    }

    static boolean verify(String key, JSONObject value, String encoded) throws Exception {
        if (encoded.length() > 200) return false;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey(key));
        signature.update(PeerWire.canonical(value).getBytes(StandardCharsets.UTF_8));
        return signature.verify(decode(encoded));
    }

    static final class Identity {
        final String deviceId;
        final String key;
        private final PrivateKey privateKey;

        private Identity(String deviceId, PublicKey key, PrivateKey privateKey) {
            this.deviceId = deviceId;
            this.key = encode(key.getEncoded());
            this.privateKey = privateKey;
        }

        static synchronized Identity load(Context context) throws Exception {
            String device = PeerIdentity.deviceId(context);
            String alias = "ride-ledger-team-" + device;
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(alias)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build());
                generator.generateKeyPair();
            }
            return new Identity(device, store.getCertificate(alias).getPublicKey(), (PrivateKey) store.getKey(alias, null));
        }

        String sign(JSONObject value) throws Exception {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(PeerWire.canonical(value).getBytes(StandardCharsets.UTF_8));
            return encode(signature.sign());
        }
    }
}
