package cl.vozlocal.app;

import android.app.*;
import android.os.Bundle;

public class VozApp extends Application {
    @Override public void onCreate(){super.onCreate();Diagnostics.init(this);Diagnostics.event("app_start",null);Settings settings=new Settings(this);if(settings.prefs.getInt("schema",0)<3){getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);for(Recording r:Recording.list(this))if(Transcript.exists(this,r.id))try{FilesStore.update(this,r.id,state->state.put("requested",false).put("status","Transcripción lista"));}catch(Exception ignored){}settings.prefs.edit().putInt("schema",3).remove("driveConnected").remove("driveEmail").remove("folderId").apply();}
        if(settings.prefs.getInt("schema",0)<4){
            // 0.4.2: el modelo se elige por transcripción (con o sin voces). Se respeta la elección previa de un modelo sin voces.
            String old=settings.prefs.getString("openaiModel","gpt-4o-transcribe-diarize");android.content.SharedPreferences.Editor e=settings.prefs.edit().putInt("schema",4);
            if(!old.equals("gpt-4o-transcribe-diarize")){e.putString("openaiTextModel",old).putString("speakersMode","never");}
            e.apply();
        }
        Thread.UncaughtExceptionHandler prior=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{Diagnostics.crash(error);if(prior!=null)prior.uncaughtException(thread,error);});
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityResumed(Activity a){Diagnostics.event("screen_open",null,"screen",a.getClass().getSimpleName());}
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        });
    }
}
