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
 * Design tokens de Voz local según Material 3 (m3.material.io), implementados sin librerías.
 * Los nombres de color son los "roles" de Material: primary, primaryContainer, surfaceContainer…
 * Documentación y razonamiento: docs/diseno/CRITERIOS.md (sección "Tokens").
 */
final class AppTheme {
    /**
     * Esquema de color Material 3. Un solo color semilla (azul) genera todos los roles;
     * los neutros están levemente tintados de ese azul para que todo se sienta de la misma familia.
     * El rojo solo aparece como "error" y como "record" (grabar).
     */
    static final class Palette {
        final boolean dark,dynamic;
        // Roles de Material 3
        final int primary,onPrimary,primaryContainer,onPrimaryContainer;
        final int secondaryContainer,onSecondaryContainer;
        final int surface,surfaceContainerLowest,surfaceContainerLow,surfaceContainer,surfaceContainerHigh,surfaceContainerHighest;
        final int onSurface,onSurfaceVariant,outline,outlineVariant;
        final int error,onError,errorContainer,onErrorContainer;
        // Roles de la app, derivados de los anteriores
        final int background,card,record,ripple;
        final int[] speakers;

        Palette(Context c,boolean dark,boolean dynamic){
            this.dark=dark;this.dynamic=dynamic&&Build.VERSION.SDK_INT>=31;
            if(this.dynamic){
                // Material You: los roles salen de la paleta que Android genera desde el fondo de pantalla.
                primary=sys(c,dark?"system_accent1_200":"system_accent1_600");onPrimary=sys(c,dark?"system_accent1_800":"system_accent1_0");
                primaryContainer=sys(c,dark?"system_accent1_700":"system_accent1_100");onPrimaryContainer=sys(c,dark?"system_accent1_100":"system_accent1_900");
                secondaryContainer=sys(c,dark?"system_accent2_700":"system_accent2_100");onSecondaryContainer=sys(c,dark?"system_accent2_100":"system_accent2_900");
                surface=sys(c,dark?"system_neutral1_900":"system_neutral1_10");
                surfaceContainerLowest=sys(c,dark?"system_neutral1_1000":"system_neutral1_0");
                surfaceContainerLow=sys(c,dark?"system_neutral1_900":"system_neutral1_10");
                surfaceContainer=sys(c,dark?"system_neutral1_800":"system_neutral1_50");
                surfaceContainerHigh=sys(c,dark?"system_neutral1_800":"system_neutral1_100");
                surfaceContainerHighest=sys(c,dark?"system_neutral1_700":"system_neutral1_100");
                onSurface=sys(c,dark?"system_neutral1_100":"system_neutral1_900");onSurfaceVariant=sys(c,dark?"system_neutral2_200":"system_neutral2_700");
                outline=sys(c,dark?"system_neutral2_400":"system_neutral2_500");outlineVariant=sys(c,dark?"system_neutral2_700":"system_neutral2_200");
            }else if(dark){
                primary=0xFFB5C4FF;onPrimary=0xFF0E2A78;primaryContainer=0xFF2A4190;onPrimaryContainer=0xFFDCE1FF;
                secondaryContainer=0xFF3F4759;onSecondaryContainer=0xFFDDE1F9;
                surface=0xFF121318;surfaceContainerLowest=0xFF0D0E13;surfaceContainerLow=0xFF1B1B21;surfaceContainer=0xFF1F1F25;surfaceContainerHigh=0xFF292A2F;surfaceContainerHighest=0xFF34343A;
                onSurface=0xFFE4E1E9;onSurfaceVariant=0xFFC6C5D0;outline=0xFF90909A;outlineVariant=0xFF45464F;
            }else{
                primary=0xFF3A5BC7;onPrimary=0xFFFFFFFF;primaryContainer=0xFFDCE1FF;onPrimaryContainer=0xFF00164E;
                secondaryContainer=0xFFDDE1F9;onSecondaryContainer=0xFF151B2C;
                surface=0xFFFBF8FF;surfaceContainerLowest=0xFFFFFFFF;surfaceContainerLow=0xFFF5F2FA;surfaceContainer=0xFFEFEDF4;surfaceContainerHigh=0xFFE9E7EF;surfaceContainerHighest=0xFFE3E1E9;
                onSurface=0xFF1B1B21;onSurfaceVariant=0xFF45464F;outline=0xFF767680;outlineVariant=0xFFC6C5D0;
            }
            error=dark?0xFFFFB4AB:0xFFBA1A1A;onError=dark?0xFF690005:0xFFFFFFFF;errorContainer=dark?0xFF93000A:0xFFFFDAD6;onErrorContainer=dark?0xFFFFDAD6:0xFF410002;
            // Fondo de pantalla un tono más oscuro que las tarjetas, como en los Ajustes de Android.
            background=dark?surface:surfaceContainer;card=dark?surfaceContainer:surfaceContainerLowest;
            record=dark?0xFFFF5449:0xFFDE3730;
            ripple=(onSurface&0x00FFFFFF)|0x1F000000;
            // Hablantes: tonos 40/80 de Material para 6 matices, armonizados (misma luminosidad); el primero es primary.
            speakers=dark?new int[]{primary,0xFFFFB870,0xFF53DBC9,0xFFE5B6FF,0xFFFFB3B3,0xFFB6D26A}
                         :new int[]{primary,0xFF8B5000,0xFF006A60,0xFF7B4E9E,0xFF9C4146,0xFF4F6300};
        }
        int speaker(int index){return speakers[Math.abs(index)%speakers.length];}
        private static int sys(Context c,String name){int id=c.getResources().getIdentifier(name,"color","android");return id==0?0xFF808080:c.getColor(id);}
    }

