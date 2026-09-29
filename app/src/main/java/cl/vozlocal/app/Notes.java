package cl.vozlocal.app;

import android.content.Context;
import org.json.*;
import java.util.*;

/**
 * «Nota para tu segundo cerebro»: resumen, decisiones, tareas, frases y etiquetas armadas por IA a partir de la
 * transcripción. STUB de la fase 0 (ver docs/diseno/SPEC-0.6.md); la parte «notes» lo implementa.
 */
final class Notes {
    private Notes(){}
    static boolean exists(Context c,String id){return FilesStore.file(c,id,".note.json").isFile();}
    static JSONObject load(Context c,String id){try{return exists(c,id)?FilesStore.read(FilesStore.file(c,id,".note.json")):null;}catch(Exception e){return null;}}
    /** ¿Hay clave para el proveedor elegido? */
    static boolean canGenerate(Context c){Settings s=new Settings(c);return s.noteProvider().equals("anthropic")?s.hasAnthropicKey():s.provider().equals("openai")&&s.hasKey();}
    /** Arma y guarda la nota (bloquea: se llama desde el trabajo de transcripción o un hilo de fondo). */
    static void generate(Context c,Recording r,HttpApi http)throws Exception{throw new UnsupportedOperationException("Nota aún no disponible");}
    /** Reemplaza {S1}, {S2}… por el nombre actual de cada voz. */
    static String resolve(String text,Map<String,String> names){return text==null?"":text;}
    /** Nota completa en Markdown (frontmatter + secciones + transcripción). Sin nota: solo la transcripción. */
    static String markdown(Context c,Recording r)throws Exception{return Transcript.load(c,r.id).text(r);}
    static void setTaskDone(Context c,String id,int index,boolean done)throws Exception{}
    static void delete(Context c,String id){FilesStore.file(c,id,".note.json").delete();}
}
