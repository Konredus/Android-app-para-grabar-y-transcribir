package cl.vozlocal.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/**
 * Guardado en la carpeta rápida (p. ej. Drive/0-Inbox): la nota .md (o el .txt si no hay nota) con un toque.
 * «Actualizar» reemplaza EL MISMO documento (guardado en el estado como inboxUri), sin crear copias «(1)».
 * Bloquea (escribe en el proveedor de documentos): se llama desde un hilo de fondo.
 */
final class Inbox {
    private Inbox(){}
    /** Evita dos guardados a la vez de la misma grabación (p. ej. la notificación y el botón): crearían dos archivos. */
    private static final Object SAVING=new Object();

    static boolean configured(Context c){return !new Settings(c).inboxTree().isEmpty();}
    static String folderName(Context c){return new Settings(c).inboxName();}
    static long savedAt(Context c,String id){return FilesStore.state(c,id).optLong("inboxAt",0);}
    /** "md", "txt" o "" si nunca se guardó. */
    static String savedKind(Context c,String id){return savedAt(c,id)==0?"":FilesStore.state(c,id).optString("inboxKind","txt");}

    /** Escribe la nota .md (o el .txt) en la carpeta rápida y devuelve el documento. Los errores llegan como HttpApi.UserAction con un texto para la persona. */
    static Uri save(Context c,Recording r)throws Exception{
        synchronized(SAVING){
            String selected=new Settings(c).inboxTree(),folder=folderName(c);
            if(selected.isEmpty())throw new HttpApi.UserAction("Primero elige tu carpeta rápida en Ajustes.");
            Recording latest=FilesStore.recording(c,r.id);if(latest!=null)r=latest;
            if(!Transcript.exists(c,r.id))throw new HttpApi.UserAction("Esta grabación aún no tiene transcripción.");
            // La hora se toma ANTES de leer: un cambio que llegue mientras se escribe deja el guardado como «por actualizar».
            long at=System.currentTimeMillis();
            boolean md=Notes.exists(c,r.id);String kind=md?"md":"txt";
            String content=md?Notes.markdown(c,r):Transcript.load(c,r.id).text(r);
            String name=md?TranscriptExport.noteFilename(r.title,r.created):TranscriptExport.filename(r.title);
            String marks=md?signature(Marks.list(c,r.id)):null;
            Uri tree=Uri.parse(selected),doc=null;String mode="new";
            try{
                String stored=FilesStore.state(c,r.id).optString("inboxUri","");
                // Con ids por ruta (carpetas del teléfono), el archivo que esta grabación guardó y que se movió o borró de la
                // carpeta pudo pasar a ser el de OTRA grabación con el mismo nombre: ese no se pisa, se crea uno nuevo.
                Uri previous=claimedByOther(c,r.id,stored)?null:reusable(c,tree,stored);
                if(previous!=null){
                    try{LocalStorage.writeText(c,previous,content);doc=rename(c,previous,name);mode="update";}
                    catch(Exception e){Diagnostics.event("transcript_inbox_update_failed",r.id,"error_class",e.getClass().getSimpleName());}
                }
                if(doc==null){doc=create(c,tree,name,md);LocalStorage.writeText(c,doc,content);}
            }catch(Exception e){
                Diagnostics.event("transcript_inbox_failed",r.id,"kind",kind,"error_class",e.getClass().getSimpleName());
                HttpApi.UserAction friendly=new HttpApi.UserAction("No se pudo guardar en "+folder+". Puede que Android haya retirado el permiso a esa carpeta: elígela de nuevo en Ajustes o usa «Otra carpeta».");
                friendly.initCause(e);throw friendly;
            }
            Uri saved=doc;String how=mode,title=r.title;
            FilesStore.update(c,r.id,s->{s.put("inboxUri",saved.toString()).put("inboxAt",at).put("inboxKind",kind).put("inboxTitle",title);if(marks==null)s.remove("inboxMarks");else s.put("inboxMarks",marks);});
            Diagnostics.event("transcript_inbox_saved",r.id,"kind",kind,"mode",how);
            return doc;
        }
    }

