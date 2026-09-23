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
    private static final int NOTIFICATION=14;private static ImportSession current;private static boolean loaded;
    private final AtomicBoolean running=new AtomicBoolean();private PowerManager.WakeLock wake;private long notified;
    static synchronized ImportSession session(Context c){if(!loaded){current=ImportSession.restore(c);loaded=true;}return current;}
    static boolean hasWork(Context c){ImportSession s=session(c);return s!=null&&!s.done&&!s.cancelled&&(s.busy||s.ready);}
    static synchronized void dismiss(Context c){ImportSession s=session(c);if(s!=null&&!s.busy){s.clean();current=null;}}
    static synchronized void load(Context c,Uri uri,String localId){ImportSession prior=session(c);if(prior!=null&&prior.busy)return;if(prior!=null)prior.clean();ImportSession s=new ImportSession(c);current=s;s.busy=true;s.persist();Intent intent=new Intent(c,ImportService.class).setAction("LOAD").putExtra("sourceId",localId);if(uri!=null){intent.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Audio",uri));}start(c,intent,s);}
    static synchronized void save(Context c,String title,long from,long to){ImportSession s=session(c);if(s==null||s.busy||!s.ready||s.done)return;s.name=title;s.from=from;s.to=to;s.busy=true;s.error="";s.stage="Preparando audio";s.position=0;s.total=to-from;s.bytesProgress=false;s.persist();start(c,new Intent(c,ImportService.class).setAction("CONVERT"),s);}
    private static void start(Context c,Intent intent,ImportSession s){try{c.startForegroundService(intent);}catch(RuntimeException e){s.busy=false;s.error="Android no pudo iniciar la preparación. Abre la app e inténtalo otra vez.";s.persist();Diagnostics.event("import_start_failed",s.id,"error_class",e.getClass().getSimpleName());}}
    static void cancel(Context c){ImportSession s=session(c);if(s!=null){s.cancel();if(!s.busy)dismiss(c);}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        ImportSession s=session(this);if(intent==null||s==null){stopSelf(startId);return START_NOT_STICKY;}
        if("CANCEL".equals(intent.getAction())){cancel(this);return START_NOT_STICKY;}
        if(!running.compareAndSet(false,true))return START_NOT_STICKY;
        try{getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("importing","Importar audio",NotificationManager.IMPORTANCE_LOW));Notification n=notification(s,false);if(Build.VERSION.SDK_INT>=35)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTIFICATION,n);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VozLocal:Import");wake.acquire(6*60*60*1000L);
        }catch(RuntimeException e){running.set(false);s.busy=false;s.error="No se pudo preparar el audio en segundo plano. Vuelve a intentarlo con la app abierta.";s.persist();stopSelf(startId);return START_NOT_STICKY;}
        new Thread(()->{long started=SystemClock.elapsedRealtime();try{
            if("LOAD".equals(intent.getAction()))read(s,intent.getData(),intent.getStringExtra("sourceId"));else convert(s);
        }catch(Exception e){s.error=s.cancelled?"Importación cancelada. El original se conserva.":s.ready?"No se pudo preparar el audio. Puedes volver a intentarlo o probar un tramo más corto.":"No se pudo leer el archivo. Revisa su formato, el espacio disponible y que dure al menos un segundo.";Diagnostics.event(s.cancelled?"import_cancelled":"import_failed",s.id,"error_class",e.getClass().getSimpleName(),"elapsed_ms",SystemClock.elapsedRealtime()-started);s.encoded.delete();if(!s.ready)s.source.delete();
        }finally{new Handler(Looper.getMainLooper()).post(()->{s.openStream=null;running.set(false);if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);s.busy=false;if(s.cancelled)s.clean();else s.persist();if(!s.cancelled&&(s.done||!s.error.isEmpty()))try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(s,true));}catch(SecurityException ignored){}stopSelf(startId);});}},"Voz-import").start();
        return START_NOT_STICKY;
    }
    private void read(ImportSession s,Uri uri,String localId)throws Exception{
        long expected=-1;InputStream stream;
        if(localId!=null){Recording r=FilesStore.recording(this,localId);if(r==null)throw new IOException("Source not found");s.name=r.title+" · recorte";expected=r.audio(this).length();stream=new FileInputStream(r.audio(this));}
        else{if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("Unsupported source");try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(0);if(name!=null&&!name.isBlank())s.name=name;if(!cursor.isNull(1))expected=cursor.getLong(1);}}stream=getContentResolver().openInputStream(uri);}
        if(s.name.length()>120)s.name=s.name.substring(0,120);s.openStream=stream;s.bytesProgress=true;s.total=expected;s.position=0;s.stage="Copiando archivo al teléfono";publish(s);
        try(InputStream in=stream;OutputStream out=new FileOutputStream(s.source)){if(in==null)throw new IOException("Unreadable source");byte[] buffer=new byte[65536];int n;long size=0;while((n=in.read(buffer))!=-1){s.cancel.check();size+=n;if(size>2_000_000_000L||s.source.getParentFile().getUsableSpace()<64_000_000)throw new IOException("Not enough local storage");out.write(buffer,0,n);s.position=size;publish(s);}}
        s.openStream=null;s.cancel.check();s.stage="Comprobando duración y formato";s.total=0;publish(s);s.duration=AudioConvert.duration(s.source);if(s.duration<500)throw new IOException("Audio too short");s.cancel.check();s.ready=true;s.from=0;s.to=s.duration;s.stage="Audio listo";s.position=0;Diagnostics.event("import_loaded",s.id,"bytes",s.source.length(),"duration_ms",s.duration);
    }
    private void convert(ImportSession s)throws Exception{
        if(!s.ready)throw new IOException("Source not ready");long estimated=Math.max(16_000_000L,(s.to-s.from)*16);if(s.encoded.getParentFile().getUsableSpace()<estimated)throw new IOException("Not enough local storage");
        AudioConvert.convert(s.source,s.encoded,s.from,s.to,s.cancel,(stage,position,total)->{s.stage=stage;s.position=position;s.total=total;s.bytesProgress=false;publish(s);});s.cancel.check();s.stage="Guardando en Biblioteca";s.total=0;publish(s);
        Recording r=new Recording(s.id,s.name,System.currentTimeMillis(),AudioConvert.duration(s.encoded));s.cancel.check();
        synchronized(FilesStore.LOCK){if(!s.encoded.renameTo(r.audio(this)))throw new IOException("Could not save audio");try{r.save(this);}catch(Exception e){r.audio(this).delete();throw e;}s.done=true;}
        s.source.delete();s.stage="Audio guardado";try{Pipeline.afterRecording(this,s.id);}catch(Exception e){Diagnostics.event("import_queue_failed",s.id,"error_class",e.getClass().getSimpleName());}Diagnostics.event("import_complete",s.id,"duration_ms",r.duration);
    }
    private void publish(ImportSession s){long now=SystemClock.elapsedRealtime();if(now-notified<300)return;notified=now;try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(s,false));}catch(SecurityException ignored){}}
    private Notification notification(ImportSession s,boolean finished){
        Intent open=s.done?new Intent(this,MainActivity.class).putExtra("library",true):new Intent(this,ImportActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch=PendingIntent.getActivity(this,14,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"importing").setSmallIcon(R.drawable.ic_mic).setContentTitle(finished?(s.done?"Audio guardado":"Importación pendiente"):s.stage).setContentText(finished?(s.done?"Disponible en Biblioteca":s.error):progressText(s)).setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(!finished).setAutoCancel(finished);
        if(!finished){b.setProgress(100,percent(s),s.total<=0);PendingIntent stop=PendingIntent.getService(this,15,new Intent(this,ImportService.class).setAction("CANCEL"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);b.addAction(new Notification.Action.Builder(null,"Cancelar",stop).build());}return b.build();
    }
    static int percent(ImportSession s){return s.total<=0?0:(int)Math.min(99,Math.max(0,s.position*100/s.total));}
    static String progressText(ImportSession s){if(s.total<=0)return "Tu archivo original se conserva";if(s.bytesProgress)return String.format(java.util.Locale.ROOT,"%.1f MB de %.1f MB",s.position/1_000_000d,s.total/1_000_000d);return Recording.time(s.position)+" de "+Recording.time(s.total)+" procesados";}
    @Override public void onTimeout(int startId,int fgsType){ImportSession s=session(this);if(s!=null){s.cancel();Diagnostics.event("import_timeout",s.id);}stopSelf();}
    @Override public void onDestroy(){if(running.get()){ImportSession s=session(this);if(s!=null)s.cancel();}if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
