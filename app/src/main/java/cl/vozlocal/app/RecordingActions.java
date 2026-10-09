package cl.vozlocal.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        if(st.optBoolean("requested")){boolean mine=Pipeline.processing(r.id);
            return new Next(Step.WORKING,working(st.has("retranscribe"),mine,mine?null:Pipeline.blocker(c,r.id)),R.drawable.ic_clock);}
        if(!Transcript.exists(c,r.id)){
            if(st.optBoolean("failed"))return new Next(Step.RETRY,Lang.str(c,R.string.act_retry),R.drawable.ic_refresh);
            return new Next(Step.TRANSCRIBE,Lang.str(c,R.string.act_transcribe),R.drawable.ic_sparkle);
        }
        if(needsReview(c,r.id))return new Next(Step.REVIEW,Lang.str(c,R.string.act_review_voices),R.drawable.ic_people);
        if(!Inbox.configured(c))return new Next(Step.CHOOSE_FOLDER,Lang.str(c,R.string.act_choose_folder),R.drawable.ic_folder);
        String folder=shortName(Inbox.folderName(c));long at=Inbox.savedAt(c,r.id);
        if(at==0)return new Next(Step.SAVE,Lang.str(c,R.string.act_save_to,folder),R.drawable.ic_inbox);
        if(Inbox.outdated(c,r.id))return new Next(Step.UPDATE,Lang.str(c,R.string.act_update_in,folder),R.drawable.ic_refresh);
        return new Next(Step.SAVED,Lang.str(c,R.string.act_saved_in,folder,when(at)),R.drawable.ic_check);
    }
    /** El botón de una grabación pedida: «Transcribiendo…» o por qué espera. Lee textos del motor: vive en StatusText. */
    static String working(boolean again,boolean mine,String blocker){return StatusText.working(again,mine,blocker);}
    /** «16:09» si fue hoy; si no, la fecha corta en el idioma de la app: «28 sept», «Sep 28», «28 set». */
    static String when(long at){
        Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(at);
        boolean today=now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR);
        String s=new SimpleDateFormat(today?"HH:mm":Lang.str(R.string.act_date_pattern),Lang.locale()).format(new Date(at));
        return s.endsWith(".")?s.substring(0,s.length()-1):s;
    }
    /** Un nombre de carpeta largo no debe romper el botón. Sin nombre: «la carpeta» (en el idioma de la app). */
    static String shortName(String name){String n=name==null||name.trim().isEmpty()?Lang.str(R.string.act_folder_fallback):name.trim();return n.length()>18?n.substring(0,17).trim()+"…":n;}

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
 * en secondaryContainer; un error, en errorContainer; y lo que falta transcribir, en el verde de las acciones (menta).
 */
