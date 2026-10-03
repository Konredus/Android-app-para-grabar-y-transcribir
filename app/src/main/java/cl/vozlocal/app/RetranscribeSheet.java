package cl.vozlocal.app;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Hojas «¿Cómo quieres volver a transcribir?» y «Nueva versión lista» (0.6.0).
 * Repetir exactamente lo mismo no sirve: cada opción cambia algo que importa, de la más recomendada a la menos.
 * La versión actual se guarda como anterior hasta que eliges con cuál quedarte.
 * 0.7.0 (Verbapp): las alternativas llevan círculos menta y sus datos (recomendada, costo) en píldoras; «Nueva versión
 * lista» compara las dos versiones en dos tarjetas lado a lado. Las piezas comunes están en {@link SheetParts}.
 */
final class RetranscribeSheet {
    private RetranscribeSheet(){}

    /** «Mi voz» solo cuenta si el servicio la recibe: OpenAI (muestras) u OpenRouter (anclas); un servidor propio no. */
    private static boolean myVoice(Context c){return TranscribeClient.knowsVoices(new Settings(c).provider())&&Voices.has(c);}
    /** Texto de cada alternativa: título y para qué sirve (sin jerga), en el idioma de la app. */
    static String title(Context c,Retranscribe.Mode mode){
        if(mode==Retranscribe.Mode.SPEAKERS&&myVoice(c))return Lang.str(c,R.string.retr_mode_speakers_me);
        return Lang.str(c,Retranscribe.labelId(mode));
    }
    static String explain(Context c,Retranscribe.Mode mode){
        boolean router=new Settings(c).openRouter();
        switch(mode){
            // Con OpenRouter las muestras van delante del audio («anclas»): es nuevo, así que se pide revisar el resultado.
            case CORRECTIONS:{String base=Lang.str(c,R.string.retr_explain_corrections);return router?base+" "+Lang.str(c,R.string.retr_explain_router_new):base;}
            // El tope lo da el motor (Retranscribe.singleMaxMs): 23 min con OpenAI; con OpenRouter, lo que acepta el modelo
            // elegido (20 min como máximo). Es el mismo número que usa el motivo cuando el audio no cabe.
            case SINGLE:return Lang.str(c,R.string.retr_explain_single,Retranscribe.singleMaxMs(c)/60_000);
            // Con OpenRouter el reconocimiento usa «anclas», una técnica nueva: se promete el intento, no el resultado.
            case SPEAKERS:return myVoice(c)?Lang.str(c,router?R.string.retr_explain_speakers_find:R.string.retr_explain_speakers_me,Voices.name(c),Lang.str(c,R.string.speaker_n,2))
                :Lang.str(c,R.string.retr_explain_speakers,Lang.str(c,R.string.speaker_n,1),Lang.str(c,R.string.speaker_n,2));
            default:return Lang.str(c,R.string.retr_explain_text);
        }
    }
    static int icon(Retranscribe.Mode mode){
        switch(mode){case CORRECTIONS:return R.drawable.ic_sparkle;case SINGLE:return R.drawable.ic_wave;case SPEAKERS:return R.drawable.ic_people;default:return R.drawable.ic_doc;}
    }
    /** «≈ US$0,048», o "" si no se conoce la tarifa (p. ej. servidor propio, o un modelo de OpenRouter sin precio por minuto). */
    static String cost(Context c,Recording r,Retranscribe.Mode mode){
        // Lo mismo que Retranscribe.cost (proveedor y modelo de esta alternativa, precio con el Context), sobre el audio que
        // OpenRouter cobra de verdad: la duración más las muestras de voz que van delante de cada bloque (billed).
        try{
            Settings s=new Settings(c);ProviderConfig config=s.config(Retranscribe.speakers(mode));
            double v=Pricing.estimate(c,config.provider,config.model,billed(c,r,mode,config));
            return v<0?"":"≈ "+Pricing.usd(v);
        }catch(Exception e){return "";}
    }
    /**
     * Audio que se cobraría con esta alternativa, con la regla única del motor (Pricing.orBilledMs). «Separar voces de
     * nuevo» y «Solo el texto» son lo mismo que una transcripción nueva con lo elegido en Ajustes: van tal cual por la
     * versión con Context, como «¿Separar voces?». «Sin cortar» (un solo envío) y la segunda pasada (más muestras: las
     * personas de la versión actual) cambian la cuenta, así que pasan por RecordingActions.billedMs, que usa la misma regla.
     */
    private static long billed(Context c,Recording r,Retranscribe.Mode mode,ProviderConfig config){
        if(mode==Retranscribe.Mode.SPEAKERS||mode==Retranscribe.Mode.TEXT||!config.speakers)return Pricing.orBilledMs(c,r.duration,config.speakers);
        return RecordingActions.billedMs(config.provider,config.model,r.duration,anchors(c,r,mode,config),mode==Retranscribe.Mode.SINGLE);
    }
    /**
     * Muestras de voz que irían en cada envío de esta alternativa: las voces conocidas (hasta Transcriber.MAX_KNOWN) y, en
     * la segunda pasada, además una por cada persona de la versión actual, sin pasar del mismo tope. Es una cuenta para
     * el estimado («≈»): el motor decide al enviar cuáles van de verdad.
     */
    private static int anchors(Context c,Recording r,Retranscribe.Mode mode,ProviderConfig config){
        if(!config.speakers||!TranscribeClient.knowsVoices(config.provider))return 0;
        int n=Voices.selected(c).size();
        if(mode==Retranscribe.Mode.CORRECTIONS)try{n+=Transcript.load(c,r.id).speakers().size();}catch(Exception ignored){}
        return Math.min(Transcriber.MAX_KNOWN,n);
    }
    private static boolean available(Context c,Recording r,Retranscribe.Mode mode){try{return Retranscribe.available(c,r,mode);}catch(Exception e){return false;}}
    private static String reason(Context c,Recording r,Retranscribe.Mode mode){
        try{String why=Retranscribe.reason(c,r,mode);return why==null||why.trim().isEmpty()?Lang.str(c,R.string.retr_unavailable):why.trim();}catch(Exception e){return Lang.str(c,R.string.retr_unavailable);}
    }

