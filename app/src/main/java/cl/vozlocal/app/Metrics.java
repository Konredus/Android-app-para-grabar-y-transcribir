package cl.vozlocal.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Métricas de uso (0.8.0, pedido 2026-09-30): todo se calcula en el teléfono con lo que la app ya guarda (estado de
 * cada grabación, transcripciones, notas, 0-Inbox, momentos ★). No envía nada. Diseño: docs/diseno/SPEC-0.8b.md («metrics»).
 *
 * Qué se cuenta y cuándo (la regla es la misma en la pantalla, en la tarjeta de Ajustes y en las pruebas):
 * - Lo GRABADO (horas, palabras, embudo, mapa de días y horas, personas) va en el día en que se grabó ("created").
 * - Lo GASTADO va en el día en que se cobró: la transcripción al terminar ("doneAt"; si no, al pedirla) y la nota al
 *   armarse ("createdAt"). Así «US$ del mes» calza con lo que el proveedor cobra ese mes.
 * - Costo real = lo que informó OpenRouter (Pricing.real, la única regla: un 0 es «dato que no vino»). Si no hay real,
 *   el estimado de Pricing (duración × tarifa pública), siempre rotulado «≈». Un servidor propio no tiene tarifa: no
 *   se inventa un costo, se cuenta aparte ("unknown").
 * - Una versión anterior de «Volver a transcribir» que todavía existe también se pagó: su costo se suma.
 * - Las grabaciones de ejemplo ("demo") no son del usuario: no cuentan.
 *
 * Rendimiento: compute() lee disco y se llama fuera del hilo principal. Lo caro es leer cada transcripción (pueden ser
 * cientos de cientos de KB); su resumen (palabras y tiempo por persona) se guarda en memoria por archivo, con su fecha y
 * tamaño, y solo se relee si cambió.
 *
 * Privacidad: los nombres de las personas solo viajan de aquí a la pantalla. Nada de esto va a la bitácora ni a
 * Diagnostics (solo cuántas grabaciones y cuánto tardó el cálculo).
 */
final class Metrics {
    private Metrics(){}
    /** Palabras por minuto al teclear a mano: con esto se calcula el «tiempo ahorrado» (SPEC-0.8b). */
    static final int TYPING_WPM=40;
    /** Semanas del gráfico de barras (la actual es la última). */
    static final int WEEKS=8;
    static final Locale CL=new Locale("es","CL");
    /** Los tres cortes de la pantalla: los últimos 7 días (como «Tu semana» en Grabar), el mes calendario y todo. */
    enum Period{WEEK,MONTH,ALL}

