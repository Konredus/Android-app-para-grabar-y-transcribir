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
 */
public class PipelineJob extends JobService {
    static final int OPEN_APP_NOTIFICATION=11;
    private volatile HttpApi active;
    @Override public boolean onStartJob(JobParameters params){
        if(TranscribeService.running)return false;
        if(Pipeline.startForeground(this))return false;
        suggestOpeningApp();
        HttpApi http=new HttpApi();active=http;
        new Thread(()->{
            boolean retry;String waiting;
            if(!Transcriber.RUNNING.tryLock()){new Handler(getMainLooper()).post(()->jobFinished(params,true));return;}
            try{Diagnostics.event("runner_round",null,"runner","job","net",Pipeline.networkName(this));Transcriber t=new Transcriber(this,http,Transcriber.JOB_BUDGET_MS);retry=t.runAll();waiting=t.waitingForeground();}
            finally{Transcriber.RUNNING.unlock();}
            boolean again=retry;String needsApp=waiting;
            // Se programa una tarea nueva (sin espera exponencial) en vez de pedir reintento con backoff.
            // Un envío que solo cabe en primer plano no reprograma nada: el aviso pide abrir la app, que lo retoma.
            if(!http.cancelled)new Handler(getMainLooper()).post(()->{jobFinished(params,false);if(again)Pipeline.schedule(this,true);
                if(needsApp!=null)openAppToSend(needsApp);else if(!again)getSystemService(NotificationManager.class).cancel(OPEN_APP_NOTIFICATION);});
        },"VozLocal-transcribe").start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        int reason=Build.VERSION.SDK_INT>=31?params.getStopReason():-1;boolean byApp=reason==JobParameters.STOP_REASON_CANCELLED_BY_APP;
        HttpApi h=active;
        if(h!=null){h.cancel();String id=h.jobId;
            if(id!=null){Pipeline.log(this,id,byApp?"Continúa en primer plano (sigue aunque bloquees el teléfono)":"Android pausó la tarea de fondo: "+stopReason(reason)+" · se reanudará");
                if(reason==JobParameters.STOP_REASON_TIMEOUT)timedOut(id);}}
        getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);Diagnostics.event("job_interrupted",null,"reason",reason,"runner","job");return true;
    }
    /**
     * Resguardo: Android cortó la tarea por tiempo en medio de un envío (el trabajo no alcanza a registrar el fallo). Cuenta
     * como un intento; al quinto, la grabación queda con error (y un «Volver a transcribir» vuelve a la versión anterior),
     * en vez de volver a subir el mismo audio en cada tarea nueva.
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
                if(restoring)Retranscribe.restore(app,id,"No se pudo hacer la nueva versión · se mantiene la anterior","retranscribe_failed");
            }catch(Exception e){Diagnostics.event("job_timeout_failed",id,"error_class",e.getClass().getSimpleName());}
        },"VozLocal-timeout").start();
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
