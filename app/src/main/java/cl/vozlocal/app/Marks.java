package cl.vozlocal.app;

import android.content.Context;
import org.json.*;

/** Momentos ★ marcados al grabar: {t (ms), label}. STUB de la fase 0 (ver SPEC-0.6.md); la parte «media» lo implementa. */
final class Marks {
    private Marks(){}
    static JSONArray list(Context c,String id){JSONArray a=FilesStore.state(c,id).optJSONArray("marks");return a==null?new JSONArray():a;}
    static void add(Context c,String id,long ms,String label)throws Exception{}
    static void remove(Context c,String id,int index)throws Exception{}
    static void rename(Context c,String id,int index,String label)throws Exception{}
}
