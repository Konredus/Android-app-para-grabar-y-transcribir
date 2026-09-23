package cl.vozlocal.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import org.json.*;
import java.util.*;

/**
 * Motor de transcripción compartido por TranscribeService (primer plano, funciona con el teléfono bloqueado)
 * y PipelineJob (tarea diferida de Android, espera Wi-Fi/cargador). Solo uno corre a la vez.
 * Cada paso queda en la bitácora de la grabación (Pipeline.log) para mostrarlo en "Detalles del proceso".
 */
final class Transcriber {
    static final java.util.concurrent.locks.ReentrantLock RUNNING=new java.util.concurrent.locks.ReentrantLock();
    static final int NOTIFICATION=9,DONE_NOTIFICATION=10;
    /** La tarea diferida cede el turno antes de este tiempo para que Android no la corte a mitad de un envío. */
    static final long JOB_BUDGET_MS=6*60*1000;
    static final class Yield extends Exception{Yield(){super("Pausa corta");}}

    private final Context c;private final HttpApi http;private final long budgetMs;private final long started=System.currentTimeMillis();
    private long lastProgress;
    Transcriber(Context c,HttpApi http,long budgetMs){this.c=c;this.http=http;this.budgetMs=budgetMs;}

    /** Procesa todas las grabaciones en cola. Devuelve true si queda trabajo pendiente para más tarde. */
    boolean runAll(){
        boolean retry=false;
        try{
            for(Recording r:Recording.list(c)){
                if(http.cancelled)break;
                JSONObject state=FilesStore.state(c,r.id);if(!state.optBoolean("requested"))continue;
                if(RecorderService.activeId!=null){retry=true;Pipeline.log(c,r.id,"En espera: hay una grabación en curso");break;}
                http.jobId=r.id;
                try{process(r);}
                catch(Yield y){retry=true;Pipeline.log(c,r.id,"Pausa corta para no exceder el límite de Android · continúa enseguida");break;}
                catch(HttpApi.UserAction e){FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",true));Pipeline.log(c,r.id,e.getMessage());notice("La transcripción necesita atención",false,-1);Diagnostics.event("job_rejected",r.id,"error_class",e.getClass().getSimpleName());}
                catch(Exception e){
                    if(http.cancelled)break;
                    if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))continue;
                    int attempts=FilesStore.state(c,r.id).optInt("attempts",0)+1;boolean again=attempts<5;retry|=again;
                    String reason=describe(e);
                    FilesStore.update(c,r.id,s->s.put("attempts",attempts).put("requested",again).put("failed",!again).put("lastError",reason));
                    Pipeline.log(c,r.id,"Intento "+attempts+" de 5 falló: "+reason+(again?" · se reintentará automáticamente":" · pulsa Reintentar"));
                    Diagnostics.event("job_retry",r.id,"count",attempts,"error_class",e.getClass().getSimpleName());
                }
            }
            retry|=Pipeline.pending(c);
        }catch(Exception e){retry=true;Diagnostics.event("pipeline_failure",null,"error_class",e.getClass().getSimpleName());}
        return retry;
    }
    /** Traduce fallos técnicos a una causa comprensible. */
    private String describe(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "el proveedor no respondió en "+(http.readTimeoutMs/60000)+" min (tiempo de espera agotado; el intento pudo cobrarse)";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "sin conexión con el servidor";
        if(e instanceof javax.net.ssl.SSLException)return "la conexión segura se interrumpió";
        if(e instanceof java.io.InterruptedIOException)return "Android pausó el trabajo";
        String m=e.getMessage();return m!=null&&m.length()<140?m:"error de red ("+e.getClass().getSimpleName()+")";
    }
    private void check(Recording r)throws Exception{http.check();if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");}
    private void stage(Recording r,String status,int percent){Pipeline.log(c,r.id,status);notice(status,true,percent);}

    private void process(Recording r)throws Exception{
        check(r);long start=System.currentTimeMillis();Settings settings=new Settings(c);
        if(!Transcript.exists(c,r.id)){
            ProviderConfig config=settings.config();if(config.key.isEmpty())throw new HttpApi.UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.");
            String profile=config.fingerprint()+settings.language();
            if(!profile.equals(FilesStore.state(c,r.id).optString("profile"))){
                java.io.File[] checkpoints=Recording.directory(c).listFiles((dir,name)->name.startsWith(r.id+".part")&&name.endsWith(".json"));if(checkpoints!=null)for(java.io.File file:checkpoints)file.delete();
                FilesStore.update(c,r.id,s->s.put("profile",profile));
            }
            Diagnostics.event("job_start",r.id,"provider",config.provider,"model",config.model,"bytes",r.audio(c).length(),"duration_ms",r.duration);
            stage(r,"Preparando audio",-1);List<AudioParts.Part> parts=AudioParts.prepare(c,r,http);List<JSONObject> responses=new ArrayList<>();List<Double> offsets=new ArrayList<>();
            int n=parts.size();FilesStore.update(c,r.id,s->s.put("blocks",n));
            if(n>1)Pipeline.log(c,r.id,"Audio de "+Recording.time(r.duration)+" dividido en "+n+" bloques de hasta 15 min (límite de duración y tamaño del proveedor)");
            try{
                for(int i=0;i<n;i++){
                    check(r);AudioParts.Part part=parts.get(i);java.io.File checkpoint=FilesStore.file(c,r.id,".part"+i+".json");JSONObject response;String block=n>1?" · bloque "+(i+1)+" de "+n:"";
                    if(checkpoint.exists())response=FilesStore.read(checkpoint);
                    else{
                        if(budgetMs>0&&i>0&&System.currentTimeMillis()-started>budgetMs)throw new Yield();
                        double seconds=(i+1<n?parts.get(i+1).offset:r.duration/1000d)-part.offset;
                        // Un bloque largo con separación de voces puede tardar varios minutos: la espera escala con su duración.
                        http.readTimeoutMs=(int)Math.min(20*60_000L,Math.max(240_000L,120_000L+(long)(seconds*1000)));
                        int index=i;long blockStart=System.currentTimeMillis();
                        stage(r,"Enviando audio"+block,0);
                        http.onProgress=(sent,total)->progress(r,sent,total,block);
                        http.onUploaded=()->{try{FilesStore.update(c,r.id,s->s.put("upSent",0).put("upTotal",0));}catch(Exception ignored){}stage(r,"Audio enviado · esperando respuesta del proveedor"+block,-1);};
                        response=new OpenAiClient(http).transcribe(part.file,config,settings.language());
                        check(r);synchronized(FilesStore.LOCK){if(r.audio(c).exists())FilesStore.write(checkpoint,response);}
                        FilesStore.update(c,r.id,s->s.put("blocksDone",index+1));
                        Pipeline.log(c,r.id,(n>1?"Bloque "+(i+1)+" de "+n+" listo":"Respuesta recibida")+" · tardó "+Recording.time(System.currentTimeMillis()-blockStart));
                        Diagnostics.event("part_complete",r.id,"part",i+1,"parts",n);
                    }
                    responses.add(response);offsets.add(part.offset);
                }
                check(r);Transcript transcript=Transcript.fromParts(responses,offsets);transcript.data.put("provider",config.provider).put("model",config.model);transcript.save(c,r.id);
            }finally{http.onUploaded=null;http.onProgress=null;for(AudioParts.Part part:parts)if(!part.file.equals(r.audio(c)))part.file.delete();}
        }
        check(r);long queued=FilesStore.state(c,r.id).optLong("queuedAt",start);
        FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",false).put("attempts",0).put("doneIn",System.currentTimeMillis()-queued).put("upSent",0).put("upTotal",0));
        Pipeline.log(c,r.id,"Transcripción lista · tiempo total "+Recording.time(System.currentTimeMillis()-queued));
        LocalStorage.enqueue(c,r.id);notice("Transcripción lista · «"+r.title+"»",false,-1);Diagnostics.event("job_complete",r.id,"elapsed_ms",System.currentTimeMillis()-start);
    }
    /** Progreso de subida: se guarda cada ~0,7 s para la pantalla de detalle y la notificación. */
    private void progress(Recording r,long sent,long total,String block){
        long now=System.currentTimeMillis();if(now-lastProgress<700&&sent<total)return;lastProgress=now;
        try{FilesStore.update(c,r.id,s->s.put("upSent",sent).put("upTotal",total));}catch(Exception ignored){}
        notice("Enviando audio"+block,true,(int)(sent*100/Math.max(1,total)));
    }
    static Notification build(Context c,String text,boolean ongoing,int percent){
        NotificationManager manager=c.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(c,9,new Intent(c,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(c,"processing").setSmallIcon(R.drawable.ic_notification).setContentTitle(ongoing?"Transcribiendo":"Voz local").setContentText(text).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true);
        if(ongoing){if(percent>=0)b.setProgress(100,percent,false);else b.setProgress(0,0,true);}
        return b.build();
    }
    private void notice(String text,boolean ongoing,int percent){
        try{c.getSystemService(NotificationManager.class).notify(ongoing?NOTIFICATION:DONE_NOTIFICATION,build(c,text,ongoing,percent));}catch(SecurityException ignored){}
        if(!ongoing)c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
    }
}
