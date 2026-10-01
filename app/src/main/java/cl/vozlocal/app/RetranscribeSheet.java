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
    /** Texto de cada alternativa: título y para qué sirve (sin jerga). */
    static String title(Context c,Retranscribe.Mode mode){
        switch(mode){
            case CORRECTIONS:return "Segunda pasada con tus correcciones";
            case SINGLE:return "Separar voces sin cortar el audio";
            case SPEAKERS:return myVoice(c)?"Separar voces de nuevo, con Mi voz":"Separar voces de nuevo";
            default:return "Solo el texto";
        }
    }
    static String explain(Context c,Retranscribe.Mode mode){
        boolean router=new Settings(c).openRouter();
        switch(mode){
            // Con OpenRouter las muestras van delante del audio («anclas»): es nuevo, así que se pide revisar el resultado.
            case CORRECTIONS:return "Usa las voces que ya corregiste o nombraste como muestra en todo el audio, desde el inicio. Es la que más mejora quién habla."+(router?" Con OpenRouter es una función nueva: revisa los nombres al terminar.":"");
            // El tope lo da el motor (Retranscribe.singleMaxMs): 23 min con OpenAI; con OpenRouter, lo que acepta el modelo
            // elegido (20 min como máximo). Es el mismo número que usa el motivo cuando el audio no cabe.
            case SINGLE:return "Todo el audio de una vez, sin uniones donde las voces se crucen. Más parejo, pero más lento. Hasta "+(Retranscribe.singleMaxMs(c)/60_000)+" min.";
            // Con OpenRouter el reconocimiento usa «anclas», una técnica nueva: se promete el intento, no el resultado.
            case SPEAKERS:return myVoice(c)?(router?"Busca tu voz para ponerte como "+Voices.name(c):"Te reconoce como "+Voices.name(c)+" desde el inicio")+"; las demás, Persona 2…":"Otra pasada separando voces: Persona 1, Persona 2…";
            default:return "Para cuando fallaron las palabras, no las voces. Más rápido, pero sin separar voces.";
        }
    }
    static int icon(Retranscribe.Mode mode){
        switch(mode){case CORRECTIONS:return R.drawable.ic_sparkle;case SINGLE:return R.drawable.ic_wave;case SPEAKERS:return R.drawable.ic_people;default:return R.drawable.ic_doc;}
    }
    /** «≈ US$0,048», o "" si no se conoce la tarifa (p. ej. servidor propio, o un modelo de OpenRouter sin precio por minuto). */
    static String cost(Context c,Recording r,Retranscribe.Mode mode){
        // Lo mismo que Retranscribe.cost (proveedor y modelo de esta alternativa, precio con el Context), más lo que OpenRouter
        // cobra por las muestras de voz que van delante de cada bloque (RecordingActions.billedMs; hallazgo de la revisión).
        try{
            Settings s=new Settings(c);ProviderConfig config=s.config(Retranscribe.speakers(mode));
            double v=Pricing.estimate(c,config.provider,config.model,RecordingActions.billedMs(config.provider,config.model,r.duration,anchors(c,r,mode,config),mode==Retranscribe.Mode.SINGLE));
            return v<0?"":"≈ "+Pricing.usd(v);
        }catch(Exception e){return "";}
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
        try{String why=Retranscribe.reason(c,r,mode);return why==null||why.trim().isEmpty()?"No disponible para este audio":why.trim();}catch(Exception e){return "No disponible para este audio";}
    }

    static void show(Screen s,Recording r,Runnable changed){
        if(r==null)return;
        if(!new Settings(s).hasKey()){RecordingActions.missingKey(s);return;}
        if(RecState.of(s,r.id).kind==RecState.Kind.QUEUED){s.message("Volver a transcribir","Esta grabación ya se está transcribiendo. Cuando termine, podrás elegir otra alternativa.");return;}
        if(!Transcript.exists(s,r.id)){RecordingActions.transcribe(s,r,changed);return;}
        // Con una versión anterior sin elegir, otra transcripción la reemplazaría sin preguntar (solo se guarda una):
        // primero se elige con cuál quedarse y después se ofrecen las alternativas.
        if(Retranscribe.hasPrevious(s,r.id)){offerKeep(s,r,changed,true,kept->show(s,r,changed));return;}
        // OpenAI y OpenRouter cobran cada envío; un servidor propio puede no cobrar (ahí se dice «se envía»).
        Settings settings=new Settings(s);boolean paid=RecordingActions.paid(settings);
        Sheet sheet=s.sheet("¿Cómo quieres volver a transcribir?","Audio de "+Ui.humanDuration(r.duration)+". "+(paid?"Se cobra de nuevo el audio completo.":"Se envía de nuevo el audio completo.")+" Tu versión actual se guarda por si prefieres volver.");
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
            String spoken=ok?label+(recommended?", recomendada":"")+". "+detail+(cost.isEmpty()?"":" · "+cost):label+". No disponible: "+detail;
            list.addView(SheetParts.option(s,icon(mode),label,detail,facts,ok,spoken,()->{sheet.dismiss();confirm(s,r,mode,changed);}),Ui.fill());
        }
        // Sin «Mi voz», la separación se equivoca más al inicio: conviene grabarla antes de repetir (una sola vez).
        if(TranscribeClient.knowsVoices(settings.provider())&&settings.canSeparate()&&!Voices.has(s))list.addView(SheetParts.item(s,sheet,R.drawable.ic_mic_fill,"Grabar mi voz antes de repetir",false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true))));
        sheet.secondary("Cancelar",null).show();
        Diagnostics.event("retranscribe_sheet",r.id,"modes",log.toString());
    }

    /** Confirma con el costo y lo que pasa con tu versión actual y tus correcciones. Lleva el ícono de la alternativa. */
    private static void confirm(Screen s,Recording r,Retranscribe.Mode mode,Runnable changed){
        String cost=cost(s,r,mode);boolean corrected=false;
        try{Transcript t=Transcript.load(s,r.id);corrected=t.edited()||t.reviewed();}catch(Exception ignored){}
        StringBuilder m=new StringBuilder(explain(s,mode)).append("\n\n");
        // Sin tarifa conocida igual se avisa que se cobra, si el proveedor cobra (OpenRouter con un modelo sin precio por minuto).
        m.append(!cost.isEmpty()?"Se cobra de nuevo el audio completo ("+cost+").":RecordingActions.paid(new Settings(s))?"Se cobra de nuevo el audio completo.":"Se envía de nuevo el audio completo.");
        m.append(" Tu versión actual se guarda por si prefieres volver.");
        if(corrected&&mode!=Retranscribe.Mode.CORRECTIONS)m.append(" Tus correcciones de voces y nombres no pasan a la nueva versión.");
        Sheet sheet=s.sheet(title(s,mode),m.toString());SheetParts.hero(sheet,icon(mode),false);
        sheet.primary("Volver a transcribir",()->start(s,r,mode,changed))
            .secondary("Cancelar",null).show();
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
                    Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);String blocker=Pipeline.blocker(s);
                    s.toast(blocker==null?"Volviendo a transcribir · sigue aunque bloquees el teléfono":"En cola · "+blocker);
                    if(changed!=null)changed.run();return;
                }
                Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.REJECT);
                String why=failed instanceof HttpApi.UserAction?failed.getMessage()
                    :failed instanceof UnsupportedOperationException?"Esta alternativa aún no está disponible en esta versión."
                    :"Vuelve a intentarlo en un momento.";
                s.message("No se pudo volver a transcribir",why+" Tu versión actual sigue intacta.");
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
        String why=beforeAgain?"Antes de volver a transcribir, elige con cuál te quedas: solo se puede guardar una versión anterior a la vez."
            :"Revisa la nueva y elige con cuál te quedas. Mientras no elijas, la anterior sigue guardada.";
        Sheet sheet=s.sheet(beforeAgain?"Antes, elige una versión":"Nueva versión lista",why);
        // La comparación va en dos tarjetas lado a lado (0.7.0): la nueva en menta, la anterior en gris suave.
        if(both!=null)sheet.add(comparison(s,both[0],both[1]));
        sheet.primary("Quedarme con la nueva",()->{
            boolean chosen=false;
            try{Retranscribe.keepNew(s,r.id);chosen=!Retranscribe.hasPrevious(s,r.id);Diagnostics.event("retranscribe_kept",r.id,"choice","new");Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast("Te quedaste con la nueva versión");}
            catch(Exception e){s.message("Nueva versión","No se pudo borrar la versión anterior. La nueva ya está en uso.");}
            if(changed!=null)changed.run();
            if(chosen&&then!=null)then.accept(true);
        });
        sheet.secondary("Volver a la anterior",()->{RecordingActions.restorePrevious(s,r,changed);if(then!=null&&!Retranscribe.hasPrevious(s,r.id))then.accept(false);});
        if(beforeAgain)sheet.secondary("Cancelar",null);
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
        head.addView(ui.text(isNew?"Nueva":"Anterior",Type.LABEL_LARGE,accent));c.addView(head);
        // Nada se corta (en 0.6.0 la comparación iba en el mensaje de la hoja, completa): con nombres largos o letra
        // grande, el dato y los nombres bajan de línea y la tarjeta crece. Las dos siguen del mismo alto (comparison) y la
        // hoja se desplaza si hace falta.
        TextView big=Ui.tabular(ui.text(v.headline,Type.TITLE_LARGE,p.onSurface));big.setPadding(0,ui.dp(S2),0,0);c.addView(big);
        if(!v.detail.isEmpty()){TextView d=ui.text(v.detail,Type.BODY_SMALL,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);c.addView(d);}
        // Para el lector de pantalla, cada tarjeta se lee de una vez: «Nueva: 3 voces (Konrad, Fran, Persona 3)».
        c.setContentDescription((isNew?"Nueva: ":"Anterior: ")+v.summary);c.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);c.setFocusable(true);
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
            String w=String.format(Locale.ROOT,"%,d",words).replace(',','.')+" palabras";return new Version("Solo texto",w,"solo texto · "+w);}
        List<String> names=new ArrayList<>(t.speakers().values());int n=names.size();
        String list=n<=3?String.join(", ",names):String.join(", ",names.subList(0,3))+" y "+(n-3)+" más";
        String count=n+(n==1?" voz":" voces");
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
    static String compare(Context c,String id){Version[] v=versions(c,id);return v==null?"":"Nueva: "+v[0].summary+"\nAnterior: "+v[1].summary;}
    static String summary(Transcript t)throws Exception{return version(t).summary;}
}
