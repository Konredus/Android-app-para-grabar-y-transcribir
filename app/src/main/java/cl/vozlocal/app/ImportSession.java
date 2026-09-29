package cl.vozlocal.app;

import android.content.Context;
import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

/** Only one import is active. Its prepared source survives Activity recreation and process death. */
final class ImportSession {
    final String id;final Context app;final File source,encoded;final HttpApi cancel=new HttpApi();
    volatile boolean busy,ready,done,cancelled;volatile String name="Audio importado",stage="Leyendo archivo",error="";
    volatile long duration,position,total,from,to;volatile boolean bytesProgress;volatile Closeable openStream;
    ImportSession(Context c){this(c,UUID.randomUUID().toString());}
    private ImportSession(Context c,String id){app=c.getApplicationContext();this.id=id;File dir=new File(app.getFilesDir(),"imports");dir.mkdirs();source=new File(dir,id+".source");encoded=new File(dir,id+".m4a");}
    static File stateFile(Context c){return new File(new File(c.getFilesDir(),"imports"),"active.json");}
    synchronized void persist(){try{FilesStore.write(stateFile(app),new JSONObject().put("id",id).put("name",name).put("duration",duration).put("ready",ready).put("busy",busy).put("done",done).put("from",from).put("to",to));}catch(Exception e){Diagnostics.event("import_state_failed",id,"error_class",e.getClass().getSimpleName());}}
    static ImportSession restore(Context c){try{JSONObject state=FilesStore.read(stateFile(c));String id=state.getString("id");if(!id.matches("[a-f0-9-]{36}"))return null;ImportSession s=new ImportSession(c,id);s.name=state.optString("name","Audio importado");s.duration=state.optLong("duration");s.from=state.optLong("from");s.to=state.optLong("to",s.duration);s.done=state.optBoolean("done");s.ready=state.optBoolean("ready")&&s.source.exists();s.encoded.delete();
        if(FilesStore.file(c,id,".m4a").exists())s.done=true;
        if(s.done){s.source.delete();return s;}if(s.ready){s.stage="Audio listo para continuar";if(state.optBoolean("busy"))s.error="La preparación se interrumpió. Tu copia está a salvo; puedes volver a guardarla.";return s;}
        s.source.delete();stateFile(c).delete();return null;
    }catch(Exception ignored){return null;}}
    /**
     * Título legible a partir del nombre del archivo: sin la extensión («.m4a», «.opus»…) y, para las notas de voz de
     * WhatsApp («PTT-20260929-WA0003»), «Audio de WhatsApp 29 sept».
     */
    static String titleFrom(String file){
        String t=file==null?"":file.trim().replaceFirst("\\.[A-Za-z0-9]{1,5}$","").trim();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(?:PTT|AUD)-(\\d{8})-WA\\d+$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if(m.matches()){
            try{java.text.SimpleDateFormat in=new java.text.SimpleDateFormat("yyyyMMdd",java.util.Locale.ROOT);in.setLenient(false);
                t="Audio de WhatsApp "+new java.text.SimpleDateFormat("d MMM",new java.util.Locale("es","CL")).format(in.parse(m.group(1)));}
            catch(Exception e){t="Audio de WhatsApp";}
        }
        if(t.isEmpty())t="Audio importado";
        return t.length()>120?t.substring(0,120).trim():t;
    }
    /** Un audio preparado que quedó sin guardar hace más de 30 min: al volver a Importar se ofrece elegir otro (el original sigue intacto). */
    static final long STALE_MS=30*60_000L;
    boolean stale(){return ready&&!busy&&!done&&error.isEmpty()&&System.currentTimeMillis()-source.lastModified()>STALE_MS;}
    void cancel(){cancelled=true;cancel.cancel();Closeable stream=openStream;if(stream!=null)try{stream.close();}catch(Exception ignored){}}
    void clean(){source.delete();encoded.delete();stateFile(app).delete();}
}
