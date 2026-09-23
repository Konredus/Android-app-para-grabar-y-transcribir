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
 * Kit de componentes nativos (sin dependencias). Cada pantalla construye su UI solo con estas piezas
 * para que la app se vea y se comporte igual en todas partes. Ver docs/diseno/CRITERIOS.md → "Componentes".
 */
final class Ui {
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
    TextView heading(String value,Type type){TextView t=text(value,type,p.ink);if(android.os.Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    TextView section(String value){TextView t=text(value.toUpperCase(new java.util.Locale("es","CL")),Type.SECTION,p.muted);t.setPadding(dp(S4),dp(S6),dp(S4),dp(S2));if(android.os.Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);return t;}
    TextView footnote(String value){TextView t=text(value,Type.FOOTNOTE,p.muted);t.setPadding(dp(S4),dp(S2),dp(S4),0);return t;}
    TextView oneLine(TextView t){t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}

    // ---------- Superficies ----------
    LinearLayout card(){LinearLayout l=column();l.setBackground(shape(c,p.surface,R_CARD));l.setPadding(dp(S4),dp(S4),dp(S4),dp(S4));return l;}
    /** Grupo estilo "lista agrupada": tarjeta sin padding cuyas filas se separan con líneas finas. */
    LinearLayout group(){LinearLayout l=column();l.setBackground(shape(c,p.surface,R_CARD));l.setClipToOutline(true);return l;}
    void addRow(LinearLayout group,View row){if(group.getChildCount()>0)group.addView(separator(S4+30+S3));group.addView(row,fill());}
    View separator(int insetDp){View v=new View(c);v.setBackgroundColor(p.separator);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(0.5f)));lp.setMarginStart(dp(insetDp));v.setLayoutParams(lp);return v;}
    Drawable ripple(Drawable content,int radius){return new RippleDrawable(ColorStateList.valueOf(p.ripple),content,content==null?shape(c,0xFF000000,radius):null);}

