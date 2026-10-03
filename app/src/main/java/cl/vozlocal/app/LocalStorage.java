package cl.vozlocal.app;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class LocalStorage {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static void enqueue(Context c,String id){Context app=c.getApplicationContext();IO.execute(()->{try{Recording r=FilesStore.recording(app,id);if(r!=null)save(app,r);}catch(Exception e){try{FilesStore.update(app,id,s->s.put("localStatus",Lang.str(app,R.string.inbox_local_failed)));}catch(Exception ignored){}Diagnostics.event("local_copy_failed",id,"error_class",e.getClass().getSimpleName());}});}
    static void save(Context c,Recording r)throws Exception{
        String selected=new Settings(c).prefs.getString("localTree","");if(selected.isEmpty())return;
        Uri tree=Uri.parse(selected),root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        Uri folder=child(c,tree,root,r.id,DocumentsContract.Document.MIME_TYPE_DIR);
        String stamp=selected+"|"+r.audio(c).length()+"|"+r.audio(c).lastModified();
        if(!stamp.equals(FilesStore.state(c,r.id).optString("localAudioStamp"))){try(InputStream in=new FileInputStream(r.audio(c))){write(c,child(c,tree,folder,"audio.m4a","audio/mp4"),in);}FilesStore.update(c,r.id,s->s.put("localAudioStamp",stamp));}
        // Los nombres de los archivos van en el idioma de la app (0.9.0): «transcripcion.txt», «transcript.txt»… (ver named).
        Map<String,Uri> existing=names(c,tree,folder);
        writeText(c,named(c,tree,folder,existing,R.string.inbox_file_info,"text/plain"),r.title+"\n"+Lang.str(c,R.string.inbox_info_duration,Recording.time(r.duration)));
        if(Transcript.exists(c,r.id)){Transcript transcript=Transcript.load(c,r.id);writeText(c,named(c,tree,folder,existing,R.string.inbox_file_transcript,"text/plain"),transcript.text(r));writeText(c,named(c,tree,folder,existing,R.string.inbox_file_transcript_json,"application/json"),transcript.data.toString(2));}
        // La nota para el segundo cerebro (0.6.0), en Markdown. Si falla, el resto de la copia sigue valiendo.
        if(Notes.exists(c,r.id)){try{writeText(c,named(c,tree,folder,existing,R.string.inbox_file_note,"text/markdown"),Notes.markdown(c,r));}catch(Exception e){Diagnostics.event("local_note_failed",r.id,"error_class",e.getClass().getSimpleName());}}
        // La versión actual no tiene nota (p. ej. al volver a transcribir): la nota.md de antes llevaba otro resumen y otro
        // texto, así que se quita (con su nombre en cualquiera de los tres idiomas). Sin transcripción (una versión nueva en
        // curso) no se toca: la copia sigue siendo la anterior.
        else if(Transcript.exists(c,r.id)){
            try{for(String name:new LinkedHashSet<>(Arrays.asList(Lang.all(R.string.inbox_file_note)))){Uri old=existing.get(name);if(old!=null)DocumentsContract.deleteDocument(c.getContentResolver(),old);}}
            catch(Exception e){Diagnostics.event("local_note_delete_failed",r.id,"error_class",e.getClass().getSimpleName());}
        }
        FilesStore.update(c,r.id,s->s.put("localStatus",Lang.str(c,R.string.inbox_local_done)));Diagnostics.event("local_copy_complete",r.id);
    }
    /**
     * El archivo nameId dentro de folder, con su nombre en el idioma de la app. Si quedó con el nombre de otro idioma (la
     * persona cambió de idioma), se renombra en vez de crear otro al lado; si el proveedor no deja renombrar, se sigue
     * usando ese. existing: lo que ya había en folder (names).
     */
    private static Uri named(Context c,Uri tree,Uri folder,Map<String,Uri> existing,int nameId,String mime)throws Exception{
        String name=Lang.str(c,nameId);Uri found=existing.get(name);if(found!=null)return found;
        for(String other:Lang.all(nameId)){
            Uri old=existing.get(other);if(old==null)continue;
            try{Uri renamed=DocumentsContract.renameDocument(c.getContentResolver(),old,name);return renamed!=null?renamed:old;}
            catch(Exception e){Diagnostics.event("local_copy_rename_failed",null,"error_class",e.getClass().getSimpleName());return old;}
        }
        return child(c,tree,folder,name,mime);
    }
    /** Lo que hay dentro de parent, por nombre (una sola consulta; con nombres repetidos, el primero, como find). */
    private static Map<String,Uri> names(Context c,Uri tree,Uri parent)throws Exception{
        Map<String,Uri> out=new HashMap<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(android.database.Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor!=null)while(cursor.moveToNext()){String name=cursor.getString(1);if(name!=null&&!out.containsKey(name))out.put(name,DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)));}
        }
        return out;
    }
    /** El documento con ese nombre dentro de parent, o null si no existe (no lo crea). */
    static Uri find(Context c,Uri tree,Uri parent,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(android.database.Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor!=null)while(cursor.moveToNext())if(name.equals(cursor.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
        }
        return null;
    }
    static Uri child(Context c,Uri tree,Uri parent,String name,String mime)throws Exception{
        Uri found=find(c,tree,parent,name);if(found!=null)return found;
        Uri created;
        try{created=DocumentsContract.createDocument(c.getContentResolver(),parent,mime,name);}
        catch(SecurityException e){throw e;}
        // Un proveedor que no conoce text/markdown: application/octet-stream conserva el nombre tal cual («nota.md», no «nota.md.txt»).
        catch(Exception e){if(!"text/markdown".equals(mime))throw e;created=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/octet-stream",name);}
        if(created==null)throw new IOException();return created;
    }
    static void writeText(Context c,Uri uri,String value)throws Exception{write(c,uri,new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));}
    static void write(Context c,Uri uri,InputStream input)throws Exception{try(InputStream in=input;OutputStream out=open(c,uri)){if(out==null)throw new IOException();byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))>=0)out.write(buffer,0,read);}}
    /** "wt" trunca el archivo existente; algunos proveedores de nube (p. ej. Google Drive) solo aceptan "w", que en ellos ya reemplaza el contenido. */
    private static OutputStream open(Context c,Uri uri)throws Exception{try{return c.getContentResolver().openOutputStream(uri,"wt");}catch(IllegalArgumentException|UnsupportedOperationException|FileNotFoundException e){Diagnostics.event("local_copy_mode_fallback",null);return c.getContentResolver().openOutputStream(uri,"w");}}
}
