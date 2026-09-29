package cl.vozlocal.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.File;
import java.util.*;

/**
 * Volver a transcribir con una alternativa que cambie algo (repetir lo mismo no sirve: el modelo varía poco y sin
 * garantía). La versión anterior se conserva y el usuario elige con cuál se queda.
 *
 * Archivos: la versión actual pasa a {@code <id>.transcript.prev.json} (y la nota a {@code <id>.note.prev.json}).
 * Estado: "retranscribe" {mode, at, before} mientras exista la versión anterior; "attempt" (entra en el perfil de
 * Transcriber: los puntos de control de un intento nunca se reutilizan en otro); "fixedRefs" [{id,name,label,start,end}]
 * con los tramos de muestra de la «Segunda pasada con tus correcciones».
 */
final class Retranscribe {
    private Retranscribe(){}
    enum Mode{CORRECTIONS,SINGLE,SPEAKERS,TEXT}
    /** Un solo envío con voces: el modelo acepta hasta 1400 s; se deja margen. */
    static final long SINGLE_MAX_MS=1_380_000;
    /** Un solo envío: el proveedor acepta archivos de hasta 25 MB. */
    static final long SINGLE_MAX_BYTES=24_000_000;
    /** Datos del estado que describen la versión anterior; vuelven con ella al restaurarla. */
    private static final String[] BEFORE={"model","speakers","provider","audioMs","blocks","blocksDone","cuts","doneIn","doneAt","doneAudioMs","bytesSent",
        "inTokens","outTokens","usageSec","blockMsSum","blockCount","retries","localCuts","noteState","noteError","suggestedTitle"};

    // ---------- Textos (para la hoja «¿Cómo quieres volver a transcribir?») ----------
    static String label(Mode m){
        switch(m){
            case CORRECTIONS:return "Segunda pasada con tus correcciones";
            case SINGLE:return "Separar voces sin cortar el audio";
            case SPEAKERS:return "Separar voces de nuevo";
            default:return "Solo el texto";
        }
    }
    /** Una línea que explica qué cambia con cada alternativa. */
    static String detail(Context c,Mode m){
        switch(m){
            case CORRECTIONS:return "Reconoce desde el inicio a las personas que nombraste o corregiste. La que más mejora.";
            case SINGLE:return "Todo el audio en un solo envío: sin uniones donde las voces se crucen. Más lento.";
            case SPEAKERS:{
                List<Voices.Voice> known=new Settings(c).provider().equals("openai")?Voices.selected(c):Collections.emptyList();
                if(known.isEmpty())return "Otra pasada completa.";
                if(known.size()==1&&known.get(0).me)return "Otra pasada completa, reconociéndote como "+known.get(0).name+".";
                return "Otra pasada completa, "+(known.get(0).me?"reconociéndote ":"reconociendo ")+Voices.people(known)+".";
            }
            default:return "Sin separar voces. Sirve si lo que falló fueron las palabras.";
        }
    }
    static boolean speakers(Mode m){return m!=Mode.TEXT;}
    /** Costo estimado en US$ de volver a transcribir todo el audio con esta alternativa (-1 si no se conoce). */
    static double cost(Context c,Recording r,Mode m){
        try{Settings s=new Settings(c);ProviderConfig config=s.config(speakers(m));return Pricing.estimate(config.provider,config.model,r.duration);}catch(Exception e){return -1;}
    }
    /** Alternativa en curso (o pendiente de elegir versión) según el estado; null si no es una repetición. */
    static Mode mode(JSONObject state){
        JSONObject o=state==null?null:state.optJSONObject("retranscribe");if(o==null)return null;
        try{return Mode.valueOf(o.optString("mode"));}catch(IllegalArgumentException e){return null;}
    }
    static boolean fitsSingle(long durationMs,long bytes){return durationMs>0&&durationMs<=SINGLE_MAX_MS&&bytes>0&&bytes<=SINGLE_MAX_BYTES;}

