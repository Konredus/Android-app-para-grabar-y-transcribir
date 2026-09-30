package cl.vozlocal.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Objects;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Bienvenida de la primera instalación (0.8.0, ver docs/diseno/SPEC-0.8.md → «onboarding»): cuatro pasos cortos a pantalla
 * completa sobre el fondo intenso de Grabar, con la estética Verbapp (docs/diseno/PROPUESTA-0.7.md):
 * 1. Bienvenida: el logo en grande, el lema y tres beneficios en tarjetas de vidrio.
 * 2. Tú: tu nombre (opcional, para el saludo y para tu voz) y el permiso del micrófono.
 * 3. Conecta tu IA: OpenRouter (recomendada) u OpenAI, y un campo para pegar la clave.
 * 4. Listo: cómo quedó la clave y dos accesos opcionales (grabar tu voz, elegir tu carpeta 0-Inbox).
 * Arriba van siempre ← (desde el segundo paso), los puntitos de avance y «Saltar»; abajo, fijo, UN botón de tinta que
 * no cambia de lugar. La ilustración de cada paso es el mismo gesto de Grabar: un disco verde con halo entre ondas grises.
 *
 * MainActivity la abre encima de Grabar mientras "welcomed" sea false; se marca al llegar al último paso, al terminar o
 * al saltar. Desde Ajustes se puede volver a ver (replay): ahí no se toca "welcomed" y solo se guarda lo que el usuario
 * escriba (un campo vacío nunca borra un nombre ni una clave que ya existan).
 * La clave nunca se muestra después de guardarla, no va a la bitácora ni a Diagnostics, y tampoco al estado que Android
 * guarda de la pantalla: mientras se escribe vive en memoria (sobrevive al giro, no a que Android cierre la app).
 */
public class OnboardingActivity extends Screen {
    // ================= Contrato de la fase 0 =================
    /** true si nunca se mostró la bienvenida (preferencia "welcomed", la misma de la hoja de bienvenida anterior). */
    static boolean shouldShow(Context c){return !new Settings(c).prefs.getBoolean("welcomed",false);}
    /** Abre la bienvenida. replay=true: el usuario pidió verla otra vez desde Ajustes (no cambia "welcomed" al salir). */
    static void open(Context c,boolean replay){c.startActivity(new Intent(c,OnboardingActivity.class).putExtra("replay",replay));}

    // ================= Lógica sin pantalla (la comprueba OnboardingChecks) =================
    static final String OPENROUTER="openrouter",OPENAI="openai";
    static final String KEYS_OPENROUTER="https://openrouter.ai/keys",KEYS_OPENAI="https://platform.openai.com/api-keys";
    /** Una clave real mide bastante más (OpenAI unas 50 letras, OpenRouter unas 70): menos que esto es un pegado a medias. */
    static final int MIN_KEY=20;
    /** Lo que se dice cuando no se pudo preguntar al proveedor (sin red, servicio caído): la clave igual quedó guardada. */
    static final String UNCHECKED="Clave guardada. No se pudo comprobar ahora: puedes hacerlo después en Ajustes.";

    /**
     * La bienvenida terminó (o se saltó): no vuelve a aparecer sola y «Novedades» no sale encima de quien recién instaló
     * (la versión instalada se da por vista; nunca se baja una más nueva). En un repaso desde Ajustes no cambia nada.
     */
    static void complete(Context c,boolean replay){
        if(replay)return;Settings s=new Settings(c);s.prefs.edit().putBoolean("welcomed",true).apply();
        int code=Novedades.versionCode(c);if(code>s.lastSeenVersion())s.setLastSeenVersion(code);
    }
    /** De quién parece ser una clave por su comienzo: "openrouter" (sk-or-), "anthropic" (sk-ant-), "openai" (sk-) o null si no se reconoce. */
    static String guessProvider(String raw){
        String k=raw==null?"":raw.trim();
        if(k.startsWith("sk-or-"))return OPENROUTER;if(k.startsWith("sk-ant-"))return "anthropic";if(k.startsWith("sk-"))return OPENAI;return null;
    }
    /**
     * Por qué esta clave no se puede guardar para ese proveedor, en palabras para la persona; null si se puede.
     * Solo se rechaza lo seguro: vacía, con espacios, cortada, o de otro servicio cuando su comienzo no deja dudas
     * (sk-or- es de OpenRouter, sk-ant- de Anthropic). Un comienzo desconocido se acepta: los formatos cambian y la
     * comprobación real la hace el proveedor. El mensaje nunca repite la clave.
     */
    static String keyProblem(String provider,String raw){
        String key=raw==null?"":raw.trim();
        if(key.isEmpty())return "Pega tu clave para continuar.";
        if(key.matches("(?s).*\\s.*")||key.length()>8192)return "La clave no debe tener espacios ni saltos de línea. Cópiala de nuevo, entera.";
        if(key.length()<MIN_KEY)return "Esa clave parece incompleta. Cópiala entera y vuelve a pegarla.";
        String guess=guessProvider(key);
        if("anthropic".equals(guess))return "Esa clave es de Anthropic (Claude). Aquí va una de OpenRouter o de OpenAI.";
        if(OPENROUTER.equals(guess)&&!OPENROUTER.equals(provider))return "Esa clave es de OpenRouter. Elige la tarjeta OpenRouter y vuelve a tocar Continuar.";
        return null;
    }
    /**
     * Guarda la clave del proveedor elegido y lo deja como servicio de transcripción. Devuelve false, sin tocar nada, si
     * no se escribió nada: así un repaso de la bienvenida no pisa las claves ni el proveedor que ya existen.
     * Con OpenRouter la nota también pasa a OpenRouter («una sola clave para todo»), salvo que ya se arme con Claude y su
     * propia clave. Con OpenAI, una nota que apuntaba a OpenRouter sin tener su clave vuelve a OpenAI para no quedar sin nota.
     */
    static boolean applyKey(Settings s,String provider,String raw)throws Exception{
        String key=raw==null?"":raw.trim();if(key.isEmpty())return false;
        if(!OPENROUTER.equals(provider)&&!OPENAI.equals(provider))throw new IllegalArgumentException("Proveedor desconocido");
        String problem=keyProblem(provider,key);if(problem!=null)throw new IllegalArgumentException(problem);
        s.saveKeyFor(provider,key);
        SharedPreferences.Editor e=s.prefs.edit().putString("provider",provider);
        if(OPENROUTER.equals(provider)){if(!OPENROUTER.equals(s.noteProvider())&&!("anthropic".equals(s.noteProvider())&&s.hasAnthropicKey()))e.putString("noteProvider",OPENROUTER).remove("noteModel");}
        else if(OPENROUTER.equals(s.noteProvider())&&!s.hasOpenRouterKey())e.putString("noteProvider",OPENAI).remove("noteModel");
        e.apply();return true;
    }
    static boolean hasKey(Settings s,String provider){return OPENROUTER.equals(provider)?s.hasOpenRouterKey():s.hasOpenAiKey();}
    static String providerName(String provider){return OPENROUTER.equals(provider)?"OpenRouter":OPENAI.equals(provider)?"OpenAI":"Tu servidor";}
    /**
     * «Clave válida · quedan US$4,20» (el saldo solo si OpenRouter lo informa). La etiqueta de la clave no se muestra.
     * El monto y el piso de «sin saldo» son los de Ajustes → «Comprobar conexión» (SettingsActivity.money y NO_BALANCE):
     * la misma clave dice lo mismo en los dos lugares.
     */
    static String validText(Models.KeyInfo info){
        if(info==null||Double.isNaN(info.remaining)||Double.isInfinite(info.remaining))return "Clave válida";
        if(info.remaining<=SettingsActivity.NO_BALANCE)return "Clave válida, pero sin saldo: carga créditos en openrouter.ai.";
        return "Clave válida · quedan "+SettingsActivity.money(info.remaining);
    }
    /** El motivo del rechazo tal como lo dice el cliente, sin mandar a Ajustes: aquí la clave se corrige en el paso anterior. */
    static String rejected(String message){String m=message==null?"":message.replace(" Revísala en Ajustes.","").trim();return m.isEmpty()?"La clave no funcionó.":m;}
    /** Primer nombre para saludar («Konrad Peschka» → «Konrad»); vacío si no hay nombre o es el «Yo» por defecto. */
    static String firstName(String raw){String n=Voices.clean(raw);if(n.isEmpty()||n.equals("Yo"))return "";int cut=n.indexOf(' ');return cut>0?n.substring(0,cut):n;}
    static String readyTitle(String name){String first=firstName(name);return first.isEmpty()?"¡Todo listo!":"Todo listo, "+first;}

