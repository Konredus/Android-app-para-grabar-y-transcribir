package cl.vozlocal.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Siguiente paso de una grabación: UN botón principal que avanza con ella (0.6.0).
 * Transcribir → Revisar voces → Guardar en 0-Inbox → ✓ En 0-Inbox · hh:mm (o Actualizar).
 * Lo usan el detalle (barra inferior) y el inicio (tarjeta «Última grabación» y Biblioteca); la acción de cada paso
 * está en {@link RecordingActions#advance}, así el botón hace lo mismo en todas partes.
 */
final class Next {
    enum Step{TRANSCRIBE,WORKING,RETRY,REVIEW,SAVE,SAVED,UPDATE,CHOOSE_FOLDER}
    final Step step;final String label;final int icon;
    Next(Step step,String label,int icon){this.step=step;this.label=label;this.icon=icon;}
    /** El trabajo sigue solo: el botón se muestra ocupado (sin acción que tomar). */
    boolean busy(){return step==Step.WORKING;}
    /** Ya llegó a destino o está en curso: el botón va en tono suave (tonal), no relleno. */
    boolean quiet(){return step==Step.SAVED||step==Step.WORKING;}
    static Next of(Context c,Recording r){
        JSONObject st=FilesStore.state(c,r.id);
        // Pedida (también al volver a transcribir, cuando la versión actual pasó a "anterior"): el trabajo va solo.
        if(st.optBoolean("requested"))return new Next(Step.WORKING,st.has("retranscribe")?"Volviendo a transcribir…":"Transcribiendo…",R.drawable.ic_clock);
        if(!Transcript.exists(c,r.id)){
            if(st.optBoolean("failed"))return new Next(Step.RETRY,"Reintentar",R.drawable.ic_refresh);
            return new Next(Step.TRANSCRIBE,"Transcribir",R.drawable.ic_sparkle);
        }
        if(needsReview(c,r.id))return new Next(Step.REVIEW,"Revisar voces",R.drawable.ic_people);
        if(!Inbox.configured(c))return new Next(Step.CHOOSE_FOLDER,"Elegir carpeta rápida",R.drawable.ic_folder);
        String folder=shortName(Inbox.folderName(c));long at=Inbox.savedAt(c,r.id);
        if(at==0)return new Next(Step.SAVE,"Guardar en "+folder,R.drawable.ic_inbox);
        if(Inbox.outdated(c,r.id))return new Next(Step.UPDATE,"Actualizar en "+folder,R.drawable.ic_refresh);
        return new Next(Step.SAVED,"En "+folder+" · "+when(at),R.drawable.ic_check);
    }
    /** «16:09» si fue hoy; si no, «28 sept». */
    static String when(long at){
        Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(at);
        boolean today=now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR);
        String s=new SimpleDateFormat(today?"HH:mm":"d MMM",new Locale("es","CL")).format(new Date(at));
        return s.endsWith(".")?s.substring(0,s.length()-1):s;
    }
    /** Un nombre de carpeta largo no debe romper el botón. */
    static String shortName(String name){String n=name==null||name.trim().isEmpty()?"la carpeta":name.trim();return n.length()>18?n.substring(0,17).trim()+"…":n;}

    /** Voces separadas y sin revisar. Se cachea por archivo: la Biblioteca lo pregunta por cada fila y una transcripción larga pesa. */
    private static final Map<String,Object[]> reviewCache=new HashMap<>();
    static boolean needsReview(Context c,String id){
        File f=FilesStore.file(c,id,".transcript.json");if(!f.isFile())return false;String stamp=f.lastModified()+":"+f.length();
        synchronized(reviewCache){Object[] hit=reviewCache.get(id);if(hit!=null&&hit[0].equals(stamp))return (Boolean)hit[1];}
        boolean need=false;
        try{Transcript t=Transcript.load(c,id);need=t.diarized()&&t.speakers().size()>1&&!t.reviewed();}catch(Exception ignored){}
        synchronized(reviewCache){if(reviewCache.size()>200)reviewCache.clear();reviewCache.put(id,new Object[]{stamp,need});}
        return need;
    }
}

/**
 * Estado visible de una grabación. Un solo lugar define texto, color e ícono de cada estado.
 * El color se reserva para lo que pide atención (0.6.0): lo terminado va en silencio (neutro); lo que está en curso,
 * en secondaryContainer; un error, en errorContainer; y lo que falta transcribir, en el azul de las acciones.
 */
