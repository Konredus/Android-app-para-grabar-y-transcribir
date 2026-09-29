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
 */
final class RetranscribeSheet {
    private RetranscribeSheet(){}

    /** Texto de cada alternativa: título y para qué sirve (sin jerga). */
    static String title(Context c,Retranscribe.Mode mode){
        switch(mode){
            case CORRECTIONS:return "Segunda pasada con tus correcciones";
            case SINGLE:return "Separar voces sin cortar el audio";
            case SPEAKERS:return Voices.has(c)?"Separar voces de nuevo, con Mi voz":"Separar voces de nuevo";
            default:return "Solo el texto";
        }
    }
    static String explain(Context c,Retranscribe.Mode mode){
        switch(mode){
            case CORRECTIONS:return "Usa las voces que ya corregiste o nombraste como muestra en todo el audio, desde el inicio. Es la que más mejora quién habla.";
            case SINGLE:return "Todo el audio de una vez, sin uniones donde las voces se crucen. Más parejo, pero más lento. Hasta 23 min.";
            case SPEAKERS:return Voices.has(c)?"Te reconoce como "+Voices.name(c)+" desde el inicio; las demás, Persona 2…":"Otra pasada separando voces: Persona 1, Persona 2…";
            default:return "Para cuando fallaron las palabras, no las voces. Más rápido, pero sin separar voces.";
        }
    }
    static int icon(Retranscribe.Mode mode){
        switch(mode){case CORRECTIONS:return R.drawable.ic_sparkle;case SINGLE:return R.drawable.ic_wave;case SPEAKERS:return R.drawable.ic_people;default:return R.drawable.ic_doc;}
    }
    /** «≈ US$0,048», o "" si no se conoce la tarifa (p. ej. servidor propio). */
    static String cost(Context c,Recording r,Retranscribe.Mode mode){
        try{Settings s=new Settings(c);double v=Pricing.estimate(s.provider(),s.config(mode!=Retranscribe.Mode.TEXT).model,r.duration);return v<0?"":"≈ "+Pricing.usd(v);}
        catch(Exception e){return "";}
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
        Ui ui=s.ui;Palette p=s.p;
        boolean paid=new Settings(s).provider().equals("openai");
        Sheet sheet=s.sheet("¿Cómo quieres volver a transcribir?","Audio de "+Ui.humanDuration(r.duration)+". "+(paid?"Se cobra de nuevo el audio completo.":"Se envía de nuevo el audio completo.")+" Tu versión actual se guarda por si prefieres volver.");
        LinearLayout list=ui.column();LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(-ui.dp(S6),0,-ui.dp(S6),0);sheet.body.addView(list,lp);
        Retranscribe.Mode[] modes={Retranscribe.Mode.CORRECTIONS,Retranscribe.Mode.SINGLE,Retranscribe.Mode.SPEAKERS,Retranscribe.Mode.TEXT};
        StringBuilder log=new StringBuilder();
        for(Retranscribe.Mode mode:modes){
            boolean ok=available(s,r,mode);log.append(mode.name()).append(ok?"+":"-");
            String cost=cost(s,r,mode);
            String detail=ok?explain(s,mode)+(cost.isEmpty()?"":" · "+cost):reason(s,r,mode);
            list.addView(option(s,icon(mode),title(s,mode),detail,ok,ok&&mode==Retranscribe.Mode.CORRECTIONS,()->{sheet.dismiss();confirm(s,r,mode,changed);}),Ui.fill());
        }
        // Sin «Mi voz», la separación se equivoca más al inicio: conviene grabarla antes de repetir (una sola vez).
        if(paid&&!Voices.has(s))sheet.action(R.drawable.ic_mic_fill,"Grabar mi voz antes de repetir",false,()->s.startActivity(new Intent(s,SettingsActivity.class).putExtra("voice",true)));
        sheet.secondary("Cancelar",null).show();
        Diagnostics.event("retranscribe_sheet",r.id,"modes",log.toString());
    }

