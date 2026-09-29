package cl.vozlocal.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.InputFilter;
import android.widget.*;
import org.json.JSONObject;
import static cl.vozlocal.app.AppTheme.*;

/** Estado visible de una grabación. Un solo lugar define texto, color e ícono de cada estado. */
/**
 * Siguiente paso de una grabación: UN botón principal que avanza con ella (0.6.0).
 * Transcribir → Revisar voces → Guardar en 0-Inbox → ✓ En 0-Inbox · hh:mm (o Actualizar).
 * Implementación básica de la fase 0; la parte «sheets» la afina (ver docs/diseno/SPEC-0.6.md).
 */
final class Next {
    enum Step{TRANSCRIBE,WORKING,RETRY,REVIEW,SAVE,SAVED,UPDATE,CHOOSE_FOLDER}
    final Step step;final String label;final int icon;
    Next(Step step,String label,int icon){this.step=step;this.label=label;this.icon=icon;}
    static Next of(Context c,Recording r){
        JSONObject st=FilesStore.state(c,r.id);
        if(!Transcript.exists(c,r.id)){
            if(st.optBoolean("requested"))return new Next(Step.WORKING,"Transcribiendo…",R.drawable.ic_clock);
            if(st.optBoolean("failed"))return new Next(Step.RETRY,"Reintentar",R.drawable.ic_refresh);
            return new Next(Step.TRANSCRIBE,"Transcribir",R.drawable.ic_sparkle);
        }
        try{Transcript t=Transcript.load(c,r.id);if(t.diarized()&&t.speakers().size()>1&&!t.reviewed())return new Next(Step.REVIEW,"Revisar voces",R.drawable.ic_people);}catch(Exception ignored){}
        if(!Inbox.configured(c))return new Next(Step.CHOOSE_FOLDER,"Elegir carpeta rápida",R.drawable.ic_folder);
        String folder=Inbox.folderName(c);long at=Inbox.savedAt(c,r.id);
        if(at==0)return new Next(Step.SAVE,"Guardar en "+folder,R.drawable.ic_save);
        if(Inbox.outdated(c,r.id))return new Next(Step.UPDATE,"Actualizar en "+folder,R.drawable.ic_refresh);
        return new Next(Step.SAVED,"En "+folder+" · "+new java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(new java.util.Date(at)),R.drawable.ic_check);
    }
}

final class RecState {
    enum Kind{NEW,QUEUED,FAILED,DONE}
    final Kind kind;final String label,detail;
    private RecState(Kind kind,String label,String detail){this.kind=kind;this.label=label;this.detail=detail;}
    static RecState of(JSONObject state,boolean transcribed){
        if(transcribed)return new RecState(Kind.DONE,"Transcrito","Transcripción lista");
        if(state.optBoolean("requested"))return new RecState(Kind.QUEUED,"En proceso",state.optString("status","En cola"));
        if(state.optBoolean("failed"))return new RecState(Kind.FAILED,"Necesita atención",state.optString("status","No se pudo transcribir"));
        return new RecState(Kind.NEW,"Sin transcribir","Solo audio");
    }
    static RecState of(Context c,String id){return of(FilesStore.state(c,id),Transcript.exists(c,id));}
    int fg(Palette p){switch(kind){case DONE:return p.primary;case QUEUED:return p.primary;case FAILED:return p.error;default:return p.onSurfaceVariant;}}
    /** Contenedor tonal del estado (fondo de ícono o chip) y su color "on" correspondiente. */
    int bg(Palette p){switch(kind){case DONE:return p.primaryContainer;case QUEUED:return p.secondaryContainer;case FAILED:return p.errorContainer;default:return p.surfaceContainerHighest;}}
    int onBg(Palette p){switch(kind){case DONE:return p.onPrimaryContainer;case QUEUED:return p.onSecondaryContainer;case FAILED:return p.onErrorContainer;default:return p.onSurfaceVariant;}}
    int icon(){switch(kind){case DONE:return R.drawable.ic_doc;case QUEUED:return R.drawable.ic_clock;case FAILED:return R.drawable.ic_alert;default:return R.drawable.ic_wave;}}
}

