package cl.vozlocal.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.9.5: Ajustes por temas, tipo de resumen («Segundo cerebro» / «Sesión con cliente») con sus partes configurables y
 * menos preguntas por defecto.
 */
final class SessionChecks {
    private SessionChecks(){}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JSONObject seg(String who,double a,double b,String text)throws Exception{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}

    static void run(Instrumentation inst,Context c)throws Exception{
        defaults(c);
        kinds(c);
        settingsTopics(inst,c);
    }

    /** Sin elegir nada, transcribir no pregunta: separa voces siempre y no pide nombre al terminar. */
    private static void defaults(Context c){
        Settings s=new Settings(c);
        String mode=s.prefs.getString("speakersMode",null);boolean hadAsk=s.prefs.contains("askTitle");boolean ask=s.prefs.getBoolean("askTitle",false);
        try{
            s.prefs.edit().remove("speakersMode").remove("askTitle").commit();
            check("always".equals(s.speakersMode())&&!s.askTitle(),"Defaults should not ask: speakers="+s.speakersMode()+", askTitle="+s.askTitle());
            check(Notes.BRAIN.equals(s.noteKind()),"The default summary type must be the second brain note");
        }finally{
            android.content.SharedPreferences.Editor e=s.prefs.edit();
            if(mode==null)e.remove("speakersMode");else e.putString("speakersMode",mode);
            if(hadAsk)e.putBoolean("askTitle",ask);else e.remove("askTitle");e.commit();
        }
    }

