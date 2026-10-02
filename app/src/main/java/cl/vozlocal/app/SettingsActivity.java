package cl.vozlocal.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.job.JobScheduler;
import android.content.*;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Ajustes en el orden de tu recorrido (0.6.0): «Tu flujo» (0-Inbox, nota, automatización, voces conocidas) → servicio de
 * transcripción → energía y red → copias → apariencia → ayuda. Cada fila muestra su valor a la derecha y a lo más
 * una línea de apoyo; las explicaciones largas viven en «Más información». Ver docs/diseno/PROPUESTA-0.6.md (#3).
 *
 * 0.7.0 (Verbapp, PROPUESTA-0.7 §3.6): fondo suave, tarjeta de marca y estado (logo, lema y «¿está funcionando?»),
 * grupos de vidrio con cada ícono en un círculo menta, el tema elegido con miniaturas y un pie con la marca.
 * Se quitó «Colores de tu fondo de pantalla» (Verbapp tiene su propio verde); todo lo demás hace lo mismo que en 0.6.
 *
 * 0.8.0 (OpenRouter): «Comprobar conexión» que muestra el saldo y dos filas de modelo («con voces» y «solo texto») que
 * abren la hoja de modelos: la lista de OpenRouter que se actualiza sola, con «Automático (recomendado)» primero. Ayuda
 * suma «Ver la bienvenida».
 *
 * 0.8.0, segunda ronda (SPEC-0.8b): solo OpenRouter. Ya no se elige proveedor ni IA de la nota: una sección «Tu IA
 * (OpenRouter)» reúne la clave (con «¿Cómo consigo una clave?»), «Comprobar conexión», los dos modelos, «Separar voces»,
 * el modelo de la nota y el idioma. Arriba, la tarjeta «Tus métricas» abre MetricsActivity. OpenAI directo, el servidor
 * compatible y la clave de Anthropic siguen en el código (los cubren pruebas), pero esta pantalla ya no lleva a ellos.
 *
 * 0.8.0, tercera ronda (SPEC-0.8c): «Red para enviar audio» cuenta que, con «Solo Wi-Fi», cada transcripción en espera
 * ofrece «Usar datos móviles ahora» (solo para esa grabación). «Comprobar conexión» y la bienvenida calculan el saldo
 * con la misma regla (Models.balance): una cuenta sin créditos no guarda el tope de la clave como saldo.
 *
 * 0.9.0 (tres idiomas): todos los textos viven en strings_settings.xml (inglés, español y portugués de Brasil). Arriba,
 * la fila «Idioma» elige el de la app (Lang); «Idioma del audio» ofrece detección automática, inglés, español o
 * portugués (sin elegir, el de la app). Ayuda suma «Política de privacidad», y guardar la primera clave pasa antes por el
 * aviso de qué se envía (Consent, requisito de Google Play). Montos, fechas y «hace 5 min» van con el formato del idioma.
 *
 * Extras del intent: focusKey (pedir la clave si falta), voice (grabar tu voz, o ver las voces conocidas, y volver), back (Atrás vuelve a
 * la pantalla anterior), inbox (elegir la carpeta de guardado rápido y volver) y noteAi (abrir «IA de la nota»).
 */
public class SettingsActivity extends Screen {
    /** Modelos de OpenAI directo para transcribir SIN separar voces. Ya no se eligen aquí: solo nombran el modelo en modelName (pruebas). */
    static final String[] MODELS={"gpt-transcribe","gpt-4o-transcribe","gpt-4o-mini-transcribe","whisper-1"};
    static final String[] MODEL_NAMES={"GPT Transcribe","GPT-4o Transcribe","GPT-4o Mini","Whisper"};
    /** Modos de «Separar voces» y su nombre (recursos: el texto sale en el idioma de la app al mostrarlo). */
    static final String[] SPEAKER_MODES={"ask","always","never"};
    private static final int[] SPEAKER_NAMES={R.string.set_speakers_ask,R.string.set_speakers_always,R.string.set_speakers_never};
    /** Páginas de OpenRouter que se abren en el navegador: crear la clave y cargar créditos. */
    static final String KEYS_URL="https://openrouter.ai/keys",CREDITS_URL="https://openrouter.ai/settings/credits";
    private static final int PICK_FOLDER=51,PICK_SAVE=52;
    /**
     * Hilos de fondo: io (carpetas, informe y la lista de modelos, que puede tardar), checks (comprobar la clave: no espera
     * detrás de la lista) y stats (el resumen de «Tus métricas», que lee todas las grabaciones).
     */
    private Settings settings;private final ExecutorService io=Executors.newSingleThreadExecutor(),checks=Executors.newSingleThreadExecutor(),stats=Executors.newSingleThreadExecutor();private HttpApi http;
    private Ui.Row folderRow,saveRow,batteryRow,voiceRow,verifyRow;
    private boolean verifying;
    private String batteryValue(){return getString(Battery.unrestricted(this)?R.string.set_battery_unrestricted:R.string.set_battery_optimized);}
    /**
     * Al volver de otra pantalla se refleja lo que cambió allá: el permiso de batería, y todo lo demás si cambió algo
     * (hallazgo de la revisión: al repasar la bienvenida, o al grabar tu voz o elegir la carpeta 0-Inbox en otra instancia
     * de Ajustes, esta quedaba mostrando lo de antes y sus hojas actuaban sobre otra cosa). Las métricas se recalculan
     * siempre: una grabación nueva las cambia sin tocar ninguna preferencia.
     */
    @Override protected void onResume(){
        super.onResume();if(batteryRow!=null)batteryRow.setValue(batteryValue());
        if(lastShown!=null&&!lastShown.equals(shown()))render();
        loadMetrics();
    }
    /** Lo que se mostró en el último render(): sus preferencias, las voces conocidas y la fecha de la lista de modelos. */
    private String lastShown;
    private String shown(){return new java.util.TreeMap<>(settings.prefs.getAll()).toString()+"|"+voiceValue()+"|"+Models.fetchedAt(this);}

    static String modelName(Settings s){if(s.openRouter())return Models.name(Models.chosen(s,false));if(!s.provider().equals("openai"))return s.prefs.getString("customModel",Lang.str(R.string.set_model_custom));int i=Arrays.asList(MODELS).indexOf(s.textModel());return i<0?s.textModel():MODEL_NAMES[i];}
    /** «OpenRouter · MAI Transcribe 2 + separación de voces». Con OpenRouter puede haber un modelo para el texto y otro para las voces. */
    static String modelSummary(Settings s){
        if(s.openRouter()){
            String name=modelName(s),text="OpenRouter · "+name;if(!s.defaultSpeakers())return text;
            String voices=Models.name(Models.chosen(s,true));
            return voices.equals(name)?Lang.str(R.string.set_summary_plus_voices,text):Lang.str(R.string.set_summary_voices_with,text,voices);
        }
        if(!s.provider().equals("openai"))return s.providerName()+" · "+modelName(s);
        String openai="OpenAI · "+modelName(s);return s.speakersMode().equals("never")?openai:Lang.str(R.string.set_summary_plus_voices,openai);
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);
        // Deja leída la lista de modelos guardada: la tarjeta de estado nombra el modelo de OpenRouter antes de armar sus filas.
        Models.cached(this);
        Intent in=getIntent();voiceFlow=in.getBooleanExtra("voice",false);returnOnBack=in.getBooleanExtra("back",false);inboxFlow=in.getBooleanExtra("inbox",false);
        render();
        if(state==null)page.post(()->openFrom(in));
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("voice",false))voiceFlow=true;if(intent.getBooleanExtra("inbox",false))inboxFlow=true;openFrom(intent);}
    /** Abre directo la hoja que pidió otra pantalla (p. ej. «Elegir carpeta rápida» desde una grabación). */
    private void openFrom(Intent intent){
        if(isFinishing())return;
        if(intent.getBooleanExtra("focusKey",false)&&!settings.hasOpenRouterKey())keySheet();
        else if(intent.getBooleanExtra("voice",false)){
            if(voiceRow!=null){if(Voices.has(this))voiceSheet();else myVoiceSheet();}
            // Sin fila «Voces conocidas» (el modelo con voces elegido no separa voces) no hay hoja que abrir: se dice por qué
            // y cómo salir, en vez de dejar a quien venía a «Grabar mi voz» en Ajustes sin nada.
            else message(getString(R.string.set_voices_title),getString(R.string.set_voices_unavailable));
        }
        else if(intent.getBooleanExtra("inbox",false))saveSheet();
        else if(intent.getBooleanExtra("noteAi",false))noteAiSheet();
    }
    /** Abierta desde otra pantalla para un paso puntual (grabar tu voz, carpeta rápida): Atrás (o terminar) vuelve ahí, no a Inicio. */
    private boolean voiceFlow,returnOnBack,inboxFlow;
    @Override public void onBackPressed(){if(voiceFlow||returnOnBack||inboxFlow){finish();return;}navigate(0);}

    private void render(){
        // Solo OpenRouter: si quedó guardado otro proveedor (un camino que no pasó por la migración del esquema 5, o algo que
        // lo cambió mientras esta pantalla estaba detrás), se pasa a OpenRouter igual que en la migración. Así lo que
        // muestra esta pantalla es lo que de verdad se usa al transcribir.
        if(!settings.openRouter()){VozApp.openRouterOnly(settings,settings.prefs.edit()).apply();Diagnostics.event("setting_changed",null,"action","provider","result","openrouter","source","settings");}
        shell(null,2);largeTitle(page,getString(R.string.nav_settings),null);
        page.addView(statusCard(),Ui.fill());
        // Tus métricas (0.8.0): cerca del inicio, como «Tu semana» en Grabar. El resumen se calcula aparte (loadMetrics).
        page.addView(metricsCard(),ui.top(S3));
        // Idioma de la app (0.9.0): arriba, a mano también para quien la abrió en un idioma que no lee bien. Va con el globo
        // (el ícono de traducir es de «Idioma del audio») y el nombre del idioma en ese mismo idioma.
        LinearLayout language=ui.group();page.addView(language,ui.top(S3));
        add(language,row(R.drawable.ic_globe,getString(R.string.common_language),null,langName(Lang.current(this))).onClick(v->appLanguageSheet()));

        // 1. Tu flujo: lo que pasa con cada grabación, de principio a fin.
        page.addView(ui.section(getString(R.string.set_section_flow)));LinearLayout flow=ui.group();page.addView(flow,Ui.fill());
        saveRow=row(R.drawable.ic_inbox,getString(R.string.set_quick_save),getString(R.string.set_quick_save_sub),inboxValue());saveRow.onClick(v->saveSheet());add(flow,saveRow);
        View[] note={null};
        note[0]=toggleRow(R.drawable.ic_doc,getString(R.string.set_note_title),noteSubtitle(settings.noteAuto()),settings.noteAuto(),on->{
            settings.prefs.edit().putBoolean("noteAuto",on).apply();Diagnostics.event("setting_changed",null,"action","note_auto","result",on);
            subtitle(note[0],noteSubtitle(on));if(on&&!noteReady())page.post(this::noteMissingKey);});
        add(flow,note[0]);
        add(flow,toggleRow(R.drawable.ic_bolt,getString(R.string.set_auto_title),getString(R.string.set_auto_sub),settings.automatic(),on->{toggle("automatic",on);if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},101);}));
        add(flow,toggleRow(R.drawable.ic_title,getString(R.string.set_ask_title),getString(R.string.set_ask_title_sub),settings.askTitle(),on->toggle("askTitle",on)));
        Ui.Row dates=row(R.drawable.ic_edit,getString(R.string.set_dates_existing),getString(R.string.set_dates_existing_sub),null);dates.onClick(v->offerDatesForExisting());
        add(flow,toggleRow(R.drawable.ic_calendar,getString(R.string.set_date_prefix),getString(R.string.set_date_prefix_example,Recording.isoDate(System.currentTimeMillis())),settings.datePrefix(),on->{settings.prefs.edit().putBoolean("datePrefix",on).apply();Diagnostics.event("setting_changed",null,"action","date_prefix","result",on);showRow(dates,on);if(on)offerDatesForExisting();}));
        add(flow,dates);showRow(dates,settings.datePrefix());
        voiceRow=null;
        // La misma compuerta del motor (TranscribeClient.knowsVoices): con OpenRouter las muestras van delante del audio,
        // solo si el modelo con voces elegido separa voces (canSeparate).
        if(TranscribeClient.knowsVoices(settings.provider())&&settings.canSeparate()){voiceRow=row(R.drawable.ic_voice,getString(R.string.set_voices_title),getString(R.string.set_voices_sub),voiceValue());voiceRow.onClick(v->voiceSheet());add(flow,voiceRow);}
        page.addView(more(getString(R.string.set_flow_foot),getString(R.string.set_section_flow),getString(R.string.set_flow_more,Transcriber.MAX_KNOWN)));

        // 2. Tu IA (OpenRouter), segunda ronda de la 0.8.0: una sola clave para transcribir y para la nota. Primero lo que
        // hace falta para empezar (la clave y si funciona); después lo que se puede afinar. Sin elección de proveedor.
        page.addView(ui.section(getString(R.string.set_section_ai)));LinearLayout ai=ui.group();page.addView(ai,Ui.fill());
        boolean hasKey=settings.hasOpenRouterKey();
        add(ai,row(R.drawable.ic_key,getString(R.string.set_key_title),hasKey?null:getString(R.string.set_key_sub),getString(hasKey?R.string.set_key_set:R.string.set_key_missing)).onClick(v->keySheet()));
        verifyRow=row(R.drawable.ic_network_check,getString(R.string.set_verify_title),verifyText(),null);verifyRow.subtitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);verifyRow.onClick(v->verify());add(ai,verifyRow);paintVerify();
        add(ai,orModelRow(true));add(ai,orModelRow(false));
        if(settings.canSeparate())add(ai,row(R.drawable.ic_people,getString(R.string.set_speakers_title),null,getString(SPEAKER_NAMES[Math.max(0,Arrays.asList(SPEAKER_MODES).indexOf(settings.speakersMode()))])).onClick(v->speakersSheet()));
        add(ai,row(R.drawable.ic_sparkle,getString(R.string.set_note_ai_title),noteAiHint(),noteRouterModel(false)).onClick(v->noteAiSheet()));
        // Idioma del audio (0.9.0): detección automática o uno de los tres idiomas de la app, con su nombre en el idioma de la
        // app («Spanish» en inglés). Sin elegir, el de la app (Settings.language).
        String audio=settings.language();
        add(ai,row(R.drawable.ic_translate,getString(R.string.set_audio_lang_title),null,audio.isEmpty()?getString(R.string.set_audio_lang_auto):audioLangName(audio)).onClick(v->audioLanguageSheet()));
        // Explicación honesta de qué viaja y a dónde (SPEC-0.8b): grabar no usa internet, transcribir sí.
        Ui.Btn how=ui.button(getString(R.string.set_how_key),0,Ui.Style.PLAIN,v->howToKey());how.setMinimumHeight(ui.dp(48));
        page.addView(more(getString(R.string.set_ai_foot),getString(R.string.set_section_ai),getString(R.string.set_ai_more),how));

        // 3. Energía y red: cuándo se envía el audio.
        page.addView(ui.section(getString(R.string.set_section_energy)));LinearLayout energy=ui.group();page.addView(energy,Ui.fill());
        // «Solo Wi-Fi» viene activado y, con datos móviles, la transcripción espera sin que se note (diagnóstico del
        // 2026-10-01: 27 min esperando Wi-Fi). Desde la tercera ronda de la 0.8.0, cada transcripción que espera ofrece
        // «Usar datos móviles ahora» (en su detalle y en la notificación), solo para esa grabación: la fila lo dice, así
        // no hace falta cambiar este ajuste para salir del paso.
        boolean wifi=settings.wifiOnly();
        add(energy,row(R.drawable.ic_wifi,getString(R.string.set_network_title),wifi?getString(R.string.set_network_sub):null,getString(wifi?R.string.set_network_wifi:R.string.set_network_any)).onClick(v->
            sheet(getString(R.string.set_network_title),getString(R.string.set_network_sheet))
                .choice(getString(R.string.set_network_wifi),getString(R.string.set_network_wifi_sub),wifi,()->{settings.prefs.edit().putBoolean("wifi",true).apply();changed("wifi");})
                .choice(getString(R.string.set_network_any_long),getString(R.string.set_network_any_sub),!wifi,()->{settings.prefs.edit().putBoolean("wifi",false).apply();changed("wifi");}).show()));
        add(energy,toggleRow(R.drawable.ic_battery,getString(R.string.set_charging),null,settings.charging(),on->toggle("charging",on)));
        batteryRow=row(R.drawable.ic_battery,getString(R.string.set_locked),null,batteryValue());batteryRow.onClick(v->RecordingActions.allowBackground(this));add(energy,batteryRow);
        page.addView(more(getString(R.string.set_energy_foot),getString(R.string.set_section_energy),getString(R.string.set_energy_more)));

        // 4. Copias
        page.addView(ui.section(getString(R.string.set_section_copies)));LinearLayout storage=ui.group();page.addView(storage,Ui.fill());
        folderRow=row(R.drawable.ic_folder,getString(R.string.set_copies_title),null,getString(R.string.set_copies_off));folderRow.onClick(v->folderSheet());add(storage,folderRow);refreshFolder();
        page.addView(more(getString(R.string.set_copies_foot),getString(R.string.set_copies_title),getString(R.string.set_copies_more)));

        // 5. Apariencia: el tema se elige mirando cómo queda. «Colores de tu fondo de pantalla» se quitó en 0.7.0: Verbapp
        // tiene su propio verde (AppTheme.dynamicColor ya devuelve false) y la preferencia antigua queda guardada, sin leerse.
        page.addView(ui.section(getString(R.string.set_section_appearance)));page.addView(themePicker(),Ui.fill());
        page.addView(ui.footnote(getString(R.string.set_theme_foot)));

        // 6. Ayuda y soporte
        page.addView(ui.section(getString(R.string.set_section_help)));LinearLayout help=ui.group();page.addView(help,Ui.fill());
        String version=versionName();
        Ui.Row news=row(R.drawable.ic_info,getString(R.string.set_news),null,version).onClick(v->Novedades.showAll(this));versionPill(news.value);add(help,news);
        // 0.8.0: la bienvenida de la primera instalación se puede volver a ver (no cambia claves ni ajustes ya guardados).
        add(help,row(R.drawable.ic_replay,getString(R.string.set_welcome),getString(R.string.set_welcome_sub),null).onClick(v->OnboardingActivity.open(this,true)));
        add(help,row(R.drawable.ic_people,getString(R.string.set_demo),getString(R.string.set_demo_sub),null).onClick(v->startActivity(new Intent(this,RecordingActivity.class).putExtra("demo",true))));
        // 0.9.0: la política de privacidad (Google Play), en el idioma de la app, junto al informe y los registros.
        add(help,row(R.drawable.ic_open_in_new,getString(R.string.privacy_policy),null,null).onClick(v->Consent.openPolicy(this)));
        add(help,row(R.drawable.ic_lifebuoy,getString(R.string.set_report),getString(R.string.set_report_sub),null).onClick(v->report()));
        add(help,row(R.drawable.ic_trash,getString(R.string.set_clear_logs),null,null).onClick(v->confirm(getString(R.string.set_clear_logs_q),getString(R.string.set_clear_logs_body),getString(R.string.set_delete),true,()->{Diagnostics.clear(this);toast(getString(R.string.set_clear_logs_done));})));
        page.addView(ui.footnote(getString(R.string.set_logs_foot)));
        page.addView(footer(version),Ui.fill());
        lastShown=shown();
    }
    /** Nombre de un idioma de la app en ese mismo idioma («English», «Español», «Português (Brasil)»). */
    private String langName(String lang){return getString(Lang.EN.equals(lang)?R.string.lang_en:Lang.ES.equals(lang)?R.string.lang_es:R.string.lang_pt);}
    /**
     * Idioma de la app (0.9.0): los tres con su nombre propio y el actual marcado. Elegir otro lo guarda y rehace la
     * pantalla (Lang.set; en Android 13+ lo hace el sistema, con todas las pantallas abiertas): onCreate vuelve a armar
     * Ajustes entera en el idioma nuevo y Screen la deja en el mismo punto del desplazamiento.
     */
    private void appLanguageSheet(){
        String current=Lang.current(this);Sheet s=sheet(getString(R.string.common_language),getString(R.string.set_app_lang_body));
        for(String lang:Lang.SUPPORTED)s.choice(langName(lang),null,lang.equals(current),()->{Diagnostics.event("setting_changed",null,"action","app_language","result",lang);Lang.set(this,lang);});
        s.show();
    }
    /** Nombre de un idioma del audio en el idioma de la app («Spanish» en inglés, «Español» en español); otro código, tal cual. */
    private String audioLangName(String lang){
        return Lang.EN.equals(lang)?getString(R.string.set_audio_lang_en):Lang.ES.equals(lang)?getString(R.string.set_audio_lang_es):Lang.PT.equals(lang)?getString(R.string.set_audio_lang_pt):lang;
    }
    /**
     * «Idioma del audio»: detección automática o inglés, español o portugués (los de la app). Se guarda el código que va
     * al modelo ("" = que detecte); sin elegir, Settings.language usa el idioma de la app.
     */
    private void audioLanguageSheet(){
        String current=settings.language();Sheet s=sheet(getString(R.string.set_audio_lang_title),getString(R.string.set_audio_lang_hint));
        s.choice(getString(R.string.set_audio_lang_detect),getString(R.string.set_audio_lang_detect_sub),current.isEmpty(),()->set("language",""));
        for(String lang:Lang.SUPPORTED)s.choice(audioLangName(lang),null,lang.equals(current),()->set("language",lang));
        s.show();
    }

    /**
     * Tarjeta de marca y estado (0.7.0). Arriba, el logo con el lema y unas ondas decorativas (las mismas barras grises que
     * rodean el micrófono en Grabar); abajo, un panel que responde «¿está funcionando?», con las mismas acciones de 0.6:
     * - listo: panel blanco con ✓ en verde de marca (no se toca: no hay nada que hacer);
     * - falta un paso (la clave de OpenRouter, o cargar saldo): panel menta con una flecha de tinta, que lleva a resolverlo;
     * - falló la última comprobación: panel en tono de error, que muestra qué pasó y permite reintentar.
     * El panel va a 8 dp del borde con esquinas de 16 dp: concéntrico con la tarjeta (24 dp), como los datos de «Tu semana».
     * Solo OpenRouter (0.8.0, segunda ronda): a quien venía de OpenAI o de su servidor, sin clave de OpenRouter todavía,
     * se le dice con palabras que la app cambió y que su clave vieja ya no se usa.
     */
    private View statusCard(){
        // Sin saldo (o una cuenta que nunca cargó créditos): la clave vale, pero no transcribe. La tarjeta no puede decir
        // «Listo» mientras «Comprobar conexión» dice «sin saldo» (hallazgo de la revisión).
        boolean hasKey=settings.hasOpenRouterKey(),failed=hasKey&&verifyFailed(),broke=hasKey&&!failed&&(noBalance()||noCredits()),ready=hasKey&&!failed&&!broke;
        String old=hasKey?null:settings.oldService();
        LinearLayout card=ui.card();card.setPadding(ui.dp(S2),ui.dp(S2),ui.dp(S2),ui.dp(S2));
        LinearLayout head=ui.row();head.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S4));
        LinearLayout brand=ui.column();brand.addView(ui.brand(22),Ui.wrap());
        TextView motto=ui.text(getString(R.string.set_motto),Type.ITEM,p.onSurfaceVariant);motto.setPadding(0,ui.dp(S1),0,0);brand.addView(motto,Ui.wrap());
        head.addView(brand,Ui.wrap());
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,ui.dp(40),1);wp.setMarginStart(ui.dp(S4));head.addView(new WaveDeco(this,p.waveIdle),wp);
        card.addView(head,Ui.fill());

        int icon=ready?R.drawable.ic_check:failed||broke?R.drawable.ic_alert:R.drawable.ic_key,panel,fg,fg2,dotFg,dotBg;
        // Listo: blanco con alfa, el mismo panel de los datos de «Tu semana» (Ui.stat), un poco más claro que el vidrio de la tarjeta.
        if(ready){panel=p.dark?p.glass:0xB3FFFFFF;fg=p.onSurface;fg2=p.onSurfaceVariant;dotFg=p.onBrand;dotBg=p.brand;}
        else if(failed){panel=p.errorContainer;fg=fg2=p.onErrorContainer;dotFg=p.error;dotBg=p.surfaceContainerLowest;}
        else{panel=p.primaryContainer;fg=fg2=p.onPrimaryContainer;dotFg=p.onPrimaryContainer;dotBg=p.surfaceContainerLowest;}
        // Una clave rechazada se dice como en la bienvenida y en Grabar (keyRejected), no como un problema de conexión.
        boolean rejected=failed&&keyRejected(settings);
        String title=getString(ready?R.string.set_status_ready:rejected?R.string.set_status_check_key:failed?R.string.set_status_failed:broke?R.string.set_status_no_balance:old!=null?R.string.set_status_now_openrouter:R.string.set_status_one_step);
        String detail=ready?modelSummary(settings):rejected?getString(R.string.set_status_rejected_detail):failed?getString(R.string.set_status_failed_detail)
            :broke?getString(noCredits()?R.string.set_status_no_credits_detail:R.string.set_status_no_balance_detail)
            :old!=null?getString(R.string.set_status_old_detail,old)
            :getString(R.string.set_status_add_key_detail);
        LinearLayout status=ui.row();status.setMinimumHeight(ui.dp(72));status.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));
        status.addView(ui.tile(icon,dotFg,dotBg,40,22));status.addView(ui.space(S3));
        LinearLayout t=ui.column();t.addView(ui.text(title,Type.TITLE_MEDIUM,fg));TextView d=ui.text(detail,Type.BODY_MEDIUM,fg2);d.setPadding(0,ui.dp(2),0,0);t.addView(d);status.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        GradientDrawable fill=shape(this,panel,R_CONTROL);
        if(ready)status.setBackground(fill);
        else{
            // Flecha de tinta: la llamada a configurar. El panel entero es el botón, como la tarjeta de 0.6.
            FrameLayout go=ui.tile(R.drawable.ic_arrow_back,p.onInk,p.ink,36,20);go.getChildAt(0).setRotation(180);
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(36),ui.dp(36));gp.setMarginStart(ui.dp(S3));status.addView(go,gp);
            status.setBackground(ui.ripple(fill,R_CONTROL));status.setClickable(true);status.setFocusable(true);status.setAccessibilityDelegate(Ui.buttonRole());status.setContentDescription(title+". "+detail);
            status.setOnClickListener(v->{if(failed)verifyError(settings.prefs.getString("verifyMsg",""));else if(broke)creditsSheet();else keySheet();});Ui.pressable(status);
        }
        card.addView(status,Ui.fill());
        return card;
    }
    /** Sin saldo: qué pasa, cómo cargar créditos (abre openrouter.ai) y volver a comprobar. Todo aviso trae su salida. */
    private void creditsSheet(){
        Sheet s=sheet(getString(R.string.set_status_no_balance),getString(noCredits()?R.string.set_credits_none_body:R.string.set_credits_empty_body));
        s.primary(getString(R.string.set_add_credits),()->browse(CREDITS_URL)).secondary(getString(R.string.set_check_again),this::verify).show();
    }
    /** Abre una página de OpenRouter en el navegador; si no hay navegador, dice a dónde ir. */
    private void browse(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(ActivityNotFoundException|SecurityException e){message(getString(R.string.set_no_browser),getString(R.string.set_no_browser_body));}
    }
    /** «¿Cómo consigo una clave?»: los mismos pasos de la bienvenida y el botón que abre openrouter.ai/keys. */
    private void howToKey(){
        Sheet s=sheet(getString(R.string.set_how_key_title),getString(R.string.set_how_key_body));
        String[] steps={getString(R.string.set_how_key_step1),getString(R.string.set_how_key_step2),getString(R.string.set_how_key_step3),getString(R.string.set_how_key_step4)};
        for(int i=0;i<steps.length;i++){
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(ui.dp(S1),ui.dp(S2),0,ui.dp(S2));
            TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onPrimaryContainer);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.primaryContainer));n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.addView(n,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);
        }
        s.primary(getString(R.string.set_open_openrouter),()->browse(KEYS_URL)).secondary(getString(R.string.set_close),null).show();
    }

    // ---------- Tus métricas (0.8.0) ----------
    /** Resumen de Metrics.summaryLine: null mientras se calcula; "" si no hay nada que contar todavía. */
    private String metricsLine;private boolean metricsLoading;
    private View metricsCard;private TextView metricsBig,metricsRest;
    /**
     * Tarjeta «Tus métricas» (SPEC-0.8b): vidrio, la cifra grande y el resto del resumen. Metrics.summaryLine entrega una
     * línea como «12 h grabadas · US$3,40 este mes»: lo que va antes del primer « · » es la cifra grande y lo demás va
     * debajo. Toda la tarjeta es un botón que abre la pantalla completa; una flecha en menta lo dice sin otra tinta.
     */
    private View metricsCard(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S3),ui.dp(S4));card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts=ui.column();
        LinearLayout head=ui.row();head.addView(ui.icon(R.drawable.ic_speed,p.primary,16));head.addView(ui.space(S2));head.addView(ui.text(getString(R.string.metrics_title),Type.TITLE_SMALL,p.onSurface));texts.addView(head);
        metricsBig=Ui.tabular(ui.text("",Type.HEADLINE_SMALL,p.onSurface));metricsBig.setPadding(0,ui.dp(S2),0,0);texts.addView(metricsBig);
        metricsRest=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);metricsRest.setPadding(0,ui.dp(2),0,0);texts.addView(metricsRest);
        card.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout go=ui.tile(R.drawable.ic_arrow_back,p.primary,p.primaryContainer,36,20);go.getChildAt(0).setRotation(180);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(36),ui.dp(36));gp.setMarginStart(ui.dp(S3));card.addView(go,gp);
        card.setBackground(ui.ripple(glass(this,p,R_CARD),R_CARD));card.setClickable(true);card.setFocusable(true);card.setAccessibilityDelegate(Ui.buttonRole());
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",getClass().getSimpleName(),"action","Tus métricas");MetricsActivity.open(this);});Ui.pressable(card);
        metricsCard=card;paintMetrics();
        return card;
    }
    /** Pone el resumen en la tarjeta. Mientras se calcula, la cifra guarda su lugar (sin saltos al llegar). */
    private void paintMetrics(){
        if(metricsCard==null)return;String line=metricsLine;
        if(line==null){metricsBig.setText("—");metricsBig.setVisibility(View.INVISIBLE);metricsRest.setText(getString(R.string.set_metrics_loading));metricsRest.setVisibility(View.VISIBLE);}
        else if(line.isEmpty()){metricsBig.setVisibility(View.GONE);metricsRest.setText(getString(R.string.set_metrics_empty));metricsRest.setVisibility(View.VISIBLE);}
        else{
            int cut=line.indexOf(" · ");String big=cut>0?line.substring(0,cut):line,rest=cut>0?line.substring(cut+3).trim():"";
            metricsBig.setText(big);metricsBig.setVisibility(View.VISIBLE);metricsRest.setText(rest);metricsRest.setVisibility(rest.isEmpty()?View.GONE:View.VISIBLE);
        }
        metricsCard.setContentDescription(line==null?getString(R.string.set_metrics_desc_loading):line.isEmpty()?getString(R.string.set_metrics_desc_empty):getString(R.string.set_metrics_desc_line,line));
    }
    /** Calcula el resumen fuera del hilo principal (lee todas las grabaciones). Una sola cuenta a la vez. */
    private void loadMetrics(){
        if(metricsLoading)return;metricsLoading=true;Context app=getApplicationContext();
        stats.execute(()->{String line;try{line=Metrics.summaryLine(app);}catch(Exception e){line="";}
            String l=line==null?"":line.trim();
            runOnUiThread(()->{metricsLoading=false;if(isDestroyed())return;metricsLine=l;paintMetrics();});});
    }
    private String versionName(){String v=Novedades.versionName(this);if(!v.isEmpty())return v;try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void set(String key,String value){settings.prefs.edit().putString(key,value).apply();changed(key);}
    private void toggle(String key,boolean on){settings.prefs.edit().putBoolean(key,on).apply();Diagnostics.event("setting_changed",null,"action",key,"result",on);reschedule(key);}
    private void changed(String key){Diagnostics.event("setting_changed",null,"action",key);reschedule(key);render();}
    /**
     * Vuelve a programar lo pendiente con los ajustes nuevos. «Red para enviar audio» y «Solo mientras carga» son
     * condiciones de la transferencia ya pedida: Pipeline.settingsChanged la reprograma con las nuevas (si no, seguía
     * esperando el Wi-Fi o el cargador de antes y, por 2 minutos, ningún otro camino la empezaba) y quita el aviso
     * «Esperando Wi-Fi» si ya no corresponde. Ajustes está a la vista: Android acepta reprogramarla.
     */
    private void reschedule(String key){if("wifi".equals(key)||"charging".equals(key))Pipeline.settingsChanged(this);else Pipeline.schedule(this,true);}
    private static void subtitle(View row,String text){if(row instanceof Ui.Row)((Ui.Row)row).setSubtitle(text==null?"":text);}
    /** Muestra u oculta una fila de grupo junto con su divisor. */
    private static void showRow(View row,boolean visible){
        int v=visible?View.VISIBLE:View.GONE;row.setVisibility(v);
        if(row.getParent() instanceof ViewGroup){ViewGroup g=(ViewGroup)row.getParent();int i=g.indexOfChild(row);if(i>0)g.getChildAt(i-1).setVisibility(v);}
    }
    /**
     * Pie corto de un grupo + «Más información», que abre la explicación completa en una hoja. extra: otros enlaces de
     * texto que van antes, en la misma línea si caben (p. ej. «¿Cómo consigo una clave?»); con letra grande bajan de línea.
     */
    private View more(String text,String title,String detail,Ui.Btn... extra){
        LinearLayout box=ui.column();box.addView(ui.footnote(text));
        Ui.Btn b=ui.button(getString(R.string.set_more_info),0,Ui.Style.PLAIN,v->message(title,detail));b.setMinimumHeight(ui.dp(48));b.setContentDescription(getString(R.string.set_more_info_about,title));
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginStart(ui.dp(S1));
        if(extra.length==0){box.addView(b,lp);return box;}
        Flow links=new Flow(this,0,0);for(Ui.Btn x:extra)links.addView(x);links.addView(b);box.addView(links,lp);return box;
    }
    /** Pie con la marca: el logo, la versión con la licencia de la app y, aparte, la de la letra Outfit (OFL). */
    private View footer(String version){
        LinearLayout f=ui.column();f.setGravity(Gravity.CENTER_HORIZONTAL);f.setPadding(ui.dp(S4),ui.dp(S10),ui.dp(S4),ui.dp(S2));
        f.addView(new Glass.BrandMark(this,p.onSurfaceVariant,p.primary),new LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)));
        TextView app=ui.text(getString(R.string.set_footer_app,"Verbapp"+(version.isEmpty()?"":" "+version)),Type.LABEL_LARGE,p.onSurfaceVariant);app.setGravity(Gravity.CENTER);app.setPadding(0,ui.dp(S2),0,0);f.addView(app,Ui.fill());
        TextView font=ui.text(getString(R.string.set_footer_font),Type.LABEL_MEDIUM,p.onSurfaceVariant);font.setGravity(Gravity.CENTER);font.setPadding(0,ui.dp(2),0,0);f.addView(font,Ui.fill());
        return f;
    }

    // ---------- Piezas propias de Ajustes (0.7.0) ----------
    /** Diámetro del círculo menta que lleva el ícono de cada fila. */
    private static final int LEAD=36;
    /**
     * Fila de lista del kit con su ícono en un círculo menta (verde de marca sobre menta), como los íconos en círculo de la
     * referencia: da color y ritmo a los grupos de vidrio sin competir con los valores. Solo cambia el ícono inicial.
     */
    private Ui.Row row(int icon,String title,String subtitle,String value){Ui.Row r=ui.listRow(0,title,subtitle,value);lead(r,icon);return r;}
    private Ui.SwitchRow toggleRow(int icon,String title,String subtitle,boolean initial,Ui.Toggle toggle){Ui.SwitchRow r=ui.switchRow(0,title,subtitle,initial,toggle);lead(r,icon);return r;}
    private void lead(LinearLayout row,int icon){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(LEAD),ui.dp(LEAD));lp.setMarginEnd(ui.dp(S3));row.addView(ui.tile(icon,p.primary,p.primaryContainer,LEAD,20),0,lp);}
    /** Agrega una fila al grupo; el divisor empieza donde empieza el texto (16 de margen + 36 del círculo + 12). */
    private void add(LinearLayout group,View row){if(group.getChildCount()>0)group.addView(ui.separator(S4+LEAD+S3));group.addView(row,Ui.fill());}
    /** La versión como píldora menta con cifras fijas (en vez de texto gris), igual que en el historial de versiones. */
    private void versionPill(TextView value){
        AppTheme.type(value,Type.LABEL_MEDIUM);Ui.tabular(value);value.setTextColor(p.onPrimaryContainer);value.setBackground(shape(this,p.primaryContainer,R_FULL));
        value.setPadding(ui.dp(S2),ui.dp(S1),ui.dp(S2),ui.dp(S1));if(value.getLayoutParams() instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)value.getLayoutParams()).setMarginStart(ui.dp(S2));
    }
    /** Panel claro sobre una hoja blanca (el «vidrio» de las hojas): gris verdoso muy suave; en oscuro, un tono más claro que la hoja. */
    private GradientDrawable inset(int radius){return shape(this,p.dark?p.surfaceContainerHigh:p.surfaceContainerLow,radius);}

    /**
     * Tema con miniaturas (0.7.0): Automático, Claro y Oscuro dibujados con sus colores reales, así se ve el resultado
     * antes de elegir. Reemplaza la fila «Tema» y su hoja: tocar una guarda la preferencia y rehace la pantalla, igual que
     * antes (la elegida no hace nada). Cada miniatura es un botón que anuncia su nombre y si está elegida.
     */
    private View themePicker(){
        String mode=AppTheme.appearance(this);String[] modes={"system","light","dark"};
        String[] names={getString(R.string.set_theme_auto),getString(R.string.set_theme_light),getString(R.string.set_theme_dark)};
        String[] details={getString(R.string.set_theme_auto_detail),getString(R.string.set_theme_light_detail),getString(R.string.set_theme_dark_detail)};
        Palette light=new Palette(this,false,false),dark=new Palette(this,true,false);
        LinearLayout card=ui.card();card.setOrientation(LinearLayout.HORIZONTAL);card.setPadding(ui.dp(S2),ui.dp(S3),ui.dp(S2),ui.dp(S2));
        for(int i=0;i<modes.length;i++){
            int k=i;boolean on=modes[i].equals(mode);
            LinearLayout tile=ui.column();tile.setGravity(Gravity.CENTER_HORIZONTAL);tile.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),ui.dp(S2));
            tile.addView(new ThemePreview(this,p,light,dark,i,on),new LinearLayout.LayoutParams(-1,ui.dp(112)));
            // Con letra grande «Automático» no cabe en un tercio: se achica en vez de cortarse. El piso es 7 sp porque con
            // la letra al 200 % 10 sp todavía no cabían (unos 105 dp de texto en 93 dp) y, cuando ningún tamaño cabe, el
            // texto se parte dentro de la palabra («Automáti»). Al 200 % elige 8 sp, que se ven como 16 sp normales. Los
            // puntos suspensivos quedan de respaldo para pantallas aún más angostas (sin setSingleLine, que anula el ajuste).
            TextView name=ui.text(names[i],Type.LABEL_LARGE,on?p.primary:p.onSurface);name.setGravity(Gravity.CENTER);name.setMaxLines(1);name.setIncludeFontPadding(false);
            name.setAutoSizeTextTypeUniformWithConfiguration(7,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,ui.dp(24));np.topMargin=ui.dp(S2);tile.addView(name,np);
            tile.setBackground(ui.ripple(null,R_CONTROL));tile.setClickable(true);tile.setFocusable(true);tile.setAccessibilityDelegate(Ui.buttonRole());
            tile.setContentDescription(getString(R.string.set_theme_desc,names[i].toLowerCase(Lang.locale(this)),details[i])+(on?", "+getString(R.string.set_selected):""));
            tile.setOnClickListener(v->{if(on)return;Ui.haptic(v,Ui.Haptic.CONFIRM);settings.prefs.edit().putString("appearance",modes[k]).apply();Diagnostics.event("setting_changed",null,"action","appearance","result",k);recreate();});
            Ui.pressable(tile);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.setMarginStart(ui.dp(S1));card.addView(tile,lp);
        }
        return card;
    }

    /**
     * Miniatura de Verbapp con un tema: el degradado blanco → verde de Grabar, el título, una tarjeta de vidrio, el botón
     * verde de grabar y la barra flotante con su píldora de tinta, con los colores reales de ese tema. «Automático» es mitad
     * clara y mitad oscura. La elegida lleva un anillo verde y un ✓. Es solo dibujo: el botón que la contiene la describe.
     * Medidas en una grilla de 80 × 100 que se escala al tamaño real; si la caja es mucho más ancha que alta (teléfono en
     * horizontal, tableta), el dibujo no se estira más: queda centrado sobre el degradado (ver mock).
     */
    private static final class ThemePreview extends View {
        private final Palette current,light,dark;private final int mode;private final boolean selected;private final float d;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF box=new RectF(),r=new RectF();private final Drawable check;
        private LinearGradient lightBg,darkBg;
        ThemePreview(Context c,Palette current,Palette light,Palette dark,int mode,boolean selected){
            super(c);this.current=current;this.light=light;this.dark=dark;this.mode=mode;this.selected=selected;d=c.getResources().getDisplayMetrics().density;
            check=c.getDrawable(R.drawable.ic_check).mutate();check.setTint(current.onPrimary);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
            super.onSizeChanged(w,h,oldW,oldH);float inset=4*d;box.set(inset,inset,w-inset,h-inset);lightBg=gradient(light);darkBg=gradient(dark);
        }
        /** El mismo reparto del fondo intenso de Grabar (Glass.Backdrop): blanco arriba y verde desde la mitad. */
        private LinearGradient gradient(Palette q){return new LinearGradient(0,box.top,0,box.bottom,new int[]{q.gradTop,q.gradTop,q.gradMid,blend(q.gradMid,q.gradBottom,0.55f),q.gradBottom},new float[]{0f,0.16f,0.44f,0.72f,1f},Shader.TileMode.CLAMP);}
        @Override protected void onDraw(Canvas canvas){
            if(box.isEmpty()||lightBg==null)return;float radius=14*d;
            mock(canvas,mode==2?dark:light,mode==2?darkBg:lightBg,radius);
            if(mode==0){canvas.save();canvas.clipRect(box.centerX(),0,getWidth(),getHeight());mock(canvas,dark,darkBg,radius);canvas.restore();}
            // Borde fino: separa la miniatura clara de la tarjeta de vidrio, que también es clara.
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1f,d));paint.setColor(current.outlineVariant);canvas.drawRoundRect(box,radius,radius,paint);
            if(selected){
                paint.setStrokeWidth(2*d);paint.setColor(current.primary);r.set(d,d,getWidth()-d,getHeight()-d);canvas.drawRoundRect(r,radius+3*d,radius+3*d,paint);
                float cx=box.right-12*d,cy=box.top+12*d;paint.setStyle(Paint.Style.FILL);paint.setColor(current.surfaceContainerLowest);canvas.drawCircle(cx,cy,10.5f*d,paint);
                paint.setColor(current.primary);canvas.drawCircle(cx,cy,9*d,paint);
                int half=Math.round(6*d);check.setBounds(Math.round(cx)-half,Math.round(cy)-half,Math.round(cx)+half,Math.round(cy)+half);check.draw(canvas);
            }
        }
        private void mock(Canvas c,Palette q,LinearGradient bg,float radius){
            // Los círculos y radios usan ux y las posiciones verticales uy: si el ancho manda sin tope (en horizontal ux
            // llega a casi 3 veces uy), el botón de grabar pisa la tarjeta y la barra, y el micrófono queda aplastado.
            // Por eso ux no pasa de 1,3 × uy (lo máximo de un teléfono en vertical, donde no cambia nada) y el dibujo se
            // centra en la caja; el fondo y el borde siguen ocupando la caja completa.
            float uy=box.height()/100f,ux=Math.min(box.width()/80f,1.3f*uy),x=box.left+(box.width()-80*ux)/2f,y=box.top;
            paint.setStyle(Paint.Style.FILL);paint.setShader(bg);c.drawRoundRect(box,radius,radius,paint);paint.setShader(null);
            // Título y bajada.
            bar(c,q.onSurface,x+8*ux,y+11*uy,30*ux,5*uy);bar(c,withAlpha(q.onSurfaceVariant,150),x+8*ux,y+20*uy,20*ux,3.5f*uy);
            // Tarjeta de vidrio con un círculo menta y dos líneas de texto.
            r.set(x+6*ux,y+29*uy,x+74*ux,y+53*uy);paint.setColor(q.glass);c.drawRoundRect(r,7*ux,7*ux,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1f,0.8f*d));paint.setColor(withAlpha(q.outlineVariant,190));c.drawRoundRect(r,7*ux,7*ux,paint);paint.setStyle(Paint.Style.FILL);
            paint.setColor(q.primaryContainer);c.drawCircle(x+15*ux,y+41*uy,4.5f*ux,paint);
            bar(c,withAlpha(q.onSurfaceVariant,130),x+23*ux,y+37.5f*uy,30*ux,3*uy);bar(c,withAlpha(q.onSurfaceVariant,90),x+23*ux,y+42.5f*uy,18*ux,3*uy);
            // Botón de grabar: verde de marca con halo.
            float mx=x+40*ux,my=y+68*uy;paint.setColor(withAlpha(q.brand,70));c.drawCircle(mx,my,11*ux,paint);paint.setColor(q.brand);c.drawCircle(mx,my,7.5f*ux,paint);
            paint.setColor(q.onBrand);r.set(mx-1.4f*ux,my-3.2f*uy,mx+1.4f*ux,my+1.6f*uy);c.drawRoundRect(r,1.4f*ux,1.4f*ux,paint);
            // Barra flotante: cápsula clara con la píldora de tinta del destino activo.
            r.set(x+20*ux,y+84*uy,x+60*ux,y+95*uy);paint.setColor(q.dark?q.surfaceContainerHigh:q.surfaceContainerLowest);c.drawRoundRect(r,r.height()/2f,r.height()/2f,paint);
            bar(c,q.ink,x+22*ux,y+86*uy,16*ux,7*uy);paint.setColor(q.onSurfaceVariant);c.drawCircle(x+45*ux,y+89.5f*uy,1.6f*ux,paint);c.drawCircle(x+53*ux,y+89.5f*uy,1.6f*ux,paint);
        }
        private void bar(Canvas c,int color,float left,float top,float w,float h){paint.setColor(color);r.set(left,top,left+w,top+h);c.drawRoundRect(r,h/2f,h/2f,paint);}
    }

    /**
     * Ondas decorativas: barras redondeadas de alto variable, más altas al centro y desvanecidas hacia los extremos (como
     * las que rodean el micrófono en Grabar). Son fijas y el lector de pantalla las ignora; setColor las tiñe (p. ej. de
     * verde mientras suena una muestra).
     */
    private static final class WaveDeco extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float d;private int color;
        WaveDeco(Context c,int color){super(c);d=c.getResources().getDisplayMetrics().density;this.color=color;paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(2.5f*d);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void setColor(int color){this.color=color;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),step=6*d;int n=(int)(w/step);if(n<4)return;
            float x0=(w-(n-1)*step)/2f,cy=h/2f,max=h/2f-1.5f*d;
            for(int i=0;i<n;i++){
                float env=(float)Math.sin(Math.PI*(i+0.5f)/n);
                // Alturas fijas pero irregulares, para que parezca voz y no un patrón.
                float noise=0.35f+0.65f*Math.abs((float)(Math.sin(i*1.9)*Math.cos(i*0.7+0.4)));
                float half=Math.max(1.25f*d,max*(0.3f+0.7f*env)*noise);
                paint.setColor(withAlpha(color,Math.round(255*(0.2f+0.8f*env))));
                canvas.drawLine(x0+i*step,cy-half,x0+i*step,cy+half,paint);
            }
        }
    }

    // ---------- Guardado rápido (0-Inbox) ----------
    private String inboxValue(){return settings.inboxTree().isEmpty()?getString(R.string.set_not_chosen):settings.prefs.getString("saveTreeName",getString(R.string.set_folder_chosen));}

    // ---------- Nota para tu segundo cerebro ----------
    private String noteSubtitle(boolean auto){return getString(auto?R.string.set_note_auto_on:R.string.set_note_auto_off);}
    /** ¿Se puede armar la nota? Hace falta la clave de OpenRouter, la misma con que se transcribe (Notes.canGenerate). */
    private boolean noteReady(){try{return Notes.canGenerate(this);}catch(Throwable t){return true;}}
    /**
     * Modelo de OpenRouter elegido para la nota: la posición de su alias en Models.NOTE_MODELS (0 = el recomendado, también
     * si no hay nada guardado o lo guardado no sirve) o -1 si es otro modelo escrito a mano, que Notes usa tal cual
     * (Notes.routerModel es la regla de los dos: aquí se muestra justo lo que se envía).
     */
    private int noteAlias(){String m=settings.noteModel().trim();int i=Arrays.asList(Models.NOTE_MODELS).indexOf(m);return i>=0?i:Notes.routerModel(m)?-1:0;}
    /** Nombre del modelo de la nota: la familia del alias («Claude Sonnet»; full, con «(el más nuevo)») o el id escrito a mano. */
    private String noteRouterModel(boolean full){int i=noteAlias();return i<0?settings.noteModel().trim():full?getString(R.string.set_note_model_latest,noteAliasName(i)):noteAliasName(i);}
    /** La familia («Claude Sonnet»): el nombre de Models.NOTE_NAMES sin lo que vaya entre paréntesis al final (0.9.0: «(el más nuevo)» sale de los textos de Ajustes, en el idioma de la app). */
    private static String noteAliasName(int i){return Models.NOTE_NAMES[i].replaceAll(" \\(.*\\)$","");}
    /** Bajo el modelo de la nota: con un alias, que no hay que actualizarlo; con uno escrito a mano, que es ese. */
    private String noteAiHint(){return getString(noteAlias()<0?R.string.set_note_ai_typed:R.string.set_note_ai_latest);}
    /** La nota quedó activada pero falta la clave de OpenRouter: se pide ahí mismo. */
    private void noteMissingKey(){keyInput();}
    /**
     * «IA de la nota» (0.8.0, segunda ronda: solo OpenRouter). Se elige una familia, no una versión: sus alias «-latest»
     * apuntan siempre a la más nueva y la nota muestra cuál respondió. El recomendado se guarda como vacío. Ya no se
     * elige entre OpenAI, Claude y OpenRouter: la nota va siempre por OpenRouter, con la misma clave.
     */
    private void noteAiSheet(){
        String[] details={getString(R.string.set_note_ai_best),getString(R.string.set_note_ai_fast),getString(R.string.set_note_ai_google)};int current=noteAlias();
        Sheet s=sheet(getString(R.string.set_note_ai_title),getString(R.string.set_note_ai_body)
            +(current<0?" "+getString(R.string.set_note_ai_now_typed,settings.noteModel().trim()):""));
        for(int i=0;i<Models.NOTE_MODELS.length;i++){int k=i;s.choice(getString(R.string.set_note_model_latest,noteAliasName(i)),i<details.length?details[i]:null,i==current,()->{
            SharedPreferences.Editor e=settings.prefs.edit();if(k==0)e.remove("noteModel");else e.putString("noteModel",Models.NOTE_MODELS[k]);e.apply();
            Diagnostics.event("setting_changed",null,"action","note_model","result",k==0?"default":"alias");render();});}
        // Otro modelo de OpenRouter, escrito a mano: un id «autor/modelo» o un alias con «~». Notes los acepta, así que la hoja también.
        s.action(R.drawable.ic_edit,getString(R.string.set_note_other_model),false,this::noteModelInput);
        if(!settings.hasOpenRouterKey())s.action(R.drawable.ic_key,getString(R.string.set_key_add),false,this::keyInput);
        s.show();
    }
    /**
     * Modelo de la nota escrito a mano: un id «autor/modelo» o un alias «~autor/familia-latest» (la regla es
     * Notes.routerModel, la misma con que Notes decide si lo envía). Vacío vuelve al recomendado.
     */
    private void noteModelInput(){
        boolean typed=noteAlias()<0;
        EditText model=ui.field(getString(R.string.set_note_model_hint),getString(R.string.set_note_model_desc));model.setText(typed?settings.noteModel().trim():"");model.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Sheet s=sheet(getString(R.string.set_note_other_title),getString(R.string.set_note_other_body)).add(model);
        s.primary(getString(R.string.set_save),Ui.Style.PRIMARY,()->{String m=model.getText().toString().trim();
            if(!m.isEmpty()&&!Notes.routerModel(m)){model.setError(getString(R.string.set_note_model_error));return false;}
            SharedPreferences.Editor e=settings.prefs.edit();if(m.isEmpty())e.remove("noteModel");else e.putString("noteModel",m);e.apply();
            Diagnostics.event("setting_changed",null,"action","note_model","result",m.isEmpty()?"default":"custom");render();return true;});
        if(typed)s.secondary(getString(R.string.set_note_use_recommended),()->{settings.prefs.edit().remove("noteModel").apply();Diagnostics.event("setting_changed",null,"action","note_model","result","default");render();});
        s.secondary(getString(R.string.common_cancel),null).show();
    }

    // ---------- Tu IA (OpenRouter): clave y separar voces ----------
    private void speakersSheet(){String current=settings.speakersMode();Sheet s=sheet(getString(R.string.set_speakers_title),getString(R.string.set_speakers_body));
        int[] details={R.string.set_speakers_ask_sub,R.string.set_speakers_always_sub,R.string.set_speakers_never_sub};
        for(int i=0;i<3;i++){String m=SPEAKER_MODES[i];s.choice(getString(SPEAKER_NAMES[i]),getString(details[i]),m.equals(current),()->set("speakersMode",m));}s.show();}
    /** La clave de OpenRouter: reemplazarla o eliminarla; sin clave, se pide. Nunca se muestra. */
    private void keySheet(){
        if(settings.hasOpenRouterKey()){sheet(getString(R.string.set_key_title),getString(R.string.set_key_sheet_body))
            .action(R.drawable.ic_edit,getString(R.string.set_key_replace),false,this::keyInput)
            .action(R.drawable.ic_trash,getString(R.string.set_key_delete),true,()->confirm(getString(R.string.set_key_delete_q),getString(R.string.set_key_delete_body),getString(R.string.set_remove),true,()->{
                try{settings.saveOpenRouterKey("");JobScheduler js=getSystemService(JobScheduler.class);js.cancel(Pipeline.JOB_ID);if(!Pipeline.working())js.cancel(Pipeline.USER_JOB_ID);Diagnostics.event("setting_changed",null,"action","api_key","result","deleted");render();}catch(Exception e){message(getString(R.string.set_key_word_title),getString(R.string.set_key_delete_failed));}})).show();return;}
        keyInput();
    }
    /**
     * Pide la clave de OpenRouter. Al guardarla se comprueba sola: el resultado queda en la fila, sin otro aviso que
     * cerrar. A quien venía de OpenAI (o de su servidor) se le explica primero por qué se le pide otra clave.
     */
    private void keyInput(){
        String old=settings.hasOpenRouterKey()?null:settings.oldService();
        secretInput(getString(R.string.set_key_add),(old!=null?getString(R.string.set_key_input_old,old)+"\n\n":"")+getString(R.string.set_key_input_body),
            "sk-or-…",getString(R.string.set_key_title),this::saveRouterKey,()->{Diagnostics.event("setting_changed",null,"action","api_key");Pipeline.schedule(this,true);render();page.post(this::verify);});
    }
    /**
     * Guarda la clave de OpenRouter y deja OpenRouter como servicio (por si quedó otro de antes: la app ya no usa otros).
     * Antes, la misma revisión que la bienvenida (OnboardingActivity.keyProblem): una clave cortada, con espacios o de otro
     * servicio (la de OpenAI que tenía quien actualiza, una sk-ant-) no se guarda, porque comprobarla y transcribir la
     * mandarían a openrouter.ai. El motivo va al campo (HttpApi.UserAction; nunca repite la clave).
     */
    private void saveRouterKey(String value)throws Exception{
        String problem=OnboardingActivity.keyProblem(value);if(problem!=null)throw new HttpApi.UserAction(problem);
        settings.saveOpenRouterKey(value);if(!settings.openRouter())VozApp.openRouterOnly(settings,settings.prefs.edit()).apply();
    }
    interface Secret{void save(String value)throws Exception;}
    /**
     * Hoja segura para pegar una clave: campo oculto, sin autocompletar ni capturas de pantalla. Debajo, «Pegar» (trae lo
     * copiado sin mostrarlo) y «¿Cómo consigo una clave?», como en la bienvenida: quien llega desde openrouter.ai con la
     * clave copiada la pega con un toque.
     * 0.9.0: si todavía no aceptó el aviso de envío (Consent, requisito de Google Play), «Guardar clave» lo muestra antes de
     * guardar: con «Acepto» se guarda y se cierra esta hoja; con «Ahora no» la hoja queda abierta, con lo pegado. Una clave
     * que no sirve se dice antes del aviso (no tiene sentido aceptarlo para después leer que la clave está cortada).
     */
    private void secretInput(String title,String message,String hint,String description,Secret secret,Runnable saved){
        EditText input=ui.field(hint,description);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Sheet s=sheet(title,message).add(input);
        // El campo va oculto: si lo pegado no sirve como clave de OpenRouter (otro servicio, cortado), se dice al tiro.
        Ui.Btn paste=ui.button(getString(R.string.set_paste),0,Ui.Style.PLAIN,v->{String copied=clipboard();if(copied.isEmpty()){input.setError(getString(R.string.set_paste_empty));return;}input.setError(null);input.setText(copied);input.setSelection(input.length());
            String problem=OnboardingActivity.keyProblem(copied);if(problem!=null){input.setError(problem);Ui.haptic(input,Ui.Haptic.REJECT);}});
        Ui.Btn how=ui.button(getString(R.string.set_how_key),0,Ui.Style.PLAIN,v->howToKey());
        // Enlaces de texto: su letra queda alineada con la del campo (se corren lo que mide el relleno del botón).
        Flow links=new Flow(this,0,0);for(Ui.Btn b:new Ui.Btn[]{paste,how}){b.setMinimumHeight(ui.dp(48));links.addView(b);}
        LinearLayout.LayoutParams lp=Ui.fill();lp.setMarginStart(-ui.dp(S3));lp.topMargin=ui.dp(S1);s.body.addView(links,lp);
        s.primary(getString(R.string.set_key_save),Ui.Style.PRIMARY,()->{
            if(input.length()==0){input.setError(getString(R.string.set_key_paste_prompt));return false;}
            if(Consent.given(this))return keep(input,secret,saved);
            String problem=OnboardingActivity.keyProblem(input.getText().toString());if(problem!=null){input.setError(problem);Ui.haptic(input,Ui.Haptic.REJECT);return false;}
            Consent.ensure(this,()->{if(keep(input,secret,saved))s.dismiss();});return false;})
            .secondary(getString(R.string.common_cancel),null).secure().show();
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    /**
     * Guarda lo pegado en la hoja de la clave: true si quedó guardado. Si la clave no sirve (secret lanza
     * HttpApi.UserAction), su motivo va al campo; si falló el guardado en el teléfono, eso.
     */
    private boolean keep(EditText input,Secret secret,Runnable saved){
        try{secret.save(input.getText().toString());input.setText("");saved.run();toast(getString(R.string.set_key_saved));return true;}
        catch(Exception e){input.setError(e instanceof HttpApi.UserAction&&e.getMessage()!=null?e.getMessage():getString(R.string.set_key_save_failed));Ui.haptic(input,Ui.Haptic.REJECT);return false;}
    }
    /** Texto copiado, sin espacios alrededor ("" si no hay o no se puede leer). Va directo al campo oculto: no se muestra ni se registra. */
    private String clipboard(){
        try{ClipboardManager cm=getSystemService(ClipboardManager.class);ClipData d=cm==null?null:cm.getPrimaryClip();
            if(d==null||d.getItemCount()==0)return "";CharSequence t=d.getItemAt(0).coerceToText(this);return t==null?"":t.toString().trim();}
        catch(RuntimeException e){return "";}
    }

    // ---------- Comprobar conexión (resultado en la misma fila, guardado en prefs) ----------
    /**
     * A qué configuración corresponde la última comprobación: si cambias proveedor, modelo o clave, deja de valer.
     * Es estático porque la bienvenida deja su comprobación en estas mismas preferencias (ver saveVerify).
     */
    private String verifyTarget(){return verifyTarget(settings);}
    static String verifyTarget(Settings settings){
        String provider=settings.provider();
        // OpenRouter: lo que se comprueba es la clave (GET /key), no el modelo. Cambiar de modelo no deja la comprobación vieja.
        if(settings.openRouter())return provider+"|"+settings.prefs.getString(settings.prefix()+"keyEncrypted","").hashCode();
        String what=provider.equals("openai")?settings.textModel()+"|"+settings.defaultSpeakers():settings.prefs.getString("customBase","")+"|"+settings.prefs.getString("customModel","")+"|"+settings.prefs.getBoolean("customSpeakers",false);
        return provider+"|"+what+"|"+settings.prefs.getString(settings.prefix()+"keyEncrypted","").hashCode();
    }
    /**
     * Guarda el resultado de comprobar la clave (preferencias verify*). Lo escriben «Comprobar conexión» y la bienvenida:
     * una clave comprobada al instalar ya aparece comprobada aquí. target: verifyTarget() al EMPEZAR la comprobación.
     * balance y free salen de Models.balance (Balance.left y Balance.noCredits: la cuenta aún no tiene créditos). El saldo
     * queda solo en las preferencias: nunca va al diagnóstico. Sin créditos (free) no se guarda ningún saldo: lo que
     * informa OpenRouter entonces es el tope de la clave, no plata disponible.
     */
    static void saveVerify(Settings settings,String target,boolean ok,long ms,String why,double balance,boolean free){
        settings.prefs.edit().putLong("verifyAt",System.currentTimeMillis()).putBoolean("verifyOk",ok).putLong("verifyMs",ms).putString("verifyFor",target).putString("verifyMsg",ok||why==null?"":why)
            .putString("verifyBalance",ok&&!free&&!Double.isNaN(balance)&&!Double.isInfinite(balance)?String.valueOf(balance):"").putBoolean("verifyFree",ok&&free).apply();
    }
    private boolean verifyValid(){return settings.prefs.getLong("verifyAt",0)>0&&verifyTarget().equals(settings.prefs.getString("verifyFor",""));}
    private boolean verifyFailed(){return verifyValid()&&!settings.prefs.getBoolean("verifyOk",true);}
    /**
     * La última comprobación (vigente) rechazó la clave: la regla de la bienvenida (OnboardingActivity.saved, un fallo que
     * habla de la clave), dicha igual aquí, en «Listo» y en Grabar. Antes la bienvenida decía «Revisa tu clave», Ajustes
     * «No se pudo conectar» (que suena a la red) y Grabar ✓ «Listo para transcribir».
     */
    static boolean keyRejected(SharedPreferences prefs,String target){
        OnboardingActivity.Verdict v=OnboardingActivity.saved(prefs,target);return v!=null&&v.state==OnboardingActivity.Check.REJECTED;
    }
    static boolean keyRejected(Settings settings){return settings.hasOpenRouterKey()&&keyRejected(settings.prefs,verifyTarget(settings));}
    /** La fila «Comprobar conexión» tras una comprobación que falló: la clave rechazada se nombra; lo demás es la conexión. */
    static String failedText(boolean rejected,long at){return Lang.str(rejected?R.string.set_verify_rejected_ago:R.string.set_verify_failed_ago,ago(at));}
    private String verifyText(){
        if(verifying)return getString(R.string.set_verify_checking);
        if(!settings.hasOpenRouterKey())return getString(R.string.set_verify_add_key_first);
        if(!verifyValid())return getString(R.string.set_verify_prompt);
        long at=settings.prefs.getLong("verifyAt",0);
        if(!settings.prefs.getBoolean("verifyOk",false))return failedText(keyRejected(settings),at);
        // OpenRouter dice cuánto queda: es el dato que sirve (con saldo en cero la clave vale, pero no transcribe).
        String what=balanceText(verifyBalance(),settings.prefs.getBoolean("verifyFree",false));
        return getString(R.string.set_verify_ok,what!=null?what:seconds(settings.prefs.getLong("verifyMs",0)),ago(at));
    }
    /** Bajo medio centavo ya no alcanza para nada: se muestra como «sin saldo». */
    static final double NO_BALANCE=0.005;
    /**
     * Lo que se dice del saldo al comprobar la clave: «quedan US$4,20», «sin saldo: …» y, si la cuenta nunca cargó
     * créditos, eso primero (el tope de la clave no es plata disponible; hallazgo de la revisión). null si no se supo.
     * Para Ajustes y la bienvenida: la misma clave dice lo mismo en los dos lugares.
     */
    static String balanceText(double left,boolean noCredits){
        if(noCredits)return Lang.str(R.string.set_balance_no_credits);
        if(Double.isNaN(left)||Double.isInfinite(left))return null;
        return left<=NO_BALANCE?Lang.str(R.string.set_balance_empty):Lang.str(R.string.set_balance_left,money(left));
    }
    /** Saldo (US$) que informó OpenRouter en la última comprobación; NaN si no lo informó. Queda solo en las preferencias: no va al diagnóstico. */
    private double verifyBalance(){try{return Double.parseDouble(settings.prefs.getString("verifyBalance",""));}catch(NumberFormatException e){return Double.NaN;}}
    /** La última comprobación (vigente) dio una clave válida, pero sin saldo. */
    private boolean noBalance(){return verifyValid()&&settings.prefs.getBoolean("verifyOk",false)&&verifyBalance()<=NO_BALANCE;}
    /** La última comprobación (vigente) dio una clave válida de una cuenta que aún no carga créditos. */
    private boolean noCredits(){return verifyValid()&&settings.prefs.getBoolean("verifyOk",false)&&settings.prefs.getBoolean("verifyFree",false);}
    private void paintVerify(){if(verifyRow==null)return;verifyRow.setSubtitle(verifyText());verifyRow.subtitle.setTextColor(!verifying&&(verifyFailed()||noBalance()||noCredits())?p.error:p.onSurfaceVariant);verifyRow.setEnabled(!verifying);}
    private void verify(){
        if(!settings.hasOpenRouterKey()){keySheet();return;}
        if(verifying)return;
        verifying=true;paintVerify();
        if(http!=null)http.cancel();HttpApi call=new HttpApi();http=call;String target=verifyTarget();
        // Son dos consultas cortas: si una no responde en 30 s, mejor decirlo que dejar «Comprobando…» por minutos.
        call.readTimeoutMs=30000;
        checks.execute(()->{
            // Si mientras esperaba su turno cambió la clave, esta comprobación ya no corresponde: se descarta sin enviar nada.
            if(call.cancelled||!target.equals(verifyTarget())){runOnUiThread(()->{if(http==call){verifying=false;paintVerify();}});return;}
            String reason=null;long started=SystemClock.elapsedRealtime(),ms=0;double balance=Double.NaN;boolean free=false;
            try{
                // GET /key dice si la clave vale y cuánto le queda a la clave (si tiene tope); el saldo de la cuenta se pide
                // aparte, sin exigirlo (Models.balance). La clave se lee por su nombre fijo (openrouter_), nunca la del
                // proveedor guardado en ese momento: así solo viaja a openrouter.ai la de OpenRouter (hallazgo de la revisión).
                String key=settings.openRouterKey();Models.KeyInfo info=Models.checkKey(call,key);ms=SystemClock.elapsedRealtime()-started;
                Models.Balance b=Models.balance(call,key,info);balance=b.left;free=b.noCredits;
            }catch(Exception e){ms=SystemClock.elapsedRealtime()-started;
                // OpenRouter respondió, pero no con un sí o un no sobre la clave (caído, límite de pedidos, algo inesperado):
                // Models.checkKey lo dice con su nombre y el código. No es la conexión del teléfono, así que se muestra eso.
                boolean answered=e instanceof java.io.IOException&&e.getMessage()!=null&&e.getMessage().startsWith(HttpApi.OPENROUTER);
                reason=e instanceof HttpApi.UserAction||answered?e.getMessage():getString(R.string.set_verify_no_internet);}
            if(call.cancelled)return; // se salió de Ajustes: no se guarda un fallo que no fue
            boolean ok=reason==null;String why=reason;long took=ms;
            saveVerify(settings,target,ok,took,why,balance,free);
            // El saldo es un dato de la cuenta: se muestra en la fila y no se registra.
            Diagnostics.event("setting_changed",null,"action","verify","result",ok,"elapsed_ms",took);
            runOnUiThread(()->{verifying=false;if(isDestroyed()||isFinishing())return;render();
                if(verifyRow!=null){Ui.haptic(verifyRow,ok?Ui.Haptic.CONFIRM:Ui.Haptic.REJECT);verifyRow.announceForAccessibility(getString(ok?R.string.set_valid_key:keyRejected(settings)?R.string.set_key_rejected:R.string.set_status_failed));}
                if(!ok)verifyError(why);
                // Con la clave recién comprobada se aprovecha de poner al día la lista de modelos (y lo que usa «Automático»).
                else if(Models.stale(this))loadCatalog();});
        });
    }
    /** Los errores siguen explicándose, con su salida: revisar la clave o reintentar. */
    private void verifyError(String reason){
        String why=reason==null||reason.isEmpty()?getString(R.string.set_verify_retry_hint):reason;
        // Un motivo que habla de la clave es un rechazo (la regla de OnboardingActivity.saved): se titula como tal.
        boolean key=StatusText.aboutKey(why);
        Sheet s=sheet(getString(key?R.string.set_status_check_key:R.string.set_status_failed),why);
        if(key)s.primary(getString(R.string.set_check_key),this::keySheet);else s.primary(getString(R.string.set_retry),this::verify);
        s.secondary(getString(R.string.set_close),null).show();
    }
    /** «0,9 s», con la coma o el punto del idioma (Lang.locale). */
    static String seconds(long ms){return String.format(Lang.locale(),"%.1f s",ms/1000.0);}
    /** «recién», «hace 5 min», «hace 2 h», «ayer», «hace 3 días» (en el idioma de la app). */
    static String ago(long at){
        long min=Math.max(0,System.currentTimeMillis()-at)/60000;
        if(min<1)return Lang.str(R.string.set_ago_now);if(min<60)return Lang.str(R.string.set_ago_min,min);
        long h=min/60;if(h<24)return Lang.str(R.string.set_ago_hours,h);
        long d=h/24;return d==1?Lang.str(R.string.set_ago_yesterday):Lang.plural(R.plurals.set_ago_days,(int)Math.min(d,Integer.MAX_VALUE));
    }

    // ---------- Modelos de OpenRouter (0.8.0) ----------
    private HttpApi catalogHttp;private boolean catalogLoading,catalogFailed;private ModelSheet modelSheet;
    /** «US$0,26»: precio por hora con dos decimales; bajo 10 centavos, con tres (0,036 no se redondea a «0,04»). Coma o punto según el idioma (Pricing.usd). */
    static String hourPrice(double perHour){return Pricing.usd(perHour,perHour>=0.095?2:3);}
    /** «US$4,20»: un saldo, siempre con dos decimales (coma o punto según el idioma). */
    static String money(double usd){return Pricing.usd(usd,2);}
    /** Precio del modelo para mostrar; con «≈» si no salió del catálogo sino de la referencia. Vacío si no se conoce. */
    private static String priceText(Models.Model m){return m==null||m.perHour<=0?"":(m.approx?"≈ ":"")+hourPrice(m.perHour);}
    /**
     * «15 de octubre» o, si es otro año, «15 ene 2027» (corto: va dentro de una píldora). La fecha es la que informa OpenRouter, en UTC: no se corre un día por la hora de Chile.
     * 0.9.0: el patrón sale de los textos de cada idioma («October 15», «15 de outubro») y los meses, de Lang.locale.
     */
    private String retireDate(long seconds){
        java.util.TimeZone utc=java.util.TimeZone.getTimeZone("UTC");java.util.Calendar now=java.util.Calendar.getInstance(utc),then=java.util.Calendar.getInstance(utc);then.setTimeInMillis(seconds*1000L);
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat(getString(now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)?R.string.set_date_day_month:R.string.set_date_short_year),Lang.locale(this));f.setTimeZone(utc);
        return f.format(then.getTime());
    }
    /**
     * «Hoy usa MAI Transcribe 2 · sin confirmar · US$0,36 por hora»: qué modelo usa hoy «Automático» (la fila y la tarjeta de
     * la hoja de modelos dicen lo mismo). Con voces, «sin confirmar» si su modo de separar voces aún no se confirma.
     */
    private String todayText(String id,String price,boolean speakers){
        String t=getString(R.string.set_model_today,Models.name(this,id));
        if(speakers&&!Models.recipe(id).verified)t+=" · "+getString(R.string.set_unconfirmed);
        return price.isEmpty()?t:t+" · "+getString(R.string.set_per_hour_price,price);
    }
    /**
     * Fila «Modelo con voces» / «Modelo solo texto»: a la derecha la elección («Automático» o el nombre) y debajo lo que
     * eso significa hoy: qué modelo usa Automático y cuánto cuesta la hora. Si el elegido a mano ya no está en la lista de
     * OpenRouter, la fila lo avisa en color de error con la salida (elegir otro).
     */
    private Ui.Row orModelRow(boolean speakers){
        String pick=speakers?settings.orSpeakersModel():settings.orTextModel(),id=Models.chosen(settings,speakers);boolean auto=pick.isEmpty()||Models.AUTO.equals(pick);
        List<Models.Model> saved=Models.cached(this);Models.Model m=Models.find(saved.isEmpty()?Models.builtin():saved,id);
        boolean gone=!auto&&m==null&&!saved.isEmpty();String price=priceText(m),name=Models.name(this,id);
        String sub=gone?getString(R.string.set_model_gone_pick):auto?todayText(id,price,speakers):price.isEmpty()?null:getString(R.string.set_per_hour_price,price);
        Ui.Row r=row(speakers?R.drawable.ic_chat:R.drawable.ic_wave,getString(speakers?R.string.set_model_voices:R.string.set_model_text),sub,auto?getString(R.string.set_automatic):name);
        if(gone)r.subtitle.setTextColor(p.error);
        return r.onClick(v->new ModelSheet(speakers).show());
    }
    /**
     * Lee la lista de modelos de OpenRouter en segundo plano (es pública: no viaja la clave). Una sola lectura a la vez;
     * si hay una hoja de modelos abierta, muestra ahí el avance, el resultado o el error. No se corta al cerrar la hoja:
     * es una consulta corta y conviene que termine, para que «Automático» quede al día.
     */
    private void loadCatalog(){
        if(catalogLoading)return;catalogLoading=true;catalogFailed=false;if(modelSheet!=null)modelSheet.paint(false);
        HttpApi call=new HttpApi();call.readTimeoutMs=30000;catalogHttp=call;
        io.execute(()->{boolean ok=true;try{Models.refresh(this,call);}catch(Exception e){ok=false;}
            boolean done=ok;runOnUiThread(()->{catalogLoading=false;if(isDestroyed()||isFinishing())return;catalogFailed=!done;
                // Las filas de Ajustes muestran lo que usa «Automático» y su precio: se rehacen con la lista nueva.
                if(done)render();
                ModelSheet open=modelSheet;if(open==null||!open.s.dialog.isShowing())return;
                if(done)open.fill();
                open.paint(done);Ui.haptic(open.again,done?Ui.Haptic.CONFIRM:Ui.Haptic.REJECT);});});
    }
    /**
     * Hoja de modelos de OpenRouter (0.8.0): la cara visible de «la lista que se actualiza sola». De arriba abajo:
     * - el estado de la lista: cuándo se actualizó, «Actualizar lista», la carga y el error con «Reintentar»;
     * - «Automático», destacado en menta con la etiqueta «Recomendado» y el modelo que usa hoy;
     * - los modelos que Verbapp conoce (su tabla: sabe cómo pedirlos) y, aparte, los que van llegando a OpenRouter («Nuevo · sin probar»).
     * Cada modelo se entiende de un vistazo: nombre y quién lo hace, una píldora que dice si separa voces (menta) o es solo
     * texto (gris) y, a la derecha, el precio por hora. Al abrirla con una lista de más de un día, se actualiza sola.
     * Sin lista guardada (primera vez o sin internet) se ofrecen los modelos conocidos, así siempre hay de dónde elegir.
     * Los textos no dicen «probado»: ningún modelo se probó todavía con audio real (hallazgo de la revisión).
     */
    private final class ModelSheet {
        final boolean speakers;final String key;final Sheet s;final LinearLayout strip,box;final TextView when;final Ui.Btn again;
        /** Etiqueta del botón que cabe junto al texto («Actualizar lista» o, en pantallas angostas, «Actualizar»); null si ninguna cabe. */
        private final String sideLabel;
        ModelSheet(boolean speakers){
            this.speakers=speakers;key=speakers?"orSpeakersModel":"orTextModel";
            s=sheet(getString(speakers?R.string.set_model_voices:R.string.set_model_text),getString(speakers?R.string.set_model_voices_body:R.string.set_model_text_body)).closable(null);
            when=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);when.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            String full=getString(R.string.set_list_refresh),brief=getString(R.string.set_refresh);
            again=ui.button(full,R.drawable.ic_refresh,Ui.Style.PLAIN,v->loadCatalog());again.setMinimumHeight(ui.dp(48));
            // El botón va al lado del texto solo si le deja al menos la mitad de la hoja; si «Actualizar lista» no cabe se
            // prueba «Actualizar» y, si tampoco (letra muy grande), el botón baja a su propia línea con el nombre completo.
            float half=(getResources().getDisplayMetrics().widthPixels-2*ui.dp(S6))*0.5f,chrome=ui.dp(18+S2+2*S3);android.graphics.Paint ink=again.label.getPaint();
            sideLabel=ink.measureText(full)+chrome<=half?full:ink.measureText(brief)+chrome<=half?brief:null;
            strip=ui.row();strip.setMinimumHeight(ui.dp(56));strip.addView(when);strip.addView(again);
            s.add(strip);box=ui.column();s.add(box);
        }
        void show(){
            modelSheet=this;if(!catalogLoading)catalogFailed=false;
            fill();paint(false);s.onDismiss(()->{if(modelSheet==this)modelSheet=null;}).show();
            if(Models.stale(SettingsActivity.this))loadCatalog();
        }
        /**
         * Estado de la lista: gris claro normalmente; en tono de error si no se pudo actualizar, con «Reintentar».
         * justDone: acaba de llegar una lista nueva; el indicador de carga del botón se transforma en ✓ (queda hasta la
         * próxima actualización, como confirmación junto a «Lista actualizada recién»).
         */
        void paint(boolean justDone){
            long at=Models.fetchedAt(SettingsActivity.this);int n=Models.cached(SettingsActivity.this).size();boolean error=catalogFailed&&!catalogLoading;
            when.setText(catalogLoading?getString(at==0?R.string.set_list_downloading:R.string.set_list_searching)
                :error?getString(at==0?R.string.set_list_download_failed:R.string.set_list_update_failed)
                :at==0?getString(R.string.set_list_never):getResources().getQuantityString(R.plurals.set_list_updated,n,n,ago(at)));
            when.setTextColor(error?p.onErrorContainer:p.onSurfaceVariant);strip.setBackground(error?shape(SettingsActivity.this,p.errorContainer,R_CONTROL):inset(R_CONTROL));
            // El error trae un texto largo y su salida («Reintentar»): va apilado, con el texto a todo el ancho.
            boolean side=sideLabel!=null&&!error;
            strip.setOrientation(side?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);strip.setGravity(side?Gravity.CENTER_VERTICAL:Gravity.START);
            strip.setPadding(ui.dp(S4),ui.dp(side?S1:S3),ui.dp(side?S1:S4),ui.dp(S1));
            when.setLayoutParams(side?new LinearLayout.LayoutParams(0,-2,1):Ui.fill());
            // Apilado, el botón de solo texto se corre a la izquierda lo que mide su relleno: su letra queda alineada con el texto.
            LinearLayout.LayoutParams ap=Ui.wrap();if(!side)ap.setMarginStart(-ui.dp(S3));again.setLayoutParams(ap);
            again.setText(error?getString(R.string.set_retry):side?sideLabel:getString(R.string.set_list_refresh));if(!error)again.setContentDescription(getString(R.string.set_list_refresh));
            again.setColors(error?p.onErrorContainer:p.primary,0);
            if(justDone)again.showDone();else{again.setIcon(R.drawable.ic_refresh);again.setBusy(catalogLoading);}
        }
        /** Arma (o rehace, al llegar una lista nueva) «Automático» y los modelos. */
        void fill(){
            box.removeAllViews();
            List<Models.Model> saved=Models.cached(SettingsActivity.this),all=saved.isEmpty()?Models.builtin():saved;
            String pick=speakers?settings.orSpeakersModel():settings.orTextModel();boolean auto=pick.isEmpty()||Models.AUTO.equals(pick);
            box.addView(autoCard(all,auto),ui.top(S3));
            // «Modelo con voces» solo ofrece los que Verbapp sabe pedir con voces. Los conocidos van en el orden de la tabla
            // (del recomendado al respaldo); los nuevos, como llegan: del más reciente al más antiguo.
            List<Models.Model> tested=new ArrayList<>(),fresh=new ArrayList<>();
            for(Models.Model m:all){if(speakers&&!m.recipe.diarizes)continue;(m.known?tested:fresh).add(m);}
            java.util.Collections.sort(tested,(a,b)->Integer.compare(Models.rank(a.id),Models.rank(b.id)));
            // El elegido a mano que ya no está en la lista se muestra igual, marcado: si no, la hoja quedaría sin nada elegido.
            Models.Model gone=!auto&&Models.find(all,pick)==null?new Models.Model(pick,Models.name(SettingsActivity.this,pick),0,-1,Models.recipe(pick),Models.known(pick),0):null;
            if(gone!=null)tested.add(0,gone);
            section(getString(speakers?R.string.set_models_separate:R.string.set_models_known),tested,auto?null:pick,gone);
            section(getString(R.string.set_models_new),fresh,auto?null:pick,null);
            TextView foot=ui.text(getString(speakers?R.string.set_models_foot_voices:R.string.set_models_foot_text),Type.BODY_SMALL,p.onSurfaceVariant);
            foot.setPadding(ui.dp(S1),ui.dp(S4),ui.dp(S1),0);box.addView(foot,Ui.fill());
        }
        private void section(String title,List<Models.Model> models,String pick,Models.Model gone){
            if(models.isEmpty())return;
            TextView t=ui.text(title,Type.TITLE_SMALL,p.primary);t.setPadding(ui.dp(S1),ui.dp(S5),ui.dp(S1),ui.dp(S2));if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);box.addView(t,Ui.fill());
            // Los modelos van juntos en un panel claro, como las voces conocidas; el divisor empieza donde empieza el nombre.
            LinearLayout list=ui.column();list.setBackground(inset(R_CARD));list.setClipToOutline(true);
            for(int i=0;i<models.size();i++){Models.Model m=models.get(i);if(i>0)list.addView(ui.separator(S4+24+S3));list.addView(modelRow(m,m.id.equals(pick),m==gone),Ui.fill());}
            box.addView(list,Ui.fill());
        }
        /**
         * «Automático»: la opción recomendada, en menta para que se distinga de la lista. Dice qué hace y qué modelo usa
         * hoy con su precio; elegida, lleva el radio lleno y un borde verde.
         */
        private View autoCard(List<Models.Model> all,boolean selected){
            String id=Models.automatic(settings,speakers),price=priceText(Models.find(all,id));
            // Si Automático cayó en un modelo cuyo modo de separar voces aún no se confirma, se dice (no se promete de más).
            String today=todayText(id,price,speakers);
            LinearLayout card=ui.row();card.setGravity(Gravity.TOP);card.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S4),ui.dp(S4));
            GradientDrawable fill=shape(SettingsActivity.this,p.primaryContainer,R_CARD);if(selected)fill.setStroke(ui.dp(2),p.primary);card.setBackground(ui.ripple(fill,R_CARD));
            card.addView(ui.icon(selected?R.drawable.ic_radio_on:R.drawable.ic_radio_off,selected?p.primary:p.onPrimaryContainer,24));card.addView(ui.space(S3));
            LinearLayout texts=ui.column();Flow head=new Flow(SettingsActivity.this,ui.dp(S2),ui.dp(S1));
            head.addView(ui.text(getString(R.string.set_automatic),Type.TITLE_MEDIUM,p.onPrimaryContainer));head.addView(pill(getString(R.string.set_recommended),p.onPrimary,p.primary,0,R.drawable.ic_star_fill));texts.addView(head,Ui.fill());
            TextView what=ui.text(getString(R.string.set_auto_what),Type.BODY_MEDIUM,p.onPrimaryContainer);what.setPadding(0,ui.dp(S1),0,0);texts.addView(what);
            TextView now=Ui.tabular(ui.text(today,Type.LABEL_LARGE,p.onPrimaryContainer));now.setPadding(0,ui.dp(S2),0,0);texts.addView(now);
            card.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            card.setClickable(true);card.setFocusable(true);card.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(card);
            card.setContentDescription(getString(R.string.set_auto_desc,today)+(selected?", "+getString(R.string.set_selected):""));
            card.setOnClickListener(v->pick(Models.AUTO));
            return card;
        }
        /**
         * Un modelo, en dos líneas que comparten columnas: arriba el nombre y, a la derecha, el precio en cifras fijas
         * (todas las filas alinean sus precios, así se comparan de un vistazo); abajo quién lo hace y sus píldoras, con
         * «por hora» bajo el precio. El elegido lleva un fondo menta tenue. Sin precio conocido, no se muestra ninguno.
         */
        private View modelRow(Models.Model m,boolean selected,boolean gone){
            LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setMinimumHeight(ui.dp(64));row.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
            row.addView(ui.icon(selected?R.drawable.ic_radio_on:R.drawable.ic_radio_off,selected?p.primary:p.onSurfaceVariant,24));row.addView(ui.space(S3));
            String label=Models.label(m),vendor=Models.vendor(m),price=priceText(m);StringBuilder said=new StringBuilder(vendor.isEmpty()?label:getString(R.string.set_model_by,label,vendor));
            LinearLayout texts=ui.column(),top=ui.row(),under=ui.row();top.setGravity(Gravity.TOP);under.setGravity(Gravity.TOP);
            TextView name=ui.text(label,Type.ITEM,p.onSurface);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Flow tags=new Flow(SettingsActivity.this,ui.dp(6),ui.dp(S1));int neutral=p.dark?p.surfaceContainerHighest:p.surfaceContainerHigh;
            if(!vendor.isEmpty()){TextView by=ui.oneLine(ui.text(vendor,Type.LABEL_MEDIUM,p.onSurfaceVariant));by.setIncludeFontPadding(false);by.setPadding(0,ui.dp(S1),ui.dp(2),ui.dp(S1));tags.addView(by);}
            // Menta = separa voces y está documentado cómo pedirlo. Con borde = debería poder, pero falta confirmarlo con
            // audio real (se dice al lado). Gris = solo texto.
            String textOnly=getString(R.string.set_pill_text_only),separates=getString(R.string.set_pill_separates);
            if(!m.recipe.diarizes){tags.addView(pill(textOnly,p.onSurfaceVariant,neutral,0,0));said.append(". ").append(textOnly);}
            else if(m.recipe.verified){tags.addView(pill(separates,p.onPrimaryContainer,p.primaryContainer,0,R.drawable.ic_people));said.append(". ").append(separates);}
            else{tags.addView(pill(separates,p.onSurfaceVariant,0,p.outline,R.drawable.ic_people));tags.addView(pill(getString(R.string.set_unconfirmed_pill),p.onSurfaceVariant,0,p.outline,0));said.append(". ").append(separates).append(", ").append(getString(R.string.set_unconfirmed));}
            if(gone){String off=getString(R.string.set_model_gone);tags.addView(pill(off,p.onErrorContainer,p.errorContainer,0,0));said.append(". ").append(off);}
            else{
                if(!m.known){tags.addView(pill(getString(R.string.set_pill_new),p.onSurfaceVariant,0,p.outline,0));said.append(". ").append(getString(R.string.set_said_new));}
                if(m.expires>0){
                    // En menos de 30 días es una alerta (Automático ya no lo elige); más lejos, solo un dato.
                    boolean soon=Models.retiresSoon(m,System.currentTimeMillis());String leaves=getString(R.string.set_model_retires,retireDate(m.expires));
                    tags.addView(soon?pill(leaves,p.onErrorContainer,p.errorContainer,0,0):pill(leaves,p.onSurfaceVariant,0,p.outline,0));said.append(". ").append(leaves);
                }
            }
            under.addView(tags,new LinearLayout.LayoutParams(0,-2,1));
            if(!price.isEmpty()){
                LinearLayout.LayoutParams pp=Ui.wrap();pp.setMarginStart(ui.dp(S3));top.addView(ui.oneLine(Ui.tabular(ui.text(price,Type.TITLE_MEDIUM,p.onSurface))),pp);
                // «por hora» con el mismo relleno vertical de las píldoras: queda a su altura.
                TextView per=ui.text(getString(R.string.set_per_hour),Type.LABEL_SMALL,p.onSurfaceVariant);per.setIncludeFontPadding(false);per.setPadding(0,ui.dp(S1),0,ui.dp(S1));
                LinearLayout.LayoutParams hp=Ui.wrap();hp.setMarginStart(ui.dp(S2));under.addView(per,hp);
                said.append(". ").append(getString(m.approx?R.string.set_said_price_approx:R.string.set_per_hour_price,hourPrice(m.perHour)));
            }else said.append(". ").append(getString(R.string.set_said_no_price));
            texts.addView(top,Ui.fill());texts.addView(under,ui.top(S1));row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            if(selected)said.append(", ").append(getString(R.string.set_selected));
            // Tenue a propósito: la píldora «Separa voces» (menta llena) tiene que seguir distinguiéndose sobre la fila elegida.
            row.setBackground(selected?ui.ripple(shape(SettingsActivity.this,withAlpha(p.primaryContainer,p.dark?0x59:0x73),0),0):ui.ripple(null,0));
            row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());row.setContentDescription(said);
            row.setOnClickListener(v->pick(m.id));
            return row;
        }
        /** Guarda la elección y cierra. En el diagnóstico queda solo si fue «Automático» o un modelo elegido a mano. */
        private void pick(String value){
            String before=speakers?settings.orSpeakersModel():settings.orTextModel();s.dismiss();
            if(value.equals(before.isEmpty()?Models.AUTO:before))return;
            settings.prefs.edit().putString(key,value).apply();
            Diagnostics.event("setting_changed",null,"action",key,"result",value.equals(Models.AUTO)?"auto":"manual");Pipeline.schedule(SettingsActivity.this,true);render();
        }
    }
    /** Píldora chica de estado (12 sp). stroke distinto de 0: solo borde (algo por confirmar); icon: ícono inicial de 14 dp. */
    private TextView pill(String text,int fg,int bg,int stroke,int icon){
        TextView t=ui.oneLine(ui.text(text,Type.LABEL_MEDIUM,fg));t.setIncludeFontPadding(false);t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(stroke!=0?outline(this,bg,stroke,R_FULL,false):shape(this,bg,R_FULL));t.setPadding(ui.dp(S2),ui.dp(S1),ui.dp(S2),ui.dp(S1));
        if(icon!=0){Drawable d=getDrawable(icon).mutate();d.setTint(fg);d.setBounds(0,0,ui.dp(14),ui.dp(14));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(ui.dp(S1));}
        return t;
    }
    /**
     * Fila que pasa a la línea siguiente lo que no cabe (las píldoras de un modelo). El kit no tiene una: con letra grande
     * o nombres largos, una fila común cortaba la última píldora. Cada línea centra sus piezas en vertical.
     */
    private static final class Flow extends ViewGroup {
        private final int gapX,gapY;
        /** Línea de cada hijo y alto de cada línea (a lo más, una por hijo). Se dimensionan al agregar o quitar hijos, no al medir. */
        private int[] lineOf=new int[0],lineHeight=new int[1];
        Flow(Context c,int gapX,int gapY){super(c);this.gapX=gapX;this.gapY=gapY;}
        @Override public void onViewAdded(View child){super.onViewAdded(child);resize();}
        @Override public void onViewRemoved(View child){super.onViewRemoved(child);resize();}
        private void resize(){int n=getChildCount();lineOf=new int[n];lineHeight=new int[n+1];}
        @Override protected void onMeasure(int wSpec,int hSpec){
            boolean free=MeasureSpec.getMode(wSpec)==MeasureSpec.UNSPECIFIED;int max=free?Integer.MAX_VALUE:Math.max(0,MeasureSpec.getSize(wSpec)-getPaddingLeft()-getPaddingRight());
            int n=Math.min(getChildCount(),lineOf.length),x=0,line=0,widest=0;java.util.Arrays.fill(lineHeight,0);
            for(int i=0;i<n;i++){
                View v=getChildAt(i);lineOf[i]=line;if(v.getVisibility()==GONE)continue;
                v.measure(free?MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED):MeasureSpec.makeMeasureSpec(max,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                int w=v.getMeasuredWidth();if(x>0&&x+w>max){line++;x=0;}
                lineOf[i]=line;lineHeight[line]=Math.max(lineHeight[line],v.getMeasuredHeight());x+=w+gapX;widest=Math.max(widest,x-gapX);
            }
            int total=0;for(int l=0;l<=line;l++)total+=lineHeight[l]+(l>0?gapY:0);
            setMeasuredDimension(resolveSize(widest+getPaddingLeft()+getPaddingRight(),wSpec),resolveSize(total+getPaddingTop()+getPaddingBottom(),hSpec));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;int width=r-l,x=0,y=getPaddingTop(),line=0;
            for(int i=0;i<getChildCount()&&i<lineOf.length;i++){
                View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
                if(lineOf[i]!=line){y+=lineHeight[line]+gapY;line=lineOf[i];x=0;}
                int w=v.getMeasuredWidth(),h=v.getMeasuredHeight(),top=y+(lineHeight[line]-h)/2,left=rtl?width-getPaddingRight()-x-w:getPaddingLeft()+x;
                v.layout(left,top,left+w,top+h);x+=w+gapX;
            }
        }
    }

    // ---------- Carpetas ----------
    private void folderSheet(){
        boolean has=!settings.prefs.getString("localTree","").isEmpty();
        Sheet s=sheet(getString(R.string.set_copies_title),getString(has?R.string.set_copies_has:R.string.set_copies_none));
        s.action(R.drawable.ic_folder,getString(has?R.string.set_folder_change:R.string.set_folder_choose),false,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),PICK_FOLDER);}catch(ActivityNotFoundException e){message(getString(R.string.set_folder),getString(R.string.set_folder_unsupported));}});
        if(has){s.action(R.drawable.ic_refresh,getString(R.string.set_copies_update_all),false,()->{for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast(getString(R.string.set_copies_updating));});
            // El permiso es uno por carpeta: si el guardado rápido usa la misma, se conserva (sin él, «Guardar en 0-Inbox» fallaría).
            s.action(R.drawable.ic_close,getString(R.string.set_copies_stop),true,()->{String old=settings.prefs.getString("localTree","");settings.prefs.edit().remove("localTree").apply();
                if(!old.isEmpty()&&!old.equals(settings.inboxTree()))try{getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}refreshFolder();});}
        s.show();
    }
    /** Aplica la fecha a los nombres de las grabaciones que ya existen (se pregunta primero). */
    private void offerDatesForExisting(){
        confirm(getString(R.string.set_dates_q),getString(R.string.set_dates_body),getString(R.string.set_dates_all),false,()->io.execute(()->{int n=0;
            for(Recording r:Recording.list(this)){String before=r.title;try{r.save(this);if(!before.equals(r.title)){Pipeline.edited(this,r.id);n++;}}catch(Exception ignored){}}
            int changed=n;runOnUiThread(()->toast(getResources().getQuantityString(R.plurals.set_names_updated,changed,changed)));}));
    }
    private void saveSheet(){
        boolean has=!settings.inboxTree().isEmpty();
        Sheet s=sheet(getString(R.string.set_quick_save),has?getString(R.string.set_quick_save_has,settings.prefs.getString("saveTreeName","")):getString(R.string.set_quick_save_none));
        s.action(R.drawable.ic_folder,getString(has?R.string.set_folder_change:R.string.set_folder_choose),false,()->{try{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);String last=settings.prefs.getString("lastSaveUri","");if(!last.isEmpty())pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(last));startActivityForResult(pick,PICK_SAVE);}catch(ActivityNotFoundException e){message(getString(R.string.set_quick_save),getString(R.string.set_folder_unsupported));}});
        if(has)s.action(R.drawable.ic_close,getString(R.string.set_quick_save_remove),true,()->{settings.prefs.edit().remove("saveTree").remove("saveTreeName").apply();if(saveRow!=null)saveRow.setValue(getString(R.string.set_not_chosen));});
        s.show();
    }
    /** Nombre visible de una carpeta elegida con el selector de Android. */
    private String folderName(Uri uri){String name=getString(R.string.set_folder_chosen);try(android.database.Cursor c=getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)name=c.getString(0);}catch(Exception ignored){}return name;}
    /** Muestra el nombre real de la carpeta (y si es Drive) en vez del identificador interno. */
    private void refreshFolder(){
        String tree=settings.prefs.getString("localTree","");if(tree.isEmpty()){folderRow.setValue(getString(R.string.set_copies_off));return;}
        folderRow.setValue("…");Ui.Row row=folderRow;io.execute(()->{Uri uri=Uri.parse(tree);String name=folderName(uri);
            String authority=uri.getAuthority()==null?"":uri.getAuthority();String label=(authority.contains("google.android.apps.docs")?"Drive · ":"")+name;
            runOnUiThread(()->{if(!isDestroyed())row.setValue(label);});});
    }
    private void report(){toast(getString(R.string.set_report_preparing));io.execute(()->{try{java.io.File f=Diagnostics.export(this);runOnUiThread(()->shareFile(f.getName(),getString(R.string.set_report_share_title)));}catch(Exception e){runOnUiThread(()->message(getString(R.string.set_report_title),getString(R.string.set_report_failed)));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_SAVE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri tree=data.getData();
            try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                io.execute(()->{String name=folderName(tree);settings.prefs.edit().putString("saveTree",tree.toString()).putString("saveTreeName",name).apply();Diagnostics.event("setting_changed",null,"action","save_tree","result",tree.getAuthority()!=null&&tree.getAuthority().contains("google.android.apps.docs")?"drive":"device");
                    runOnUiThread(()->{if(isDestroyed())return;if(saveRow!=null)saveRow.setValue(name);toast(getString(R.string.set_quick_save_in,name));if(inboxFlow)finish();});});}
            catch(Exception e){message(getString(R.string.set_quick_save),getString(R.string.set_quick_save_no_perm));}
        }
        if(request==PICK_FOLDER&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try{Uri tree=data.getData();if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new SecurityException();getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                settings.prefs.edit().putString("localTree",tree.toString()).apply();Diagnostics.event("setting_changed",null,"action","local_tree","result",tree.getAuthority()!=null&&tree.getAuthority().contains("google.android.apps.docs")?"drive":"device");refreshFolder();
                for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast(getString(R.string.set_copies_saved));}
            catch(Exception e){message(getString(R.string.set_folder),getString(R.string.set_copies_no_perm));}
        }
    }
    @Override protected void onDestroy(){if(http!=null)http.cancel();if(catalogHttp!=null)catalogHttp.cancel();io.shutdown();checks.shutdown();stats.shutdown();stopVoice(false);releaseVoicePlayer();super.onDestroy();}

    // ---------- Voces conocidas ----------
    private static final int MIC_FOR_VOICE=62;
    private android.media.MediaRecorder voiceRecorder;private java.io.File voiceTmp;private long voiceStarted;private android.media.MediaPlayer voicePlayer;private Runnable voiceEnded;
    private final Handler voiceTimer=new Handler(Looper.getMainLooper());private Sheet voiceRecording;
    /** De quién es la muestra que se graba: tu voz (me), una voz guardada (id: se reemplaza su muestra) o una nueva (id null). */
    private static final class VoiceTake{final String name,id;final boolean me;VoiceTake(String name,String id,boolean me){this.name=name;this.id=id;this.me=me;}}
    private VoiceTake voiceTake,pendingVoice;
    /** Al salir de la pantalla Android silencia el micrófono: la toma se descarta en vez de guardar una muestra muda. */
    @Override protected void onStop(){if(voiceRecorder!=null){stopVoice(false);if(voiceRecording!=null)voiceRecording.dismiss();toast(getString(R.string.set_voice_interrupted));}releaseVoicePlayer();super.onStop();}
    /** «Konrad (tú) · Fran», o «Ninguna». */
    private String voiceValue(){StringBuilder b=new StringBuilder();for(Voices.Voice v:Voices.list(this))b.append(b.length()==0?"":" · ").append(v.label());return b.length()==0?getString(R.string.set_voices_none):b.toString();}
    private void refreshVoices(){if(voiceRow!=null)voiceRow.setValue(voiceValue());}
    /**
     * Voces conocidas: tu voz y las de las personas con que más hablas. Al separar voces van como muestra en todo el
     * audio (hasta 4 por audio, la tuya primero) para reconocer a cada persona desde el inicio. Tocar una abre sus opciones.
     */
    private void voiceSheet(){
        List<Voices.Voice> all=Voices.list(this);
        Sheet s=sheet(getString(R.string.set_voices_title),getString(all.isEmpty()?R.string.set_voices_empty_body:R.string.set_voices_body));
        if(!all.isEmpty()){
            // Las voces van juntas en un panel claro (el «vidrio» de las hojas blancas), como un grupo de Ajustes.
            LinearLayout list=ui.column();list.setBackground(inset(R_CARD));list.setClipToOutline(true);
            for(int i=0;i<all.size();i++){if(i>0)list.addView(ui.separator(S4+40+S3));voiceItem(s,list,all.get(i),i);}
            s.add(list);
        }
        int used=Voices.used(this).size();
        int max=Transcriber.MAX_KNOWN;
        TextView note=ui.text((used>max?getResources().getQuantityString(R.plurals.set_voices_limit_over,max,max,used):getResources().getQuantityString(R.plurals.set_voices_limit,max,max))
            +" "+getString(R.string.set_voices_privacy)
            // OpenRouter no tiene «voces conocidas»: las muestras van delante del audio y la app deduce quién es quién. Es nuevo y puede fallar.
            +(settings.openRouter()?" "+getString(R.string.set_voices_or_new):""),Type.BODY_SMALL,p.onSurfaceVariant);
        note.setPadding(0,ui.dp(all.isEmpty()?0:S3),0,ui.dp(S2));s.add(note);
        if(!Voices.has(this))s.action(R.drawable.ic_mic_fill,getString(R.string.set_voice_record_mine),false,this::myVoiceSheet);
        s.action(R.drawable.ic_person,getString(R.string.set_voice_add),false,this::addVoiceSheet);
        s.secondary(getString(R.string.set_close),null).show();
    }
    /**
     * Fila de una voz: su color e inicial, su nombre (con «tú» en menta si es la tuya), si se usa al transcribir y una
     * flecha que invita a ver sus opciones. No registra nombres en el diagnóstico.
     */
    private void voiceItem(Sheet s,LinearLayout list,Voices.Voice v,int index){
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(64));row.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S3),ui.dp(S3));
        TextView avatar=ui.text(NameVoices.initial(v.name),Type.TITLE_MEDIUM,p.surface);avatar.setGravity(Gravity.CENTER);avatar.setBackground(oval(p.speaker(index)));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);if(!v.use)avatar.setAlpha(0.38f);
        row.addView(avatar,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();LinearLayout top=ui.row();
        TextView name=ui.oneLine(ui.text(v.name,Type.ITEM,p.onSurface));name.setMaxWidth(ui.dp(160));top.addView(name,Ui.wrap());
        if(v.me){TextView tag=ui.chip(getString(R.string.set_voice_you),p.onPrimaryContainer,p.primaryContainer);AppTheme.type(tag,Type.LABEL_MEDIUM);tag.setPadding(ui.dp(S2),ui.dp(2),ui.dp(S2),ui.dp(2));LinearLayout.LayoutParams tp=Ui.wrap();tp.setMarginStart(ui.dp(S2));top.addView(tag,tp);}
        texts.addView(top);
        String status=getString(v.use?R.string.set_voice_used:R.string.set_voice_unused);TextView st=ui.text(status,Type.BODY_MEDIUM,p.onSurfaceVariant);st.setPadding(0,ui.dp(2),0,0);texts.addView(st);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageView go=ui.icon(R.drawable.ic_chevron_down,p.onSurfaceVariant,20);go.setRotation(-90);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20));gp.setMarginStart(ui.dp(S2));row.addView(go,gp);
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setContentDescription(getString(v.me?R.string.set_voice_item_desc_me:R.string.set_voice_item_desc,v.name,status));
        row.setOnClickListener(x->{s.dismiss();voiceDetail(v.id);});
        list.addView(row,Ui.fill());
    }
    /** Opciones de una voz: escuchar, usarla o no al transcribir, cambiar nombre, grabar de nuevo y eliminar. */
    private void voiceDetail(String id){
        Voices.Voice v=Voices.get(this,id);if(v==null){voiceSheet();return;}
        String when=savedOn(v.createdAt);
        Sheet s=sheet(v.name,v.me?getString(R.string.set_voice_mine_when,when):Character.toUpperCase(when.charAt(0))+when.substring(1));
        // La muestra como mini reproductor (0.7.0): ▶ redondo de tinta y unas ondas que se tiñen de verde mientras suena.
        // Toda la fila es el botón; se anuncia «Escuchar» o «Detener», igual que el botón de 0.6.
        LinearLayout player=ui.row();player.setMinimumHeight(ui.dp(64));player.setPadding(ui.dp(S2),ui.dp(S2),ui.dp(S4),ui.dp(S2));
        FrameLayout knob=ui.tile(R.drawable.ic_play,p.onInk,p.ink,48,22);ImageView glyph=(ImageView)knob.getChildAt(0);player.addView(knob);player.addView(ui.space(S3));
        TextView what=ui.oneLine(ui.text(getString(R.string.set_voice_listen_sample),Type.TITLE_MEDIUM,p.onSurface));player.addView(what,Ui.wrap());
        WaveDeco wave=new WaveDeco(this,p.waveIdle);LinearLayout.LayoutParams wl=new LinearLayout.LayoutParams(0,ui.dp(28),1);wl.setMarginStart(ui.dp(S3));player.addView(wave,wl);
        player.setBackground(ui.ripple(inset(R_CARD),R_CARD));player.setClickable(true);player.setFocusable(true);player.setAccessibilityDelegate(Ui.buttonRole());player.setContentDescription(getString(R.string.set_voice_listen));Ui.pressable(player);
        Runnable idle=()->{glyph.setImageResource(R.drawable.ic_play);what.setText(getString(R.string.set_voice_listen_sample));player.setContentDescription(getString(R.string.set_voice_listen));wave.setColor(p.waveIdle);};
        player.setOnClickListener(x->{
            Diagnostics.event("ui_action",null,"screen",getClass().getSimpleName(),"action",String.valueOf(player.getContentDescription()));
            if(voicePlayer!=null){releaseVoicePlayer();return;}
            if(playVoice(v.id,idle)){String stop=getString(R.string.set_voice_stop);glyph.setImageResource(R.drawable.ic_stop);what.setText(stop);player.setContentDescription(stop);wave.setColor(p.primary);}
        });
        LinearLayout.LayoutParams lp=Ui.fill();lp.bottomMargin=ui.dp(S2);s.body.addView(player,lp);
        Ui.SwitchRow[] use={null};
        use[0]=ui.switchRow(R.drawable.ic_voice,getString(R.string.set_voice_use),useText(v.use),v.use,on->{
            if(!Voices.setUse(this,v.id,on)){toast(getString(R.string.set_change_failed));return;}
            use[0].setSubtitle(useText(on));refreshVoices();});
        use[0].setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));LinearLayout.LayoutParams up=Ui.fill();up.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);s.body.addView(use[0],up);
        s.action(R.drawable.ic_edit,getString(R.string.set_rename),false,()->renameVoice(v.id));
        s.action(R.drawable.ic_mic_fill,getString(R.string.set_record_again),false,()->recordVoice(new VoiceTake(v.name,v.id,v.me)));
        s.action(R.drawable.ic_trash,getString(R.string.set_remove),true,()->confirm(v.me?getString(R.string.set_voice_delete_mine_q):getString(R.string.set_voice_delete_q,v.name),
            getString(v.me?R.string.set_voice_delete_mine_body:R.string.set_voice_delete_body),getString(R.string.set_remove),true,()->{
                Voices.remove(this,v.id);refreshVoices();toast(getString(v.me?R.string.set_voice_deleted_mine:R.string.set_voice_deleted));voiceSheet();}));
        s.onDismiss(this::releaseVoicePlayer);
        s.show();
    }
    private String useText(boolean on){return getString(on?R.string.set_voice_use_on:R.string.set_voice_use_off);}
    /** «guardada el 29 de septiembre» (con el año si no es este), con el formato de fecha del idioma de la app. */
    private String savedOn(long at){
        if(at<=0)return getString(R.string.set_voice_saved_here);
        java.util.Calendar now=java.util.Calendar.getInstance(),then=java.util.Calendar.getInstance();then.setTimeInMillis(at);
        String pattern=getString(now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)?R.string.set_date_day_month:R.string.set_date_day_month_year);
        return getString(R.string.set_voice_saved_on,new java.text.SimpleDateFormat(pattern,Lang.locale(this)).format(new java.util.Date(at)));
    }
    private EditText nameField(String hint,String description,String value){
        EditText f=ui.field(hint,description);f.setSingleLine(true);f.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        f.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);if(value!=null)f.setText(value);return f;
    }
    private void renameVoice(String id){
        Voices.Voice v=Voices.get(this,id);if(v==null){voiceSheet();return;}
        EditText name=nameField(getString(v.me?R.string.set_your_name:R.string.set_their_name),getString(v.me?R.string.set_your_name:R.string.set_person_name),v.name);
        Sheet s=sheet(getString(R.string.set_rename),getString(v.me?R.string.set_rename_mine_body:R.string.set_rename_body)).add(name);
        s.primary(getString(R.string.set_save),Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError(getString(R.string.set_name_empty));return false;}
            Voices.Voice same=Voices.findByName(this,n);if(same!=null&&!same.id.equals(v.id)){name.setError(getString(R.string.set_name_taken));return false;}
            if(!Voices.rename(this,v.id,n)){name.setError(getString(R.string.set_save_failed_retry));return false;}
            refreshVoices();toast(getString(R.string.set_name_saved));page.post(this::voiceSheet);return true;});
        s.secondary(getString(R.string.common_cancel),null).show();
    }
    /** Grabar tu voz: tu nombre y 10 s leyendo en voz alta. */
    private void myVoiceSheet(){
        Sheet s=sheet(getString(R.string.set_voice_record_mine),getString(R.string.set_my_voice_body));
        EditText name=nameField(getString(R.string.set_your_name_hint),getString(R.string.set_your_name),settings.prefs.getString("myVoiceName",""));s.add(name);
        s.primary(getString(R.string.set_voice_record_mine),Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError(getString(R.string.set_your_name_empty));return false;}
            Voices.Voice same=Voices.findByName(this,n);if(same!=null&&!same.me){name.setError(getString(R.string.set_name_taken_other));return false;}
            Voices.setName(this,n);recordVoice(new VoiceTake(n,Voices.has(this)?Voices.ME_ID:null,true));return true;});
        s.secondary(getString(R.string.common_cancel),null).show();
    }
    /** Agregar la voz de otra persona: su nombre y 10 s leyendo en voz alta (también se puede guardar desde una grabación). */
    private void addVoiceSheet(){
        Sheet s=sheet(getString(R.string.set_voice_add),getString(R.string.set_add_voice_body));
        EditText name=nameField(getString(R.string.set_their_name_hint),getString(R.string.set_person_name),"");s.add(name);
        s.primary(getString(R.string.set_record_their_voice),Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError(getString(R.string.set_their_name_empty));return false;}
            Voices.Voice same=Voices.findByName(this,n);
            if(same!=null){page.post(()->confirm(getString(R.string.set_replace_voice_q,same.name),getString(R.string.set_replace_voice_body),getString(R.string.set_replace),false,()->recordVoice(new VoiceTake(same.name,same.id,same.me))));return true;}
            recordVoice(new VoiceTake(n,null,false));return true;});
        s.secondary(getString(R.string.common_cancel),null).show();
    }
    /**
     * Hoja de grabación de 10 s con un texto para leer (dirigido a quien habla). Cerrarla guarda; «Cancelar» descarta.
     * 0.7.0: el guion va en una tarjeta clara con letra de lectura grande (se lee con el teléfono a un brazo de distancia);
     * debajo, el cronómetro en Outfit con cifras fijas, una barra que se llena hasta el máximo y el estado (región en vivo,
     * con el punto naranja que late mientras graba). «Grabar» (verde) pasa a «Detener y guardar» (tinta) al empezar.
     * Los botones van apilados a lo ancho: «Detener y guardar» no cabe en medio ancho.
     */
    private void recordVoice(VoiceTake t){
        if(t==null)return;
        if(RecorderService.activeId!=null){message(getString(R.string.set_voices_title),getString(R.string.set_voice_busy));return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingVoice=t;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},MIC_FOR_VOICE);return;}
        releaseVoicePlayer();
        String who=t.name==null||t.name.trim().isEmpty()?(t.me?Voices.name(this):getString(R.string.set_that_person)):t.name.trim();
        LinearLayout paper=ui.column();paper.setBackground(inset(R_CARD));paper.setPadding(ui.dp(S5),ui.dp(S4),ui.dp(S5),ui.dp(S5));
        TextView script=ui.text(getString(t.me?R.string.set_voice_script_mine:R.string.set_voice_script,who),Type.BODY_LARGE,p.onSurface);
        script.setTextSize(18);script.setLineSpacing(ui.dp(4),1f);paper.addView(script);
        // Cronómetro: la cifra de DISPLAY_LARGE (68) a 56 sp, para que la hoja entera quepa en un teléfono de 360 × 740 dp.
        TextView clock=Ui.tabular(ui.text(mmss(0),Type.DISPLAY_LARGE,p.onSurface));clock.setTextSize(56);clock.setIncludeFontPadding(false);clock.setGravity(Gravity.CENTER);
        clock.setPadding(0,ui.dp(S5),0,ui.dp(S3));clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // el estado de abajo lo anuncia
        VoiceProgress bar=new VoiceProgress(this,p);
        LinearLayout line=ui.row();line.setGravity(Gravity.CENTER);line.setPadding(0,ui.dp(S3),0,0);
        View dot=new View(this);dot.setBackground(oval(p.record));dot.setVisibility(View.GONE);dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8));dl.setMarginEnd(ui.dp(S2));line.addView(dot,dl);
        TextView status=ui.text(t.me?getString(R.string.set_voice_status_mine):getString(R.string.set_voice_status,who),Type.BODY_MEDIUM,p.onSurfaceVariant);
        status.setFontFeatureSettings("tnum");status.setGravity(Gravity.CENTER);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);line.addView(status,Ui.wrap());
        Sheet s=sheet(t.me?getString(R.string.set_read_aloud):getString(R.string.set_they_read_aloud,who),null);s.add(paper);s.add(clock);
        s.body.addView(bar,new LinearLayout.LayoutParams(-1,ui.dp(6)));
        s.add(line);voiceRecording=s;
        Ui.Btn go=ui.button(getString(R.string.set_record),R.drawable.ic_mic_fill,Ui.Style.RECORD,null);LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S5);s.body.addView(go,lp);
        ObjectAnimator[] pulse={null};
        go.setOnClickListener(v->{
            if(voiceRecorder==null){
                if(startVoice()){voiceTake=t;go.setText(getString(R.string.set_stop_save));go.setIcon(R.drawable.ic_stop);go.setColors(p.onInk,p.ink);Ui.haptic(go,Ui.Haptic.CONFIRM);
                    bar.start(voiceStarted);dot.setVisibility(View.VISIBLE);
                    if(AppTheme.motion()){pulse[0]=ObjectAnimator.ofFloat(dot,View.ALPHA,1f,0.25f);pulse[0].setDuration(600);pulse[0].setRepeatMode(ValueAnimator.REVERSE);pulse[0].setRepeatCount(ValueAnimator.INFINITE);pulse[0].start();}
                    voiceTimer.post(new Runnable(){public void run(){long ms=android.os.SystemClock.elapsedRealtime()-voiceStarted;status.setText(getString(R.string.set_voice_recording_progress,ms/1000,Voices.MAX_MS/1000));clock.setText(mmss(ms));bar.invalidate();if(ms>=Voices.MAX_MS){s.dismiss();return;}voiceTimer.postDelayed(this,250);}});}
                else{s.dismiss();message(getString(R.string.set_voices_title),getString(R.string.set_mic_failed));}
            }else s.dismiss();
        });
        // Cerrar la hoja guarda lo grabado; "Cancelar" lo descarta (la marca se pone antes de cerrar).
        boolean[] cancel={false};
        Ui.Btn no=ui.button(getString(R.string.common_cancel),0,Ui.Style.SECONDARY,v->{cancel[0]=true;s.dismiss();});LinearLayout.LayoutParams np=Ui.fill();np.topMargin=ui.dp(S2);s.body.addView(no,np);
        s.onDismiss(()->{if(pulse[0]!=null)pulse[0].cancel();bar.stop();stopVoice(!cancel[0]);}).show();
    }
    /** «0:07»: minutos y segundos del cronómetro de la muestra. */
    private static String mmss(long ms){long s=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    /**
     * Barra de la muestra de voz: riel gris y relleno verde que llega al final en Voices.MAX_MS. Mientras graba avanza a
     * cada cuadro (se ve continua aunque el texto cambie cada 250 ms); con «Quitar animaciones» avanza a saltos, con el reloj.
     */
    private static final class VoiceProgress extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF r=new RectF();private final int track,fill;
        private long started;private boolean running;private float value;
        VoiceProgress(Context c,Palette p){super(c);track=p.dark?p.surfaceContainerHighest:p.surfaceContainerHigh;fill=p.primary;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void start(long startedAt){started=startedAt;running=true;invalidate();}
        void stop(){running=false;}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),rad=h/2f;if(w<=0||h<=0)return;
            if(running)value=Math.min(1f,(SystemClock.elapsedRealtime()-started)/(float)Voices.MAX_MS);
            paint.setColor(track);r.set(0,0,w,h);canvas.drawRoundRect(r,rad,rad,paint);
            if(value>0f){paint.setColor(fill);r.set(0,0,Math.max(h,w*value),h);canvas.drawRoundRect(r,rad,rad,paint);}
            if(running&&value<1f&&AppTheme.motion())postInvalidateOnAnimation();
        }
    }
    private boolean startVoice(){
        try{voiceTmp=new java.io.File(getCacheDir(),"voice-take.m4a");voiceTmp.delete();
            voiceRecorder=Build.VERSION.SDK_INT>=31?new android.media.MediaRecorder(this):new android.media.MediaRecorder();
            voiceRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);voiceRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);voiceRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioEncodingBitRate(96000);voiceRecorder.setAudioSamplingRate(44100);voiceRecorder.setAudioChannels(1);voiceRecorder.setOutputFile(voiceTmp.getAbsolutePath());
            voiceRecorder.prepare();voiceRecorder.start();voiceStarted=android.os.SystemClock.elapsedRealtime();return true;
        }catch(Exception e){if(voiceRecorder!=null){voiceRecorder.release();voiceRecorder=null;}Diagnostics.event("recorder_failure",null,"action","my_voice","error_class",e.getClass().getSimpleName());return false;}
    }
    /** Detiene la muestra; si keep, la guarda (mínimo 3 s): una voz nueva o la nueva muestra de una voz guardada. */
    private void stopVoice(boolean keep){
        voiceTimer.removeCallbacksAndMessages(null);if(voiceRecorder==null)return;
        long ms=android.os.SystemClock.elapsedRealtime()-voiceStarted;boolean ok=false;
        try{voiceRecorder.stop();ok=true;}catch(RuntimeException ignored){}finally{voiceRecorder.release();voiceRecorder=null;}
        VoiceTake t=voiceTake;voiceTake=null;
        if(!keep||isFinishing()||t==null){if(voiceTmp!=null)voiceTmp.delete();return;}
        if(!ok||ms<Voices.MIN_MS){if(voiceTmp!=null)voiceTmp.delete();message(getString(R.string.set_voices_title),getString(R.string.set_sample_short));return;}
        try{
            String name;
            if(t.id!=null&&Voices.get(this,t.id)!=null){Voices.replaceAudio(this,t.id,voiceTmp);name=t.name;}
            else name=Voices.add(this,t.name,voiceTmp,t.me).name;
            refreshVoices();
            String done=t.me?getString(R.string.set_voice_saved_mine):getString(R.string.set_voice_saved_of,name);
            // voiceFlow: se vino solo a grabar la voz y se vuelve. Desde una grabación (hoja «¿Separar voces?») lo que sigue
            // es transcribir; desde la bienvenida (que además trae «back») todavía no hay nada que transcribir.
            if(voiceFlow){toast(done+(returnOnBack?"":" · "+getString(R.string.set_tap_transcribe)));finish();}else{toast(done);page.post(this::voiceSheet);}
        }catch(Exception e){if(voiceTmp!=null)voiceTmp.delete();message(getString(R.string.set_voices_title),getString(R.string.set_sample_save_failed));}
    }
    /** Reproduce una muestra; ended corre al terminar o al detenerla. */
    private boolean playVoice(String id,Runnable ended){
        releaseVoicePlayer();
        try{voicePlayer=new android.media.MediaPlayer();voicePlayer.setDataSource(Voices.file(this,id).getAbsolutePath());voicePlayer.setOnCompletionListener(mp->releaseVoicePlayer());voicePlayer.prepare();voicePlayer.start();voiceEnded=ended;return true;}
        catch(Exception e){releaseVoicePlayer();message(getString(R.string.set_voices_title),getString(R.string.set_sample_play_failed));return false;}
    }
    private void releaseVoicePlayer(){if(voicePlayer!=null){try{voicePlayer.release();}catch(Exception ignored){}voicePlayer=null;}Runnable r=voiceEnded;voiceEnded=null;if(r!=null)r.run();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MIC_FOR_VOICE){VoiceTake t=pendingVoice;pendingVoice=null;
            if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recordVoice(t);else message(getString(R.string.set_voices_title),getString(R.string.set_mic_denied));}
    }
}
