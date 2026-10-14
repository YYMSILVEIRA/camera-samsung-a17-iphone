package com.ib2s.lumaa17;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-supplied API key encrypted with a non-exportable Android Keystore key. */
final class GeminiKeyStore {
    private static final String ALIAS = "luma_gemini_api_v1";
    private static final String PREF = "luma_private";
    private static final String FIELD = "encrypted_gemini_key";

    private GeminiKeyStore() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    static void save(Context context, String value) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (value == null || value.trim().isEmpty()) {
            p.edit().remove(FIELD).apply();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(data, 0, combined, iv.length, data.length);
        if (!p.edit().putString(FIELD, Base64.encodeToString(combined, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Não foi possível armazenar a chave");
    }

    static String load(Context context) throws Exception {
        String encoded = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(FIELD, null);
        if (encoded == null || encoded.isEmpty()) return "";
        byte[] combined = Base64.decode(encoded, Base64.DEFAULT);
        if (combined.length < 29) throw new IllegalStateException("Chave criptografada inválida");
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(combined, iv.length, combined.length - iv.length), StandardCharsets.UTF_8);
    }
}
