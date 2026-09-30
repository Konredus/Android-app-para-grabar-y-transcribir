package cl.vozlocal.app;

import android.content.Context;
import java.io.File;
import java.util.List;
import org.json.JSONObject;

/**
 * Cliente de transcripción de OpenRouter (0.8.0): POST BASE+"/audio/transcriptions" con JSON y el audio en base64.
 * FASE 0 (contrato): lo implementa la parte «engine». Ver docs/diseno/SPEC-0.8.md.
 */
final class OpenRouterClient implements TranscribeClient {
    final Context c;final HttpApi http;
    OpenRouterClient(Context c,HttpApi http){this.c=c.getApplicationContext();this.http=http;}
    @Override public JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,OpenAiClient.Delta delta)throws Exception{
        throw new UnsupportedOperationException("OpenRouter pendiente (fase engine).");
    }
}
