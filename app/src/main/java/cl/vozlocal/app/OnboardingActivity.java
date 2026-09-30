package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Bienvenida de la primera instalación (0.8.0): unas pocas pantallas a pantalla completa, con la estética Verbapp, que
 * presentan la app y dejan lista la configuración mínima (nombre, micrófono, clave de la IA). Se puede saltar y volver
 * a ver desde Ajustes.
 * FASE 0 (contrato): la implementa la parte «onboarding». Ver docs/diseno/SPEC-0.8.md.
 */
public class OnboardingActivity extends Screen {
    /** true si nunca se mostró la bienvenida (preferencia "welcomed", la misma de la hoja de bienvenida anterior). */
    static boolean shouldShow(Context c){return !new Settings(c).prefs.getBoolean("welcomed",false);}
    /** Abre la bienvenida. replay=true: el usuario pidió verla otra vez desde Ajustes (no cambia "welcomed" al salir). */
    static void open(Context c,boolean replay){c.startActivity(new Intent(c,OnboardingActivity.class).putExtra("replay",replay));}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);shell(null,-1,true);
        new Settings(this).prefs.edit().putBoolean("welcomed",true).apply();
        largeTitle(page,"Bienvenido a Verbapp","Tus palabras, para siempre");
        page.addView(ui.button("Empezar",0,Ui.Style.PRIMARY,v->finish()),ui.top(S6));
    }
}
