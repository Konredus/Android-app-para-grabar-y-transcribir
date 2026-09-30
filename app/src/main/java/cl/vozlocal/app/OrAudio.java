package cl.vozlocal.app;

import java.io.File;
import java.util.List;

/**
 * Audio para OpenRouter (0.8.0): convierte un bloque (AAC/.m4a u otro formato que Android decodifique) a mono de
 * 16 kHz y 16 bits, en FLAC (o WAV si el teléfono no tiene codificador FLAC), que aceptan todos los modelos que
 * separan voces. Opcionalmente antepone «anclas»: clips de voces conocidas separados por 1 s de silencio, para que el
 * modelo las separe como hablantes y la app sepa quién es quién por el momento en que suena cada una.
 * FASE 0 (contrato): lo implementa la parte «audio». Ver docs/diseno/SPEC-0.8.md.
 */
final class OrAudio {
    /** Frecuencia de salida (Hz), mono, 16 bits. */
    static final int RATE=16000;
    /** Silencio entre anclas y antes del audio (ms). */
    static final long GAP_MS=1000;

    /** Resultado de build(). */
    static final class Built{
        /** Archivo listo para enviar. */
        final File file;
        /** "flac" o "wav": el valor de input_audio.format. */
        final String format;
        /** Milisegundos que ocupan las anclas y sus silencios al comienzo: se restan de los tiempos de la respuesta. 0 sin anclas. */
        final long leadMs;
        /** Por cada ancla, en el mismo orden en que se pidieron: {inicio ms, fin ms} dentro del archivo. */
        final long[][] anchors;
        /** Duración total del archivo (ms), con anclas. */
        final long durationMs;
        Built(File file,String format,long leadMs,long[][] anchors,long durationMs){this.file=file;this.format=format;this.leadMs=leadMs;this.anchors=anchors;this.durationMs=durationMs;}
    }

    /**
     * Arma el archivo para enviar.
     * @param audio   el bloque a transcribir.
     * @param anchors clips de voz a anteponer, en orden (puede ser null o vacío). Un clip que no se pueda leer se omite
     *                y su entrada en Built.anchors queda {-1,-1}.
     * @param outBase ruta de salida SIN extensión (se agrega ".flac" o ".wav"); se escribe a ".tmp" y se renombra.
     * @param cancel  para cancelar (cancel.check()) durante la conversión; puede ser null.
     */
    static Built build(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception{
        throw new UnsupportedOperationException("Conversión pendiente (fase audio).");
    }
    private OrAudio(){}
}
