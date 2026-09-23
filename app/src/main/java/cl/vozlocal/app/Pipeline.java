package cl.vozlocal.app;

import android.app.job.*;
import android.content.*;
import org.json.JSONObject;

final class Pipeline {
    static final int JOB_ID=4102;
    static void request(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction("El ejemplo no se envía a la API.");
        if(Transcript.exists(c,id)){LocalStorage.enqueue(c,id);return;}
        FilesStore.update(c,id,s -> s.put("requested",true).put("failed",false).put("attempts",0).put("status","En cola · esperando las condiciones configuradas"));
        Diagnostics.event("job_queued",id);schedule(c,true);
    }
    static void afterRecording(Context c,String id){LocalStorage.enqueue(c,id);Diagnostics.event("audio_saved",id);try{if(new Settings(c).automatic())request(c,id);}catch(Exception ignored){}}
    static void edited(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo")){FilesStore.version.incrementAndGet();return;}
        LocalStorage.enqueue(c,id);Diagnostics.event("recording_edited",id);
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
    static void cancel(Context c,String id)throws Exception{
        Diagnostics.event("job_cancelled",id);FilesStore.update(c,id,s -> s.put("requested",false).put("status","Procesamiento cancelado"));
        c.getSystemService(JobScheduler.class).cancel(JOB_ID);schedule(c,true);
    }
}
