package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.*;
import org.json.*;
import java.util.concurrent.*;

public class SettingsActivity extends Screen {
    private Settings settings;private TextView keyStatus,driveStatus,folderStatus;private EditText keyInput;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private HttpApi http;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);setup("Configuración","Tú decides cuándo procesar y dónde guardar. Los ajustes se guardan al cambiarlos.");
        LinearLayout openai=card("Transcripción con OpenAI");
        help(openai,"Detecta las voces como Persona 1, Persona 2… Después podrás ponerles nombre.");
        keyStatus=text("",14,GREEN);openai.addView(keyStatus);
        keyInput=input(openai,"Clave de API de OpenAI","sk-…");keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSaveEnabled(false);keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        CheckBox show=new CheckBox(this);show.setText("Mostrar la clave que estoy escribiendo");show.setTextColor(INK);show.setOnCheckedChangeListener((b,on)->{keyInput.setInputType(InputType.TYPE_CLASS_TEXT|(on?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD));keyInput.setSelection(keyInput.length());});openai.addView(show);
        Button save=button("Guardar clave",true);save.setOnClickListener(v->{try{if(keyInput.getText().toString().trim().isEmpty()){keyInput.setError("Escribe una clave para guardarla");return;}settings.saveKey(keyInput.getText().toString());keyInput.setText("");refresh();message("Clave guardada","Quedó cifrada en este teléfono. Puedes comprobar la conexión o transcribir un audio.");}catch(Exception e){keyInput.setError(e instanceof IllegalArgumentException?e.getMessage():"No se pudo guardar la clave de forma segura.");}});openai.addView(save);
        Button verify=button("Comprobar conexión",false);verify.setOnClickListener(v->{if(!settings.hasKey()){message("Falta la clave","Guarda primero tu clave de OpenAI.");return;}background(verify,()->{new OpenAiClient(http).verify(settings.apiKey());return "Conexión correcta. El modelo de separación de voces está disponible. La transcripción requiere saldo en tu cuenta de API.";});});openai.addView(verify);
        Button remove=button("Eliminar clave guardada",false);remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("¿Eliminar la clave?").setMessage("Se cancelará el trabajo de red actual. Podrás seguir grabando sin conexión.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(d,w)->{try{settings.saveKey("");getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);refresh();}catch(Exception e){message("No se pudo eliminar","Vuelve a intentarlo.");}}).show());openai.addView(remove);
        help(openai,"Modelo: gpt-4o-transcribe-diarize. El audio se envía a OpenAI para transcribir, incluso si eliges subir solo el texto a Drive. El uso de la API tiene costo en tu cuenta.");
        choice(openai,"Idioma del audio",new String[]{"Español","Detectar automáticamente"},settings.language().equals("es")?0:1,index->settings.prefs.edit().putString("language",index==0?"es":"").apply());
        LinearLayout timing=card("Cuándo procesar y subir");
        toggle(timing,"Procesar nuevas grabaciones automáticamente","automatic",settings.automatic());
        help(timing,"Si está desactivado, pulsa Transcribir en cada grabación. Las grabaciones antiguas no se envían por activar este ajuste.");
        choice(timing,"Conexión permitida",new String[]{"Solo Wi-Fi o red no medida","Wi-Fi y datos móviles"},settings.wifiOnly()?0:1,index->{settings.prefs.edit().putBoolean("wifi",index==0).apply();Pipeline.schedule(this,true);});
        toggle(timing,"Solo mientras el teléfono está cargando","charging",settings.charging());
        help(timing,"Estas condiciones también se respetan al pulsar Transcribir o Sincronizar. Con batería baja el trabajo espera. Android decide el momento de ejecución en segundo plano.");
        LinearLayout drive=card("Google Drive");driveStatus=text("",15,GREEN);drive.addView(driveStatus);
        Button connect=button("Vincular Google Drive",true);connect.setOnClickListener(v->connect());drive.addView(connect);
        folderStatus=text("",14,MUTED);folderStatus.setPadding(0,dp(8),0,dp(8));drive.addView(folderStatus);
        Button folder=button("Elegir o crear carpeta",false);folder.setOnClickListener(v->folders(folder));drive.addView(folder);
        choice(drive,"Qué guardar en Drive",new String[]{"Solo transcripción (.txt)","Audio (.m4a) y transcripción (.txt)"},settings.includeAudio()?1:0,index->settings.prefs.edit().putBoolean("includeAudio",index==1).apply());
        help(drive,"El audio original siempre queda en el teléfono. Elegir solo texto no borra audios que ya subiste. Los cambios de nombres actualizan el mismo documento al sincronizar.");
        Button disconnect=button("Desvincular en este teléfono",false);disconnect.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("¿Desvincular Drive?").setMessage("Se detendrán las subidas. Los archivos que ya están en Drive se conservan. Puedes revocar el permiso de Google desde tu cuenta.").setNegativeButton("Cancelar",null).setPositiveButton("Desvincular",(d,w)->{settings.prefs.edit().putBoolean("driveConnected",false).remove("driveEmail").remove("folderId").apply();getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);Pipeline.schedule(this,true);refresh();}).show());drive.addView(disconnect);
        Button setup=button("Ayuda para activar Google Drive",false);setup.setOnClickListener(v->googleHelp());drive.addView(setup);
        LinearLayout basics=card("Al grabar");toggle(basics,"Preguntar el título antes de grabar","askTitle",settings.askTitle());help(basics,"El título es opcional. Siempre puedes cambiarlo después desde Opciones → Cambiar título.");
        LinearLayout preview=card("Prueba sin consumir API");help(preview,"Abre una conversación de ejemplo y cambia los nombres de sus tres hablantes. No se enviará nada a internet.");Button demo=button("Probar edición de hablantes",false);demo.setOnClickListener(v->startActivity(new Intent(this,TranscriptActivity.class).putExtra("demo",true)));preview.addView(demo);
        help(page,"Voz local 0.2 · Los audios y las transcripciones se guardan en el teléfono. Exporta una copia antes de desinstalar.");refresh();
    }
    private void refresh(){keyStatus.setText(settings.hasKey()?"Clave guardada · cifrada en el teléfono":"Sin clave configurada");driveStatus.setText(settings.driveConnected()?"Vinculado: "+settings.prefs.getString("driveEmail","cuenta de Google"):"Sin vincular");folderStatus.setText("Carpeta: "+settings.prefs.getString("folderName","Voz local")+(settings.folderId().isEmpty()?" · se creará al subir":""));}
    private void toggle(LinearLayout p,String label,String key,boolean initial){Switch s=new Switch(this);s.setText(label);s.setTextColor(INK);s.setTextSize(16);s.setMinHeight(dp(64));s.setChecked(initial);s.setOnCheckedChangeListener((b,on)->{settings.prefs.edit().putBoolean(key,on).apply();Pipeline.schedule(this,true);});p.addView(s);}
    interface Choice{void select(int index);}
    private void choice(LinearLayout p,String label,String[] options,int selected,Choice choice){label(p,label);Spinner spinner=new Spinner(this);spinner.setContentDescription(label);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);spinner.setAdapter(adapter);spinner.setSelection(selected);p.addView(spinner,new LinearLayout.LayoutParams(-1,dp(56)));spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){int previous=selected;public void onNothingSelected(android.widget.AdapterView<?> a){}public void onItemSelected(android.widget.AdapterView<?> a,View v,int index,long id){if(index!=previous){previous=index;choice.select(index);}}});}
    interface Task{String run()throws Exception;}
    private void background(Button button,Task task){button.setEnabled(false);http=new HttpApi();io.execute(()->{String result;try{result=task.run();}catch(Exception e){result=e instanceof HttpApi.UserAction?e.getMessage():"No se pudo completar la conexión. Revisa internet y vuelve a intentarlo.";}String message=result;runOnUiThread(()->{if(isDestroyed())return;button.setEnabled(true);refresh();message("Conexión",message);});});}
    private void connect(){
        if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)!=ConnectionResult.SUCCESS){message("Google Play Services no está disponible","Este emulador no incluye los servicios de Google. Puedes probar los ajustes y las voces aquí; para vincular Drive usa un teléfono con Google Play o un emulador con Google APIs.");return;}
        Identity.getAuthorizationClient(this).authorize(DriveClient.request(this)).addOnSuccessListener(result->{
            if(result.hasResolution()){try{startIntentSenderForResult(result.getPendingIntent().getIntentSender(),81,null,0,0,0);}catch(Exception e){message("Google Drive","No se pudo abrir la autorización de Google.");}}
            else connected(result);
        }).addOnFailureListener(e->message("No se pudo vincular Drive","Revisa internet y la cuenta de Google. Si Google muestra DEVELOPER_ERROR, falta registrar esta app en Google Cloud. Consulta Ayuda para activar Google Drive."));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==81){if(result!=RESULT_OK){message("Vinculación cancelada","No se cambió la conexión con Drive.");return;}try{connected(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));}catch(Exception e){message("Google Drive","No se recibió una autorización válida.");}}}
    private void connected(AuthorizationResult result){String token=result.getAccessToken();if(token==null){message("Google Drive","No se recibió permiso para acceder a Drive.");return;}io.execute(()->{try{JSONObject user=new DriveClient(new HttpApi(),token).about().getJSONObject("user");settings.prefs.edit().putBoolean("driveConnected",true).putString("driveEmail",user.getString("emailAddress")).commit();Pipeline.connected(this);runOnUiThread(()->{if(!isDestroyed()){refresh();message("Drive vinculado","Las subidas respetarán tus condiciones de conexión. Se usará la carpeta indicada.");}});}catch(Exception e){runOnUiThread(()->message("Google Drive","La autorización se recibió, pero no se pudo comprobar el acceso a Drive. Verifica que la API de Google Drive esté habilitada."));}});}
    private void folders(Button button){
        if(!settings.driveConnected()){message("Vincula Drive primero","Después podrás elegir una carpeta creada por esta app o crear una nueva.");return;}
        button.setEnabled(false);io.execute(()->{try{DriveClient drive=new DriveClient(new HttpApi(),DriveClient.token(this));JSONArray items=drive.folders();String[] labels=new String[items.length()+1];labels[0]="Crear una carpeta nueva";for(int i=0;i<items.length();i++)labels[i+1]=items.getJSONObject(i).getString("name");runOnUiThread(()->{if(isDestroyed())return;button.setEnabled(true);new AlertDialog.Builder(this).setTitle("Carpetas autorizadas de Voz local").setItems(labels,(d,index)->{if(index==0){LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);EditText name=input(box,"Nombre de la carpeta","Voz local");new AlertDialog.Builder(this).setTitle("Nueva carpeta").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Usar carpeta",(a,b)->{String value=name.getText().toString().trim();if(value.isEmpty())value="Voz local";settings.prefs.edit().putString("folderName",value).remove("folderId").apply();refresh();}).show();}else{try{JSONObject f=items.getJSONObject(index-1);settings.prefs.edit().putString("folderName",f.getString("name")).putString("folderId",f.getString("id")).apply();refresh();}catch(Exception ignored){}}}).setNegativeButton("Cancelar",null).show();});}catch(Exception e){runOnUiThread(()->{button.setEnabled(true);message("Google Drive","No se pudieron obtener las carpetas. Revisa la conexión o vuelve a vincular Drive.");});}});
    }
    private void googleHelp(){String sha="No disponible";try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.GET_SIGNATURES);byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(info.signatures[0].toByteArray());StringBuilder out=new StringBuilder();for(byte b:digest){if(out.length()>0)out.append(':');out.append(String.format(java.util.Locale.ROOT,"%02X",b));}sha=out.toString();}catch(Exception ignored){}
        String details="Activación única para quien configura la app:\n\n1. Habilitar Google Drive API en Google Cloud.\n2. Configurar la pantalla de consentimiento y agregar tu cuenta como usuario de prueba si corresponde.\n3. Crear un cliente OAuth de tipo Android con estos datos:\n\nPaquete: "+getPackageName()+"\nSHA-1: "+sha+"\n\nPermiso solicitado: drive.file (solo archivos autorizados para Voz local). No debes introducir tu contraseña de Google en la app.";
        new AlertDialog.Builder(this).setTitle("Activar acceso a Drive").setMessage(details).setPositiveButton("Entendido",null).setNeutralButton("Copiar datos",(d,w)->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Configuración de Drive",details));Toast.makeText(this,"Datos copiados",Toast.LENGTH_SHORT).show();}).show();
    }
    @Override protected void onDestroy(){if(http!=null)http.cancel();io.shutdownNow();super.onDestroy();}
}
