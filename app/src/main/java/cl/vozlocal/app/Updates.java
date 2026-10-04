package cl.vozlocal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallException;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallErrorCode;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import java.util.Locale;

/**
 * 0.9.2: avisa cuando Google Play tiene una versión nueva de Verbapp, con las «actualizaciones dentro de la app» de Google
 * (tipo flexible, librería com.google.android.play:app-update; la única dependencia de la app). Play pide confirmar (con
 * el tamaño), descarga mientras se sigue usando la app y, al tocar «Instalar», cierra Verbapp y la vuelve a abrir ya
 * actualizada. Play decide qué versión le toca a cada uno (prueba cerrada o pública): nunca se avisa de una que Google
 * aún no aprueba.
 * Solo funciona si la app se instaló desde Google Play. Una APK instalada a mano (o un teléfono sin Play Store) queda en
 * NOT_PLAY y Ajustes explica cómo pasarse. El estado vive en memoria: cada revisión lo vuelve a leer de Play (una descarga
 * ya lista sigue apareciendo aunque la app se haya cerrado).
 * Dónde se ve: la píldora bajo el saludo de Grabar (MainActivity.renderChip, solo si nada más pide atención) y la fila
 * «Buscar actualizaciones» de Ajustes → Ayuda y soporte.
 */
final class Updates {
    private Updates(){}
    enum State{UNKNOWN,NONE,AVAILABLE,DOWNLOADING,DOWNLOADED,NOT_PLAY,FAILED}
    static volatile State state=State.UNKNOWN;
    /** Hay una revisión en curso: Ajustes dice «Buscando…»; lo demás sigue mostrando el último estado. */
    static volatile boolean checking;
    /** La versión ofrecida (versionCode) y el avance de la descarga (0–100; -1 mientras Play no lo diga). */
    static volatile int available,percent=-1;
    /** Revisiones automáticas (al volver a Grabar o a Ajustes): una cada 30 min como mucho. La de la fila, siempre. */
    static final long AUTO_EVERY_MS=30*60_000L;
    /** La pantalla visible, para redibujar cuando cambia el estado (se llama en el hilo principal); null si ninguna. */
    static volatile Runnable onChange;
    private static AppUpdateManager manager;private static AppUpdateInfo info;private static long checkedAt;private static Context app;
    private static boolean listening;
    private static final InstallStateUpdatedListener LISTENER=Updates::installState;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    /** Pruebas (UpdatesChecks): usa este AppUpdateManager (FakeAppUpdateManager) y parte de cero. null: el de Google Play. */
    static synchronized void use(AppUpdateManager m){
        if(manager!=null&&listening)try{manager.unregisterListener(LISTENER);}catch(RuntimeException ignored){}
        manager=m;listening=false;info=null;state=State.UNKNOWN;checking=false;available=0;percent=-1;checkedAt=0;
    }
    private static synchronized AppUpdateManager manager(Context c){
        app=c.getApplicationContext();
        if(manager==null)manager=AppUpdateManagerFactory.create(app);
        if(!listening){manager.registerListener(LISTENER);listening=true;}
        return manager;
    }

