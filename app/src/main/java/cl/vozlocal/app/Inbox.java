package cl.vozlocal.app;

import android.content.Context;
import android.net.Uri;

/**
 * Guardado en la carpeta rápida (p. ej. Drive/0-Inbox): la nota .md (o el .txt) con un toque, y «Actualizar»
 * reemplaza el mismo archivo. STUB de la fase 0 (ver docs/diseno/SPEC-0.6.md); la parte «notes» lo implementa.
 */
final class Inbox {
    private Inbox(){}
    static boolean configured(Context c){return !new Settings(c).inboxTree().isEmpty();}
    static String folderName(Context c){return new Settings(c).inboxName();}
    static Uri save(Context c,Recording r)throws Exception{throw new UnsupportedOperationException("Guardado rápido aún no disponible");}
    static long savedAt(Context c,String id){return FilesStore.state(c,id).optLong("inboxAt",0);}
    static boolean outdated(Context c,String id){return false;}
}
