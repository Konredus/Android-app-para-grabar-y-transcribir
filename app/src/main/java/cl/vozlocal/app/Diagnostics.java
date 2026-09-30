package cl.vozlocal.app;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Structured local events. Callers may only pass identifiers/enums/numbers, never user content. */
final class Diagnostics {
    private static Context app; private static String version="desconocida";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static void init(Context c){app=c.getApplicationContext();try{android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);version=info.versionName+" ("+info.versionCode+")";}catch(Exception ignored){}}
    static void event(String event,String job,Object... fields){
        if(app==null)return;
        Context context=app;
        IO.execute(()->{try{
            JSONObject row=new JSONObject().put("time",System.currentTimeMillis()).put("event",event).put("version",version).put("job",job==null?"":job);
            for(int i=0;i+1<fields.length;i+=2){String key=String.valueOf(fields[i]);Object value=fields[i+1];if((key.equals("action")||key.equals("label"))&&value instanceof String)value=safeAction((String)value);if(Arrays.asList("stage","provider","model","http","request_id","code","type","param","elapsed_ms","bytes","duration_ms","part","parts","action","screen","result","error_class","count","runner","net","reason","local","display","idle","battery","mode","kind","source","label","cost","format","anchors","matched").contains(key))row.put(key,value);}
            File dir=new File(context.getFilesDir(),"diagnostics");dir.mkdirs();File file=new File(dir,"events.jsonl"),old=new File(dir,"events.previous.jsonl");
            if(file.length()>2*1024*1024){old.delete();file.renameTo(old);}
            if(old.exists() && old.lastModified()<System.currentTimeMillis()-30L*86400000)old.delete();
            if(file.exists() && file.lastModified()<System.currentTimeMillis()-30L*86400000)file.delete();
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((row+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}});
    }
    static void crash(Throwable error){if(app==null)return;try{JSONObject row=new JSONObject().put("time",System.currentTimeMillis()).put("error_class",error.getClass().getName());org.json.JSONArray frames=new org.json.JSONArray();for(StackTraceElement frame:error.getStackTrace())if(frame.getClassName().startsWith("cl.vozlocal.app."))frames.put(frame.toString());row.put("app_frames",frames);FilesStore.write(new File(app.getFilesDir(),"last-crash.json"),row);}catch(Exception ignored){}}
    static File export(Context c)throws Exception{
        return IO.submit(()->{
            File export=new File(c.getCacheDir(),"support.txt");StringBuilder out=new StringBuilder("VOZ LOCAL — INFORME DE SOPORTE\nVersión: "+version+"\nAndroid API: "+Build.VERSION.SDK_INT+"\n"+device(c)+"\nNo incluye claves, rutas, títulos, audio ni transcripciones.\n\n");
            Map<String,Integer> counts=new TreeMap<>();StringBuilder events=new StringBuilder();
            for(String name:new String[]{"events.previous.jsonl","events.jsonl"}){
                File file=new File(c.getFilesDir(),"diagnostics/"+name);if(!file.exists())continue;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){try{JSONObject row=new JSONObject(line);if(row.optLong("time")<System.currentTimeMillis()-30L*86400000)continue;String label=row.optString("event")+" "+row.optString("action",row.optString("screen",""));counts.put(label,counts.getOrDefault(label,0)+1);events.append(row).append('\n');}catch(Exception ignored){}}}
            }
            File crash=new File(c.getFilesDir(),"last-crash.json");if(crash.exists()&&crash.lastModified()>System.currentTimeMillis()-30L*86400000)out.append("ÚLTIMO FALLO\n").append(FilesStore.read(crash)).append("\n\n");out.append(timelines(c));out.append("FRECUENCIA DE EVENTOS\n");for(Map.Entry<String,Integer> count:counts.entrySet())out.append(count.getKey()).append(": ").append(count.getValue()).append('\n');
            out.append("\nSECUENCIA TÉCNICA\n").append(events);try(FileOutputStream stream=new FileOutputStream(export)){stream.write(out.toString().getBytes(StandardCharsets.UTF_8));}return export;
        }).get(15,TimeUnit.SECONDS);
    }
    /** Datos del teléfono que afectan el trabajo en segundo plano (sin datos personales). */
    private static String device(Context c){
        StringBuilder d=new StringBuilder("Teléfono: "+Build.MANUFACTURER+" "+Build.MODEL+"\n");
        try{android.os.PowerManager pm=c.getSystemService(android.os.PowerManager.class);d.append("Optimización de batería para Verbapp: ").append(pm.isIgnoringBatteryOptimizations(c.getPackageName())?"desactivada (sin restricciones)":"activada (Android puede frenar el trabajo de fondo)").append("\n");}catch(Exception ignored){}        try{android.app.ActivityManager am=c.getSystemService(android.app.ActivityManager.class);if(Build.VERSION.SDK_INT>=28)d.append("Segundo plano restringido: ").append(am.isBackgroundRestricted()?"sí":"no").append("\n");}catch(Exception ignored){}
        try{d.append("Red al generar el informe: ").append(Pipeline.networkName(c)).append("\n");}catch(Exception ignored){}
        return d.toString();
    }
    /**
     * Bitácoras de las últimas 5 grabaciones con actividad de transcripción (identificador corto, sin títulos). La
     * bitácora en pantalla puede nombrar personas; aquí se tapan con «…» el título, los nombres de las voces conocidas,
     * los nombres puestos en la transcripción y todo texto entre « » (ver {@link #redact}).
     */
    private static String timelines(Context c){
        StringBuilder out=new StringBuilder("BITÁCORAS DE TRANSCRIPCIÓN (últimas 5)\n");
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("dd/MM HH:mm:ss",java.util.Locale.ROOT);
        List<Object[]> found=new ArrayList<>();
        for(Recording r:Recording.list(c)){JSONObject st=FilesStore.state(c,r.id);org.json.JSONArray log=st.optJSONArray("log");if(log==null||log.length()==0)continue;JSONObject last=log.optJSONObject(log.length()-1);found.add(new Object[]{last==null?0L:last.optLong("t"),r,st,log});}
        found.sort((a,b)->Long.compare((Long)b[0],(Long)a[0]));
        List<String> voices=new ArrayList<>();try{for(Voices.Voice v:Voices.list(c))voices.add(v.name);}catch(Exception ignored){}
        for(Object[] item:found.subList(0,Math.min(5,found.size()))){
            Recording r=(Recording)item[1];JSONObject st=(JSONObject)item[2];org.json.JSONArray log=(org.json.JSONArray)item[3];
            List<String> hide=new ArrayList<>(voices);hide.add(r.title);hide.add(Notes.heading(r.title));hide.addAll(speakerNames(c,r.id));
            out.append("\n· Grabación ").append(r.id,0,8).append(" · audio ").append(Recording.time(r.duration)).append(" · modelo ").append(st.optString("model","?")).append(" · voces ").append(st.optBoolean("speakers")?"sí":"no")
               .append(" · bloques ").append(st.optInt("blocksDone")).append('/').append(st.optInt("blocks")).append(" · reintentos ").append(st.optInt("retries",st.optInt("attempts"))).append(" (cortes del teléfono ").append(st.optInt("localCuts")).append(')').append(" · estado ").append(st.optBoolean("requested")?"en proceso":st.optBoolean("failed")?"error":"terminado").append("\n");
            if(st.has("doneIn"))out.append("  tiempo total ").append(Recording.time(st.optLong("doneIn"))).append("\n");
            for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null)out.append("  ").append(f.format(new Date(e.optLong("t")))).append("  ").append(redact(e.optString("m"),hide)).append("\n");}
        }
        return out.append("\n").toString();
    }
    /** Textos fijos de la app que sí pueden ir entre « » en el informe (qué alternativa de «Volver a transcribir» se usó). */
    private static Set<String> fixedQuotes(){Set<String> out=new HashSet<>();for(Retranscribe.Mode m:Retranscribe.Mode.values())out.add(Retranscribe.label(m));return out;}
    private static final java.util.regex.Pattern QUOTED=java.util.regex.Pattern.compile("«([^»]*)»");
    /**
     * Línea de bitácora para el informe: el texto entre « » pasa a «…» (salvo los textos fijos de la app) y cada texto
     * de hide (títulos y nombres, sin distinguir mayúsculas, como palabra completa) también.
     */
    static String redact(String line,Collection<String> hide){
        if(line==null||line.isEmpty())return "";
        java.util.regex.Matcher m=QUOTED.matcher(line);StringBuffer quoted=new StringBuffer();Set<String> keep=fixedQuotes();
        while(m.find())m.appendReplacement(quoted,java.util.regex.Matcher.quoteReplacement(keep.contains(m.group(1))?m.group():"…"));
        m.appendTail(quoted);String out=quoted.toString();
        List<String> names=new ArrayList<>();if(hide!=null)for(String h:hide){String t=h==null?"":h.trim();if(t.length()>=2&&!names.contains(t))names.add(t);}
        names.sort((a,b)->b.length()-a.length());
        for(String name:names)out=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])"+java.util.regex.Pattern.quote(name)+"(?![\\p{L}\\p{N}])",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(out).replaceAll("…");
        return out;
    }
    /** Nombres que el usuario puso a las voces de una grabación (versión actual y anterior sin elegir). */
    private static List<String> speakerNames(Context c,String id){
        List<String> out=new ArrayList<>();
        for(String suffix:new String[]{".transcript.json",".transcript.prev.json"}){
            try{File file=FilesStore.file(c,id,suffix);if(!file.isFile())continue;JSONObject names=FilesStore.read(file).optJSONObject("names");
                if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();){String n=names.optString(it.next(),"").trim();if(!n.isEmpty())out.add(n);}}
            catch(Exception ignored){}
        }
        return out;
    }
    static void clear(Context c){IO.execute(()->{new File(c.getFilesDir(),"last-crash.json").delete();new File(c.getCacheDir(),"support.txt").delete();new File(c.getFilesDir(),"diagnostics/events.jsonl").delete();new File(c.getFilesDir(),"diagnostics/events.previous.jsonl").delete();});}

    // ---------- Etiquetas de botones sin datos personales ----------
    /** Palabras frecuentes que aparecen en títulos sin decir nada de nadie (no se tapan en las etiquetas). */
    private static final Set<String> STOPWORDS=new HashSet<>(Arrays.asList("con","del","las","los","por","para","una","uno","unos","unas","que","sin","sobre",
        "entre","hasta","desde","como","mas","pero","muy","este","esta","esto","estos","estas","ese","esa","eso","sus","tus","mis","nos","les","hay","fue","son","ser","cuando","donde","todo","todos","otra","otro"));
    /** Palabras de los títulos, de las voces conocidas y de los nombres de voces de cada transcripción (en minúsculas, sin tildes). */
    private static volatile Set<String> personalWords=Collections.emptySet();private static long personalAt;
    /** Nombres por grabación leídos de su transcripción, con la marca (tamaño y fecha) de los archivos: solo se relee lo que cambió. */
    private static final Map<String,Object[]> NAMES=new HashMap<>();
    static final long PERSONAL_REFRESH_MS=60_000;
    /** Minúsculas y sin tildes: «Reunión» y «reunion» son la misma palabra. */
    static String fold(String s){return java.text.Normalizer.normalize(s==null?"":s.toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");}
    /** Palabras de 3 o más letras (sin las frecuentes) de estos textos. */
    static Set<String> tokens(Collection<String> texts){Set<String> out=new HashSet<>();if(texts!=null)for(String t:texts)addTokens(out,t);return out;}
    private static void addTokens(Set<String> out,String text){if(text==null)return;for(String w:fold(text).split("[^\\p{L}\\p{N}]+"))if(w.length()>=3&&!STOPWORDS.contains(w))out.add(w);}
    /** Palabras personales de este teléfono, recién leídas (sin caché): títulos escritos por la persona, voces y nombres. */
    static Set<String> personalTokens(Context c){
        Set<String> out=new HashSet<>(),seen=new HashSet<>();
        for(Recording r:Recording.list(c)){
            seen.add(r.id);
            if(!Notes.isDefaultTitle(r.title,r.created))addTokens(out,r.title);
            List<String> names;
            synchronized(NAMES){
                File now=FilesStore.file(c,r.id,".transcript.json"),prev=FilesStore.file(c,r.id,".transcript.prev.json");
                String stamp=now.length()+":"+now.lastModified()+"|"+prev.length()+":"+prev.lastModified();Object[] cached=NAMES.get(r.id);
                if(cached!=null&&stamp.equals(cached[0])){@SuppressWarnings("unchecked") List<String> list=(List<String>)cached[1];names=list;}
                else{names=speakerNames(c,r.id);NAMES.put(r.id,new Object[]{stamp,names});}
            }
            for(String n:names)addTokens(out,n);
        }
        synchronized(NAMES){NAMES.keySet().retainAll(seen);}
        try{for(Voices.Voice v:Voices.list(c))addTokens(out,v.name);}catch(Exception ignored){}
        return out;
    }
    /** Las palabras personales en caché (se rehacen a lo más cada 60 s; se llama desde el hilo de registro). */
    private static Set<String> cachedPersonal(){
        Context c=app;if(c==null)return personalWords;
        synchronized(Diagnostics.class){
            long now=android.os.SystemClock.elapsedRealtime();
            if(personalAt==0||now-personalAt>=PERSONAL_REFRESH_MS){try{personalWords=personalTokens(c);}catch(Exception ignored){}personalAt=now;}
            return personalWords;
        }
    }
    /**
     * Etiquetas de botones sin datos personales. Desde la primera palabra que puede ser personal, el resto se reemplaza por
     * «…»: palabras de un título, de una voz conocida o de un nombre de voz (también en minúsculas, en cualquier posición,
     * incluido el título que se está grabando) y, después de la primera palabra, las que empiezan con mayúscula o «, o
     * tienen números. «Guardar la voz de Fran» → «Guardar la voz de …»; «Opciones de reunión con el abogado» → «Opciones de …».
     */
    static String safeAction(String action){
        Set<String> known=cachedPersonal();String live=RecorderService.activeTitle;
        if(live!=null&&!live.trim().isEmpty()){known=new HashSet<>(known);addTokens(known,live);}
        return safeAction(action,known);
    }
    /** Igual que {@link #safeAction(String)} con un conjunto de palabras personales dado (pruebas). */
    static String safeAction(String action,Set<String> known){
        String[] words=(action==null?"":action).trim().split("\\s+");StringBuilder out=new StringBuilder();
        for(int i=0;i<words.length;i++){String w=words[i];if(w.isEmpty())continue;
            boolean personal=(i>0&&(Character.isUpperCase(w.codePointAt(0))||w.matches(".*\\d.*")||w.startsWith("«")))||mentions(w,known);
            out.append(out.length()==0?"":" ").append(personal?"…":w);
            if(personal)break;
        }
        String s=out.toString();return s.length()>48?s.substring(0,48):s;
    }
    /** ¿La palabra (o una de sus partes: «reunión,» → «reunion») es una palabra personal? */
    private static boolean mentions(String word,Set<String> known){
        if(known==null||known.isEmpty())return false;
        for(String piece:fold(word).split("[^\\p{L}\\p{N}]+"))if(piece.length()>=3&&known.contains(piece))return true;
        return false;
    }
}
