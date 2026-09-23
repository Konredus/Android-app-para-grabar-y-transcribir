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
    interface Body { long length(); void write(OutputStream out) throws Exception; }
    static class Response {
        String jobId; final int code; final String text, location,requestId;
        Response(int code,String text,String location){this(code,text,location,"");}
        Response(int code,String text,String location,String requestId){this.code=code;this.text=text;this.location=location;this.requestId=safeToken(requestId);}
        JSONObject json() throws Exception { return text.isEmpty()?new JSONObject():new JSONObject(text); }
    }
    static class UserAction extends Exception { UserAction(String message){super(message);} }
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    void check() throws InterruptedIOException {if(cancelled || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Trabajo pausado");}
    Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra) throws Exception {
        check(); URL target=new URL(url);long started=System.currentTimeMillis();Diagnostics.event("http_start",jobId,"bytes",body==null?0:body.length());
        if(!"https".equals(target.getProtocol()))throw new SecurityException("Solo HTTPS");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();active=c;
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(readTimeoutMs);c.setRequestMethod(method);
            c.setRequestProperty("Authorization","Bearer "+token);
            if(contentType!=null)c.setRequestProperty("Content-Type",contentType);
            if(extra!=null)for(Map.Entry<String,String> entry:extra.entrySet())c.setRequestProperty(entry.getKey(),entry.getValue());
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length());try(OutputStream out=c.getOutputStream()){body.write(out);}if(onUploaded!=null)onUploaded.run();}
            check();int code=c.getResponseCode();String location=c.getHeaderField("Location");
            InputStream raw=code>=400?c.getErrorStream():c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(raw!=null)try(InputStream in=raw){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){check();if(bytes.size()+n>8*1024*1024)throw new IOException("Respuesta demasiado grande");bytes.write(buffer,0,n);}}
            String requestId=c.getHeaderField("x-request-id");Diagnostics.event("http_end",jobId,"http",code,"request_id",safeToken(requestId),"elapsed_ms",System.currentTimeMillis()-started);
            Response response=new Response(code,bytes.toString(StandardCharsets.UTF_8.name()),location,requestId);response.jobId=jobId;return response;
        }catch(Exception e){Diagnostics.event("http_failure",jobId,"error_class",e.getClass().getSimpleName(),"elapsed_ms",System.currentTimeMillis()-started);throw e;
        }finally{c.disconnect();active=null;}
    }
    static Body bytes(byte[] bytes){return new Body(){public long length(){return bytes.length;}public void write(OutputStream out)throws Exception{out.write(bytes);}};}
    static Body json(JSONObject json){return bytes(json.toString().getBytes(StandardCharsets.UTF_8));}
    Body file(File file){return new Body(){public long length(){return file.length();}public void write(OutputStream out)throws Exception{copy(file,out);}};}
    void copy(File file,OutputStream out)throws Exception{long total=file.length(),sent=0;Progress progress=onProgress;try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);sent+=n;if(progress!=null)progress.update(sent,total);}}}
    static void require(Response response,String service)throws Exception{
        if(response.code>=200 && response.code<300)return;
        String code="",type="",param="",reason="Revisa el formato del audio y los parámetros del modelo.";
        try{JSONObject error=response.json().optJSONObject("error");if(error!=null){code=safeToken(error.optString("code"));type=safeToken(error.optString("type"));param=safeToken(error.optString("param"));String message=error.optString("message").toLowerCase(Locale.ROOT);
            if(message.contains("duration")||message.contains("too long"))reason="El modelo rechazó la duración del audio. Prueba un tramo más corto.";
            else if(message.contains("format")||message.contains("decode")||message.contains("corrupt"))reason="El proveedor no pudo decodificar este audio. Prueba importar una copia para convertirla.";
            else if(message.contains("too short")||message.contains("empty"))reason="El audio está vacío o es demasiado corto. Prueba una grabación de al menos unos segundos.";
            else if(message.contains("model"))reason="El modelo no está disponible o no admite estos parámetros. Revisa el modelo y sus capacidades.";
            else if(message.contains("size")||message.contains("large"))reason="El bloque supera un límite del proveedor. Prueba recortar el audio.";
        }}catch(Exception ignored){}
        Diagnostics.event("api_rejected",response.jobId,"http",response.code,"request_id",response.requestId,"code",code,"type",type,"param",param);
        if(response.code==401)throw new UserAction("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.");
        if(response.code==403)throw new UserAction("Sin permiso en "+service+". Revisa la cuenta y la configuración del servicio.");
        if(response.code==429 && response.text.contains("insufficient_quota"))throw new UserAction("El proveedor no tiene saldo o cuota disponible. Revisa la facturación de tu API.");
        if(response.code==408 || response.code==429 || response.code>=500)throw new IOException(service+" no está disponible temporalmente ("+response.code+").");
        throw new UserAction(service+" · HTTP "+response.code+". "+reason+(code.isEmpty()?"":" Código: "+code)+(param.isEmpty()?"":" Parámetro: "+param)+(response.requestId.isEmpty()?"":" · Ref: "+response.requestId));
    }
    static String safeToken(String value){return value!=null && value.matches("[A-Za-z0-9_.:/-]{1,120}")?value:"";}
}
