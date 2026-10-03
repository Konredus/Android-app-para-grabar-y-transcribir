package cl.vozlocal.app;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class AudioProvider extends ContentProvider {
    /**
     * Autoridad del proveedor: el paquete de la app + ".audio" (0.9.0: el paquete pasó de cl.vozlocal.app a cl.verbapp.app;
     * el manifiesto usa ${applicationId}.audio, así la versión de Play y la anterior pueden convivir en el teléfono).
     */
    static String authority(Context c) { return c.getPackageName() + ".audio"; }
    /** content://<paquete>.audio/<nombre> para compartir un audio, una transcripción o un informe. */
    static Uri uri(Context c, String name) { return Uri.parse("content://" + authority(c) + "/" + name); }
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !authority(getContext()).equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        if (name == null || !(name.matches("[a-f0-9-]{36}\\.(m4a|txt)") || Diagnostics.isReport(name))) throw new FileNotFoundException();
        File f = new File(name.endsWith(".txt")?getContext().getCacheDir():Recording.directory(getContext()), name);
        if (!f.isFile() || name.equals(RecorderService.activeId + ".m4a")) throw new FileNotFoundException();
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Solo lectura");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return uri.getLastPathSegment()!=null&&uri.getLastPathSegment().endsWith(".txt")?"text/plain":"audio/mp4"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri); String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(cols); Object[] row = new Object[cols.length];
            String displayName = f.getName();
            String requested = uri.getQueryParameter(TranscriptExport.DISPLAY_NAME);
            if (requested != null && displayName.endsWith(".txt") && !Diagnostics.isReport(displayName)) displayName = TranscriptExport.filename(requested);
            for (int i=0; i<cols.length; i++) { if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = displayName; else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length(); }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
