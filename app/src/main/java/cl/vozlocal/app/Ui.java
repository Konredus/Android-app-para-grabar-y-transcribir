package cl.vozlocal.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Kit de componentes Material 3 construido de forma nativa (sin la librería de Material).
 * Cada pantalla arma su UI solo con estas piezas, así toda la app sigue la misma línea.
 * Ver docs/diseno/CRITERIOS.md → "Componentes".
 */
final class Ui {
    /** Estilos de botón de Material 3: Filled, Tonal, Outlined, Text, y Error (acciones destructivas). */
    enum Style{PRIMARY,TONAL,SECONDARY,PLAIN,DESTRUCTIVE,RECORD}

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
    TextView heading(String value,Type type){TextView t=text(value,type,p.onSurface);if(android.os.Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    /** Subtítulo de lista (como en Ajustes de Android): color primary, Title Small, sin mayúsculas forzadas. */
    TextView section(String value){TextView t=text(value,Type.TITLE_SMALL,p.primary);t.setPadding(dp(S4),dp(S6),dp(S4),dp(S2));if(android.os.Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    TextView footnote(String value){TextView t=text(value,Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(dp(S4),dp(S2),dp(S4),0);return t;}
    TextView oneLine(TextView t){t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}

    // ---------- Superficies ----------
    LinearLayout card(){LinearLayout l=column();l.setBackground(shape(c,p.card,R_CARD));l.setPadding(dp(S4),dp(S4),dp(S4),dp(S4));return l;}
    /** Grupo de lista: tarjeta cuyas filas se separan con divisores finos (outlineVariant). */
    LinearLayout group(){LinearLayout l=column();l.setBackground(shape(c,p.card,R_CARD));l.setClipToOutline(true);return l;}
    void addRow(LinearLayout group,View row){if(group.getChildCount()>0)group.addView(separator(S4+24+S4));group.addView(row,fill());}
    View separator(int insetDp){View v=new View(c);v.setBackgroundColor(p.outlineVariant);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(0.5f)));lp.setMarginStart(dp(insetDp));v.setLayoutParams(lp);return v;}
    Drawable ripple(Drawable content,int radius){return new RippleDrawable(ColorStateList.valueOf(p.ripple),content,content==null?shape(c,0xFF000000,radius):null);}

    // ---------- Iconos ----------
    ImageView icon(int res,int color,int sizeDp){ImageView i=new ImageView(c);i.setImageResource(res);i.setImageTintList(ColorStateList.valueOf(color));i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));return i;}
    /** Ícono dentro de un círculo tonal (avatar de Material). Siempre en la misma familia de color. */
    FrameLayout tile(int res,int fg,int bg,int sizeDp,int iconDp){FrameLayout f=new FrameLayout(c);f.setBackground(oval(bg));ImageView i=icon(res,fg,iconDp);f.addView(i,new FrameLayout.LayoutParams(dp(iconDp),dp(iconDp),Gravity.CENTER));f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return f;}
    /** Botón de ícono (Material "icon button"). Área táctil mínima 48 dp. */
    ImageButton iconButton(int res,String description,int tint,int bg,int sizeDp){
        ImageButton b=new ImageButton(c){@Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",description);return super.performClick();}};
        b.setImageResource(res);b.setImageTintList(ColorStateList.valueOf(tint));b.setContentDescription(description);b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable shape=oval(bg==0?0x00000000:bg);b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),shape,oval(0xFF000000)));
        int size=Math.max(48,sizeDp);b.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));pressable(b);return b;
    }

    // ---------- Botones ----------
    /** Botón Material 3 (forma de píldora). Es un LinearLayout para centrar ícono+texto; se anuncia como Button. */
    final class Btn extends LinearLayout {
        final TextView label;final ImageView glyph;final Style style;
        Btn(String text,int iconRes,Style style){
            super(c);this.style=style;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setClickable(true);setFocusable(true);
            int fg,bg=0;GradientDrawable shape;
            switch(style){
                case PRIMARY:fg=p.onPrimary;bg=p.primary;break;
                case RECORD:fg=0xFFFFFFFF;bg=p.record;break;
                case TONAL:fg=p.onSecondaryContainer;bg=p.secondaryContainer;break;
                case DESTRUCTIVE:fg=p.onError;bg=p.error;break;
                case PLAIN:fg=p.primary;break;
                default:fg=p.primary;
            }
            if(style==Style.SECONDARY)shape=outline(c,0x00000000,p.outline,R_FULL,false);else shape=bg==0?null:shape(c,bg,R_FULL);
            setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),shape,shape(c,0xFF000000,R_FULL)));
            int h=style==Style.PLAIN?44:52;setMinimumHeight(dp(h));setPadding(dp(style==Style.PLAIN?S3:S6),dp(S2),dp(style==Style.PLAIN?S3:S6),dp(S2));
            glyph=iconRes==0?null:icon(iconRes,fg,18);if(glyph!=null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(18),dp(18));lp.setMarginEnd(dp(S2));addView(glyph,lp);}
            label=text(text,Type.LABEL_LARGE,fg);label.setTextSize(15);label.setGravity(Gravity.CENTER);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(label,wrap());
            setContentDescription(text);pressable(this);
        }
        void setText(String value){label.setText(value);setContentDescription(value);}
        void setIcon(int res){if(glyph!=null)glyph.setImageResource(res);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.38f);}
        @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label.getText().toString());return super.performClick();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    }
    Btn button(String text,int icon,Style style,View.OnClickListener click){Btn b=new Btn(text,icon,style);if(click!=null)b.setOnClickListener(click);return b;}

    /** Acción vertical ícono + etiqueta (barra inferior de acciones). Neutra: onSurfaceVariant. */
    LinearLayout action(int res,String label,View.OnClickListener click){
        LinearLayout l=column();l.setGravity(Gravity.CENTER);l.setPadding(dp(S1),dp(S2),dp(S1),dp(S2));l.setMinimumHeight(dp(56));l.setClickable(true);l.setFocusable(true);l.setContentDescription(label);
        l.setBackground(ripple(null,R_CONTROL));l.addView(icon(res,p.onSurfaceVariant,24));TextView t=text(label,Type.LABEL_MEDIUM,p.onSurfaceVariant);t.setPadding(0,dp(S1),0,0);t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);l.addView(t);
        l.setAccessibilityDelegate(buttonRole());l.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label);click.onClick(v);});pressable(l);return l;
    }

    // ---------- Chips ----------
    /** Chip de estado/asistencia (Material: esquinas de 8 dp, 32 dp de alto). */
    TextView chip(String value,int fg,int bg){TextView t=text(value,Type.LABEL_LARGE,fg);t.setBackground(shape(c,bg,R_SMALL));t.setPadding(dp(S3),dp(6),dp(S3),dp(6));t.setGravity(Gravity.CENTER);oneLine(t);return t;}
    /** Chip con borde (sin relleno) e ícono inicial opcional: estado neutro o acción secundaria. */
    TextView outlinedChip(String value,int icon,int iconColor){
        TextView t=text(value,Type.LABEL_LARGE,p.onSurfaceVariant);t.setBackground(ripple(outline(c,0x00000000,p.outline,R_SMALL,false),R_SMALL));t.setPadding(dp(S2),dp(6),dp(S3),dp(6));t.setGravity(Gravity.CENTER_VERTICAL);t.setMinHeight(dp(32));oneLine(t);
        if(icon!=0){Drawable d=c.getDrawable(icon).mutate();d.setTint(iconColor);d.setBounds(0,0,dp(18),dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(dp(S2));}
        return t;
    }
    /** Chip de filtro de Material 3: borde si no está elegido; relleno secondaryContainer con ✓ si está elegido. */
    TextView filter(String value,boolean selected,View.OnClickListener click){
        TextView t=text(value,Type.LABEL_LARGE,selected?p.onSecondaryContainer:p.onSurfaceVariant);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(selected?S2:S4),0,dp(S4),0);t.setMinHeight(dp(32));t.setMinimumHeight(dp(32));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),selected?shape(c,p.secondaryContainer,R_SMALL):outline(c,0x00000000,p.outline,R_SMALL,false),null));
        if(selected){Drawable d=c.getDrawable(R.drawable.ic_check).mutate();d.setTint(p.onSecondaryContainer);d.setBounds(0,0,dp(18),dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(dp(S2));}
        t.setSelected(selected);t.setOnClickListener(click);t.setAccessibilityDelegate(buttonRole());t.setContentDescription(value+(selected?", seleccionado":""));return t;
    }

    // ---------- Filas de lista ----------
    /** Elemento de lista de Material 3: [ícono 24] título / texto de apoyo ........ valor */
    final class Row extends LinearLayout {
        final TextView title,subtitle,value;
        Row(int iconRes,String titleText,String subtitleText,String valueText){
            super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(56));setPadding(dp(S4),dp(S3),dp(S4),dp(S3));
            if(iconRes!=0){ImageView i=icon(iconRes,p.onSurfaceVariant,24);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(24),dp(24));lp.setMarginEnd(dp(S4));addView(i,lp);}
            LinearLayout texts=column();title=text(titleText,Type.BODY_LARGE,p.onSurface);texts.addView(title);
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
    /** Fila con interruptor (Material switch): toda la fila es el área táctil. */
    LinearLayout switchRow(int iconRes,String title,String subtitle,boolean initial,Toggle toggle){
        Row row=new Row(iconRes,title,subtitle,null);
        Switch s=new Switch(c);s.setChecked(initial);s.setContentDescription(title);s.setShowText(false);
        int[][] states={{android.R.attr.state_checked},{}};
        s.setThumbTintList(new ColorStateList(states,new int[]{p.onPrimary,p.outline}));
        s.setTrackTintList(new ColorStateList(states,new int[]{p.primary,p.surfaceContainerHighest}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        row.addView(s,wrap());row.setBackground(ripple(null,0));row.setOnClickListener(v->s.toggle());
        s.setOnCheckedChangeListener((b,on)->toggle.changed(on));return row;
    }

    // ---------- Campos ----------
    /** Campo relleno (Material "filled text field" simplificado). */
    EditText field(String hint,String description){
        EditText e=new EditText(c);e.setHint(hint);e.setContentDescription(description);e.setSingleLine(true);e.setTextColor(p.onSurface);e.setHintTextColor(p.onSurfaceVariant);AppTheme.type(e,Type.BODY_LARGE);
        e.setBackground(shape(c,p.surfaceContainerHighest,R_CONTROL));e.setPadding(dp(S4),dp(S3),dp(S4),dp(S3));e.setMinHeight(dp(56));e.setMinimumHeight(dp(56));return e;
    }
    /** Etiqueta + campo apilados. */
    EditText labeled(LinearLayout parent,String label,String hint){TextView t=text(label,Type.BODY_SMALL,p.onSurfaceVariant);t.setPadding(dp(S1),dp(S3),0,dp(6));parent.addView(t);EditText e=field(hint,label);parent.addView(e,fill());return e;}

    // ---------- Interacción ----------
    /** Retroalimentación táctil: leve escala al presionar (movimiento corto, ver MOTION_FAST). */
    static void pressable(View v){v.setOnTouchListener((view,event)->{
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&view.isEnabled())view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(MOTION_FAST).start();
        else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)view.animate().scaleX(1f).scaleY(1f).setDuration(MOTION_BASE).start();
        return false;});}
    static void haptic(View v){v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);}
    static View.AccessibilityDelegate buttonRole(){return new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}};}
    void fadeIn(View v){v.setAlpha(0f);v.setTranslationY(dp(8));v.animate().alpha(1f).translationY(0).setDuration(MOTION_BASE).start();}
}
