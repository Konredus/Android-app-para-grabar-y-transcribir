package cl.vozlocal.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class Recording {
    final String id;
    String title;
    long created, duration;
    Recording(String id, String title, long created, long duration) {
        this.id = id; this.title = title; this.created = created; this.duration = duration;
    }
    static File directory(Context c) { File d = new File(c.getFilesDir(), "recordings"); d.mkdirs(); return d; }
    File audio(Context c) { return new File(directory(c), id + ".m4a"); }
    /** «Grabación 02 oct · 16:05», en el idioma de la app (la fecha con su formato). */
    static String defaultTitle(long time) { return Lang.str(R.string.rec_default_title, new SimpleDateFormat("dd MMM · HH:mm", Lang.locale()).format(new Date(time))); }
    /** «Audio recuperado · revisar»: el audio está, pero se perdió su archivo de datos. */
    static String recoveredTitle() { return Lang.str(R.string.rec_recovered); }
    /**
     * ¿Sigue siendo un título automático? («Grabación 29 sept. · 16:05», con o sin la fecha ISO delante, o «Audio recuperado ·
     * revisar»), en cualquiera de los tres idiomas: el título se guarda en el idioma de cuando se grabó.
     */
    static boolean automaticTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || Lang.isAny(R.string.rec_recovered, t)) return true;
        return t.matches("^(\\d{4}-\\d{2}-\\d{2} )?" + Lang.anyRegex(R.string.rec_default_title) + " (\\d{1,2} \\S{2,6} · )?\\d{1,2}:\\d{2}$");
    }
    /** Fecha ISO de la grabación (año-mes-día): ordena solos los archivos en cualquier carpeta. */
    static String isoDate(long time) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(time)); }
    /** "2026-09-27 Nombre". No duplica si el nombre ya empieza con una fecha; el título por defecto queda "2026-09-27 Grabación 16:00". */
    static String withDate(String title, long created) {
        String t = title == null ? "" : title.trim();
        if (t.matches("^\\d{4}-\\d{2}-\\d{2}( .*)?$")) return t;
        if (t.isEmpty() || t.equals(defaultTitle(created))) return isoDate(created) + " " + Lang.str(R.string.rec_default_title, new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(created)));
        return isoDate(created) + " " + t;
    }
    void save(Context c) throws Exception {
        if (new Settings(c).datePrefix()) title = withDate(title, created);
        JSONObject j = new JSONObject().put("id", id).put("title", title).put("created", created).put("duration", duration);
        android.util.AtomicFile f = new android.util.AtomicFile(new File(directory(c), id + ".json"));
        FileOutputStream out = null;
        try { out = f.startWrite(); out.write(j.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out); }
        catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
        FilesStore.version.incrementAndGet();
    }
    /** Grabaciones eliminadas que esperan el «Deshacer» (RecordingActions.trash): no se muestran ni se procesan. */
    static final Set<String> HIDDEN = Collections.synchronizedSet(new HashSet<>());
    /*
     * 0.9.6: la lista se guarda en memoria y se vuelve a leer solo si algo cambió: FilesStore.version (cada guardado o
     * borrado), la fecha de la carpeta (un archivo nuevo, renombrado o borrado la cambia) o lo oculto. Antes cada pantalla
     * leía un JSON por grabación varias veces por segundo en el hilo de la pantalla. Siempre se entregan copias: quien
     * cambia un título no toca la lista guardada.
     */
    private static final Object CACHE_LOCK = new Object();
    private static String cacheKey; private static ArrayList<Recording> cache;
    static ArrayList<Recording> list(Context c) {
        File dir = directory(c);
        String key = FilesStore.version.get() + ":" + dir.lastModified() + ":" + HIDDEN.size() + ":" + RecorderService.activeId;
        synchronized (CACHE_LOCK) { if (key.equals(cacheKey) && cache != null) return copy(cache); }
        ArrayList<Recording> all = read(c);
        synchronized (CACHE_LOCK) { cacheKey = key; cache = all; }
        return copy(all);
    }
    private static ArrayList<Recording> copy(ArrayList<Recording> from) { ArrayList<Recording> out = new ArrayList<>(from.size()); for (Recording r : from) out.add(new Recording(r.id, r.title, r.created, r.duration)); return out; }
    private static ArrayList<Recording> read(Context c) {
        ArrayList<Recording> all = new ArrayList<>();
        File[] files = directory(c).listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return all;
        for (File file : files) {
            String id = file.getName().replace(".m4a", "");
            if (id.equals(RecorderService.activeId) || HIDDEN.contains(id)) continue;
            Recording r = new Recording(id, defaultTitle(file.lastModified()), file.lastModified(), 0);
            try {
                JSONObject j = FilesStore.read(new File(directory(c), id + ".json"));
                r.title = j.getString("title"); r.created = j.getLong("created"); r.duration = j.getLong("duration");
            } catch (Exception ignored) {
                // Keep audio visible even if metadata was lost during interruption.
                try { r.duration = AudioConvert.duration(file); } catch (Exception unavailable) { r.title = recoveredTitle(); }
            }
            all.add(r);
        }
        all.sort((a,b) -> Long.compare(b.created, a.created)); return all;
    }
    /** Archivos propios de cada grabación (además del audio). Los que no están en la lista igual se borran por prefijo. */
    static final String[] FILES = {".json", ".sync.json", ".transcript.json", ".transcript.prev.json", ".note.json", ".note.prev.json", ".wave.json"};
    boolean delete(Context c) {
        // Al eliminar no se restaura la versión anterior de «Volver a transcribir»: se borra todo.
        try { Pipeline.cancel(c,id,false); } catch(Exception ignored) { }
        AudioParts.clearBlocks(c,id);Transcriber.clearDone(c,id);
        synchronized(FilesStore.LOCK) {
            if (!audio(c).delete()) return false;
            // AtomicFile.delete también quita sus respaldos (.bak/.new).
            for (String suffix : FILES) new android.util.AtomicFile(new File(directory(c), id + suffix)).delete();
            File[] files=directory(c).listFiles((dir,name)->name.startsWith(id+"."));
            if(files!=null)for(File file:files)file.delete();
            FilesStore.version.incrementAndGet();return true;
        }
    }
    static String time(long milliseconds) {
        long s = Math.max(0, milliseconds / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s/3600, (s/60)%60, s%60) : String.format(Locale.ROOT, "%02d:%02d", s/60, s%60);
    }
}
