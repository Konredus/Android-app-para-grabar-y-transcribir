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
        // Novedades: orden de versiones, titulares y fechas en español.
        check(Novedades.compare("0.4.10","0.5.0")<0&&Novedades.compare("0.6.0","0.6.0")==0,"Version compare wrong");
        check(Novedades.headline("Mi voz: x")==6&&Novedades.headline("sin titular")<0,"Novedades headline wrong");
        check(Novedades.parse("[{\"version\":\"0.4.1\",\"items\":[\"a\"]},{\"version\":\"0.6.0\",\"items\":[\"b\"]}]").get(0).version.equals("0.6.0"),"Novedades not sorted newest first");
        check(Novedades.date("2026-09-29").equals("29 de septiembre de 2026"),"Novedades date wrong");
        check(!Novedades.current(c).isEmpty(),"Current version has no novedades (assets/novedades.json)");
        // Diagnóstico sin datos personales: nombres y títulos en etiquetas de botones se ocultan.
        check(Diagnostics.safeAction("Guardar la voz de Fran").equals("Guardar la voz de …"),"Name leaked into diagnostics");
        check(Diagnostics.safeAction("Opciones de 2026-09-28 Tareas pendientes").equals("Opciones de … pendientes"),"Title leaked into diagnostics");
        check(Diagnostics.safeAction("Ver detalles del proceso").equals("Ver detalles del proceso"),"Plain label altered");
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
}
