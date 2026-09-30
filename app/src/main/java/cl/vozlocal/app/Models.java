package cl.vozlocal.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Modelos de OpenRouter (0.8.0): el catálogo que se actualiza solo, la «receta» de cada modelo de transcripción
 * (cómo pedirle que separe voces, cuánto audio acepta, en qué unidad cobra) y la elección «Automático (recomendado)».
 * Diseño e investigación: docs/PLAN-openrouter.md y docs/diseno/SPEC-0.8.md.
 *
 * FASE 0 (contrato): las firmas de este archivo son el contrato entre las partes. La parte «catalog» lo implementa
 * (catálogo, caché, precios, clave); puede AGREGAR métodos y completar la tabla, pero no cambiar estas firmas.
 */
final class Models {
    static final String BASE="https://openrouter.ai/api/v1";
    /** Valor de las preferencias orSpeakersModel/orTextModel para «Automático (recomendado)». */
    static final String AUTO="auto";
    /** Recomendados mientras no se haya leído el catálogo (verificados el 30-09-2026). */
    static final String DEFAULT_SPEAKERS="microsoft/mai-transcribe-2",DEFAULT_TEXT="microsoft/mai-transcribe-2";

    /** Cómo se pide la separación de voces en POST /audio/transcriptions. */
    enum Diarize{
        /** No separa voces. */
        NONE,
        /** provider.options.azure.diarization.enabled=true (ejemplo oficial de MAI-Transcribe 2). */
        AZURE,
        /** provider.options.deepgram.diarize=true. */
        DEEPGRAM,
        /** Parámetro genérico diarize:true (exige verbose_json; 400 si el modelo no puede). */
        GENERIC,
        /** Siempre activa, con marcas «<|speaker:N|>» dentro del texto (Fish Audio Transcribe 1 Pro). */
        INLINE
    }
    /** Unidad de pricing.prompt en el catálogo (no viene indicada: depende del modelo). */
    enum Unit{SECOND,HOUR,TOKEN,UNKNOWN}

    /** Receta de un modelo de transcripción. Nunca es null: un modelo desconocido recibe la receta por defecto. */
    static final class Recipe{
        final String id;final Diarize diarize;
        /** true si la receta sabe pedir voces (diarize != NONE). */
        final boolean diarizes;
        /** Acepta response_format=verbose_json (tramos con tiempos). Si no, se pide json (solo texto). */
        final boolean verbose;
        /** Audio máximo por envío con voces, en ms (p. ej. Gemini: 30 min). */
        final long maxMs;
        final Unit unit;
        /** El modo de pedir voces está documentado por OpenRouter (true) o es una suposición razonable (false). */
        final boolean verified;
        Recipe(String id,Diarize diarize,boolean verbose,long maxMs,Unit unit,boolean verified){this.id=id;this.diarize=diarize;this.diarizes=diarize!=Diarize.NONE;this.verbose=verbose;this.maxMs=maxMs;this.unit=unit;this.verified=verified;}
    }
    private static final long MIN=60_000L;
    private static final Recipe[] TABLE={
        new Recipe("microsoft/mai-transcribe-2",Diarize.AZURE,true,110*MIN,Unit.HOUR,true),
        new Recipe("deepgram/nova-3",Diarize.DEEPGRAM,true,110*MIN,Unit.SECOND,true),
        new Recipe("google/gemini-3.5-transcribe",Diarize.GENERIC,true,30*MIN,Unit.TOKEN,false),
        new Recipe("x-ai/grok-stt-1.0",Diarize.GENERIC,true,110*MIN,Unit.SECOND,false),
        new Recipe("fish-audio/transcribe-1-pro",Diarize.INLINE,true,60*MIN,Unit.SECOND,false),
        new Recipe("mistralai/voxtral-mini-transcribe",Diarize.GENERIC,true,110*MIN,Unit.SECOND,false),
        new Recipe("openai/gpt-transcribe",Diarize.NONE,true,110*MIN,Unit.SECOND,true),
        new Recipe("microsoft/mai-transcribe-1.5",Diarize.NONE,false,110*MIN,Unit.HOUR,true),
        new Recipe("openai/gpt-4o-transcribe",Diarize.NONE,false,110*MIN,Unit.TOKEN,true),
        new Recipe("openai/gpt-4o-mini-transcribe",Diarize.NONE,false,110*MIN,Unit.TOKEN,true),
    };
    /** Receta del modelo (por id exacto); si no está en la tabla, una por defecto: solo texto, verbose_json, unidad desconocida. */
    static Recipe recipe(String modelId){
        for(Recipe r:TABLE)if(r.id.equals(modelId))return r;
        return new Recipe(modelId==null?"":modelId,Diarize.NONE,true,110*MIN,Unit.UNKNOWN,false);
    }
    /**
     * Id del modelo a usar: la elección del usuario o, con «Automático», el recomendado vigente (lo escribe refresh() en
     * las preferencias orAutoSpeakers/orAutoText tras validar contra el catálogo; mientras tanto, los DEFAULT_*).
     */
    static String chosen(Settings s,boolean speakers){
        String v=speakers?s.orSpeakersModel():s.orTextModel();
        if(v.isEmpty()||AUTO.equals(v))v=s.prefs.getString(speakers?"orAutoSpeakers":"orAutoText",speakers?DEFAULT_SPEAKERS:DEFAULT_TEXT);
        return v;
    }

