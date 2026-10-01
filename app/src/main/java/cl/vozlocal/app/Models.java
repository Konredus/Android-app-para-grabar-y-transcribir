package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Modelos de OpenRouter (0.8.0): el catálogo que se actualiza solo, la «receta» de cada modelo de transcripción
 * (cómo pedirle que separe voces, cuánto audio acepta, en qué unidad cobra) y la elección «Automático (recomendado)».
 * Diseño e investigación: docs/PLAN-openrouter.md y docs/diseno/SPEC-0.8.md.
 *
 * FASE 0 (contrato): las firmas de este archivo son el contrato entre las partes. La parte «catalog» lo implementa
 * (catálogo, caché, precios, clave); puede AGREGAR métodos y completar la tabla, pero no cambiar estas firmas.
 *
 * Cómo funciona el catálogo (parte «catalog»):
 * - refresh() lee la lista pública de OpenRouter (sin clave), guarda solo lo que se usa de cada modelo en
 *   files/openrouter-models.json y deja en las preferencias qué modelo usa «Automático» (orAutoSpeakers / orAutoText).
 * - cached() arma la lista desde ese archivo con la tabla de recetas de ESTA versión de la app: si una actualización
 *   aprende a usar un modelo nuevo, la lista guardada lo refleja sin volver a descargar.
 * - El catálogo no dice qué modelo es mejor ni en qué unidad cobra. Por eso «Automático» sale de un orden de preferencia
 *   fijo (modelos de la tabla, cuyo uso está documentado; ninguno se probó todavía con audio real) y el precio por hora
 *   solo se calcula para los modelos cuya unidad se conoce. Los textos de la app dicen «recomendado» o «conocido», no «probado».
 * - Nada de esto está probado contra la red real (no había clave al escribirlo): todo campo puede faltar o venir con
 *   otro tipo, y una respuesta rara nunca reemplaza una lista buena.
 */
final class Models {
    static final String BASE="https://openrouter.ai/api/v1";
    /** Valor de las preferencias orSpeakersModel/orTextModel para «Automático (recomendado)». */
    static final String AUTO="auto";
    /** Recomendados mientras no se haya leído el catálogo (verificados el 30-09-2026). */
    static final String DEFAULT_SPEAKERS="microsoft/mai-transcribe-2",DEFAULT_TEXT="microsoft/mai-transcribe-2";
    /** Catálogo público de modelos de transcripción, más nuevos primero. No lleva clave. */
    static final String CATALOG=BASE+"/models?output_modalities=transcription&sort=newest";
    /** El catálogo guardado se considera al día durante 24 h. */
    static final long FRESH_MS=24*60*60_000L;
    /** «Automático» no elige un modelo que OpenRouter retira en menos de 30 días: quedaría botado a mitad de mes. */
    static final long SOON_MS=30L*24*60*60_000L;

    /** Cómo se pide la separación de voces en POST /audio/transcriptions. */
    enum Diarize{
        /** No separa voces. */
        NONE,
        /** provider.options.azure.diarization.enabled=true (ejemplo oficial de MAI-Transcribe 2). */
        AZURE,
        /** provider.options.deepgram.diarize=true. */
        DEEPGRAM,
        /** Parámetro genérico diarize:true (exige verbose_json; 400 si el modelo no puede). */
        GENERIC,
        /** Siempre activa, con marcas «<|speaker:N|>» dentro del texto (Fish Audio Transcribe 1 Pro). */
        INLINE
    }
    /** Unidad de pricing.prompt en el catálogo (no viene indicada: depende del modelo). */
    enum Unit{SECOND,HOUR,TOKEN,UNKNOWN}

