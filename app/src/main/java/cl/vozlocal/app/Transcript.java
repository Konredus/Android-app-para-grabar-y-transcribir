package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;

/**
 * Transcripción guardada: tramos (quién, desde, hasta, texto), nombres de las voces y correcciones.
 *
 * Correcciones de voces (0.5.0): cada tramo corregido guarda su etiqueta original en "orig", así siempre se puede
 * "Restaurar voces originales". El orden de las voces ("order") se congela en la primera corrección para que nadie
 * cambie de número ni de color al corregir.
 */
final class Transcript {
    final JSONObject data;
    /** Una pausa más larga que esto separa dos intervenciones de la misma persona (en pantalla y en el .txt). */
    static final double TURN_GAP_S=20;
    Transcript(JSONObject data) { this.data=data; }
    // Pricing.attach: deja el Context de la app para los costos estimados de OpenRouter (ver Pricing.estimate).
    static boolean exists(Context c,String id) { Pricing.attach(c); return FilesStore.file(c,id,".transcript.json").isFile(); }
    static Transcript load(Context c,String id) throws Exception { Pricing.attach(c); synchronized(FilesStore.LOCK) { Transcript t=new Transcript(FilesStore.read(FilesStore.file(c,id,".transcript.json")));t.clean();return t; } }
    /**
     * Guarda la transcripción y deja en el estado su comienzo legible ("snippet", ~120 caracteres) para que la
     * Biblioteca no tenga que leer cada transcripción. Como toda corrección pasa por aquí, el comienzo siempre
     * muestra los nombres actuales.
     */
    void save(Context c,String id) throws Exception {
        synchronized(FilesStore.LOCK) {
            if(!FilesStore.file(c,id,".m4a").exists())return;
            FilesStore.write(FilesStore.file(c,id,".transcript.json"),data);
            storeSnippet(c,id,this);
        }
    }
    static final int SNIPPET=120;
    private static void storeSnippet(Context c,String id,Transcript t){
        try{String snippet=t.snippet(SNIPPET);FilesStore.update(c,id,s->s.put("snippet",snippet));}catch(Exception ignored){}
    }
    /** Vuelve a calcular el comienzo guardado (p. ej. al restaurar una versión anterior copiando el archivo). */
    static void refreshSnippet(Context c,String id){
        synchronized(FilesStore.LOCK){try{if(exists(c,id))storeSnippet(c,id,load(c,id));else FilesStore.update(c,id,s->s.remove("snippet"));}catch(Exception ignored){}}
    }
    /** Comienzo legible de una grabación: el guardado, o se calcula y se guarda (transcripciones anteriores a la 0.6.0). "" sin transcripción. */
    static String snippetOf(Context c,String id){
        JSONObject state=FilesStore.state(c,id);if(state.has("snippet"))return state.optString("snippet");
        if(!exists(c,id))return "";
        refreshSnippet(c,id);return FilesStore.state(c,id).optString("snippet");
    }
    /** ¿Tiene algo dicho? (tramos con texto). */
    boolean hasText(){JSONArray s=segments();for(int i=0;i<s.length();i++){JSONObject seg=s.optJSONObject(i);if(seg!=null&&!seg.optString("text").trim().isEmpty())return true;}return false;}
    /** ¿El usuario le puso nombre a alguna voz? */
    boolean named(){JSONObject names=data.optJSONObject("names");if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();)if(!names.optString(it.next()).trim().isEmpty())return true;return false;}
    JSONArray segments() { return data.optJSONArray("segments") == null ? new JSONArray() : data.optJSONArray("segments"); }
    boolean diarized(){return data.optBoolean("diarized",true);}

    /** Quita tramos sin texto: el modelo a veces devuelve "voces" vacías que aparecían como personas fantasma. */
    void clean() throws JSONException {
        JSONArray source=data.optJSONArray("segments");if(source==null)return;JSONArray kept=new JSONArray();
        for(int i=0;i<source.length();i++){JSONObject s=source.optJSONObject(i);if(s!=null&&!s.optString("text").trim().isEmpty())kept.put(s);}
        if(kept.length()!=source.length())data.put("segments",kept);
    }
    /** Voces presentes, en orden de aparición. */
    private LinkedHashSet<String> present() throws JSONException {
        LinkedHashSet<String> ids=new LinkedHashSet<>();JSONArray s=segments();
        for(int i=0;i<s.length();i++)ids.add(s.getJSONObject(i).getString("speaker"));
        return ids;
    }
    /** Orden estable de las voces (define su número y su color). Incluye voces ya unidas a otra, para no correr la numeración. */
    List<String> order() throws JSONException {
        List<String> out=new ArrayList<>();JSONArray frozen=data.optJSONArray("order");
        if(frozen!=null)for(int i=0;i<frozen.length();i++){String id=frozen.optString(i);if(!out.contains(id))out.add(id);}
        for(String id:present())if(!out.contains(id))out.add(id);
        return out;
    }
    /** Etiqueta por defecto de una voz ("Persona N"), estable gracias a order(). */
    String defaultLabel(String id) throws JSONException {return "Persona "+(order().indexOf(id)+1);}
    /**
     * ¿El usuario ya revisó las voces? Las transcripciones nuevas lo dicen explícitamente ("reviewed");
     * en las anteriores a 0.5.0 cuenta como revisada si alguna voz tiene nombre.
     */
    boolean reviewed(){
        if(data.has("reviewed"))return data.optBoolean("reviewed");
        JSONObject names=data.optJSONObject("names");if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();)if(!names.optString(it.next()).trim().isEmpty())return true;
        return false;
    }
    /** Índice de color de una voz (el mismo en chips, encabezados y hojas). */
    int colorIndex(String id) throws JSONException {return order().indexOf(id);}
    /** Voces presentes → nombre visible ("Persona N" o el nombre que le puso el usuario). */
    LinkedHashMap<String,String> speakers() throws Exception {
        LinkedHashMap<String,String> speakers=new LinkedHashMap<>();List<String> order=order();Set<String> present=present();
        for(String id:order)if(present.contains(id))speakers.put(id,diarized()?"Persona "+(order.indexOf(id)+1):"Texto");
        JSONObject names=data.optJSONObject("names");
        if(names!=null)for(String id:speakers.keySet()){String name=names.optString(id,"").trim();if(!name.isEmpty())speakers.put(id,name);}
        return speakers;
    }
    /** Inicio (s) de cada bloque en que se procesó el audio. Para transcripciones anteriores a 0.5.0 sale de los cortes guardados. */
    List<Double> blockStarts(JSONObject state){
        List<Double> out=new ArrayList<>();JSONArray b=data.optJSONArray("blocks");
        if(b!=null){for(int i=0;i<b.length();i++)out.add(b.optDouble(i));return out;}
        JSONArray cuts=state==null?null:state.optJSONArray("cuts");
        if(cuts!=null&&cuts.length()-1==data.optInt("parts",1))for(int i=0;i+1<cuts.length();i++)out.add(cuts.optLong(i)/1000d);
        return out;
    }
    /** Proporción del tiempo que habla cada voz presente (0..1). */
    Map<String,Double> talkShare() throws JSONException {
        Map<String,Double> talk=new LinkedHashMap<>();double total=0;JSONArray s=segments();
        for(int i=0;i<s.length();i++){JSONObject seg=s.getJSONObject(i);double d=Math.max(0,seg.optDouble("end",seg.getDouble("start"))-seg.getDouble("start"));talk.merge(seg.getString("speaker"),d,Double::sum);total+=d;}
        if(total>0)for(Map.Entry<String,Double> e:talk.entrySet())e.setValue(e.getValue()/total);
        return talk;
    }
    /** Comienzo legible para la Biblioteca: «Konrad: la idea es…». */
    String snippet(int max) throws Exception {
        JSONArray s=segments();if(s.length()==0)return "";Map<String,String> names=speakers();JSONObject first=s.getJSONObject(0);
        StringBuilder b=new StringBuilder();if(diarized())b.append(names.get(first.getString("speaker"))).append(": ");
        for(int i=0;i<s.length()&&b.length()<max+20;i++)b.append(i==0?"":" ").append(s.getJSONObject(i).getString("text").trim());
        String t=b.toString().replaceAll("\\s+"," ");return t.length()>max?t.substring(0,max-1).trim()+"…":t;
    }
    /**
     * ¿Los tramos traen su propio tiempo? Sin voces, OpenAI devuelve un solo tramo por bloque (inicio = fin = comienzo
     * del bloque); OpenRouter puede devolver cada frase con su tiempo real, y ahí el aviso de «bloques» sobra.
     */
    boolean phraseTimes(){JSONArray s=segments();for(int i=0;i<s.length();i++){JSONObject seg=s.optJSONObject(i);if(seg!=null&&seg.optDouble("end",0)>seg.optDouble("start",0))return true;}return false;}
    /** true si el usuario corrigió quién habla en algún tramo. */
    boolean edited(){JSONArray s=segments();for(int i=0;i<s.length();i++)if(s.optJSONObject(i)!=null&&s.optJSONObject(i).has("orig"))return true;return false;}

    // ---------- Correcciones ----------
    interface Edit{void apply(Transcript t)throws Exception;}
    private void freeze() throws JSONException {if(!data.has("order"))data.put("order",new JSONArray(order()));data.put("reviewed",true);}
    private static void setSpeaker(JSONObject segment,String target) throws JSONException {
        String current=segment.getString("speaker");if(current.equals(target))return;
        String orig=segment.optString("orig",current);segment.put("speaker",target);
        if(orig.equals(target))segment.remove("orig");else segment.put("orig",orig);
    }
    /** Los tramos [from, to) pasan a decirlos la voz target. */
    void assign(int from,int to,String target) throws JSONException {
        freeze();JSONArray s=segments();for(int i=Math.max(0,from);i<Math.min(to,s.length());i++)setSpeaker(s.getJSONObject(i),target);
    }
    /** Intercambia dos voces en los tramos que empiezan entre fromS y toS (segundos). Arregla un cruce con un toque. */
    void swap(String a,String b,double fromS,double toS) throws JSONException {
        freeze();JSONArray s=segments();
        for(int i=0;i<s.length();i++){JSONObject seg=s.getJSONObject(i);double start=seg.getDouble("start");if(start<fromS-0.001||start>=toS)continue;
            String sp=seg.getString("speaker");if(sp.equals(a))setSpeaker(seg,b);else if(sp.equals(b))setSpeaker(seg,a);}
    }
    /**
     * La voz x es la misma persona que y: sus tramos pasan a y. Si x tenía nombre e y no, y lo hereda (se anota en
     * "inherited" para que "Restaurar" no deje ese nombre en la persona equivocada). El nombre de x se conserva:
     * no se ve mientras x no tenga tramos, y reaparece si se restaura.
     */
    void merge(String x,String y) throws JSONException {
        if(x.equals(y))return;freeze();JSONArray s=segments();
        for(int i=0;i<s.length();i++){JSONObject seg=s.getJSONObject(i);if(seg.getString("speaker").equals(x))setSpeaker(seg,y);}
        JSONObject names=data.optJSONObject("names");if(names==null){names=new JSONObject();data.put("names",names);}
        String nx=names.optString(x,"").trim();
        if(!nx.isEmpty()&&names.optString(y,"").trim().isEmpty()){names.put(y,nx);JSONObject inherited=data.optJSONObject("inherited");if(inherited==null){inherited=new JSONObject();data.put("inherited",inherited);}if(!inherited.has(y))inherited.put(y,nx);}
    }
    /** Crea una voz nueva ("Otra persona") y devuelve su id. */
    String newPerson() throws JSONException {
        freeze();List<String> order=order();int k=1;while(order.contains("manual:"+k))k++;String id="manual:"+k;
        data.getJSONArray("order").put(id);return id;
    }
    /** Deshace todas las correcciones de quién habla. Cada voz vuelve con su propio nombre (los heredados al unir se retiran). */
    void restore() throws JSONException {
        JSONArray s=segments();for(int i=0;i<s.length();i++){JSONObject seg=s.getJSONObject(i);if(seg.has("orig")){seg.put("speaker",seg.getString("orig"));seg.remove("orig");}}
        JSONObject inherited=data.optJSONObject("inherited"),names=data.optJSONObject("names");
        // Un nombre heredado al unir se retira (si el usuario no lo cambió después): vuelve a quien lo tenía.
        if(inherited!=null&&names!=null)for(Iterator<String> it=inherited.keys();it.hasNext();){String y=it.next();if(names.optString(y,"").trim().equals(inherited.optString(y).trim()))names.remove(y);}
        data.remove("inherited");
    }
    /** Aplica nombres; las voces con el mismo nombre se unen (mismo nombre = misma persona). Devuelve cuántas se unieron. */
    int applyNames(Map<String,String> names) throws Exception {
        JSONObject mapping=data.optJSONObject("names");if(mapping==null)mapping=new JSONObject();
        Set<String> present=present();Map<String,String> typedLabel=new HashMap<>();
        for(Map.Entry<String,String> name:names.entrySet()){String id=name.getKey(),v=name.getValue().trim();
            // Escribir la etiqueta propia es no ponerle nombre; escribir la de OTRA voz ("Persona 1") es decir que es esa persona.
            boolean own=v.equalsIgnoreCase(defaultLabel(id)),other=false;for(String o:present)if(!o.equals(id)&&v.equalsIgnoreCase(defaultLabel(o)))other=true;
            if(v.isEmpty()||own)mapping.remove(id);else if(other){mapping.remove(id);typedLabel.put(id,v.toLowerCase(Locale.ROOT));}else mapping.put(id,v);}
        data.put("names",mapping);data.put("reviewed",true);
        // Mismo nombre = misma persona. Una voz sin nombre se agrupa por su etiqueta ("Persona 2").
        Map<String,String> first=new HashMap<>();int merged=0;
        for(String id:order()){if(!present.contains(id))continue;String stored=mapping.optString(id,"").trim();
            String key=typedLabel.containsKey(id)?typedLabel.get(id):(stored.isEmpty()?defaultLabel(id):stored).toLowerCase(Locale.ROOT);
            String into=first.get(key);if(into==null)first.put(key,id);else{merge(id,into);merged++;}}
        return merged;
    }
    /** Corrige sobre la versión guardada más reciente. Devuelve la versión anterior (para "Deshacer"). */
    static JSONObject edit(Context c,String id,Edit op) throws Exception {
        JSONObject before;
        synchronized(FilesStore.LOCK){Transcript latest=load(c,id);before=new JSONObject(latest.data.toString());op.apply(latest);latest.save(c,id);}
        Pipeline.edited(c,id);return before;
    }
    /** Vuelve a una versión anterior (Deshacer). */
    static void replace(Context c,String id,JSONObject previous) throws Exception {
        synchronized(FilesStore.LOCK){new Transcript(new JSONObject(previous.toString())).save(c,id);}
        Pipeline.edited(c,id);
    }
    /** Nombra voces; devuelve cuántas se unieron por tener el mismo nombre. */
    static int rename(Context c,String id,Map<String,String> names) throws Exception {
        int[] merged={0};edit(c,id,t->merged[0]=t.applyNames(names));return merged[0];
    }

    // ---------- Texto exportado ----------
    String text(Recording r) throws Exception {
        StringBuilder text=new StringBuilder(r.title).append("\n")
            .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",new Locale("es","CL")).format(new Date(r.created))).append("\n\n");
        if(data.optBoolean("demo"))text.append("EJEMPLO DE DEMOSTRACIÓN · No proviene de una transcripción real.\n\n");
        if(diarized()){if(!reviewed()&&segments().length()>0)text.append("Voces separadas automáticamente: pueden tener errores.\n\n");}
        else if(data.optInt("parts",1)>1&&!phraseTimes())text.append("Audio procesado en bloques. Los tiempos indican el inicio de cada bloque, no de cada frase.\n\n");
        Map<String,String> names=speakers(); JSONArray segments=segments();
        // Los tramos seguidos de la misma persona van en un solo párrafo (con la hora del primero).
        for(int i=0;i<segments.length();){
            JSONObject segment=segments.getJSONObject(i);String speaker=segment.getString("speaker");StringBuilder turn=new StringBuilder(segment.getString("text").trim());double end=segment.optDouble("end",segment.getDouble("start"));int j=i+1;
            while(j<segments.length()){JSONObject next=segments.getJSONObject(j);if(!next.getString("speaker").equals(speaker)||next.getDouble("start")-end>TURN_GAP_S)break;turn.append(' ').append(next.getString("text").trim());end=Math.max(end,next.optDouble("end",end));j++;}
            text.append("[").append(Recording.time((long)(segment.getDouble("start")*1000))).append("] ").append(names.get(speaker)).append(": ").append(turn).append("\n\n");
            i=j;
        }
        if(segments.length()==0)text.append("No se detectó habla en este audio.\n");
        return text.toString();
    }

    // ---------- Unión de bloques ----------
    /**
     * Une las respuestas de cada bloque. "_known" dice qué nombres enviados como muestras de voz reconoció el modelo:
     * - objeto {nombre enviado → voz} (0.5.0): nombres únicos como "voz_1", que no chocan con las letras del modelo;
     * - arreglo de letras (hasta 0.4.4): la letra del bloque 1.
     * "_prefix" (0.6.0, «Segunda pasada con tus correcciones»): las voces que el modelo NO reconoció llevan este prefijo.
     * Las reconocidas vuelven con el mismo id de la versión anterior ("block0:A", "voice:me"…), y una voz nueva a la que
     * el modelo llame "A" no debe confundirse con la "A" de la versión anterior.
     * Los tramos sin texto se descartan.
     */
    static Transcript fromParts(List<JSONObject> parts,List<Double> offsets) throws Exception {
        JSONArray segments=new JSONArray();
        for(int p=0;p<parts.size();p++){
            JSONObject response=parts.get(p); JSONArray source=response.optJSONArray("segments");String prefix=response.optString("_prefix","");
            Map<String,String> map=new HashMap<>();Set<String> legacy=new HashSet<>();Object known=response.opt("_known");
            if(known instanceof JSONObject){JSONObject k=(JSONObject)known;for(Iterator<String> it=k.keys();it.hasNext();){String name=it.next();map.put(name,k.getString(name));}}
            else if(known instanceof JSONArray){JSONArray k=(JSONArray)known;for(int i=0;i<k.length();i++)legacy.add(k.optString(i));}
            if(source==null)throw new java.io.IOException("El proveedor no devolvió los segmentos de hablantes esperados.");
            for(int i=0;i<source.length();i++){
                JSONObject segment=source.getJSONObject(i);
                if(!segment.has("speaker") || !segment.has("start") || !segment.has("end") || !segment.has("text"))throw new java.io.IOException("La transcripción recibida está incompleta.");
                if(segment.optString("text").trim().isEmpty())continue;
                String speaker=segment.isNull("speaker")?"unknown":segment.getString("speaker");
                String id=map.containsKey(speaker)?map.get(speaker):prefix+(parts.size()<=1?speaker:(p>0&&legacy.contains(speaker)?"block0:"+speaker:"block"+p+":"+speaker));
                segments.put(new JSONObject().put("speaker",id)
                    .put("start",segment.getDouble("start")+offsets.get(p)).put("end",segment.getDouble("end")+offsets.get(p)).put("text",segment.getString("text")));
            }
        }
        // Con voces si ALGUNA parte vino con voces (0.8.0): con OpenRouter cada respuesta dice lo suyo, y una parte 1 en
        // silencio (sin hablantes) no debe dejar como «Texto» a las personas de las demás. Con OpenAI todas traen lo mismo.
        boolean diarized=parts.isEmpty();for(JSONObject part:parts)if(part.optBoolean("_diarized",true)){diarized=true;break;}
        JSONObject data=new JSONObject().put("segments",segments).put("parts",parts.size()).put("names",new JSONObject()).put("diarized",diarized).put("reviewed",false);
        if(parts.size()>1)data.put("blocks",new JSONArray(offsets));
        return new Transcript(data);
    }
}
