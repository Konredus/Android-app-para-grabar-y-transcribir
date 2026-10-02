package cl.vozlocal.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Hoja inferior (bottom sheet): reemplaza los AlertDialog del sistema para menús, confirmaciones y formularios cortos.
 * Queda al alcance del pulgar y mantiene el contexto visible detrás. Ver docs/diseno/CRITERIOS.md → "Hojas".
 *
 * 0.7.0 (Verbapp): blanca con esquinas de 32 dp, título en Outfit y, en Android 12+, lo de atrás se ve desenfocado.
 * Con dos botones cortos van lado a lado como en la referencia ([Cancelar] gris · [Guardar] tinta); si alguno es
 * largo, se apilan (el principal arriba).
 */
final class Sheet {
    final Dialog dialog;final LinearLayout body;final Ui ui;private final Activity activity;
    private LinearLayout list,buttons;private final LinearLayout titleRow;private ImageButton close;
    /** Botones pendientes de ubicar (se ordenan al mostrar la hoja). */
    private final List<Ui.Btn> primaries=new ArrayList<>(),secondaries=new ArrayList<>();

    Sheet(Activity activity,Ui ui,String title,String message){
        this.activity=activity;this.ui=ui;dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout frame=ui.column();GradientDrawable bg=new GradientDrawable();bg.setColor(ui.p.dark?ui.p.surfaceContainerLow:ui.p.surfaceContainerLowest);float r=ui.dp(R_SHEET);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});frame.setBackground(bg);
        View grabber=new View(activity);grabber.setBackground(shape(activity,ui.p.outlineVariant,99));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(36),ui.dp(4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.topMargin=ui.dp(S3);frame.addView(grabber,gp);
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);body=ui.column();body.setClipToPadding(false);body.setPadding(ui.dp(S6),ui.dp(S4),ui.dp(S6),ui.dp(S5));scroll.addView(body);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ((LinearLayout.LayoutParams)scroll.getLayoutParams()).height=-2;((LinearLayout.LayoutParams)scroll.getLayoutParams()).weight=0;
        titleRow=ui.row();titleRow.setGravity(Gravity.CENTER_VERTICAL);
        if(title!=null&&!title.isEmpty()){TextView t=ui.heading(title,Type.HEADLINE_SMALL);t.setTextSize(22);titleRow.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
        else titleRow.addView(ui.flex());
        titleRow.setPadding(0,0,0,ui.dp(message==null||message.isEmpty()?S3:S2));
        if(title!=null&&!title.isEmpty())body.addView(titleRow,Ui.fill());
        if(message!=null&&!message.isEmpty()){TextView m=ui.text(message,Type.BODY_MEDIUM,ui.p.onSurfaceVariant);m.setPadding(0,0,0,ui.dp(S4));body.addView(m);}
        dialog.setContentView(frame);
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawable(new ColorDrawable(0));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);w.setWindowAnimations(android.R.style.Animation_InputMethod);w.setDimAmount(0.32f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setNavigationBarColor(ui.p.dark?ui.p.surfaceContainerLow:ui.p.surfaceContainerLowest);
            // Android 11+: la hoja se dibuja también detrás de la barra de navegación (el blanco llega hasta abajo, sin franja
            // verde de la pantalla de atrás). El contenido se corre por esa barra y por el teclado.
            if(Build.VERSION.SDK_INT>=30){
                w.setDecorFitsSystemWindows(false);int base=body.getPaddingBottom();
                // La hoja es otra ventana y no hereda los íconos que pidió la pantalla (AppTheme.window): con tema claro la hoja es
                // blanca y, sin pedirlo aquí, los botones de navegación (◁ ○ □) quedan blancos sobre blanco. Claro → íconos
                // oscuros; oscuro → íconos claros. Se pide después de setDecorFitsSystemWindows.
                int light=WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,navIcons=ui.p.dark?0:light;
                WindowInsetsController controller=w.getInsetsController();
                if(controller!=null)controller.setSystemBarsAppearance(navIcons,light);
                // Si la ventana aún no tiene controlador, se pide apenas la hoja entra en pantalla.
                else w.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                    @Override public void onViewAttachedToWindow(View v){WindowInsetsController c=v.getWindowInsetsController();if(c!=null)c.setSystemBarsAppearance(navIcons,light);}
                    @Override public void onViewDetachedFromWindow(View v){}});
                // Android 11 y 12 borran ese pedido en una ventana que oscurece lo de atrás, como esta: los íconos quedarían
                // blancos igual. Ahí, con navegación de 3 botones (su medida «tocable» es mayor que 0; con gestos es 0 y la
                // barrita se adapta sola al fondo), la hoja termina sobre la barra, como en 0.6.0, y los botones se ven sobre la
                // pantalla oscurecida.
                boolean above=false;
                if(Build.VERSION.SDK_INT<DARK_NAV_ICONS_FROM){
                    WindowInsets ri=activity.getWindow().getDecorView().getRootWindowInsets();if(ri==null)ri=activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
                    android.graphics.Insets tap=ri.getInsets(WindowInsets.Type.tappableElement());above=tap.bottom>0||tap.left>0||tap.right>0;
                }
                // La ventana se aleja solo de la barra de estado (una hoja muy alta no queda bajo el reloj), no de la de navegación
                // (salvo en el caso anterior).
                WindowManager.LayoutParams a=w.getAttributes();a.setFitInsetsTypes(above?WindowInsets.Type.systemBars():WindowInsets.Type.statusBars());w.setAttributes(a);
                // Las medidas se toman en la ventana completa de la hoja: el marco interno del diálogo puede no recibirlas.
                // Además de abajo, el contenido se corre por los costados: en horizontal la barra de 3 botones y el recorte de la
                // pantalla van a un lado, y sin ese margen el botón principal y el ✕ quedaban debajo (el sistema se lleva esos toques).
                w.getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
                    android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());
                    frame.setPadding(bars.left,0,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});
                body.setPadding(body.getPaddingLeft(),body.getPaddingTop(),body.getPaddingRight(),base);
            }
            // Android 12+: lo de atrás se desenfoca (vidrio). Solo si el teléfono lo permite (ahorro de batería, gama baja).
            if(Build.VERSION.SDK_INT>=31)try{WindowManager wm=activity.getSystemService(WindowManager.class);if(wm!=null&&wm.isCrossWindowBlurEnabled()){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.getAttributes().setBlurBehindRadius(ui.dp(18));w.setDimAmount(0.22f);}}catch(RuntimeException ignored){}
        }
    }
    /**
     * Primera versión de Android (13) que respeta los íconos oscuros de navegación pedidos por una ventana que oscurece lo
     * de atrás. En las anteriores con Android 11+, la hoja no pasa por detrás de la barra de 3 botones (ver el constructor).
     */
    private static final int DARK_NAV_ICONS_FROM=33;
    Sheet add(View v){body.addView(v,Ui.fill());return this;}
    /**
     * Botón ✕ redondo junto al título (como «Nombra esta grabación» en la referencia). onClose puede ser null.
     * Se ve de 40 dp pero se toca en 48×48 dp (el mínimo de CRITERIOS): el círculo de vidrio va 4 dp hacia adentro.
     */
    Sheet closable(Runnable onClose){
        if(close!=null)return this;
        close=ui.glassButton(R.drawable.ic_close,Lang.str(activity,R.string.ui_close));close.setElevation(0);
        // El InsetDrawable trae su propio relleno (4 dp parejos) y setBackground lo aplica a la vista: no se pone otro relleno
        // después. El ícono va centrado (ScaleType.CENTER), así que no se mueve. El vidrio se suelta antes de envolverlo: si
        // no, al cambiar el fondo la vista le quita el aviso de redibujo al fondo anterior y la onda del toque no se animaría.
        int in=ui.dp(4);Drawable glass=close.getBackground();close.setBackground(null);close.setBackground(new InsetDrawable(glass,in));
        close.setOnClickListener(v->{dialog.dismiss();if(onClose!=null)onClose.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));lp.setMarginStart(ui.dp(S2)-in);titleRow.addView(close,lp);
        if(titleRow.getParent()==null)body.addView(titleRow,0,Ui.fill());
        // Los 4 dp de más por lado no mueven nada de lugar: la fila sale 4 dp hacia arriba y hacia el final (dentro del relleno
        // del cuerpo, así toda el área queda dentro de la fila y recibe el toque), pierde 4 dp de relleno abajo, y el título
        // los recupera como margen (con un título de varias líneas también queda todo igual que antes).
        LinearLayout.LayoutParams tp=(LinearLayout.LayoutParams)titleRow.getLayoutParams();tp.topMargin=-in;tp.setMarginEnd(-in);titleRow.setLayoutParams(tp);
        titleRow.setPadding(0,0,0,Math.max(0,titleRow.getPaddingBottom()-in));
        View head=titleRow.getChildAt(0);LinearLayout.LayoutParams hp=(LinearLayout.LayoutParams)head.getLayoutParams();hp.topMargin=in;hp.bottomMargin=in;head.setLayoutParams(hp);
        return this;
    }

    /** Opción de menú (lista, sin tarjeta). Íconos neutros; las destructivas van en color error y al final. */
    Sheet action(int icon,String label,boolean destructive,Runnable run){
        if(list==null){list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);body.addView(list,lp);}
        int color=destructive?ui.p.error:ui.p.onSurface;
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(56));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        row.addView(ui.icon(icon,destructive?ui.p.error:ui.p.onSurfaceVariant,24));row.addView(ui.space(S4));TextView t=ui.text(label,Type.ITEM,color);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(label);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",activity.getClass().getSimpleName(),"action",label);dialog.dismiss();run.run();});
        list.addView(row,Ui.fill());return this;
    }
    /** Opción grande de dos líneas (ícono en círculo menta + título + explicación), para decisiones importantes. */
    Sheet option(int icon,String label,String detail,Runnable run){
        if(list==null){list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);body.addView(list,lp);}
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(72));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        row.addView(ui.tile(icon,ui.p.onPrimaryContainer,ui.p.primaryContainer,44,22));row.addView(ui.space(S4));
        LinearLayout texts=ui.column();texts.addView(ui.text(label,Type.TITLE_MEDIUM,ui.p.onSurface));TextView d=ui.text(detail,Type.BODY_MEDIUM,ui.p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(label+". "+detail);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",activity.getClass().getSimpleName(),"action",label);dialog.dismiss();run.run();});
        list.addView(row,Ui.fill());return this;
    }
    /** Opción de selección única con botón de radio (patrón de Material para elegir uno entre varios). */
    Sheet choice(String label,String detail,boolean selected,Runnable run){return choice(label,detail,selected,0,run);}
    /** dot: color de la persona (el mismo punto que en la transcripción); 0 = sin punto. */
    Sheet choice(String label,String detail,boolean selected,int dot,Runnable run){
        if(list==null){list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);body.addView(list,lp);}
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(56));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        row.addView(ui.icon(selected?R.drawable.ic_radio_on:R.drawable.ic_radio_off,selected?ui.p.primary:ui.p.onSurfaceVariant,24));row.addView(ui.space(S4));
        if(dot!=0){View d=new View(activity);d.setBackground(oval(dot));row.addView(d,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));row.addView(ui.space(S3));}
        LinearLayout texts=ui.column();texts.addView(ui.text(label,Type.ITEM,ui.p.onSurface));if(detail!=null&&!detail.isEmpty()){TextView d=ui.text(detail,Type.BODY_MEDIUM,ui.p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);}
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setContentDescription(selected?Lang.str(activity,R.string.ui_selected_item,label):label);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->{dialog.dismiss();if(!selected)run.run();});
        list.addView(row,Ui.fill());return this;
    }
    private LinearLayout buttons(){if(buttons==null){buttons=ui.column();buttons.setPadding(0,ui.dp(S4),0,0);body.addView(buttons,Ui.fill());}return buttons;}
    /** Botón principal. Si run devuelve false la hoja queda abierta (p. ej. validación). */
    interface Check{boolean run();}
    Sheet primary(String label,Ui.Style style,Check run){buttons();Ui.Btn b=ui.button(label,0,style,v->{if(run.run())dialog.dismiss();});if(laidOut)stack(b);else primaries.add(b);return this;}
    Sheet primary(String label,Runnable run){return primary(label,Ui.Style.PRIMARY,()->{run.run();return true;});}
    /** Botón secundario: píldora gris suave (Cancelar, Listo, Ahora no). */
    Sheet secondary(String label,Runnable run){buttons();Ui.Btn b=ui.button(label,0,Ui.Style.SECONDARY,v->{dialog.dismiss();if(run!=null)run.run();});if(laidOut)stack(b);else secondaries.add(b);return this;}
    /** Ya mostrada la hoja, un botón nuevo va abajo de los que hay. */
    private void stack(Ui.Btn b){LinearLayout.LayoutParams lp=Ui.fill();if(buttons.getChildCount()>0)lp.topMargin=ui.dp(S2);buttons.addView(b,lp);}
    private boolean laidOut;
    Sheet onDismiss(Runnable run){dialog.setOnDismissListener(d->run.run());return this;}
    Sheet secure(){if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);return this;}
    Sheet show(){if(!activity.isFinishing()&&!activity.isDestroyed()){layoutButtons();dialog.show();settle();}return this;}
    /**
     * Ubica los botones: un principal y un secundario cortos van lado a lado, el secundario a la izquierda; si no, se
     * apilan con los principales arriba. «Cortos» = ≤ 14 letras cada uno y, además, que quepan en una línea en su mitad de
     * la hoja (ver fitsHalf): con «Tamaño de fuente» grande, «Ver grabación» ya no cabe en media hoja y se cortaba.
     */
    private void layoutButtons(){
        if(laidOut)return;laidOut=true;
        if(buttons==null||primaries.isEmpty()&&secondaries.isEmpty())return;
        boolean side=primaries.size()==1&&secondaries.size()==1;
        if(side){int half=halfWidth();side=fitsHalf(primaries.get(0),half)&&fitsHalf(secondaries.get(0),half);}
        if(side){
            LinearLayout row=ui.row();row.setBaselineAligned(false);Ui.Btn s=secondaries.get(0),pr=primaries.get(0);
            // Alto mínimo de 52 dp (lo trae Btn), no fijo: si el texto igual pasara a dos líneas (p. ej. al aparecer el indicador
            // de carga), el botón crece en vez de cortarlo, y el otro lo acompaña (alto -1: el de la fila).
            s.setPadding(ui.dp(S3),ui.dp(S2),ui.dp(S3),ui.dp(S2));pr.setPadding(ui.dp(S3),ui.dp(S2),ui.dp(S3),ui.dp(S2));
            row.addView(s,new LinearLayout.LayoutParams(0,-1,1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMarginStart(ui.dp(S3));row.addView(pr,lp);
            buttons.addView(row,Ui.fill());return;
        }
        boolean first=true;
        for(Ui.Btn b:primaries){LinearLayout.LayoutParams lp=Ui.fill();if(!first)lp.topMargin=ui.dp(S2);buttons.addView(b,lp);first=false;}
        for(Ui.Btn b:secondaries){LinearLayout.LayoutParams lp=Ui.fill();if(!first)lp.topMargin=ui.dp(S2);buttons.addView(b,lp);first=false;}
    }
    /**
     * Ancho de cada mitad de la fila de botones, en px: el de la hoja (el de la pantalla, sin la barra lateral ni el
     * recorte, que en horizontal le quitan ancho) menos su relleno (S6 por lado) y la separación entre los dos (S3).
     */
    private int halfWidth(){
        int w=activity.getResources().getDisplayMetrics().widthPixels;
        if(Build.VERSION.SDK_INT>=30){
            View d=activity.getWindow().getDecorView();WindowInsets ri=d.getRootWindowInsets();
            if(ri!=null&&d.getWidth()>0){android.graphics.Insets bars=ri.getInsets(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());w=d.getWidth()-bars.left-bars.right;}
        }
        return (w-2*ui.dp(S6)-ui.dp(S3))/2;
    }
    /**
     * ¿La etiqueta cabe en una línea dentro de media hoja? Se mide el texto con la letra real del botón (Outfit, su
     * espaciado y el tamaño en px ya escalado por «Tamaño de fuente»), no contando letras: a 130 % «Ver grabación» no cabe.
     * Se descuenta el relleno lateral del botón (S3 por lado) y 4 dp de holgura por el redondeo.
     */
    private boolean fitsHalf(Ui.Btn b,int half){
        CharSequence t=b.label.getText();
        return t.length()<=14&&b.label.getPaint().measureText(t,0,t.length())<=half-2*ui.dp(S3)-ui.dp(S1);
    }
    /**
     * Mientras la hoja sube, su contenido se asienta con un resorte leve (Material 3 Expressive): parte 24 dp más abajo
     * y aparece con la curva emphasized. Con «Quitar animaciones» se muestra quieta.
     */
    private void settle(){
        if(!AppTheme.motion())return;
        body.setAlpha(0f);body.setTranslationY(ui.dp(S6));
        body.animate().translationY(0f).setStartDelay(0).setDuration(SPATIAL.duration).setInterpolator(SPATIAL).start();
        body.animate().alpha(1f).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_DECELERATE).start();
    }
    void dismiss(){dialog.dismiss();}
}
