package cl.vozlocal.app;

import android.app.job.JobScheduler;
import android.content.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Ajustes en el orden de tu recorrido (0.6.0): «Tu flujo» (0-Inbox, nota, automatización, Mi voz) → servicio de
 * transcripción → energía y red → copias → apariencia → ayuda. Cada fila muestra su valor a la derecha y a lo más
 * una línea de apoyo; las explicaciones largas viven en «Más información». Ver docs/diseno/PROPUESTA-0.6.md (#3).
 *
 * Extras del intent: focusKey (pedir la clave si falta), voice (grabar «Mi voz» y volver), back (Atrás vuelve a
 * la pantalla anterior), inbox (elegir la carpeta de guardado rápido y volver) y noteAi (abrir «IA de la nota»).
 */
public class SettingsActivity extends Screen {
    /** Modelos para transcribir SIN separar voces. Para separar voces siempre se usa gpt-4o-transcribe-diarize. */
    static final String[] MODELS={"gpt-transcribe","gpt-4o-transcribe","gpt-4o-mini-transcribe","whisper-1"};
    static final String[] MODEL_NAMES={"GPT Transcribe","GPT-4o Transcribe","GPT-4o Mini","Whisper"};
    static final String[] MODEL_DETAILS={"Recomendado · el más nuevo, rápido y con texto en vivo","Alta calidad","El más económico","Modelo clásico"};
    static final String[] SPEAKER_MODES={"ask","always","never"},SPEAKER_NAMES={"Preguntar cada vez","Siempre","Nunca"};
    private static final int PICK_FOLDER=51,PICK_SAVE=52;
    private static final Locale CL=new Locale("es","CL");
    private Settings settings;private final ExecutorService io=Executors.newSingleThreadExecutor();private HttpApi http;
    private Ui.Row folderRow,saveRow,batteryRow,voiceRow,verifyRow;
    private boolean verifying;
    private String batteryValue(){return Battery.unrestricted(this)?"Puede transcribir":"Batería optimizada";}
    /** Al volver del permiso del sistema, se refleja el nuevo estado. */
    @Override protected void onResume(){super.onResume();if(batteryRow!=null)batteryRow.setValue(batteryValue());}

    static String modelName(Settings s){if(!s.provider().equals("openai"))return s.prefs.getString("customModel","personalizado");int i=Arrays.asList(MODELS).indexOf(s.textModel());return i<0?s.textModel():MODEL_NAMES[i];}
    static String modelSummary(Settings s){if(!s.provider().equals("openai"))return "Tu servidor · "+modelName(s);return "OpenAI · "+modelName(s)+(s.speakersMode().equals("never")?"":" + separación de voces");}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);
        Intent in=getIntent();voiceFlow=in.getBooleanExtra("voice",false);returnOnBack=in.getBooleanExtra("back",false);inboxFlow=in.getBooleanExtra("inbox",false);
        render();
        if(state==null)page.post(()->openFrom(in));
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("voice",false))voiceFlow=true;if(intent.getBooleanExtra("inbox",false))inboxFlow=true;openFrom(intent);}
    /** Abre directo la hoja que pidió otra pantalla (p. ej. «Elegir carpeta rápida» desde una grabación). */
    private void openFrom(Intent intent){
        if(isFinishing())return;
        if(intent.getBooleanExtra("focusKey",false)&&!settings.hasKey())keySheet();
        else if(intent.getBooleanExtra("voice",false)&&voiceRow!=null)voiceSheet();
        else if(intent.getBooleanExtra("inbox",false))saveSheet();
        else if(intent.getBooleanExtra("noteAi",false))noteAiSheet();
    }
    /** Abierta desde otra pantalla para un paso puntual (Mi voz, carpeta rápida): Atrás (o terminar) vuelve ahí, no a Inicio. */
    private boolean voiceFlow,returnOnBack,inboxFlow;
    @Override public void onBackPressed(){if(voiceFlow||returnOnBack||inboxFlow){finish();return;}navigate(0);}

    private void render(){
        shell(null,2);largeTitle(page,"Ajustes",null);
        page.addView(statusCard(),Ui.fill());
        boolean openai=settings.provider().equals("openai");

        // 1. Tu flujo: lo que pasa con cada grabación, de principio a fin.
        page.addView(ui.section("Tu flujo"));LinearLayout flow=ui.group();page.addView(flow,Ui.fill());
        saveRow=ui.listRow(R.drawable.ic_inbox,"Guardado rápido","Donde guarda el botón de cada grabación",inboxValue());saveRow.onClick(v->saveSheet());ui.addRow(flow,saveRow);
        View[] note={null};
        note[0]=ui.switchRow(R.drawable.ic_doc,"Nota para tu segundo cerebro",noteSubtitle(settings.noteAuto()),settings.noteAuto(),on->{
            settings.prefs.edit().putBoolean("noteAuto",on).apply();Diagnostics.event("setting_changed",null,"action","note_auto","result",on);
            subtitle(note[0],noteSubtitle(on));if(on&&!noteReady())page.post(this::noteMissingKey);});
        ui.addRow(flow,note[0]);
        Ui.Row noteAi=ui.listRow(R.drawable.ic_sparkle,"IA de la nota",noteReady()?null:noteMissing(),noteAiValue());noteAi.onClick(v->noteAiSheet());ui.addRow(flow,noteAi);
        ui.addRow(flow,ui.switchRow(R.drawable.ic_bolt,"Transcribir automáticamente","Al guardar una grabación o importar un audio",settings.automatic(),on->{toggle("automatic",on);if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},101);}));
        ui.addRow(flow,ui.switchRow(R.drawable.ic_title,"Resumen al terminar","Nombrar la grabación y elegir el siguiente paso",settings.askTitle(),on->toggle("askTitle",on)));
        Ui.Row dates=ui.listRow(R.drawable.ic_edit,"Agregar fecha a las existentes","Para las grabaciones anteriores a esta opción",null);dates.onClick(v->offerDatesForExisting());
        ui.addRow(flow,ui.switchRow(R.drawable.ic_calendar,"Fecha delante del nombre","Ej.: "+Recording.isoDate(System.currentTimeMillis())+" Reunión",settings.datePrefix(),on->{settings.prefs.edit().putBoolean("datePrefix",on).apply();Diagnostics.event("setting_changed",null,"action","date_prefix","result",on);showRow(dates,on);if(on)offerDatesForExisting();}));
        ui.addRow(flow,dates);showRow(dates,settings.datePrefix());
        voiceRow=null;
        if(openai&&settings.canSeparate()){voiceRow=ui.listRow(R.drawable.ic_mic_fill,"Mi voz","Para que te reconozca al separar voces",voiceValue());voiceRow.onClick(v->voiceSheet());ui.addRow(flow,voiceRow);}
        page.addView(more("Guarda la nota o el texto con un toque desde cada grabación.","Tu flujo",
            "Guardado rápido: la carpeta donde el botón de cada grabación deja la nota (.md) o el texto (.txt), por ejemplo tu 0-Inbox de Google Drive. Si después corriges voces o nombres, «Actualizar» reemplaza el mismo archivo, sin crear copias.\n\n"
            +"Nota para tu segundo cerebro: una IA arma un resumen con decisiones, tareas y frases clave a partir del texto de la transcripción (no se vuelve a enviar el audio). Automática, se arma al terminar cada transcripción; si la apagas, la pides con un toque desde la grabación. Revísala antes de guardarla: puede tener errores.\n\n"
            +"Mi voz: una muestra de 10 s para que la app te reconozca con tu nombre al separar voces."));

        // 2. Servicio de transcripción: quién transcribe y con qué clave. El resultado de la comprobación queda en su fila.
        page.addView(ui.section("Servicio de transcripción"));LinearLayout api=ui.group();page.addView(api,Ui.fill());
        ui.addRow(api,ui.listRow(R.drawable.ic_globe,"Proveedor",null,openai?"OpenAI":"Tu servidor").onClick(v->providerSheet()));
        if(openai)ui.addRow(api,ui.listRow(R.drawable.ic_wave,"Modelo de texto",null,modelName(settings)).onClick(v->modelSheet()));
        else{Ui.Row server=ui.listRow(R.drawable.ic_server,"Servidor y modelo",settings.prefs.getString("customBase","Sin configurar"),null);ui.oneLine(server.subtitle);ui.addRow(api,server.onClick(v->custom()));}
        if(settings.canSeparate())ui.addRow(api,ui.listRow(R.drawable.ic_people,"Separar voces",null,SPEAKER_NAMES[Math.max(0,Arrays.asList(SPEAKER_MODES).indexOf(settings.speakersMode()))]).onClick(v->speakersSheet()));
        ui.addRow(api,ui.listRow(R.drawable.ic_key,"Clave de API",null,settings.hasKey()?"Configurada":"Falta").onClick(v->keySheet()));
        verifyRow=ui.listRow(R.drawable.ic_network_check,"Comprobar conexión",verifyText(),null);verifyRow.subtitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);verifyRow.onClick(v->verify());ui.addRow(api,verifyRow);paintVerify();
        ui.addRow(api,ui.listRow(R.drawable.ic_translate,"Idioma del audio",null,settings.language().equals("es")?"Español":"Automático").onClick(v->
            sheet("Idioma del audio","Indicar el idioma mejora la precisión.").choice("Español",null,settings.language().equals("es"),()->set("language","es"))
                .choice("Detección automática","Para audios en otros idiomas o mezclados",!settings.language().equals("es"),()->set("language","")).show()));
        page.addView(openai
            ?more("El uso se cobra en tu cuenta de OpenAI. Tu clave queda cifrada en este teléfono.","Servicio de transcripción","El modelo de texto se usa cuando no separas voces. Para separar voces (Persona 1, Persona 2…) se usa GPT-4o Diarize.\n\nEl uso se cobra en tu cuenta de OpenAI: pagas solo lo que transcribes, sin suscripción.\n\nLa clave se guarda cifrada en este teléfono y nunca aparece en los informes de soporte.\n\n«Comprobar conexión» confirma que la clave funciona y que el modelo existe. No prueba todavía la carga de audio, el saldo ni la separación de voces.")
            :more("Tu servidor debe ser compatible con OpenAI. Tu clave queda cifrada en este teléfono.","Servicio de transcripción","Tu servidor debe implementar /audio/transcriptions igual que OpenAI. Si además admite diarized_json y chunking_strategy, puede separar voces.\n\nLa clave se guarda cifrada en este teléfono y nunca aparece en los informes de soporte. Si cambias la URL, se borra la clave anterior."));

        // 3. Energía y red: cuándo se envía el audio.
        page.addView(ui.section("Energía y red"));LinearLayout energy=ui.group();page.addView(energy,Ui.fill());
        ui.addRow(energy,ui.listRow(R.drawable.ic_wifi,"Red para enviar audio",null,settings.wifiOnly()?"Solo Wi-Fi":"Wi-Fi y datos").onClick(v->
            sheet("Red para enviar audio","Los audios pueden pesar varios MB.").choice("Solo Wi-Fi","O cualquier red no medida",settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",true).apply();changed("wifi");})
                .choice("Wi-Fi y datos móviles","Empieza antes, usa tu plan de datos",!settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",false).apply();changed("wifi");}).show()));
        ui.addRow(energy,ui.switchRow(R.drawable.ic_battery,"Solo mientras carga",null,settings.charging(),on->toggle("charging",on)));
        batteryRow=ui.listRow(R.drawable.ic_battery,"Con la pantalla bloqueada",null,batteryValue());batteryRow.onClick(v->RecordingActions.allowBackground(this));ui.addRow(energy,batteryRow);
        page.addView(more("Aplica también cuando transcribes a mano.","Energía y red","Con batería baja, el trabajo espera. Android puede retrasarlo unos minutos.\n\nPara que transcriba con el teléfono bloqueado, permite a Voz local usar batería en segundo plano («Con la pantalla bloqueada»). Solo la usa mientras transcribe."));

        // 4. Copias
        page.addView(ui.section("Copias"));LinearLayout storage=ui.group();page.addView(storage,Ui.fill());
        folderRow=ui.listRow(R.drawable.ic_folder,"Carpeta de copias",null,"Desactivada");folderRow.onClick(v->folderSheet());ui.addRow(storage,folderRow);refreshFolder();
        page.addView(more("Cada grabación se copia con su audio y su texto.","Carpeta de copias","Cada grabación se copia a esa carpeta con su audio, su información y su transcripción, y la copia se actualiza sola.\n\nPuedes elegir una carpeta del teléfono o de Google Drive (si tienes su app): en el selector, abre el menú ☰.\n\nBorrar una grabación en Voz local no borra sus copias."));

        // 5. Apariencia
        String mode=AppTheme.appearance(this);String[] modes={"system","light","dark"},modeNames={"Automático","Claro","Oscuro"};
        page.addView(ui.section("Apariencia"));LinearLayout look=ui.group();page.addView(look,Ui.fill());
        ui.addRow(look,ui.listRow(R.drawable.ic_palette,"Tema",null,modeNames[Math.max(0,Arrays.asList(modes).indexOf(mode))]).onClick(v->{
            Sheet s=sheet("Tema",null);for(int i=0;i<3;i++){int k=i;s.choice(modeNames[i],i==0?"Igual que el teléfono":null,modes[i].equals(mode),()->{settings.prefs.edit().putString("appearance",modes[k]).apply();Diagnostics.event("setting_changed",null,"action","appearance","result",k);recreate();});}s.show();}));
        if(Build.VERSION.SDK_INT>=31){
            View dynamic=ui.switchRow(R.drawable.ic_sparkle,"Colores de tu fondo de pantalla","Adapta la app a los colores de tu teléfono",AppTheme.dynamicColor(this),on->{settings.prefs.edit().putBoolean("dynamicColor",on).apply();Diagnostics.event("setting_changed",null,"action","dynamic_color","result",on);page.postDelayed(this::recreate,200);});
            if(dynamic instanceof Ui.Row&&((Ui.Row)dynamic).subtitle.getParent() instanceof ViewGroup)((ViewGroup)((Ui.Row)dynamic).subtitle.getParent()).addView(palettePreview());
            ui.addRow(look,dynamic);
        }

        // 6. Ayuda y soporte
        page.addView(ui.section("Ayuda y soporte"));LinearLayout help=ui.group();page.addView(help,Ui.fill());
        String version=versionName();
        ui.addRow(help,ui.listRow(R.drawable.ic_info,"Novedades y versiones",null,version).onClick(v->Novedades.showAll(this)));
        ui.addRow(help,ui.listRow(R.drawable.ic_people,"Probar edición de voces","Un ejemplo que no usa tu clave",null).onClick(v->startActivity(new Intent(this,RecordingActivity.class).putExtra("demo",true))));
        ui.addRow(help,ui.listRow(R.drawable.ic_lifebuoy,"Compartir informe de soporte","Sin claves, títulos, audio ni texto",null).onClick(v->report()));
        ui.addRow(help,ui.listRow(R.drawable.ic_trash,"Borrar registros de diagnóstico",null,null).onClick(v->confirm("¿Borrar los registros locales?","Las grabaciones y transcripciones se conservan.","Borrar",true,()->{Diagnostics.clear(this);toast("Registros borrados");})));
        page.addView(ui.footnote("El registro técnico queda solo en este teléfono (máx. ~4 MB, 30 días) y se comparte únicamente si tú lo envías."));
        TextView footer=ui.text("Voz local "+version+" · Software libre · Licencia MIT",Type.BODY_MEDIUM,p.outline);footer.setGravity(Gravity.CENTER);footer.setPadding(0,ui.dp(S8),0,0);page.addView(footer,Ui.fill());
    }

    /** Tarjeta de estado: responde «¿está funcionando?». Tonal si falta un paso o falló la última comprobación; neutra si todo está listo. */
    private View statusCard(){
        boolean hasKey=settings.hasKey(),failed=hasKey&&verifyFailed(),ready=hasKey&&!failed;
        LinearLayout card=ui.card();card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        int fg=ready?p.onSurface:p.onSecondaryContainer,fg2=ready?p.onSurfaceVariant:p.onSecondaryContainer;
        int icon=ready?R.drawable.ic_check:failed?R.drawable.ic_alert:R.drawable.ic_key;
        card.addView(ui.tile(icon,ready?p.onPrimaryContainer:p.onSecondaryContainer,ready?p.primaryContainer:p.surfaceContainerLowest,40,22));card.addView(ui.space(S4));
        String title=ready?"Listo para transcribir":failed?"No se pudo conectar":"Falta un paso para transcribir";
        String detail=ready?modelSummary(settings):failed?"Toca para ver qué pasó y reintentar":"Agrega tu clave de API. Grabar funciona igual sin ella.";
        LinearLayout t=ui.column();t.addView(ui.text(title,Type.TITLE_MEDIUM,fg));TextView d=ui.text(detail,Type.BODY_MEDIUM,fg2);d.setPadding(0,ui.dp(2),0,0);t.addView(d);card.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(!ready){card.setBackground(ui.ripple(shape(this,p.secondaryContainer,R_CARD),R_CARD));card.setClickable(true);card.setAccessibilityDelegate(Ui.buttonRole());card.setContentDescription(title+". "+detail);
            card.setOnClickListener(v->{if(failed)verifyError(settings.prefs.getString("verifyMsg",""));else keySheet();});}
        return card;
    }
    private String versionName(){String v=Novedades.versionName(this);if(!v.isEmpty())return v;try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void set(String key,String value){settings.prefs.edit().putString(key,value).apply();changed(key);}
    private void toggle(String key,boolean on){settings.prefs.edit().putBoolean(key,on).apply();Diagnostics.event("setting_changed",null,"action",key,"result",on);Pipeline.schedule(this,true);}
    private void changed(String key){Diagnostics.event("setting_changed",null,"action",key);Pipeline.schedule(this,true);render();}
    private static void subtitle(View row,String text){if(row instanceof Ui.Row)((Ui.Row)row).setSubtitle(text==null?"":text);}
    /** Muestra u oculta una fila de grupo junto con su divisor. */
    private static void showRow(View row,boolean visible){
        int v=visible?View.VISIBLE:View.GONE;row.setVisibility(v);
        if(row.getParent() instanceof ViewGroup){ViewGroup g=(ViewGroup)row.getParent();int i=g.indexOfChild(row);if(i>0)g.getChildAt(i-1).setVisibility(v);}
    }
    /** Pie corto de un grupo + «Más información», que abre la explicación completa en una hoja. */
    private View more(String text,String title,String detail){
        LinearLayout box=ui.column();box.addView(ui.footnote(text));
        Ui.Btn b=ui.button("Más información",0,Ui.Style.PLAIN,v->message(title,detail));b.setMinimumHeight(ui.dp(48));b.setContentDescription("Más información: "+title);
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginStart(ui.dp(S1));box.addView(b,lp);return box;
    }
    /** Vista previa en vivo de los colores que Android sacaría de tu fondo de pantalla. */
    private View palettePreview(){
        LinearLayout r=ui.row();r.setPadding(0,ui.dp(S2),0,0);r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        try{Palette sys=new Palette(this,p.dark,true);
            for(int color:new int[]{sys.primary,sys.primaryContainer,sys.secondaryContainer,sys.surfaceContainerHighest}){
                View dot=new View(this);GradientDrawable o=oval(color);o.setStroke(Math.max(1,ui.dp(1)),p.outlineVariant);dot.setBackground(o);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(16),ui.dp(16));lp.setMarginEnd(ui.dp(S1));r.addView(dot,lp);}
        }catch(Exception e){r.setVisibility(View.GONE);}
        return r;
    }

    // ---------- Guardado rápido (0-Inbox) ----------
    private String inboxValue(){return settings.inboxTree().isEmpty()?"Sin elegir":settings.prefs.getString("saveTreeName","Carpeta elegida");}

    // ---------- Nota para tu segundo cerebro ----------
    private boolean claude(){return settings.noteProvider().equals("anthropic");}
    private static String noteSubtitle(boolean auto){return auto?"Se arma sola al terminar cada transcripción":"Con un toque, desde cada grabación";}
    private boolean noteReady(){try{return Notes.canGenerate(this);}catch(Throwable t){return true;}}
    private String noteMissing(){if(claude())return "Falta la clave de Anthropic";return settings.provider().equals("openai")?"Falta la clave de OpenAI":"Con tu servidor, elige Claude";}
    private String noteAiValue(){String m=settings.noteModel();return (claude()?"Claude":"OpenAI")+(m.isEmpty()?"":" · "+m);}
    /** La nota quedó activada pero su IA no tiene clave: se ofrece la salida justa. */
    private void noteMissingKey(){if(claude())anthropicKeyInput();else noteAiSheet();}
    private void noteAiSheet(){
        boolean claude=claude(),openaiReady=settings.provider().equals("openai")&&settings.hasKey(),hasClaudeKey=settings.hasAnthropicKey();
        String openaiDetail=openaiReady?"Usa tu clave de OpenAI, sin configurar nada más":settings.provider().equals("openai")?"Usa tu clave de OpenAI · aún no la agregas":"Solo si transcribes con OpenAI";
        Sheet s=sheet("IA de la nota","Arma el resumen, las decisiones y las tareas con el texto de la transcripción. No se vuelve a enviar el audio, así que cuesta poco.");
        s.choice("OpenAI",openaiDetail,!claude,()->setNoteProvider("openai"));
        s.choice("Claude (Anthropic)","Mejor redacción en español · "+(hasClaudeKey?"clave configurada":"requiere tu clave de Anthropic"),claude,()->{setNoteProvider("anthropic");if(!settings.hasAnthropicKey())anthropicKeyInput();});
        s.action(R.drawable.ic_edit,"Modelo: "+(settings.noteModel().isEmpty()?"el recomendado":settings.noteModel()),false,this::noteModelSheet);
        if(claude||hasClaudeKey)s.action(R.drawable.ic_key,hasClaudeKey?"Clave de Anthropic":"Agregar clave de Anthropic",false,this::anthropicKeySheet);
        s.show();
    }
    /** Cambiar de IA borra el modelo elegido a mano: el de una no sirve para la otra. */
    private void setNoteProvider(String provider){settings.prefs.edit().putString("noteProvider",provider).remove("noteModel").apply();Diagnostics.event("setting_changed",null,"action","note_provider","result",provider);render();}
    private void noteModelSheet(){
        EditText model=ui.field("Vacío = el recomendado","Modelo de la nota");model.setText(settings.noteModel());model.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Sheet s=sheet("Modelo de la nota","Déjalo vacío para usar el recomendado de "+(claude()?"Claude":"OpenAI")+". Escribe otro solo si conoces su nombre exacto.").add(model);
        s.primary("Guardar",Ui.Style.PRIMARY,()->{String m=model.getText().toString().trim();
            if(!m.isEmpty()&&!m.matches("[A-Za-z0-9_.:/-]{1,120}")){model.setError("Usa solo letras, números, puntos y guiones");return false;}
            settings.prefs.edit().putString("noteModel",m).apply();Diagnostics.event("setting_changed",null,"action","note_model","result",m.isEmpty()?"default":"custom");render();return true;});
        if(!settings.noteModel().isEmpty())s.secondary("Usar el recomendado",()->{settings.prefs.edit().remove("noteModel").apply();Diagnostics.event("setting_changed",null,"action","note_model","result","default");render();});
        s.secondary("Cancelar",null).show();
    }
    private void anthropicKeySheet(){
        if(settings.hasAnthropicKey()){sheet("Clave de Anthropic","Configurada y cifrada en este teléfono. Se usa solo para armar la nota y nunca aparece en informes.")
            .action(R.drawable.ic_edit,"Reemplazar clave",false,this::anthropicKeyInput)
            .action(R.drawable.ic_trash,"Eliminar clave",true,()->confirm("¿Eliminar la clave de Anthropic?","Sin clave, Claude no podrá armar tus notas. Puedes volver a OpenAI en «IA de la nota».","Eliminar",true,()->{try{settings.saveAnthropicKey("");Diagnostics.event("setting_changed",null,"action","anthropic_key","result","deleted");render();}catch(Exception e){message("Clave","No se pudo eliminar.");}})).show();return;}
        anthropicKeyInput();
    }
    private void anthropicKeyInput(){
        secretInput("Agregar clave de Anthropic","Créala en console.anthropic.com → API Keys y pégala aquí. Se usa solo para armar la nota con Claude: se envía el texto de la transcripción, no el audio. Se guarda cifrada y deja de mostrarse.","sk-ant-…","Clave de Anthropic",
            settings::saveAnthropicKey,()->{Diagnostics.event("setting_changed",null,"action","anthropic_key");render();});
    }

    // ---------- Servicio de transcripción ----------
    private void providerSheet(){boolean openai=settings.provider().equals("openai");
        sheet("Proveedor","Cada proveedor usa su propia clave.").choice("OpenAI","Recomendado · separación de voces",openai,()->set("provider","openai"))
            .choice("Servidor compatible","Cualquier API con /audio/transcriptions",!openai,()->set("provider","custom")).show();}
    private void modelSheet(){String current=settings.textModel();Sheet s=sheet("Modelo de texto","Se usa cuando no separas voces. Si un audio falla, probar otro modelo puede ayudar.");
        for(int i=0;i<MODELS.length;i++){String m=MODELS[i];double rate=Pricing.perMinute(m);s.choice(MODEL_NAMES[i],MODEL_DETAILS[i]+(rate>0?" · "+Pricing.usd(rate)+"/min":""),m.equals(current),()->set("openaiTextModel",m));}s.show();}
    private void speakersSheet(){String current=settings.speakersMode();Sheet s=sheet("Separar voces","Separar voces identifica a cada persona (Persona 1, Persona 2…), pero es más lento.");
        String[] details={"Te preguntamos al transcribir cada audio","Para reuniones y conversaciones","Solo texto: más rápido y económico"};
        for(int i=0;i<3;i++){String m=SPEAKER_MODES[i];s.choice(SPEAKER_NAMES[i],details[i]+(i==0?" (la transcripción automática separa voces)":""),m.equals(current),()->set("speakersMode",m));}s.show();}
    private void keySheet(){
        if(settings.hasKey()){sheet("Clave de API","Configurada y cifrada en este teléfono. Nunca aparece en informes.")
            .action(R.drawable.ic_edit,"Reemplazar clave",false,this::keyInput).action(R.drawable.ic_trash,"Eliminar clave",true,()->confirm("¿Eliminar la clave?","Las transcripciones pendientes quedarán en espera hasta que agregues otra.","Eliminar",true,()->{try{settings.saveKey("");getSystemService(JobScheduler.class).cancel(Pipeline.JOB_ID);render();}catch(Exception e){message("Clave","No se pudo eliminar.");}})).show();return;}
        keyInput();
    }
    /** Al guardar la clave se comprueba sola: el resultado queda en la fila, sin otro aviso que cerrar. */
    private void keyInput(){
        boolean openai=settings.provider().equals("openai");
        secretInput("Agregar clave de API",openai?"Créala en platform.openai.com → API keys y pégala aquí. Se guarda cifrada y deja de mostrarse.":"Pega la clave de tu servidor. Se guarda cifrada.",openai?"sk-…":"Clave del servidor","Clave de API",
            settings::saveKey,()->{Diagnostics.event("setting_changed",null,"action","api_key");Pipeline.schedule(this,true);render();page.post(this::verify);});
    }
    interface Secret{void save(String value)throws Exception;}
    /** Hoja segura para pegar una clave: campo oculto, sin autocompletar ni capturas de pantalla. */
    private void secretInput(String title,String message,String hint,String description,Secret secret,Runnable saved){
        EditText input=ui.field(hint,description);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Sheet s=sheet(title,message).add(input);
        s.primary("Guardar clave",Ui.Style.PRIMARY,()->{try{if(input.length()==0){input.setError("Pega tu clave");return false;}secret.save(input.getText().toString());input.setText("");saved.run();toast("Clave guardada");return true;}catch(Exception e){input.setError("No se pudo guardar. Revisa que no tenga espacios.");return false;}})
            .secondary("Cancelar",null).secure().show();
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    // ---------- Comprobar conexión (resultado en la misma fila, guardado en prefs) ----------
    /** A qué configuración corresponde la última comprobación: si cambias proveedor, modelo o clave, deja de valer. */
    private String verifyTarget(){
        String provider=settings.provider();
        String what=provider.equals("openai")?settings.textModel()+"|"+settings.defaultSpeakers():settings.prefs.getString("customBase","")+"|"+settings.prefs.getString("customModel","")+"|"+settings.prefs.getBoolean("customSpeakers",false);
        return provider+"|"+what+"|"+settings.prefs.getString(settings.prefix()+"keyEncrypted","").hashCode();
    }
    private boolean verifyValid(){return settings.prefs.getLong("verifyAt",0)>0&&verifyTarget().equals(settings.prefs.getString("verifyFor",""));}
    private boolean verifyFailed(){return verifyValid()&&!settings.prefs.getBoolean("verifyOk",true);}
    private String verifyText(){
        if(verifying)return "Comprobando…";
        if(!settings.hasKey())return "Primero agrega tu clave";
        if(!verifyValid())return "Confirma que tu clave funciona";
        long at=settings.prefs.getLong("verifyAt",0);
        if(settings.prefs.getBoolean("verifyOk",false))return "✓ Conectado · "+seconds(settings.prefs.getLong("verifyMs",0))+" · "+ago(at);
        return "No se pudo conectar · "+ago(at);
    }
    private void paintVerify(){if(verifyRow==null)return;verifyRow.setSubtitle(verifyText());verifyRow.subtitle.setTextColor(!verifying&&verifyFailed()?p.error:p.onSurfaceVariant);verifyRow.setEnabled(!verifying);}
    private void verify(){
        if(!settings.hasKey()){keySheet();return;}
        if(verifying)return;
        verifying=true;paintVerify();
        if(http!=null)http.cancel();HttpApi call=new HttpApi();http=call;String target=verifyTarget();boolean openai=settings.provider().equals("openai");
        io.execute(()->{String reason=null;long started=SystemClock.elapsedRealtime();
            try{new OpenAiClient(call).verify(settings.config());}
            catch(Exception e){reason=e instanceof HttpApi.UserAction?e.getMessage():openai?"No se pudo conectar. Revisa tu conexión a internet.":"No se pudo conectar. Revisa tu conexión y la URL del servidor.";}
            if(call.cancelled)return; // se salió de Ajustes: no se guarda un fallo que no fue
            long ms=SystemClock.elapsedRealtime()-started;boolean ok=reason==null;String why=reason;
            settings.prefs.edit().putLong("verifyAt",System.currentTimeMillis()).putBoolean("verifyOk",ok).putLong("verifyMs",ms).putString("verifyFor",target).putString("verifyMsg",ok?"":why).apply();
            Diagnostics.event("setting_changed",null,"action","verify","result",ok,"elapsed_ms",ms);
            runOnUiThread(()->{verifying=false;if(isDestroyed()||isFinishing())return;render();
                if(verifyRow!=null){Ui.haptic(verifyRow,ok?Ui.Haptic.CONFIRM:Ui.Haptic.REJECT);verifyRow.announceForAccessibility(ok?"Conectado":"No se pudo conectar");}
                if(!ok)verifyError(why);});
        });
    }
    /** Los errores siguen explicándose, con su salida: revisar la clave o reintentar. */
    private void verifyError(String reason){
        String why=reason==null||reason.isEmpty()?"Revisa tu conexión y vuelve a intentarlo.":reason;
        Sheet s=sheet("No se pudo conectar",why);
        if(why.toLowerCase(CL).contains("clave"))s.primary("Revisar la clave",this::keySheet);else s.primary("Reintentar",this::verify);
        s.secondary("Cerrar",null).show();
    }
    /** «0,9 s». */
    static String seconds(long ms){return String.format(CL,"%.1f s",ms/1000.0);}
    /** «recién», «hace 5 min», «hace 2 h», «ayer», «hace 3 días». */
    static String ago(long at){
        long min=Math.max(0,System.currentTimeMillis()-at)/60000;
        if(min<1)return "recién";if(min<60)return "hace "+min+" min";
        long h=min/60;if(h<24)return "hace "+h+" h";
        long d=h/24;return d==1?"ayer":"hace "+d+" días";
    }
    private void custom(){
        LinearLayout box=ui.column();EditText base=ui.labeled(box,"URL base HTTPS","https://proveedor.com/v1");base.setText(settings.prefs.getString("customBase",""));base.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText model=ui.labeled(box,"Identificador del modelo","whisper-1");model.setText(settings.prefs.getString("customModel",""));
        CheckBox voices=new CheckBox(this);voices.setText("Admite diarized_json y chunking_strategy");voices.setTextColor(p.onSurface);voices.setButtonTintList(android.content.res.ColorStateList.valueOf(p.primary));voices.setChecked(settings.prefs.getBoolean("customSpeakers",false));voices.setPadding(ui.dp(S1),ui.dp(S3),0,ui.dp(S3));voices.setMinHeight(ui.dp(48));box.addView(voices);
        sheet("Servidor compatible","Enviarás audio y tu clave a este servidor. Si cambias la URL, se borra la clave anterior.").add(box)
            .primary("Guardar",Ui.Style.PRIMARY,()->{try{ProviderConfig config=new ProviderConfig("custom",base.getText().toString().trim(),model.getText().toString().trim(),"",voices.isChecked());if(!config.base.equals(settings.prefs.getString("customBase","")))settings.saveKey("");settings.prefs.edit().putString("customBase",config.base).putString("customModel",config.model).putBoolean("customSpeakers",config.speakers).apply();Pipeline.schedule(this,true);render();return true;}catch(Exception e){base.setError("Usa una URL HTTPS sin credenciales ni parámetros y un modelo válido.");return false;}})
            .secondary("Cancelar",null).show();
    }

    // ---------- Carpetas ----------
    private void folderSheet(){
        boolean has=!settings.prefs.getString("localTree","").isEmpty();
        Sheet s=sheet("Carpeta de copias",has?"Las grabaciones se copian automáticamente a esta carpeta.":"Elige o crea una carpeta. En el selector, abre el menú ☰ para ver Google Drive u otras ubicaciones.");
        s.action(R.drawable.ic_folder,has?"Cambiar carpeta":"Elegir carpeta",false,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),PICK_FOLDER);}catch(ActivityNotFoundException e){message("Carpeta","Este teléfono no permite elegir carpetas.");}});
        if(has){s.action(R.drawable.ic_refresh,"Actualizar todas las copias",false,()->{for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast("Actualizando copias…");});
            s.action(R.drawable.ic_close,"Dejar de copiar",true,()->{String old=settings.prefs.getString("localTree","");settings.prefs.edit().remove("localTree").apply();try{getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}refreshFolder();});}
        s.show();
    }
    /** Aplica la fecha a los nombres de las grabaciones que ya existen (se pregunta primero). */
    private void offerDatesForExisting(){
        confirm("¿Agregar la fecha a tus grabaciones actuales?","Las nuevas la llevarán siempre. Esto la agrega también a las que ya tienes (las que ya empiezan con fecha no cambian).","Agregar a todas",false,()->io.execute(()->{int n=0;
            for(Recording r:Recording.list(this)){String before=r.title;try{r.save(this);if(!before.equals(r.title)){Pipeline.edited(this,r.id);n++;}}catch(Exception ignored){}}
            int changed=n;runOnUiThread(()->toast(changed==1?"1 nombre actualizado":changed+" nombres actualizados"));}));
    }
    private void saveSheet(){
        boolean has=!settings.inboxTree().isEmpty();
        Sheet s=sheet("Guardado rápido",has?"El botón de cada grabación guarda la nota (.md) o el texto (.txt) en «"+settings.prefs.getString("saveTreeName","")+"». Si corriges algo después, reemplaza el mismo archivo.":"Elige la carpeta donde quieres dejar siempre tus notas y transcripciones, por ejemplo tu 0-Inbox. En el selector abre el menú ☰ y elige Drive para usar una carpeta de Google Drive.");
        s.action(R.drawable.ic_folder,has?"Cambiar carpeta":"Elegir carpeta",false,()->{try{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);String last=settings.prefs.getString("lastSaveUri","");if(!last.isEmpty())pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(last));startActivityForResult(pick,PICK_SAVE);}catch(ActivityNotFoundException e){message("Guardado rápido","Este teléfono no permite elegir carpetas.");}});
        if(has)s.action(R.drawable.ic_close,"Quitar guardado rápido",true,()->{settings.prefs.edit().remove("saveTree").remove("saveTreeName").apply();if(saveRow!=null)saveRow.setValue("Sin elegir");});
        s.show();
    }
    /** Nombre visible de una carpeta elegida con el selector de Android. */
    private String folderName(Uri uri){String name="Carpeta elegida";try(android.database.Cursor c=getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)name=c.getString(0);}catch(Exception ignored){}return name;}
    /** Muestra el nombre real de la carpeta (y si es Drive) en vez del identificador interno. */
    private void refreshFolder(){
        String tree=settings.prefs.getString("localTree","");if(tree.isEmpty()){folderRow.setValue("Desactivada");return;}
        folderRow.setValue("…");Ui.Row row=folderRow;io.execute(()->{Uri uri=Uri.parse(tree);String name=folderName(uri);
            String authority=uri.getAuthority()==null?"":uri.getAuthority();String label=(authority.contains("google.android.apps.docs")?"Drive · ":"")+name;
            runOnUiThread(()->{if(!isDestroyed())row.setValue(label);});});
    }
    private void report(){toast("Preparando informe…");io.execute(()->{try{Diagnostics.export(this);runOnUiThread(()->shareFile("support.txt","Informe de soporte"));}catch(Exception e){runOnUiThread(()->message("Informe","No se pudo generar el informe."));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_SAVE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri tree=data.getData();
            try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                io.execute(()->{String name=folderName(tree);settings.prefs.edit().putString("saveTree",tree.toString()).putString("saveTreeName",name).apply();Diagnostics.event("setting_changed",null,"action","save_tree","result",tree.getAuthority()!=null&&tree.getAuthority().contains("google.android.apps.docs")?"drive":"device");
                    runOnUiThread(()->{if(isDestroyed())return;if(saveRow!=null)saveRow.setValue(name);toast("Guardado rápido en "+name);if(inboxFlow)finish();});});}
            catch(Exception e){message("Guardado rápido","No se obtuvo permiso de escritura en esa carpeta. Elige otra.");}
        }
        if(request==PICK_FOLDER&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try{Uri tree=data.getData();if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new SecurityException();getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                settings.prefs.edit().putString("localTree",tree.toString()).apply();Diagnostics.event("setting_changed",null,"action","local_tree","result",tree.getAuthority()!=null&&tree.getAuthority().contains("google.android.apps.docs")?"drive":"device");refreshFolder();
                for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast("Carpeta guardada · copiando grabaciones");}
            catch(Exception e){message("Carpeta","No se obtuvo permiso de escritura. Elige otra carpeta.");}
        }
    }
    @Override protected void onDestroy(){if(http!=null)http.cancel();io.shutdown();stopVoice(false);releaseVoicePlayer();super.onDestroy();}

    // ---------- Mi voz ----------
    private static final int MIC_FOR_VOICE=62;
    private android.media.MediaRecorder voiceRecorder;private java.io.File voiceTmp;private long voiceStarted;private android.media.MediaPlayer voicePlayer;
    private final Handler voiceTimer=new Handler(Looper.getMainLooper());private String pendingVoiceName;private Sheet voiceRecording;
    /** Al salir de la pantalla Android silencia el micrófono: la toma se descarta en vez de guardar una muestra muda. */
    @Override protected void onStop(){if(voiceRecorder!=null){stopVoice(false);if(voiceRecording!=null)voiceRecording.dismiss();toast("Grabación de tu voz interrumpida · vuelve a intentarlo");}super.onStop();}
    private String voiceValue(){return Voices.has(this)?"Grabada · "+Voices.name(this):"Sin grabar";}
    /** Muestra de la voz del usuario: se envía en todos los bloques al separar voces para reconocerlo desde el inicio. */
    private void voiceSheet(){
        boolean has=Voices.has(this);
        Sheet s=sheet("Mi voz","Graba 10 segundos leyendo en voz alta. Al separar voces, tu voz va como muestra en todo el audio para que la app te reconozca desde el inicio y ponga tu nombre. Queda en este teléfono y se envía a OpenAI solo junto con los audios que transcribes.");
        EditText name=ui.field("Tu nombre (p. ej. Konrad)","Tu nombre");name.setSingleLine(true);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        name.setText(settings.prefs.getString("myVoiceName",""));s.add(name);
        if(has){
            s.action(R.drawable.ic_play,"Escuchar mi muestra",false,this::playVoice);
            s.action(R.drawable.ic_mic_fill,"Grabar de nuevo",false,()->recordVoice(name.getText().toString()));
            s.action(R.drawable.ic_trash,"Borrar mi voz",true,()->confirm("¿Borrar tu voz?","Al separar voces ya no se usará tu muestra.","Borrar",true,()->{Voices.delete(this);if(voiceRow!=null)voiceRow.setValue(voiceValue());toast("Muestra borrada");}));
            s.primary("Guardar nombre",Ui.Style.PRIMARY,()->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Escribe tu nombre");return false;}Voices.setName(this,n);if(voiceRow!=null)voiceRow.setValue(voiceValue());return true;});
        }else s.primary("Grabar mi voz",Ui.Style.PRIMARY,()->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Escribe tu nombre");return false;}recordVoice(n);return true;});
        s.secondary("Cancelar",null).show();
    }
    private void recordVoice(String who){
        String n=who==null?"":who.trim();if(!n.isEmpty())Voices.setName(this,n);
        if(RecorderService.activeId!=null){message("Mi voz","Termina la grabación en curso antes de grabar tu muestra.");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingVoiceName=n;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},MIC_FOR_VOICE);return;}
        TextView status=ui.text("Toca Grabar y lee el texto con tu voz normal.",Type.BODY_MEDIUM,p.onSurfaceVariant);status.setFontFeatureSettings("tnum");status.setPadding(0,ui.dp(S3),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView script=ui.text("«Hola, soy "+Voices.name(this)+". Estoy grabando mi voz para que Voz local me reconozca en mis conversaciones y reuniones. Hoy es un buen día para ordenar ideas.»",Type.BODY_LARGE,p.onSurface);
        Sheet s=sheet("Lee en voz alta",null);s.add(script);s.add(status);voiceRecording=s;
        Ui.Btn go=ui.button("Grabar",R.drawable.ic_mic_fill,Ui.Style.RECORD,null);LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S4);s.body.addView(go,lp);
        go.setOnClickListener(v->{
            if(voiceRecorder==null){
                if(startVoice()){go.setText("Detener y guardar");Ui.haptic(go,Ui.Haptic.CONFIRM);
                    voiceTimer.post(new Runnable(){public void run(){long ms=android.os.SystemClock.elapsedRealtime()-voiceStarted;status.setText("Grabando · "+(ms/1000)+" s de "+(Voices.MAX_MS/1000));if(ms>=Voices.MAX_MS){s.dismiss();return;}voiceTimer.postDelayed(this,250);}});}
                else{s.dismiss();message("Mi voz","No se pudo usar el micrófono. Revisa el permiso y vuelve a intentarlo.");}
            }else s.dismiss();
        });
        // Cerrar la hoja guarda lo grabado; "Cancelar" lo descarta (la marca se pone antes de cerrar).
        boolean[] cancel={false};
        Ui.Btn no=ui.button("Cancelar",0,Ui.Style.PLAIN,v->{cancel[0]=true;s.dismiss();});LinearLayout.LayoutParams np=Ui.fill();np.topMargin=ui.dp(S1);s.body.addView(no,np);
        s.onDismiss(()->stopVoice(!cancel[0])).show();
    }
    private boolean startVoice(){
        try{voiceTmp=new java.io.File(getCacheDir(),"my-voice.m4a");voiceTmp.delete();
            voiceRecorder=Build.VERSION.SDK_INT>=31?new android.media.MediaRecorder(this):new android.media.MediaRecorder();
            voiceRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);voiceRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);voiceRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioEncodingBitRate(96000);voiceRecorder.setAudioSamplingRate(44100);voiceRecorder.setAudioChannels(1);voiceRecorder.setOutputFile(voiceTmp.getAbsolutePath());
            voiceRecorder.prepare();voiceRecorder.start();voiceStarted=android.os.SystemClock.elapsedRealtime();return true;
        }catch(Exception e){if(voiceRecorder!=null){voiceRecorder.release();voiceRecorder=null;}Diagnostics.event("recorder_failure",null,"action","my_voice","error_class",e.getClass().getSimpleName());return false;}
    }
    /** Detiene la muestra; si keep, la guarda (mínimo 3 s). */
    private void stopVoice(boolean keep){
        voiceTimer.removeCallbacksAndMessages(null);if(voiceRecorder==null)return;
        long ms=android.os.SystemClock.elapsedRealtime()-voiceStarted;boolean ok=false;
        try{voiceRecorder.stop();ok=true;}catch(RuntimeException ignored){}finally{voiceRecorder.release();voiceRecorder=null;}
        if(!keep||isFinishing()){if(voiceTmp!=null)voiceTmp.delete();return;}
        if(!ok||ms<Voices.MIN_MS){if(voiceTmp!=null)voiceTmp.delete();message("Mi voz","La muestra quedó muy corta. Graba al menos 3 segundos.");return;}
        java.io.File target=Voices.file(this);
        if(voiceTmp.renameTo(target)){if(voiceRow!=null)voiceRow.setValue(voiceValue());Diagnostics.event("setting_changed",null,"action","my_voice","result","recorded");
            if(voiceFlow){toast("Tu voz quedó guardada · toca Transcribir");finish();}else toast("Tu voz quedó guardada");}
        else message("Mi voz","No se pudo guardar la muestra. Vuelve a intentarlo.");
    }
    private void playVoice(){
        releaseVoicePlayer();
        try{voicePlayer=new android.media.MediaPlayer();voicePlayer.setDataSource(Voices.file(this).getAbsolutePath());voicePlayer.setOnCompletionListener(mp->releaseVoicePlayer());voicePlayer.prepare();voicePlayer.start();}
        catch(Exception e){releaseVoicePlayer();message("Mi voz","No se pudo reproducir la muestra.");}
    }
    private void releaseVoicePlayer(){if(voicePlayer!=null){voicePlayer.release();voicePlayer=null;}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MIC_FOR_VOICE){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recordVoice(pendingVoiceName);else message("Mi voz","Sin permiso de micrófono no se puede grabar tu muestra.");}
    }
}