    /** Resultado de comprobar una clave. */
    static final class Verdict{final int state;final String message;Verdict(int state,String message){this.state=state;this.message=message;}}
    /** Pregunta al proveedor por la clave: devuelve el texto de «válida» o lanza (HttpApi.UserAction si el proveedor la rechazó). */
    interface Checker{String check(HttpApi http,String key)throws Exception;}
    static Checker checker(String provider){
        if(OPENROUTER.equals(provider))return (http,key)->validText(Models.checkKey(http,key));
        return (http,key)->{new OpenAiClient(http).verify(key);return "Clave válida";};
    }
    /**
     * Comprueba sin bloquear nunca: válida, rechazada con el mensaje del proveedor (HttpApi.UserAction) o, ante cualquier
     * otra cosa (sin red, servicio caído, comprobación aún no disponible), «no se pudo comprobar ahora». No lanza.
     */
    static Verdict verify(Checker checker,HttpApi http,String key){
        try{return new Verdict(Check.VALID,checker.check(http,key));}
        catch(HttpApi.UserAction e){return new Verdict(Check.REJECTED,rejected(e.getMessage()));}
        catch(Exception e){return new Verdict(Check.UNKNOWN,UNCHECKED);}
    }
    /** Comprobación de la clave recién guardada. Vive fuera de la pantalla para seguir tras un giro; no guarda la clave. */
    static final class Check{
        static final int CHECKING=0,VALID=1,REJECTED=2,UNKNOWN=3;
        final String provider;final HttpApi http=new HttpApi();int state=CHECKING;String message="";OnboardingActivity screen;
        Check(String provider){this.provider=provider;}
    }

    // ================= Pantalla =================
    private static final int WELCOME=0,YOU=1,AI=2,READY=3,STEPS=4,REQ_MIC=31;
    /** Caja de la ilustración (dp): la misma del micrófono de Grabar, con su halo. */
    private static final int HERO=132;
    private static final Object HERO_TAG=new Object();
    /** Tono de la línea bajo el campo de la clave. */
    private static final int INFO=0,OK=1,BAD=2;
    /** Estado de una fila del último paso: por hacer (flecha), hecho (✓), en curso (anillo), con problema, o sin marca. */
    private static final int ROW_GO=0,ROW_DONE=1,ROW_BUSY=2,ROW_BAD=3,ROW_PLAIN=4;
    /**
     * Lo que sigue tras un giro sin pasar por el estado guardado de la pantalla (que Android conserva fuera de la app):
     * la clave a medio escribir y la comprobación en curso.
     */
    private static final class Kept{String key="";Check check;}

