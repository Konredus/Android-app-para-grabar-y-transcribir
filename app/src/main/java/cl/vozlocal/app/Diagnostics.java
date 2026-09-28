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
            for(int i=0;i+1<fields.length;i+=2){String key=String.valueOf(fields[i]);if(Arrays.asList("stage","provider","model","http","request_id","code","type","param","elapsed_ms","bytes","duration_ms","part","parts","action","screen","result","error_class","count","runner","net","reason","local","display","idle","battery").contains(key))row.put(key,fields[i+1]);}
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
        try{android.os.PowerManager pm=c.getSystemService(android.os.PowerManager.class);d.append("Optimización de batería para Voz local: ").append(pm.isIgnoringBatteryOptimizations(c.getPackageName())?"desactivada (sin restricciones)":"activada (Android puede frenar el trabajo de fondo)").append("\n");}catch(Exception ignored){}        try{android.app.ActivityManager am=c.getSystemService(android.app.ActivityManager.class);if(Build.VERSION.SDK_INT>=28)d.append("Segundo plano restringido: ").append(am.isBackgroundRestricted()?"sí":"no").append("\n");}catch(Exception ignored){}
        try{d.append("Red al generar el informe: ").append(Pipeline.networkName(c)).append("\n");}catch(Exception ignored){}
        return d.toString();
    }
    /** Bitácoras de las últimas 5 grabaciones con actividad de transcripción (identificador corto, sin títulos). */
    private static String timelines(Context c){
        StringBuilder out=new StringBuilder("BITÁCORAS DE TRANSCRIPCIÓN (últimas 5)\n");
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("dd/MM HH:mm:ss",java.util.Locale.ROOT);
        List<Object[]> found=new ArrayList<>();
        for(Recording r:Recording.list(c)){JSONObject st=FilesStore.state(c,r.id);org.json.JSONArray log=st.optJSONArray("log");if(log==null||log.length()==0)continue;JSONObject last=log.optJSONObject(log.length()-1);found.add(new Object[]{last==null?0L:last.optLong("t"),r,st,log});}
        found.sort((a,b)->Long.compare((Long)b[0],(Long)a[0]));
        for(Object[] item:found.subList(0,Math.min(5,found.size()))){
            Recording r=(Recording)item[1];JSONObject st=(JSONObject)item[2];org.json.JSONArray log=(org.json.JSONArray)item[3];
            out.append("\n· Grabación ").append(r.id,0,8).append(" · audio ").append(Recording.time(r.duration)).append(" · modelo ").append(st.optString("model","?")).append(" · voces ").append(st.optBoolean("speakers")?"sí":"no")
               .append(" · bloques ").append(st.optInt("blocksDone")).append('/').append(st.optInt("blocks")).append(" · reintentos ").append(st.optInt("retries",st.optInt("attempts"))).append(" (cortes del teléfono ").append(st.optInt("cuts")).append(')').append(" · estado ").append(st.optBoolean("requested")?"en proceso":st.optBoolean("failed")?"error":"terminado").append("\n");
            if(st.has("doneIn"))out.append("  tiempo total ").append(Recording.time(st.optLong("doneIn"))).append("\n");
            for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null)out.append("  ").append(f.format(new Date(e.optLong("t")))).append("  ").append(e.optString("m")).append("\n");}
        }
        return out.append("\n").toString();
    }
    static void clear(Context c){IO.execute(()->{new File(c.getFilesDir(),"last-crash.json").delete();new File(c.getCacheDir(),"support.txt").delete();new File(c.getFilesDir(),"diagnostics/events.jsonl").delete();new File(c.getFilesDir(),"diagnostics/events.previous.jsonl").delete();});}
}
