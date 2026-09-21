package cl.vozlocal.app;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Settings {
    final SharedPreferences prefs;
    Settings(Context c) { prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    boolean automatic() { return prefs.getBoolean("automatic", false); }
    boolean wifiOnly() { return prefs.getBoolean("wifi", true); }
    boolean charging() { return prefs.getBoolean("charging", false); }
    boolean includeAudio() { return prefs.getBoolean("includeAudio", false); }
    boolean askTitle() { return prefs.getBoolean("askTitle", true); }
    boolean driveConnected() { return prefs.getBoolean("driveConnected", false); }
    String folderId() { return prefs.getString("folderId", ""); }
    String language() { return prefs.getString("language", "es"); }
    boolean hasKey() { return prefs.contains("keyEncrypted"); }
    private static synchronized javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("voz-local-openai")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("voz-local-openai", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey("voz-local-openai", null);
    }
    void saveKey(String value) throws Exception {
        value = value.trim();
        if (value.isEmpty()) { prefs.edit().remove("keyEncrypted").remove("keyIv").commit(); return; }
        if (!value.startsWith("sk-") || value.length() < 20 || value.matches(".*\\s.*")) throw new IllegalArgumentException("Revisa la clave: debe comenzar con sk- y no contener espacios.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("keyEncrypted", encrypted).putString("keyIv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()) throw new java.io.IOException("No se pudo guardar la clave.");
    }
    String apiKey() throws Exception {
        if (!hasKey()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("keyIv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("keyEncrypted", ""), Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
}
