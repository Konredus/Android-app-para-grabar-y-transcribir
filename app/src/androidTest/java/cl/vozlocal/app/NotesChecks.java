package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pruebas de la parte «notes» de la 0.6.0 (ver docs/diseno/SPEC-0.6.md) y de la nota por OpenRouter de la 0.8.0
 * (docs/diseno/SPEC-0.8.md). Sin llamadas reales a APIs: HttpApi falso y claves de mentira. Corren en español
 * (Lang.override, como toda la prueba); los idiomas de la 0.9.0 (inglés y portugués) se prueban en languages() y en
 * router() (un pedido completo en inglés), y siempre vuelven a español.
 */
final class NotesChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Mismo check con otro nombre: dentro de un HttpApi falso, «check» sería HttpApi.check(). */
    static void expect(boolean ok,String text){check(ok,text);}
    private static JSONObject seg(String who,double a,double b,String text)throws JSONException{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}
    /** Conversación de prueba: Fran (B) abre, Konrad (A) responde, Fran cierra y «Mi voz» sin nombre. */
    private static Transcript conversation()throws JSONException{
        return new Transcript(new JSONObject().put("diarized",true).put("reviewed",true).put("names",new JSONObject().put("B","Fran").put("A","Konrad")).put("segments",new JSONArray()
            .put(seg("B",0,2,"Hola, partamos.")).put(seg("B",2.5,4,"con el presupuesto")).put(seg("A",5,9,"Yo envío la planilla el viernes."))
            .put(seg("B",10,12,"Perfecto.")).put(seg(Voices.ME,13,15,"Anoto."))));
    }
    private static JSONArray marks()throws JSONException{return new JSONArray().put(new JSONObject().put("t",7000).put("label","precio")).put(new JSONObject().put("t",12500).put("label",""));}
    /** Respuesta típica de la IA (con errores que la app debe limpiar). */
    private static JSONObject answer()throws JSONException{
        return new JSONObject().put("title","«2026-09-29 Presupuesto del segundo semestre.»").put("summary","S1 y {s2} revisaron el presupuesto.")
            .put("decisions",new JSONArray().put("Usar la planilla de {S2}").put(""))
            .put("tasks",new JSONArray().put(new JSONObject().put("text","Enviar la planilla").put("who","{S2}").put("when","el viernes"))
                .put(new JSONObject().put("text","Llamar a Juan").put("who","Pedro").put("when",JSONObject.NULL))
                .put(new JSONObject().put("text","Revisar las cifras").put("who","S9").put("when","")))
            .put("quotes",new JSONArray().put(new JSONObject().put("t","00:05").put("text","«Yo envío la planilla»").put("who","S2"))
                .put(new JSONObject().put("t",999).put("text","Cierre").put("who",""))
                .put(new JSONObject().put("t",1).put("text","Uno").put("who","")).put(new JSONObject().put("t",2).put("text","Dos").put("who",""))
                .put(new JSONObject().put("t",3).put("text","Tres").put("who","")).put(new JSONObject().put("t",4).put("text","Seis").put("who","")))
            .put("tags",new JSONArray().put("#Presupuesto").put("Equipo de trabajo").put("presupuesto").put("2026").put("finanzas").put("planilla").put("extra").put("otra"));
    }

    static void run(Context c,Recording source)throws Exception{
        pure();
        Settings settings=new Settings(c);boolean prefix=settings.datePrefix();String noteModel=settings.prefs.getString("noteModel",null);
        // Las pruebas de OpenRouter (0.8.0) cambian el proveedor, la IA de la nota y la clave de OpenRouter: al terminar,
        // todo vuelve a como estaba (la clave se repone cifrada, tal cual, sin leerla).
        String[] kept={"provider","noteProvider","openrouter_keyEncrypted","openrouter_keyIv"},before=new String[kept.length];
        for(int i=0;i<kept.length;i++)before[i]=settings.prefs.getString(kept[i],null);
        List<Recording> fixtures=new ArrayList<>();
        try{files(c,source,settings,fixtures);router(c,source,settings,fixtures);}
        finally{
            android.content.SharedPreferences.Editor edit=settings.prefs.edit().putBoolean("datePrefix",prefix);if(noteModel==null)edit.remove("noteModel");else edit.putString("noteModel",noteModel);
            for(int i=0;i<kept.length;i++){if(before[i]==null)edit.remove(kept[i]);else edit.putString(kept[i],before[i]);}
            edit.commit();
            for(Recording f:fixtures){File[] files=Recording.directory(c).listFiles((dir,name)->name.startsWith(f.id+"."));if(files!=null)for(File file:files)file.delete();}
            FilesStore.version.incrementAndGet();
        }
    }

    /** Lógica pura: pedido, lectura de la respuesta, nombres y Markdown. */
    static void pure()throws Exception{
        long created=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT).parse("2026-09-29 16:05").getTime();
        Recording r=new Recording(UUID.randomUUID().toString(),"2026-09-29 Reunión de presupuesto",created,60_000);
        Transcript t=conversation();
        // Pedido: marcas por orden de aparición, turnos agrupados, momentos ★ y cabecera.
        Notes.Prompt p=Notes.prompt(t,r,marks());
        expect(p.tokens.get("S1").equals("B")&&p.tokens.get("S2").equals("A")&&p.tokens.get("S3").equals(Voices.ME)&&p.tokens.size()==3,"Speaker tokens not numbered by appearance: "+p.tokens);
        expect(p.text.contains("[00:00] {S1}: Hola, partamos. con el presupuesto\n")&&p.text.contains("[00:05] {S2}: Yo envío la planilla el viernes.\n")&&p.text.contains("[00:10] {S1}: Perfecto.\n")&&p.text.contains("[00:13] {S3}: Anoto."),"Prompt turns wrong:\n"+p.text);
        expect(p.text.contains("★ 00:07 precio\n")&&p.text.contains("★ 00:12\n"),"Marks missing from prompt");
        expect(p.text.contains("Personas que hablan: {S1}, {S2}, {S3}")&&p.text.contains("Duración: 1 min")&&p.text.contains("29 de septiembre de 2026")&&p.text.contains("«2026-09-29 Reunión de presupuesto» (lo escribió la persona)"),"Prompt header wrong:\n"+p.text);
        expect(!p.text.contains("Fran")&&!p.text.contains("Konrad")&&!p.text.contains(Voices.ME),"Prompt leaked names or voice ids");
        Recording auto=new Recording(r.id,Recording.defaultTitle(created),created,60_000);
        expect(Notes.prompt(t,auto,null).text.contains("(automático)"),"Default title not flagged as automatic");
        Transcript plain=new Transcript(new JSONObject().put("diarized",false).put("segments",new JSONArray().put(seg("text",0,0,"Hola mundo, esto es un dictado."))));
        Notes.Prompt pp=Notes.prompt(plain,r,new JSONArray());
        expect(pp.tokens.isEmpty()&&!pp.text.contains("{S")&&pp.text.contains("[00:00] Hola mundo, esto es un dictado.")&&pp.text.contains("no se separaron las voces"),"Plain transcript prompt wrong:\n"+pp.text);
        // 0.9.0: el pedido va en el idioma de la app (aquí, español) y las instrucciones en español son las de siempre,
        // letra por letra: largo y hash de String (Java lo define igual en todas partes). Si se cambian a propósito, se
        // actualizan estos dos números.
        String instructions=Notes.system(Lang.ES);
        expect(p.lang.equals(Lang.ES)&&pp.lang.equals(Lang.ES)&&instructions.length()==1887&&instructions.hashCode()==-2044384809
            &&instructions.startsWith("Tomas notas de las grabaciones de una persona en Chile")&&instructions.contains("1. Escribe en español neutro, claro y cercano, como lo leería alguien de Chile."),"Spanish note instructions changed");

        // Respuesta: JSON entre ``` o con texto alrededor; basura → error legible.
        expect(Notes.parseAnswer("Aquí va:\n```json\n{\"title\":\"Hola\"}\n```\n").optString("title").equals("Hola"),"Fenced JSON not parsed");
        expect(Notes.parseAnswer("{\"title\":\"Directo\"}").optString("title").equals("Directo"),"Plain JSON not parsed");
        boolean bad=false;try{Notes.parseAnswer("No puedo ayudar con eso.");}catch(Notes.BadAnswer e){bad=!e.getMessage().isEmpty();}expect(bad,"Garbage answer accepted");

        // Limpieza: título, marcas, quién, plazos, frases (máx. 5, t en segundos) y etiquetas (3–6, minúsculas).
        JSONObject n=Notes.normalize(answer(),p.tokens,r.duration);
        expect(n.getString("title").equals("Presupuesto del segundo semestre"),"Title not cleaned: "+n.getString("title"));
        expect(n.getString("summary").equals("{S1} y {S2} revisaron el presupuesto."),"Summary tokens not normalized: "+n.getString("summary"));
        expect(n.getJSONArray("decisions").length()==1&&n.getJSONArray("decisions").getString(0).equals("Usar la planilla de {S2}"),"Decisions wrong");
        JSONArray tasks=n.getJSONArray("tasks");
        expect(tasks.length()==3&&tasks.getJSONObject(0).getString("who").equals("S2")&&tasks.getJSONObject(0).getString("when").equals("el viernes")&&!tasks.getJSONObject(0).getBoolean("done"),"Task 1 wrong: "+tasks);
        expect(tasks.getJSONObject(1).getString("who").equals("Pedro")&&tasks.getJSONObject(1).getString("when").isEmpty()&&tasks.getJSONObject(2).getString("who").isEmpty(),"Task who/when wrong: "+tasks);
        JSONArray quotes=n.getJSONArray("quotes");
        expect(quotes.length()==5&&quotes.getJSONObject(0).getLong("t")==5&&quotes.getJSONObject(0).getString("text").equals("Yo envío la planilla")&&quotes.getJSONObject(0).getString("who").equals("S2"),"Quotes wrong: "+quotes);
        expect(quotes.getJSONObject(1).getLong("t")==60,"Quote time not clamped to the audio");
        JSONArray tags=n.getJSONArray("tags");
        expect(tags.length()==6&&tags.getString(0).equals("presupuesto")&&tags.getString(1).equals("equipo-de-trabajo")&&!tags.toString().contains("#")&&!tags.toString().contains("2026"),"Tags wrong: "+tags);
        expect(Notes.cleanTitle("Reunión con {S1} sobre el presupuesto anual del área comercial y los viajes").length()<=60&&!Notes.cleanTitle("Reunión con {S1}").contains("{"),"Long title or tokens kept");

        // Nombres actuales.
        Map<String,String> names=new HashMap<>();names.put("S1","Fran");names.put("{S2}","Konrad");
        expect(Notes.resolve("{S1} y {S2} acordaron; {S9} no.",names).equals("Fran y Konrad acordaron; alguien no.")&&Notes.resolve(null,names).isEmpty(),"resolve() wrong");
        JSONObject note=new JSONObject(n.toString()).put("provider","openai").put("model",Notes.OPENAI_MODEL).put("speakers",new JSONObject(p.tokens));
        LinkedHashMap<String,String> current=Notes.names(note,t);
        expect(current.get("S1").equals("Fran")&&current.get("S2").equals("Konrad")&&current.get("S3").equals("Persona 3"),"names() wrong: "+current);
        // Si una voz se unió a otra al corregir, su marca toma el nombre de la voz que hoy dice esos tramos.
        Transcript merged=conversation();merged.merge("A","B");note.getJSONObject("speakers").put("S4","Z");
        LinkedHashMap<String,String> after=Notes.names(note,merged);
        expect(after.get("S2").equals("Fran")&&after.get("S4").equals("Persona 4"),"Merged or missing voice not resolved: "+after);
        note.getJSONObject("speakers").remove("S4");

        // Markdown para Obsidian: frontmatter, secciones en orden, tareas marcables y transcripción con nombres.
        String md=Notes.markdown(r,note,t,marks());
        expect(md.startsWith("---\nfecha: 2026-09-29\nhora: \"16:05\"\nduracion: \"1 min\"\npersonas:\n  - \"Fran\"\n  - \"Konrad\"\n  - \"Persona 3\"\ntags:\n  - \"presupuesto\"\n"),"Frontmatter wrong:\n"+md);
        expect(md.contains("fuente: \"Verbapp\"\n")&&md.contains("grabacion: \"2026-09-29 Reunión de presupuesto\"\n")&&md.contains("\n---\n\n# Reunión de presupuesto\n"),"Frontmatter tail or heading wrong:\n"+md);
        expect(md.contains("## Resumen\n\nFran y Konrad revisaron el presupuesto.\n")&&md.contains("## Decisiones\n\n- Usar la planilla de Konrad\n"),"Summary/decisions wrong:\n"+md);
        expect(md.contains("## Tareas\n\n- [ ] Enviar la planilla — Konrad · el viernes\n- [ ] Llamar a Juan — Pedro\n- [ ] Revisar las cifras\n"),"Tasks wrong:\n"+md);
        expect(md.contains("## Momentos marcados\n\n- ★ 00:07 precio\n- ★ 00:12\n"),"Marks section wrong:\n"+md);
        expect(md.contains("## Frases clave\n\n> «Yo envío la planilla» — Konrad (00:05)\n"),"Quotes section wrong:\n"+md);
        expect(md.contains("## Transcripción\n\n**Fran** (00:00): Hola, partamos. con el presupuesto\n\n★ **Konrad** (00:05): Yo envío la planilla el viernes.\n\n★ **Fran** (00:10): Perfecto.\n\n**Persona 3** (00:13): Anoto.\n"),"Transcript section wrong:\n"+md);
        expect(!md.contains("{S")&&!md.contains("pueden tener errores"),"Unresolved tokens or stale warning in markdown");
        int[] order={md.indexOf("## Resumen"),md.indexOf("## Decisiones"),md.indexOf("## Tareas"),md.indexOf("## Momentos marcados"),md.indexOf("## Frases clave"),md.indexOf("## Transcripción")};
        for(int i=1;i<order.length;i++)expect(order[i-1]>0&&order[i]>order[i-1],"Markdown sections out of order");
        note.getJSONArray("tasks").getJSONObject(0).put("done",true);
        expect(Notes.markdown(r,note,t,marks()).contains("- [x] Enviar la planilla"),"Done task not checked in markdown");
        String bare=Notes.markdown(r,null,t,marks());
        expect(bare.startsWith("---\nfecha: 2026-09-29\n")&&bare.contains("## Transcripción")&&bare.contains("## Momentos marcados")&&!bare.contains("## Resumen")&&!bare.contains("tags:")&&!bare.contains("nota_ia"),"Markdown without a note wrong:\n"+bare);
        String dictation=Notes.markdown(r,null,plain,null);
        expect(dictation.contains("personas: []")&&dictation.contains("## Transcripción\n\nHola mundo, esto es un dictado.\n"),"Plain markdown wrong:\n"+dictation);

        // Pedidos a cada proveedor (forma).
        JSONObject openai=Notes.openaiBody(Notes.OPENAI_MODEL,p);
        expect(openai.getString("model").equals("gpt-6-luna")&&openai.getJSONObject("response_format").getString("type").equals("json_object")&&openai.getString("reasoning_effort").equals("low"),"OpenAI body wrong: "+openai);
        JSONArray messages=openai.getJSONArray("messages");
        expect(messages.getJSONObject(0).getString("role").equals("system")&&messages.getJSONObject(0).getString("content").contains("JSON")&&messages.getJSONObject(1).getString("role").equals("user")&&messages.getJSONObject(1).getString("content").equals(p.text),"OpenAI messages wrong");
        expect(!Notes.openaiBody("gpt-4.1-mini",p).has("reasoning_effort"),"reasoning_effort sent to a non-reasoning model");
        JSONObject claude=Notes.anthropicBody(Notes.ANTHROPIC_MODEL,p);
        expect(claude.getString("model").equals("claude-sonnet-5-5")&&claude.getInt("max_tokens")>=4000&&claude.getString("system").equals(Notes.system(Lang.ES))&&claude.getJSONArray("messages").length()==1&&claude.getJSONArray("messages").getJSONObject(0).getString("role").equals("user")&&claude.getJSONObject("output_config").getString("effort").equals("medium"),"Anthropic body wrong: "+claude);
        expect(!Notes.anthropicBody("claude-haiku-4-5-20251001",p).has("output_config"),"Effort sent to Haiku");
        Map<String,String> headers=Notes.anthropicHeaders("ak-test");
        expect(headers.get("x-api-key").equals("ak-test")&&headers.get("anthropic-version").equals("2023-06-01")&&!headers.containsKey("Authorization"),"Anthropic headers wrong");

        // OpenRouter (0.8.0): mismo formato de chat, con modelo alias. Claude no recibe «reasoning»; los que razonan por
        // defecto, el mínimo. El modo simple (reintento tras un 400) lleva solo el modelo y los mensajes.
        expect(Notes.OPENROUTER_URL.equals("https://openrouter.ai/api/v1/chat/completions")&&Notes.OPENAI_URL.equals("https://api.openai.com/v1/chat/completions"),"Note endpoints changed");
        JSONObject routerBody=Notes.openrouterBody(Models.NOTE_DEFAULT,p,false);
        expect(routerBody.getString("model").equals("~anthropic/claude-sonnet-latest")&&routerBody.getJSONObject("response_format").getString("type").equals("json_object")&&routerBody.getInt("max_tokens")>=4000
            &&!routerBody.has("reasoning")&&!routerBody.has("reasoning_effort")&&!routerBody.has("max_completion_tokens"),"OpenRouter body wrong: "+routerBody.names());
        JSONArray routerMessages=routerBody.getJSONArray("messages");
        expect(routerMessages.length()==2&&routerMessages.getJSONObject(0).getString("role").equals("system")&&routerMessages.getJSONObject(0).getString("content").equals(Notes.system(Lang.ES))
            &&routerMessages.getJSONObject(1).getString("role").equals("user")&&routerMessages.getJSONObject(1).getString("content").equals(p.text),"OpenRouter messages wrong");
        expect(Notes.openrouterBody("~openai/gpt-luna-latest",p,false).getJSONObject("reasoning").getString("effort").equals("low")&&Notes.openrouterBody("~google/gemini-flash-latest",p,false).has("reasoning"),"Reasoning effort not lowered for models that reason by default");
        // El modo simple conserva el tope de salida (0.8.0, segunda ronda): sin max_tokens OpenRouter reserva el máximo del
        // modelo y, con poco saldo, responde un 402 falso. Solo deja fuera response_format y reasoning.
        JSONObject simpleBody=Notes.openrouterBody(Models.NOTE_DEFAULT,p,true);
        expect(simpleBody.length()==3&&simpleBody.getString("model").equals(Models.NOTE_DEFAULT)&&simpleBody.getJSONArray("messages").length()==2&&simpleBody.getInt("max_tokens")==routerBody.getInt("max_tokens"),"Simple OpenRouter body carries optional fields or lost max_tokens: "+simpleBody.names());
        Map<String,String> routerHeaders=Notes.openrouterHeaders();
        // Una sola fuente: los mismos encabezados con que se transcribe (OpenRouterClient.headers()).
        expect(routerHeaders.equals(OpenRouterClient.headers())&&"Verbapp".equals(routerHeaders.get("X-OpenRouter-Title"))&&routerHeaders.get("HTTP-Referer").startsWith("https://")&&!routerHeaders.containsKey("Authorization"),"OpenRouter headers wrong: "+routerHeaders);
        expect(Notes.service("openrouter").equals("OpenRouter")&&Notes.service("openai").equals("OpenAI")&&Notes.service("anthropic").equals("Claude")&&Notes.service("").equals("OpenAI"),"Service names wrong");
        expect(Notes.defaultModel("openrouter").equals(Models.NOTE_DEFAULT)&&Notes.defaultModel("openai").equals("gpt-6-luna")&&Notes.defaultModel("anthropic").equals("claude-sonnet-5-5"),"Default note models wrong");
        // La nota muestra el modelo que respondió (con un alias, la versión concreta) y el costo: real con OpenRouter, estimado si no.
        JSONObject routed=new JSONObject(n.toString()).put("provider","openrouter").put("model",Models.NOTE_DEFAULT).put("modelUsed","anthropic/claude-sonnet-5.5").put("costUsd",0.0042).put("costReal",true).put("speakers",new JSONObject(p.tokens));
        expect(Notes.modelShown(routed).equals("anthropic/claude-sonnet-5.5")&&Notes.modelShown(note).equals("gpt-6-luna")&&Notes.modelShown(null).isEmpty()&&Notes.modelShown(new JSONObject()).isEmpty(),"modelShown() wrong");
        expect(Notes.credit(routed).equals("Armada con OpenRouter · anthropic/claude-sonnet-5.5 · costó US$0,004"),"Note credit wrong: "+Notes.credit(routed));
        expect(Notes.credit(note).equals("Armada con OpenAI · gpt-6-luna")&&Notes.credit(new JSONObject(note.toString()).put("costUsd",0.0123)).equals("Armada con OpenAI · gpt-6-luna · ≈ US$0,012")
            &&Notes.credit(new JSONObject(note.toString()).put("costUsd",0.0002)).equals("Armada con OpenAI · gpt-6-luna · < US$0,001")&&Notes.credit(new JSONObject()).isEmpty()&&Notes.credit(null).isEmpty(),"Note credit for estimated costs wrong");
        expect(md.contains("nota_ia: \"OpenAI · gpt-6-luna\"\n")&&Notes.markdown(r,routed,t,marks()).contains("nota_ia: \"OpenRouter · anthropic/claude-sonnet-5.5\"\n"),"nota_ia must name the model that answered");

        // Título automático: solo el de la app (con o sin fecha), nunca uno escrito por la persona.
        expect(Notes.isDefaultTitle(Recording.defaultTitle(created),created)&&Notes.isDefaultTitle(Recording.withDate(Recording.defaultTitle(created),created),created)&&Notes.isDefaultTitle("2026-09-27 Grabación 16:05",created)&&Notes.isDefaultTitle("",created),"Default title not recognized");
        expect(!Notes.isDefaultTitle("Grabación con Fran",created)&&!Notes.isDefaultTitle("2026-09-29 Reunión",created)&&!Notes.isDefaultTitle("Reunión de presupuesto",created),"User title treated as automatic");

        // Nombre del .md: misma limpieza que el .txt y la fecha delante sin duplicarla.
        expect(TranscriptExport.noteFilename("Reunión: equipo",created).equals("2026-09-29 Reunión equipo.md"),"Note filename wrong: "+TranscriptExport.noteFilename("Reunión: equipo",created));
        expect(TranscriptExport.noteFilename("2026-09-20 Ya fechada.md",created).equals("2026-09-20 Ya fechada.md")&&TranscriptExport.markdownFilename("... /\\").equals("Transcripción.md"),"Note filename duplicated date/extension or lost fallback");
        expect(TranscriptExport.filename("Reunión.txt").equals("Reunión.txt")&&TranscriptExport.filename("Notas.md").equals("Notas.md.txt"),".txt filename behavior changed");
        expect(Notes.usd(Notes.OPENAI_MODEL,1_000_000,1_000_000)>0&&Notes.estimateUsd(Notes.ANTHROPIC_MODEL,52*60_000)<0.2&&Notes.usd("modelo-propio",1,1)<0,"Cost estimate wrong");

        // «Armando la nota…» solo mientras es reciente: si Android cerró la app a mitad, se puede reintentar.
        long now=System.currentTimeMillis();
        expect(Notes.working(new JSONObject().put("noteState","working").put("noteStartedAt",now-60_000)),"Recent note not reported as working");
        expect(!Notes.working(new JSONObject().put("noteState","working").put("noteStartedAt",now-Notes.WORKING_MAX_MS-1000))&&!Notes.working(new JSONObject().put("noteState","working")),"Stale «working» note would stick forever");
        expect(!Notes.working(new JSONObject().put("noteState","ready").put("noteStartedAt",now))&&!Notes.working(null),"Ready note reported as working");
        languages();
    }

    /**
     * Idiomas (0.9.0): con la app en inglés o en portugués, la IA recibe el pedido entero en ese idioma (instrucciones y
     * cabecera, nada en español) con el mismo formato JSON, y lo que la app muestra de la nota (pie, errores, bitácora,
     * Markdown) sale en ese idioma. Lo que devuelve la IA se lee igual en los tres. Al terminar, la app vuelve a español
     * pase lo que pase.
     */
    static void languages()throws Exception{
        long created=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT).parse("2026-09-29 16:05").getTime();
        Recording r=new Recording(UUID.randomUUID().toString(),"2026-09-29 Reunión de presupuesto",created,60_000);
        Transcript t=conversation();
        // Lo que escribe la IA cuando no se sabe quién o el plazo, en cualquiera de los tres idiomas (puede mezclarlos).
        Map<String,String> known=new HashMap<>();known.put("S1","B");
        for(String none:new String[]{"nadie","Desconocido","no se sabe","—","nobody","No one","unknown","N/A","not stated","ninguém","Nenhuma","desconhecido","NÃO SE SABE","não informado"})
            expect(Notes.who(none,known).isEmpty(),"«"+none+"» not read as nobody");
        expect(Notes.who("Pedro",known).equals("Pedro")&&Notes.who("Nadia",known).equals("Nadia")&&Notes.who("{s1}",known).equals("S1"),"A real name or a marker was dropped");
        JSONObject raw=new JSONObject().put("tasks",new JSONArray()
            .put(new JSONObject().put("text","Send the budget").put("who","nobody").put("when","unknown"))
            .put(new JSONObject().put("text","Enviar o orçamento").put("who","ninguém").put("when","não informado"))
            .put(new JSONObject().put("text","Llamar a Juan").put("who","Pedro").put("when","no se sabe"))
            .put(new JSONObject().put("text","Revisar las cifras").put("who","{S1}").put("when","el viernes")));
        JSONArray tasks=Notes.normalize(raw,known,60_000).getJSONArray("tasks");
        expect(tasks.getJSONObject(0).getString("who").isEmpty()&&tasks.getJSONObject(0).getString("when").isEmpty()&&tasks.getJSONObject(1).getString("who").isEmpty()&&tasks.getJSONObject(1).getString("when").isEmpty(),"English or Portuguese «nobody/unknown» kept: "+tasks);
        expect(tasks.getJSONObject(2).getString("who").equals("Pedro")&&tasks.getJSONObject(2).getString("when").isEmpty()&&tasks.getJSONObject(3).getString("who").equals("S1")&&tasks.getJSONObject(3).getString("when").equals("el viernes"),"Spanish owners or deadlines changed: "+tasks);

        Notes.Prompt es=Notes.prompt(t,r,marks());
        JSONObject note=Notes.normalize(answer(),es.tokens,r.duration).put("provider","openai").put("model",Notes.OPENAI_MODEL).put("speakers",new JSONObject(es.tokens));
        JSONObject routed=new JSONObject(note.toString()).put("provider","openrouter").put("model",Models.NOTE_DEFAULT).put("modelUsed","anthropic/claude-sonnet-5.5").put("costUsd",0.0042).put("costReal",true);
        Transcript unreviewed=new Transcript(new JSONObject(conversation().data.toString()).put("reviewed",false));
        Transcript plain=new Transcript(new JSONObject().put("diarized",false).put("segments",new JSONArray().put(seg("text",0,0,"Hola mundo, esto es un dictado."))));
        // Mismo formato JSON y mismos límites en los tres idiomas (normalize lee igual lo que vuelva).
        String shape="{\"title\":\"…\",\"summary\":\"…\",\"decisions\":[\"…\"],\"tasks\":[{\"text\":\"…\",\"who\":\"{S1}\",\"when\":\"…\"}],\"quotes\":[{\"t\":192,\"text\":\"…\",\"who\":\"{S2}\"}],\"tags\":[\"…\"]}\n";
        String[] spanish={"Reglas","Escribe","español","Responde","Usa solo","transcripción","Transcripción","Título actual","Fecha:","Duración:","Personas que hablan","Momentos que la persona","lo escribió","Chile","Grabación","Vuelve a"};
        try{
            for(String lang:new String[]{Lang.EN,Lang.PT}){
                Lang.override(lang);boolean en=Lang.EN.equals(lang);
                // Pedido: instrucciones y cabecera en el idioma de la app, sin nada en español (la transcripción va tal cual se dijo).
                Notes.Prompt p=Notes.prompt(t,r,marks());String system=Notes.system(p.lang);
                int cut=p.text.indexOf(en?"\nTranscript:\n":"\nTranscrição:\n");
                expect(p.lang.equals(lang)&&cut>0&&p.text.contains("[00:00] {S1}: Hola, partamos. con el presupuesto\n"),"Prompt not built in "+lang+":\n"+p.text);
                String head=p.text.substring(0,cut);
                expect(system.contains(en?"1. Write in natural, clear and friendly English.":"1. Escreva em português do Brasil natural, claro e próximo.")&&system.contains(shape)&&!system.equals(Notes.system(Lang.ES)),"The "+lang+" instructions do not ask for "+lang+" or lost the JSON shape");
                for(String field:new String[]{"- title: ","- summary: ","- decisions: ","- tasks: ","- quotes: ","- tags: "," 60 "," 5 "})expect(system.contains(field),"The "+lang+" instructions lost «"+field+"»");
                for(String word:spanish)expect(!system.contains(word)&&!head.contains(word),"The "+lang+" prompt has Spanish instructions («"+word+"»):\n"+head);
                expect(head.contains(en?"Current title: “2026-09-29 Reunión de presupuesto” (written by the person)":"Título atual: “2026-09-29 Reunión de presupuesto” (escrito pela pessoa)")
                    &&head.contains(en?"Date: Tuesday, September 29, 2026, 16:05":"Data: terça-feira, 29 de setembro de 2026, 16:05")&&head.contains((en?"Duration: ":"Duração: ")+Ui.humanDuration(60_000))
                    &&head.contains(en?"Speakers: {S1}, {S2}, {S3}":"Pessoas que falam: {S1}, {S2}, {S3}")&&head.contains("★ 00:07 precio\n")&&head.contains(en?"marked with ★ while recording":"marcou com ★ durante a gravação"),"The "+lang+" prompt header is wrong:\n"+head);
                expect(Notes.prompt(t,new Recording(r.id,Recording.defaultTitle(created),created,60_000),null).text.contains(en?" (automatic)\n":" (automático)\n")&&Notes.prompt(plain,r,new JSONArray()).text.contains(en?"Voices were not separated":"as vozes não foram separadas"),"Automatic title or plain transcript not explained in "+lang);
                // Las instrucciones van en el idioma del pedido aunque la app cambie de idioma antes de enviarlo.
                Lang.override(Lang.ES);
                expect(Notes.openrouterBody(Models.NOTE_DEFAULT,p,false).getJSONArray("messages").getJSONObject(0).getString("content").equals(system)&&Notes.anthropicBody(Notes.ANTHROPIC_MODEL,p).getString("system").equals(system)
                    &&Notes.openaiBody(Notes.OPENAI_MODEL,p).getJSONArray("messages").getJSONObject(0).getString("content").equals(system),"Instructions and request sent in different languages ("+lang+")");
                Lang.override(lang);

                // Lo que muestra la app: pie de la nota, nombres de respaldo y Markdown.
                expect(Notes.credit(routed).equals((en?"Created with OpenRouter · anthropic/claude-sonnet-5.5 · cost ":"Criada com OpenRouter · anthropic/claude-sonnet-5.5 · custou ")+Pricing.usd(0.0042)),"Note credit not translated: "+Notes.credit(routed));
                note.getJSONObject("speakers").put("S4","Z");Map<String,String> names=Notes.names(note,t);note.getJSONObject("speakers").remove("S4");
                expect(names.get("S3").equals(en?"Person 3":"Pessoa 3")&&names.get("S4").equals(en?"Person 4":"Pessoa 4")&&Notes.resolve("{S1} y {S9}",names).equals(en?"Fran y someone":"Fran y alguém")&&Notes.heading("").equals(en?"Recording":"Gravação"),"Fallback names not translated: "+names);
                String md=Notes.markdown(r,note,t,marks());
                expect(md.startsWith(en?"---\ndate: 2026-09-29\ntime: \"16:05\"\nduration: ":"---\ndata: 2026-09-29\nhora: \"16:05\"\nduracao: ")
                    &&md.contains(en?"\npeople:\n  - \"Fran\"\n  - \"Konrad\"\n  - \"Person 3\"\ntags:\n  - \"presupuesto\"\n":"\npessoas:\n  - \"Fran\"\n  - \"Konrad\"\n  - \"Pessoa 3\"\ntags:\n  - \"presupuesto\"\n")
                    &&md.contains(en?"\nsource: \"Verbapp\"\nrecording: \"2026-09-29 Reunión de presupuesto\"\nai_note: \"OpenAI · gpt-6-luna\"\n---\n":"\nfonte: \"Verbapp\"\ngravacao: \"2026-09-29 Reunión de presupuesto\"\nnota_ia: \"OpenAI · gpt-6-luna\"\n---\n"),"Markdown properties not translated:\n"+md);
                int at=0;
                for(String section:en?new String[]{"## Summary\n","## Decisions\n","## Tasks\n","## Marked moments\n","## Key quotes\n","## Transcript\n"}:new String[]{"## Resumo\n","## Decisões\n","## Tarefas\n","## Momentos marcados\n","## Frases-chave\n","## Transcrição\n"}){
                    int i=md.indexOf(section);expect(i>at,"Markdown section missing or out of order in "+lang+": "+section+"\n"+md);at=i;}
                expect(md.contains("\n> “Yo envío la planilla” — Konrad (00:05)\n")&&md.contains(en?"\n**Person 3** (00:13): Anoto.\n":"\n**Pessoa 3** (00:13): Anoto.\n")&&!md.contains("## Resumen")&&!md.contains("fecha:")&&!md.contains("«"),"Markdown body not translated:\n"+md);
                expect(Notes.markdown(r,null,unreviewed,null).contains(en?"\n_Voices separated automatically: they may contain errors._\n":"\n_Vozes separadas automaticamente: podem ter erros._\n"),"Automatic voices notice not translated in "+lang);

                // Errores: solo el de la clave habla de la clave (y termina con key_fix_in_settings, que la bienvenida quita).
                String fix=Lang.str(R.string.key_fix_in_settings),key=rejected(401,"{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}");
                expect(key.equals(en?"Your OpenRouter key is invalid or has been revoked. Check it in Settings.":"Sua chave do OpenRouter não é válida ou foi revogada. Confira em Ajustes.")
                    &&key.endsWith(" "+fix)&&StatusText.aboutKey(key)&&StatusText.withoutSettingsHint(key).equals(key.substring(0,key.length()-fix.length()-1)),"401 not explained as a key problem in "+lang+": "+key);
                String broke=rejected(402,"{\"error\":{\"code\":402,\"message\":\"Insufficient credits\"}}"),policy=rejected(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found matching your data policy\"}}"),
                    gone=rejected(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found for anthropic/claude-sonnet-latest.\"}}"),tooLong=rejected(400,"{\"error\":{\"code\":400,\"message\":\"This endpoint's maximum context length is 200000 tokens.\"}}"),
                    other=rejected(422,"{\"error\":{\"code\":422,\"message\":\"Unprocessable\"}}");
                expect(broke.contains("openrouter.ai")&&broke.contains(en?"credits":"créditos")&&policy.contains("Settings → Privacy")&&policy.contains(en?"privacy":"privacidade")
                    &&gone.contains(en?"is no longer available on OpenRouter":"não está mais disponível no OpenRouter")&&gone.contains("~anthropic/claude-sonnet-latest")&&tooLong.contains(en?"too long":"longa demais")&&other.contains("(HTTP 422)"),"API errors not translated in "+lang+": "+Arrays.asList(broke,policy,gone,tooLong,other));
                for(String m:new String[]{broke,policy,gone,tooLong,other})
                    for(String word:new String[]{"Vuelve","Elige","Prueba","Revísala","demasiado","pedido de la nota","saldo en"})expect(!StatusText.aboutKey(m)&&!m.contains(word),"Error in Spanish or mistaken for a key problem in "+lang+": "+m);
                expect(Notes.friendly(new java.net.SocketTimeoutException(),"OpenRouter").equals(en?"OpenRouter took too long to respond. Try again.":"OpenRouter demorou demais para responder. Tente novamente.")
                    &&Notes.friendly(new IOException("x"),"OpenRouter").equals(en?"Couldn't create the note. Try again.":"Não foi possível criar a nota. Tente novamente.")
                    &&Notes.friendly(new IOException("O OpenRouter está temporariamente indisponível (503)."),"OpenRouter").startsWith("O OpenRouter"),"friendly() not translated in "+lang);
                boolean unreadable=false;try{Notes.parseAnswer("nope");}catch(Notes.BadAnswer e){unreadable=e.getMessage().startsWith(en?"The AI replied":"A IA respondeu");}
                expect(unreadable&&new Notes.Discarded().getMessage().startsWith(en?"The transcript changed":"A transcrição mudou"),"Unreadable or discarded note messages not translated in "+lang);
            }
        }finally{Lang.override(Lang.ES);}
        expect(Lang.ES.equals(Lang.current())&&Notes.prompt(t,r,marks()).lang.equals(Lang.ES),"The language was not restored to Spanish");
    }
    /** El mensaje con que Notes.require rechaza esta respuesta de OpenRouter ("" si la deja pasar). */
    private static String rejected(int code,String body){try{Notes.require(new HttpApi.Response(code,body,null),HttpApi.OPENROUTER,Models.NOTE_DEFAULT);return "";}catch(Exception e){return e.getMessage();}}
    /** Respuesta de Chat Completions con la nota de prueba. */
    private static String openAiReply(String title)throws JSONException{
        JSONObject content=answer().put("title",title);
        return new JSONObject().put("id","chatcmpl-test").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason","stop")
            .put("message",new JSONObject().put("role","assistant").put("content",content.toString()).put("refusal",JSONObject.NULL))))
            .put("usage",new JSONObject().put("prompt_tokens",1000).put("completion_tokens",200)).toString();
    }

    /** Con archivos: generar con cada proveedor (HttpApi falso), título automático, tareas, 0-Inbox y borrar. */
    static void files(Context c,Recording source,Settings settings,List<Recording> fixtures)throws Exception{
        settings.prefs.edit().putBoolean("datePrefix",true).putString("noteModel","claude-opus-5-5").commit();
        expect(Notes.model(settings,"openai").equals(Notes.OPENAI_MODEL)&&Notes.model(settings,"anthropic").equals("claude-opus-5-5"),"Note model override misapplied");
        settings.prefs.edit().remove("noteModel").commit();
        expect(Notes.model(settings,"anthropic").equals(Notes.ANTHROPIC_MODEL),"Default Anthropic model not used");

        long now=System.currentTimeMillis();
        Recording a=fixture(c,source,Recording.defaultTitle(now),now,fixtures);
        String suggestion="Presupuesto del segundo semestre";
        // OpenAI: URL, clave, JSON pedido y estado «working» mientras espera.
        int[] calls={0};
        HttpApi fakeOpenAi=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            calls[0]++;
            expect(method.equals("POST")&&url.equals("https://api.openai.com/v1/chat/completions")&&"sk-test-notes".equals(token)&&"application/json".equals(type)&&extra==null,"OpenAI request line wrong");
            expect(FilesStore.state(c,a.id).optString("noteState").equals("working"),"noteState not working during the request");
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);expect(out.size()==body.length(),"Body length mismatch");
            JSONObject sent=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            expect(sent.getString("model").equals("gpt-6-luna")&&sent.getJSONObject("response_format").getString("type").equals("json_object"),"OpenAI body fields wrong");
            String user=sent.getJSONArray("messages").getJSONObject(1).getString("content");
            expect(user.contains("{S1}: Hola, partamos.")&&user.contains("★ 00:07 precio")&&user.contains("(automático)"),"OpenAI prompt missing tokens, marks or title hint");
            JSONObject content=answer().put("title",suggestion);
            JSONObject reply=new JSONObject().put("id","chatcmpl-test").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason","stop")
                .put("message",new JSONObject().put("role","assistant").put("content",content.toString()).put("refusal",JSONObject.NULL))))
                .put("usage",new JSONObject().put("prompt_tokens",1000).put("completion_tokens",200).put("total_tokens",1200));
            return new Response(200,reply.toString(),null);
        }};
        fakeOpenAi.readTimeoutMs=1234;
        Notes.generate(c,a,fakeOpenAi,"openai",Notes.OPENAI_MODEL,"sk-test-notes");
        expect(calls[0]==1&&fakeOpenAi.readTimeoutMs==1234,"OpenAI called more than once or timeout not restored");
        JSONObject note=Notes.load(c,a.id);
        expect(note!=null&&note.getInt("version")==1&&note.getString("provider").equals("openai")&&note.getString("model").equals("gpt-6-luna")&&note.getJSONObject("speakers").getString("S1").equals("B")&&note.getJSONObject("usage").getLong("input_tokens")==1000&&note.getDouble("costUsd")>0&&note.getLong("createdAt")>0,"Saved note wrong: "+note);
        // OpenAI directo sigue igual en la 0.8.0: no informa costo real ni (en esta respuesta) el modelo que respondió.
        expect(!note.has("modelUsed")&&!note.has("costReal")&&Notes.credit(note).equals("Armada con OpenAI · gpt-6-luna · < US$0,001"),"OpenAI note gained OpenRouter-only fields: "+Notes.credit(note));
        JSONObject st=FilesStore.state(c,a.id);
        expect(st.optString("noteState").equals("ready")&&!st.has("noteError")&&st.optString("suggestedTitle").equals(suggestion),"Note state wrong: "+st);
        Recording afterA=FilesStore.recording(c,a.id);
        expect(afterA.title.equals(Recording.withDate(suggestion,a.created)),"Suggested title not applied over the automatic one: "+afterA.title);
        expect(Notes.markdown(c,afterA).contains("\n# "+suggestion+"\n"),"Markdown lost the applied title");

        // Tareas: marcar persiste (y un índice inválido no rompe nada).
        Notes.setTaskDone(c,a.id,0,true);Notes.setTaskDone(c,a.id,99,true);
        expect(Notes.load(c,a.id).getJSONArray("tasks").getJSONObject(0).getBoolean("done")&&!Notes.load(c,a.id).getJSONArray("tasks").getJSONObject(1).getBoolean("done"),"setTaskDone not persisted");
        expect(Notes.markdown(c,afterA).contains("- [x] Enviar la planilla — Konrad · el viernes"),"Checked task missing in markdown");

        // 0-Inbox: al día → sin cambios; momentos, tipo o archivos posteriores → «Actualizar».
        String sig=Inbox.signature(Marks.list(c,a.id));long future=System.currentTimeMillis()+60_000;
        FilesStore.update(c,a.id,s->s.put("inboxAt",future).put("inboxKind","md").put("inboxTitle",afterA.title).put("inboxMarks",sig).put("inboxUri","content://example/doc"));
        expect(!Inbox.outdated(c,a.id)&&Inbox.savedKind(c,a.id).equals("md")&&Inbox.savedAt(c,a.id)==future,"Fresh inbox save reported as outdated");
        FilesStore.update(c,a.id,s->s.put("inboxMarks","otro"));expect(Inbox.outdated(c,a.id),"New marks not detected");
        FilesStore.update(c,a.id,s->s.put("inboxMarks",sig).put("inboxTitle","Otro título"));expect(Inbox.outdated(c,a.id),"Renamed recording not detected");
        FilesStore.update(c,a.id,s->s.put("inboxTitle",afterA.title).put("inboxKind","txt"));expect(Inbox.outdated(c,a.id),"Note created after a .txt save not detected");
        FilesStore.update(c,a.id,s->s.put("inboxKind","md").put("inboxAt",1L));expect(Inbox.outdated(c,a.id),"Edited transcript/note not detected");

        // Claude: sin Authorization, clave en x-api-key, primer bloque de texto aunque antes venga el razonamiento.
        Recording b=fixture(c,source,"Planificación trimestral",now,fixtures);String userTitle=FilesStore.recording(c,b.id).title;
        HttpApi fakeClaude=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            expect(method.equals("POST")&&url.equals("https://api.anthropic.com/v1/messages")&&token!=null&&token.isEmpty()&&"application/json".equals(type),"Anthropic request line wrong (token must be empty: no Authorization)");
            expect(extra!=null&&"ak-test-notes".equals(extra.get("x-api-key"))&&"2023-06-01".equals(extra.get("anthropic-version"))&&!extra.containsKey("Authorization"),"Anthropic headers wrong: "+extra);
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);JSONObject sent=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            expect(sent.getString("model").equals("claude-sonnet-5-5")&&sent.getString("system").equals(Notes.system(Lang.ES))&&sent.getInt("max_tokens")>0&&sent.getJSONArray("messages").getJSONObject(0).getString("content").contains("(lo escribió la persona)"),"Anthropic body wrong");
            JSONObject reply=new JSONObject().put("type","message").put("stop_reason","end_turn")
                .put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","…")).put(new JSONObject().put("type","text").put("text","```json\n"+answer().put("title","Metas del trimestre")+"\n```")))
                .put("usage",new JSONObject().put("input_tokens",900).put("output_tokens",300));
            return new Response(200,reply.toString(),null);
        }};
        Notes.generate(c,b,fakeClaude,"anthropic",Notes.ANTHROPIC_MODEL,"ak-test-notes");
        JSONObject noteB=Notes.load(c,b.id);
        expect(noteB!=null&&noteB.getString("provider").equals("anthropic")&&noteB.getString("summary").equals("{S1} y {S2} revisaron el presupuesto.")&&noteB.getJSONObject("usage").getLong("output_tokens")==300,"Anthropic note wrong: "+noteB);
        expect(FilesStore.recording(c,b.id).title.equals(userTitle)&&FilesStore.state(c,b.id).optString("suggestedTitle").equals("Metas del trimestre"),"User title overwritten or suggestion missing");

        // 0-Inbox: un documento que otra grabación guardó después (el proveedor reutilizó su id) no se pisa.
        String shared="content://example/doc";long later=System.currentTimeMillis();
        FilesStore.update(c,b.id,s->s.put("inboxUri",shared).put("inboxAt",later));
        expect(Inbox.claimedByOther(c,a.id,shared)&&!Inbox.claimedByOther(c,b.id,shared)&&!Inbox.claimedByOther(c,a.id,""),"A document saved later by another recording would be overwritten");
        FilesStore.update(c,b.id,s->{s.remove("inboxUri");s.remove("inboxAt");});

        // «Volver a transcribir» empezó mientras se armaba la nota: la respuesta (de la versión anterior) no se guarda
        // y el estado de la nota de la nueva versión no se toca.
        Recording late=fixture(c,source,"Nota que llega tarde",now,fixtures);
        HttpApi racing=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            FilesStore.update(c,late.id,s->s.put("attempt",s.optInt("attempt",0)+1).put("noteState","working").put("noteStartedAt",12345L));
            return new Response(200,openAiReply("Tarde"),null);
        }};
        boolean discarded=false;try{Notes.generate(c,late,racing,"openai",Notes.OPENAI_MODEL,"sk-test-notes");}catch(Notes.Discarded e){discarded=true;}
        JSONObject lateState=FilesStore.state(c,late.id);
        expect(discarded&&!Notes.exists(c,late.id)&&!lateState.has("suggestedTitle"),"A note for the previous version was saved over the new one");
        expect(lateState.optString("noteState").equals("working")&&lateState.optLong("noteStartedAt")==12345L,"A late note changed the note state of the new version: "+lateState);

        // Errores: clave inválida y respuesta ilegible → «failed» con un texto claro; la nota anterior se conserva.
        HttpApi denied=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){return new Response(401,"{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}",null);}};
        boolean userAction=false;try{Notes.generate(c,b,denied,"anthropic",Notes.ANTHROPIC_MODEL,"ak-test-notes");}catch(HttpApi.UserAction e){userAction=true;}
        JSONObject failed=FilesStore.state(c,b.id);
        expect(userAction&&failed.optString("noteState").equals("failed")&&failed.optString("noteError").contains("clave de Claude")&&Notes.exists(c,b.id),"401 not reported as a key problem: "+failed);
        HttpApi garbage=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            return new Response(200,new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content","Lo siento")))).toString(),null);}};
        boolean unreadable=false;try{Notes.generate(c,b,garbage,"openai",Notes.OPENAI_MODEL,"sk-test-notes");}catch(Notes.BadAnswer e){unreadable=true;}
        expect(unreadable&&FilesStore.state(c,b.id).optString("noteState").equals("failed"),"Unreadable answer not reported");

        // Borrar la nota limpia su estado.
        Notes.delete(c,a.id);JSONObject cleared=FilesStore.state(c,a.id);
        expect(!Notes.exists(c,a.id)&&!cleared.has("noteState")&&!cleared.has("suggestedTitle")&&Notes.load(c,a.id)==null,"Note delete left state behind");
        expect(Inbox.outdated(c,a.id),"Deleted note after an .md save not detected");
    }

    /** Respuesta de OpenRouter (Chat Completions): además trae el modelo que respondió y el costo real en usage.cost. */
    private static String routerReply(String title,String model,double cost)throws JSONException{
        JSONObject usage=new JSONObject().put("prompt_tokens",1500).put("completion_tokens",300).put("total_tokens",1800);if(cost>=0)usage.put("cost",cost);
        JSONObject reply=new JSONObject().put("id","gen-test").put("object","chat.completion").put("provider","Anthropic").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason","stop")
            .put("message",new JSONObject().put("role","assistant").put("content",answer().put("title",title).toString()).put("refusal",JSONObject.NULL)))).put("usage",usage);
        if(model!=null)reply.put("model",model);
        return reply.toString();
    }
    private static boolean logged(Context c,String id,String text){
        JSONArray log=FilesStore.state(c,id).optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null&&e.optString("m").contains(text))return true;}
        return false;
    }
    /** Clave de mentira: nunca sale del teléfono (el HttpApi es falso) y no debe aparecer en el pedido, el estado ni la nota. */
    private static final String ROUTER_KEY="sk-or-test-this-is-not-a-real-key";

    /**
     * Nota por OpenRouter (0.8.0), sin red: qué IA se usa por defecto, modelo alias, pedido (URL, clave, encabezados,
     * cuerpo), modelo que respondió y costo real guardados, y errores (402, 404, 401, 400 con reintento simple, error
     * dentro de un 200). run() deja las preferencias y la clave como estaban.
     */
    static void router(Context c,Recording source,Settings settings,List<Recording> fixtures)throws Exception{
        // IA de la nota: desde la segunda ronda de la 0.8.0 (SPEC-0.8b, decisión 3) la nota va SIEMPRE por OpenRouter. Ni el
        // proveedor guardado ni una «noteProvider» que haya quedado de antes (OpenAI, Claude, un valor raro) la desvían: así
        // una clave vieja de OpenAI o de Anthropic nunca se vuelve a enviar. (En la primera ronda esto respetaba la elección.)
        String[][] cases={{"openrouter",null},{"openai",null},{"custom",null},{"openrouter","openai"},{"openrouter","anthropic"},{"openrouter","otra-cosa"},{"openai","openrouter"},{"openai","anthropic"}};
        for(String[] k:cases){
            android.content.SharedPreferences.Editor e=settings.prefs.edit().putString("provider",k[0]);if(k[1]==null)e.remove("noteProvider");else e.putString("noteProvider",k[1]);e.commit();
            expect(Notes.provider(settings).equals("openrouter"),"The note must always go through OpenRouter (provider "+k[0]+", noteProvider "+k[1]+")");
        }
        settings.prefs.edit().putString("provider","openai").putString("noteProvider","openrouter").commit();

        // Modelo: los ids de OpenRouter llevan «autor/»; nunca llegan a OpenAI ni a Claude, y uno de otra IA no va a OpenRouter.
        settings.prefs.edit().putString("noteModel","~openai/gpt-luna-latest").commit();
        expect(Notes.model(settings,"openrouter").equals("~openai/gpt-luna-latest")&&Notes.model(settings,"openai").equals(Notes.OPENAI_MODEL)&&Notes.model(settings,"anthropic").equals(Notes.ANTHROPIC_MODEL),"OpenRouter alias rejected or leaked to another provider");
        settings.prefs.edit().putString("noteModel","google/gemini-3-flash").commit();
        expect(Notes.model(settings,"openrouter").equals("google/gemini-3-flash"),"Plain OpenRouter id rejected");
        settings.prefs.edit().putString("noteModel","claude-opus-5-5").commit();
        expect(Notes.model(settings,"openrouter").equals(Models.NOTE_DEFAULT),"A model without «autor/» was sent to OpenRouter");
        settings.prefs.edit().putString("noteModel","autor/modelo con espacios").commit();
        expect(Notes.model(settings,"openrouter").equals(Models.NOTE_DEFAULT),"Malformed model accepted");
        settings.prefs.edit().remove("noteModel").commit();
        expect(Notes.model(settings,"openrouter").equals("~anthropic/claude-sonnet-latest"),"Default OpenRouter note model is not the alias");

        // La clave que decide si se puede armar la nota es la de la IA elegida (aquí OpenRouter, transcribiendo con OpenAI).
        settings.saveKeyFor("openrouter","");
        expect(!Notes.canGenerate(c),"Note offered without an OpenRouter key");
        settings.saveKeyFor("openrouter",ROUTER_KEY);
        expect(Notes.canGenerate(c)&&settings.openRouterKey().equals(ROUTER_KEY)&&!settings.prefs.getString("openrouter_keyEncrypted","").contains("sk-or-test"),"OpenRouter key not usable for the note, or stored as plaintext");

        // Nota completa con los ajustes reales (IA, clave y modelo salen de Settings): URL, encabezados, alias y estado.
        long now=System.currentTimeMillis();
        Recording a=fixture(c,source,"Reunión por OpenRouter",now,fixtures);String userTitle=FilesStore.recording(c,a.id).title;
        int[] calls={0};
        HttpApi fake=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            calls[0]++;
            expect(method.equals("POST")&&url.equals("https://openrouter.ai/api/v1/chat/completions")&&ROUTER_KEY.equals(token)&&"application/json".equals(type),"OpenRouter request line wrong");
            expect(extra!=null&&"Verbapp".equals(extra.get("X-OpenRouter-Title"))&&extra.get("HTTP-Referer")!=null&&extra.get("HTTP-Referer").startsWith("https://")&&!extra.containsKey("Authorization")&&!extra.containsKey("x-api-key")&&!extra.containsValue(ROUTER_KEY),"OpenRouter headers wrong");
            expect(FilesStore.state(c,a.id).optString("noteState").equals("working"),"noteState not working during the OpenRouter request");
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);expect(out.size()==body.length(),"Body length mismatch");
            String raw=out.toString(StandardCharsets.UTF_8.name());JSONObject sent=new JSONObject(raw);
            expect(sent.getString("model").equals("~anthropic/claude-sonnet-latest")&&sent.getJSONObject("response_format").getString("type").equals("json_object")&&!sent.has("reasoning"),"OpenRouter body fields wrong");
            JSONArray messages=sent.getJSONArray("messages");
            expect(messages.getJSONObject(0).getString("content").equals(Notes.system(Lang.ES))&&messages.getJSONObject(1).getString("content").contains("{S1}: Hola, partamos.")&&messages.getJSONObject(1).getString("content").contains("(lo escribió la persona)"),"OpenRouter prompt wrong");
            expect(!raw.contains(ROUTER_KEY)&&!raw.contains("Fran")&&!raw.contains("Konrad"),"The key or the speakers' names travelled in the note request");
            return new Response(200,routerReply("Metas del trimestre","anthropic/claude-sonnet-5.5",0.0123),null);
        }};
        fake.readTimeoutMs=4321;
        Notes.generate(c,a,fake);
        JSONObject note=Notes.load(c,a.id);
        expect(calls[0]==1&&fake.readTimeoutMs==4321,"OpenRouter called more than once or timeout not restored");
        expect(note!=null&&note.getInt("version")==1&&note.getString("provider").equals("openrouter")&&note.getString("model").equals("~anthropic/claude-sonnet-latest")&&note.getString("modelUsed").equals("anthropic/claude-sonnet-5.5"),"OpenRouter note lost the alias or the model that answered: "+note);
        expect(Math.abs(note.getDouble("costUsd")-0.0123)<1e-9&&note.getBoolean("costReal")&&note.getJSONObject("usage").getLong("input_tokens")==1500&&note.getJSONObject("usage").getLong("output_tokens")==300&&note.getJSONObject("speakers").getString("S1").equals("B"),"OpenRouter real cost or usage not saved: "+note);
        JSONObject st=FilesStore.state(c,a.id);
        expect(st.optString("noteState").equals("ready")&&!st.has("noteError")&&st.optString("suggestedTitle").equals("Metas del trimestre")&&FilesStore.recording(c,a.id).title.equals(userTitle),"OpenRouter note state wrong, or user title overwritten");
        expect(logged(c,a.id,"Armando la nota con OpenRouter")&&!st.toString().contains(ROUTER_KEY)&&!note.toString().contains(ROUTER_KEY),"Log does not name OpenRouter, or the key leaked into the state or the note");
        expect(Notes.credit(note).equals("Armada con OpenRouter · anthropic/claude-sonnet-5.5 · costó US$0,012")&&Notes.markdown(c,FilesStore.recording(c,a.id)).contains("nota_ia: \"OpenRouter · anthropic/claude-sonnet-5.5\"\n"),"The model that answered is not shown: "+Notes.credit(note));

        // Respuesta sin costo y con un «modelo» que no parece un id (nada de esto está confirmado con la API real): la
        // nota se guarda igual, sin inventar el costo y sin guardar texto libre como modelo.
        Recording b=fixture(c,source,"Sin modelo ni costo",now,fixtures);
        HttpApi bare=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            return new Response(200,routerReply("Otro título","modelo con espacios <b>",-1),null);}};
        Notes.generate(c,b,bare,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);
        JSONObject noteB=Notes.load(c,b.id);
        expect(noteB!=null&&!noteB.has("modelUsed")&&!noteB.has("costUsd")&&!noteB.has("costReal")&&noteB.getJSONObject("usage").getLong("input_tokens")==1500,"Missing cost or model was made up: "+noteB);
        expect(Notes.modelShown(noteB).equals(Models.NOTE_DEFAULT)&&Notes.credit(noteB).equals("Armada con OpenRouter · ~anthropic/claude-sonnet-latest"),"Fallback to the requested model wrong: "+Notes.credit(noteB));

        // 402: sin saldo. Se explica con su salida, no se reintenta y la nota anterior se conserva.
        int[] broke={0};
        HttpApi noCredit=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            broke[0]++;return new Response(402,"{\"error\":{\"code\":402,\"message\":\"Insufficient credits. Add more using https://openrouter.ai/settings/credits\",\"metadata\":{}}}",null);}};
        boolean noBalance=false;try{Notes.generate(c,a,noCredit,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){noBalance=e.getMessage().contains("saldo en OpenRouter");}
        JSONObject failed=FilesStore.state(c,a.id);
        expect(noBalance&&broke[0]==1&&failed.optString("noteState").equals("failed")&&failed.optString("noteError").contains("saldo en OpenRouter")&&failed.optString("noteError").contains("openrouter.ai"),"402 not reported as missing credits: "+failed.optString("noteError"));
        expect(Notes.exists(c,a.id)&&Notes.load(c,a.id).getString("modelUsed").equals("anthropic/claude-sonnet-5.5"),"A failed attempt removed the previous note");

        // 404: OpenRouter retiró el modelo (o ningún proveedor lo ofrece) → elegir otro. 401: la clave.
        HttpApi gone=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            return new Response(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found for anthropic/claude-sonnet-latest.\"}}",null);}};
        boolean retired=false;try{Notes.generate(c,a,gone,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){retired=e.getMessage().contains("ya no está disponible en OpenRouter");}
        expect(retired&&FilesStore.state(c,a.id).optString("noteError").contains("Elige otro"),"404 not reported as a retired model");
        // Un id que no existe llega como 400: tampoco se reintenta en modo simple (no es una opción del pedido).
        int[] unknownTries={0};
        HttpApi unknown=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            unknownTries[0]++;return new Response(400,"{\"error\":{\"code\":400,\"message\":\"autor/no-existe is not a valid model ID\"}}",null);}};
        boolean unknownModel=false;try{Notes.generate(c,a,unknown,"openrouter","autor/no-existe",ROUTER_KEY);}catch(HttpApi.UserAction e){unknownModel=e.getMessage().contains("ya no está disponible en OpenRouter");}
        expect(unknownModel&&unknownTries[0]==1,"Unknown model id retried or not explained (tries: "+unknownTries[0]+")");
        HttpApi denied=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            return new Response(401,"{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}",null);}};
        boolean badKey=false;try{Notes.generate(c,a,denied,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){badKey=e.getMessage().contains("clave de OpenRouter");}
        expect(badKey,"401 not reported as an OpenRouter key problem");

        // 400 por una opción del pedido: se reintenta UNA vez en modo simple (solo modelo y mensajes) y la nota sale.
        Recording d=fixture(c,source,"Reintento simple",now,fixtures);
        int[] tries={0};
        HttpApi picky=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            tries[0]++;ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);JSONObject sent=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            expect(url.equals(Notes.OPENROUTER_URL)&&ROUTER_KEY.equals(token)&&extra!=null&&"Verbapp".equals(extra.get("X-OpenRouter-Title"))&&sent.getString("model").equals("~openai/gpt-luna-latest")&&sent.getJSONArray("messages").length()==2,"OpenRouter attempt "+tries[0]+" wrong");
            if(tries[0]==1){
                expect(sent.has("response_format")&&sent.getJSONObject("reasoning").getString("effort").equals("low"),"First attempt should carry the full options");
                return new Response(400,"{\"error\":{\"code\":400,\"message\":\"response_format is not supported by this model\"}}",null);
            }
            // El reintento simple deja fuera response_format y reasoning, pero no el tope de salida (ver openrouterBody).
            expect(sent.length()==3&&sent.getInt("max_tokens")>0&&!sent.has("response_format")&&!sent.has("reasoning"),"Simple retry still carries optional fields or lost max_tokens: "+sent.names());
            return new Response(200,routerReply("Reintento","openai/gpt-6-luna",0.0004),null);
        }};
        Notes.generate(c,d,picky,"openrouter","~openai/gpt-luna-latest",ROUTER_KEY);
        JSONObject noteD=Notes.load(c,d.id);
        expect(tries[0]==2&&noteD!=null&&noteD.getString("model").equals("~openai/gpt-luna-latest")&&noteD.getString("modelUsed").equals("openai/gpt-6-luna")&&logged(c,d.id,"modo simple"),"400 for an option was not retried once in simple mode (tries: "+tries[0]+")");
        expect(Notes.credit(noteD).equals("Armada con OpenRouter · openai/gpt-6-luna · costó < US$0,001"),"Tiny real cost shown wrong: "+Notes.credit(noteD));

        // Un 400 por largo no se reintenta (el modo simple no lo arregla) y dice qué hacer.
        int[] longTries={0};
        HttpApi tooLong=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            longTries[0]++;return new Response(400,"{\"error\":{\"code\":400,\"message\":\"This endpoint's maximum context length is 200000 tokens.\"}}",null);}};
        boolean tooBig=false;try{Notes.generate(c,d,tooLong,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){tooBig=e.getMessage().contains("demasiado larga");}
        expect(tooBig&&longTries[0]==1,"Context-length 400 retried or not explained (tries: "+longTries[0]+")");
        // Lo mismo cuando OpenRouter deja la causa solo en error.metadata.raw (mensaje genérico «Provider returned error»).
        int[] rawTries={0};
        HttpApi rawLong=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            rawTries[0]++;return new Response(400,"{\"error\":{\"code\":400,\"message\":\"Provider returned error\",\"metadata\":{\"raw\":\"prompt is too long: 250000 tokens > 200000 maximum\",\"provider_name\":\"Anthropic\"}}}",null);}};
        boolean rawBig=false;try{Notes.generate(c,d,rawLong,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){rawBig=e.getMessage().contains("demasiado larga");}
        expect(rawBig&&rawTries[0]==1,"metadata.raw not read: too-long prompt retried or not explained (tries: "+rawTries[0]+")");

        // 404 por la privacidad de la cuenta: se dice dónde está ese ajuste, no que el modelo se retiró.
        HttpApi policy=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            return new Response(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found matching your data policy (Free model training). Configure: https://openrouter.ai/settings/privacy\"}}",null);}};
        String privacy="";try{Notes.generate(c,d,policy,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){privacy=e.getMessage();}
        expect(privacy.contains("privacidad")&&privacy.contains("openrouter.ai")&&!privacy.contains("ya no está disponible"),"Data-policy 404 explained as a retired model: "+privacy);

        // usage.cost=0 no es un cobro real (con BYOK OpenRouter informa 0): no se guarda «costó US$0,000».
        Recording z=fixture(c,source,"Costo cero",now,fixtures);
        HttpApi free=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            return new Response(200,routerReply("Costo cero","anthropic/claude-sonnet-5.5",0),null);}};
        Notes.generate(c,z,free,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);
        JSONObject noteZ=Notes.load(c,z.id);
        expect(noteZ!=null&&!noteZ.optBoolean("costReal")&&!Notes.credit(noteZ).contains("costó"),"A zero usage.cost was shown as the real cost: "+Notes.credit(noteZ));

        // 0.9.0: con la app en inglés, el pedido real va entero en inglés (instrucciones y cabecera), la nota guarda su
        // idioma y la bitácora sale en inglés. La app vuelve a español pase lo que pase.
        Recording eng=fixture(c,source,"Budget meeting",now,fixtures);
        HttpApi english=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);JSONArray messages=new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONArray("messages");
            String system=messages.getJSONObject(0).getString("content"),user=messages.getJSONObject(1).getString("content");
            expect(system.equals(Notes.system(Lang.EN))&&system.contains("English")&&!system.contains("español")&&user.startsWith("Current title: “")&&user.contains("” (written by the person)\nDate: ")
                &&user.contains("\nTranscript:\n[00:00] {S1}: Hola, partamos.")&&!user.contains("Transcripción")&&!user.contains("Fecha:"),"English note request not in English:\n"+user);
            return new Response(200,routerReply("Budget review","anthropic/claude-sonnet-5.5",0.002),null);
        }};
        try{Lang.override(Lang.EN);Notes.generate(c,eng,english,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}
        finally{Lang.override(Lang.ES);}
        JSONObject noteEng=Notes.load(c,eng.id);
        expect(noteEng!=null&&Lang.EN.equals(noteEng.optString("lang"))&&Lang.ES.equals(Notes.load(c,a.id).optString("lang")),"The note does not record the language it was asked in: "+noteEng);
        expect(logged(c,eng.id,"Creating the note with OpenRouter")&&logged(c,eng.id,"Note ready · took ")&&!logged(c,eng.id,"Armando la nota"),"The note log is not in English");

        // OpenRouter puede responder 200 con el error del proveedor adentro: se trata como ese error (aquí, pasajero).
        HttpApi inside=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            return new Response(200,"{\"error\":{\"code\":503,\"message\":\"Provider returned error\"}}",null);}};
        boolean passing=false;try{Notes.generate(c,d,inside,"openrouter",Models.NOTE_DEFAULT,ROUTER_KEY);}catch(HttpApi.UserAction e){passing=false;}catch(IOException e){passing=!(e instanceof Notes.BadAnswer);}
        expect(passing&&FilesStore.state(c,d.id).optString("noteState").equals("failed")&&!FilesStore.state(c,d.id).optString("noteError").isEmpty()&&Notes.exists(c,d.id),"An error inside a 200 was not treated as a temporary failure: "+FilesStore.state(c,d.id).optString("noteError"));

        // Sin clave de OpenRouter no se llama a nadie: se avisa qué falta.
        settings.saveKeyFor("openrouter","");
        int[] none={0};
        HttpApi unused=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){none[0]++;return new Response(200,"{}",null);}};
        boolean missing=false;try{Notes.generate(c,d,unused);}catch(HttpApi.UserAction e){missing=e.getMessage().contains("clave de OpenRouter");}
        expect(missing&&none[0]==0&&!Notes.canGenerate(c)&&FilesStore.state(c,d.id).optString("noteError").contains("OpenRouter"),"Missing OpenRouter key not reported before calling the API");
    }

    private static Recording fixture(Context c,Recording source,String title,long created,List<Recording> fixtures)throws Exception{
        Recording f=new Recording(UUID.randomUUID().toString(),title,created,60_000);fixtures.add(f);
        java.nio.file.Files.copy(source.audio(c).toPath(),f.audio(c).toPath());f.save(c);
        conversation().save(c,f.id);
        JSONArray marks=marks();FilesStore.update(c,f.id,s->s.put("marks",marks));
        return f;
    }
}
