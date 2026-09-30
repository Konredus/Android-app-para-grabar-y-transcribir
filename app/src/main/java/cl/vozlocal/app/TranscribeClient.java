package cl.vozlocal.app;

import android.content.Context;
import java.io.File;
import java.util.List;
import org.json.JSONObject;

/**
 * Cliente de transcripción (0.8.0): el motor (Transcriber) habla con esta interfaz y no sabe de proveedores.
 *
 * Contrato de salida, igual para todos (es lo que Transcript.fromParts ya consume):
 *   {"segments":[{"speaker":String,"start":segundos,"end":segundos,"text":String},…],"usage":{…},"_diarized":boolean}
 * - Con voces, "speaker" es una etiqueta estable dentro del bloque. Si se enviaron muestras (references) y el
 *   proveedor reconoció a esa persona, "speaker" es el NOMBRE ENVIADO de la muestra (references[i][0]); así el motor
 *   la une con su id global mediante _known, igual que con OpenAI.
 * - Sin voces, "speaker" es "text" y "_diarized" es false.
 * - "usage" opcional: {"type":"duration","seconds":N} o tokens; OpenRouter agrega "cost" (US$ reales del envío).
 */
interface TranscribeClient {
    /** references: {nombre enviado, data URL del clip (audio/mp4), id global, descripción}; puede ser null. */
    JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,OpenAiClient.Delta delta)throws Exception;

    /** El cliente que corresponde al proveedor de la configuración. */
    static TranscribeClient of(Context c,HttpApi http,ProviderConfig config){
        return "openrouter".equals(config.provider)?new OpenRouterClient(c,http):new OpenAiClient(http);
    }
}
