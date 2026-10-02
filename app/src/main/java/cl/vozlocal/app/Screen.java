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
    /** Fondo degradado de la pantalla (blanco → verde); Grabar lo lleva intenso, las demás suave. */
    Glass.Backdrop backdrop;
    private int savedScroll;private android.animation.ValueAnimator vividAnim;

    /** Idioma de la app (Lang): cada pantalla se crea con el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
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
     * El fondo es el degradado suave (ver {@link #shell(String,int,boolean)} para el intenso).
     */
    void shell(String back,int tab){shell(back,tab,false);}
    /**
     * vivid: fondo intenso (blanco arriba, verde abajo), el de Grabar. La pantalla dibuja de borde a borde: el degradado
     * pasa por detrás de las barras del sistema y el contenido se corre según sus medidas (insets).
     */
    void shell(String back,int tab,boolean vivid){
        int position=scroll==null?savedScroll:scroll.getScrollY();
        root=ui.column();backdrop=new Glass.Backdrop(this,p,vivid);root.setBackground(backdrop);
        AppTheme.window(this,p,backdrop.bottomColor());
        if(back!=null){
            // Barra superior: botón redondo de vidrio ← (sin texto) a la izquierda y acciones a la derecha.
            bar=ui.row();bar.setPadding(ui.dp(S3),ui.dp(S2),ui.dp(S3),ui.dp(S1));bar.setMinimumHeight(ui.dp(64));bar.setClipToPadding(false);bar.setClipChildren(false);
            ImageButton backButton=ui.glassButton(R.drawable.ic_arrow_back,getString(R.string.common_back_to,back));backButton.setOnClickListener(v->onBackPressed());bar.addView(backButton);bar.addView(ui.flex());
            barActions=ui.row();barActions.setClipChildren(false);bar.addView(barActions);root.addView(bar,Ui.fill());
        }
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        // Lo que se desplaza se desvanece en los bordes (sobre todo abajo, junto a la barra flotante) en vez de cortarse en seco.
        scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(ui.dp(S8));
        page=ui.column();page.setPadding(ui.dp(S4),ui.dp(back==null?S4:0),ui.dp(S4),ui.dp(S8));scroll.addView(page,new FrameLayout.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        bottom=ui.column();bottom.setVisibility(View.GONE);bottom.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S3));bottom.setClipToPadding(false);root.addView(bottom,Ui.fill());
        if(tab>=0){nav=new BottomNav(this,p,tab,this::navigate);root.addView(nav,Ui.fill());}
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            boolean keyboard;
            if(Build.VERSION.SDK_INT>=30){
                Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);
                keyboard=insets.isVisible(WindowInsets.Type.ime());
            }else{
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
                keyboard=insets.getSystemWindowInsetBottom()-insets.getStableInsetBottom()>ui.dp(120);
            }
            // La barra de pestañas se esconde mientras está el teclado (PROPUESTA #2): deja más espacio para escribir.
            if(nav!=null){int want=keyboard||navHidden?View.GONE:View.VISIBLE;if(nav.getVisibility()!=want)nav.setVisibility(want);}
            return insets;});
        root.requestApplyInsets();
        scroll.post(()->scroll.scrollTo(0,position));
    }
    /** La pantalla pidió esconder la barra de pestañas (p. ej. Grabar mientras graba: pantalla completa). */
    private boolean navHidden;
    /** Esconde o muestra la barra de pestañas (también la respeta el teclado). */
    void setNavHidden(boolean hidden){navHidden=hidden;if(nav!=null)nav.setVisibility(hidden?View.GONE:View.VISIBLE);}
    /**
     * Pasa el fondo a intenso (verde abajo) o a suave, con un fundido de 400 ms. También cambia el color de los íconos
     * de la barra de navegación del sistema según lo que queda detrás.
     */
    void setVivid(boolean vivid,boolean animate){
        if(backdrop==null)return;float to=vivid?1f:0f;if(vividAnim!=null)vividAnim.cancel();
        if(!animate||!AppTheme.motion()||backdrop.vivid()==to){backdrop.setVivid(to);AppTheme.window(this,p,backdrop.bottomColor());return;}
        vividAnim=android.animation.ValueAnimator.ofFloat(backdrop.vivid(),to);vividAnim.setDuration(MOTION_SLOW);vividAnim.setInterpolator(EMPHASIZED);
        vividAnim.addUpdateListener(a->backdrop.setVivid((float)a.getAnimatedValue()));
        vividAnim.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(!isDestroyed())AppTheme.window(Screen.this,p,backdrop.bottomColor());}});
        vividAnim.start();
    }
    /** Botón redondo de vidrio para la barra superior (a la derecha, p. ej. «Más opciones»). */
    ImageButton barButton(int res,String description,View.OnClickListener click){
        ImageButton b=ui.glassButton(res,description);b.setOnClickListener(click);
        if(barActions!=null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));lp.setMarginStart(ui.dp(S2));barActions.addView(b,lp);}
        return b;
    }
    /** Título grande (Outfit) + bajada opcional. */
    TextView largeTitle(ViewGroup parent,String title,String subtitle){
        TextView t=ui.heading(title,Type.HEADLINE_LARGE);t.setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(subtitle==null||subtitle.isEmpty()?S4:S1));parent.addView(t);
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
    void message(String title,String value){sheet(title,value).primary(getString(R.string.common_ok),()->{}).show();}
    void confirm(String title,String value,String action,boolean destructive,Runnable run){
        sheet(title,value).primary(action,destructive?Ui.Style.DESTRUCTIVE:Ui.Style.PRIMARY,()->{run.run();return true;}).secondary(getString(R.string.common_cancel),null).show();
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
        LinearLayout s=ui.row();s.setBackground(shape(this,p.inverseSurface,R_FULL));s.setPadding(ui.dp(S5),ui.dp(S1),ui.dp(S2),ui.dp(S1));s.setMinimumHeight(ui.dp(52));s.setElevation(ui.dp(4));
        s.addView(ui.text(text,Type.BODY_MEDIUM,p.inverseOnSurface),new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null&&run!=null){TextView a=ui.text(action,Type.LABEL_LARGE,p.inversePrimary);a.setGravity(Gravity.CENTER);a.setMinHeight(ui.dp(48));a.setPadding(ui.dp(S3),0,ui.dp(S3),0);a.setBackground(ui.ripple(null,R_SMALL));a.setAccessibilityDelegate(Ui.buttonRole());a.setOnClickListener(v->{hideSnackbar();run.run();});s.addView(a);}
        s.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S2));
        int at=bottom==null?root.getChildCount():root.indexOfChild(bottom);root.addView(s,Math.max(0,at),lp);snack=s;
        if(AppTheme.motion()){s.setAlpha(0f);s.setTranslationY(ui.dp(S6));s.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_DECELERATE).start();}
        s.announceForAccessibility(text+(action!=null?". "+getString(R.string.common_action_available,action):""));
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
    void shareFile(String filename,String title){android.net.Uri uri=AudioProvider.uri(this,filename);Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(android.content.ClipData.newRawUri(title,uri));startActivity(Intent.createChooser(share,title));}
    int dp(float n){return ui.dp(n);}
}
