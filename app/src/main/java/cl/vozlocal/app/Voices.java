package cl.vozlocal.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Voces conocidas (0.6.0): tu voz («Mi voz») y las de las personas con que más hablas. Cada una es una muestra de 3 a
 * 10 s que, al separar voces, va como voz conocida en TODOS los bloques, incluido el primero: así el modelo reconoce a
 * cada persona desde el segundo 0 (sin muestra, el bloque 1 es donde más se confunde) y la app le pone su nombre.
 * El modelo acepta hasta {@link Transcriber#MAX_KNOWN} por envío: va primero la tuya y después las demás por nombre.
 *
 * Archivos: {@code filesDir/voices/<id>.m4a} y el índice {@code voices/voices.json} [{id,name,me,createdAt,use}]
 * (escritura atómica). Tu voz tiene el id "me": es el mismo {@code me.m4a} de la 0.5, que se migra solo (con el nombre
 * de Ajustes, "myVoiceName"). Las demás llevan un id corto al azar ([a-z0-9]{8}), que es también el nombre con que se
 * envían ("voz_<id>"): único, para que no choque con las letras que el modelo da a las voces que no reconoce.
 * Las muestras quedan en este teléfono y viajan al proveedor (OpenAI u OpenRouter) solo junto con los audios que se transcriben.
 */
final class Voices {
    private Voices(){}
    /** Nombre con que se envía tu muestra: único, para que no choque con las letras que el modelo da a voces desconocidas. */
    static final String MINE="voz_propia";
    /** Id de tu voz dentro de la transcripción (igual en todos los bloques). */
    static final String ME="voice:me";
    /** Id de tu voz en la biblioteca (y nombre de su archivo: me.m4a, igual que en la 0.5). */
    static final String ME_ID="me";
    /** Prefijo de las voces conocidas dentro de la transcripción: "voice:me", "voice:<id>". */
    static final String TARGET="voice:";
    static final long MIN_MS=3000,MAX_MS=9500;
    /** Una muestra más chica que esto no es audio (mismo criterio que la 0.5). */
    static final long MIN_BYTES=1000;
    private static final Object LOCK=new Object();
    private static final String ALPHABET="abcdefghijklmnopqrstuvwxyz0123456789";
    private static final java.security.SecureRandom RANDOM=new java.security.SecureRandom();

    /** Una voz guardada. use: se envía al transcribir (se puede apagar sin borrarla). */
    static final class Voice {
        final String id,name;final boolean me,use;final long createdAt;
        Voice(String id,String name,boolean me,long createdAt,boolean use){this.id=id;this.name=name;this.me=me;this.createdAt=createdAt;this.use=use;}
        /** Id de esta voz dentro de la transcripción. */
        String target(){return me?ME:TARGET+id;}
        /** Nombre con que se envía la muestra. */
        String sentName(){return me?MINE:"voz_"+id;}
        /** «Konrad (tú)» o «Fran». */
        String label(){return me?name+" (tú)":name;}
        JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("name",name).put("me",me).put("createdAt",createdAt).put("use",use);}
        Voice with(String name,boolean use){return new Voice(id,name,me,createdAt,use);}
    }

    // ---------- Archivos ----------
    static File dir(Context c){File d=new File(c.getFilesDir(),"voices");d.mkdirs();return d;}
    private static File index(Context c){return new File(dir(c),"voices.json");}
    /** "me" o un id corto de letras minúsculas y números: nunca una ruta. */
    static boolean validId(String id){return id!=null&&(id.equals(ME_ID)||id.matches("[a-z0-9]{6,12}"));}
    static File file(Context c,String id){if(!validId(id))throw new IllegalArgumentException("Voz inválida");return new File(dir(c),id+".m4a");}
    private static boolean valid(File f){return f.isFile()&&f.length()>MIN_BYTES;}
    private static String prefsName(Context c){return new Settings(c).prefs.getString("myVoiceName","").trim();}
    /** Sin espacios de más y con un largo razonable. */
    static String clean(String name){String n=name==null?"":name.trim().replaceAll("\\s+"," ");return n.length()>80?n.substring(0,80).trim():n;}

    // ---------- API anterior («Mi voz»), se mantiene ----------
    /** Archivo de tu voz (voices/me.m4a). */
    static File file(Context c){return file(c,ME_ID);}
    /** ¿Grabaste tu voz? (aunque esté apagada para transcribir). */
    static boolean has(Context c){return get(c,ME_ID)!=null;}
    /** Tu nombre: el de tu voz guardada, el de Ajustes o «Yo». */
    static String name(Context c){Voice me=get(c,ME_ID);if(me!=null&&!me.name.isEmpty())return me.name;String n=prefsName(c);return n.isEmpty()?"Yo":n;}
    static void setName(Context c,String name){String n=clean(name);new Settings(c).prefs.edit().putString("myVoiceName",n).apply();if(!n.isEmpty())rename(c,ME_ID,n);}
    static void delete(Context c){remove(c,ME_ID);}
    /** {nombre enviado, data URL, voz destino, descripción} de tu voz, o null si no la grabaste. */
    static String[] reference(Context c){Voice me=get(c,ME_ID);return me==null?null:reference(c,me);}

    // ---------- Biblioteca ----------
    /** Todas las voces guardadas con su muestra: la tuya primero y después por nombre. */
    static List<Voice> list(Context c){
        synchronized(LOCK){List<Voice> out=new ArrayList<>();for(Voice v:load(c))if(valid(file(c,v.id)))out.add(v);sort(out);return out;}
    }
    static Voice get(Context c,String id){if(!validId(id))return null;for(Voice v:list(c))if(v.id.equals(id))return v;return null;}
    /** La voz con ese nombre (sin distinguir mayúsculas), o null. */
    static Voice findByName(Context c,String name){String n=clean(name);if(n.isEmpty())return null;for(Voice v:list(c))if(v.name.equalsIgnoreCase(n))return v;return null;}
    /** La voz guardada que corresponde a un id de la transcripción ("voice:me", "voice:<id>"), o null. */
    static Voice byTarget(Context c,String target){
        if(target==null||!target.startsWith(TARGET))return null;String id=target.substring(TARGET.length());return validId(id)?get(c,id):null;
    }
    /** Nombre visible de una voz conocida de la transcripción, o null si no es una voz guardada. */
    static String nameFor(Context c,String target){Voice v=byTarget(c,target);return v==null?null:v.name;}
    /** Las que se usan al transcribir, en orden (la tuya primero). */
    static List<Voice> used(Context c){List<Voice> out=new ArrayList<>();for(Voice v:list(c))if(v.use)out.add(v);return out;}
    /** Las que van en cada envío: las usadas, hasta {@link Transcriber#MAX_KNOWN}. */
    static List<Voice> selected(Context c){List<Voice> used=used(c);return used.size()>Transcriber.MAX_KNOWN?new ArrayList<>(used.subList(0,Transcriber.MAX_KNOWN)):used;}

    /**
     * Guarda una voz nueva con la muestra clip (que se MUEVE a la biblioteca: el archivo de origen deja de existir).
     * me=true es tu voz: si ya existía, se reemplaza su muestra y su nombre.
     */
    static Voice add(Context c,String name,File clip,boolean me)throws IOException{
        String n=clean(name);
        if(n.isEmpty()){if(!me)throw new IllegalArgumentException("Falta el nombre");n=prefsName(c).isEmpty()?"Yo":prefsName(c);}
        if(clip==null||!valid(clip))throw new IOException("La muestra está vacía");
        Voice v;
        synchronized(LOCK){
            List<Voice> all=load(c);String id=me?ME_ID:newId(c,all);Voice old=find(all,id);
            place(clip,file(c,id));
            v=new Voice(id,n,me,old!=null?old.createdAt:System.currentTimeMillis(),old==null||old.use);
            if(old!=null)all.remove(old);all.add(v);save(c,all);
        }
        if(me)new Settings(c).prefs.edit().putString("myVoiceName",n).apply();
        Diagnostics.event("setting_changed",null,"action",me?"my_voice":"known_voice","result","added","count",list(c).size());
        return v;
    }
    static boolean rename(Context c,String id,String name){
        String n=clean(name);if(n.isEmpty()||!validId(id))return false;
        synchronized(LOCK){List<Voice> all=load(c);Voice v=find(all,id);if(v==null)return false;all.set(all.indexOf(v),v.with(n,v.use));if(!trySave(c,all))return false;}
        if(ME_ID.equals(id))new Settings(c).prefs.edit().putString("myVoiceName",n).apply();
        return true;
    }
    /** Usar (o no) esta voz al transcribir, sin borrarla. */
    static boolean setUse(Context c,String id,boolean use){
        if(!validId(id))return false;
        synchronized(LOCK){List<Voice> all=load(c);Voice v=find(all,id);if(v==null)return false;all.set(all.indexOf(v),v.with(v.name,use));if(!trySave(c,all))return false;}
        Diagnostics.event("setting_changed",null,"action",ME_ID.equals(id)?"my_voice":"known_voice","result",use?"used":"unused");
        return true;
    }
    /** Borra la voz: su muestra y su entrada. Las transcripciones ya hechas no cambian. */
    static boolean remove(Context c,String id){
        if(!validId(id))return false;boolean existed;
        synchronized(LOCK){
            List<Voice> all=load(c);Voice v=find(all,id);File f=file(c,id);existed=v!=null||f.exists();
            f.delete();new File(f.getPath()+".tmp").delete();
            if(v!=null){all.remove(v);trySave(c,all);}
        }
        if(existed)Diagnostics.event("setting_changed",null,"action",ME_ID.equals(id)?"my_voice":"known_voice","result","deleted");
        return existed;
    }
    /** Nueva muestra para una voz guardada (se MUEVE clip). Cambia la huella: lo transcrito con la anterior no se reutiliza. */
    static void replaceAudio(Context c,String id,File clip)throws IOException{
        if(clip==null||!valid(clip))throw new IOException("La muestra está vacía");
        synchronized(LOCK){if(find(load(c),id)==null)throw new FileNotFoundException("Voz no encontrada");place(clip,file(c,id));}
        Diagnostics.event("setting_changed",null,"action",ME_ID.equals(id)?"my_voice":"known_voice","result","recorded");
    }

    /**
     * Muestras que van en cada envío al separar voces: {nombre enviado, data URL, voz destino, descripción}, tu voz
     * primero y a lo más {@link Transcriber#MAX_KNOWN}. Tu voz va como "voz_propia" → "voice:me"; las demás como
     * "voz_<id>" → "voice:<id>".
     */
    static List<String[]> references(Context c){
        List<String[]> out=new ArrayList<>();
        for(Voice v:used(c)){if(out.size()>=Transcriber.MAX_KNOWN)break;String[] ref=reference(c,v);if(ref!=null)out.add(ref);}
        return out;
    }
    private static String[] reference(Context c,Voice v){
        try{byte[] data=java.nio.file.Files.readAllBytes(file(c,v.id).toPath());return new String[]{v.sentName(),"data:audio/mp4;base64,"+android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP),v.target(),v.label()};}
        catch(Exception e){return null;}
    }
    /**
     * Huella de las voces que se usan (id, tamaño y fecha de cada muestra): si cambia, los bloques ya transcritos con
     * otras muestras no se reutilizan. Con solo tu voz queda igual que en la 0.5 (una transcripción en curso al
     * actualizar la app no pierde las partes ya listas). "none" si no se usa ninguna.
     */
    static String fingerprint(Context c){
        List<Voice> used=used(c);if(used.isEmpty())return "none";
        if(used.size()==1&&used.get(0).me){File f=file(c,ME_ID);return f.length()+"-"+f.lastModified();}
        StringBuilder b=new StringBuilder();
        for(Voice v:used){File f=file(c,v.id);if(b.length()>0)b.append(',');b.append(v.id).append(':').append(f.length()).append('-').append(f.lastModified());}
        return b.toString();
    }
    /** «a ti y a Fran», «a Fran, a Pedro y a Ana» (para los textos que dicen a quién reconoce). */
    static String people(List<Voice> voices){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<voices.size();i++){if(i>0)b.append(i==voices.size()-1?" y ":", ");b.append("a ").append(voices.get(i).me?"ti":voices.get(i).name);}
        return b.toString();
    }

    // ---------- Índice ----------
    /** Entradas del índice (con migración desde «Mi voz» de la 0.5). Llamar con LOCK tomado. */
    private static List<Voice> load(Context c){
        List<Voice> out=new ArrayList<>();AtomicFile index=new AtomicFile(index(c));
        try{
            JSONArray a=new JSONArray(new String(index.readFully(),StandardCharsets.UTF_8));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id");if(!validId(id)||find(out,id)!=null)continue;
                String name=clean(o.optString("name"));boolean me=ME_ID.equals(id);if(name.isEmpty())name=me?"Yo":"Voz guardada";
                out.add(new Voice(id,name,me,o.optLong("createdAt",0),o.optBoolean("use",true)));
            }
        }catch(FileNotFoundException none){/* primera vez */}catch(Exception ignored){}
        // Migración: en la 0.5, «Mi voz» era solo voices/me.m4a con el nombre en Ajustes.
        File mine=file(c,ME_ID);
        if(find(out,ME_ID)==null&&valid(mine)){
            String n=prefsName(c);out.add(0,new Voice(ME_ID,n.isEmpty()?"Yo":clean(n),true,mine.lastModified(),true));
            if(trySave(c,out))Diagnostics.event("setting_changed",null,"action","my_voice","result","migrated");
        }
        return out;
    }
    /** Escritura atómica del índice. Las entradas sin muestra se descartan. Llamar con LOCK tomado. */
    private static void save(Context c,List<Voice> all)throws IOException{
        JSONArray a=new JSONArray();
        try{for(Voice v:all)if(valid(file(c,v.id)))a.put(v.json());}catch(JSONException e){throw new IOException(e);}
        AtomicFile f=new AtomicFile(index(c));FileOutputStream out=null;
        try{out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(IOException e){if(out!=null)f.failWrite(out);throw e;}
    }
    private static boolean trySave(Context c,List<Voice> all){try{save(c,all);return true;}catch(IOException e){return false;}}
    private static Voice find(List<Voice> all,String id){for(Voice v:all)if(v.id.equals(id))return v;return null;}
    private static void sort(List<Voice> all){
        java.text.Collator collator=java.text.Collator.getInstance(new Locale("es","CL"));collator.setStrength(java.text.Collator.SECONDARY);
        all.sort((a,b)->a.me!=b.me?(a.me?-1:1):collator.compare(a.name,b.name)!=0?collator.compare(a.name,b.name):Long.compare(a.createdAt,b.createdAt));
    }
    private static String newId(Context c,List<Voice> all){
        while(true){
            StringBuilder b=new StringBuilder();for(int i=0;i<8;i++)b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String id=b.toString();if(find(all,id)==null&&!file(c,id).exists())return id;
        }
    }
    /** Mueve clip a target pasando por un temporal (nunca queda una muestra a medias). */
    private static void place(File clip,File target)throws IOException{
        File tmp=new File(target.getParentFile(),target.getName()+".tmp");tmp.delete();
        if(!clip.renameTo(tmp)){java.nio.file.Files.copy(clip.toPath(),tmp.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);clip.delete();}
        if(!tmp.renameTo(target)){target.delete();if(!tmp.renameTo(target)){tmp.delete();throw new IOException("No se pudo guardar la muestra");}}
        // La huella usa la fecha del archivo: una muestra nueva siempre cuenta como cambio.
        target.setLastModified(System.currentTimeMillis());
    }
}
