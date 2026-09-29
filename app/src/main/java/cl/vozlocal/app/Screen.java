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
    /** Si cambió el tema o «Colores de tu fondo de pantalla» mientras la pantalla estaba detrás, se rehace con la paleta nueva. */
    @Override protected void onResume(){super.onResume();if(p.dark!=AppTheme.isDark(this)||p.dynamic!=AppTheme.dynamicColor(this))recreate();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("screen_scroll",scroll==null?0:scroll.getScrollY());super.onSaveInstanceState(state);}

    /**
     * Construye la estructura. back: texto del botón volver (null = pantalla raíz sin barra).
     * tab: pestaña activa de la barra inferior (-1 = sin barra de pestañas).
     */
    void shell(String back,int tab){
        int position=scroll==null?savedScroll:scroll.getScrollY();
        int navigationColor=tab>=0?p.surfaceContainer:p.background;
        AppTheme.window(this,p,navigationColor);
        root=ui.column();Backdrop backdrop=new Backdrop(p.background,navigationColor);root.setBackground(backdrop);
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
            boolean keyboard;
            if(Build.VERSION.SDK_INT>=30){
                Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);
                backdrop.band(insets.getInsets(WindowInsets.Type.navigationBars()).bottom);keyboard=insets.isVisible(WindowInsets.Type.ime());
            }else{
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
                backdrop.band(insets.getStableInsetBottom());keyboard=insets.getSystemWindowInsetBottom()-insets.getStableInsetBottom()>ui.dp(120);
            }
            // La barra de pestañas se esconde mientras está el teclado (PROPUESTA #2): deja más espacio para escribir.
            if(nav!=null){int want=keyboard?View.GONE:View.VISIBLE;if(nav.getVisibility()!=want)nav.setVisibility(want);}
            return insets;});
        root.requestApplyInsets();
        scroll.post(()->scroll.scrollTo(0,position));
    }
    /**
     * Fondo de la pantalla que pinta la zona detrás de la barra de navegación del sistema con el color de lo que
     * queda justo encima (barra de pestañas o fondo). En Android 15+ la app dibuja de borde a borde y, sin esto,
     * en modo oscuro se veía una franja de otro color bajo las pestañas.
     */
    private static final class Backdrop extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint=new android.graphics.Paint();private final int background,navigation;private int band;
        Backdrop(int background,int navigation){this.background=background;this.navigation=navigation;}
        void band(int px){if(px!=band){band=Math.max(0,px);invalidateSelf();}}
        @Override public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect b=getBounds();paint.setColor(background);canvas.drawRect(b,paint);
            if(band>0&&navigation!=background){paint.setColor(navigation);canvas.drawRect(b.left,b.bottom-band,b.right,b.bottom,paint);}
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){}
        @Override public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
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
    private View snack,leaving;private final android.os.Handler snackTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    /** Al salir de la pantalla (otra app, compartir, bloqueo), el aviso se quita: su «Deshacer» ya no tiene contexto. */
    @Override protected void onPause(){super.onPause();removeSnackbar();}
    /**
     * Snackbar de Material 3: confirma un cambio y ofrece deshacerlo durante unos segundos.
     * Va sobre la zona inferior fija, sin tapar el contenido (colores invertidos, ver CRITERIOS.md → Componentes).
     * Entra deslizándose hacia arriba con la curva emphasized y sale hacia abajo, más rápido.
     */
    void snackbar(String text,String action,Runnable run){
        removeSnackbar();
        LinearLayout s=ui.row();s.setBackground(shape(this,p.inverseSurface,R_SMALL));s.setPadding(ui.dp(S4),ui.dp(S1),ui.dp(S2),ui.dp(S1));s.setMinimumHeight(ui.dp(48));
        s.addView(ui.text(text,Type.BODY_MEDIUM,p.inverseOnSurface),new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null&&run!=null){TextView a=ui.text(action,Type.LABEL_LARGE,p.inversePrimary);a.setGravity(Gravity.CENTER);a.setMinHeight(ui.dp(48));a.setPadding(ui.dp(S3),0,ui.dp(S3),0);a.setBackground(ui.ripple(null,R_SMALL));a.setAccessibilityDelegate(Ui.buttonRole());a.setOnClickListener(v->{hideSnackbar();run.run();});s.addView(a);}
        s.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S2));
        int at=bottom==null?root.getChildCount():root.indexOfChild(bottom);root.addView(s,Math.max(0,at),lp);snack=s;
        if(AppTheme.motion()){s.setAlpha(0f);s.setTranslationY(ui.dp(S6));s.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_DECELERATE).start();}
        s.announceForAccessibility(text+(action!=null?". "+action+" disponible":""));
        android.view.accessibility.AccessibilityManager am=getSystemService(android.view.accessibility.AccessibilityManager.class);
        long timeout=8000;boolean keep=false;
        if(am!=null){if(Build.VERSION.SDK_INT>=29)timeout=am.getRecommendedTimeoutMillis(8000,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT|(action!=null?android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS:0));else keep=action!=null&&am.isTouchExplorationEnabled();}
        if(!keep)snackTimer.postDelayed(this::hideSnackbar,timeout);
    }
    /** Quita el aviso con una salida corta (desliza hacia abajo y se desvanece). */
    void hideSnackbar(){
        snackTimer.removeCallbacksAndMessages(null);View s=snack;if(s==null)return;snack=null;
        if(!AppTheme.motion()||!s.isAttachedToWindow()||isFinishing()){detach(s);return;}
        detach(leaving);leaving=s;
        s.animate().alpha(0f).translationY(ui.dp(S4)).setStartDelay(0).setDuration(MOTION_FAST+50).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(()->{if(leaving==s)leaving=null;detach(s);}).start();
    }
    /** Quita el aviso al instante (al mostrar otro o al salir de la pantalla). */
    private void removeSnackbar(){snackTimer.removeCallbacksAndMessages(null);View s=snack;snack=null;detach(s);detach(leaving);leaving=null;}
    private static void detach(View v){if(v==null)return;v.animate().cancel();if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    void shareFile(String filename,String title){android.net.Uri uri=android.net.Uri.parse("content://cl.vozlocal.app.audio/"+filename);Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(android.content.ClipData.newRawUri(title,uri));startActivity(Intent.createChooser(share,title));}
    int dp(float n){return ui.dp(n);}
}