    /** Uso de un modelo de transcripción (desglose de costos). */
    static final class ModelUse {
        final String provider,model,name;int count,unknown;long audioMs;double realUsd,estUsd;
        ModelUse(String provider,String model,String name){this.provider=provider;this.model=model;this.name=name;}
        double usd(){return realUsd+estUsd;}
    }
    /** Una persona con nombre en las transcripciones: cuánto habla y en cuántas grabaciones aparece. */
    static final class Person {
        final String name;long ms;int recordings;
        Person(String name){this.name=name;}
    }
    /** Todo lo de un período. Los ids de «a medio camino» van de la grabación más nueva a la más vieja. */
    static final class Totals {
        int recordings,transcribed,withNote,inInbox,marks,passes,unknown,notes;
        long audioMs,transcribedMs,words,billedMs,noteIn,noteOut;
        /** Transcripción: cobrado (real) y estimado. Notas: igual, aparte. */
        double realUsd,estUsd,noteReal,noteEst;
        final Set<Long> days=new HashSet<>();
        final List<String> pending=new ArrayList<>(),queued=new ArrayList<>(),failed=new ArrayList<>(),noNote=new ArrayList<>(),notSaved=new ArrayList<>();
        /** Minutos grabados y cantidad de grabaciones por día de la semana (0 = lunes) y hora en que se empezó a grabar. */
        final long[][] heatMs=new long[7][24];final int[][] heatCount=new int[7][24];
        final LinkedHashMap<String,ModelUse> models=new LinkedHashMap<>();
        private final Map<String,Person> people=new HashMap<>();
        double real(){return realUsd+noteReal;}
        double estimated(){return estUsd+noteEst;}
        double usd(){return real()+estimated();}
        /** Costo de transcribir por hora de audio (sin las notas: no dependen del audio). -1 si no hay costo conocido. */
        double usdPerHour(){double t=realUsd+estUsd;return billedMs<=0||t<=0?-1:t/(billedMs/3_600_000d);}
        /** Palabras por minuto de audio transcrito; -1 si no hay. */
        int wpm(){return transcribedMs<60_000||words<=0?-1:(int)Math.round(words/(transcribedMs/60_000d));}
        /** Lo que se habría tardado en escribir esas palabras a mano. */
        long savedMs(){return Math.round(words*60_000d/TYPING_WPM);}
        /** Personas de más a menos tiempo hablado. */
        List<Person> people(){List<Person> out=new ArrayList<>(people.values());out.sort((a,b)->a.ms!=b.ms?Long.compare(b.ms,a.ms):Integer.compare(b.recordings,a.recordings));return out;}
        /** Modelos de más a menos gasto (y, a igual gasto, de más a menos audio). */
        List<ModelUse> models(){List<ModelUse> out=new ArrayList<>(models.values());out.sort((a,b)->Double.compare(b.usd(),a.usd())!=0?Double.compare(b.usd(),a.usd()):Long.compare(b.audioMs,a.audioMs));return out;}
    }
    /** Resultado del cálculo: los tres períodos, las 8 semanas, la racha y algunos datos de Ajustes. */
    static final class Data {
        long now;int total;
        final Totals week=new Totals(),month=new Totals(),all=new Totals();
        /** Comienzo (lunes 00:00) de cada una de las 8 semanas; la última es la actual. */
        final long[] weekStarts=new long[WEEKS];final long[] weekMs=new long[WEEKS];final int[] weekCount=new int[WEEKS];
        final double[] weekReal=new double[WEEKS],weekEst=new double[WEEKS];
        /** Racha actual (días seguidos con grabaciones, hasta hoy o ayer), si ya grabaste hoy, y la mejor racha. */
        int streak,best;boolean today;
        int knownVoices;boolean inboxOn;
        /** Saldo de OpenRouter de la última comprobación válida en Ajustes (NaN si no hay) y cuándo fue. */
        double balance=Double.NaN;long balanceAt;
        boolean deep;
        Totals of(Period p){return p==Period.WEEK?week:p==Period.MONTH?month:all;}
    }

    // ---------- Resúmenes para otras pantallas ----------
    /** Cifra grande + rótulo + detalle: «12 h» «grabadas» «US$3,40 este mes». Para la tarjeta de Ajustes. */
    static final class Summary {
        final String figure,label,detail;
        Summary(String figure,String label,String detail){this.figure=figure;this.label=label;this.detail=detail;}
        String line(){return detail.isEmpty()?figure+" "+label:figure+" "+label+" · "+detail;}
    }
    /** Resumen de una línea para la tarjeta de Ajustes, p. ej. «12 h grabadas · US$3,40 este mes». Lee disco: llamar fuera del hilo principal. */
    static String summaryLine(Context c){Summary s=summary(c);return s==null?"":s.line();}
    /**
     * Lo mismo, en partes, para quien quiera mostrar la cifra en grande. Hace la pasada liviana (sin leer transcripciones):
     * la tarjeta de Ajustes se pinta en cada visita. null si algo falló (la tarjeta queda sin cifra, nunca se cae).
     */
    static Summary summary(Context c){try{return summary(compute(c,false));}catch(RuntimeException e){return null;}}
    static Summary summary(Data d){
        if(d.total==0)return new Summary("0","grabaciones","aquí verás tu voz en números");
        Totals m=d.month;double usd=m.usd();
        String detail=usd>0?(m.estimated()>0?"≈ ":"")+Pricing.usd(usd)+" este mes":count(d.total,"grabación","grabaciones");
        return new Summary(hours(d.all.audioMs),"grabadas",detail);
    }

