package cl.vozlocal.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

class HttpApi {
    volatile boolean cancelled;
    private volatile HttpURLConnection active;
    interface Body { long length(); void write(OutputStream out) throws Exception; }
    static class Response {
        final int code; final String text, location;
        Response(int code,String text,String location){this.code=code;this.text=text;this.location=location;}
        JSONObject json() throws Exception { return text.isEmpty()?new JSONObject():new JSONObject(text); }
    }
    static class UserAction extends Exception { UserAction(String message){super(message);} }
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    void check() throws InterruptedIOException {if(cancelled || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Trabajo pausado");}
    Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra) throws Exception {
        check(); URL target=new URL(url);
        if(!"https".equals(target.getProtocol()))throw new SecurityException("Solo HTTPS");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();active=c;
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(240000);c.setRequestMethod(method);
            c.setRequestProperty("Authorization","Bearer "+token);
            if(contentType!=null)c.setRequestProperty("Content-Type",contentType);
            if(extra!=null)for(Map.Entry<String,String> entry:extra.entrySet())c.setRequestProperty(entry.getKey(),entry.getValue());
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length());try(OutputStream out=c.getOutputStream()){body.write(out);}}
            check();int code=c.getResponseCode();String location=c.getHeaderField("Location");
            InputStream raw=code>=400?c.getErrorStream():c.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(raw!=null)try(InputStream in=raw){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){check();if(bytes.size()+n>8*1024*1024)throw new IOException("Respuesta demasiado grande");bytes.write(buffer,0,n);}}
            return new Response(code,bytes.toString(StandardCharsets.UTF_8.name()),location);
        }finally{c.disconnect();active=null;}
    }
    static Body bytes(byte[] bytes){return new Body(){public long length(){return bytes.length;}public void write(OutputStream out)throws Exception{out.write(bytes);}};}
    static Body json(JSONObject json){return bytes(json.toString().getBytes(StandardCharsets.UTF_8));}
    Body file(File file){return new Body(){public long length(){return file.length();}public void write(OutputStream out)throws Exception{copy(file,out);}};}
    void copy(File file,OutputStream out)throws Exception{try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);}}}
    static void require(Response response,String service)throws Exception{
        if(response.code>=200 && response.code<300)return;
        if(response.code==401)throw new UserAction(service.equals("OpenAI")?"La clave de OpenAI no es válida o fue revocada. Revísala en Configuración.":"Vuelve a vincular tu cuenta de Google Drive.");
        if(response.code==403)throw new UserAction("Sin permiso en "+service+". Revisa la cuenta y la configuración del servicio.");
        if(response.code==429 && response.text.contains("insufficient_quota"))throw new UserAction("OpenAI no tiene saldo o cuota disponible. Revisa la facturación de tu API.");
        if(response.code==408 || response.code==429 || response.code>=500)throw new IOException(service+" no está disponible temporalmente ("+response.code+").");
        throw new UserAction(service+" rechazó la solicitud ("+response.code+"). Revisa la configuración y vuelve a intentarlo.");
    }
}
