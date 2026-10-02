package cl.vozlocal.app;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/**
 * Idioma de la app (0.9.0): inglés, español o portugués de Brasil, elegido en la bienvenida o en Ajustes.
 *
 * La primera vez parte en el idioma del teléfono si es uno de los tres; si no, en inglés (decisión del dueño). La elección
 * queda en la preferencia "appLang" ("en", "es" o "pt"). En Android 13+ también se avisa al sistema (LocaleManager), así
 * aparece en Ajustes del teléfono → Idiomas de las apps; si la persona la cambia ahí, la app la adopta al volver.
 *
 * Cómo se usan los textos:
 * - En pantallas (Activity, vistas): getString(R.string.x, …). Cada pantalla hereda de Screen, que ya envuelve su
 *   contexto con este idioma (attachBaseContext).
 * - En todo lo demás (servicios, notificaciones, motor, modelos, ayudas estáticas): Lang.str(c, R.string.x, …). Usa los
 *   recursos del idioma vigente aunque el contexto sea el de la app, que se creó con otro idioma o antes de elegirlo.
 * - Formatos de números y fechas: Lang.locale(c).
 * Recursos: values/ (inglés, el respaldo), values-es/ y values-pt/ (portugués de Brasil; sirve también a pt-PT).
 */
final class Lang {
    private Lang(){}
    static final String EN="en",ES="es",PT="pt";
    static final String[] SUPPORTED={EN,ES,PT};
    private static final String PREFS="settings",KEY="appLang";
    /** Solo para pruebas: fuerza un idioma sin tocar lo elegido por la persona. */
    private static volatile String override;
    private static volatile String cachedTag;private static volatile Resources cachedRes;

    /** Nombre de cada idioma en su propio idioma, para el selector. */
    static String nativeName(String lang){return EN.equals(lang)?"English":ES.equals(lang)?"Español":"Português (Brasil)";}

    /** El idioma vigente: el forzado por pruebas, el elegido (o el del sistema en Android 13+), o el del teléfono la primera vez. */
    static String current(Context c){
        String o=override;if(o!=null)return o;
        String saved=prefs(c).getString(KEY,null);
        if(Build.VERSION.SDK_INT>=33){
            // Si la persona lo cambió en Ajustes del teléfono → Idiomas de las apps, manda eso.
            try{LocaleList l=c.getSystemService(LocaleManager.class).getApplicationLocales();
                if(l!=null&&!l.isEmpty()){String sys=normalize(l.get(0).getLanguage());if(sys!=null){if(!sys.equals(saved))prefs(c).edit().putString(KEY,sys).apply();return sys;}}}
            catch(RuntimeException ignored){}
        }
        if(saved!=null&&normalize(saved)!=null)return saved;
        return deviceDefault();
    }
    /** ¿Ya se eligió un idioma (en la bienvenida o en Ajustes)? */
    static boolean chosen(Context c){return override!=null||prefs(c).contains(KEY);}
    /** El idioma del teléfono si la app lo tiene; si no, inglés. */
    static String deviceDefault(){String n=normalize(device().getLanguage());return n!=null?n:EN;}
    /** El primer idioma del teléfono (el del sistema, no el elegido para la app). */
    private static Locale device(){
        try{return Build.VERSION.SDK_INT>=24?Resources.getSystem().getConfiguration().getLocales().get(0):Locale.getDefault();}
        catch(RuntimeException e){return Locale.getDefault();}
    }
    /** "es", "es-CL", "pt-BR", "pt_PT"… → "es"/"pt"; null si la app no lo tiene. */
    static String normalize(String language){
        if(language==null)return null;String l=language.toLowerCase(Locale.ROOT);int cut=l.indexOf('-');if(cut<0)cut=l.indexOf('_');if(cut>0)l=l.substring(0,cut);
        for(String s:SUPPORTED)if(s.equals(l))return s;return null;
    }
    /**
     * Locale para textos, números y fechas. Si el teléfono está en ese mismo idioma, con su región (es-MX, en-GB, pt-PT…:
     * fechas y decimales como los usa la persona; los textos salen igual de values-es / values-pt). Si no, la región de
     * referencia de cada idioma: español de Chile (el de la app desde su inicio), portugués de Brasil (el de sus textos) e
     * inglés de EE. UU.
     */
    static Locale locale(String lang){
        String n=normalize(lang);if(n==null)n=EN;
        Locale d=device();
        if(d!=null&&n.equals(normalize(d.getLanguage()))&&!d.getCountry().isEmpty())return new Locale(d.getLanguage(),d.getCountry());
        return PT.equals(n)?new Locale("pt","BR"):ES.equals(n)?new Locale("es","CL"):Locale.US;
    }
    static Locale locale(Context c){return locale(current(c));}
    /** Etiqueta BCP 47 ("en", "es", "pt-BR"), p. ej. para pedir la nota en ese idioma. */
    static String tag(Context c){return locale(c).toLanguageTag();}

    /**
     * Guarda la elección y la aplica. En Android 13+ el sistema rehace las pantallas abiertas; antes, se rehace la pantalla
     * actual (las demás toman el idioma al volver a crearse). Las notificaciones y el motor lo toman en el acto (Lang.str).
     */
    static void set(Activity a,String lang){
        String n=normalize(lang);if(n==null)return;
        prefs(a).edit().putString(KEY,n).apply();cachedTag=null;cachedRes=null;
        if(Build.VERSION.SDK_INT>=33){
            try{a.getSystemService(LocaleManager.class).setApplicationLocales(new LocaleList(locale(n)));return;}catch(RuntimeException ignored){}
        }
        a.recreate();
    }
    /** Solo para pruebas instrumentadas: fuerza (o con null, libera) el idioma de toda la app en este proceso. */
    static void override(String lang){override=lang==null?null:normalize(lang);cachedTag=null;cachedRes=null;}