    /** Para los retrasos de esta pantalla (se limpian al destruirla). */
    private final Handler main=new Handler(Looper.getMainLooper());
    /** Para entregar el resultado de la comprobación: no es de ninguna pantalla, así un giro justo en ese momento no lo pierde. */
    private static final Handler RESULTS=new Handler(Looper.getMainLooper());
    private Settings settings;private Kept kept;private boolean replay,switching,askedMic,imeShown,secured;
    private int step,keyTone;private long switchedAt;private String provider=OPENROUTER,name="",guessed;
    // Marco fijo: barra de arriba y zona de abajo
    private ImageButton back;private Steps dots;private Ui.Btn skip,next;private TextView later;private LinearLayout stage;
    // Vistas del paso que está a la vista (null en los demás)
    private TextView heading,micDetail,keyLabel,keyStatus;private EditText nameInput,keyInput;private Emblem micArt;private Ui.Btn allow;
    private LinearLayout nameCard,keyCard,readyList;private final LinearLayout[] cards=new LinearLayout[2];private final ImageView[] radios=new ImageView[2];

    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);replay=getIntent().getBooleanExtra("replay",false);
        Object last=getLastNonConfigurationInstance();kept=last instanceof Kept?(Kept)last:new Kept();if(kept.check!=null)kept.check.screen=this;
        if(state!=null){step=Math.max(0,Math.min(STEPS-1,state.getInt("ob_step")));provider=OPENAI.equals(state.getString("ob_provider"))?OPENAI:OPENROUTER;name=state.getString("ob_name","");askedMic=state.getBoolean("ob_asked");}
        else{
            String saved=settings.prefs.getString("myVoiceName","").trim();name=saved.equals("Yo")?"":saved;
            // OpenRouter es la recomendada; en un repaso parte elegida la que ya usas.
            provider=settings.provider().equals(OPENAI)&&settings.hasOpenAiKey()?OPENAI:OPENROUTER;
            Diagnostics.event("ui_action",null,"screen","Onboarding","action",replay?"replay":"start");
        }
        shell(null,-1,true);
        // El teclado nunca se abre solo al entrar a un paso: solo cuando tocas un campo.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildFrame();show(step,0,false);
        if(state==null)enter(stage);
    }
    @Override protected void onResume(){super.onResume();renderMic(false);renderReady();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("ob_step",step);out.putString("ob_provider",provider);out.putString("ob_name",name);out.putBoolean("ob_asked",askedMic);super.onSaveInstanceState(out);}
    @Override public Object onRetainNonConfigurationInstance(){return kept;}
    @Override protected void onDestroy(){
        main.removeCallbacksAndMessages(null);Check c=kept.check;
        // Si la pantalla se va de verdad (no por un giro), la comprobación ya no tiene a quién avisar: se corta.
        if(c!=null){if(c.screen==this)c.screen=null;if(isFinishing())c.http.cancel();}
        super.onDestroy();
    }
    @Override public void onBackPressed(){
        if(switching)return;
        if(step>0){go(step-1);return;}
        // En el primer paso, Atrás sale: en un repaso vuelve a Ajustes; la primera vez cierra la app, y la bienvenida
        // vuelve a aparecer al abrirla (no quedó vista).
        if(replay)finish();else finishAffinity();
    }

    // ---------- Marco ----------
    private void buildFrame(){
        root.setFocusableInTouchMode(true);
        // Barra: ← a la izquierda, los puntitos al centro y «Saltar» siempre a mano.
        FrameLayout top=new FrameLayout(this);top.setPadding(dp(S3),dp(S1),dp(S2),dp(S1));top.setClipChildren(false);top.setClipToPadding(false);
        back=ui.glassButton(R.drawable.ic_arrow_back,"Volver al paso anterior");back.setOnClickListener(v->onBackPressed());
        top.addView(back,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        dots=new Steps(this,p,STEPS);top.addView(dots,new FrameLayout.LayoutParams(-2,dp(48),Gravity.CENTER));
        skip=ui.button(replay?"Cerrar":"Saltar",0,Ui.Style.PLAIN,v->finishFlow("skip"));skip.setMinimumHeight(dp(48));skip.setContentDescription(replay?"Cerrar la bienvenida":"Saltar la bienvenida");
        top.addView(skip,new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL));
        root.addView(top,0,Ui.fill());
        // Las ondas de la ilustración llegan hasta el borde de la pantalla: nada se corta en los márgenes.
        page.setPadding(dp(S4),0,dp(S4),dp(S2));page.setClipToPadding(false);page.setClipChildren(false);
        // Abajo, fijo: el botón de tinta siempre en el mismo lugar. «Ahora no» (solo en el paso de la clave) va ENCIMA, así
        // no lo mueve, y es una píldora de vidrio: se lee tanto sobre el verde de abajo como sobre el blanco.
        bottom.setVisibility(View.VISIBLE);
        later=ui.text("Ahora no, solo grabar",Type.LABEL_LARGE,p.onSurface);later.setGravity(Gravity.CENTER);int inset=dp(S1);
        later.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(outline(this,p.glass,p.outlineVariant,R_FULL,false),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,inset,0,inset)));
        // El relleno va después del fondo: el InsetDrawable trae el suyo y setBackground lo reemplazaría.
        later.setPadding(dp(S5),0,dp(S5),0);later.setMinHeight(dp(48));later.setMinimumHeight(dp(48));
        later.setClickable(true);later.setFocusable(true);later.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(later);
        later.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","Onboarding","action","later");go(READY);});
        LinearLayout.LayoutParams ll=Ui.wrap();ll.gravity=Gravity.CENTER_HORIZONTAL;ll.bottomMargin=dp(S1);bottom.addView(later,ll);
        next=ui.button("Empezar",0,Ui.Style.PRIMARY,v->advance());bottom.addView(next,Ui.fill());
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::checkIme);
    }
    /** Lo que cambia con el paso en el marco: ←, los puntitos y el verbo del botón principal. */
    private void frame(boolean animate){
        dots.setStep(step,animate);
        back.setVisibility(step>0||replay?View.VISIBLE:View.INVISIBLE);back.setContentDescription(step>0?"Volver al paso anterior":"Cerrar la bienvenida");
        boolean last=step==READY;
        next.setText(step==WELCOME?"Empezar":!last?"Continuar":replay?"Listo":"Hacer mi primera grabación");next.setIcon(last&&!replay?R.drawable.ic_mic_fill:0);
        later.setVisibility(step==AI&&!imeShown?View.VISIBLE:View.GONE);
        // El lector de pantalla anuncia el cambio de paso con el título de la ventana.
        setTitle(STEP_NAMES[step]+", paso "+(step+1)+" de "+STEPS);
        secure();
    }
    private static final String[] STEP_NAMES={"Bienvenida","Tú","Conecta tu IA","Listo"};
    /**
     * Mientras la clave está en el campo (o se está por escribir), la pantalla no sale en capturas ni en la vista de
     * apps recientes, igual que la hoja de la clave en Ajustes. Solo cambia cuando hace falta: no en cada letra.
     */
    private void secure(){
        boolean want=step==AI&&(!kept.key.isEmpty()||(keyInput!=null&&keyInput.hasFocus()));
        if(want==secured)return;secured=want;getWindow().setFlags(want?WindowManager.LayoutParams.FLAG_SECURE:0,WindowManager.LayoutParams.FLAG_SECURE);
    }
    /**
     * Con el teclado abierto el botón principal sigue a la vista, justo sobre el teclado (la pantalla se achica con él);
     * «Ahora no» se esconde para dejar más lugar al campo, y el campo que se escribe sube a la vista con su tarjeta.
     */
    private void checkIme(){
        boolean ime;
        if(Build.VERSION.SDK_INT>=30){WindowInsets w=root.getRootWindowInsets();ime=w!=null&&w.isVisible(WindowInsets.Type.ime());}
        else{Rect r=new Rect();root.getWindowVisibleDisplayFrame(r);ime=root.getRootView().getHeight()-r.bottom>dp(160);}
        if(ime==imeShown)return;imeShown=ime;later.setVisibility(step==AI&&!ime?View.VISIBLE:View.GONE);
        if(ime)scroll.post(()->{View f=getCurrentFocus();if(f==keyInput&&keyCard!=null)reveal(keyCard);else if(f==nameInput&&nameCard!=null)reveal(nameCard);});
    }
    /** Desplaza lo justo para que la vista quede entera sobre el botón principal (o, si no cabe, desde su comienzo). */
    private void reveal(View v){
        if(v==null||!v.isAttachedToWindow()||scroll.getHeight()==0)return;
        try{Rect r=new Rect(0,0,v.getWidth(),v.getHeight());page.offsetDescendantRectToMyCoords(v,r);
            int y=Math.max(0,Math.min(r.top-dp(S2),r.bottom+dp(S3)-scroll.getHeight()));if(y>scroll.getScrollY())scroll.smoothScrollTo(0,y);
        }catch(IllegalArgumentException ignored){}
    }
    private void hideKeyboard(){
        View f=getCurrentFocus();InputMethodManager imm=getSystemService(InputMethodManager.class);
        if(imm!=null&&f!=null)imm.hideSoftInputFromWindow(f.getWindowToken(),0);
        if(f instanceof EditText){f.clearFocus();root.requestFocus();}
    }

    // ---------- Pasos ----------
    private void advance(){
        // Un doble toque no salta dos pasos: mientras cambia el paso, y un instante después, el botón no avanza.
        if(switching||SystemClock.elapsedRealtime()-switchedAt<350)return;
        if(step==WELCOME)go(YOU);else if(step==YOU){saveName();go(AI);}else if(step==AI)submitKey();else finishFlow("done");
    }
    private void go(int target){
        if(switching||target==step||target<0||target>=STEPS)return;
        hideKeyboard();Diagnostics.event("ui_action",null,"screen","Onboarding","action","step","result",target+1);
        // Llegar a «Listo» ya cuenta como bienvenida vista: desde ahí se puede ir a Ajustes y volver a Grabar por las pestañas.
        if(target==READY)complete(this,replay);
        show(target,target>step?1:-1,true);
    }
    /**
     * Cambia de paso: el que sale se desvanece hacia el lado y el nuevo entra en cascada (ilustración, título, tarjetas).
     * Con «Quitar animaciones» el cambio es directo. Mientras dura el cambio no se aceptan más toques de avance.
     */
    private void show(int target,int dir,boolean animate){
        step=target;LinearLayout out=stage;
        heading=null;micDetail=null;keyLabel=null;keyStatus=null;nameInput=null;keyInput=null;micArt=null;allow=null;nameCard=null;keyCard=null;readyList=null;cards[0]=cards[1]=null;
        LinearLayout in=target==WELCOME?buildWelcome():target==YOU?buildYou():target==AI?buildAi():buildReady();stage=in;
        boolean[] done={false};
        Runnable swap=()->{
            if(done[0]||stage!=in||isDestroyed())return;done[0]=true;switching=false;switchedAt=animate?SystemClock.elapsedRealtime():0;
            page.removeAllViews();page.addView(in,new LinearLayout.LayoutParams(-1,0,1));scroll.scrollTo(0,0);root.requestFocus();frame(animate);
            if(!animate)return;
            enter(in);
            // Lector de pantalla: el foco pasa al título del paso nuevo (sin lector, no hace nada).
            TextView h=heading;if(h!=null)main.postDelayed(()->{if(heading==h&&h.isAttachedToWindow())h.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);},AppTheme.motion()?MOTION_SLOW:0);
        };
        if(out==null||!animate||!AppTheme.motion()){swap.run();return;}
        switching=true;out.animate().cancel();
        out.animate().alpha(0f).translationX(-dir*dp(S6)).setStartDelay(0).setDuration(MOTION_FAST+80).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(swap).start();
        // Si algo cortara la salida (su final no llegaría), el paso nuevo entra igual: nunca queda la pantalla a medias.
        main.postDelayed(swap,MOTION_FAST+380);
    }
    /**
     * Entrada de un paso: la ilustración se asienta con un resorte (tamaño) mientras aparece (transparencia, sin rebote),
     * las ondas llegan después y el resto sube en cascada. Con «Quitar animaciones» no hace nada: todo ya está en su lugar.
     */
    private void enter(ViewGroup c){
        if(c==null||!AppTheme.motion())return;int n=0;
        for(int i=0;i<c.getChildCount();i++){
            View v=c.getChildAt(i);
            if(v.getTag()==HERO_TAG&&v instanceof ViewGroup){
                View waves=((ViewGroup)v).getChildAt(0),art=((ViewGroup)v).getChildAt(1);
                waves.setAlpha(0f);waves.animate().alpha(1f).setStartDelay(140).setDuration(MOTION_SLOW).setInterpolator(EMPHASIZED_DECELERATE).start();
                art.setScaleX(0.8f);art.setScaleY(0.8f);art.setAlpha(0f);
                art.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(SPATIAL_SLOW.duration).setInterpolator(SPATIAL_SLOW).start();
                art.animate().alpha(1f).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_DECELERATE).start();
                continue;
            }
            v.setAlpha(0f);v.setTranslationY(dp(S4));
            v.animate().alpha(1f).translationY(0f).setStartDelay(60+45L*n++).setDuration(MOTION_SLOW).setInterpolator(EMPHASIZED_DECELERATE).start();
        }
    }
    /** Terminar o saltar: la primera vez vuelve a Grabar, que esperaba debajo; en un repaso, a Ajustes. */
    private void finishFlow(String how){
        Diagnostics.event("ui_action",null,"screen","Onboarding","action",how,"result",step+1);
        hideKeyboard();complete(this,replay);
        // Si por algo Grabar no estuviera debajo (Android cerró la app a mitad de la bienvenida y solo repuso esta pantalla), se abre.
        if(!replay&&isTaskRoot())startActivity(new Intent(this,MainActivity.class));
        finish();overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);
    }

    // ---------- Piezas comunes ----------
    private LinearLayout column(){LinearLayout c=ui.column();c.setClipChildren(false);c.setClipToPadding(false);return c;}
    /** Ilustración del paso: el disco con halo entre ondas grises que llegan al borde de la pantalla, como el micrófono de Grabar. */
    private FrameLayout hero(Emblem art){
        FrameLayout f=new FrameLayout(this);f.setClipChildren(false);f.setTag(HERO_TAG);f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        Waveform.Decor waves=new Waveform.Decor(this,p.waveIdle);waves.setGap(dp(HERO-12));f.addView(waves,new FrameLayout.LayoutParams(-1,dp(72),Gravity.CENTER_VERTICAL));
        f.addView(art,new FrameLayout.LayoutParams(dp(HERO),dp(HERO),Gravity.CENTER));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(HERO));lp.setMarginStart(-dp(S4));lp.setMarginEnd(-dp(S4));lp.bottomMargin=dp(S1);f.setLayoutParams(lp);
        return f;
    }
    /** Línea chica sobre el título (como «Buenos días,» sobre tu nombre en Grabar). */
    private TextView overline(String text){TextView t=ui.text(text,Type.ITEM,p.onSurfaceVariant);t.setGravity(Gravity.CENTER);return t;}
    /** Título grande centrado con la última palabra destacada en menta. spoken: cómo lo lee el lector si shown trae un salto de línea. */
    private TextView title(String shown,String spoken){
        TextView t=ui.heading("",Type.DISPLAY_SMALL);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(2),0,0);
        // Si la línea más larga no cabe a 36 sp (pantalla angosta, letra grande del sistema o un nombre largo), baja a 32 y
        // luego a 28 antes de partirse en más líneas: el título pesa igual y no empuja las tarjetas fuera de la pantalla.
        float room=getResources().getDisplayMetrics().widthPixels-2*dp(S4)-2*dp(6);
        for(Type smaller:new Type[]{Type.HEADLINE_LARGE,Type.HEADLINE_MEDIUM}){
            float widest=0;for(String line:shown.split("\n"))widest=Math.max(widest,t.getPaint().measureText(line));
            if(widest<=room)break;AppTheme.type(t,smaller);
        }
        ui.highlightLast(t,shown);if(spoken!=null)t.setContentDescription(spoken);return t;
    }
    /** Aire que se estira: en pantallas altas empuja las tarjetas hacia el botón; en las bajas queda en su mínimo. */
    private View gap(){View v=new View(this);v.setMinimumHeight(dp(S5));v.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));return v;}
    /** Fila de tarjeta: ícono en círculo menta + título + apoyo. */
    private LinearLayout lead(int icon,String title,TextView detail){
        LinearLayout row=ui.row();row.addView(ui.tile(icon,p.onPrimaryContainer,p.primaryContainer,44,22));row.addView(ui.space(S3));
        LinearLayout t=ui.column();t.addView(ui.text(title,Type.TITLE_MEDIUM,p.onSurface));detail.setPadding(0,dp(2),0,0);t.addView(detail);
        row.addView(t,new LinearLayout.LayoutParams(0,-2,1));return row;
    }

    // ---------- 1. Bienvenida ----------
    private LinearLayout buildWelcome(){
        LinearLayout c=column();
        c.addView(hero(new Emblem(this,p,Emblem.LOGO)));
        c.addView(overline("Bienvenido a Verbapp"),Ui.fill());
        heading=title("Tus palabras,\npara siempre","Tus palabras, para siempre");c.addView(heading,Ui.fill());
        c.addView(gap());
        int[] icons={R.drawable.ic_mic,R.drawable.ic_people,R.drawable.ic_note};
        String[][] rows={{"Graba sin internet","Todo queda en tu teléfono."},{"Transcribe separando voces","Para saber quién dijo cada cosa."},{"Una nota lista para guardar","Directo a tu segundo cerebro."}};
        for(int i=0;i<rows.length;i++){
            LinearLayout card=lead(icons[i],rows[i][0],ui.text(rows[i][1],Type.BODY_MEDIUM,p.onSurfaceVariant));
            card.setBackground(glass(this,p,R_CARD));card.setPadding(dp(S3),dp(S3),dp(S4),dp(S3));
            // Para el lector de pantalla cada tarjeta es una sola frase.
            card.setContentDescription(rows[i][0]+". "+rows[i][1]);card.setFocusable(true);card.getChildAt(2).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            c.addView(card,ui.top(i==0?0:S2));
        }
        return c;
    }

    // ---------- 2. Tú ----------
    private LinearLayout buildYou(){
        LinearLayout c=column();
        micArt=new Emblem(this,p,Emblem.MIC);c.addView(hero(micArt));
        c.addView(overline("Un gusto,"),Ui.fill());
        heading=title("¿Cómo te llamas?",null);c.addView(heading,Ui.fill());
        // El nombre: opcional. La tarjeta va a 8 dp del campo (esquinas de 16 dentro de 24: concéntricas).
        nameCard=ui.card();nameCard.setPadding(dp(S2),dp(S2),dp(S2),dp(S3));
        nameInput=ui.field("Tu nombre","Tu nombre, opcional");nameInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PERSON_NAME|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);nameInput.setSaveEnabled(false);nameInput.setText(name);
        nameInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int n){}public void onTextChanged(CharSequence s,int a,int b,int n){name=s.toString();}public void afterTextChanged(Editable e){}});
        nameInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){hideKeyboard();return true;}return false;});
        nameCard.addView(nameInput,Ui.fill());
        TextView why=ui.text("Opcional: para saludarte y poner tu nombre en las transcripciones.",Type.BODY_MEDIUM,p.onSurfaceVariant);why.setPadding(dp(S2),dp(S2),dp(S2),0);nameCard.addView(why,Ui.fill());
        c.addView(nameCard,ui.top(S4));
        c.addView(gap());
        // El micrófono: se puede seguir sin permitirlo (Grabar lo vuelve a pedir al primer toque).
        LinearLayout mic=ui.card();mic.setPadding(dp(S2),dp(S2),dp(S2),dp(S2));
        micDetail=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);micDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout head=lead(R.drawable.ic_mic,"Micrófono",micDetail);head.setPadding(dp(S1),dp(S1),dp(S2),dp(S3));mic.addView(head,Ui.fill());
        allow=ui.button("Permitir micrófono",R.drawable.ic_mic_fill,Ui.Style.RECORD,v->askMic());mic.addView(allow,Ui.fill());
        c.addView(mic,Ui.fill());
        renderMic(false);
        return c;
    }
    private boolean micGranted(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    /** Estado real del permiso: verde «Permitir micrófono» mientras falta; menta con ✓ (lo logrado) cuando ya está, y el ✓ también en la ilustración. */
    private void renderMic(boolean celebrate){
        if(allow==null||micDetail==null)return;boolean ok=micGranted();
        allow.setText(ok?"Micrófono permitido":"Permitir micrófono");allow.setIcon(ok?R.drawable.ic_check:R.drawable.ic_mic_fill);
        allow.setColors(ok?p.onPrimaryContainer:p.onBrand,ok?p.primaryContainer:p.brand);allow.setClickable(!ok);
        // Ya concedido deja de ser un botón: sin esto, el gesto de «hundirse» al tocar quedaría a medias (no llega el soltar).
        if(ok){allow.setOnTouchListener(null);allow.animate().cancel();allow.setScaleX(1f);allow.setScaleY(1f);}
        String text=ok?"Listo: ya puedes grabar cuando quieras.":Build.VERSION.SDK_INT>=33?"Solo se usa mientras grabas. Los avisos muestran que sigue grabando.":"Solo se usa mientras grabas. El audio queda en tu teléfono.";
        if(!text.contentEquals(micDetail.getText()))micDetail.setText(text);
        if(micArt!=null)micArt.setBadge(ok,celebrate);
        if(celebrate&&ok)Ui.haptic(allow,Ui.Haptic.CONFIRM);
    }
    /** Pide el micrófono y, en Android 13+, los avisos (la notificación que muestra que la grabación sigue), en un solo gesto. */
    private void askMic(){
        if(micGranted()){renderMic(false);return;}
        // Ya se pidió y Android no volverá a preguntar (se negó dos veces): la salida son los ajustes del sistema.
        if(askedMic&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
            sheet("El micrófono está desactivado","Actívalo en los ajustes de Android para poder grabar. También puedes hacerlo después.")
                .primary("Abrir ajustes",()->{try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(RuntimeException e){toast("No se pudieron abrir los ajustes.");}})
                .secondary("Ahora no",null).show();return;
        }
        askedMic=true;ArrayList<String> ask=new ArrayList<>();ask.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){ask.add(Manifest.permission.POST_NOTIFICATIONS);notificationsAsked();}
        requestPermissions(ask.toArray(new String[0]),REQ_MIC);
    }
    /**
     * Grabar pregunta por los avisos una sola vez y lo recuerda en las preferencias propias de MainActivity
     * ("notificationAsked"): si ya se preguntó aquí, no se repite al hacer la primera grabación.
     */
    private void notificationsAsked(){
        String cls=MainActivity.class.getName(),pkg=getPackageName(),file=cls.startsWith(pkg+".")?cls.substring(pkg.length()+1):cls;
        getSharedPreferences(file,MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=REQ_MIC)return;
        boolean ok=micGranted();Diagnostics.event("ui_action",null,"screen","Onboarding","action","mic","result",ok);renderMic(true);
    }
    /** Solo si escribió algo distinto: un campo vacío no borra el nombre que ya hubiera. */
    private void saveName(){
        String n=Voices.clean(name);name=n;
        if(n.isEmpty()||n.equals(settings.prefs.getString("myVoiceName","")))return;
        try{Voices.setName(this,n);}catch(RuntimeException e){settings.prefs.edit().putString("myVoiceName",n).apply();}
    }

    // ---------- 3. Conecta tu IA ----------
    private LinearLayout buildAi(){
        LinearLayout c=column();c.setPadding(0,dp(S2),0,0);
        heading=title("Conecta tu IA",null);c.addView(heading,Ui.fill());
        TextView sub=ui.text("Verbapp usa tu propia clave para transcribir y armar tus notas: pagas solo lo que usas.",Type.BODY_MEDIUM,p.onSurfaceVariant);
        sub.setGravity(Gravity.CENTER);sub.setPadding(dp(S4),dp(S2),dp(S4),dp(S4));c.addView(sub,Ui.fill());
        cards[0]=providerCard(0,OPENROUTER,R.drawable.onboarding_hub,"OpenRouter","Una sola clave para todo",true);c.addView(cards[0],Ui.fill());
        cards[1]=providerCard(1,OPENAI,R.drawable.ic_sparkle,"OpenAI","Con tu clave de OpenAI",false);c.addView(cards[1],ui.top(S2));
        // La clave: rótulo, campo con «Pegar» adentro, el estado en palabras y cómo conseguirla.
        keyCard=ui.card();keyCard.setPadding(dp(S2),dp(S3),dp(S2),dp(S1));
        LinearLayout label=ui.row();label.setPadding(dp(S2),0,dp(S2),dp(S2));label.addView(ui.icon(R.drawable.ic_key,p.primary,18));label.addView(ui.space(S2));
        keyLabel=ui.text("",Type.TITLE_SMALL,p.onSurface);label.addView(keyLabel);keyCard.addView(label,Ui.fill());
        LinearLayout box=ui.row();box.setBackground(ui.fieldBackground());box.setPadding(dp(S4),0,dp(S1),0);box.setMinimumHeight(dp(56));
        // Campo oculto, sin autocompletar y sin guardarse en el estado de la pantalla (igual que la hoja de la clave en Ajustes).
        keyInput=ui.field("","Clave");keyInput.setBackground(null);keyInput.setPadding(0,dp(S3),dp(S2),dp(S3));
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSaveEnabled(false);keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyInput.setText(kept.key);
        keyInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int n){}public void onTextChanged(CharSequence s,int a,int b,int n){keyChanged(s.toString());}public void afterTextChanged(Editable e){}});
        keyInput.setOnFocusChangeListener((v,focused)->secure());
        keyInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){advance();return true;}return false;});
        box.addView(keyInput,new LinearLayout.LayoutParams(0,-2,1));
        TextView paste=ui.text("Pegar",Type.LABEL_LARGE,p.onPrimaryContainer);paste.setGravity(Gravity.CENTER);int in=dp(6);
        paste.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onPrimaryContainer)),new InsetDrawable(shape(this,p.primaryContainer,R_FULL),0,in,0,in),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,in,0,in)));
        paste.setPadding(dp(S3),0,dp(S4),0);paste.setMinHeight(dp(48));paste.setMinimumHeight(dp(48));
        Drawable clip=getDrawable(R.drawable.onboarding_paste).mutate();clip.setTint(p.onPrimaryContainer);clip.setBounds(0,0,dp(18),dp(18));paste.setCompoundDrawablesRelative(clip,null,null,null);paste.setCompoundDrawablePadding(dp(6));
        paste.setClickable(true);paste.setFocusable(true);paste.setAccessibilityDelegate(Ui.buttonRole());paste.setContentDescription("Pegar la clave copiada");paste.setOnClickListener(v->paste());Ui.pressable(paste);
        box.addView(paste);keyCard.addView(box,Ui.fill());
        keyStatus=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);keyStatus.setPadding(dp(S2),dp(S2),dp(S2),0);keyStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);keyCard.addView(keyStatus,Ui.fill());
        Ui.Btn how=ui.button("¿Cómo consigo una clave?",R.drawable.ic_open_in_new,Ui.Style.PLAIN,v->howTo());how.setMinimumHeight(dp(48));how.setPadding(dp(S2),0,dp(S3),0);keyCard.addView(how,Ui.wrap());
        c.addView(keyCard,ui.top(S3));
        paintProviders();defaultNote();
        return c;
    }
    /** Tarjeta de proveedor: una de dos (botón de radio). La elegida lleva el borde verde de marca. */
    private LinearLayout providerCard(int index,String id,int icon,String label,String detail,boolean recommended){
        LinearLayout card=ui.row();card.setPadding(dp(S3),dp(S3),dp(S4),dp(S3));card.setMinimumHeight(dp(68));
        card.addView(ui.tile(icon,p.onPrimaryContainer,p.primaryContainer,44,22));card.addView(ui.space(S3));
        LinearLayout texts=ui.column(),line=ui.row();line.addView(ui.text(label,Type.TITLE_MEDIUM,p.onSurface));
        if(recommended){TextView chip=ui.chip("Recomendada",p.onPrimaryContainer,p.primaryContainer);AppTheme.type(chip,Type.LABEL_MEDIUM);chip.setPadding(dp(S2),dp(2),dp(S2),dp(2));LinearLayout.LayoutParams cp=Ui.wrap();cp.setMarginStart(dp(S2));line.addView(chip,cp);}
        texts.addView(line,Ui.wrap());TextView d=ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,dp(2),0,0);texts.addView(d);
        card.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        radios[index]=ui.icon(R.drawable.ic_radio_off,p.onSurfaceVariant,24);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(24),dp(24));rp.setMarginStart(dp(S2));card.addView(radios[index],rp);
        card.setClickable(true);card.setFocusable(true);card.setContentDescription(label+(recommended?", recomendada. ":". ")+detail);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(RadioButton.class.getName());info.setCheckable(true);info.setChecked(id.equals(provider));}});
        card.setOnClickListener(v->{Ui.haptic(v);Diagnostics.event("ui_action",null,"screen","Onboarding","action","provider","result",id);pick(id,true);});Ui.pressable(card);
        return card;
    }
    private void paintProviders(){
        String[] ids={OPENROUTER,OPENAI};
        for(int i=0;i<2;i++){
            if(cards[i]==null)continue;boolean on=ids[i].equals(provider);
            GradientDrawable g=shape(this,p.glass,R_CARD);g.setStroke(on?dp(2):Math.max(1,dp(1)),on?p.primary:p.glassStroke);
            cards[i].setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),g,shape(this,0xFF000000,R_CARD)));cards[i].setSelected(on);
            radios[i].setImageResource(on?R.drawable.ic_radio_on:R.drawable.ic_radio_off);radios[i].setImageTintList(ColorStateList.valueOf(on?p.primary:p.onSurfaceVariant));
        }
        if(keyLabel!=null)keyLabel.setText("Clave de "+providerName(provider));
        if(keyInput!=null){keyInput.setHint(OPENROUTER.equals(provider)?"sk-or-…":"sk-…");keyInput.setContentDescription("Clave de "+providerName(provider));}
    }
    /** byUser: la eligió tocando su tarjeta (la línea de estado vuelve a lo que corresponde a ese proveedor). */
    private void pick(String id,boolean byUser){
        if(id.equals(provider))return;provider=id;paintProviders();if(byUser)defaultNote();
    }
    private void note(String text,int tone){
        if(keyStatus==null)return;keyTone=tone;if(!text.contentEquals(keyStatus.getText()))keyStatus.setText(text);
        keyStatus.setTextColor(tone==BAD?p.error:tone==OK?p.primary:p.onSurfaceVariant);
    }
    /** Lo que dice la línea bajo el campo cuando no hay nada que avisar: si la última clave falló, si ya hay una guardada, o cómo se guarda. */
    private void defaultNote(){
        Check c=kept.check;
        if(c!=null&&c.state==Check.REJECTED&&c.provider.equals(provider))note(c.message+" Pega otra clave.",BAD);
        else if(hasKey(settings,provider))note("Ya hay una clave guardada. Pega otra solo si quieres cambiarla.",OK);
        else note("Se guarda cifrada en este teléfono.",INFO);
    }
    /**
     * Cada cambio del campo: la clave queda solo en memoria; si por su comienzo es claramente del otro proveedor, se elige
     * su tarjeta (una vez por clave: si después tocas la otra tarjeta, se respeta). Se espera a 8 letras para no saltar
     * entre tarjetas mientras alguien escribe «sk-or-…» a mano.
     */
    private void keyChanged(String text){
        kept.key=text;secure();String t=text.trim();
        if(t.isEmpty()){guessed=null;defaultNote();return;}
        String g=t.length()>=8?guessProvider(t):null;
        if(!Objects.equals(g,guessed)){guessed=g;if((OPENROUTER.equals(g)||OPENAI.equals(g))&&!g.equals(provider)){pick(g,false);note("Es una clave de "+providerName(g)+": elegimos esa tarjeta.",OK);return;}}
        if(keyTone==BAD)defaultNote();
    }
    /** «Pegar»: trae lo copiado solo si tiene forma de clave (el campo va oculto: no se vería qué se pegó). Lo copiado nunca se registra. */
    private void paste(){
        if(keyInput==null)return;String text="";
        try{android.content.ClipboardManager cm=getSystemService(android.content.ClipboardManager.class);ClipData clip=cm==null?null:cm.getPrimaryClip();
            if(clip!=null&&clip.getItemCount()>0){CharSequence t=clip.getItemAt(0).coerceToText(this);if(t!=null)text=t.toString().trim();}
        }catch(RuntimeException ignored){}
        if(text.isEmpty()){note("No hay nada copiado. Copia tu clave y vuelve a tocar Pegar.",BAD);Ui.haptic(keyInput,Ui.Haptic.REJECT);return;}
        if(text.matches("(?s).*\\s.*")||text.length()<MIN_KEY||text.length()>8192){note("Lo que copiaste no parece una clave. Cópiala entera y vuelve a tocar Pegar.",BAD);Ui.haptic(keyInput,Ui.Haptic.REJECT);return;}
        String before=provider;keyInput.setText(text);keyInput.setSelection(keyInput.length());Ui.haptic(keyInput,Ui.Haptic.CONFIRM);
        if(before.equals(provider))note("Clave pegada. Toca Continuar.",OK);
    }
    private void keyError(String text){note(text,BAD);Ui.haptic(next,Ui.Haptic.REJECT);scroll.post(()->reveal(keyCard));}
    /**
     * Continuar en el paso de la clave: se guarda, queda como servicio de transcripción y se comprueba en segundo plano
     * mientras ya se pasa a «Listo» (no bloquea: sin red igual se sigue). Sin nada escrito solo se avanza si ya había una
     * clave o es un repaso; la primera vez se explica cómo seguir sin clave.
     */
    private void submitKey(){
        String raw=kept.key.trim();
        if(raw.isEmpty()){if(replay||hasKey(settings,provider))go(READY);else keyError("Pega tu clave o toca «Ahora no, solo grabar».");return;}
        String problem=keyProblem(provider,raw);if(problem!=null){keyError(problem);return;}
        try{applyKey(settings,provider,raw);}
        catch(Exception e){Diagnostics.event("setting_changed",null,"action","api_key","source","onboarding","result","failed","error_class",e.getClass().getSimpleName());keyError("No se pudo guardar la clave en este teléfono. Inténtalo de nuevo.");return;}
        Diagnostics.event("setting_changed",null,"action","api_key","source","onboarding","provider",provider);
        // La clave ya no se vuelve a mostrar: el campo queda vacío aunque se vuelva a este paso.
        kept.key="";guessed=null;if(keyInput!=null)keyInput.setText("");
        try{Pipeline.schedule(this,true);}catch(RuntimeException ignored){}
        // Recién ahora el proveedor es OpenRouter (al abrir la app aún no lo era): se trae la lista de modelos en segundo
        // plano, para que «Automático» y los precios estén al día desde la primera transcripción. Es pública: no viaja la clave.
        if(OPENROUTER.equals(provider))try{Models.refreshIfStale(this);}catch(RuntimeException ignored){}
        startCheck(provider,raw);go(READY);
    }
    private void startCheck(String prov,String key){
        if(kept.check!=null)kept.check.http.cancel();
        Check c=new Check(prov);kept.check=c;c.screen=this;
        // El resultado queda también en las preferencias de Ajustes → «Comprobar conexión» (verify*): una clave comprobada
        // aquí ya aparece comprobada allá, con su saldo, y una rechazada aparece con su motivo. Solo si aquí se comprobó lo
        // mismo que comprobaría Ajustes: con OpenRouter siempre (GET /key); con OpenAI, cuando el modelo que se usaría hoy
        // es el de voces, que es por el que pregunta la bienvenida. «No se pudo comprobar» (sin red) no se guarda.
        Settings prefs=settings;String target=SettingsActivity.verifyTarget(prefs);boolean router=OPENROUTER.equals(prov),same=router||prefs.defaultSpeakers();
        Models.KeyInfo[] info={null};
        Checker checker=router?(h,k)->validText(info[0]=Models.checkKey(h,k)):checker(prov);
        new Thread(()->{
            long began=SystemClock.elapsedRealtime();
            Verdict v=verify(checker,c.http,key);
            if(same&&v.state!=Check.UNKNOWN&&!c.http.cancelled&&target.equals(SettingsActivity.verifyTarget(prefs))){
                Models.KeyInfo k=info[0];
                SettingsActivity.saveVerify(prefs,target,v.state==Check.VALID,SystemClock.elapsedRealtime()-began,v.message,k==null?Double.NaN:k.remaining,k!=null&&k.freeTier);
            }
            Diagnostics.event("setting_changed",null,"action","verify","source","onboarding","provider",prov,"result",v.state==Check.VALID?"valid":v.state==Check.REJECTED?"rejected":"unknown");
            RESULTS.post(()->{c.state=v.state;c.message=v.message;OnboardingActivity s=c.screen;if(s!=null&&s.kept.check==c)s.checked();});
        },"Voz-bienvenida").start();
    }
    /** Llegó el resultado de la comprobación: se refleja donde esté la persona (la fila del último paso, o la línea del campo). */
    private void checked(){
        Check c=kept.check;if(c==null||isDestroyed())return;
        if(readyList!=null){renderReady();Ui.haptic(readyList,c.state==Check.REJECTED?Ui.Haptic.REJECT:Ui.Haptic.CONFIRM);readyList.announceForAccessibility(c.message);}
        else if(keyStatus!=null&&kept.key.trim().isEmpty())defaultNote();
    }
    /** Antes de mandar a nadie al navegador: los pasos, en palabras simples, y recién ahí el botón que abre la página. */
    private void howTo(){
        boolean or=OPENROUTER.equals(provider);String site=or?"openrouter.ai":"platform.openai.com",url=or?KEYS_OPENROUTER:KEYS_OPENAI;
        Sheet s=sheet("Cómo conseguir tu clave",or?"OpenRouter te da acceso a muchas IA con una sola clave. Pagas solo lo que usas.":"La clave se crea en tu cuenta de desarrollador de OpenAI (no es la misma de ChatGPT).");
        String[] steps=or?new String[]{"Crea tu cuenta en openrouter.ai.","Carga un poco de crédito: se descuenta solo lo que usas.","En «Keys», crea una clave y cópiala.","Vuelve a Verbapp y toca Pegar."}
            :new String[]{"Entra a platform.openai.com con tu cuenta.","Agrega crédito o un medio de pago en «Billing».","En «API keys», crea una clave y cópiala.","Vuelve a Verbapp y toca Pegar."};
        for(int i=0;i<steps.length;i++){
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(dp(S1),dp(S2),0,dp(S2));
            TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onPrimaryContainer);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.primaryContainer));n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.addView(n,new LinearLayout.LayoutParams(dp(24),dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);
        }
        s.primary("Abrir "+site,()->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
            catch(ActivityNotFoundException|SecurityException e){message("No se pudo abrir el navegador","Entra a "+site+" desde un navegador, crea tu clave y vuelve para pegarla.");}
        }).secondary("Cerrar",null).show();
    }

    // ---------- 4. Listo ----------
    private LinearLayout buildReady(){
        LinearLayout c=column();
        c.addView(hero(new Emblem(this,p,Emblem.DONE)));
        c.addView(overline("Ya puedes grabar"),Ui.fill());
        heading=title(readyTitle(name),null);c.addView(heading,Ui.fill());
        c.addView(gap());
        readyList=ui.group();c.addView(readyList,Ui.fill());renderReady();
        return c;
    }
    /** Las tres filas del último paso, con su estado real: la clave (y su comprobación), tu voz y tu carpeta rápida. */
    private void renderReady(){
        if(readyList==null)return;readyList.removeAllViews();
        String prov=settings.provider(),who=providerName(prov);boolean has=settings.hasKey();Check c=kept.check;
        if(has&&c!=null&&c.provider.equals(prov)){
            if(c.state==Check.CHECKING)readyRow(R.drawable.ic_sparkle,who,"Comprobando tu clave…",ROW_BUSY,null);
            else if(c.state==Check.VALID)readyRow(R.drawable.ic_sparkle,who,c.message,ROW_DONE,null);
            else if(c.state==Check.REJECTED)readyRow(R.drawable.ic_alert,"Revisa tu clave de "+who,c.message,ROW_BAD,()->go(AI));
            else readyRow(R.drawable.ic_sparkle,who,c.message,ROW_PLAIN,null);
        }
        else if(has)readyRow(R.drawable.ic_sparkle,who,"Clave guardada: lista para transcribir.",ROW_DONE,null);
        else readyRow(R.drawable.ic_key,"Conectar tu IA","Sin clave solo grabas. Agrégala cuando quieras.",ROW_GO,()->go(AI));
        boolean voice=false;try{voice=Voices.has(this);}catch(RuntimeException ignored){}
        if(voice)readyRow(R.drawable.ic_voice,"Tu voz","Guardada: Verbapp ya te reconoce.",ROW_DONE,()->openSettings("voice"));
        else readyRow(R.drawable.ic_voice,"Grabar mi voz","Opcional: 10 segundos para que Verbapp te reconozca.",ROW_GO,()->openSettings("voice"));
        if(Inbox.configured(this))readyRow(R.drawable.ic_inbox,"Guardado rápido","Tus notas van a «"+Inbox.folderName(this)+"» con un toque.",ROW_DONE,()->openSettings("inbox"));
        else readyRow(R.drawable.ic_inbox,"Elegir mi carpeta 0-Inbox","Opcional: donde quedan tus notas con un toque.",ROW_GO,()->openSettings("inbox"));
    }
    private void readyRow(int icon,String title,String detail,int tone,Runnable click){
        boolean bad=tone==ROW_BAD;
        LinearLayout row=ui.row();row.setMinimumHeight(dp(72));row.setPadding(dp(S4),dp(S3),dp(S3),dp(S3));
        row.addView(ui.tile(icon,bad?p.onErrorContainer:p.onPrimaryContainer,bad?p.errorContainer:p.primaryContainer,44,22));row.addView(ui.space(S3));
        LinearLayout t=ui.column();t.addView(ui.text(title,Type.TITLE_MEDIUM,p.onSurface));TextView d=ui.text(detail,Type.BODY_MEDIUM,bad?p.error:p.onSurfaceVariant);d.setPadding(0,dp(2),0,0);t.addView(d);
        row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        // A la derecha, el estado de un vistazo: ✓ verde (hecho), anillo que gira (comprobando) o una flecha (por hacer o revisar).
        View mark=null;
        if(tone==ROW_DONE)mark=ui.tile(R.drawable.ic_check,p.onBrand,p.brand,28,18);
        else if(tone==ROW_BUSY)mark=new MainActivity.Ring(this,p.primary,p.primaryContainer,dp(2.5f));
        else if(tone==ROW_GO||bad){FrameLayout go=ui.tile(R.drawable.ic_arrow_back,p.onSurfaceVariant,0,32,18);go.setBackground(glassOval(this,p));go.getChildAt(0).setRotation(180);mark=go;}
        if(mark!=null){int size=dp(tone==ROW_DONE?28:tone==ROW_BUSY?24:32);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(size,size);mp.setMarginStart(dp(S3));mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(mark,mp);}
        row.setContentDescription(title+". "+detail);
        if(click!=null){
            row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());
            // Se registra el tipo de fila, no su texto (el de la carpeta trae un nombre puesto por la persona).
            row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","Onboarding","action","ready_row","result",readyList==null?-1:readyList.indexOfChild(row));click.run();});
        }else row.setFocusable(true);
        if(readyList.getChildCount()>0)readyList.addView(ui.separator(S4+44+S3));
        readyList.addView(row,Ui.fill());
    }
    /** Accesos opcionales: Ajustes abre directo ese paso («voice» = grabar tu voz, «inbox» = carpeta rápida) y vuelve aquí al terminar. */
    private void openSettings(String extra){startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true).putExtra(extra,true));}

    // ================= Vistas propias =================
    /**
     * Indicador de pasos: una píldora verde en el paso actual y puntos en los demás (verdes los ya vistos). Al cambiar, la
     * píldora se desliza al punto siguiente (con «Quitar animaciones», salta). Para el lector de pantalla: «Paso 2 de 4».
     */
    static final class Steps extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF rect=new RectF();private final int count,on,off;private float pos;private ValueAnimator anim;
        Steps(Context c,Palette p,int count){super(c);this.count=count;on=p.primary;off=p.outlineVariant;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);setStep(0,false);}
        void setStep(int step,boolean animate){
            setContentDescription("Paso "+(step+1)+" de "+count);if(anim!=null)anim.cancel();
            if(!animate||!AppTheme.motion()||!isAttachedToWindow()){pos=step;invalidate();return;}
            anim=ValueAnimator.ofFloat(pos,step);anim.setDuration(MOTION_SLOW);anim.setInterpolator(EMPHASIZED);
            anim.addUpdateListener(a->{pos=(float)a.getAnimatedValue();invalidate();});anim.start();
        }
        /** Ancho total: no cambia mientras la píldora se desliza (lo que un punto gana, el vecino lo pierde). */
        private float total(){return px(6)*count+(px(22)-px(6))+px(6)*(count-1);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize((int)Math.ceil(total()),w),resolveSize(Math.round(px(24)),h));}
        @Override protected void onDraw(Canvas canvas){
            float dot=px(6),wide=px(22),gap=px(6),x=(getWidth()-total())/2f,cy=getHeight()/2f;
            for(int i=0;i<count;i++){
                // near: cuánto de «paso actual» tiene este punto (1 = es el actual); los anteriores quedan verdes.
                float near=Math.max(0f,1f-Math.abs(i-pos)),w=dot+(wide-dot)*near;
                paint.setColor(blend(off,on,pos-i+1f));rect.set(x,cy-dot/2f,x+w,cy+dot/2f);canvas.drawRoundRect(rect,dot/2f,dot/2f,paint);x+=w+gap;
            }
        }
        private float px(float dp){return dp*getResources().getDisplayMetrics().density;}
    }

    /**
     * Ilustración de un paso: el disco verde de marca con su halo de dos anillos que respiran (el mismo gesto del botón de
     * grabar) y un símbolo blanco al centro. LOGO: las barras y el destello de Verbapp, como el ícono de la app · MIC: el
     * micrófono · DONE: un ✓ con destellos alrededor. Puede llevar una insignia ✓ (permiso concedido). Es solo dibujo: no
     * se toca ni se anuncia. El halo respira solo si Android permite animaciones y la vista está en pantalla.
     */
    static final class Emblem extends View {
        static final int LOGO=0,MIC=1,DONE=2;
        /** Las barras del logo en su caja de 24 (las mismas de Glass.BrandMark). */
        private static final float[] XS={3.5f,8.5f,13.5f,18f},TOPS={10f,5f,8f,12.5f},BOTTOMS={16f,21f,18f,15f};
        private final int kind,brand,light,deep,white,mint,accent,badgeFill;
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),halo=new Paint(Paint.ANTI_ALIAS_FLAG),bars=new Paint(Paint.ANTI_ALIAS_FLAG),spark=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path=new Path();private final Drawable glyph,tick;
        private float breath=0.5f,badge;private boolean shown;private ValueAnimator breathing,pop;

        Emblem(Context c,Palette p,int kind){
            super(c);this.kind=kind;brand=p.brand;deep=p.brandDeep;white=p.onBrand;light=blend(p.brand,p.onBrand,0.16f);
            // Destello del logo: menta clara, que se ve sobre el disco en ambos temas. Los de afuera van en el verde de la pantalla.
            mint=blend(p.onBrand,p.brand,0.24f);accent=p.primary;badgeFill=p.dark?p.ink:p.surfaceContainerLowest;
            halo.setColor(p.brand);bars.setColor(white);bars.setStyle(Paint.Style.STROKE);bars.setStrokeCap(Paint.Cap.ROUND);
            glyph=kind==LOGO?null:c.getDrawable(kind==MIC?R.drawable.ic_mic_fill:R.drawable.ic_check).mutate();if(glyph!=null)glyph.setTint(white);
            tick=c.getDrawable(R.drawable.ic_check).mutate();tick.setTint(p.dark?p.onInk:p.primary);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Sombra suave con la forma del disco, en verde muy oscuro (como el botón de grabar).
            setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){float cx=v.getWidth()/2f,cy=v.getHeight()/2f,r=radius();o.setOval(Math.round(cx-r),Math.round(cy-r),Math.round(cx+r),Math.round(cy+r));}});
            setElevation(p.dark?0:px(6));if(Build.VERSION.SDK_INT>=28&&!p.dark){setOutlineSpotShadowColor(p.shadow);setOutlineAmbientShadowColor(p.shadow);}
        }
        /** Insignia ✓ abajo a la derecha del disco. animate: aparece con un resorte corto (es tamaño, puede rebotar). */
        void setBadge(boolean on,boolean animate){
            float target=on?1f:0f;if(pop!=null)pop.cancel();
            if(!animate||!AppTheme.motion()||!isAttachedToWindow()||badge==target){badge=target;invalidate();return;}
            pop=ValueAnimator.ofFloat(badge,target);pop.setDuration(SPATIAL_FAST.duration);pop.setInterpolator(SPATIAL_FAST);
            pop.addUpdateListener(a->{badge=(float)a.getAnimatedValue();invalidate();});pop.start();
        }
        // ---------- Respiración del halo (igual que RecordButton) ----------
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncBreath();}
        @Override protected void onDetachedFromWindow(){shown=false;syncBreath();super.onDetachedFromWindow();}
        @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);shown=visible;syncBreath();}
        private void syncBreath(){
            boolean want=shown&&isAttachedToWindow()&&AppTheme.motion();
            if(want&&breathing==null){
                breathing=ValueAnimator.ofFloat(0f,1f);breathing.setDuration(2600);breathing.setRepeatMode(ValueAnimator.REVERSE);breathing.setRepeatCount(ValueAnimator.INFINITE);breathing.setInterpolator(new AccelerateDecelerateInterpolator());
                // Se redibuja por pasos de 1/48: se ve continuo y la pantalla no se repinta en cada cuadro.
                breathing.addUpdateListener(a->{float b=Math.round((float)a.getAnimatedValue()*48f)/48f;if(b!=breath){breath=b;invalidate();}});
                breathing.start();
            }else if(!want&&breathing!=null){breathing.cancel();breathing=null;breath=0.5f;invalidate();}
        }
        // ---------- Dibujo ----------
        /** Radio del disco: lo que sobra alrededor (28 dp) es para el halo. */
        private float radius(){return Math.max(px(20),Math.min(getWidth(),getHeight())/2f-px(28));}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            super.onSizeChanged(w,h,ow,oh);invalidateOutline();float cx=w/2f,cy=h/2f,r=radius();
            // Brillo arriba a la izquierda hacia el verde profundo abajo a la derecha (volumen, como el ícono de la app).
            fill.setShader(new LinearGradient(cx-r,cy-r,cx+r,cy+r,new int[]{light,brand,blend(brand,deep,0.35f)},new float[]{0f,0.5f,1f},Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f,r=radius(),room=Math.min(cx,cy)-r;
            // Dos anillos (discos translúcidos) que respiran en fases opuestas.
            halo.setAlpha(Math.round(20+10*breath));canvas.drawCircle(cx,cy,r+room*(0.8f+0.14f*breath),halo);
            halo.setAlpha(Math.round(38+8*(1f-breath)));canvas.drawCircle(cx,cy,r+room*(0.42f+0.08f*(1f-breath)),halo);
            canvas.drawCircle(cx,cy,r,fill);
            if(kind==LOGO){
                // u: una unidad de la caja de 24 del logo; el conjunto (barras + destello) queda centrado a ojo en el disco.
                float u=r*0.058f,ox=cx-12.4f*u,oy=cy-12.6f*u;bars.setStrokeWidth(2.3f*u);
                for(int i=0;i<XS.length;i++)canvas.drawLine(ox+XS[i]*u,oy+TOPS[i]*u,ox+XS[i]*u,oy+BOTTOMS[i]*u,bars);
                spark.setColor(mint);canvas.drawPath(Glass.sparkle(path,ox+20.5f*u,oy+4.5f*u,3.8f*u),spark);
            }else if(glyph!=null){
                int half=Math.round(Math.min(px(kind==DONE?20:17),r*0.5f));glyph.setBounds(Math.round(cx)-half,Math.round(cy)-half,Math.round(cx)+half,Math.round(cy)+half);glyph.draw(canvas);
            }
            if(kind==DONE){
                // Destellos ✦ (el del logo) alrededor del ✓: celebran sin ruido y laten apenas con el halo.
                float k=0.9f+0.1f*breath;spark.setColor(accent);canvas.drawPath(Glass.sparkle(path,cx+r*1.2f,cy-r*0.9f,px(9)*k),spark);
                spark.setAlpha(190);canvas.drawPath(Glass.sparkle(path,cx-r*1.3f,cy-r*0.42f,px(6)*(1.9f-k)),spark);
                spark.setAlpha(150);canvas.drawPath(Glass.sparkle(path,cx+r*1.28f,cy+r*0.66f,px(5)*k),spark);
            }
            if(badge>0.01f){
                float bx=cx+r*0.7f,by=cy+r*0.7f;spark.setColor(badgeFill);canvas.drawCircle(bx,by,px(13)*badge,spark);
                int half=Math.round(px(8)*badge);tick.setBounds(Math.round(bx)-half,Math.round(by)-half,Math.round(bx)+half,Math.round(by)+half);tick.draw(canvas);
            }
        }
        private float px(float dp){return dp*getResources().getDisplayMetrics().density;}
    }
}
