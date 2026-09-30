package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/**
 * Pruebas de la parte «onboarding» de la 0.8.0 (ver docs/diseno/SPEC-0.8.md). Sin red ni claves reales: las claves son
 * textos inventados y el proveedor es un HttpApi simulado. Todo lo que tocan en las preferencias se repone al terminar.
 */
final class OnboardingChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Claves de mentira con la forma de las reales (no existen en ningún proveedor). */
    private static final String OR="sk-or-v1-0123456789abcdef0123456789abcdef",OA="sk-proj-0123456789abcdef0123456789",CLAUDE="sk-ant-api03-0123456789abcdef0123";
    /** Preferencias que estas pruebas cambian: se guardan antes y se reponen después, aunque algo falle. */
    private static final String[] PREFS={"welcomed","lastSeenVersion","provider","noteProvider","noteModel","keyEncrypted","keyIv","openrouter_keyEncrypted","openrouter_keyIv","custom_keyEncrypted","custom_keyIv","anthropic_keyEncrypted","anthropic_keyIv"};

    static void run(Context c,Recording r)throws Exception{
        keys();
        texts();
        verdicts();
        preferences(c);
        UiChecks.onMain(()->{drawables(c);views(c,false);views(c,true);});
    }

    /** Validaciones de la clave: qué se rechaza (lo seguro) y qué se deja pasar (lo no confirmado). */
    static void keys(){
        check(OnboardingActivity.keyProblem("openrouter","")!=null&&OnboardingActivity.keyProblem("openrouter","   ")!=null&&OnboardingActivity.keyProblem("openrouter",null)!=null,"Una clave vacía debe rechazarse");
        check(OnboardingActivity.keyProblem("openrouter",OR)==null&&OnboardingActivity.keyProblem("openai",OA)==null,"Una clave con buena forma debe aceptarse");
        check(OnboardingActivity.keyProblem("openrouter","  "+OR+"\n")==null,"Los espacios alrededor de lo pegado no son un problema");
        String spaced=OR.substring(0,14)+" "+OR.substring(14),broken=OR.substring(0,14)+"\n"+OR.substring(14);
        check(OnboardingActivity.keyProblem("openrouter",spaced)!=null&&OnboardingActivity.keyProblem("openrouter",broken)!=null,"Una clave con espacios o saltos adentro debe rechazarse");
        check(OnboardingActivity.keyProblem("openrouter","sk-or-v1-corta")!=null,"Una clave cortada debe rechazarse");
        check(OnboardingActivity.keyProblem("openai",OR)!=null,"Una clave de OpenRouter no va en la tarjeta de OpenAI");
        check(OnboardingActivity.keyProblem("openrouter",CLAUDE)!=null&&OnboardingActivity.keyProblem("openai",CLAUDE)!=null,"Una clave de Anthropic no sirve en este paso");
        // El comienzo «sk-» no está confirmado como exclusivo de OpenAI: no bloquea en la tarjeta de OpenRouter.
        check(OnboardingActivity.keyProblem("openrouter",OA)==null,"Un comienzo no confirmado no debe bloquear");
        check(OnboardingActivity.keyProblem("openai","clave-sin-prefijo-conocido-0123456789")==null,"Un formato desconocido no debe bloquear");
        // Privacidad: ningún mensaje repite la clave (ni un trozo reconocible).
        for(String[] k:new String[][]{{"openrouter",spaced},{"openrouter","sk-or-v1-corta"},{"openai",OR},{"openrouter",CLAUDE}}){
            String m=OnboardingActivity.keyProblem(k[0],k[1]);check(m!=null&&!m.contains(k[1])&&!m.contains("0123456789")&&!m.contains("corta"),"El mensaje no debe repetir la clave");
        }
        check("openrouter".equals(OnboardingActivity.guessProvider(OR))&&"openai".equals(OnboardingActivity.guessProvider(" "+OA+" "))&&"anthropic".equals(OnboardingActivity.guessProvider(CLAUDE)),"No se reconoció de quién es la clave");
        check(OnboardingActivity.guessProvider("hola")==null&&OnboardingActivity.guessProvider("")==null&&OnboardingActivity.guessProvider(null)==null,"Un texto cualquiera no es de ningún proveedor");
    }

    /** Textos que ve la persona: saldo, rechazo y saludo final. */
    static void texts(){
        String valid=OnboardingActivity.validText(new Models.KeyInfo("etiqueta-privada",1.5,4.2,false));
        check("Clave válida · quedan US$ 4,20".equals(valid),"Saldo mal escrito: «"+valid+"»");
        check("Clave válida".equals(OnboardingActivity.validText(new Models.KeyInfo("",Double.NaN,Double.NaN,false)))&&"Clave válida".equals(OnboardingActivity.validText(null)),"Sin saldo informado, solo «Clave válida»");
        String empty=OnboardingActivity.validText(new Models.KeyInfo("",10,0,false));
        check(empty.startsWith("Clave válida")&&empty.contains("sin saldo"),"Una clave sin saldo debe avisarlo: «"+empty+"»");
        check(!valid.contains("etiqueta"),"La etiqueta de la clave no se muestra");
        check("La clave del proveedor no es válida o fue revocada.".equals(OnboardingActivity.rejected("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.")),"El rechazo no debe mandar a Ajustes desde la bienvenida");
        check(!OnboardingActivity.rejected(null).isEmpty()&&!OnboardingActivity.rejected("  ").isEmpty(),"Un rechazo sin mensaje igual se explica");
        check("Konrad".equals(OnboardingActivity.firstName("  Konrad   Peschka "))&&"Fran".equals(OnboardingActivity.firstName("Fran")),"firstName no tomó el primer nombre");
        check(OnboardingActivity.firstName("Yo").isEmpty()&&OnboardingActivity.firstName("").isEmpty()&&OnboardingActivity.firstName(null).isEmpty(),"Sin nombre (o «Yo») no hay a quién nombrar");
        check("Todo listo, Konrad".equals(OnboardingActivity.readyTitle("Konrad Peschka"))&&"¡Todo listo!".equals(OnboardingActivity.readyTitle("")),"Título final equivocado");
        check("OpenRouter".equals(OnboardingActivity.providerName("openrouter"))&&"OpenAI".equals(OnboardingActivity.providerName("openai")),"Nombre visible del proveedor");
        check(OnboardingActivity.KEYS_OPENROUTER.startsWith("https://openrouter.ai/")&&OnboardingActivity.KEYS_OPENAI.startsWith("https://platform.openai.com/"),"Las páginas para crear la clave deben ser las oficiales, por HTTPS");
    }

    /** Comprobación de la clave: válida, rechazada con el mensaje del proveedor, o «no se pudo comprobar» sin bloquear. */
    static void verdicts()throws Exception{
        HttpApi idle=new HttpApi();
        OnboardingActivity.Verdict ok=OnboardingActivity.verify((h,k)->"Clave válida · quedan US$ 1,00",idle,OR);
        check(ok.state==OnboardingActivity.Check.VALID&&"Clave válida · quedan US$ 1,00".equals(ok.message),"Una clave válida debe informarse tal cual");
        OnboardingActivity.Verdict no=OnboardingActivity.verify((h,k)->{throw new HttpApi.UserAction("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.");},idle,OR);
        check(no.state==OnboardingActivity.Check.REJECTED&&"La clave del proveedor no es válida o fue revocada.".equals(no.message),"Un rechazo del proveedor debe mostrar su mensaje");
        // Lo que lanza hoy Models.checkKey mientras la parte «catalog» no lo implementa, y un corte de red: nada bloquea.
        OnboardingActivity.Verdict pending=OnboardingActivity.verify((h,k)->{throw new UnsupportedOperationException("pendiente");},idle,OR);
        OnboardingActivity.Verdict offline=OnboardingActivity.verify((h,k)->{throw new java.io.IOException("sin red");},idle,OR);
        check(pending.state==OnboardingActivity.Check.UNKNOWN&&offline.state==OnboardingActivity.Check.UNKNOWN&&OnboardingActivity.UNCHECKED.equals(pending.message)&&OnboardingActivity.UNCHECKED.equals(offline.message),"Lo que no se pudo comprobar no es un rechazo");
        for(OnboardingActivity.Verdict v:new OnboardingActivity.Verdict[]{ok,no,pending,offline})check(!v.message.contains(OR),"El resultado no debe repetir la clave");

        // OpenAI con un HttpApi simulado: la clave viaja solo como Bearer a api.openai.com.
        String[] seen=new String[2];int[] calls={0};
        HttpApi accepted=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){calls[0]++;seen[0]=method+" "+url;seen[1]=token;return new Response(200,"{\"id\":\"gpt-4o-transcribe-diarize\"}",null);}};
        OnboardingActivity.Verdict openai=OnboardingActivity.verify(OnboardingActivity.checker("openai"),accepted,OA);
        check(openai.state==OnboardingActivity.Check.VALID&&"Clave válida".equals(openai.message),"OpenAI aceptó la clave y no quedó como válida");
        check(calls[0]==1&&seen[0].startsWith("GET https://api.openai.com/")&&!seen[0].contains(OA)&&OA.equals(seen[1]),"La clave de OpenAI debe ir solo en el encabezado, a api.openai.com");
        HttpApi denied=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){return new Response(401,"{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}",null);}};
        OnboardingActivity.Verdict bad=OnboardingActivity.verify(OnboardingActivity.checker("openai"),denied,OA);
        check(bad.state==OnboardingActivity.Check.REJECTED&&!bad.message.isEmpty()&&!bad.message.contains(OA),"Un 401 de OpenAI debe quedar como clave rechazada: «"+bad.message+"»");
        HttpApi down=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{throw new java.io.IOException("sin red");}};
        check(OnboardingActivity.verify(OnboardingActivity.checker("openai"),down,OA).state==OnboardingActivity.Check.UNKNOWN,"Sin red, la clave de OpenAI queda sin comprobar (no rechazada)");
        // OpenRouter: pase lo que pase con Models.checkKey (pendiente o ya implementado), sin red nunca se da por válida ni se cae.
        check(OnboardingActivity.verify(OnboardingActivity.checker("openrouter"),down,OR).state!=OnboardingActivity.Check.VALID,"Sin red, una clave de OpenRouter no puede darse por válida");
    }

    /** shouldShow, complete y applyKey sobre las preferencias reales (se reponen al final). */
    static void preferences(Context c)throws Exception{
        Settings s=new Settings(c);Map<String,?> all=s.prefs.getAll();Map<String,Object> before=new HashMap<>();
        for(String k:PREFS)if(all.containsKey(k))before.put(k,all.get(k));
        try{
            SharedPreferences.Editor clean=s.prefs.edit();for(String k:PREFS)clean.remove(k);clean.commit();
            // Primera instalación: aparece; un repaso desde Ajustes no la marca como vista; terminar o saltar sí.
            check(OnboardingActivity.shouldShow(c),"Sin «welcomed», la bienvenida debe aparecer");
            OnboardingActivity.complete(c,true);
            check(OnboardingActivity.shouldShow(c)&&s.lastSeenVersion()==0,"Un repaso no debe marcar la bienvenida como vista");
            OnboardingActivity.complete(c,false);
            int code=Novedades.versionCode(c);
            check(!OnboardingActivity.shouldShow(c),"Al terminar, la bienvenida no debe volver a aparecer");
            check(code>0&&s.lastSeenVersion()==code,"Quien recién instaló no debe ver «Novedades» encima: la versión queda vista");
            s.setLastSeenVersion(code+7);OnboardingActivity.complete(c,false);
            check(s.lastSeenVersion()==code+7,"complete no debe bajar la última versión vista");

            // Sin nada escrito no se guarda ni cambia nada (así un repaso no pisa lo que existe).
            check(!OnboardingActivity.applyKey(s,"openrouter","   ")&&!OnboardingActivity.applyKey(s,"openrouter",null),"Una clave vacía no se guarda");
            check(!s.hasOpenRouterKey()&&!s.hasOpenAiKey()&&!s.prefs.contains("provider")&&!s.prefs.contains("noteProvider"),"Una clave vacía no debe cambiar ninguna preferencia");
            // OpenRouter: guarda su clave, queda como proveedor y la nota usa la misma clave.
            check(OnboardingActivity.applyKey(s,"openrouter","  "+OR+"\n"),"La clave de OpenRouter no se guardó");
            check(s.provider().equals("openrouter")&&s.openRouter()&&s.hasKey()&&OR.equals(s.openRouterKey())&&OR.equals(s.apiKey()),"OpenRouter no quedó como proveedor con su clave");
            check(s.noteProvider().equals("openrouter"),"Con OpenRouter la nota debe usar la misma clave");
            check(!s.hasOpenAiKey()&&!s.prefs.contains("custom_keyEncrypted"),"La clave de OpenRouter no debe ocupar el lugar de otra");
            check(!s.prefs.getString("openrouter_keyEncrypted","").contains(OR),"La clave debe guardarse cifrada");
            // Un repaso con el campo vacío (aunque se elija la otra tarjeta) deja todo igual.
            check(!OnboardingActivity.applyKey(s,"openai","")&&s.provider().equals("openrouter")&&OR.equals(s.openRouterKey())&&s.noteProvider().equals("openrouter"),"Un repaso sin escribir pisó la configuración");
            // Una clave que no corresponde no se guarda ni cambia el proveedor.
            boolean refused=false;try{OnboardingActivity.applyKey(s,"openai",OR);}catch(IllegalArgumentException e){refused=true;}
            check(refused&&!s.hasOpenAiKey()&&s.provider().equals("openrouter"),"Una clave de OpenRouter se guardó como si fuera de OpenAI");
            refused=false;try{OnboardingActivity.applyKey(s,"custom",OA);}catch(IllegalArgumentException e){refused=true;}
            check(refused&&s.provider().equals("openrouter")&&!s.prefs.contains("custom_keyEncrypted"),"La bienvenida solo guarda claves de OpenRouter u OpenAI");
            // OpenAI: guarda la suya sin pisar la de OpenRouter; la nota sigue con OpenRouter porque su clave existe.
            check(OnboardingActivity.applyKey(s,"openai",OA),"La clave de OpenAI no se guardó");
            check(s.provider().equals("openai")&&OA.equals(s.openAiKey())&&OA.equals(s.apiKey())&&OR.equals(s.openRouterKey()),"Las claves de OpenAI y OpenRouter se pisaron");
            check(s.noteProvider().equals("openrouter"),"La nota no debe cambiar si su clave de OpenRouter sigue ahí");
            // Sin clave de OpenRouter, una nota que apuntaba a OpenRouter vuelve a OpenAI (si no, quedaría sin nota).
            s.saveKeyFor("openrouter","");s.prefs.edit().putString("noteProvider","openrouter").putString("noteModel","~openai/gpt-luna-latest").commit();
            check(OnboardingActivity.applyKey(s,"openai",OA)&&s.noteProvider().equals("openai")&&!s.prefs.contains("noteModel"),"La nota quedó apuntando a OpenRouter sin tener su clave");
            // Quien arma la nota con Claude y su propia clave la conserva al pasar a OpenRouter.
            s.saveAnthropicKey(CLAUDE);s.prefs.edit().putString("noteProvider","anthropic").putString("noteModel","claude-sonnet-5-5").commit();
            check(OnboardingActivity.applyKey(s,"openrouter",OR)&&s.provider().equals("openrouter")&&s.noteProvider().equals("anthropic")&&"claude-sonnet-5-5".equals(s.noteModel()),"La bienvenida cambió la IA de la nota de quien usa Claude");
            check(OnboardingActivity.hasKey(s,"openrouter")&&OnboardingActivity.hasKey(s,"openai"),"hasKey debe mirar la clave de cada proveedor");
        }finally{
            SharedPreferences.Editor e=s.prefs.edit();
            for(String k:PREFS){Object v=before.get(k);if(v==null)e.remove(k);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else e.putString(k,String.valueOf(v));}
            e.commit();
        }
    }

    /** Los íconos propios de la bienvenida se pueden cargar y dibujar (un pathData malo recién falla al inflarse). */
    static void drawables(Context c){
        Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        for(int id:new int[]{R.drawable.onboarding_hub,R.drawable.onboarding_paste}){
            Drawable d=c.getDrawable(id);check(d!=null&&d.getIntrinsicWidth()>0&&d.getIntrinsicWidth()==d.getIntrinsicHeight(),"Ícono de la bienvenida no cuadrado o ausente");
            bitmap.eraseColor(0);d.setBounds(0,0,96,96);d.draw(canvas);
            int painted=0;for(int y=0;y<96;y+=2)for(int x=0;x<96;x+=2)if(Color.alpha(bitmap.getPixel(x,y))>0)painted++;
            check(painted>40,"Un ícono de la bienvenida no dibuja nada");
        }
        bitmap.recycle();
    }

    /** Vistas propias, en claro y oscuro: el indicador de pasos y las ilustraciones se miden y se dibujan fuera de pantalla. */
    static void views(Context c,boolean dark){
        AppTheme.Palette p=new AppTheme.Palette(c,dark,false);float d=c.getResources().getDisplayMetrics().density;
        OnboardingActivity.Steps steps=new OnboardingActivity.Steps(c,p,4);
        check("Paso 1 de 4".contentEquals(steps.getContentDescription()),"El indicador debe anunciar el paso");
        // Paso 1: la píldora del paso actual va en verde y quedan puntos por recorrer; en el último, todo es verde.
        int[] first=colors(steps,p);check(first[0]>0&&first[1]>0,"En el primer paso debe haber una píldora verde y puntos pendientes");
        steps.setStep(3,false);check("Paso 4 de 4".contentEquals(steps.getContentDescription()),"setStep no actualizó el anuncio");
        int[] last=colors(steps,p);check(last[0]>first[0]&&last[1]==0,"En el último paso no deben quedar puntos pendientes");
        steps.setStep(1,true);check("Paso 2 de 4".contentEquals(steps.getContentDescription()),"setStep animado no actualizó el anuncio");
        // Geometría de la ilustración (la misma de Emblem): disco de radio «mitad menos 28 dp»; la insignia ✓ (13 dp de radio)
        // va centrada a 0,7 radios abajo a la derecha. Se mira un punto dentro de la insignia y fuera de su ✓ (10 dp más abajo).
        int size=Math.round(132*d),mid=size/2;float radius=mid-28*d;int bx=Math.round(mid+0.7f*radius),by=Math.round(mid+0.7f*radius+10*d);
        for(int kind:new int[]{OnboardingActivity.Emblem.LOGO,OnboardingActivity.Emblem.MIC,OnboardingActivity.Emblem.DONE}){
            OnboardingActivity.Emblem art=new OnboardingActivity.Emblem(c,p,kind);
            check(art.getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO,"La ilustración es decorativa: no debe anunciarse");
            Bitmap b=paint(art,size,size);
            // El disco verde ocupa el centro; la esquina queda libre (el halo no llega hasta ahí).
            check(Color.alpha(b.getPixel(mid,mid))==255,"La ilustración no dibujó su disco");
            check(Color.alpha(b.getPixel(1,1))==0,"La ilustración no debe pintar su esquina");
            int without=b.getPixel(bx,by);b.recycle();
            // Con la insignia ✓ (permiso concedido) ese punto pasa del halo translúcido al relleno opaco de la insignia.
            art.setBadge(true,false);b=paint(art,size,size);int with=b.getPixel(bx,by);b.recycle();
            check(Color.alpha(without)<255&&Color.alpha(with)==255&&with!=without,"La insignia ✓ no se dibujó");
            art.setBadge(false,true);b=paint(art,size,size);check(b.getPixel(bx,by)==without,"La insignia ✓ no se quitó");b.recycle();
        }
    }
    /** {píxeles del verde de marca, píxeles del color «pendiente»} en la línea central del indicador. */
    private static int[] colors(OnboardingActivity.Steps steps,AppTheme.Palette p){
        int w=AppTheme.dp(steps.getContext(),120),h=AppTheme.dp(steps.getContext(),48);Bitmap b=paint(steps,w,h);int on=0,off=0;
        for(int x=0;x<b.getWidth();x++){int px=b.getPixel(x,b.getHeight()/2);if(px==p.primary)on++;else if(px==p.outlineVariant)off++;}
        b.recycle();return new int[]{on,off};
    }
    private static Bitmap paint(View v,int width,int height){
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,width,height);
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));return b;
    }
}
