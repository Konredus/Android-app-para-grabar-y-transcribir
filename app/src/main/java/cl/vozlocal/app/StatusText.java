package cl.vozlocal.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lo que las pantallas leen de los textos del motor (0.9.0). El motor (Pipeline, Transcriber, TranscribeService,
 * PipelineJob, OrAudio, HttpApi, Models…) escribe estados, esperas, errores y la bitácora de cada grabación como texto;
 * el detalle, la Biblioteca, la bienvenida y Ajustes los vuelven a leer para decidir qué mostrar («¿espera Wi-Fi?»,
 * «¿ya se envió?», «¿el error habla de la clave?»). Antes esas lecturas estaban repartidas por las pantallas, comparando
 * frases en español.
 *
 * Con tres idiomas, quien escribe y quien lee tienen que ponerse de acuerdo en cada idioma: por eso todas las lecturas
 * viven aquí, junto al motor que escribe esos textos. Lo guardado (la bitácora, el último error) puede estar en el idioma
 * de cuando se escribió: las lecturas lo reconocen en cualquiera de los tres. Nunca comparan palabras escritas aquí: leen
 * los mismos recursos con que el motor escribe (strings_engine.xml), con Lang.startsAny / containsAny / isAny cuando
 * basta el comienzo del texto, y con like / startsLike cuando hay argumentos al medio («Parte %1$d de %2$d lista»).
 *
 * Las pantallas llaman a estas funciones (RecordingActivity.human, Next.working… delegan aquí) y no comparan textos del
 * motor por su cuenta.
 */
final class StatusText {
    private StatusText(){}

    // ---------- Esperas (Pipeline.blocker) ----------
    /**
     * El botón de una grabación pedida (0.8.0, tercera ronda): «Transcribiendo…» solo si es ESTA la que se procesa ahora
     * (mine: Pipeline.processing, que cuenta también la espera entre intentos); si no, por qué espera (blocker:
     * Pipeline.blocker con su id) o «En cola…». Antes decía «Transcribiendo…» con la grabación en cola, esperando Wi-Fi o
     * el cargador; y en cada espera para reintentar pasaba a «En cola…» (Transcriber.currentId queda en null entre intentos).
     */
    static String working(boolean again,boolean mine,String blocker){
        if(mine)return Lang.str(again?R.string.eng_btn_working_again:R.string.eng_btn_working);
        if(blocker==null)return Lang.str(R.string.eng_btn_queued);
        // «Wi-Fi» se escribe igual en los tres idiomas.
        if(Pipeline.isWifiWait(blocker)||blocker.contains("Wi-Fi"))return Lang.str(R.string.eng_btn_wait_wifi);
        if(Lang.containsAny(blocker,R.string.eng_wait_charger))return Lang.str(R.string.eng_btn_wait_charger);
        if(Lang.containsAny(blocker,R.string.eng_wait_internet))return Lang.str(R.string.eng_btn_wait_connection);
        return Lang.str(Lang.containsAny(blocker,R.string.eng_wait_battery)?R.string.eng_btn_battery_low:R.string.eng_btn_queued);
    }
    /** El aviso breve al pedir una transcripción. detail: se muestra en el detalle (sin el paréntesis del Wi-Fi). */
    static String queuedToast(String doing,String blocker,boolean behind,boolean detail){
        if(blocker!=null)return Lang.str(R.string.eng_queued_wait,detail?inDetail(blocker):blocker);
        return behind?Lang.str(R.string.eng_queued_behind):Lang.str(R.string.eng_toast_doing,doing);
    }
    /**
     * La nota de la tarjeta según qué pasa con ESTA grabación (0.8.0, tercera ronda). mine: es la que se procesa ahora;
     * running: hay un trabajo andando; other: ese trabajo está con otra grabación (RecordingActions.behind: la transcribe o
     * espera para reintentarla); slow: lleva más de 15 min en el mismo
     * paso; blocker: por qué espera esta grabación (Pipeline.blocker con su id) o null. Antes bastaba un trabajo andando
     * con cualquiera: una grabación en cola o esperando Wi-Fi decía «Puedes cerrar la app» y, a los 15 min, «Este paso
     * tarda más de lo normal… se corta y se reintenta solo», sin estar en ningún paso.
     */
    static String waitingNote(boolean mine,boolean running,boolean other,boolean slow,String blocker){
        if(mine)return Lang.str(slow?R.string.eng_note_slow:R.string.eng_note_close_app);
        if(blocker!=null)return Lang.str(R.string.eng_note_waiting,waitingFor(blocker));
        if(other)return Lang.str(R.string.eng_note_behind);
        return Lang.str(running?R.string.eng_note_close_app:R.string.eng_note_not_started);
    }
    /**
     * Lo que espera un motivo, para «Esperando: …»: sin la palabra de espera del comienzo («esperando», «waiting for»,
     * «aguardando», en cualquiera de los tres idiomas) ni el paréntesis del final. «esperando Wi-Fi (…)» → «Wi-Fi».
     */
    static String waitingFor(String blocker){
        String s=blocker;
        for(String word:Lang.all(R.string.eng_waiting_word))if(s.startsWith(word+" ")){s=s.substring(word.length()+1);break;}
        return s.replaceFirst(" \\(.*$","");
    }
    /** Una espera en la lista de la Biblioteca: «En cola · esperando Wi-Fi», sin el paréntesis. */
    static String queuedLine(String blocker){return blocker!=null?Lang.str(R.string.eng_queued_wait,blocker.replaceFirst(" \\(.*$","")):Lang.str(R.string.eng_queued);}

