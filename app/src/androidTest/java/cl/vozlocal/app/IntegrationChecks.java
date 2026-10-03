package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Pruebas de integración de la 0.6.0 que no pertenecen a una sola parte (hojas, novedades, importar, siguiente paso). */
final class IntegrationChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static JSONObject seg(String who,double a,double b)throws JSONException{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text","hola que tal como estás");}
    static void run(Context c,Recording r)throws Exception{
        // Nombrar voces: muestra limpia de cada voz, nombres recientes, iniciales y porcentajes.
        JSONArray segs=new JSONArray().put(seg("A",0,7)).put(seg("B",7,14)).put(seg("C",14,20)).put(seg("A",20,27));
        double[] a=NameVoices.sample(segs,"A");check(a!=null&&a[0]<=1&&a[1]>=6,"Voice sample not found");
        JSONArray crowded=new JSONArray().put(seg("A",0,5)).put(seg("B",4,8));check(NameVoices.sample(crowded,"A")==null,"Crowded sample accepted");
        check(NameVoices.mergeRecent(Collections.singletonList("Fran"),Arrays.asList("Konra","persona 2","fran")).equals(Arrays.asList("Konra","fran")),"Recent names merge wrong");
        check(NameVoices.initial("Persona 3").equals("3")&&NameVoices.initial("fran").equals("F"),"Voice initials wrong");
        check(NameVoices.percent(0.004).equals("< 1 %")&&NameVoices.percent(0.46).equals("46 %"),"Voice share wrong");
        // 0.9.0: «Persona N» puede venir de otro idioma; el % sigue el estilo del idioma; escribir la etiqueta de otra voz en
        // otro idioma también une; los nombres de «Volver a transcribir» se reconocen en los tres idiomas (Diagnostics).
        check(NameVoices.initial("Person 3").equals("3")&&NameVoices.initial("Pessoa 12").equals("12"),"Voice initials from other languages wrong");
        try{Lang.override(Lang.EN);check(NameVoices.percent(0.46).equals("46%")&&NameVoices.percent(0.004).equals("<1%"),"English voice share wrong: "+NameVoices.percent(0.46));}
        finally{Lang.override(Lang.ES);}
        Transcript cross=new Transcript(new JSONObject().put("diarized",true).put("segments",new JSONArray().put(seg("A",0,5)).put(seg("B",5,9))));
        check(cross.applyNames(Collections.singletonMap("B","Person 1"))==1&&cross.speakers().size()==1,"A label typed in another language did not merge");
        Set<String> labels=Retranscribe.allLabels();
        check(labels.size()==12&&labels.contains("Second pass with your corrections")&&labels.contains(Retranscribe.label(Retranscribe.Mode.CORRECTIONS)),"Retranscribe labels in all languages wrong: "+labels);
        // Novedades: orden de versiones, titulares y fechas en español.
        check(Novedades.compare("0.4.10","0.5.0")<0&&Novedades.compare("0.6.0","0.6.0")==0,"Version compare wrong");
        check(Novedades.headline("Mi voz: x")==6&&Novedades.headline("sin titular")<0,"Novedades headline wrong");
        check(Novedades.parse("[{\"version\":\"0.4.1\",\"items\":[\"a\"]},{\"version\":\"0.6.0\",\"items\":[\"b\"]}]").get(0).version.equals("0.6.0"),"Novedades not sorted newest first");
        check(Novedades.date("2026-09-29").equals("29 de septiembre de 2026"),"Novedades date wrong");
        check(!Novedades.current(c).isEmpty(),"Current version has no novedades (assets/novedades.json)");
        // Diagnóstico sin datos personales: nombres y títulos en etiquetas de botones se ocultan (desde la primera palabra personal).
        Set<String> none=Collections.emptySet();
        check(Diagnostics.safeAction("Guardar la voz de Fran",none).equals("Guardar la voz de …"),"Name leaked into diagnostics");
        check(Diagnostics.safeAction("Opciones de 2026-09-28 Tareas pendientes",none).equals("Opciones de …"),"Title leaked into diagnostics");
        check(Diagnostics.safeAction("Ver detalles del proceso",none).equals("Ver detalles del proceso"),"Plain label altered");
        privacy(c,r);
        chooseFirst(c,r);
        // Importar: título sin extensión y nombres de WhatsApp legibles.
        check(ImportSession.titleFrom("Reunión.m4a").equals("Reunión"),"Import title keeps extension");
        check(ImportSession.titleFrom("PTT-20260929-WA0003.opus").startsWith("Audio de WhatsApp"),"WhatsApp title not friendly");
        check(ImportSession.titleFrom("").equals("Audio importado"),"Empty import title");
        // Siguiente paso: sin transcripción → Transcribir.
        Recording fresh=new Recording(UUID.randomUUID().toString(),"Paso",System.currentTimeMillis(),5000);
        try{java.nio.file.Files.copy(r.audio(c).toPath(),fresh.audio(c).toPath());fresh.save(c);
            check(Next.of(c,fresh).step==Next.Step.TRANSCRIBE,"New recording should offer Transcribir");
        }finally{fresh.delete(c);}
    }