    // ---------- Disponibilidad ----------
    /** Lo que decide si cada alternativa se puede usar (separado del teléfono para poder probarlo). */
    static final class Facts{
        boolean exists=true,demo,busy,transcribed,previous,hasKey,openai,canSeparate,diarized,confirmed;
        long durationMs,bytes;int parts=1;
        /** Voces conocidas que ya van en cada envío (ocupan lugares de las muestras). */
        int saved;
        /** Muestras limpias para la segunda pasada; -1 si aún no se calculan. */
        int samples=-1;
        Transcript transcript;
    }
    static Facts facts(Context c,Recording r){
        Facts f=new Facts();Settings s=new Settings(c);JSONObject st=FilesStore.state(c,r.id);File audio=r.audio(c);
        f.exists=audio.isFile();f.demo=st.optBoolean("demo");f.busy=st.optBoolean("requested");
        f.transcribed=Transcript.exists(c,r.id);f.previous=hasPrevious(c,r.id);
        f.hasKey=s.hasKey();f.openai=s.provider().equals("openai");f.canSeparate=s.canSeparate();f.saved=f.openai?Voices.selected(c).size():0;
        f.bytes=audio.length();f.durationMs=r.duration;
        if(f.durationMs<=0&&f.exists)try{f.durationMs=AudioConvert.duration(audio);}catch(Exception ignored){}
        if(f.transcribed)try{Transcript t=Transcript.load(c,r.id);f.transcript=t;f.diarized=t.diarized();f.confirmed=t.reviewed()||t.edited()||t.named();f.parts=t.data.optInt("parts",1);}catch(Exception ignored){}
        return f;
    }
    /** Motivo en palabras simples por el que la alternativa no se puede usar, o null si se puede. */
    static String reason(Facts f,Mode mode){
        if(!f.exists)return "No se encontró el audio de esta grabación.";
        if(f.demo)return "El ejemplo no se vuelve a transcribir.";
        if(f.busy)return "Ya se está transcribiendo. Espera a que termine.";
        if(!f.transcribed)return f.previous?"La nueva versión no terminó. Reintenta o vuelve a la anterior.":"Todavía no tiene transcripción. Usa «Transcribir».";
        if(!f.hasKey)return "Agrega tu clave de API en Ajustes para volver a transcribir.";
        switch(mode){
            case CORRECTIONS:
                if(!f.openai)return "Solo funciona con OpenAI: tu servicio de transcripción no acepta muestras de voz.";
                if(!f.diarized)return "Esta versión no tiene voces separadas.";
                if(!f.confirmed)return "Primero nombra o corrige las voces: la segunda pasada aprende de eso.";
                if(f.samples==0)return f.saved>=Transcriber.MAX_KNOWN?"Ya van "+Transcriber.MAX_KNOWN+" voces conocidas en cada envío: no queda lugar para muestras de esta grabación.":"No hay tramos claros de cada persona para usar como muestra.";
                return null;
            case SINGLE:
                if(!f.canSeparate)return "Tu servicio de transcripción no separa voces.";
                if(f.durationMs>SINGLE_MAX_MS)return "Solo para audios de hasta 23 min. Este dura "+Recording.time(f.durationMs)+".";
                if(f.bytes>SINGLE_MAX_BYTES)return "El archivo es muy pesado para enviarlo de una vez (más de 24 MB).";
                if(f.diarized&&f.parts<=1)return "La versión actual ya separó voces sin cortar el audio.";
                return null;
            case SPEAKERS:
                return f.canSeparate?null:"Tu servicio de transcripción no separa voces.";
            default:
                return null;
        }
    }
    static boolean available(Context c,Recording r,Mode mode){return reason(c,r,mode)==null;}
    /** Texto si la alternativa no está disponible; null si se puede usar. */
    static String reason(Context c,Recording r,Mode mode){
        try{
            Facts f=facts(c,r);
            if(mode==Mode.CORRECTIONS&&reason(f,mode)==null)f.samples=samples(c,r.id,f.transcript);
            return reason(f,mode);
        }catch(Exception e){return "No se pudo revisar esta grabación.";}
    }
    /** Cuántas muestras tendría la segunda pasada (se recuerda mientras nada cambie: la hoja lo pregunta varias veces). */
    private static String samplesKey;private static int samplesCount;
    private static synchronized int samples(Context c,String id,Transcript t)throws Exception{
        Map<String,String> saved=savedVoices(c);
        String key=id+"|"+FilesStore.version.get()+"|"+Voices.fingerprint(c)+"|"+saved;
        if(key.equals(samplesKey))return samplesCount;
        samplesCount=pickKnown(t,saved).length();samplesKey=key;return samplesCount;
    }
    /** Voces conocidas que irán en cada envío (voz destino → nombre); vacío si el proveedor no acepta muestras. */
    static Map<String,String> savedVoices(Context c){
        Map<String,String> out=new LinkedHashMap<>();if(!new Settings(c).provider().equals("openai"))return out;
        for(Voices.Voice v:Voices.selected(c))out.put(v.target(),v.name);
        return out;
    }