    // ---------- Iconos ----------
    ImageView icon(int res,int color,int sizeDp){ImageView i=new ImageView(c);i.setImageResource(res);i.setImageTintList(ColorStateList.valueOf(color));i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));return i;}
    /** Ícono dentro de un cuadrado redondeado de color (filas de ajustes y biblioteca). */
    FrameLayout tile(int res,int fg,int bg,int sizeDp,int iconDp){FrameLayout f=new FrameLayout(c);f.setBackground(shape(c,bg,Math.round(sizeDp*0.28f)));ImageView i=icon(res,fg,iconDp);f.addView(i,new FrameLayout.LayoutParams(dp(iconDp),dp(iconDp),Gravity.CENTER));f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return f;}
    /** Botón circular solo con ícono. Área táctil mínima 48 dp. */
    ImageButton iconButton(int res,String description,int tint,int bg,int sizeDp){
        ImageButton b=new ImageButton(c){@Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",description);return super.performClick();}};
        b.setImageResource(res);b.setImageTintList(ColorStateList.valueOf(tint));b.setContentDescription(description);b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable shape=oval(bg==0?0x00000000:bg);b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),shape,oval(0xFF000000)));
        int size=Math.max(48,sizeDp);b.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));pressable(b);return b;
    }

    // ---------- Botones ----------
    /** Botón con etiqueta y, opcionalmente, ícono. Es un LinearLayout para centrar ícono+texto, anunciado como Button. */
    final class Btn extends LinearLayout {
        final TextView label;final ImageView glyph;final Style style;
        Btn(String text,int iconRes,Style style){
            super(c);this.style=style;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setClickable(true);setFocusable(true);
            int fg,bg;switch(style){
                case PRIMARY:fg=p.onPrimary;bg=p.primaryFill;break;
                case RECORD:fg=0xFFFFFFFF;bg=p.record;break;
                case TONAL:fg=p.accent;bg=p.accentSoft;break;
                case DESTRUCTIVE:fg=p.danger;bg=p.dangerSoft;break;
                case PLAIN:fg=p.accent;bg=0;break;
                default:fg=p.ink;bg=p.fill;
            }
            setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),bg==0?null:shape(c,bg,R_CONTROL),shape(c,0xFF000000,R_CONTROL)));
            int h=style==Style.PLAIN?44:52;setMinimumHeight(dp(h));setPadding(dp(S5),dp(S2),dp(S5),dp(S2));
            glyph=iconRes==0?null:icon(iconRes,fg,20);if(glyph!=null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(20),dp(20));lp.setMarginEnd(dp(S2));addView(glyph,lp);}
            label=text(text,Type.HEADLINE,fg);label.setGravity(Gravity.CENTER);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addView(label,wrap());
            setContentDescription(text);pressable(this);
        }
        void setText(String value){label.setText(value);setContentDescription(value);}
        void setIcon(int res){if(glyph!=null)glyph.setImageResource(res);}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.4f);}
        @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label.getText().toString());return super.performClick();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    }
    Btn button(String text,int icon,Style style,View.OnClickListener click){Btn b=new Btn(text,icon,style);if(click!=null)b.setOnClickListener(click);return b;}

    /** Botón vertical ícono + etiqueta corta (barras de acciones). */
    LinearLayout action(int res,String label,View.OnClickListener click){
        LinearLayout l=column();l.setGravity(Gravity.CENTER);l.setPadding(dp(S1),dp(S2),dp(S1),dp(S2));l.setMinimumHeight(dp(56));l.setClickable(true);l.setFocusable(true);l.setContentDescription(label);
        l.setBackground(ripple(null,R_CONTROL));l.addView(icon(res,p.accent,22));TextView t=text(label,Type.CAPTION,p.accent);t.setPadding(0,dp(S1),0,0);t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);l.addView(t);
        l.setAccessibilityDelegate(buttonRole());l.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",label);click.onClick(v);});pressable(l);return l;
    }

    // ---------- Chips y estados ----------
    TextView chip(String value,int fg,int bg){TextView t=text(value,Type.CAPTION,fg);t.setBackground(shape(c,bg,99));t.setPadding(dp(10),dp(4),dp(10),dp(4));t.setGravity(Gravity.CENTER);oneLine(t);return t;}
    /** Chip seleccionable para filtros. */
    TextView filter(String value,boolean selected,View.OnClickListener click){
        TextView t=text(value,Type.SUBHEAD,selected?p.onPrimary:p.ink);t.setTypeface(typeface(Weight.MEDIUM));t.setGravity(Gravity.CENTER);t.setPadding(dp(14),0,dp(14),0);t.setMinHeight(dp(36));t.setMinimumHeight(dp(36));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),shape(c,selected?p.primaryFill:p.surface,99),null));t.setSelected(selected);t.setOnClickListener(click);t.setAccessibilityDelegate(buttonRole());
        t.setContentDescription(value+(selected?", seleccionado":""));return t;
    }

    // ---------- Filas ----------
    /** Fila de lista: [ícono] título / subtítulo ........ valor [>] */
    final class Row extends LinearLayout {
        final TextView title,subtitle,value;
        Row(int iconRes,int iconColor,String titleText,String subtitleText,String valueText,boolean chevron){
            super(c);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(56));setPadding(dp(S4),dp(S3),dp(S4),dp(S3));
            if(iconRes!=0){View t=tile(iconRes,0xFFFFFFFF,iconColor,30,18);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(30),dp(30));lp.setMarginEnd(dp(S3));addView(t,lp);}
            LinearLayout texts=column();title=text(titleText,Type.BODY,p.ink);texts.addView(title);
            subtitle=text(subtitleText==null?"":subtitleText,Type.FOOTNOTE,p.muted);subtitle.setVisibility(subtitleText==null||subtitleText.isEmpty()?GONE:VISIBLE);subtitle.setPadding(0,dp(2),0,0);texts.addView(subtitle);
            addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            value=text(valueText==null?"":valueText,Type.CALLOUT,p.muted);value.setMaxWidth(dp(170));value.setGravity(Gravity.END);oneLine(value);value.setPadding(dp(S2),0,0,0);if(valueText!=null&&!valueText.isEmpty())addView(value,wrap());
            if(chevron){ImageView i=icon(R.drawable.ic_chevron_right,p.faint,20);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(20),dp(20));lp.setMarginStart(dp(S1));addView(i,lp);}
        }
        Row onClick(View.OnClickListener click){setBackground(ripple(null,0));setClickable(true);setFocusable(true);setAccessibilityDelegate(buttonRole());setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",c.getClass().getSimpleName(),"action",title.getText().toString());click.onClick(v);});return this;}
        void setValue(String v){value.setText(v);if(value.getParent()==null&&!v.isEmpty())addView(value,getChildCount()-(getChildCount()>2?1:0),wrap());}
        void setSubtitle(String v){subtitle.setText(v);subtitle.setVisibility(v.isEmpty()?GONE:VISIBLE);}
    }
    Row listRow(int icon,int iconColor,String title,String subtitle,String value,boolean chevron){return new Row(icon,iconColor,title,subtitle,value,chevron);}

    interface Toggle{void changed(boolean on);}
    /** Fila con interruptor: toda la fila es el área táctil. */
    LinearLayout switchRow(int iconRes,int iconColor,String title,String subtitle,boolean initial,Toggle toggle){
        Row row=new Row(iconRes,iconColor,title,subtitle,null,false);
        Switch s=new Switch(c);s.setChecked(initial);s.setContentDescription(title);s.setShowText(false);
        int[][] states={{android.R.attr.state_checked},{}};
        s.setThumbTintList(new ColorStateList(states,new int[]{0xFFFFFFFF,p.dark?0xFFCFCFD4:0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states,new int[]{p.success,p.dark?0xFF48484A:0xFFD1D1D6}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        row.addView(s,wrap());row.setBackground(ripple(null,0));row.setOnClickListener(v->s.toggle());
        s.setOnCheckedChangeListener((b,on)->toggle.changed(on));return row;
    }

    // ---------- Campos ----------
    EditText field(String hint,String description){
        EditText e=new EditText(c);e.setHint(hint);e.setContentDescription(description);e.setSingleLine(true);e.setTextColor(p.ink);e.setHintTextColor(p.faint);AppTheme.type(e,Type.BODY);
        e.setBackground(shape(c,p.fill,R_CONTROL));e.setPadding(dp(S4),dp(S3),dp(S4),dp(S3));e.setMinHeight(dp(52));e.setMinimumHeight(dp(52));return e;
    }
    /** Etiqueta + campo apilados. */
    EditText labeled(LinearLayout parent,String label,String hint){TextView t=text(label,Type.FOOTNOTE,p.muted);t.setPadding(dp(S1),dp(S3),0,dp(6));parent.addView(t);EditText e=field(hint,label);parent.addView(e,fill());return e;}

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