    /**
     * ¿Hay cambios posteriores al último guardado? Transcripción, nota o título modificados después de inboxAt; una
     * nota que apareció (o se borró) desde entonces; o momentos ★ distintos a los que llevaba la nota guardada.
     */
    static boolean outdated(Context c,String id){
        JSONObject st=FilesStore.state(c,id);long at=st.optLong("inboxAt",0);if(at==0)return false;
        String kind=st.optString("inboxKind","txt");boolean note=Notes.exists(c,id);
        if(FilesStore.file(c,id,".transcript.json").lastModified()>at)return true;
        if(note!=kind.equals("md"))return true;
        if(note&&FilesStore.file(c,id,".note.json").lastModified()>at)return true;
        if(st.has("inboxTitle")){if(!st.optString("inboxTitle").equals(title(c,id)))return true;}
        else if(FilesStore.file(c,id,".json").lastModified()>at)return true;
        return note&&st.has("inboxMarks")&&!st.optString("inboxMarks").equals(signature(Marks.list(c,id)));
    }
    /** Título guardado (lectura directa del .json: barato aunque se llame por cada fila de la Biblioteca). */
    private static String title(Context c,String id){try{return FilesStore.read(FilesStore.file(c,id,".json")).optString("title");}catch(Exception e){return "";}}
    static String signature(JSONArray marks){return marks==null||marks.length()==0?"0":Integer.toHexString(marks.toString().hashCode())+":"+marks.length();}

    /**
     * ¿Otra grabación guardó después en este mismo documento? Entonces es suyo (el proveedor reutilizó el id tras mover o
     * borrar el archivo de esta) y esta grabación no debe escribirlo.
     */
    static boolean claimedByOther(Context c,String id,String uri){
        if(uri==null||uri.isEmpty())return false;
        long mine=FilesStore.state(c,id).optLong("inboxAt",0);
        for(Recording other:Recording.list(c)){
            if(other.id.equals(id))continue;JSONObject st=FilesStore.state(c,other.id);
            if(uri.equals(st.optString("inboxUri",""))&&st.optLong("inboxAt",0)>=mine)return true;
        }
        return false;
    }
    /** El documento guardado antes, si sigue existiendo, se puede escribir y está en la carpeta rápida ACTUAL. */
    private static Uri reusable(Context c,Uri tree,String previous){
        if(previous==null||previous.isEmpty())return null;
        try{
            Uri doc=Uri.parse(previous);
            if(!String.valueOf(doc.getAuthority()).equals(tree.getAuthority())||!DocumentsContract.getTreeDocumentId(doc).equals(DocumentsContract.getTreeDocumentId(tree)))return null;
            try(Cursor cursor=c.getContentResolver().query(doc,new String[]{Document.COLUMN_FLAGS},null,null,null)){
                if(cursor==null||!cursor.moveToFirst())return null;
                return (cursor.getInt(0)&Document.FLAG_SUPPORTS_WRITE)!=0?doc:null;
            }
        }catch(Exception e){return null;}
    }
    /** Crea el documento; si el proveedor no acepta text/markdown, lo intenta como text/plain y corrige el nombre. */
    private static Uri create(Context c,Uri tree,String name,boolean markdown)throws Exception{
        ContentResolver resolver=c.getContentResolver();
        Uri dir=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));Uri doc=null;
        if(markdown){try{doc=DocumentsContract.createDocument(resolver,dir,"text/markdown",name);}catch(SecurityException e){throw e;}catch(Exception e){Diagnostics.event("transcript_inbox_mime_fallback",null,"error_class",e.getClass().getSimpleName());}}
        if(doc==null){doc=DocumentsContract.createDocument(resolver,dir,"text/plain",name);if(markdown&&doc!=null)doc=rename(c,doc,name);}
        if(doc==null)throw new IOException("El proveedor no creó el archivo");
        return doc;
    }
    /** Deja el documento con el nombre esperado (título o extensión cambiaron). Si el proveedor no deja renombrar, se queda como está. */
    private static Uri rename(Context c,Uri doc,String name){
        try{
            try(Cursor cursor=c.getContentResolver().query(doc,new String[]{Document.COLUMN_DISPLAY_NAME},null,null,null)){
                if(cursor==null||!cursor.moveToFirst()||name.equals(cursor.getString(0)))return doc;
            }
            Uri renamed=DocumentsContract.renameDocument(c.getContentResolver(),doc,name);
            return renamed==null?doc:renamed;
        }catch(Exception e){return doc;}
    }
}