    /** Las instrucciones de cada tipo en los cuatro idiomas, las partes quitadas, la nota y su Markdown. */
    private static void kinds(Context c)throws Exception{
        Settings s=new Settings(c);
        String[] keys={"noteKind","note_client_watch","note_client_quotes","note_brain_tags"};
        java.util.Map<String,?> before=new java.util.HashMap<>(s.prefs.getAll());
        long created=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).parse("2026-10-09 10:00").getTime();
        Recording r=new Recording("session-check","Sesión de prueba",created,600_000);
        Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",true).put("names",new JSONObject().put("A","Stephan").put("B","Lisa")).put("segments",new JSONArray()
            .put(seg("A",0,5,"¿Cómo te fue esta semana con el plan de ventas?")).put(seg("B",6,12,"Mejor, pero me cuesta ordenar las mañanas."))
            .put(seg("A",13,20,"Entonces revisa el plan tres veces por semana y nos vemos el jueves."))));
        String[] own={"(professional)","(profesional)","(profissional)","(Fachperson)"};
        String[] forbidden={"terap","therap","pacient","patient","salud","health","saúde","gesundheit","clínic","clinic","klinik","médic","medic","diagn"};
        try{
            for(int i=0;i<Lang.SUPPORTED.length;i++){
                String lang=Lang.SUPPORTED[i];
                Notes.Prompt brain=Notes.prompt(t,r,new JSONArray(),lang);
                check(brain.system().startsWith(Notes.system(lang))&&brain.system().contains("<transcript>"),"The second brain note must keep its instructions plus the transcript rule ("+lang+")");
                Notes.Prompt client=Notes.prompt(t,r,new JSONArray(),lang).as(Notes.CLIENT,null);String sys=client.system();
                for(String field:new String[]{"\"client\":[","\"work\":[","\"agreements\":[","\"next\":[","\"watch\":[","- summary: ","- client: ","- work: ","- agreements: ","- next: ","- watch: ","- quotes: ","- tags: "," 60 "})
                    check(sys.contains(field),"Client session instructions in "+lang+" lack «"+field+"»");
                check(sys.contains(own[i])&&!sys.equals(Notes.system(lang)),"Client session instructions not in "+lang);
                String lower=sys.toLowerCase(java.util.Locale.ROOT);
                for(String word:forbidden)check(!lower.contains(word),"Client session instructions in "+lang+" mention «"+word+"»: Verbapp is not a health app");
                check(Notes.openrouterBody(Models.NOTE_DEFAULT,client,false).getJSONArray("messages").getJSONObject(0).getString("content").equals(sys),"The client instructions must travel with the request ("+lang+")");
                Set<String> skip=new LinkedHashSet<>(Arrays.asList("quotes","tags"));
                String cut=Notes.prompt(t,r,new JSONArray(),lang).as(Notes.BRAIN,skip).system();
                check(cut.startsWith(Notes.system(lang))&&cut.endsWith("quotes, tags."),"Removed parts must be asked to stay empty ("+lang+"): "+cut.substring(Math.max(0,cut.length()-120)));
            }
            check(Arrays.asList(Notes.parts(Notes.BRAIN)).equals(Arrays.asList("decisions","tasks","quotes","tags"))&&Arrays.asList(Notes.parts(Notes.CLIENT)).containsAll(Arrays.asList(Notes.CLIENT_LISTS)),"Configurable parts changed");

            // Partes quitadas desde Ajustes.
            s.prefs.edit().putString("noteKind",Notes.CLIENT).putBoolean("note_client_watch",false).putBoolean("note_client_quotes",false).putBoolean("note_brain_tags",false).commit();
            check(Notes.CLIENT.equals(s.noteKind())&&s.skippedParts(Notes.CLIENT).equals(new LinkedHashSet<>(Arrays.asList("watch","quotes")))&&s.skippedParts(Notes.BRAIN).equals(Collections.singleton("tags")),
                "Skipped parts wrong: "+s.skippedParts(Notes.CLIENT)+" / "+s.skippedParts(Notes.BRAIN));

            // La nota de «Sesión con cliente»: listas limpias y con marcas de voz, como el resto de la nota.
            Lang.override(Lang.ES);
            Notes.Prompt p=Notes.prompt(t,r,new JSONArray(),Lang.ES).as(Notes.CLIENT,null);
            JSONArray many=new JSONArray();for(int i=0;i<15;i++)many.put("Punto "+i+" de {S2}");
            JSONObject raw=new JSONObject().put("title","Seguimiento semanal").put("summary","{S1} (profesional) y {S2} (cliente) revisaron la semana.")
                .put("client",new JSONArray().put("{s2} siente que le cuesta ordenar las mañanas.").put("")).put("work",new JSONArray().put("Revisaron el plan de ventas."))
                .put("agreements",new JSONArray().put("Seguir tres veces por semana.")).put("next",new JSONArray().put("Sesión el jueves.")).put("watch",many)
                .put("quotes",new JSONArray()).put("tags",new JSONArray().put("seguimiento"));
            JSONObject note=Notes.normalize(raw,p.tokens,r.duration).put("kind",Notes.CLIENT).put("speakers",new JSONObject(p.tokens)).put("provider","openrouter").put("model",Models.NOTE_DEFAULT);
            check(note.getJSONArray("client").length()==1&&note.getJSONArray("client").getString(0).equals("{S2} siente que le cuesta ordenar las mañanas.")&&note.getJSONArray("watch").length()==12,
                "Client lists not cleaned: "+note);
            check(!Notes.normalize(new JSONObject().put("summary","x"),p.tokens,r.duration).has("client"),"A second brain note must not get client lists");
            String md=Notes.markdown(r,note,t,new JSONArray());
            int at=0;
            for(String section:new String[]{"## Panorama general\n","## Lo que contó el cliente\n","## Lo que se trabajó\n","## Recomendaciones y acuerdos\n","## Próximos pasos\n","## Puntos a vigilar\n","## Transcripción\n"}){
                int k=md.indexOf(section);check(k>at,"Client Markdown section missing or out of order: "+section+"\n"+md);at=k;}
            check(md.contains("- Lisa siente que le cuesta ordenar las mañanas.\n")&&!md.contains("## Resumen\n"),"Client Markdown body wrong:\n"+md);
        }finally{
            Lang.override(Lang.ES);
            android.content.SharedPreferences.Editor e=s.prefs.edit();
            for(String k:keys){Object v=before.get(k);if(v==null)e.remove(k);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else e.putString(k,String.valueOf(v));}
            e.commit();
        }
    }

    /** Cada tema se abre sin errores, con su título; Atrás vuelve a la lista (y de «Tipo de resumen», a su tema). */
    private static void settingsTopics(Instrumentation inst,Context c)throws Exception{
        Activity a=inst.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();
        Method open=SettingsActivity.class.getDeclaredMethod("open",String.class);open.setAccessible(true);
        Field section=SettingsActivity.class.getDeclaredField("section");section.setAccessible(true);
        Field page=Screen.class.getDeclaredField("page");page.setAccessible(true);
        try{
            Throwable[] failure={null};
            for(String key:new String[]{SettingsActivity.GENERAL,SettingsActivity.RECORD,SettingsActivity.SUMMARY,SettingsActivity.SAVE,SettingsActivity.AI,SettingsActivity.NOISE,SettingsActivity.ENERGY,SettingsActivity.HELP}){
                inst.runOnMainSync(()->{try{open.invoke(a,key);
                    android.widget.LinearLayout content=(android.widget.LinearLayout)page.get(a);
                    check(key.equals(section.get(a))&&content.getChildCount()>=2,"Topic "+key+" did not open");}catch(Throwable e){failure[0]=e;}});
                inst.waitForIdleSync();if(failure[0]!=null)throw new AssertionError("Settings topic "+key+" failed",failure[0]);
            }
            inst.runOnMainSync(()->{try{open.invoke(a,SettingsActivity.SUMMARY);a.onBackPressed();check(SettingsActivity.RECORD.equals(section.get(a)),"Back from the summary type should go to Record and transcribe");
                a.onBackPressed();check(section.get(a)==null,"Back from a topic should go to the topic list");}catch(Throwable e){failure[0]=e;}});
            inst.waitForIdleSync();if(failure[0]!=null)throw new AssertionError("Settings back navigation failed",failure[0]);
        }finally{a.finish();inst.waitForIdleSync();}
    }
}
