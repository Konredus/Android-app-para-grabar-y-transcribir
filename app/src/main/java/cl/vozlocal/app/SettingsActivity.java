package cl.vozlocal.app;

import android.app.job.JobScheduler;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.concurrent.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Ajustes como lista agrupada: cada fila muestra su valor actual a la derecha y abre una hoja para cambiarlo.
 * Nada de desplegables ni textos largos en pantalla: la explicación vive en el pie de cada grupo.
 */
public class SettingsActivity extends Screen {
    static final String[] MODELS={"gpt-4o-transcribe-diarize","gpt-4o-transcribe","gpt-4o-mini-transcribe","whisper-1"};
    static final String[] MODEL_NAMES={"GPT-4o Diarize","GPT-4o Transcribe","GPT-4o Mini","Whisper"};
    static final String[] MODEL_DETAILS={"Separa voces · ideal para conversaciones","Texto de alta calidad, sin separar voces","Más económico, sin separar voces","Modelo clásico, sin separar voces"};
    private static final int PICK_FOLDER=51;
    private Settings settings;private final ExecutorService io=Executors.newSingleThreadExecutor();private HttpApi http;
    private Ui.Row folderRow;

    static String modelName(Settings s){if(!s.provider().equals("openai"))return s.prefs.getString("customModel","personalizado");int i=Arrays.asList(MODELS).indexOf(s.prefs.getString("openaiModel",MODELS[0]));return MODEL_NAMES[Math.max(0,i)];}
    static String modelSummary(Settings s){return (s.provider().equals("openai")?"OpenAI":"tu servidor")+" · "+modelName(s);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);render();
        if(state==null&&getIntent().getBooleanExtra("focusKey",false)&&!settings.hasKey())page.post(this::keySheet);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("focusKey",false)&&!settings.hasKey())keySheet();}
    @Override public void onBackPressed(){navigate(0);}
    private void render(){
        shell(null,2);largeTitle(page,"Ajustes",null);
        page.addView(statusCard(),Ui.fill());

        // Transcripción
        page.addView(ui.section("Transcripción"));LinearLayout api=ui.group();page.addView(api,Ui.fill());
        boolean openai=settings.provider().equals("openai");
        ui.addRow(api,ui.listRow(R.drawable.ic_globe,"Proveedor",null,openai?"OpenAI":"Personalizado").onClick(v->providerSheet()));
        if(openai)ui.addRow(api,ui.listRow(R.drawable.ic_sparkle,"Modelo",null,modelName(settings)).onClick(v->modelSheet()));
        else ui.addRow(api,ui.listRow(R.drawable.ic_server,"Servidor y modelo",settings.prefs.getString("customBase","Sin configurar"),null).onClick(v->custom()));
        ui.addRow(api,ui.listRow(R.drawable.ic_key,"Clave de API",null,settings.hasKey()?"Configurada":"Falta").onClick(v->keySheet()));
        Ui.Row verify=ui.listRow(R.drawable.ic_check_circle,"Comprobar conexión",null,null);verify.onClick(v->verify(verify));ui.addRow(api,verify);
        ui.addRow(api,ui.listRow(R.drawable.ic_chat,"Idioma del audio",null,settings.language().equals("es")?"Español":"Automático").onClick(v->
            sheet("Idioma del audio","Indicar el idioma mejora la precisión.").choice("Español",null,settings.language().equals("es"),()->set("language","es"))
                .choice("Detección automática","Para audios en otros idiomas o mezclados",!settings.language().equals("es"),()->set("language","")).show()));
        page.addView(ui.footnote(openai?"Solo GPT-4o Diarize separa voces (Persona 1, Persona 2…). El uso se cobra en tu cuenta de OpenAI; la clave se guarda cifrada en este teléfono.":"Tu servidor debe implementar /audio/transcriptions compatible con OpenAI. La clave se guarda cifrada en este teléfono."));

        // Automatización
        page.addView(ui.section("Automatización"));LinearLayout auto=ui.group();page.addView(auto,Ui.fill());
        ui.addRow(auto,ui.switchRow(R.drawable.ic_bolt,"Transcribir automáticamente","Al guardar una grabación o importar un audio",settings.automatic(),on->{toggle("automatic",on);if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},101);}));
        ui.addRow(auto,ui.listRow(R.drawable.ic_wifi,"Red para enviar audio",null,settings.wifiOnly()?"Solo Wi-Fi":"Wi-Fi y datos").onClick(v->
            sheet("Red para enviar audio","Los audios pueden pesar varios MB.").choice("Solo Wi-Fi","O cualquier red no medida",settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",true).apply();changed("wifi");})
                .choice("Wi-Fi y datos móviles","Empieza antes, usa tu plan de datos",!settings.wifiOnly(),()->{settings.prefs.edit().putBoolean("wifi",false).apply();changed("wifi");}).show()));
        ui.addRow(auto,ui.switchRow(R.drawable.ic_battery,"Solo mientras carga",null,settings.charging(),on->toggle("charging",on)));
        page.addView(ui.footnote("Aplica también cuando transcribes a mano. Con batería baja el trabajo espera; Android puede retrasarlo unos minutos."));

        // Almacenamiento
        page.addView(ui.section("Copias de tus archivos"));LinearLayout storage=ui.group();page.addView(storage,Ui.fill());
        folderRow=ui.listRow(R.drawable.ic_folder,"Carpeta de copias",null,"Desactivada");folderRow.onClick(v->folderSheet());ui.addRow(storage,folderRow);refreshFolder();
        page.addView(ui.footnote("Cada grabación se copia con su audio, información y transcripción. Puedes elegir una carpeta del teléfono o de Google Drive (si tienes su app). Borrar en Voz local no borra estas copias."));

        // Grabación y apariencia
        page.addView(ui.section("Grabación"));LinearLayout rec=ui.group();page.addView(rec,Ui.fill());
        ui.addRow(rec,ui.switchRow(R.drawable.ic_title,"Resumen al terminar","Nombrar la grabación y elegir el siguiente paso",settings.askTitle(),on->toggle("askTitle",on)));
        String mode=AppTheme.appearance(this);String[] modes={"system","light","dark"},modeNames={"Automático","Claro","Oscuro"};
        page.addView(ui.section("Apariencia"));LinearLayout look=ui.group();page.addView(look,Ui.fill());
        ui.addRow(look,ui.listRow(R.drawable.ic_palette,"Tema",null,modeNames[Math.max(0,Arrays.asList(modes).indexOf(mode))]).onClick(v->{
            Sheet s=sheet("Tema",null);for(int i=0;i<3;i++){int k=i;s.choice(modeNames[i],i==0?"Igual que el teléfono":null,modes[i].equals(mode),()->{settings.prefs.edit().putString("appearance",modes[k]).apply();Diagnostics.event("setting_changed",null,"action","appearance","result",k);recreate();});}s.show();}));
        if(Build.VERSION.SDK_INT>=31)ui.addRow(look,ui.switchRow(R.drawable.ic_sparkle,"Colores de tu fondo de pantalla","Material You · adapta la app a los colores del sistema",AppTheme.dynamicColor(this),on->{settings.prefs.edit().putBoolean("dynamicColor",on).apply();Diagnostics.event("setting_changed",null,"action","dynamic_color","result",on);page.postDelayed(this::recreate,200);}));

        // Ayuda
        page.addView(ui.section("Ayuda y soporte"));LinearLayout help=ui.group();page.addView(help,Ui.fill());
        ui.addRow(help,ui.listRow(R.drawable.ic_people,"Probar edición de voces","Ejemplo sin usar la API",null).onClick(v->startActivity(new Intent(this,RecordingActivity.class).putExtra("demo",true))));
        ui.addRow(help,ui.listRow(R.drawable.ic_lifebuoy,"Compartir informe de soporte","Sin claves, títulos, audio ni texto",null).onClick(v->report()));
        Ui.Row clear=ui.listRow(R.drawable.ic_trash,"Borrar registros de diagnóstico",null,null);clear.title.setTextColor(p.error);clear.onClick(v->confirm("¿Borrar los registros locales?","Las grabaciones y transcripciones se conservan.","Borrar",true,()->{Diagnostics.clear(this);toast("Registros borrados");}));ui.addRow(help,clear);
        page.addView(ui.footnote("El registro técnico queda solo en este teléfono (máx. ~4 MB, 30 días) y se comparte únicamente si tú lo envías."));
        TextView version=ui.text("Voz local "+versionName()+" · Software libre · Licencia MIT",Type.BODY_MEDIUM,p.outline);version.setGravity(Gravity.CENTER);version.setPadding(0,ui.dp(S8),0,0);page.addView(version,Ui.fill());
    }
    /** Tarjeta de estado: tonal (secondaryContainer) si falta un paso, neutra si todo está listo. */
    private View statusCard(){
        boolean ready=settings.hasKey();LinearLayout card=ui.card();card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        int fg=ready?p.onSurface:p.onSecondaryContainer,fg2=ready?p.onSurfaceVariant:p.onSecondaryContainer;
        card.addView(ui.tile(ready?R.drawable.ic_check:R.drawable.ic_key,ready?p.onPrimaryContainer:p.onSecondaryContainer,ready?p.primaryContainer:p.surfaceContainerLowest,40,22));card.addView(ui.space(S4));
        LinearLayout t=ui.column();t.addView(ui.text(ready?"Listo para transcribir":"Falta un paso para transcribir",Type.TITLE_MEDIUM,fg));TextView d=ui.text(ready?modelSummary(settings):"Agrega tu clave de API. Grabar funciona igual sin ella.",Type.BODY_MEDIUM,fg2);d.setPadding(0,ui.dp(2),0,0);t.addView(d);card.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(!ready){card.setBackground(ui.ripple(shape(this,p.secondaryContainer,R_CARD),R_CARD));card.setClickable(true);card.setAccessibilityDelegate(Ui.buttonRole());card.setOnClickListener(v->keySheet());}
        return card;
    }
    private String versionName(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void set(String key,String value){settings.prefs.edit().putString(key,value).apply();changed(key);}
    private void toggle(String key,boolean on){settings.prefs.edit().putBoolean(key,on).apply();Diagnostics.event("setting_changed",null,"action",key,"result",on);Pipeline.schedule(this,true);}
    private void changed(String key){Diagnostics.event("setting_changed",null,"action",key);Pipeline.schedule(this,true);render();}

    private void providerSheet(){boolean openai=settings.provider().equals("openai");
        sheet("Proveedor","Cada proveedor usa su propia clave.").choice("OpenAI","Recomendado · separación de voces",openai,()->set("provider","openai"))
            .choice("Servidor compatible","Cualquier API con /audio/transcriptions",!openai,()->set("provider","custom")).show();}
    private void modelSheet(){String current=settings.prefs.getString("openaiModel",MODELS[0]);Sheet s=sheet("Modelo","Si un audio falla, probar otro modelo puede ayudar. Las transcripciones guardadas no cambian.");
        for(int i=0;i<MODELS.length;i++){String m=MODELS[i];s.choice(MODEL_NAMES[i],MODEL_DETAILS[i],m.equals(current),()->set("openaiModel",m));}s.show();}
    private void keySheet(){
        if(settings.hasKey()){sheet("Clave de API","Configurada y cifrada en este teléfono. Nunca aparece en informes.")
            .action(R.drawable.ic_edit,"Reemplazar clave",false,this::keyInput).action(R.drawable.ic_trash,"Eliminar clave",true,()->confirm("¿Eliminar la clave?","Las transcripciones pendientes quedarán en espera hasta que agregues otra.","Eliminar",true,()->{try{settings.saveKey("");getSystemService(JobScheduler.class).cancel(Pipeline.JOB_ID);render();}catch(Exception e){message("Clave","No se pudo eliminar.");}})).show();return;}
        keyInput();
    }
    private void keyInput(){
        EditText input=ui.field(settings.provider().equals("openai")?"sk-…":"Clave del servidor","Clave de API");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Sheet s=sheet("Agregar clave de API",settings.provider().equals("openai")?"Créala en platform.openai.com → API keys y pégala aquí. Se guarda cifrada y deja de mostrarse.":"Pega la clave de tu servidor. Se guarda cifrada.").add(input);
        s.primary("Guardar clave",Ui.Style.PRIMARY,()->{try{if(input.length()==0){input.setError("Pega tu clave");return false;}settings.saveKey(input.getText().toString());input.setText("");Diagnostics.event("setting_changed",null,"action","api_key");Pipeline.schedule(this,true);render();toast("Clave guardada");return true;}catch(Exception e){input.setError("No se pudo guardar. Revisa que no tenga espacios.");return false;}})
            .secondary("Cancelar",null).secure().show();
        input.requestFocus();s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void verify(Ui.Row row){
        if(!settings.hasKey()){keySheet();return;}
        row.setEnabled(false);row.setSubtitle("Comprobando…");http=new HttpApi();
        io.execute(()->{String result;boolean ok=false;try{new OpenAiClient(http).verify(settings.config());ok=true;result="El servidor respondió y reconoce el modelo. Esto no prueba todavía la carga de audio, el saldo ni la separación de voces.";}
            catch(Exception e){result=e instanceof HttpApi.UserAction?e.getMessage():"No se pudo conectar. Revisa tu conexión y la URL del servidor.";}
            String value=result;boolean success=ok;runOnUiThread(()->{if(isDestroyed())return;row.setEnabled(true);row.setSubtitle(success?"Conexión correcta":"Falló la última comprobación");row.subtitle.setTextColor(success?p.primary:p.error);message(success?"Conexión correcta":"No se pudo verificar",value);});});
    }
    private void custom(){
        LinearLayout box=ui.column();EditText base=ui.labeled(box,"URL base HTTPS","https://proveedor.com/v1");base.setText(settings.prefs.getString("customBase",""));base.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText model=ui.labeled(box,"Identificador del modelo","whisper-1");model.setText(settings.prefs.getString("customModel",""));
        CheckBox voices=new CheckBox(this);voices.setText("Admite diarized_json y chunking_strategy");voices.setTextColor(p.onSurface);voices.setButtonTintList(android.content.res.ColorStateList.valueOf(p.primary));voices.setChecked(settings.prefs.getBoolean("customSpeakers",false));voices.setPadding(ui.dp(S1),ui.dp(S3),0,ui.dp(S3));box.addView(voices);
        sheet("Servidor compatible","Enviarás audio y tu clave a este servidor. Si cambias la URL, se borra la clave anterior.").add(box)
            .primary("Guardar",Ui.Style.PRIMARY,()->{try{ProviderConfig config=new ProviderConfig("custom",base.getText().toString().trim(),model.getText().toString().trim(),"",voices.isChecked());if(!config.base.equals(settings.prefs.getString("customBase","")))settings.saveKey("");settings.prefs.edit().putString("customBase",config.base).putString("customModel",config.model).putBoolean("customSpeakers",config.speakers).apply();Pipeline.schedule(this,true);render();return true;}catch(Exception e){base.setError("Usa una URL HTTPS sin credenciales ni parámetros y un modelo válido.");return false;}})
            .secondary("Cancelar",null).show();
    }
    private void folderSheet(){
        boolean has=!settings.prefs.getString("localTree","").isEmpty();
        Sheet s=sheet("Carpeta de copias",has?"Las grabaciones se copian automáticamente a esta carpeta.":"Elige o crea una carpeta. En el selector, abre el menú ☰ para ver Google Drive u otras ubicaciones.");
        s.action(R.drawable.ic_folder,has?"Cambiar carpeta":"Elegir carpeta",false,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),PICK_FOLDER);}catch(ActivityNotFoundException e){message("Carpeta","Este teléfono no permite elegir carpetas.");}});
        if(has){s.action(R.drawable.ic_refresh,"Actualizar todas las copias",false,()->{for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast("Actualizando copias…");});
            s.action(R.drawable.ic_close,"Dejar de copiar",true,()->{String old=settings.prefs.getString("localTree","");settings.prefs.edit().remove("localTree").apply();try{getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}refreshFolder();});}
        s.show();
    }
    /** Muestra el nombre real de la carpeta (y si es Drive) en vez del identificador interno. */
    private void refreshFolder(){
        String tree=settings.prefs.getString("localTree","");if(tree.isEmpty()){folderRow.setValue("Desactivada");return;}
        folderRow.setValue("…");io.execute(()->{String name="Carpeta elegida";Uri uri=Uri.parse(tree);
            try(android.database.Cursor c=getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)name=c.getString(0);}catch(Exception ignored){}
            String authority=uri.getAuthority()==null?"":uri.getAuthority();String label=(authority.contains("google.android.apps.docs")?"Drive · ":"")+name;
            runOnUiThread(()->{if(!isDestroyed())folderRow.setValue(label);});});
    }
    private void report(){toast("Preparando informe…");io.execute(()->{try{Diagnostics.export(this);runOnUiThread(()->shareFile("support.txt","Informe de soporte"));}catch(Exception e){runOnUiThread(()->message("Informe","No se pudo generar el informe."));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_FOLDER&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try{Uri tree=data.getData();if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new SecurityException();getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                settings.prefs.edit().putString("localTree",tree.toString()).apply();Diagnostics.event("setting_changed",null,"action","local_tree","result",tree.getAuthority()!=null&&tree.getAuthority().contains("google.android.apps.docs")?"drive":"device");refreshFolder();
                for(Recording r:Recording.list(this))LocalStorage.enqueue(this,r.id);toast("Carpeta guardada · copiando grabaciones");}
            catch(Exception e){message("Carpeta","No se obtuvo permiso de escritura. Elige otra carpeta.");}
        }
    }
    @Override protected void onDestroy(){if(http!=null)http.cancel();io.shutdown();super.onDestroy();}
}
