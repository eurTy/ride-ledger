package com.rideledger;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps local SQLite values without changing the invitation or peer protocol. */
final class TripSecrets {
    private static final String ALIAS = "ride-ledger-trip-secrets-v1";
    private static final String PREFIX = "keystore:v1:";
    private static SecretKey cached;

    private static synchronized SecretKey key(boolean create) throws Exception {
        if (cached != null) return cached;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return cached = existing;
        if (!create) throw new IllegalStateException("小队密钥无法从本机读取");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return cached = generator.generateKey();
    }

    static String wrap(String tripId, String secret) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(tripId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(sealed, Base64.NO_WRAP);
        } catch (Exception error) { throw new IllegalStateException("无法保护本机小队密钥", error); }
    }

    static String unwrap(String tripId, String stored) {
        try {
            if (!stored.startsWith(PREFIX)) throw new IllegalArgumentException("小队密钥格式无效");
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            cipher.updateAAD(tripId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("无法读取本机小队密钥", error); }
    }
}