    // ---------- Cálculo ----------
    /**
     * Calcula todo con los datos reales de la app. deep=false se salta las transcripciones (palabras y personas): sirve
     * para la tarjeta de Ajustes, que solo muestra horas y gasto.
     */
    static Data compute(Context c,boolean deep){
        Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;Pricing.attach(app);
        String me="";try{me=Voices.name(app);}catch(RuntimeException ignored){}
        boolean inbox=false;try{inbox=Inbox.configured(app);}catch(RuntimeException ignored){}
        if(deep)loadCache(app);
        Data d=compute(app,Recording.directory(app),System.currentTimeMillis(),me,inbox,deep);
        if(deep){try{d.knownVoices=Voices.list(app).size();}catch(RuntimeException ignored){}balance(app,d);saveCache(app);}
        return d;
    }

    /**
     * El cálculo, con todo explícito para poder probarlo con una carpeta de prueba (MetricsChecks) sin tocar los datos
     * reales: dir = carpeta de grabaciones; now = el «ahora»; me = tu nombre (tu voz no cuenta entre «las personas con
     * las que más conversas»); inboxOn = hay carpeta 0-Inbox elegida. El Context solo se usa para los estimados de costo
     * de OpenRouter (Pricing lee el catálogo de modelos guardado) y para nombrar los modelos.
     */
    static Data compute(Context c,File dir,long now,String me,boolean inboxOn,boolean deep){
        Data d=new Data();d.now=now;d.inboxOn=inboxOn;d.deep=deep;
        ZoneId zone=ZoneId.systemDefault();LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for(int i=0;i<WEEKS;i++)d.weekStarts[i]=monday.minusWeeks(WEEKS-1-i).atStartOfDay(zone).toInstant().toEpochMilli();
        long weeksEnd=monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long monthStart=today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(),weekStart=now-7L*86_400_000L;
        String mine=me==null?"":me.trim().toLowerCase(CL);
        File[] files=dir==null?null:dir.listFiles((x,name)->name.endsWith(".m4a"));
        // Primero lo liviano (fecha y duración) para recorrer de la más nueva a la más vieja: así las listas de «a medio
        // camino» quedan en el orden de la Biblioteca y «abrir la más reciente» es la primera.
        List<long[]> order=new ArrayList<>();List<String> ids=new ArrayList<>();
        if(files!=null)for(File audio:files){
            String id=audio.getName().substring(0,audio.getName().length()-4);
            if(!id.matches("[a-f0-9-]{36}")||id.equals(RecorderService.activeId))continue;
            long created=audio.lastModified(),duration=0;JSONObject meta=read(new File(dir,id+".json"));
            if(meta!=null){created=meta.optLong("created",created);duration=Math.max(0,meta.optLong("duration",0));}
            order.add(new long[]{created,duration,ids.size()});ids.add(id);
        }
        order.sort((a,b)->Long.compare(b[0],a[0]));
        TreeSet<Long> days=new TreeSet<>();
        for(long[] o:order){
            String id=ids.get((int)o[2]);long created=o[0],duration=o[1];
            JSONObject st=read(new File(dir,id+".sync.json"));if(st==null)st=new JSONObject();
            if(st.optBoolean("demo"))continue;
            File tf=new File(dir,id+".transcript.json");boolean transcribed=tf.isFile();
            Spoken sp=deep&&transcribed?spoken(tf):null;if(sp!=null&&sp.demo)continue;
            boolean note=new File(dir,id+".note.json").isFile();long inboxAt=st.optLong("inboxAt",0);
            RecState.Kind kind=RecState.of(st,transcribed).kind;JSONArray marks=st.optJSONArray("marks");
            ZonedDateTime at=Instant.ofEpochMilli(created).atZone(zone);long day=at.toLocalDate().toEpochDay();
            int dow=at.getDayOfWeek().getValue()-1,hour=at.getHour();
            d.total++;days.add(day);
            int w=bucket(d.weekStarts,weeksEnd,created);if(w>=0){d.weekMs[w]+=duration;d.weekCount[w]++;}
            for(Totals t:periods(d,created,weekStart,monthStart)){
                t.recordings++;t.audioMs+=duration;t.days.add(day);t.marks+=marks==null?0:marks.length();
                t.heatMs[dow][hour]+=duration;t.heatCount[dow][hour]++;
                if(transcribed){
                    t.transcribed++;t.transcribedMs+=duration;if(sp!=null)t.words+=sp.words;
                    if(note)t.withNote++;else t.noNote.add(id);
                    if(inboxAt>0)t.inInbox++;else t.notSaved.add(id);
                    if(sp!=null)for(Map.Entry<String,Long> e:sp.named.entrySet()){
                        String key=e.getKey().toLowerCase(CL);if(key.equals(mine))continue;
                        Person p=t.people.get(key);if(p==null){p=new Person(e.getKey());t.people.put(key,p);}
                        p.ms+=e.getValue();p.recordings++;
                    }
                }
                if(kind==RecState.Kind.QUEUED)t.queued.add(id);else if(kind==RecState.Kind.FAILED)t.failed.add(id);else if(kind==RecState.Kind.NEW)t.pending.add(id);
            }
            // Costos: la versión vigente y, si todavía existe, la anterior de «Volver a transcribir» (también se pagó).
            String tProvider="",tModel="";
            if(sp!=null){tProvider=sp.provider;tModel=sp.model;}
            JSONObject info=st.optJSONObject("retranscribe");JSONObject before=info==null?null:info.optJSONObject("before");
            if(before!=null&&!new File(dir,id+".transcript.prev.json").isFile())before=null;
            // Mientras la nueva versión espera en la cola, el estado todavía trae los datos de la pasada anterior (los mismos
            // que guardó «before»): se cuentan una sola vez. Al empezar la nueva, el motor los reinicia y ya no coinciden.
            boolean leftover=before!=null&&!transcribed&&st.optLong("doneAt",-1)==before.optLong("doneAt",-2)&&Pricing.real(st)==Pricing.real(before);
            if(!leftover)spend(c,d,st,transcribed,tProvider,tModel,duration,created,weekStart,monthStart,weeksEnd);
            if(before!=null)spend(c,d,before,true,"","",duration,created,weekStart,monthStart,weeksEnd);
            for(String suffix:new String[]{".note.json",".note.prev.json"}){File nf=new File(dir,id+suffix);if(nf.isFile())note(d,read(nf),created,weekStart,monthStart,weeksEnd);}
        }
        // Racha: días seguidos con alguna grabación hasta hoy. Si hoy aún no grabas, sigue viva desde ayer (hasta medianoche).
        long t0=today.toEpochDay();d.today=days.contains(t0);long from=d.today?t0:t0-1;
        while(days.contains(from-d.streak))d.streak++;
        int run=0;long prev=Long.MIN_VALUE;for(long x:days){run=x==prev+1?run+1:1;prev=x;d.best=Math.max(d.best,run);}
        return d;
    }