    /**
     * Opción grande de dos líneas, como {@link Sheet#option}, pero que puede mostrarse desactivada con su motivo
     * (no se esconde: así se entiende por qué no se puede) y destacar la recomendada.
     */
    private static View option(Screen s,int icon,String label,String detail,boolean enabled,boolean recommended,Runnable run){
        Ui ui=s.ui;Palette p=s.p;
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setMinimumHeight(ui.dp(72));row.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),ui.dp(S3));
        FrameLayout tile=recommended?ui.tile(icon,p.onPrimaryContainer,p.primaryContainer,40,22):ui.tile(icon,p.onSecondaryContainer,p.secondaryContainer,40,22);
        row.addView(tile);row.addView(ui.space(S4));
        LinearLayout texts=ui.column();
        LinearLayout head=ui.row();TextView t=ui.text(label,Type.TITLE_MEDIUM,p.onSurface);head.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(recommended){TextView badge=ui.text("Recomendada",Type.LABEL_MEDIUM,p.onPrimaryContainer);badge.setBackground(shape(s,p.primaryContainer,R_SMALL));badge.setPadding(ui.dp(S2),ui.dp(2),ui.dp(S2),ui.dp(2));LinearLayout.LayoutParams blp=Ui.wrap();blp.setMarginStart(ui.dp(S2));head.addView(badge,blp);}
        texts.addView(head,Ui.fill());
        TextView d=ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);texts.addView(d);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        if(enabled){
            row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(label+(recommended?", recomendada":"")+". "+detail);row.setAccessibilityDelegate(Ui.buttonRole());
            row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen",s.getClass().getSimpleName(),"action",label);run.run();});
        }else{
            // Desactivada: el título y el ícono se atenúan; el motivo se lee completo.
            tile.setAlpha(0.38f);t.setAlpha(0.38f);row.setContentDescription(label+". No disponible: "+detail);
        }
        return row;
    }

    /** Confirma con el costo y lo que pasa con tu versión actual y tus correcciones. */
    private static void confirm(Screen s,Recording r,Retranscribe.Mode mode,Runnable changed){
        String cost=cost(s,r,mode);boolean corrected=false;
        try{Transcript t=Transcript.load(s,r.id);corrected=t.edited()||t.reviewed();}catch(Exception ignored){}
        StringBuilder m=new StringBuilder(explain(s,mode)).append("\n\n");
        m.append(cost.isEmpty()?"Se envía de nuevo el audio completo.":"Se cobra de nuevo el audio completo ("+cost+").");
        m.append(" Tu versión actual se guarda por si prefieres volver.");
        if(corrected&&mode!=Retranscribe.Mode.CORRECTIONS)m.append(" Tus correcciones de voces y nombres no pasan a la nueva versión.");
        s.sheet(title(s,mode),m.toString())
            .primary("Volver a transcribir",()->start(s,r,mode,changed))
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
    static void offerKeep(Screen s,Recording r,Runnable changed){
        if(r==null||!Retranscribe.hasPrevious(s,r.id)||!Transcript.exists(s,r.id))return;
        String compare=compare(s,r.id);
        Sheet sheet=s.sheet("Nueva versión lista",(compare.isEmpty()?"":compare+"\n\n")+"Revisa la nueva y elige con cuál te quedas. Mientras no elijas, la anterior sigue guardada.");
        sheet.primary("Quedarme con la nueva",()->{
            try{Retranscribe.keepNew(s,r.id);Diagnostics.event("retranscribe_kept",r.id,"choice","new");Ui.haptic(s.getWindow().getDecorView(),Ui.Haptic.CONFIRM);s.toast("Te quedaste con la nueva versión");}
            catch(Exception e){s.message("Nueva versión","No se pudo borrar la versión anterior. La nueva ya está en uso.");}
            if(changed!=null)changed.run();
        });
        sheet.secondary("Volver a la anterior",()->RecordingActions.restorePrevious(s,r,changed));
        sheet.show();
        Diagnostics.event("retranscribe_offer",r.id);
    }
    /** «Ahora: 3 voces (Konrad, Fran, Persona 3) · Antes: 2 voces (…)», si ambas versiones se pueden leer. */
    static String compare(Context c,String id){
        try{
            Transcript now=Transcript.load(c,id);
            JSONObject prevData;synchronized(FilesStore.LOCK){prevData=FilesStore.read(FilesStore.file(c,id,".transcript.prev.json"));}
            Transcript before=new Transcript(prevData);before.clean();
            return "Nueva: "+summary(now)+"\nAnterior: "+summary(before);
        }catch(Exception e){return "";}
    }
    static String summary(Transcript t)throws Exception{
        if(!t.diarized()){int words=0;org.json.JSONArray s=t.segments();for(int i=0;i<s.length();i++){String x=s.getJSONObject(i).optString("text").trim();if(!x.isEmpty())words+=x.split("\\s+").length;}
            return "solo texto · "+String.format(Locale.ROOT,"%,d",words).replace(',','.')+" palabras";}
        List<String> names=new ArrayList<>(t.speakers().values());int n=names.size();
        String list=n<=3?String.join(", ",names):String.join(", ",names.subList(0,3))+" y "+(n-3)+" más";
        return n+(n==1?" voz":" voces")+(n==0?"":" ("+list+")");
    }
}
