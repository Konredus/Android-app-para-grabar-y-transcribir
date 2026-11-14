package cl.vozlocal.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/**
 * Cliente de transcripción de OpenRouter (0.8.0): POST BASE+"/audio/transcriptions" con JSON y el audio en base64
 * (en multipart OpenRouter descarta provider.options y la separación de voces falla sin avisar).
 *
 * Qué hace con cada envío (ver docs/diseno/SPEC-0.8.md, decisiones 2 a 6 y 8):
 * 1. Convierte el bloque a FLAC/WAV mono de 16 kHz con OrAudio. Si hay muestras de voz (references), van ANTES del audio
 *    como «anclas», separadas por 1 s de silencio: OpenRouter no tiene «voces conocidas», así que se le pide separar
 *    voces y después se mira qué hablante suena en la ventana de cada ancla.
 * 2. Pide voces según la receta del modelo (Models.Recipe): Azure, Deepgram, genérico o marcas dentro del texto.
 * 3. Normaliza la respuesta al contrato de TranscribeClient: tramos {speaker,start,end,text}. Al hablante de cada ancla
 *    lo devuelve con el NOMBRE ENVIADO de su muestra (así el motor lo une con _known, igual que con OpenAI), borra lo
 *    que cae en la zona de anclas y resta ese tiempo.
 * 4. Devuelve usage {"type":"duration","seconds":N,"cost":US$} con el costo real del envío.
 *
 * Nada de esto se probó con audio real ni con una clave (no había): por eso es defensivo. Las anclas nunca hacen fallar
 * una transcripción; a lo más, una voz conocida queda sin reconocer. En la bitácora y en Diagnostics solo van cantidades.
 *
 * Segunda ronda (docs/diseno/SPEC-0.8b.md, parte engine): la conversión es la etapa «Preparando audio» (HttpApi la avisa
 * al motor); si el proveedor no puede con el FLAC se repite una vez en WAV; y lo que un modelo no acepta (formato con
 * tiempos, tiempos reales, FLAC) se recuerda por modelo durante la sesión, para no subir cada bloque dos veces.
 */
final class OpenRouterClient implements TranscribeClient {
    /**
     * Versión de este cliente dentro del perfil del motor (Transcriber): si cambia el audio que se envía o la forma de
     * normalizar la respuesta, los puntos de control hechos con la versión anterior no se reutilizan.
     */
    static final String PROFILE="or1|flac16k";
    /** Página pública de la app: OpenRouter la pide para identificar de qué app viene cada llamada (no lleva datos del usuario). */
    static final String REFERER="https://github.com/Konredus/Android-app-para-grabar-y-transcribir";
    /** Encabezados que identifican a Verbapp ante OpenRouter. «X-Title» es el nombre anterior del mismo encabezado. */
    /** Respuestas 200 ilegibles por grabación, en esta sesión del proceso (0.9.6). */
    private static final java.util.concurrent.ConcurrentHashMap<String,Integer> UNREADABLE=new java.util.concurrent.ConcurrentHashMap<>();
    static Map<String,String> headers(){Map<String,String> h=new LinkedHashMap<>();h.put("HTTP-Referer",REFERER);h.put("X-OpenRouter-Title","Verbapp");h.put("X-Title","Verbapp");return h;}
    /** Una pausa más larga que esto (s) corta el tramo al agrupar palabras. */
    static final double PAUSE_S=1.2;
    /** Un tramo armado con palabras no pasa de esto (s). */
    static final double TURN_MAX_S=45;
    /** Lugar del audio dentro del JSON: el cuerpo se parte ahí y el archivo va en base64, en streaming. */
    private static final String AUDIO="@@VERBAPP_AUDIO@@";
    /** Marca de cambio de voz dentro del texto (Fish Audio): «<|speaker:2|>». */
    private static final Pattern MARK=Pattern.compile("<\\|\\s*speaker\\s*:\\s*(\\d+)\\s*\\|>");
    /**
     * Modelos que en esta sesión respondieron sin tiempos (con voces pedidas y algo dicho): con ellos no se pueden usar
     * anclas (no se sabría dónde terminan) ni sacar muestras de la parte 1. Se anota en la primera respuesta así, lleve o
     * no anclas: si no, las partes que salen en paralelo se enviaban con anclas y se pagaban dos veces.
     */
    static final Set<String> NO_TIMES=ConcurrentHashMap.newKeySet();
    /**
     * Modelos que en esta sesión rechazaron el formato con tiempos o la separación de voces (400): los bloques que siguen
     * van directo en modo simple, en vez de subir cada bloque dos veces (el audio entero viaja antes de leer el 400).
     */
    static final Set<String> PLAIN=ConcurrentHashMap.newKeySet();
    /** Modelos cuyo proveedor rechazó el FLAC y aceptó el mismo audio en WAV: en esta sesión van directo en WAV. */
    static final Set<String> NO_FLAC=ConcurrentHashMap.newKeySet();

