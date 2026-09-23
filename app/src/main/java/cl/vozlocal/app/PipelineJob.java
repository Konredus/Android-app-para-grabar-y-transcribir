package cl.vozlocal.app;

import android.app.*;
import android.app.job.*;
import android.content.Intent;
import android.os.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class PipelineJob extends JobService {
    private static final java.util.concurrent.locks.ReentrantLock RUNNING=new java.util.concurrent.locks.ReentrantLock();
    private volatile HttpApi active;
    @Override public boolean onStartJob(JobParameters params){
        HttpApi http=new HttpApi();active=http;
        new Thread(()->{
            boolean retry=false;
            if(!RUNNING.tryLock()){new Handler(getMainLooper()).post(()->jobFinished(params,true));return;}
            try{
                for(Recording r:Recording.list(this)){
                    if(http.cancelled)break;
                    JSONObject state=FilesStore.state(this,r.id);if(!state.optBoolean("requested"))continue;
                    if(RecorderService.activeId!=null){retry=true;break;}
                    http.jobId=r.id;
                    try{process(r,http);}
                    catch(HttpApi.UserAction e){FilesStore.update(this,r.id,s->s.put("requested",false).put("failed",true).put("status",e.getMessage()));notice("La transcripción necesita atención",false);Diagnostics.event("job_rejected",r.id,"error_class",e.getClass().getSimpleName());}
                    catch(Exception e){
                        if(http.cancelled)break;
                        if(!r.audio(this).exists() || !FilesStore.state(this,r.id).optBoolean("requested"))continue;
                        int attempts=state.optInt("attempts",0)+1;boolean again=attempts<5;retry|=again;
                        FilesStore.update(this,r.id,s->s.put("attempts",attempts).put("requested",again).put("failed",!again).put("status",again?"Procesamiento interrumpido · se volverá a intentar":"No se pudo completar. Revisa el informe de soporte y pulsa Reintentar."));
                        Diagnostics.event("job_retry",r.id,"count",attempts,"error_class",e.getClass().getSimpleName());
                    }
                }
                retry|=Pipeline.pending(this);
            }catch(Exception e){retry=true;Diagnostics.event("pipeline_failure",null,"error_class",e.getClass().getSimpleName());}
            finally{RUNNING.unlock();boolean again=retry;if(!http.cancelled)new Handler(getMainLooper()).post(()->{if(again)notice("En espera para volver a intentar",false);jobFinished(params,again);});}
        },"VozLocal-transcribe").start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){if(active!=null){active.cancel();if(active.jobId!=null)try{FilesStore.status(this,active.jobId,"En espera · Android reanudará el procesamiento");}catch(Exception ignored){}}getSystemService(NotificationManager.class).cancel(9);Diagnostics.event("job_interrupted",null);return true;}
    private void check(Recording r,HttpApi http)throws Exception{http.check();if(!r.audio(this).exists() || !FilesStore.state(this,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");}
    private void stage(Recording r,String status){try{FilesStore.status(this,r.id,status);}catch(Exception ignored){}notice(status,true);}
    private void process(Recording r,HttpApi http)throws Exception{
        check(r,http);long started=System.currentTimeMillis();Settings settings=new Settings(this);
        if(!Transcript.exists(this,r.id)){
            ProviderConfig config=settings.config();if(config.key.isEmpty())throw new HttpApi.UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.");
            String profile=config.fingerprint()+settings.language();
            if(!profile.equals(FilesStore.state(this,r.id).optString("profile"))){
                java.io.File[] checkpoints=Recording.directory(this).listFiles((dir,name)->name.startsWith(r.id+".part")&&name.endsWith(".json"));if(checkpoints!=null)for(java.io.File file:checkpoints)file.delete();
                FilesStore.update(this,r.id,s->s.put("profile",profile));
            }
            Diagnostics.event("job_start",r.id,"provider",config.provider,"model",config.model,"bytes",r.audio(this).length(),"duration_ms",r.duration);
            stage(r,"Preparando audio…");List<AudioParts.Part> parts=AudioParts.prepare(this,r,http);List<JSONObject> responses=new ArrayList<>();List<Double> offsets=new ArrayList<>();
            try{
                for(int i=0;i<parts.size();i++){
                    check(r,http);AudioParts.Part part=parts.get(i);java.io.File checkpoint=FilesStore.file(this,r.id,".part"+i+".json");JSONObject response;String block=" · bloque "+(i+1)+" de "+parts.size();
                    if(checkpoint.exists())response=FilesStore.read(checkpoint);
                    else{
                        stage(r,"Enviando audio"+block);http.onUploaded=()->stage(r,"Transcribiendo"+block);
                        response=new OpenAiClient(http).transcribe(part.file,config,settings.language());
                        check(r,http);synchronized(FilesStore.LOCK){if(r.audio(this).exists())FilesStore.write(checkpoint,response);}
                        Diagnostics.event("part_complete",r.id,"part",i+1,"parts",parts.size());
                    }
                    responses.add(response);offsets.add(part.offset);
                }
                check(r,http);Transcript transcript=Transcript.fromParts(responses,offsets);transcript.data.put("provider",config.provider).put("model",config.model);transcript.save(this,r.id);
            }finally{http.onUploaded=null;for(AudioParts.Part part:parts)if(!part.file.equals(r.audio(this)))part.file.delete();}
        }
        check(r,http);FilesStore.update(this,r.id,s->s.put("requested",false).put("failed",false).put("attempts",0).put("status","Transcripción lista"));
        LocalStorage.enqueue(this,r.id);notice("Transcripción lista · abre tu biblioteca",false);Diagnostics.event("job_complete",r.id,"elapsed_ms",System.currentTimeMillis()-started);
    }
    private void notice(String text,boolean ongoing){
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,9,new Intent(this,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"processing").setSmallIcon(cl.vozlocal.app.R.drawable.ic_mic).setContentTitle("Voz local").setContentText(text).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing);
        if(ongoing)builder.setProgress(0,0,true);try{manager.notify(9,builder.build());}catch(SecurityException ignored){}
    }
    static String hash(byte[] data)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    static String safeName(String title){return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");}
}