    // ---------- Segunda pasada con tus correcciones ----------
    /** Igual que {@link #pickKnown(Transcript,Map)} con solo «Mi voz» (myName, o null si no se usa). */
    static JSONArray pickFixed(Transcript t,String myName)throws Exception{
        Map<String,String> saved=myName==null?Collections.<String,String>emptyMap():Collections.singletonMap(Voices.ME,myName);
        return pickKnown(t,saved);
    }
    /**
     * Elige tramos limpios de cada persona en la transcripción corregida (máx. 4 voces conocidas por envío, incluidas
     * las guardadas): primero las voces que el usuario nombró, después las demás por tiempo de habla. Las voces
     * conocidas (saved: voz destino → nombre; «Mi voz» y las demás guardadas) no necesitan muestra: ni su propio id
     * ("voice:me", "voice:<id>") ni la voz que lleva su mismo nombre. Cada una ocupa uno de los 4 lugares.
     * Devuelve [{id, name (el que puso el usuario o ""), label (el visible), start, end}] en segundos.
     */
    static JSONArray pickKnown(Transcript t,Map<String,String> saved)throws Exception{
        JSONArray out=new JSONArray();if(t==null||!t.diarized())return out;
        Map<String,String> labels=t.speakers();JSONObject names=t.data.optJSONObject("names");
        Map<String,String> given=new HashMap<>();for(String id:labels.keySet())given.put(id,names==null?"":names.optString(id,"").trim());
        Set<String> exclude=new HashSet<>();exclude.add("unknown");
        if(saved==null)saved=Collections.emptyMap();
        for(String id:labels.keySet())if(Transcriber.isSaved(saved,id,given.get(id)))exclude.add(id);
        List<AudioParts.Clip> clips=AudioParts.pickReferences(t.segments(),Integer.MAX_VALUE,exclude);
        // pickReferences entrega primero una muestra por persona (por tiempo de habla) y después las segundas muestras.
        List<AudioParts.Clip> first=new ArrayList<>(),second=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(AudioParts.Clip clip:clips)(seen.add(clip.label)?first:second).add(clip);
        first.sort(Comparator.comparingInt(clip->given.getOrDefault(clip.label,"").isEmpty()?1:0));
        int limit=Transcriber.MAX_KNOWN-saved.size();List<AudioParts.Clip> chosen=new ArrayList<>();Set<String> in=new HashSet<>();
        for(AudioParts.Clip clip:first){if(chosen.size()>=limit)break;chosen.add(clip);in.add(clip.label);}
        for(AudioParts.Clip clip:second){if(chosen.size()>=limit)break;if(in.contains(clip.label))chosen.add(clip);}
        for(AudioParts.Clip clip:chosen)out.put(new JSONObject().put("id",clip.label).put("name",given.getOrDefault(clip.label,""))
            .put("label",labels.getOrDefault(clip.label,clip.label)).put("start",clip.start).put("end",clip.end));
        return out;
    }
    /** "Fran, Persona 2" (una vez cada voz). */
    static String who(JSONArray fixed){
        LinkedHashSet<String> people=new LinkedHashSet<>();for(int i=0;fixed!=null&&i<fixed.length();i++){JSONObject f=fixed.optJSONObject(i);if(f!=null)people.add(f.optString("label",f.optString("id")));}
        return String.join(", ",people);
    }

    // ---------- Empezar ----------
    /** Mueve la transcripción actual a la versión anterior, marca el estado y la pone en cola. */
    static void start(Context c,Recording r,Mode mode)throws Exception{
        Facts f=facts(c,r);JSONArray fixed=null;
        if(mode==Mode.CORRECTIONS&&f.transcript!=null){fixed=pickKnown(f.transcript,savedVoices(c));f.samples=fixed.length();}
        String why=reason(f,mode);if(why!=null)throw new HttpApi.UserAction(why);
        prepare(c,r,mode,fixed);
        try{Pipeline.request(c,r.id,speakers(mode));}
        catch(Exception e){try{restore(c,r.id,"No se pudo poner en cola · se mantiene la versión anterior","retranscribe_queue_failed");}catch(Exception ignored){}throw e;}
        // La bitácora dice qué alternativa se usó (lo escribe Pipeline.request, que empieza una bitácora nueva).
        Diagnostics.event("retranscribe_start",r.id,"mode",mode.name(),"count",fixed==null?0:fixed.length(),"duration_ms",r.duration);
    }
    /** Todo lo de start() menos encolar (las pruebas lo usan sin llamar a la API). */
    static void prepare(Context c,Recording r,Mode mode,JSONArray fixed)throws Exception{
        String id=r.id;
        synchronized(FilesStore.LOCK){
            File current=FilesStore.file(c,id,".transcript.json");if(!current.isFile())throw new HttpApi.UserAction("Todavía no tiene transcripción.");
            // Si quedaba una versión anterior sin elegir, la que se reemplaza es la que el usuario está viendo.
            move(current,FilesStore.file(c,id,".transcript.prev.json"));
            File note=FilesStore.file(c,id,".note.json"),notePrev=FilesStore.file(c,id,".note.prev.json");
            if(note.isFile())move(note,notePrev);else new AtomicFile(notePrev).delete();
            JSONObject st=FilesStore.state(c,id),before=new JSONObject();for(String key:BEFORE)if(st.has(key))before.put(key,st.get(key));
            int attempt=st.optInt("attempt",0)+1;long now=System.currentTimeMillis();
            FilesStore.update(c,id,s->{
                s.put("attempt",attempt).put("retranscribe",new JSONObject().put("mode",mode.name()).put("at",now).put("before",before));
                if(fixed!=null&&fixed.length()>0)s.put("fixedRefs",fixed);else s.remove("fixedRefs");
                // Nada del intento anterior se reutiliza: ni cortes ni perfil; la nota y el comienzo son de la nueva versión.
                s.remove("cuts");s.remove("profile");s.remove("noteState");s.remove("noteError");s.remove("suggestedTitle");s.remove("snippet");s.remove("notePending");s.remove("opened");
            });
        }
        clearCheckpoints(c,id);AudioParts.clearBlocks(c,id);Transcriber.clearDone(c,id);
    }

