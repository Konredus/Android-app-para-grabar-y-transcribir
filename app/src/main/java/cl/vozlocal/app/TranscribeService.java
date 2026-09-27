package cl.vozlocal.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.TimeUnit;

/**
 * Transcripción en primer plano: con notificación visible, Android no la pausa al bloquear la pantalla
 * (a diferencia de una tarea de fondo, que entra en ahorro de energía).
 *
 * Lección de la 0.4.2: si un intento fallaba aquí, el reintento pasaba a una tarea de fondo y, con la app
 * cerrada, Android no dejaba volver a primer plano (7 h para un audio de 32 min). Ahora los reintentos
 * ocurren DENTRO de este servicio, con esperas crecientes y esperando la red si se cae.
 */
public class TranscribeService extends Service {
    static volatile boolean running;
    /** Conexión en curso, para poder cancelar un envío al instante desde la pantalla de detalle. */
    static volatile HttpApi current;
    /** Esperas entre reintentos dentro del servicio (ms). */
    private static final long[] BACKOFF={20_000,60_000,120_000,300_000};
    private volatile HttpApi http;private PowerManager.WakeLock wake;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(running)return START_NOT_STICKY;
        try{
            android.app.Notification n=Transcriber.build(this,"Preparando…",true,-1);
            if(Build.VERSION.SDK_INT>=29)startForeground(Transcriber.NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(Transcriber.NOTIFICATION,n);
        }catch(RuntimeException e){Diagnostics.event("transcribe_fgs_failed",null,"error_class",e.getClass().getSimpleName());Pipeline.schedule(this,true);stopSelf();return START_NOT_STICKY;}
        running=true;http=new HttpApi();current=http;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VozLocal:Transcribe");wake.acquire(3*60*60*1000L);
        new Thread(()->{
            boolean retry=true;
            // Si una tarea de fondo estaba trabajando, se detiene y este servicio toma el relevo (sin perder bloques listos).
            getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);
            boolean locked=false;try{locked=Transcriber.RUNNING.tryLock(60,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            if(locked){
                try{
                    for(int round=0;round<=BACKOFF.length&&!http.cancelled;round++){
                        retry=new Transcriber(this,http,0).runAll();Diagnostics.event("runner_round",null,"runner","fgs","count",round,"result",retry);
                        if(!retry||http.cancelled||!Pipeline.pending(this)||round==BACKOFF.length)break;
                        if(!waitBeforeRetry(BACKOFF[round]))break;
                    }
                }finally{Transcriber.RUNNING.unlock();}
            }
            boolean again=retry&&!http.cancelled;
            new Handler(Looper.getMainLooper()).post(()->{running=false;current=null;if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);if(again)Pipeline.schedule(this,true);stopSelf();});
        },"VozLocal-transcribe-fg").start();
        return START_NOT_STICKY;
    }
    /** Espera antes de reintentar; si falta red/cargador, espera hasta 15 min a que vuelva. Devuelve false si hay que ceder. */
    private boolean waitBeforeRetry(long delay){
        String id=pendingId();String blocker=Pipeline.blocker(this);
        if(id!=null)Pipeline.log(this,id,blocker==null?"Reintento en "+(delay/1000)+" s · sigue en primer plano":"En pausa: "+blocker+" · se retoma sola al cumplirse");
        long until=System.currentTimeMillis()+(blocker==null?delay:15*60_000);
        while(System.currentTimeMillis()<until&&!http.cancelled){
            SystemClock.sleep(5_000);
            if(blocker!=null&&Pipeline.blocker(this)==null)return true;
        }
        return !http.cancelled&&Pipeline.blocker(this)==null;
    }
    private String pendingId(){for(Recording r:Recording.list(this))if(FilesStore.state(this,r.id).optBoolean("requested"))return r.id;return null;}
    @Override public void onDestroy(){HttpApi h=http;if(running&&h!=null)h.cancel();running=false;if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    /** Android 15 limita el tiempo de dataSync; al agotarse, se cede a la tarea diferida sin perder bloques ya listos. */
    @Override public void onTimeout(int startId,int fgsType){HttpApi h=http;if(h!=null)h.cancel();Pipeline.schedule(this,true);stopSelf();}
}
