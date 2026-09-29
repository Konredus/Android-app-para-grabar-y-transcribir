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
    /** Tope del texto enviado (≈ 10 h de conversación): protege de un pedido absurdo si algo sale mal. */
    static final int MAX_TRANSCRIPT_CHARS=400_000;

    static final String SYSTEM=
        "Tomas notas de las grabaciones de una persona en Chile y las guardas en su «segundo cerebro» (Obsidian). "
        +"Recibes la transcripción automática de una grabación: una reunión, una conversación o una nota de voz.\n\n"
        +"Reglas:\n"
        +"1. Escribe en español neutro, claro y cercano, como lo leería alguien de Chile. Frases cortas, sin relleno.\n"
        +"2. Usa solo lo que está en la transcripción. No inventes hechos, cifras, fechas, nombres ni tareas. Si algo no queda claro, déjalo fuera.\n"
        +"3. Para referirte a quienes hablan usa SOLO su marca exacta entre llaves, por ejemplo {S1} o {S2}. Nunca escribas «Persona 1» ni «el hablante», y no adivines nombres para las marcas. A otras personas que solo se mencionan en la conversación sí las nombras como se dijo.\n"
        +"4. La transcripción puede traer errores de palabras o de quién habla: interpreta con criterio y no los copies.\n"
        +"5. Responde SOLO con un objeto JSON válido, sin texto antes ni después, con esta forma:\n"
        +"{\"title\":\"…\",\"summary\":\"…\",\"decisions\":[\"…\"],\"tasks\":[{\"text\":\"…\",\"who\":\"{S1}\",\"when\":\"…\"}],\"quotes\":[{\"t\":192,\"text\":\"…\",\"who\":\"{S2}\"}],\"tags\":[\"…\"]}\n"
        +"- title: título breve y específico del tema, de máximo 60 caracteres, sin fecha, sin marcas como {S1} y sin la palabra «Grabación».\n"
        +"- summary: de 1 a 5 oraciones con lo esencial.\n"
        +"- decisions: lo que se decidió o acordó. Lista vacía si no hubo decisiones.\n"
        +"- tasks: lo que alguien quedó de hacer. text empieza con un verbo; who es la marca de quien lo hará ({S1}), el nombre si es otra persona mencionada, o \"\" si no se sabe; when es el plazo tal como se dijo («el viernes», «antes del 15») o \"\". Lista vacía si no hay tareas.\n"
        +"- quotes: hasta 5 frases textuales que valga la pena recordar, copiadas tal cual; t es el segundo en que empieza su línea (número, según la hora [mm:ss]); who es la marca de quien la dijo o \"\".\n"
        +"- tags: de 3 a 6 etiquetas temáticas en minúsculas, de una palabra cada una, sin «#».";

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
    static final class Discarded extends HttpApi.UserAction{Discarded(){super("La transcripción cambió mientras se armaba la nota. Vuelve a armarla cuando la nueva versión esté lista.");}}
    static JSONObject load(Context c,String id){try{return exists(c,id)?FilesStore.read(FilesStore.file(c,id,".note.json")):null;}catch(Exception e){return null;}}
    /** ¿Hay clave para el proveedor elegido? (OpenAI usa la misma clave de transcribir). */
    static boolean canGenerate(Context c){Settings s=new Settings(c);return s.noteProvider().equals("anthropic")?s.hasAnthropicKey():s.hasOpenAiKey();}
    /** "openai" o "anthropic". */
    static String provider(Settings s){return "anthropic".equals(s.noteProvider())?"anthropic":"openai";}
    static String defaultModel(String provider){return "anthropic".equals(provider)?ANTHROPIC_MODEL:OPENAI_MODEL;}
    /** Modelo efectivo: el de Ajustes si corresponde a ese proveedor; si no, el recomendado. */
    static String model(Settings s,String provider){
        String m=s.noteModel()==null?"":s.noteModel().trim();
        if(m.isEmpty()||!m.matches("[A-Za-z0-9._:-]{2,80}"))return defaultModel(provider);
        boolean claude=m.startsWith("claude");
        return "anthropic".equals(provider)==claude?m:defaultModel(provider);
    }
    static String service(String provider){return "anthropic".equals(provider)?"Claude":"OpenAI";}

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
        try{key="anthropic".equals(provider)?s.anthropicKey():s.openAiKey();}catch(Exception ignored){}
        if(key==null||key.isEmpty()){
            String why="anthropic".equals(provider)?"Falta tu clave de Claude para armar la nota. Agrégala en Ajustes.":"La nota usa tu clave de OpenAI. Configúrala en Ajustes.";
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
            if(!Transcript.exists(c,id))throw new HttpApi.UserAction("Esta grabación aún no tiene transcripción.");
            Transcript t=Transcript.load(c,id);
            if(t.data.optBoolean("demo")||FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction("El ejemplo no se envía a la IA.");
            if(t.segments().length()==0)throw new HttpApi.UserAction("No se detectó habla en esta grabación: no hay nada que resumir.");
            Recording latest=FilesStore.recording(c,id);if(latest!=null){r.title=latest.title;r.created=latest.created;r.duration=latest.duration;}
            Prompt prompt=prompt(t,r,Marks.list(c,id));
            log(c,id,"Armando la nota con "+service);Diagnostics.event("note_started",id,"provider",provider,"model",model);
            // La respuesta llega completa al final: la espera crece con el largo del audio (2 min como mínimo, 5 como máximo).
            http.readTimeoutMs=(int)Math.min(300_000,Math.max(120_000,60_000+r.duration/60_000*2_000));
            http.onUploaded=null;http.onEvent=null;http.onProgress=null;if(http.jobId==null)http.jobId=id;
            Answer answer="anthropic".equals(provider)?anthropic(http,key,model,prompt):openai(http,key,model,prompt);
            JSONObject note=normalize(parseAnswer(answer.text),prompt.tokens,r.duration);
            note.put("version",1).put("provider",provider).put("model",model).put("createdAt",System.currentTimeMillis()).put("speakers",new JSONObject(prompt.tokens));
            if(answer.usage!=null){note.put("usage",answer.usage);double cost=usd(model,answer.usage.optLong("input_tokens"),answer.usage.optLong("output_tokens"));if(cost>=0)note.put("costUsd",cost);}
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
            log(c,id,"Nota lista · tardó "+Ui.humanDuration(took)+(applied?" · le puso título":""));
            Diagnostics.event("note_ready",id,"provider",provider,"model",model,"elapsed_ms",took,"count",note.getJSONArray("tasks").length(),"result",applied?"title_applied":"title_suggested");
            LocalStorage.enqueue(c,id);
        }catch(Exception e){
            // El estado de la nota solo se toca si sigue siendo el de ESTE pedido (no el de una versión nueva).
            if(e instanceof Discarded){
                try{FilesStore.update(c,id,st->{if(ours(st,started,attempt)){st.remove("noteState");st.remove("noteError");}});}catch(Exception ignored){}
                log(c,id,"La nota se descartó: la transcripción cambió mientras se armaba");Diagnostics.event("note_discarded",id);throw e;
            }
            boolean cancelled=http.cancelled||(e instanceof InterruptedIOException&&!(e instanceof java.net.SocketTimeoutException));
            if(cancelled){try{FilesStore.update(c,id,st->{if(ours(st,started,attempt)){st.remove("noteState");st.remove("noteError");}});}catch(Exception ignored){}Diagnostics.event("note_cancelled",id);throw e;}
            String why=friendly(e,service);
            try{FilesStore.update(c,id,st->{if(ours(st,started,attempt))st.put("noteState","failed").put("noteError",why);});}catch(Exception ignored){}
            log(c,id,"No se pudo armar la nota: "+why);
            Diagnostics.event("note_failed",id,"provider",provider,"model",model,"error_class",e.getClass().getSimpleName(),"reason",HttpApi.safeReason(e));
            throw e;
        }finally{http.readTimeoutMs=timeout;http.onUploaded=uploaded;http.onEvent=events;http.onProgress=progress;http.jobId=job;}
    }
    /** ¿El estado sigue siendo el de este pedido? Misma versión de la transcripción y la misma hora de inicio de la nota. */
    private static boolean ours(JSONObject st,long started,int attempt){return st.optInt("attempt",0)==attempt&&st.optLong("noteStartedAt",0)==started;}
    private static void failed(Context c,String id,String why){try{FilesStore.update(c,id,st->st.put("noteState","failed").put("noteError",why));}catch(Exception ignored){}}
    /** Texto para la persona (sin datos técnicos ni contenido). */
    static String friendly(Exception e,String service){
        if(e instanceof HttpApi.UserAction||e instanceof BadAnswer)return e.getMessage();
        if(e instanceof java.net.SocketTimeoutException)return service+" tardó demasiado en responder. Vuelve a intentarlo.";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException||e instanceof java.net.NoRouteToHostException)return "Sin conexión con "+service+". Vuelve a intentarlo cuando tengas internet.";
        String m=e.getMessage();if(e instanceof IOException&&m!=null&&m.startsWith(service))return m;
        return "No se pudo armar la nota. Vuelve a intentarlo.";
    }
    /** Bitácora visible del proceso: agrega una línea SIN cambiar el estado (el titular sigue siendo el de la transcripción). */
    private static void log(Context c,String id,String message){
        try{FilesStore.update(c,id,st->{JSONArray log=st.optJSONArray("log");if(log==null)log=new JSONArray();log.put(new JSONObject().put("t",System.currentTimeMillis()).put("m",message));while(log.length()>80)log.remove(0);st.put("log",log);});}catch(Exception ignored){}
    }

    // ---------- Título sugerido ----------
    /** ¿El título sigue siendo el automático («Grabación 29 sept. · 16:05», con o sin la fecha ISO delante)? */
    static boolean isDefaultTitle(String title,long created){
        String t=title==null?"":title.trim();if(t.isEmpty()||t.equals("Audio recuperado · revisar"))return true;
        String auto=Recording.defaultTitle(created);if(t.equals(auto)||t.equals(Recording.withDate(auto,created)))return true;
        return t.matches("^(\\d{4}-\\d{2}-\\d{2} )?Grabación (\\d{1,2} \\S{2,6} · )?\\d{1,2}:\\d{2}$");
    }
    /** Pone el título sugerido solo si el actual es el automático; nunca pisa uno que escribió la persona. */
    private static boolean applyTitle(Context c,Recording r,String suggested){
        try{
            Recording latest=FilesStore.recording(c,r.id);if(latest==null||!isDefaultTitle(latest.title,latest.created))return false;
            latest.title=suggested;latest.save(c);r.title=latest.title;
            log(c,r.id,"Título puesto a partir de la nota (puedes cambiarlo)");Diagnostics.event("note_title_applied",r.id);return true;
        }catch(Exception e){return false;}
    }

    // ---------- Pedido ----------
    /** Texto que se envía y marca → id de voz (S1 → "A"). */
    static final class Prompt{final String text;final LinkedHashMap<String,String> tokens;final boolean diarized;Prompt(String text,LinkedHashMap<String,String> tokens,boolean diarized){this.text=text;this.tokens=tokens;this.diarized=diarized;}}

    /** Arma el pedido compacto: cabecera, momentos ★ y una línea por intervención «[mm:ss] {S1}: texto». */
    static Prompt prompt(Transcript t,Recording r,JSONArray marks)throws Exception{
        boolean diarized=t.diarized();JSONArray s=t.segments();
        LinkedHashMap<String,String> tokens=new LinkedHashMap<>();Map<String,String> byVoice=new HashMap<>();
        if(diarized)for(int i=0;i<s.length();i++){String v=s.getJSONObject(i).getString("speaker");if(!byVoice.containsKey(v)){String k="S"+(byVoice.size()+1);byVoice.put(v,k);tokens.put(k,v);}}
        StringBuilder b=new StringBuilder();
        String title=r.title==null?"":r.title.trim();
        b.append("Título actual: «").append(title).append("»").append(isDefaultTitle(title,r.created)?" (automático)":" (lo escribió la persona)").append('\n');
        b.append("Fecha: ").append(new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm",new Locale("es","CL")).format(new Date(r.created))).append('\n');
        b.append("Duración: ").append(Ui.humanDuration(r.duration)).append('\n');
        if(diarized){b.append("Personas que hablan: ");int n=0;for(String k:tokens.keySet())b.append(n++==0?"":", ").append('{').append(k).append('}');b.append('\n');}
        else b.append("En esta transcripción no se separaron las voces: deja who vacío (\"\") salvo que se nombre a alguien.\n");
        if(marks!=null&&marks.length()>0){
            b.append("\nMomentos que la persona marcó con ★ mientras grababa (dales importancia):\n");
            for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;String label=m.optString("label","").trim();b.append("★ ").append(Recording.time(m.optLong("t"))).append(label.isEmpty()?"":" "+label).append('\n');}
        }
        b.append("\nTranscripción:\n");int header=b.length();
        for(int i=0;i<s.length();){
            JSONObject seg=s.getJSONObject(i);String voice=seg.getString("speaker");StringBuilder turn=new StringBuilder(seg.getString("text").trim());double end=seg.optDouble("end",seg.getDouble("start"));int j=i+1;
            if(diarized)while(j<s.length()){JSONObject next=s.getJSONObject(j);if(!next.getString("speaker").equals(voice)||next.getDouble("start")-end>Transcript.TURN_GAP_S)break;turn.append(' ').append(next.getString("text").trim());end=Math.max(end,next.optDouble("end",end));j++;}
            String line="["+Recording.time((long)(seg.getDouble("start")*1000))+"] "+(diarized?"{"+byVoice.get(voice)+"}: ":"")+turn.toString().replaceAll("\\s+"," ")+"\n";
            if(b.length()-header+line.length()>MAX_TRANSCRIPT_CHARS){b.append("[…] (transcripción recortada por largo)\n");break;}
            b.append(line);i=j;
        }
        return new Prompt(b.toString(),tokens,diarized);
    }

    /** Texto de la respuesta y uso de tokens normalizado {input_tokens, output_tokens}. */
    static final class Answer{final String text;final JSONObject usage;Answer(String text,JSONObject usage){this.text=text;this.usage=usage;}}

    /** Cuerpo del pedido a OpenAI (Chat Completions, respuesta JSON). */
    static JSONObject openaiBody(String model,Prompt prompt)throws JSONException{
        JSONObject body=new JSONObject().put("model",model)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",SYSTEM)).put(new JSONObject().put("role","user").put("content",prompt.text)))
            .put("response_format",new JSONObject().put("type","json_object")).put("max_completion_tokens",8000);
        // Solo los modelos que razonan aceptan reasoning_effort; «low» basta para ordenar una conversación.
        if(model.matches("^(gpt-[5-9]|o[1-9]).*"))body.put("reasoning_effort","low");
        return body;
    }
    static Answer openai(HttpApi http,String key,String model,Prompt prompt)throws Exception{
        HttpApi.Response res=http.request("POST",OPENAI_URL,key,"application/json",HttpApi.json(openaiBody(model,prompt)),null);
        require(res,"OpenAI",model);
        JSONObject json;try{json=res.json();}catch(Exception e){throw new BadAnswer("OpenAI respondió en un formato inesperado. Vuelve a intentarlo.");}
        JSONArray choices=json.optJSONArray("choices");if(choices==null||choices.length()==0)throw new BadAnswer("OpenAI no devolvió la nota. Vuelve a intentarlo.");
        JSONObject choice=choices.getJSONObject(0),message=choice.optJSONObject("message");
        if(message!=null&&message.has("refusal")&&!message.isNull("refusal")&&!message.optString("refusal").trim().isEmpty())throw new HttpApi.UserAction("OpenAI no quiso resumir esta grabación.");
        String text=message==null||message.isNull("content")?"":message.optString("content","");
        if(text.trim().isEmpty())throw new BadAnswer("length".equals(choice.optString("finish_reason"))?"La respuesta de OpenAI se cortó antes de terminar. Vuelve a intentarlo.":"OpenAI no devolvió la nota. Vuelve a intentarlo.");
        JSONObject u=json.optJSONObject("usage"),usage=u==null?null:new JSONObject().put("input_tokens",u.optLong("prompt_tokens")).put("output_tokens",u.optLong("completion_tokens"));
        return new Answer(text,usage);
    }

    /** Cuerpo del pedido a Anthropic (Messages API). */
    static JSONObject anthropicBody(String model,Prompt prompt)throws JSONException{
        JSONObject body=new JSONObject().put("model",model).put("max_tokens",16000).put("system",SYSTEM)
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
        JSONObject json;try{json=res.json();}catch(Exception e){throw new BadAnswer("Claude respondió en un formato inesperado. Vuelve a intentarlo.");}
        JSONArray content=json.optJSONArray("content");String text=null;
        // El primer bloque puede ser de razonamiento: se toma el primer bloque de texto.
        if(content!=null)for(int i=0;i<content.length()&&text==null;i++){JSONObject block=content.optJSONObject(i);if(block!=null&&"text".equals(block.optString("type"))&&!block.optString("text").trim().isEmpty())text=block.optString("text");}
        String stop=json.optString("stop_reason");
        if(text==null){if("refusal".equals(stop))throw new HttpApi.UserAction("Claude no quiso resumir esta grabación.");throw new BadAnswer("max_tokens".equals(stop)?"La respuesta de Claude se cortó antes de terminar. Vuelve a intentarlo.":"Claude no devolvió la nota. Vuelve a intentarlo.");}
        JSONObject u=json.optJSONObject("usage"),usage=u==null?null:new JSONObject().put("input_tokens",u.optLong("input_tokens")+u.optLong("cache_read_input_tokens")+u.optLong("cache_creation_input_tokens")).put("output_tokens",u.optLong("output_tokens"));
        return new Answer(text,usage);
    }

    /** Errores con palabras de la nota (no del audio); el resto, con la clasificación común de HttpApi. */
    static void require(HttpApi.Response res,String service,String model)throws Exception{
        if(res.code>=200&&res.code<300)return;
        String message="",type="";
        try{JSONObject e=res.json().optJSONObject("error");if(e!=null){message=e.optString("message").toLowerCase(Locale.ROOT);type=e.optString("type");}}catch(Exception ignored){}
        String why=null;boolean transientError=false;
        if(res.code==401)why="La clave de "+service+" no es válida o fue revocada. Revísala en Ajustes.";
        else if(res.code==529||"overloaded_error".equals(type)){why=service+" está saturado en este momento. Vuelve a intentarlo en unos minutos.";transientError=true;}
        else if(res.code==400||res.code==404||res.code==413||res.code==422){
            if(message.contains("credit balance")||message.contains("billing"))why="Tu cuenta de "+service+" no tiene saldo. Revisa la facturación de tu API.";
            else if(res.code==413||message.contains("context")||message.contains("too long")||message.contains("too many tokens")||(message.contains("maximum")&&message.contains("prompt")))why="La transcripción es demasiado larga para el modelo de la nota. Prueba con otro modelo.";
            else if(message.contains("model"))why="El modelo de la nota («"+model+"») no está disponible en tu cuenta de "+service+".";
            // Sin esto, HttpApi.require hablaría del audio, que aquí no se envía.
            else why=service+" rechazó el pedido de la nota (HTTP "+res.code+"). Vuelve a intentarlo o prueba otro modelo."+(res.requestId.isEmpty()?"":" · Ref: "+res.requestId);
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
        if(a<0||b<a)throw new BadAnswer("La IA respondió en un formato inesperado. Vuelve a intentarlo.");
        try{return new JSONObject(s.substring(a,b+1));}catch(JSONException e){throw new BadAnswer("La IA respondió en un formato inesperado. Vuelve a intentarlo.");}
    }
    private static final Pattern TOKEN=Pattern.compile("\\{\\s*[Ss](\\d{1,2})\\s*\\}|(?<![\\w{])S(\\d{1,2})(?![\\w}])");
    /** Deja las marcas como {S1} (acepta «{s1}», «{ S1 }» o «S1» suelto de una marca conocida). */
    static String tokens(String text,Map<String,String> known){
        if(text==null)return "";Matcher m=TOKEN.matcher(text);StringBuffer out=new StringBuffer();
        while(m.find()){String n=m.group(1)!=null?m.group(1):m.group(2);boolean bare=m.group(1)==null;String k="S"+Integer.parseInt(n);
            m.appendReplacement(out,Matcher.quoteReplacement(bare&&!known.containsKey(k)?m.group():"{"+k+"}"));}
        m.appendTail(out);return out.toString().trim();
    }
    /** Quién: "S1" si es una marca conocida; el nombre si es otra persona; "" si no se sabe. */
    static String who(Object value,Map<String,String> known){
        String v=value==null||value==JSONObject.NULL?"":String.valueOf(value).trim();
        Matcher m=Pattern.compile("^\\{?\\s*[Ss](\\d{1,2})\\s*\\}?$").matcher(v);
        if(m.matches()){String k="S"+Integer.parseInt(m.group(1));return known.containsKey(k)?k:"";}
        if(v.matches("(?i)(null|ninguno|nadie|desconocido|no se sabe|-|—)"))return "";
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
            String t=item.trim().toLowerCase(new Locale("es","CL")).replace("#","").replaceAll("[\\s_]+","-").replaceAll("[^\\p{L}\\p{N}-]","").replaceAll("-+","-").replaceAll("^-|-$","");
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
            JSONObject o=item instanceof JSONObject?(JSONObject)item:new JSONObject();String when=o.isNull("when")?"":o.optString("when","").trim();if(when.matches("(?i)null|-|—|no se sabe"))when="";
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
    /** Reemplaza {S1}, {S2}… por el nombre actual de cada voz. names acepta claves "S1" o "{S1}". */
    static String resolve(String text,Map<String,String> names){
        if(text==null)return "";if(names==null)names=Collections.emptyMap();
        Matcher m=Pattern.compile("\\{\\s*[Ss](\\d{1,2})\\s*\\}").matcher(text);StringBuffer out=new StringBuffer();
        while(m.find()){String k="S"+Integer.parseInt(m.group(1));String name=names.get(k);if(name==null)name=names.get("{"+k+"}");m.appendReplacement(out,Matcher.quoteReplacement(name==null||name.trim().isEmpty()?"alguien":name.trim()));}
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
     * dice esos tramos; si no se encuentra, «Persona N».
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
            out.put(k,name==null?"Persona "+k.replaceAll("\\D",""):name);
        }
        return out;
    }
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
    /** Título visible sin la fecha ISO delante (la fecha ya va en el frontmatter). */
    static String heading(String title){String t=title==null?"":title.trim();String h=t.replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s+","").trim();return h.isEmpty()?(t.isEmpty()?"Grabación":t):h;}

    static String markdown(Recording r,JSONObject note,Transcript t,JSONArray marks)throws Exception{
        Map<String,String> names=names(note,t);
        LinkedHashMap<String,String> voices=new LinkedHashMap<>();if(t!=null&&t.diarized())voices=t.speakers();
        StringBuilder md=new StringBuilder("---\n");
        md.append("fecha: ").append(Recording.isoDate(r.created)).append('\n');
        md.append("hora: ").append(q(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(r.created)))).append('\n');
        md.append("duracion: ").append(q(Ui.humanDuration(r.duration))).append('\n');
        LinkedHashSet<String> people=new LinkedHashSet<>(t!=null?voices.values():names.values());
        md.append("personas:");if(people.isEmpty())md.append(" []\n");else{md.append('\n');for(String p:people)md.append("  - ").append(q(p)).append('\n');}
        // "tags" es la propiedad que Obsidian reconoce como etiquetas (con otro nombre no aparecen en su panel de etiquetas).
        JSONArray tags=note==null?null:note.optJSONArray("tags");
        if(tags!=null&&tags.length()>0){md.append("tags:\n");for(int i=0;i<tags.length();i++)md.append("  - ").append(q(tags.optString(i))).append('\n');}
        md.append("fuente: ").append(q("Voz local")).append('\n');
        md.append("grabacion: ").append(q(r.title)).append('\n');
        if(note!=null)md.append("nota_ia: ").append(q(service(note.optString("provider"))+" · "+note.optString("model"))).append('\n');
        md.append("---\n\n# ").append(line(heading(r.title))).append("\n\n");
        if(note!=null){
            md.append("## Resumen\n\n").append(resolve(note.optString("summary").trim(),names)).append("\n\n");
            JSONArray decisions=note.optJSONArray("decisions");
            if(decisions!=null&&decisions.length()>0){md.append("## Decisiones\n\n");for(int i=0;i<decisions.length();i++)md.append("- ").append(line(resolve(decisions.optString(i),names))).append('\n');md.append('\n');}
            JSONArray tasks=note.optJSONArray("tasks");
            if(tasks!=null&&tasks.length()>0){md.append("## Tareas\n\n");
                for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null)continue;String who=whoName(task.optString("who"),names),when=task.optString("when").trim();
                    md.append(task.optBoolean("done")?"- [x] ":"- [ ] ").append(line(resolve(task.optString("text"),names))).append(who.isEmpty()?"":" — "+line(who)).append(when.isEmpty()?"":" · "+line(when)).append('\n');}
                md.append('\n');}
        }
        if(marks!=null&&marks.length()>0){md.append("## Momentos marcados\n\n");
            for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;String label=line(m.optString("label",""));md.append("- ★ ").append(Recording.time(m.optLong("t"))).append(label.isEmpty()?"":" "+label).append('\n');}
            md.append('\n');}
        if(note!=null){JSONArray quotes=note.optJSONArray("quotes");
            if(quotes!=null&&quotes.length()>0){md.append("## Frases clave\n\n");
                for(int i=0;i<quotes.length();i++){JSONObject qt=quotes.optJSONObject(i);if(qt==null)continue;String who=whoName(qt.optString("who"),names);
                    md.append("> «").append(line(resolve(qt.optString("text"),names))).append("»");
                    if(!who.isEmpty())md.append(" — ").append(line(who));
                    if(qt.has("t"))md.append(" (").append(Recording.time(qt.optLong("t")*1000)).append(")");
                    md.append("\n\n");}}}
        if(t!=null){md.append("## Transcripción\n\n");appendTranscript(md,t,voices,marks);}
        return md.toString().replaceAll("\\n{3,}","\n\n").trim()+"\n";
    }
    /** Intervenciones agrupadas «**Nombre** (mm:ss): texto»; las que tienen un momento ★ lo llevan delante. */
    private static void appendTranscript(StringBuilder md,Transcript t,Map<String,String> voices,JSONArray marks)throws Exception{
        boolean diarized=t.diarized();JSONArray s=t.segments();
        if(t.data.optBoolean("demo"))md.append("_Ejemplo de demostración: no proviene de una transcripción real._\n\n");
        if(diarized&&!t.reviewed()&&s.length()>0)md.append("_Voces separadas automáticamente: pueden tener errores._\n\n");
        if(s.length()==0){md.append("_No se detectó habla en este audio._\n");return;}
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
            if(diarized){String name=voices.get(who.get(i));md.append("**").append(line(name==null?"Persona":name)).append("** (").append(when).append("): ");}
            else if(times)md.append("(").append(when).append(") ");
            md.append(text.get(i)).append("\n\n");
        }
    }
}
