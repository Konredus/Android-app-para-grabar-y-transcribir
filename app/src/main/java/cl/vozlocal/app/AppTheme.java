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
 * Design tokens de Verbapp (0.7.0, «Bosque de vidrio»), implementados sin librerías.
 * Los nombres de color siguen los "roles" de Material 3 (primary, primaryContainer, surfaceContainer…) para que todo el
 * código existente siga funcionando; encima se agregan los roles propios de la estética: tinta (botón principal negro),
 * verde de marca, vidrio (tarjetas translúcidas) y el degradado blanco → verde del fondo.
 * Documentación y razonamiento: docs/diseno/CRITERIOS.md (sección "Tokens") y docs/diseno/PROPUESTA-0.7.md.
 */
final class AppTheme {
    /**
     * Esquema de color. Un solo verde de marca (#2F6B58) genera todo; los neutros están levemente tintados de verde para
     * que todo se sienta de la misma familia. El rojo anaranjado solo marca «grabando» y los errores.
     */
    static final class Palette {
        final boolean dark,dynamic;
        // Roles de Material 3 (se conservan: los usa toda la app)
        final int primary,onPrimary,primaryContainer,onPrimaryContainer;
        final int secondaryContainer,onSecondaryContainer;
        final int surface,surfaceContainerLowest,surfaceContainerLow,surfaceContainer,surfaceContainerHigh,surfaceContainerHighest;
        final int onSurface,onSurfaceVariant,outline,outlineVariant;
        final int error,onError,errorContainer,onErrorContainer;
        /** Roles invertidos (snackbar con "Deshacer"): cápsula de tinta en tema claro y clara en oscuro. */
        final int inverseSurface,inverseOnSurface,inversePrimary;
        // Roles de la app, derivados de los anteriores
        final int background,card,record,ripple;
        final int[] speakers;
        // ---- Roles propios de Verbapp (0.7.0) ----
        /** Tinta: el botón principal (negro verdoso en claro, blanco menta en oscuro) y su contenido. */
        final int ink,onInk;
        /** Verde de marca (botón de micrófono, ícono de la app) y su versión profunda para degradados. */
        final int brand,brandDeep,onBrand;
        /** Vidrio: relleno translúcido de tarjetas y su borde claro de 1 dp. */
        final int glass,glassStroke;
        /** Vidrio sobre el verde intenso (píldoras Detener/Pausa): relleno, borde y contenido. */
        final int glassOnVivid,glassOnVividStroke,onVivid,onVividVariant;
        /** Fondo del subrayado de una palabra destacada (la última palabra de un título grande). */
        final int highlight;
        /** Degradado del fondo: arriba, medio y abajo (intenso = Grabar; suave = listas y documentos). */
        final int gradTop,gradMid,gradBottom,softMid,softBottom;
        /** Puntitos de la textura de la parte alta del fondo. */
        final int dotGrid;
        /** Barras decorativas de onda sobre fondo claro (en reposo). */
        final int waveIdle;
        /** Color de sombra (Android 9+): verde muy oscuro en vez de gris. */
        final int shadow;

