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
            if(!http.cancelled)new Handler(getMainLooper()).post(()->{jobFinished(params,false);if(again)Pipeline.schedule(this,true);
                if(needsApp!=null)openAppToSend(needsApp);else if(!again)getSystemService(NotificationManager.class).cancel(OPEN_APP_NOTIFICATION);});
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
        try{setNotification(params,Transcriber.NOTIFICATION,Transcriber.build(this,"Preparando…",true,-1),JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);}
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
        HttpApi h=actives.remove(params.getJobId());
        if(h!=null){h.cancel();String id=h.jobId;
            if(id!=null){Pipeline.log(this,id,byApp?"Continúa en primer plano (sigue aunque bloquees el teléfono)":(user?"Android pausó la transferencia: ":"Android pausó la tarea de fondo: ")+stopReason(reason)+" · se reanudará");
                if(reason==JobParameters.STOP_REASON_TIMEOUT)timedOut(id);
                if(reason==JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)wifiNotice(id);}}
        if(user&&current==h){userRunning=false;current=null;}
        // La notificación de avance es compartida: si otro trabajador la está usando (el servicio en primer plano o la
        // transferencia que tomó el relevo), no se quita.
        if(!Pipeline.working())getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);Diagnostics.event("job_interrupted",null,"reason",reason,"runner",user?"uij":"job");return true;
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
                int[] attempts={0};
                FilesStore.update(app,id,s->{if(!s.optBoolean("requested"))return;int a=s.optInt("attempts",0)+1;attempts[0]=a;s.put("attempts",a).put("retries",s.optInt("retries")+1);
                    if(a>=5)s.put("requested",false).put("failed",true).put("lastError","Android cortó la tarea de fondo por tiempo");});
                if(attempts[0]==0)return;
                Diagnostics.event("job_timeout",id,"count",attempts[0],"runner","job");
                if(attempts[0]<5){Pipeline.log(app,id,"Intento "+attempts[0]+" de 5: Android cortó la tarea de fondo por tiempo · abre la app para seguir en primer plano");return;}
                boolean restoring=Retranscribe.hasPrevious(app,id)&&!Transcript.exists(app,id);
                Pipeline.log(app,id,"Intento 5 de 5: Android cortó la tarea de fondo por tiempo"+(restoring?"":" · abre la app y pulsa Reintentar"));
                Diagnostics.event("job_failed",id,"count",attempts[0],"reason","timeout","runner","job");
                if(restoring){
                    Retranscribe.restore(app,id,"No se pudo hacer la nueva versión · se mantiene la anterior","retranscribe_failed");
                    Transcriber.attention(app,id,"No se pudo hacer la nueva versión","Android cortó la tarea de fondo 5 veces. Se mantiene la versión anterior.");
                }else Transcriber.attention(app,id,"La transcripción necesita atención","Android cortó la tarea de fondo 5 veces. Abre la grabación y pulsa Reintentar.");
            }catch(Exception e){Diagnostics.event("job_timeout_failed",id,"error_class",e.getClass().getSimpleName());}
        },"VozLocal-timeout").start();
    }
    /**
     * Android detuvo el trabajo porque cambió la red: con «Solo con Wi-Fi», se fue el Wi-Fi y quedaron los datos móviles (la
     * transferencia exige Wi-Fi). Si la grabación queda esperando Wi-Fi, se avisa con la salida «Usar datos móviles»: antes
     * solo lo decía la bitácora y la espera volvía a ser silenciosa (diagnóstico del 2026-10-01). Se mira unos segundos
     * después, y hasta 30 s: mientras el teléfono pasa del Wi-Fi a los datos móviles, a veces no hay ninguna red.
     */
    private void wifiNotice(String id){
        android.content.Context app=getApplicationContext();
        new Thread(()->{
            for(int i=0;i<6;i++){
                SystemClock.sleep(5_000);
                if(Pipeline.network(app)==null)continue;
                if(Pipeline.waitsForWifi(app,id))Pipeline.waitingWifi(app,id);
                return;
            }
        },"VozLocal-wifi-wait").start();
    }
    /** Un envío que solo se puede hacer en primer plano: el aviso abre esa grabación, y abrirla lo retoma. */
    private void openAppToSend(String id){
        NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        Intent intent=new Intent(this,RecordingActivity.class).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open=PendingIntent.getActivity(this,OPEN_APP_NOTIFICATION,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text="Este envío es largo y necesita la app abierta. Toca para seguir: continúa aunque bloquees el teléfono.";
        Notification n=new Notification.Builder(this,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle("Abre Verbapp para terminar").setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        try{m.notify(OPEN_APP_NOTIFICATION,n);}catch(SecurityException ignored){}
    }
    /** Motivo de detención de Android (API 31+) en palabras simples. */
    static String stopReason(int reason){
        switch(reason){
            case JobParameters.STOP_REASON_TIMEOUT:return "límite de tiempo de las tareas de fondo";
            case JobParameters.STOP_REASON_DEVICE_STATE:return "ahorro de energía del teléfono (pantalla apagada)";
            case JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY:return "cambió o se perdió la conexión";
            case JobParameters.STOP_REASON_CONSTRAINT_CHARGING:return "se desconectó el cargador";
            case JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW:return "batería baja";
            case JobParameters.STOP_REASON_QUOTA:return "Android agotó el tiempo que le da a la app en segundo plano";
            case JobParameters.STOP_REASON_APP_STANDBY:return "la app lleva tiempo sin usarse (modo reposo)";
            case JobParameters.STOP_REASON_BACKGROUND_RESTRICTION:return "la app tiene restringido el uso en segundo plano";
            case JobParameters.STOP_REASON_PREEMPT:return "Android priorizó otra tarea";
            case JobParameters.STOP_REASON_SYSTEM_PROCESSING:return "Android necesitaba recursos";
            case JobParameters.STOP_REASON_USER:return "detenida desde Ajustes de Android";
            default:return "motivo no informado por Android";
        }
    }
    /** Con la app cerrada, Android no deja trabajar en primer plano: se sugiere abrirla (un toque) para acelerar. */
    private void suggestOpeningApp(){
        for(Recording r:Recording.list(this))if(FilesStore.state(this,r.id).optBoolean("requested")){Pipeline.log(this,r.id,"Con la app cerrada Android solo permite una tarea de fondo (puede pausarla) · abre la app para acelerar");break;}
        NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,11,new Intent(this,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle("Transcripción en segundo plano").setContentText("Toca para abrir Verbapp y acelerarla").setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build();
        try{m.notify(OPEN_APP_NOTIFICATION,n);}catch(SecurityException ignored){}
    }
    static String hash(byte[] data)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    static String safeName(String title){return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");}
}