    /** Receta de un modelo de transcripción. Nunca es null: un modelo desconocido recibe la receta por defecto. */
    static final class Recipe{
        final String id;final Diarize diarize;
        /** true si la receta sabe pedir voces (diarize != NONE). */
        final boolean diarizes;
        /** Acepta response_format=verbose_json (tramos con tiempos). Si no, se pide json (solo texto). */
        final boolean verbose;
        /** Audio máximo por envío con voces, en ms (p. ej. Gemini: 30 min). */
        final long maxMs;
        final Unit unit;
        /** El modo de pedir voces está documentado por OpenRouter (true) o es una suposición razonable (false). */
        final boolean verified;
        Recipe(String id,Diarize diarize,boolean verbose,long maxMs,Unit unit,boolean verified){this.id=id;this.diarize=diarize;this.diarizes=diarize!=Diarize.NONE;this.verbose=verbose;this.maxMs=maxMs;this.unit=unit;this.verified=verified;}
    }
    private static final long MIN=60_000L;
    private static final Recipe[] TABLE={
        new Recipe("microsoft/mai-transcribe-2",Diarize.AZURE,true,110*MIN,Unit.HOUR,true),
        new Recipe("deepgram/nova-3",Diarize.DEEPGRAM,true,110*MIN,Unit.SECOND,true),
        new Recipe("google/gemini-3.5-transcribe",Diarize.GENERIC,true,30*MIN,Unit.TOKEN,false),
        new Recipe("x-ai/grok-stt-1.0",Diarize.GENERIC,true,110*MIN,Unit.SECOND,false),
        new Recipe("fish-audio/transcribe-1-pro",Diarize.INLINE,true,60*MIN,Unit.SECOND,false),
        new Recipe("mistralai/voxtral-mini-transcribe",Diarize.GENERIC,true,110*MIN,Unit.SECOND,false),
        new Recipe("openai/gpt-transcribe",Diarize.NONE,true,110*MIN,Unit.SECOND,true),
        new Recipe("microsoft/mai-transcribe-1.5",Diarize.NONE,false,110*MIN,Unit.HOUR,true),
        new Recipe("openai/gpt-4o-transcribe",Diarize.NONE,false,110*MIN,Unit.TOKEN,true),
        new Recipe("openai/gpt-4o-mini-transcribe",Diarize.NONE,false,110*MIN,Unit.TOKEN,true),
    };
    /**
     * Nombre para mostrar y precio de referencia (US$ por hora de audio) de los modelos de la tabla, en el mismo orden.
     * El precio es el de docs/PLAN-openrouter.md (30-09-2026; los gpt-4o, la tarifa pública de OpenAI): solo se usa cuando
     * el catálogo no permite calcularlo (nunca se leyó, o el modelo cobra por tokens) y se muestra con «≈». "" = sin dato.
     */
    private static final String[][] INFO={
        {"MAI Transcribe 2","0.10"},
        {"Nova-3","0.26"},
        {"Gemini 3.5 Transcribe","0.30"},
        {"Grok STT 1.0","0.10"},
        {"Fish Transcribe 1 Pro","0.36"},
        {"Voxtral Mini Transcribe","0.18"},
        {"GPT Transcribe","0.27"},
        {"MAI Transcribe 1.5",""},
        {"GPT-4o Transcribe","0.36"},
        {"GPT-4o Mini Transcribe","0.18"},
    };
    /**
     * Orden de preferencia de «Automático»: modelos cuyo modo de uso está documentado, del mejor al respaldo. Para voces,
     * MAI-Transcribe 2 (menos errores y más barato) y Deepgram Nova-3; para solo texto se suman los que no separan voces.
     * Si ninguno está en el catálogo se usa cualquier otro de la tabla (ver auto()).
     */
    private static final String[] PREFER_SPEAKERS={"microsoft/mai-transcribe-2","deepgram/nova-3"};
    private static final String[] PREFER_TEXT={"microsoft/mai-transcribe-2","deepgram/nova-3","openai/gpt-transcribe","microsoft/mai-transcribe-1.5"};

    /** Receta del modelo (por id exacto); si no está en la tabla, una por defecto: solo texto, verbose_json, unidad desconocida. */
    static Recipe recipe(String modelId){
        for(Recipe r:TABLE)if(r.id.equals(modelId))return r;
        return new Recipe(modelId==null?"":modelId,Diarize.NONE,true,110*MIN,Unit.UNKNOWN,false);
    }
    /** Posición del modelo en la tabla local (el orden en que se ofrecen los ya probados); Integer.MAX_VALUE si no está. */
    static int rank(String modelId){for(int i=0;i<TABLE.length;i++)if(TABLE[i].id.equals(modelId))return i;return Integer.MAX_VALUE;}
    /** ¿Está en la tabla local (probado o documentado)? Si no: «Nuevo · sin probar». */
    static boolean known(String modelId){return rank(modelId)!=Integer.MAX_VALUE;}
    /** Precio de referencia (US$ por hora) de un modelo de la tabla; -1 si no hay. */
    static double reference(String modelId){int i=rank(modelId);if(i==Integer.MAX_VALUE||INFO[i][1].isEmpty())return -1;return Double.parseDouble(INFO[i][1]);}
    /**
     * Id del modelo a usar: la elección del usuario o, con «Automático», el recomendado vigente (lo escribe refresh() en
     * las preferencias orAutoSpeakers/orAutoText tras validar contra el catálogo; mientras tanto, los DEFAULT_*).
     */
    static String chosen(Settings s,boolean speakers){
        String v=speakers?s.orSpeakersModel():s.orTextModel();
        if(v.isEmpty()||AUTO.equals(v))v=automatic(s,speakers);
        return v;
    }
    /** Lo que usa hoy «Automático», lo tenga elegido el usuario o no. */
    static String automatic(Settings s,boolean speakers){String v=s.prefs.getString(speakers?"orAutoSpeakers":"orAutoText","");return v.isEmpty()?(speakers?DEFAULT_SPEAKERS:DEFAULT_TEXT):v;}

