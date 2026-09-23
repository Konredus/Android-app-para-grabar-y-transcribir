package cl.vozlocal.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
public class ReceiveAudioActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if(Intent.ACTION_SEND.equals(getIntent().getAction())&&uri!=null&&"content".equals(uri.getScheme())){
            Intent open=new Intent(this,ImportActivity.class).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.setClipData(android.content.ClipData.newRawUri("Audio",uri));startActivity(open);
        }finish();
    }
}
