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
    boolean askTitle() { return prefs.getBoolean("askTitle", true); }
    String language() { return prefs.getString("language", "es"); }
    String provider(){return prefs.getString("provider","openai");}
    /** Prefijo de las preferencias de la clave: "" (OpenAI), "openrouter_" u "custom_" (servidor propio). Cada proveedor guarda la suya. */
    String prefix(){return prefix(provider());}
    static String prefix(String provider){return provider.equals("openai")?"":provider.equals("openrouter")?"openrouter_":"custom_";}
    boolean openRouter(){return provider().equals("openrouter");}
    /** «Servidor compatible»: todo lo que no es OpenAI ni OpenRouter (su clave va con el prefijo "custom_"). */
    boolean custom(){return !provider().equals("openai")&&!openRouter();}
    /** Nombre del proveedor para los textos: «OpenRouter», «OpenAI» o «Tu servidor». */
    String providerName(){return openRouter()?"OpenRouter":custom()?"Tu servidor":"OpenAI";}
    boolean hasKey() { return prefs.contains(prefix()+"keyEncrypted"); }
    /** Agregar la fecha (2026-09-27) delante de cada nombre. Activado por defecto (pedido del usuario, 0.4.3). */
    boolean datePrefix(){return prefs.getBoolean("datePrefix",true);}
    /** "ask" (preguntar cada vez), "always" o "never". */
    String speakersMode(){return prefs.getString("speakersMode","ask");}
    /** Modelo para transcribir sin separar voces (OpenAI). */
    String textModel(){return prefs.getString("openaiTextModel","gpt-transcribe");}
    /** ¿El proveedor configurado puede separar voces? */
    boolean canSeparate(){return provider().equals("openai")||(openRouter()?Models.recipe(Models.chosen(this,true)).diarizes:prefs.getBoolean("customSpeakers",false));}
    /** Elección por defecto cuando no se puede preguntar (p. ej. transcripción automática). */
    boolean defaultSpeakers(){return canSeparate()&&!speakersMode().equals("never");}
    ProviderConfig config()throws Exception{return config(defaultSpeakers());}
    /** Dirección del servidor propio ("" si aún no se configura). */
    String customBase(){return prefs.getString("customBase","").trim();}
    /** Con «Servidor compatible» sin dirección no se puede transcribir (ni comprobar la clave). */
    boolean needsServer(){return custom()&&customBase().isEmpty();}
    static final String NO_SERVER="Configura la dirección de tu servidor en Ajustes.";
    ProviderConfig config(boolean speakers)throws Exception{
        boolean openai=provider().equals("openai");
        if(openai)return new ProviderConfig("openai","https://api.openai.com/v1",speakers?"gpt-4o-transcribe-diarize":textModel(),apiKey(),speakers);
        // OpenRouter (0.8.0): el modelo sale de la elección del usuario o de «Automático»; solo separa voces si su receta sabe pedirlo.
        if(openRouter()){String model=Models.chosen(this,speakers);return new ProviderConfig("openrouter",Models.BASE,model,apiKey(),speakers&&Models.recipe(model).diarizes);}
        // Sin dirección no hay a dónde enviar: nunca una por defecto (la clave y el audio irían a un tercero).
        if(customBase().isEmpty())throw new HttpApi.UserAction(NO_SERVER);
        return new ProviderConfig(provider(),customBase(),prefs.getString("customModel","whisper-1"),apiKey(),speakers&&prefs.getBoolean("customSpeakers",false));
    }
    // ---------- 0.8.0: OpenRouter ----------
    /** Modelo de OpenRouter para transcribir separando voces: un id del catálogo o Models.AUTO («Automático (recomendado)»). */
    String orSpeakersModel(){return prefs.getString("orSpeakersModel",Models.AUTO);}
    /** Modelo de OpenRouter para transcribir solo el texto: un id del catálogo o Models.AUTO. */
    String orTextModel(){return prefs.getString("orTextModel",Models.AUTO);}
    /** Clave de OpenRouter aunque el proveedor activo sea otro (la usan la nota y la bienvenida). */
    boolean hasOpenRouterKey(){return prefs.contains("openrouter_keyEncrypted");}
    String openRouterKey()throws Exception{return hasOpenRouterKey()?decrypt("openrouter_"):"";}
    /** Guarda la clave de un proveedor ("openai", "openrouter", "custom" o "anthropic") sin cambiar el proveedor activo. Vacía = borrarla. */
    void saveKeyFor(String provider,String value)throws Exception{encrypt(provider.equals("anthropic")?"anthropic_":prefix(provider),value);}
    void saveOpenRouterKey(String value)throws Exception{encrypt("openrouter_",value);}
    // ---------- 0.6.0 ----------
    /** Armar la «Nota para tu segundo cerebro» al terminar cada transcripción. */
    boolean noteAuto(){return prefs.getBoolean("noteAuto",true);}
    /**
     * IA que arma la nota: "openai" (clave de OpenAI), "anthropic" (Claude, su clave) u "openrouter" (clave de OpenRouter; 0.8.0).
     * Quien nunca eligió una usa la de siempre (OpenAI), salvo que transcriba con OpenRouter: ahí la nota sale con la
     * misma clave, sin configurar nada más. Elegir una en Ajustes la deja fija aunque después cambie el proveedor.
     */
    String noteProvider(){return prefs.getString("noteProvider",openRouter()?"openrouter":"openai");}
    /** Modelo de la nota; vacío = el recomendado del proveedor (ver Notes). */
    String noteModel(){return prefs.getString("noteModel","");}
    /** Clave de OpenAI aunque se transcriba con un servidor propio (la usa la nota). */
    boolean hasOpenAiKey(){return prefs.contains("keyEncrypted");}
    String openAiKey()throws Exception{return hasOpenAiKey()?decrypt(""):"";}
    boolean hasAnthropicKey(){return prefs.contains("anthropic_keyEncrypted");}
    String anthropicKey()throws Exception{return hasAnthropicKey()?decrypt("anthropic_"):"";}
    void saveAnthropicKey(String value)throws Exception{encrypt("anthropic_",value);}
    /** Carpeta de guardado rápido (p. ej. Drive/0-Inbox): uri del árbol o "". */
    String inboxTree(){return prefs.getString("saveTree","");}
    String inboxName(){return prefs.getString("saveTreeName","0-Inbox");}
    int lastSeenVersion(){return prefs.getInt("lastSeenVersion",0);}
    void setLastSeenVersion(int v){prefs.edit().putInt("lastSeenVersion",v).apply();}
    /** Recordar si la bitácora quedó abierta (se miraba en cada visita). */
    boolean bitacoraOpen(){return prefs.getBoolean("bitacoraOpen",false);}
    void setBitacoraOpen(boolean open){prefs.edit().putBoolean("bitacoraOpen",open).apply();}
    private void encrypt(String prefixKey,String value)throws Exception{
        value=value.trim();
        if(value.isEmpty()){prefs.edit().remove(prefixKey+"keyEncrypted").remove(prefixKey+"keyIv").commit();return;}
        if(value.length()>8192||value.matches(".*\\s.*"))throw new IllegalArgumentException("La clave no debe contener espacios ni saltos de línea.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString(prefixKey+"keyEncrypted",encrypted).putString(prefixKey+"keyIv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit())throw new java.io.IOException("No se pudo guardar la clave.");
    }
    private String decrypt(String prefixKey)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString(prefixKey+"keyIv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString(prefixKey+"keyEncrypted",""),Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
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
    /** Clave del proveedor activo. Vacía = borrarla. Mismo cifrado que las demás: cambia solo el prefijo. */
    void saveKey(String value) throws Exception { encrypt(prefix(), value); }
    String apiKey() throws Exception { return hasKey() ? decrypt(prefix()) : ""; }
}