        Palette(Context c,boolean dark,boolean dynamic){
            this.dark=dark;this.dynamic=false;
            if(dark){
                primary=0xFF93D4BA;onPrimary=0xFF00382B;primaryContainer=0xFF1F4E40;onPrimaryContainer=0xFFC4EBDA;
                secondaryContainer=0xFF25352F;onSecondaryContainer=0xFFD6E7DF;
                surface=0xFF0E1412;surfaceContainerLowest=0xFF0A0F0D;surfaceContainerLow=0xFF151C19;surfaceContainer=0xFF19211E;surfaceContainerHigh=0xFF212B27;surfaceContainerHighest=0xFF2A3530;
                onSurface=0xFFE1EAE6;onSurfaceVariant=0xFFB4C3BC;outline=0xFF82918A;outlineVariant=0xFF36433E;
                ink=0xFFE3F0EA;onInk=0xFF0F1714;brand=0xFF3F8A6F;brandDeep=0xFF1F4E40;onBrand=0xFFFFFFFF;
                glass=0x17FFFFFF;glassStroke=0x26FFFFFF;glassOnVivid=0x1FFFFFFF;glassOnVividStroke=0x40FFFFFF;onVivid=0xFFF2F7F4;onVividVariant=0xCCF2F7F4;
                highlight=0xFF28503F;gradTop=0xFF0D1311;gradMid=0xFF101B17;gradBottom=0xFF1F4A3D;softMid=0xFF0F1714;softBottom=0xFF14241E;
                dotGrid=0x14FFFFFF;waveIdle=0xFF55635D;shadow=0xFF000000;
                inverseSurface=0xFFE1EAE6;inverseOnSurface=0xFF17201C;inversePrimary=0xFF2F6B58;
            }else{
                primary=0xFF2F6B58;onPrimary=0xFFFFFFFF;primaryContainer=0xFFD4E9DF;onPrimaryContainer=0xFF0C3427;
                secondaryContainer=0xFFE2EEE8;onSecondaryContainer=0xFF15261F;
                surface=0xFFF7FAF8;surfaceContainerLowest=0xFFFFFFFF;surfaceContainerLow=0xFFF2F6F4;surfaceContainer=0xFFECF1EF;surfaceContainerHigh=0xFFE5ECE9;surfaceContainerHighest=0xFFDEE6E2;
                onSurface=0xFF111714;onSurfaceVariant=0xFF4F5D57;outline=0xFF7C8A84;outlineVariant=0xFFCBD6D1;
                ink=0xFF121815;onInk=0xFFFFFFFF;brand=0xFF2F6B58;brandDeep=0xFF1E4D3F;onBrand=0xFFFFFFFF;
                // Borde del vidrio: un trazo muy tenue de verde oscuro. Uno blanco no se ve sobre la parte blanca del fondo.
                glass=0xC7FFFFFF;glassStroke=0x1C0B2A20;glassOnVivid=0x33FFFFFF;glassOnVividStroke=0x73FFFFFF;onVivid=0xFFFFFFFF;onVividVariant=0xD9FFFFFF;
                highlight=0xFFD8EDE3;gradTop=0xFFFFFFFF;gradMid=0xFFF1F6F4;gradBottom=0xFF3E7262;softMid=0xFFF5F9F7;softBottom=0xFFD3E4DC;
                dotGrid=0x1A1E3A30;waveIdle=0xFFA3AEA9;shadow=0xFF0B2A20;
                inverseSurface=0xFF121815;inverseOnSurface=0xFFEEF3F1;inversePrimary=0xFF9ED5BF;
            }
            error=dark?0xFFFFB4AB:0xFFBA1A1A;onError=dark?0xFF690005:0xFFFFFFFF;errorContainer=dark?0xFF93000A:0xFFFFDAD6;onErrorContainer=dark?0xFFFFDAD6:0xFF410002;
            // El fondo real es un degradado (Glass.Backdrop); este color sólido es su tono de arriba (barra de estado, ventanas).
            background=gradTop;card=glass;
            record=dark?0xFFFF7A55:0xFFE4572E;
            ripple=(onSurface&0x00FFFFFF)|0x1F000000;
            // Hablantes: 6 matices armonizados con el verde (misma luminosidad); el primero es el verde de marca.
            speakers=dark?new int[]{primary,0xFFF2B37A,0xFFA9C3FF,0xFFD9B3F5,0xFFFFB1BE,0xFFBFD77E}
                         :new int[]{primary,0xFFA5561C,0xFF3559A8,0xFF7E4C9F,0xFFA83F55,0xFF5D7A12};
        }
        int speaker(int index){return speakers[Math.abs(index)%speakers.length];}
    }

