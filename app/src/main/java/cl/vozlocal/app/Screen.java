package cl.vozlocal.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Base de todas las pantallas: estructura común (barra superior, título grande, contenido desplazable,
 * zona inferior fija y barra de pestañas), más utilidades de mensajes. Así cada pantalla solo describe su contenido.
 */
abstract class Screen extends Activity {
    Palette p;Ui ui;
    LinearLayout root,page,bar,barActions,bottom;ScrollView scroll;BottomNav nav;
    private int savedScroll;

    @Override public void onCreate(Bundle state){
        p=AppTheme.apply(this);ui=new Ui(this,p);
        super.onCreate(state);if(state!=null)savedScroll=state.getInt("screen_scroll",0);
    }
    @Override protected void onResume(){super.onResume();if(p.dark!=AppTheme.isDark(this))recreate();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("screen_scroll",scroll==null?0:scroll.getScrollY());super.onSaveInstanceState(state);}

    /**
     * Construye la estructura. back: texto del botón volver (null = pantalla raíz sin barra).
     * tab: pestaña activa de la barra inferior (-1 = sin barra de pestañas).
     */
    void shell(String back,int tab){
        int position=scroll==null?savedScroll:scroll.getScrollY();
        AppTheme.window(this,p,tab>=0?p.surfaceContainer:p.background);
        root=ui.column();root.setBackgroundColor(p.background);
        if(back!=null){
            // Barra superior de Material: botón de ícono ← (sin texto) a la izquierda y acciones a la derecha.
            bar=ui.row();bar.setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(S1));bar.setMinimumHeight(ui.dp(64));
            ImageButton backButton=ui.iconButton(R.drawable.ic_arrow_back,"Volver a "+back,p.onSurface,0,48);backButton.setOnClickListener(v->onBackPressed());bar.addView(backButton);bar.addView(ui.flex());
            barActions=ui.row();bar.addView(barActions);root.addView(bar,Ui.fill());
        }
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        page=ui.column();page.setPadding(ui.dp(S4),ui.dp(back==null?S4:0),ui.dp(S4),ui.dp(S8));scroll.addView(page,new FrameLayout.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        bottom=ui.column();bottom.setVisibility(View.GONE);bottom.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S3));bottom.setBackgroundColor(p.background);root.addView(bottom,Ui.fill());
        if(tab>=0){nav=new BottomNav(this,p,tab,this::navigate);root.addView(nav,Ui.fill());}
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;});
        root.requestApplyInsets();
        scroll.post(()->scroll.scrollTo(0,position));
    }
    /** Título grande (estilo iOS) + bajada opcional. */
    TextView largeTitle(ViewGroup parent,String title,String subtitle){
        TextView t=ui.heading(title,Type.HEADLINE_MEDIUM);t.setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(subtitle==null||subtitle.isEmpty()?S4:S1));parent.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView s=ui.text(subtitle,Type.BODY_MEDIUM,p.onSurfaceVariant);s.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S4));parent.addView(s);}
        return t;
    }
    /** Navegación entre pestañas: 0 Grabar, 1 Biblioteca (MainActivity) · 2 Ajustes. */
    void navigate(int tab){
        if(tab==2){if(!(this instanceof SettingsActivity)){startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));overridePendingTransition(0,0);}}
        else{startActivity(new Intent(this,MainActivity.class).putExtra("library",tab==1).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));overridePendingTransition(0,0);if(this instanceof SettingsActivity)finish();}
    }

    // ---------- Mensajes ----------
    Sheet sheet(String title,String message){return new Sheet(this,ui,title,message);}
    void message(String title,String value){sheet(title,value).primary("Entendido",()->{}).show();}
    void confirm(String title,String value,String action,boolean destructive,Runnable run){
        sheet(title,value).primary(action,destructive?Ui.Style.DESTRUCTIVE:Ui.Style.PRIMARY,()->{run.run();return true;}).secondary("Cancelar",null).show();
    }
    void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    void shareFile(String filename,String title){android.net.Uri uri=android.net.Uri.parse("content://cl.vozlocal.app.audio/"+filename);Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(android.content.ClipData.newRawUri(title,uri));startActivity(Intent.createChooser(share,title));}
    int dp(float n){return ui.dp(n);}
}