    /** Pregunta a Google Play si hay una versión nueva. manual: lo pidió la persona (la fila de Ajustes): no se espacia. */
    static void check(Context c,boolean manual){
        long now=System.currentTimeMillis();
        if(checking||!manual&&(state==State.DOWNLOADING||checkedAt>0&&now-checkedAt<AUTO_EVERY_MS))return;
        checkedAt=now;checking=true;if(manual)changed();
        try{
            manager(c).getAppUpdateInfo()
                .addOnSuccessListener(i->{checking=false;info=i;apply(i);Diagnostics.event("update_check",null,"result",name(),"available",available,"manual",manual);changed();})
                .addOnFailureListener(e->{checking=false;info=null;state=notFromPlay(e)?State.NOT_PLAY:State.FAILED;Diagnostics.event("update_check",null,"result",name(),"error",errorCode(e),"manual",manual);changed();});
        }catch(RuntimeException e){checking=false;state=State.FAILED;Diagnostics.event("update_check",null,"result",name(),"error_class",e.getClass().getSimpleName());changed();}
    }
    /** Lo que respondió Play, como estado (separado para probarlo). */
    static void apply(AppUpdateInfo i){
        int status=i.installStatus(),availability=i.updateAvailability();available=i.availableVersionCode();
        if(status==InstallStatus.DOWNLOADED){state=State.DOWNLOADED;percent=100;}
        else if(status==InstallStatus.PENDING||status==InstallStatus.DOWNLOADING){state=State.DOWNLOADING;percent=percent(i.bytesDownloaded(),i.totalBytesToDownload());}
        // Una descarga que falló o se canceló (sigue «en curso» para Play) se puede volver a pedir.
        else if(availability==UpdateAvailability.UPDATE_AVAILABLE||availability==UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS){state=State.AVAILABLE;percent=-1;}
        else{state=State.NONE;available=0;percent=-1;}
    }
    /** El avance de la descarga, 0–100; -1 si aún no se sabe el tamaño. */
    static int percent(long done,long total){return total<=0?-1:(int)Math.max(0,Math.min(100,done*100/total));}
    /** Lo que avisa Play mientras descarga e instala. Si la descarga falla o se cancela, se vuelve a preguntar. */
    private static void installState(InstallState s){
        int status=s.installStatus();
        if(status==InstallStatus.PENDING||status==InstallStatus.DOWNLOADING){state=State.DOWNLOADING;percent=percent(s.bytesDownloaded(),s.totalBytesToDownload());}
        else if(status==InstallStatus.DOWNLOADED){state=State.DOWNLOADED;percent=100;Diagnostics.event("update_downloaded",null,"available",available);}
        else if(status==InstallStatus.FAILED||status==InstallStatus.CANCELED){
            Diagnostics.event("update_failed",null,"status",status,"error",s.installErrorCode());
            state=State.AVAILABLE;info=null;percent=-1;checkedAt=0;Context c=app;if(c!=null)check(c,false);
        }
        else return;
        changed();
    }