    /** Un modelo del catálogo, listo para mostrar. */
    static final class Model{
        final String id,name;
        /** Segundos Unix en que OpenRouter lo agregó (no es la fecha de lanzamiento ni mide calidad). */
        final long created;
        /** US$ por hora de audio; -1 si no se puede saber desde el catálogo (p. ej. cobra por tokens). */
        final double perHour;
        final Recipe recipe;
        /** Está en la tabla local (probado o documentado). Si no: «Nuevo · sin probar». */
        final boolean known;
        /** Segundos Unix en que OpenRouter lo retira; 0 si no tiene fecha. */
        final long expires;
        /** perHour no salió del catálogo sino del precio de referencia de la tabla: se muestra con «≈». */
        final boolean approx;
        Model(String id,String name,long created,double perHour,Recipe recipe,boolean known,long expires){this(id,name,created,perHour,recipe,known,expires,false);}
        Model(String id,String name,long created,double perHour,Recipe recipe,boolean known,long expires,boolean approx){this.id=id;this.name=name;this.created=created;this.perHour=perHour;this.recipe=recipe;this.known=known;this.expires=expires;this.approx=approx;}
    }

    // ---------- Catálogo guardado ----------
    private static final Object LOCK=new Object();
    /** Lo último leído del archivo, para no abrirlo en cada consulta de precio; se rehace si el archivo cambió. */
    private static List<Model> memo;private static long memoAt,memoStamp=-1,memoSize=-1;
    /** El catálogo guardado: {"v":1,"fetchedAt":ms,"models":[{id,name,created,prompt,expires}]}. */
    static File file(Context c){return new File(c.getFilesDir(),"openrouter-models.json");}
    /** Olvida lo leído en memoria (pruebas: después de tocar el archivo a mano). */
    static void forget(){synchronized(LOCK){memo=null;memoAt=0;memoStamp=-1;memoSize=-1;}}
    private static void load(Context c){
        File f=file(c);
        synchronized(LOCK){
            long stamp=f.lastModified(),size=f.length();
            if(memo!=null&&stamp==memoStamp&&size==memoSize)return;
            List<Model> list=new ArrayList<>();long at=0;
            // Un archivo dañado se trata como «nunca se leyó»: la próxima actualización lo reemplaza.
            if(f.isFile())try{JSONObject saved=FilesStore.read(f);at=saved.optLong("fetchedAt",0);list=build(saved.optJSONArray("models"),System.currentTimeMillis());}catch(Exception ignored){}
            memo=list;memoAt=list.isEmpty()?0:at;memoStamp=stamp;memoSize=size;
        }
    }
    /** Último catálogo guardado en el teléfono (más nuevos primero); lista vacía si nunca se leyó. No usa la red. */
    static List<Model> cached(Context c){load(c);synchronized(LOCK){return new ArrayList<>(memo);}}
    /** Momento (ms) de la última lectura correcta del catálogo; 0 si nunca. */
    static long fetchedAt(Context c){load(c);synchronized(LOCK){return memoAt;}}
    /** ¿Toca volver a leerlo? Nunca se leyó, tiene más de 24 h o su fecha quedó en el futuro (cambio de hora del teléfono). */
    static boolean stale(Context c){long at=fetchedAt(c),age=System.currentTimeMillis()-at;return at<=0||age>FRESH_MS||age<-60_000;}
    /** La tabla local como lista, sin red: lo que se ofrece mientras el catálogo nunca se ha podido leer. */
    static List<Model> builtin(){
        List<Model> list=new ArrayList<>();
        for(int i=0;i<TABLE.length;i++){double h=reference(TABLE[i].id);list.add(new Model(TABLE[i].id,INFO[i][0],0,h,TABLE[i],true,0,h>0));}
        return list;
    }
    static Model find(List<Model> list,String modelId){if(list!=null&&modelId!=null)for(Model m:list)if(m.id.equals(modelId))return m;return null;}

