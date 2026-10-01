package cl.vozlocal.app;

import android.content.Context;

/**
 * Métricas de uso (0.8.0, pedido 2026-09-30): todo se calcula en el teléfono con lo que la app ya guarda (estado de
 * cada grabación, transcripciones, notas, 0-Inbox, momentos ★). No envía nada.
 * FASE 0 (contrato): lo implementa la parte «metrics». Ver docs/diseno/SPEC-0.8b.md.
 */
final class Metrics {
    /** Resumen de una línea para la tarjeta de Ajustes, p. ej. «12 h grabadas · US$3,40 este mes». Lee disco: llamar fuera del hilo principal. */
    static String summaryLine(Context c){return "";}
    private Metrics(){}
}
