package cl.vozlocal.app;

import android.content.*;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Ahorro de batería de Android y de los fabricantes. Con la optimización activa, algunos teléfonos (vivo, Xiaomi,
 * Huawei, OPPO…) congelan la app con la pantalla bloqueada aunque tenga un servicio en primer plano: el teléfono
 * corta la conexión con OpenAI ("Software caused connection abort") y las esperas de 1 min duran 15.
 */
final class Battery {
    private Battery(){}
    /** true si Android no optimiza la batería de Verbapp (puede trabajar con la pantalla bloqueada). */
    static boolean unrestricted(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm!=null&&pm.isIgnoringBatteryOptimizations(c.getPackageName());}
    static boolean screenOn(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm==null||pm.isInteractive();}
    static boolean idle(Context c){PowerManager pm=c.getSystemService(PowerManager.class);return pm!=null&&pm.isDeviceIdleMode();}
    /** Marcas que suelen tener un control de energía propio además del de Android. */
    static boolean aggressiveMaker(){String m=android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);for(String x:new String[]{"vivo","iqoo","xiaomi","redmi","poco","huawei","honor","oppo","realme","oneplus","samsung"})if(m.contains(x))return true;return false;}
    /** Pide a Android que deje de optimizar la batería de la app (diálogo del sistema de un toque). */
    static void request(Context c){
        try{c.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+c.getPackageName())));return;}catch(RuntimeException ignored){}
        appSettings(c);
    }
    /** Ficha de la app en Ajustes de Android (ahí está "Batería" en la mayoría de los teléfonos). */
    static void appSettings(Context c){
        try{c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.getPackageName())));}
        catch(RuntimeException e){try{c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException ignored){}}
    }
    /** Instrucción extra según la marca, para el control de energía propio del fabricante. */
    static String makerHint(){
        String m=android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if(m.contains("vivo")||m.contains("iqoo"))return "En vivo, además: Ajustes → Batería → Control de energía en segundo plano → Verbapp → Permitir uso de energía en segundo plano.";
        if(m.contains("xiaomi")||m.contains("redmi")||m.contains("poco"))return "En Xiaomi, además: Ajustes → Apps → Verbapp → Ahorro de batería → Sin restricciones.";
        if(m.contains("samsung"))return "En Samsung, además: Ajustes → Apps → Verbapp → Batería → Sin restricciones.";
        if(m.contains("huawei")||m.contains("honor"))return "En Huawei/Honor, además: Ajustes → Batería → Inicio de aplicaciones → Verbapp → Gestionar manualmente y activa \"Ejecutar en segundo plano\".";
        if(m.contains("oppo")||m.contains("realme")||m.contains("oneplus"))return "En OPPO/realme/OnePlus, además: Ajustes → Batería → Verbapp → Permitir actividad en segundo plano.";
        return "";
    }
}
