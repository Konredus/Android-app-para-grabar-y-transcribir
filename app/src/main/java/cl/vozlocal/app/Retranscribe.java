package cl.vozlocal.app;

import android.content.Context;

/**
 * Volver a transcribir con una alternativa que cambie algo (no repetir lo mismo). La versión anterior se conserva.
 * STUB de la fase 0 (ver docs/diseno/SPEC-0.6.md); la parte «engine» lo implementa.
 */
final class Retranscribe {
    private Retranscribe(){}
    enum Mode{CORRECTIONS,SINGLE,SPEAKERS,TEXT}
    static boolean available(Context c,Recording r,Mode mode){return false;}
    static String reason(Context c,Recording r,Mode mode){return "Aún no disponible";}
    static void start(Context c,Recording r,Mode mode)throws Exception{throw new UnsupportedOperationException("Aún no disponible");}
    static boolean hasPrevious(Context c,String id){return FilesStore.file(c,id,".transcript.prev.json").isFile();}
    static void keepNew(Context c,String id){}
    static void restorePrevious(Context c,String id)throws Exception{}
}
