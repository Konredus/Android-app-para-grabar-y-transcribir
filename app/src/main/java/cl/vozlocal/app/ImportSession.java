package cl.vozlocal.app;

import android.content.Context;
import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

/** Only one import is active. Its prepared source survives Activity recreation and process death. */
final class ImportSession {
    final String id;final Context app;final File source,encoded;final HttpApi cancel=new HttpApi();
    /** name: el título que tendrá la grabación. error: el aviso que ve la persona (vacío si no hay), ya en su idioma. */
    volatile boolean busy,ready,done,cancelled;volatile String name,error="";
    /** Etapa visible: un texto de strings_import (R.string.imp_stage_*), que se traduce al mostrarlo, en el idioma vigente. */
    volatile int stage=R.string.imp_stage_reading;
    volatile long duration,position,total,from,to;volatile boolean bytesProgress;volatile Closeable openStream;
    ImportSession(Context c){this(c,UUID.randomUUID().toString());}
    private ImportSession(Context c,String id){app=c.getApplicationContext();this.id=id;name=Lang.str(app,R.string.imp_default_name);File dir=new File(app.getFilesDir(),"imports");dir.mkdirs();source=new File(dir,id+".source");encoded=new File(dir,id+".m4a");}
    static File stateFile(Context c){return new File(new File(c.getFilesDir(),"imports"),"active.json");}
    synchronized void persist(){try{FilesStore.write(stateFile(app),new JSONObject().put("id",id).put("name",name).put("duration",duration).put("ready",ready).put("busy",busy).put("done",done).put("from",from).put("to",to));}catch(Exception e){Diagnostics.event("import_state_failed",id,"error_class",e.getClass().getSimpleName());}}
    static ImportSession restore(Context c){try{JSONObject state=FilesStore.read(stateFile(c));String id=state.getString("id");if(!id.matches("[a-f0-9-]{36}"))return null;ImportSession s=new ImportSession(c,id);s.name=state.optString("name",s.name);s.duration=state.optLong("duration");s.from=state.optLong("from");s.to=state.optLong("to",s.duration);s.done=state.optBoolean("done");s.ready=state.optBoolean("ready")&&s.source.exists();s.encoded.delete();
        if(FilesStore.file(c,id,".m4a").exists())s.done=true;
        if(s.done){s.source.delete();return s;}if(s.ready){s.stage=R.string.imp_stage_resume;if(state.optBoolean("busy"))s.error=Lang.str(c,R.string.imp_err_interrupted);return s;}
        s.source.delete();stateFile(c).delete();return null;
    }catch(Exception ignored){return null;}}
    /**
     * Título legible a partir del nombre del archivo: sin la extensión («.m4a», «.opus»…) y, para las notas de voz de
     * WhatsApp («PTT-20260929-WA0003»), «Audio de WhatsApp 29 sept» (en el idioma de la app, con su nombre del mes).
     */
    static String titleFrom(String file){
        String t=file==null?"":file.trim().replaceFirst("\\.[A-Za-z0-9]{1,5}$","").trim();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(?:PTT|AUD)-(\\d{8})-WA\\d+$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if(m.matches()){
            try{java.text.SimpleDateFormat in=new java.text.SimpleDateFormat("yyyyMMdd",java.util.Locale.ROOT);in.setLenient(false);java.util.Date at=in.parse(m.group(1));java.util.Locale l=Lang.locale();
                t=Lang.str(R.string.imp_whatsapp_title,new java.text.SimpleDateFormat("d",l).format(at),new java.text.SimpleDateFormat("MMM",l).format(at));}
            catch(Exception e){t=Lang.str(R.string.imp_whatsapp_audio);}
        }
        if(t.isEmpty())t=Lang.str(R.string.imp_default_name);
        return t.length()>120?t.substring(0,120).trim():t;
    }
    /** Un audio preparado que quedó sin guardar hace más de 30 min: al volver a Importar se ofrece elegir otro (el original sigue intacto). */
    static final long STALE_MS=30*60_000L;
    boolean stale(){return ready&&!busy&&!done&&error.isEmpty()&&System.currentTimeMillis()-source.lastModified()>STALE_MS;}
    /** Se acaba de mirar el formulario: los 30 min se cuentan desde ahora (no desde que terminó la copia). */
    void touch(){if(ready&&!busy&&!done&&source.exists())source.setLastModified(System.currentTimeMillis());}
    void cancel(){cancelled=true;cancel.cancel();Closeable stream=openStream;if(stream!=null)try{stream.close();}catch(Exception ignored){}}
    void clean(){source.delete();encoded.delete();stateFile(app).delete();}
}
