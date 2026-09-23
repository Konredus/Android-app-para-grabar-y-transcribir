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
        if(Build.VERSION.SDK_INT>=33&&s.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)s.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
        try{Pipeline.request(s,r.id);if(changed!=null)changed.run();Settings settings=new Settings(s);s.toast(settings.wifiOnly()?"En cola · se enviará con Wi-Fi":"En cola · empezará en breve");}
        catch(Exception e){s.message("No se pudo poner en cola",e instanceof HttpApi.UserAction?e.getMessage():"Vuelve a intentarlo.");}
    }
    static void cancel(Screen s,Recording r,Runnable changed){try{Pipeline.cancel(s,r.id);if(changed!=null)changed.run();}catch(Exception e){s.message("Transcripción","No se pudo cancelar el trabajo.");}}
    static void delete(Screen s,Recording r,Runnable before,Runnable after){
        s.confirm("¿Eliminar «"+r.title+"»?","Se borrarán el audio y la transcripción de este teléfono. Las copias en tu carpeta elegida se conservan. No se puede deshacer.","Eliminar",true,()->{
            if(before!=null)before.run();if(!r.delete(s))s.message("Eliminar","No se pudo eliminar el audio.");else{Diagnostics.event("recording_deleted",r.id);if(after!=null)after.run();}
        });
    }
    private RecordingActions(){}
}
