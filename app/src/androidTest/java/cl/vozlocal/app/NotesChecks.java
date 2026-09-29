package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pruebas de la parte «notes» de la 0.6.0 (ver docs/diseno/SPEC-0.6.md). Sin llamadas reales a APIs: HttpApi falso. */
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
        List<Recording> fixtures=new ArrayList<>();
        try{files(c,source,settings,fixtures);}
        finally{
            android.content.SharedPreferences.Editor edit=settings.prefs.edit().putBoolean("datePrefix",prefix);if(noteModel==null)edit.remove("noteModel");else edit.putString("noteModel",noteModel);edit.commit();
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
        expect(md.contains("fuente: \"Voz local\"\n")&&md.contains("grabacion: \"2026-09-29 Reunión de presupuesto\"\n")&&md.contains("\n---\n\n# Reunión de presupuesto\n"),"Frontmatter tail or heading wrong:\n"+md);
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
        expect(claude.getString("model").equals("claude-sonnet-5-5")&&claude.getInt("max_tokens")>=4000&&claude.getString("system").equals(Notes.SYSTEM)&&claude.getJSONArray("messages").length()==1&&claude.getJSONArray("messages").getJSONObject(0).getString("role").equals("user")&&claude.getJSONObject("output_config").getString("effort").equals("medium"),"Anthropic body wrong: "+claude);
        expect(!Notes.anthropicBody("claude-haiku-4-5-20251001",p).has("output_config"),"Effort sent to Haiku");
        Map<String,String> headers=Notes.anthropicHeaders("ak-test");
        expect(headers.get("x-api-key").equals("ak-test")&&headers.get("anthropic-version").equals("2023-06-01")&&!headers.containsKey("Authorization"),"Anthropic headers wrong");

        // Título automático: solo el de la app (con o sin fecha), nunca uno escrito por la persona.
        expect(Notes.isDefaultTitle(Recording.defaultTitle(created),created)&&Notes.isDefaultTitle(Recording.withDate(Recording.defaultTitle(created),created),created)&&Notes.isDefaultTitle("2026-09-27 Grabación 16:05",created)&&Notes.isDefaultTitle("",created),"Default title not recognized");
        expect(!Notes.isDefaultTitle("Grabación con Fran",created)&&!Notes.isDefaultTitle("2026-09-29 Reunión",created)&&!Notes.isDefaultTitle("Reunión de presupuesto",created),"User title treated as automatic");

        // Nombre del .md: misma limpieza que el .txt y la fecha delante sin duplicarla.
        expect(TranscriptExport.noteFilename("Reunión: equipo",created).equals("2026-09-29 Reunión equipo.md"),"Note filename wrong: "+TranscriptExport.noteFilename("Reunión: equipo",created));
        expect(TranscriptExport.noteFilename("2026-09-20 Ya fechada.md",created).equals("2026-09-20 Ya fechada.md")&&TranscriptExport.markdownFilename("... /\\").equals("Transcripción.md"),"Note filename duplicated date/extension or lost fallback");
        expect(TranscriptExport.filename("Reunión.txt").equals("Reunión.txt")&&TranscriptExport.filename("Notas.md").equals("Notas.md.txt"),".txt filename behavior changed");
        expect(Notes.usd(Notes.OPENAI_MODEL,1_000_000,1_000_000)>0&&Notes.estimateUsd(Notes.ANTHROPIC_MODEL,52*60_000)<0.2&&Notes.usd("modelo-propio",1,1)<0,"Cost estimate wrong");
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
            expect(sent.getString("model").equals("claude-sonnet-5-5")&&sent.getString("system").equals(Notes.SYSTEM)&&sent.getInt("max_tokens")>0&&sent.getJSONArray("messages").getJSONObject(0).getString("content").contains("(lo escribió la persona)"),"Anthropic body wrong");
            JSONObject reply=new JSONObject().put("type","message").put("stop_reason","end_turn")
                .put("content",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","…")).put(new JSONObject().put("type","text").put("text","```json\n"+answer().put("title","Metas del trimestre")+"\n```")))
                .put("usage",new JSONObject().put("input_tokens",900).put("output_tokens",300));
            return new Response(200,reply.toString(),null);
        }};
        Notes.generate(c,b,fakeClaude,"anthropic",Notes.ANTHROPIC_MODEL,"ak-test-notes");
        JSONObject noteB=Notes.load(c,b.id);
        expect(noteB!=null&&noteB.getString("provider").equals("anthropic")&&noteB.getString("summary").equals("{S1} y {S2} revisaron el presupuesto.")&&noteB.getJSONObject("usage").getLong("output_tokens")==300,"Anthropic note wrong: "+noteB);
        expect(FilesStore.recording(c,b.id).title.equals(userTitle)&&FilesStore.state(c,b.id).optString("suggestedTitle").equals("Metas del trimestre"),"User title overwritten or suggestion missing");

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

    private static Recording fixture(Context c,Recording source,String title,long created,List<Recording> fixtures)throws Exception{
        Recording f=new Recording(UUID.randomUUID().toString(),title,created,60_000);fixtures.add(f);
        java.nio.file.Files.copy(source.audio(c).toPath(),f.audio(c).toPath());f.save(c);
        conversation().save(c,f.id);
        JSONArray marks=marks();FilesStore.update(c,f.id,s->s.put("marks",marks));
        return f;
    }
}
