package cl.vozlocal.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Kit de componentes construido de forma nativa (sin la librería de Material), con la estética «Bosque de vidrio» de
 * Verbapp (0.7.0): píldoras, vidrio translúcido, tinta para lo principal y Outfit en títulos y botones.
 * Cada pantalla arma su UI solo con estas piezas, así toda la app sigue la misma línea.
 * Ver docs/diseno/CRITERIOS.md → "Componentes" y docs/diseno/PROPUESTA-0.7.md.
 */
final class Ui {
    /**
     * Estilos de botón (todos en forma de píldora):
     * PRIMARY = tinta (negro verdoso; en oscuro, blanco menta), lo principal de la pantalla · TONAL = menta ·
     * SECONDARY = gris suave (Cancelar, Listo) · PLAIN = solo texto verde · DESTRUCTIVE = error ·
     * RECORD = verde de marca (grabar) · VIVID = vidrio blanco sobre el verde intenso (Detener, Pausa).
     */
    enum Style{PRIMARY,TONAL,SECONDARY,PLAIN,DESTRUCTIVE,RECORD,VIVID}

    final Context c;final Palette p;
    Ui(Context c,Palette p){this.c=c;this.p=p;}
    int dp(float n){return AppTheme.dp(c,n);}

    // ---------- Layout ----------
    LinearLayout column(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    View space(int dp){View v=new View(c);v.setLayoutParams(new LinearLayout.LayoutParams(dp(dp),dp(dp)));return v;}
    View flex(){View v=new View(c);v.setLayoutParams(new LinearLayout.LayoutParams(0,0,1));return v;}
    static LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-2,-2);}
    static LinearLayout.LayoutParams fill(){return new LinearLayout.LayoutParams(-1,-2);}
    static LinearLayout.LayoutParams weight(float w){return new LinearLayout.LayoutParams(0,-2,w);}
    LinearLayout.LayoutParams top(int dp){LinearLayout.LayoutParams lp=fill();lp.topMargin=dp(dp);return lp;}

    // ---------- Texto ----------
    TextView text(String value,Type type,int color){TextView t=new TextView(c);t.setText(value);t.setTextColor(color);AppTheme.type(t,type);t.setIncludeFontPadding(true);return t;}
    TextView heading(String value,Type type){TextView t=text(value,type,p.onSurface);if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    /** Subtítulo de lista (como en Ajustes de Android): verde de marca, Title Small (Outfit), sin mayúsculas forzadas. */
    TextView section(String value){TextView t=text(value,Type.TITLE_SMALL,p.primary);t.setPadding(dp(S4),dp(S6),dp(S4),dp(S2));if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    TextView footnote(String value){TextView t=text(value,Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(dp(S4),dp(S2),dp(S4),0);return t;}
    TextView oneLine(TextView t){t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}
    /** Números que cambian (cronómetro, duraciones, contadores): cifras de ancho fijo (Outfit trae «tnum»), así no bailan. */
    static TextView tabular(TextView t){t.setFontFeatureSettings("tnum");return t;}
    /**
     * Destaca la última palabra de un título grande con un rectángulo menta detrás (como «Simple» en la referencia).
     * El recuadro es una sola pieza y Android no puede partirlo entre líneas. Por eso solo se destaca si la palabra es
     * corta (≤ 16 letras) y, además, cabe entera con su recuadro en una línea, medida con la letra real del TextView (con
     * «Tamaño de fuente» grande, «mantenimiento» a 36 sp ya no cabe). Si no cabe, el título va como texto simple: Android
     * parte la palabra y se lee completa, en vez de salirse por el borde o quedar reemplazada por «…».
     * No se mira maxLines: ese límite cambia después de poner el texto (la vista de grabación se compacta). Si el TextView
     * termina cortando el título con «…», el recuadro no queda suelto: Android 10+ lo descarta y en Android 8–9 lo hace
     * {@link Glass.Highlight}.
     */
    void highlightLast(TextView t,String value){
        String v=value==null?"":value.trim();int cut=v.lastIndexOf(' ');String last=v.substring(cut+1);int pad=dp(6);
        if(v.isEmpty()||last.length()>16||last.length()<2||t.getPaint().measureText(last)+2*pad>lineWidth(t)){t.setText(v);return;}
        android.text.SpannableString s=new android.text.SpannableString(v);
        s.setSpan(new Glass.Highlight(p.highlight,pad,dp(10)),cut+1,v.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);t.setContentDescription(v);
    }
    /**
     * Ancho útil de una línea de t, en px. Si ya está en pantalla y su ancho lo fija su contenedor, el real. Si no (recién
     * creado, o ancho según su propio texto), el de la pantalla menos 104 dp: el título más angosto que usa el recuadro
     * (Detalle: márgenes de la página + lápiz de 48 dp). Quedarse corto solo quita el recuadro; la palabra no se pierde.
     */
    private int lineWidth(TextView t){
        ViewGroup.LayoutParams lp=t.getLayoutParams();
        if(t.getWidth()>0&&lp!=null&&lp.width!=ViewGroup.LayoutParams.WRAP_CONTENT)return t.getWidth()-t.getCompoundPaddingLeft()-t.getCompoundPaddingRight();
        return c.getResources().getDisplayMetrics().widthPixels-dp(104);
    }
    /** Logo + nombre «Verbapp» (Outfit, levemente inclinado como en la referencia). textSp: tamaño del nombre. */
    LinearLayout brand(float textSp){
        LinearLayout r=row();r.setContentDescription("Verbapp");r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        int mark=Math.round(textSp*1.3f);r.addView(new Glass.BrandMark(c,p.onSurface,p.brand),new LinearLayout.LayoutParams(dp(mark),dp(mark)));r.addView(space(S1));
        TextView name=text("Verbapp",Type.TITLE_LARGE,p.onSurface);name.setTextSize(textSp);name.setTypeface(AppTheme.outfit(c,Weight.MEDIUM));name.getPaint().setTextSkewX(-0.12f);name.setLetterSpacing(-0.01f);name.setIncludeFontPadding(false);name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(name);return r;
    }

    // ---------- Superficies ----------
    /** Tarjeta de vidrio: blanco translúcido con borde claro y esquinas de 24 dp. */
    LinearLayout card(){LinearLayout l=column();l.setBackground(glass(c,p,R_CARD));l.setPadding(dp(S4),dp(S4),dp(S4),dp(S4));return l;}
    /** Grupo de lista: tarjeta de vidrio cuyas filas se separan con divisores finos (outlineVariant). */
    LinearLayout group(){LinearLayout l=column();l.setBackground(glass(c,p,R_CARD));l.setClipToOutline(true);return l;}
    /**
     * Dato de un resumen (p. ej. «Tu semana»): ícono en círculo blanco, número grande (Outfit) y rótulo.
     * Lo arma una tarjeta chica de vidrio; quien la usa decide el ancho (normalmente 1/3 de una fila).
     */
    LinearLayout stat(int icon,String value,String label){
        LinearLayout s=column();s.setBackground(shape(c,p.dark?p.glass:0xB3FFFFFF,R_CONTROL+4));s.setPadding(dp(S3),dp(S3),dp(S3),dp(S3));
        FrameLayout dot=new FrameLayout(c);dot.setBackground(glassOval(c,p));dot.addView(icon(icon,p.onSurface,16),new FrameLayout.LayoutParams(dp(16),dp(16),Gravity.CENTER));s.addView(dot,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView v=tabular(text(value,Type.TITLE_LARGE,p.onSurface));v.setPadding(0,dp(S3),0,0);oneLine(v);s.addView(v);
        TextView l=oneLine(text(label,Type.LABEL_MEDIUM,p.onSurfaceVariant));s.addView(l);
        s.setContentDescription(value+" "+label);s.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return s;
    }
    void addRow(LinearLayout group,View row){if(group.getChildCount()>0)group.addView(separator(S4+24+S4));group.addView(row,fill());}
    View separator(int insetDp){View v=new View(c);v.setBackgroundColor(p.outlineVariant);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(0.5f)));lp.setMarginStart(dp(insetDp));v.setLayoutParams(lp);return v;}
    Drawable ripple(Drawable content,int radius){return new RippleDrawable(ColorStateList.valueOf(p.ripple),content,content==null?shape(c,0xFF000000,radius):null);}
    /** Capa de estado de Material 3: el color del contenido (texto/ícono) al 12 %, así la onda se ve sobre cualquier relleno. */
    static int stateLayer(int content){return withAlpha(content,0x1F);}

    // ---------- Iconos ----------
    /** Íconos: Material Symbols Rounded de contorno (peso 400, 24 dp), en res/drawable con el mismo nombre de siempre. */
    ImageView icon(int res,int color,int sizeDp){ImageView i=new ImageView(c);i.setImageResource(res);i.setImageTintList(ColorStateList.valueOf(color));i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));return i;}
    /** Ícono dentro de un círculo tonal (avatar de Material). Siempre en la misma familia de color. */
    FrameLayout tile(int res,int fg,int bg,int sizeDp,int iconDp){FrameLayout f=new FrameLayout(c);f.setBackground(oval(bg));ImageView i=icon(res,fg,iconDp);f.addView(i,new FrameLayout.LayoutParams(dp(iconDp),dp(iconDp),Gravity.CENTER));f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return f;}
    /** Botón de ícono (Material "icon button"). Área táctil mínima 48 dp. Registra su descripción pasada por Diagnostics.safeAction. */
    ImageButton iconButton(int res,String description,int tint,int bg,int sizeDp){
        ImageButton b=new ImageButton(c){@Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",description);return super.performClick();}};
        b.setImageResource(res);b.setImageTintList(ColorStateList.valueOf(tint));b.setContentDescription(description);b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable shape=oval(bg==0?0x00000000:bg);b.setBackground(new RippleDrawable(ColorStateList.valueOf(bg==0?p.ripple:stateLayer(tint)),shape,oval(0xFF000000)));
        int size=Math.max(48,sizeDp);b.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));pressable(b);return b;
    }
    /** Botón de ícono redondo de vidrio (blanco con borde), como «volver» o «más» de la referencia. 48 dp. */
    ImageButton glassButton(int res,String description){
        ImageButton b=iconButton(res,description,p.onSurface,0,48);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),glassOval(c,p),oval(0xFF000000)));
        if(Build.VERSION.SDK_INT>=28&&!p.dark){b.setOutlineSpotShadowColor(p.shadow);b.setOutlineAmbientShadowColor(p.shadow);}
        b.setElevation(p.dark?0:dp(1));return b;
    }

    // ---------- Botones ----------
    /**
     * Botón Material 3 (forma de píldora). Es un LinearLayout para centrar ícono+texto; se anuncia como Button.
     * Al presionarlo se hunde (0,96) y cierra un poco las esquinas. setBusy muestra un indicador de carga en el
     * lugar del ícono y showDone lo transforma en ✓ (confirmación visible, PROPUESTA #12).
     */
    final class Btn extends LinearLayout {
        final TextView label;final ImageView glyph;final Style style;
        /** Colores actuales del contenido y del relleno (cambian con setColors, p. ej. al pasar a tonal). */
        int fg,bg;
        GradientDrawable fill;
        private final FrameLayout slot;private ProgressBar spinner;private boolean busy,hasIcon,done;
        Btn(String text,int iconRes,Style style){
            super(c);this.style=style;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setClickable(true);setFocusable(true);
            switch(style){
                case PRIMARY:fg=p.onInk;bg=p.ink;break;
                case RECORD:fg=p.onBrand;bg=p.brand;break;
                case TONAL:fg=p.onPrimaryContainer;bg=p.primaryContainer;break;
                case SECONDARY:fg=p.onSurface;bg=p.dark?p.surfaceContainerHighest:p.surfaceContainerHigh;break;
                case DESTRUCTIVE:fg=p.onError;bg=p.error;break;
                case VIVID:fg=p.onVivid;bg=p.glassOnVivid;break;
                case PLAIN:fg=p.primary;break;
                default:fg=p.primary;
            }
            if(style==Style.VIVID)fill=outline(c,bg,p.glassOnVividStroke,R_FULL,false);else fill=bg==0?null:shape(c,bg,R_FULL);
            setBackground(new RippleDrawable(ColorStateList.valueOf(stateLayer(fg)),fill,shape(c,0xFF000000,R_FULL)));
            int h=style==Style.PLAIN?44:52;setMinimumHeight(dp(h));setPadding(dp(style==Style.PLAIN?S3:S6),dp(S2),dp(style==Style.PLAIN?S3:S6),dp(S2));
            // El ícono vive en un espacio fijo de 18 dp que comparte con el indicador de carga: así el texto no salta.
            slot=new FrameLayout(c);slot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);hasIcon=iconRes!=0;
            glyph=icon(hasIcon?iconRes:R.drawable.ic_check,fg,18);slot.addView(glyph,new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(18),dp(18));lp.setMarginEnd(dp(S2));addView(slot,lp);slot.setVisibility(hasIcon?VISIBLE:GONE);
            label=text(text,Type.LABEL_LARGE,fg);label.setTextSize(15);label.setGravity(Gravity.CENTER);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(label,wrap());
            setContentDescription(text);pressable(this);
        }
        void setText(String value){label.setText(value);setContentDescription(value);}
        /** Cambia el ícono; 0 lo oculta. Tras showDone, pedir ic_check conserva el ✓ animado. */
        void setIcon(int res){
            if(done&&res==R.drawable.ic_check)return;
            done=false;hasIcon=res!=0;if(hasIcon){glyph.animate().cancel();glyph.setScaleX(1f);glyph.setScaleY(1f);glyph.setImageResource(res);}
            if(!busy){glyph.setVisibility(VISIBLE);slot.setVisibility(hasIcon?VISIBLE:GONE);}
        }
        /** Recolorea contenido y relleno (sin crear otro fondo). */
        void setColors(int fg,int bg){
            this.fg=fg;this.bg=bg;label.setTextColor(fg);glyph.setImageTintList(ColorStateList.valueOf(fg));if(spinner!=null)spinner.setIndeterminateTintList(ColorStateList.valueOf(fg));
            if(fill!=null)fill.setColor(bg);
            if(getBackground() instanceof RippleDrawable)((RippleDrawable)getBackground()).setColor(ColorStateList.valueOf(stateLayer(fg)));
        }
        /** Reemplaza la forma del fondo por esquinas propias (en px, orden de GradientDrawable.setCornerRadii). */
        void setCorners(float[] radii){
            GradientDrawable f=new GradientDrawable();f.setColor(bg);f.setCornerRadii(radii.clone());GradientDrawable mask=new GradientDrawable();mask.setColor(0xFF000000);mask.setCornerRadii(radii.clone());
            fill=f;setBackground(new RippleDrawable(ColorStateList.valueOf(stateLayer(fg)),f,mask));
        }
        boolean busy(){return busy;}
        /** Trabajo en curso: indicador de carga en lugar del ícono; el botón no acepta toques hasta terminar. */
        void setBusy(boolean on){
            if(busy==on)return;busy=on;setClickable(!on);
            if(on){
                if(done){done=false;glyph.animate().cancel();glyph.setScaleX(1f);glyph.setScaleY(1f);}
                if(spinner==null){spinner=new ProgressBar(c,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminate(true);spinner.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);slot.addView(spinner,new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER));}
                spinner.setIndeterminateTintList(ColorStateList.valueOf(fg));spinner.animate().cancel();spinner.setAlpha(1f);spinner.setScaleX(1f);spinner.setScaleY(1f);spinner.setVisibility(VISIBLE);
                glyph.setVisibility(INVISIBLE);slot.setVisibility(VISIBLE);if(Build.VERSION.SDK_INT>=30)setStateDescription(Lang.str(c,R.string.ui_busy));
            }else{
                if(spinner!=null){spinner.animate().cancel();spinner.setVisibility(GONE);}
                glyph.setVisibility(VISIBLE);slot.setVisibility(hasIcon?VISIBLE:GONE);if(Build.VERSION.SDK_INT>=30)setStateDescription(null);
            }
        }
        /** Termina el trabajo con una confirmación visible: el indicador de carga se transforma en ✓. */
        void showDone(){
            busy=false;done=true;setClickable(true);if(Build.VERSION.SDK_INT>=30)setStateDescription(null);
            hasIcon=true;slot.setVisibility(VISIBLE);boolean motion=AppTheme.motion();
            glyph.animate().cancel();glyph.setImageResource(R.drawable.avd_check);glyph.setVisibility(VISIBLE);
            if(spinner!=null&&spinner.getVisibility()==VISIBLE){
                ProgressBar sp=spinner;
                if(motion)sp.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setStartDelay(0).setDuration(MOTION_FAST).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(()->{sp.setVisibility(GONE);sp.setAlpha(1f);sp.setScaleX(1f);sp.setScaleY(1f);}).start();
                else sp.setVisibility(GONE);
            }
            if(motion){glyph.setScaleX(0.5f);glyph.setScaleY(0.5f);glyph.animate().scaleX(1f).scaleY(1f).setStartDelay(MOTION_FAST/2).setDuration(SPATIAL_FAST.duration).setInterpolator(SPATIAL_FAST).start();}
            else{glyph.setScaleX(1f);glyph.setScaleY(1f);}
            Drawable d=glyph.getDrawable();if(d instanceof Animatable)((Animatable)d).start();
        }
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.38f);}
        /**
         * Registra la etiqueta visible (p. ej. «Detener y guardar»). Puede ser contenido del usuario (el título que se está
         * grabando, un nombre): Diagnostics.event la pasa por {@link Diagnostics#safeAction}, en su hilo, antes de guardarla.
         */
        @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label.getText().toString());return super.performClick();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    }
    Btn button(String text,int icon,Style style,View.OnClickListener click){Btn b=new Btn(text,icon,style);if(click!=null)b.setOnClickListener(click);return b;}

    /**
     * Botón de dos partes de Material 3 Expressive: acción principal (píldora de tinta) + ▾ con las demás,
     * separados por 2 dp y ambos de 52 dp de alto. Las esquinas interiores son pequeñas, así se leen como un solo control.
     * Al abrir el menú, la flecha gira y su segmento se redondea; vuelve solo cuando la hoja se cierra.
     */
    final class Split extends LinearLayout {
        final Btn main;final ImageButton more;
        private final GradientDrawable moreFill,moreMask;private final RotateDrawable chevron;
        private final float[] mainCorners,moreCorners,moreRound;
        private boolean tonal,menuOpen;private ValueAnimator colors,menu;
        Split(String label,int icon,View.OnClickListener onMain,View.OnClickListener onMore){
            super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            float o=dp(26),i=dp(R_SMALL);
            mainCorners=new float[]{o,o,i,i,i,i,o,o};moreCorners=new float[]{i,i,o,o,o,o,i,i};moreRound=new float[]{o,o,o,o,o,o,o,o};
            main=button(label,icon,Style.PRIMARY,onMain);main.setCorners(mainCorners);main.setPadding(dp(S6),0,dp(S5),0);oneLine(main.label);
            addView(main,new LinearLayout.LayoutParams(0,dp(52),1));
            chevron=new RotateDrawable();chevron.setDrawable(c.getDrawable(R.drawable.ic_chevron_down).mutate());chevron.setFromDegrees(0f);chevron.setToDegrees(180f);chevron.setLevel(0);
            more=iconButton(R.drawable.ic_chevron_down,Lang.str(c,R.string.ui_more_save_share),p.onInk,0,52);more.setImageDrawable(chevron);more.setImageTintList(ColorStateList.valueOf(p.onInk));
            moreFill=new GradientDrawable();moreFill.setColor(p.ink);moreFill.setCornerRadii(moreCorners.clone());moreMask=new GradientDrawable();moreMask.setColor(0xFF000000);moreMask.setCornerRadii(moreCorners.clone());
            more.setBackground(new RippleDrawable(ColorStateList.valueOf(stateLayer(p.onInk)),moreFill,moreMask));pressable(more,false);
            more.setOnClickListener(v->{openMenu();if(onMore!=null)onMore.onClick(v);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(52),dp(52));lp.setMarginStart(dp(2));addView(more,lp);
        }
        void setLabel(String v){main.setText(v);}
        void setIcon(int res){main.setIcon(res);}
        boolean tonal(){return tonal;}
        /** Menta (primaryContainer) para un estado ya logrado, p. ej. «✓ En 0-Inbox · 16:09»; tinta para la acción pendiente. */
        void setTonal(boolean value){
            if(tonal==value)return;tonal=value;
            int fromFg=main.fg,fromBg=main.bg,toFg=value?p.onPrimaryContainer:p.onInk,toBg=value?p.primaryContainer:p.ink;
            if(colors!=null)colors.cancel();
            if(!AppTheme.motion()||!isAttachedToWindow()){paint(toFg,toBg);return;}
            ArgbEvaluator ev=new ArgbEvaluator();colors=ValueAnimator.ofFloat(0f,1f);colors.setDuration(MOTION_BASE);colors.setInterpolator(EMPHASIZED);
            colors.addUpdateListener(a->{float f=a.getAnimatedFraction();paint((int)ev.evaluate(f,fromFg,toFg),(int)ev.evaluate(f,fromBg,toBg));});colors.start();
        }
        private void paint(int fg,int bg){
            main.setColors(fg,bg);moreFill.setColor(bg);more.setImageTintList(ColorStateList.valueOf(fg));
            if(more.getBackground() instanceof RippleDrawable)((RippleDrawable)more.getBackground()).setColor(ColorStateList.valueOf(stateLayer(fg)));
        }
        void setBusy(boolean busy){main.setBusy(busy);}
        /** El indicador de carga se transforma en ✓ y vibra como confirmación. */
        void showDone(){main.showDone();haptic(this,Haptic.CONFIRM);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);main.setEnabled(enabled);more.setEnabled(enabled);more.setAlpha(enabled?1f:0.38f);}
        private void openMenu(){
            menuOpen=true;morphMenu(true);
            // Si el toque no abrió ninguna hoja (la ventana no perdió el foco), la flecha vuelve sola.
            postDelayed(()->{if(menuOpen&&hasWindowFocus())closeMenu();},600);
        }
        private void closeMenu(){menuOpen=false;morphMenu(false);}
        @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&menuOpen)closeMenu();}
        private void morphMenu(boolean open){
            if(menu!=null)menu.cancel();
            float[] from=moreFill.getCornerRadii(),to=open?moreRound:moreCorners;if(from==null)from=moreCorners.clone();
            int levelFrom=chevron.getLevel(),levelTo=open?10000:0;float[] start=from;
            if(!AppTheme.motion()||!isAttachedToWindow()){setMoreCorners(to);chevron.setLevel(levelTo);return;}
            menu=ValueAnimator.ofFloat(0f,1f);menu.setDuration(SPATIAL.duration);menu.setInterpolator(SPATIAL);
            menu.addUpdateListener(a->{float f=(float)a.getAnimatedValue();float[] r=new float[8];for(int k=0;k<8;k++)r[k]=Math.max(0f,start[k]+(to[k]-start[k])*f);setMoreCorners(r);chevron.setLevel(Math.round(levelFrom+(levelTo-levelFrom)*Math.min(1f,f)));});
            menu.start();
        }
        private void setMoreCorners(float[] r){moreFill.setCornerRadii(r.clone());moreMask.setCornerRadii(r.clone());}
    }
    Split split(String label,int icon,View.OnClickListener main,View.OnClickListener more){return new Split(label,icon,main,more);}

    /** Acción vertical ícono + etiqueta (barra inferior de acciones). Neutra: onSurfaceVariant. */
    LinearLayout action(int res,String label,View.OnClickListener click){
        LinearLayout l=column();l.setGravity(Gravity.CENTER);l.setPadding(dp(S1),dp(S2),dp(S1),dp(S2));l.setMinimumHeight(dp(56));l.setClickable(true);l.setFocusable(true);l.setContentDescription(label);
        l.setBackground(ripple(null,R_CONTROL));l.addView(icon(res,p.onSurfaceVariant,24));TextView t=text(label,Type.LABEL_MEDIUM,p.onSurfaceVariant);t.setPadding(0,dp(S1),0,0);t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);l.addView(t);
        l.setAccessibilityDelegate(buttonRole());l.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label);click.onClick(v);});pressable(l);return l;
    }

    // ---------- Chips ----------
    /** Chip de estado (píldora de 32 dp de alto). */
    TextView chip(String value,int fg,int bg){TextView t=text(value,Type.LABEL_LARGE,fg);t.setBackground(shape(c,bg,R_FULL));t.setPadding(dp(S3),dp(6),dp(S3),dp(6));t.setGravity(Gravity.CENTER);oneLine(t);return t;}
    /** Chip de vidrio con borde fino e ícono inicial opcional: estado neutro o acción secundaria. */
    TextView outlinedChip(String value,int icon,int iconColor){
        TextView t=text(value,Type.LABEL_LARGE,p.onSurfaceVariant);t.setBackground(ripple(outline(c,p.glass,p.outlineVariant,R_FULL,false),R_FULL));t.setPadding(dp(S3),dp(6),dp(S4),dp(6));t.setGravity(Gravity.CENTER_VERTICAL);t.setMinHeight(dp(36));oneLine(t);
        if(icon!=0){Drawable d=c.getDrawable(icon).mutate();d.setTint(iconColor);d.setBounds(0,0,dp(18),dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(dp(S2));}
        return t;
    }
    /** Chip de filtro (píldora): vidrio con borde si no está elegido; tinta con ✓ si está elegido. */
    TextView filter(String value,boolean selected,View.OnClickListener click){
        TextView t=text(value,Type.LABEL_LARGE,selected?p.onInk:p.onSurfaceVariant);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(selected?S3:S4),0,dp(S4),0);t.setMinHeight(dp(36));t.setMinimumHeight(dp(36));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),selected?shape(c,p.ink,R_FULL):outline(c,p.glass,p.outlineVariant,R_FULL,false),null));
        if(selected){Drawable d=c.getDrawable(R.drawable.ic_check).mutate();d.setTint(p.onInk);d.setBounds(0,0,dp(18),dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(dp(6));}
        t.setSelected(selected);t.setOnClickListener(click);t.setAccessibilityDelegate(buttonRole());t.setContentDescription(selected?Lang.str(c,R.string.ui_selected_item,value):value);return t;
    }

    // ---------- Filas de lista ----------
    /** Elemento de lista de Material 3: [ícono 24] título / texto de apoyo ........ valor */
    class Row extends LinearLayout {
        final TextView title,subtitle,value;
        Row(int iconRes,String titleText,String subtitleText,String valueText){
            super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(56));setPadding(dp(S4),dp(S3),dp(S4),dp(S3));
            if(iconRes!=0){ImageView i=icon(iconRes,p.onSurfaceVariant,24);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(24),dp(24));lp.setMarginEnd(dp(S4));addView(i,lp);}
            LinearLayout texts=column();title=text(titleText,Type.ITEM,p.onSurface);texts.addView(title);
            subtitle=text(subtitleText==null?"":subtitleText,Type.BODY_MEDIUM,p.onSurfaceVariant);subtitle.setVisibility(subtitleText==null||subtitleText.isEmpty()?GONE:VISIBLE);subtitle.setPadding(0,dp(2),0,0);texts.addView(subtitle);
            addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            value=text(valueText==null?"":valueText,Type.BODY_MEDIUM,p.onSurfaceVariant);value.setMaxWidth(dp(170));value.setGravity(Gravity.END);oneLine(value);value.setPadding(dp(S2),0,0,0);addView(value,wrap());
            value.setVisibility(valueText==null||valueText.isEmpty()?GONE:VISIBLE);
        }
        Row onClick(View.OnClickListener click){setBackground(ripple(null,0));setClickable(true);setFocusable(true);setAccessibilityDelegate(buttonRole());setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",title.getText().toString());click.onClick(v);});return this;}
        void setValue(String v){value.setText(v);value.setVisibility(v.isEmpty()?GONE:VISIBLE);}
        void setSubtitle(String v){subtitle.setText(v);subtitle.setVisibility(v.isEmpty()?GONE:VISIBLE);}
    }
    Row listRow(int icon,String title,String subtitle,String value){return new Row(icon,title,subtitle,value);}

    interface Toggle{void changed(boolean on);}
    /** Fila con interruptor de Material 3: toda la fila es el área táctil; vibra distinto al encender y al apagar. */
    final class SwitchRow extends Row {
        /** El Switch del sistema se mantiene (lo anuncia el lector de pantalla); solo cambia su dibujo. */
        final Switch control;
        SwitchRow(int iconRes,String titleText,String subtitleText,boolean initial,Toggle toggle){
            super(iconRes,titleText,subtitleText,null);
            control=new Switch(c);control.setShowText(false);control.setContentDescription(titleText);
            SwitchLook look=new SwitchLook();control.setTrackDrawable(look.track);control.setThumbDrawable(look.thumb);control.setSwitchMinWidth(dp(52));
            control.setChecked(initial);
            LinearLayout.LayoutParams lp=wrap();lp.setMarginStart(dp(S3));addView(control,lp);
            setBackground(ripple(null,0));setOnClickListener(v->control.toggle());
            control.setOnCheckedChangeListener((b,on)->{haptic(b,on?Haptic.TOGGLE_ON:Haptic.TOGGLE_OFF);toggle.changed(on);});
        }
        boolean isChecked(){return control.isChecked();}
        void setChecked(boolean on){control.setChecked(on);}
    }
    SwitchRow switchRow(int iconRes,String title,String subtitle,boolean initial,Toggle toggle){return new SwitchRow(iconRes,title,subtitle,initial,toggle);}

    /**
     * Dibujo del interruptor de Material 3: riel de 52×32 dp y perilla dentro.
     * Apagado: riel con borde (outline) y perilla chica de 16 dp; encendido: riel primary y perilla de 24 dp (onPrimary) con ✓.
     * Presionado: la perilla crece a 28 dp. El cambio de color y tamaño se anima (sin rebote: es color y forma).
     */
    private final class SwitchLook {
        float t;boolean on,pressed,enabled=true,drawn;ValueAnimator anim;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF rect=new RectF();final Drawable check;
        final Drawable track,thumb;
        SwitchLook(){
            check=c.getDrawable(R.drawable.ic_check).mutate();check.setTint(p.onPrimaryContainer);
            track=new Part(true);thumb=new Part(false);
        }
        void state(int[] state){
            boolean o=false,pr=false,en=false;for(int s:state){if(s==android.R.attr.state_checked)o=true;else if(s==android.R.attr.state_pressed)pr=true;else if(s==android.R.attr.state_enabled)en=true;}
            pressed=pr;enabled=en;if(o==on)return;on=o;float target=on?1f:0f;if(anim!=null)anim.cancel();
            if(!drawn||!AppTheme.motion()){t=target;return;}
            anim=ValueAnimator.ofFloat(t,target);anim.setDuration(MOTION_BASE);anim.setInterpolator(EMPHASIZED);
            anim.addUpdateListener(a->{t=(float)a.getAnimatedValue();track.invalidateSelf();thumb.invalidateSelf();});anim.start();
        }
        final class Part extends Drawable {
            final boolean isTrack;
            Part(boolean isTrack){this.isTrack=isTrack;}
            @Override public boolean isStateful(){return true;}
            @Override protected boolean onStateChange(int[] state){state(state);return true;}
            @Override public int getIntrinsicWidth(){return dp(isTrack?52:20);}
            @Override public int getIntrinsicHeight(){return dp(32);}
            /** El relleno del riel (6 dp a cada lado) fija el recorrido: centro de la perilla a 16 dp y a 36 dp. */
            @Override public boolean getPadding(Rect padding){if(!isTrack)return super.getPadding(padding);padding.set(dp(6),0,dp(6),0);return true;}
            @Override public void draw(Canvas canvas){
                drawn=true;Rect b=getBounds();if(b.isEmpty())return;int alpha=enabled?255:97;float cy=b.exactCenterY();
                if(isTrack){
                    float h=Math.min(b.height(),dp(32)),r=h/2f;rect.set(b.left,cy-h/2f,b.right,cy+h/2f);
                    paint.setStyle(Paint.Style.FILL);paint.setColor(blend(p.surfaceContainerHighest,p.primary,t));paint.setAlpha(alpha);canvas.drawRoundRect(rect,r,r,paint);
                    if(t<1f){float sw=dp(2);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(sw);paint.setColor(p.outline);paint.setAlpha(Math.round(alpha*(1f-t)));rect.inset(sw/2f,sw/2f);canvas.drawRoundRect(rect,r-sw/2f,r-sw/2f,paint);}
                }else{
                    float cx=b.exactCenterX(),r=pressed?dp(14):dp(8)+(dp(12)-dp(8))*t;
                    paint.setStyle(Paint.Style.FILL);paint.setColor(blend(pressed&&!on?p.onSurfaceVariant:p.outline,p.onPrimary,t));paint.setAlpha(alpha);canvas.drawCircle(cx,cy,r,paint);
                    if(t>0.02f){int half=dp(8);check.setBounds(Math.round(cx)-half,Math.round(cy)-half,Math.round(cx)+half,Math.round(cy)+half);check.setAlpha(Math.round(alpha*t));check.draw(canvas);}
                }
            }
            @Override public void setAlpha(int alpha){}
            @Override public void setColorFilter(ColorFilter filter){}
            @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        }
    }

    // ---------- Campos ----------
    /** Campo de texto: blanco (en oscuro, gris verdoso) con borde fino y esquinas de 16 dp. */
    EditText field(String hint,String description){
        EditText e=new EditText(c);e.setHint(hint);e.setContentDescription(description);e.setSingleLine(true);e.setTextColor(p.onSurface);e.setHintTextColor(p.onSurfaceVariant);AppTheme.type(e,Type.BODY_LARGE);
        e.setBackground(fieldBackground());e.setPadding(dp(S4),dp(S3),dp(S4),dp(S3));e.setMinHeight(dp(56));e.setMinimumHeight(dp(56));return e;
    }
    /** Fondo de un campo (también para cajas que envuelven un campo, p. ej. fecha fija + título). */
    GradientDrawable fieldBackground(){return outline(c,p.dark?p.surfaceContainerHigh:p.surfaceContainerLowest,p.outlineVariant,R_CONTROL,false);}
    /** Etiqueta + campo apilados. */
    EditText labeled(LinearLayout parent,String label,String hint){TextView t=text(label,Type.BODY_SMALL,p.onSurfaceVariant);t.setPadding(dp(S1),dp(S3),0,dp(6));parent.addView(t);EditText e=field(hint,label);parent.addView(e,fill());return e;}

    // ---------- Interacción ----------
    /**
     * Respuesta al tocar (Material 3 Expressive): la vista se hunde a 0,96 y, si su fondo es un rectángulo redondeado,
     * cierra un poco las esquinas; al soltar vuelve con un resorte corto. Sin animaciones del sistema, no hace nada.
     */
    static void pressable(View v){pressable(v,true);}
    /** morph=false: solo se hunde (para fondos que animan su forma por su cuenta). */
    static void pressable(View v,boolean morph){
        Press state=new Press();
        v.setOnTouchListener((view,event)->{
            int a=event.getActionMasked();
            if(a==MotionEvent.ACTION_DOWN&&view.isEnabled()){
                if(!AppTheme.motion())return false;
                view.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(MOTION_FAST).setInterpolator(EMPHASIZED).start();
                if(morph)state.press(view);
            }else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
                if(!AppTheme.motion()){view.animate().cancel();view.setScaleX(1f);view.setScaleY(1f);state.release(false);return false;}
                view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(SPATIAL_FAST.duration).setInterpolator(SPATIAL_FAST).start();
                state.release(true);
            }
            return false;});
    }
    /** Esquinas de una vista mientras se presiona: las originales para volver exacto y la animación en curso. */
    private static final class Press {
        GradientDrawable[] shapes;float[][] rest;float[] restUniform;ValueAnimator anim;
        void press(View view){
            if(anim!=null)anim.cancel();anim=null;
            // Un segundo toque mientras vuelve: primero se reponen las esquinas originales, para no guardar las intermedias.
            restore();
            // Solo las formas con alguna esquina grande (píldoras, tarjetas); las filas rectas no cambian.
            List<GradientDrawable> list=new ArrayList<>();collect(view.getBackground(),list);float threshold=AppTheme.dp(view.getContext(),14);
            List<GradientDrawable> round=new ArrayList<>();for(GradientDrawable g:list){for(float r:effective(g))if(r>=threshold){round.add(g);break;}}
            if(round.isEmpty()){shapes=null;return;}
            int n=round.size();shapes=round.toArray(new GradientDrawable[0]);rest=new float[n][];restUniform=new float[n];
            float[][] from=new float[n][],to=new float[n][];
            for(int i=0;i<n;i++){
                GradientDrawable g=shapes[i];rest[i]=g.getCornerRadii();restUniform[i]=g.getCornerRadius();
                from[i]=effective(g);to[i]=from[i].clone();for(int k=0;k<8;k++)if(to[i][k]>=threshold)to[i][k]*=0.65f;
            }
            animate(from,to,MOTION_FAST,EMPHASIZED,false);
        }
        void release(boolean animated){
            if(shapes==null)return;if(anim!=null)anim.cancel();anim=null;
            if(!animated){restore();return;}
            int n=shapes.length;float[][] from=new float[n][],to=new float[n][];
            for(int i=0;i<n;i++){from[i]=effective(shapes[i]);GradientDrawable probe=shapes[i];to[i]=clamp(rest[i]!=null?rest[i].clone():uniform(restUniform[i]),probe.getBounds());}
            animate(from,to,MOTION_BASE,EMPHASIZED_DECELERATE,true);
        }
        private void animate(float[][] from,float[][] to,long duration,android.animation.TimeInterpolator curve,boolean restoreAtEnd){
            ValueAnimator va=ValueAnimator.ofFloat(0f,1f);va.setDuration(duration);va.setInterpolator(curve);
            va.addUpdateListener(x->{float f=x.getAnimatedFraction();for(int i=0;i<shapes.length;i++){float[] r=new float[8];for(int k=0;k<8;k++)r[k]=from[i][k]+(to[i][k]-from[i][k])*f;shapes[i].setCornerRadii(r);}});
            if(restoreAtEnd)va.addListener(new android.animation.AnimatorListenerAdapter(){boolean cancelled;@Override public void onAnimationCancel(android.animation.Animator x){cancelled=true;}@Override public void onAnimationEnd(android.animation.Animator x){if(!cancelled)restore();}});
            anim=va;va.start();
        }
        private void restore(){if(shapes==null)return;for(int i=0;i<shapes.length;i++){if(rest[i]!=null)shapes[i].setCornerRadii(rest[i]);else shapes[i].setCornerRadius(restUniform[i]);}shapes=null;}
        private static void collect(Drawable d,List<GradientDrawable> out){
            if(d instanceof GradientDrawable){GradientDrawable g=(GradientDrawable)d;if(g.getShape()==GradientDrawable.RECTANGLE&&!g.getBounds().isEmpty())out.add(g);}
            else if(d instanceof LayerDrawable){LayerDrawable l=(LayerDrawable)d;for(int i=0;i<l.getNumberOfLayers();i++)collect(l.getDrawable(i),out);}
        }
        private static float[] uniform(float r){return new float[]{r,r,r,r,r,r,r,r};}
        private static float[] effective(GradientDrawable g){float[] r=g.getCornerRadii();return clamp(r!=null?r:uniform(g.getCornerRadius()),g.getBounds());}
        private static float[] clamp(float[] r,Rect b){float max=Math.min(b.width(),b.height())/2f;for(int k=0;k<8;k++)r[k]=Math.min(r[k],max);return r;}
    }
    /**
     * Abre el teclado para este campo apenas su ventana tiene el foco: antes de eso Android ignora el pedido (0.9.2, ver
     * Sheet.keyboard). No toca el texto ni la selección.
     */
    static void showKeyboard(EditText input){
        Runnable show=()->input.post(()->{
            if(!input.isAttachedToWindow()||!input.hasFocus())return;
            if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=input.getWindowInsetsController();if(c!=null)c.show(WindowInsets.Type.ime());}
            android.view.inputmethod.InputMethodManager imm=input.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(imm!=null)imm.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        if(input.hasWindowFocus()){show.run();return;}
        input.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener(){
            @Override public void onWindowFocusChanged(boolean focused){if(!focused)return;input.getViewTreeObserver().removeOnWindowFocusChangeListener(this);show.run();}});
    }
    static void haptic(View v){v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);}
    /**
     * Vibraciones con significado (PROPUESTA #12): cada tipo de momento se siente distinto.
     * CONFIRM: empezar o detener, ★, lista, guardado · REJECT: error · TOGGLE_ON/OFF: interruptores ·
     * TICK: pasar por una marca o un cambio de persona al arrastrar. En Android antiguo se mantiene la vibración de siempre.
     */
    enum Haptic{CONFIRM,REJECT,TICK,TOGGLE_ON,TOGGLE_OFF}
    static int hapticConstant(Haptic h){
        int sdk=Build.VERSION.SDK_INT;
        switch(h){
            case CONFIRM:return sdk>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY;
            case REJECT:return sdk>=30?HapticFeedbackConstants.REJECT:HapticFeedbackConstants.VIRTUAL_KEY;
            case TICK:return sdk>=34?HapticFeedbackConstants.SEGMENT_FREQUENT_TICK:HapticFeedbackConstants.CLOCK_TICK;
            case TOGGLE_ON:return sdk>=34?HapticFeedbackConstants.TOGGLE_ON:HapticFeedbackConstants.VIRTUAL_KEY;
            default:return sdk>=34?HapticFeedbackConstants.TOGGLE_OFF:HapticFeedbackConstants.KEYBOARD_TAP;
        }
    }
    static void haptic(View v,Haptic h){if(v==null||h==null)return;try{v.performHapticFeedback(hapticConstant(h));}catch(RuntimeException ignored){}}
    /** Duración en lenguaje humano, en el idioma de la app: «5 s», «4 min», «52 min», «1 h», «1 h 04 min» (en inglés, «1 hr 4 min»). */
    static String humanDuration(long ms){
        long s=Math.max(0,Math.round(ms/1000.0));if(s<60)return Lang.str(R.string.ui_duration_s,s);
        long m=Math.round(s/60.0);if(m<60)return Lang.str(R.string.ui_duration_min,m);
        long h=m/60,rest=m%60;return rest==0?Lang.str(R.string.ui_duration_h,h):Lang.str(R.string.ui_duration_h_min,h,rest);
    }
    static View.AccessibilityDelegate buttonRole(){return new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}};}
    void fadeIn(View v){
        if(!AppTheme.motion()){v.animate().cancel();v.setAlpha(1f);v.setTranslationY(0f);return;}
        v.setAlpha(0f);v.setTranslationY(dp(8));v.animate().alpha(1f).translationY(0).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_DECELERATE).start();
    }
}
