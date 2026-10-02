package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Nota para tu segundo cerebro» (0.6.0): resumen, decisiones, tareas, frases clave y etiquetas que arma una IA a
 * partir de la transcripción, más los momentos ★ marcados al grabar.
 *
 * Personas: la IA nunca ve ni escribe nombres de quienes hablan. Recibe marcas {S1}, {S2}… (numeradas en el orden en
 * que aparecen) y la nota guarda qué voz es cada marca ("speakers"). Al mostrarla, {@link #resolve} pone el nombre
 * ACTUAL de cada voz, así la nota sigue siendo válida después de nombrar o corregir voces.
 *
 * Archivo: &lt;id&gt;.note.json (ver docs/diseno/SPEC-0.6.md). Estado: noteState working → ready | failed (+ noteError),
 * suggestedTitle. Nada de esto viaja al informe de soporte: solo eventos sin contenido.
 *
 * IA de la nota (0.8.0): OpenRouter, con la misma clave con que se transcribe (ver {@link #provider}). La nota guarda
 * "model" (el que se pidió), "modelUsed" (el que respondió) y "costUsd", que con OpenRouter es el cobro real
 * ("costReal") y con los demás un estimado. Diseño: docs/diseno/SPEC-0.8.md y SPEC-0.8b.md (segunda ronda: solo
 * OpenRouter). Los caminos de OpenAI y de Claude siguen en el código, con sus pruebas, pero la app ya no los elige.
 *
 * Idiomas (0.9.0): la nota se escribe en el idioma de la app (inglés, español o portugués de Brasil). La IA recibe el
 * pedido entero en ese idioma, instrucciones y cabecera ({@link #system}, {@link #prompt}), con el mismo formato JSON en
 * los tres; en español es el pedido de siempre, letra por letra. Lo que la app muestra (bitácora, errores, Markdown, pie
 * de la nota) sale de strings_notes.xml. Lo que devuelve la IA se lee igual en los tres idiomas («nadie», «nobody»,
 * «ninguém»…), y la nota guarda en qué idioma se pidió ("lang").
 */
final class Notes {
    private Notes(){}

    /**
     * Modelo por defecto con OpenAI: gpt-6-luna, el más eficiente de la familia GPT-6 (US$0,10 / US$0,50 por millón de
     * tokens de entrada / salida). Resumir y ordenar una conversación en español es una tarea acotada: con esfuerzo de
     * razonamiento «low» responde rápido y una reunión de 52 min cuesta menos de US$0,01. Acepta Chat Completions y
     * JSON. Revisado: 2026-09-29 (developers.openai.com/api/docs/models/gpt-6-luna). Settings.noteModel() lo reemplaza.
     */
    static final String OPENAI_MODEL="gpt-6-luna";
    /**
     * Modelo por defecto con Claude: claude-sonnet-5-5 (US$2 / US$10 por millón de tokens). Quien elige Claude lo hace
     * por la redacción y el matiz en español de Chile (PENDIENTES.md), así que aquí pesa la calidad: la diferencia con
     * Haiku es de centavos por nota. Además, claude-haiku-4-5-20251001 tiene retiro anunciado desde el 15-10-2026.
     * Revisado: 2026-09-29 (platform.claude.com/docs/en/models/overview).
     */
    static final String ANTHROPIC_MODEL="claude-sonnet-5-5";
    static final String REVIEWED="2026-09-29";
    static final String OPENAI_URL="https://api.openai.com/v1/chat/completions";
    static final String ANTHROPIC_URL="https://api.anthropic.com/v1/messages";
    /**
     * OpenRouter (0.8.0): Chat Completions con la misma clave con que se transcribe. El modelo por defecto es un alias
     * ({@link Models#NOTE_DEFAULT}) que OpenRouter resuelve siempre a la última versión de su familia; la nota guarda
     * qué versión respondió ("modelUsed"). La clave de OpenRouter solo viaja a esta dirección.
     */
    static final String OPENROUTER_URL=Models.BASE+"/chat/completions";
    /** Tope del texto enviado (≈ 10 h de conversación): protege de un pedido absurdo si algo sale mal. */
    static final int MAX_TRANSCRIPT_CHARS=400_000;

    // ---------- Instrucciones para la IA, en el idioma de la nota (0.9.0) ----------
    /*
     * Viven aquí y no en strings_notes.xml: no son textos de pantalla sino un contrato con la IA, que cambia junto con
     * SHAPE y normalize() (claves, límites). Así las tres versiones se leen una al lado de la otra, el formato JSON es uno
     * solo y el español queda letra por letra como antes (sin escapes de XML de por medio).
     */
    /** Forma exacta del JSON que se pide: la misma en los tres idiomas (normalize la lee igual). */
    private static final String SHAPE="{\"title\":\"…\",\"summary\":\"…\",\"decisions\":[\"…\"],\"tasks\":[{\"text\":\"…\",\"who\":\"{S1}\",\"when\":\"…\"}],\"quotes\":[{\"t\":192,\"text\":\"…\",\"who\":\"{S2}\"}],\"tags\":[\"…\"]}\n";
    /** Español: las instrucciones de siempre, sin cambiar una letra (NotesChecks lo vigila). */
    private static final String SYSTEM_ES=
        "Tomas notas de las grabaciones de una persona en Chile y las guardas en su «segundo cerebro» (Obsidian). "
        +"Recibes la transcripción automática de una grabación: una reunión, una conversación o una nota de voz.\n\n"
        +"Reglas:\n"
        +"1. Escribe en español neutro, claro y cercano, como lo leería alguien de Chile. Frases cortas, sin relleno.\n"
        +"2. Usa solo lo que está en la transcripción. No inventes hechos, cifras, fechas, nombres ni tareas. Si algo no queda claro, déjalo fuera.\n"
        +"3. Para referirte a quienes hablan usa SOLO su marca exacta entre llaves, por ejemplo {S1} o {S2}. Nunca escribas «Persona 1» ni «el hablante», y no adivines nombres para las marcas. A otras personas que solo se mencionan en la conversación sí las nombras como se dijo.\n"
        +"4. La transcripción puede traer errores de palabras o de quién habla: interpreta con criterio y no los copies.\n"
        +"5. Responde SOLO con un objeto JSON válido, sin texto antes ni después, con esta forma:\n"
        +SHAPE
        +"- title: título breve y específico del tema, de máximo 60 caracteres, sin fecha, sin marcas como {S1} y sin la palabra «Grabación».\n"
        +"- summary: de 1 a 5 oraciones con lo esencial.\n"
        +"- decisions: lo que se decidió o acordó. Lista vacía si no hubo decisiones.\n"
        +"- tasks: lo que alguien quedó de hacer. text empieza con un verbo; who es la marca de quien lo hará ({S1}), el nombre si es otra persona mencionada, o \"\" si no se sabe; when es el plazo tal como se dijo («el viernes», «antes del 15») o \"\". Lista vacía si no hay tareas.\n"
        +"- quotes: hasta 5 frases textuales que valga la pena recordar, copiadas tal cual; t es el segundo en que empieza su línea (número, según la hora [mm:ss]); who es la marca de quien la dijo o \"\".\n"
        +"- tags: de 3 a 6 etiquetas temáticas en minúsculas, de una palabra cada una, sin «#».";
    /** Inglés: las mismas reglas, en inglés natural y claro (sin suponer un país). «Person 1» y «Recording» son los textos de la app en inglés. */
    private static final String SYSTEM_EN=
        "You take notes from a person's recordings and save them in their “second brain” (Obsidian). "
        +"You receive the automatic transcript of a recording: a meeting, a conversation or a voice memo.\n\n"
        +"Rules:\n"
        +"1. Write in natural, clear and friendly English. Short sentences, no filler.\n"
        +"2. Use only what is in the transcript. Do not invent facts, figures, dates, names or tasks. If something is unclear, leave it out.\n"
        +"3. To refer to the speakers, use ONLY their exact marker in braces, for example {S1} or {S2}. Never write “Person 1” or “the speaker”, and do not guess names for the markers. Other people who are only mentioned in the conversation should be named the way they were mentioned.\n"
        +"4. The transcript may have wrong words or the wrong speaker: interpret it with judgment and do not copy those errors.\n"
        +"5. Reply ONLY with a valid JSON object, with no text before or after it, in this shape:\n"
        +SHAPE
        +"- title: a short, specific title for the topic, at most 60 characters, with no date, no markers like {S1} and without the word “Recording”.\n"
        +"- summary: 1 to 5 sentences with the essentials.\n"
        +"- decisions: what was decided or agreed. Empty list if there were no decisions.\n"
        +"- tasks: what someone committed to do. text starts with a verb; who is the marker of the person who will do it ({S1}), the name if it is another person mentioned, or \"\" if unknown; when is the deadline exactly as it was said (“on Friday”, “before the 15th”) or \"\". Empty list if there are no tasks.\n"
        +"- quotes: up to 5 verbatim lines worth remembering, copied exactly as said; t is the second at which its line starts (a number, from the [mm:ss] time); who is the marker of the person who said it, or \"\".\n"
        +"- tags: 3 to 6 topic tags in lowercase, one word each, without “#”.";
    /** Portugués de Brasil (con «você»): las mismas reglas. «Pessoa 1» y «Gravação» son los textos de la app en portugués. */
    private static final String SYSTEM_PT=
        "Você faz anotações das gravações de uma pessoa e as guarda no “segundo cérebro” dela (Obsidian). "
        +"Você recebe a transcrição automática de uma gravação: uma reunião, uma conversa ou uma nota de voz.\n\n"
        +"Regras:\n"
        +"1. Escreva em português do Brasil natural, claro e próximo. Frases curtas, sem rodeios.\n"
        +"2. Use só o que está na transcrição. Não invente fatos, números, datas, nomes nem tarefas. Se algo não ficar claro, deixe de fora.\n"
        +"3. Para se referir a quem fala, use SÓ a marca exata entre chaves, por exemplo {S1} ou {S2}. Nunca escreva “Pessoa 1” nem “o falante”, e não adivinhe nomes para as marcas. Já as outras pessoas que só são mencionadas na conversa, nomeie do jeito que foram citadas.\n"
        +"4. A transcrição pode ter erros de palavras ou de quem fala: interprete com critério e não copie esses erros.\n"
        +"5. Responda SÓ com um objeto JSON válido, sem texto antes nem depois, neste formato:\n"
        +SHAPE
        +"- title: título curto e específico do assunto, com no máximo 60 caracteres, sem data, sem marcas como {S1} e sem a palavra “Gravação”.\n"
        +"- summary: de 1 a 5 frases com o essencial.\n"
        +"- decisions: o que foi decidido ou combinado. Lista vazia se não houve decisões.\n"
        +"- tasks: o que alguém ficou de fazer. text começa com um verbo; who é a marca de quem vai fazer ({S1}), o nome se for outra pessoa mencionada, ou \"\" se não se sabe; when é o prazo do jeito que foi dito (“na sexta”, “antes do dia 15”) ou \"\". Lista vazia se não houver tarefas.\n"
        +"- quotes: até 5 frases literais que valha a pena lembrar, copiadas tal como foram ditas; t é o segundo em que a linha dela começa (um número, conforme a hora [mm:ss]); who é a marca de quem a disse, ou \"\".\n"
        +"- tags: de 3 a 6 etiquetas temáticas em minúsculas, de uma palavra cada, sem “#”.";
    /** Instrucciones para la IA en el idioma de la nota ("en", "es" o "pt"; cualquier otro, inglés, como values/). */
    static String system(String lang){return ai(lang,SYSTEM_EN,SYSTEM_ES,SYSTEM_PT);}
    /** El texto para la IA en el idioma de la nota, en el orden de Lang.SUPPORTED: inglés, español, portugués de Brasil. */
    private static String ai(String lang,String en,String es,String pt){return Lang.ES.equals(lang)?es:Lang.PT.equals(lang)?pt:en;}

    // ---------- Consultas ----------
    static boolean exists(Context c,String id){return FilesStore.file(c,id,".note.json").isFile();}
    /** Más que la espera máxima de la respuesta (5 min) más el armado del pedido: pasado esto, «working» quedó de un proceso que murió. */
    static final long WORKING_MAX_MS=7*60_000;
    /**
     * ¿Se está armando la nota ahora? noteState "working" solo cuenta si empezó hace menos de {@link #WORKING_MAX_MS}: si
     * Android cerró la app a mitad del pedido, el estado queda en "working" para siempre y hay que poder reintentar.
     */
    static boolean working(JSONObject state){
        if(state==null||!"working".equals(state.optString("noteState")))return false;
        long age=System.currentTimeMillis()-state.optLong("noteStartedAt",0);
        return age>=-60_000&&age<WORKING_MAX_MS;
    }
    /** La respuesta llegó cuando la transcripción ya había cambiado (otra versión o cancelada): no se guarda. */
    static final class Discarded extends HttpApi.UserAction{Discarded(){super(Lang.str(R.string.note_err_discarded));}}
    static JSONObject load(Context c,String id){try{return exists(c,id)?FilesStore.read(FilesStore.file(c,id,".note.json")):null;}catch(Exception e){return null;}}
    /** ¿Hay clave para la IA de la nota? Es la de OpenRouter, la misma con que se transcribe. */
    static boolean canGenerate(Context c){return new Settings(c).hasOpenRouterKey();}
    /**
     * IA de la nota: siempre "openrouter" (SPEC-0.8b, decisión 3: «Nota: siempre por OpenRouter»). Una preferencia
     * "noteProvider" que haya quedado de antes (OpenAI o Claude) ya no se respeta: la migración del esquema 5 la borra y
     * la interfaz no la ofrece, y así una clave de OpenAI o de Anthropic guardada nunca se vuelve a enviar sin querer.
     * Los caminos "openai" y "anthropic" quedan en generate(…, provider, model, key) para sus pruebas.
     */
    static String provider(Settings s){return "openrouter";}
    static String defaultModel(String provider){return "anthropic".equals(provider)?ANTHROPIC_MODEL:"openrouter".equals(provider)?Models.NOTE_DEFAULT:OPENAI_MODEL;}
    /** Modelo efectivo: el de Ajustes si corresponde a ese proveedor; si no, el recomendado. */
    static String model(Settings s,String provider){
        String m=s.noteModel()==null?"":s.noteModel().trim();
        // OpenRouter: ids «autor/modelo» o alias «~autor/familia-latest». Sin «/» no es de OpenRouter (quedó de otra IA).
        if("openrouter".equals(provider))return routerModel(m)?m:defaultModel(provider);
        // OpenAI y Claude: nombres sin «/» ni «~», así un id de OpenRouter nunca llega a sus APIs.
        if(m.isEmpty()||!m.matches("[A-Za-z0-9._:-]{2,80}"))return defaultModel(provider);
        boolean claude=m.startsWith("claude");
        return "anthropic".equals(provider)==claude?m:defaultModel(provider);
    }
    /**
     * ¿Sirve como modelo de OpenRouter para la nota? Un id «autor/modelo» o un alias «~autor/familia-latest», de hasta 80
     * caracteres. Es la misma regla de {@link #model}: la hoja «Modelo de la nota» de Ajustes acepta justo lo que aquí se envía.
     */
    static boolean routerModel(String m){return m!=null&&m.length()<=80&&m.matches("~?[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+");}
    /** Nombre del servicio en textos y errores. El de OpenRouter es HttpApi.OPENROUTER: con ese nombre HttpApi.require usa sus mensajes propios. */
    static String service(String provider){return "anthropic".equals(provider)?"Claude":"openrouter".equals(provider)?HttpApi.OPENROUTER:"OpenAI";}
    /** Modelo que respondió de verdad (con un alias «…-latest», la versión concreta); si no se supo, el que se pidió. */
    static String modelShown(JSONObject note){if(note==null)return "";String used=note.optString("modelUsed","");return used.isEmpty()?note.optString("model",""):used;}
    /**
     * «Armada con OpenRouter · anthropic/claude-sonnet-5.5 · costó US$0,004»: con qué IA se armó la nota y cuánto costó,
     * en el idioma de la app. El costo es el real si el proveedor lo informó ("costReal"); si no, el estimado con la
     * tarifa pública («≈»).
     */
    static String credit(JSONObject note){
        String model=modelShown(note);if(model.isEmpty())return "";
        StringBuilder b=new StringBuilder(Lang.str(R.string.note_credit,service(note.optString("provider")),model));
        double cost=note.optDouble("costUsd",-1);
        // «< US$0,001» ya dice que es aproximado: no lleva «≈» delante.
        if(cost>=0){String usd=Pricing.usd(cost);b.append(" · ").append(note.optBoolean("costReal")?Lang.str(R.string.note_cost_real,usd):usd.startsWith("<")?usd:"≈ "+usd);}
        return b.toString();
    }

    // ---------- Costos (estimados; el cobro real lo define el proveedor) ----------
    /** USD por millón de tokens {entrada, salida}, o null si no se conoce. Revisado: 2026-09-29. */
    static double[] rates(String model){
        if(model==null)return null;
        switch(model){
            case "gpt-6-luna":return new double[]{0.10,0.50};
            case "gpt-6-sol":return new double[]{2,10};
            case "gpt-5-nano":return new double[]{0.05,0.40};
            case "claude-sonnet-5-5":case "claude-sonnet-5":return new double[]{2,10};
            case "claude-haiku-4-5":case "claude-haiku-4-5-20251001":return new double[]{1,5};
            case "claude-opus-5-5":return new double[]{4,20};
            default:return null;
        }
    }
    static double usd(String model,long input,long output){double[] r=rates(model);return r==null?-1:(input*r[0]+output*r[1])/1e6;}
    /** Costo aproximado de armar la nota de un audio (≈ 210 tokens por minuto de conversación). -1 si no se conoce. */
    static double estimateUsd(String model,long audioMs){long input=1200+Math.round(audioMs/60000d*210),output=model!=null&&model.startsWith("claude-sonnet")?2500:1500;return usd(model,input,output);}

    // ---------- Armar la nota ----------
    /** Respuesta de la IA que no se pudo leer (se puede reintentar). */
    static final class BadAnswer extends IOException{BadAnswer(String m){super(m);}}

    /** Arma y guarda la nota (bloquea: se llama desde el trabajo de transcripción o un hilo de fondo). */
    static void generate(Context c,Recording r,HttpApi http)throws Exception{
        Settings s=new Settings(c);String provider=provider(s);String key="";
        try{key=s.openRouterKey();}catch(Exception ignored){}
        if(key==null||key.isEmpty()){
            String why=Lang.str(c,R.string.note_err_no_key);
            failed(c,r.id,why);throw new HttpApi.UserAction(why);
        }
        generate(c,r,http,provider,model(s,provider),key);
    }

    /** Igual que {@link #generate(Context,Recording,HttpApi)}, con proveedor, modelo y clave explícitos (pruebas). */
    static void generate(Context c,Recording r,HttpApi http,String provider,String model,String key)throws Exception{
        String id=r.id,service=service(provider);long started=System.currentTimeMillis();
        int timeout=http.readTimeoutMs;Runnable uploaded=http.onUploaded;HttpApi.Events events=http.onEvent;HttpApi.Progress progress=http.onProgress;String job=http.jobId;
        // Versión de la transcripción para la que se pide la nota: si «Volver a transcribir» empieza entretanto, la
        // respuesta es de la versión anterior y no se guarda (ni toca el estado de la nueva).
        int attempt=FilesStore.state(c,id).optInt("attempt",0);
        try{
            FilesStore.update(c,id,st->st.put("noteState","working").put("noteStartedAt",started).remove("noteError"));
            if(!Transcript.exists(c,id))throw new HttpApi.UserAction(Lang.str(c,R.string.note_err_no_transcript));
            Transcript t=Transcript.load(c,id);
            if(t.data.optBoolean("demo")||FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction(Lang.str(c,R.string.note_err_demo));
            if(t.segments().length()==0)throw new HttpApi.UserAction(Lang.str(c,R.string.note_err_no_speech));
            Recording latest=FilesStore.recording(c,id);if(latest!=null){r.title=latest.title;r.created=latest.created;r.duration=latest.duration;}
            // La nota sale en el idioma de la app (0.9.0): el pedido entero va en ese idioma.
            Prompt prompt=prompt(t,r,Marks.list(c,id),Lang.current(c));
            log(c,id,Lang.str(c,R.string.note_log_started,service));Diagnostics.event("note_started",id,"provider",provider,"model",model);
            // La respuesta llega completa al final: la espera crece con el largo del audio (2 min como mínimo, 5 como máximo).
            http.readTimeoutMs=(int)Math.min(300_000,Math.max(120_000,60_000+r.duration/60_000*2_000));
            http.onUploaded=null;http.onEvent=null;http.onProgress=null;if(http.jobId==null)http.jobId=id;
            Answer answer="anthropic".equals(provider)?anthropic(http,key,model,prompt)
                :"openrouter".equals(provider)?openrouter(http,key,model,prompt,()->{log(c,id,Lang.str(c,R.string.note_log_retry_simple));Diagnostics.event("note_retry_simple",id,"provider",provider,"model",model,"http",400);})
                :openai(http,key,model,prompt);
            JSONObject note=normalize(parseAnswer(answer.text),prompt.tokens,r.duration);
            // "lang" (0.9.0): en qué idioma se pidió la nota ("en", "es" o "pt").
            note.put("version",1).put("provider",provider).put("model",model).put("createdAt",System.currentTimeMillis()).put("speakers",new JSONObject(prompt.tokens)).put("lang",prompt.lang);
            // "model" es el que se pidió (puede ser un alias); "modelUsed", la versión que respondió de verdad (0.8.0).
            if(!answer.model.isEmpty())note.put("modelUsed",answer.model);
            if(answer.usage!=null)note.put("usage",answer.usage);
            // Costo: el real si el proveedor lo informa (OpenRouter); si no, el estimado con la tarifa pública del modelo.
            if(answer.cost>=0)note.put("costUsd",answer.cost).put("costReal",true);
            else if(answer.usage!=null){double cost=usd(model,answer.usage.optLong("input_tokens"),answer.usage.optLong("output_tokens"));if(cost>=0)note.put("costUsd",cost);}
            synchronized(FilesStore.LOCK){
                if(!FilesStore.file(c,id,".m4a").exists())return;
                if(http.cancelled)throw new InterruptedIOException("Cancelado");
                if(!Transcript.exists(c,id)||!ours(FilesStore.state(c,id),started,attempt))throw new Discarded();
                FilesStore.write(FilesStore.file(c,id,".note.json"),note);
            }
            String suggested=note.optString("title");
            boolean applied=!suggested.isEmpty()&&applyTitle(c,r,suggested);
            long took=System.currentTimeMillis()-started;
            FilesStore.update(c,id,st->{st.put("noteState","ready").remove("noteError");if(suggested.isEmpty())st.remove("suggestedTitle");else st.put("suggestedTitle",suggested);});
            log(c,id,Lang.str(c,R.string.note_log_ready)+" · "+Lang.str(c,R.string.note_log_took,Ui.humanDuration(took))+(applied?" · "+Lang.str(c,R.string.note_log_titled):""));
            // Solo datos sin contenido: el costo (un número) sirve para revisar tarifas; el título y el texto nunca se registran.
            Diagnostics.event("note_ready",id,"provider",provider,"model",model,"elapsed_ms",took,"count",note.getJSONArray("tasks").length(),"result",applied?"title_applied":"title_suggested","cost",note.has("costUsd")?(Object)note.optDouble("costUsd"):null);
            LocalStorage.enqueue(c,id);
        }catch(Exception e){
            // El estado de la nota solo se toca si sigue siendo el de ESTE pedido (no el de una versión nueva).
            if(e instanceof Discarded){
                try{FilesStore.update(c,id,st->{if(ours(st,started,attempt)){st.remove("noteState");st.remove("noteError");}});}catch(Exception ignored){}
                log(c,id,Lang.str(c,R.string.note_log_discarded));Diagnostics.event("note_discarded",id);throw e;
            }
            boolean cancelled=http.cancelled||(e instanceof InterruptedIOException&&!(e instanceof java.net.SocketTimeoutException));
            if(cancelled){try{FilesStore.update(c,id,st->{if(ours(st,started,attempt)){st.remove("noteState");st.remove("noteError");}});}catch(Exception ignored){}Diagnostics.event("note_cancelled",id);throw e;}
            String why=friendly(e,service);
            try{FilesStore.update(c,id,st->{if(ours(st,started,attempt))st.put("noteState","failed").put("noteError",why);});}catch(Exception ignored){}
            log(c,id,Lang.str(c,R.string.note_log_failed,why));
            Diagnostics.event("note_failed",id,"provider",provider,"model",model,"error_class",e.getClass().getSimpleName(),"reason",HttpApi.safeReason(e));
            throw e;
        }finally{http.readTimeoutMs=timeout;http.onUploaded=uploaded;http.onEvent=events;http.onProgress=progress;http.jobId=job;}
    }
    /** ¿El estado sigue siendo el de este pedido? Misma versión de la transcripción y la misma hora de inicio de la nota. */
    private static boolean ours(JSONObject st,long started,int attempt){return st.optInt("attempt",0)==attempt&&st.optLong("noteStartedAt",0)==started;}
    private static void failed(Context c,String id,String why){try{FilesStore.update(c,id,st->st.put("noteState","failed").put("noteError",why));}catch(Exception ignored){}}
    /** Texto para la persona (sin datos técnicos ni contenido), en el idioma de la app. */
    static String friendly(Exception e,String service){
        if(e instanceof HttpApi.UserAction||e instanceof BadAnswer)return e.getMessage();
        if(e instanceof java.net.SocketTimeoutException)return Lang.str(R.string.note_err_timeout,service);
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException||e instanceof java.net.NoRouteToHostException)return Lang.str(R.string.note_err_offline,service);
        // Los avisos pasajeros que nombran al servicio («OpenRouter no está disponible temporalmente (503).», «Claude está
        // saturado…») ya están escritos para la persona. En inglés o portugués el nombre puede no ir al comienzo
        // («O OpenRouter está…»): basta con que esté.
        String m=e.getMessage();if(e instanceof IOException&&m!=null&&m.contains(service))return m;
        return Lang.str(R.string.note_err_generic);
    }
    /** Bitácora visible del proceso: agrega una línea SIN cambiar el estado (el titular sigue siendo el de la transcripción). */
    private static void log(Context c,String id,String message){
        try{FilesStore.update(c,id,st->{JSONArray log=st.optJSONArray("log");if(log==null)log=new JSONArray();log.put(new JSONObject().put("t",System.currentTimeMillis()).put("m",message));while(log.length()>80)log.remove(0);st.put("log",log);});}catch(Exception ignored){}
    }

    // ---------- Título sugerido ----------
    /** ¿El título sigue siendo el automático («Grabación 29 sept. · 16:05», con o sin la fecha ISO delante)? */
    static boolean isDefaultTitle(String title,long created){
        String t=title==null?"":title.trim();if(t.isEmpty()||Lang.isAny(R.string.rec_recovered,t))return true;
        String auto=Recording.defaultTitle(created);if(t.equals(auto)||t.equals(Recording.withDate(auto,created)))return true;
        return Recording.automaticTitle(t);
    }
    /** Pone el título sugerido solo si el actual es el automático; nunca pisa uno que escribió la persona. */
    private static boolean applyTitle(Context c,Recording r,String suggested){
        try{
            Recording latest=FilesStore.recording(c,r.id);if(latest==null||!isDefaultTitle(latest.title,latest.created))return false;
            latest.title=suggested;latest.save(c);r.title=latest.title;
            log(c,r.id,Lang.str(c,R.string.note_log_title_applied));Diagnostics.event("note_title_applied",r.id);return true;
        }catch(Exception e){return false;}
    }

    // ---------- Pedido ----------
    /**
     * Texto que se envía, marca → id de voz (S1 → "A") y el idioma del pedido ("en", "es" o "pt"; 0.9.0): las
     * instrucciones van en ese mismo idioma ({@link #system}), aunque la app cambie de idioma mientras se envía.
     */
    static final class Prompt{final String text,lang;final LinkedHashMap<String,String> tokens;final boolean diarized;Prompt(String text,LinkedHashMap<String,String> tokens,boolean diarized,String lang){this.text=text;this.tokens=tokens;this.diarized=diarized;this.lang=lang;}}

    /** Arma el pedido compacto en el idioma de la app: cabecera, momentos ★ y una línea por intervención «[mm:ss] {S1}: texto». */
    static Prompt prompt(Transcript t,Recording r,JSONArray marks)throws Exception{return prompt(t,r,marks,Lang.current());}
    /**
     * Igual, en un idioma dado ("en", "es" o "pt"; otro, inglés): todo lo que lee la IA va en ese idioma, así escribe la
     * nota en él. En español, el pedido de siempre. La fecha, con el formato y los nombres de ese idioma: «martes 29 de
     * septiembre de 2026, 16:05», «Tuesday, September 29, 2026, 16:05», «terça-feira, 29 de setembro de 2026, 16:05».
     */
    static Prompt prompt(Transcript t,Recording r,JSONArray marks,String lang)throws Exception{
        String l=Lang.normalize(lang);if(l==null)l=Lang.EN;
        boolean diarized=t.diarized();JSONArray s=t.segments();
        LinkedHashMap<String,String> tokens=new LinkedHashMap<>();Map<String,String> byVoice=new HashMap<>();
        if(diarized)for(int i=0;i<s.length();i++){String v=s.getJSONObject(i).getString("speaker");if(!byVoice.containsKey(v)){String k="S"+(byVoice.size()+1);byVoice.put(v,k);tokens.put(k,v);}}
        StringBuilder b=new StringBuilder();
        String title=r.title==null?"":r.title.trim();
        b.append(ai(l,"Current title: “","Título actual: «","Título atual: “")).append(title).append(ai(l,"”","»","”"))
            .append(isDefaultTitle(title,r.created)?ai(l," (automatic)"," (automático)"," (automático)"):ai(l," (written by the person)"," (lo escribió la persona)"," (escrito pela pessoa)")).append('\n');
        b.append(ai(l,"Date: ","Fecha: ","Data: ")).append(new SimpleDateFormat(ai(l,"EEEE, MMMM d, yyyy, HH:mm","EEEE d 'de' MMMM 'de' yyyy, HH:mm","EEEE, d 'de' MMMM 'de' yyyy, HH:mm"),Lang.locale(l)).format(new Date(r.created))).append('\n');
        b.append(ai(l,"Duration: ","Duración: ","Duração: ")).append(Ui.humanDuration(r.duration)).append('\n');
        if(diarized){b.append(ai(l,"Speakers: ","Personas que hablan: ","Pessoas que falam: "));int n=0;for(String k:tokens.keySet())b.append(n++==0?"":", ").append('{').append(k).append('}');b.append('\n');}
        else b.append(ai(l,"Voices were not separated in this transcript: leave who empty (\"\") unless someone is named.\n","En esta transcripción no se separaron las voces: deja who vacío (\"\") salvo que se nombre a alguien.\n","Nesta transcrição as vozes não foram separadas: deixe who vazio (\"\") a menos que alguém seja nomeado.\n"));
        if(marks!=null&&marks.length()>0){
            b.append(ai(l,"\nMoments the person marked with ★ while recording (give them weight):\n","\nMomentos que la persona marcó con ★ mientras grababa (dales importancia):\n","\nMomentos que a pessoa marcou com ★ durante a gravação (dê importância a eles):\n"));
            for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;String label=m.optString("label","").trim();b.append("★ ").append(Recording.time(m.optLong("t"))).append(label.isEmpty()?"":" "+label).append('\n');}
        }
        b.append(ai(l,"\nTranscript:\n","\nTranscripción:\n","\nTranscrição:\n"));int header=b.length();
        for(int i=0;i<s.length();){
            JSONObject seg=s.getJSONObject(i);String voice=seg.getString("speaker");StringBuilder turn=new StringBuilder(seg.getString("text").trim());double end=seg.optDouble("end",seg.getDouble("start"));int j=i+1;
            if(diarized)while(j<s.length()){JSONObject next=s.getJSONObject(j);if(!next.getString("speaker").equals(voice)||next.getDouble("start")-end>Transcript.TURN_GAP_S)break;turn.append(' ').append(next.getString("text").trim());end=Math.max(end,next.optDouble("end",end));j++;}
            String line="["+Recording.time((long)(seg.getDouble("start")*1000))+"] "+(diarized?"{"+byVoice.get(voice)+"}: ":"")+turn.toString().replaceAll("\\s+"," ")+"\n";
            if(b.length()-header+line.length()>MAX_TRANSCRIPT_CHARS){b.append(ai(l,"[…] (transcript cut for length)\n","[…] (transcripción recortada por largo)\n","[…] (transcrição cortada por ser longa)\n"));break;}
            b.append(line);i=j;
        }
        return new Prompt(b.toString(),tokens,diarized,l);
    }

    /**
     * Texto de la respuesta y uso de tokens normalizado {input_tokens, output_tokens}. Desde la 0.8.0 también el modelo
     * que respondió ("" si el proveedor no lo dijo) y el costo real en US$ (-1 si no vino; solo OpenRouter lo informa).
     */
    static final class Answer{
        final String text;final JSONObject usage;final String model;final double cost;
        Answer(String text,JSONObject usage){this(text,usage,"",-1);}
        Answer(String text,JSONObject usage,String model,double cost){this.text=text;this.usage=usage;this.model=model==null?"":model;this.cost=cost;}
    }
    /** El nombre de modelo que devuelve el proveedor se guarda y se muestra: solo si parece un id (nada de texto libre). */
    private static String safeModel(String m){return m!=null&&m.matches("[A-Za-z0-9._:/~-]{1,120}")?m:"";}

    /** Cuerpo del pedido a OpenAI (Chat Completions, respuesta JSON). Las instrucciones, en el idioma del pedido. */
    static JSONObject openaiBody(String model,Prompt prompt)throws JSONException{
        JSONObject body=new JSONObject().put("model",model)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system(prompt.lang))).put(new JSONObject().put("role","user").put("content",prompt.text)))
            .put("response_format",new JSONObject().put("type","json_object")).put("max_completion_tokens",8000);
        // Solo los modelos que razonan aceptan reasoning_effort; «low» basta para ordenar una conversación.
        if(model.matches("^(gpt-[5-9]|o[1-9]).*"))body.put("reasoning_effort","low");
        return body;
    }
    static Answer openai(HttpApi http,String key,String model,Prompt prompt)throws Exception{
        HttpApi.Response res=http.request("POST",OPENAI_URL,key,"application/json",HttpApi.json(openaiBody(model,prompt)),null);
        require(res,"OpenAI",model);
        return chatAnswer(res,"OpenAI");
    }
    /** Lee una respuesta de Chat Completions (el formato de OpenAI, que OpenRouter repite): texto, uso, modelo y costo. */
    private static Answer chatAnswer(HttpApi.Response res,String service)throws Exception{
        JSONObject json;try{json=res.json();}catch(Exception e){throw new BadAnswer(Lang.str(R.string.note_err_format,service));}
        JSONArray choices=json.optJSONArray("choices");if(choices==null||choices.length()==0)throw new BadAnswer(Lang.str(R.string.note_err_empty,service));
        JSONObject choice=choices.getJSONObject(0),message=choice.optJSONObject("message");
        if(message!=null&&message.has("refusal")&&!message.isNull("refusal")&&!message.optString("refusal").trim().isEmpty())throw new HttpApi.UserAction(Lang.str(R.string.note_err_refused,service));
        String text=message==null||message.isNull("content")?"":content(message.opt("content"));
        if(text.trim().isEmpty())throw new BadAnswer(Lang.str("length".equals(choice.optString("finish_reason"))?R.string.note_err_cut:R.string.note_err_empty,service));
        JSONObject u=json.optJSONObject("usage"),usage=u==null?null:new JSONObject().put("input_tokens",u.optLong("prompt_tokens")).put("output_tokens",u.optLong("completion_tokens"));
        // usage.cost: lo que cobró OpenRouter por este pedido, en US$. OpenAI no lo envía. Un 0 no cuenta como cobro real
        // (la misma regla de Pricing.real): con una clave propia del proveedor (BYOK) OpenRouter informa 0 aunque el
        // proveedor sí cobró, y «costó US$0,000» escondería el estimado.
        double cost=u==null?-1:u.optDouble("cost",-1);
        return new Answer(text,usage,safeModel(json.optString("model")),cost>0&&!Double.isInfinite(cost)?cost:-1);
    }
    /** El contenido es un texto; algunos modelos lo entregan en partes [{type:"text",text:"…"}]: se unen las de texto. */
    private static String content(Object value){
        if(!(value instanceof JSONArray))return value==null?"":String.valueOf(value);
        StringBuilder b=new StringBuilder();JSONArray parts=(JSONArray)value;
        for(int i=0;i<parts.length();i++){JSONObject part=parts.optJSONObject(i);if(part!=null&&!part.isNull("text"))b.append(part.optString("text"));}
        return b.toString();
    }

    /**
     * Cuerpo del pedido a OpenRouter (Chat Completions). simple=true: solo el modelo, los mensajes y el tope de salida,
     * para reintentar si rechazó alguna opción: los alias «…-latest» cambian de versión solos y no todas las versiones
     * aceptan lo mismo. max_tokens va también en el modo simple: sin él OpenRouter reserva el máximo de salida del modelo
     * y, con poco saldo, responde 402 aunque la nota (8000 tokens) sí alcanzaba (hallazgo de la revisión).
     */
    static JSONObject openrouterBody(String model,Prompt prompt,boolean simple)throws JSONException{
        JSONObject body=new JSONObject().put("model",model)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system(prompt.lang))).put(new JSONObject().put("role","user").put("content",prompt.text)))
            .put("max_tokens",8000);
        if(simple)return body;
        body.put("response_format",new JSONObject().put("type","json_object"));
        // Claude no razona si no se le pide (y así responde rápido). A los que razonan por defecto (GPT, Gemini) se les
        // pide el mínimo: ordenar una conversación no necesita más, y razonar de más es más lento y más caro.
        if(!model.contains("anthropic/"))body.put("reasoning",new JSONObject().put("effort","low"));
        return body;
    }
    /**
     * Encabezados con que la app se identifica ante OpenRouter: los mismos de la transcripción (una sola fuente,
     * OpenRouterClient.headers()), así la nota y el audio aparecen como la misma app en la cuenta de OpenRouter.
     * La clave va aparte, en Authorization (la pone HttpApi).
     */
    static Map<String,String> openrouterHeaders(){return OpenRouterClient.headers();}
    /**
     * Nota por OpenRouter. simpler (opcional) avisa que el primer pedido fue rechazado por una opción y se reintenta una
     * vez en modo simple (un 400 no se cobra). No se probó contra la API real: por eso es tolerante con lo que responda.
     */
    static Answer openrouter(HttpApi http,String key,String model,Prompt prompt,Runnable simpler)throws Exception{
        Map<String,String> headers=openrouterHeaders();
        HttpApi.Response res=inner(http.request("POST",OPENROUTER_URL,key,"application/json",HttpApi.json(openrouterBody(model,prompt,false)),headers));
        if(res.code==400&&optionRejected(res)){
            if(simpler!=null)simpler.run();
            res=inner(http.request("POST",OPENROUTER_URL,key,"application/json",HttpApi.json(openrouterBody(model,prompt,true)),headers));
        }
        require(res,HttpApi.OPENROUTER,model);
        return chatAnswer(res,HttpApi.OPENROUTER);
    }
    /**
     * ¿Un 400 que puede deberse a una opción del pedido? No lo es si habla del largo de la transcripción, del saldo o de
     * un modelo que no existe: ahí el modo simple no cambia nada (mismos criterios que {@link #require}).
     */
    private static boolean optionRejected(HttpApi.Response res){
        String m=errorText(res);
        boolean tooLong=m.contains("context")||m.contains("too long")||m.contains("too many tokens")||(m.contains("maximum")&&m.contains("prompt"));
        return !(tooLong||m.contains("credit")||m.contains("billing")||m.contains("not a valid model"));
    }
    /**
     * Texto del error en minúsculas: error.message más error.metadata.raw, donde OpenRouter deja lo que dijo el proveedor
     * final cuando su propio mensaje es genérico («Provider returned error»). Así se reconoce «prompt is too long» aunque
     * venga solo ahí (como ya hace HttpApi.require). Vacío si la respuesta no trae error legible. Nunca se registra.
     */
    private static String errorText(HttpApi.Response res){
        try{
            JSONObject e=res.json().optJSONObject("error");if(e==null)return "";
            JSONObject meta=e.optJSONObject("metadata");String raw=meta==null||meta.isNull("raw")?"":meta.optString("raw");
            return (e.optString("message")+" "+raw).toLowerCase(Locale.ROOT);
        }catch(Exception ignored){return "";}
    }
    /**
     * OpenRouter puede responder 200 con el error del proveedor adentro ({"error":{…}} o choices[0].error). Se convierte
     * en la respuesta de error que indica su código, para que {@link #require} la explique igual que cualquier otra.
     */
    private static HttpApi.Response inner(HttpApi.Response res){
        if(res.code<200||res.code>=300)return res;
        try{
            JSONObject json=res.json(),error=json.optJSONObject("error");
            // Con el error dentro de la primera opción, el proveedor falló a mitad de la respuesta: no hay nota que leer.
            if(error==null){JSONArray choices=json.optJSONArray("choices");JSONObject first=choices==null?null:choices.optJSONObject(0);if(first!=null)error=first.optJSONObject("error");}
            if(error==null)return res;
            // Sin un código HTTP de error reconocible se trata como una falla pasajera del proveedor (se puede reintentar).
            int code=error.optInt("code",502);if(code<400||code>599)code=502;
            HttpApi.Response out=new HttpApi.Response(code,new JSONObject().put("error",error).toString(),null,res.requestId);out.jobId=res.jobId;return out;
        }catch(Exception e){return res;}
    }

    /** Cuerpo del pedido a Anthropic (Messages API). */
    static JSONObject anthropicBody(String model,Prompt prompt)throws JSONException{
        JSONObject body=new JSONObject().put("model",model).put("max_tokens",16000).put("system",system(prompt.lang))
            .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",prompt.text)));
        // El esfuerzo solo existe desde Sonnet/Opus 5 (Haiku 4.5 lo rechaza). «medium»: buena redacción sin pensar de más.
        if(model.matches("^claude-(sonnet|opus|fable)-[5-9].*"))body.put("output_config",new JSONObject().put("effort","medium"));
        return body;
    }
    /** Encabezados de Anthropic: la clave va en x-api-key (sin Authorization; HttpApi lo omite con token ""). */
    static Map<String,String> anthropicHeaders(String key){Map<String,String> h=new LinkedHashMap<>();h.put("x-api-key",key);h.put("anthropic-version","2023-06-01");return h;}
    static Answer anthropic(HttpApi http,String key,String model,Prompt prompt)throws Exception{
        HttpApi.Response res=http.request("POST",ANTHROPIC_URL,"","application/json",HttpApi.json(anthropicBody(model,prompt)),anthropicHeaders(key));
        require(res,"Claude",model);
        JSONObject json;try{json=res.json();}catch(Exception e){throw new BadAnswer(Lang.str(R.string.note_err_format,"Claude"));}
        JSONArray content=json.optJSONArray("content");String text=null;
        // El primer bloque puede ser de razonamiento: se toma el primer bloque de texto.
        if(content!=null)for(int i=0;i<content.length()&&text==null;i++){JSONObject block=content.optJSONObject(i);if(block!=null&&"text".equals(block.optString("type"))&&!block.optString("text").trim().isEmpty())text=block.optString("text");}
        String stop=json.optString("stop_reason");
        if(text==null){if("refusal".equals(stop))throw new HttpApi.UserAction(Lang.str(R.string.note_err_refused,"Claude"));throw new BadAnswer(Lang.str("max_tokens".equals(stop)?R.string.note_err_cut:R.string.note_err_empty,"Claude"));}
        JSONObject u=json.optJSONObject("usage"),usage=u==null?null:new JSONObject().put("input_tokens",u.optLong("input_tokens")+u.optLong("cache_read_input_tokens")+u.optLong("cache_creation_input_tokens")).put("output_tokens",u.optLong("output_tokens"));
        return new Answer(text,usage,safeModel(json.optString("model")),-1);
    }

    /**
     * Errores con palabras de la nota (no del audio), en el idioma de la app; el resto, con la clasificación común de
     * HttpApi. Solo el de la clave (401) nombra la clave: StatusText.aboutKey reconoce key_word en cualquier mensaje.
     */
    static void require(HttpApi.Response res,String service,String model)throws Exception{
        if(res.code>=200&&res.code<300)return;
        String message=errorText(res),type="";
        try{JSONObject e=res.json().optJSONObject("error");if(e!=null)type=e.optString("type");}catch(Exception ignored){}
        String why=null;boolean transientError=false,router=HttpApi.OPENROUTER.equals(service);
        String broke=router?Lang.str(R.string.note_err_no_credits_router):Lang.str(R.string.note_err_no_credits,service);
        // Termina con key_fix_in_settings («Revísala en Ajustes.»), el mismo final que los del motor: la bienvenida lo quita
        // (StatusText.withoutSettingsHint), porque ahí la clave se corrige en el paso anterior.
        if(res.code==401)why=Lang.str(R.string.note_err_key,service,Lang.str(R.string.key_fix_in_settings));
        // 402 (0.8.0): así avisa OpenRouter que no quedan créditos. Sin esto, HttpApi.require hablaría del formato del audio.
        else if(res.code==402)why=broke;
        else if(res.code==529||"overloaded_error".equals(type)){why=Lang.str(R.string.note_err_overloaded,service);transientError=true;}
        else if(res.code==400||res.code==404||res.code==413||res.code==422){
            if(message.contains("credit balance")||message.contains("billing")||message.contains("insufficient credits"))why=broke;
            else if(res.code==413||message.contains("context")||message.contains("too long")||message.contains("too many tokens")||(message.contains("maximum")&&message.contains("prompt")))why=Lang.str(R.string.note_err_too_long);
            // 404 por la privacidad de la cuenta («No endpoints found matching your data policy»): el modelo existe, pero la
            // configuración de privacidad de OpenRouter descarta a todos sus proveedores. Cambiar de modelo puede no servir:
            // se dice dónde está el ajuste (hallazgo de la revisión; HttpApi.require ya lo distingue para el audio).
            else if(router&&res.code==404&&(message.contains("data policy")||message.contains("privacy")))why=Lang.str(R.string.note_err_data_policy);
            // OpenRouter responde 404 cuando retiró el modelo o ningún proveedor lo ofrece (y 400 si el id no existe): la
            // salida es elegir otro. Otro 400 que solo nombre «model» puede ser cualquier cosa: va al texto general.
            else if(router&&(res.code==404||message.contains("not a valid model")))why=Lang.str(R.string.note_err_model_gone,model);
            else if(!router&&message.contains("model"))why=Lang.str(R.string.note_err_model_unavailable,model,service);
            // Sin esto, HttpApi.require hablaría del audio, que aquí no se envía.
            else why=Lang.str(R.string.note_err_rejected,service,res.code)+(res.requestId.isEmpty()?"":" · Ref: "+res.requestId);
        }
        if(why==null){HttpApi.require(res,service);return;}
        Diagnostics.event("api_rejected",res.jobId,"http",res.code,"request_id",res.requestId,"type",HttpApi.safeToken(type));
        if(transientError)throw new IOException(why);
        throw new HttpApi.UserAction(why);
    }

    // ---------- Respuesta ----------
    /** Saca el objeto JSON de la respuesta, aunque venga entre ```json … ``` o con texto alrededor. */
    static JSONObject parseAnswer(String text)throws BadAnswer{
        String s=text==null?"":text.trim();int a=s.indexOf('{'),b=s.lastIndexOf('}');
        if(a<0||b<a)throw new BadAnswer(Lang.str(R.string.note_err_format_ai));
        try{return new JSONObject(s.substring(a,b+1));}catch(JSONException e){throw new BadAnswer(Lang.str(R.string.note_err_format_ai));}
    }
    private static final Pattern TOKEN=Pattern.compile("\\{\\s*[Ss](\\d{1,2})\\s*\\}|(?<![\\w{])S(\\d{1,2})(?![\\w}])");
    /** Deja las marcas como {S1} (acepta «{s1}», «{ S1 }» o «S1» suelto de una marca conocida). */
    static String tokens(String text,Map<String,String> known){
        if(text==null)return "";Matcher m=TOKEN.matcher(text);StringBuffer out=new StringBuffer();
        while(m.find()){String n=m.group(1)!=null?m.group(1):m.group(2);boolean bare=m.group(1)==null;String k="S"+Integer.parseInt(n);
            m.appendReplacement(out,Matcher.quoteReplacement(bare&&!known.containsKey(k)?m.group():"{"+k+"}"));}
        m.appendTail(out);return out.toString().trim();
    }
    /*
     * «No se sabe» en lo que devuelve la IA, en los tres idiomas y sea cual sea el de la nota (puede responder en otro):
     * quién («nadie», «nobody», «ninguém»…) y plazo de una tarea («no se sabe», «unknown», «não informado»…) quedan
     * vacíos. Las palabras en español son las de siempre: lo que la app hacía con ellas no cambia.
     */
    private static final Pattern NO_WHO=Pattern.compile("(?iu)(?:null|ninguno|nadie|desconocido|no se sabe|-|—|none|nobody|no one|unknown|n/a|not stated|not specified|nenhum|nenhuma|ninguém|desconhecido|desconhecida|não se sabe|não informado|não informada)");
    private static final Pattern NO_WHEN=Pattern.compile("(?iu)(?:null|-|—|no se sabe|none|unknown|n/a|not stated|not specified|nenhum|nenhuma|desconhecido|não se sabe|não informado|não informada)");
    /** Quién: "S1" si es una marca conocida; el nombre si es otra persona; "" si no se sabe. */
    static String who(Object value,Map<String,String> known){
        String v=value==null||value==JSONObject.NULL?"":String.valueOf(value).trim();
        Matcher m=Pattern.compile("^\\{?\\s*[Ss](\\d{1,2})\\s*\\}?$").matcher(v);
        if(m.matches()){String k="S"+Integer.parseInt(m.group(1));return known.containsKey(k)?k:"";}
        if(NO_WHO.matcher(v).matches())return "";
        v=tokens(v,known);return v.length()>60?v.substring(0,60).trim():v;
    }
    static String cleanTitle(String title){
        String t=title==null?"":title.replaceAll("[\\r\\n\\t]+"," ").trim();
        t=t.replaceAll("^[«»\"'“”]+|[«»\"'“”]+$","").trim().replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s*[-·:]?\\s*","").replaceAll("\\{\\s*[Ss]\\d{1,2}\\s*\\}","").replaceAll("\\s+"," ").trim();
        t=t.replaceAll("[.。]+$","").trim();
        if(t.length()>60){int cut=t.lastIndexOf(' ',60);t=(cut>30?t.substring(0,cut):t.substring(0,60)).replaceAll("[\\s,;:·-]+$","");}
        return t;
    }
    static JSONArray cleanTags(Object raw){
        JSONArray out=new JSONArray();Set<String> seen=new LinkedHashSet<>();List<String> items=new ArrayList<>();
        if(raw instanceof JSONArray){JSONArray a=(JSONArray)raw;for(int i=0;i<a.length();i++)items.add(a.optString(i));}
        else if(raw instanceof String)items.addAll(Arrays.asList(((String)raw).split("[,;]")));
        for(String item:items){
            // Locale.ROOT: el mismo resultado en los tres idiomas (en español, igual que con es-CL) y sin sorpresas regionales.
            String t=item.trim().toLowerCase(Locale.ROOT).replace("#","").replaceAll("[\\s_]+","-").replaceAll("[^\\p{L}\\p{N}-]","").replaceAll("-+","-").replaceAll("^-|-$","");
            if(t.isEmpty()||t.matches("\\d+")||t.length()>40||!seen.add(t))continue;
            out.put(t);if(out.length()==6)break;
        }
        return out;
    }
    /** Segundos desde un número o un texto «mm:ss» / «h:mm:ss». -1 si no se entiende. */
    static double seconds(Object value){
        if(value instanceof Number)return ((Number)value).doubleValue();
        String v=value==null?"":String.valueOf(value).trim().replaceAll("[\\[\\]]","");
        try{if(v.matches("\\d+(\\.\\d+)?"))return Double.parseDouble(v);
            if(v.matches("\\d{1,2}:\\d{2}(:\\d{2})?")){String[] p=v.split(":");double s=0;for(String x:p)s=s*60+Integer.parseInt(x);return s;}}catch(Exception ignored){}
        return -1;
    }
    private static List<Object> list(Object raw){List<Object> out=new ArrayList<>();if(raw instanceof JSONArray){JSONArray a=(JSONArray)raw;for(int i=0;i<a.length();i++)out.add(a.opt(i));}else if(raw instanceof String&&!((String)raw).trim().isEmpty())out.add(raw);return out;}
    private static String textOf(Object item,Map<String,String> known){
        String v=item instanceof JSONObject?((JSONObject)item).optString("text",""):item==null||item==JSONObject.NULL?"":String.valueOf(item);
        return tokens(v.replaceAll("\\s+"," "),known);
    }
    /** Nota limpia a partir de lo que devolvió la IA: límites, marcas {S1} y tipos correctos. */
    static JSONObject normalize(JSONObject raw,Map<String,String> known,long durationMs)throws JSONException{
        JSONObject n=new JSONObject();
        n.put("title",cleanTitle(raw.optString("title","")));
        n.put("summary",tokens(raw.optString("summary","").replaceAll("[ \\t]+"," "),known));
        JSONArray decisions=new JSONArray();for(Object d:list(raw.opt("decisions"))){String v=textOf(d,known);if(!v.isEmpty()&&decisions.length()<12)decisions.put(v);}
        n.put("decisions",decisions);
        JSONArray tasks=new JSONArray();
        for(Object item:list(raw.opt("tasks"))){
            String text=textOf(item,known);if(text.isEmpty()||tasks.length()>=20)continue;
            JSONObject o=item instanceof JSONObject?(JSONObject)item:new JSONObject();String when=o.isNull("when")?"":o.optString("when","").trim();if(NO_WHEN.matcher(when).matches())when="";
            tasks.put(new JSONObject().put("text",text).put("who",who(o.opt("who"),known)).put("when",when).put("done",o.optBoolean("done",false)));
        }
        n.put("tasks",tasks);
        JSONArray quotes=new JSONArray();double max=durationMs>0?durationMs/1000d:Double.MAX_VALUE;
        for(Object item:list(raw.opt("quotes"))){
            if(quotes.length()>=5)break;String text=textOf(item,known).replaceAll("^[«\"“]+|[»\"”]+$","").trim();if(text.isEmpty())continue;
            JSONObject o=item instanceof JSONObject?(JSONObject)item:new JSONObject();double t=seconds(o.opt("t"));
            JSONObject q=new JSONObject().put("text",text).put("who",who(o.opt("who"),known));if(t>=0)q.put("t",Math.round(Math.min(t,max)));
            quotes.put(q);
        }
        n.put("quotes",quotes);
        n.put("tags",cleanTags(raw.opt("tags")));
        return n;
    }

    // ---------- Nombres ----------
    /** Reemplaza {S1}, {S2}… por el nombre actual de cada voz («alguien», en el idioma de la app, si no está). names acepta claves "S1" o "{S1}". */
    static String resolve(String text,Map<String,String> names){
        if(text==null)return "";if(names==null)names=Collections.emptyMap();
        Matcher m=Pattern.compile("\\{\\s*[Ss](\\d{1,2})\\s*\\}").matcher(text);StringBuffer out=new StringBuffer();
        while(m.find()){String k="S"+Integer.parseInt(m.group(1));String name=names.get(k);if(name==null)name=names.get("{"+k+"}");m.appendReplacement(out,Matcher.quoteReplacement(name==null||name.trim().isEmpty()?Lang.str(R.string.note_someone):name.trim()));}
        m.appendTail(out);return out.toString();
    }
    /** Quién (de una tarea o frase) → nombre visible: la marca se resuelve; un nombre escrito se deja tal cual. */
    static String whoName(String who,Map<String,String> names){
        if(who==null||who.trim().isEmpty())return "";String w=who.trim();
        if(w.matches("S\\d{1,2}")){String n=names==null?null:names.get(w);return n==null?"":n;}
        return resolve(w,names);
    }
    /** Marca → nombre actual (S1 → «Konrad») para la nota guardada de esta grabación. */
    static LinkedHashMap<String,String> names(Context c,String id){
        Transcript t=null;try{if(Transcript.exists(c,id))t=Transcript.load(c,id);}catch(Exception ignored){}
        return names(load(c,id),t);
    }
    /**
     * Marca → nombre actual. Si la voz ya no aparece (se unió a otra al corregir), toma el nombre de la voz que hoy
     * dice esos tramos; si no se encuentra, «Persona N» en el idioma de la app (speaker_n, como Transcript).
     */
    static LinkedHashMap<String,String> names(JSONObject note,Transcript t){
        LinkedHashMap<String,String> out=new LinkedHashMap<>();JSONObject speakers=note==null?null:note.optJSONObject("speakers");
        if(speakers==null)return out;
        Map<String,String> current=new HashMap<>();if(t!=null)try{current=t.speakers();}catch(Exception ignored){}
        List<String> keys=new ArrayList<>();for(Iterator<String> it=speakers.keys();it.hasNext();)keys.add(it.next());
        keys.sort(Comparator.comparingInt(k->{try{return Integer.parseInt(k.replaceAll("\\D",""));}catch(Exception e){return 999;}}));
        for(String k:keys){
            String voice=speakers.optString(k),name=current.get(voice);
            if(name==null&&t!=null)name=mergedInto(t,voice,current);
            // El número sale de la marca (S3 → 3); una marca sin número razonable toma su lugar en la lista.
            if(name==null){int n=number(k);name=Lang.str(R.string.speaker_n,n>0?n:out.size()+1);}
            out.put(k,name);
        }
        return out;
    }
    /** El número de una marca ("S3" → 3); 0 si no trae uno o es absurdo (un archivo dañado no rompe la nota). */
    private static int number(String k){String d=k==null?"":k.replaceAll("\\D","");return d.isEmpty()||d.length()>6?0:Integer.parseInt(d);}
    /** Nombre de la voz que hoy dice la mayoría de los tramos que eran de «voice». */
    private static String mergedInto(Transcript t,String voice,Map<String,String> current){
        Map<String,Integer> count=new HashMap<>();JSONArray s=t.segments();
        for(int i=0;i<s.length();i++){JSONObject seg=s.optJSONObject(i);if(seg!=null&&voice.equals(seg.optString("orig",null)))count.merge(seg.optString("speaker"),1,Integer::sum);}
        String best=null;int most=0;for(Map.Entry<String,Integer> e:count.entrySet())if(e.getValue()>most){most=e.getValue();best=e.getKey();}
        return best==null?null:current.get(best);
    }

    // ---------- Tareas y borrado ----------
    static void setTaskDone(Context c,String id,int index,boolean done)throws Exception{
        synchronized(FilesStore.LOCK){
            JSONObject note=load(c,id);if(note==null)return;JSONArray tasks=note.optJSONArray("tasks");
            if(tasks==null||index<0||index>=tasks.length()||tasks.optJSONObject(index)==null)return;
            tasks.getJSONObject(index).put("done",done);FilesStore.write(FilesStore.file(c,id,".note.json"),note);
        }
        Diagnostics.event("note_task_toggled",id,"result",done?"done":"open");LocalStorage.enqueue(c,id);
    }
    static void delete(Context c,String id){
        synchronized(FilesStore.LOCK){FilesStore.file(c,id,".note.json").delete();FilesStore.version.incrementAndGet();}
        try{FilesStore.update(c,id,st->{st.remove("noteState");st.remove("noteError");st.remove("suggestedTitle");});}catch(Exception ignored){}
    }

    // ---------- Markdown (Obsidian) ----------
    /** Nota completa en Markdown (frontmatter + secciones + transcripción). Sin nota: frontmatter, momentos y transcripción. */
    static String markdown(Context c,Recording r)throws Exception{
        Recording latest=FilesStore.recording(c,r.id);if(latest!=null)r=latest;
        JSONObject note=load(c,r.id);Transcript t=null;
        if(Transcript.exists(c,r.id))t=Transcript.load(c,r.id);
        if(t==null&&note==null)throw new java.io.FileNotFoundException("Sin transcripción");
        return markdown(r,note,t,Marks.list(c,r.id));
    }
    private static String q(String v){return "\""+(v==null?"":v).replace("\\","\\\\").replace("\"","\\\"").replaceAll("[\\r\\n\\t]+"," ").trim()+"\"";}
    private static String line(String v){return v==null?"":v.replaceAll("[\\r\\n]+"," ").trim();}
    /** Título visible sin la fecha ISO delante (la fecha ya va en el frontmatter); sin título, «Grabación» en el idioma de la app. */
    static String heading(String title){String t=title==null?"":title.trim();String h=t.replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s+","").trim();return h.isEmpty()?(t.isEmpty()?Lang.str(R.string.note_untitled):t):h;}
    /** «fecha: 2026-09-29»: una propiedad del frontmatter, con su nombre en el idioma de la app. */
    private static void property(StringBuilder md,int name,String value){md.append(Lang.str(name)).append(": ").append(value).append('\n');}
    /** «## Resumen»: el título de una sección, en el idioma de la app. */
    private static void section(StringBuilder md,int title){md.append("## ").append(Lang.str(title)).append("\n\n");}

    /**
     * Nota en Markdown, en el idioma de la app (0.9.0): propiedades del frontmatter, secciones y avisos. En español, lo de
     * siempre (fecha, hora, duracion, personas…). Lo escrito por la IA queda en el idioma en que se pidió la nota.
     */
    static String markdown(Recording r,JSONObject note,Transcript t,JSONArray marks)throws Exception{
        Map<String,String> names=names(note,t);
        LinkedHashMap<String,String> voices=new LinkedHashMap<>();if(t!=null&&t.diarized())voices=t.speakers();
        StringBuilder md=new StringBuilder("---\n");
        property(md,R.string.note_md_date,Recording.isoDate(r.created));
        property(md,R.string.note_md_time,q(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(r.created))));
        property(md,R.string.note_md_duration,q(Ui.humanDuration(r.duration)));
        LinkedHashSet<String> people=new LinkedHashSet<>(t!=null?voices.values():names.values());
        md.append(Lang.str(R.string.note_md_people)).append(':');if(people.isEmpty())md.append(" []\n");else{md.append('\n');for(String p:people)md.append("  - ").append(q(p)).append('\n');}
        // "tags" es la propiedad que Obsidian reconoce como etiquetas (con otro nombre no aparecen en su panel de
        // etiquetas): no se traduce.
        JSONArray tags=note==null?null:note.optJSONArray("tags");
        if(tags!=null&&tags.length()>0){md.append("tags:\n");for(int i=0;i<tags.length();i++)md.append("  - ").append(q(tags.optString(i))).append('\n');}
        property(md,R.string.note_md_source,q("Verbapp"));
        property(md,R.string.note_md_recording,q(r.title));
        // Con un alias de OpenRouter se escribe la versión que respondió («anthropic/claude-sonnet-5.5»), no el alias.
        if(note!=null)property(md,R.string.note_md_ai,q(service(note.optString("provider"))+" · "+modelShown(note)));
        md.append("---\n\n# ").append(line(heading(r.title))).append("\n\n");
        if(note!=null){
            section(md,R.string.note_summary);md.append(resolve(note.optString("summary").trim(),names)).append("\n\n");
            JSONArray decisions=note.optJSONArray("decisions");
            if(decisions!=null&&decisions.length()>0){section(md,R.string.note_decisions);for(int i=0;i<decisions.length();i++)md.append("- ").append(line(resolve(decisions.optString(i),names))).append('\n');md.append('\n');}
            JSONArray tasks=note.optJSONArray("tasks");
            if(tasks!=null&&tasks.length()>0){section(md,R.string.note_tasks);
                for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;String who=whoName(task.optString("who"),names),when=task.optString("when").trim();
                    md.append(task.optBoolean("done")?"- [x] ":"- [ ] ").append(line(resolve(task.optString("text"),names))).append(who.isEmpty()?"":" — "+line(who)).append(when.isEmpty()?"":" · "+line(when)).append('\n');}
                md.append('\n');}
        }
        if(marks!=null&&marks.length()>0){section(md,R.string.note_marks);
            for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;String label=line(m.optString("label",""));md.append("- ★ ").append(Recording.time(m.optLong("t"))).append(label.isEmpty()?"":" "+label).append('\n');}
            md.append('\n');}
        if(note!=null){JSONArray quotes=note.optJSONArray("quotes");
            if(quotes!=null&&quotes.length()>0){section(md,R.string.note_quotes);
                // Comillas del idioma de la app: «…» en español, “…” en inglés y portugués.
                for(int i=0;i<quotes.length();i++){JSONObject qt=quotes.optJSONObject(i);if(qt==null)continue;String who=whoName(qt.optString("who"),names);
                    md.append("> ").append(Lang.str(R.string.note_md_quote,line(resolve(qt.optString("text"),names))));
                    if(!who.isEmpty())md.append(" — ").append(line(who));
                    if(qt.has("t"))md.append(" (").append(Recording.time(qt.optLong("t")*1000)).append(")");
                    md.append("\n\n");}}}
        if(t!=null){section(md,R.string.note_transcript);appendTranscript(md,t,voices,marks);}
        return md.toString().replaceAll("\\n{3,}","\n\n").trim()+"\n";
    }
    /** Intervenciones agrupadas «**Nombre** (mm:ss): texto»; las que tienen un momento ★ lo llevan delante. */
    private static void appendTranscript(StringBuilder md,Transcript t,Map<String,String> voices,JSONArray marks)throws Exception{
        boolean diarized=t.diarized();JSONArray s=t.segments();
        // Avisos en cursiva («_…_»): el texto, en el idioma de la app.
        if(t.data.optBoolean("demo"))md.append('_').append(Lang.str(R.string.note_md_demo)).append("_\n\n");
        if(diarized&&!t.reviewed()&&s.length()>0)md.append('_').append(Lang.str(R.string.note_md_auto_voices)).append("_\n\n");
        if(s.length()==0){md.append('_').append(Lang.str(R.string.note_md_no_speech)).append("_\n");return;}
        List<Double> starts=new ArrayList<>();List<String> who=new ArrayList<>(),text=new ArrayList<>();
        for(int i=0;i<s.length();){
            JSONObject seg=s.getJSONObject(i);String voice=seg.getString("speaker");StringBuilder turn=new StringBuilder(seg.getString("text").trim());double end=seg.optDouble("end",seg.getDouble("start"));int j=i+1;
            if(diarized)while(j<s.length()){JSONObject next=s.getJSONObject(j);if(!next.getString("speaker").equals(voice)||next.getDouble("start")-end>Transcript.TURN_GAP_S)break;turn.append(' ').append(next.getString("text").trim());end=Math.max(end,next.optDouble("end",end));j++;}
            starts.add(seg.getDouble("start"));who.add(voice);text.add(turn.toString().replaceAll("\\s+"," "));i=j;
        }
        // Cada ★ va en la intervención que sonaba en ese momento (la última que empezó antes).
        boolean[] starred=new boolean[starts.size()];
        if(marks!=null)for(int k=0;k<marks.length();k++){JSONObject m=marks.optJSONObject(k);if(m==null)continue;double at=m.optLong("t")/1000d;int hit=0;for(int i=0;i<starts.size();i++)if(starts.get(i)<=at+0.001)hit=i;starred[hit]=true;}
        boolean times=diarized||starts.size()>1;
        for(int i=0;i<starts.size();i++){
            String when=Recording.time((long)(starts.get(i)*1000));md.append(starred[i]?"★ ":"");
            if(diarized){String name=voices.get(who.get(i));md.append("**").append(line(name==null?Lang.str(R.string.note_person):name)).append("** (").append(when).append("): ");}
            else if(times)md.append("(").append(when).append(") ");
            md.append(text.get(i)).append("\n\n");
        }
    }
}