final class RecState {
    enum Kind{NEW,QUEUED,FAILED,DONE}
    final Kind kind;final String label,detail;
    private RecState(Kind kind,String label,String detail){this.kind=kind;this.label=label;this.detail=detail;}
    static RecState of(JSONObject state,boolean transcribed){
        // "requested" primero: al volver a transcribir puede quedar una versión mientras se hace la nueva.
        // Los textos van en el idioma de la app; el estado ("status") es el que escribió el motor.
        if(state.optBoolean("requested"))return new RecState(Kind.QUEUED,Lang.str(R.string.act_state_queued),state.optString("status",StatusText.queuedLine(null)));
        if(transcribed)return new RecState(Kind.DONE,Lang.str(R.string.act_state_done),Lang.str(R.string.home_transcript_ready));
        if(state.optBoolean("failed"))return new RecState(Kind.FAILED,Lang.str(R.string.act_state_failed),state.optString("status",Lang.str(R.string.home_failed)));
        return new RecState(Kind.NEW,Lang.str(R.string.act_not_transcribed),Lang.str(R.string.act_state_audio_only));
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
    /**
     * Menú de una grabación (0.7.0): arriba, la grabación misma (estado en su círculo, título en Outfit y «12 min ·
     * Transcrito»); después, las tres acciones de siempre en tarjetas chicas (como «Tu semana» de la referencia); y al
     * final la lista de lo que depende del estado, con cada ícono en un círculo menta. Eliminar va último y en rojo.
     */
    static void menu(Screen s,Recording r,Runnable changed,Runnable beforeRelease){
        RecState state=RecState.of(s,r.id);boolean transcribed=Transcript.exists(s,r.id),queued=state.kind==RecState.Kind.QUEUED;
        Ui ui=s.ui;Sheet sheet=s.sheet(null,null);
        sheet.add(header(s,r,state));
        // Siempre disponibles: a un toque, sin leer la lista. Las tres miden lo mismo aunque un nombre ocupe dos líneas.
        LinearLayout quick=ui.row();quick.setGravity(Gravity.TOP);
        quick.addView(SheetParts.quick(s,sheet,R.drawable.ic_edit,Lang.str(s,R.string.act_rename),()->rename(s,r,changed)),new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(0,-1,1);gap.setMarginStart(ui.dp(S2));
        quick.addView(SheetParts.quick(s,sheet,R.drawable.ic_share,Lang.str(s,R.string.act_share_audio),()->shareAudio(s,r)),gap);
        gap=new LinearLayout.LayoutParams(0,-1,1);gap.setMarginStart(ui.dp(S2));
        quick.addView(SheetParts.quick(s,sheet,R.drawable.ic_cut,Lang.str(s,R.string.act_trim_copy),()->{if(beforeRelease!=null)beforeRelease.run();s.startActivity(new Intent(s,ImportActivity.class).putExtra("sourceId",r.id));}),gap);
        LinearLayout.LayoutParams qlp=ui.top(S5);qlp.bottomMargin=ui.dp(S2);sheet.body.addView(quick,qlp);
        LinearLayout list=SheetParts.list(sheet);
        if(state.kind==RecState.Kind.NEW)list.addView(SheetParts.item(s,sheet,R.drawable.ic_sparkle,Lang.str(s,R.string.act_transcribe),false,()->transcribe(s,r,changed)));
        if(state.kind==RecState.Kind.FAILED){
            list.addView(SheetParts.item(s,sheet,R.drawable.ic_refresh,Lang.str(s,R.string.act_retry_transcription),false,()->transcribe(s,r,changed)));
            // Si falló al volver a transcribir, la versión anterior sigue guardada: siempre hay una salida.
            if(!transcribed&&Retranscribe.hasPrevious(s,r.id))list.addView(SheetParts.item(s,sheet,R.drawable.ic_replay,Lang.str(s,R.string.act_back_to_previous),false,()->restorePrevious(s,r,changed)));
        }
        if(transcribed&&!queued){
            if(Inbox.configured(s)){String folder=Next.shortName(Inbox.folderName(s));long at=Inbox.savedAt(s,r.id);
                list.addView(SheetParts.item(s,sheet,R.drawable.ic_inbox,Lang.str(s,at==0?R.string.act_save_to:Inbox.outdated(s,r.id)?R.string.act_update_in:R.string.act_save_again_to,folder),false,()->saveToInbox(s,r,changed)));}
            list.addView(SheetParts.item(s,sheet,R.drawable.ic_refresh,Lang.str(s,R.string.act_retranscribe),false,()->RetranscribeSheet.show(s,r,changed)));
            if(Retranscribe.hasPrevious(s,r.id))list.addView(SheetParts.item(s,sheet,R.drawable.ic_replay,Lang.str(s,R.string.act_choose_version),false,()->RetranscribeSheet.offerKeep(s,r,changed)));
        }
        // «Cancelar transcripción» queda lejos del resto (antes de Eliminar) y siempre se confirma.
        if(queued)list.addView(SheetParts.item(s,sheet,R.drawable.ic_close,Lang.str(s,R.string.act_cancel_transcription),false,()->cancel(s,r,changed)));
        list.addView(SheetParts.item(s,sheet,R.drawable.ic_trash,Lang.str(s,R.string.act_delete),true,()->delete(s,r,beforeRelease,changed)));
        sheet.show();Diagnostics.event("recording_menu",r.id);
    }
    /** Cabecera del menú: el estado en su círculo tonal (el mismo de la Biblioteca), el título y «duración · estado». */
    private static View header(Screen s,Recording r,RecState state){
        Ui ui=s.ui;Palette p=s.p;
        // Arriba y no al centro: con un título de 3 líneas el círculo queda junto a la primera, no flotando al medio.
        LinearLayout h=ui.row();h.setGravity(Gravity.TOP);
        h.addView(ui.tile(state.icon(),state.onBg(p),state.bg(p),48,24));h.addView(ui.space(S4));
        LinearLayout texts=ui.column();
        TextView t=ui.heading(r.title,Type.TITLE_LARGE);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);texts.addView(t);
        // El estado va en su color solo si pide atención (RecState.fg): lo terminado queda en gris, en silencio.
        String duration=Ui.humanDuration(r.duration);SpannableString meta=new SpannableString(duration+" · "+state.label);
        meta.setSpan(new ForegroundColorSpan(state.fg(p)),duration.length()+3,meta.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView m=Ui.tabular(ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant));m.setText(meta);m.setPadding(0,ui.dp(2),0,0);texts.addView(m);
        h.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        return h;
    }

    /** El botón principal que avanza (ver {@link Next}): hace lo que corresponde al paso actual de la grabación. */
    static void advance(Screen s,Recording r,Runnable changed){
        Next next=Next.of(s,r);Diagnostics.event("next_step",r.id,"step",next.step.name());
        switch(next.step){
            case TRANSCRIBE:case RETRY:transcribe(s,r,changed);break;
            case WORKING:
                if(s instanceof RecordingActivity){JSONObject st=FilesStore.state(s,r.id);s.toast(RecordingActivity.inDetail(st.optString("status",Lang.str(s,R.string.act_doing_transcribe))));}
                else s.startActivity(new Intent(s,RecordingActivity.class).putExtra("id",r.id));
                break;
            case REVIEW:
                if(s instanceof RecordingActivity){try{NameVoices.show(s,r.id,false,Transcript.load(s,r.id),changed);}catch(Exception e){s.message(Lang.str(s,R.string.act_review_voices),Lang.str(s,R.string.act_open_failed));}}
                else s.startActivity(new Intent(s,RecordingActivity.class).putExtra("id",r.id).putExtra("names",true));
                break;
            case SAVE:case UPDATE:saveToInbox(s,r,changed);break;
            case SAVED:savedSheet(s,r,changed);break;
            case CHOOSE_FOLDER:chooseFolder(s);break;
        }
    }
    /** Ya está en la carpeta rápida: explica cuándo se guardó y permite guardar de nuevo (reemplaza el mismo archivo). */
    private static void savedSheet(Screen s,Recording r,Runnable changed){
        String folder=Inbox.folderName(s);long at=Inbox.savedAt(s,r.id);String when=Next.when(at);
        // Hoy va con la hora («hoy a las 16:09»); otro día, con la fecha («el 28 sept»).
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_already_in,folder),Lang.str(s,when.contains(":")?R.string.act_saved_today_body:R.string.act_saved_day_body,when));
        SheetParts.hero(sheet,R.drawable.ic_check_circle,false);
        sheet.primary(Lang.str(s,R.string.act_save_again),()->saveToInbox(s,r,changed)).secondary(Lang.str(s,R.string.ui_close),null).show();
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
                if(done){Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast(Lang.str(s,R.string.act_saved_to,folder));if(changed!=null)changed.run();return;}
                Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.REJECT);
                Sheet sheet=s.sheet(Lang.str(s,R.string.act_save_failed_title,folder),reason!=null?reason:Lang.str(s,R.string.act_save_failed_body));
                SheetParts.hero(sheet,R.drawable.ic_alert,true);
                sheet.primary(Lang.str(s,R.string.act_choose_folder_again),()->chooseFolder(s)).secondary(Lang.str(s,R.string.ui_close),null).show();
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

    /**
     * Cambiar título: el mismo flujo de 0.6.0, porque en 0.7.0 solo cambia la interfaz. El campo trae el título completo
     * (con su fecha, si la tiene) y se edita entero; un título que es solo la fecha se guarda tal cual; y la tecla de
     * acción del teclado no guarda ni cierra la hoja: eso lo hace solo «Guardar». De 0.7.0 queda únicamente el estilo del
     * campo, que ya viene del kit (ui.field con ui.fieldBackground). La fecha fija en una píldora es solo de «Nombra
     * esta grabación» (PROPUESTA-0.7 §3.3), que ya la tenía en 0.6.0.
     */
    static void rename(Screen s,Recording r,Runnable changed){
        EditText input=s.ui.field(Lang.str(s,R.string.act_title_hint),Lang.str(s,R.string.home_recording_title));input.setText(r.title);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_rename),null).add(input);
        sheet.primary(Lang.str(s,R.string.act_save),Ui.Style.PRIMARY,()->{
            String title=input.getText().toString().trim();if(title.isEmpty()){input.setError(Lang.str(s,R.string.act_title_required));return false;}
            String previous=r.title;r.title=title;
            try{r.save(s);Pipeline.edited(s,r.id);Diagnostics.event("title_edited",r.id);if(changed!=null)changed.run();return true;}
            catch(Exception e){r.title=previous;input.setError(Lang.str(s,R.string.act_title_save_failed));return false;}
        }).secondary(Lang.str(s,R.string.common_cancel),null).show();
        sheet.keyboard(input);
    }
    static void shareAudio(Screen s,Recording r){
        Uri uri=AudioProvider.uri(s,r.id+".m4a");
        Intent share=new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,r.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri(r.title,uri));s.startActivity(Intent.createChooser(share,Lang.str(s,R.string.act_share_audio)));
    }
    // ---------- Proveedor (0.8.0): lo que cambia en las pantallas según con quién se transcribe ----------
    /** Nombre para los textos: «OpenAI», «OpenRouter» o «tu servidor» (cualquier otro valor es un servidor propio; en el idioma de la app). */
    static String providerName(String provider){return "openrouter".equals(provider)?"OpenRouter":"openai".equals(provider)?"OpenAI":Lang.str(R.string.act_your_server);}
    static String providerName(Settings s){return providerName(s.provider());}
    // ¿Las voces conocidas viajan con el audio? Lo decide el motor, en un solo lugar: TranscribeClient.knowsVoices(provider).
    /** ¿El proveedor cobra cada audio? Con un servidor propio no se sabe: ahí se dice «se envía», no «se cobra». */
    static boolean paid(Settings s){return s.provider().equals("openai")||s.openRouter();}
    /** Modelo con que se transcribiría hoy. Igual que Settings.config(), pero sin leer la clave (sirve para armar pantallas). */
    static String model(Settings s,boolean speakers){
        if(s.provider().equals("openai"))return speakers?"gpt-4o-transcribe-diarize":s.textModel();
        return s.openRouter()?Models.chosen(s,speakers):s.prefs.getString("customModel","whisper-1");
    }
    // Costos: el estimado sale de Pricing.estimate(Context, proveedor, modelo, ms) (OpenAI por su tabla; OpenRouter por el
    // catálogo guardado, que Models ya recuerda en memoria) y el real, de Pricing.real(estado). El audio que se cobra (con
    // las muestras de voz que OpenRouter recibe delante de cada bloque) es una regla única del motor: Pricing.billedMs.
    /** Audio que cobra OpenRouter al transcribir (ms), para los estimados con «≈»: la regla única, Pricing.billedMs. */
    static long billedMs(String provider,String model,long durationMs,int anchors,boolean single){
        return Pricing.billedMs(provider,model,durationMs,anchors,single);
    }

    /**
     * Encola la transcripción. Si falta la clave, lleva directo a configurarla; si ya está transcrita, ofrece volver a transcribir.
     * 0.9.0 (Google Play): antes del primer envío se muestra el aviso de qué se envía y a quién (Consent), una sola vez;
     * aceptado, sigue sin preguntar nada más. Lo usan «Transcribir» y «Reintentar» de Grabar, la Biblioteca, el menú y el
     * botón que avanza (advance).
     */
    static void transcribe(Screen s,Recording r,Runnable changed){
        if(Transcript.exists(s,r.id)){RetranscribeSheet.show(s,r,changed);return;}
        if(!new Settings(s).hasKey()){missingKey(s);return;}
        Consent.ensure(s,()->guard(s,r,()->{
            Settings settings=new Settings(s);
            if(settings.canSeparate()&&settings.speakersMode().equals("ask")){askSpeakers(s,r,changed,settings);return;}
            start(s,r,changed,settings.defaultSpeakers());
        }));
    }
    /** 0.9.6: sobre este costo estimado (US$) se pide confirmar antes de enviar. Lo normal (una hora, sin voces) cuesta menos. */
    static final double CONFIRM_USD=0.50;
    /**
     * 0.9.6: antes de gastar. Una grabación casi muda (el micrófono no captó nada) pregunta si transcribir igual; después,
     * un costo alto o mayor que el último saldo comprobado pide confirmar. Con «Preguntar cada vez» el costo ya se ve en
     * las opciones, así que ahí solo se mira el silencio.
     */
    static void guard(Screen s,Recording r,Runnable go){
        if(RecorderService.mostlySilent(FilesStore.state(s,r.id))){
            Diagnostics.event("transcribe_guard",r.id,"reason","silent");
            Sheet sheet=s.sheet(Lang.str(s,R.string.act_silent_title),Lang.str(s,R.string.act_silent_body));
            sheet.primary(Lang.str(s,R.string.act_transcribe_anyway),()->costGuard(s,r,go)).secondary(Lang.str(s,R.string.common_cancel),null).show();return;
        }
        costGuard(s,r,go);
    }
    private static void costGuard(Screen s,Recording r,Runnable go){
        double cost=-1,balance=Double.NaN;Settings settings=new Settings(s);
        try{
            if(!(settings.canSeparate()&&settings.speakersMode().equals("ask"))){
                boolean speakers=settings.defaultSpeakers();
                cost=Pricing.estimate(s,settings.provider(),settings.config(speakers).model,Pricing.orBilledMs(s,r.duration,speakers));
            }
            balance=Double.parseDouble(settings.prefs.getString("verifyBalance",""));
        }catch(Exception ignored){}
        boolean over=cost>0&&!Double.isNaN(balance)&&cost>balance;
        if(cost<CONFIRM_USD&&!over){go.run();return;}
        Diagnostics.event("transcribe_guard",r.id,"reason",over?"balance":"cost");
        String price=Pricing.usd(cost);
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_cost_title,price),over?Lang.str(s,R.string.act_cost_over_body,Pricing.usd(balance)):Lang.str(s,R.string.act_cost_body,Ui.humanDuration(r.duration)));
        sheet.primary(Lang.str(s,R.string.act_transcribe),go).secondary(Lang.str(s,R.string.common_cancel),null).show();
    }
    /**
     * Falta la clave de OpenRouter: el error trae su salida («Configurar ahora», que abre directo el campo de la clave).
     * A quien actualizó desde una versión con OpenAI (o su servidor) se le explica por qué se le pide otra clave: la que
     * tenía queda guardada, pero la app ya no la usa (SPEC-0.8b, decisión 2).
     */
    static void missingKey(Screen s){
        String old=new Settings(s).oldService();
        Sheet sheet=s.sheet(Lang.str(s,old!=null?R.string.act_now_openrouter_title:R.string.act_missing_key_title),old!=null
            ?Lang.str(s,R.string.act_now_openrouter_body,old)
            :Lang.str(s,R.string.act_missing_key_body));
        SheetParts.hero(sheet,R.drawable.ic_key,false);
        sheet.primary(Lang.str(s,R.string.act_setup_now),()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("focusKey",true))).secondary(Lang.str(s,R.string.act_later),null).show();
    }
    /**
     * Pregunta si separar voces, con el costo y la velocidad de cada opción para este audio. La velocidad y el costo van
     * en píldoras bajo la explicación (0.7.0): se comparan de un vistazo; el lector de pantalla lee la frase completa.
     */
    static void askSpeakers(Screen s,Recording r,Runnable changed,Settings settings){
        try{
            ProviderConfig withVoices=settings.config(true);String voices=withVoices.model,text=settings.config(false).model,provider=settings.provider();
            // El texto en vivo solo existe con gpt-transcribe de OpenAI directo (OpenRouter responde todo al final).
            boolean live=provider.equals("openai")&&text.equals("gpt-transcribe");
            // Voces conocidas que van en este audio (hasta 4, la tuya primero): se dice a quién reconoce desde el inicio.
            List<Voices.Voice> known=TranscribeClient.knowsVoices(provider)?Voices.selected(s):Collections.emptyList();
            // Con voces, OpenRouter cobra también las muestras que van delante de cada bloque: el estimado las suma con la
            // regla única del motor (Pricing.orBilledMs, con lo elegido en Ajustes), la misma que usa el detalle de la grabación.
            long billed=Pricing.orBilledMs(s,r.duration,withVoices.speakers);
            String costVoices=Pricing.usd(Pricing.estimate(s,provider,voices,billed)),costText=Pricing.usd(Pricing.estimate(s,provider,text,r.duration));
            boolean onlyMe=known.size()==1&&known.get(0).me;
            // Con OpenRouter el reconocimiento usa «anclas», una técnica nueva: se promete el intento, no el resultado.
            boolean sure=!settings.openRouter();
            // Las voces sin reconocer se llaman «Persona 2…» (el mismo texto que la transcripción, en el idioma de la app).
            String others=Lang.str(s,R.string.speaker_n,known.size()+1);
            String who=known.isEmpty()?Lang.str(s,R.string.act_who_new,Lang.str(s,R.string.speaker_n,1),Lang.str(s,R.string.speaker_n,2))
                :onlyMe?Lang.str(s,sure?R.string.act_who_me_sure:R.string.act_who_me_try,known.get(0).name,others)
                :Lang.str(s,known.get(0).me?(sure?R.string.act_who_with_me_sure:R.string.act_who_with_me_try):(sure?R.string.act_who_known_sure:R.string.act_who_known_try),Voices.people(known),others);
            Sheet sheet=s.sheet(Lang.str(s,R.string.act_speakers_title),Lang.str(s,R.string.act_speakers_body,Ui.humanDuration(r.duration)));
            LinearLayout list=SheetParts.list(sheet);
            String yes=Lang.str(s,known.isEmpty()?R.string.act_speakers_yes:onlyMe?R.string.act_speakers_yes_me:R.string.act_speakers_yes_known);
            String yesDetail=who+" · "+Lang.str(s,R.string.act_slower_inline)+(costVoices.equals("—")?"":" · ≈ "+costVoices);
            List<SheetParts.Fact> yesFacts=new ArrayList<>();yesFacts.add(SheetParts.fact(R.drawable.ic_hourglass,Lang.str(s,R.string.act_slower)));if(!costVoices.equals("—"))yesFacts.add(SheetParts.cost("≈ "+costVoices));
            list.addView(SheetParts.option(s,R.drawable.ic_people,yes,who,yesFacts,true,yes+". "+yesDetail,()->{sheet.dismiss();start(s,r,changed,true);}));
            String no=Lang.str(s,R.string.act_speakers_no),noDetail=Lang.str(s,live?R.string.act_speakers_no_detail_live:R.string.act_speakers_no_detail)+(costText.equals("—")?"":" · ≈ "+costText);
            List<SheetParts.Fact> noFacts=new ArrayList<>();noFacts.add(SheetParts.fact(R.drawable.ic_bolt,Lang.str(s,R.string.act_faster)));if(live)noFacts.add(SheetParts.fact(R.drawable.ic_transcribe,Lang.str(s,R.string.act_live_text)));if(!costText.equals("—"))noFacts.add(SheetParts.cost("≈ "+costText));
            list.addView(SheetParts.option(s,R.drawable.ic_doc,no,Lang.str(s,R.string.act_speakers_no_for),noFacts,true,no+". "+noDetail,()->{sheet.dismiss();start(s,r,changed,false);}));
            // Sin "Mi voz", la separación se equivoca más al inicio: se sugiere grabarla (una sola vez).
            if(TranscribeClient.knowsVoices(provider)&&!Voices.has(s))list.addView(SheetParts.item(s,sheet,R.drawable.ic_mic_fill,Lang.str(s,R.string.act_record_my_voice),false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true))));
            sheet.show();
        }catch(Exception e){start(s,r,changed,settings.defaultSpeakers());}
    }
    static void start(Screen s,Recording r,Runnable changed,boolean speakers){
        askNotifications(s);
        try{Pipeline.request(s,r.id,speakers);Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);if(changed!=null)changed.run();s.toast(queuedToast(s,r,Lang.str(s,R.string.act_doing_transcribe)));}
        catch(Exception e){s.message(Lang.str(s,R.string.act_queue_failed),e instanceof HttpApi.UserAction?e.getMessage():Lang.str(s,R.string.act_try_again));}
    }
    /**
     * Aviso breve al pedir una transcripción («Transcribir», «Volver a transcribir»), con lo que espera ESTA grabación
     * (Pipeline.blocker con su id). El de todas no sirve: con otra grabación autorizada a usar datos móviles no ve el Wi-Fi,
     * y decía «Transcribiendo» de una que en verdad esperaba Wi-Fi. En el detalle, el Wi-Fi sin el paréntesis que manda al
     * detalle (RecordingActivity.inDetail). doing: «Transcribiendo» o «Volviendo a transcribir».
     */
    static String queuedToast(Screen s,Recording r,String doing){
        return queuedToast(doing,Pipeline.blocker(s,r.id),behind(r.id),s instanceof RecordingActivity);
    }
    /**
     * ¿El trabajo andando está con OTRA grabación? La transcribe (Transcriber.currentId) o espera para reintentarla
     * (Transcriber.retryingId): esta espera su turno. Antes solo miraba currentId, y detrás de una que esperaba su reintento
     * decía «Transcribiendo · sigue aunque bloquees el teléfono».
     */
    static boolean behind(String id){return behind(Pipeline.working(),Pipeline.processing(id),Transcriber.currentId,Transcriber.retryingId);}
    /** Lo mismo con lo ya leído (separado del teléfono para poder probarlo). mine: Pipeline.processing de esta grabación. */
    static boolean behind(boolean working,boolean mine,String current,String retrying){return working&&!mine&&(current!=null||retrying!=null);}
    /** El aviso breve al pedir una transcripción (ver StatusText.queuedToast). */
    static String queuedToast(String doing,String blocker,boolean behind,boolean detail){return StatusText.queuedToast(doing,blocker,behind,detail);}
    /** Android 13+: el aviso de «lista» necesita permiso de notificaciones; se pide al encolar. */
    static void askNotifications(Screen s){
        if(Build.VERSION.SDK_INT>=33&&s.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)s.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
    }
    /** Explica por qué conviene quitar la optimización de batería y abre el permiso del sistema. */
    static void allowBackground(Screen s){
        String hint=Battery.makerHint();
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_background_title),Lang.str(s,R.string.act_background_body,providerName(new Settings(s)))+(hint.isEmpty()?"":"\n\n"+hint));
        SheetParts.hero(sheet,R.drawable.ic_battery,false);
        sheet.primary(Lang.str(s,R.string.act_allow),()->{Diagnostics.event("ui_action",null,"action","battery_request");Battery.request(s);});
        if(!hint.isEmpty())sheet.secondary(Lang.str(s,R.string.act_open_app_settings),()->Battery.appSettings(s));
        sheet.secondary(Lang.str(s,R.string.act_not_now),null).show();
    }
    /**
     * Cancelar pierde el trabajo en curso: siempre se confirma (pedido del usuario, 0.4.3).
     * Si era una nueva versión (volver a transcribir), la anterior se recupera: nunca te quedas sin transcripción.
     */
    static void cancel(Screen s,Recording r,Runnable changed){
        boolean again=!Transcript.exists(s,r.id)&&Retranscribe.hasPrevious(s,r.id);
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_cancel_title),Lang.str(s,again?R.string.act_cancel_body_again:R.string.act_cancel_body));
        SheetParts.hero(sheet,R.drawable.ic_close,true);
        sheet.primary(Lang.str(s,R.string.act_cancel_transcription),Ui.Style.DESTRUCTIVE,()->{
                try{Pipeline.cancel(s,r.id);
                    if(again&&Retranscribe.hasPrevious(s,r.id)&&!Transcript.exists(s,r.id)){try{Retranscribe.restorePrevious(s,r.id);}catch(Exception e){Diagnostics.event("retranscribe_restore_failed",r.id,"error_class",e.getClass().getSimpleName());}}
                    if(changed!=null)changed.run();}
                catch(Exception e){s.message(Lang.str(s,R.string.act_transcription),Lang.str(s,R.string.act_cancel_failed));}return true;})
            .secondary(Lang.str(s,R.string.act_keep_transcribing),null).show();
    }
    /** Vuelve a la versión anterior sin preguntar de nuevo (se llega aquí desde un menú que ya lo nombra). */
    static void restorePrevious(Screen s,Recording r,Runnable changed){
        try{Retranscribe.restorePrevious(s,r.id);Diagnostics.event("retranscribe_restored",r.id);Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast(Lang.str(s,R.string.act_restored));if(changed!=null)changed.run();}
        catch(Exception e){s.message(Lang.str(s,R.string.act_previous_version),Lang.str(s,R.string.act_restore_failed));}
    }
    /** Siempre se confirma, nombrando la grabación. Es lo mismo que Screen.confirm, más el ícono de aviso en rojo. */
    static void delete(Screen s,Recording r,Runnable before,Runnable after){
        Sheet sheet=s.sheet(Lang.str(s,R.string.act_delete_title,r.title),Lang.str(s,R.string.act_delete_body));
        SheetParts.hero(sheet,R.drawable.ic_trash,true);
        sheet.primary(Lang.str(s,R.string.act_delete),Ui.Style.DESTRUCTIVE,()->{
            if(before!=null)before.run();trash(s,r);if(after!=null)after.run();offerUndo(s);
            return true;
        }).secondary(Lang.str(s,R.string.common_cancel),null).show();
    }

    /*
     * 0.9.6: eliminar se puede deshacer. La grabación se oculta al instante (Recording.HIDDEN) y sus archivos se borran
     * UNDO_MS después; mientras, «Grabación eliminada · Deshacer» la devuelve tal cual. Si la app se cierra antes, no se
     * borra nada (vuelve a aparecer): nunca se pierde por un toque. Un segundo borrado confirma el anterior.
     */
    static final long UNDO_MS=10_000;
    private static final android.os.Handler TRASH=new android.os.Handler(android.os.Looper.getMainLooper());
    private static Recording trashed;private static Context trashApp;private static java.lang.ref.WeakReference<Screen> shownOn;
    static void trash(Context c,Recording r){
        commitTrash();trashed=r;trashApp=c.getApplicationContext();Recording.HIDDEN.add(r.id);FilesStore.version.incrementAndGet();
        TRASH.postDelayed(RecordingActions::commitTrash,UNDO_MS);Diagnostics.event("recording_trashed",r.id);
    }
    /** Borra de verdad lo que estaba esperando el «Deshacer». */
    static void commitTrash(){
        TRASH.removeCallbacksAndMessages(null);Recording r=trashed;if(r==null)return;trashed=null;Context c=trashApp;
        Screen shown=shownOn==null?null:shownOn.get();shownOn=null;if(shown!=null&&!shown.isFinishing())shown.hideSnackbar();
        new Thread(()->{boolean ok=false;try{ok=r.delete(c);}catch(Exception e){Diagnostics.crash(e);}
            Recording.HIDDEN.remove(r.id);FilesStore.version.incrementAndGet();Diagnostics.event(ok?"recording_deleted":"recording_delete_failed",r.id);},"VozLocal-delete").start();
    }
    static void undoTrash(){
        TRASH.removeCallbacksAndMessages(null);Recording r=trashed;trashed=null;shownOn=null;if(r==null)return;
        Recording.HIDDEN.remove(r.id);FilesStore.version.incrementAndGet();Diagnostics.event("recording_restored",r.id);
    }
    /** «Grabación eliminada · Deshacer» en esta pantalla, si hay un borrado esperando (también al volver del detalle). */
    static void offerUndo(Screen s){
        Recording r=trashed;if(r==null||s.isFinishing())return;shownOn=new java.lang.ref.WeakReference<>(s);
        s.snackbar(Lang.str(s,R.string.act_deleted,r.title),Lang.str(s,R.string.detail_undo),RecordingActions::undoTrash);
    }
    private RecordingActions(){}
}

