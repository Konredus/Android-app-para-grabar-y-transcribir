package cl.vozlocal.app;

import android.content.Context;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 0.9.7 («un solo botón»): al terminar de transcribir, la nota (o el texto) se guarda sola en la carpeta rápida
 * (p. ej. Drive/0-Inbox), sin tocar «Guardar». Y si después cambia algo de una grabación ya guardada (nombres de las
 * voces, el título, la nota), el mismo archivo se actualiza solo a los pocos segundos: nunca queda una copia atrasada.
 * Se apaga en Ajustes → Guardar y copias. Escribe en el proveedor de documentos: siempre desde un hilo de fondo.
 */
final class AutoSave {
    private AutoSave(){}
    /** Espera tras el último cambio antes de actualizar: varios cambios seguidos (nombrar 4 voces) hacen un solo guardado. */
    static final long SETTLE_MS=4000;
    private static final ScheduledExecutorService WORKER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"VozLocal-autosave");t.setDaemon(true);return t;});
    private static final Map<String,ScheduledFuture<?>> PENDING=new ConcurrentHashMap<>();

    static boolean on(Context c){return new Settings(c).autoInbox()&&Inbox.configured(c);}

    /**
     * Recién transcrita (la llama el motor, en su hilo). Devuelve true si quedó guardada. Si falla (p. ej. Drive retiró
     * el permiso de la carpeta) queda anotado en el estado ("inboxAutoFailed") y en la bitácora, y el aviso de «lista»
     * lo dice: la persona elige la carpeta de nuevo y guarda con un toque, como antes.
     */
    static boolean afterTranscription(Context c,Recording r){
        if(!on(c))return false;
        String folder=Next.shortName(Inbox.folderName(c));
        try{
            Inbox.save(c,r);
            FilesStore.update(c,r.id,s->s.remove("inboxAutoFailed"));
            Pipeline.log(c,r.id,Lang.str(c,R.string.eng_log_auto_saved,folder));
            Diagnostics.event("auto_inbox",r.id,"result","saved","stage","done");
            return true;
        }catch(Exception e){
            Throwable cause=e.getCause()==null?e:e.getCause();
            try{FilesStore.update(c,r.id,s->s.put("inboxAutoFailed",true));}catch(Exception ignored){}
            Pipeline.log(c,r.id,Lang.str(c,R.string.eng_log_auto_save_failed,folder));
            Diagnostics.event("auto_inbox",r.id,"result","failed","stage","done","error_class",cause.getClass().getSimpleName(),"permission",Inbox.permission(c));
            return false;
        }
    }

    /**
     * Se eligió la carpeta rápida de nuevo (p. ej. porque Drive había retirado el permiso): lo que no se pudo guardar
     * solo se guarda ahora, sin tocar cada grabación.
     */
    static void retryFailed(Context c){
        Context app=c.getApplicationContext();
        WORKER.execute(()->{
            try{
                if(!on(app))return;int saved=0;
                for(Recording r:Recording.list(app)){
                    org.json.JSONObject st=FilesStore.state(app,r.id);
                    if(!st.optBoolean("inboxAutoFailed")||st.optBoolean("requested")||!Transcript.exists(app,r.id))continue;
                    try{Inbox.save(app,r);FilesStore.update(app,r.id,s->s.remove("inboxAutoFailed"));saved++;}catch(Exception e){break;}
                }
                if(saved>0){Diagnostics.event("auto_inbox",null,"result","saved","stage","retry","count",saved);FilesStore.version.incrementAndGet();}
            }catch(RuntimeException ignored){}
        });
    }

    /** Algo cambió en esta grabación: si ya estaba guardada y quedó atrasada, se actualiza sola (tras SETTLE_MS de calma). */
    static void afterEdit(Context c,String id){
        Context app=c.getApplicationContext();
        try{if(!on(app)||Inbox.savedAt(app,id)==0)return;}catch(RuntimeException e){return;}
        ScheduledFuture<?> previous=PENDING.put(id,WORKER.schedule(()->{PENDING.remove(id);update(app,id);},SETTLE_MS,TimeUnit.MILLISECONDS));
        if(previous!=null)previous.cancel(false);
    }
    private static void update(Context c,String id){
        try{
            if(!on(c)||Inbox.savedAt(c,id)==0||!Inbox.outdated(c,id))return;
            // Mientras se transcribe de nuevo no hay nada estable que guardar: al terminar se guarda sola.
            if(FilesStore.state(c,id).optBoolean("requested"))return;
            Recording r=FilesStore.recording(c,id);if(r==null)return;
            Inbox.save(c,r);FilesStore.update(c,id,s->s.remove("inboxAutoFailed"));
            Diagnostics.event("auto_inbox",id,"result","saved","stage","edit");
        }catch(Exception e){
            Throwable cause=e.getCause()==null?e:e.getCause();
            try{FilesStore.update(c,id,s->s.put("inboxAutoFailed",true));}catch(Exception ignored){}
            Diagnostics.event("auto_inbox",id,"result","failed","stage","edit","error_class",cause.getClass().getSimpleName(),"permission",Inbox.permission(c));
        }
        FilesStore.version.incrementAndGet();
    }
}
