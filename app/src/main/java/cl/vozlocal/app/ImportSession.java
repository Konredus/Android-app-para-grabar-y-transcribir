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
    void cancel(){cancelled=true;cancel.cancel();Closeable stream=openStream;if(stream!=null)try{stream.close();}catch(Exception ignored){}}
    void clean(){source.delete();encoded.delete();stateFile(app).delete();}
}
