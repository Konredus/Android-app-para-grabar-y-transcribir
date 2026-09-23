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
            for(int i=0;i+1<fields.length;i+=2){String key=String.valueOf(fields[i]);if(Arrays.asList("stage","provider","model","http","request_id","code","type","param","elapsed_ms","bytes","duration_ms","part","parts","action","screen","result","error_class","count").contains(key))row.put(key,fields[i+1]);}
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
            File export=new File(c.getCacheDir(),"support.txt");StringBuilder out=new StringBuilder("VOZ LOCAL — INFORME DE SOPORTE\nVersión: "+version+"\nAndroid API: "+Build.VERSION.SDK_INT+"\n\nNo incluye claves, rutas, títulos, audio ni transcripciones.\n\n");
            Map<String,Integer> counts=new TreeMap<>();StringBuilder events=new StringBuilder();
            for(String name:new String[]{"events.previous.jsonl","events.jsonl"}){
                File file=new File(c.getFilesDir(),"diagnostics/"+name);if(!file.exists())continue;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){try{JSONObject row=new JSONObject(line);if(row.optLong("time")<System.currentTimeMillis()-30L*86400000)continue;String label=row.optString("event")+" "+row.optString("action",row.optString("screen",""));counts.put(label,counts.getOrDefault(label,0)+1);events.append(row).append('\n');}catch(Exception ignored){}}}
            }
            File crash=new File(c.getFilesDir(),"last-crash.json");if(crash.exists()&&crash.lastModified()>System.currentTimeMillis()-30L*86400000)out.append("ÚLTIMO FALLO\n").append(FilesStore.read(crash)).append("\n\n");out.append("FRECUENCIA DE EVENTOS\n");for(Map.Entry<String,Integer> count:counts.entrySet())out.append(count.getKey()).append(": ").append(count.getValue()).append('\n');
            out.append("\nSECUENCIA TÉCNICA\n").append(events);try(FileOutputStream stream=new FileOutputStream(export)){stream.write(out.toString().getBytes(StandardCharsets.UTF_8));}return export;
        }).get(15,TimeUnit.SECONDS);
    }
    static void clear(Context c){IO.execute(()->{new File(c.getFilesDir(),"last-crash.json").delete();new File(c.getCacheDir(),"support.txt").delete();new File(c.getFilesDir(),"diagnostics/events.jsonl").delete();new File(c.getFilesDir(),"diagnostics/events.previous.jsonl").delete();});}
}
