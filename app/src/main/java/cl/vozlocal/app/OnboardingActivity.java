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
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Bienvenida de la primera instalación (0.8.0, ver docs/diseno/SPEC-0.8.md → «onboarding»): cuatro pasos cortos a pantalla
 * completa sobre el fondo intenso de Grabar, con la estética Verbapp (docs/diseno/PROPUESTA-0.7.md):
 * 1. Bienvenida: el logo en grande, el lema y tres beneficios en tarjetas de vidrio. Bajo el lema, el idioma de la app
 *    (0.9.0): parte en el del teléfono (o en inglés) y se cambia ahí mismo, sin perder el paso ni lo escrito.
 * 2. Tú: tu nombre (opcional, para el saludo y para tu voz), el permiso del micrófono y, debajo, una fila más liviana
 *    «Con la pantalla bloqueada» (permiso de batería y la guía del fabricante; SPEC-0.8c, decisión 4).
 * 3. Conecta tu IA: solo OpenRouter (0.8.0, segunda ronda: docs/diseno/SPEC-0.8b.md). Antes de pedir la clave se dice en
 *    claro qué sale del teléfono, a quién y quién paga; después, el campo para pegarla. La clave se guarda recién cuando
 *    la persona aceptó el aviso de envío (Consent, 0.9.0): guardarla deja lista la primera subida de audio.
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
    static final String KEYS_OPENROUTER="https://openrouter.ai/keys";
    /** Una clave real mide bastante más (las de OpenRouter, unas 70 letras): menos que esto es un pegado a medias. */
    static final int MIN_KEY=20;
    /**
     * La explicación honesta del paso 3, antes de pedir la clave: qué sale del teléfono, a quién y quién paga. Se graba sin
     * internet, pero transcribir y armar la nota NO pasan en el teléfono (SPEC-0.8b: «Todo queda en tu teléfono» era falso).
     * Los textos son métodos y no constantes: una constante quedaría en el idioma de cuando se cargó la clase.
     */
    static String sent(){return Lang.str(R.string.onb_sent);}
    /** Lo que se dice de grabar en la bienvenida: verdadero también después de transcribir (lo grabado sigue en el teléfono). */
    static String kept(){return Lang.str(R.string.onb_kept);}
    /** Lo que se dice cuando no se pudo preguntar al proveedor (sin red, servicio caído): la clave igual quedó guardada. */
    static String unchecked(){return Lang.str(R.string.onb_unchecked);}

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
     * Por qué esta clave no se puede guardar como clave de OpenRouter, en palabras para la persona; null si se puede.
     * Se rechaza lo seguro: vacía, con espacios, cortada, o de otro servicio cuando su comienzo lo dice (ver foreign).
     * Un comienzo desconocido se acepta: los formatos cambian y la comprobación real la hace OpenRouter. El mensaje nunca
     * repite la clave.
     */
    static String keyProblem(String raw){
        String key=raw==null?"":raw.trim();
        if(key.isEmpty())return Lang.str(R.string.onb_key_empty);
        if(key.matches("(?s).*\\s.*")||key.length()>8192)return Lang.str(R.string.onb_key_spaces);
        if(key.length()<MIN_KEY)return Lang.str(R.string.onb_key_short);
        return foreign(guessProvider(key));
    }
    /**
     * Una clave que por su comienzo es de otro servicio (sk-ant- de Anthropic; sk- sin «or-», de OpenAI u otro) no se
     * acepta: además de no servir en OpenRouter, guardarla mandaría a openrouter.ai la clave de otra cuenta al comprobarla.
     * Antes (con la tarjeta de OpenAI) se elegía esa tarjeta; ahora solo hay OpenRouter. null si no es de otro servicio.
     */
    static String foreign(String guess){
        if("anthropic".equals(guess))return Lang.str(R.string.onb_key_anthropic);
        if(OPENAI.equals(guess))return Lang.str(R.string.onb_key_openai);
        return null;
    }
    /**
     * Guarda la clave de OpenRouter y deja OpenRouter como servicio de transcripción. Devuelve false, sin tocar nada, si no
     * se escribió nada: así un repaso de la bienvenida no pisa la clave que ya existe.
     * La nota ya no mira la preferencia "noteProvider": Notes.provider() es siempre OpenRouter (SPEC-0.8b, decisión 3) y la
     * migración de VozApp la borra. Por eso aquí no se escribe ni se borra (pedido de la parte settings en la segunda
     * ronda). Las claves viejas de otros servicios y el modelo de la nota quedan como estaban (Notes descarta solo un
     * modelo que no es de OpenRouter), así nada se borra sin aviso.
     */
    static boolean applyKey(Settings s,String raw)throws Exception{
        String key=raw==null?"":raw.trim();if(key.isEmpty())return false;
        String problem=keyProblem(key);if(problem!=null)throw new IllegalArgumentException(problem);
        s.saveKeyFor(OPENROUTER,key);
        s.prefs.edit().putString("provider",OPENROUTER).apply();return true;
    }
    /** Lo que hace «Continuar» en el paso de la clave: seguir sin clave, explicar qué falta, pedir el aviso de envío o guardar. */
    static final int KEY_SKIP=0,KEY_ERROR=1,KEY_CONSENT=2,KEY_SAVE=3;
    /**
     * raw: lo escrito; skippable: se puede seguir sin escribir nada (repaso, o ya hay una clave guardada); consent: ya aceptó
     * que su audio se envíe (Consent.given). Una clave que sirve sin el aviso aceptado no se guarda (Google Play pide la
     * aceptación antes del primer envío, y con la clave guardada Pipeline.schedule puede empezar a transcribir lo que
     * esperaba en cola). Una clave que no sirve ni siquiera pide el aviso: primero se corrige.
     */
    static int keyAction(String raw,boolean skippable,boolean consent){
        String key=raw==null?"":raw.trim();
        if(key.isEmpty())return skippable?KEY_SKIP:KEY_ERROR;
        if(keyProblem(key)!=null)return KEY_ERROR;
        return consent?KEY_SAVE:KEY_CONSENT;
    }
    /**
     * «Clave válida · quedan US$4,20» (el saldo solo si se supo). La etiqueta de la clave no se muestra. Lo que va después
     * de «Clave válida» es SettingsActivity.balanceText, el mismo texto de Ajustes → «Comprobar conexión»: la misma clave
     * dice lo mismo en los dos lugares (también «aún sin créditos», que manda sobre el tope de la clave).
     */
    static String validText(double balance,boolean noCredits){
        String what=SettingsActivity.balanceText(balance,noCredits);return what==null?Lang.str(R.string.onb_key_valid):Lang.str(R.string.onb_key_valid_with,what);
    }
    /**
     * Lo que se supo de una clave válida: el texto para la persona y lo que queda en Ajustes. balance: el saldo que se
     * guarda (NaN si no se supo, o si la cuenta aún no carga créditos: el tope de la clave no es plata disponible y
     * «Tus métricas» lo mostraría como saldo). free: la cuenta aún no carga créditos (Models.Balance.noCredits), lo que
     * SettingsActivity.saveVerify guarda como verifyFree. empty: válida pero así no transcribe (sin saldo o sin créditos).
     */
    static final class Valid{
        final String text;final double balance;final boolean free,empty;
        Valid(String text,double balance,boolean free,boolean empty){this.text=text;this.balance=balance;this.free=free;this.empty=empty;}
    }
    /**
     * De lo que respondió OpenRouter a lo que se muestra y se guarda. La regla del saldo es UNA, Models.balance (la misma
     * de Ajustes): el menor entre lo que le queda a la clave y a la cuenta, y «sin créditos» si la cuenta es gratuita y
     * no informa saldo.
     */
    static Valid valid(Models.Balance b){
        boolean none=b!=null&&b.noCredits;double left=b==null||none||Double.isInfinite(b.left)?Double.NaN:b.left;
        return validOf(left,none);
    }
    /** Lo mismo desde el saldo y «sin créditos» ya sabidos (lo que guardan las preferencias verify*). */
    static Valid validOf(double left,boolean none){
        return new Valid(validText(left,none),left,none,none||(!Double.isNaN(left)&&left<=SettingsActivity.NO_BALANCE));
    }
    /** Lo mismo desde GET /key y el saldo de la cuenta (GET /credits; NaN si no se supo). */
    static Valid valid(Models.KeyInfo info,double account){return valid(Models.balance(info,account));}
    /** El motivo del rechazo tal como lo dice el cliente, sin mandar a Ajustes: aquí la clave se corrige en el paso anterior. */
    static String rejected(String message){String m=StatusText.withoutSettingsHint(message);return m.isEmpty()?Lang.str(R.string.onb_key_failed):m;}
    /** Primer nombre para saludar («Konrad Peschka» → «Konrad»); vacío si no hay nombre o es el «Yo» por defecto. */
    static String firstName(String raw){String n=Voices.clean(raw);if(n.isEmpty()||Voices.isDefaultMeName(n))return "";int cut=n.indexOf(' ');return cut>0?n.substring(0,cut):n;}
    static String readyTitle(String name){String first=firstName(name);return first.isEmpty()?Lang.str(R.string.onb_ready_title):Lang.str(R.string.onb_ready_title_name,first);}

    /** Resultado de comprobar una clave. valid: lo que se supo de ella si es válida (null si no). */
    static final class Verdict{
        final int state;final String message;final Valid valid;
        Verdict(int state,String message,Valid valid){this.state=state;this.message=message;this.valid=valid;}
        /** Válida, pero sin saldo o sin créditos: no se marca como lista (con ✓) porque así no transcribe. */
        boolean empty(){return valid!=null&&valid.empty;}
    }
    /** Pregunta por la clave: devuelve lo que se supo de ella o lanza (HttpApi.UserAction si OpenRouter la rechazó). */
    interface Checker{Valid check(HttpApi http,String key)throws Exception;}
    /**
     * La comprobación de OpenRouter, la misma de Ajustes → «Comprobar conexión»: GET /key dice si vale y cuánto le queda a
     * la clave; Models.balance pide el saldo de la cuenta (GET /credits) sin exigirlo y junta los dos. Antes solo se
     * miraba la clave, y la bienvenida podía decir «quedan US$5,00» (el tope de la clave) en una cuenta sin saldo.
     */
    static Checker checker(){return (http,key)->{Models.KeyInfo info=Models.checkKey(http,key);return valid(Models.balance(http,key,info));};}
    /**
     * Comprueba sin bloquear nunca: válida, rechazada con el mensaje del proveedor (HttpApi.UserAction) o, ante cualquier
     * otra cosa (sin red, servicio caído), «no se pudo comprobar ahora». No lanza.
     */
    static Verdict verify(Checker checker,HttpApi http,String key){
        try{Valid v=checker.check(http,key);return new Verdict(Check.VALID,v==null?Lang.str(R.string.onb_key_valid):v.text,v);}
        catch(HttpApi.UserAction e){return new Verdict(Check.REJECTED,rejected(e.getMessage()),null);}
        catch(Exception e){return new Verdict(Check.UNKNOWN,unchecked(),null);}
    }
    /**
     * La última comprobación guardada (preferencias verify*, las de Ajustes) si es de esta clave (target: la huella de
     * SettingsActivity.verifyTarget); null si no hay. La usa «Listo» cuando no tiene la suya en memoria: Kept sobrevive a
     * un giro, pero no a que Android cierre la app mientras cargas créditos en el navegador. Sin esto, al volver la fila
     * decía «lista para transcribir» con ✓ aunque la clave estuviera rechazada o sin saldo (y Ajustes dijera eso).
     * Un fallo guardado solo es rechazo si habla de la clave (la regla con que Ajustes ofrece «Revisar la clave»): «No se
     * pudo conectar…» o «OpenRouter no está disponible temporalmente (503).» no dicen nada de ella y quedan como UNKNOWN,
     * igual que verify() sin red.
     */
    static Verdict saved(SharedPreferences prefs,String target){
        if(prefs.getLong("verifyAt",0)<=0||target==null||!target.equals(prefs.getString("verifyFor","")))return null;
        String why=prefs.getString("verifyMsg","");
        if(!prefs.getBoolean("verifyOk",false))return StatusText.aboutKey(why)?new Verdict(Check.REJECTED,rejected(why),null):new Verdict(Check.UNKNOWN,unchecked(),null);
        double left;try{left=Double.parseDouble(prefs.getString("verifyBalance",""));}catch(NumberFormatException e){left=Double.NaN;}
        Valid v=validOf(left,prefs.getBoolean("verifyFree",false));return new Verdict(Check.VALID,v.text,v);
    }
    /** Comprobación de la clave recién guardada. Vive fuera de la pantalla para seguir tras un giro; no guarda la clave. */
    static final class Check{
        static final int CHECKING=0,VALID=1,REJECTED=2,UNKNOWN=3;
        final HttpApi http=new HttpApi();int state=CHECKING;String message="";boolean empty;OnboardingActivity screen;
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
    /**
     * Permiso de batería: askedBattery = se abrió el diálogo de Android y falta ver qué respondió (se mira al volver, en
     * onResume); guideShown = la guía del fabricante ya se mostró sola una vez (después, solo si se toca la fila).
     */
    private boolean askedBattery,guideShown;
    /** Se eligió otro idioma: la pantalla se rehace en él (Lang.set) y, al volver, el lector de pantalla lee el selector. */
    private boolean languageChanged;
    private int step,keyTone;private long switchedAt,consentAskedAt;private String name="";
    // Marco fijo: barra de arriba y zona de abajo
    private ImageButton back;private Steps dots;private Ui.Btn skip,next;private TextView later;private LinearLayout stage;
    // Vistas del paso que está a la vista (null en los demás)
    private TextView heading,micDetail,keyStatus,languageChip;private EditText nameInput,keyInput;private Emblem micArt,keyArt;private Ui.Btn allow;
    private LinearLayout nameCard,keyCard,readyList,batteryCard;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new Settings(this);replay=getIntent().getBooleanExtra("replay",false);
        Object last=getLastNonConfigurationInstance();kept=last instanceof Kept?(Kept)last:new Kept();if(kept.check!=null)kept.check.screen=this;
        if(state!=null){step=Math.max(0,Math.min(STEPS-1,state.getInt("ob_step")));name=state.getString("ob_name","");askedMic=state.getBoolean("ob_asked");askedBattery=state.getBoolean("ob_battery");guideShown=state.getBoolean("ob_guide");}
        else{
            String saved=settings.prefs.getString("myVoiceName","").trim();name=Voices.isDefaultMeName(saved)?"":saved;
            Diagnostics.event("ui_action",null,"screen","Onboarding","action",replay?"replay":"start");
        }
        shell(null,-1,true);
        // El teclado nunca se abre solo al entrar a un paso: solo cuando tocas un campo.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildFrame();show(step,0,false);
        if(state==null)enter(stage);
        // Recién cambiado el idioma: el paso, el nombre y la clave a medio escribir siguen (estado guardado y Kept). El lector
        // de pantalla vuelve al selector, que dice el idioma nuevo, en vez de quedar perdido en la pantalla rehecha.
        else if(state.getBoolean("ob_lang"))main.postDelayed(()->{TextView l=languageChip;if(l!=null&&l.isAttachedToWindow())l.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);},600);
    }
    @Override protected void onResume(){super.onResume();renderMic(false);batteryReturned();renderReady();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("ob_step",step);out.putString("ob_name",name);out.putBoolean("ob_asked",askedMic);out.putBoolean("ob_battery",askedBattery);out.putBoolean("ob_guide",guideShown);out.putBoolean("ob_lang",languageChanged);super.onSaveInstanceState(out);}
    @Override public Object onRetainNonConfigurationInstance(){return kept;}
    @Override protected void onDestroy(){
        main.removeCallbacksAndMessages(null);Check c=kept.check;
        // La comprobación NO se corta al salir: quien toca «Hacer mi primera grabación» medio segundo después de Continuar
        // igual debe ver en Ajustes si su clave vale (o que fue rechazada). Termina sola (30 s como mucho por consulta) y
        // no retiene esta pantalla: solo escribe en las preferencias, y sin pantalla a quién avisar no muestra nada.
        if(c!=null&&c.screen==this)c.screen=null;
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
        back=ui.glassButton(R.drawable.ic_arrow_back,getString(R.string.onb_back_step));back.setOnClickListener(v->onBackPressed());
        top.addView(back,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));
        dots=new Steps(this,p,STEPS);top.addView(dots,new FrameLayout.LayoutParams(-2,dp(48),Gravity.CENTER));
        skip=ui.button(getString(replay?R.string.onb_close:R.string.onb_skip),0,Ui.Style.PLAIN,v->finishFlow("skip"));skip.setMinimumHeight(dp(48));skip.setContentDescription(getString(replay?R.string.onb_close_desc:R.string.onb_skip_desc));
        top.addView(skip,new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL));
        root.addView(top,0,Ui.fill());
        // Las ondas de la ilustración llegan hasta el borde de la pantalla: nada se corta en los márgenes.
        page.setPadding(dp(S4),0,dp(S4),dp(S2));page.setClipToPadding(false);page.setClipChildren(false);
        // Abajo, fijo: el botón de tinta siempre en el mismo lugar. «Ahora no» (solo en el paso de la clave) va ENCIMA, así
        // no lo mueve, y es una píldora de vidrio: se lee tanto sobre el verde de abajo como sobre el blanco.
        bottom.setVisibility(View.VISIBLE);
        later=ui.text(getString(R.string.onb_later),Type.LABEL_LARGE,p.onSurface);later.setGravity(Gravity.CENTER);int inset=dp(S1);
        later.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(outline(this,p.glass,p.outlineVariant,R_FULL,false),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,inset,0,inset)));
        // El relleno va después del fondo: el InsetDrawable trae el suyo y setBackground lo reemplazaría.
        later.setPadding(dp(S5),0,dp(S5),0);later.setMinHeight(dp(48));later.setMinimumHeight(dp(48));
        later.setClickable(true);later.setFocusable(true);later.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(later);
        later.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","Onboarding","action","later");go(READY);});
        LinearLayout.LayoutParams ll=Ui.wrap();ll.gravity=Gravity.CENTER_HORIZONTAL;ll.bottomMargin=dp(S1);bottom.addView(later,ll);
        next=ui.button(getString(R.string.onb_start),0,Ui.Style.PRIMARY,v->advance());bottom.addView(next,Ui.fill());
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::checkIme);
    }
    /** Lo que cambia con el paso en el marco: ←, los puntitos y el verbo del botón principal. */
    private void frame(boolean animate){
        dots.setStep(step,animate);
        back.setVisibility(step>0||replay?View.VISIBLE:View.INVISIBLE);back.setContentDescription(getString(step>0?R.string.onb_back_step:R.string.onb_close_desc));
        boolean last=step==READY;
        next.setText(getString(step==WELCOME?R.string.onb_start:!last?R.string.onb_continue:replay?R.string.onb_done:R.string.onb_first_recording));next.setIcon(last&&!replay?R.drawable.ic_mic_fill:0);
        later.setVisibility(step==AI&&!imeShown?View.VISIBLE:View.GONE);
        // El lector de pantalla anuncia el cambio de paso con el título de la ventana.
        setTitle(getString(R.string.onb_window_title,getString(STEP_NAMES[step]),step+1,STEPS));
        secure();
    }
    private static final int[] STEP_NAMES={R.string.onb_step_welcome,R.string.onb_step_you,R.string.onb_step_ai,R.string.onb_step_ready};
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
        if(step==WELCOME)go(YOU);else if(step==YOU)go(AI);else if(step==AI)submitKey();else finishFlow("done");
    }
    private void go(int target){
        if(switching||target==step||target<0||target>=STEPS)return;
        // El nombre se guarda al salir de «Tú» hacia cualquier lado (también con ←): así no se pierde si después se salta.
        if(step==YOU)saveName();
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
        heading=null;micDetail=null;keyStatus=null;languageChip=null;nameInput=null;keyInput=null;micArt=null;keyArt=null;allow=null;nameCard=null;keyCard=null;readyList=null;batteryCard=null;
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
        // «Saltar» está siempre a mano: el nombre escrito en «Tú» se guarda igual (saveName no borra ni repite uno ya guardado).
        saveName();hideKeyboard();complete(this,replay);
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
    /** Tarjeta de vidrio que informa (no se toca): ícono en círculo menta + título + apoyo. Para el lector es una sola frase. */
    private LinearLayout glassLead(int icon,String title,String detail){
        LinearLayout card=lead(icon,title,ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant));
        card.setBackground(glass(this,p,R_CARD));card.setPadding(dp(S3),dp(S3),dp(S4),dp(S3));
        card.setContentDescription(title+". "+detail);card.setFocusable(true);card.getChildAt(2).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return card;
    }

    // ---------- 1. Bienvenida ----------
    private LinearLayout buildWelcome(){
        LinearLayout c=column();
        c.addView(hero(new Emblem(this,p,Emblem.LOGO)));
        c.addView(overline(getString(R.string.onb_welcome_overline)),Ui.fill());
        // El lema va en dos líneas; el lector lo lee como una sola frase.
        String motto=getString(R.string.onb_welcome_title);heading=title(motto,motto.replace('\n',' '));c.addView(heading,Ui.fill());
        languageChip=languageButton();LinearLayout.LayoutParams lp=Ui.wrap();lp.gravity=Gravity.CENTER_HORIZONTAL;lp.topMargin=dp(S2);c.addView(languageChip,lp);
        c.addView(gap());
        // Lo que se promete tiene que ser cierto: se graba sin internet y lo grabado queda en el teléfono, pero para
        // transcribir el audio sale (paso 3 lo explica). Cada apoyo cabe en una línea, así las tres tarjetas miden igual.
        int[] icons={R.drawable.ic_mic,R.drawable.ic_people,R.drawable.ic_note};
        int[][] rows={{R.string.onb_welcome_record,R.string.onb_kept},{R.string.onb_welcome_voices,R.string.onb_welcome_voices_detail},{R.string.onb_welcome_note,R.string.onb_welcome_note_detail}};
        for(int i=0;i<rows.length;i++)c.addView(glassLead(icons[i],getString(rows[i][0]),getString(rows[i][1])),ui.top(i==0?0:S2));
        return c;
    }
    /**
     * Idioma de la app (0.9.0): píldora de vidrio bajo el lema con el globo, el idioma vigente y ▾ («English ▾»): el del
     * teléfono si la app lo tiene; si no, inglés. Se toca en 48 dp aunque se vea de 40 (como «Ahora no» del paso 3).
     */
    private TextView languageButton(){
        String now=getString(languageName(Lang.current(this)));
        TextView t=ui.text(now,Type.LABEL_LARGE,p.onSurface);t.setGravity(Gravity.CENTER_VERTICAL);int inset=dp(S1);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(outline(this,p.glass,p.outlineVariant,R_FULL,false),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,inset,0,inset)));
        t.setPadding(dp(S3),0,dp(S3),0);t.setMinHeight(dp(48));t.setMinimumHeight(dp(48));
        Drawable globe=getDrawable(R.drawable.ic_globe).mutate();globe.setTint(p.primary);globe.setBounds(0,0,dp(18),dp(18));
        Drawable more=getDrawable(R.drawable.ic_chevron_down).mutate();more.setTint(p.onSurfaceVariant);more.setBounds(0,0,dp(18),dp(18));
        t.setCompoundDrawablesRelative(globe,null,more,null);t.setCompoundDrawablePadding(dp(6));
        t.setClickable(true);t.setFocusable(true);t.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(t);
        t.setContentDescription(getString(R.string.onb_language_desc,now));t.setOnClickListener(v->languageSheet());
        return t;
    }
    /** El nombre de cada idioma en su propio idioma («English», «Español», «Português (Brasil)»): cada persona encuentra el suyo. */
    static int languageName(String lang){return Lang.ES.equals(lang)?R.string.lang_es:Lang.PT.equals(lang)?R.string.lang_pt:R.string.lang_en;}
    /** Los tres idiomas, con el vigente marcado (radio y «seleccionado» para el lector). Elegir el mismo no hace nada. */
    private void languageSheet(){
        if(switching)return;String now=Lang.current(this);
        Sheet s=sheet(getString(R.string.common_language),getString(R.string.common_language_hint));
        for(String lang:Lang.SUPPORTED)s.choice(getString(languageName(lang)),null,lang.equals(now),()->pickLanguage(lang));
        s.show();
    }
    /**
     * Guarda el idioma y rehace la pantalla en él (Lang.set: Android 13+ lo hace solo; antes, recreate). El paso, el nombre
     * y la clave a medio escribir siguen: van en el estado guardado y en Kept, igual que en un giro.
     */
    private void pickLanguage(String lang){
        if(isFinishing()||lang.equals(Lang.current(this)))return;
        Diagnostics.event("setting_changed",null,"action","app_language","source","onboarding","result",lang);
        languageChanged=true;Ui.haptic(root,Ui.Haptic.CONFIRM);Lang.set(this,lang);
    }

    // ---------- 2. Tú ----------
    private LinearLayout buildYou(){
        LinearLayout c=column();
        micArt=new Emblem(this,p,Emblem.MIC);c.addView(hero(micArt));
        c.addView(overline(getString(R.string.onb_you_overline)),Ui.fill());
        heading=title(getString(R.string.onb_you_title),null);c.addView(heading,Ui.fill());
        // El nombre: opcional. La tarjeta va a 8 dp del campo (esquinas de 16 dentro de 24: concéntricas).
        nameCard=ui.card();nameCard.setPadding(dp(S2),dp(S2),dp(S2),dp(S3));
        nameInput=ui.field(getString(R.string.onb_name_hint),getString(R.string.onb_name_desc));nameInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PERSON_NAME|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);nameInput.setSaveEnabled(false);nameInput.setText(name);
        nameInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int n){}public void onTextChanged(CharSequence s,int a,int b,int n){name=s.toString();}public void afterTextChanged(Editable e){}});
        nameInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){hideKeyboard();return true;}return false;});
        nameCard.addView(nameInput,Ui.fill());
        TextView why=ui.text(getString(R.string.onb_name_why),Type.BODY_MEDIUM,p.onSurfaceVariant);why.setPadding(dp(S2),dp(S2),dp(S2),0);nameCard.addView(why,Ui.fill());
        c.addView(nameCard,ui.top(S4));
        c.addView(gap());
        // El micrófono: se puede seguir sin permitirlo (Grabar lo vuelve a pedir al primer toque).
        LinearLayout mic=ui.card();mic.setPadding(dp(S2),dp(S2),dp(S2),dp(S2));
        micDetail=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);micDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout head=lead(R.drawable.ic_mic,getString(R.string.onb_mic),micDetail);head.setPadding(dp(S1),dp(S1),dp(S2),dp(S3));mic.addView(head,Ui.fill());
        allow=ui.button(getString(R.string.onb_mic_allow),R.drawable.ic_mic_fill,Ui.Style.RECORD,v->askMic());mic.addView(allow,Ui.fill());
        c.addView(mic,Ui.fill());
        // Debajo, «Con la pantalla bloqueada» (SPEC-0.8c, decisión 4): una instalación nueva queda con la batería optimizada
        // y Android pausa la transcripción con la app cerrada (diagnóstico del 2026-10-01). Va como una fila de vidrio que
        // se toca, sin otro botón grande: el micrófono sigue siendo lo principal del paso y esto se puede saltar sin más.
        batteryCard=ui.group();c.addView(batteryCard,ui.top(S2));
        renderMic(false);batteryShown="";renderBattery(false);
        return c;
    }
    private boolean micGranted(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    /** Estado real del permiso: verde «Permitir micrófono» mientras falta; menta con ✓ (lo logrado) cuando ya está, y el ✓ también en la ilustración. */
    private void renderMic(boolean celebrate){
        if(allow==null||micDetail==null)return;boolean ok=micGranted();
        allow.setText(getString(ok?R.string.onb_mic_allowed:R.string.onb_mic_allow));allow.setIcon(ok?R.drawable.ic_check:R.drawable.ic_mic_fill);
        allow.setColors(ok?p.onPrimaryContainer:p.onBrand,ok?p.primaryContainer:p.brand);allow.setClickable(!ok);
        // Ya concedido deja de ser un botón: sin esto, el gesto de «hundirse» al tocar quedaría a medias (no llega el soltar).
        if(ok){allow.setOnTouchListener(null);allow.animate().cancel();allow.setScaleX(1f);allow.setScaleY(1f);}
        // Nada de «el audio queda en tu teléfono» a secas: para transcribir sale (lo explica el paso siguiente).
        String text=getString(ok?R.string.onb_mic_ready:Build.VERSION.SDK_INT>=33?R.string.onb_mic_use_notify:R.string.onb_mic_use_offline);
        if(!text.contentEquals(micDetail.getText()))micDetail.setText(text);
        if(micArt!=null)micArt.setBadge(ok,celebrate);
        if(celebrate&&ok)Ui.haptic(allow,Ui.Haptic.CONFIRM);
    }
    /** Pide el micrófono y, en Android 13+, los avisos (la notificación que muestra que la grabación sigue), en un solo gesto. */
    private void askMic(){
        if(micGranted()){renderMic(false);return;}
        // Ya se pidió y Android no volverá a preguntar (se negó dos veces): la salida son los ajustes del sistema.
        if(askedMic&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
            sheet(getString(R.string.onb_mic_off_title),getString(R.string.onb_mic_off_body))
                .primary(getString(R.string.onb_open_settings),()->{try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(RuntimeException e){toast(getString(R.string.onb_settings_failed));}})
                .secondary(getString(R.string.onb_not_now),null).show();return;
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
    /** Lo que muestra la fila de batería ahora («permitido|guía»): no se rearma si no cambió (sin parpadeo al volver). */
    private String batteryShown="";
    /**
     * Fila «Con la pantalla bloqueada» con su estado real, como las filas del último paso: por hacer (flecha; abre el
     * diálogo de Android) o hecho (✓). El ✓ es por lo único que la app puede comprobar, la optimización de Android. En
     * marcas con un ahorro propio (vivo, Xiaomi, OPPO…), que ninguna app puede leer, el ✓ va con «hay un ajuste más» y la
     * fila abre la guía del fabricante. celebrate: recién permitido (el ✓ entra con un resorte y vibra).
     */
    private void renderBattery(boolean celebrate){
        if(batteryCard==null)return;
        boolean ok=Battery.unrestricted(this),extra=ok&&Battery.makerSteps().length>0;String key=ok+"|"+extra;
        if(key.equals(batteryShown)&&!celebrate)return;batteryShown=key;batteryCard.removeAllViews();
        String detail;View.OnClickListener click;
        if(!ok){detail=getString(R.string.onb_battery_ask);click=v->askBattery();}
        else if(extra){detail=getString(R.string.onb_battery_extra,Battery.makerName());click=v->makerGuide();}
        else{detail=getString(R.string.onb_battery_done);click=null;}
        LinearLayout row=stateRow(R.drawable.ic_battery,getString(R.string.onb_battery_title),detail,ok?ROW_DONE:ROW_GO,click);
        // Alineada con la tarjeta del micrófono de arriba: el círculo del ícono queda a 12 dp del borde, igual que allá.
        row.setPaddingRelative(dp(S3),dp(S3),dp(S3),dp(S3));batteryCard.addView(row,Ui.fill());
        // Para el lector de pantalla, lo pendiente dice primero qué hace el toque (la flecha no se anuncia).
        if(!ok)row.setContentDescription(getString(R.string.onb_battery_allow_desc,detail));
        if(celebrate&&ok){
            // La marca ✓ es lo último de la fila: aparece con un resorte corto (tamaño: puede rebotar), como la insignia de arriba.
            View mark=row.getChildAt(row.getChildCount()-1);
            if(AppTheme.motion()){mark.setScaleX(0.4f);mark.setScaleY(0.4f);mark.animate().scaleX(1f).scaleY(1f).setDuration(SPATIAL_FAST.duration).setInterpolator(SPATIAL_FAST).start();}
            Ui.haptic(row,Ui.Haptic.CONFIRM);
        }
    }
    /** Abre el diálogo de Android «¿Permitir que Verbapp se ejecute en segundo plano?» (o la ficha de la app, si no hay diálogo). */
    private void askBattery(){
        if(Battery.unrestricted(this)){renderBattery(false);return;}
        askedBattery=true;Diagnostics.event("ui_action",null,"screen","Onboarding","action","battery_request");
        Battery.request(this);
    }
    /**
     * Al volver (onResume): si se había abierto el diálogo de batería, se registra qué pasó y, si se permitió, se celebra
     * y, en marcas con un ahorro propio, la guía del fabricante aparece sola UNA vez: sin ese ajuste, vivo igual pausa
     * Verbapp (el caso del diagnóstico). Si no se permitió no se insiste: la fila sigue ahí con su flecha.
     */
    private void batteryReturned(){
        if(!askedBattery){renderBattery(false);return;}
        askedBattery=false;boolean ok=Battery.unrestricted(this);
        Diagnostics.event("ui_action",null,"screen","Onboarding","action","battery","result",ok);
        renderBattery(ok);
        if(!ok||batteryCard==null)return;
        batteryCard.announceForAccessibility(getString(R.string.onb_battery_allowed));
        // Primero se ve el ✓ y después sube la guía (con «Quitar animaciones», de inmediato).
        if(!guideShown&&Battery.makerSteps().length>0)main.postDelayed(()->{if(!isFinishing()&&!isDestroyed()&&step==YOU)makerGuide();},AppTheme.motion()?MOTION_SLOW:0);
    }
    /**
     * Guía del ahorro propio del fabricante: por qué hace falta, los pasos en palabras simples y el botón que abre la ficha
     * de Verbapp en Ajustes de Android. La hoja queda abierta al tocar ese botón: al volver, los pasos siguen a la vista.
     * La app no puede saber si se hizo, así que no marca nada: solo explica.
     */
    private void makerGuide(){
        String[] steps=Battery.makerSteps();if(steps.length==0)return;guideShown=true;String maker=Battery.makerName();
        Diagnostics.event("ui_action",null,"screen","Onboarding","action","battery_guide");
        Sheet s=sheet(getString(R.string.bat_guide_title,maker),getString(R.string.bat_guide_body,maker));
        SheetParts.hero(s,R.drawable.ic_battery,false);numbered(s,steps);
        TextView tip=ui.text(getString(R.string.bat_search_tip),Type.BODY_MEDIUM,p.onSurfaceVariant);tip.setPadding(dp(S1),dp(S3),0,0);s.add(tip);
        // El botón ya queda en el diagnóstico por su rótulo (Ui.Btn); devolver false deja la hoja abierta.
        s.primary(Battery.openSettings(),Ui.Style.PRIMARY,()->{Battery.appSettings(this);return false;}).secondary(getString(R.string.onb_done),null).show();
    }
    /** Pasos numerados en una hoja: círculo menta con el número y el texto al lado (como «Cómo conseguir tu clave»). */
    private void numbered(Sheet s,String[] steps){
        for(int i=0;i<steps.length;i++){
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(dp(S1),dp(S2),0,dp(S2));
            TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onPrimaryContainer);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.primaryContainer));n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.addView(n,new LinearLayout.LayoutParams(dp(24),dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);
        }
    }

    /** Solo si escribió algo distinto: un campo vacío no borra el nombre que ya hubiera. */
    private void saveName(){
        String n=Voices.clean(name);name=n;
        if(n.isEmpty()||n.equals(settings.prefs.getString("myVoiceName","")))return;
        try{Voices.setName(this,n);}catch(RuntimeException e){settings.prefs.edit().putString("myVoiceName",n).apply();}
    }

    // ---------- 3. Conecta tu IA ----------
    /**
     * Solo OpenRouter (SPEC-0.8b): ya no se elige proveedor. Sin las dos tarjetas de elegir, el paso toma el mismo ritmo de
     * los otros tres: ilustración, lema y título arriba; las tarjetas abajo, cerca del pulgar y del botón. Primero lo
     * honesto (qué sale del teléfono, a quién y quién paga) y después la clave. La ilustración es el «centro que reparte»
     * de OpenRouter (una clave, muchos modelos), con ✓ si ya hay una clave guardada, como el micrófono del paso anterior.
     */
    private LinearLayout buildAi(){
        LinearLayout c=column();
        keyArt=new Emblem(this,p,Emblem.HUB);renderKeyArt();c.addView(hero(keyArt));
        c.addView(overline(getString(R.string.onb_ai_overline)),Ui.fill());
        heading=title(getString(R.string.onb_ai_title),null);c.addView(heading,Ui.fill());
        c.addView(gap());
        c.addView(glassLead(R.drawable.ic_upload,getString(R.string.onb_ai_how),getString(R.string.onb_sent)),Ui.fill());
        // La clave: rótulo, campo con «Pegar» adentro, el estado en palabras y cómo conseguirla.
        keyCard=ui.card();keyCard.setPadding(dp(S2),dp(S3),dp(S2),dp(S1));
        LinearLayout label=ui.row();label.setPadding(dp(S2),0,dp(S2),dp(S2));label.addView(ui.icon(R.drawable.ic_key,p.primary,18));label.addView(ui.space(S2));
        label.addView(ui.text(getString(R.string.onb_ai_key_label),Type.TITLE_SMALL,p.onSurface));keyCard.addView(label,Ui.fill());
        LinearLayout box=ui.row();box.setBackground(ui.fieldBackground());box.setPadding(dp(S4),0,dp(S1),0);box.setMinimumHeight(dp(56));
        // Campo oculto, sin autocompletar y sin guardarse en el estado de la pantalla (igual que la hoja de la clave en Ajustes).
        // «sk-or-…» es la forma de la clave, igual en todos los idiomas.
        keyInput=ui.field("sk-or-…",getString(R.string.onb_ai_key_label));keyInput.setBackground(null);keyInput.setPadding(0,dp(S3),dp(S2),dp(S3));
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSaveEnabled(false);keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyInput.setText(kept.key);
        keyInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int n){}public void onTextChanged(CharSequence s,int a,int b,int n){keyChanged(s.toString());}public void afterTextChanged(Editable e){}});
        keyInput.setOnFocusChangeListener((v,focused)->secure());
        keyInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){advance();return true;}return false;});
        box.addView(keyInput,new LinearLayout.LayoutParams(0,-2,1));
        TextView paste=ui.text(getString(R.string.onb_paste),Type.LABEL_LARGE,p.onPrimaryContainer);paste.setGravity(Gravity.CENTER);int in=dp(6);
        paste.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onPrimaryContainer)),new InsetDrawable(shape(this,p.primaryContainer,R_FULL),0,in,0,in),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,in,0,in)));
        paste.setPadding(dp(S3),0,dp(S4),0);paste.setMinHeight(dp(48));paste.setMinimumHeight(dp(48));
        Drawable clip=getDrawable(R.drawable.onboarding_paste).mutate();clip.setTint(p.onPrimaryContainer);clip.setBounds(0,0,dp(18),dp(18));paste.setCompoundDrawablesRelative(clip,null,null,null);paste.setCompoundDrawablePadding(dp(6));
        paste.setClickable(true);paste.setFocusable(true);paste.setAccessibilityDelegate(Ui.buttonRole());paste.setContentDescription(getString(R.string.onb_paste_desc));paste.setOnClickListener(v->paste());Ui.pressable(paste);
        box.addView(paste);keyCard.addView(box,Ui.fill());
        keyStatus=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);keyStatus.setPadding(dp(S2),dp(S2),dp(S2),0);keyStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);keyCard.addView(keyStatus,Ui.fill());
        Ui.Btn how=ui.button(getString(R.string.onb_how_get_key),R.drawable.ic_open_in_new,Ui.Style.PLAIN,v->howTo());how.setMinimumHeight(dp(48));how.setPadding(dp(S2),0,dp(S3),0);keyCard.addView(how,Ui.wrap());
        c.addView(keyCard,ui.top(S2));
        // Tras un giro con la clave a medio escribir, la línea de estado vuelve a decir lo que corresponde (p. ej. «es de otro servicio»).
        defaultNote();if(!kept.key.trim().isEmpty())keyChanged(kept.key);
        return c;
    }
    /** ✓ en la ilustración del paso 3 mientras haya una clave guardada que OpenRouter no rechazó (como el ✓ del micrófono). */
    private void renderKeyArt(){if(keyArt==null)return;Verdict v=rejection();keyArt.setBadge(settings.hasOpenRouterKey()&&v==null,false);}
    /**
     * El rechazo de la clave guardada, o null: el de la comprobación en memoria o, sin ella, el guardado (saved), el mismo
     * que usa «Listo». Así el paso 3 no muestra ✓ ni «Ya hay una clave guardada» de la clave a la que «Listo» manda a revisar
     * (al repasar la bienvenida, o si Android cerró la app tras el rechazo).
     */
    private Verdict rejection(){
        Check c=kept.check;
        if(c!=null)return c.state==Check.REJECTED?new Verdict(Check.REJECTED,c.message,null):null;
        Verdict v=settings.hasOpenRouterKey()?saved(settings.prefs,SettingsActivity.verifyTarget(settings)):null;
        return v!=null&&v.state==Check.REJECTED?v:null;
    }
    private void note(String text,int tone){
        if(keyStatus==null)return;keyTone=tone;if(!text.contentEquals(keyStatus.getText()))keyStatus.setText(text);
        keyStatus.setTextColor(tone==BAD?p.error:tone==OK?p.primary:p.onSurfaceVariant);
    }
    /** Lo que dice la línea bajo el campo cuando no hay nada que avisar: si la última clave falló, si ya hay una guardada, o cómo se guarda. */
    private void defaultNote(){
        Verdict v=rejection();
        if(v!=null)note(getString(R.string.onb_key_rejected_paste,v.message),BAD);
        else if(settings.hasOpenRouterKey())note(getString(R.string.onb_key_saved_already),OK);
        else note(getString(R.string.onb_key_encrypted),INFO);
    }
    /**
     * Cada cambio del campo: la clave queda solo en memoria. Si por su comienzo ya se sabe que es de otro servicio, se avisa
     * al tiro, antes de tocar Continuar. Se espera a 8 letras para no avisar mientras alguien escribe «sk-or-…» a mano.
     */
    private void keyChanged(String text){
        kept.key=text;secure();String t=text.trim();
        if(t.isEmpty()){defaultNote();return;}
        String bad=t.length()>=8?foreign(guessProvider(t)):null;
        if(bad!=null)note(bad,BAD);else if(keyTone==BAD)defaultNote();
    }
    /** «Pegar»: trae lo copiado solo si tiene forma de clave (el campo va oculto: no se vería qué se pegó). Lo copiado nunca se registra. */
    private void paste(){
        if(keyInput==null)return;String text="";
        try{android.content.ClipboardManager cm=getSystemService(android.content.ClipboardManager.class);ClipData clip=cm==null?null:cm.getPrimaryClip();
            if(clip!=null&&clip.getItemCount()>0){CharSequence t=clip.getItemAt(0).coerceToText(this);if(t!=null)text=t.toString().trim();}
        }catch(RuntimeException ignored){}
        if(text.isEmpty()){note(getString(R.string.onb_paste_empty),BAD);Ui.haptic(keyInput,Ui.Haptic.REJECT);return;}
        if(text.matches("(?s).*\\s.*")||text.length()<MIN_KEY||text.length()>8192){note(getString(R.string.onb_paste_not_key),BAD);Ui.haptic(keyInput,Ui.Haptic.REJECT);return;}
        keyInput.setText(text);keyInput.setSelection(keyInput.length());
        // Si es de otro servicio, keyChanged ya lo dijo en la línea de estado: aquí solo cambia la vibración.
        boolean bad=foreign(guessProvider(text))!=null;Ui.haptic(keyInput,bad?Ui.Haptic.REJECT:Ui.Haptic.CONFIRM);
        if(!bad)note(getString(R.string.onb_paste_ok),OK);
    }
    private void keyError(String text){note(text,BAD);Ui.haptic(next,Ui.Haptic.REJECT);scroll.post(()->reveal(keyCard));}
    /**
     * Continuar en el paso de la clave: se guarda, OpenRouter queda como servicio de transcripción y se comprueba en
     * segundo plano mientras ya se pasa a «Listo» (no bloquea: sin red igual se sigue). Sin nada escrito solo se avanza si
     * ya había una clave o es un repaso; la primera vez se explica cómo seguir sin clave.
     * 0.9.0: si aún no aceptó el aviso de envío, primero se muestra (Consent.ensure: qué se envía, a quién, la política de
     * privacidad y «Acepto»). Con «Ahora no» la clave no se guarda y queda en el campo; «Ahora no, solo grabar» sigue a mano.
     */
    private void submitKey(){
        String raw=kept.key.trim();
        int action=keyAction(raw,replay||settings.hasOpenRouterKey(),Consent.given(this));
        if(action==KEY_SKIP){go(READY);return;}
        if(action==KEY_ERROR){keyError(raw.isEmpty()?getString(R.string.onb_key_or_later,getString(R.string.onb_later)):keyProblem(raw));return;}
        if(action==KEY_CONSENT){
            // Un doble toque en Continuar abre una sola hoja.
            long now=SystemClock.elapsedRealtime();if(now-consentAskedAt<1000)return;consentAskedAt=now;
            hideKeyboard();Diagnostics.event("ui_action",null,"screen","Onboarding","action","upload_consent");
            Consent.ensure(this,()->{if(!isFinishing()&&!isDestroyed())saveKey(raw);});return;
        }
        saveKey(raw);
    }
    /** Guarda la clave ya revisada (y con el aviso aceptado), la comprueba en segundo plano y pasa a «Listo». */
    private void saveKey(String raw){
        try{applyKey(settings,raw);}
        catch(Exception e){Diagnostics.event("setting_changed",null,"action","api_key","source","onboarding","result","failed","error_class",e.getClass().getSimpleName());keyError(getString(R.string.onb_key_save_failed));return;}
        Diagnostics.event("setting_changed",null,"action","api_key","source","onboarding","provider",OPENROUTER);
        // La clave ya no se vuelve a mostrar: el campo queda vacío aunque se vuelva a este paso.
        kept.key="";if(keyInput!=null)keyInput.setText("");
        try{Pipeline.schedule(this,true);}catch(RuntimeException ignored){}
        // Con la clave guardada se trae la lista de modelos en segundo plano, para que «Automático» y los precios estén al
        // día desde la primera transcripción. La lista es pública: no viaja la clave.
        try{Models.refreshIfStale(this);}catch(RuntimeException ignored){}
        startCheck(raw);go(READY);
    }
    /**
     * Comprueba en segundo plano la clave recién guardada. El resultado queda también en Ajustes → «Comprobar conexión»
     * (las mismas preferencias verify*): una clave comprobada aquí aparece comprobada allá, con el MISMO saldo (el de la
     * clave y el de la cuenta, ver checker), y una rechazada aparece con su motivo. «No se pudo comprobar» (sin red) no se
     * guarda: no dice nada de la clave. Sigue aunque se cierre la bienvenida (ver onDestroy).
     */
    private void startCheck(String key){
        if(kept.check!=null)kept.check.http.cancel();
        Check c=new Check();kept.check=c;c.screen=this;
        // Son dos consultas cortas, como en Ajustes: una red trabada no deja «Comprobando tu clave…» girando por minutos.
        c.http.readTimeoutMs=30000;
        // Las preferencias de la app (no de esta pantalla): el hilo puede seguir después de que la bienvenida se cierre.
        Settings prefs=new Settings(getApplicationContext());String target=SettingsActivity.verifyTarget(prefs);Checker checker=checker();
        new Thread(()->{
            long began=SystemClock.elapsedRealtime();
            Verdict v=verify(checker,c.http,key);
            // cancelled: otra clave reemplazó a esta. Si la clave cambió por otro lado (Ajustes), target ya no calza.
            if(v.state!=Check.UNKNOWN&&!c.http.cancelled&&target.equals(SettingsActivity.verifyTarget(prefs))){
                // Lo mismo que guarda «Comprobar conexión»: el saldo de Models.balance y si la cuenta aún no carga créditos
                // (Balance.noCredits, que Ajustes muestra antes que cualquier monto). Sin créditos el saldo va «no sabido».
                Valid ok=v.valid;
                SettingsActivity.saveVerify(prefs,target,v.state==Check.VALID,SystemClock.elapsedRealtime()-began,v.message,ok==null?Double.NaN:ok.balance,ok!=null&&ok.free);
            }
            // El saldo es un dato de la cuenta: no se registra; solo si alcanza o no.
            Diagnostics.event("setting_changed",null,"action","verify","source","onboarding","provider",OPENROUTER,"result",v.state==Check.VALID?(v.empty()?"valid_no_balance":"valid"):v.state==Check.REJECTED?"rejected":"unknown");
            RESULTS.post(()->{c.state=v.state;c.message=v.message;c.empty=v.empty();OnboardingActivity s=c.screen;if(s!=null&&s.kept.check==c)s.checked();});
        },"Voz-bienvenida").start();
    }
    /** Llegó el resultado de la comprobación: se refleja donde esté la persona (la fila del último paso, o la línea del campo). */
    private void checked(){
        Check c=kept.check;if(c==null||isDestroyed())return;
        if(readyList!=null){renderReady();Ui.haptic(readyList,c.state==Check.REJECTED||c.empty?Ui.Haptic.REJECT:Ui.Haptic.CONFIRM);readyList.announceForAccessibility(c.message);}
        else{renderKeyArt();if(keyStatus!=null&&kept.key.trim().isEmpty())defaultNote();}
    }
    /** Antes de mandar a nadie al navegador: los pasos, en palabras simples, y recién ahí el botón que abre la página. */
    private void howTo(){
        String site="openrouter.ai";
        Sheet s=sheet(getString(R.string.onb_howto_title),getString(R.string.onb_howto_body));
        numbered(s,new String[]{getString(R.string.onb_howto_1),getString(R.string.onb_howto_2),getString(R.string.onb_howto_3),getString(R.string.onb_howto_4)});
        s.primary(getString(R.string.onb_open_site,site),()->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(KEYS_OPENROUTER)));}
            catch(ActivityNotFoundException|SecurityException e){message(getString(R.string.onb_no_browser_title),getString(R.string.onb_no_browser_body,site));}
        }).secondary(getString(R.string.onb_close),null).show();
    }

    // ---------- 4. Listo ----------
    private LinearLayout buildReady(){
        LinearLayout c=column();
        c.addView(hero(new Emblem(this,p,Emblem.DONE)));
        c.addView(overline(getString(R.string.onb_ready_overline)),Ui.fill());
        heading=title(readyTitle(name),null);c.addView(heading,Ui.fill());
        c.addView(gap());
        readyList=ui.group();c.addView(readyList,Ui.fill());renderReady();
        return c;
    }
    /** Las tres filas del último paso, con su estado real: la clave (y su comprobación), tu voz y tu carpeta rápida. */
    private void renderReady(){
        if(readyList==null)return;readyList.removeAllViews();
        // Con el mismo ícono de OpenRouter del paso 3. Válida pero sin saldo va sin ✓: así todavía no transcribe.
        String who="OpenRouter";int hub=R.drawable.onboarding_hub;boolean has=settings.hasOpenRouterKey();Check c=kept.check;
        if(has&&c!=null){
            if(c.state==Check.CHECKING)readyRow(hub,who,getString(R.string.onb_checking),ROW_BUSY,null);
            else if(c.state==Check.VALID)readyRow(hub,who,c.message,c.empty?ROW_PLAIN:ROW_DONE,null);
            else if(c.state==Check.REJECTED)readyRow(R.drawable.ic_alert,getString(R.string.onb_check_key_of,who),c.message,ROW_BAD,()->go(AI));
            else readyRow(hub,who,c.message,ROW_PLAIN,null);
        }
        else if(has){
            // Sin comprobación en memoria (p. ej. Android cerró la app mientras estabas en el navegador): manda la guardada,
            // la misma que muestra Ajustes. Rechazada, sin saldo o sin comprobar (falló sin decir nada de la clave) no lleva
            // ✓, como en la comprobación en memoria; sin nada guardado (o válida con saldo), sí.
            Verdict v=saved(settings.prefs,SettingsActivity.verifyTarget(settings));
            if(v!=null&&v.state==Check.REJECTED)readyRow(R.drawable.ic_alert,getString(R.string.onb_check_key_of,who),v.message,ROW_BAD,()->go(AI));
            else if(v!=null&&(v.empty()||v.state==Check.UNKNOWN))readyRow(hub,who,v.message,ROW_PLAIN,null);
            else readyRow(hub,who,getString(R.string.onb_key_ready),ROW_DONE,null);
        }
        else readyRow(R.drawable.ic_key,getString(R.string.onb_connect_ai),getString(R.string.onb_no_key),ROW_GO,()->go(AI));
        boolean voice=false;try{voice=Voices.has(this);}catch(RuntimeException ignored){}
        if(voice)readyRow(R.drawable.ic_voice,getString(R.string.onb_voice),getString(R.string.onb_voice_saved),ROW_DONE,()->openSettings("voice"));
        else readyRow(R.drawable.ic_voice,getString(R.string.onb_voice_record),getString(R.string.onb_voice_optional),ROW_GO,()->openSettings("voice"));
        if(Inbox.configured(this))readyRow(R.drawable.ic_inbox,getString(R.string.onb_inbox),getString(R.string.onb_inbox_set,Inbox.folderName(this)),ROW_DONE,()->openSettings("inbox"));
        else readyRow(R.drawable.ic_inbox,getString(R.string.onb_inbox_pick),getString(R.string.onb_inbox_optional),ROW_GO,()->openSettings("inbox"));
    }
    private void readyRow(int icon,String title,String detail,int tone,Runnable click){
        // Se registra el tipo de fila (su lugar en la lista), no su texto: el de la carpeta trae un nombre puesto por la persona.
        LinearLayout row=stateRow(icon,title,detail,tone,click==null?null:v->{Diagnostics.event("ui_action",null,"screen","Onboarding","action","ready_row","result",readyList==null?-1:readyList.indexOfChild(v));click.run();});
        if(readyList.getChildCount()>0)readyList.addView(ui.separator(S4+44+S3));
        readyList.addView(row,Ui.fill());
    }
    /**
     * Fila con estado (la del último paso y la de batería del paso «Tú»): ícono en círculo menta (rojo si hay un problema),
     * título, apoyo y, a la derecha, el estado de un vistazo. Con click es un botón para el lector de pantalla.
     */
    private LinearLayout stateRow(int icon,String title,String detail,int tone,View.OnClickListener click){
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
        if(click!=null){row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());row.setOnClickListener(click);}
        else row.setFocusable(true);
        return row;
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
            // Lang.str y no getString: la vista puede vivir en un contexto sin el idioma de la app (las pruebas la crean así).
            setContentDescription(Lang.str(getContext(),R.string.onb_step_of,step+1,count));if(anim!=null)anim.cancel();
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
     * micrófono · DONE: un ✓ con destellos alrededor · HUB: el «centro que reparte» de OpenRouter (una clave, muchos
     * modelos). Puede llevar una insignia ✓ (permiso concedido, clave guardada). Es solo dibujo: no se toca ni se anuncia.
     * El halo respira solo si Android permite animaciones y la vista está en pantalla.
     */
    static final class Emblem extends View {
        static final int LOGO=0,MIC=1,DONE=2,HUB=3;
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
            glyph=kind==LOGO?null:c.getDrawable(kind==MIC?R.drawable.ic_mic_fill:kind==HUB?R.drawable.onboarding_hub:R.drawable.ic_check).mutate();if(glyph!=null)glyph.setTint(white);
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
                // HUB es de trazo fino y ocupa casi toda su caja: un poco más grande que el micrófono para que pese lo mismo.
                int half=Math.round(Math.min(px(kind==DONE?20:kind==HUB?19:17),r*0.5f));glyph.setBounds(Math.round(cx)-half,Math.round(cy)-half,Math.round(cx)+half,Math.round(cy)+half);glyph.draw(canvas);
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