final class RecState {
    enum Kind{NEW,QUEUED,FAILED,DONE}
    final Kind kind;final String label,detail;
    private RecState(Kind kind,String label,String detail){this.kind=kind;this.label=label;this.detail=detail;}
    static RecState of(JSONObject state,boolean transcribed){
        // "requested" primero: al volver a transcribir puede quedar una versión mientras se hace la nueva.
        if(state.optBoolean("requested"))return new RecState(Kind.QUEUED,"En proceso",state.optString("status","En cola"));
        if(transcribed)return new RecState(Kind.DONE,"Transcrito","Transcripción lista");
        if(state.optBoolean("failed"))return new RecState(Kind.FAILED,"Necesita atención",state.optString("status","No se pudo transcribir"));
        return new RecState(Kind.NEW,"Sin transcribir","Solo audio");
    }
    static RecState of(Context c,String id){return of(FilesStore.state(c,id),Transcript.exists(c,id));}
    /** Color del texto de estado sobre el fondo de la pantalla. */
    int fg(Palette p){switch(kind){case QUEUED:return p.primary;case FAILED:return p.error;case NEW:return p.primary;default:return p.onSurfaceVariant;}}
    /** Contenedor tonal del estado (fondo de ícono o chip) y su color "on" correspondiente. */
    int bg(Palette p){switch(kind){case QUEUED:return p.secondaryContainer;case FAILED:return p.errorContainer;case NEW:return p.primaryContainer;default:return p.surfaceContainerHighest;}}
    int onBg(Palette p){switch(kind){case QUEUED:return p.onSecondaryContainer;case FAILED:return p.onErrorContainer;case NEW:return p.onPrimaryContainer;default:return p.onSurfaceVariant;}}
    int icon(){switch(kind){case DONE:return R.drawable.ic_doc;case QUEUED:return R.drawable.ic_clock;case FAILED:return R.drawable.ic_alert;default:return R.drawable.ic_wave;}}
}

/** Acciones sobre una grabación, compartidas por Biblioteca, Inicio y Detalle para que se comporten igual. */
final class RecordingActions {
    static void menu(Screen s,Recording r,Runnable changed,Runnable beforeRelease){
        RecState state=RecState.of(s,r.id);boolean transcribed=Transcript.exists(s,r.id),queued=state.kind==RecState.Kind.QUEUED;
        Sheet sheet=s.sheet(r.title,Ui.humanDuration(r.duration)+" · "+state.label);
        sheet.action(R.drawable.ic_edit,"Cambiar título",false,()->rename(s,r,changed));
        if(state.kind==RecState.Kind.NEW)sheet.action(R.drawable.ic_sparkle,"Transcribir",false,()->transcribe(s,r,changed));
        if(state.kind==RecState.Kind.FAILED){
            sheet.action(R.drawable.ic_refresh,"Reintentar transcripción",false,()->transcribe(s,r,changed));
            // Si falló al volver a transcribir, la versión anterior sigue guardada: siempre hay una salida.
            if(!transcribed&&Retranscribe.hasPrevious(s,r.id))sheet.action(R.drawable.ic_replay,"Volver a la versión anterior",false,()->restorePrevious(s,r,changed));
        }
        if(transcribed&&!queued){
            if(Inbox.configured(s)){String folder=Next.shortName(Inbox.folderName(s));long at=Inbox.savedAt(s,r.id);
                sheet.action(R.drawable.ic_inbox,(at==0?"Guardar en ":Inbox.outdated(s,r.id)?"Actualizar en ":"Guardar de nuevo en ")+folder,false,()->saveToInbox(s,r,changed));}
            sheet.action(R.drawable.ic_refresh,"Volver a transcribir…",false,()->RetranscribeSheet.show(s,r,changed));
            if(Retranscribe.hasPrevious(s,r.id))sheet.action(R.drawable.ic_replay,"Elegir versión: nueva o anterior…",false,()->RetranscribeSheet.offerKeep(s,r,changed));
        }
        sheet.action(R.drawable.ic_share,"Compartir audio",false,()->shareAudio(s,r));
        sheet.action(R.drawable.ic_cut,"Recortar una copia",false,()->{if(beforeRelease!=null)beforeRelease.run();s.startActivity(new Intent(s,ImportActivity.class).putExtra("sourceId",r.id));});
        // «Cancelar transcripción» queda lejos del resto (antes de Eliminar) y siempre se confirma.
        if(queued)sheet.action(R.drawable.ic_close,"Cancelar transcripción",false,()->cancel(s,r,changed));
        sheet.action(R.drawable.ic_trash,"Eliminar",true,()->delete(s,r,beforeRelease,changed));
        sheet.show();Diagnostics.event("recording_menu",r.id);
    }

