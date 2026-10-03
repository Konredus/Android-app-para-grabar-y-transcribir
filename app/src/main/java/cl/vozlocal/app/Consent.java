package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Aviso destacado y consentimiento antes del primer envío a OpenRouter (0.9.0, requisito de Google Play para datos
 * personales y sensibles: el audio de las grabaciones). Dice qué se envía (el audio de lo que transcribes; el texto, para
 * la nota), a quién (OpenRouter y el modelo elegido) y que lo que no transcribes nunca sale del teléfono. Se pide una vez,
 * antes de guardar una clave (bienvenida y Ajustes) y antes de la primera transcripción si aún no se aceptó. «Acepto»
 * queda guardado en "uploadConsentAt"; sin aceptar, la app sigue funcionando para grabar y escuchar.
 */
final class Consent {
    private Consent(){}
    private static final String KEY="uploadConsentAt";
    /** ¿Ya aceptó que su audio se envíe para transcribirlo? */
    static boolean given(Context c){return new Settings(c).prefs.getLong(KEY,0)>0;}
    static void accept(Context c){new Settings(c).prefs.edit().putLong(KEY,System.currentTimeMillis()).apply();Diagnostics.event("setting_changed",null,"action","upload_consent","result","accepted");}
    /**
     * Si ya aceptó, sigue (then) en el acto. Si no, muestra el aviso: «Acepto» lo guarda y sigue; «Ahora no» no hace nada;
     * «Política de privacidad» abre la política en el idioma de la app (el aviso queda abierto).
     */
    static void ensure(Screen s,Runnable then){
        if(given(s)){then.run();return;}
        Sheet sheet=s.sheet(s.getString(R.string.consent_title),s.getString(R.string.consent_body));
        sheet.primary(s.getString(R.string.consent_accept),()->{accept(s);then.run();});
        sheet.primary(s.getString(R.string.privacy_policy),Ui.Style.SECONDARY,()->{openPolicy(s);return false;});
        sheet.secondary(s.getString(R.string.consent_later),null).show();
    }
    /** La política de privacidad en el navegador. */
    static void openPolicy(Context c){
        try{c.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(Lang.str(c,R.string.privacy_url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException ignored){}
    }
}
