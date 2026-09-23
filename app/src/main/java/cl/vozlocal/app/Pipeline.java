package cl.vozlocal.app;

import android.app.job.*;
import android.content.*;
import android.net.*;
import android.os.BatteryManager;
import org.json.*;

final class Pipeline {
    static final int JOB_ID=4102;
    static void request(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction("El ejemplo no se envía a la API.");
        if(Transcript.exists(c,id)){LocalStorage.enqueue(c,id);return;}
        FilesStore.update(c,id,s -> s.put("requested",true).put("failed",false).put("attempts",0).put("queuedAt",System.currentTimeMillis()).put("log",new JSONArray()).put("blocksDone",0).put("blocks",0).put("upSent",0).put("upTotal",0).remove("lastError"));
        Diagnostics.event("job_queued",id);
        String blocker=blocker(c);
        log(c,id,blocker==null?"En cola · empezando":"En cola · "+blocker);
        if(blocker!=null||!startForeground(c))schedule(c,true);
    }
    static void afterRecording(Context c,String id){LocalStorage.enqueue(c,id);Diagnostics.event("audio_saved",id);try{if(new Settings(c).automatic())request(c,id);}catch(Exception ignored){}}
    static void edited(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo")){FilesStore.version.incrementAndGet();return;}
        LocalStorage.enqueue(c,id);Diagnostics.event("recording_edited",id);
    }
    static boolean pending(Context c){for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("requested"))return true;return false;}
    /** Hay grabaciones pedidas que aún no tuvieron ningún intento (recién encoladas). */
    static boolean hasFresh(Context c){for(Recording r:Recording.list(c)){JSONObject s=FilesStore.state(c,r.id);if(s.optBoolean("requested")&&s.optInt("attempts",0)==0)return true;}return false;}
    static void schedule(Context c,boolean replace){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(replace)scheduler.cancel(JOB_ID);
        if(!pending(c)){if(replace)scheduler.cancel(JOB_ID);return;}
        if(!replace && scheduler.getPendingJob(JOB_ID)!=null)return;
        Settings settings=new Settings(c);
        JobInfo info=jobInfo(c,settings);
        scheduler.schedule(info);
    }
    static JobInfo jobInfo(Context c,Settings settings){
        return new JobInfo.Builder(JOB_ID,new ComponentName(c,PipelineJob.class))
            .setRequiredNetworkType(settings.wifiOnly()?JobInfo.NETWORK_TYPE_UNMETERED:JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(settings.charging()).setRequiresBatteryNotLow(true)
            .setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
    }
    /**
     * Intenta transcribir en primer plano (sigue con el teléfono bloqueado). Android 12+ solo lo permite
     * mientras la app está visible o desde ciertas acciones del usuario; si no se puede, devuelve false.
     */
    static boolean startForeground(Context c){
        if(TranscribeService.running)return true;
        if(!pending(c)||blocker(c)!=null)return false;
        try{c.startForegroundService(new Intent(c,TranscribeService.class));return true;}
        catch(RuntimeException e){Diagnostics.event("transcribe_fgs_denied",null,"error_class",e.getClass().getSimpleName());return false;}
    }
    /** Motivo por el que aún no puede empezar (según tus ajustes), o null si puede empezar ya. */
    static String blocker(Context c){
        Settings settings=new Settings(c);Network network=network(c);
        if(network==null)return "esperando conexión a internet";
        if(settings.wifiOnly()&&!unmetered(c))return "esperando Wi-Fi (ahora usas datos móviles; puedes permitirlos en Ajustes)";
        BatteryManager battery=c.getSystemService(BatteryManager.class);
        if(settings.charging()&&!battery.isCharging())return "esperando que conectes el cargador";
        if(!battery.isCharging()&&battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)<=15)return "batería baja: Android espera a que cargues";
        return null;
    }
    static Network network(Context c){ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)?n:null;}
    static boolean unmetered(Context c){ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);}
    /** Bitácora visible del proceso: cada paso con su hora (máx. 60 entradas). También actualiza el estado actual. */
    static void log(Context c,String id,String message){
        try{FilesStore.update(c,id,s->{JSONArray log=s.optJSONArray("log");if(log==null)log=new JSONArray();log.put(new JSONObject().put("t",System.currentTimeMillis()).put("m",message));while(log.length()>60)log.remove(0);s.put("log",log).put("status",message).put("since",System.currentTimeMillis());});}catch(Exception ignored){}
    }
    static void cancel(Context c,String id)throws Exception{
        Diagnostics.event("job_cancelled",id);FilesStore.update(c,id,s -> s.put("requested",false));log(c,id,"Transcripción cancelada");
        HttpApi active=TranscribeService.current;if(active!=null&&id.equals(active.jobId))active.cancel();
        c.getSystemService(JobScheduler.class).cancel(JOB_ID);schedule(c,true);
    }
}
