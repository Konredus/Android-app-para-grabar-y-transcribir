package cl.vozlocal.app;

import android.content.Context;

/** Envolvente del audio para la onda del reproductor (n valores 0..1). STUB de la fase 0 (ver SPEC-0.6.md); la parte «media» lo implementa. */
final class WaveData {
    private WaveData(){}
    static final int N=600;
    /** Rápido, sin decodificar: la envolvente guardada o null. */
    static float[] cached(Context c,Recording r){return null;}
    /** BLOQUEA (hilo de fondo): decodifica, guarda y devuelve la envolvente; null si no se pudo. */
    static float[] compute(Context c,Recording r){return null;}
}