    /** Escala tipográfica de Material 3 (sp). Roboto del sistema. El tracking del cuerpo se redujo un poco respecto de la spec para textos en español, que son más largos. */
    enum Type {
        HEADLINE_LARGE(32,Weight.REGULAR,0f),
        HEADLINE_MEDIUM(28,Weight.REGULAR,0f),
        HEADLINE_SMALL(24,Weight.REGULAR,0f),
        TITLE_LARGE(22,Weight.REGULAR,0f),
        TITLE_MEDIUM(16,Weight.MEDIUM,0.009f),
        TITLE_SMALL(14,Weight.MEDIUM,0.007f),
        BODY_LARGE(16,Weight.REGULAR,0.01f),
        BODY_MEDIUM(14,Weight.REGULAR,0.01f),
        BODY_SMALL(12,Weight.REGULAR,0.02f),
        LABEL_LARGE(14,Weight.MEDIUM,0.007f),
        LABEL_MEDIUM(12,Weight.MEDIUM,0.02f),
        LABEL_SMALL(11,Weight.MEDIUM,0.045f);
        final int size;final Weight weight;final float tracking;
        Type(int size,Weight weight,float tracking){this.size=size;this.weight=weight;this.tracking=tracking;}
    }
    enum Weight{REGULAR,MEDIUM,BOLD,LIGHT}

    /** Espaciado en múltiplos de 4 dp (Material usa una grilla de 4/8 dp). */
    static final int S1=4,S2=8,S3=12,S4=16,S5=20,S6=24,S8=32,S10=40;
    /** Formas de Material 3: extra-small 4 · small 8 · medium 12 · large 16 · extra-large 28 · full (99). */
    static final int R_SMALL=8,R_CONTROL=12,R_CARD=16,R_SHEET=28,R_FULL=99;
    /** Movimiento (ms): corto para respuestas, medio para aparecer, largo para transformaciones. */
    static final int MOTION_FAST=100,MOTION_BASE=250,MOTION_SLOW=400;

    static String appearance(Context c){return new Settings(c).prefs.getString("appearance","system");}
    static boolean dynamicColor(Context c){return Build.VERSION.SDK_INT>=31&&new Settings(c).prefs.getBoolean("dynamicColor",false);}
    static boolean isDark(Context c){String mode=appearance(c);return mode.equals("dark") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static Palette apply(Activity activity){Palette p=new Palette(activity,isDark(activity),dynamicColor(activity));activity.setTheme(p.dark?R.style.AppThemeDark:R.style.AppTheme);return p;}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void window(Activity activity,Palette p,int navigationColor){
        activity.getWindow().setStatusBarColor(p.background);activity.getWindow().setNavigationBarColor(navigationColor);activity.getWindow().getDecorView().setBackgroundColor(p.background);
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
        float extra=type.size>=22?type.size*0.15f:type.size*0.4f;t.setLineSpacing(dp(t.getContext(),extra/2f),1f);
    }
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static GradientDrawable outline(Context c,int fill,int stroke,int radius,boolean dashed){GradientDrawable d=shape(c,fill,radius);if(dashed)d.setStroke(dp(c,1.5f),stroke,dp(c,6),dp(c,5));else d.setStroke(dp(c,1),stroke);return d;}
    static GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}
    private AppTheme(){}
}