    // ---------- Elegir versión ----------
    static boolean hasPrevious(Context c,String id){return FilesStore.file(c,id,".transcript.prev.json").isFile();}
    /** Quedarse con la nueva: se borra la anterior. Sin nueva versión terminada no hace nada (nunca deja la grabación sin transcripción). */
    static void keepNew(Context c,String id){
        Mode m;
        synchronized(FilesStore.LOCK){
            if(!Transcript.exists(c,id))return;
            m=mode(FilesStore.state(c,id));
            new AtomicFile(FilesStore.file(c,id,".transcript.prev.json")).delete();new AtomicFile(FilesStore.file(c,id,".note.prev.json")).delete();
            try{FilesStore.update(c,id,s->{s.remove("retranscribe");s.remove("fixedRefs");});}catch(Exception ignored){}
        }
        Pipeline.log(c,id,"Te quedaste con la nueva versión");
        Diagnostics.event("retranscribe_keep",id,"mode",m==null?"":m.name());
    }
    /** Volver a la anterior (también con la nota y los datos del proceso). Si la nueva aún se estaba haciendo, se detiene. */
    static void restorePrevious(Context c,String id)throws Exception{restore(c,id,"Volviste a la versión anterior","retranscribe_restore");}
    /** message: línea para la bitácora; event: registro técnico (elegida por el usuario, cancelada o fallida). */
    static void restore(Context c,String id,String message,String event)throws Exception{
        if(!hasPrevious(c,id))return;
        if(FilesStore.state(c,id).optBoolean("requested"))Pipeline.cancel(c,id,false);
        Mode m;
        synchronized(FilesStore.LOCK){
            File prev=FilesStore.file(c,id,".transcript.prev.json");if(!prev.isFile())return;
            JSONObject st=FilesStore.state(c,id);m=mode(st);JSONObject info=st.optJSONObject("retranscribe");JSONObject before=info==null?null:info.optJSONObject("before");
            move(prev,FilesStore.file(c,id,".transcript.json"));
            File note=FilesStore.file(c,id,".note.json"),notePrev=FilesStore.file(c,id,".note.prev.json");
            if(notePrev.isFile())move(notePrev,note);else new AtomicFile(note).delete();
            FilesStore.update(c,id,s->{
                s.remove("retranscribe");s.remove("fixedRefs");s.remove("notePending");s.put("failed",false);
                if(before!=null)for(String key:BEFORE){if(before.has(key))s.put(key,before.get(key));else s.remove(key);}
            });
        }
        clearCheckpoints(c,id);AudioParts.clearBlocks(c,id);
        Transcript.refreshSnippet(c,id);
        try{Pipeline.edited(c,id);}catch(Exception ignored){}
        Pipeline.log(c,id,message);
        Diagnostics.event(event,id,"mode",m==null?"":m.name());
    }

    // ---------- Archivos ----------
    /** Copia un JSON con escritura atómica y borra el original (con sus respaldos .bak/.new, que AtomicFile restauraría). */
    private static void move(File from,File to)throws Exception{JSONObject data=FilesStore.read(from);FilesStore.write(to,data);new AtomicFile(from).delete();}
    /** Respuestas guardadas por parte (<id>.partN.json, con sus respaldos: AtomicFile podría revivir uno viejo). */
    static void clearCheckpoints(Context c,String id){
        File[] files=Recording.directory(c).listFiles((dir,name)->name.startsWith(id+".part")&&name.contains(".json"));
        if(files!=null)for(File file:files)file.delete();
    }
}