    /**
     * Escala tipográfica. Outfit (geométrica, en assets/fonts, licencia OFL) para todo lo que se «mira»: cronómetro,
     * títulos, etiquetas, botones y números. Roboto del sistema para el texto largo que se «lee» (transcripciones,
     * explicaciones), porque a tamaño chico y en párrafos se lee mejor.
     */
    enum Type {
        DISPLAY_LARGE(68,Weight.MEDIUM,-0.02f,true),
        DISPLAY_MEDIUM(44,Weight.MEDIUM,-0.015f,true),
        DISPLAY_SMALL(36,Weight.MEDIUM,-0.01f,true),
        HEADLINE_LARGE(32,Weight.MEDIUM,-0.01f,true),
        HEADLINE_MEDIUM(28,Weight.MEDIUM,-0.005f,true),
        HEADLINE_SMALL(24,Weight.MEDIUM,0f,true),
        TITLE_LARGE(22,Weight.MEDIUM,0f,true),
        TITLE_MEDIUM(16,Weight.MEDIUM,0.005f,true),
        TITLE_SMALL(14,Weight.SEMIBOLD,0.01f,true),
        /** Título de fila de lista o de opción de menú: Outfit normal, 16 sp. */
        ITEM(16,Weight.REGULAR,0.005f,true),
        BODY_LARGE(16,Weight.REGULAR,0.01f,false),
        BODY_MEDIUM(14,Weight.REGULAR,0.01f,false),
        BODY_SMALL(12,Weight.REGULAR,0.02f,false),
        LABEL_LARGE(14,Weight.MEDIUM,0.01f,true),
        LABEL_MEDIUM(12,Weight.MEDIUM,0.02f,true),
        LABEL_SMALL(11,Weight.MEDIUM,0.04f,true);
        final int size;final Weight weight;final float tracking;final boolean display;
        Type(int size,Weight weight,float tracking,boolean display){this.size=size;this.weight=weight;this.tracking=tracking;this.display=display;}
    }
    enum Weight{
        REGULAR(400),MEDIUM(500),BOLD(700),LIGHT(300),SEMIBOLD(600);
        final int value;Weight(int value){this.value=value;}
    }

    /** Espaciado en múltiplos de 4 dp (grilla de 4/8 dp). */
    static final int S1=4,S2=8,S3=12,S4=16,S5=20,S6=24,S8=32,S10=40;
    /**
     * Formas (0.7.0, más redondas): chica 8 · control 16 · tarjeta 24 · hoja 32 · píldora (99).
     * Las tarjetas de vidrio usan R_CARD; las esquinas grandes son parte de la estética de la referencia.
     */
    static final int R_SMALL=8,R_CONTROL=16,R_CARD=24,R_SHEET=32,R_FULL=99;
    /** Movimiento (ms): corto para respuestas, medio para aparecer, largo para transformaciones. */
    static final int MOTION_FAST=100,MOTION_BASE=250,MOTION_SLOW=400;

