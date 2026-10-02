package cl.vozlocal.app;

import android.app.Service;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.Collection;
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
 *
 * 0.8.0, tercera ronda: las rondas con reintentos (rounds) las comparte la transferencia iniciada por el usuario de
 * Android 14+ (PipelineJob). Si lo único que queda espera Wi-Fi desde el comienzo de una ronda, no se retiene el servicio:
 * la tarea de fondo lo retoma. Si el Wi-Fi se va a mitad, se espera aquí (hasta 15 min) con el aviso «Esperando Wi-Fi».
 */
public class TranscribeService extends Service {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
    static volatile boolean running;
    /** Conexión en curso, para poder cancelar un envío al instante desde la pantalla de detalle. */
    static volatile HttpApi current;
    /** Esperas entre reintentos dentro del servicio (ms). */
    private static final long[] BACKOFF={20_000,60_000,120_000,300_000};
    /** Tras un corte del propio teléfono se reintenta pronto: no es un problema del proveedor. */
    private static final long LOCAL_CUT_DELAY=15_000;
    private volatile HttpApi http;private PowerManager.WakeLock wake;private android.net.wifi.WifiManager.WifiLock wifi;
    private final java.util.concurrent.atomic.AtomicBoolean nudged=new java.util.concurrent.atomic.AtomicBoolean();
    /** Pantalla encendida o desbloqueada: buen momento para reintentar (Android deja de frenar la app). */
    private final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){nudged.set(true);}};
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
                try{retry=rounds(this,http,"fgs",nudged);}
                finally{Transcriber.RUNNING.unlock();}
            }
            // Tras «Cancelar» (o eliminar) la grabación que se enviaba, lo demás pedido lo retoma la tarea de fondo, como en la
            // transferencia iniciada por el usuario (schedule no hace nada si no queda nada). Antes no se programaba: la tarea
            // que Pipeline.cancel acababa de programar llegaba con este servicio aún andando, se descartaba, y lo demás quedaba
            // pedido sin trabajo hasta abrir una pantalla.
            boolean again=retry||http.cancelled;
            new Handler(Looper.getMainLooper()).post(()->{running=false;current=null;releaseLocks();stopForeground(STOP_FOREGROUND_REMOVE);if(again)Pipeline.schedule(this,true);stopSelf();});
        },"VozLocal-transcribe-fg").start();
        return START_NOT_STICKY;
    }
    /**
     * Rondas de trabajo con reintentos dentro del mismo proceso, con esperas crecientes. Las usan este servicio y la
     * transferencia iniciada por el usuario (PipelineJob, Android 14+). runner: "fgs" o "uij" para Diagnostics.
     * nudged: se pone en true cuando conviene reintentar ya (pantalla encendida). Devuelve true si queda trabajo pendiente.
     * Los cortes del propio teléfono (y el Wi-Fi que se fue a mitad) no avanzan la escala de esperas; el tope de vueltas
     * evita un bucle eterno.
     */
    static boolean rounds(Context c,HttpApi http,String runner,java.util.concurrent.atomic.AtomicBoolean nudged){
        boolean retry=true;int round=0;
        try{
            for(int loop=0;loop<40&&!http.cancelled;loop++){
                Transcriber t=new Transcriber(c,http,0);t.runner=runner;retry=t.runAll();Diagnostics.event("runner_round",null,"runner",runner,"count",round,"result",retry);
                if(!retry||http.cancelled||!Pipeline.pending(c)||round==BACKOFF.length)break;
                // Lo único que queda espera Wi-Fi desde el comienzo de la ronda (y no tiene permiso para datos móviles): no se
                // retiene el trabajo esperando; la tarea de fondo lo retoma sola cuando haya Wi-Fi (o al tocar «Usar datos móviles
                // ahora»). Si el Wi-Fi se fue a mitad (t.lostWifi), en cambio, se espera aquí: ver waitBeforeRetry.
                if(t.onlyWaitingWifi())break;
                // Una ronda detenida por el cargador, la batería o internet (t.held) no falló: no sube la escala de esperas.
                if(!waitBeforeRetry(c,http,t.sawLocalCut||t.lostWifi||t.held?LOCAL_CUT_DELAY:BACKOFF[round++],nudged,t.retrying()))break;
            }
        }finally{Transcriber.retryingId=null;}
        return retry;
    }
    /**
     * La grabación que el trabajo reintenta tras la espera: la primera que la ronda dejó para reintentar (held, en orden) y
     * que sigue lista (ready: pedida y sin nada que la retenga). null si ninguna: entonces nadie dice «Reintento en…» (una
     * recién pedida que espera su turno no se reintenta). Separada del teléfono para poder probarla.
     */
    static String retryTarget(Collection<String> held,java.util.function.Predicate<String> ready){
        for(String id:held)if(ready.test(id))return id;
        return null;
    }
    /**
     * Espera antes de reintentar; si falta red/cargador, espera hasta 15 min a que vuelva. Devuelve false si hay que ceder.
     * Con «Solo con Wi-Fi» y datos móviles (antes o durante la espera) avisa (ver wifiWait) y espera igual: ceder de inmediato
     * dejaba el resto a la tarea de fondo que Android pausa, porque con la app cerrada ni este servicio ni la transferencia
     * iniciada por el usuario se vuelven a abrir. A la transferencia, que exige Wi-Fi, la detiene Android (onStopJob) y la
     * retoma él mismo al volver el Wi-Fi, sin dejar de ser iniciada por el usuario.
     * held: las que la ronda dejó para reintentar (Transcriber.retrying). Mientras se espera para reintentar una (sin nada que
     * la retenga), Transcriber.retryingId la nombra: para las pantallas (Pipeline.processing) se sigue procesando, no está
     * «En cola». Si pasa a esperar Wi-Fi, deja de nombrarla (las pantallas dicen «Esperando Wi-Fi», con «Usar datos móviles»)
     * y su titular lo dice; si se cancela durante la espera, no se escribe más en ella. Si la espera termina con algo que
     * falta (p. ej. se desconectó el cargador), se cede y su titular también lo dice: no queda en «Reintento en 20 s».
     */
    private static boolean waitBeforeRetry(Context c,HttpApi http,long delay,java.util.concurrent.atomic.AtomicBoolean nudged,Collection<String> held){
        try{
            String id=pendingId(c);String blocker=Pipeline.blocker(c);
            String again=blocker==null?retryTarget(held,x->FilesStore.state(c,x).optBoolean("requested")&&Pipeline.blocker(c,x)==null):null;
            Transcriber.retryingId=again;
            // «Reintento en…» solo en la que de verdad se reintenta: antes iba a la primera pedida, aunque nunca se hubiera
            // enviado (una que esperaba su turno, o el Wi-Fi). La que espera su turno conserva su «En cola · …».
            if(blocker!=null){if(id!=null)Pipeline.log(c,id,"En pausa: "+blocker+" · se retoma sola al cumplirse");}
            else if(again!=null)Pipeline.log(c,again,"Reintento en "+(delay/1000)+" s · sigue trabajando");
            if(Pipeline.isWifiWait(blocker))wifiWait(c,http,id);else if(blocker!=null)hold(c,http,blocker);
            boolean wifiOnly=new Settings(c).wifiOnly(),wasMetered=metered(c,wifiOnly);
            // elapsedRealtime sigue contando si Android congela la app: así se detecta (y se anota) una espera que se alargó.
            // due: cuándo toca reintentar según la escala de esperas, aunque el Wi-Fi se vaya y vuelva antes.
            long start=SystemClock.elapsedRealtime(),due=start+(blocker==null?delay:0),until=start+(blocker==null?delay:15*60_000),last=start;nudged.set(false);
            while(SystemClock.elapsedRealtime()<until&&!http.cancelled){
                SystemClock.sleep(1_000);long now=SystemClock.elapsedRealtime();
                // Cancelada durante la espera (Pipeline.cancel limpia retryingId): ya no es la que se reintenta y no se escribe
                // más en ella. Si el envío era de otra, http no se corta y la espera sigue.
                if(again!=null&&!again.equals(Transcriber.retryingId))again=null;
                String who=again!=null?again:id;
                if(now-last>30_000&&who!=null){Pipeline.log(c,who,"Android tuvo la app congelada "+Recording.time(now-last)+" (ahorro de batería con la pantalla bloqueada)");Diagnostics.event("app_frozen",who,"elapsed_ms",now-last,"display",Battery.screenOn(c)?"on":"off","battery",Battery.unrestricted(c)?"unrestricted":"optimized");}
                last=now;
                // Se fue el Wi-Fi durante la espera (el motivo completo, que recorre la biblioteca, se mira solo al pasar a datos
                // móviles): se avisa y, si era una espera corta, pasa a ser la de 15 min, como si hubiera faltado desde el comienzo.
                boolean onMobile=metered(c,wifiOnly);
                if(onMobile&&!wasMetered){
                    // La que se iba a reintentar ahora espera Wi-Fi: ya no se procesa, espera (y se le ofrece «Usar datos móviles»).
                    // Su titular lo dice: el aviso de abajo es de la primera pedida, que puede ser otra, y la que se reintentaba
                    // seguía con «Reintento en 20 s» hasta 15 min.
                    if(again!=null&&Pipeline.waitsForWifi(c,again)){Transcriber.retryingId=null;Pipeline.log(c,again,"En pausa: "+Pipeline.wifiWait()+" · se retoma sola al cumplirse");again=null;}
                    if(Pipeline.isWifiWait(Pipeline.blocker(c))){
                        Transcriber.retryingId=null;again=null;
                        String waiting=pendingId(c);if(waiting!=null)Pipeline.log(c,waiting,"En pausa: "+Pipeline.wifiWait()+" · se retoma sola al cumplirse");
                        wifiWait(c,http,waiting);
                        if(blocker==null){blocker=Pipeline.wifiWait();until=now+15*60_000;}
                    }
                }
                wasMetered=onMobile;
                if(blocker!=null&&now>=due&&Pipeline.blocker(c)==null)return true;
                if(nudged.get()&&Pipeline.blocker(c)==null){
                    if(again!=null)Pipeline.log(c,again,"Pantalla encendida · se reintenta ahora");else if(id!=null)Pipeline.log(c,id,"Pantalla encendida · se retoma ahora");
                    return true;
                }
            }
            // Se cumplió la espera pero ahora falta algo (p. ej. se desconectó el cargador): se cede a la tarea de fondo, y la que
            // se iba a reintentar lo dice (conservaba «Reintento en 20 s · sigue trabajando» mientras esperaba).
            String still=http.cancelled?null:Pipeline.blocker(c);
            if(still!=null&&again!=null&&again.equals(Transcriber.retryingId))Pipeline.log(c,again,"En pausa: "+still+" · se retoma sola al cumplirse");
            return !http.cancelled&&still==null;
        }finally{Transcriber.retryingId=null;}
    }
    /**
     * Espera por el cargador, la batería o internet (Transcriber.holds): la notificación de avance lo dice, en vez de quedar
     * en lo último que se envió («Transcribiendo · 3 de 3 partes listas»; la del servicio en primer plano no se puede quitar).
     */
    private static void hold(Context c,HttpApi http,String blocker){
        if(http.cancelled||blocker.isEmpty())return;
        String text=Character.toUpperCase(blocker.charAt(0))+blocker.substring(1)+" · se retoma sola al cumplirse";
        try{c.getSystemService(android.app.NotificationManager.class).notify(Transcriber.NOTIFICATION,Transcriber.build(c,text,true,-1));}catch(RuntimeException ignored){}
    }
    /** «Solo con Wi-Fi», hay red y no es Wi-Fi (barato: no lee la biblioteca). */
    private static boolean metered(Context c,boolean wifiOnly){return wifiOnly&&Pipeline.network(c)!=null&&!Pipeline.unmetered(c);}
    /**
     * «Solo con Wi-Fi» y ahora hay datos móviles: el aviso «Esperando Wi-Fi» con la salida «Usar datos móviles» (antes esta
     * espera era silenciosa, diagnóstico del 2026-10-01), y la notificación de avance lo dice en vez de «se reintenta solo».
     * Con este motivo ninguna grabación pedida tiene permiso para datos móviles: todas esperan, el aviso es de la primera (id).
     */
    private static void wifiWait(Context c,HttpApi http,String id){
        if(id==null||http.cancelled)return;
        Pipeline.waitingWifi(c,id);
        try{c.getSystemService(android.app.NotificationManager.class).notify(Transcriber.NOTIFICATION,Transcriber.build(c,Transcriber.WIFI_WAIT_TEXT,true,-1));}catch(RuntimeException ignored){}
    }
    private static String pendingId(Context c){for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("requested"))return r.id;return null;}
    @Override public void onDestroy(){HttpApi h=http;if(running&&h!=null)h.cancel();running=false;releaseLocks();super.onDestroy();}
    private void releaseLocks(){
        if(wake!=null&&wake.isHeld())wake.release();
        if(wifi!=null&&wifi.isHeld())wifi.release();
        try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}
    }
    /** Android 15 limita el tiempo de dataSync; al agotarse, se cede a la tarea diferida sin perder bloques ya listos. */
    @Override public void onTimeout(int startId,int fgsType){HttpApi h=http;if(h!=null)h.cancel();Pipeline.schedule(this,true);stopSelf();}
}
