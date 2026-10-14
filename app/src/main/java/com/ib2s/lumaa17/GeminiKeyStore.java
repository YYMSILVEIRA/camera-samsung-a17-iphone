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

/** Personal BYOK: no credential in the APK, diagnostics, URLs, or logs. */
public final class GeminiKeyStore {
    public static final String DEFAULT_MODEL="gemini-nano-banana-2.1";
    private static final String ALIAS="luma_gemini_personal_key_v1";
    private final SharedPreferences prefs;
    public GeminiKeyStore(Context context){prefs=context.getSharedPreferences("luma_gemini",Context.MODE_PRIVATE);}
    public boolean configured(){return prefs.contains("ciphertext");}
    public String model(){return prefs.getString("model",DEFAULT_MODEL);}
    public String size(){return prefs.getString("size","4K");}
    public void options(String model,String size){prefs.edit().putString("model",model).putString("size",size).apply();}
    public void clear(){prefs.edit().remove("ciphertext").remove("iv").apply();}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    public void save(String value) throws Exception {
        validate(value);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("ciphertext",encrypted).putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Não foi possível salvar a chave.");
    }
    public String read() throws Exception {
        if(!configured())throw new IllegalStateException("Insira sua chave de API nos ajustes do Gemini.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("ciphertext",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    public static void validate(String key){
        if(key.startsWith("gen-lang-client-"))throw new IllegalArgumentException("Esse é um ID de projeto. Cole a chave de API criada no Google AI Studio.");
        if(!key.matches("[A-Za-z0-9_-]{30,200}"))throw new IllegalArgumentException("Cole uma chave de API válida do Google AI Studio.");
    }
}
