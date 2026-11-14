package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-started foreground work keeps conversion running with the display off. */
public class ImportService extends Service {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
    private static final int NOTIFICATION=14;private static ImportSession current;private static boolean loaded;
    /** Plazo de cada toma del candado de CPU; los avisos de avance lo renuevan (ver keepAwake). */
    private static final long WAKE_MS=30*60_000L;
    private final AtomicBoolean running=new AtomicBoolean();private PowerManager.WakeLock wake;private long notified,awakeAt;
    static synchronized ImportSession session(Context c){if(!loaded){current=ImportSession.restore(c);loaded=true;}return current;}
    static boolean hasWork(Context c){ImportSession s=session(c);return s!=null&&!s.done&&!s.cancelled&&(s.busy||s.ready);}
    /** Descarta la copia preparada; su aviso «listo»/«pendiente» ya no lleva a nada, así que también se quita. */
    static synchronized void dismiss(Context c){ImportSession s=session(c);if(s!=null&&!s.busy){if(!s.done)try{c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);}catch(RuntimeException ignored){}s.clean();current=null;}}
    static synchronized void load(Context c,Uri uri,String localId){ImportSession prior=session(c);if(prior!=null&&prior.busy)return;if(prior!=null)prior.clean();ImportSession s=new ImportSession(c);current=s;s.busy=true;s.persist();Intent intent=new Intent(c,ImportService.class).setAction("LOAD").putExtra("sourceId",localId);if(uri!=null){intent.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Audio",uri));}start(c,intent,s);}
    static synchronized void save(Context c,String title,long from,long to){ImportSession s=session(c);if(s==null||s.busy||!s.ready||s.done)return;s.name=title;s.from=from;s.to=to;s.busy=true;s.error="";s.stage=R.string.imp_stage_preparing;s.position=0;s.total=to-from;s.bytesProgress=false;s.persist();start(c,new Intent(c,ImportService.class).setAction("CONVERT"),s);}
    private static void start(Context c,Intent intent,ImportSession s){try{c.startForegroundService(intent);}catch(RuntimeException e){s.busy=false;s.error=Lang.str(c,R.string.imp_err_start);s.persist();Diagnostics.event("import_start_failed",s.id,"error_class",e.getClass().getSimpleName());}}
    static void cancel(Context c){ImportSession s=session(c);if(s!=null){s.cancel();if(!s.busy)dismiss(c);}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        ImportSession s=session(this);if(intent==null||s==null){stopSelf(startId);return START_NOT_STICKY;}
        if("CANCEL".equals(intent.getAction())){cancel(this);return START_NOT_STICKY;}
        if(!running.compareAndSet(false,true))return START_NOT_STICKY;
        // El nombre del canal se vuelve a poner en cada inicio: si cambió el idioma de la app, Ajustes del teléfono lo muestra en el nuevo.
        try{getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("importing",Lang.str(this,R.string.imp_channel),NotificationManager.IMPORTANCE_LOW));Notification n=notification(s,false);if(Build.VERSION.SDK_INT>=35)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTIFICATION,n);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VozLocal:Import");wake.setReferenceCounted(false);wake.acquire(WAKE_MS);awakeAt=SystemClock.elapsedRealtime();
        }catch(RuntimeException e){running.set(false);s.busy=false;s.error=Lang.str(this,R.string.imp_err_background);s.persist();stopSelf(startId);return START_NOT_STICKY;}
        new Thread(()->{long started=SystemClock.elapsedRealtime();try{
            if("LOAD".equals(intent.getAction()))read(s,intent.getData(),intent.getStringExtra("sourceId"));else convert(s);
        }catch(Exception e){s.error=Lang.str(this,s.cancelled?R.string.imp_err_cancelled:failure(e,s.ready));Diagnostics.event(s.cancelled?"import_cancelled":"import_failed",s.id,"error_class",e.getClass().getSimpleName(),"elapsed_ms",SystemClock.elapsedRealtime()-started);s.encoded.delete();if(!s.ready)s.source.delete();
        }finally{new Handler(Looper.getMainLooper()).post(()->{s.openStream=null;running.set(false);if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);s.busy=false;if(s.cancelled)s.clean();else s.persist();
            // Aviso final (el de avance se quitó con stopForeground): guardado, pendiente o —tras copiar— listo para guardar.
            // «Listo» solo si no estás mirando la pantalla de Importar (ahí ya se ve el formulario).
            if(!s.cancelled&&(s.done||!s.error.isEmpty()||(s.ready&&!ImportActivity.inFront)))try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(s,true));}catch(SecurityException ignored){}stopSelf(startId);});}},"Voz-import").start();
        return START_NOT_STICKY;
    }
    /**
     * 0.9.6: el aviso dice qué pasó: sin espacio, audio demasiado corto, formato que el teléfono no sabe leer, o el error
     * general de antes. Se decide por el mensaje técnico (en inglés, nunca se muestra).
     */
    static int failure(Exception e,boolean ready){
        String m=e.getMessage()==null?"":e.getMessage();
        if(m.contains("Not enough local storage"))return R.string.imp_err_space;
        if(m.contains("Audio too short"))return R.string.imp_err_short;
        if(!ready&&(e instanceof RuntimeException||m.contains("Unknown duration")||m.contains("No audio track")))return R.string.imp_err_format;
        return ready?R.string.imp_err_convert:R.string.imp_err_read;
    }
    private void read(ImportSession s,Uri uri,String localId)throws Exception{
        long expected=-1;InputStream stream;
        if(localId!=null){Recording r=FilesStore.recording(this,localId);if(r==null)throw new IOException("Source not found");s.name=Lang.str(this,R.string.imp_crop_name,r.title);expected=r.audio(this).length();stream=new FileInputStream(r.audio(this));}
        else{if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("Unsupported source");try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(0);if(name!=null&&!name.isBlank())s.name=ImportSession.titleFrom(name);if(!cursor.isNull(1))expected=cursor.getLong(1);}}stream=getContentResolver().openInputStream(uri);}
        if(s.name.length()>120)s.name=s.name.substring(0,120);s.openStream=stream;s.bytesProgress=true;s.total=expected;s.position=0;s.stage=R.string.imp_stage_copying;publish(s);
        try(InputStream in=stream;OutputStream out=new FileOutputStream(s.source)){if(in==null)throw new IOException("Unreadable source");byte[] buffer=new byte[65536];int n;long size=0;while((n=in.read(buffer))!=-1){s.cancel.check();size+=n;if(size>2_000_000_000L||s.source.getParentFile().getUsableSpace()<64_000_000)throw new IOException("Not enough local storage");out.write(buffer,0,n);s.position=size;publish(s);}}
        s.openStream=null;s.cancel.check();s.stage=R.string.imp_stage_checking;s.total=0;publish(s);s.duration=AudioConvert.duration(s.source);if(s.duration<500)throw new IOException("Audio too short");s.cancel.check();s.ready=true;s.from=0;s.to=s.duration;s.stage=R.string.imp_stage_ready;s.position=0;Diagnostics.event("import_loaded",s.id,"bytes",s.source.length(),"duration_ms",s.duration);
    }
    private void convert(ImportSession s)throws Exception{
        if(!s.ready)throw new IOException("Source not ready");long estimated=Math.max(16_000_000L,(s.to-s.from)*16);if(s.encoded.getParentFile().getUsableSpace()<estimated)throw new IOException("Not enough local storage");
        // Sin jerga: las etapas técnicas del conversor se muestran como «Guardando audio» / «Casi listo».
        AudioConvert.convert(s.source,s.encoded,s.from,s.to,s.cancel,(stage,position,total)->{s.stage=friendlyStage(stage);s.position=position;s.total=total;s.bytesProgress=false;publish(s);});s.cancel.check();
        // La barra sigue siendo determinada hasta el final (antes pasaba a indeterminada mientras guardaba).
        s.stage=R.string.imp_stage_library;if(s.total<=0)s.total=Math.max(1,s.to-s.from);s.position=s.total;publish(s);
        Recording r=new Recording(s.id,s.name,System.currentTimeMillis(),AudioConvert.duration(s.encoded));s.cancel.check();
        synchronized(FilesStore.LOCK){if(!s.encoded.renameTo(r.audio(this)))throw new IOException("Could not save audio");try{r.save(this);}catch(Exception e){r.audio(this).delete();throw e;}s.done=true;}
        s.source.delete();s.stage=R.string.imp_saved;try{Pipeline.afterRecording(this,s.id);}catch(Exception e){Diagnostics.event("import_queue_failed",s.id,"error_class",e.getClass().getSimpleName());}Diagnostics.event("import_complete",s.id,"duration_ms",r.duration);
    }
    private void publish(ImportSession s){keepAwake();long now=SystemClock.elapsedRealtime();if(now-notified<300)return;notified=now;try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(s,false));}catch(SecurityException ignored){}}
    /**
     * Candado de CPU (la importación sigue con la pantalla apagada): se toma por 30 min y cada aviso de avance lo renueva,
     * a lo más una vez por minuto. Así dura lo que dura el trabajo y, si este dejara de avanzar, vence solo en vez de quedar
     * tomado horas (Google Play vigila los candados de activación excesivos; antes se tomaba por 6 h de una vez). Sin
     * conteo de referencias: cada renovación reemplaza el plazo anterior y un solo release() lo suelta (al terminar y en
     * onDestroy). Lo llama el hilo de la importación.
     */
    private void keepAwake(){PowerManager.WakeLock w=wake;long now=SystemClock.elapsedRealtime();if(w==null||now-awakeAt<60_000)return;awakeAt=now;try{w.acquire(WAKE_MS);}catch(RuntimeException ignored){}}
    private Notification notification(ImportSession s,boolean finished){
        // «resume»: llegar desde el aviso retoma la copia preparada aunque hayan pasado más de 30 min (no se descarta).
        Intent open=s.done?new Intent(this,MainActivity.class).putExtra("library",true):new Intent(this,ImportActivity.class).putExtra("resume",true);open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch=PendingIntent.getActivity(this,14,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean pending=!s.error.isEmpty();
        Notification.Builder b=new Notification.Builder(this,"importing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(Lang.str(this,finished?(s.done?R.string.imp_saved:pending?R.string.imp_pending:R.string.imp_ready_to_save):s.stage)).setContentText(finished?(s.done?Lang.str(this,R.string.imp_in_library):pending?s.error:Lang.str(this,R.string.imp_tap_to_save)):progressText(s)).setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(!finished).setAutoCancel(finished);
        if(!finished){b.setProgress(100,percent(s),s.total<=0);PendingIntent stop=PendingIntent.getService(this,15,new Intent(this,ImportService.class).setAction("CANCEL"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);b.addAction(new Notification.Action.Builder(null,Lang.str(this,R.string.common_cancel),stop).build());}return b.build();
    }
    /**
     * La etapa que se muestra mientras se guarda: las del conversor (códigos de AudioConvert, sin jerga ni idioma) quedan
     * en «Guardando audio», y la verificación final en «Casi listo». Devuelve el texto (R.string) que guarda la sesión.
     */
    static int friendlyStage(String stage){return AudioConvert.VERIFY.equals(stage)?R.string.imp_stage_almost:R.string.imp_stage_saving;}
    static int percent(ImportSession s){return s.total<=0?0:(int)Math.min(99,Math.max(0,s.position*100/s.total));}
    /** «12,5 MB de 100,0 MB» (los decimales con el separador del idioma) o «01:30 de 05:00»; sin total, que el original se conserva. */
    static String progressText(ImportSession s){if(s.total<=0)return Lang.str(s.app,R.string.imp_original_safe);if(s.bytesProgress)return Lang.str(s.app,R.string.imp_progress_mb,s.position/1_000_000d,s.total/1_000_000d);return Lang.str(s.app,R.string.imp_progress_time,Recording.time(s.position),Recording.time(s.total));}
    @Override public void onTimeout(int startId,int fgsType){ImportSession s=session(this);if(s!=null){s.cancel();Diagnostics.event("import_timeout",s.id);}stopSelf();}
    @Override public void onDestroy(){if(running.get()){ImportSession s=session(this);if(s!=null)s.cancel();}if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
