package cl.vozlocal.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;

/**
 * 0.9.2: una hoja con un campo («Cambiar título», como «Nombra esta grabación») abre el teclado sola y conserva la
 * selección de todo el texto (Sheet.keyboard). Antes había que tocar el campo para que saliera el teclado, y ese toque
 * movía el cursor y deshacía la selección.
 */
final class KeyboardChecks {
    private KeyboardChecks(){}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    static void run(Instrumentation inst,Context c,Recording r)throws Exception{
        if(Build.VERSION.SDK_INT<30)return;
        Activity a=inst.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();
        try{
            inst.runOnMainSync(()->RecordingActions.rename((Screen)a,r,null));
            String[] seen={"no field"};boolean[] ok={false};long end=System.currentTimeMillis()+5000;
            while(!ok[0]&&System.currentTimeMillis()<end){
                inst.runOnMainSync(()->{
                    EditText field=focusedField();if(field==null){seen[0]="no focused field";return;}
                    WindowInsets insets=field.getRootWindowInsets();boolean keyboard=insets!=null&&insets.isVisible(WindowInsets.Type.ime());
                    boolean all=field.length()>0&&field.getSelectionStart()==0&&field.getSelectionEnd()==field.length();
                    seen[0]="keyboard "+keyboard+", selection "+field.getSelectionStart()+"–"+field.getSelectionEnd()+" of "+field.length();ok[0]=keyboard&&all;
                });
                if(!ok[0])Thread.sleep(100);
            }
            check(ok[0],"The rename sheet should open the keyboard with the whole title selected: "+seen[0]);
        }finally{
            // Atrás cierra el teclado y después la hoja; la pantalla de prueba se cierra igual.
            for(int i=0;i<2;i++){inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);inst.waitForIdleSync();}
            a.finish();inst.waitForIdleSync();
        }
    }
    /** El campo con el foco, en cualquier ventana de la app (la hoja es otra ventana). */
    private static EditText focusedField(){
        for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){EditText f=find(root);if(f!=null)return f;}
        return null;
    }
    private static EditText find(View v){
        if(v instanceof EditText&&v.hasFocus())return (EditText)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText f=find(g.getChildAt(i));if(f!=null)return f;}}
        return null;
    }
}