    /**
     * Lee el catálogo público (sin clave): GET BASE+"/models?output_modalities=transcription&sort=newest", lo guarda y
     * actualiza los recomendados de «Automático». Llamar fuera del hilo principal.
     * Si falla (sin red, otro formato, lista vacía) lanza y la lista guardada queda como estaba.
     */
    static List<Model> refresh(Context c,HttpApi http)throws Exception{
        HttpApi.Response res=http.request("GET",CATALOG,"",null,null,null);
        if(res.code<200||res.code>=300)throw new IOException("OpenRouter no entregó la lista de modelos ("+res.code+").");
        long now=System.currentTimeMillis();JSONArray rows=trim(res.json());List<Model> list=build(rows,now);
        // Una lista vacía es una respuesta rara (cambió el formato o el filtro), no «ya no hay modelos»: no se guarda.
        if(list.isEmpty())throw new IOException("La lista de modelos de OpenRouter llegó vacía.");
        synchronized(LOCK){File f=file(c);FilesStore.write(f,new JSONObject().put("v",1).put("fetchedAt",now).put("models",rows));memo=list;memoAt=now;memoStamp=f.lastModified();memoSize=f.length();}
        Settings s=new Settings(c);SharedPreferences.Editor e=s.prefs.edit();String voices=auto(list,true,now),text=auto(list,false,now);
        // Si «Automático» cambia de modelo, se recuerda el anterior: un trabajo a medias lo termina con él (ver resume()).
        String oldVoices=automatic(s,true),oldText=automatic(s,false);
        if(!oldVoices.equals(voices.isEmpty()?DEFAULT_SPEAKERS:voices))e.putString("orAutoBeforeSpeakers",oldVoices);
        if(!oldText.equals(text.isEmpty()?DEFAULT_TEXT:text))e.putString("orAutoBeforeText",oldText);
        // Sin candidato se borra la preferencia: «Automático» vuelve a los DEFAULT_* en vez de quedarse con uno que ya no está.
        if(voices.isEmpty())e.remove("orAutoSpeakers");else e.putString("orAutoSpeakers",voices);
        if(text.isEmpty())e.remove("orAutoText");else e.putString("orAutoText",text);
        e.apply();
        Diagnostics.event("catalog_refreshed",null,"count",list.size());
        return new ArrayList<>(list);
    }
    /** El catálogo tal como llega de OpenRouter, convertido en lista (sin guardar nada). now en ms. */
    static List<Model> parse(JSONObject catalog,long now)throws Exception{return build(trim(catalog),now);}

