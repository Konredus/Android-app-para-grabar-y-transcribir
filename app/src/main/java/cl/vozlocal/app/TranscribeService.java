package cl.vozlocal.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

/**
 * Transcripción en primer plano: con notificación visible, Android no la pausa al bloquear la pantalla
 * (a diferencia de una tarea de fondo, que entra en ahorro de energía). Se inicia cuando el usuario lo pide
 * y las condiciones (red/cargador) ya se cumplen; si no, Pipeline deja una tarea diferida.
 */
public class TranscribeService extends Service {
    static volatile boolean running;
    /** Conexión en curso, para poder cancelar un envío al instante desde la pantalla de detalle. */
    static volatile HttpApi current;
    private volatile HttpApi http;private PowerManager.WakeLock wake;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(running)return START_NOT_STICKY;
        try{
            android.app.Notification n=Transcriber.build(this,"Preparando…",true,-1);
            if(Build.VERSION.SDK_INT>=29)startForeground(Transcriber.NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(Transcriber.NOTIFICATION,n);
        }catch(RuntimeException e){Diagnostics.event("transcribe_fgs_failed",null,"error_class",e.getClass().getSimpleName());Pipeline.schedule(this,true);stopSelf();return START_NOT_STICKY;}
        running=true;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VozLocal:Transcribe");wake.acquire(3*60*60*1000L);
        new Thread(()->{
            boolean retry=false;
            if(Transcriber.RUNNING.tryLock()){
                try{getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);http=new HttpApi();current=http;
                    // Si mientras tanto se pidió otra transcripción, se atiende en la misma sesión (máx. 3 rondas).
                    int rounds=0;do{retry=new Transcriber(this,http,0).runAll();rounds++;}while(retry&&!http.cancelled&&rounds<3&&Pipeline.hasFresh(this)&&Pipeline.blocker(this)==null);}
                finally{Transcriber.RUNNING.unlock();}
            }else retry=true;
            boolean again=retry;
            new Handler(Looper.getMainLooper()).post(()->{running=false;current=null;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);if(again)Pipeline.schedule(this,true);stopSelf();});
        },"VozLocal-transcribe-fg").start();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){HttpApi h=http;if(running&&h!=null)h.cancel();running=false;if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    /** Android 15 limita el tiempo de dataSync; al agotarse, se cede a la tarea diferida sin perder bloques ya listos. */
    @Override public void onTimeout(int startId,int fgsType){HttpApi h=http;if(h!=null)h.cancel();Pipeline.schedule(this,true);stopSelf();}
}