    /** Contexto con el idioma vigente: lo usan Screen, la app y los servicios en attachBaseContext. */
    static Context wrap(Context base){
        if(base==null)return null;
        Locale l=locale(current(base));Locale.setDefault(l);
        Configuration conf=new Configuration(base.getResources().getConfiguration());conf.setLocale(l);
        if(Build.VERSION.SDK_INT>=24)conf.setLocales(new LocaleList(l));
        return base.createConfigurationContext(conf);
    }
    /** Recursos del idioma vigente (se guardan mientras no cambie el idioma). */
    static Resources res(Context c){
        String t=current(c);Resources r=cachedRes;
        if(r!=null&&t.equals(cachedTag))return r;
        Configuration conf=new Configuration(c.getResources().getConfiguration());Locale l=locale(t);conf.setLocale(l);
        if(Build.VERSION.SDK_INT>=24)conf.setLocales(new LocaleList(l));
        r=c.createConfigurationContext(conf).getResources();cachedRes=r;cachedTag=t;return r;
    }
    /** Texto del idioma vigente, con argumentos opcionales (formato de String.format con el Locale del idioma). */
    static String str(Context c,int id,Object... args){
        Resources r=res(c);return args==null||args.length==0?r.getString(id):String.format(locale(c),r.getString(id),args);
    }
    /** Plurales del idioma vigente («1 grabación» / «2 grabaciones»). */
    static String plural(Context c,int id,int count,Object... args){
        Resources r=res(c);Object[] a=args==null||args.length==0?new Object[]{count}:args;return String.format(locale(c),r.getQuantityString(id,count),a);
    }

    // ---------- Sin Context (ayudas estáticas del motor y de las pantallas) ----------
    /*
     * Muchas ayudas estáticas arman textos sin tener un Context a mano (Recording.defaultTitle, Next.working…), y las
     * pruebas las llaman así. VozApp deja aquí su contexto al nacer (attachBaseContext, antes que cualquier pantalla,
     * servicio o prueba instrumentada), y estas variantes lo usan. Ninguna constante "static final" debe guardar un texto
     * traducido: quedaría fija en el idioma de cuando se cargó la clase. En su lugar, un método que llama a Lang.str.
     */
    private static volatile Context app;
    /** Lo llama VozApp.attachBaseContext. */
    static void init(Context c){if(c!=null&&app==null)app=c;}
    private static Context app(){Context c=app;if(c==null)throw new IllegalStateException("Lang.init no se llamó (VozApp)");return c;}
    static String current(){return current(app());}
    static Locale locale(){return locale(app());}
    static String str(int id,Object... args){return str(app(),id,args);}
    static String plural(int id,int count,Object... args){return plural(app(),id,count,args);}

    // ---------- Textos guardados en otro idioma ----------
    /*
     * Algunos textos se guardan y se vuelven a leer: la bitácora de cada grabación, el título por defecto, el nombre «Yo»
     * de tu voz, el último mensaje de «Comprobar conexión»… Si la persona cambia de idioma entre medio, lo guardado queda
     * en el idioma anterior. Para reconocerlo igual, estas ayudas comparan con el mismo texto en los tres idiomas.
     */
    private static final Resources[] ALL=new Resources[3];
    /** El texto en inglés, español y portugués (sin argumentos; un texto con %1$s se compara solo hasta el primer %). */
    static String[] all(int id){
        Context c=app();String[] out=new String[SUPPORTED.length];
        for(int i=0;i<SUPPORTED.length;i++){
            Resources r=ALL[i];
            if(r==null){Configuration conf=new Configuration(c.getResources().getConfiguration());Locale l=locale(SUPPORTED[i]);conf.setLocale(l);
                if(Build.VERSION.SDK_INT>=24)conf.setLocales(new LocaleList(l));r=c.createConfigurationContext(conf).getResources();ALL[i]=r;}
            out[i]=r.getString(id);
        }
        return out;
    }
    /** El texto hasta su primer argumento ("Grabación %1$s" → "Grabación "), para comparar comienzos. */
    private static String head(String s){int cut=s.indexOf('%');return cut<0?s:s.substring(0,cut);}
    /** ¿text es este texto en alguno de los tres idiomas? */
    static boolean isAny(int id,String text){if(text==null)return false;for(String s:all(id))if(s.equals(text))return true;return false;}
    /** ¿text empieza con este texto (hasta su primer argumento) en alguno de los tres idiomas? */
    static boolean startsAny(String text,int id){if(text==null)return false;for(String s:all(id)){String h=head(s);if(!h.isEmpty()&&text.startsWith(h))return true;}return false;}
    /** ¿text contiene este texto (hasta su primer argumento) en alguno de los tres idiomas? Sin distinguir mayúsculas. */
    static boolean containsAny(String text,int id){
        if(text==null)return false;String t=text.toLowerCase(Locale.ROOT);
        for(String s:all(id)){String h=head(s).toLowerCase(Locale.ROOT);if(!h.isEmpty()&&t.contains(h))return true;}return false;
    }
    /** ¿text termina con este texto (sin argumentos) en alguno de los tres idiomas? */
    static boolean endsAny(String text,int id){if(text==null)return false;for(String s:all(id))if(!s.isEmpty()&&text.endsWith(s))return true;return false;}
    /** Las versiones del texto, escapadas para una expresión regular: "(?:Recording|Grabación|Gravação)". */
    static String anyRegex(int id){
        StringBuilder b=new StringBuilder("(?:");String[] all=all(id);
        for(int i=0;i<all.length;i++){if(i>0)b.append('|');b.append(java.util.regex.Pattern.quote(head(all[i]).trim()));}
        return b.append(')').toString();
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
}