    /** autor/modelo. Deja fuera los alias («~autor/modelo-latest») y las variantes («…:free»), que repiten un modelo con otro nombre. */
    private static final String ID="[A-Za-z0-9_.-]{1,40}/[A-Za-z0-9_.-]{1,78}";
    /** Con el filtro de transcripción llegan unas pocas decenas. Sobre esto, el servidor ignoró el filtro y mandó todo. */
    private static final int FILTERED_MAX=80,KEEP_MAX=200;
    /**
     * Deja de cada modelo solo lo que se usa, con tipos fijos: id, nombre, fecha en segundos, precio tal como viene
     * (pricing.prompt, sin unidad) y fecha de retiro en segundos. Tolera campos faltantes, null o con otro tipo.
     */
    private static JSONArray trim(JSONObject catalog)throws Exception{
        JSONArray out=new JSONArray(),data=catalog==null?null:catalog.optJSONArray("data");if(data==null)return out;
        boolean unfiltered=data.length()>FILTERED_MAX;List<String> seen=new ArrayList<>();
        for(int i=0;i<data.length()&&out.length()<KEEP_MAX;i++){
            JSONObject m=data.optJSONObject(i);if(m==null)continue;
            String id=m.isNull("id")?"":m.optString("id").trim();
            if(!id.matches(ID)||seen.contains(id))continue;
            if(unfiltered&&!known(id)&&!transcribes(m))continue;
            seen.add(id);
            String name=m.isNull("name")?"":m.optString("name").replaceAll("[\\p{Cntrl}]"," ").trim();if(name.length()>80)name=name.substring(0,80).trim();
            JSONObject row=new JSONObject().put("id",id).put("name",name.isEmpty()?id:name).put("created",seconds(m.opt("created"))).put("expires",expires(m));
            JSONObject pricing=m.optJSONObject("pricing");double prompt=pricing==null||pricing.isNull("prompt")?Double.NaN:pricing.optDouble("prompt",Double.NaN);
            if(!Double.isNaN(prompt)&&!Double.isInfinite(prompt))row.put("prompt",prompt);
            out.put(row);
        }
        return out;
    }
    /** ¿El modelo declara que transcribe? (architecture.output_modalities o architecture.modality). */
    private static boolean transcribes(JSONObject m){
        JSONObject a=m.optJSONObject("architecture");if(a==null)return false;
        JSONArray out=a.optJSONArray("output_modalities");
        if(out!=null)for(int i=0;i<out.length();i++)if("transcription".equalsIgnoreCase(out.optString(i)))return true;
        return a.optString("modality").toLowerCase(Locale.ROOT).contains("transcription");
    }
    /** Un momento como segundos Unix, venga como número o texto, en segundos o milisegundos; 0 si no se entiende. */
    private static long seconds(Object v){
        double n;
        if(v instanceof Number)n=((Number)v).doubleValue();
        else if(v instanceof String)try{n=Double.parseDouble(((String)v).trim());}catch(NumberFormatException e){return 0;}
        else return 0;
        if(Double.isNaN(n)||n<=0)return 0;
        return n>100_000_000_000d?(long)(n/1000):(long)n;
    }
    /**
     * Fecha en que OpenRouter retira el modelo, en segundos Unix; 0 si no tiene. El campo documentado es
     * expiration_date ("2026-12-31" o null); se aceptan nombres parecidos y también un número o una fecha con hora.
     */
    private static long expires(JSONObject m){
        for(String key:new String[]{"expiration_date","expires_at","deprecation_date","expires"}){
            if(m.isNull(key))continue;Object v=m.opt(key);
            long n=seconds(v);if(n>0)return n;
            String text=String.valueOf(v).trim();
            if(text.length()>=10)try{
                java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);day.setLenient(false);day.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d=day.parse(text.substring(0,10));if(d!=null&&d.getTime()>0)return d.getTime()/1000;
            }catch(java.text.ParseException ignored){}
        }
        return 0;
    }
    /** US$ por hora creíbles para transcribir. Fuera de este rango, la unidad de la receta ya no es la del catálogo. */
    private static final double MIN_HOUR=0.005,MAX_HOUR=20;
    /**
     * pricing.prompt llevado a US$ por hora de audio según la unidad de la receta: por segundo ×3600, por hora tal cual;
     * por tokens o desconocida no se puede (-1). Un resultado absurdo (US$360 la hora) también es -1: mejor no mostrar
     * precio que mostrar uno inventado.
     */
    static double hourly(double prompt,Unit unit){
        if(Double.isNaN(prompt)||prompt<=0)return -1;
        double h=unit==Unit.SECOND?prompt*3600:unit==Unit.HOUR?prompt:-1;
        return h>=MIN_HOUR&&h<=MAX_HOUR?h:-1;
    }
    /** De las filas guardadas a modelos, con la tabla de esta versión. Los ya retirados no se ofrecen. */
    private static List<Model> build(JSONArray rows,long now){
        List<Model> list=new ArrayList<>();if(rows==null)return list;
        for(int i=0;i<rows.length();i++){
            JSONObject m=rows.optJSONObject(i);if(m==null)continue;
            String id=m.optString("id");if(!id.matches(ID)||find(list,id)!=null)continue;
            long expires=m.optLong("expires",0);if(expires>0&&expires*1000L<=now)continue;
            Recipe recipe=recipe(id);double perHour=hourly(m.optDouble("prompt",Double.NaN),recipe.unit);boolean approx=false;
            if(perHour<0){perHour=reference(id);approx=perHour>0;}
            String name=m.optString("name",id);
            list.add(new Model(id,name.isEmpty()?id:name,m.optLong("created",0),perHour,recipe,known(id),expires,approx));
        }
        // Más nuevos primero. El orden es estable: si OpenRouter ya los manda así, no cambia nada.
        Collections.sort(list,(a,b)->Long.compare(b.created,a.created));
        return list;
    }
    /** US$ por minuto de audio según el catálogo guardado; -1 si no se sabe. */
    static double perMinute(Context c,String modelId){
        if(modelId==null)return -1;
        Model m=find(cached(c),modelId);double h=m!=null?m.perHour:reference(modelId);
        return h>0?h/60:-1;
    }

    // ---------- «Automático (recomendado)» ----------
    /** ¿OpenRouter lo retira en menos de 30 días? */
    static boolean retiresSoon(Model m,long now){return m.expires>0&&m.expires*1000L-now<SOON_MS;}
    private static boolean usable(Model m,boolean speakers,long now){return m!=null&&m.known&&(!speakers||m.recipe.diarizes)&&!retiresSoon(m,now);}
    /**
     * El modelo que usa «Automático» con este catálogo: el primero del orden de preferencia que esté en la lista y no se
     * retire en 30 días. Si no queda ninguno de los documentados, cualquier otro de la tabla que sirva (para voces, uno
     * que sepa separarlas), en el orden de la tabla: es mejor un modelo menos probado que uno que ya no existe. "" si
     * tampoco hay: quien llama vuelve a los DEFAULT_*.
     */
    static String auto(List<Model> catalog,boolean speakers,long now){
        for(String id:speakers?PREFER_SPEAKERS:PREFER_TEXT)if(usable(find(catalog,id),speakers,now))return id;
        for(Recipe r:TABLE)if(usable(find(catalog,r.id),speakers,now))return r.id;
        return "";
    }
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    /**
     * Pone al día el catálogo en segundo plano, a lo más una vez al día, para que «Automático» cambie solo si OpenRouter
     * retira un modelo aunque nadie abra la hoja de modelos. Solo para quien transcribe con OpenRouter; la descarga, solo
     * si ya tiene clave. De paso deja leída la lista guardada (los nombres de name(id) salen de ahí).
     * Si falla no avisa: se reintenta a las 6 horas, o al abrir la hoja (que sí muestra el error).
     */
    static void refreshIfStale(Context context){
        Context a=context.getApplicationContext();Context c=a==null?context:a;
        Settings s=new Settings(c);if(!s.openRouter()||!BUSY.compareAndSet(false,true))return;
        Thread t=new Thread(()->{
            try{
                long now=System.currentTimeMillis(),tried=s.prefs.getLong("orCatalogTried",0);
                if(!stale(c)||!s.hasKey()||(tried<=now&&now-tried<6*60*60_000L))return;
                s.prefs.edit().putLong("orCatalogTried",now).apply();
                HttpApi http=new HttpApi();http.readTimeoutMs=30000;refresh(c,http);
            }catch(Exception ignored){}finally{BUSY.set(false);}
        },"catalogo-openrouter");
        t.setPriority(Thread.MIN_PRIORITY);t.start();
    }

    // ---------- Nombres para mostrar ----------
    private static String slug(String id){int cut=id==null?-1:id.indexOf('/');return id==null?"":cut<0?id:id.substring(cut+1);}
    /** Nombre corto: el del catálogo sin el autor («Microsoft: MAI Transcribe 2» → «MAI Transcribe 2»). */
    static String label(Model m){
        String n=m.name==null?"":m.name.trim();int cut=n.indexOf(": ");if(cut>0&&cut<n.length()-2)n=n.substring(cut+2).trim();
        if(!n.isEmpty()&&!n.equals(m.id))return n;
        int i=rank(m.id);return i==Integer.MAX_VALUE?slug(m.id):INFO[i][0];
    }
    /** Quién lo hace: lo que el catálogo pone antes de «: » o, si no viene, el autor del id con su nombre conocido. */
    static String vendor(Model m){
        String n=m.name==null?"":m.name;int cut=n.indexOf(": ");if(cut>0&&cut<=30&&!n.equals(m.id))return n.substring(0,cut).trim();
        int slash=m.id.indexOf('/');String author=slash<0?m.id:m.id.substring(0,slash);
        switch(author){
            case "microsoft":return "Microsoft";case "deepgram":return "Deepgram";case "google":return "Google";case "x-ai":return "xAI";
            case "fish-audio":return "Fish Audio";case "mistralai":return "Mistral";case "openai":return "OpenAI";case "assemblyai":return "AssemblyAI";case "meta":return "Meta";
            default:return author.isEmpty()?"":Character.toUpperCase(author.charAt(0))+author.substring(1);
        }
    }
    /** Nombre corto de un modelo por su id: el del catálogo guardado, el de la tabla o, si no, lo que va después de «/». */
    static String name(Context c,String modelId){if(c!=null)load(c);return name(modelId);}
    /**
     * Igual, para quien no tiene un Context a mano (p. ej. SettingsActivity.modelSummary(Settings)): usa la lista que ya
     * esté leída en memoria. Si todavía nadie la leyó, los modelos de la tabla salen igual con su nombre; uno nuevo, con
     * lo que va después de «/».
     */
    static String name(String modelId){
        if(modelId==null||modelId.isEmpty())return "";
        List<Model> list;synchronized(LOCK){list=memo;}
        Model m=find(list,modelId);if(m!=null)return label(m);
        int i=rank(modelId);return i==Integer.MAX_VALUE?slug(modelId):INFO[i][0];
    }

    /** Lo que OpenRouter dice de una clave (GET BASE+"/key"). */
    static final class KeyInfo{
        final String label;
        /** US$ usados con esta clave; NaN si no viene. */
        final double usage;
        /** US$ que le quedan a la clave (su límite menos lo usado); NaN si la clave no tiene límite o no viene. */
        final double remaining;
        final boolean freeTier;
        KeyInfo(String label,double usage,double remaining,boolean freeTier){this.label=label;this.usage=usage;this.remaining=remaining;this.freeTier=freeTier;}
    }
    private static double number(JSONObject o,String key){return o==null||o.isNull(key)?Double.NaN:o.optDouble(key,Double.NaN);}
    /**
     * Comprueba la clave; lanza HttpApi.UserAction si no es válida (o falta) y IOException si no se pudo saber (sin red,
     * servicio caído, respuesta inesperada). Llamar fuera del hilo principal.
     * Hace una sola llamada (GET BASE+"/key") y lo único que decide es el código HTTP: si la respuesta trae otro
     * formato, la clave igual vale y los montos quedan en NaN. La etiqueta (label) es un dato de la cuenta: no se registra.
     */
    static KeyInfo checkKey(HttpApi http,String key)throws Exception{
        String k=key==null?"":key.trim();
        // El nombre del servicio es el mismo de todos los errores de OpenRouter (HttpApi.OPENROUTER), y la app se identifica
        // con los mismos encabezados que al transcribir y al armar la nota (OpenRouterClient.headers()).
        String service=HttpApi.OPENROUTER;
        if(k.isEmpty())throw new HttpApi.UserAction("Falta la clave de "+service+". Agrégala en Ajustes.");
        HttpApi.Response res=http.request("GET",BASE+"/key",k,null,null,OpenRouterClient.headers());
        // HttpApi.UserAction solo cuando OpenRouter rechaza la clave: la bienvenida y Ajustes muestran ese mensaje como
        // «clave mala». Dice «clave»: con esa palabra Ajustes ofrece «Revisar la clave» en vez de «Reintentar».
        if(res.code==401||res.code==403)throw new HttpApi.UserAction("La clave de "+service+" no es válida o fue revocada. Revísala en Ajustes.");
        // Todo lo demás (servicio caído, límite de pedidos, una respuesta que no se esperaba) no dice nada de la clave: sale
        // como IOException, igual que un corte de red, y quien llama lo muestra como «no se pudo comprobar ahora».
        if(res.code==408||res.code==429||res.code>=500)throw new IOException(service+" no está disponible temporalmente ("+res.code+").");
        if(res.code<200||res.code>=300)throw new IOException(service+" respondió algo inesperado (HTTP "+res.code+"). Vuelve a intentarlo en un momento.");
        JSONObject root;try{root=res.json();}catch(Exception e){root=new JSONObject();}
        JSONObject d=root.optJSONObject("data");if(d==null)d=root;
        double usage=number(d,"usage"),limit=number(d,"limit"),left=number(d,"limit_remaining");
        if(Double.isNaN(left)&&!Double.isNaN(limit)&&!Double.isNaN(usage))left=limit-usage;
        return new KeyInfo(d.isNull("label")?"":d.optString("label"),usage,left,d.optBoolean("is_free_tier",false));
    }
    /**
     * Saldo de la cuenta en US$ (créditos cargados menos lo usado): GET BASE+"/credits". NaN si OpenRouter no lo entrega
     * a esta clave (puede pedir una clave de administración), si la cuenta nunca cargó créditos o ante cualquier falla.
     * Es un dato de cortesía para «Comprobar conexión»: nunca lanza y no decide si la clave vale.
     */
    static double credits(HttpApi http,String key){
        try{
            HttpApi.Response res=http.request("GET",BASE+"/credits",key,null,null,OpenRouterClient.headers());if(res.code!=200)return Double.NaN;
            JSONObject d=res.json().optJSONObject("data");double total=number(d,"total_credits"),used=number(d,"total_usage");
            return Double.isNaN(total)||Double.isNaN(used)||total<=0?Double.NaN:total-used;
        }catch(Exception e){return Double.NaN;}
    }
    /** Saldo que se muestra al comprobar la clave. Es un dato de la cuenta: queda solo en las preferencias, nunca en el diagnóstico. */
    static final class Balance{
        /**
         * US$ que quedan para gastar: el menor entre lo que le queda a la clave (si tiene tope) y el saldo de la cuenta;
         * NaN si no se supo, y también si la cuenta aún no tiene créditos (noCredits).
         */
        final double left;
        /**
         * La cuenta todavía no carga créditos: OpenRouter la marca is_free_tier y su saldo no se pudo leer. El tope de la
         * clave («quedan US$5») no es plata disponible, así que manda este aviso.
         */
        final boolean noCredits;
        Balance(double left,boolean noCredits){this.left=left;this.noCredits=noCredits;}
    }
    /**
     * Junta lo que dice la clave (GET /key) con el saldo de la cuenta (GET /credits; NaN si no se supo). Una sola regla
     * para Ajustes → «Comprobar conexión» y para la bienvenida (hallazgo de la revisión: la bienvenida mostraba el tope
     * de la clave como saldo, y Ajustes, otra cosa).
     * Una cuenta sin créditos deja el saldo en NaN, igual que la bienvenida (OnboardingActivity.valid): si se guardara el
     * tope de la clave, «Tus métricas» y cualquier pantalla que lea verifyBalance lo mostrarían como plata disponible.
     */
    static Balance balance(KeyInfo info,double account){
        double key=info==null?Double.NaN:info.remaining;boolean known=!Double.isNaN(account)&&!Double.isInfinite(account);
        if(Double.isInfinite(key))key=Double.NaN;
        boolean none=info!=null&&info.freeTier&&!known;
        double left=none?Double.NaN:!known?key:Double.isNaN(key)?account:Math.min(key,account);
        return new Balance(left,none);
    }
    /** Igual, pidiendo el saldo de la cuenta (de cortesía: nunca lanza). Llamar fuera del hilo principal. */
    static Balance balance(HttpApi http,String key,KeyInfo info){return balance(info,credits(http,key));}

    // ---------- Trabajos a medias ----------
    /**
     * Modelo con que seguir una transcripción por partes que quedó a medias. Si «Automático» cambió de modelo entre dos
     * intentos (refresh() lo movió porque OpenRouter lo retira en menos de 30 días o salió de la lista), las partes ya
     * pagadas se hicieron con el anterior: cambiar de modelo obligaría a reenviarlas y pagarlas de nuevo (hallazgo de la
     * revisión). Mientras el anterior siga en la lista guardada (y separe voces si se piden), se termina con él.
     * Solo cuando el trabajo usaba justo el modelo del que se movió «Automático» (refresh() lo deja en orAutoBefore*):
     * si fue la persona quien cambió de modelo, manda su elección. Con un modelo elegido a mano, sin partes listas o con
     * otro proveedor, manda chosen(). state: el estado de la grabación (FilesStore.state). Lo usa el motor al armar la
     * configuración de cada intento.
     */
    static String resume(Context c,Settings s,boolean speakers,JSONObject state){
        String now=chosen(s,speakers),pick=speakers?s.orSpeakersModel():s.orTextModel();
        if(state==null||!(pick.isEmpty()||AUTO.equals(pick))||!"openrouter".equals(state.optString("provider"))||state.optInt("blocksDone",0)<=0)return now;
        if(state.has("speakers")&&state.optBoolean("speakers")!=speakers)return now;
        String before=state.optString("model","");
        if(before.isEmpty()||before.equals(now)||!before.equals(s.prefs.getString(speakers?"orAutoBeforeSpeakers":"orAutoBeforeText","")))return now;
        Model m=find(cached(c),before);
        return m==null||(speakers&&!m.recipe.diarizes)?now:before;
    }

    // ---------- Notas (chat completions de OpenRouter) ----------
    /** Alias que OpenRouter resuelve siempre a la última versión de cada familia. El primero es el recomendado. */
    static final String[] NOTE_MODELS={"~anthropic/claude-sonnet-latest","~openai/gpt-luna-latest","~google/gemini-flash-latest"};
    static final String[] NOTE_NAMES={"Claude Sonnet (el más nuevo)","GPT Luna (el más nuevo)","Gemini Flash (el más nuevo)"};
    static final String NOTE_DEFAULT=NOTE_MODELS[0];
    private Models(){}
}