    /** Costura de prueba: quién arma el audio. Por defecto OrAudio.build; las pruebas ponen uno falso con anclas en tiempos conocidos. */
    interface Builder{
        OrAudio.Built build(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception;
        /** El mismo audio en WAV, para cuando el proveedor rechaza el FLAC. */
        default OrAudio.Built wav(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception{return OrAudio.build(audio,anchors,outBase,cancel,false);}
    }
    /** El que usan los clientes nuevos (el motor crea el suyo por dentro: las pruebas del motor cambian este). */
    static volatile Builder defaultBuilder=OrAudio::build;
    /** Líneas para la bitácora de la grabación (cantidades y avisos; nunca nombres ni texto). */
    interface Log{void line(String message);}

    final Context c;final HttpApi http;
    Builder builder=defaultBuilder;
    Log log;
    OpenRouterClient(Context c,HttpApi http){
        this.c=c.getApplicationContext();this.http=http;Pricing.attach(this.c);
        // El motor pone en jobId la grabación que se está transcribiendo; sin ella (pruebas) no hay bitácora donde escribir.
        this.log=message->{String id=this.http.jobId;if(id!=null)Pipeline.log(this.c,id,message);};
    }

    @Override public JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,OpenAiClient.Delta delta)throws Exception{
        Models.Recipe recipe=Models.recipe(config.model);
        // Solo se piden voces si el motor las pidió y la receta del modelo sabe cómo.
        boolean voices=config.speakers&&recipe.diarizes;
        File dir=new File(c.getCacheDir(),"openrouter");dir.mkdirs();sweep(dir);
        String tag=UUID.randomUUID().toString();List<File> temps=new ArrayList<>();Kept kept=null;
        // plain: modo simple (json, sin voces ni tiempos), porque el modelo ya rechazó el formato con tiempos en esta sesión
        // o lo rechaza en este envío. wav: el proveedor no acepta el FLAC. before: costo de envíos cobrados que se descartaron.
        boolean plain=PLAIN.contains(config.model),wav=NO_FLAC.contains(config.model),flacRefused=false;double before=0;
        try{
            // 1. Muestras de voz (data URL) → archivos temporales, en el mismo orden. Una que no se pueda leer se omite.
            List<String[]> refs=new ArrayList<>();List<File> anchors=new ArrayList<>();
            if(voices&&!plain&&references!=null&&!NO_TIMES.contains(config.model))for(String[] ref:references){
                if(ref==null||ref.length<2)continue;File clip=new File(dir,tag+"-voz"+anchors.size()+".m4a");
                if(decode(ref[1],clip)){refs.add(ref);anchors.add(clip);temps.add(clip);}
            }
            // 0.9.1: si un intento anterior de esta misma parte ya preparó su audio (y falló por el servidor), se reutiliza.
            kept=Kept.of(dir,http.jobId,audio,refs,wav,http.audioClean);
            OrAudio.Built built=kept==null?null:kept.load();
            if(built!=null){if(built.leadMs<=0)anchors=Collections.emptyList();log.line(Lang.str(c,R.string.eng_log_or_prepared_reused));}
            // Cada vuelta cambia algo una sola vez (WAV, modo simple o sin anclas): a lo más cuatro envíos.
            for(int round=0;round<4;round++){
                http.check();
                // 2. Audio («Preparando audio»): se arma una vez; de nuevo solo si hay que quitarle las anclas o pasarlo a WAV.
                // El primer armado queda guardado (Kept) hasta que la parte se transcriba; un rearmado va a un temporal.
                if(built==null||(built.leadMs>0&&anchors.isEmpty())){
                    if(built!=null&&(kept==null||!kept.owns(built.file)))built.file.delete();
                    boolean keep=kept!=null&&round==0&&built==null;
                    if(!keep&&kept!=null){kept.drop();kept=null;}
                    built=prepare(audio,anchors,keep?kept.base():new File(dir,tag+"-"+round),wav);
                    if(keep)kept.save(built);else temps.add(built.file);
                    if(built.leadMs<=0)anchors=Collections.emptyList();
                }
                boolean ask=voices&&!plain,verbose=!plain&&(recipe.verbose||ask);
                // 3. Envío.
                long[] size={0};HttpApi.Response response=send(built,config,body(config.model,built.format,language,recipe,ask,verbose),size);
                JSONObject json=null;int code=response.code;
                if(code>=200&&code<300){
                    try{json=response.json();}catch(JSONException e){
                        // 0.9.6: un 200 que no es JSON (una página HTML de un intermediario) rara vez se arregla solo: la segunda
                        // vez para la misma grabación ya no se reintenta (cada reintento vuelve a enviar y a cobrar el audio).
                        String key=http.jobId==null?"":http.jobId;int seen=UNREADABLE.merge(key,1,Integer::sum);
                        Diagnostics.event("or_unreadable",http.jobId,"count",seen);
                        if(seen>=2){UNREADABLE.remove(key);throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_or_unreadable));}
                        throw new IOException(Lang.str(c,R.string.eng_err_or_unreadable));}
                    // OpenRouter a veces responde 200 con el error del proveedor adentro: se trata como el error que es.
                    JSONObject error=json.optJSONObject("error");
                    if(error!=null&&!json.has("text")&&!json.has("segments")){int inner=error.optInt("code",502);code=inner>=400&&inner<600?inner:502;}
                }
                // 7. El proveedor no pudo con el FLAC (400 por el formato del audio): una vez más en WAV, que acepta cualquiera.
                // Un 400 no se cobra; el WAV pesa el doble, así que solo se recuerda para el modelo si de verdad resolvió el problema.
                if(code==400&&"flac".equals(built.format)&&!wav&&audioRejected(response.text)){
                    wav=true;flacRefused=true;built.file.delete();built=null;
                    log.line(Lang.str(c,R.string.eng_log_or_wav));
                    Diagnostics.event("or_fallback",http.jobId,"provider","openrouter","model",config.model,"reason","flac","http",400);
                    continue;
                }
                // 6. El modelo no acepta verbose_json, los tiempos o la separación de voces: una vez más, en modo simple. Se
                // recuerda en esta sesión: los bloques que siguen van directo así (y el aviso queda una sola vez en la bitácora).
                if(code==400&&(verbose||ask)&&formatRejected(response.text)){
                    plain=true;anchors=Collections.emptyList();
                    if(PLAIN.add(config.model))log.line(Lang.str(c,ask?R.string.eng_log_or_plain_voices:R.string.eng_log_or_plain_times));
                    Diagnostics.event("or_fallback",http.jobId,"provider","openrouter","model",config.model,"reason","format","http",400);
                    continue;
                }
                if(code!=response.code){HttpApi.Response inner=new HttpApi.Response(code,response.text,null,response.requestId);inner.jobId=response.jobId;inner.retryAfterMs=response.retryAfterMs;response=inner;}
                HttpApi.require(response,HttpApi.OPENROUTER);
                // Una respuesta sin texto, tramos ni palabras no es «silencio» (eso llega como texto vacío): es un formato que
                // la app no conoce. No se guarda como transcripción vacía ni se reintenta (cada reintento se cobraría): se
                // avisa, y en Diagnostics quedan los nombres de los campos que sí vinieron (son de la API, no del usuario).
                if(!json.has("text")&&!json.has("segments")&&!json.has("words")){
                    StringBuilder fields=new StringBuilder();for(Iterator<String> it=json.keys();it.hasNext()&&fields.length()<80;)fields.append(fields.length()>0?",":"").append(it.next().replaceAll("[^A-Za-z0-9_]",""));
                    Diagnostics.event("or_fallback",http.jobId,"provider","openrouter","model",config.model,"reason","shape:"+fields);
                    throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_or_shape));
                }
                // 4. Respuesta → tramos.
                Parsed parsed=read(json,ask,built.durationMs);
                // Voces pedidas, algo dicho y ningún tiempo real: el modelo no sirve para anclas ni para muestras. Se anota ya,
                // aunque este envío no llevara anclas (p. ej. la parte 1), antes de que salgan las demás partes en paralelo.
                if(ask&&!parsed.timed&&said(parsed))NO_TIMES.add(config.model);
                if(built.leadMs>0&&!parsed.timed){
                    // Sin tiempos reales no se sabe dónde terminan las anclas: su texto contaminaría el comienzo. Se repite sin ellas.
                    NO_TIMES.add(config.model);anchors=Collections.emptyList();before+=cost(json);
                    log.line(Lang.str(c,R.string.eng_log_or_no_times));
                    Diagnostics.event("or_fallback",http.jobId,"provider","openrouter","model",config.model,"reason","no_times");
                    continue;
                }
                if(ask&&!parsed.diarized)log.line(Lang.str(c,R.string.eng_log_or_no_voices));
                if(flacRefused)NO_FLAC.add(config.model);
                JSONObject out=finish(parsed,built.leadMs,built.anchors,refs);
                JSONObject usage=usage(json,built.durationMs,before);before=0;
                // _timed: los tiempos son reales (el motor solo saca muestras de voz de una parte con tiempos reales).
                out.put("usage",usage).put("_bytes",size[0]).put("_format",built.format).put("_timed",parsed.timed);
                Diagnostics.event("or_transcribed",http.jobId,"provider","openrouter","model",config.model,"format",built.format,"bytes",size[0],"duration_ms",built.durationMs,
                    "anchors",out.optInt("_anchors"),"matched",out.optInt("_matched"),"cost",usage.optDouble("cost",-1),"count",out.getJSONArray("segments").length());
                if(kept!=null)kept.drop();
                return out;
            }
            throw new IOException(Lang.str(c,R.string.eng_err_or_unusable));
        }catch(Exception e){
            // Un envío cobrado y descartado (p. ej. sin tiempos) cuyo reenvío falló no queda en ninguna respuesta: el motor
            // lo suma igual al costo real, para no mostrar menos de lo que se cobró.
            if(before>0){HttpApi.Billed billed=http.onBilled;if(billed!=null)billed.cost(before);}
            // El audio preparado queda para el próximo intento solo si el problema fue de red o del servidor.
            if(kept!=null&&!(e instanceof IOException))kept.drop();
            throw e;
        }finally{
            // 5. Temporales: el audio convertido y las muestras no quedan en el teléfono.
            for(File f:temps)f.delete();
        }
    }
    private static boolean said(Parsed p){for(Piece x:p.pieces)if(x.text!=null&&!x.text.trim().isEmpty())return true;return false;}
    /**
     * Etapa «Preparando audio»: convertir el bloque antes de enviarlo. El motor la muestra aparte, y su vigilante no la
     * toma como una subida detenida (ver HttpApi.startPreparing).
     */
    private OrAudio.Built prepare(File audio,List<File> anchors,File outBase,boolean wav)throws Exception{
        http.startPreparing();boolean ok=false;
        try{OrAudio.Built built=build(audio,anchors,outBase,wav);ok=true;return built;}
        finally{http.endPreparing(ok);}
    }
    /**
     * Arma el audio. Si falla por las anclas, se repite sin ellas: una muestra de voz nunca hace fallar la transcripción.
     * Lo que reintentar no arregla (audio vacío o sin pista de audio, disco lleno) se le dice al usuario de inmediato;
     * cualquier otro problema al convertir cuenta como un intento, con un motivo claro y su rastro en Diagnostics.
     */
    private OrAudio.Built build(File audio,List<File> anchors,File outBase,boolean wav)throws Exception{
        try{
            if(!anchors.isEmpty())try{return make(audio,anchors,outBase,wav);}
                catch(InterruptedIOException|HttpApi.UserAction|OrAudio.TooShort|OrAudio.NoTrack e){throw e;}
                catch(Exception e){
                    http.check();
                    Diagnostics.event("or_fallback",http.jobId,"provider","openrouter","reason","anchors","error_class",e.getClass().getSimpleName());
                }
            return make(audio,null,outBase,wav);
        }
        catch(InterruptedIOException|HttpApi.UserAction e){throw e;}
        catch(OrAudio.TooShort e){
            Diagnostics.event("or_prepare_failed",http.jobId,"provider","openrouter","reason","too_short");
            throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_audio_empty));
        }
        catch(OrAudio.NoTrack e){
            Diagnostics.event("or_prepare_failed",http.jobId,"provider","openrouter","reason","no_track");
            throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_audio_no_track));
        }
        catch(Exception e){
            Diagnostics.event("or_prepare_failed",http.jobId,"provider","openrouter","error_class",e.getClass().getSimpleName(),"reason",HttpApi.safeReason(e));
            throw new IOException(Lang.str(c,R.string.eng_err_prepare_failed,e.getClass().getSimpleName()),e);
        }
    }
    private OrAudio.Built make(File audio,List<File> anchors,File outBase,boolean wav)throws Exception{return wav?builder.wav(audio,anchors,outBase,http):builder.build(audio,anchors,outBase,http);}
    /**
     * 0.9.1: el audio ya preparado de una parte, guardado mientras la parte no se transcriba. Preparar una parte de 6 min
     * tarda de 20 s a más de 1 min (bastante más con la pantalla apagada), y cuando OpenRouter falla (502, 429) se repetía
     * en cada reintento: el diagnóstico del 2026-10-03 muestra la misma parte preparada cinco veces (00:17, 00:24, 00:32,
     * 00:59, 01:08). La clave es la grabación, el archivo de la parte (ruta, tamaño, fecha), las muestras de voz y el
     * formato; no el modelo: el mismo audio sirve si se prueba con otro. Queda en el caché privado de la app y se borra al
     * transcribirse la parte, ante un error que reintentar no arregla, al cancelar o eliminar la grabación (forget) o a
     * las 3 h (sweep).
     */
    static final class Kept{
        private final File dir;private final String name,key;
        private Kept(File dir,String name,String key){this.dir=dir;this.name=name;this.key=key;}
        /** null si no hay con qué identificar la parte (sin grabación o sin archivo). */
        static Kept of(File dir,String jobId,File audio,List<String[]> refs,boolean wav,int clean){
            if(jobId==null||!jobId.matches("[a-f0-9-]{36}")||audio==null||!audio.isFile())return null;
            StringBuilder k=new StringBuilder(audio.getAbsolutePath()).append('|').append(audio.length()).append('|').append(audio.lastModified()).append('|').append(wav?"wav":"flac").append("|clean").append(clean);
            if(refs!=null)for(String[] ref:refs)k.append('|').append(ref.length>0?ref[0]:"").append(':').append(digest(ref.length>1?ref[1]:""));
            String key=k.toString();
            return new Kept(dir,"keep-"+jobId+"-"+digest(key).substring(0,16),key);
        }
        /** Ruta de salida sin extensión (OrAudio agrega ".flac" o ".wav"). */
        File base(){return new File(dir,name);}
        private File meta(){return new File(dir,name+".json");}
        boolean owns(File f){return f!=null&&f.getName().startsWith(name);}
        /** El audio guardado de esta parte, o null si no hay (o no coincide). */
        OrAudio.Built load(){
            try{
                File m=meta();if(!m.isFile())return null;
                JSONObject j=new JSONObject(new String(java.nio.file.Files.readAllBytes(m.toPath()),StandardCharsets.UTF_8));
                if(!key.equals(j.optString("key")))return null;
                File f=new File(dir,j.optString("file"));if(!owns(f)||!f.isFile()||f.length()==0)return null;
                JSONArray a=j.optJSONArray("anchors");long[][] anchors=new long[a==null?0:a.length()][];
                for(int i=0;i<anchors.length;i++){JSONArray p=a.getJSONArray(i);anchors[i]=new long[]{p.getLong(0),p.getLong(1)};}
                return new OrAudio.Built(f,j.getString("format"),j.getLong("leadMs"),anchors,j.getLong("durationMs"));
            }catch(Exception e){drop();return null;}
        }
        void save(OrAudio.Built b){
            try{
                JSONArray a=new JSONArray();if(b.anchors!=null)for(long[] p:b.anchors)a.put(new JSONArray().put(p[0]).put(p[1]));
                JSONObject j=new JSONObject().put("key",key).put("file",b.file.getName()).put("format",b.format).put("leadMs",b.leadMs).put("durationMs",b.durationMs).put("anchors",a);
                java.nio.file.Files.write(meta().toPath(),j.toString().getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){drop();}
        }
        void drop(){File[] all=dir.listFiles((d,n)->n.startsWith(name));if(all!=null)for(File f:all)f.delete();}
        private static String digest(String s){
            try{byte[] h=java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format(Locale.ROOT,"%02x",x));return b.toString();}
            catch(Exception e){return Integer.toHexString(s.hashCode());}
        }
    }
    /** Borra el audio preparado que quedó guardado de una grabación (al cancelar, eliminar o terminar su transcripción). */
    static void forget(Context c,String id){
        if(id==null)return;File dir=new File(c.getCacheDir(),"openrouter");File[] all=dir.listFiles((d,n)->n.startsWith("keep-"+id+"-"));
        if(all!=null)for(File f:all)f.delete();
    }
    /** Carpeta de trabajo: lo que quedó de un envío que Android cortó a la mitad se borra en la siguiente pasada. */
    private static void sweep(File dir){File[] old=dir.listFiles();long limit=System.currentTimeMillis()-3*3600_000L;if(old!=null)for(File f:old)if(f.lastModified()<limit)f.delete();}
    /** «data:audio/mp4;base64,…» → archivo. false si no es una data URL o no se pudo escribir. */
    private static boolean decode(String url,File target){
        try{
            int comma=url==null?-1:url.indexOf(',');if(comma<0||!url.startsWith("data:"))return false;
            byte[] data=android.util.Base64.decode(url.substring(comma+1),android.util.Base64.DEFAULT);if(data.length==0)return false;
            try(FileOutputStream out=new FileOutputStream(target)){out.write(data);}
            return true;
        }catch(Exception e){target.delete();return false;}
    }

    // ---------- Envío ----------
    /**
     * Cuerpo del envío (con un marcador donde va el audio). voices: pedir separación de voces según la receta.
     * verbose: verbose_json con tiempos por tramo y por palabra (las palabras permiten cortar justo donde terminan las anclas).
     */
    static JSONObject body(String model,String format,String language,Models.Recipe recipe,boolean voices,boolean verbose)throws JSONException{
        JSONObject b=new JSONObject().put("model",model).put("input_audio",new JSONObject().put("data",AUDIO).put("format",format));
        // Idioma vacío = detección automática: el campo no se envía.
        if(language!=null&&!language.isEmpty())b.put("language",language);
        b.put("response_format",verbose?"verbose_json":"json");
        if(verbose)b.put("timestamp_granularities",new JSONArray().put("segment").put("word"));
        if(voices)switch(recipe.diarize){
            case AZURE:b.put("provider",new JSONObject().put("options",new JSONObject().put("azure",new JSONObject().put("diarization",new JSONObject().put("enabled",true)))));break;
            case DEEPGRAM:b.put("provider",new JSONObject().put("options",new JSONObject().put("deepgram",new JSONObject().put("diarize",true))));break;
            case GENERIC:b.put("diarize",true);break;
            // INLINE: el modelo siempre marca las voces dentro del texto; no hay nada que pedir.
            default:break;
        }
        return b;
    }
    private HttpApi.Response send(OrAudio.Built built,ProviderConfig config,JSONObject body,long[] size)throws Exception{
        String json=body.toString(),mark="\""+AUDIO+"\"";int at=json.indexOf(mark);
        if(at<0)throw new IllegalStateException("Cuerpo sin audio");
        // El prefijo termina en la comilla que abre el audio y el sufijo empieza en la que lo cierra.
        byte[] prefix=json.substring(0,at+1).getBytes(StandardCharsets.UTF_8),suffix=json.substring(at+mark.length()-1).getBytes(StandardCharsets.UTF_8);
        HttpApi.Body content=http.base64(prefix,built.file,suffix);size[0]=content.length();
        return http.request("POST",config.base+"/audio/transcriptions",config.key,"application/json",content,headers());
    }
    /**
     * ¿El 400 es por el formato de respuesta, los tiempos o la separación de voces? (mensaje de OpenRouter o del proveedor
     * final). También cuenta un parámetro que el modelo no reconoce: lo único opcional que se envía es justamente eso.
     * Un 400 por otra causa (audio dañado, muy largo…) no se reintenta: repetirlo solo volvería a subir el audio.
     */
    static boolean formatRejected(String text){
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        return t.contains("response_format")||t.contains("verbose_json")||t.contains("timestamp_granularit")||t.contains("diariz")
            ||t.contains("unrecognized")||t.contains("unknown param")||t.contains("unsupported param");
    }
    /**
     * ¿El 400 es porque el proveedor no pudo con el audio (formato, códec, «dañado»)? El FLAC sale del codificador del
     * teléfono y se comprueba leyéndolo de vuelta, así que un audio que el proveedor no entiende apunta al FLAC: se repite
     * una vez en WAV. Un 400 por el formato de la RESPUESTA (response_format, tiempos) no cuenta aquí, salvo que nombre al FLAC.
     */
    static boolean audioRejected(String text){
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        if(t.contains("flac"))return true;
        if(t.contains("response_format")||t.contains("verbose_json")||t.contains("timestamp_granularit"))return false;
        return (t.contains("audio")||t.contains("file"))&&(t.contains("format")||t.contains("codec")||t.contains("decod")||t.contains("unsupported")||t.contains("not supported")||t.contains("corrupt")||t.contains("invalid"));
    }

    // ---------- Respuesta → tramos ----------
    /** Un trozo de la respuesta (tramo o palabra) con tiempos en segundos del archivo enviado, anclas incluidas. */
    static final class Piece{
        String who,text;double start,end;
        Piece(String who,double start,double end,String text){this.who=who;this.start=start;this.end=end;this.text=text;}
    }
    /** Respuesta leída, antes de quitar las anclas. */
    static final class Parsed{
        final List<Piece> pieces=new ArrayList<>();
        /** Palabras con tiempo (y hablante, si vino): con ellas se mide cada ancla y se corta justo donde terminan. */
        final List<Piece> words=new ArrayList<>();
        /** diarized: vinieron voces. timed: los tiempos son reales (no repartidos ni en cero). */
        boolean diarized,timed;
    }
    private static double time(JSONObject o,String key){return o.isNull(key)?Double.NaN:o.optDouble(key,Double.NaN);}
    /** optString de Android devuelve "null" para un null de JSON: aquí es texto vacío. */
    private static String str(JSONObject o,String key){return o.isNull(key)?"":o.optString(key,"");}
    /** Hablante de un tramo o palabra como texto: los índices enteros (0, 1…) pasan a "0", "1". null si no viene. */
    private static String who(JSONObject o){
        for(String key:new String[]{"speaker","speaker_label","speaker_id"}){
            if(o.isNull(key))continue;Object v=o.opt(key);
            String s=v instanceof Number?String.valueOf(((Number)v).longValue()):String.valueOf(v).trim();
            if(!s.isEmpty())return s;
        }
        return null;
    }
    private static String strip(String text){return text==null?"":MARK.matcher(text).replaceAll(" ").replaceAll("\\s+"," ").trim();}
    /** Agrega una palabra al tramo: con espacio, salvo que sea un signo que va pegado a la palabra anterior. */
    private static void join(StringBuilder b,String token){if(b.length()>0&&!token.matches("^[,.;:!?%)\\]»…].*"))b.append(' ');b.append(token);}

    /**
     * Lee la respuesta de cualquiera de los proveedores (decisión 5):
     * - tramos con hablante → tal cual;
     * - hablantes solo en las palabras → se agrupan palabras seguidas de la misma voz (corta en pausas y a los ~45 s);
     * - marcas «<|speaker:N|>» en el texto → se parte por marcas;
     * - sin voces → tramos "text" con sus tiempos reales si vienen.
     * voices: se pidieron voces (si no, cualquier dato de hablante se ignora). durationMs: duración del archivo enviado.
     */
    static Parsed read(JSONObject json,boolean voices,long durationMs){
        Parsed p=new Parsed();double total=durationMs/1000d;
        JSONArray segs=json.optJSONArray("segments"),top=json.optJSONArray("words");String text=str(json,"text");
        // Las palabras vienen arriba (OpenAI, Deepgram) o dentro de cada tramo.
        List<JSONObject> rawWords=new ArrayList<>();
        if(top!=null){for(int i=0;i<top.length();i++){JSONObject o=top.optJSONObject(i);if(o!=null)rawWords.add(o);}}
        else if(segs!=null)for(int i=0;i<segs.length();i++){JSONObject s=segs.optJSONObject(i);JSONArray inner=s==null?null:s.optJSONArray("words");if(inner!=null)for(int k=0;k<inner.length();k++){JSONObject o=inner.optJSONObject(k);if(o!=null)rawWords.add(o);}}
        // Los tiempos se esperan en segundos. Si el mayor pasa por lejos de la duración del archivo, vienen en milisegundos.
        double max=0;
        if(segs!=null)for(int i=0;i<segs.length();i++){JSONObject o=segs.optJSONObject(i);if(o!=null){double e=time(o,"end");if(!Double.isNaN(e))max=Math.max(max,e);}}
        for(JSONObject o:rawWords){double e=time(o,"end");if(!Double.isNaN(e))max=Math.max(max,e);}
        double scale=total>0&&max>total*50?0.001:1;
        boolean segWho=false,wordWho=false,marks=MARK.matcher(text).find(),segTimed=false,segMarks=false;
        List<Piece> raw=new ArrayList<>();
        if(segs!=null){
            segTimed=segs.length()>0;double last=0;
            for(int i=0;i<segs.length();i++){
                JSONObject o=segs.optJSONObject(i);if(o==null)continue;String w=who(o),t=str(o,"text");segWho|=w!=null;segMarks|=MARK.matcher(t).find();
                double a=time(o,"start")*scale,b=time(o,"end")*scale;
                // Un tramo sin tiempos no rompe nada: sigue donde terminó el anterior, pero ya no se confía en los tiempos.
                if(Double.isNaN(a)||Double.isNaN(b)){segTimed=false;if(Double.isNaN(a))a=last;if(Double.isNaN(b))b=a;}
                if(b<a)b=a;last=b;raw.add(new Piece(w,a,b,t));
            }
            if(max<=0)segTimed=false;
        }
        for(JSONObject o:rawWords){
            String w=who(o),t=str(o,"punctuated_word");if(t.isEmpty())t=str(o,"word");if(t.isEmpty())t=str(o,"text");
            double a=time(o,"start")*scale,b=time(o,"end")*scale;if(Double.isNaN(a))continue;if(Double.isNaN(b)||b<a)b=a;
            wordWho|=w!=null;p.words.add(new Piece(w,a,b,t));
        }
        if(voices&&segWho){
            for(Piece s:raw)p.pieces.add(new Piece(s.who==null?"unknown":s.who,s.start,s.end,strip(s.text)));
            p.diarized=true;p.timed=segTimed;
        }else if(voices&&wordWho){
            p.pieces.addAll(group(p.words,true));p.diarized=true;p.timed=true;
        }else if(voices&&(marks||segMarks)){
            // Con tiempos por tramo, cada tramo se reparte entre sus marcas; si no, el texto entero se reparte en la duración.
            List<Piece> units=raw;p.timed=segTimed&&segMarks;
            if(!p.timed){
                // Las marcas están en el texto completo, o solo en tramos sin tiempos: en ese caso se juntan los tramos.
                StringBuilder all=new StringBuilder(marks?text:"");if(!marks)for(Piece s:raw)all.append(' ').append(s.text);
                units=Collections.singletonList(new Piece(null,0,total,all.toString()));
            }
            String voice="unknown";
            for(Piece u:units){
                Matcher m=MARK.matcher(u.text);List<String[]> chunks=new ArrayList<>();int at=0;
                while(m.find()){String said=u.text.substring(at,m.start()).trim();if(!said.isEmpty())chunks.add(new String[]{voice,said});voice=m.group(1);at=m.end();}
                String tail=u.text.substring(at).trim();if(!tail.isEmpty())chunks.add(new String[]{voice,tail});
                int chars=0;for(String[] chunk:chunks)chars+=chunk[1].length();
                double t=u.start,span=Math.max(0,u.end-u.start);
                for(String[] chunk:chunks){double d=chars==0?0:span*chunk[1].length()/chars;p.pieces.add(new Piece(chunk[0],t,t+d,chunk[1].replaceAll("\\s+"," ")));t+=d;}
            }
            p.diarized=true;
        }else{
            if(segTimed){for(Piece s:raw)p.pieces.add(new Piece("text",s.start,s.end,strip(s.text)));p.timed=true;}
            else if(!p.words.isEmpty()){p.pieces.addAll(group(p.words,false));p.timed=true;}
            else{
                String all=strip(text);
                if(all.isEmpty()){StringBuilder b=new StringBuilder();for(Piece s:raw){String t=strip(s.text);if(!t.isEmpty())b.append(b.length()>0?" ":"").append(t);}all=b.toString();}
                p.pieces.add(new Piece("text",0,0,all));
            }
        }
        return p;
    }
    /** Palabras → tramos: seguidas de la misma voz, cortando en pausas de más de 1,2 s y a los ~45 s. withWho=false: todo es "text". */
    private static List<Piece> group(List<Piece> words,boolean withWho){
        List<Piece> out=new ArrayList<>();Piece current=null;StringBuilder b=new StringBuilder();String last=withWho?"unknown":"text";
        for(Piece w:words){
            String token=strip(w.text);if(token.isEmpty())continue;
            // Una palabra sin hablante (p. ej. un signo suelto) sigue con la voz anterior.
            String voice=!withWho?"text":w.who!=null?w.who:last;last=voice;
            if(current==null||!current.who.equals(voice)||w.start-current.end>PAUSE_S||w.end-current.start>TURN_MAX_S){
                if(current!=null){current.text=b.toString();out.add(current);}
                current=new Piece(voice,w.start,w.end,"");b=new StringBuilder();
            }
            join(b,token);current.end=Math.max(current.end,w.end);
        }
        if(current!=null){current.text=b.toString();out.add(current);}
        return out;
    }

    // ---------- Anclas ----------
    /** A quién corresponde una muestra: su id global si viene (varias muestras pueden ser de la misma persona) o su nombre enviado. */
    private static String person(String[] ref){return ref.length>2&&ref[2]!=null?ref[2]:ref[0];}
    /**
     * Termina la respuesta (decisión 3): empareja cada ancla con el hablante que más suena en su ventana, le pone a ese
     * hablante el nombre enviado de la muestra, borra lo que cae en la zona de anclas y resta leadMs a los tiempos.
     * - Un ancla se empareja con el hablante que suma más segundos dentro de su ventana, con al menos 1 s o el 40 % del ancla.
     * - Un hablante queda con una sola ancla (la de mayor coincidencia); las demás quedan sin emparejar. No es un error.
     * windows: {inicio ms, fin ms} de cada ancla dentro del archivo ({-1,-1} si se omitió), en el orden de refs.
     * Devuelve {"segments":[…],"_diarized":bool} y, si hubo anclas, "_anchors" (personas enviadas) y "_matched" (reconocidas).
     */
    static JSONObject finish(Parsed p,long leadMs,long[][] windows,List<String[]> refs)throws JSONException{
        double lead=Math.max(0,leadMs)/1000d;
        // El corte va a mitad del silencio que separa la última ancla del audio: tolera tiempos del modelo corridos hacia
        // cualquier lado. Se mide con el fin real de la última ancla; si no se conoce, medio silencio antes del audio.
        long lastEnd=-1;if(windows!=null)for(long[] w:windows)if(w!=null&&w.length>=2&&w[0]>=0&&w[1]>w[0]&&w[1]<=leadMs)lastEnd=Math.max(lastEnd,w[1]);
        double cut=leadMs<=0?0:lastEnd>0?(lastEnd+leadMs)/2000d:Math.max(0,lead-OrAudio.GAP_MS/2000d);
        Map<String,String> rename=new HashMap<>();Set<String> people=new LinkedHashSet<>(),got=new HashSet<>();
        if(leadMs>0&&windows!=null&&refs!=null){
            int n=Math.min(windows.length,refs.size());String[] best=new String[n];double[] score=new double[n];
            boolean byWord=false;for(Piece w:p.words)if(w.who!=null){byWord=true;break;}
            List<Piece> units=byWord?p.words:p.pieces;
            for(int i=0;i<n;i++){
                long[] w=windows[i];if(w==null||w.length<2||w[0]<0||w[1]<=w[0])continue;
                people.add(person(refs.get(i)));if(!p.diarized)continue;
                double a=w[0]/1000d,b=w[1]/1000d;Map<String,Double> talk=new LinkedHashMap<>();
                for(Piece u:units){if(u.who==null||u.who.equals("unknown")||u.who.equals("text"))continue;double o=Math.min(u.end,b)-Math.max(u.start,a);if(o>0)talk.merge(u.who,o,Double::sum);}
                double need=Math.min(1.0,0.4*(b-a));
                for(Map.Entry<String,Double> e:talk.entrySet())if(e.getValue()>=need&&e.getValue()>score[i]){score[i]=e.getValue();best[i]=e.getKey();}
            }
            Map<String,Integer> winner=new LinkedHashMap<>();
            for(int i=0;i<n;i++){if(best[i]==null)continue;Integer w=winner.get(best[i]);if(w==null||score[i]>score[w])winner.put(best[i],i);}
            for(Map.Entry<String,Integer> e:winner.entrySet()){String[] ref=refs.get(e.getValue());rename.put(e.getKey(),ref[0]);got.add(person(ref));}
        }
        JSONArray segments=new JSONArray();
        for(Piece piece:p.pieces){
            double start=piece.start,end=Math.max(piece.start,piece.end);String text=piece.text;
            if(leadMs>0){
                // Entero dentro de la zona de anclas: es la muestra hablando, no la grabación.
                if(end<=cut)continue;
                // A caballo entre la última ancla y el audio: queda solo lo dicho después del corte.
                if(start<cut){text=tail(p.words,piece,cut,lastEnd>0?lastEnd/1000d:-1,lead);start=cut;}
                start=Math.max(0,start-lead);end=Math.max(start,end-lead);
            }
            text=text==null?"":text.trim();if(text.isEmpty())continue;
            String voice=rename.containsKey(piece.who)?rename.get(piece.who):piece.who;
            segments.put(new JSONObject().put("speaker",voice==null?"unknown":voice).put("start",round(start)).put("end",round(end)).put("text",text));
        }
        JSONObject out=new JSONObject().put("segments",segments).put("_diarized",p.diarized);
        if(!people.isEmpty())out.put("_anchors",people.size()).put("_matched",got.size());
        return out;
    }
    private static double round(double seconds){return Math.round(seconds*1000)/1000d;}
    /**
     * Lo que un tramo dice después del corte: con las palabras si tienen tiempo; si no, en proporción al tiempo.
     * anchorEnd: fin real de la última ancla (s; -1 si no se conoce) y lead: comienzo del audio (s). Entre los dos hay
     * silencio puesto por OrAudio, así que la proporción se reparte solo entre lo que sonó del ancla (antes de anchorEnd)
     * y lo que sonó del audio (después de lead): un tramo que empieza en ese silencio no pierde palabras reales.
     */
    private static String tail(List<Piece> words,Piece piece,double cut,double anchorEnd,double lead){
        StringBuilder b=new StringBuilder();boolean any=false;
        for(Piece w:words){
            double mid=(w.start+w.end)/2;if(mid<piece.start-0.01||mid>piece.end+0.01)continue;
            if(w.who!=null&&piece.who!=null&&!w.who.equals(piece.who))continue;
            any=true;String token=strip(w.text);if(mid>=cut&&!token.isEmpty())join(b,token);
        }
        if(any)return b.toString();
        String[] tokens=piece.text.trim().split("\\s+");double span=piece.end-piece.start;int skip;
        if(anchorEnd>0){double anchor=Math.max(0,anchorEnd-piece.start),real=Math.max(0,piece.end-lead);skip=anchor<=0?0:(int)Math.round(tokens.length*anchor/(anchor+real));}
        else skip=span<=0?0:(int)Math.round(tokens.length*(cut-piece.start)/span);
        skip=Math.max(0,Math.min(skip,tokens.length));
        return String.join(" ",Arrays.copyOfRange(tokens,skip,tokens.length));
    }

    // ---------- Costo ----------
    private static boolean bad(double v){return Double.isNaN(v)||Double.isInfinite(v)||v<0;}
    /** US$ reales del envío (usage.cost); 0 si no viene. */
    private static double cost(JSONObject json){JSONObject u=json.optJSONObject("usage");double v=u==null||u.isNull("cost")?Double.NaN:u.optDouble("cost",Double.NaN);return bad(v)?0:v;}
    /**
     * Uso en el formato del motor (decisión 6): segundos facturados (usage.seconds, usage.duration o duration; si no
     * vienen, lo que dura el archivo enviado) y "cost" en US$ si OpenRouter lo informa. before: costo de un envío anterior
     * de este mismo bloque que se descartó (también se cobró).
     */
    static JSONObject usage(JSONObject json,long durationMs,double before)throws JSONException{
        JSONObject u=json.optJSONObject("usage");double seconds=Double.NaN;boolean priced=false;
        if(u!=null){
            seconds=u.isNull("seconds")?Double.NaN:u.optDouble("seconds",Double.NaN);
            if(bad(seconds))seconds=u.isNull("duration")?Double.NaN:u.optDouble("duration",Double.NaN);
            priced=!u.isNull("cost")&&!bad(u.optDouble("cost",Double.NaN));
        }
        if(bad(seconds))seconds=json.isNull("duration")?Double.NaN:json.optDouble("duration",Double.NaN);
        if(bad(seconds))seconds=Math.max(0,durationMs)/1000d;
        JSONObject out=new JSONObject().put("type","duration").put("seconds",seconds);
        if(priced||before>0)out.put("cost",cost(json)+before);
        if(u!=null&&u.has("input_tokens"))out.put("input_tokens",u.optLong("input_tokens"));
        if(u!=null&&u.has("output_tokens"))out.put("output_tokens",u.optLong("output_tokens"));
        return out;
    }
}