    // ---------- Bitácora y estado (FilesStore.state: "status", "log") ----------
    /** Líneas de la bitácora que dicen que la grabación todavía espera (en cola, en espera, en pausa por algo que falta). */
    private static final int[] WAITS={R.string.eng_queued_wait,R.string.eng_queued_behind,R.string.eng_queued_starting,R.string.eng_log_hold_recording,R.string.eng_log_wifi_wait,R.string.eng_log_paused};
    /** ¿Esta línea de la bitácora es de espera (no de trabajo)? En cualquiera de los tres idiomas. */
    static boolean waitLine(String m){
        if(m==null)return false;if(Lang.startsAny(m,R.string.eng_queued))return true;
        for(int id:WAITS)if(startsLike(m,id))return true;
        return false;
    }
    /** Cuándo empezó a trabajar de verdad (no el tiempo esperando Wi-Fi o el cargador). */
    static long startedAt(JSONObject st){
        JSONArray log=st.optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;if(!waitLine(e.optString("m")))return e.optLong("t");}
        return st.optLong("queuedAt",System.currentTimeMillis());
    }
    /** La última línea de la bitácora en palabras simples («parte» en vez de «bloque», sin tiempos técnicos). */
    static String human(String m){
        if(m==null||m.trim().isEmpty())return Lang.str(R.string.eng_queued);
        // Bitácoras de versiones anteriores a la 0.8.0, que solo existieron en español: decían «bloque» donde hoy el motor
        // dice «parte» (en los tres idiomas). Una línea de hoy no tiene nada que cambiar aquí. Solo palabras completas: antes
        // «pantalla bloqueada» y «aunque bloquees» salían como «pantalla parteada» y «aunque partees» (y en portugués,
        // «bloquear o celular» habría salido mal).
        String s=inDetail(m).replaceAll("Bloque (\\d+) de (\\d+) listo","Parte $1 de $2 lista").replaceAll("Bloque (\\d+) enviado","Parte $1 enviada")
            .replaceAll("\\bEl bloque\\b","La parte").replaceAll("\\bdel bloque\\b","de la parte").replaceAll("\\bel bloque\\b","la parte").replaceAll("\\blos bloques\\b","las partes")
            .replaceAll("\\bbloques\\b","partes").replaceAll("\\bBloque\\b","Parte").replaceAll("\\bbloque\\b","parte");
        String[] parts=s.split(" · ");StringBuilder b=new StringBuilder(parts[0].trim());
        for(int i=1;i<parts.length;i++){String x=parts[i].trim();if(x.isEmpty()||detail(x))continue;if(b.length()+x.length()>110)break;b.append(" · ").append(x);}
        return b.toString();
    }
    /** ¿Este trozo de una línea es un detalle técnico que el titular no muestra («tardó …», «se envían …», «tiempo total …»)? */
    private static boolean detail(String x){return Lang.startsAny(x,R.string.eng_frag_took)||Lang.startsAny(x,R.string.eng_frag_parallel)||Lang.startsAny(x,R.string.eng_frag_total);}
    /**
     * Un texto de espera de Wi-Fi tal como se ve en el detalle: sin «(ahora usas datos móviles; puedes usarlos igual desde
     * el detalle de la grabación)» de Pipeline.wifiWait() ni el «: puedes usarlos … desde su detalle» de la bitácora del
     * motor (eng_log_mobile_hint), en cualquiera de los tres idiomas. Aquí sobran: el botón «Usar datos móviles ahora» está
     * a la vista. Lo demás queda igual (la Biblioteca corta el paréntesis a su manera, queuedLine). Lo usan el titular, la
     * bitácora y los avisos breves del detalle.
     */
    static String inDetail(String m){
        if(m==null)return "";
        String s=m;
        for(Pattern wifi:wifiParens())s=wifi.matcher(s).replaceAll("$1");
        for(String hint:Lang.all(R.string.eng_log_mobile_hint))s=s.replace(hint,"");
        return s;
    }
    private static volatile Pattern[] wifiParens;
    /** «esperando Wi-Fi (…)» en los tres idiomas: el comienzo de wait_wifi (lo que va antes del paréntesis) y su paréntesis. */
    private static Pattern[] wifiParens(){
        Pattern[] p=wifiParens;if(p!=null)return p;
        String[] all=Lang.all(R.string.wait_wifi);p=new Pattern[all.length];
        for(int i=0;i<all.length;i++){int cut=all[i].indexOf(" (");p[i]=Pattern.compile("("+Pattern.quote(cut>0?all[i].substring(0,cut):all[i])+") \\([^)]*\\)");}
        wifiParens=p;return p;
    }
    /** ¿La bitácora ya dice que se envió algo («Parte 1 enviada», «Audio enviado»)? */
    static boolean uploadedInLog(JSONObject st){
        JSONArray log=st.optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null&&sentLine(e.optString("m")))return true;}
        return false;
    }
    private static boolean sentLine(String m){
        return startsLike(m,R.string.eng_log_part_sent)||startsLike(m,R.string.eng_log_audio_sent)||startsLike(m,R.string.eng_log_part_sent_server)||startsLike(m,R.string.eng_log_audio_sent_server);
    }
    /** ¿Esta línea de la bitácora es la de «Transcripción lista…»? */
    static boolean transcriptionDone(String m){return Lang.startsAny(m,R.string.eng_done_title);}
    /** ¿El estado dice que se está preparando (convirtiendo) el audio? */
    static boolean preparing(String status){
        return startsLike(status,R.string.eng_st_preparing_audio)||startsLike(status,R.string.eng_st_preparing_part)||startsLike(status,R.string.eng_st_preparing_send);
    }

    // ---------- Notificación de avance (Transcriber.build) ----------
    /**
     * ¿El texto de la notificación de avance dice una espera? («Esperando Wi-Fi…», «… se retoma sola…», «… se reintenta
     * solo»): mientras tanto no se envía nada. Lo usa Transcriber.waiting.
     */
    static boolean paused(String text){
        return like(text,R.string.eng_notif_wifi_wait)||like(text,R.string.eng_notif_hold)||like(text,R.string.eng_notif_cut_retry)||like(text,R.string.eng_notif_attempt_retry);
    }

    // ---------- Errores de la clave (HttpApi, Models, Notes) ----------
    /** ¿El error habla de la clave? Así un fallo guardado de «Comprobar conexión» se trata como rechazo de la clave. */
    static boolean aboutKey(String message){return Lang.containsAny(message,R.string.key_word);}
    /** El error sin el «Revísala en Ajustes.» del final (la bienvenida corrige la clave en el paso anterior, no en Ajustes). */
    static String withoutSettingsHint(String message){
        String m=message==null?"":message;for(String hint:Lang.all(R.string.key_fix_in_settings))m=m.replace(" "+hint,"");return m.trim();
    }

    // ---------- Textos con argumentos, en cualquiera de los tres idiomas ----------
    /** Argumento de formato: %1$s, %2$d, %%… (el tipo es la última letra). */
    private static final Pattern ARG=Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");
    /** Las expresiones de cada recurso, una por idioma (los textos no cambian mientras corre la app). */
    private static final Map<Integer,Pattern[]> PATTERNS=new ConcurrentHashMap<>();
    /**
     * El recurso en los tres idiomas como expresiones regulares: el texto fijo tal cual y cada argumento, cualquier valor
     * («Parte %1$d de %2$d lista» → «Parte \d+ de \d+ lista»).
     */
    private static Pattern[] patterns(int id){
        Pattern[] p=PATTERNS.get(id);if(p!=null)return p;
        String[] all=Lang.all(id);p=new Pattern[all.length];
        for(int i=0;i<all.length;i++){
            String s=all[i];Matcher m=ARG.matcher(s);StringBuilder b=new StringBuilder();int at=0;
            while(m.find()){
                if(m.start()>at)b.append(Pattern.quote(s.substring(at,m.start())));
                String type=m.group(2);b.append("%".equals(type)?"%":"d".equals(type)?"-?\\d+":".*?");at=m.end();
            }
            if(at<s.length())b.append(Pattern.quote(s.substring(at)));
            p[i]=Pattern.compile(b.toString(),Pattern.DOTALL);
        }
        PATTERNS.put(id,p);return p;
    }
    /** ¿text es este texto, con cualquier valor en sus argumentos, en alguno de los tres idiomas? */
    static boolean like(String text,int id){if(text==null)return false;for(Pattern p:patterns(id))if(p.matcher(text).matches())return true;return false;}
    /** ¿text empieza con este texto (con cualquier valor en sus argumentos) en alguno de los tres idiomas? */
    static boolean startsLike(String text,int id){if(text==null)return false;for(Pattern p:patterns(id))if(p.matcher(text).lookingAt())return true;return false;}
}
