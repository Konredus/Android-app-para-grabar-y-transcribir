package cl.vozlocal.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.Locale;

/**
 * Ahorro de batería de Android y de los fabricantes. Con la optimización activa, algunos teléfonos (vivo, Xiaomi,
 * Huawei, OPPO…) congelan la app con la pantalla bloqueada aunque tenga un servicio en primer plano: el teléfono
 * corta la conexión con OpenRouter ("Software caused connection abort"), las esperas de 1 min duran 15 y la tarea de
 * fondo queda pausada hasta que se abre la app (diagnóstico del 2026-10-01 en docs/PENDIENTES.md: vivo, Android 16).
 *
 * Hay dos capas y las dos importan:
 * 1. La de Android («optimización de batería»): se ve con unrestricted() y se pide con request().
 * 2. La del fabricante (vivo, Xiaomi, Huawei, OPPO…): ninguna app puede leerla ni cambiarla; solo se explica con
 *    makerSteps() / makerHint() y se abre la ficha de la app (appSettings()) para que la persona la cambie.
 */
final class Battery {
    private Battery(){}
    /** true si Android no optimiza la batería de Verbapp (puede trabajar con la pantalla bloqueada). */
    static boolean unrestricted(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm!=null&&pm.isIgnoringBatteryOptimizations(c.getPackageName());}
    static boolean screenOn(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm==null||pm.isInteractive();}
    static boolean idle(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm!=null&&pm.isDeviceIdleMode();}
    /** Marcas que suelen tener un control de energía propio además del de Android. */
    static boolean aggressiveMaker(){String m=Build.MANUFACTURER.toLowerCase(Locale.ROOT);for(String x:new String[]{"vivo","iqoo","xiaomi","redmi","poco","huawei","honor","oppo","realme","oneplus","samsung"})if(m.contains(x))return true;return false;}
    /**
     * Pide a Android que deje de optimizar la batería de la app (diálogo del sistema de un toque). Si la app no declara el
     * permiso REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (docs/PLAN-play-store.md propone quitarlo para publicar en Play),
     * Android cerraría ese diálogo sin mostrar nada: en ese caso, y si el diálogo no existe, se abre la ficha de la app,
     * donde está «Batería». Así quitar el permiso del manifiesto no deja este botón mudo.
     */
    static void request(Context c){
        if(canAskDirectly(c))try{c.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+c.getPackageName())));return;}catch(RuntimeException ignored){}
        appSettings(c);
    }
    /** La app declara el permiso que habilita el diálogo directo de Android. */
    static boolean canAskDirectly(Context c){
        try{
            PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_PERMISSIONS);
            if(info.requestedPermissions!=null)for(String p:info.requestedPermissions)if(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(p))return true;
        }catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
        return false;
    }
    /** Ficha de la app en Ajustes de Android (ahí está "Batería" en la mayoría de los teléfonos). */
    static void appSettings(Context c){
        try{c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.getPackageName())));}
        catch(RuntimeException e){try{c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException ignored){}}
    }

    // ---------- Guía del fabricante ----------
    /*
     * Los nombres de los menús cambian entre versiones (Funtouch OS, OriginOS, MIUI, HyperOS, ColorOS…) y no se pueden
     * leer desde la app: por eso cada guía dice la ruta más probable para la versión de Android, nombra la opción por lo
     * que hace («permitir en segundo plano») y la bienvenida agrega el atajo que sirve en todos: buscar «segundo plano»
     * en Ajustes. Nada de esto se confirmó en cada teléfono; vivo con Android 16 es el caso real del diagnóstico.
     */
    /** Nombre de la marca para los textos («vivo», «Xiaomi»…); "" si no se reconoce. manufacturer: Build.MANUFACTURER. */
    static String makerName(String manufacturer){
        String m=manufacturer==null?"":manufacturer.toLowerCase(Locale.ROOT);
        if(m.contains("iqoo"))return "iQOO";if(m.contains("vivo"))return "vivo";
        if(m.contains("xiaomi")||m.contains("redmi")||m.contains("poco"))return "Xiaomi";
        if(m.contains("huawei"))return "Huawei";if(m.contains("honor"))return "Honor";
        if(m.contains("oppo"))return "OPPO";if(m.contains("realme"))return "realme";if(m.contains("oneplus"))return "OnePlus";
        if(m.contains("samsung"))return "Samsung";
        return "";
    }
    static String makerName(){return makerName(Build.MANUFACTURER);}
    /** El botón de la guía que abre la ficha de la app (appSettings); el primer paso lo nombra igual, letra por letra. */
    static final String OPEN_SETTINGS="Abrir ajustes de Verbapp";
    /**
     * Pasos para el ahorro PROPIO del fabricante, el que sigue activo aunque Android ya no optimice la app; vacío si la
     * marca no lo tiene. Samsung no está: su «Sin restricciones» es el mismo ajuste que cambia el diálogo de Android.
     * El primer paso siempre es el botón «Abrir ajustes de Verbapp» de la hoja (appSettings), así nadie busca la app a mano.
     * sdk: Build.VERSION.SDK_INT (de Android 13 en adelante, las marcas llevaron este control a la ficha de la app).
     */
    static String[] makerSteps(String manufacturer,int sdk){
        String m=manufacturer==null?"":manufacturer.toLowerCase(Locale.ROOT);
        String open="Toca «"+OPEN_SETTINGS+"», aquí abajo.";
        if(m.contains("vivo")||m.contains("iqoo")){
            // vivo (Funtouch OS 13–15, OriginOS en Android 16): en la ficha de la app, «Batería» lleva al consumo en
            // segundo plano. En las versiones anteriores estaba en Ajustes → Batería → Control de energía en segundo plano.
            if(sdk>=33)return new String[]{open,"Entra a «Batería» (o «Consumo de energía»).","Elige la opción que permite el consumo de energía en segundo plano.","Si ves «Inicio automático», actívalo también."};
            return new String[]{"Abre Ajustes → Batería → «Control de energía en segundo plano».","Busca Verbapp.","Activa «Permitir uso de energía en segundo plano»."};
        }
        if(m.contains("xiaomi")||m.contains("redmi")||m.contains("poco"))
            return new String[]{open,"Entra a «Ahorro de batería» y elige «Sin restricciones».","Activa «Inicio automático»."};
        if(m.contains("huawei")||m.contains("honor"))
            return new String[]{"Abre Ajustes → Batería → «Inicio de aplicaciones».","Busca Verbapp y desactiva «Gestionar automáticamente».","Deja activado «Ejecutar en segundo plano»."};
        if(m.contains("oppo")||m.contains("realme")||m.contains("oneplus"))
            return new String[]{open,"Entra a «Uso de la batería».","Activa «Permitir actividad en segundo plano» y «Permitir inicio automático»."};
        return new String[0];
    }
    static String[] makerSteps(){return makerSteps(Build.MANUFACTURER,Build.VERSION.SDK_INT);}
    /**
     * La misma guía en una línea, para la hoja «Trabajar con la pantalla bloqueada» (RecordingActions.allowBackground),
     * que la muestra junto a «Abrir ajustes de la app». "" si la marca no tiene un control propio que explicar.
     */
    static String makerHint(String manufacturer,int sdk){
        String m=manufacturer==null?"":manufacturer.toLowerCase(Locale.ROOT),name=makerName(manufacturer);
        if(m.contains("vivo")||m.contains("iqoo"))return sdk>=33
            ?"En "+name+", además: Ajustes → Apps → Verbapp → Batería → permite el consumo de energía en segundo plano. Si no lo ves, busca «segundo plano» en Ajustes."
            :"En "+name+", además: Ajustes → Batería → Control de energía en segundo plano → Verbapp → Permitir uso de energía en segundo plano.";
        if(m.contains("xiaomi")||m.contains("redmi")||m.contains("poco"))return "En Xiaomi, además: Ajustes → Apps → Verbapp → Ahorro de batería → Sin restricciones, y activa «Inicio automático».";
        if(m.contains("samsung"))return "En Samsung, además: Ajustes → Apps → Verbapp → Batería → Sin restricciones.";
        if(m.contains("huawei")||m.contains("honor"))return "En "+name+", además: Ajustes → Batería → Inicio de aplicaciones → Verbapp → Gestionar manualmente y activa «Ejecutar en segundo plano».";
        if(m.contains("oppo")||m.contains("realme")||m.contains("oneplus"))return "En "+name+", además: Ajustes → Apps → Verbapp → Uso de la batería → Permitir actividad en segundo plano.";
        return "";
    }
    /** Instrucción extra según la marca de este teléfono, para el control de energía propio del fabricante. */
    static String makerHint(){return makerHint(Build.MANUFACTURER,Build.VERSION.SDK_INT);}
}