    /** El botón principal que avanza (ver {@link Next}): hace lo que corresponde al paso actual de la grabación. */
    static void advance(Screen s,Recording r,Runnable changed){
        Next next=Next.of(s,r);Diagnostics.event("next_step",r.id,"step",next.step.name());
        switch(next.step){
            case TRANSCRIBE:case RETRY:transcribe(s,r,changed);break;
            case WORKING:
                if(s instanceof RecordingActivity){JSONObject st=FilesStore.state(s,r.id);s.toast(st.optString("status","Transcribiendo"));}
                else s.startActivity(new Intent(s,RecordingActivity.class).putExtra("id",r.id));
                break;
            case REVIEW:
                if(s instanceof RecordingActivity){try{NameVoices.show(s,r.id,false,Transcript.load(s,r.id),changed);}catch(Exception e){s.message("Revisar voces","No se pudo abrir la transcripción.");}}
                else s.startActivity(new Intent(s,RecordingActivity.class).putExtra("id",r.id).putExtra("names",true));
                break;
            case SAVE:case UPDATE:saveToInbox(s,r,changed);break;
            case SAVED:savedSheet(s,r,changed);break;
            case CHOOSE_FOLDER:chooseFolder(s);break;
        }
    }
    /** Ya está en la carpeta rápida: explica cuándo se guardó y permite guardar de nuevo (reemplaza el mismo archivo). */
    private static void savedSheet(Screen s,Recording r,Runnable changed){
        String folder=Inbox.folderName(s);long at=Inbox.savedAt(s,r.id);
        s.sheet("Ya está en "+folder,"Se guardó "+(Next.when(at).contains(":")?"hoy a las ":"el ")+Next.when(at)+". Si cambias algo, se reemplaza el mismo archivo, sin crear una copia.")
            .primary("Guardar de nuevo",()->saveToInbox(s,r,changed)).secondary("Cerrar",null).show();
    }
    /** Lleva a Ajustes para elegir la carpeta rápida (p. ej. Drive/0-Inbox). */
    static void chooseFolder(Screen s){
        Diagnostics.event("ui_action",null,"action","choose_inbox");
        s.startActivity(new Intent(s,SettingsActivity.class).putExtra("back",true).putExtra("inbox",true));
    }
    static void saveToInbox(Screen s,Recording r,Runnable changed){saveToInbox(s,r,changed,null);}
    /**
     * Guarda en la carpeta rápida en segundo plano: la nota .md (o el .txt si no hay nota), reemplazando el mismo archivo
     * si ya se guardó antes. finished (opcional, en el hilo de la UI) recibe true/false, p. ej. para el indicador del botón.
     */
    static void saveToInbox(Screen s,Recording r,Runnable changed,java.util.function.Consumer<Boolean> finished){
        if(!Inbox.configured(s)){if(finished!=null)finished.accept(false);chooseFolder(s);return;}
        String folder=Inbox.folderName(s);Context app=s.getApplicationContext();
        new Thread(()->{
            boolean ok;Exception error=null;
            try{
                try{Inbox.save(app,r);}catch(UnsupportedOperationException notYet){legacySave(app,r);}
                ok=true;Diagnostics.event("inbox_saved",r.id);
            }catch(Exception e){ok=false;error=e;Diagnostics.event("inbox_save_failed",r.id,"error_class",e.getClass().getSimpleName());}
            boolean done=ok;String reason=error instanceof HttpApi.UserAction?error.getMessage():null;
            s.runOnUiThread(()->{
                if(s.isFinishing()||s.isDestroyed())return;
                if(finished!=null)finished.accept(done);
                if(done){Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast("Guardado en "+folder);if(changed!=null)changed.run();return;}
                Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.REJECT);
                s.sheet("No se pudo guardar en "+folder,reason!=null?reason:"Puede que Android haya retirado el permiso a esa carpeta. Elígela de nuevo y vuelve a intentarlo.")
                    .primary("Elegir la carpeta de nuevo",()->chooseFolder(s)).secondary("Cerrar",null).show();
            });
        }).start();
    }
    /** Respaldo mientras la carpeta rápida no sabe guardar la nota: el .txt de siempre (como la 0.5). */
    private static void legacySave(Context c,Recording r)throws Exception{
        Uri tree=Uri.parse(new Settings(c).inboxTree());Uri dir=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        Uri doc=DocumentsContract.createDocument(c.getContentResolver(),dir,"text/plain",TranscriptExport.filename(r.title));if(doc==null)throw new java.io.IOException("No se pudo crear el archivo");
        LocalStorage.writeText(c,doc,Transcript.load(c,r.id).text(r));
        FilesStore.update(c,r.id,st->st.put("inboxUri",doc.toString()).put("inboxAt",System.currentTimeMillis()).put("inboxKind","txt"));
    }

    static void rename(Screen s,Recording r,Runnable changed){
        EditText input=s.ui.field("Título","Título de la grabación");input.setText(r.title);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        Sheet sheet=s.sheet("Cambiar título",null).add(input);
        sheet.primary("Guardar",Ui.Style.PRIMARY,()->{
            String title=input.getText().toString().trim();if(title.isEmpty()){input.setError("Escribe un título");return false;}
            String previous=r.title;r.title=title;
            try{r.save(s);Pipeline.edited(s,r.id);Diagnostics.event("title_edited",r.id);if(changed!=null)changed.run();return true;}
            catch(Exception e){r.title=previous;input.setError("No se pudo guardar el título");return false;}
        }).secondary("Cancelar",null).show();
        input.requestFocus();sheet.dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    static void shareAudio(Screen s,Recording r){
        Uri uri=Uri.parse("content://cl.vozlocal.app.audio/"+r.id+".m4a");
        Intent share=new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,r.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri(r.title,uri));s.startActivity(Intent.createChooser(share,"Compartir audio"));
    }
    /** Encola la transcripción. Si falta la clave, lleva directo a configurarla; si ya está transcrita, ofrece volver a transcribir. */
    static void transcribe(Screen s,Recording r,Runnable changed){
        if(Transcript.exists(s,r.id)){RetranscribeSheet.show(s,r,changed);return;}
        if(!new Settings(s).hasKey()){missingKey(s);return;}
        Settings settings=new Settings(s);
        if(settings.canSeparate()&&settings.speakersMode().equals("ask")){askSpeakers(s,r,changed,settings);return;}
        start(s,r,changed,settings.defaultSpeakers());
    }
    /** Falta la clave: el error trae su salida («Configurar ahora»). */
    static void missingKey(Screen s){
        s.sheet("Falta tu clave de API","Para transcribir, Voz local usa tu propia cuenta del proveedor (por ejemplo OpenAI). Solo pagas lo que usas.")
            .primary("Configurar ahora",()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("focusKey",true))).secondary("Más tarde",null).show();
    }
    /** Pregunta si separar voces, con el costo y la velocidad de cada opción para este audio. */
    static void askSpeakers(Screen s,Recording r,Runnable changed,Settings settings){
        try{
            String voices=settings.config(true).model,text=settings.config(false).model;
            String provider=settings.provider();String costVoices=Pricing.usd(Pricing.estimate(provider,voices,r.duration)),costText=Pricing.usd(Pricing.estimate(provider,text,r.duration));boolean live=provider.equals("openai")&&text.equals("gpt-transcribe");
            boolean mine=provider.equals("openai")&&Voices.has(s);
            Sheet sheet=s.sheet("¿Separar voces?","Audio de "+Ui.humanDuration(r.duration)+". Puedes cambiar esta pregunta en Ajustes.")
                .option(R.drawable.ic_people,mine?"Sí, separar voces · con Mi voz":"Sí, separar voces",(mine?"Te reconoce como "+Voices.name(s)+" desde el inicio; las demás, Persona 2…":"Para reuniones y conversaciones: Persona 1, Persona 2…")+" · más lento"+(costVoices.equals("—")?"":" · ≈ "+costVoices),()->start(s,r,changed,true))
                .option(R.drawable.ic_doc,"No, solo el texto","Para dictados y notas · más rápido"+(live?", el texto aparece en vivo":"")+(costText.equals("—")?"":" · ≈ "+costText),()->start(s,r,changed,false));
            // Sin "Mi voz", la separación se equivoca más al inicio: se sugiere grabarla (una sola vez).
            if(provider.equals("openai")&&!mine)sheet.action(R.drawable.ic_mic_fill,"Grabar mi voz para que me reconozca",false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true)));
            sheet.show();
        }catch(Exception e){start(s,r,changed,settings.defaultSpeakers());}
    }
    static void start(Screen s,Recording r,Runnable changed,boolean speakers){
        askNotifications(s);
        try{Pipeline.request(s,r.id,speakers);Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);if(changed!=null)changed.run();String blocker=Pipeline.blocker(s);s.toast(blocker==null?"Transcribiendo · sigue aunque bloquees el teléfono":"En cola · "+blocker);}
        catch(Exception e){s.message("No se pudo poner en cola",e instanceof HttpApi.UserAction?e.getMessage():"Vuelve a intentarlo.");}
    }
    /** Android 13+: el aviso de «lista» necesita permiso de notificaciones; se pide al encolar. */
    static void askNotifications(Screen s){
        if(Build.VERSION.SDK_INT>=33&&s.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)s.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
    }
    /** Explica por qué conviene quitar la optimización de batería y abre el permiso del sistema. */
    static void allowBackground(Screen s){
        String hint=Battery.makerHint();
        Sheet sheet=s.sheet("Trabajar con la pantalla bloqueada","Con la optimización de batería activa, Android puede congelar Voz local al bloquear el teléfono y cortar el envío a OpenAI. Solo gasta batería mientras transcribe."+(hint.isEmpty()?"":"\n\n"+hint))
            .primary("Permitir",()->{Diagnostics.event("ui_action",null,"action","battery_request");Battery.request(s);});
        if(!hint.isEmpty())sheet.secondary("Abrir ajustes de la app",()->Battery.appSettings(s));
        sheet.secondary("Ahora no",null).show();
    }
    /**
     * Cancelar pierde el trabajo en curso: siempre se confirma (pedido del usuario, 0.4.3).
     * Si era una nueva versión (volver a transcribir), la anterior se recupera: nunca te quedas sin transcripción.
     */
    static void cancel(Screen s,Recording r,Runnable changed){
        boolean again=!Transcript.exists(s,r.id)&&Retranscribe.hasPrevious(s,r.id);
        s.sheet("¿Cancelar la transcripción?",again?"Se detiene el envío y vuelves a tu versión anterior, tal como estaba."
                :"Se detiene el envío. Las partes ya listas no se vuelven a cobrar si la reanudas más tarde con la misma opción de voces.")
            .primary("Cancelar transcripción",Ui.Style.DESTRUCTIVE,()->{
                try{Pipeline.cancel(s,r.id);
                    if(again&&Retranscribe.hasPrevious(s,r.id)&&!Transcript.exists(s,r.id)){try{Retranscribe.restorePrevious(s,r.id);}catch(Exception e){Diagnostics.event("retranscribe_restore_failed",r.id,"error_class",e.getClass().getSimpleName());}}
                    if(changed!=null)changed.run();}
                catch(Exception e){s.message("Transcripción","No se pudo cancelar el trabajo.");}return true;})
            .secondary("Seguir transcribiendo",null).show();
    }
    /** Vuelve a la versión anterior sin preguntar de nuevo (se llega aquí desde un menú que ya lo nombra). */
    static void restorePrevious(Screen s,Recording r,Runnable changed){
        try{Retranscribe.restorePrevious(s,r.id);Diagnostics.event("retranscribe_restored",r.id);Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast("Volviste a la versión anterior");if(changed!=null)changed.run();}
        catch(Exception e){s.message("Versión anterior","No se pudo recuperar la versión anterior. Tu audio sigue intacto.");}
    }
    static void delete(Screen s,Recording r,Runnable before,Runnable after){
        s.confirm("¿Eliminar «"+r.title+"»?","Se borrarán el audio y la transcripción de este teléfono. Las copias en tu carpeta elegida se conservan. No se puede deshacer.","Eliminar",true,()->{
            if(before!=null)before.run();if(!r.delete(s))s.message("Eliminar","No se pudo eliminar el audio.");else{Diagnostics.event("recording_deleted",r.id);if(after!=null)after.run();}
        });
    }
    private RecordingActions(){}
}
