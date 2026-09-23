package cl.vozlocal.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Motor de transcripción compartido por TranscribeService (primer plano, funciona con el teléfono bloqueado)
 * y PipelineJob (tarea diferida de Android, espera Wi-Fi/cargador). Solo uno corre a la vez.
 *
 * Audios largos: bloques cortados en pausas, enviados de a {@link #PARALLEL} en paralelo. Con separación de voces,
 * el bloque 1 va primero y de él se sacan muestras de voz para reconocer a las mismas personas en los demás.
 * Cada paso y cada métrica (tiempos, tokens, datos, costo estimado) quedan en el estado de la grabación.
 */
final class Transcriber {
    static final java.util.concurrent.locks.ReentrantLock RUNNING=new java.util.concurrent.locks.ReentrantLock();
    static final int NOTIFICATION=9,DONE_NOTIFICATION=10,PARALLEL=3;
    /** Duración objetivo de cada bloque: más corto con voces (el modelo es más lento), más largo sin voces. */
    static final long BLOCK_SPEAKERS_MS=5*60_000,BLOCK_TEXT_MS=8*60_000;
    /** La tarea diferida cede el turno antes de este tiempo para que Android no la corte a mitad de un envío. */
    static final long JOB_BUDGET_MS=6*60*1000;
    static final class Yield extends Exception{Yield(){super("Pausa corta");}}

    private final Context c;private final HttpApi http;private final long budgetMs;private final long started=System.currentTimeMillis();
    private final Map<Integer,long[]> uploads=new ConcurrentHashMap<>();private volatile long lastProgress;
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
                    Pipeline.log(c,r.id,"Intento "+attempts+" de 5 falló: "+reason+(again?" · se reintentará (los bloques ya listos no se vuelven a enviar)":" · pulsa Reintentar"));
                    Diagnostics.event("job_retry",r.id,"count",attempts,"error_class",e.getClass().getSimpleName());
                }
            }
            retry|=Pipeline.pending(c);
        }catch(Exception e){retry=true;Diagnostics.event("pipeline_failure",null,"error_class",e.getClass().getSimpleName());}
        return retry;
    }
    /** Traduce fallos técnicos a una causa comprensible. */
    private String describe(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "el proveedor no respondió a tiempo (el intento pudo cobrarse)";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "sin conexión con el servidor";
        if(e instanceof javax.net.ssl.SSLException)return "la conexión segura se interrumpió";
        if(e instanceof java.io.InterruptedIOException)return "Android pausó el trabajo";
        String m=e.getMessage();return m!=null&&m.length()<160?m:"error de red ("+e.getClass().getSimpleName()+")";
    }
    private void check(Recording r)throws Exception{http.check();if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");}
    private void stage(Recording r,String status,int percent){Pipeline.log(c,r.id,status);notice(status,true,percent);}

    private void process(Recording r)throws Exception{
        check(r);long start=System.currentTimeMillis();Settings settings=new Settings(c);
        JSONObject initial=FilesStore.state(c,r.id);boolean wantSpeakers=initial.has("speakers")?initial.optBoolean("speakers"):settings.defaultSpeakers();
        if(!Transcript.exists(c,r.id)){
            ProviderConfig config=settings.config(wantSpeakers);if(config.key.isEmpty())throw new HttpApi.UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.");
            long target=config.speakers?BLOCK_SPEAKERS_MS:BLOCK_TEXT_MS;boolean compress=!Pipeline.unmetered(c);
            String profile=config.fingerprint()+settings.language()+"|v2|"+target;
            if(!profile.equals(FilesStore.state(c,r.id).optString("profile"))){
                java.io.File[] checkpoints=Recording.directory(c).listFiles((dir,name)->name.startsWith(r.id+".part")&&name.endsWith(".json"));if(checkpoints!=null)for(java.io.File file:checkpoints)file.delete();
                FilesStore.update(c,r.id,s->s.put("profile",profile).put("blocksDone",0).put("doneAudioMs",0).put("bytesSent",0).put("inTokens",0).put("outTokens",0).put("usageSec",0).put("blockMsSum",0).put("blockCount",0));
            }
            FilesStore.update(c,r.id,s->s.put("model",config.model).put("speakers",config.speakers).put("audioMs",r.duration).put("provider",config.provider));
            Diagnostics.event("job_start",r.id,"provider",config.provider,"model",config.model,"bytes",r.audio(c).length(),"duration_ms",r.duration);
            stage(r,"Preparando audio"+(compress?" · comprimido para ahorrar datos móviles":""),-1);
            List<AudioParts.Part> parts=AudioParts.plan(c,r,http,target,compress,line->Pipeline.log(c,r.id,line));
            int n=parts.size();FilesStore.update(c,r.id,s->s.put("blocks",n));
            if(n>1)Pipeline.log(c,r.id,"Audio de "+Recording.time(r.duration)+" dividido en "+n+" bloques de ~"+(target/60000)+" min, cortados en pausas · se envían de a "+PARALLEL+" en paralelo");
            JSONObject[] responses=new JSONObject[n];
            try{
                List<String[]> references=null;int from=0;
                if(config.speakers&&n>1){
                    // El bloque 1 va solo: de él salen las muestras de voz para los demás.
                    responses[0]=block(r,config,settings,parts,0,null);from=1;
                    check(r);references=AudioParts.references(c,r,responses[0],http);
                    if(!references.isEmpty())Pipeline.log(c,r.id,"Muestras de voz: "+references.size()+(references.size()==1?" persona":" personas")+" del bloque 1 · se usan para reconocerlas en los demás bloques");
                }
                if(budgetMs>0&&from<n&&System.currentTimeMillis()-started>budgetMs&&!allDone(r,from,n))throw new Yield();
                List<String[]> refs=references;ExecutorService pool=Executors.newFixedThreadPool(PARALLEL);List<Future<JSONObject>> futures=new ArrayList<>();
                try{
                    for(int i=from;i<n;i++){int index=i;futures.add(pool.submit(()->block(r,config,settings,parts,index,refs)));}
                    Exception first=null;
                    for(int i=from;i<n;i++){try{responses[i]=futures.get(i-from).get();}catch(ExecutionException e){if(first==null)first=e.getCause() instanceof Exception?(Exception)e.getCause():e;}}
                    if(first!=null)throw first;
                }finally{pool.shutdownNow();}
                check(r);Transcript transcript=Transcript.fromParts(Arrays.asList(responses),offsets(parts));transcript.data.put("provider",config.provider).put("model",config.model);transcript.save(c,r.id);
            }finally{http.onUploaded=null;http.onProgress=null;for(AudioParts.Part part:parts)if(!part.file.equals(r.audio(c)))part.file.delete();}
        }
        check(r);long queued=FilesStore.state(c,r.id).optLong("queuedAt",start);long total=System.currentTimeMillis()-queued;
        FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",false).put("attempts",0).put("doneIn",total).put("upSent",0).put("upTotal",0).put("doneAudioMs",r.duration));
        Pipeline.log(c,r.id,"Transcripción lista · tiempo total "+Recording.time(total));
        LocalStorage.enqueue(c,r.id);notice("Transcripción lista · «"+r.title+"»",false,-1);Diagnostics.event("job_complete",r.id,"elapsed_ms",System.currentTimeMillis()-start);
    }
    private boolean allDone(Recording r,int from,int n){for(int i=from;i<n;i++)if(!FilesStore.file(c,r.id,".part"+i+".json").exists())return false;return true;}
    private static List<Double> offsets(List<AudioParts.Part> parts){List<Double> o=new ArrayList<>();for(AudioParts.Part p:parts)o.add(p.offset);return o;}

    /** Envía un bloque (o lo lee del punto de control si ya estaba listo) y registra sus métricas. */
    private JSONObject block(Recording r,ProviderConfig config,Settings settings,List<AudioParts.Part> parts,int i,List<String[]> refs)throws Exception{
        int n=parts.size();java.io.File checkpoint=FilesStore.file(c,r.id,".part"+i+".json");
        if(checkpoint.exists())return FilesStore.read(checkpoint);
        check(r);AudioParts.Part part=parts.get(i);String label=n>1?"bloque "+(i+1)+" de "+n:"audio";
        HttpApi h=http.child();h.jobId=r.id;long partMs=part.durationMs>0?part.durationMs:r.duration;
        // La espera escala con la duración: un bloque con separación de voces puede tardar varios minutos.
        h.readTimeoutMs=(int)Math.min(20*60_000L,Math.max(240_000L,120_000L+partMs));
        long blockStart=System.currentTimeMillis();
        stage(r,"Enviando "+label,0);
        h.onProgress=(sent,total)->progress(r,i,sent,total,label);
        h.onUploaded=()->{uploads.remove(i);publishUploads(r);Pipeline.log(c,r.id,(n>1?"Bloque "+(i+1)+" enviado":"Audio enviado")+" · esperando respuesta del proveedor");};
        OpenAiClient.Delta delta=chars->liveText(r,i,chars);
        JSONObject response;
        try{response=new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta);}
        catch(HttpApi.UserAction e){
            if(refs==null||refs.isEmpty()||!String.valueOf(e.getMessage()).contains("known_speaker"))throw e;
            Pipeline.log(c,r.id,"El proveedor rechazó las muestras de voz · se reenvía el "+label+" sin ellas");
            response=new OpenAiClient(h).transcribe(part.file,config,settings.language(),null,delta);refs=null;
        }
        check(r);
        if(refs!=null&&!refs.isEmpty()){JSONArray known=new JSONArray();for(String[] ref:refs)known.put(ref[0]);response.put("_known",known);}
        synchronized(FilesStore.LOCK){if(r.audio(c).exists())FilesStore.write(checkpoint,response);}
        long took=System.currentTimeMillis()-blockStart;long bytes=part.file.length();JSONObject usage=response.optJSONObject("usage");
        FilesStore.update(c,r.id,s->{
            s.put("blocksDone",s.optInt("blocksDone")+1).put("doneAudioMs",s.optLong("doneAudioMs")+partMs).put("bytesSent",s.optLong("bytesSent")+bytes)
             .put("blockMsSum",s.optLong("blockMsSum")+took).put("blockCount",s.optInt("blockCount")+1);
            if(usage!=null){
                if("duration".equals(usage.optString("type")))s.put("usageSec",s.optDouble("usageSec",0)+usage.optDouble("seconds",0));
                else s.put("inTokens",s.optLong("inTokens")+usage.optLong("input_tokens")).put("outTokens",s.optLong("outTokens")+usage.optLong("output_tokens"));
            }
        });
        Pipeline.log(c,r.id,(n>1?"Bloque "+(i+1)+" de "+n+" listo":"Respuesta recibida")+" · tardó "+Recording.time(took));
        Diagnostics.event("part_complete",r.id,"part",i+1,"parts",n,"elapsed_ms",took);
        JSONObject st=FilesStore.state(c,r.id);notice("Transcribiendo · "+st.optInt("blocksDone")+" de "+n+" bloques listos",true,(int)(st.optLong("doneAudioMs")*100/Math.max(1,r.duration)));
        return response;
    }
    /** Progreso de subida sumado entre bloques en paralelo; se guarda cada ~0,7 s. */
    private void progress(Recording r,int block,long sent,long total,String label){
        uploads.put(block,new long[]{sent,total});long now=System.currentTimeMillis();if(now-lastProgress<700&&sent<total)return;lastProgress=now;
        publishUploads(r);long[] sum=sum();notice("Enviando "+label,true,(int)(sum[0]*100/Math.max(1,sum[1])));
    }
    private long[] sum(){long s=0,t=0;for(long[] u:uploads.values()){s+=u[0];t+=u[1];}return new long[]{s,t};}
    private void publishUploads(Recording r){long[] sum=sum();try{FilesStore.update(c,r.id,s->s.put("upSent",sum[0]).put("upTotal",sum[1]));}catch(Exception ignored){}}
    private volatile long lastLive;
    /** Texto recibido en vivo (modelo con streaming). */
    private void liveText(Recording r,int block,int chars){long now=System.currentTimeMillis();if(now-lastLive<800)return;lastLive=now;try{FilesStore.update(c,r.id,s->s.put("liveChars",chars));}catch(Exception ignored){}}

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