    /** Un modelo del catálogo, listo para mostrar. */
    static final class Model{
        final String id,name;
        /** Segundos Unix en que OpenRouter lo agregó (no es la fecha de lanzamiento ni mide calidad). */
        final long created;
        /** US$ por hora de audio; -1 si no se puede saber desde el catálogo (p. ej. cobra por tokens). */
        final double perHour;
        final Recipe recipe;
        /** Está en la tabla local (probado o documentado). Si no: «Nuevo · sin probar». */
        final boolean known;
        /** Segundos Unix en que OpenRouter lo retira; 0 si no tiene fecha. */
        final long expires;
        Model(String id,String name,long created,double perHour,Recipe recipe,boolean known,long expires){this.id=id;this.name=name;this.created=created;this.perHour=perHour;this.recipe=recipe;this.known=known;this.expires=expires;}
    }
    /** Último catálogo guardado en el teléfono (más nuevos primero); lista vacía si nunca se leyó. No usa la red. */
    static List<Model> cached(Context c){return new ArrayList<>();}
    /** Momento (ms) de la última lectura correcta del catálogo; 0 si nunca. */
    static long fetchedAt(Context c){return 0;}
    /**
     * Lee el catálogo público (sin clave): GET BASE+"/models?output_modalities=transcription&sort=newest", lo guarda y
     * actualiza los recomendados de «Automático». Llamar fuera del hilo principal.
     */
    static List<Model> refresh(Context c,HttpApi http)throws Exception{throw new UnsupportedOperationException("Catálogo pendiente (fase catalog).");}
    /** US$ por minuto de audio según el catálogo guardado; -1 si no se sabe. */
    static double perMinute(Context c,String modelId){return -1;}

    /** Lo que OpenRouter dice de una clave (GET BASE+"/key"). */
    static final class KeyInfo{
        final String label;
        /** US$ usados con esta clave; NaN si no viene. */
        final double usage;
        /** US$ que le quedan a la clave (su límite menos lo usado); NaN si la clave no tiene límite o no viene. */
        final double remaining;
        final boolean freeTier;
        KeyInfo(String label,double usage,double remaining,boolean freeTier){this.label=label;this.usage=usage;this.remaining=remaining;this.freeTier=freeTier;}
    }
    /** Comprueba la clave; lanza HttpApi.UserAction si no es válida. Llamar fuera del hilo principal. */
    static KeyInfo checkKey(HttpApi http,String key)throws Exception{throw new UnsupportedOperationException("Comprobación pendiente (fase catalog).");}

    // ---------- Notas (chat completions de OpenRouter) ----------
    /** Alias que OpenRouter resuelve siempre a la última versión de cada familia. El primero es el recomendado. */
    static final String[] NOTE_MODELS={"~anthropic/claude-sonnet-latest","~openai/gpt-luna-latest","~google/gemini-flash-latest"};
    static final String[] NOTE_NAMES={"Claude Sonnet (el más nuevo)","GPT Luna (el más nuevo)","Gemini Flash (el más nuevo)"};
    static final String NOTE_DEFAULT=NOTE_MODELS[0];
    private Models(){}
}
