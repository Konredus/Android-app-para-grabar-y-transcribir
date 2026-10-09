package cl.vozlocal.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
public class ReceiveAudioActivity extends Activity {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
    @Override public void onCreate(Bundle saved){
        // 0.9.6: también «Abrir con Verbapp» (VIEW) desde el administrador de archivos o el correo, además de «Compartir».
        String action=getIntent().getAction();
        super.onCreate(saved);Uri uri=Intent.ACTION_VIEW.equals(action)?getIntent().getData():getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if((Intent.ACTION_SEND.equals(action)||Intent.ACTION_VIEW.equals(action))&&uri!=null&&"content".equals(uri.getScheme())){
            Intent open=new Intent(this,ImportActivity.class).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.setClipData(android.content.ClipData.newRawUri("Audio",uri));startActivity(open);
        }finish();
    }
}
