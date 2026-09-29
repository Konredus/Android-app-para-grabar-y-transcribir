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
    /** Duración objetivo de cada bloque sin voces. */
    static final long BLOCK_TEXT_MS=8*60_000;
    /**
     * Con voces, bloques parejos de hasta 12 min (antes 5): cada unión entre bloques es una oportunidad de que una persona
     * cambie de etiqueta, así que conviene tener pocas. El modelo acepta hasta 1400 s por envío.
     * 26 min → 3 bloques de ~8:40 (antes 5 de 5 min).
     */
    static final long SPEAKER_BLOCK_MAX_MS=12*60_000;
    static long speakerBlockMs(long totalMs,long bytes){int n=(int)Math.max(Math.ceil(totalMs/(double)SPEAKER_BLOCK_MAX_MS),Math.ceil(bytes/19_000_000d));return n<=1?Math.max(1,totalMs):totalMs/n;}
    /** Máximo de voces conocidas por envío (límite de la API). */
    static final int MAX_KNOWN=4;
    /** La tarea diferida cede el turno antes de este tiempo para que Android no la corte a mitad de un envío. */
    static final long JOB_BUDGET_MS=6*60*1000;
    static final class Yield extends Exception{Yield(){super("Pausa corta");}}
    /** Cortes del propio teléfono que se reintentan sin gastar intentos; pasado este número sí cuentan. */
    static final int MAX_LOCAL_CUTS=12;
    /** Vigilante de conexiones: mide con elapsedRealtime, que avanza aunque Android congele la app. */
    private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"VozLocal-watchdog");t.setDaemon(true);return t;});
    /** Sin avance del envío durante este tiempo, se corta y se reintenta. */
    static final long UPLOAD_STALL_MS=90_000;
    /** Espera máxima de la respuesta tras el envío: lo que dura el audio más 1 min (mín. 3, máx. 20). OpenAI suele tardar la mitad. */
    static long responseLimit(long partMs){return Math.min(20*60_000L,Math.max(3*60_000L,partMs+60_000L));}
    /** En la última ronda hubo un corte del propio teléfono (el servicio reintenta pronto, sin esperas largas). */
    volatile boolean sawLocalCut;
    private volatile long frozenLoggedAt;
    /** En esta ronda ya se envió al menos un bloque (la tarea de fondo solo cede el turno después de avanzar). */
    private final java.util.concurrent.atomic.AtomicBoolean sentThisRun=new java.util.concurrent.atomic.AtomicBoolean();

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
                if(budgetMs>0&&System.currentTimeMillis()-started>budgetMs){retry=true;break;}
                http.jobId=r.id;
                try{process(r);}
                catch(Yield y){retry=true;Pipeline.log(c,r.id,"Pausa corta para no exceder el límite de Android · continúa enseguida");break;}
                catch(HttpApi.UserAction e){FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",true));Pipeline.log(c,r.id,e.getMessage());notice("La transcripción necesita atención",false,-1);Diagnostics.event("job_rejected",r.id,"error_class",e.getClass().getSimpleName());}
                catch(Exception e){
                    if(http.cancelled)break;
                    if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))continue;
                    JSONObject st=FilesStore.state(c,r.id);int cuts=st.optInt("localCuts",0)+(localCut(e)?1:0);
                    // Un corte hecho por el propio teléfono (pantalla bloqueada, ahorro de batería) no es culpa del proveedor:
                    // no gasta uno de los 5 intentos, salvo que se repita demasiado.
                    boolean local=localCut(e)&&cuts<=MAX_LOCAL_CUTS;sawLocalCut|=local;
                    int attempts=st.optInt("attempts",0)+(local?0:1);boolean again=attempts<5;retry|=again;
                    String reason=describe(e);
                    FilesStore.update(c,r.id,s->s.put("attempts",attempts).put("retries",s.optInt("retries")+1).put("localCuts",cuts).put("requested",again).put("failed",!again).put("lastError",reason));
                    String hint=local&&!Battery.unrestricted(c)?" · para evitarlo, permite a Voz local usar batería en segundo plano":"";
                    Pipeline.log(c,r.id,local?"El teléfono cortó la conexión ("+reason+") · se reintenta sin gastar un intento"+hint
                        :"Intento "+attempts+" de 5 falló: "+reason+(again?" · se reintentará (los bloques ya listos no se vuelven a enviar)":" · pulsa Reintentar"));
                    Diagnostics.event("job_retry",r.id,"count",attempts,"error_class",e.getClass().getSimpleName(),"reason",HttpApi.safeReason(e),"net",Pipeline.networkName(c),
                        "local",local,"display",Battery.screenOn(c)?"on":"off","idle",Battery.idle(c),"battery",Battery.unrestricted(c)?"unrestricted":"optimized");
                }
            }
            retry|=Pipeline.pending(c);
        }catch(Exception e){retry=true;Diagnostics.event("pipeline_failure",null,"error_class",e.getClass().getSimpleName());}
        return retry;
    }
    /** Traduce fallos técnicos a una causa comprensible. */
    private String describe(Exception e){
        if(e instanceof HttpApi.Stalled)return e.getMessage();
        if(localCut(e))return "pantalla bloqueada o ahorro de batería";
        if(e instanceof java.net.SocketTimeoutException)return "el proveedor no respondió a tiempo (el intento pudo cobrarse)";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "sin conexión con el servidor";
        if(e instanceof javax.net.ssl.SSLException)return "la conexión segura se interrumpió";
        if(e instanceof java.io.InterruptedIOException)return "Android pausó el trabajo";
        String m=e.getMessage();return m!=null&&m.length()<160?m:"error de red ("+e.getClass().getSimpleName()+")";
    }
    /** Conexión cortada por el propio teléfono ("Software caused connection abort") o por el vigilante tras una congelación. */
    static boolean localCut(Throwable e){
        for(Throwable t=e;t!=null;t=t.getCause()){
            if(t instanceof HttpApi.Stalled)return true;
            String m=t.getMessage();if(t instanceof java.net.SocketException&&m!=null&&m.toLowerCase(Locale.ROOT).contains("abort"))return true;
        }
        return false;
    }
    private void check(Recording r)throws Exception{http.check();if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");}
    private void stage(Recording r,String status,int percent){Pipeline.log(c,r.id,status);notice(status,true,percent);}

    private void process(Recording r)throws Exception{
        check(r);long start=System.currentTimeMillis();Settings settings=new Settings(c);
        JSONObject initial=FilesStore.state(c,r.id);boolean wantSpeakers=initial.has("speakers")?initial.optBoolean("speakers"):settings.defaultSpeakers();
        if(!Transcript.exists(c,r.id)){
            ProviderConfig config=settings.config(wantSpeakers);if(config.key.isEmpty())throw new HttpApi.UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.");
            long target=config.speakers?speakerBlockMs(r.duration>0?r.duration:AudioConvert.duration(r.audio(c)),r.audio(c).length()):BLOCK_TEXT_MS;
            // "Mi voz" va como voz conocida en todos los bloques (también el primero). Si cambia la muestra, no se reutilizan bloques ya transcritos.
            String[] mine=config.speakers&&config.provider.equals("openai")?Voices.reference(c):null;
            String profile=config.fingerprint()+settings.language()+"|v3|"+target+"|"+(mine==null?"none":Voices.fingerprint(c));
            if(!profile.equals(FilesStore.state(c,r.id).optString("profile"))){
                java.io.File[] checkpoints=Recording.directory(c).listFiles((dir,name)->name.startsWith(r.id+".part")&&name.endsWith(".json"));if(checkpoints!=null)for(java.io.File file:checkpoints)file.delete();
                AudioParts.clearBlocks(c,r.id);
                FilesStore.update(c,r.id,s->s.put("profile",profile).remove("cuts"));
                FilesStore.update(c,r.id,s->s.put("blocksDone",0).put("doneAudioMs",0).put("bytesSent",0).put("inTokens",0).put("outTokens",0).put("usageSec",0).put("blockMsSum",0).put("blockCount",0));
            }
            FilesStore.update(c,r.id,s->s.put("model",config.model).put("speakers",config.speakers).put("audioMs",r.duration).put("provider",config.provider));
            Diagnostics.event("job_start",r.id,"provider",config.provider,"model",config.model,"bytes",r.audio(c).length(),"duration_ms",r.duration,"net",Pipeline.networkName(c),"runner",budgetMs>0?"job":"fgs");
            stage(r,"Preparando audio",-1);long prepStart=System.currentTimeMillis();
            List<Long> cuts=new ArrayList<>();
            List<AudioParts.Part> parts=AudioParts.plan(c,r,http,target,FilesStore.state(c,r.id).optJSONArray("cuts"),cuts,line->Pipeline.log(c,r.id,line));
            JSONArray savedCuts=new JSONArray();for(Long cut:cuts)savedCuts.put(cut);FilesStore.update(c,r.id,s->s.put("cuts",savedCuts));
            Diagnostics.event("prepare_done",r.id,"parts",parts.size(),"elapsed_ms",System.currentTimeMillis()-prepStart);
            int n=parts.size();FilesStore.update(c,r.id,s->s.put("blocks",n));
            if(n>1)Pipeline.log(c,r.id,"Audio de "+Recording.time(r.duration)+" dividido en "+n+" bloques de ~"+(target/60000)+" min, cortados en pausas · se envían de a "+PARALLEL+" en paralelo");
            JSONObject[] responses=new JSONObject[n];
            try{
                List<String[]> own=mine==null?null:Collections.singletonList(mine);
                if(mine!=null)Pipeline.log(c,r.id,"Tu voz («"+Voices.name(c)+"») va como muestra en "+(n>1?"todos los bloques":"el envío")+" para reconocerte desde el inicio");
                List<String[]> references=own;int from=0;
                if(config.speakers&&n>1){
                    // El bloque 1 va solo: de él salen las muestras de voz para los demás.
                    responses[0]=block(r,config,settings,parts,0,own);from=1;
                    check(r);
                    // Si el bloque 1 ya reconoció al usuario con "Mi voz", no hace falta muestra automática de él.
                    Set<String> exclude=new HashSet<>();if(mine!=null)exclude.add(Voices.MINE);
                    List<String[]> auto=AudioParts.references(c,r,responses[0],http,MAX_KNOWN-(mine==null?0:1),exclude);
                    references=new ArrayList<>();if(mine!=null)references.add(mine);references.addAll(auto);
                    if(!auto.isEmpty()){StringBuilder which=new StringBuilder();for(String[] ref:auto)which.append(which.length()==0?"":", ").append(ref[3]);
                        Pipeline.log(c,r.id,"Muestras de voz del bloque 1: "+which+" · se usan para reconocer a las mismas personas en los demás bloques");}
                    else if(mine==null)Pipeline.log(c,r.id,"El bloque 1 no tiene tramos limpios para muestras de voz · cada bloque separa voces por su cuenta");
                }
                if(budgetMs>0&&from<n&&System.currentTimeMillis()-started>budgetMs&&!allDone(r,from,n))throw new Yield();
                List<String[]> refs=references;ExecutorService pool=Executors.newFixedThreadPool(PARALLEL);List<Future<JSONObject>> futures=new ArrayList<>();
                try{
                    for(int i=from;i<n;i++){int index=i;futures.add(pool.submit(()->block(r,config,settings,parts,index,refs)));}
                    Exception first=null;
                    for(int i=from;i<n;i++){try{responses[i]=futures.get(i-from).get();}catch(ExecutionException e){if(first==null)first=e.getCause() instanceof Exception?(Exception)e.getCause():e;}}
                    if(first!=null)throw first;
                }finally{pool.shutdownNow();}
                check(r);Transcript transcript=Transcript.fromParts(Arrays.asList(responses),offsets(parts));transcript.data.put("provider",config.provider).put("model",config.model);
                if(mine!=null&&transcript.speakers().containsKey(Voices.ME))transcript.data.getJSONObject("names").put(Voices.ME,Voices.name(c));
                transcript.save(c,r.id);
            }finally{http.onUploaded=null;http.onProgress=null;}
            // Los bloques se conservan entre intentos; se borran solo con la transcripción ya guardada.
            AudioParts.clearBlocks(c,r.id);
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
        // Tarea de fondo: Android la corta a los ~10 min. Si este bloque no alcanza a volver, se cede el turno antes de enviarlo.
        // Nunca antes del primer envío de la ronda: así cada ronda avanza al menos un bloque.
        if(budgetMs>0){long partEstimate=90_000+(long)(0.6*(part.durationMs>0?part.durationMs:r.duration));if(sentThisRun.get()&&System.currentTimeMillis()-started+partEstimate>9*60_000)throw new Yield();}
        sentThisRun.set(true);
        HttpApi h=http.child();h.jobId=r.id;long partMs=part.durationMs>0?part.durationMs:r.duration;
        // La espera escala con la duración: un bloque con separación de voces puede tardar varios minutos.
        h.readTimeoutMs=(int)Math.min(20*60_000L,Math.max(240_000L,120_000L+partMs));
        long blockStart=System.currentTimeMillis();
        stage(r,"Enviando "+label,0);
        h.onProgress=(sent,total)->progress(r,i,sent,total,label);
        java.util.concurrent.atomic.AtomicBoolean uploaded=new java.util.concurrent.atomic.AtomicBoolean();long limit=responseLimit(partMs);
        h.onUploaded=()->{uploaded.set(true);uploads.remove(i);publishUploads(r);Pipeline.log(c,r.id,(n>1?"Bloque "+(i+1)+" enviado":"Audio enviado")+(config.provider.equals("openai")?" · OpenAI está transcribiendo":" · el servidor está transcribiendo"));};
        OpenAiClient.Delta delta=chars->liveText(r,i,chars);
        JSONObject response;
        // Vigilante: si el envío deja de avanzar o la respuesta tarda mucho más de lo normal (p. ej. el teléfono congeló
        // la app con la pantalla bloqueada), corta y reintenta en vez de quedar colgado media hora.
        long[] lastTick={android.os.SystemClock.elapsedRealtime()};
        ScheduledFuture<?> guard=WATCHDOG.scheduleWithFixedDelay(()->{
            long now=android.os.SystemClock.elapsedRealtime(),gap=now-lastTick[0];lastTick[0]=now;
            if(gap>30_000&&now-frozenLoggedAt>30_000){frozenLoggedAt=now;Pipeline.log(c,r.id,"Android tuvo la app congelada "+Recording.time(gap)+" (ahorro de batería con la pantalla bloqueada)");Diagnostics.event("app_frozen",r.id,"elapsed_ms",gap,"display",Battery.screenOn(c)?"on":"off","battery",Battery.unrestricted(c)?"unrestricted":"optimized");}
            if(h.lastActivity==0)return;long idle=now-h.lastActivity;
            if(!uploaded.get()&&idle>UPLOAD_STALL_MS)h.abortStalled("el envío dejó de avanzar");
            else if(uploaded.get()&&idle>limit)h.abortStalled("sin respuesta en "+Recording.time(idle)+", lo normal es menos de "+Recording.time(limit));
        },5,5,TimeUnit.SECONDS);
        try{
            try{response=new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta);}
            catch(HttpApi.UserAction e){
                if(refs==null||refs.isEmpty()||!String.valueOf(e.getMessage()).contains("known_speaker"))throw e;
                Pipeline.log(c,r.id,"El proveedor rechazó las muestras de voz · se reenvía el "+label+" sin ellas");
                uploaded.set(false);response=new OpenAiClient(h).transcribe(part.file,config,settings.language(),null,delta);refs=null;
            }
        }finally{guard.cancel(false);}
        check(r);
        // "_known": qué nombre enviado corresponde a qué voz (se usa al unir bloques; ver Transcript.fromParts).
        JSONObject known=new JSONObject();if(refs!=null)for(String[] ref:refs)known.put(ref[0],ref.length>2?ref[2]:"block0:"+ref[0]);if(known.length()>0)response.put("_known",known);
        String voices=describeVoices(response,known);
        synchronized(FilesStore.LOCK){if(r.audio(c).exists())FilesStore.write(checkpoint,response);}
        long took=System.currentTimeMillis()-blockStart;long bytes=part.file.length();JSONObject usage=response.optJSONObject("usage");
        FilesStore.update(c,r.id,s->{
            s.put("localCuts",0).put("blocksDone",s.optInt("blocksDone")+1).put("doneAudioMs",s.optLong("doneAudioMs")+partMs).put("bytesSent",s.optLong("bytesSent")+bytes)
             .put("blockMsSum",s.optLong("blockMsSum")+took).put("blockCount",s.optInt("blockCount")+1);
            if(usage!=null){
                if("duration".equals(usage.optString("type")))s.put("usageSec",s.optDouble("usageSec",0)+usage.optDouble("seconds",0));
                else s.put("inTokens",s.optLong("inTokens")+usage.optLong("input_tokens")).put("outTokens",s.optLong("outTokens")+usage.optLong("output_tokens"));
            }
        });
        Pipeline.log(c,r.id,(n>1?"Bloque "+(i+1)+" de "+n+" listo":"Respuesta recibida")+" · tardó "+Recording.time(took)+voices);
        Diagnostics.event("part_complete",r.id,"part",i+1,"parts",n,"elapsed_ms",took);
        JSONObject st=FilesStore.state(c,r.id);notice("Transcribiendo · "+st.optInt("blocksDone")+" de "+n+" bloques listos",true,(int)(st.optLong("doneAudioMs")*100/Math.max(1,r.duration)));
        return response;
    }
    /** Resumen de voces de un bloque para la bitácora: cuántas reconoció por muestra y cuántas son nuevas. */
    private static String describeVoices(JSONObject response,JSONObject known){
        JSONArray s=response.optJSONArray("segments");if(s==null||!response.optBoolean("_diarized",true))return "";
        Set<String> recognized=new HashSet<>(),fresh=new HashSet<>();
        for(int k=0;k<s.length();k++){JSONObject seg=s.optJSONObject(k);if(seg==null||seg.optString("text").trim().isEmpty())continue;String sp=seg.optString("speaker");if(known.has(sp))recognized.add(known.optString(sp));else fresh.add(sp);}
        if(known.length()==0)return fresh.isEmpty()?"":" · "+fresh.size()+(fresh.size()==1?" voz":" voces");
        return " · reconoció "+recognized.size()+(recognized.size()==1?" voz":" voces")+(fresh.isEmpty()?"":", "+fresh.size()+(fresh.size()==1?" nueva":" nuevas"));
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
