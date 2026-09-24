package cl.vozlocal.app;

import java.util.Locale;

/**
 * Tarifas públicas por minuto de audio (USD) para estimar costos en pantalla.
 * Son ESTIMADOS: el cobro real lo define el proveedor (revisa platform.openai.com/usage).
 * Actualizar esta tabla cuando cambien los precios. Revisado: 2026-09-23.
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
    /** Solo hay tarifas conocidas para OpenAI; un servidor propio puede cobrar distinto (o nada). */
    static double estimate(String provider,String model,long audioMs){return "openai".equals(provider)?estimate(model,audioMs):-1;}
    /** Formato chileno: US$0,012 (3 decimales bajo 1 dólar). */
    static String usd(double value){
        if(value<0)return "—";
        if(value>0&&value<0.001)return "< US$0,001";
        String s=String.format(Locale.ROOT,value<1?"%.3f":"%.2f",value).replace('.',',');
        return "US$"+s;
    }
    private Pricing(){}
}