    /** Los períodos en que cae un momento: siempre «todo»; el mes calendario y los últimos 7 días si corresponde. */
    private static List<Totals> periods(Data d,long t,long weekStart,long monthStart){
        List<Totals> out=new ArrayList<>(3);out.add(d.all);
        if(t>=monthStart&&t<=d.now+86_400_000L)out.add(d.month);
        if(t>=weekStart&&t<=d.now+86_400_000L)out.add(d.week);
        return out;
    }
    /** Semana (0…7) en que cae t, o -1 si queda fuera de las 8 semanas. */
    static int bucket(long[] starts,long end,long t){if(t<starts[0]||t>=end)return -1;for(int i=starts.length-1;i>=0;i--)if(t>=starts[i])return i;return -1;}

    /**
     * Costo de una pasada de transcripción. st es el estado (o el «before» de la versión anterior): provider, model,
     * audioMs, costUsd, doneAt. Sin modelo guardado se usa el de la transcripción, y sin proveedor, OpenAI (lo único que
     * existía antes de la 0.8).
     */
    private static void spend(Context c,Data d,JSONObject st,boolean transcribed,String tProvider,String tModel,long duration,long created,long weekStart,long monthStart,long weeksEnd){
        double real=Pricing.real(st);if(real<0&&!transcribed)return;
        long at=st.optLong("doneAt",0);if(at<=0)at=st.optLong("queuedAt",0);if(at<=0)at=created;
        String provider=st.optString("provider","");if(provider.isEmpty())provider=tProvider==null||tProvider.isEmpty()?"openai":tProvider;
        String model=st.optString("model","");if(model.isEmpty())model=tModel==null?"":tModel;
        long audio=st.optLong("audioMs",0);if(audio<=0)audio=duration;
        double est=-1;if(real<0&&!model.isEmpty()){try{est=Pricing.estimate(c,provider,model,audio);}catch(RuntimeException ignored){}}
        String key=provider+"|"+model,name=null;
        for(Totals t:periods(d,at,weekStart,monthStart)){
            ModelUse u=t.models.get(key);if(u==null){if(name==null)name=modelName(c,provider,model);u=new ModelUse(provider,model,name);t.models.put(key,u);}
            t.passes++;u.count++;u.audioMs+=audio;
            if(real>0){t.realUsd+=real;u.realUsd+=real;t.billedMs+=audio;}
            else if(est>=0){t.estUsd+=est;u.estUsd+=est;t.billedMs+=audio;}
            else{t.unknown++;u.unknown++;}
        }
        int w=bucket(d.weekStarts,weeksEnd,at);if(w>=0){if(real>0)d.weekReal[w]+=real;else if(est>0)d.weekEst[w]+=est;}
    }
    /**
     * Costo y tokens de una nota. Cobrado si la nota lo dice ("costReal" con un monto mayor que 0: un 0 de OpenRouter es
     * un dato que no vino, la misma regla de Pricing.real). Si no, el estimado guardado o el de la tarifa del modelo.
     */
    private static void note(Data d,JSONObject n,long created,long weekStart,long monthStart,long weeksEnd){
        if(n==null)return;
        long at=n.optLong("createdAt",0);if(at<=0)at=created;
        JSONObject usage=n.optJSONObject("usage");long in=usage==null?0:Math.max(0,usage.optLong("input_tokens")),out=usage==null?0:Math.max(0,usage.optLong("output_tokens"));
        double cost=n.optDouble("costUsd",-1);if(Double.isNaN(cost))cost=-1;boolean real=n.optBoolean("costReal")&&cost>0;double est=-1;
        if(!real&&!n.optBoolean("costReal")&&cost>0)est=cost;
        else if(!real&&cost<0&&usage!=null){double e=Notes.usd(n.optString("model"),in,out);if(e>0)est=e;}
        for(Totals t:periods(d,at,weekStart,monthStart)){t.notes++;t.noteIn+=in;t.noteOut+=out;if(real)t.noteReal+=cost;else if(est>0)t.noteEst+=est;}
        int w=bucket(d.weekStarts,weeksEnd,at);if(w>=0){if(real)d.weekReal[w]+=cost;else if(est>0)d.weekEst[w]+=est;}
    }
    /** Nombre corto del modelo para el desglose de costos («MAI Transcribe 2», «GPT-4o Transcribe»). */
    static String modelName(Context c,String provider,String model){
        if(model==null||model.isEmpty())return "Modelo sin nombre";
        if("openrouter".equals(provider)){try{String n=Models.name(c,model);if(n!=null&&!n.isEmpty())return n;}catch(RuntimeException ignored){}return model;}
        switch(model){
            case "gpt-transcribe":return "GPT Transcribe";
            case "gpt-4o-transcribe":return "GPT-4o Transcribe";
            case "gpt-4o-mini-transcribe":return "GPT-4o Mini";
            case "gpt-4o-transcribe-diarize":return "GPT-4o Diarize";
            case "whisper-1":return "Whisper";
            default:return model;
        }
    }
    /** Nombre del servicio para mostrar bajo cada modelo. */
    static String service(String provider){return "openrouter".equals(provider)?"OpenRouter":"openai".equals(provider)?"OpenAI":"Tu servidor";}