    private static Recording fixture(Context c,Recording r,String title)throws Exception{
        Recording d=new Recording(UUID.randomUUID().toString(),title,System.currentTimeMillis(),r.duration);
        java.nio.file.Files.copy(r.audio(c).toPath(),d.audio(c).toPath());d.save(c);return d;
    }
    private static Transcript said(String text)throws JSONException{
        return new Transcript(new JSONObject().put("diarized",true).put("segments",new JSONArray().put(seg("A",0,3).put("text",text)).put(seg("B",3,6))));
    }

    /**
     * Palabras en minúscula de un título, de un nombre de voz de la transcripción y del título que se está grabando no
     * llegan al registro técnico; la bitácora del informe tapa títulos, nombres y texto entre « ».
     */
    static void privacy(Context c,Recording r)throws Exception{
        Recording p=fixture(c,r,"cita con abogado por divorcio");
        try{
            Transcript t=said("hola");t.data.put("names",new JSONObject().put("A","mamá"));t.save(c,p.id);
            Set<String> words=Diagnostics.personalTokens(c);
            check(words.contains("abogado")&&words.contains("divorcio")&&words.contains("mama")&&!words.contains("con")&&!words.contains("por"),"Title words or voice names missing from the private words");
            Set<String> mine=Diagnostics.tokens(Arrays.asList(p.title,"mamá"));
            check(Diagnostics.safeAction("Opciones de cita con abogado por divorcio",mine).equals("Opciones de …"),"Lowercase title leaked into diagnostics");
            check(Diagnostics.safeAction("cita con abogado por divorcio",mine).equals("…"),"Title shown on a button leaked into diagnostics");
            check(Diagnostics.safeAction("Guardar la voz de mamá",mine).equals("Guardar la voz de …"),"Lowercase voice name leaked into diagnostics");
            check(Diagnostics.safeAction("Intercambiar mamá y papá desde aquí",mine).equals("Intercambiar …"),"Names after a hidden word leaked into diagnostics");
            check(Diagnostics.safeAction("Ver detalles del proceso",mine).equals("Ver detalles del proceso"),"Plain label altered by private words");
            // El título que se está grabando (aún no guardado) también se tapa.
            String active=RecorderService.activeTitle;
            try{RecorderService.activeTitle="cena sorpresa para ximena";check(Diagnostics.safeAction("cena sorpresa para ximena").equals("…"),"Title being recorded leaked into diagnostics");}
            finally{RecorderService.activeTitle=active;}
            String line=Diagnostics.redact("Volver a transcribir: «"+Retranscribe.label(Retranscribe.Mode.CORRECTIONS)+"» · Tu voz («Konrad») · muestras de Mamá, Persona 2",Arrays.asList("mamá","Konrad"));
            check(line.equals("Volver a transcribir: «"+Retranscribe.label(Retranscribe.Mode.CORRECTIONS)+"» · Tu voz (…) · muestras de …, Persona 2"),"Support log not redacted: "+line);
            List<String> title=Arrays.asList(p.title,Notes.heading(p.title));
            check(Diagnostics.redact("Guardado en «cita con abogado por divorcio.md»",title).equals("Guardado en …")
                &&Diagnostics.redact("Título: Cita con abogado por divorcio",title).equals("Título: …"),"Title left in the support log");
        }finally{p.delete(c);}
    }

    /** Con una versión anterior esperando la elección, no se puede volver a transcribir (la borraría sin aviso). */
    static void chooseFirst(Context c,Recording r)throws Exception{
        Recording d=fixture(c,r,"Prueba elegir versión");
        try{
            said("Versión uno").save(c,d.id);
            Retranscribe.prepare(c,d,Retranscribe.Mode.TEXT,null);
            said("Versión dos").save(c,d.id);
            java.io.File prev=FilesStore.file(c,d.id,".transcript.prev.json");
            check(Retranscribe.hasPrevious(c,d.id)&&Transcript.exists(c,d.id),"Test versions not set up");
            for(Retranscribe.Mode m:Retranscribe.Mode.values()){
                check(Retranscribe.chooseFirst().equals(Retranscribe.reason(c,d,m))&&!Retranscribe.available(c,d,m),"Retranscribe offered while a previous version is pending: "+m);
            }
            boolean refused=false;try{Retranscribe.start(c,d,Retranscribe.Mode.TEXT);}catch(HttpApi.UserAction e){refused=true;}
            check(refused&&!FilesStore.state(c,d.id).optBoolean("requested"),"A second retranscription was queued");
            refused=false;try{Retranscribe.prepare(c,d,Retranscribe.Mode.SPEAKERS,null);}catch(HttpApi.UserAction e){refused=true;}
            check(refused,"prepare() replaced a previous version not chosen yet");
            check(FilesStore.read(prev).getJSONArray("segments").getJSONObject(0).getString("text").equals("Versión uno")
                &&Transcript.load(c,d.id).segments().getJSONObject(0).getString("text").equals("Versión dos"),"The pending versions changed");
        }finally{d.delete(c);}
    }
}