/** Acciones sobre una grabación, compartidas por Biblioteca y Detalle para que se comporten igual. */
final class RecordingActions {
    static void menu(Screen s,Recording r,Runnable changed,Runnable beforeRelease){
        RecState state=RecState.of(s,r.id);Sheet sheet=s.sheet(r.title,Recording.time(r.duration)+" · "+state.label);
        sheet.action(R.drawable.ic_edit,"Cambiar título",false,()->rename(s,r,changed));
        if(state.kind==RecState.Kind.NEW)sheet.action(R.drawable.ic_sparkle,"Transcribir",false,()->transcribe(s,r,changed));
        if(state.kind==RecState.Kind.FAILED)sheet.action(R.drawable.ic_refresh,"Reintentar transcripción",false,()->transcribe(s,r,changed));
        if(state.kind==RecState.Kind.QUEUED)sheet.action(R.drawable.ic_close,"Cancelar transcripción",false,()->cancel(s,r,changed));
        sheet.action(R.drawable.ic_share,"Compartir audio",false,()->shareAudio(s,r));
        sheet.action(R.drawable.ic_cut,"Recortar una copia",false,()->{if(beforeRelease!=null)beforeRelease.run();s.startActivity(new Intent(s,ImportActivity.class).putExtra("sourceId",r.id));});
        sheet.action(R.drawable.ic_trash,"Eliminar",true,()->delete(s,r,beforeRelease,changed));
        sheet.show();Diagnostics.event("recording_menu",r.id);
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
    /** Encola la transcripción. Si falta la clave, lleva directo a configurarla en vez de solo mostrar un error. */
    static void transcribe(Screen s,Recording r,Runnable changed){
        if(Transcript.exists(s,r.id)){s.message("Ya está transcrito","Esta grabación ya tiene transcripción. Para otra versión, recorta una copia.");return;}
        if(!new Settings(s).hasKey()){
            s.sheet("Falta tu clave de API","Para transcribir, Voz local usa tu propia cuenta del proveedor (por ejemplo OpenAI). Solo pagas lo que usas.")
                .primary("Configurar ahora",()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("focusKey",true))).secondary("Más tarde",null).show();return;
        }
        Settings settings=new Settings(s);
        if(settings.canSeparate()&&settings.speakersMode().equals("ask")){askSpeakers(s,r,changed,settings);return;}
        start(s,r,changed,settings.defaultSpeakers());
    }
    /** Pregunta si separar voces, con el costo y la velocidad de cada opción para este audio. */
    static void askSpeakers(Screen s,Recording r,Runnable changed,Settings settings){
        try{
            String voices=settings.config(true).model,text=settings.config(false).model;
            String provider=settings.provider();String costVoices=Pricing.usd(Pricing.estimate(provider,voices,r.duration)),costText=Pricing.usd(Pricing.estimate(provider,text,r.duration));boolean live=provider.equals("openai")&&text.equals("gpt-transcribe");
            boolean mine=provider.equals("openai")&&Voices.has(s);
            Sheet sheet=s.sheet("¿Separar voces?","Audio de "+Recording.time(r.duration)+". Puedes cambiar esta pregunta en Ajustes.")
                .option(R.drawable.ic_people,"Sí, separar voces",(mine?"Te reconoce como "+Voices.name(s)+"; las demás, Persona 2…":"Para reuniones y conversaciones: Persona 1, Persona 2…")+" · más lento"+(costVoices.equals("—")?"":" · ≈"+costVoices),()->start(s,r,changed,true))
                .option(R.drawable.ic_doc,"No, solo el texto","Para dictados y notas · más rápido"+(live?", el texto aparece en vivo":"")+(costText.equals("—")?"":" · ≈"+costText),()->start(s,r,changed,false));
            // Sin "Mi voz", la separación se equivoca más al inicio: se sugiere grabarla (una sola vez).
            if(provider.equals("openai")&&!mine)sheet.action(R.drawable.ic_mic_fill,"Grabar mi voz para que me reconozca",false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true)));
            sheet.show();
        }catch(Exception e){start(s,r,changed,settings.defaultSpeakers());}
    }
    static void start(Screen s,Recording r,Runnable changed,boolean speakers){
        if(Build.VERSION.SDK_INT>=33&&s.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)s.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
        try{Pipeline.request(s,r.id,speakers);if(changed!=null)changed.run();String blocker=Pipeline.blocker(s);s.toast(blocker==null?"Transcribiendo · sigue aunque bloquees el teléfono":"En cola · "+blocker);}
        catch(Exception e){s.message("No se pudo poner en cola",e instanceof HttpApi.UserAction?e.getMessage():"Vuelve a intentarlo.");}
    }
    /** Cancelar pierde el trabajo en curso: siempre se confirma (pedido del usuario, 0.4.3). */
    /** Explica por qué conviene quitar la optimización de batería y abre el permiso del sistema. */
    static void allowBackground(Screen s){
        String hint=Battery.makerHint();
        Sheet sheet=s.sheet("Trabajar con la pantalla bloqueada","Con la optimización de batería activa, Android puede congelar Voz local al bloquear el teléfono y cortar el envío a OpenAI. Solo gasta batería mientras transcribe."+(hint.isEmpty()?"":"\n\n"+hint))
            .primary("Permitir",()->{Diagnostics.event("ui_action",null,"action","battery_request");Battery.request(s);});
        if(!hint.isEmpty())sheet.secondary("Abrir ajustes de la app",()->Battery.appSettings(s));
        sheet.secondary("Ahora no",null).show();
    }
    static void cancel(Screen s,Recording r,Runnable changed){
        s.sheet("¿Cancelar la transcripción?","Se detiene el envío a OpenAI. Los bloques ya listos no se vuelven a cobrar si la reanudas más tarde con la misma opción de voces.")
            .primary("Cancelar transcripción",Ui.Style.DESTRUCTIVE,()->{try{Pipeline.cancel(s,r.id);if(changed!=null)changed.run();}catch(Exception e){s.message("Transcripción","No se pudo cancelar el trabajo.");}return true;})
            .secondary("Seguir transcribiendo",null).show();
    }
    static void delete(Screen s,Recording r,Runnable before,Runnable after){
        s.confirm("¿Eliminar «"+r.title+"»?","Se borrarán el audio y la transcripción de este teléfono. Las copias en tu carpeta elegida se conservan. No se puede deshacer.","Eliminar",true,()->{
            if(before!=null)before.run();if(!r.delete(s))s.message("Eliminar","No se pudo eliminar el audio.");else{Diagnostics.event("recording_deleted",r.id);if(after!=null)after.run();}
        });
    }
    private RecordingActions(){}
}
