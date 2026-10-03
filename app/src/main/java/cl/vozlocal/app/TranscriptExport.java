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
        return new Uri.Builder().scheme("content").authority(AudioProvider.authority(context))
            .appendPath(file.getName()).appendQueryParameter(DISPLAY_NAME, name).build();
    }

    static Intent shareIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(Lang.str(R.string.export_transcript), uri));
        return intent;
    }

    static String filename(String title) { return base(title, ".txt") + ".txt"; }

    /** Markdown con la misma limpieza que el .txt: «Reunión.md» (sin duplicar la extensión). */
    static String markdownFilename(String title) { return base(title, ".md") + ".md"; }

    /** Nota para el segundo cerebro: siempre con la fecha delante, «2026-09-29 Título.md» (sin duplicarla si ya la tiene). */
    static String noteFilename(String title, long created) { return markdownFilename(Recording.withDate(title, created)); }

    /** Nombre seguro sin extensión: sin rutas, controles ni nombres reservados, y con espacio para la extensión. */
    private static String base(String title, String extension) {
        String normalized = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFC);
        StringBuilder safe = new StringBuilder();
        normalized.codePoints().forEach(point -> {
            int type = Character.getType(point);
            if (Character.isISOControl(point) || type == Character.FORMAT || type == Character.SURROGATE) return;
            if ("/\\:*?\"<>|".indexOf(point) >= 0) safe.append(' ');
            else safe.appendCodePoint(point);
        });
        String value = safe.toString().replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (value.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) value = value.substring(0, value.length() - extension.length());
        value = value.replaceAll("^[. ]+|[. ]+$", "");
        // «Transcripción» va en el idioma de la app (export_transcript), también como nombre de respaldo.
        if (value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) value = Lang.str(R.string.export_transcript) + " " + value;
        // Leave room for the extension on providers that enforce a byte-based filename limit.
        while (value.getBytes(StandardCharsets.UTF_8).length > 180) {
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        }
        value = value.replaceAll("[. ]+$", "");
        if (value.isEmpty()) value = Lang.str(R.string.export_transcript);
        return value;
    }
}