/**
 * Piezas de las hojas de acciones de Verbapp (0.7.0), compartidas por {@link RecordingActions}, {@link NameVoices} y
 * {@link RetranscribeSheet}. Viven aquí porque el kit ({@link Sheet}, {@link Ui}) no se toca en esta versión; son
 * candidatas a subir a él. Siguen la estética «Bosque de vidrio»: círculos menta, tarjetas suaves y píldoras.
 */
final class SheetParts {
    private SheetParts(){}

    /**
     * Fondo de una tarjeta dentro de una hoja. La hoja es blanca: el vidrio blanco de las pantallas no se vería, así que
     * en claro va un gris verdoso muy suave; en oscuro, el vidrio de siempre (translúcido con borde claro) sí se ve.
     */
    static GradientDrawable card(Context c,Palette p,int radius){return p.dark?glass(c,p,radius):shape(c,p.surfaceContainerLow,radius);}

    /**
     * Ícono protagonista de un aviso: círculo menta (o rojo, si el aviso es de error o destructivo) con un halo suave,
     * como el botón de micrófono de Grabar. Va arriba, sobre el título y alineado con su borde izquierdo.
     */
    static void hero(Sheet sheet,int icon,boolean alert){
        Ui ui=sheet.ui;Palette p=ui.p;int bg=alert?p.errorContainer:p.primaryContainer,fg=alert?p.error:p.onPrimaryContainer;
        int ring=ui.dp(S2);LayerDrawable d=new LayerDrawable(new Drawable[]{oval(withAlpha(bg,p.dark?0x66:0x80)),oval(bg)});d.setLayerInset(1,ring,ring,ring,ring);
        FrameLayout f=new FrameLayout(ui.c);f.setBackground(d);f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(ui.icon(icon,fg,26),new FrameLayout.LayoutParams(ui.dp(26),ui.dp(26),Gravity.CENTER));
        // El halo sobresale 8 dp: el círculo sólido queda alineado con el texto.
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(64),ui.dp(64));lp.setMarginStart(-ring);lp.topMargin=-ui.dp(S1);lp.bottomMargin=ui.dp(S3);
        sheet.body.addView(f,0,lp);
    }

    /** Lista de filas de borde a borde dentro de la hoja (el toque se ve en todo el ancho, como en Sheet.action). */
    static LinearLayout list(Sheet sheet){
        Ui ui=sheet.ui;LinearLayout l=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);sheet.body.addView(l,lp);return l;
    }
    /**
     * Opción de menú con su ícono en un círculo menta; las destructivas, en círculo rojo claro y texto rojo.
     * Hace lo mismo que Sheet.action: registra la acción, cierra la hoja y la ejecuta.
     */
    static View item(Screen s,Sheet sheet,int icon,String label,boolean destructive,Runnable run){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(56));row.setPadding(ui.dp(S6),ui.dp(S2),ui.dp(S6),ui.dp(S2));
        row.addView(ui.tile(icon,destructive?p.error:p.onPrimaryContainer,destructive?p.errorContainer:p.primaryContainer,40,20));row.addView(ui.space(S4));
        row.addView(ui.text(label,Type.ITEM,destructive?p.error:p.onSurface),new LinearLayout.LayoutParams(0,-2,1));
        row.setBackground(ui.ripple(null,0));act(s,sheet,row,label,run);
        return row;
    }
    /** Acción rápida en tarjeta chica: círculo menta arriba y el nombre abajo (hasta 3 líneas), como los datos de «Tu semana». */
    static View quick(Screen s,Sheet sheet,int icon,String label,Runnable run){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout t=ui.column();t.setGravity(Gravity.CENTER_HORIZONTAL);t.setPadding(ui.dp(S2),ui.dp(S4),ui.dp(S2),ui.dp(S3));t.setMinimumHeight(ui.dp(96));
        t.setBackground(ui.ripple(card(s,p,R_CARD-4),R_CARD-4));
        t.addView(ui.tile(icon,p.onPrimaryContainer,p.primaryContainer,40,20));
        TextView l=ui.text(label,Type.LABEL_LARGE,p.onSurface);l.setGravity(Gravity.CENTER);l.setMaxLines(3);l.setEllipsize(TextUtils.TruncateAt.END);l.setPadding(0,ui.dp(S2),0,0);l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);t.addView(l,Ui.fill());
        act(s,sheet,t,label,run);Ui.pressable(t);
        return t;
    }
    /** Toque de una opción: el mismo registro que Sheet.action (pantalla y etiqueta), cierra la hoja y ejecuta. */
    private static void act(Screen s,Sheet sheet,View v,String label,Runnable run){
        v.setClickable(true);v.setFocusable(true);v.setContentDescription(label);v.setAccessibilityDelegate(Ui.buttonRole());
        v.setOnClickListener(x->{Diagnostics.event("ui_action",null,"screen",s.getClass().getSimpleName(),"action",label);sheet.dismiss();run.run();});
    }

    /** Dato corto de una opción (velocidad, costo, «Recomendada»), que va en una píldora chica bajo la explicación. */
    static final class Fact {
        final int icon;final String text;final boolean accent,tabular;
        Fact(int icon,String text,boolean accent,boolean tabular){this.icon=icon;this.text=text;this.accent=accent;this.tabular=tabular;}
    }
    static Fact fact(int icon,String text){return new Fact(icon,text,false,false);}
    /** Costo estimado: cifras de ancho fijo. */
    static Fact cost(String text){return new Fact(0,text,false,true);}
    /** La alternativa recomendada: píldora en el verde de marca con ✦ (se distingue de los círculos menta). */
    static Fact recommended(){return new Fact(R.drawable.ic_sparkle,Lang.str(R.string.act_recommended),true,false);}

    /**
     * Opción grande de dos líneas, como Sheet.option (círculo menta de 44 dp, título y explicación), con sus datos en
     * píldoras abajo. Puede mostrarse desactivada con su motivo: no se esconde, así se entiende por qué no se puede.
     * spoken: lo que lee el lector de pantalla (la frase completa, con los datos de las píldoras).
     * run: lo que hace al tocarla (quien la usa cierra su hoja).
     */
    static View option(Screen s,int icon,String label,String detail,List<Fact> facts,boolean enabled,String spoken,Runnable run){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setMinimumHeight(ui.dp(72));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        FrameLayout tile=ui.tile(icon,p.onPrimaryContainer,p.primaryContainer,44,22);row.addView(tile);row.addView(ui.space(S4));
        LinearLayout texts=ui.column();texts.setPadding(0,ui.dp(2),0,0);
        TextView t=ui.text(label,Type.TITLE_MEDIUM,p.onSurface);texts.addView(t);
        TextView d=ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);
        if(enabled&&facts!=null&&!facts.isEmpty()){
            Flow f=new Flow(s,ui.dp(6),ui.dp(6));for(Fact x:facts)f.addView(pill(s,x));
            LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S2);texts.addView(f,lp);
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        if(enabled){
            row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(spoken);row.setAccessibilityDelegate(Ui.buttonRole());
            row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",s.getClass().getSimpleName(),"action",label);run.run();});
        }else{
            // Desactivada: el título y el círculo se atenúan; el motivo se lee completo.
            tile.setAlpha(0.38f);t.setAlpha(0.38f);row.setContentDescription(spoken);
        }
        return row;
    }
    /** Píldora de 24 dp de un dato: gris suave; la recomendada, en verde de marca. */
    private static TextView pill(Screen s,Fact f){
        Ui ui=s.ui;Palette p=s.p;int fg=f.accent?p.onPrimary:p.onSurfaceVariant,bg=f.accent?p.primary:p.dark?p.surfaceContainerHighest:p.surfaceContainerHigh;
        TextView t=ui.text(f.text,Type.LABEL_MEDIUM,fg);t.setBackground(shape(s,bg,R_FULL));t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);t.setMinHeight(ui.dp(24));
        t.setPaddingRelative(ui.dp(f.icon!=0?S2:10),ui.dp(2),ui.dp(10),ui.dp(2));t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(f.tabular)Ui.tabular(t);
        if(f.icon!=0){Drawable d=s.getDrawable(f.icon).mutate();d.setTint(fg);d.setBounds(0,0,ui.dp(14),ui.dp(14));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(ui.dp(S1));}
        return t;
    }

    /**
     * Fila que baja a la línea siguiente cuando lo que lleva no cabe (píldoras de datos). Así nada se corta en un
     * teléfono angosto ni con la letra grande de Accesibilidad.
     */
    static final class Flow extends ViewGroup {
        private final int gapH,gapV;
        Flow(Context c,int gapH,int gapV){super(c);this.gapH=gapH;this.gapV=gapV;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int max=MeasureSpec.getSize(widthSpec);boolean bounded=MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED;
            int x=0,y=0,line=0,widest=0;
            for(int i=0;i<getChildCount();i++){
                View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
                v.measure(bounded?MeasureSpec.makeMeasureSpec(max,MeasureSpec.AT_MOST):MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                int w=v.getMeasuredWidth(),h=v.getMeasuredHeight();
                if(bounded&&x>0&&x+w>max){y+=line+gapV;x=0;line=0;}
                x+=w;widest=Math.max(widest,x);x+=gapH;line=Math.max(line,h);
            }
            setMeasuredDimension(resolveSize(widest,widthSpec),resolveSize(y+line,heightSpec));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            int max=r-l,x=0,y=0,line=0;boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
            for(int i=0;i<getChildCount();i++){
                View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
                int w=v.getMeasuredWidth(),h=v.getMeasuredHeight();
                if(x>0&&x+w>max){y+=line+gapV;x=0;line=0;}
                int left=rtl?max-x-w:x;v.layout(left,y,left+w,y+h);
                x+=w+gapH;line=Math.max(line,h);
            }
        }
    }
}
