package cl.vozlocal.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

class HttpApi {
    volatile boolean cancelled;
    private volatile HttpURLConnection active;
    String jobId;
    Runnable onUploaded;
    /** Espera máxima de respuesta. Las transcripciones la ajustan a la duración del audio (un bloque largo puede tardar varios minutos). */
    volatile int readTimeoutMs=240000;
    interface Progress{void update(long sent,long total);}
    /** Progreso de subida del archivo (bytes enviados / total). */
    volatile Progress onProgress;
    /** Eventos de una respuesta en streaming (text/event-stream): cada línea "data:" como JSON. */
    interface Events{void event(JSONObject event)throws Exception;}
    volatile Events onEvent;
    /** Última señal de avance (SystemClock.elapsedRealtime, que sigue contando con la app congelada o el teléfono dormido). */
    volatile long lastActivity;
    /** Motivo si el vigilante cortó la conexión por falta de avance; null si no. */
    volatile String stalled;
    private volatile boolean stalledLocal=true;
    /**
     * Conexión cortada por el vigilante. local=true: la cortó el propio teléfono (el envío dejó de avanzar, o Android
     * congeló la app mientras esperaba): se repite sin gastar un intento. local=false (0.8.0): el proveedor no respondió
     * a tiempo con el teléfono funcionando bien; eso sí gasta un intento, porque repetirlo gratis daba vueltas por más de
     * una hora sin avisar (12 cortes «gratis» de hasta 5 min cada uno).
     */
    static class Stalled extends IOException{final boolean local;Stalled(String m){this(m,true);}Stalled(String m,boolean local){super(m);this.local=local;}}
    /** Corta la conexión activa sin cancelar el trabajo (a diferencia de cancel()). */
    void abortStalled(String why){abortStalled(why,true);}
    void abortStalled(String why,boolean local){stalledLocal=local;stalled=why;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    private void touch(){lastActivity=android.os.SystemClock.elapsedRealtime();}
    // ---------- 0.8.0: en qué paso va el envío (para el vigilante y el informe) ----------
    /** Pasos: nada, preparando el audio (OpenRouter lo convierte antes de enviarlo), subiendo, esperando la respuesta y leyéndola. */
    static final int IDLE=0,PREPARE=1,UPLOAD=2,WAIT=3,READ=4;
    /** Nombre técnico de cada paso para Diagnostics (clave "stage"). */
    static final String[] PHASES={"idle","prepare","upload","response","read"};
    /** Paso actual y el último distinto de IDLE (dónde quedó un envío que falló). */
    volatile int phase,lastPhase;
    private void phase(int p){phase=p;if(p!=IDLE)lastPhase=p;}
    /** Aviso de inicio (started=true) y fin (ok: terminó bien) de la preparación del audio: la etapa «Preparando audio». */
    interface Preparing{void changed(boolean started,boolean ok);}
    volatile Preparing onPreparing;
    /** Por qué el vigilante cortó una preparación que no terminaba; null si no. */
    private volatile String prepareStalled;
    /**
     * La preparación del audio tardó demasiado: lo lanza check(). Es un InterruptedIOException para que la conversión lo
     * deje pasar tal cual (no se confunde con una falla del FLAC ni lo apaga para los bloques siguientes).
     */
    static class PrepareStalled extends InterruptedIOException{PrepareStalled(String m){super(m);}}
    /** Avance de la preparación en % (0.8.0, tercera ronda): la etapa «Preparando audio» lo muestra en pantalla y en la notificación. */
    interface PrepareProgress{void percent(int value);}
    volatile PrepareProgress onPrepareProgress;
    private volatile int preparedPercent=-1;
    /**
     * OrAudio informa cuánto lleva convertido (0–100). Solo avanza (un reintento en WAV no lo devuelve a cero en pantalla) y
     * solo avisa cuando cambia el número: el decodificador llama miles de veces por bloque.
     */
    void prepared(int percent){
        int p=Math.max(0,Math.min(100,percent));if(p<=preparedPercent)return;preparedPercent=p;
        PrepareProgress callback=onPrepareProgress;if(callback!=null)try{callback.percent(p);}catch(RuntimeException ignored){}
    }
    /** El cliente empieza a preparar el audio. Mientras dure, el vigilante no lo toma como una subida detenida. */
    void startPreparing(){prepareStalled=null;preparedPercent=-1;phase(PREPARE);Preparing p=onPreparing;if(p!=null)p.changed(true,false);}
    /** Terminó la preparación (ok=false si falló). El tiempo sin avance se mide desde aquí. */
    void endPreparing(boolean ok){prepareStalled=null;if(phase==PREPARE)phase=IDLE;touch();Preparing p=onPreparing;if(p!=null)p.changed(false,ok);}
    /** El vigilante corta una preparación que no termina: el próximo check() lo hace saber. */
    void abortPreparing(String why){prepareStalled=why;}
    /** OpenRouter: costo (US$) de un envío cobrado que no quedó en ninguna respuesta (se descartó y el reenvío falló). */
    interface Billed{void cost(double usd);}
    volatile Billed onBilled;
    /**
     * Revisión justo antes de abrir cada conexión (0.8.0, tercera ronda): el motor mira ahí «Solo con Wi-Fi», que pudo
     * cambiar desde que empezó la grabación (p. ej. se fue el Wi-Fi mientras se preparaba el audio). Si lanza, no se envía
     * nada. Solo la tienen las conexiones de las partes (la nota y las consultas no envían audio).
     */
    interface Gate{void pass()throws Exception;}
    volatile Gate beforeSend;
    /** Conexiones hijas (bloques en paralelo): cancelar la madre cancela todas. */
    private final HttpApi parent;private final java.util.List<HttpApi> children=new java.util.concurrent.CopyOnWriteArrayList<>();
    HttpApi(){parent=null;}
    private HttpApi(HttpApi parent){this.parent=parent;this.jobId=parent.jobId;this.readTimeoutMs=parent.readTimeoutMs;this.audioClean=parent.audioClean;}
    /**
     * 0.9.3: limpieza del audio que se envía a transcribir (AudioClean.LEVEL|NOISE; 0 = nada). La pone el motor con los
     * ajustes de ese momento (Transcriber); OrAudio la lee al armar el archivo y OpenRouterClient la suma a la clave del
     * audio guardado (Kept), para no reutilizar uno armado con otra limpieza.
     */
    volatile int audioClean;
    HttpApi child(){HttpApi c=new HttpApi(this);children.add(c);if(cancelled)c.cancelled=true;return c;}
    interface Body { long length(); void write(OutputStream out) throws Exception; }
    static class Response {
        String jobId; final int code; final String text, location,requestId;
        /** 0.9.1: lo que el servidor pidió esperar antes de reintentar (cabecera Retry-After), en ms; 0 si no lo dijo. */
        long retryAfterMs;
        Response(int code,String text,String location){this(code,text,location,"");}
        Response(int code,String text,String location,String requestId){this.code=code;this.text=text;this.location=location;this.requestId=safeToken(requestId);}
        JSONObject json() throws Exception { return text.isEmpty()?new JSONObject():new JSONObject(text); }
    }
    static class UserAction extends Exception { UserAction(String message){super(message);} }
    /**
     * 0.9.1: el servicio está caído o saturado (408, 429, 5xx). No es culpa del teléfono, de la red ni de la clave: el motor
     * lo reintenta solo por más tiempo que un error común (Transcriber.outcome) y respeta la espera que pidió el servidor.
     * Sigue siendo una IOException, así que todo lo que ya trataba estos errores como reintentables sigue igual.
     */
    static class ServerBusy extends IOException {
        /** El código HTTP (429, 502…). */
        final int code;
        /** Lo que el servidor pidió esperar (Retry-After), en ms; 0 si no lo dijo. */
        final long retryAfterMs;
        ServerBusy(String message,int code,long retryAfterMs){super(message);this.code=code;this.retryAfterMs=retryAfterMs;}
    }
    /** ¿e (o lo que la causó) es una caída del servidor? */
    static ServerBusy serverBusy(Throwable e){for(int i=0;e!=null&&i<6;i++,e=e.getCause())if(e instanceof ServerBusy)return (ServerBusy)e;return null;}
    /** Tope de la espera que se acepta de un Retry-After (una hora). */
    static final long RETRY_AFTER_MAX_MS=3600_000L;
    /**
     * Retry-After en ms: segundos («120») o una fecha HTTP («Wed, 21 Oct 2026 07:28:00 GMT»), con tope de una hora.
     * 0 si no viene o no se entiende. now: la hora actual en ms (para la fecha).
     */
    static long retryAfter(String value,long now){
        if(value==null)return 0;String v=value.trim();if(v.isEmpty())return 0;
        try{long s=Long.parseLong(v);return s<=0?0:Math.min(RETRY_AFTER_MAX_MS,s*1000L);}catch(NumberFormatException ignored){}
        try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            java.util.Date d=f.parse(v);if(d==null)return 0;long ms=d.getTime()-now;return ms<=0?0:Math.min(RETRY_AFTER_MAX_MS,ms);}
        catch(Exception ignored){return 0;}
    }
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();for(HttpApi c:children)c.cancel();}
    void check() throws InterruptedIOException {
        if(cancelled || (parent!=null&&parent.cancelled) || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Trabajo pausado");
        String why=prepareStalled;if(why!=null)throw new PrepareStalled(why);
    }
    Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra) throws Exception {
        check();Gate gate=beforeSend;if(gate!=null)gate.pass(); URL target=new URL(url);long started=System.currentTimeMillis();Diagnostics.event("http_start",jobId,"bytes",body==null?0:body.length());
        if(!"https".equals(target.getProtocol()))throw new SecurityException(Lang.str(R.string.eng_err_https_only));
        HttpURLConnection c=(HttpURLConnection)target.openConnection();active=c;stalled=null;stalledLocal=true;phase(body!=null?UPLOAD:WAIT);touch();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(readTimeoutMs);c.setRequestMethod(method);
            if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
            if(contentType!=null)c.setRequestProperty("Content-Type",contentType);
            if(extra!=null)for(Map.Entry<String,String> entry:extra.entrySet())c.setRequestProperty(entry.getKey(),entry.getValue());
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length());try(OutputStream out=c.getOutputStream()){body.write(out);}touch();phase(WAIT);if(onUploaded!=null)onUploaded.run();}
            check();int code=c.getResponseCode();phase(READ);String location=c.getHeaderField("Location");
            String type=c.getContentType();Events events=onEvent;
            if(code<400&&events!=null&&type!=null&&type.contains("event-stream")){
                // Streaming: el texto llega por partes; se guarda solo el evento final (texto completo + uso).
                JSONObject done=null;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){check();touch();if(!line.startsWith("data:"))continue;String data=line.substring(5).trim();if(data.isEmpty()||data.equals("[DONE]"))continue;JSONObject event=new JSONObject(data);events.event(event);if(event.optString("type").endsWith(".done"))done=event;}}
                if(done==null)throw new IOException(Lang.str(R.string.eng_err_stream_cut));
                String requestId=c.getHeaderField("x-request-id");Diagnostics.event("http_end",jobId,"http",code,"request_id",safeToken(requestId),"elapsed_ms",System.currentTimeMillis()-started);
                Response response=new Response(code,done.toString(),location,requestId);response.jobId=jobId;response.retryAfterMs=retryAfter(c.getHeaderField("Retry-After"),System.currentTimeMillis());return response;
            }
            InputStream raw=code>=400?c.getErrorStream():c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(raw!=null)try(InputStream in=raw){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){check();if(bytes.size()+n>8*1024*1024)throw new IOException(Lang.str(R.string.eng_err_response_too_big));bytes.write(buffer,0,n);}}
            String requestId=c.getHeaderField("x-request-id");Diagnostics.event("http_end",jobId,"http",code,"request_id",safeToken(requestId),"elapsed_ms",System.currentTimeMillis()-started);
            Response response=new Response(code,bytes.toString(StandardCharsets.UTF_8.name()),location,requestId);response.jobId=jobId;response.retryAfterMs=retryAfter(c.getHeaderField("Retry-After"),System.currentTimeMillis());return response;
        }catch(Exception e){
            String why=stalled;if(why!=null&&!(e instanceof Stalled)){Stalled s=new Stalled(why,stalledLocal);s.initCause(e);e=s;}
            Diagnostics.event("http_failure",jobId,"error_class",e.getClass().getSimpleName(),"elapsed_ms",System.currentTimeMillis()-started,"reason",safeReason(e),"stage",PHASES[lastPhase]);throw e;
        }finally{c.disconnect();active=null;phase=IDLE;}
    }
    static Body bytes(byte[] bytes){return new Body(){public long length(){return bytes.length;}public void write(OutputStream out)throws Exception{out.write(bytes);}};}
    static Body json(JSONObject json){return bytes(json.toString().getBytes(StandardCharsets.UTF_8));}
    Body file(File file){return new Body(){public long length(){return file.length();}public void write(OutputStream out)throws Exception{copy(file,out);}};}
    void copy(File file,OutputStream out)throws Exception{long total=file.length(),sent=0;Progress progress=onProgress;try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);sent+=n;touch();if(progress!=null)progress.update(sent,total);}}}
    // ---------- 0.8.0: cuerpo JSON con el audio en base64 (OpenRouter) ----------
    /** Largo exacto de n bytes en base64 sin saltos de línea (con relleno «=»). */
    static long base64Length(long bytes){return 4*((bytes+2)/3);}
    /**
     * Cuerpo prefijo + archivo en base64 + sufijo, sin cargar el audio en memoria (un bloque de 12 min son ~15 MB, y van
     * hasta 3 en paralelo). El largo se conoce antes de escribir, como exige setFixedLengthStreamingMode: por eso el
     * tamaño del archivo se fija al crear el cuerpo y, si cambió al enviar, se falla antes de mandar un JSON cortado.
     */
    Body base64(byte[] prefix,File file,byte[] suffix){
        long size=file.length();
        return new Body(){
            public long length(){return prefix.length+base64Length(size)+suffix.length;}
            public void write(OutputStream out)throws Exception{if(file.length()!=size)throw new IOException(Lang.str(R.string.eng_err_audio_changed));out.write(prefix);copyBase64(file,out);out.write(suffix);}
        };
    }
    /**
     * Igual que copy(), pero en base64: avanza el vigilante (touch) y el progreso de subida en cada vuelta, y respeta la
     * cancelación. Lee de a 48 KB (múltiplo de 3): así solo la última vuelta lleva relleno y el resultado es el mismo
     * que codificar el archivo entero. El progreso se informa en bytes del archivo, no del texto base64.
     */
    void copyBase64(File file,OutputStream out)throws Exception{
        long total=file.length(),sent=0;Progress progress=onProgress;
        try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[49152];
            while(true){
                // read() puede devolver menos de lo pedido: se llena el bloque entero para no meter relleno a mitad del archivo.
                int n=0,k;while(n<b.length&&(k=in.read(b,n,b.length-n))!=-1)n+=k;
                if(n==0)break;
                check();out.write(android.util.Base64.encode(b,0,n,android.util.Base64.NO_WRAP));sent+=n;touch();if(progress!=null)progress.update(sent,total);
                if(n<b.length)break;
            }
        }
    }
    /** Nombre de servicio de OpenRouter para require(): activa sus mensajes propios (sin saldo, modelo retirado). */
    static final String OPENROUTER="OpenRouter";
    /**
     * 413: el envío pesa más de lo que acepta el proveedor. Sigue siendo un UserAction (repetir lo mismo no sirve), pero
     * con clase propia: el motor la usa para achicar a la mitad los bloques de OpenRouter, una sola vez.
     */
    static class TooLarge extends UserAction{TooLarge(String message){super(message);}}
    /**
     * Convierte una respuesta de error en lo que se le dice a la persona, en el idioma de la app. service: el nombre que
     * se muestra («OpenRouter», «OpenAI», «Proveedor»…). Los errores que se reintentan (IOException) empiezan con ese
     * nombre en los tres idiomas: Notes y Ajustes lo usan para saber que respondió el servicio (no la red del teléfono).
     */
    static void require(Response response,String service)throws Exception{
        if(response.code>=200 && response.code<300)return;
        boolean router=OPENROUTER.equals(service);
        String code="",type="",param="",all="";int reason=response.code==413?R.string.eng_reason_too_large:R.string.eng_reason_check_format;
        // OpenRouter: {"error":{"code":N,"message":"…","metadata":{"raw":"…"}}}. El mensaje suele ser genérico («Provider
        // returned error») y el detalle del proveedor final viene en metadata.raw: se miran los dos. Nunca se muestran tal cual.
        try{JSONObject error=response.json().optJSONObject("error");if(error!=null){code=safeToken(error.optString("code"));type=safeToken(error.optString("type"));param=safeToken(error.optString("param"));JSONObject meta=error.optJSONObject("metadata");String message=(error.optString("message")+" "+(meta==null?"":meta.optString("raw"))).toLowerCase(Locale.ROOT);all=message;
            if(message.contains("duration")||message.contains("too long"))reason=R.string.eng_reason_duration;
            else if(message.contains("format")||message.contains("decode")||message.contains("corrupt"))reason=R.string.eng_reason_decode;
            else if(message.contains("too short")||message.contains("empty"))reason=R.string.eng_reason_short;
            else if(message.contains("model"))reason=R.string.eng_reason_model;
            else if(message.contains("size")||message.contains("large"))reason=R.string.eng_reason_size;
        }}catch(Exception ignored){}
        Diagnostics.event("api_rejected",response.jobId,"http",response.code,"request_id",response.requestId,"code",code,"type",type,"param",param);
        // Dice «clave» y termina con «Revísala en Ajustes.» (key_word, key_fix_in_settings): StatusText.aboutKey y
        // withoutSettingsHint lo reconocen en los tres idiomas.
        if(response.code==401)throw new UserAction(Lang.str(R.string.eng_err_key_rejected,Lang.str(R.string.key_fix_in_settings)));
        if(router&&response.code==402)throw new UserAction(Lang.str(R.string.eng_err_or_no_credits));
        // 404 de OpenRouter: el modelo se retiró (o ningún proveedor lo ofrece con la privacidad elegida en la cuenta).
        if(router&&response.code==404)throw new UserAction(Lang.str(all.contains("data policy")||all.contains("privacy")?R.string.eng_err_or_privacy:R.string.eng_err_or_model_gone));
        if(response.code==403)throw new UserAction(Lang.str(R.string.eng_err_forbidden,service));
        if(response.code==429 && response.text.contains("insufficient_quota"))throw new UserAction(Lang.str(R.string.eng_err_no_quota));
        // Se reintentan. Con OpenRouter, un 429 es su límite de ritmo (o el del proveedor final). 0.9.1: son caídas del servicio
        // (ServerBusy), que el motor reintenta solo por más tiempo y respetando la espera que pidió el servidor.
        if(response.code==408 || response.code==429 || response.code>=500)throw new ServerBusy(router&&response.code==429?Lang.str(R.string.eng_err_or_rate_limited):Lang.str(R.string.eng_err_unavailable,service,response.code),response.code,response.retryAfterMs);
        String text=Lang.str(R.string.eng_err_http,service,response.code,Lang.str(reason))+(code.isEmpty()?"":" "+Lang.str(R.string.eng_err_code,code))+(param.isEmpty()?"":" "+Lang.str(R.string.eng_err_param,param))+(response.requestId.isEmpty()?"":" · "+Lang.str(R.string.eng_err_ref,response.requestId));
        if(response.code==413)throw new TooLarge(text);
        throw new UserAction(text);
    }
    /** Mensaje técnico de la excepción, solo letras y signos simples (sin URLs, números de puerto ni datos). */
    static String safeReason(Exception e){String m=e.getMessage();if(m==null)return "";m=m.replaceAll("https?://[^ ]+","").replaceAll("[^A-Za-z ._:-]","").trim();return m.length()>80?m.substring(0,80):m;}
    static String safeToken(String value){return value!=null && value.matches("[A-Za-z0-9_.:/-]{1,120}")?value:"";}
}
