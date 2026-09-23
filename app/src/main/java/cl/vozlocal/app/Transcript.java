package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;

final class Transcript {
    final JSONObject data;
    Transcript(JSONObject data) { this.data=data; }
    static boolean exists(Context c,String id) { return FilesStore.file(c,id,".transcript.json").isFile(); }
    static Transcript load(Context c,String id) throws Exception { synchronized(FilesStore.LOCK) { return new Transcript(FilesStore.read(FilesStore.file(c,id,".transcript.json"))); } }
    void save(Context c,String id) throws Exception {
        synchronized(FilesStore.LOCK) { if(FilesStore.file(c,id,".m4a").exists()) FilesStore.write(FilesStore.file(c,id,".transcript.json"),data); }
    }
    JSONArray segments() { return data.optJSONArray("segments") == null ? new JSONArray() : data.optJSONArray("segments"); }
    LinkedHashMap<String,String> speakers() throws Exception {
        LinkedHashMap<String,String> speakers=new LinkedHashMap<>();
        JSONArray segments=segments();
        for(int i=0;i<segments.length();i++) { String id=segments.getJSONObject(i).getString("speaker"); if(!speakers.containsKey(id))speakers.put(id,data.optBoolean("diarized",true)?"Persona "+(speakers.size()+1):"Texto"); }
        JSONObject names=data.optJSONObject("names");
        if(names!=null)for(String id:speakers.keySet()){String name=names.optString(id,"").trim();if(!name.isEmpty())speakers.put(id,name);}
        return speakers;
    }
    static void rename(Context c,String id,Map<String,String> names) throws Exception {
        synchronized(FilesStore.LOCK) {
            Transcript latest=load(c,id); JSONObject mapping=new JSONObject();
            for(Map.Entry<String,String> name:names.entrySet()) mapping.put(name.getKey(),name.getValue().trim());
            latest.data.put("names",mapping); latest.save(c,id);
        }
        Pipeline.edited(c,id);
    }
    String text(Recording r) throws Exception {
        StringBuilder text=new StringBuilder(r.title).append("\n")
            .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",new Locale("es","CL")).format(new Date(r.created))).append("\n\n");
        if(data.optBoolean("demo"))text.append("EJEMPLO DE DEMOSTRACIÓN · No proviene de una transcripción real.\n\n");
        if(data.optInt("parts",1)>1)text.append(data.optBoolean("diarized",true)
            ? "Audio procesado en bloques. Las etiquetas de personas son independientes entre bloques; revisa sus nombres.\n\n"
            : "Audio procesado en bloques. Los tiempos indican el inicio de cada bloque, no de cada frase.\n\n");
        Map<String,String> names=speakers(); JSONArray segments=segments();
        for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);text.append("[").append(Recording.time((long)(segment.getDouble("start")*1000))).append("] ").append(names.get(segment.getString("speaker"))).append(": ").append(segment.getString("text")).append("\n\n");}
        if(segments.length()==0)text.append("No se detectó habla en este audio.\n");
        return text.toString();
    }
    static Transcript fromParts(List<JSONObject> parts,List<Double> offsets) throws Exception {
        JSONArray segments=new JSONArray();
        for(int p=0;p<parts.size();p++){
            JSONObject response=parts.get(p); JSONArray source=response.optJSONArray("segments");
            if(source==null)throw new java.io.IOException("OpenAI no devolvió los segmentos de hablantes esperados.");
            for(int i=0;i<source.length();i++){
                JSONObject segment=source.getJSONObject(i);
                if(!segment.has("speaker") || !segment.has("start") || !segment.has("end") || !segment.has("text"))throw new java.io.IOException("La transcripción recibida está incompleta.");
                String speaker=segment.isNull("speaker")?"unknown":segment.getString("speaker");
                segments.put(new JSONObject().put("speaker",parts.size()>1?"block"+p+":"+speaker:speaker)
                    .put("start",segment.getDouble("start")+offsets.get(p)).put("end",segment.getDouble("end")+offsets.get(p)).put("text",segment.getString("text")));
            }
        }
        return new Transcript(new JSONObject().put("segments",segments).put("parts",parts.size()).put("names",new JSONObject()).put("diarized",parts.isEmpty()||parts.get(0).optBoolean("_diarized",true)));
    }
}
