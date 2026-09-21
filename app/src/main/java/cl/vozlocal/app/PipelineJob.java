package cl.vozlocal.app;

import android.app.job.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

public class PipelineJob extends JobService {
    private static final java.util.concurrent.locks.ReentrantLock RUNNING=new java.util.concurrent.locks.ReentrantLock();
    private volatile HttpApi active;
    @Override public boolean onStartJob(JobParameters params){
        HttpApi http=new HttpApi();active=http;
        new Thread(() -> {
            boolean retry=false;
            if(!RUNNING.tryLock()){new Handler(getMainLooper()).post(() -> jobFinished(params,true));return;}
            try{
                if(RecorderService.activeId!=null){retry=true;return;}
                for(Recording r:Recording.list(this)){
                    if(http.cancelled)break;
                    JSONObject state=FilesStore.state(this,r.id);if(!state.optBoolean("requested"))continue;
                    if(RecorderService.activeId!=null){retry=true;break;}
                    try{process(r,http);}
                    catch(HttpApi.UserAction e){FilesStore.update(this,r.id,s -> s.put("requested",false).put("status",e.getMessage()));}
                    catch(Exception e){
                        if(http.cancelled)break;
                        if(!r.audio(this).exists() || !FilesStore.state(this,r.id).optBoolean("requested"))continue;
                        int attempts=state.optInt("attempts",0)+1;boolean again=attempts<5;retry|=again;
                        FilesStore.update(this,r.id,s -> s.put("attempts",attempts).put("requested",again).put("status",again?"Conexión interrumpida · se volverá a intentar":"No se pudo completar. Revisa la conexión y pulsa Reintentar."));
                    }
                }
                retry|=Pipeline.pending(this);
            }catch(Exception ignored){retry=true;}
            finally{RUNNING.unlock();boolean again=retry;if(!http.cancelled)new Handler(getMainLooper()).post(() -> jobFinished(params,again));}
        },"VozLocal-sync").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){if(active!=null)active.cancel();return true;}
    private void check(Recording r,HttpApi http)throws Exception{
        http.check();if(!r.audio(this).exists() || !FilesStore.state(this,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");
    }
    private void process(Recording r,HttpApi http)throws Exception{
        check(r,http);Settings settings=new Settings(this);
        if(!Transcript.exists(this,r.id)){
            String key;
            try{key=settings.apiKey();}catch(Exception e){throw new HttpApi.UserAction("No se pudo abrir la clave guardada. Vuelve a ingresarla en Configuración.");}
            if(key.isEmpty())throw new HttpApi.UserAction("Agrega tu clave de OpenAI en Configuración y pulsa Reintentar.");
            FilesStore.status(this,r.id,"Preparando audio para transcribir…");
            List<AudioParts.Part> parts=AudioParts.prepare(this,r,http);List<JSONObject> responses=new ArrayList<>();List<Double> offsets=new ArrayList<>();
            try{
                for(int i=0;i<parts.size();i++){
                    check(r,http);AudioParts.Part part=parts.get(i);java.io.File checkpoint=FilesStore.file(this,r.id,".part"+i+".json");JSONObject response;
                    if(checkpoint.exists())response=FilesStore.read(checkpoint);
                    else{
                        FilesStore.status(this,r.id,"Transcribiendo con voces · bloque "+(i+1)+" de "+parts.size());
                        response=new OpenAiClient(http).transcribe(part.file,key,settings.language());
                        check(r,http);synchronized(FilesStore.LOCK){if(r.audio(this).exists())FilesStore.write(checkpoint,response);}
                    }
                    responses.add(response);offsets.add(part.offset);
                }
                check(r,http);Transcript.fromParts(responses,offsets).save(this,r.id);
            }finally{for(AudioParts.Part part:parts)if(!part.file.equals(r.audio(this)))part.file.delete();}
        }
        check(r,http);
        if(!settings.driveConnected()){
            FilesStore.update(this,r.id,s -> s.put("requested",false).put("status","Transcripción lista · Drive sin vincular"));return;
        }
        FilesStore.status(this,r.id,"Conectando con Google Drive…");
        DriveClient drive=new DriveClient(http,DriveClient.token(this));String folder=drive.ensureFolder(this);check(r,http);
        Recording latest=FilesStore.recording(this,r.id);if(latest==null)return;
        Transcript transcript=Transcript.load(this,r.id);byte[] text=transcript.text(latest).getBytes(StandardCharsets.UTF_8);
        String hash=hash(text);JSONObject state=FilesStore.state(this,r.id);
        if(!folder.equals(state.optString("remoteFolder"))){FilesStore.update(this,r.id,s -> {s.put("remoteFolder",folder);s.remove("textId");s.remove("audioId");s.remove("uploadedHash");s.remove("audioUploaded");});state=FilesStore.state(this,r.id);}
        String textId=state.optString("textId");if(textId.isEmpty()){textId=drive.generateId();String id=textId;FilesStore.update(this,r.id,s -> s.put("textId",id));}
        if(!hash.equals(state.optString("uploadedHash"))){
            FilesStore.status(this,r.id,"Subiendo transcripción a Drive…");check(r,http);
            drive.upload(textId,safeName(latest.title)+".txt",folder,"text/plain; charset=UTF-8",HttpApi.bytes(text));
            FilesStore.update(this,r.id,s -> s.put("uploadedHash",hash));
        }
        if(settings.includeAudio()){
            state=FilesStore.state(this,r.id);String audioId=state.optString("audioId");if(audioId.isEmpty()){audioId=drive.generateId();String id=audioId;FilesStore.update(this,r.id,s -> s.put("audioId",id));}
            if(!state.optBoolean("audioUploaded")){
                FilesStore.status(this,r.id,"Subiendo audio a Drive…");check(r,http);
                drive.upload(audioId,safeName(latest.title)+".m4a",folder,"audio/mp4",http.file(r.audio(this)));
                FilesStore.update(this,r.id,s -> s.put("audioUploaded",true).put("audioTitle",latest.title));
            }else if(!latest.title.equals(state.optString("audioTitle"))){drive.rename(audioId,safeName(latest.title)+".m4a");FilesStore.update(this,r.id,s -> s.put("audioTitle",latest.title));}
        }
        check(r,http);Recording current=FilesStore.recording(this,r.id);if(current==null)return;
        boolean changed=!hash.equals(hash(Transcript.load(this,r.id).text(current).getBytes(StandardCharsets.UTF_8)));
        FilesStore.update(this,r.id,s -> s.put("requested",changed).put("uploadPending",changed).put("attempts",0).put("status",changed?"Cambios nuevos · pendiente de sincronizar":"Sincronizado con Drive"));
    }
    static String hash(byte[] data)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    static String safeName(String title){return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");}
}
