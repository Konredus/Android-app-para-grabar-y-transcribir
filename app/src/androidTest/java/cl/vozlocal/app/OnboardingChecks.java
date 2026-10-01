package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pruebas de la parte «onboarding» de la 0.8.0 (ver docs/diseno/SPEC-0.8.md y, para «solo OpenRouter», SPEC-0.8b.md).
 * Sin red ni claves reales: las claves son textos inventados y OpenRouter es un HttpApi simulado. Todo lo que tocan en
 * las preferencias se repone al terminar.
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
        battery(c);
        libraryFilter();
        UiChecks.onMain(()->{drawables(c);views(c,false);views(c,true);});
    }

    /**
     * «Con la pantalla bloqueada» (SPEC-0.8c, decisión 4): la guía de cada marca. Solo lógica con nombres de fabricante
     * inventados; no abre ningún diálogo ni cambia la batería del teléfono de pruebas.
     */
    static void battery(Context c){
        // Lo que se lee del teléfono no debe lanzar (solo se consulta; la respuesta depende del dispositivo).
        Battery.unrestricted(c);Battery.canAskDirectly(c);Battery.makerSteps();Battery.makerHint();Battery.makerName();
        check("vivo".equals(Battery.makerName("vivo"))&&"vivo".equals(Battery.makerName("VIVO"))&&"iQOO".equals(Battery.makerName("iQOO"))&&"Xiaomi".equals(Battery.makerName("POCO"))&&"Samsung".equals(Battery.makerName("samsung")),"Nombre de la marca equivocado");
        check(Battery.makerName("Google").isEmpty()&&Battery.makerName(null).isEmpty(),"Una marca sin ahorro propio no tiene nombre que mostrar");
        // vivo con Android 16 (el teléfono del diagnóstico): parte por el botón de la hoja y dice qué permitir.
        String[] vivo=Battery.makerSteps("vivo",36);
        check(vivo.length>=3&&vivo.length<=5&&vivo[0].contains("«"+Battery.OPEN_SETTINGS+"»"),"La guía de vivo debe empezar por el botón «"+Battery.OPEN_SETTINGS+"»");
        check(String.join(" ",vivo).contains("segundo plano"),"La guía de vivo debe decir que se permite el uso en segundo plano");
        check(String.join(" ",Battery.makerSteps("vivo",31)).contains("Control de energía en segundo plano"),"En vivo con Android 12 o menos el ajuste está en Batería");
        String hint=Battery.makerHint("vivo",36);
        check(hint.startsWith("En vivo, además:")&&hint.contains("segundo plano")&&!hint.contains("OpenAI"),"La línea de vivo para «Trabajar con la pantalla bloqueada» no sirve: «"+hint+"»");
        // Todas las marcas con ahorro propio tienen guía y línea; Samsung solo la línea (su ajuste es el mismo de Android).
        for(String maker:new String[]{"vivo","iqoo","Xiaomi","Redmi","POCO","HUAWEI","HONOR","OPPO","realme","OnePlus"})for(int sdk:new int[]{29,33,36}){
            String[] steps=Battery.makerSteps(maker,sdk);
            check(steps.length>0&&!Battery.makerHint(maker,sdk).isEmpty()&&!Battery.makerName(maker).isEmpty(),"Falta la guía de "+maker);
            for(String step:steps)check(!step.isEmpty()&&step.length()<=110&&step.endsWith("."),"Paso de "+maker+" largo o sin punto final: «"+step+"»");
        }
        check(Battery.makerSteps("samsung",34).length==0&&!Battery.makerHint("samsung",34).isEmpty(),"Samsung no necesita un paso aparte en la bienvenida");
        check(Battery.makerSteps("Google",36).length==0&&Battery.makerSteps(null,36).length==0&&Battery.makerHint("Google",36).isEmpty(),"Un teléfono sin ahorro propio no muestra guía");
    }

    /** La Biblioteca abierta desde «Tus métricas» («A medio camino») toma el filtro que se pide, y solo uno que existe. */
    static void libraryFilter(){
        android.content.Intent plain=new android.content.Intent().putExtra("library",true);
        check(MainActivity.requestedFilter(plain,3)==3&&MainActivity.requestedFilter(null,2)==2,"Sin «filter» se queda el filtro que había");
        for(int f=0;f<=4;f++)check(MainActivity.requestedFilter(new android.content.Intent(plain).putExtra("filter",f),3)==f,"No se tomó el filtro "+f);
        check(MainActivity.requestedFilter(new android.content.Intent(plain).putExtra("filter",9),2)==2&&MainActivity.requestedFilter(new android.content.Intent(plain).putExtra("filter",-1),1)==1,"Un filtro que no existe no se aplica");
    }

    /** Validaciones de la clave de OpenRouter: qué se rechaza (lo seguro) y qué se deja pasar (lo no confirmado). */
    static void keys(){
        check(OnboardingActivity.keyProblem("")!=null&&OnboardingActivity.keyProblem("   ")!=null&&OnboardingActivity.keyProblem(null)!=null,"Una clave vacía debe rechazarse");
        check(OnboardingActivity.keyProblem(OR)==null,"Una clave de OpenRouter con buena forma debe aceptarse");
        check(OnboardingActivity.keyProblem("  "+OR+"\n")==null,"Los espacios alrededor de lo pegado no son un problema");
        String spaced=OR.substring(0,14)+" "+OR.substring(14),broken=OR.substring(0,14)+"\n"+OR.substring(14);
        check(OnboardingActivity.keyProblem(spaced)!=null&&OnboardingActivity.keyProblem(broken)!=null,"Una clave con espacios o saltos adentro debe rechazarse");
        check(OnboardingActivity.keyProblem("sk-or-v1-corta")!=null,"Una clave cortada debe rechazarse");
        check(OnboardingActivity.keyProblem(CLAUDE)!=null,"Una clave de Anthropic no sirve en este paso");
        // Solo OpenRouter (SPEC-0.8b): una clave «sk-» que no es «sk-or-» (OpenAI u otro) ya no tiene tarjeta propia. Se
        // rechaza: no serviría en OpenRouter y, al comprobarla, la clave de otra cuenta viajaría a openrouter.ai.
        check(OnboardingActivity.keyProblem(OA)!=null&&OnboardingActivity.keyProblem(OA).contains("sk-or-"),"Una clave de OpenAI no debe guardarse como de OpenRouter");
        check(OnboardingActivity.keyProblem("clave-sin-prefijo-conocido-0123456789")==null,"Un formato desconocido no debe bloquear");
        // Privacidad: ningún mensaje repite la clave (ni un trozo reconocible).
        for(String k:new String[]{spaced,"sk-or-v1-corta",OA,CLAUDE}){
            String m=OnboardingActivity.keyProblem(k);check(m!=null&&!m.contains(k)&&!m.contains("0123456789")&&!m.contains("corta"),"El mensaje no debe repetir la clave");
        }
        check(OnboardingActivity.foreign("openrouter")==null&&OnboardingActivity.foreign(null)==null,"Una clave de OpenRouter (o sin comienzo conocido) no es de otro servicio");
        check("openrouter".equals(OnboardingActivity.guessProvider(OR))&&"openai".equals(OnboardingActivity.guessProvider(" "+OA+" "))&&"anthropic".equals(OnboardingActivity.guessProvider(CLAUDE)),"No se reconoció de quién es la clave");
        check(OnboardingActivity.guessProvider("hola")==null&&OnboardingActivity.guessProvider("")==null&&OnboardingActivity.guessProvider(null)==null,"Un texto cualquiera no es de ningún proveedor");
    }

    /** Textos que ve la persona: lo que se envía, saldo, rechazo y saludo final. */
    static void texts(){
        // Lo honesto (SPEC-0.8b): se dice que el audio sale hacia OpenRouter y quién paga; ya no «Todo queda en tu teléfono».
        check(OnboardingActivity.SENT.contains("audio se envía a OpenRouter")&&OnboardingActivity.SENT.contains("paga solo lo que usas"),"El paso 3 debe decir que el audio se envía a OpenRouter y quién paga");
        check(!OnboardingActivity.KEPT.contains("Todo queda")&&OnboardingActivity.KEPT.contains("hasta transcribir"),"La bienvenida no debe prometer que todo queda en el teléfono");
        String valid=OnboardingActivity.validText(4.2,false);
        // El saldo se escribe igual que en Ajustes (SettingsActivity.money): «US$4,20», sin espacio.
        check("Clave válida · quedan US$4,20".equals(valid)&&valid.endsWith(SettingsActivity.money(4.2)),"Saldo mal escrito: «"+valid+"»");
        check("Clave válida".equals(OnboardingActivity.validText(Double.NaN,false)),"Sin saldo informado, solo «Clave válida»");
        String empty=OnboardingActivity.validText(0,false),none=OnboardingActivity.validText(5,true);
        check(empty.startsWith("Clave válida")&&empty.contains("sin saldo"),"Una clave sin saldo debe avisarlo: «"+empty+"»");
        check(none.startsWith("Clave válida")&&none.contains("sin créditos")&&!none.contains("5,00"),"Una cuenta sin créditos no muestra el tope de la clave como saldo: «"+none+"»");
        // El saldo que sirve: el menor entre la clave (si tiene tope) y la cuenta; lo que no se supo no cuenta. Desde la
        // tercera ronda la bienvenida usa la regla de Ajustes (Models.balance) en vez de una copia propia: se prueba esa.
        Models.KeyInfo capped=new Models.KeyInfo("etiqueta-privada",1.5,5,false),open=new Models.KeyInfo("",1.5,Double.NaN,false),free=new Models.KeyInfo("",0,5,true);
        check(Models.balance(capped,0.4).left==0.4&&Models.balance(capped,9).left==5&&Models.balance(capped,Double.NaN).left==5,"El saldo debe ser el menor entre la clave y la cuenta");
        check(Models.balance(open,4.2).left==4.2&&Double.isNaN(Models.balance(open,Double.NaN).left)&&Double.isNaN(Models.balance(null,Double.NaN).left),"Sin tope en la clave manda la cuenta; sin nada, no se sabe");
        check(Models.balance(free,Double.NaN).noCredits&&!Models.balance(free,3).noCredits&&!Models.balance(capped,Double.NaN).noCredits,"Cuenta sin créditos: gratuita y sin saldo de cuenta");
        // Lo que dice la bienvenida después de «Clave válida» es lo mismo que dice Ajustes (SettingsActivity.balanceText).
        for(double left:new double[]{4.2,0,Double.NaN})for(boolean unpaid:new boolean[]{false,true}){
            String what=SettingsActivity.balanceText(left,unpaid),text=OnboardingActivity.validText(left,unpaid);
            check(what==null?"Clave válida".equals(text):text.equals("Clave válida · "+what),"La bienvenida y Ajustes deben decir lo mismo del saldo: «"+text+"»");
        }
        OnboardingActivity.Valid shown=OnboardingActivity.valid(capped,0.4);
        check("Clave válida · quedan US$0,40".equals(shown.text)&&shown.balance==0.4&&!shown.empty&&!shown.free&&!shown.text.contains("etiqueta"),"Con la cuenta casi vacía manda la cuenta: «"+shown.text+"»");
        OnboardingActivity.Valid broke=OnboardingActivity.valid(free,Double.NaN);
        check(broke.empty&&broke.free&&Double.isNaN(broke.balance)&&broke.text.contains("sin créditos"),"Una cuenta sin créditos no queda como lista ni guarda el tope como saldo");
        check(OnboardingActivity.valid(capped,0).empty&&!OnboardingActivity.valid(open,Double.NaN).empty,"Sin saldo no queda como lista; sin saber el saldo, sí");
        // «free» es lo que Ajustes guarda como verifyFree: la cuenta AÚN no carga créditos (Balance.noCredits), no solo
        // que OpenRouter la marque gratuita. Gratuita pero con saldo informado: manda el saldo.
        OnboardingActivity.Valid paid=OnboardingActivity.valid(free,3);
        check(!paid.free&&!paid.empty&&paid.balance==3&&"Clave válida · quedan US$3,00".equals(paid.text),"Una cuenta con saldo no es «sin créditos»: «"+paid.text+"»");
        OnboardingActivity.Valid nothing=OnboardingActivity.valid(null);
        check("Clave válida".equals(nothing.text)&&Double.isNaN(nothing.balance)&&!nothing.free&&!nothing.empty,"Sin datos del saldo, la clave vale y nada más");
        check("La clave del proveedor no es válida o fue revocada.".equals(OnboardingActivity.rejected("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.")),"El rechazo no debe mandar a Ajustes desde la bienvenida");
        check(!OnboardingActivity.rejected(null).isEmpty()&&!OnboardingActivity.rejected("  ").isEmpty(),"Un rechazo sin mensaje igual se explica");
        check("Konrad".equals(OnboardingActivity.firstName("  Konrad   Peschka "))&&"Fran".equals(OnboardingActivity.firstName("Fran")),"firstName no tomó el primer nombre");
        check(OnboardingActivity.firstName("Yo").isEmpty()&&OnboardingActivity.firstName("").isEmpty()&&OnboardingActivity.firstName(null).isEmpty(),"Sin nombre (o «Yo») no hay a quién nombrar");
        check("Todo listo, Konrad".equals(OnboardingActivity.readyTitle("Konrad Peschka"))&&"¡Todo listo!".equals(OnboardingActivity.readyTitle("")),"Título final equivocado");
        check(OnboardingActivity.KEYS_OPENROUTER.startsWith("https://openrouter.ai/"),"La página para crear la clave debe ser la oficial, por HTTPS");
    }

    /** Comprobación de la clave: válida (con el saldo de la clave y de la cuenta), rechazada, o «no se pudo comprobar» sin bloquear. */
    static void verdicts()throws Exception{
        HttpApi idle=new HttpApi();
        OnboardingActivity.Verdict ok=OnboardingActivity.verify((h,k)->new OnboardingActivity.Valid("Clave válida · quedan US$1,00",1,false,false),idle,OR);
        check(ok.state==OnboardingActivity.Check.VALID&&"Clave válida · quedan US$1,00".equals(ok.message)&&ok.valid!=null&&!ok.empty(),"Una clave válida debe informarse tal cual");
        OnboardingActivity.Verdict no=OnboardingActivity.verify((h,k)->{throw new HttpApi.UserAction("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.");},idle,OR);
        check(no.state==OnboardingActivity.Check.REJECTED&&"La clave del proveedor no es válida o fue revocada.".equals(no.message)&&no.valid==null,"Un rechazo del proveedor debe mostrar su mensaje");
        OnboardingActivity.Verdict pending=OnboardingActivity.verify((h,k)->{throw new UnsupportedOperationException("pendiente");},idle,OR);
        OnboardingActivity.Verdict offline=OnboardingActivity.verify((h,k)->{throw new java.io.IOException("sin red");},idle,OR);
        check(pending.state==OnboardingActivity.Check.UNKNOWN&&offline.state==OnboardingActivity.Check.UNKNOWN&&OnboardingActivity.UNCHECKED.equals(pending.message)&&OnboardingActivity.UNCHECKED.equals(offline.message),"Lo que no se pudo comprobar no es un rechazo");
        for(OnboardingActivity.Verdict v:new OnboardingActivity.Verdict[]{ok,no,pending,offline})check(!v.message.contains(OR),"El resultado no debe repetir la clave");

        // OpenRouter con un HttpApi simulado: GET /key y GET /credits, con la clave solo como Bearer y solo a openrouter.ai.
        // Cuenta con US$0,40 y clave con tope de US$5: la bienvenida dice lo mismo que diría Ajustes (US$0,40), no US$5.
        OnboardingActivity.Verdict low=routerVerify(200,"{\"data\":{\"label\":\"etiqueta-privada\",\"usage\":0,\"limit\":5,\"limit_remaining\":5,\"is_free_tier\":false}}",200,"{\"data\":{\"total_credits\":10,\"total_usage\":9.6}}",2);
        check(low.state==OnboardingActivity.Check.VALID&&"Clave válida · quedan US$0,40".equals(low.message)&&Math.abs(low.valid.balance-0.4)<1e-9&&!low.message.contains("etiqueta"),"El saldo debe considerar la cuenta: «"+low.message+"»");
        // Cuenta que ya gastó todo: «sin saldo», no el tope de la clave.
        OnboardingActivity.Verdict spent=routerVerify(200,"{\"data\":{\"limit\":5,\"limit_remaining\":5,\"is_free_tier\":false}}",200,"{\"data\":{\"total_credits\":10,\"total_usage\":10}}",2);
        check(spent.state==OnboardingActivity.Check.VALID&&spent.empty()&&spent.message.contains("sin saldo"),"Una cuenta gastada debe decir «sin saldo»: «"+spent.message+"»");
        // Cuenta nueva que nunca cargó créditos (/credits no da saldo): «aún sin créditos».
        OnboardingActivity.Verdict fresh=routerVerify(200,"{\"data\":{\"limit\":5,\"limit_remaining\":5,\"is_free_tier\":true}}",200,"{\"data\":{\"total_credits\":0,\"total_usage\":0}}",2);
        check(fresh.state==OnboardingActivity.Check.VALID&&fresh.empty()&&fresh.message.contains("sin créditos")&&Double.isNaN(fresh.valid.balance)&&fresh.valid.free,"Una cuenta sin créditos debe decirlo: «"+fresh.message+"»");
        // /credits no disponible para esta clave: vale lo de /key, sin más.
        OnboardingActivity.Verdict keyOnly=routerVerify(200,"{\"data\":{\"limit\":null,\"limit_remaining\":null,\"is_free_tier\":false}}",403,"{\"error\":{\"code\":403,\"message\":\"Forbidden\"}}",2);
        check(keyOnly.state==OnboardingActivity.Check.VALID&&"Clave válida".equals(keyOnly.message)&&!keyOnly.empty(),"Sin saldo informado, la clave vale igual: «"+keyOnly.message+"»");
        // 401: rechazada, sin la clave en el mensaje ni el «Revísala en Ajustes» (aquí se corrige en el paso anterior).
        OnboardingActivity.Verdict bad=routerVerify(401,"{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}",200,"{}",1);
        check(bad.state==OnboardingActivity.Check.REJECTED&&!bad.message.isEmpty()&&!bad.message.contains(OR)&&!bad.message.contains("Ajustes"),"Un 401 de OpenRouter debe quedar como clave rechazada: «"+bad.message+"»");
        // Servicio caído: sin comprobar (no rechazada, no válida).
        check(routerVerify(503,"{\"error\":{\"code\":503,\"message\":\"down\"}}",200,"{}",1).state==OnboardingActivity.Check.UNKNOWN,"Un 503 no dice nada de la clave");
        HttpApi down=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra)throws Exception{throw new java.io.IOException("sin red");}};
        check(OnboardingActivity.verify(OnboardingActivity.checker(),down,OR).state==OnboardingActivity.Check.UNKNOWN,"Sin red, una clave de OpenRouter queda sin comprobar (ni válida ni rechazada)");
    }
    /** Comprueba OR contra un OpenRouter simulado (respuestas de /key y /credits) y revisa adónde y cómo viajó la clave. */
    private static OnboardingActivity.Verdict routerVerify(int keyCode,String keyBody,int creditsCode,String creditsBody,int expectedCalls){
        List<String> urls=new ArrayList<>();List<String> tokens=new ArrayList<>();
        HttpApi fake=new HttpApi(){@Override Response request(String method,String url,String token,String type,Body body,Map<String,String> extra){
            urls.add(method+" "+url);tokens.add(token);boolean credits=url.endsWith("/credits");return new Response(credits?creditsCode:keyCode,credits?creditsBody:keyBody,null);}};
        OnboardingActivity.Verdict v=OnboardingActivity.verify(OnboardingActivity.checker(),fake,OR);
        check(urls.size()==expectedCalls,"Consultas a OpenRouter: "+urls.size()+" en vez de "+expectedCalls);
        for(int i=0;i<urls.size();i++)check(urls.get(i).startsWith("GET https://openrouter.ai/")&&!urls.get(i).contains(OR)&&OR.equals(tokens.get(i)),"La clave debe ir solo en el encabezado, y solo a openrouter.ai");
        check(!v.message.contains(OR),"El resultado no debe repetir la clave");
        return v;
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
            check(!OnboardingActivity.applyKey(s,"   ")&&!OnboardingActivity.applyKey(s,null),"Una clave vacía no se guarda");
            check(!s.hasOpenRouterKey()&&!s.prefs.contains("provider")&&!s.prefs.contains("noteProvider"),"Una clave vacía no debe cambiar ninguna preferencia");
            // Alguien que venía de OpenAI, con la nota armada por Claude con su propia clave (como quedaba en 0.7/0.8 primera ronda).
            s.saveKeyFor("openai",OA);s.saveKeyFor("anthropic",CLAUDE);
            s.prefs.edit().putString("provider","openai").putString("noteProvider","anthropic").putString("noteModel","claude-sonnet-5-5").commit();
            // OpenRouter: guarda su clave y queda como proveedor. La nota va por OpenRouter sin que la bienvenida toque
            // "noteProvider" (Notes ya no la mira y la migración de VozApp la borra; pedido de la parte settings).
            check(OnboardingActivity.applyKey(s,"  "+OR+"\n"),"La clave de OpenRouter no se guardó");
            check(s.provider().equals("openrouter")&&s.openRouter()&&s.hasKey()&&s.hasOpenRouterKey()&&OR.equals(s.openRouterKey())&&OR.equals(s.apiKey()),"OpenRouter no quedó como proveedor con su clave");
            check("anthropic".equals(s.prefs.getString("noteProvider",""))&&"openrouter".equals(Notes.provider(s)),"La bienvenida no debe escribir «noteProvider», y la nota igual va por OpenRouter");
            // Nada se borra sin aviso: las claves viejas quedan sin uso y el modelo de la nota, donde estaba.
            check(s.prefs.contains("keyEncrypted")&&s.prefs.contains("anthropic_keyEncrypted")&&"claude-sonnet-5-5".equals(s.prefs.getString("noteModel","")),"La bienvenida no debe borrar claves viejas ni el modelo de la nota");
            check(!s.prefs.contains("custom_keyEncrypted"),"La clave de OpenRouter no debe ocupar el lugar de otra");
            check(!s.prefs.getString("openrouter_keyEncrypted","").contains(OR),"La clave debe guardarse cifrada");
            // Un repaso con el campo vacío deja todo igual.
            check(!OnboardingActivity.applyKey(s,"")&&s.provider().equals("openrouter")&&OR.equals(s.openRouterKey()),"Un repaso sin escribir pisó la configuración");
            // Una clave de otro servicio no se guarda ni cambia nada (tampoco va a parar a openrouter_).
            String stored=s.prefs.getString("openrouter_keyEncrypted","");
            for(String other:new String[]{OA,CLAUDE}){
                boolean refused=false;try{OnboardingActivity.applyKey(s,other);}catch(IllegalArgumentException e){refused=true;}
                check(refused&&stored.equals(s.prefs.getString("openrouter_keyEncrypted",""))&&OR.equals(s.openRouterKey())&&s.provider().equals("openrouter"),"Una clave de otro servicio se guardó como de OpenRouter");
            }
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
        for(int kind:new int[]{OnboardingActivity.Emblem.LOGO,OnboardingActivity.Emblem.MIC,OnboardingActivity.Emblem.DONE,OnboardingActivity.Emblem.HUB}){
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
