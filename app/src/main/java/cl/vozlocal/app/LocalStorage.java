package cl.vozlocal.app;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

final class LocalStorage {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static void enqueue(Context c,String id){Context app=c.getApplicationContext();IO.execute(()->{try{Recording r=FilesStore.recording(app,id);if(r!=null)save(app,r);}catch(Exception e){try{FilesStore.update(app,id,s->s.put("localStatus","No se pudo actualizar la carpeta · el original sigue en la app"));}catch(Exception ignored){}Diagnostics.event("local_copy_failed",id,"error_class",e.getClass().getSimpleName());}});}
    static void save(Context c,Recording r)throws Exception{
        String selected=new Settings(c).prefs.getString("localTree","");if(selected.isEmpty())return;
        Uri tree=Uri.parse(selected),root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        Uri folder=child(c,tree,root,r.id,DocumentsContract.Document.MIME_TYPE_DIR);
        String stamp=selected+"|"+r.audio(c).length()+"|"+r.audio(c).lastModified();
        if(!stamp.equals(FilesStore.state(c,r.id).optString("localAudioStamp"))){try(InputStream in=new FileInputStream(r.audio(c))){write(c,child(c,tree,folder,"audio.m4a","audio/mp4"),in);}FilesStore.update(c,r.id,s->s.put("localAudioStamp",stamp));}
        writeText(c,child(c,tree,folder,"informacion.txt","text/plain"),r.title+"\nDuración: "+Recording.time(r.duration));
        if(Transcript.exists(c,r.id)){Transcript transcript=Transcript.load(c,r.id);writeText(c,child(c,tree,folder,"transcripcion.txt","text/plain"),transcript.text(r));writeText(c,child(c,tree,folder,"transcripcion.json","application/json"),transcript.data.toString(2));}
        FilesStore.update(c,r.id,s->s.put("localStatus","Copia local actualizada"));Diagnostics.event("local_copy_complete",r.id);
    }
    static Uri child(Context c,Uri tree,Uri parent,String name,String mime)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(android.database.Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor!=null)while(cursor.moveToNext())if(name.equals(cursor.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
        }
        Uri created=DocumentsContract.createDocument(c.getContentResolver(),parent,mime,name);if(created==null)throw new IOException();return created;
    }
    static void writeText(Context c,Uri uri,String value)throws Exception{write(c,uri,new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));}
    static void write(Context c,Uri uri,InputStream input)throws Exception{try(InputStream in=input;OutputStream out=open(c,uri)){if(out==null)throw new IOException();byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))>=0)out.write(buffer,0,read);}}
    /** "wt" trunca el archivo existente; algunos proveedores de nube (p. ej. Google Drive) solo aceptan "w", que en ellos ya reemplaza el contenido. */
    private static OutputStream open(Context c,Uri uri)throws Exception{try{return c.getContentResolver().openOutputStream(uri,"wt");}catch(IllegalArgumentException|UnsupportedOperationException|FileNotFoundException e){Diagnostics.event("local_copy_mode_fallback",null);return c.getContentResolver().openOutputStream(uri,"w");}}
}
