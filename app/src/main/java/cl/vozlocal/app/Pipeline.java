package cl.vozlocal.app;

import android.app.job.*;
import android.content.*;
import org.json.JSONObject;

final class Pipeline {
    static final int JOB_ID=4102;
    static void request(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction("El ejemplo no se envía a OpenAI ni a Drive.");
        FilesStore.update(c,id,s -> s.put("requested",true).put("uploadPending",true).put("attempts",0).put("status","En cola · esperando las condiciones configuradas"));
        schedule(c,true);
    }
    static void afterRecording(Context c,String id){try{if(new Settings(c).automatic())request(c,id);}catch(Exception ignored){}}
    static void edited(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo")){FilesStore.version.incrementAndGet();return;}
        if(Transcript.exists(c,id)){
            boolean automatic=new Settings(c).automatic() && new Settings(c).driveConnected();
            FilesStore.update(c,id,s -> {s.put("uploadPending",true).put("status","Cambios guardados · pendiente de sincronizar");if(automatic)s.put("requested",true).put("attempts",0);});
            schedule(c,false);
        }
    }
    static boolean pending(Context c){for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("requested"))return true;return false;}
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
    static void connected(Context c)throws Exception{
        for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("uploadPending") && !FilesStore.state(c,r.id).optBoolean("demo"))
            FilesStore.update(c,r.id,s -> s.put("requested",true).put("attempts",0).put("status","En cola para Google Drive"));
        schedule(c,true);
    }
    static void cancel(Context c,String id)throws Exception{
        FilesStore.update(c,id,s -> s.put("requested",false).put("status","Procesamiento cancelado"));
        c.getSystemService(JobScheduler.class).cancel(JOB_ID);schedule(c,true);
    }
}
