package cl.vozlocal.app;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 0.9.8: Papelera. Eliminar una grabación ya no la borra: sus archivos (audio, transcripción, nota, voces…) pasan a
 * «files/trash», fuera de la Biblioteca, de las métricas y del motor, y se pueden restaurar desde Ajustes → Guardar y
 * copias → Papelera. A los {@link #KEEP_DAYS} días se borran solas (al abrir la app), y «Vaciar papelera» las borra ya.
 * Mover es renombrar dentro del mismo almacenamiento: es inmediato y no duplica nada.
 */
final class Trash {
    private Trash(){}
    static final int KEEP_DAYS=30;
    private static final long DAY_MS=24L*60*60_000;
    /** Marca con la hora en que se eliminó («id.trashed»). */
    private static final String MARK=".trashed";

    static File dir(Context c){File d=new File(c.getFilesDir(),"trash");d.mkdirs();return d;}

    /** Una grabación en la papelera. */
    static final class Item{
        final Recording r;final long at,bytes;
        Item(Recording r,long at,long bytes){this.r=r;this.at=at;this.bytes=bytes;}
        /** Días que faltan para que se borre sola (al menos 1 mientras siga aquí). */
        int daysLeft(long now){return (int)Math.max(1,(at+KEEP_DAYS*DAY_MS-now+DAY_MS-1)/DAY_MS);}
    }

    /**
     * Manda la grabación a la papelera. Antes detiene lo que se estuviera haciendo con ella (transcripción en curso,
     * partes preparadas). Devuelve false si no se pudo (no queda a medias: lo movido vuelve a su lugar).
     */
    static boolean move(Context c,Recording r){
        try{Pipeline.cancel(c,r.id,false);}catch(Exception ignored){}
        AudioParts.clearBlocks(c,r.id);Transcriber.clearDone(c,r.id);
        synchronized(FilesStore.LOCK){
            File from=Recording.directory(c),to=dir(c);
            if(!r.audio(c).exists())return false;
            File[] files=from.listFiles((d,name)->name.startsWith(r.id+"."));if(files==null)return false;
            List<File> moved=new ArrayList<>();
            for(File f:files){
                File target=new File(to,f.getName());target.delete();
                if(f.renameTo(target))moved.add(target);
                else{for(File m:moved)m.renameTo(new File(from,m.getName()));return false;}
            }
            try{FilesStore.write(new File(to,r.id+MARK),new JSONObject().put("at",System.currentTimeMillis()));}catch(Exception ignored){}
            FilesStore.version.incrementAndGet();
        }
        Diagnostics.event("recording_trashed",r.id);
        return true;
    }

    /** Lo que hay en la papelera, lo eliminado más recientemente primero. */
    static List<Item> list(Context c){
        List<Item> out=new ArrayList<>();File d=dir(c);
        File[] audios=d.listFiles((x,name)->name.endsWith(".m4a"));if(audios==null)return out;
        for(File audio:audios){
            String id=audio.getName().substring(0,audio.getName().length()-4);
            Recording r=new Recording(id,Recording.defaultTitle(audio.lastModified()),audio.lastModified(),0);
            try{JSONObject j=FilesStore.read(new File(d,id+".json"));r.title=j.getString("title");r.created=j.getLong("created");r.duration=j.getLong("duration");}
            catch(Exception e){try{r.duration=AudioConvert.duration(audio);}catch(Exception ignored){}}
            long at=audio.lastModified();File mark=new File(d,id+MARK);
            try{at=FilesStore.read(mark).optLong("at",mark.lastModified());}catch(Exception e){if(mark.exists())at=mark.lastModified();}
            long bytes=0;File[] files=d.listFiles((x,name)->name.startsWith(id+"."));if(files!=null)for(File f:files)bytes+=f.length();
            out.add(new Item(r,at,bytes));
        }
        out.sort((a,b)->Long.compare(b.at,a.at));
        return out;
    }
    static int count(Context c){String[] names=dir(c).list((x,name)->name.endsWith(".m4a"));return names==null?0:names.length;}
    static long bytes(Context c){long sum=0;File[] files=dir(c).listFiles();if(files!=null)for(File f:files)sum+=f.length();return sum;}

    /** Devuelve la grabación a la Biblioteca, tal como estaba. */
    static boolean restore(Context c,String id){
        synchronized(FilesStore.LOCK){
            File from=dir(c),to=Recording.directory(c);
            File[] files=from.listFiles((d,name)->name.startsWith(id+"."));
            if(files==null||!new File(from,id+".m4a").exists())return false;
            boolean ok=true;
            for(File f:files){
                if(f.getName().equals(id+MARK)||f.getName().startsWith(id+MARK+".")){f.delete();continue;}
                if(!f.renameTo(new File(to,f.getName())))ok=false;
            }
            FilesStore.version.incrementAndGet();
            Diagnostics.event("recording_restored",id,"result",ok?"ok":"partial");
            return ok;
        }
    }
    /** Borra para siempre una grabación de la papelera. */
    static void purge(Context c,String id){
        synchronized(FilesStore.LOCK){File[] files=dir(c).listFiles((d,name)->name.startsWith(id+"."));if(files!=null)for(File f:files)f.delete();}
        Diagnostics.event("recording_deleted",id);
    }
    /** Vacía la papelera. Devuelve cuántas grabaciones borró. */
    static int empty(Context c){
        int n=count(c);
        synchronized(FilesStore.LOCK){File[] files=dir(c).listFiles();if(files!=null)for(File f:files)f.delete();}
        Diagnostics.event("trash_emptied",null,"count",n);
        return n;
    }
    /** Borra lo que lleva más de KEEP_DAYS en la papelera. Corre al abrir la app, en segundo plano. */
    static int expire(Context c,long now){
        int n=0;
        for(Item item:list(c))if(now-item.at>KEEP_DAYS*DAY_MS){purge(c,item.r.id);n++;}
        if(n>0)Diagnostics.event("trash_expired",null,"count",n);
        return n;
    }
}
