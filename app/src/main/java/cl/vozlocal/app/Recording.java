package cl.vozlocal.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
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
    static String defaultTitle(long time) { return "Grabación " + new SimpleDateFormat("dd MMM · HH:mm", new Locale("es", "CL")).format(new Date(time)); }
    void save(Context c) throws Exception {
        JSONObject j = new JSONObject().put("id", id).put("title", title).put("created", created).put("duration", duration);
        android.util.AtomicFile f = new android.util.AtomicFile(new File(directory(c), id + ".json"));
        FileOutputStream out = null;
        try { out = f.startWrite(); out.write(j.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out); }
        catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
        FilesStore.version.incrementAndGet();
    }
    static ArrayList<Recording> list(Context c) {
        ArrayList<Recording> all = new ArrayList<>();
        File[] files = directory(c).listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return all;
        for (File file : files) {
            String id = file.getName().replace(".m4a", "");
            if (id.equals(RecorderService.activeId)) continue;
            Recording r = new Recording(id, defaultTitle(file.lastModified()), file.lastModified(), 0);
            try {
                JSONObject j = FilesStore.read(new File(directory(c), id + ".json"));
                r.title = j.getString("title"); r.created = j.getLong("created"); r.duration = j.getLong("duration");
            } catch (Exception ignored) {
                // Keep audio visible even if metadata was lost during interruption.
                try (MediaMetadataRetriever m = new MediaMetadataRetriever()) {
                    m.setDataSource(file.getPath()); r.duration = Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                } catch (Exception unavailable) { r.title = "Audio recuperado · revisar"; }
            }
            all.add(r);
        }
        all.sort((a,b) -> Long.compare(b.created, a.created)); return all;
    }
    boolean delete(Context c) {
        try { Pipeline.cancel(c,id); } catch(Exception ignored) { }
        synchronized(FilesStore.LOCK) {
            if (!audio(c).delete()) return false;
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
