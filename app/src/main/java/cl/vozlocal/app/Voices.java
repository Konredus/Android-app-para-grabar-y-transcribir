package cl.vozlocal.app;

import android.content.Context;
import java.io.File;

/**
 * "Mi voz": una muestra de 3 a 10 s del usuario que se envía como voz conocida en TODOS los bloques, incluido el
 * primero. Así el modelo lo reconoce desde el segundo 0 (sin muestra, el bloque 1 es donde más se confunde).
 * La muestra queda en este teléfono y viaja a OpenAI solo junto con los audios que se transcriben.
 */
final class Voices {
    private Voices(){}
    /** Nombre con que se envía la muestra: único, para que no choque con las letras que el modelo da a voces desconocidas. */
    static final String MINE="voz_propia";
    /** Id de la voz del usuario dentro de la transcripción (igual en todos los bloques). */
    static final String ME="voice:me";
    static final long MIN_MS=3000,MAX_MS=9500;
    static File file(Context c){File d=new File(c.getFilesDir(),"voices");d.mkdirs();return new File(d,"me.m4a");}
    static boolean has(Context c){return file(c).length()>1000;}
    static String name(Context c){String n=new Settings(c).prefs.getString("myVoiceName","").trim();return n.isEmpty()?"Yo":n;}
    static void setName(Context c,String name){new Settings(c).prefs.edit().putString("myVoiceName",name.trim()).apply();}
    static void delete(Context c){file(c).delete();Diagnostics.event("setting_changed",null,"action","my_voice","result","deleted");}
    /** Huella de la muestra: si cambia, los bloques ya transcritos con la muestra anterior no se reutilizan. */
    static String fingerprint(Context c){File f=file(c);return has(c)?f.length()+"-"+f.lastModified():"none";}
    /** {nombre enviado, data URL, voz destino, descripción} o null si no hay muestra. */
    static String[] reference(Context c){
        if(!has(c))return null;
        try{byte[] data=java.nio.file.Files.readAllBytes(file(c).toPath());return new String[]{MINE,"data:audio/mp4;base64,"+android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP),ME,"tu voz"};}
        catch(Exception e){return null;}
    }
}
