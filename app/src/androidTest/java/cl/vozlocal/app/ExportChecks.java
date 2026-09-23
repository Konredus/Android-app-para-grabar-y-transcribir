package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Exercises receiver-facing names and content through the actual ContentProvider. */
final class ExportChecks {
    static void run(Context context, Recording source) throws Exception {
        Recording fixture = new Recording(UUID.randomUUID().toString(), "Reunión con José", System.currentTimeMillis(), source.duration);
        ArrayList<Uri> exports = new ArrayList<>();
        try {
            java.nio.file.Files.copy(source.audio(context).toPath(), fixture.audio(context).toPath());
            fixture.save(context);
            Transcript transcript = new Transcript(new JSONObject("{\"diarized\":true,\"names\":{\"A\":\"José\"},\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":2,\"text\":\"Mañana revisamos el proyecto.\"}]}"));
            transcript.save(context, fixture.id);
            String dataBefore = transcript.data.toString();
            Uri first = TranscriptExport.create(context, fixture, transcript); exports.add(first);
            check("Reunión con José.txt".equals(name(context, first)), "TXT filename lost accents/title");
            String firstText = read(context, first);
            check(firstText.equals(transcript.text(fixture)), "Exported TXT changed transcript content");
            check(firstText.contains("José: Mañana"), "Export lost speaker names");
            check(size(context, first) == firstText.getBytes(StandardCharsets.UTF_8).length, "Exported file size mismatch");

            Recording renamed = FilesStore.recording(context, fixture.id);
            renamed.title = "Ideas después de la reunión"; renamed.save(context);
            Uri second = TranscriptExport.create(context, fixture, transcript); exports.add(second);
            check("Ideas después de la reunión.txt".equals(name(context, second)), "Renaming left stale export filename");
            check(read(context, second).startsWith(renamed.title + "\n"), "Renaming left stale TXT heading");
            check(!first.equals(second) && firstText.equals(read(context, first)), "Export snapshot was overwritten");
            Uri sameName = TranscriptExport.create(context, renamed, transcript); exports.add(sameName);
            check(!sameName.equals(second) && name(context, sameName).equals(name(context, second)), "Identical titles collided");
            check(dataBefore.equals(transcript.data.toString()), "Sharing modified the transcript");
            check(dataBefore.equals(Transcript.load(context, fixture.id).data.toString()), "Sharing modified saved names or segments");
            Transcript plain = new Transcript(new JSONObject("{\"diarized\":false,\"parts\":2,\"segments\":[{\"speaker\":\"text\",\"start\":0,\"end\":0,\"text\":\"Texto sin voces.\"}]}"));
            check(!plain.text(renamed).contains("etiquetas de personas") && plain.text(renamed).contains("inicio de cada bloque"), "Plain model exported speaker instructions");

            renamed.title = "../../Informe\\privado:\r\n\u202E.txt"; renamed.save(context);
            Uri unsafe = TranscriptExport.create(context, renamed, transcript); exports.add(unsafe);
            String display = name(context, unsafe);
            check(display.endsWith(".txt") && !display.contains("/") && !display.contains("\\") && !display.contains(":") && !display.contains("\n") && !display.contains("\u202E"), "Unsafe title leaked into display filename");
            check(!display.startsWith("."), "Hidden/path-like export filename");
            check(read(context, unsafe).startsWith(renamed.title + "\n"), "Sanitizing filename changed transcript text");
            Uri maliciousMetadata = first.buildUpon().clearQuery().appendQueryParameter(TranscriptExport.DISPLAY_NAME, "../evil\\name\u0000.txt").build();
            check(!name(context, maliciousMetadata).contains("/") && !name(context, maliciousMetadata).contains("\\"), "Provider trusted unsafe display-name metadata");
            check(firstText.equals(read(context, maliciousMetadata)), "Display name changed resolved filesystem path");

            for (String mode : new String[]{"w", "rw", "rwt", "wa"}) {
                boolean rejected = false;
                try (android.os.ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(first, mode)) { }
                catch (FileNotFoundException expected) { rejected = true; }
                check(rejected, "Export provider accepted write mode " + mode);
            }
            Uri traversal = Uri.parse("content://cl.vozlocal.app.audio/../" + first.getLastPathSegment());
            boolean rejected = false;
            try (InputStream ignored = context.getContentResolver().openInputStream(traversal)) { }
            catch (FileNotFoundException expected) { rejected = true; }
            check(rejected, "Provider accepted traversal path");
            check("Transcripción.txt".equals(TranscriptExport.filename("... /\\")), "Empty title fallback missing");
            check("Transcripción CON.txt".equals(TranscriptExport.filename("CON")), "Reserved filename not handled");
            check("Reunión.txt".equals(TranscriptExport.filename("Reunión.txt")), "Double TXT extension");
            String large = new String(new char[200]).replace("\0", "é");
            check(TranscriptExport.filename(large).getBytes(StandardCharsets.UTF_8).length <= 184, "Export name exceeds byte limit");
            Intent send = TranscriptExport.shareIntent(first);
            check("text/plain".equals(send.getType()) && (send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "Share intent lacks MIME type/read grant");
            check(first.equals(send.getClipData().getItemAt(0).getUri()), "Share intent lacks ClipData URI grant");
        } finally {
            for (Uri uri : exports) new File(context.getCacheDir(), uri.getLastPathSegment()).delete();
            File[] files = Recording.directory(context).listFiles((dir, name) -> name.startsWith(fixture.id + "."));
            if (files != null) for (File file : files) file.delete();
        }
    }
    private static String name(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            check(cursor != null && cursor.moveToFirst(), "Provider returned no name"); return cursor.getString(0);
        }
    }
    private static long size(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            check(cursor != null && cursor.moveToFirst(), "Provider returned no size"); return cursor.getLong(0);
        }
    }
    private static String read(Context context, Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("No export stream");
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
