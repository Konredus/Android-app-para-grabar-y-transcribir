package cl.vozlocal.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

/**
 * Design tokens de Voz local. Toda la UI toma colores, tipografía, espacios y radios de aquí.
 * Documentación y razonamiento: docs/diseno/CRITERIOS.md (sección "Tokens").
 */
final class AppTheme {
    /** Colores semánticos: se nombran por función, nunca por tono ("record", no "rojo"). */
    static final class Palette {
        final boolean dark;
        final int background, surface, elevated, fill, separator;
        final int ink, muted, faint;
        final int accent, accentSoft, primaryFill, onPrimary;
        final int record, recordSoft;
        final int success, successSoft, warning, warningSoft, danger, dangerSoft;
        final int ripple, scrim;
        final int[] speakers;
        Palette(boolean dark){
            this.dark=dark;
            background=dark?0xFF000000:0xFFF2F2F7;
            surface=dark?0xFF1C1C1E:0xFFFFFFFF;
            elevated=dark?0xFF2C2C2E:0xFFFFFFFF;
            fill=dark?0xFF2C2C2E:0xFFE9E9EF;
            separator=dark?0xFF38383A:0xFFE2E2E7;
            ink=dark?0xFFF5F5F7:0xFF1C1C1E;
            muted=dark?0xFFA1A1A6:0xFF6C6C70;
            faint=dark?0xFF636366:0xFFAEAEB2;
            accent=dark?0xFF8EA8FF:0xFF2F5BEA;
            accentSoft=dark?0xFF1B2547:0xFFEAF0FF;
            primaryFill=dark?0xFF3D63F5:0xFF2F5BEA;
            onPrimary=0xFFFFFFFF;
            record=dark?0xFFFF453A:0xFFE5372B;
            recordSoft=dark?0xFF3A1614:0xFFFDECEA;
            success=dark?0xFF32D26A:0xFF1C8A4A;
            successSoft=dark?0xFF0E2C19:0xFFE5F5EB;
            warning=dark?0xFFFFB340:0xFFA55A00;
            warningSoft=dark?0xFF33260D:0xFFFFF2DE;
            danger=dark?0xFFFF6961:0xFFD1242F;
            dangerSoft=dark?0xFF3A1614:0xFFFDECEC;
            ripple=dark?0x33FFFFFF:0x1A000000;
            scrim=0x66000000;
            speakers=dark?new int[]{0xFF8EA8FF,0xFFFF9A62,0xFF4CD4A8,0xFFC79BFF,0xFFFF8AC0,0xFF5FD0E6}
                         :new int[]{0xFF2F5BEA,0xFFC2410C,0xFF0F8A6A,0xFF8B3FD9,0xFFBE185D,0xFF0E7490};
        }
        int speaker(int index){return speakers[Math.abs(index)%speakers.length];}
    }

    /** Escala tipográfica (sp). Inspirada en la escala de iOS: pocos tamaños, bien diferenciados. */
    enum Type {
        LARGE_TITLE(32,Weight.BOLD,-0.02f),
        TITLE(24,Weight.BOLD,-0.01f),
        TITLE_SMALL(20,Weight.MEDIUM,-0.01f),
        HEADLINE(17,Weight.MEDIUM,0f),
        BODY(17,Weight.REGULAR,0f),
        CALLOUT(16,Weight.REGULAR,0f),
        SUBHEAD(15,Weight.REGULAR,0f),
        FOOTNOTE(13,Weight.REGULAR,0f),
        CAPTION(12,Weight.MEDIUM,0.02f),
        SECTION(13,Weight.MEDIUM,0.06f);
        final int size;final Weight weight;final float tracking;
        Type(int size,Weight weight,float tracking){this.size=size;this.weight=weight;this.tracking=tracking;}
    }
    enum Weight{REGULAR,MEDIUM,BOLD,LIGHT}

    /** Espaciado en múltiplos de 4 dp. */
    static final int S1=4,S2=8,S3=12,S4=16,S5=20,S6=24,S8=32,S10=40;
    /** Radios: controles pequeños, tarjetas, hojas. */
    static final int R_SMALL=10,R_CONTROL=14,R_CARD=18,R_SHEET=28;
    /** Duraciones de movimiento (ms). Cortas: la app debe sentirse inmediata. */
    static final int MOTION_FAST=120,MOTION_BASE=220,MOTION_SLOW=360;

    static String appearance(Context c){return new Settings(c).prefs.getString("appearance","system");}
    static boolean isDark(Context c){String mode=appearance(c);return mode.equals("dark") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static Palette apply(Activity activity){Palette p=new Palette(isDark(activity));activity.setTheme(p.dark?R.style.AppThemeDark:R.style.AppTheme);return p;}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void window(Activity activity,Palette p,int navigationColor){
        activity.getWindow().setStatusBarColor(p.background);activity.getWindow().setNavigationBarColor(navigationColor);
        activity.getWindow().getDecorView().setSystemUiVisibility(p.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
    }
    static Typeface typeface(Weight weight){
        switch(weight){
            case BOLD:return Typeface.create("sans-serif",Typeface.BOLD);
            case MEDIUM:return Typeface.create("sans-serif-medium",Typeface.NORMAL);
            case LIGHT:return Typeface.create("sans-serif-light",Typeface.NORMAL);
            default:return Typeface.create("sans-serif",Typeface.NORMAL);
        }
    }
    static void type(TextView t,Type type){
        t.setTextSize(type.size);t.setTypeface(typeface(type.weight));t.setLetterSpacing(type.tracking);
        float extra=type.size>=20?0:type.size*0.3f;t.setLineSpacing(dp(t.getContext(),extra/2f),1f);
    }
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static GradientDrawable outline(Context c,int fill,int stroke,int radius,boolean dashed){GradientDrawable d=shape(c,fill,radius);if(dashed)d.setStroke(dp(c,1.5f),stroke,dp(c,6),dp(c,5));else d.setStroke(dp(c,1),stroke);return d;}
    static GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}
    private AppTheme(){}
}
