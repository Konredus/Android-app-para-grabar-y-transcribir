package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;

/**
 * Momentos ★ marcados al grabar. Viven en el estado de la grabación (clave «marks» de {@code <id>.sync.json}):
 * {@code [{"t": ms, "label": ""}]}, siempre ordenados por t (ms de grabación, sin contar las pausas).
 * Los índices de {@link #remove} y {@link #rename} son los de {@link #list} (ya ordenada).
 */
final class Marks {
    private Marks(){}
    /** Largo máximo de la palabra de una marca («precio», «idea»). */
    static final int LABEL_MAX=40;

    /** Marcas ordenadas por t. Nunca null; ignora entradas dañadas. */
    static JSONArray list(Context c,String id){return sorted(FilesStore.state(c,id).optJSONArray("marks"));}
    static int count(Context c,String id){return list(c,id).length();}
    /** Solo los tiempos (ms), en orden: para dibujar los ★ sobre la onda. */
    static long[] times(Context c,String id){JSONArray a=list(c,id);long[] t=new long[a.length()];for(int i=0;i<t.length;i++)t[i]=a.optJSONObject(i).optLong("t");return t;}
    /** «1 momento marcado», «3 momentos marcados». */
    static String describe(int n){return n==1?"1 momento marcado":n+" momentos marcados";}

    /**
     * Agrega una marca en ms (de grabación). Si ya hay una en el mismo instante no la duplica:
     * solo le pone la palabra nueva, si viene una.
     */
    static void add(Context c,String id,long ms,String label)throws Exception{
        long t=Math.max(0,ms);String text=clean(label);
        FilesStore.update(c,id,s->{
            JSONArray a=sorted(s.optJSONArray("marks"));
            for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.optLong("t")==t){if(!text.isEmpty())m.put("label",text);s.put("marks",a);return;}}
            a.put(new JSONObject().put("t",t).put("label",text));s.put("marks",sorted(a));
        });
    }
    /** Quita la marca en esa posición de {@link #list}. Un índice fuera de rango no hace nada. */
    static void remove(Context c,String id,int index)throws Exception{
        FilesStore.update(c,id,s->{JSONArray a=sorted(s.optJSONArray("marks"));if(index>=0&&index<a.length())a.remove(index);s.put("marks",a);});
    }
    /** Cambia (o borra, con "") la palabra de la marca en esa posición de {@link #list}. */
    static void rename(Context c,String id,int index,String label)throws Exception{
        String text=clean(label);
        FilesStore.update(c,id,s->{JSONArray a=sorted(s.optJSONArray("marks"));if(index>=0&&index<a.length())a.getJSONObject(index).put("label",text);s.put("marks",a);});
    }
    /** Reemplaza todas las marcas (lo usa la grabadora al terminar, para que el archivo quede igual a lo marcado). */
    static void setAll(Context c,String id,JSONArray marks)throws Exception{
        FilesStore.update(c,id,s->s.put("marks",sorted(marks)));
    }

    static String clean(String label){
        String t=label==null?"":label.replaceAll("\\s+"," ").trim();
        return t.length()>LABEL_MAX?t.substring(0,LABEL_MAX).trim():t;
    }
    /** Copia ordenada por t; descarta lo que no es una marca válida y completa «label». */
    static JSONArray sorted(JSONArray raw){
        ArrayList<JSONObject> all=new ArrayList<>();
        if(raw!=null)for(int i=0;i<raw.length();i++){
            JSONObject m=raw.optJSONObject(i);if(m==null||!m.has("t"))continue;
            long t=m.optLong("t",-1);if(t<0)continue;
            try{JSONObject copy=new JSONObject(m.toString());copy.put("t",t).put("label",clean(m.optString("label","")));all.add(copy);}catch(JSONException ignored){}
        }
        all.sort((a,b)->Long.compare(a.optLong("t"),b.optLong("t")));
        JSONArray out=new JSONArray();for(JSONObject m:all)out.put(m);return out;
    }
}
