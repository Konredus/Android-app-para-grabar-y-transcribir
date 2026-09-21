package cl.vozlocal.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

final class FilesStore {
    static final Object LOCK = new Object();
    static final AtomicInteger version = new AtomicInteger();
    static File file(Context c, String id, String suffix) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Grabación inválida");
        return new File(Recording.directory(c), id + suffix);
    }
    static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }
    static void write(File file, JSONObject json) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
        try { out=atomic.startWrite(); out.write(json.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(out); }
        catch(Exception e) { if(out != null) atomic.failWrite(out); throw e; }
        version.incrementAndGet();
    }
    static JSONObject state(Context c, String id) {
        synchronized(LOCK) { try { return read(file(c,id,".sync.json")); } catch(Exception e) { return new JSONObject(); } }
    }
    interface Change { void apply(JSONObject data) throws Exception; }
    static void update(Context c, String id, Change change) throws Exception {
        synchronized(LOCK) {
            if (!file(c,id,".m4a").exists()) return;
            JSONObject state = state(c,id); change.apply(state); write(file(c,id,".sync.json"),state);
        }
    }
    static void status(Context c, String id, String label) throws Exception { update(c,id,s -> s.put("status",label)); }
    static String label(Context c, String id) {
        JSONObject state=state(c,id);
        if (state.optBoolean("demo")) return "Ejemplo · texto de demostración";
        return state.optString("status", Transcript.exists(c,id) ? "Transcripción disponible" : "Solo en este teléfono");
    }
    static Recording recording(Context c, String id) { for(Recording r:Recording.list(c)) if(r.id.equals(id))return r; return null; }
}
