package cl.vozlocal.app;

import android.content.Context;

/** Pruebas de la parte de la 0.6.0 (ver docs/diseno/SPEC-0.6.md). Sin llamadas reales a APIs. */
final class MediaChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void run(Context c,Recording r)throws Exception{}
}
