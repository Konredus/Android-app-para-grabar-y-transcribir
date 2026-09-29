package cl.vozlocal.app;

import android.app.Service;
import android.content.*;
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
 *
 * 0.4.4: algunos teléfonos (vivo) congelan la app con la pantalla bloqueada y cortan la conexión. Se mantiene
 * el Wi-Fi despierto, un corte del teléfono se reintenta pronto y sin gastar intentos, y al encender la pantalla
 * se reintenta de inmediato.
 */
public class TranscribeService extends Service {
    static volatile boolean running;
    /** Conexión en curso, para poder cancelar un envío al instante desde la pantalla de detalle. */
    static volatile HttpApi current;
    /** Esperas entre reintentos dentro del servicio (ms). */
    private static final long[] BACKOFF={20_000,60_000,120_000,300_000};
    /** Tras un corte del propio teléfono se reintenta pronto: no es un problema del proveedor. */
    private static final long LOCAL_CUT_DELAY=15_000;
    private volatile HttpApi http;private PowerManager.WakeLock wake;private android.net.wifi.WifiManager.WifiLock wifi;private volatile boolean nudged;
    /** Pantalla encendida o desbloqueada: buen momento para reintentar (Android deja de frenar la app). */
    private final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){nudged=true;}};
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(running)return START_NOT_STICKY;
        try{
            android.app.Notification n=Transcriber.build(this,"Preparando…",true,-1);
            if(Build.VERSION.SDK_INT>=29)startForeground(Transcriber.NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(Transcriber.NOTIFICATION,n);
        }catch(RuntimeException e){Diagnostics.event("transcribe_fgs_failed",null,"error_class",e.getClass().getSimpleName());Pipeline.schedule(this,true);stopSelf();return START_NOT_STICKY;}
        running=true;http=new HttpApi();current=http;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VozLocal:Transcribe");wake.acquire(3*60*60*1000L);
        // Mantiene el Wi-Fi despierto con la pantalla apagada mientras se transcribe (se libera al terminar).
        try{android.net.wifi.WifiManager wm=getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);if(wm!=null){wifi=wm.createWifiLock(Build.VERSION.SDK_INT>=29?android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY:android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"VozLocal:Transcribe");wifi.setReferenceCounted(false);wifi.acquire();}}catch(RuntimeException ignored){wifi=null;}
        IntentFilter screenFilter=new IntentFilter(Intent.ACTION_SCREEN_ON);screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,screenFilter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,screenFilter);
        new Thread(()->{
            boolean retry=true;
            // Si una tarea de fondo estaba trabajando, se detiene y este servicio toma el relevo (sin perder bloques listos).
            getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);
            boolean locked=false;try{locked=Transcriber.RUNNING.tryLock(60,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            if(locked){
                try{
                    // Los cortes del propio teléfono no avanzan la escala de esperas; el tope de vueltas evita un bucle eterno.
                    int round=0;
                    for(int loop=0;loop<40&&!http.cancelled;loop++){
                        Transcriber t=new Transcriber(this,http,0);retry=t.runAll();Diagnostics.event("runner_round",null,"runner","fgs","count",round,"result",retry);
                        if(!retry||http.cancelled||!Pipeline.pending(this)||round==BACKOFF.length)break;
                        if(!waitBeforeRetry(t.sawLocalCut?LOCAL_CUT_DELAY:BACKOFF[round++]))break;
                    }
                }finally{Transcriber.RUNNING.unlock();}
            }
            boolean again=retry&&!http.cancelled;
            new Handler(Looper.getMainLooper()).post(()->{running=false;current=null;releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);if(again)Pipeline.schedule(this,true);stopSelf();});
        },"VozLocal-transcribe-fg").start();
        return START_NOT_STICKY;
    }
    /** Espera antes de reintentar; si falta red/cargador, espera hasta 15 min a que vuelva. Devuelve false si hay que ceder. */
    private boolean waitBeforeRetry(long delay){
        String id=pendingId();String blocker=Pipeline.blocker(this);
        if(id!=null)Pipeline.log(this,id,blocker==null?"Reintento en "+(delay/1000)+" s · sigue en primer plano":"En pausa: "+blocker+" · se retoma sola al cumplirse");
        // elapsedRealtime sigue contando si Android congela la app: así se detecta (y se anota) una espera que se alargó.
        long start=SystemClock.elapsedRealtime(),until=start+(blocker==null?delay:15*60_000),last=start;nudged=false;
        while(SystemClock.elapsedRealtime()<until&&!http.cancelled){
            SystemClock.sleep(1_000);long now=SystemClock.elapsedRealtime();
            if(now-last>30_000&&id!=null){Pipeline.log(this,id,"Android tuvo la app congelada "+Recording.time(now-last)+" (ahorro de batería con la pantalla bloqueada)");Diagnostics.event("app_frozen",id,"elapsed_ms",now-last,"display",Battery.screenOn(this)?"on":"off","battery",Battery.unrestricted(this)?"unrestricted":"optimized");}
            last=now;
            if(blocker!=null&&Pipeline.blocker(this)==null)return true;
            if(nudged&&Pipeline.blocker(this)==null){if(id!=null)Pipeline.log(this,id,"Pantalla encendida · se reintenta ahora");return true;}
        }
        return !http.cancelled&&Pipeline.blocker(this)==null;
    }
    private String pendingId(){for(Recording r:Recording.list(this))if(FilesStore.state(this,r.id).optBoolean("requested"))return r.id;return null;}
    @Override public void onDestroy(){HttpApi h=http;if(running&&h!=null)h.cancel();running=false;releaseLocks();super.onDestroy();}
    private void releaseLocks(){
        if(wake!=null&&wake.isHeld())wake.release();
        if(wifi!=null&&wifi.isHeld())wifi.release();
        try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}
    }
    /** Android 15 limita el tiempo de dataSync; al agotarse, se cede a la tarea diferida sin perder bloques ya listos. */
    @Override public void onTimeout(int startId,int fgsType){HttpApi h=http;if(h!=null)h.cancel();Pipeline.schedule(this,true);stopSelf();}
}
