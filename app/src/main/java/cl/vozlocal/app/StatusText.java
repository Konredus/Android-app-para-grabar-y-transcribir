package cl.vozlocal.app;

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
 * de cuando se escribió: las lecturas lo reconocen en cualquiera de los tres (Lang.startsAny, Lang.containsAny…).
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
        if(mine)return again?"Volviendo a transcribir…":"Transcribiendo…";
        if(blocker==null)return "En cola…";
        if(blocker.contains("Wi-Fi"))return "Esperando Wi-Fi…";
        if(blocker.contains("cargador"))return "Esperando el cargador…";
        if(blocker.contains("internet"))return "Esperando conexión…";
        return blocker.startsWith("batería baja")?"Batería baja · en espera…":"En cola…";
    }
    /** El aviso breve al pedir una transcripción. detail: se muestra en el detalle (sin el paréntesis del Wi-Fi). */
    static String queuedToast(String doing,String blocker,boolean behind,boolean detail){
        if(blocker!=null)return "En cola · "+(detail?inDetail(blocker):blocker);
        return behind?"En cola · empieza cuando termine la transcripción en curso":doing+" · sigue aunque bloquees el teléfono";
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
        if(mine)return slow?"Este paso tarda más de lo normal. Si no avanza, se corta y se reintenta solo; si no resulta, te aviso.":"Puedes cerrar la app: te aviso cuando esté lista.";
        if(blocker!=null)return "Esperando: "+blocker.replaceFirst("^esperando ","").replaceFirst(" \\(.*$","")+". Empieza sola cuando se cumpla; puedes cerrar la app.";
        if(other)return "En cola: empieza cuando termine la transcripción en curso. Puedes cerrar la app.";
        return running?"Puedes cerrar la app: te aviso cuando esté lista.":"Android aún no la empieza. Se hará sola, o toca «Empezar ahora».";
    }
    /** Una espera en la lista de la Biblioteca: «En cola · esperando Wi-Fi», sin el paréntesis. */
    static String queuedLine(String blocker){return blocker!=null?"En cola · "+blocker.replaceFirst(" \\(.*$",""):"En cola";}

    // ---------- Bitácora y estado (FilesStore.state: "status", "log") ----------
    /** Cuándo empezó a trabajar de verdad (no el tiempo esperando Wi-Fi o el cargador). */
    static long startedAt(JSONObject st){
        JSONArray log=st.optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;String m=e.optString("m");if(!m.startsWith("En cola")&&!m.startsWith("En espera")&&!m.contains("esperando"))return e.optLong("t");}
        return st.optLong("queuedAt",System.currentTimeMillis());
    }
    /** La última línea de la bitácora en palabras simples («parte» en vez de «bloque», sin tiempos técnicos). */
    static String human(String m){
        if(m==null||m.trim().isEmpty())return "En cola";
        String s=inDetail(m).replaceAll("Bloque (\\d+) de (\\d+) listo","Parte $1 de $2 lista").replaceAll("Bloque (\\d+) enviado","Parte $1 enviada")
            .replace("El bloque","La parte").replace("del bloque","de la parte").replace("el bloque","la parte").replace("los bloques","las partes").replace("bloques","partes").replace("Bloque","Parte").replace("bloque","parte");
        String[] parts=s.split(" · ");StringBuilder b=new StringBuilder(parts[0].trim());
        for(int i=1;i<parts.length;i++){String x=parts[i].trim();if(x.isEmpty()||x.startsWith("tardó")||x.startsWith("se envían")||x.startsWith("tiempo total"))continue;if(b.length()+x.length()>110)break;b.append(" · ").append(x);}
        return b.toString();
    }
    /**
     * Un texto de espera de Wi-Fi tal como se ve en el detalle: sin «(ahora usas datos móviles; puedes usarlos igual desde
     * el detalle de la grabación)» de Pipeline.wifiWait() ni el «: puedes usarlos … desde su detalle» de la bitácora del
     * motor. Aquí sobran: el botón «Usar datos móviles ahora» está a la vista. Lo demás queda igual (la Biblioteca corta el
     * paréntesis a su manera, queuedLine). Lo usan el titular, la bitácora y los avisos breves del detalle.
     */
    static String inDetail(String m){
        if(m==null)return "";
        return m.replaceAll("(esperando Wi-Fi) \\([^)]*\\)","$1").replaceAll(": puedes usarlos [^·]*desde su detalle","");
    }
    /** ¿La bitácora ya dice que se envió algo («Parte 1 enviada», «Audio enviado»)? */
    static boolean uploadedInLog(JSONObject st){
        JSONArray log=st.optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null&&e.optString("m").contains("enviado"))return true;}
        return false;
    }
    /** ¿Esta línea de la bitácora es la de «Transcripción lista…»? */
    static boolean transcriptionDone(String m){return m!=null&&m.startsWith("Transcripción lista");}
    /** ¿El estado dice que se está preparando (convirtiendo) el audio? */
    static boolean preparing(String status){return status!=null&&status.startsWith("Preparando");}

    // ---------- Errores de la clave (HttpApi, Models, Notes) ----------
    /** ¿El error habla de la clave? Así un fallo guardado de «Comprobar conexión» se trata como rechazo de la clave. */
    static boolean aboutKey(String message){return Lang.containsAny(message,R.string.key_word);}
    /** El error sin el «Revísala en Ajustes.» del final (la bienvenida corrige la clave en el paso anterior, no en Ajustes). */
    static String withoutSettingsHint(String message){
        String m=message==null?"":message;for(String hint:Lang.all(R.string.key_fix_in_settings))m=m.replace(" "+hint,"");return m.trim();
    }
}
