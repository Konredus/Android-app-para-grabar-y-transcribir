package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;

/** Share an immutable snapshot; user titles are metadata, never filesystem paths. */
final class TranscriptExport {
    static final String DISPLAY_NAME = "displayName";

    static Uri create(Context context, Recording recording, Transcript transcript) throws Exception {
        Recording latest = FilesStore.recording(context, recording.id);
        if (latest != null) recording = latest;
        String name = filename(recording.title);
        File file = new File(context.getCacheDir(), UUID.randomUUID() + ".txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(transcript.text(recording).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            file.delete();
            throw error;
        }
        return new Uri.Builder().scheme("content").authority("cl.vozlocal.app.audio")
            .appendPath(file.getName()).appendQueryParameter(DISPLAY_NAME, name).build();
    }

    static Intent shareIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Transcripción", uri));
        return intent;
    }

    static String filename(String title) {
        String normalized = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFC);
        StringBuilder safe = new StringBuilder();
        normalized.codePoints().forEach(point -> {
            int type = Character.getType(point);
            if (Character.isISOControl(point) || type == Character.FORMAT || type == Character.SURROGATE) return;
            if ("/\\:*?\"<>|".indexOf(point) >= 0) safe.append(' ');
            else safe.appendCodePoint(point);
        });
        String value = safe.toString().replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (value.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) value = value.substring(0, value.length() - 4);
        value = value.replaceAll("^[. ]+|[. ]+$", "");
        if (value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) value = "Transcripción " + value;
        // Leave room for .txt on providers that enforce a byte-based filename limit.
        while (value.getBytes(StandardCharsets.UTF_8).length > 180) {
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        }
        value = value.replaceAll("[. ]+$", "");
        if (value.isEmpty()) value = "Transcripción";
        return value + ".txt";
    }
}
