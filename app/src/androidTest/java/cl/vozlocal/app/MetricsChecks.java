package cl.vozlocal.app;

import android.content.Context;

/** Pruebas de la parte «metrics» de la 0.8.0 (ver docs/diseno/SPEC-0.8b.md). Sin red. */
final class MetricsChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void run(Context c,Recording r)throws Exception{
        // FASE 0: la parte dueña agrega aquí sus comprobaciones.
    }
}