    /**
     * Saldo de OpenRouter guardado por «Comprobar conexión» (Ajustes) o por la bienvenida, solo si esa comprobación es de
     * la clave vigente (la misma huella que usa Ajustes: proveedor + clave cifrada). No se consulta la red.
     */
    private static void balance(Context c,Data d){
        try{
            Settings s=new Settings(c);if(!s.openRouter()||!s.prefs.getBoolean("verifyOk",false))return;
            String target=s.provider()+"|"+s.prefs.getString(s.prefix()+"keyEncrypted","").hashCode();
            if(!target.equals(s.prefs.getString("verifyFor","")))return;
            double v=Double.parseDouble(s.prefs.getString("verifyBalance",""));if(Double.isNaN(v)||v<0)return;
            d.balance=v;d.balanceAt=s.prefs.getLong("verifyAt",0);
        }catch(RuntimeException ignored){}
    }

    // ---------- Transcripciones (con caché por archivo) ----------
    /** Resumen de una transcripción: palabras, quién habló cuánto (solo voces con nombre) y con qué se transcribió. */
    static final class Spoken {
        final long modified,length,words;final boolean demo;final String provider,model;
        /** Nombre visible → ms hablados. Tu voz guardada ("voice:me") no entra: no es alguien «con quien conversas». */
        final Map<String,Long> named;
        Spoken(long modified,long length,long words,boolean demo,String provider,String model,Map<String,Long> named){this.modified=modified;this.length=length;this.words=words;this.demo=demo;this.provider=provider;this.model=model;this.named=named;}
    }
    private static final Map<String,Spoken> SPOKEN=new ConcurrentHashMap<>();
    /** Lee (o toma de la caché) el resumen de una transcripción. Una ilegible cuenta como vacía (no detiene el cálculo). */
    static Spoken spoken(File f){
        String key=f.getAbsolutePath();long modified=f.lastModified(),length=f.length();
        Spoken cached=SPOKEN.get(key);if(cached!=null&&cached.modified==modified&&cached.length==length)return cached;
        JSONObject data=read(f);Spoken s;
        if(data==null)s=new Spoken(modified,length,0,false,"","",Collections.emptyMap());
        else{
            JSONArray segs=data.optJSONArray("segments");JSONObject names=data.optJSONObject("names");long words=0;Map<String,Long> named=new LinkedHashMap<>();
            // Mismo nombre = misma persona (regla de Transcript.applyNames): se suman sin distinguir mayúsculas.
            Map<String,String> first=new HashMap<>();
            for(int i=0;segs!=null&&i<segs.length();i++){
                JSONObject seg=segs.optJSONObject(i);if(seg==null)continue;String text=seg.optString("text");int n=countWords(text);if(n==0)continue;words+=n;
                String sp=seg.optString("speaker");if(Voices.ME.equals(sp))continue;
                String name=names==null?"":Voices.clean(names.optString(sp,""));if(name.isEmpty())continue;
                double start=seg.optDouble("start",0),end=seg.optDouble("end",start);long ms=Math.round(Math.max(0,end-start)*1000);
                String k=name.toLowerCase(CL);String shown=first.get(k);if(shown==null){shown=name;first.put(k,name);}
                named.merge(shown,ms,Long::sum);
            }
            s=new Spoken(modified,length,words,data.optBoolean("demo"),data.optString("provider",""),data.optString("model",""),named);
        }
        // Tope de la caché: con miles de archivos se vacía y se vuelve a llenar.
        if(SPOKEN.size()>4000)SPOKEN.clear();
        SPOKEN.put(key,s);dirty=true;return s;
    }

