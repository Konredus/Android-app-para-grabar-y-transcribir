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
 * Extras del intent: focusKey (pedir la clave si falta), voice (grabar tu voz, o ver las voces conocidas, y volver), back (Atrás vuelve a
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
        else if(intent.getBooleanExtra("voice",false)&&voiceRow!=null){if(Voices.has(this))voiceSheet();else myVoiceSheet();}
        else if(intent.getBooleanExtra("inbox",false))saveSheet();
        else if(intent.getBooleanExtra("noteAi",false))noteAiSheet();
    }
    /** Abierta desde otra pantalla para un paso puntual (grabar tu voz, carpeta rápida): Atrás (o terminar) vuelve ahí, no a Inicio. */
    private boolean voiceFlow,returnOnBack,inboxFlow;
    @Override public void onBackPressed(){if(voiceFlow||returnOnBack||inboxFlow){finish();return;}navigate(0);}

    private void render(){
        shell(null,2);largeTitle(page,"Ajustes",null);
        page.addView(statusCard(),Ui.fill());
        boolean openai=settings.provider().equals("openai");

        // 1. Tu flujo: lo que pasa con cada grabación, de principio a fin.
        page.addView(ui.section("Tu flujo"));LinearLayout flow=ui.group();page.addView(flow,Ui.fill());
        saveRow=row(R.drawable.ic_inbox,"Guardado rápido","Donde guarda el botón de cada grabación",inboxValue());saveRow.onClick(v->saveSheet());add(flow,saveRow);
        View[] note={null};
        note[0]=toggleRow(R.drawable.ic_doc,"Nota para tu segundo cerebro",noteSubtitle(settings.noteAuto()),settings.noteAuto(),on->{
            settings.prefs.edit().putBoolean("noteAuto",on).apply();Diagnostics.event("setting_changed",null,"action","note_auto","result",on);
            subtitle(note[0],noteSubtitle(on));if(on&&!noteReady())page.post(this::noteMissingKey);});
        add(flow,note[0]);
        Ui.Row noteAi=row(R.drawable.ic_sparkle,"IA de la nota",noteReady()?null:noteMissing(),noteAiValue());noteAi.onClick(v->noteAiSheet());add(flow,noteAi);
        add(flow,toggleRow(R.drawable.ic_bolt,"Transcribir automáticamente","Al guardar una grabación o importar un audio",settings.automatic(),on->{toggle("automatic",on);if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},101);}));
        add(flow,toggleRow(R.drawable.ic_title,"Nombrar al terminar de grabar","Una hoja para poner el título y seguir",settings.askTitle(),on->toggle("askTitle",on)));
        Ui.Row dates=row(R.drawable.ic_edit,"Agregar fecha a las existentes","Para las grabaciones anteriores a esta opción",null);dates.onClick(v->offerDatesForExisting());
        add(flow,toggleRow(R.drawable.ic_calendar,"Fecha delante del nombre","Ej.: "+Recording.isoDate(System.currentTimeMillis())+" Reunión",settings.datePrefix(),on->{settings.prefs.edit().putBoolean("datePrefix",on).apply();Diagnostics.event("setting_changed",null,"action","date_prefix","result",on);showRow(dates,on);if(on)offerDatesForExisting();}));
        add(flow,dates);showRow(dates,settings.datePrefix());
        voiceRow=null;
        if(openai&&settings.canSeparate()){voiceRow=row(R.drawable.ic_voice,"Voces conocidas","Para reconocer a cada persona al separar voces",voiceValue());voiceRow.onClick(v->voiceSheet());add(flow,voiceRow);}
        page.addView(more("Guarda la nota o el texto con un toque desde cada grabación.","Tu flujo",
            "Guardado rápido: la carpeta donde el botón de cada grabación deja la nota (.md) o el texto (.txt), por ejemplo tu 0-Inbox de Google Drive. Si después corriges voces o nombres, «Actualizar» reemplaza el mismo archivo, sin crear copias.\n\n"
            +"Nota para tu segundo cerebro: una IA arma un resumen con decisiones, tareas y frases clave a partir del texto de la transcripción (no se vuelve a enviar el audio). Automática, se arma al terminar cada transcripción; si la apagas, la pides con un toque desde la grabación. Revísala antes de guardarla: puede tener errores.\n\n"
            +"Voces conocidas: muestras de 10 s de tu voz y de las personas con que más hablas, para que la app reconozca a cada una con su nombre al separar voces. Se usan hasta "+Transcriber.MAX_KNOWN+" por audio. También puedes guardar la voz de alguien desde una grabación: toca su nombre y «Guardar la voz de…»."));

        // 2. Servicio de transcripción: quién transcribe y con qué clave. El resultado de la comprobación queda en su fila.
        page.addView(ui.section("Servicio de transcripción"));LinearLayout api=ui.group();page.addView(api,Ui.fill());
        add(api,row(R.drawable.ic_globe,"Proveedor",null,openai?"OpenAI":"Tu servidor").onClick(v->providerSheet()));
        if(openai)add(api,row(R.drawable.ic_wave,"Modelo de texto",null,modelName(settings)).onClick(v->modelSheet()));
        else{Ui.Row server=row(R.drawable.ic_server,"Servidor y modelo",settings.prefs.getString("customBase","Sin configurar"),null);ui.oneLine(server.subtitle);add(api,server.onClick(v->custom()));}
        if(settings.canSeparate())add(api,row(R.drawable.ic_people,"Separar voces",null,SPEAKER_NAMES[Math.max(0,Arrays.asList(SPEAKER_MODES).indexOf(settings.speakersMode()))]).onClick(v->speakersSheet()));
        add(api,row(R.drawable.ic_key,"Clave de API",null,settings.hasKey()?"Configurada":"Falta").onClick(v->keySheet()));
        verifyRow=row(R.drawable.ic_network_check,"Comprobar conexión",verifyText(),null);verifyRow.subtitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);verifyRow.onClick(v->verify());add(api,verifyRow);paintVerify();
        add(api,row(R.drawable.ic_translate,"Idioma del audio",null,settings.language().equals("es")?"Español":"Automático").onClick(v->
            sheet("Idioma del audio","Indicar el idioma mejora la precisión.").choice("Español",null,settings.language().equals("es"),()->set("language","es"))
                .choice("Detección automática","Para audios en otros idiomas o mezclados",!settings.language().equals("es"),()->set("language","")).show()));
        page.addView(openai
            ?more("El uso se cobra en tu cuenta de OpenAI. Tu clave queda cifrada en este teléfono.","Servicio de transcripción","El modelo de texto se usa cuando no separas voces. Para separar voces (Persona 1, Persona 2…) se usa GPT-4o Diarize.\n\nEl uso se cobra en tu cuenta de OpenAI: pagas solo lo que transcribes, sin suscripción.\n\nLa clave se guarda cifrada en este teléfono y nunca aparece en los informes de soporte.\n\n«Comprobar conexión» confirma que la clave funciona y que el modelo existe. No prueba todavía la carga de audio, el saldo ni la separación de voces.")
            :more("Tu servidor debe ser compatible con OpenAI. Tu clave queda cifrada en este teléfono.","Servicio de transcripción","Tu servidor debe implementar /audio/transcriptions igual que OpenAI. Si además admite diarized_json y chunking_strategy, puede separar voces.\n\nLa clave se guarda cifrada en este teléfono y nunca aparece en los informes de soporte. Si cambias la URL, se borra la clave anterior."));

        // 3. Energía y red: cuándo se envía el audio.
        page.addView(ui.section("Energía y red"));LinearLayout energy=ui.group();page.addView(energy,Ui.fill());
        add(energy,row(R.drawable.ic_wifi,"Red para enviar audio",null,settings.wifiOnly()?"Solo Wi-Fi":"Wi-Fi y datos").onClick(v->
            sheet("Red para enviar audio","Los audios pueden pesar varios MB.").choice("Solo Wi-Fi","O cualquier red no medida",settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",true).apply();changed("wifi");})
                .choice("Wi-Fi y datos móviles","Empieza antes, usa tu plan de datos",!settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",false).apply();changed("wifi");}).show()));
        add(energy,toggleRow(R.drawable.ic_battery,"Solo mientras carga",null,settings.charging(),on->toggle("charging",on)));
        batteryRow=row(R.drawable.ic_battery,"Con la pantalla bloqueada",null,batteryValue());batteryRow.onClick(v->RecordingActions.allowBackground(this));add(energy,batteryRow);
        page.addView(more("Aplica también cuando transcribes a mano.","Energía y red","Con batería baja, el trabajo espera. Android puede retrasarlo unos minutos.\n\nPara que transcriba con el teléfono bloqueado, permite a Verbapp usar batería en segundo plano («Con la pantalla bloqueada»). Solo la usa mientras transcribe."));

        // 4. Copias
        page.addView(ui.section("Copias"));LinearLayout storage=ui.group();page.addView(storage,Ui.fill());
        folderRow=row(R.drawable.ic_folder,"Carpeta de copias",null,"Desactivada");folderRow.onClick(v->folderSheet());add(storage,folderRow);refreshFolder();
        page.addView(more("Cada grabación se copia con su audio y su texto.","Carpeta de copias","Cada grabación se copia a esa carpeta con su audio, su información y su transcripción, y la copia se actualiza sola.\n\nPuedes elegir una carpeta del teléfono o de Google Drive (si tienes su app): en el selector, abre el menú ☰.\n\nBorrar una grabación en Verbapp no borra sus copias."));

        // 5. Apariencia: el tema se elige mirando cómo queda. «Colores de tu fondo de pantalla» se quitó en 0.7.0: Verbapp
        // tiene su propio verde (AppTheme.dynamicColor ya devuelve false) y la preferencia antigua queda guardada, sin leerse.
        page.addView(ui.section("Apariencia"));page.addView(themePicker(),Ui.fill());
        page.addView(ui.footnote("Automático usa el mismo tema que tu teléfono."));

        // 6. Ayuda y soporte
        page.addView(ui.section("Ayuda y soporte"));LinearLayout help=ui.group();page.addView(help,Ui.fill());
        String version=versionName();
        Ui.Row news=row(R.drawable.ic_info,"Novedades y versiones",null,version).onClick(v->Novedades.showAll(this));versionPill(news.value);add(help,news);
        add(help,row(R.drawable.ic_people,"Probar edición de voces","Un ejemplo que no usa tu clave",null).onClick(v->startActivity(new Intent(this,RecordingActivity.class).putExtra("demo",true))));
        add(help,row(R.drawable.ic_lifebuoy,"Compartir informe de soporte","Sin claves, títulos, audio ni texto",null).onClick(v->report()));
        add(help,row(R.drawable.ic_trash,"Borrar registros de diagnóstico",null,null).onClick(v->confirm("¿Borrar los registros locales?","Las grabaciones y transcripciones se conservan.","Borrar",true,()->{Diagnostics.clear(this);toast("Registros borrados");})));
        page.addView(ui.footnote("El registro técnico queda solo en este teléfono (máx. ~4 MB, 30 días) y se comparte únicamente si tú lo envías."));
        page.addView(footer(version),Ui.fill());
    }

    /**
     * Tarjeta de marca y estado (0.7.0). Arriba, el logo con el lema y unas ondas decorativas (las mismas barras grises que
     * rodean el micrófono en Grabar); abajo, un panel que responde «¿está funcionando?», con las mismas acciones de 0.6:
     * - listo: panel blanco con ✓ en verde de marca (no se toca: no hay nada que hacer);
     * - falta un paso (la clave o la dirección del servidor): panel menta con una flecha de tinta, que lleva a configurarlo;
     * - falló la última comprobación: panel en tono de error, que muestra qué pasó y permite reintentar.
     * El panel va a 8 dp del borde con esquinas de 16 dp: concéntrico con la tarjeta (24 dp), como los datos de «Tu semana».
     */
    private View statusCard(){
        // Servidor propio sin dirección: falta ese paso aunque la clave esté (sin dirección no se envía nada).
        boolean hasKey=settings.hasKey(),server=hasKey&&settings.needsServer(),failed=hasKey&&!server&&verifyFailed(),ready=hasKey&&!server&&!failed;
        LinearLayout card=ui.card();card.setPadding(ui.dp(S2),ui.dp(S2),ui.dp(S2),ui.dp(S2));
        LinearLayout head=ui.row();head.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S4));
        LinearLayout brand=ui.column();brand.addView(ui.brand(22),Ui.wrap());
        TextView motto=ui.text("Tus palabras, para siempre",Type.ITEM,p.onSurfaceVariant);motto.setPadding(0,ui.dp(S1),0,0);brand.addView(motto,Ui.wrap());
        head.addView(brand,Ui.wrap());
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,ui.dp(40),1);wp.setMarginStart(ui.dp(S4));head.addView(new WaveDeco(this,p.waveIdle),wp);
        card.addView(head,Ui.fill());

        int icon=ready?R.drawable.ic_check:failed?R.drawable.ic_alert:server?R.drawable.ic_server:R.drawable.ic_key,panel,fg,fg2,dotFg,dotBg;
        // Listo: blanco con alfa, el mismo panel de los datos de «Tu semana» (Ui.stat), un poco más claro que el vidrio de la tarjeta.
        if(ready){panel=p.dark?p.glass:0xB3FFFFFF;fg=p.onSurface;fg2=p.onSurfaceVariant;dotFg=p.onBrand;dotBg=p.brand;}
        else if(failed){panel=p.errorContainer;fg=fg2=p.onErrorContainer;dotFg=p.error;dotBg=p.surfaceContainerLowest;}
        else{panel=p.primaryContainer;fg=fg2=p.onPrimaryContainer;dotFg=p.onPrimaryContainer;dotBg=p.surfaceContainerLowest;}
        String title=ready?"Listo para transcribir":failed?"No se pudo conectar":"Falta un paso para transcribir";
        String detail=ready?modelSummary(settings):failed?"Toca para ver qué pasó y reintentar":server?"Configura la dirección de tu servidor.":"Agrega tu clave de API. Grabar funciona igual sin ella.";
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
            status.setOnClickListener(v->{if(failed)verifyError(settings.prefs.getString("verifyMsg",""));else if(server)custom();else keySheet();});Ui.pressable(status);
        }
        card.addView(status,Ui.fill());
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
    /** Pie con la marca: el logo, la versión con la licencia de la app y, aparte, la de la letra Outfit (OFL). */
    private View footer(String version){
        LinearLayout f=ui.column();f.setGravity(Gravity.CENTER_HORIZONTAL);f.setPadding(ui.dp(S4),ui.dp(S10),ui.dp(S4),ui.dp(S2));
        f.addView(new Glass.BrandMark(this,p.onSurfaceVariant,p.primary),new LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)));
        TextView app=ui.text("Verbapp"+(version.isEmpty()?"":" "+version)+" · Software libre · Licencia MIT",Type.LABEL_LARGE,p.onSurfaceVariant);app.setGravity(Gravity.CENTER);app.setPadding(0,ui.dp(S2),0,0);f.addView(app,Ui.fill());
        TextView font=ui.text("Tipografía Outfit · SIL Open Font License",Type.LABEL_MEDIUM,p.onSurfaceVariant);font.setGravity(Gravity.CENTER);font.setPadding(0,ui.dp(2),0,0);f.addView(font,Ui.fill());
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
        String mode=AppTheme.appearance(this);String[] modes={"system","light","dark"},names={"Automático","Claro","Oscuro"},details={"igual que el teléfono","siempre claro","siempre oscuro"};
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
            tile.setContentDescription("Tema "+names[i].toLowerCase(CL)+", "+details[i]+(on?", seleccionado":""));
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
    /**
     * Al guardar la clave se comprueba sola: el resultado queda en la fila, sin otro aviso que cerrar. Con un servidor
     * propio aún sin dirección, primero se pide la dirección (la clave no se envía a ninguna parte hasta tenerla).
     */
    private void keyInput(){
        boolean openai=settings.provider().equals("openai");
        secretInput("Agregar clave de API",openai?"Créala en platform.openai.com → API keys y pégala aquí. Se guarda cifrada y deja de mostrarse.":"Pega la clave de tu servidor. Se guarda cifrada.",openai?"sk-…":"Clave del servidor","Clave de API",
            settings::saveKey,()->{Diagnostics.event("setting_changed",null,"action","api_key");Pipeline.schedule(this,true);render();page.post(settings.needsServer()?this::custom:this::verify);});
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
        if(settings.needsServer()){custom();return;}
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
            .primary("Guardar",Ui.Style.PRIMARY,()->{try{ProviderConfig config=new ProviderConfig("custom",base.getText().toString().trim(),model.getText().toString().trim(),"",voices.isChecked());
                // La clave anterior se borra solo si cambias a OTRO servidor; la que agregaste antes de poner la primera dirección se conserva.
                String old=settings.customBase();boolean moved=!old.isEmpty()&&!config.base.equals(old);if(moved)settings.saveKey("");
                settings.prefs.edit().putString("customBase",config.base).putString("customModel",config.model).putBoolean("customSpeakers",config.speakers).apply();Pipeline.schedule(this,true);render();
                if(old.isEmpty()&&settings.hasKey())page.post(this::verify);return true;}catch(Exception e){base.setError("Usa una URL HTTPS sin credenciales ni parámetros y un modelo válido.");return false;}})
            .secondary("Cancelar",null).show();
    }

    // ---------- Carpetas ----------
    private void folderSheet(){
        boolean has=!settings.prefs.getString("localTree","").isEmpty();
        Sheet s=sheet("Carpeta de copias",has?"Las grabaciones se copian automáticamente a esta carpeta.":"Elige o crea una carpeta. En el selector, abre el menú ☰ para ver Google Drive u otras ubicaciones.");
        s.action(R.drawable.ic_folder,has?"Cambiar carpeta":"Elegir carpeta",false,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),PICK_FOLDER);}catch(ActivityNotFoundException e){message("Carpeta","Este teléfono no permite elegir carpetas.");}});
        if(has){s.action(R.drawable.ic_refresh,"Actualizar todas las copias",false,()->{for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast("Actualizando copias…");});
            // El permiso es uno por carpeta: si el guardado rápido usa la misma, se conserva (sin él, «Guardar en 0-Inbox» fallaría).
            s.action(R.drawable.ic_close,"Dejar de copiar",true,()->{String old=settings.prefs.getString("localTree","");settings.prefs.edit().remove("localTree").apply();
                if(!old.isEmpty()&&!old.equals(settings.inboxTree()))try{getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}refreshFolder();});}
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

    // ---------- Voces conocidas ----------
    private static final int MIC_FOR_VOICE=62;
    private android.media.MediaRecorder voiceRecorder;private java.io.File voiceTmp;private long voiceStarted;private android.media.MediaPlayer voicePlayer;private Runnable voiceEnded;
    private final Handler voiceTimer=new Handler(Looper.getMainLooper());private Sheet voiceRecording;
    /** De quién es la muestra que se graba: tu voz (me), una voz guardada (id: se reemplaza su muestra) o una nueva (id null). */
    private static final class VoiceTake{final String name,id;final boolean me;VoiceTake(String name,String id,boolean me){this.name=name;this.id=id;this.me=me;}}
    private VoiceTake voiceTake,pendingVoice;
    /** Al salir de la pantalla Android silencia el micrófono: la toma se descarta en vez de guardar una muestra muda. */
    @Override protected void onStop(){if(voiceRecorder!=null){stopVoice(false);if(voiceRecording!=null)voiceRecording.dismiss();toast("Grabación de la voz interrumpida · vuelve a intentarlo");}releaseVoicePlayer();super.onStop();}
    /** «Konrad (tú) · Fran», o «Ninguna». */
    private String voiceValue(){StringBuilder b=new StringBuilder();for(Voices.Voice v:Voices.list(this))b.append(b.length()==0?"":" · ").append(v.label());return b.length()==0?"Ninguna":b.toString();}
    private void refreshVoices(){if(voiceRow!=null)voiceRow.setValue(voiceValue());}
    /**
     * Voces conocidas: tu voz y las de las personas con que más hablas. Al separar voces van como muestra en todo el
     * audio (hasta 4 por audio, la tuya primero) para reconocer a cada persona desde el inicio. Tocar una abre sus opciones.
     */
    private void voiceSheet(){
        List<Voices.Voice> all=Voices.list(this);
        Sheet s=sheet("Voces conocidas",all.isEmpty()
            ?"Graba 10 segundos de tu voz y de las personas con que más hablas. Al separar voces, estas muestras van en todo el audio para reconocer a cada una desde el inicio y ponerle su nombre."
            :"Al separar voces, estas muestras van en todo el audio para reconocer a cada persona desde el inicio y ponerle su nombre.");
        if(!all.isEmpty()){
            // Las voces van juntas en un panel claro (el «vidrio» de las hojas blancas), como un grupo de Ajustes.
            LinearLayout list=ui.column();list.setBackground(inset(R_CARD));list.setClipToOutline(true);
            for(int i=0;i<all.size();i++){if(i>0)list.addView(ui.separator(S4+40+S3));voiceItem(s,list,all.get(i),i);}
            s.add(list);
        }
        int used=Voices.used(this).size();
        TextView note=ui.text("Se usan hasta "+Transcriber.MAX_KNOWN+" voces por audio"+(used>Transcriber.MAX_KNOWN?": tienes "+used+" activas, así que van tu voz y las primeras por nombre.":".")
            +" Quedan en este teléfono y se envían a OpenAI solo junto con los audios que transcribes.",Type.BODY_SMALL,p.onSurfaceVariant);
        note.setPadding(0,ui.dp(all.isEmpty()?0:S3),0,ui.dp(S2));s.add(note);
        if(!Voices.has(this))s.action(R.drawable.ic_mic_fill,"Grabar mi voz",false,this::myVoiceSheet);
        s.action(R.drawable.ic_person,"Agregar otra voz",false,this::addVoiceSheet);
        s.secondary("Cerrar",null).show();
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
        if(v.me){TextView tag=ui.chip("tú",p.onPrimaryContainer,p.primaryContainer);AppTheme.type(tag,Type.LABEL_MEDIUM);tag.setPadding(ui.dp(S2),ui.dp(2),ui.dp(S2),ui.dp(2));LinearLayout.LayoutParams tp=Ui.wrap();tp.setMarginStart(ui.dp(S2));top.addView(tag,tp);}
        texts.addView(top);
        String status=v.use?"Se usa al transcribir":"No se usa";TextView st=ui.text(status,Type.BODY_MEDIUM,p.onSurfaceVariant);st.setPadding(0,ui.dp(2),0,0);texts.addView(st);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageView go=ui.icon(R.drawable.ic_chevron_down,p.onSurfaceVariant,20);go.setRotation(-90);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20));gp.setMarginStart(ui.dp(S2));row.addView(go,gp);
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setContentDescription(v.name+(v.me?", tu voz":"")+". "+status+". Ver opciones");
        row.setOnClickListener(x->{s.dismiss();voiceDetail(v.id);});
        list.addView(row,Ui.fill());
    }
    /** Opciones de una voz: escuchar, usarla o no al transcribir, cambiar nombre, grabar de nuevo y eliminar. */
    private void voiceDetail(String id){
        Voices.Voice v=Voices.get(this,id);if(v==null){voiceSheet();return;}
        String when=savedOn(v.createdAt);
        Sheet s=sheet(v.name,v.me?"Tu voz · "+when:Character.toUpperCase(when.charAt(0))+when.substring(1));
        // La muestra como mini reproductor (0.7.0): ▶ redondo de tinta y unas ondas que se tiñen de verde mientras suena.
        // Toda la fila es el botón; se anuncia «Escuchar» o «Detener», igual que el botón de 0.6.
        LinearLayout player=ui.row();player.setMinimumHeight(ui.dp(64));player.setPadding(ui.dp(S2),ui.dp(S2),ui.dp(S4),ui.dp(S2));
        FrameLayout knob=ui.tile(R.drawable.ic_play,p.onInk,p.ink,48,22);ImageView glyph=(ImageView)knob.getChildAt(0);player.addView(knob);player.addView(ui.space(S3));
        TextView what=ui.oneLine(ui.text("Escuchar la muestra",Type.TITLE_MEDIUM,p.onSurface));player.addView(what,Ui.wrap());
        WaveDeco wave=new WaveDeco(this,p.waveIdle);LinearLayout.LayoutParams wl=new LinearLayout.LayoutParams(0,ui.dp(28),1);wl.setMarginStart(ui.dp(S3));player.addView(wave,wl);
        player.setBackground(ui.ripple(inset(R_CARD),R_CARD));player.setClickable(true);player.setFocusable(true);player.setAccessibilityDelegate(Ui.buttonRole());player.setContentDescription("Escuchar");Ui.pressable(player);
        Runnable idle=()->{glyph.setImageResource(R.drawable.ic_play);what.setText("Escuchar la muestra");player.setContentDescription("Escuchar");wave.setColor(p.waveIdle);};
        player.setOnClickListener(x->{
            Diagnostics.event("ui_action",null,"screen",getClass().getSimpleName(),"action",String.valueOf(player.getContentDescription()));
            if(voicePlayer!=null){releaseVoicePlayer();return;}
            if(playVoice(v.id,idle)){glyph.setImageResource(R.drawable.ic_stop);what.setText("Detener");player.setContentDescription("Detener");wave.setColor(p.primary);}
        });
        LinearLayout.LayoutParams lp=Ui.fill();lp.bottomMargin=ui.dp(S2);s.body.addView(player,lp);
        Ui.SwitchRow[] use={null};
        use[0]=ui.switchRow(R.drawable.ic_voice,"Usar al transcribir",useText(v.use),v.use,on->{
            if(!Voices.setUse(this,v.id,on)){toast("No se pudo guardar el cambio");return;}
            use[0].setSubtitle(useText(on));refreshVoices();});
        use[0].setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));LinearLayout.LayoutParams up=Ui.fill();up.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);s.body.addView(use[0],up);
        s.action(R.drawable.ic_edit,"Cambiar nombre",false,()->renameVoice(v.id));
        s.action(R.drawable.ic_mic_fill,"Grabar de nuevo",false,()->recordVoice(new VoiceTake(v.name,v.id,v.me)));
        s.action(R.drawable.ic_trash,"Eliminar",true,()->confirm(v.me?"¿Eliminar tu voz?":"¿Eliminar la voz de «"+v.name+"»?",
            (v.me?"Al separar voces ya no se usará tu muestra.":"Ya no se reconocerá en tus próximos audios.")+" Las transcripciones que ya tienes no cambian.","Eliminar",true,()->{
                Voices.remove(this,v.id);refreshVoices();toast(v.me?"Tu voz se eliminó":"Voz eliminada");voiceSheet();}));
        s.onDismiss(this::releaseVoicePlayer);
        s.show();
    }
    private static String useText(boolean on){return on?"Va como muestra al separar voces":"Queda guardada, pero no se envía";}
    /** «guardada el 29 de septiembre» (con el año si no es este). */
    private static String savedOn(long at){
        if(at<=0)return "guardada en este teléfono";
        java.util.Calendar now=java.util.Calendar.getInstance(),then=java.util.Calendar.getInstance();then.setTimeInMillis(at);
        String pattern=now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)?"d 'de' MMMM":"d 'de' MMMM 'de' yyyy";
        return "guardada el "+new java.text.SimpleDateFormat(pattern,CL).format(new java.util.Date(at));
    }
    private EditText nameField(String hint,String description,String value){
        EditText f=ui.field(hint,description);f.setSingleLine(true);f.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        f.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);if(value!=null)f.setText(value);return f;
    }
    private void renameVoice(String id){
        Voices.Voice v=Voices.get(this,id);if(v==null){voiceSheet();return;}
        EditText name=nameField(v.me?"Tu nombre":"Su nombre",v.me?"Tu nombre":"Nombre de la persona",v.name);
        Sheet s=sheet("Cambiar nombre",v.me?"Así aparecerás al separar voces.":"Así aparecerá al separar voces.").add(name);
        s.primary("Guardar",Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError("Escribe un nombre");return false;}
            Voices.Voice same=Voices.findByName(this,n);if(same!=null&&!same.id.equals(v.id)){name.setError("Ya tienes una voz con ese nombre");return false;}
            if(!Voices.rename(this,v.id,n)){name.setError("No se pudo guardar. Vuelve a intentarlo.");return false;}
            refreshVoices();toast("Nombre guardado");page.post(this::voiceSheet);return true;});
        s.secondary("Cancelar",null).show();
    }
    /** Grabar tu voz: tu nombre y 10 s leyendo en voz alta. */
    private void myVoiceSheet(){
        Sheet s=sheet("Grabar mi voz","Graba 10 segundos leyendo en voz alta. Al separar voces, tu voz va como muestra en todo el audio para que la app te reconozca desde el inicio y ponga tu nombre. Queda en este teléfono y se envía a OpenAI solo junto con los audios que transcribes.");
        EditText name=nameField("Tu nombre (p. ej. Konrad)","Tu nombre",settings.prefs.getString("myVoiceName",""));s.add(name);
        s.primary("Grabar mi voz",Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError("Escribe tu nombre");return false;}
            Voices.Voice same=Voices.findByName(this,n);if(same!=null&&!same.me){name.setError("Ya tienes guardada otra voz con ese nombre");return false;}
            Voices.setName(this,n);recordVoice(new VoiceTake(n,Voices.has(this)?Voices.ME_ID:null,true));return true;});
        s.secondary("Cancelar",null).show();
    }
    /** Agregar la voz de otra persona: su nombre y 10 s leyendo en voz alta (también se puede guardar desde una grabación). */
    private void addVoiceSheet(){
        Sheet s=sheet("Agregar otra voz","Para reconocer a alguien con quien hablas seguido. Pásale el teléfono para que lea 10 segundos en voz alta. También puedes guardarla desde una grabación: toca su nombre y elige «Guardar la voz de…».");
        EditText name=nameField("Su nombre (p. ej. Fran)","Nombre de la persona","");s.add(name);
        s.primary("Grabar su voz",Ui.Style.PRIMARY,()->{
            String n=Voices.clean(name.getText().toString());if(n.isEmpty()){name.setError("Escribe su nombre");return false;}
            Voices.Voice same=Voices.findByName(this,n);
            if(same!=null){page.post(()->confirm("¿Reemplazar la voz guardada de "+same.name+"?","Grabarás una muestra nueva en lugar de la anterior.","Reemplazar",false,()->recordVoice(new VoiceTake(same.name,same.id,same.me))));return true;}
            recordVoice(new VoiceTake(n,null,false));return true;});
        s.secondary("Cancelar",null).show();
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
        if(RecorderService.activeId!=null){message("Voces conocidas","Termina la grabación en curso antes de grabar una voz.");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingVoice=t;requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},MIC_FOR_VOICE);return;}
        releaseVoicePlayer();
        String who=t.name==null||t.name.trim().isEmpty()?(t.me?Voices.name(this):"esa persona"):t.name.trim();
        LinearLayout paper=ui.column();paper.setBackground(inset(R_CARD));paper.setPadding(ui.dp(S5),ui.dp(S4),ui.dp(S5),ui.dp(S5));
        TextView script=ui.text(t.me?"«Hola, soy "+who+". Estoy grabando mi voz para que Verbapp me reconozca en mis conversaciones y reuniones. Hoy es un buen día para ordenar ideas.»"
            :"«Hola, soy "+who+". Estoy grabando mi voz para que Verbapp me reconozca en las conversaciones y reuniones. Hoy es un buen día para ordenar ideas.»",Type.BODY_LARGE,p.onSurface);
        script.setTextSize(18);script.setLineSpacing(ui.dp(4),1f);paper.addView(script);
        // Cronómetro: la cifra de DISPLAY_LARGE (68) a 56 sp, para que la hoja entera quepa en un teléfono de 360 × 740 dp.
        TextView clock=Ui.tabular(ui.text(mmss(0),Type.DISPLAY_LARGE,p.onSurface));clock.setTextSize(56);clock.setIncludeFontPadding(false);clock.setGravity(Gravity.CENTER);
        clock.setPadding(0,ui.dp(S5),0,ui.dp(S3));clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // el estado de abajo lo anuncia
        VoiceProgress bar=new VoiceProgress(this,p);
        LinearLayout line=ui.row();line.setGravity(Gravity.CENTER);line.setPadding(0,ui.dp(S3),0,0);
        View dot=new View(this);dot.setBackground(oval(p.record));dot.setVisibility(View.GONE);dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8));dl.setMarginEnd(ui.dp(S2));line.addView(dot,dl);
        TextView status=ui.text(t.me?"Toca Grabar y lee el texto con tu voz normal.":"Toca Grabar y pásale el teléfono a "+who+" para que lea con su voz normal.",Type.BODY_MEDIUM,p.onSurfaceVariant);
        status.setFontFeatureSettings("tnum");status.setGravity(Gravity.CENTER);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);line.addView(status,Ui.wrap());
        Sheet s=sheet(t.me?"Lee en voz alta":"Que "+who+" lea en voz alta",null);s.add(paper);s.add(clock);
        s.body.addView(bar,new LinearLayout.LayoutParams(-1,ui.dp(6)));
        s.add(line);voiceRecording=s;
        Ui.Btn go=ui.button("Grabar",R.drawable.ic_mic_fill,Ui.Style.RECORD,null);LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S5);s.body.addView(go,lp);
        ObjectAnimator[] pulse={null};
        go.setOnClickListener(v->{
            if(voiceRecorder==null){
                if(startVoice()){voiceTake=t;go.setText("Detener y guardar");go.setIcon(R.drawable.ic_stop);go.setColors(p.onInk,p.ink);Ui.haptic(go,Ui.Haptic.CONFIRM);
                    bar.start(voiceStarted);dot.setVisibility(View.VISIBLE);
                    if(AppTheme.motion()){pulse[0]=ObjectAnimator.ofFloat(dot,View.ALPHA,1f,0.25f);pulse[0].setDuration(600);pulse[0].setRepeatMode(ValueAnimator.REVERSE);pulse[0].setRepeatCount(ValueAnimator.INFINITE);pulse[0].start();}
                    voiceTimer.post(new Runnable(){public void run(){long ms=android.os.SystemClock.elapsedRealtime()-voiceStarted;status.setText("Grabando · "+(ms/1000)+" s de "+(Voices.MAX_MS/1000));clock.setText(mmss(ms));bar.invalidate();if(ms>=Voices.MAX_MS){s.dismiss();return;}voiceTimer.postDelayed(this,250);}});}
                else{s.dismiss();message("Voces conocidas","No se pudo usar el micrófono. Revisa el permiso y vuelve a intentarlo.");}
            }else s.dismiss();
        });
        // Cerrar la hoja guarda lo grabado; "Cancelar" lo descarta (la marca se pone antes de cerrar).
        boolean[] cancel={false};
        Ui.Btn no=ui.button("Cancelar",0,Ui.Style.SECONDARY,v->{cancel[0]=true;s.dismiss();});LinearLayout.LayoutParams np=Ui.fill();np.topMargin=ui.dp(S2);s.body.addView(no,np);
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
        if(!ok||ms<Voices.MIN_MS){if(voiceTmp!=null)voiceTmp.delete();message("Voces conocidas","La muestra quedó muy corta. Graba al menos 3 segundos.");return;}
        try{
            String name;
            if(t.id!=null&&Voices.get(this,t.id)!=null){Voices.replaceAudio(this,t.id,voiceTmp);name=t.name;}
            else name=Voices.add(this,t.name,voiceTmp,t.me).name;
            refreshVoices();
            String done=t.me?"Tu voz quedó guardada":"Voz de "+name+" guardada";
            if(voiceFlow){toast(done+" · toca Transcribir");finish();}else{toast(done);page.post(this::voiceSheet);}
        }catch(Exception e){if(voiceTmp!=null)voiceTmp.delete();message("Voces conocidas","No se pudo guardar la muestra. Vuelve a intentarlo.");}
    }
    /** Reproduce una muestra; ended corre al terminar o al detenerla. */
    private boolean playVoice(String id,Runnable ended){
        releaseVoicePlayer();
        try{voicePlayer=new android.media.MediaPlayer();voicePlayer.setDataSource(Voices.file(this,id).getAbsolutePath());voicePlayer.setOnCompletionListener(mp->releaseVoicePlayer());voicePlayer.prepare();voicePlayer.start();voiceEnded=ended;return true;}
        catch(Exception e){releaseVoicePlayer();message("Voces conocidas","No se pudo reproducir la muestra.");return false;}
    }
    private void releaseVoicePlayer(){if(voicePlayer!=null){try{voicePlayer.release();}catch(Exception ignored){}voicePlayer=null;}Runnable r=voiceEnded;voiceEnded=null;if(r!=null)r.run();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MIC_FOR_VOICE){VoiceTake t=pendingVoice;pendingVoice=null;
            if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recordVoice(t);else message("Voces conocidas","Sin permiso de micrófono no se puede grabar la muestra.");}
    }
}
