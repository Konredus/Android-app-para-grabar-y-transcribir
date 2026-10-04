package cl.vozlocal.app;

import android.app.Activity;
import android.content.Context;
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager;
import com.google.android.play.core.install.InstallException;
import com.google.android.play.core.install.model.InstallErrorCode;
import java.util.function.BooleanSupplier;

/**
 * 0.9.2: el aviso de versión nueva (Updates) con el Google Play falso de la propia librería (FakeAppUpdateManager): sin
 * versión nueva, con una, la descarga con su avance, lista para instalar, la instalación y una descarga que falla.
 * Los emuladores no tienen Play Store: el de verdad respondería «no vino de Google Play» (NOT_PLAY), que también se prueba.
 */
final class UpdatesChecks {
    private UpdatesChecks(){}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    /** Espera (hasta 5 s) a que se cumpla: las respuestas de Play llegan en el hilo principal. */
    private static void await(BooleanSupplier done,String what)throws InterruptedException{
        long end=System.currentTimeMillis()+5000;while(!done.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(25);
        check(done.getAsBoolean(),"Timed out waiting for "+what+" (state "+Updates.state+")");
    }

    static void run(Context c,Activity activity)throws Exception{
        Runnable listener=Updates.onChange;Updates.onChange=null;
        FakeAppUpdateManager fake=new FakeAppUpdateManager(c);Updates.use(fake);
        try{
            // 1. Sin versión nueva: Ajustes dice que es la última y Grabar no dice nada.
            check(Updates.rowText(c).equals(Lang.str(c,R.string.upd_row_check)),"Before checking, the row should invite to check");
            fake.setUpdateNotAvailable();Updates.check(c,true);await(()->!Updates.checking,"the first check");
            check(Updates.state==Updates.State.NONE&&Updates.homeText(c)==null&&!Updates.news(),"No update should read as up to date: "+Updates.state);
            check(Updates.rowText(c).equals(Lang.str(c,R.string.upd_row_latest)),"Row text wrong: "+Updates.rowText(c));
            // 2. Las revisiones automáticas se espacian: otra al tiro no vuelve a preguntar.
            fake.setUpdateAvailable(99);Updates.check(c,false);Thread.sleep(300);
            check(Updates.state==Updates.State.NONE&&!Updates.checking,"Automatic checks should be spaced out");
            // 3. La de la fila siempre pregunta: hay versión nueva y Grabar lo dice.
            Updates.check(c,true);await(()->!Updates.checking,"the manual check");
            check(Updates.state==Updates.State.AVAILABLE&&Updates.available==99&&Updates.news(),"Update not offered: "+Updates.state);
            check(Lang.str(c,R.string.upd_home_available).equals(Updates.homeText(c))&&Updates.rowText(c).equals(Lang.str(c,R.string.upd_row_available)),"Update texts wrong");
            // 4. «Actualizar»: Play pide confirmar; se acepta y descarga (con su avance); luego queda lista para instalar.
            activity.runOnUiThread(()->Updates.start(activity));
            await(fake::isConfirmationDialogVisible,"Play's confirmation");
            fake.userAcceptsUpdate();fake.downloadStarts();fake.setTotalBytesToDownload(1000);fake.setBytesDownloaded(450);
            await(()->Updates.state==Updates.State.DOWNLOADING&&Updates.percent==45,"the download progress");
            check(Lang.str(c,R.string.upd_row_downloading_pct,45).equals(Updates.rowText(c))&&Lang.str(c,R.string.upd_home_downloading_pct,45).equals(Updates.homeText(c)),"Download texts wrong: "+Updates.rowText(c));
            fake.downloadCompletes();await(()->Updates.state==Updates.State.DOWNLOADED,"the finished download");
            check(Lang.str(c,R.string.upd_home_ready).equals(Updates.homeText(c))&&Updates.news(),"Ready text wrong: "+Updates.homeText(c));
            // 5. Instalar: Play muestra su pantalla de instalación (y en un teléfono, reabre la app ya actualizada).
            Updates.complete(c);await(fake::isInstallSplashScreenVisible,"the install screen");fake.installCompletes();
            // 6. Una descarga que falla vuelve a ofrecer la versión (no queda «descargando» para siempre).
            FakeAppUpdateManager again=new FakeAppUpdateManager(c);Updates.use(again);again.setUpdateAvailable(100);
            Updates.check(c,true);await(()->Updates.state==Updates.State.AVAILABLE,"the second offer");
            activity.runOnUiThread(()->Updates.start(activity));await(again::isConfirmationDialogVisible,"the second confirmation");
            again.userAcceptsUpdate();again.downloadStarts();await(()->Updates.state==Updates.State.DOWNLOADING,"the second download");
            again.downloadFails();await(()->Updates.state!=Updates.State.DOWNLOADING&&!Updates.checking,"the failed download");
            check(Updates.state==Updates.State.AVAILABLE||Updates.state==Updates.State.NONE,"A failed download should offer the update again: "+Updates.state);
            // 7. Errores de Play: una copia que no vino de Google Play (o sin Play Store) se distingue de un fallo cualquiera.
            check(Updates.notFromPlay(new InstallException(InstallErrorCode.ERROR_APP_NOT_OWNED))&&Updates.notFromPlay(new InstallException(InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND))
                &&!Updates.notFromPlay(new InstallException(InstallErrorCode.ERROR_INTERNAL_ERROR))&&!Updates.notFromPlay(new java.io.IOException("x")),"Play errors classified wrong");
            check(Updates.percent(450,1000)==45&&Updates.percent(0,0)==-1&&Updates.percent(2000,1000)==100,"Download percent wrong");
        }finally{Updates.use(null);Updates.onChange=listener;}
    }
}