    static void show(Screen s,Recording r,Runnable changed){
        if(r==null)return;
        if(!new Settings(s).hasKey()){RecordingActions.missingKey(s);return;}
        if(RecState.of(s,r.id).kind==RecState.Kind.QUEUED){s.message(s.getString(R.string.retr_again),s.getString(R.string.retr_busy_body));return;}
        if(!Transcript.exists(s,r.id)){RecordingActions.transcribe(s,r,changed);return;}
        // Con una versión anterior sin elegir, otra transcripción la reemplazaría sin preguntar (solo se guarda una):
        // primero se elige con cuál quedarse y después se ofrecen las alternativas.
        if(Retranscribe.hasPrevious(s,r.id)){offerKeep(s,r,changed,true,kept->show(s,r,changed));return;}
        // OpenAI y OpenRouter cobran cada envío; un servidor propio puede no cobrar (ahí se dice «se envía»).
        Settings settings=new Settings(s);boolean paid=RecordingActions.paid(settings);
        Sheet sheet=s.sheet(s.getString(R.string.retr_sheet_title),s.getString(R.string.retr_audio_of,Ui.humanDuration(r.duration))+" "+s.getString(paid?R.string.retr_charged_again:R.string.retr_sent_again)+" "+s.getString(R.string.retr_kept));
        // Las 4 alternativas con su círculo menta (0.7.0): la recomendada lleva «✦ Recomendada» y el costo va en su
        // píldora con cifras fijas, así se comparan de un vistazo. La que no se puede usar se ve atenuada con su motivo.
        LinearLayout list=SheetParts.list(sheet);
        Retranscribe.Mode[] modes={Retranscribe.Mode.CORRECTIONS,Retranscribe.Mode.SINGLE,Retranscribe.Mode.SPEAKERS,Retranscribe.Mode.TEXT};
        StringBuilder log=new StringBuilder();
        for(Retranscribe.Mode mode:modes){
            boolean ok=available(s,r,mode);log.append(mode.name()).append(ok?"+":"-");
            boolean recommended=ok&&mode==Retranscribe.Mode.CORRECTIONS;String label=title(s,mode),cost=cost(s,r,mode);
            List<SheetParts.Fact> facts=new ArrayList<>();if(recommended)facts.add(SheetParts.recommended());if(!cost.isEmpty())facts.add(SheetParts.cost(cost));
            String detail=ok?explain(s,mode):reason(s,r,mode);
            String spoken=ok?(recommended?s.getString(R.string.retr_spoken_recommended,label):label)+". "+detail+(cost.isEmpty()?"":" · "+cost):s.getString(R.string.retr_spoken_unavailable,label,detail);
            list.addView(SheetParts.option(s,icon(mode),label,detail,facts,ok,spoken,()->{sheet.dismiss();confirm(s,r,mode,changed);}),Ui.fill());
        }
        // Sin «Mi voz», la separación se equivoca más al inicio: conviene grabarla antes de repetir (una sola vez).
        if(TranscribeClient.knowsVoices(settings.provider())&&settings.canSeparate()&&!Voices.has(s))list.addView(SheetParts.item(s,sheet,R.drawable.ic_mic_fill,s.getString(R.string.retr_record_voice_first),false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true))));
        sheet.secondary(s.getString(R.string.common_cancel),null).show();
        Diagnostics.event("retranscribe_sheet",r.id,"modes",log.toString());
    }

    /** Confirma con el costo y lo que pasa con tu versión actual y tus correcciones. Lleva el ícono de la alternativa. */
    private static void confirm(Screen s,Recording r,Retranscribe.Mode mode,Runnable changed){
        String cost=cost(s,r,mode);boolean corrected=false;
        try{Transcript t=Transcript.load(s,r.id);corrected=t.edited()||t.reviewed();}catch(Exception ignored){}
        StringBuilder m=new StringBuilder(explain(s,mode)).append("\n\n");
        // Sin tarifa conocida igual se avisa que se cobra, si el proveedor cobra (OpenRouter con un modelo sin precio por minuto).
        m.append(!cost.isEmpty()?s.getString(R.string.retr_charged_again_cost,cost):s.getString(RecordingActions.paid(new Settings(s))?R.string.retr_charged_again:R.string.retr_sent_again));
        m.append(' ').append(s.getString(R.string.retr_kept));
        if(corrected&&mode!=Retranscribe.Mode.CORRECTIONS)m.append(' ').append(s.getString(R.string.retr_corrections_lost));
        Sheet sheet=s.sheet(title(s,mode),m.toString());SheetParts.hero(sheet,icon(mode),false);
        // 0.9.0: si aún no se aceptó el aviso de envío (Consent), se muestra antes de volver a enviar el audio.
        sheet.primary(s.getString(R.string.retr_again),()->Consent.ensure(s,()->start(s,r,mode,changed)))
            .secondary(s.getString(R.string.common_cancel),null).show();
    }
    private static void start(Screen s,Recording r,Retranscribe.Mode mode,Runnable changed){
        RecordingActions.askNotifications(s);Context app=s.getApplicationContext();
        new Thread(()->{
            Exception error=null;
            try{Retranscribe.start(app,r,mode);Diagnostics.event("retranscribe_started",r.id,"mode",mode.name());Pipeline.startForeground(app);}
            catch(Exception e){error=e;Diagnostics.event("retranscribe_failed",r.id,"mode",mode.name(),"error_class",e.getClass().getSimpleName());}
            Exception failed=error;
            s.runOnUiThread(()->{
                if(s.isFinishing()||s.isDestroyed())return;
                if(failed==null){
                    // Lo que espera ESTA grabación (Pipeline.blocker con su id), no el de todas: «Volver a transcribir» le
                    // quita el permiso de datos móviles, aunque otra pedida lo tenga.
                    Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast(RecordingActions.queuedToast(s,r,s.getString(R.string.retr_doing)));
                    if(changed!=null)changed.run();return;
                }
                Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.REJECT);
                String why=failed instanceof HttpApi.UserAction?failed.getMessage()
                    :failed instanceof UnsupportedOperationException?s.getString(R.string.retr_option_unavailable)
                    :s.getString(R.string.detail_try_again_soon);
                s.message(s.getString(R.string.retr_failed_title),s.getString(R.string.retr_failed_body,why));
                if(changed!=null)changed.run();
            });
        }).start();
    }

    /** «Nueva versión lista»: quedarse con la nueva o volver a la anterior, con una comparación simple de las dos. */
    static Sheet offerKeep(Screen s,Recording r,Runnable changed){return offerKeep(s,r,changed,false,null);}
    /**
     * beforeAgain: se abre porque pediste volver a transcribir con una versión aún sin elegir (se pide elegir antes).
     * then: lo que sigue después de elegir (true = te quedaste con la nueva). No corre si cierras la hoja o la elección
     * falla. Devuelve la hoja abierta, o null si no hay nada que elegir.
     */
    static Sheet offerKeep(Screen s,Recording r,Runnable changed,boolean beforeAgain,java.util.function.Consumer<Boolean> then){
        if(r==null||!Retranscribe.hasPrevious(s,r.id)||!Transcript.exists(s,r.id))return null;
        Version[] both=versions(s,r.id);
        String why=s.getString(beforeAgain?R.string.retr_keep_before_body:R.string.retr_keep_body);
        Sheet sheet=s.sheet(s.getString(beforeAgain?R.string.retr_keep_before_title:R.string.retr_keep_title),why);
        // La comparación va en dos tarjetas lado a lado (0.7.0): la nueva en menta, la anterior en gris suave.
        if(both!=null)sheet.add(comparison(s,both[0],both[1]));
        sheet.primary(s.getString(R.string.retr_keep_new),()->{
            boolean chosen=false;
            try{Retranscribe.keepNew(s,r.id);chosen=!Retranscribe.hasPrevious(s,r.id);Diagnostics.event("retranscribe_kept",r.id,"choice","new");Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast(s.getString(R.string.retr_kept_new));}
            catch(Exception e){s.message(s.getString(R.string.retr_new_version),s.getString(R.string.retr_keep_failed));}
            if(changed!=null)changed.run();
            if(chosen&&then!=null)then.accept(true);
        });
        sheet.secondary(s.getString(R.string.retr_back_previous),()->{RecordingActions.restorePrevious(s,r,changed);if(then!=null&&!Retranscribe.hasPrevious(s,r.id))then.accept(false);});
        if(beforeAgain)sheet.secondary(s.getString(R.string.common_cancel),null);
        sheet.show();
        Diagnostics.event("retranscribe_offer",r.id,"source",beforeAgain?"retranscribe":"ready");
        return sheet;
    }
    /** Dos tarjetas iguales, lado a lado: «✦ Nueva» (menta) y «Anterior» (gris suave), con lo que tiene cada una. */
    private static View comparison(Screen s,Version now,Version before){
        LinearLayout row=s.ui.row();row.setGravity(Gravity.TOP);
        row.addView(versionCard(s,true,now),new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMarginStart(s.ui.dp(S2));row.addView(versionCard(s,false,before),lp);
        return row;
    }
    private static View versionCard(Screen s,boolean isNew,Version v){
        Ui ui=s.ui;Palette p=s.p;int accent=isNew?p.primary:p.onSurfaceVariant;
        LinearLayout c=ui.column();c.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S4));
        c.setBackground(isNew?shape(s,p.highlight,R_CARD-4):SheetParts.card(s,p,R_CARD-4));
        LinearLayout head=ui.row();head.addView(ui.icon(isNew?R.drawable.ic_sparkle:R.drawable.ic_history,accent,16));head.addView(ui.space(6));
        head.addView(ui.text(s.getString(isNew?R.string.retr_new:R.string.retr_previous),Type.LABEL_LARGE,accent));c.addView(head);
        // Nada se corta (en 0.6.0 la comparación iba en el mensaje de la hoja, completa): con nombres largos o letra
        // grande, el dato y los nombres bajan de línea y la tarjeta crece. Las dos siguen del mismo alto (comparison) y la
        // hoja se desplaza si hace falta.
        TextView big=Ui.tabular(ui.text(v.headline,Type.TITLE_LARGE,p.onSurface));big.setPadding(0,ui.dp(S2),0,0);c.addView(big);
        if(!v.detail.isEmpty()){TextView d=ui.text(v.detail,Type.BODY_SMALL,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);c.addView(d);}
        // Para el lector de pantalla, cada tarjeta se lee de una vez: «Nueva: 3 voces (Konrad, Fran, Persona 3)».
        c.setContentDescription(s.getString(isNew?R.string.retr_new_desc:R.string.retr_previous_desc,v.summary));c.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);c.setFocusable(true);
        for(int i=0;i<c.getChildCount();i++)c.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return c;
    }

    /** Lo que tiene una versión: el dato grande («3 voces», «Solo texto»), el detalle y el resumen de una línea. */
    private static final class Version {
        final String headline,detail,summary;
        Version(String headline,String detail,String summary){this.headline=headline;this.detail=detail;this.summary=summary;}
    }
    private static Version version(Transcript t)throws Exception{
        if(!t.diarized()){int words=0;org.json.JSONArray s=t.segments();for(int i=0;i<s.length();i++){String x=s.getJSONObject(i).optString("text").trim();if(!x.isEmpty())words+=x.split("\\s+").length;}
            String w=Lang.plural(R.plurals.retr_words,words);return new Version(Lang.str(R.string.retr_text_only),w,Lang.str(R.string.retr_text_only_summary,w));}
        List<String> names=new ArrayList<>(t.speakers().values());int n=names.size();
        String list=n<=3?String.join(", ",names):Lang.str(R.string.retr_names_more,String.join(", ",names.subList(0,3)),n-3);
        String count=Lang.plural(R.plurals.retr_voices,n);
        return new Version(count,n==0?"":list,count+(n==0?"":" ("+list+")"));
    }
    /** La versión en uso y la anterior, o null si alguna no se puede leer (entonces no se muestra la comparación). */
    private static Version[] versions(Context c,String id){
        try{
            Transcript now=Transcript.load(c,id);
            JSONObject prevData;synchronized(FilesStore.LOCK){prevData=FilesStore.read(FilesStore.file(c,id,".transcript.prev.json"));}
            Transcript before=new Transcript(prevData);before.clean();
            return new Version[]{version(now),version(before)};
        }catch(Exception e){return null;}
    }
    /** «Nueva: 3 voces (Konrad, Fran, Persona 3)\nAnterior: 2 voces (…)», si ambas versiones se pueden leer. */
    static String compare(Context c,String id){Version[] v=versions(c,id);return v==null?"":Lang.str(c,R.string.retr_compare,v[0].summary,v[1].summary);}
    static String summary(Transcript t)throws Exception{return version(t).summary;}
}
