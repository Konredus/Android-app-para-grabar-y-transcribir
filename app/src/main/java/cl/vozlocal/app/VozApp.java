package cl.vozlocal.app;

import android.app.*;
import android.os.Bundle;

public class VozApp extends Application {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){Lang.init(base);super.attachBaseContext(Lang.wrap(base));}
    @Override public void onCreate(){super.onCreate();Diagnostics.init(this);Diagnostics.event("app_start",null);Settings settings=new Settings(this);if(settings.prefs.getInt("schema",0)<3){getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);for(Recording r:Recording.list(this))if(Transcript.exists(this,r.id))try{FilesStore.update(this,r.id,state->state.put("requested",false).put("status","Transcripción lista"));}catch(Exception ignored){}settings.prefs.edit().putInt("schema",3).remove("driveConnected").remove("driveEmail").remove("folderId").apply();}
        if(settings.prefs.getInt("schema",0)<4){
            // 0.4.2: el modelo se elige por transcripción (con o sin voces). Se respeta la elección previa de un modelo sin voces.
            String old=settings.prefs.getString("openaiModel","gpt-4o-transcribe-diarize");android.content.SharedPreferences.Editor e=settings.prefs.edit().putInt("schema",4);
            if(!old.equals("gpt-4o-transcribe-diarize")){e.putString("openaiTextModel",old).putString("speakersMode","never");}
            e.apply();
        }
        if(settings.prefs.getInt("schema",0)<5){
            // 0.8.0, segunda ronda (SPEC-0.8b, decisión 2): la app transcribe y arma notas solo con OpenRouter. Quien venía de
            // OpenAI o de su servidor pasa a OpenRouter; sus claves viejas quedan cifradas y sin uso (no se borran ni se
            // muestran), y nada de sus grabaciones, transcripciones, voces o notas cambia. Si aún no tiene clave de OpenRouter,
            // la app se la pide donde siempre pide la clave que falta (Grabar → «Configurar transcripción», Ajustes, Transcribir)
            // y explica por qué. Lo que estaba en cola se transcribe con OpenRouter; sin clave, queda para «Reintentar».
            String before=settings.prefs.getString("provider",settings.hasOpenAiKey()?"openai":"none");
            openRouterOnly(settings,settings.prefs.edit().putInt("schema",5)).apply();
            // Solo identificadores: de dónde venía (sin datos de la cuenta) y si ya tenía clave de OpenRouter.
            Diagnostics.event("setting_changed",null,"action","schema_openrouter","provider",before.equals("openai")||before.equals("openrouter")||before.equals("none")?before:"custom","result",settings.hasOpenRouterKey()?"has_key":"needs_key");
        }
        // Al abrir se pone al día la lista de modelos (una vez al día, en segundo plano), para que «Automático» pase solo a
        // otro modelo si OpenRouter retira el que usaba.
        // Pricing.estimate("openrouter", …) lee el precio del catálogo guardado y necesita un Context: se deja desde el arranque,
        // así la primera pantalla que muestre un costo ya lo tiene (antes dependía de que el motor o Transcript pasaran primero).
        Pricing.attach(this);
        Models.refreshIfStale(this);
        Thread.UncaughtExceptionHandler prior=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{Diagnostics.crash(error);if(prior!=null)prior.uncaughtException(thread,error);});
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityResumed(Activity a){Diagnostics.event("screen_open",null,"screen",a.getClass().getSimpleName());}
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        });
    }
    /**
     * «Solo OpenRouter» (esquema 5): proveedor "openrouter" y sin elección de IA para la nota (la nota va por OpenRouter).
     * Un modelo de nota de OpenAI o de Claude no sirve en OpenRouter (Notes lo ignoraría y Ajustes mostraría uno que no
     * se usa): se quita. Uno de OpenRouter, de quien probó la primera ronda de la 0.8.0, se conserva. No toca ninguna
     * clave. Devuelve el mismo editor, sin aplicar: lo usan la migración y Ajustes (si encuentra otro proveedor guardado).
     */
    static android.content.SharedPreferences.Editor openRouterOnly(Settings s,android.content.SharedPreferences.Editor e){
        e.putString("provider","openrouter").remove("noteProvider");
        String model=s.noteModel()==null?"":s.noteModel().trim();if(!model.isEmpty()&&!Notes.routerModel(model))e.remove("noteModel");
        return e;
    }
}
