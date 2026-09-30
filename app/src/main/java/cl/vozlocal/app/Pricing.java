package cl.vozlocal.app;

import android.content.Context;
import java.util.Locale;

/**
 * Tarifas públicas por minuto de audio (USD) para estimar costos en pantalla.
 * Son ESTIMADOS: el cobro real lo define el proveedor (revisa platform.openai.com/usage).
 * Actualizar esta tabla cuando cambien los precios. Revisado: 2026-09-23.
 * OpenRouter (0.8.0) no usa esta tabla: su precio sale del catálogo que se actualiza solo (Models.perMinute) y, al
 * terminar cada parte, del costo real que informa la respuesta (clave de estado "costUsd", ver real()).
 */
final class Pricing {
    static final String REVIEWED="23-09-2026";
    /** USD por minuto de audio, o -1 si no se conoce (p. ej. servidor personalizado). */
    static double perMinute(String model){
        if(model==null)return -1;
        switch(model){
            case "gpt-transcribe":return 0.0045;
            case "gpt-4o-mini-transcribe":return 0.003;
            case "gpt-4o-transcribe":case "gpt-4o-transcribe-diarize":case "whisper-1":return 0.006;
            default:return -1;
        }
    }
    static double estimate(String model,long audioMs){double rate=perMinute(model);return rate<0?-1:rate*audioMs/60000d;}
    /**
     * Estimado según el proveedor: OpenAI por la tabla de arriba; OpenRouter por el catálogo guardado en el teléfono
     * (Models.perMinute); un servidor propio puede cobrar distinto (o nada): -1.
     * El catálogo se lee con un Context y esta firma no lo trae: usa el que dejó attach(). Quien tenga un Context a mano
     * debe preferir {@link #estimate(Context,String,String,long)}.
     */
    static double estimate(String provider,String model,long audioMs){
        if("openrouter".equals(provider)){Context a=app;return a==null?-1:estimate(a,provider,model,audioMs);}
        return "openai".equals(provider)?estimate(model,audioMs):-1;
    }
    /** Igual que estimate(provider, model, audioMs), con el Context para leer el catálogo de OpenRouter. */
    static double estimate(Context c,String provider,String model,long audioMs){
        if(!"openrouter".equals(provider))return estimate(provider,model,audioMs);
        if(c==null||model==null)return -1;
        attach(c);
        double rate;try{rate=Models.perMinute(c,model);}catch(RuntimeException e){rate=-1;}
        return rate<0||Double.isNaN(rate)?-1:rate*audioMs/60000d;
    }
    /**
     * Context de la app para los estimados de OpenRouter. Lo deja VozApp al arrancar; por si acaso, también el motor
     * y Transcript cada vez que reciben un Context (toda pantalla que muestra costos pasa antes por alguno de ellos).
     * Es siempre el Context de la aplicación (vive lo mismo que el proceso): no retiene ninguna pantalla.
     */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static volatile Context app;
    static void attach(Context c){if(app==null&&c!=null){Context a=c.getApplicationContext();app=a!=null?a:c;}}
    /**
     * Costo real de la transcripción en US$ (lo que informó OpenRouter en usage.cost, sumado entre partes), o -1 si el
     * estado no lo trae (OpenAI directo y servidor propio no lo informan): ahí se muestra el estimado.
     */
    static double real(org.json.JSONObject state){
        if(state==null||!state.has("costUsd"))return -1;double v=state.optDouble("costUsd",-1);return Double.isNaN(v)||v<0?-1:v;
    }
    /** Formato chileno: US$0,012 (3 decimales bajo 1 dólar). */
    static String usd(double value){
        if(value<0)return "—";
        if(value>0&&value<0.001)return "< US$0,001";
        String s=String.format(Locale.ROOT,value<1?"%.3f":"%.2f",value).replace('.',',');
        return "US$"+s;
    }
    private Pricing(){}
}