    /*
     * Curvas de movimiento de Material 3 Expressive (ver PROPUESTA-0.6 #12):
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
    /**
     * Colores del fondo de pantalla (Material You): desde 0.7.0 ya no se usan. Verbapp tiene una identidad propia (el
     * verde de marca y el degradado), que los colores del fondo de pantalla romperían. Se conserva el método para no
     * cambiar a quien lo consulta.
     */
    static boolean dynamicColor(Context c){return false;}
    static boolean isDark(Context c){String mode=appearance(c);return mode.equals("dark") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static Palette apply(Activity activity){Palette p=new Palette(activity,isDark(activity),false);activity.setTheme(p.dark?R.style.AppThemeDark:R.style.AppTheme);return p;}
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    /**
     * Pantalla de borde a borde: el degradado del fondo pasa por detrás de la barra de estado y de la de navegación
     * (ambas transparentes). Los íconos del sistema se eligen según lo que queda detrás: arriba siempre claro (íconos
     * oscuros en tema claro); abajo, verde intenso en Grabar (íconos blancos) o suave en las demás (íconos oscuros).
     * navigationColor: el color que queda detrás de la barra de navegación (decide el color de sus íconos).
     */
    static void window(Activity activity,Palette p,int navigationColor){
        Window w=activity.getWindow();
        w.setStatusBarColor(0x00000000);w.setNavigationBarColor(0x00000000);w.getDecorView().setBackgroundColor(p.background);
        if(Build.VERSION.SDK_INT>=28)w.setNavigationBarDividerColor(0x00000000);
        if(Build.VERSION.SDK_INT>=29){w.setNavigationBarContrastEnforced(false);w.setStatusBarContrastEnforced(false);}
        boolean lightNav=!p.dark&&luminance(navigationColor)>0.5f;
        if(Build.VERSION.SDK_INT>=30){
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController controller=w.getInsetsController();
            if(controller!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance((p.dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)|(lightNav?WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0),mask);}
        }else{
            int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if(!p.dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(lightNav&&Build.VERSION.SDK_INT>=27)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            // Android 8.0 no tiene íconos oscuros en la barra de navegación: sobre fondo claro, un velo la mantiene legible.
            if(lightNav&&Build.VERSION.SDK_INT<27)w.setNavigationBarColor(0x66000000);
            w.getDecorView().setSystemUiVisibility(flags);
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
    /** Roboto del sistema (texto de lectura). */
    static Typeface typeface(Weight weight){
        switch(weight){
            case BOLD:return Typeface.create("sans-serif",Typeface.BOLD);
            case MEDIUM:case SEMIBOLD:return Typeface.create("sans-serif-medium",Typeface.NORMAL);
            case LIGHT:return Typeface.create("sans-serif-light",Typeface.NORMAL);
            default:return Typeface.create("sans-serif",Typeface.NORMAL);
        }
    }
    private static final Typeface[] OUTFIT=new Typeface[10];
    /**
     * Outfit (fuente variable de 100 a 900) con el peso pedido. Se carga una vez por peso. Si el archivo faltara o
     * Android no pudiera leerlo, se usa Roboto: la app nunca queda sin letra.
     */
    static Typeface outfit(Context c,Weight weight){return outfit(c,weight.value);}
    static Typeface outfit(Context c,int weight){
        int slot=Math.max(1,Math.min(9,Math.round(weight/100f)));
        synchronized(OUTFIT){
            if(OUTFIT[slot]!=null)return OUTFIT[slot];
            Typeface t=null;
            try{t=new Typeface.Builder(c.getApplicationContext().getAssets(),"fonts/Outfit.ttf").setFontVariationSettings("'wght' "+(slot*100)).setWeight(slot*100).build();}catch(RuntimeException ignored){}
            if(t==null)t=typeface(slot>=6?Weight.BOLD:slot==5?Weight.MEDIUM:slot<=3?Weight.LIGHT:Weight.REGULAR);
            OUTFIT[slot]=t;return t;
        }
    }
    /** Letra de un estilo de la escala (Outfit o Roboto según corresponda). */
    static Typeface font(Context c,Type type){return type.display?outfit(c,type.weight):typeface(type.weight);}
    static void type(TextView t,Type type){
        t.setTextSize(type.size);t.setTypeface(font(t.getContext(),type));t.setLetterSpacing(type.tracking);
        float extra=type.size>=22?type.size*0.12f:type.size*0.4f;t.setLineSpacing(dp(t.getContext(),extra/2f),1f);
    }
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static GradientDrawable outline(Context c,int fill,int stroke,int radius,boolean dashed){GradientDrawable d=shape(c,fill,radius);if(dashed)d.setStroke(dp(c,1.5f),stroke,dp(c,6),dp(c,5));else d.setStroke(dp(c,1),stroke);return d;}
    static GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}
    /** Vidrio: relleno translúcido con borde claro de 1 dp (tarjetas, grupos de lista, campos). */
    static GradientDrawable glass(Context c,Palette p,int radius){GradientDrawable d=shape(c,p.glass,radius);d.setStroke(Math.max(1,dp(c,1)),p.glassStroke);return d;}
    /** Vidrio circular (botones de ícono flotantes: volver, más opciones). */
    static GradientDrawable glassOval(Context c,Palette p){GradientDrawable d=oval(p.dark?p.glass:0xF2FFFFFF);d.setStroke(Math.max(1,dp(c,1)),p.glassStroke);return d;}
    private AppTheme(){}
}
