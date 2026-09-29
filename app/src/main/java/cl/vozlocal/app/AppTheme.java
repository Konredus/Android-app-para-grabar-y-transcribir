package cl.vozlocal.app;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
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
        /** Roles invertidos (snackbar con "Deshacer"): superficie oscura en tema claro y viceversa. */
        final int inverseSurface,inverseOnSurface,inversePrimary;
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
            inverseSurface=onSurface;inverseOnSurface=surfaceContainerLow;inversePrimary=this.dynamic?sys(c,dark?"system_accent1_600":"system_accent1_200"):(dark?0xFF3A5BC7:0xFFB5C4FF);
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

    /*
     * Curvas de movimiento de Material 3 Expressive (ver PROPUESTA #12):
     * - Color, transparencia y forma usan las curvas "emphasized", sin rebote.
     * - Lo que cambia de lugar o de tamaño usa un resorte con un rebote leve (SPATIAL).
     * Todo respeta «Quitar animaciones» de Android: con la escala de animación en 0, motion() es false
     * y los animadores terminan de inmediato.
     */
    /** Emphasized (estándar para transformaciones completas). */
    static final TimeInterpolator EMPHASIZED=new PathInterpolator(0.2f,0f,0f,1f);
    /** Emphasized decelerate: para lo que entra a la pantalla. */
    static final TimeInterpolator EMPHASIZED_DECELERATE=new PathInterpolator(0.05f,0.7f,0.1f,1f);
    /** Emphasized accelerate: para lo que sale de la pantalla. */
    static final TimeInterpolator EMPHASIZED_ACCELERATE=new PathInterpolator(0.3f,0f,0.8f,0.15f);
    /** Resortes espaciales de Material 3 Expressive (amortiguación, rigidez): rápido, normal y lento. */
    static final Spring SPATIAL_FAST=new Spring(0.6f,800f),SPATIAL=new Spring(0.8f,380f),SPATIAL_SLOW=new Spring(0.8f,200f);

    /**
     * Resorte amortiguado como interpolador (masa 1). Su duración es el tiempo que tarda en asentarse
     * (±0,1 %): se usa como `animate().setDuration(s.duration).setInterpolator(s)`.
     * Con amortiguación &lt; 1 pasa un poco de largo y vuelve: 0,8 ≈ 1,5 % de rebote, 0,6 ≈ 9 %.
     */
    static final class Spring implements Interpolator {
        final float damping,stiffness;final long duration;
        Spring(float damping,float stiffness){
            this.damping=damping;this.stiffness=stiffness;double w=Math.sqrt(stiffness);
            double envelope=damping<1f?1.0/Math.sqrt(1-damping*damping):1.0;
            duration=Math.max(150,Math.min(1000,Math.round(1000*Math.log(1000*envelope)/(damping*w))));
        }
        @Override public float getInterpolation(float input){
            if(input<=0f)return 0f;if(input>=1f)return 1f;
            double t=input*duration/1000.0,w=Math.sqrt(stiffness),z=damping;
            if(z<1){double wd=w*Math.sqrt(1-z*z);return (float)(1-Math.exp(-z*w*t)*(Math.cos(wd*t)+z*w/wd*Math.sin(wd*t)));}
            return (float)(1-Math.exp(-w*t)*(1+w*t));
        }
    }
    /** false si el usuario activó «Quitar animaciones» (o la escala de animación está en 0). */
    static boolean motion(){return ValueAnimator.areAnimatorsEnabled();}
    static boolean motion(Context c){
        try{if(android.provider.Settings.Global.getFloat(c.getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f)return false;}catch(RuntimeException ignored){}
        return motion();
    }

    static String appearance(Context c){return new Settings(c).prefs.getString("appearance","system");}
    static boolean dynamicColor(Context c){return Build.VERSION.SDK_INT>=31&&new Settings(c).prefs.getBoolean("dynamicColor",false);}
    static boolean isDark(Context c){String mode=appearance(c);return mode.equals("dark") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static Palette apply(Activity activity){Palette p=new Palette(activity,isDark(activity),dynamicColor(activity));activity.setTheme(p.dark?R.style.AppThemeDark:R.style.AppTheme);return p;}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    /**
     * Barras del sistema del mismo color que la pantalla: la de estado como el fondo y la de navegación como la
     * zona que queda encima (barra de pestañas o fondo). Sin divisor ni velo de contraste, así en modo oscuro no se ve una franja.
     * En Android 15+ (de borde a borde) el color lo pinta Screen detrás de la barra.
     */
    static void window(Activity activity,Palette p,int navigationColor){
        Window w=activity.getWindow();
        w.setStatusBarColor(p.background);w.setNavigationBarColor(navigationColor);w.getDecorView().setBackgroundColor(p.background);
        if(Build.VERSION.SDK_INT>=28)w.setNavigationBarDividerColor(navigationColor);
        if(Build.VERSION.SDK_INT>=29){w.setNavigationBarContrastEnforced(false);w.setStatusBarContrastEnforced(false);}
        boolean lightNav=!p.dark&&luminance(navigationColor)>0.5f;
        w.getDecorView().setSystemUiVisibility((p.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)|(lightNav&&Build.VERSION.SDK_INT>=27?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=w.getInsetsController();
            if(controller!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance((p.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)|(lightNav?WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0),mask);}
        }
    }
    /** Mezcla lineal de dos colores ARGB (t = 0 → a, t = 1 → b). */
    static int blend(int a,int b,float t){
        t=Math.max(0f,Math.min(1f,t));float u=1f-t;
        return (Math.round(((a>>>24)&0xFF)*u+((b>>>24)&0xFF)*t)<<24)|(Math.round(((a>>16)&0xFF)*u+((b>>16)&0xFF)*t)<<16)|(Math.round(((a>>8)&0xFF)*u+((b>>8)&0xFF)*t)<<8)|Math.round((a&0xFF)*u+(b&0xFF)*t);
    }
    /** El mismo color con otra opacidad (0–255). */
    static int withAlpha(int color,int alpha){return (color&0x00FFFFFF)|((alpha&0xFF)<<24);}
    /** Luminancia relativa aproximada (0 = negro, 1 = blanco). */
    static float luminance(int color){return (0.2126f*((color>>16)&0xFF)+0.7152f*((color>>8)&0xFF)+0.0722f*(color&0xFF))/255f;}
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