    /*
     * Caché en disco (metrics-spoken.json, en la carpeta de caché de la app): la primera vez que se abre «Tus métricas»
     * después de reiniciar el teléfono no hay que volver a leer cientos de transcripciones. Guarda lo mismo que la de
     * memoria (palabras, minutos por nombre, modelo) y queda en el almacenamiento privado de la app, igual que las
     * transcripciones; nunca va al informe de soporte. Si Android la borra para hacer espacio, solo se recalcula. No usa
     * FilesStore.write: eso avisaría a las pantallas de un cambio en las grabaciones que no ocurrió.
     */
    private static volatile boolean diskLoaded,dirty;
    private static File cacheFile(Context c){return new File(c.getCacheDir(),"metrics-spoken.json");}
    private static void loadCache(Context c){
        synchronized(SPOKEN){
            if(diskLoaded)return;diskLoaded=true;
            try{
                JSONObject items=new JSONObject(new String(new android.util.AtomicFile(cacheFile(c)).readFully(),java.nio.charset.StandardCharsets.UTF_8)).optJSONObject("items");
                if(items==null)return;
                for(Iterator<String> it=items.keys();it.hasNext();){
                    String key=it.next();JSONObject o=items.optJSONObject(key);if(o==null)continue;
                    Map<String,Long> named=new LinkedHashMap<>();JSONObject n=o.optJSONObject("n");
                    if(n!=null)for(Iterator<String> k=n.keys();k.hasNext();){String name=k.next();named.put(name,n.optLong(name));}
                    SPOKEN.putIfAbsent(key,new Spoken(o.optLong("m"),o.optLong("l"),o.optLong("w"),o.optBoolean("d"),o.optString("p"),o.optString("o"),named));
                }
            }catch(Exception ignored){/* sin caché (primera vez o ilegible): se recalcula */}
        }
    }
    private static void saveCache(Context c){
        if(!dirty)return;
        synchronized(SPOKEN){
            dirty=false;android.util.AtomicFile file=new android.util.AtomicFile(cacheFile(c));java.io.FileOutputStream out=null;
            try{
                JSONObject items=new JSONObject();
                for(Map.Entry<String,Spoken> e:SPOKEN.entrySet()){
                    // Solo las transcripciones que siguen existiendo: las de grabaciones borradas salen de la caché.
                    if(!new File(e.getKey()).isFile())continue;Spoken s=e.getValue();
                    items.put(e.getKey(),new JSONObject().put("m",s.modified).put("l",s.length).put("w",s.words).put("d",s.demo).put("p",s.provider).put("o",s.model).put("n",new JSONObject(s.named)));
                }
                out=file.startWrite();out.write(new JSONObject().put("v",1).put("items",items).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(out);
            }catch(Exception e){if(out!=null)file.failWrite(out);}
        }
    }
    /** Palabras de un texto: tramos sin espacios que tengan alguna letra o número («—» o «…» sueltos no cuentan). */
    static int countWords(String s){
        if(s==null)return 0;int n=0;boolean in=false,has=false;
        for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(Character.isWhitespace(ch)){if(in&&has)n++;in=false;has=false;}else{in=true;if(Character.isLetterOrDigit(ch))has=true;}}
        if(in&&has)n++;return n;
    }
    /** Lee un JSON guardado con AtomicFile (bajo el mismo candado que FilesStore.state); null si no existe o no se puede leer. */
    private static JSONObject read(File f){
        if(f==null||!f.isFile())return null;
        synchronized(FilesStore.LOCK){try{return FilesStore.read(f);}catch(Exception e){return null;}}
    }

    // ---------- Formatos (cifras chilenas: 12.480 palabras, 1,5 h) ----------
    /** Entero con punto de miles: 128.450. */
    static String number(long n){return String.format(CL,"%,d",n);}
    /** «1 grabación» / «12 grabaciones». */
    static String count(long n,String one,String many){return number(n)+" "+(n==1?one:many);}
    /** Horas para una cifra grande: «45 min», «1,5 h», «12 h». Menos de un minuto (pero algo): «< 1 min». */
    static String hours(long ms){
        if(ms<=0)return "0 h";long min=Math.round(ms/60_000d);if(min<1)return "< 1 min";if(min<60)return min+" min";
        double h=ms/3_600_000d;if(h>=10)return number(Math.round(h))+" h";
        String s=String.format(Locale.ROOT,"%.1f",h);if(s.endsWith(".0"))s=s.substring(0,s.length()-2);return s.replace('.',',')+" h";
    }
}
