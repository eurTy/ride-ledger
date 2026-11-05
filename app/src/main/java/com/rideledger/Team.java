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
    final String tripId;
    final String issuer;
    final String issuerKey;
    final List<String> names;
    final String fingerprint;

    Team(String tripId, String issuer, String issuerKey, List<String> names) throws JSONException {
        if (!PeerIdentity.valid(tripId) || !PeerIdentity.valid(issuer) || names.size() < 2 || names.size() > MAX_DEVICES)
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
        try { fingerprint = PeerWire.hash(json()); }
        catch (Exception e) { throw new JSONException("无法验证小队资料"); }
    }

    JSONObject json() throws JSONException {
        return new JSONObject().put("format", 1).put("tripId", tripId).put("issuer", issuer)
                .put("issuerKey", issuerKey).put("names", new JSONArray(names));
    }

    static Team parse(JSONObject value) throws JSONException {
        if (value.getInt("format") != 1) throw new IllegalArgumentException("小队版本不兼容");
        JSONArray members = value.getJSONArray("names");
        if (members.length() > MAX_DEVICES) throw new IllegalArgumentException("最多五台设备");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) names.add(members.getString(i));
        return new Team(value.getString("tripId"), value.getString("issuer"), value.getString("issuerKey"), names);
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
        if (!issuer.equals(signer.deviceId) || !issuerKey.equals(signer.key))
            throw new IllegalStateException("首次登记需与建队设备连通");
        JSONObject value = grantBody(device, key, member, seat);
        return value.put("signature", signer.sign(value));
    }

    void verifyGrant(JSONObject value, String device, String key, String member) throws Exception {
        JSONObject expected = grantBody(device, key, member, value.getInt("seat"));
        String signature = value.getString("signature");
        if (!verify(issuerKey, expected, signature) ||
                !PeerWire.canonical(value).equals(PeerWire.canonical(expected.put("signature", signature))))
            throw new IllegalArgumentException("设备登记凭证无效");
    }

    private JSONObject grantBody(String device, String key, String member, int seat) throws Exception {
        if (!PeerIdentity.valid(device) || seat < 0 || seat >= size() || index(member) != seat ||
                (seat == 0) != issuer.equals(device) || (seat == 0) != issuerKey.equals(key))
            throw new IllegalArgumentException("设备席位无效");
        publicKey(key);
        return new JSONObject().put("domain", "ride-ledger-team-seat-1").put("team", fingerprint)
                .put("device", device).put("key", key).put("member", member).put("seat", seat);
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