    /** Lo que hace tocar el aviso (Grabar) o la fila (Ajustes), según el estado. */
    static void act(Screen s){
        switch(state){
            case AVAILABLE:start(s);break;
            case DOWNLOADING:s.toast(Lang.str(s,R.string.upd_toast_downloading));break;
            case DOWNLOADED:install(s);break;
            case NOT_PLAY:s.sheet(Lang.str(s,R.string.upd_not_play_title),Lang.str(s,R.string.upd_not_play_body)).primary(Lang.str(s,R.string.upd_open_play),()->openStore(s)).secondary(Lang.str(s,R.string.common_cancel),null).show();break;
            default:check(s,true);
        }
    }
    /**
     * «Actualizar»: Play muestra su propia confirmación (con el tamaño) y, si se acepta, descarga en segundo plano. Si Play
     * no deja hacerlo desde la app (p. ej. por falta de espacio), se abre Verbapp en Play Store.
     */
    static void start(Activity a){
        AppUpdateInfo i=info;
        if(i==null){check(a,true);return;}
        if(!i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)){openStore(a);return;}
        // Una respuesta de Play sirve para un solo intento: el siguiente vuelve a preguntar.
        info=null;Diagnostics.event("update_start",null,"available",available);
        try{
            manager(a).startUpdateFlow(i,a,AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
                .addOnSuccessListener(code->{
                    Diagnostics.event("update_flow",null,"result",code);
                    if(code==Activity.RESULT_OK){if(state!=State.DOWNLOADED)state=State.DOWNLOADING;}else{checkedAt=0;check(a,false);}
                    changed();})
                .addOnFailureListener(e->{Diagnostics.event("update_flow",null,"result","failed","error",errorCode(e));openStore(a);});
        }catch(RuntimeException e){openStore(a);}
    }
    /** «Instalar ahora»: Verbapp se cierra un momento. Grabando o importando no se ofrece, porque se cortaría. */
    private static void install(Screen s){
        ImportSession imported=ImportService.session(s);
        if(RecorderService.activeId!=null||imported!=null&&imported.busy){s.message(Lang.str(s,R.string.upd_install_title),Lang.str(s,R.string.upd_install_busy));return;}
        // Lo pedido queda guardado (Pipeline): al volver a abrirse, la transcripción sigue sola desde la parte en que iba.
        String body=Lang.str(s,R.string.upd_install_body)+(Pipeline.working()?" "+Lang.str(s,R.string.upd_install_transcribing):"");
        s.sheet(Lang.str(s,R.string.upd_install_title),body).primary(Lang.str(s,R.string.upd_install_now),()->complete(s)).secondary(Lang.str(s,R.string.upd_later),null).show();
    }
    /** Play cierra Verbapp, instala la versión descargada y la vuelve a abrir. */
    static void complete(Context c){
        Diagnostics.event("update_install",null,"available",available);
        try{manager(c).completeUpdate().addOnFailureListener(e->{Diagnostics.event("update_install_failed",null,"error",errorCode(e));state=State.FAILED;changed();});}
        catch(RuntimeException e){state=State.FAILED;changed();}
    }
    /** Verbapp en Play Store (la app de Play; si no está, la página web). */
    static void openStore(Context c){
        String id=c.getPackageName();
        try{c.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+id)).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(ActivityNotFoundException e){try{c.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(ActivityNotFoundException ignored){}}
    }

    /** El aviso de la píldora de Grabar, o null si no hay nada que decir. */
    static String homeText(Context c){
        switch(state){
            case AVAILABLE:return Lang.str(c,R.string.upd_home_available);
            case DOWNLOADING:return percent>=0?Lang.str(c,R.string.upd_home_downloading_pct,percent):Lang.str(c,R.string.upd_home_downloading);
            case DOWNLOADED:return Lang.str(c,R.string.upd_home_ready);
            default:return null;
        }
    }
    /** El texto de apoyo de la fila «Buscar actualizaciones» de Ajustes. */
    static String rowText(Context c){
        if(checking)return Lang.str(c,R.string.upd_row_checking);
        switch(state){
            case NONE:return Lang.str(c,R.string.upd_row_latest);
            case AVAILABLE:return Lang.str(c,R.string.upd_row_available);
            case DOWNLOADING:return percent>=0?Lang.str(c,R.string.upd_row_downloading_pct,percent):Lang.str(c,R.string.upd_row_downloading);
            case DOWNLOADED:return Lang.str(c,R.string.upd_row_ready);
            case NOT_PLAY:return Lang.str(c,R.string.upd_row_not_play);
            case FAILED:return Lang.str(c,R.string.upd_row_failed);
            default:return Lang.str(c,R.string.upd_row_check);
        }
    }
    /** ¿Hay algo que destacar (una versión nueva o una descarga lista)? */
    static boolean news(){return state==State.AVAILABLE||state==State.DOWNLOADED;}

    static int errorCode(Exception e){return e instanceof InstallException?((InstallException)e).getErrorCode():0;}
    /** La app no vino de Google Play (APK instalada a mano) o el teléfono no tiene Play Store: no hay actualizaciones por aquí. */
    static boolean notFromPlay(Exception e){int code=errorCode(e);return code==InstallErrorCode.ERROR_APP_NOT_OWNED||code==InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND||code==InstallErrorCode.ERROR_API_NOT_AVAILABLE;}
    private static String name(){return state.name().toLowerCase(Locale.ROOT);}
    private static void changed(){MAIN.post(()->{Runnable r=onChange;if(r!=null)r.run();});}
}
