package cl.vozlocal.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Motor de transcripción compartido por TranscribeService (primer plano, funciona con el teléfono bloqueado)
 * y PipelineJob (tarea diferida de Android, espera Wi-Fi/cargador). Solo uno corre a la vez.
 *
 * Audios largos: partes cortadas en pausas, enviadas de a {@link #PARALLEL} en paralelo. Con separación de voces,
 * la parte 1 va primero y de ella se sacan muestras de voz para reconocer a las mismas personas en las demás.
 * Cada paso y cada métrica (tiempos, tokens, datos, costo estimado) quedan en el estado de la grabación.
 * (En el código y en el estado se llaman "bloques"; al usuario se le dice "partes".)
 *
 * 0.6.0: «Volver a transcribir» (ver {@link Retranscribe}): la segunda pasada con tus correcciones envía muestras de la
 * versión anterior a TODAS las partes desde el inicio; «sin cortar» manda el audio completo en un solo envío. Al terminar,
 * la «Nota para tu segundo cerebro» se arma sola (si está activada) y la notificación «lista» abre esa grabación.
 *
 * 0.8.0: el motor no sabe de proveedores. Cada envío lo hace el cliente que corresponde ({@link TranscribeClient#of}):
 * OpenAI (y servidor compatible) u OpenRouter. Con OpenRouter cambian cuatro cosas, todas aquí a la vista: el tamaño de
 * los bloques sale de la receta del modelo, las muestras de voz viajan como «anclas» (mismo flujo de voces conocidas),
 * el costo real de cada envío se suma en el estado ("costUsd") y un 413 baja los bloques a la mitad una vez ("orHalf").
 */
final class Transcriber {
    static final java.util.concurrent.locks.ReentrantLock RUNNING=new java.util.concurrent.locks.ReentrantLock();
    static final int NOTIFICATION=9,DONE_NOTIFICATION=10,PARALLEL=3;
    /** Canal de «Transcripción lista»: importancia normal, suena una vez. */
    static final String DONE_CHANNEL="done";
    /** Duración objetivo de cada bloque sin voces. */
    static final long BLOCK_TEXT_MS=8*60_000;
    /**
     * Con voces, bloques parejos de hasta 12 min (antes 5): cada unión entre bloques es una oportunidad de que una persona
     * cambie de etiqueta, así que conviene tener pocas. El modelo acepta hasta 1400 s por envío.
     * 26 min → 3 bloques de ~8:40 (antes 5 de 5 min).
     */
    static final long SPEAKER_BLOCK_MAX_MS=12*60_000;
    static long speakerBlockMs(long totalMs,long bytes){int n=(int)Math.max(Math.ceil(totalMs/(double)SPEAKER_BLOCK_MAX_MS),Math.ceil(bytes/19_000_000d));return n<=1?Math.max(1,totalMs):totalMs/n;}
    // ---------- 0.8.0: OpenRouter ----------
    /** Lugar que se deja a las anclas (hasta 4 muestras de ~10 s más sus silencios) dentro del tope del modelo. */
    static final long OR_ANCHOR_ROOM_MS=60_000;
    /**
     * OpenRouter: duración máxima de un bloque. Con voces, 12 min como con OpenAI; sin voces, 8. Nunca más de lo que
     * acepta el modelo según su receta (p. ej. un modelo que solo recibe 10 min), dejando lugar para las anclas.
     */
    static long orBlockMax(Models.Recipe recipe,boolean speakers){return Math.max(60_000L,Math.min(speakers?SPEAKER_BLOCK_MAX_MS:BLOCK_TEXT_MS,recipe.maxMs-OR_ANCHOR_ROOM_MS));}
    /**
     * OpenRouter: bloques parejos de hasta maxMs. El peso del m4a no cuenta (se envía FLAC, cuyo peso depende solo de la
     * duración). half: el proveedor respondió 413 (envío muy grande) y los bloques bajan a la mitad.
     */
    static long orBlockMs(long totalMs,long maxMs,boolean half){
        // Sin duración conocida se usa el tope (AudioParts.plan mide el audio y corta igual).
        if(totalMs<=0)return half?Math.max(1,maxMs/2):maxMs;
        int n=(int)Math.ceil(totalMs/(double)Math.max(1,maxMs));long block=n<=1?totalMs:totalMs/n;return half?Math.max(1,block/2):block;
    }
    /** Máximo de voces conocidas por envío (límite de la API). */
    static final int MAX_KNOWN=4;
    /** La tarea diferida cede el turno antes de este tiempo para que Android no la corte a mitad de un envío. */
    static final long JOB_BUDGET_MS=6*60*1000;
    /** Lo que la tarea de fondo se permite gastar en un envío: Android la corta a los 10 min. */
    static final long JOB_SEND_LIMIT_MS=9*60_000;
    /** Cuánto puede tardar el envío de una parte (subida + respuesta), para decidir si cabe en la tarea de fondo. */
    static long sendEstimate(long partMs){return 90_000+(long)(0.6*partMs);}
    static final class Yield extends Exception{Yield(){super("Pausa corta");}}
    /**
     * Un envío que ni solo cabe en la tarea de fondo (p. ej. «sin cortar» de 20 min): no se manda desde ahí, porque
     * Android lo cortaría a mitad y cada intento volvería a subir (y quizá a cobrar) el audio completo. Queda pedido y lo
     * hace el servicio en primer plano al abrir la app.
     */
    static final class NeedsForeground extends Exception{NeedsForeground(String what){super(what);}}
    /** Grabaciones que esta ronda dejó para el primer plano (la tarea de fondo no se reprograma por ellas). */
    private final Set<String> waitingForeground=ConcurrentHashMap.newKeySet();
    /** La primera grabación que quedó esperando la app abierta, o null. */
    String waitingForeground(){for(String id:waitingForeground)return id;return null;}
    /** Cortes del propio teléfono que se reintentan sin gastar intentos; pasado este número sí cuentan. */
    static final int MAX_LOCAL_CUTS=12;
    /** Vigilante de conexiones: mide con elapsedRealtime, que avanza aunque Android congele la app. */
    private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"VozLocal-watchdog");t.setDaemon(true);return t;});
    /** Sin avance del envío durante este tiempo, se corta y se reintenta. */
    static final long UPLOAD_STALL_MS=90_000;
    /** Espera máxima de la respuesta tras el envío: lo que dura el audio más 1 min (mín. 3, máx. 20). OpenAI suele tardar la mitad. */
    static long responseLimit(long partMs){return Math.min(20*60_000L,Math.max(3*60_000L,partMs+60_000L));}
    /**
     * Con OpenRouter la espera no pasa de 5 min: sus proveedores cortan a los ~60 s de proceso, así que una respuesta que
     * no llegó en ese tiempo ya no va a llegar (esperar 13 min por un bloque de 12 solo demoraría el reintento).
     */
    static long responseLimit(String provider,long partMs){long limit=responseLimit(partMs);return "openrouter".equals(provider)?Math.min(limit,5*60_000L):limit;}
    /** Grabación que se está transcribiendo: la notificación de avance abre su detalle. */
    static volatile String currentId;
    /** En la última ronda hubo un corte del propio teléfono (el servicio reintenta pronto, sin esperas largas). */
    volatile boolean sawLocalCut;
    private volatile long frozenLoggedAt;
    /** En esta ronda ya se envió al menos un bloque (la tarea de fondo solo cede el turno después de avanzar). */
    private final java.util.concurrent.atomic.AtomicBoolean sentThisRun=new java.util.concurrent.atomic.AtomicBoolean();

    private final Context c;private final HttpApi http;private final long budgetMs;private final long started=System.currentTimeMillis();
    private final Map<Integer,long[]> uploads=new ConcurrentHashMap<>();private volatile long lastProgress;
    Transcriber(Context c,HttpApi http,long budgetMs){this.c=c;this.http=http;this.budgetMs=budgetMs;Pricing.attach(c);}

    /** Procesa todas las grabaciones en cola. Devuelve true si queda trabajo pendiente para más tarde. */
    boolean runAll(){
        boolean retry=false;
        try{
            for(Recording r:Recording.list(c)){
                if(http.cancelled)break;
                JSONObject state=FilesStore.state(c,r.id);if(!state.optBoolean("requested"))continue;
                if(RecorderService.activeId!=null){retry=true;Pipeline.log(c,r.id,"En espera: hay una grabación en curso");break;}
                if(budgetMs>0&&System.currentTimeMillis()-started>budgetMs){retry=true;break;}
                http.jobId=r.id;currentId=r.id;
                try{process(r);}
                catch(Yield y){retry=true;Pipeline.log(c,r.id,"Pausa corta para no exceder el límite de Android · continúa enseguida");break;}
                catch(NeedsForeground f){
                    // Sigue pedida; no se reintenta desde aquí (daría vueltas sin avanzar): la retoma el primer plano.
                    waitingForeground.add(r.id);
                    Pipeline.log(c,r.id,f.getMessage()+" tarda más de lo que Android da a una tarea de fondo · abre la app para enviarlo (sigue aunque bloquees el teléfono)");
                    Diagnostics.event("job_needs_foreground",r.id,"runner","job");
                }
                catch(HttpApi.UserAction e){
                    FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",true));Pipeline.log(c,r.id,e.getMessage());
                    if(!keepPrevious(r))attention(r,"La transcripción necesita atención",e.getMessage());
                    Diagnostics.event("job_rejected",r.id,"error_class",e.getClass().getSimpleName());
                }
                catch(Exception e){
                    if(http.cancelled)break;
                    if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))continue;
                    JSONObject st=FilesStore.state(c,r.id);int cuts=st.optInt("localCuts",0)+(localCut(e)?1:0);
                    // Un corte hecho por el propio teléfono (pantalla bloqueada, ahorro de batería) no es culpa del proveedor:
                    // no gasta uno de los 5 intentos, salvo que se repita demasiado.
                    boolean local=localCut(e)&&cuts<=MAX_LOCAL_CUTS;sawLocalCut|=local;
                    int attempts=st.optInt("attempts",0)+(local?0:1);boolean again=attempts<5;retry|=again;
                    String reason=describe(e);
                    // «Volver a transcribir» que se rinde: vuelve la versión anterior en vez de pedir Reintentar.
                    boolean restoring=!again&&Retranscribe.hasPrevious(c,r.id)&&!Transcript.exists(c,r.id);
                    FilesStore.update(c,r.id,s->s.put("attempts",attempts).put("retries",s.optInt("retries")+1).put("localCuts",cuts).put("requested",again).put("failed",!again).put("lastError",reason));
                    String hint=local&&!Battery.unrestricted(c)?" · para evitarlo, permite a Verbapp usar batería en segundo plano":"";
                    Pipeline.log(c,r.id,local?"El teléfono cortó la conexión ("+reason+") · se reintenta sin gastar un intento"+hint
                        :"Intento "+attempts+" de 5 falló: "+reason+(again?" · se reintentará (las partes ya listas no se vuelven a enviar)":restoring?"":" · pulsa Reintentar"));
                    if(restoring)keepPrevious(r);
                    Diagnostics.event("job_retry",r.id,"count",attempts,"error_class",e.getClass().getSimpleName(),"reason",HttpApi.safeReason(e),"net",Pipeline.networkName(c),
                        "local",local,"display",Battery.screenOn(c)?"on":"off","idle",Battery.idle(c),"battery",Battery.unrestricted(c)?"unrestricted":"optimized");
                }
                finally{currentId=null;}
            }
            // Queda trabajo si hay otra grabación pedida (las que esperan la app abierta no cuentan).
            if(!retry)for(Recording r:Recording.list(c))if(!waitingForeground.contains(r.id)&&FilesStore.state(c,r.id).optBoolean("requested")){retry=true;break;}
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
    /** «Volver a transcribir» que no se pudo terminar: vuelve la versión anterior (la grabación nunca queda sin transcripción). */
    private boolean keepPrevious(Recording r){
        if(!Retranscribe.hasPrevious(c,r.id)||Transcript.exists(c,r.id))return false;
        try{Retranscribe.restore(c,r.id,"No se pudo hacer la nueva versión · se mantiene la anterior","retranscribe_failed");}catch(Exception e){return false;}
        attention(r,"No se pudo hacer la nueva versión","Se mantiene la anterior · «"+r.title+"»");
        return true;
    }
    private void check(Recording r)throws Exception{http.check();if(!r.audio(c).exists()||!FilesStore.state(c,r.id).optBoolean("requested"))throw new java.io.InterruptedIOException("Cancelado");}
    private void stage(Recording r,String status,int percent){Pipeline.log(c,r.id,status);notice(status,true,percent);}
    /** Perfil de un intento: si cambia, no se reutilizan partes ya transcritas. Cada «Volver a transcribir» suma un intento. */
    static String profile(String base,int attempt,Retranscribe.Mode mode){return attempt>0?base+"|a"+attempt+(mode==null?"":"|"+mode.name()):base;}

    /**
     * OpenRouter: si el proveedor responde 413 (el envío pesa más de lo que acepta; su tope para el JSON no está
     * documentado), los bloques bajan a la mitad y se repite de inmediato, sin gastar un intento. Una sola vez por
     * transcripción (clave de estado "orHalf"): si con la mitad tampoco cabe, el error llega al usuario.
     * (Visible en el paquete para probar el motor con un proveedor simulado, sin pasar por la cola.)
     */
    void process(Recording r)throws Exception{
        try{transcribe(r);}
        catch(HttpApi.TooLarge e){
            JSONObject st=FilesStore.state(c,r.id);
            if(!"openrouter".equals(st.optString("provider"))||st.optBoolean("orHalf"))throw e;
            check(r);FilesStore.update(c,r.id,s->s.put("orHalf",true));
            Pipeline.log(c,r.id,"El proveedor no aceptó un envío tan grande · se repite con partes de la mitad (no gasta un intento)");
            Diagnostics.event("job_halved",r.id,"provider","openrouter","model",st.optString("model"));
            transcribe(r);
        }
    }
    private void transcribe(Recording r)throws Exception{
        check(r);long start=System.currentTimeMillis();Settings settings=new Settings(c);
        JSONObject initial=FilesStore.state(c,r.id);boolean wantSpeakers=initial.has("speakers")?initial.optBoolean("speakers"):settings.defaultSpeakers();
        Retranscribe.Mode mode=Retranscribe.mode(initial);
        if(!Transcript.exists(c,r.id)){
            ProviderConfig config=settings.config(wantSpeakers);if(config.key.isEmpty())throw new HttpApi.UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.");
            // OpenRouter (0.8.0): el tamaño de los bloques sale de la receta del modelo y no del peso del m4a.
            boolean router=config.provider.equals("openrouter"),half=router&&initial.optBoolean("orHalf");Models.Recipe recipe=router?Models.recipe(config.model):null;
            long bytes=r.audio(c).length(),audioMs=r.duration>0||!(config.speakers||router)?r.duration:AudioConvert.duration(r.audio(c));
            // «Separar voces sin cortar el audio»: un solo envío, sin uniones donde las voces se crucen.
            boolean single=mode==Retranscribe.Mode.SINGLE&&config.speakers&&!half&&(router?Retranscribe.fitsSingle(recipe,audioMs):Retranscribe.fitsSingle(audioMs,bytes));
            // Tarea de fondo: un envío único que no alcanza a volver antes del corte de Android se deja al primer plano.
            if(single&&budgetMs>0&&sendEstimate(audioMs)>JOB_SEND_LIMIT_MS)throw new NeedsForeground("Sin cortar: el envío del audio completo");
            long blockMax=router?orBlockMax(recipe,config.speakers):0;
            long target=single?audioMs:router?orBlockMs(audioMs,blockMax,half):config.speakers?speakerBlockMs(audioMs,bytes):BLOCK_TEXT_MS;
            // Hasta qué duración el audio va entero. Con OpenRouter partido a la mitad, el propio bloque es el tope.
            long wholeMax=router?Math.min(AudioParts.SINGLE_MAX_MS,half?target:blockMax):AudioParts.SINGLE_MAX_MS;
            // Voces conocidas («Mi voz» y las demás guardadas) van en todos los bloques (también el primero), la tuya primero y
            // a lo más MAX_KNOWN. Si cambia alguna muestra, no se reutilizan bloques ya transcritos.
            // OpenAI las recibe como voces conocidas; OpenRouter, como anclas antes del audio. Un servidor propio, no.
            boolean samples=config.speakers&&TranscribeClient.knowsVoices(config.provider);
            List<String[]> saved=samples?Voices.references(c):Collections.emptyList();
            // Nombre de cada voz conocida (voz destino → nombre), para no sacarle otra muestra en la segunda pasada.
            Map<String,String> savedNames=new LinkedHashMap<>();for(String[] ref:saved){String name=Voices.nameFor(c,ref[2]);savedNames.put(ref[2],name==null?"":name);}
            // «Segunda pasada con tus correcciones»: muestras de la versión anterior (solo si el proveedor acepta muestras).
            JSONArray fixed=mode==Retranscribe.Mode.CORRECTIONS&&samples?initial.optJSONArray("fixedRefs"):null;
            int attempt=initial.optInt("attempt",0);
            // Con OpenRouter el perfil lleva además el formato del audio y el tope de bloque: así no se mezclan puntos de
            // control hechos con otro formato o con otro tamaño. Para los demás proveedores queda igual que antes.
            String profile=profile(config.fingerprint()+settings.language()+"|v3|"+target+"|"+(saved.isEmpty()?"none":Voices.fingerprint(c))
                +(router?"|"+OpenRouterClient.PROFILE+"|"+blockMax+(half?"|half":""):""),attempt,mode);
            if(!profile.equals(FilesStore.state(c,r.id).optString("profile"))){
                Retranscribe.clearCheckpoints(c,r.id);
                AudioParts.clearBlocks(c,r.id);
                FilesStore.update(c,r.id,s->s.put("profile",profile).remove("cuts"));
                FilesStore.update(c,r.id,s->s.put("blocksDone",0).put("doneAudioMs",0).put("bytesSent",0).put("inTokens",0).put("outTokens",0).put("usageSec",0).put("blockMsSum",0).put("blockCount",0).remove("costUsd"));
            }
            FilesStore.update(c,r.id,s->s.put("model",config.model).put("speakers",config.speakers).put("audioMs",r.duration).put("provider",config.provider));
            Diagnostics.event("job_start",r.id,"provider",config.provider,"model",config.model,"bytes",bytes,"duration_ms",r.duration,"net",Pipeline.networkName(c),"runner",budgetMs>0?"job":"fgs","mode",mode==null?"":mode.name());
            stage(r,"Preparando audio",-1);long prepStart=System.currentTimeMillis();
            List<Long> cuts=new ArrayList<>();List<AudioParts.Part> parts;
            if(single){parts=Collections.singletonList(new AudioParts.Part(r.audio(c),0,audioMs));cuts.add(0L);cuts.add(audioMs);Pipeline.log(c,r.id,"Sin cortar: el audio completo va en un solo envío · tarda más, pero no hay uniones donde las voces se crucen");}
            else parts=AudioParts.plan(c,r,http,target,FilesStore.state(c,r.id).optJSONArray("cuts"),cuts,line->Pipeline.log(c,r.id,line),wholeMax);
            JSONArray savedCuts=new JSONArray();for(Long cut:cuts)savedCuts.put(cut);FilesStore.update(c,r.id,s->s.put("cuts",savedCuts));
            Diagnostics.event("prepare_done",r.id,"parts",parts.size(),"elapsed_ms",System.currentTimeMillis()-prepStart);
            int n=parts.size();FilesStore.update(c,r.id,s->s.put("blocks",n));
            if(n>1)Pipeline.log(c,r.id,"Audio de "+Recording.time(r.duration)+" dividido en "+n+" partes de ~"+(target/60000)+" min, cortadas en pausas · se envían de a "+PARALLEL+" en paralelo");
            JSONObject[] responses=new JSONObject[n];
            try{
                List<String[]> own=saved.isEmpty()?null:saved;
                if(!saved.isEmpty()){
                    // Cantidades, no nombres: la bitácora viaja (sin títulos ni nombres) en el informe de soporte.
                    boolean mine=false;for(String[] ref:saved)if(Voices.ME.equals(ref[2]))mine=true;int active=Voices.used(c).size();
                    Pipeline.log(c,r.id,"Voces conocidas: "+saved.size()+(mine?" (incluida la tuya)":"")+" · se reconocen desde el inicio"+(active>saved.size()?" (van "+saved.size()+" de tus "+active+": el máximo por audio)":""));
                }
                List<String[]> references=own;int from=0;String fresh=null;
                List<String[]> corrections=fixed==null?Collections.emptyList():fixedReferences(r,fixed,savedNames);
                if(!corrections.isEmpty()){
                    // Las voces reconocidas vuelven con su id anterior; las nuevas llevan un prefijo para no chocar con esos ids.
                    fresh="pass"+attempt+":";
                    references=new ArrayList<>(saved);references.addAll(corrections);
                    LinkedHashSet<String> people=new LinkedHashSet<>();for(String[] ref:corrections)people.add(ref[2]);
                    Pipeline.log(c,r.id,"Segunda pasada: muestras de "+people.size()+(people.size()==1?" persona":" personas")+" (de tus correcciones) van en "+(n>1?"todas las partes":"el envío")+" desde el inicio");
                }else{
                    if(fixed!=null)Pipeline.log(c,r.id,"No se pudieron preparar las muestras de tus correcciones · se separan voces de nuevo sin ellas");
                    if(config.speakers&&n>1){
                        // La parte 1 va sola: de ella salen las muestras de voz para las demás.
                        responses[0]=block(r,config,settings,parts,0,own,null);from=1;
                        check(r);
                        // Las voces conocidas que la parte 1 ya reconoció (con su nombre enviado) no necesitan muestra automática;
                        // las automáticas usan los lugares que quedan.
                        Set<String> exclude=new HashSet<>();for(String[] ref:saved)exclude.add(ref[0]);
                        // Si la parte 1 volvió sin voces (OpenRouter: el modelo no las entregó), no hay de quién sacar muestras.
                        List<String[]> auto=responses[0].optBoolean("_diarized",true)?AudioParts.references(c,r,responses[0],http,MAX_KNOWN-saved.size(),exclude):new ArrayList<>();
                        references=new ArrayList<>(saved);references.addAll(auto);
                        if(!auto.isEmpty()){StringBuilder which=new StringBuilder();for(String[] ref:auto)which.append(which.length()==0?"":", ").append(ref[3]);
                            Pipeline.log(c,r.id,"Muestras de voz de la parte 1: "+which+" · se usan para reconocer a las mismas personas en las demás partes");}
                        else if(saved.isEmpty())Pipeline.log(c,r.id,"La parte 1 no tiene tramos limpios para muestras de voz · cada parte separa voces por su cuenta");
                    }
                }
                if(budgetMs>0&&from<n&&System.currentTimeMillis()-started>budgetMs&&!allDone(r,from,n))throw new Yield();
                List<String[]> refs=references;String prefix=fresh;ExecutorService pool=Executors.newFixedThreadPool(PARALLEL);List<Future<JSONObject>> futures=new ArrayList<>();
                try{
                    for(int i=from;i<n;i++){int index=i;futures.add(pool.submit(()->block(r,config,settings,parts,index,refs,prefix)));}
                    Exception first=null;
                    for(int i=from;i<n;i++){try{responses[i]=futures.get(i-from).get();}catch(ExecutionException e){if(first==null)first=e.getCause() instanceof Exception?(Exception)e.getCause():e;}}
                    if(first!=null)throw first;
                }finally{pool.shutdownNow();}
                check(r);Transcript transcript=Transcript.fromParts(Arrays.asList(responses),offsets(parts));transcript.data.put("provider",config.provider).put("model",config.model);
                if(mode!=null)transcript.data.put("pass",mode.name());
                prefillVoices(c,transcript);
                if(!corrections.isEmpty())prefill(transcript,fixed);
                boolean note=settings.noteAuto()&&transcript.hasText()&&canNote();
                // Se guarda solo si nadie canceló entretanto: cancelar una repetición devuelve la versión anterior.
                synchronized(FilesStore.LOCK){check(r);transcript.save(c,r.id);if(note)FilesStore.update(c,r.id,s->s.put("notePending",true));}
            }finally{http.onUploaded=null;http.onProgress=null;}
            // Los bloques se conservan entre intentos; se borran solo con la transcripción ya guardada.
            AudioParts.clearBlocks(c,r.id);
        }
        check(r);
        if(FilesStore.state(c,r.id).optBoolean("notePending"))note(r);
        check(r);long queued=FilesStore.state(c,r.id).optLong("queuedAt",start);long total=System.currentTimeMillis()-queued;
        boolean again=FilesStore.state(c,r.id).has("retranscribe");
        FilesStore.update(c,r.id,s->s.put("requested",false).put("failed",false).put("attempts",0).put("doneIn",total).put("upSent",0).put("upTotal",0).put("doneAudioMs",r.duration).put("doneAt",System.currentTimeMillis()));
        Pipeline.log(c,r.id,"Transcripción lista · tiempo total "+Recording.time(total));
        if(again)Pipeline.log(c,r.id,"Nueva versión lista · elige si te quedas con ella");
        LocalStorage.enqueue(c,r.id);done(r,again);Diagnostics.event("job_complete",r.id,"elapsed_ms",System.currentTimeMillis()-start,"mode",mode==null?"":mode.name());
    }
    private boolean allDone(Recording r,int from,int n){for(int i=from;i<n;i++)if(!FilesStore.file(c,r.id,".part"+i+".json").exists())return false;return true;}
    private static List<Double> offsets(List<AudioParts.Part> parts){List<Double> o=new ArrayList<>();for(AudioParts.Part p:parts)o.add(p.offset);return o;}
    private boolean canNote(){try{return Notes.canGenerate(c);}catch(Exception e){return false;}}

    /** «Nota para tu segundo cerebro»: nunca hace fallar la transcripción, que ya está guardada. */
    private void note(Recording r)throws Exception{
        stage(r,"Armando la nota para tu segundo cerebro",-1);long noteStart=System.currentTimeMillis();
        try{
            Notes.generate(c,r,http);
            Pipeline.log(c,r.id,"Nota lista · tardó "+Recording.time(System.currentTimeMillis()-noteStart));
            Diagnostics.event("note_auto",r.id,"result","ok","elapsed_ms",System.currentTimeMillis()-noteStart);
        }catch(Throwable e){
            if(e instanceof VirtualMachineError)throw (VirtualMachineError)e;
            // Pausa de Android o cancelación: la nota queda pendiente para la próxima vuelta.
            if(http.cancelled&&e instanceof Exception)throw (Exception)e;
            String why=e instanceof UnsupportedOperationException||!(e instanceof Exception)?null:describe((Exception)e);
            Pipeline.log(c,r.id,why==null?"La nota para tu segundo cerebro no está disponible por ahora · la transcripción está lista"
                :"No se pudo armar la nota: "+why+" · la transcripción está lista y puedes armar la nota después");
            try{FilesStore.update(c,r.id,s->{if("working".equals(s.optString("noteState"))){s.put("noteState","failed");s.put("noteError",why==null?"No disponible por ahora":why);}});}catch(Exception ignored){}
            Diagnostics.event("note_auto",r.id,"result","failed","error_class",e.getClass().getSimpleName());
        }
        FilesStore.update(c,r.id,s->s.remove("notePending"));
    }

    // ---------- Segunda pasada con tus correcciones ----------
    /** Nombres enviados para las muestras de la segunda pasada: {nombre enviado, voz destino, nombre visible, índice en fixedRefs}. */
    static List<String[]> fixedPlan(JSONArray fixed,boolean mine){Map<String,String> saved=mine?Collections.singletonMap(Voices.ME,""):Collections.<String,String>emptyMap();return fixedPlan(fixed,saved);}
    /**
     * saved: voces conocidas que ya van en el envío (voz destino → nombre). Cada una ocupa un lugar y no se le saca otra
     * muestra: ni a su propio id ("voice:me", "voice:<id>") ni a una voz que el usuario nombró igual.
     */
    static List<String[]> fixedPlan(JSONArray fixed,Map<String,String> saved){
        List<String[]> out=new ArrayList<>();if(fixed==null)return out;
        Map<String,Integer> person=new HashMap<>(),taken=new HashMap<>();int limit=MAX_KNOWN-(saved==null?0:saved.size());
        for(int i=0;i<fixed.length()&&out.size()<limit;i++){
            JSONObject f=fixed.optJSONObject(i);if(f==null)continue;String id=f.optString("id");
            // Las voces conocidas («Mi voz» y las demás guardadas) ya van con su propia muestra.
            if(id.isEmpty()||isSaved(saved,id,f.optString("name"))||f.optDouble("end",0)-f.optDouble("start",0)<1)continue;
            Integer p=person.get(id);if(p==null){p=person.size()+1;person.put(id,p);}
            int k=taken.merge(id,1,Integer::sum);String name=f.optString("name").trim();
            // Nombres únicos ("voz_1", "voz_1b"): no chocan con las letras que el modelo da a las voces que no reconoce.
            out.add(new String[]{"voz_"+p+(k>1?String.valueOf((char)('a'+k-1)):""),id,name.isEmpty()?f.optString("label","Persona "+p):name,String.valueOf(i)});
        }
        return out;
    }
    /** ¿Esta voz ya va como voz conocida? (por su id o porque el usuario le puso el mismo nombre). */
    static boolean isSaved(Map<String,String> saved,String id,String name){
        if(saved==null||saved.isEmpty())return false;if(saved.containsKey(id))return true;
        String n=name==null?"":name.trim();if(n.isEmpty())return false;
        for(String s:saved.values())if(s!=null&&!s.trim().isEmpty()&&s.trim().equalsIgnoreCase(n))return true;
        return false;
    }
    /** Recorta del audio original cada muestra: {nombre enviado, data URL, voz destino (id anterior), nombre visible}. */
    private List<String[]> fixedReferences(Recording r,JSONArray fixed,Map<String,String> saved){
        List<String[]> refs=new ArrayList<>();
        for(String[] plan:fixedPlan(fixed,saved)){
            JSONObject f=fixed.optJSONObject(Integer.parseInt(plan[3]));java.io.File file=new java.io.File(c.getCacheDir(),r.id+"-fixed-"+refs.size()+".m4a");
            try{
                AudioConvert.convert(r.audio(c),file,(long)(f.optDouble("start")*1000),(long)(f.optDouble("end")*1000),http);byte[] data=java.nio.file.Files.readAllBytes(file.toPath());
                refs.add(new String[]{plan[0],"data:audio/mp4;base64,"+android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP),plan[1],plan[2]});
            }catch(Exception ignored){}finally{file.delete();}
        }
        return refs;
    }
    /** Las voces conocidas que el modelo reconoció ("voice:me", "voice:<id>") llegan con su nombre guardado. */
    static void prefillVoices(Context c,Transcript t)throws Exception{
        JSONObject names=t.data.optJSONObject("names");if(names==null){names=new JSONObject();t.data.put("names",names);}
        for(String id:t.speakers().keySet()){
            if(!id.startsWith(Voices.TARGET)||!names.optString(id,"").trim().isEmpty())continue;
            String name=Voices.nameFor(c,id);if(name!=null&&!name.trim().isEmpty())names.put(id,name.trim());
        }
    }
    /** Las voces reconocidas en la segunda pasada vuelven con el nombre que el usuario les había puesto. */
    static void prefill(Transcript t,JSONArray fixed)throws Exception{
        JSONObject names=t.data.optJSONObject("names");if(names==null){names=new JSONObject();t.data.put("names",names);}
        Set<String> present=t.speakers().keySet();
        for(int i=0;fixed!=null&&i<fixed.length();i++){
            JSONObject f=fixed.optJSONObject(i);if(f==null)continue;String id=f.optString("id"),name=f.optString("name").trim();
            if(!name.isEmpty()&&present.contains(id)&&names.optString(id,"").trim().isEmpty())names.put(id,name);
        }
    }

    /** Envía un bloque (o lo lee del punto de control si ya estaba listo) y registra sus métricas. fresh: prefijo para voces nuevas (o null). */
    private JSONObject block(Recording r,ProviderConfig config,Settings settings,List<AudioParts.Part> parts,int i,List<String[]> refs,String fresh)throws Exception{
        int n=parts.size();java.io.File checkpoint=FilesStore.file(c,r.id,".part"+i+".json");
        if(checkpoint.exists())return FilesStore.read(checkpoint);
        check(r);AudioParts.Part part=parts.get(i);String label=n>1?"parte "+(i+1)+" de "+n:"audio";
        // Tarea de fondo: Android la corta a los ~10 min. Si este bloque no alcanza a volver, se cede el turno antes de enviarlo.
        // Nunca antes del primer envío de la ronda: así cada ronda avanza al menos un bloque. Un bloque que ni solo cabe
        // no se envía desde aquí (se cortaría siempre a mitad y se volvería a subir entero): lo hace el primer plano.
        if(budgetMs>0){long partEstimate=sendEstimate(part.durationMs>0?part.durationMs:r.duration);
            if(partEstimate>JOB_SEND_LIMIT_MS)throw new NeedsForeground(n>1?"El envío de la parte "+(i+1)+" de "+n:"El envío del audio");
            if(sentThisRun.get()&&System.currentTimeMillis()-started+partEstimate>JOB_SEND_LIMIT_MS)throw new Yield();}
        sentThisRun.set(true);
        HttpApi h=http.child();h.jobId=r.id;long partMs=part.durationMs>0?part.durationMs:r.duration;
        // La espera escala con la duración: un bloque con separación de voces puede tardar varios minutos.
        // (Con OpenRouter no pasa de 6 min: ver responseLimit.)
        boolean router=config.provider.equals("openrouter");
        h.readTimeoutMs=(int)Math.min(router?6*60_000L:20*60_000L,Math.max(240_000L,120_000L+partMs));
        long blockStart=System.currentTimeMillis();
        stage(r,"Enviando "+label,0);
        h.onProgress=(sent,total)->progress(r,i,sent,total,label);
        java.util.concurrent.atomic.AtomicBoolean uploaded=new java.util.concurrent.atomic.AtomicBoolean();long limit=responseLimit(config.provider,partMs);
        h.onUploaded=()->{uploaded.set(true);uploads.remove(i);publishUploads(r);Pipeline.log(c,r.id,(n>1?"Parte "+(i+1)+" enviada":"Audio enviado")+(config.provider.equals("openai")?" · OpenAI está transcribiendo":router?" · OpenRouter está transcribiendo":" · el servidor está transcribiendo"));};
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
            // El cliente sale del proveedor: OpenAI (y servidor compatible) u OpenRouter. El motor no distingue la respuesta.
            try{response=TranscribeClient.of(c,h,config).transcribe(part.file,config,settings.language(),refs,delta);}
            catch(HttpApi.UserAction e){
                if(refs==null||refs.isEmpty()||!String.valueOf(e.getMessage()).contains("known_speaker"))throw e;
                Pipeline.log(c,r.id,"El proveedor rechazó las muestras de voz · se reenvía "+(n>1?"la "+label:"el audio")+" sin ellas");
                uploaded.set(false);response=TranscribeClient.of(c,h,config).transcribe(part.file,config,settings.language(),null,delta);refs=null;
            }
        }finally{guard.cancel(false);}
        check(r);
        // "_known": qué nombre enviado corresponde a qué voz (se usa al unir bloques; ver Transcript.fromParts).
        JSONObject known=new JSONObject();if(refs!=null)for(String[] ref:refs)known.put(ref[0],ref.length>2?ref[2]:"block0:"+ref[0]);if(known.length()>0)response.put("_known",known);
        if(fresh!=null)response.put("_prefix",fresh);
        String voices=describeVoices(response,known);
        synchronized(FilesStore.LOCK){if(r.audio(c).exists())FilesStore.write(checkpoint,response);}
        // OpenRouter informa lo que pesó de verdad el envío (FLAC en base64), que no es el peso del m4a.
        long took=System.currentTimeMillis()-blockStart;long bytes=response.optLong("_bytes",part.file.length());JSONObject usage=response.optJSONObject("usage");
        // Costo real del envío en US$ (OpenRouter lo informa en usage.cost); -1 si el proveedor no lo dice.
        double cost=usage==null||usage.isNull("cost")?-1:usage.optDouble("cost",-1);
        FilesStore.update(c,r.id,s->{
            s.put("localCuts",0).put("blocksDone",s.optInt("blocksDone")+1).put("doneAudioMs",s.optLong("doneAudioMs")+partMs).put("bytesSent",s.optLong("bytesSent")+bytes)
             .put("blockMsSum",s.optLong("blockMsSum")+took).put("blockCount",s.optInt("blockCount")+1);
            if(usage!=null){
                if("duration".equals(usage.optString("type")))s.put("usageSec",s.optDouble("usageSec",0)+usage.optDouble("seconds",0));
                else s.put("inTokens",s.optLong("inTokens")+usage.optLong("input_tokens")).put("outTokens",s.optLong("outTokens")+usage.optLong("output_tokens"));
            }
            // "costUsd" solo existe si el proveedor informó el costo: las pantallas muestran ese y no el estimado.
            if(cost>=0)s.put("costUsd",s.optDouble("costUsd",0)+cost);
        });
        Pipeline.log(c,r.id,(n>1?"Parte "+(i+1)+" de "+n+" lista":"Respuesta recibida")+" · tardó "+Recording.time(took)+voices);
        if(cost>=0)Diagnostics.event("part_complete",r.id,"part",i+1,"parts",n,"elapsed_ms",took,"cost",cost);
        else Diagnostics.event("part_complete",r.id,"part",i+1,"parts",n,"elapsed_ms",took);
        JSONObject st=FilesStore.state(c,r.id);notice(n>1?"Transcribiendo · "+st.optInt("blocksDone")+" de "+n+" partes listas":"Transcribiendo · respuesta recibida",true,(int)(st.optLong("doneAudioMs")*100/Math.max(1,r.duration)));
        return response;
    }
    /** Resumen de voces de un bloque para la bitácora: cuántas reconoció por muestra y cuántas son nuevas. */
    private static String describeVoices(JSONObject response,JSONObject known){
        JSONArray s=response.optJSONArray("segments");if(s==null||!response.optBoolean("_diarized",true))return "";
        Set<String> recognized=new HashSet<>(),fresh=new HashSet<>();
        for(int k=0;k<s.length();k++){JSONObject seg=s.optJSONObject(k);if(seg==null||seg.optString("text").trim().isEmpty())continue;String sp=seg.optString("speaker");if(known.has(sp))recognized.add(known.optString(sp));else fresh.add(sp);}
        if(known.length()==0)return fresh.isEmpty()?"":" · "+fresh.size()+(fresh.size()==1?" voz":" voces");
        // OpenRouter: las muestras van como anclas antes del audio y el cliente cuenta cuántas personas emparejó (cantidades,
        // nunca nombres). Sirve para medir cuánto acierta la técnica, que todavía no se probó con audio real.
        if(response.has("_anchors")){int sent=response.optInt("_anchors"),matched=response.optInt("_matched");
            return " · reconoció "+matched+" de "+sent+(sent==1?" voz conocida":" voces conocidas")+(fresh.isEmpty()?"":", "+fresh.size()+(fresh.size()==1?" nueva":" nuevas"));}
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

    // ---------- Notificaciones ----------
    /** Notificación de avance (también la del servicio en primer plano). Mientras transcribe, abre esa grabación. */
    static Notification build(Context c,String text,boolean ongoing,int percent){
        NotificationManager manager=c.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        String id=currentId;
        PendingIntent open=id!=null?PendingIntent.getActivity(c,NOTIFICATION,openIntent(c,id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)
            :PendingIntent.getActivity(c,NOTIFICATION,new Intent(c,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(c,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(ongoing?"Transcribiendo":"Verbapp").setContentText(text).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true);
        if(ongoing){if(percent>=0)b.setProgress(100,percent,false);else b.setProgress(0,0,true);}
        return b.build();
    }
    private void notice(String text,boolean ongoing,int percent){
        try{c.getSystemService(NotificationManager.class).notify(ongoing?NOTIFICATION:DONE_NOTIFICATION,build(c,text,ongoing,percent));}catch(SecurityException ignored){}
        if(!ongoing)c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
    }
    /**
     * «Transcripción lista» (o «Nueva versión lista»): suena una vez (canal "done"), abre esa grabación y ofrece el
     * siguiente paso: «Revisar voces» si hay voces separadas sin revisar; si no, «Guardar en 0-Inbox» si la carpeta
     * rápida está configurada. Extras para RecordingActivity: id, names, save, retranscribed.
     */
    static Notification buildDone(Context c,Recording r,boolean again){
        c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(DONE_CHANNEL,"Transcripciones listas",NotificationManager.IMPORTANCE_DEFAULT));
        int voices=0;boolean review=false;
        try{Transcript t=Transcript.load(c,r.id);voices=t.speakers().size();review=t.diarized()&&voices>1&&!t.reviewed();}catch(Exception ignored){}
        Intent open=openIntent(c,r.id);if(again)open.putExtra("retranscribed",true);
        String text="«"+r.title+"»"+(review?" · "+voices+" voces por revisar":again?" · elige si te quedas con ella":"");
        Notification.Builder b=new Notification.Builder(c,DONE_CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(again?"Nueva versión lista":"Transcripción lista").setContentText(text)
            .setContentIntent(activity(c,r.id,0,open)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setShowWhen(true);
        if(review)b.addAction(action(c,R.drawable.ic_people,"Revisar voces",activity(c,r.id,1,openIntent(c,r.id).putExtra("names",true))));
        else if(Inbox.configured(c))b.addAction(action(c,R.drawable.ic_save,"Guardar en "+Inbox.folderName(c),activity(c,r.id,2,openIntent(c,r.id).putExtra("save",true))));
        return b.build();
    }
    private void done(Recording r,boolean again){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        try{manager.notify(doneId(r.id),buildDone(c,r,again));}catch(RuntimeException ignored){}
        manager.cancel(NOTIFICATION);
    }
    /** Algo falló y necesita al usuario: abre esa grabación. */
    private void attention(Recording r,String title,String text){
        NotificationManager manager=c.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("processing","Transcripciones",NotificationManager.IMPORTANCE_LOW));
        Notification n=new Notification.Builder(c,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(activity(c,r.id,3,openIntent(c,r.id))).setAutoCancel(true).build();
        try{manager.notify(doneId(r.id),n);}catch(RuntimeException ignored){}
        manager.cancel(NOTIFICATION);
    }
    /** Id de la notificación «lista» de una grabación (una por grabación: dos que terminan seguidas no se pisan). */
    static int doneId(String id){return 1000+(id.hashCode()&0xffff);}
    /** Quita la notificación «lista» de esa grabación (p. ej. al abrirla o al volver a transcribirla). */
    static void clearDone(Context c,String id){try{c.getSystemService(NotificationManager.class).cancel(doneId(id));}catch(RuntimeException ignored){}}
    private static Intent openIntent(Context c,String id){return new Intent(c,RecordingActivity.class).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    /** Código único por grabación y acción: con el mismo código, Android reutilizaría el PendingIntent de otra grabación. */
    private static PendingIntent activity(Context c,String id,int kind,Intent intent){return PendingIntent.getActivity(c,((id.hashCode()&0x0fffffff)<<2)|kind,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private static Notification.Action action(Context c,int icon,String title,PendingIntent intent){return new Notification.Action.Builder(Icon.createWithResource(c,icon),title,intent).build();}
}
