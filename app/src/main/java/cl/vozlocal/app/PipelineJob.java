package cl.vozlocal.app;

import android.app.*;
import android.app.job.*;
import android.content.Intent;
import android.os.*;

/**
 * Tarea diferida: Android la ejecuta cuando se cumplen las condiciones (Wi-Fi, cargador, batería).
 * Primero intenta pasar el trabajo a TranscribeService (primer plano). Con la app cerrada, Android 12+ no
 * lo permite: entonces transcribe aquí (Android puede pausarla) y avisa con una notificación para abrir la app,
 * lo que pasa el trabajo a primer plano y lo acelera.
 *
 * 0.8.0, tercera ronda: este mismo servicio corre la transferencia iniciada por el usuario de Android 14+ (Pipeline,
 * USER_JOB_ID). Esa no tiene las cuotas ni el corte de 10 min de las tareas de fondo: trabaja como el servicio en primer
 * plano (mismas rondas con reintentos, TranscribeService.rounds), con su notificación obligatoria.
 */
public class PipelineJob extends JobService {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
    static final int OPEN_APP_NOTIFICATION=11;
    /** Una transferencia iniciada por el usuario está trabajando (las demás vías le ceden el turno). */
    static volatile boolean userRunning;
    /** Conexión de la transferencia iniciada por el usuario, para cancelar un envío al instante (Pipeline.cancel). */
    static volatile HttpApi current;
    /** Conexión de cada trabajo en curso, por id: el mismo servicio puede correr la tarea de fondo y la transferencia a la vez. */
    private final java.util.Map<Integer,HttpApi> actives=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params){
        if(Build.VERSION.SDK_INT>=34&&params.isUserInitiatedJob())return startUser(params);
        if(TranscribeService.running||userRunning)return false;
        if(Pipeline.startForeground(this))return false;
        suggestOpeningApp();
        HttpApi http=new HttpApi();actives.put(params.getJobId(),http);
        new Thread(()->{
            boolean retry;String waiting;
            if(!Transcriber.RUNNING.tryLock()){new Handler(getMainLooper()).post(()->jobFinished(params,true));return;}
            try{Diagnostics.event("runner_round",null,"runner","job","net",Pipeline.networkName(this));Transcriber t=new Transcriber(this,http,Transcriber.JOB_BUDGET_MS);retry=t.runAll();waiting=t.waitingForeground();}
            finally{Transcriber.RUNNING.unlock();}
            boolean again=retry;String needsApp=waiting;actives.remove(params.getJobId(),http);
            // Se programa una tarea nueva (sin espera exponencial) en vez de pedir reintento con backoff.
            // Un envío que solo cabe en primer plano no reprograma nada: el aviso pide abrir la app, que lo retoma.
            // La notificación de avance de esta tarea es una común (no la de un servicio): si nadie más la usa, se quita al
            // terminar. Antes quedaba, p. ej., «Enviando parte 2 de 3 · 45 %» junto a «Esperando Wi-Fi» hasta que volviera.
            if(!http.cancelled)new Handler(getMainLooper()).post(()->{jobFinished(params,false);if(again)Pipeline.schedule(this,true);
                if(needsApp!=null)openAppToSend(needsApp);else if(!again)getSystemService(NotificationManager.class).cancel(OPEN_APP_NOTIFICATION);
                if(!Pipeline.working())getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);});
        },"VozLocal-transcribe").start();return true;
    }
    /**
     * Transferencia iniciada por el usuario (Android 14+). La notificación va de inmediato: Android la exige en los primeros
     * segundos y, si no llega, detiene el trabajo. Si el servicio en primer plano ya está trabajando, este no hace falta.
     * La tarea de fondo que estuviera trabajando se detiene y esta toma el relevo (sin perder partes ya listas).
     */
    private boolean startUser(JobParameters params){
        if(Build.VERSION.SDK_INT<34)return false;
        if(TranscribeService.running||userRunning){Diagnostics.event("user_job",null,"result","skipped","runner","uij");return false;}
        try{setNotification(params,Transcriber.NOTIFICATION,Transcriber.build(this,Lang.str(this,R.string.eng_notif_preparing),true,-1),JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);}
        catch(RuntimeException e){Diagnostics.event("user_job",null,"result","no_notification","error_class",e.getClass().getSimpleName());}
        userRunning=true;HttpApi http=new HttpApi();actives.put(params.getJobId(),http);current=http;
        getSystemService(JobScheduler.class).cancel(Pipeline.JOB_ID);
        Diagnostics.event("user_job",null,"result","started","runner","uij","net",Pipeline.networkName(this));
        new Thread(()->{
            boolean retry=true;
            // Wi-Fi despierto con la pantalla apagada, como el servicio en primer plano (algunos teléfonos lo duermen y cortan el envío).
            android.net.wifi.WifiManager.WifiLock wifi=null;
            try{android.net.wifi.WifiManager wm=getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);if(wm!=null){wifi=wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,"VozLocal:TranscribeUser");wifi.setReferenceCounted(false);wifi.acquire();}}catch(RuntimeException ignored){wifi=null;}
            try{
                boolean locked=false;try{locked=Transcriber.RUNNING.tryLock(60,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                if(locked){try{retry=TranscribeService.rounds(this,http,"uij",new java.util.concurrent.atomic.AtomicBoolean());}finally{Transcriber.RUNNING.unlock();}}
            }finally{try{if(wifi!=null&&wifi.isHeld())wifi.release();}catch(RuntimeException ignored){}}
            // onStopJob saca la entrada antes de cortar: si sigue aquí, Android no detuvo esta transferencia. Antes se miraba
            // http.cancelled, que también queda en true cuando la persona cancela la grabación que se enviaba (Pipeline.cancel):
            // entonces nunca se llamaba jobFinished y la transferencia seguía «activa» para Android, con su notificación
            // congelada, hasta el límite de tiempo.
            boolean stopped=!actives.remove(params.getJobId(),http);
            // Tras «Cancelar», lo demás pedido lo retoma la tarea de fondo (schedule no hace nada si no queda nada).
            boolean again=!stopped&&(retry||http.cancelled);
            new Handler(getMainLooper()).post(()->{
                // Solo si sigue siendo la transferencia en curso: Android pudo detenerla y empezar otra mientras esta terminaba.
                if(current==http){userRunning=false;current=null;}
                // Detenida por Android (onStopJob): ahí ya se decidió si se repite; no se llama jobFinished.
                if(!stopped)jobFinished(params,false);
                // Lo que quede (p. ej. una grabación que espera Wi-Fi) lo retoma la tarea de fondo.
                if(again)Pipeline.schedule(this,true);
            });
        },"VozLocal-transcribe-user").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        int reason=Build.VERSION.SDK_INT>=31?params.getStopReason():-1;boolean byApp=reason==JobParameters.STOP_REASON_CANCELLED_BY_APP;
        boolean user=Build.VERSION.SDK_INT>=34&&params.isUserInitiatedJob();
        // Detenida por una condición que los ajustes de ahora ya no piden (ver staleStop): la tarea de fondo, con las
        // condiciones de ahora, sigue ya. Esta queda programada como estaba; si vuelve a cumplirse antes, toma el relevo.
        boolean stale=false;
        if(user&&(reason==JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY||reason==JobParameters.STOP_REASON_CONSTRAINT_CHARGING)){
            JobInfo job=null;try{job=getSystemService(JobScheduler.class).getPendingJob(params.getJobId());}catch(RuntimeException ignored){}
            Settings s=new Settings(this);stale=staleStop(reason,job,s.wifiOnly()&&!Pipeline.anyMobileOk(this),s.charging());
        }
        HttpApi h=actives.remove(params.getJobId());
        if(h!=null){h.cancel();String id=h.jobId;
            // La línea va a una grabación que sigue pedida: http.jobId es la última que tomó el trabajo, que pudo terminar
            // mientras esperaba para seguir con otra (antes «se reanudará» quedaba en una ya transcrita).
            String shown=byApp?(requested(id)?id:null):pausedId(id);
            // stale solo puede darse en la transferencia (ver arriba).
            if(shown!=null)Pipeline.log(this,shown,byApp?Lang.str(this,R.string.eng_log_continues_foreground)
                :Lang.str(this,!user?R.string.eng_log_paused_job:stale?R.string.eng_log_paused_transfer_stale:R.string.eng_log_paused_transfer,stopReason(this,reason)));
            if(id!=null&&reason==JobParameters.STOP_REASON_TIMEOUT)timedOut(id);
            if(reason==JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)wifiNotice();}
        if(user&&current==h){userRunning=false;current=null;}
        if(stale){Diagnostics.event("user_job",null,"result","stale","runner","uij","reason",reason);android.content.Context app=getApplicationContext();new Handler(getMainLooper()).post(()->Pipeline.schedule(app,true));}
        // 0.9.3 (informe del 2026-10-04, un Xiaomi): Android cortó la transferencia con la grabación a la vista y quedó «En
        // cola…» 42 s, hasta volver a Grabar. Con una pantalla de Verbapp abierta, se sigue al tiro en primer plano.
        else if(h!=null&&resumeNow(reason,Screen.visible>0)){android.content.Context app=getApplicationContext();new Handler(getMainLooper()).post(()->{if(Screen.visible>0&&!Pipeline.working()){Diagnostics.event("job_resumed",null,"reason",reason);Pipeline.startForeground(app);}});}
        // La notificación de avance es compartida: si otro trabajador la está usando (el servicio en primer plano o la
        // transferencia que tomó el relevo), no se quita.
        if(!Pipeline.working())getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);Diagnostics.event("job_interrupted",null,"reason",reason,"runner",user?"uij":"job");return true;
    }
    /**
     * ¿Android detuvo la transferencia iniciada por el usuario por una condición que los ajustes de ahora ya no piden? Se
     * programa con las condiciones de ese momento y, mientras trabaja, Pipeline.settingsChanged no la reemplaza (cortaría la
     * subida). Si entretanto se eligió «Wi-Fi y datos móviles» (o se permitieron los datos móviles a una grabación) o se quitó
     * «Solo mientras carga», al irse el Wi-Fi o desconectar el cargador Android la detiene y la retiene con la condición
     * vieja: la transcripción esperaba en silencio algo que ya no se pide. job: la detenida (null si no se pudo leer: cuenta
     * solo el motivo). wifiNow, chargingNow: lo que piden los ajustes de ahora. Separada del teléfono para poder probarla.
     */
    static boolean staleStop(int reason,JobInfo job,boolean wifiNow,boolean chargingNow){
        // Pipeline.couldStart con lo demás cumplido dice si la tarea exigía Wi-Fi (o el cargador).
        if(reason==JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)return !wifiNow&&(job==null||!Pipeline.couldStart(job,false,true));
        if(reason==JobParameters.STOP_REASON_CONSTRAINT_CHARGING)return !chargingNow&&(job==null||!Pipeline.couldStart(job,true,false));
        return false;
    }
    private boolean requested(String id){return id!=null&&FilesStore.state(this,id).optBoolean("requested");}
    /**
     * ¿Seguir al tiro en primer plano tras un corte de Android? Solo con una pantalla de Verbapp a la vista, y no si lo cortó
     * la propia app (otro trabajador tomó el relevo) o una condición que sigue sin cumplirse (red, cargador, batería, espacio):
     * esas las retoma la tarea de fondo cuando se cumplan. Separada para probarla.
     */
    static boolean resumeNow(int reason,boolean visible){
        if(!visible)return false;
        switch(reason){
            case JobParameters.STOP_REASON_CANCELLED_BY_APP:case JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY:case JobParameters.STOP_REASON_CONSTRAINT_CHARGING:
            case JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW:case JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW:return false;
            default:return true;
        }
    }
    /**
     * A quién le corresponde «Android pausó…»: la del envío si sigue pedida; si no, la que el trabajo esperaba para reintentar
     * (Transcriber.retryingId) o la primera pedida. null si no queda ninguna.
     */
    private String pausedId(String id){
        if(requested(id))return id;
        String again=Transcriber.retryingId;if(requested(again))return again;
        for(Recording r:Recording.list(this))if(requested(r.id))return r.id;
        return null;
    }
    /**
     * Resguardo: Android cortó la tarea por tiempo en medio de un envío (el trabajo no alcanza a registrar el fallo). Cuenta
     * como un intento; al quinto, la grabación queda con error (y un «Volver a transcribir» vuelve a la versión anterior),
     * en vez de volver a subir el mismo audio en cada tarea nueva. Al rendirse avisa con una notificación, igual que el
     * motor (Transcriber.attention): antes solo quedaba escrito en la bitácora y, con la app cerrada, nadie se enteraba.
     */
    private void timedOut(String id){
        android.content.Context app=getApplicationContext();
        new Thread(()->{
            try{
                int[] attempts={0};int max=Transcriber.ATTEMPTS;String why=Lang.str(app,R.string.eng_reason_job_timeout);
                FilesStore.update(app,id,s->{if(!s.optBoolean("requested"))return;int a=s.optInt("attempts",0)+1;attempts[0]=a;s.put("attempts",a).put("retries",s.optInt("retries")+1);
                    if(a>=max)s.put("requested",false).put("failed",true).put("lastError",why);});
                if(attempts[0]==0)return;
                Diagnostics.event("job_timeout",id,"count",attempts[0],"runner","job");
                if(attempts[0]<max){Pipeline.log(app,id,Lang.str(app,R.string.eng_log_job_timeout,attempts[0],max));return;}
                boolean restoring=Retranscribe.hasPrevious(app,id)&&!Transcript.exists(app,id);
                Pipeline.log(app,id,Lang.str(app,restoring?R.string.eng_log_job_timeout_restoring:R.string.eng_log_job_timeout_last,max,max));
                Diagnostics.event("job_failed",id,"count",attempts[0],"reason","timeout","runner","job");
                if(restoring){
                    Retranscribe.restore(app,id,Lang.str(app,R.string.eng_log_new_version_failed),"retranscribe_failed");
                    Transcriber.attention(app,id,Lang.str(app,R.string.eng_notif_new_version_failed),Lang.str(app,R.string.eng_notif_job_cut_keep,max));
                }else Transcriber.attention(app,id,Lang.str(app,R.string.eng_notif_attention_title),Lang.str(app,R.string.eng_notif_job_cut_retry,max));
            }catch(Exception e){Diagnostics.event("job_timeout_failed",id,"error_class",e.getClass().getSimpleName());}
        },"VozLocal-timeout").start();
    }
    /**
     * Android detuvo el trabajo porque cambió la red: con «Solo con Wi-Fi», se fue el Wi-Fi y quedaron los datos móviles (la
     * transferencia exige Wi-Fi). Si la grabación queda esperando Wi-Fi, se avisa con la salida «Usar datos móviles»: antes
     * solo lo decía la bitácora y la espera volvía a ser silenciosa (diagnóstico del 2026-10-01). Se mira unos segundos
     * después, y hasta 30 s: mientras el teléfono pasa del Wi-Fi a los datos móviles, a veces no hay ninguna red. El aviso es
     * de la primera grabación pedida que espera Wi-Fi, no de http.jobId: esa pudo terminar mientras el trabajo esperaba para
     * seguir con otra, y entonces la que de verdad esperaba se quedaba sin aviso.
     */
    private void wifiNotice(){
        android.content.Context app=getApplicationContext();
        new Thread(()->{
            for(int i=0;i<6;i++){
                SystemClock.sleep(5_000);
                if(Pipeline.network(app)==null)continue;
                for(Recording r:Recording.list(app))if(Pipeline.waitsForWifi(app,r.id)){Pipeline.waitingWifi(app,r.id);break;}
                return;
            }
        },"VozLocal-wifi-wait").start();
    }
    /** Un envío que solo se puede hacer en primer plano: el aviso abre esa grabación, y abrirla lo retoma. */
    private void openAppToSend(String id){
        NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("processing",Lang.str(this,R.string.eng_channel_processing),NotificationManager.IMPORTANCE_LOW));
        Intent intent=new Intent(this,RecordingActivity.class).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open=PendingIntent.getActivity(this,OPEN_APP_NOTIFICATION,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=Lang.str(this,R.string.eng_notif_open_app_text);
        Notification n=new Notification.Builder(this,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(Lang.str(this,R.string.eng_notif_open_app_title)).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        try{m.notify(OPEN_APP_NOTIFICATION,n);}catch(SecurityException ignored){}
    }
    /** Motivo de detención de Android (API 31+) en palabras simples, en el idioma de la app. */
    static String stopReason(android.content.Context c,int reason){return Lang.str(c,stopReasonText(reason));}
    /** Igual, sin Context a mano. */
    static String stopReason(int reason){return Lang.str(stopReasonText(reason));}
    /** El texto de cada motivo de detención de Android. */
    private static int stopReasonText(int reason){
        switch(reason){
            case JobParameters.STOP_REASON_TIMEOUT:return R.string.eng_stop_timeout;
            case JobParameters.STOP_REASON_DEVICE_STATE:return R.string.eng_stop_device_state;
            case JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY:return R.string.eng_stop_connectivity;
            case JobParameters.STOP_REASON_CONSTRAINT_CHARGING:return R.string.eng_stop_charging;
            case JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW:return R.string.eng_stop_battery;
            case JobParameters.STOP_REASON_QUOTA:return R.string.eng_stop_quota;
            case JobParameters.STOP_REASON_APP_STANDBY:return R.string.eng_stop_standby;
            case JobParameters.STOP_REASON_BACKGROUND_RESTRICTION:return R.string.eng_stop_restricted;
            case JobParameters.STOP_REASON_PREEMPT:return R.string.eng_stop_preempt;
            case JobParameters.STOP_REASON_SYSTEM_PROCESSING:return R.string.eng_stop_system;
            case JobParameters.STOP_REASON_USER:return R.string.eng_stop_user;
            default:return R.string.eng_stop_unknown;
        }
    }
    /** Con la app cerrada, Android no deja trabajar en primer plano: se sugiere abrirla (un toque) para acelerar. */
    private void suggestOpeningApp(){
        for(Recording r:Recording.list(this))if(FilesStore.state(this,r.id).optBoolean("requested")){Pipeline.log(this,r.id,Lang.str(this,R.string.eng_log_background_only));break;}
        NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("processing",Lang.str(this,R.string.eng_channel_processing),NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,11,new Intent(this,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(Lang.str(this,R.string.eng_notif_background_title)).setContentText(Lang.str(this,R.string.eng_notif_background_text)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        try{m.notify(OPEN_APP_NOTIFICATION,n);}catch(SecurityException ignored){}
    }
    static String hash(byte[] data)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    static String safeName(String title){return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");}
}
