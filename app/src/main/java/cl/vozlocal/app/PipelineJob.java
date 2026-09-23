package cl.vozlocal.app;

import android.app.NotificationManager;
import android.app.job.*;
import android.os.*;

/**
 * Tarea diferida: Android la ejecuta cuando se cumplen las condiciones (Wi-Fi, cargador, batería).
 * Primero intenta pasar el trabajo a TranscribeService (primer plano); si Android no lo permite desde
 * segundo plano, transcribe aquí cediendo el turno entre bloques para no exceder el límite de tiempo.
 */
public class PipelineJob extends JobService {
    private volatile HttpApi active;
    @Override public boolean onStartJob(JobParameters params){
        if(TranscribeService.running)return false;
        if(Pipeline.startForeground(this))return false;
        HttpApi http=new HttpApi();active=http;
        new Thread(()->{
            boolean retry;
            if(!Transcriber.RUNNING.tryLock()){new Handler(getMainLooper()).post(()->jobFinished(params,true));return;}
            try{retry=new Transcriber(this,http,Transcriber.JOB_BUDGET_MS).runAll();}
            finally{Transcriber.RUNNING.unlock();}
            boolean again=retry;
            // Se programa una tarea nueva (sin espera exponencial) en vez de pedir reintento con backoff.
            if(!http.cancelled)new Handler(getMainLooper()).post(()->{jobFinished(params,false);if(again)Pipeline.schedule(this,true);});
        },"VozLocal-transcribe").start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        HttpApi h=active;if(h!=null){h.cancel();if(h.jobId!=null)Pipeline.log(this,h.jobId,"Android pausó el trabajo (ahorro de energía, red o límite de tiempo) · se reanudará");}
        getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);Diagnostics.event("job_interrupted",null);return true;
    }
    static String hash(byte[] data)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    static String safeName(String title){return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");}
}
