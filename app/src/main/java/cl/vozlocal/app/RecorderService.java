package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaRecorder;
import android.os.*;
import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Grabación en primer plano. Acciones de intent:
 * START (extra opcional «title»), PAUSE (alterna; con extra «toggle»=false solo pausa), RESUME (solo reanuda),
 * MARK (★; extras opcionales «label» y «t», ms de grabación), TITLE («title») y STOP. El extra «source»="notification" indica que el toque
 * vino de la notificación: ahí la grabadora confirma ★ con una vibración corta (en la app vibra el botón).
 */
public class RecorderService extends Service {
    /** Idioma de la app (Lang): textos y notificaciones en el idioma elegido, aunque el teléfono esté en otro. */
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Lang.wrap(base));}
    static volatile String activeId;
    static volatile boolean paused;
    static volatile String error;
    /** Última grabación guardada; la pantalla de inicio la consume para ofrecer los siguientes pasos. */
    static volatile String lastSavedId;
    static volatile String activeTitle;
    /** Duración mínima de una grabación; por debajo se descarta como toque accidental. */
    static final long MIN_MS = 3000;
    /** Dos ★ sin palabra a menos de esto cuentan como uno (doble toque). */
    static final long MARK_DEBOUNCE_MS = 1000;
    /** Aviso breve para la pantalla de inicio (p. ej. grabación descartada por corta). */
    static volatile String notice;
    private static volatile long startedAtMs;
    /** elapsedRealtime del inicio de la grabación actual (0 si no se graba): la UI ignora Detener durante el primer segundo. */
    static long startedAt(){return activeId==null?0:startedAtMs;}

    /** Momentos ★ de la grabación en curso, en memoria. t: ms de grabación (sin pausas, misma escala que {@link #elapsed()}). */
    private static final class Mark { final long t; String label; Mark(long t,String label){this.t=t;this.label=label;} }
    private static final ArrayList<Mark> MARKS = new ArrayList<>();
    private static volatile long lastMark;
    /** Momentos ★ marcados en la grabación en curso (0 si no se graba). */
    static int marksCount(){synchronized(MARKS){return MARKS.size();}}
    /** Tiempo de grabación (ms, sin pausas; misma escala que {@link #elapsed()}) del último ★, o 0 si no hay. */
    static long lastMarkAt(){return lastMark;}
    /** Tiempos (ms de grabación) de los ★ de la grabación en curso, en orden: para dibujarlos sobre la onda en vivo. */
    static long[] markTimes(){synchronized(MARKS){long[] t=new long[MARKS.size()];for(int i=0;i<t.length;i++)t[i]=MARKS.get(i).t;return t;}}

    private static volatile long accumulated, started;
    private MediaRecorder recorder;
    private Recording recording;
    private PowerManager.WakeLock wakeLock;
    /**
     * Wake lock con plazo (0.9.0, Google Play no acepta uno tomado sin tope): cada toma vence sola a los 10 min y, mientras
     * se graba, se renueva cada 5. Al detener se suelta como siempre. Sin contar referencias: cada toma renueva el plazo.
     */
    static final long WAKE_MS = 10 * 60_000L, WAKE_RENEW_MS = 5 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable keepAwake = new Runnable() { @Override public void run() { PowerManager.WakeLock w = wakeLock; if (recorder != null && w != null) { if (!paused) w.acquire(WAKE_MS); handler.postDelayed(this, WAKE_RENEW_MS); } } };
    private static RecorderService instance;

    /*
     * 0.9.6: medidor del micrófono. El servicio es el único que lee getMaxAmplitude() (cada lectura reinicia el pico: si la
     * pantalla también leía, se robaban los picos). Cada segundo cuenta silencio y saturación, y avisa si el micrófono quedó
     * mudo (una llamada, otra app con prioridad o el interruptor de privacidad de Android 12+ lo dejan en ceros sin error).
     */
    /** Pico del micrófono (0-32767) de la última lectura; la pantalla dibuja la onda con esto. */
    private static volatile int level;
    /** Aviso para la pantalla y la notificación mientras el micrófono no capta nada (null si está bien). */
    static volatile String micWarning;
    /** Bajo esto, el segundo cuenta como «casi no se oye». Una voz a un par de metros pasa de 1000. */
    static final int QUIET_LEVEL = 300, CLIP_LEVEL = 32000;
    /** Segundos seguidos en cero absoluto (no es silencio de la sala: el micrófono no entrega nada) antes de avisar. */
    static final int MUTE_WARN_S = 15;
    /** Espacio libre: bajo el primero se avisa; bajo el segundo se detiene y se guarda lo grabado. */
    static final long LOW_SPACE = 50L * 1024 * 1024, STOP_SPACE = 15L * 1024 * 1024;
    static final long METER_MS = 60;
    private int seconds, quietSeconds, clipSeconds, muteRun, secondPeak; private long secondStart, lastDiskCheck; private boolean silenced, warned, lowSpace;
    private final Runnable meter = new Runnable() { @Override public void run() { tick(); if (recorder != null) handler.postDelayed(this, METER_MS); } };
    static int amplitude() { return instance != null && instance.recorder != null && !paused ? level : 0; }
    private void tick() {
        MediaRecorder r = recorder; if (r == null) return;
        if (paused) { level = 0; secondStart = SystemClock.elapsedRealtime(); return; }
        int a; try { a = r.getMaxAmplitude(); } catch (RuntimeException e) { a = 0; }
        level = a; secondPeak = Math.max(secondPeak, a);
        long now = SystemClock.elapsedRealtime(); if (secondStart == 0) secondStart = now;
        if (now - secondStart < 1000) return;
        secondStart = now; seconds++;
        if (secondPeak < QUIET_LEVEL) quietSeconds++;
        if (secondPeak >= CLIP_LEVEL) clipSeconds++;
        muteRun = secondPeak == 0 ? muteRun + 1 : 0; secondPeak = 0;
        if (Build.VERSION.SDK_INT >= 29) { try { android.media.AudioRecordingConfiguration conf = r.getActiveRecordingConfiguration(); silenced = conf != null && conf.isClientSilenced(); } catch (RuntimeException e) { silenced = false; } }
        if (now - lastDiskCheck >= 60_000) { lastDiskCheck = now; checkSpace(); if (recorder == null) return; }
        String warning = silenced || muteRun >= MUTE_WARN_S ? Lang.str(this, R.string.eng_rec_warn_mute) : lowSpace ? Lang.str(this, R.string.eng_rec_warn_space) : null;
        if (!java.util.Objects.equals(warning, micWarning)) {
            micWarning = warning; refresh();
            if (warning != null && !warned) { warned = true; alert(); Diagnostics.event("recording_warning", activeId, "reason", silenced ? "silenced" : lowSpace ? "space" : "mute", "elapsed_ms", elapsed()); }
            if (warning == null) warned = false;
        }
    }
    /** Poco espacio: avisa; casi nada: detiene y guarda lo grabado (mejor que un archivo cortado a la mitad). */
    private void checkSpace() {
        long free = Recording.directory(this).getUsableSpace();
        lowSpace = free < LOW_SPACE;
        if (free < STOP_SPACE) { Diagnostics.event("recording_warning", activeId, "reason", "space_stop", "free", free); error = Lang.str(this, R.string.eng_rec_err_space); finishRecording(); stopSelf(); }
    }
    /** Vibración más larga que la de ★: algo anda mal con la grabación. */
    private void alert() {
        try {
            Vibrator v = Build.VERSION.SDK_INT >= 31 ? getSystemService(VibratorManager.class).getDefaultVibrator() : getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 180, 120, 180}, -1));
        } catch (RuntimeException ignored) { }
    }
    /** Lo que midió el medidor, para avisar antes de gastar en transcribir una grabación muda. */
    private JSONObject meterJson() {
        try { return new JSONObject().put("seconds", seconds).put("quiet", quietSeconds).put("clip", clipSeconds); } catch (Exception e) { return new JSONObject(); }
    }
    /** ¿Casi todo es silencio? Necesita al menos 10 s medidos (una grabación importada no tiene medición). */
    static boolean mostlySilent(JSONObject state) {
        JSONObject m = state == null ? null : state.optJSONObject("level");
        if (m == null) return false;
        int s = m.optInt("seconds", 0); return s >= 10 && m.optInt("quiet", 0) >= s * 0.9;
    }
    static long elapsed() { return accumulated + (activeId != null && !paused ? SystemClock.elapsedRealtime() - started : 0); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel channel = new NotificationChannel("recording", Lang.str(this, R.string.eng_channel_recording), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "STOP" : intent.getAction();
        boolean fromNotification = intent != null && "notification".equals(intent.getStringExtra("source"));
        String source = fromNotification ? "notification" : "app";
        // PAUSE sin más alterna (así lo usaba la app); se registra como RESUME cuando reanuda, para distinguirlos.
        if ("PAUSE".equals(action) && paused && intent.getBooleanExtra("toggle", true)) action = "RESUME";
        if (!"MARK".equals(action)) Diagnostics.event("recorder_action", activeId, "action", action, "source", source);
        if ("START".equals(action) && recorder == null) startRecording(intent.getStringExtra("title"));
        else if ("PAUSE".equals(action) && recorder != null) pause();
        else if ("RESUME".equals(action) && recorder != null) resume();
        else if ("MARK".equals(action)) mark(intent, fromNotification);
        else if ("TITLE".equals(action) && recording != null) { String t = intent.getStringExtra("title"); if (t != null && !t.trim().isEmpty()) { recording.title = t.trim().substring(0, Math.min(120, t.trim().length())); activeTitle = recording.title; } }
        else if ("STOP".equals(action)) { finishRecording(); if (savingId == null) stopSelf(); }
        // Un toque que llega sin grabación en curso (p. ej. ★ desde una notificación vieja) no deja el servicio vivo.
        if (recorder == null && savingId == null && !"STOP".equals(action)) stopSelf(startId);
        return START_NOT_STICKY;
    }

    /** Notificación de grabación: tiempo en vivo, Pausar/Reanudar, ★ Marcar y Detener, también en la pantalla de bloqueo. */
    static Notification notification(Context c, boolean isPaused, long elapsedMs, int marks) { return notification(c, isPaused, elapsedMs, marks, null); }
    /** warning: el micrófono no capta nada o queda poco espacio (0.9.6); reemplaza el texto y se ve en la pantalla bloqueada. */
    static Notification notification(Context c, boolean isPaused, long elapsedMs, int marks, String warning) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = command(c, 1, new Intent(c, RecorderService.class).setAction("STOP"));
        PendingIntent toggle = isPaused ? command(c, 3, new Intent(c, RecorderService.class).setAction("RESUME"))
            : command(c, 2, new Intent(c, RecorderService.class).setAction("PAUSE").putExtra("toggle", false));
        PendingIntent mark = command(c, 4, new Intent(c, RecorderService.class).setAction("MARK"));
        String marked = marks > 0 ? Lang.plural(c, R.plurals.eng_rec_marks, marks) : null;
        Notification.Builder b = new Notification.Builder(c, "recording").setSmallIcon(cl.vozlocal.app.R.drawable.ic_notification).setColor(0xFF2F6B58)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (isPaused) b.setContentTitle(Lang.str(c, R.string.eng_rec_paused_title, Recording.time(elapsedMs)))
            .setContentText(marked != null ? marked : Lang.str(c, R.string.eng_rec_paused_text))
            .setUsesChronometer(false).setShowWhen(false);
        else b.setContentTitle(Lang.str(c, R.string.eng_rec_title))
            .setContentText(warning != null ? warning : marked != null ? marked : Lang.str(c, R.string.eng_rec_text))
            .setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - Math.max(0, elapsedMs));
        b.addAction(action(c, isPaused ? cl.vozlocal.app.R.drawable.ic_play : cl.vozlocal.app.R.drawable.ic_pause, Lang.str(c, isPaused ? R.string.eng_rec_resume : R.string.eng_rec_pause), toggle))
            .addAction(action(c, R.drawable.ic_star, Lang.str(c, R.string.eng_rec_mark), mark))
            .addAction(action(c, R.drawable.ic_stop, Lang.str(c, R.string.eng_rec_stop), stop));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    private static PendingIntent command(Context c, int code, Intent intent) {
        return PendingIntent.getService(c, code, intent.putExtra("source", "notification"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static Notification.Action action(Context c, int icon, String title, PendingIntent intent) {
        Notification.Action.Builder a = new Notification.Action.Builder(icon == 0 ? null : Icon.createWithResource(c, icon), title, intent);
        // ★, Pausar y Detener funcionan sin desbloquear el teléfono.
        if (Build.VERSION.SDK_INT >= 31) a.setAuthenticationRequired(false);
        return a.build();
    }
    private Notification notification() { return notification(this, paused, elapsed(), marksCount(), micWarning); }
    private void refresh() {
        if (recorder == null) return;
        try { getSystemService(NotificationManager.class).notify(7, notification()); } catch (RuntimeException ignored) { }
    }

    private void startRecording(String title) {
        error = null; paused = false; accumulated = 0; clearMarks(); resetMeter();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(7, notification());
            if (Recording.directory(this).getUsableSpace() < 20L * 1024 * 1024) throw new java.io.IOException("No hay espacio suficiente en el teléfono.");
            long now = System.currentTimeMillis();
            recording = new Recording(UUID.randomUUID().toString(), Recording.defaultTitle(now), now, 0);
            if(title!=null && !title.trim().isEmpty())recording.title=title.trim().substring(0,Math.min(120,title.trim().length()));
            activeId = recording.id; activeTitle = title != null && !title.trim().isEmpty() ? recording.title : null;
            // 0.9.3: «Grabar con reducción de ruido del teléfono» usa el micrófono de las llamadas. Si el teléfono no lo deja
            // preparar, se graba con el normal (la grabación no se pierde por una opción).
            quietMic = new Settings(this).recordNoise();
            try { recorder = newRecorder(quietMic); recorder.prepare(); }
            catch (Exception e) { if (!quietMic) throw e; if (recorder != null) recorder.release(); quietMic = false; recorder = newRecorder(false); recorder.prepare(); Diagnostics.event("recording_mic_fallback", activeId, "error_class", e.getClass().getSimpleName()); }
            recorder.start(); started = SystemClock.elapsedRealtime(); startedAtMs = started;Diagnostics.event("recording_started",activeId,"mic",quietMic?"voice":"default");
            refresh(); // el cronómetro de la notificación parte ahora, no al preparar el micrófono
            handler.postDelayed(meter, METER_MS);
            getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VozLocal:Recording");
            wakeLock.setReferenceCounted(false); wakeLock.acquire(WAKE_MS); handler.postDelayed(keepAwake, WAKE_RENEW_MS);
        } catch (Exception e) {
            Diagnostics.event("recorder_failure",activeId,"error_class",e.getClass().getSimpleName());error = Lang.str(this, R.string.eng_rec_err_start);
            if (recorder != null) { recorder.release(); recorder = null; }
            if (recording != null) discard(recording.id);
            activeId = null; recording = null; clearMarks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private void pause() {
        if (paused) return;
        try { long duration = elapsed(); recorder.pause(); accumulated = duration; paused = true; refresh(); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException e) { error = Lang.str(this, R.string.eng_rec_err_pause); }
    }
    private void resume() {
        if (!paused) return;
        try { recorder.resume(); started = SystemClock.elapsedRealtime(); paused = false; refresh(); if (wakeLock != null) wakeLock.acquire(WAKE_MS); }
        catch (RuntimeException e) { error = Lang.str(this, R.string.eng_rec_err_pause); }
    }

    /**
     * ★ en el instante actual de la grabación (también en pausa: queda donde se pausó). Con el extra «t» (ms de
     * grabación, p. ej. el momento en que se mantuvo pulsado ★ antes de escribir la palabra) marca ese instante;
     * si ya hay un ★ justo ahí, solo le pone la palabra.
     */
    private void mark(Intent intent, boolean fromNotification) {
        String source = fromNotification ? "notification" : "app";
        if (recorder == null || recording == null) { Diagnostics.event("recorder_action", activeId, "action", "MARK", "source", source, "result", "no_recording"); return; }
        long now = elapsed();
        long t = intent.hasExtra("t") ? Math.max(0, Math.min(now, intent.getLongExtra("t", now))) : now;
        String text = Marks.clean(intent.getStringExtra("label")); String result = "added"; int count;
        synchronized (MARKS) {
            Mark same = null, near = null; int at = MARKS.size();
            for (int i = 0; i < MARKS.size(); i++) {
                Mark m = MARKS.get(i);
                if (m.t == t) same = m;
                if (Math.abs(m.t - t) < MARK_DEBOUNCE_MS) near = m;
                if (m.t > t && at == MARKS.size()) at = i;
            }
            if (same != null && !text.isEmpty()) { same.label = text; result = "labeled"; }
            else if (near != null && text.isEmpty()) result = "duplicate";
            else MARKS.add(at, new Mark(t, text));
            if (!"duplicate".equals(result)) lastMark = t;
            count = MARKS.size();
        }
        Diagnostics.event("recorder_action", recording.id, "action", "MARK", "source", source, "result", result, "count", count, "elapsed_ms", t);
        if ("duplicate".equals(result)) return;
        // Se guarda de inmediato: si el proceso muere, las marcas ya están en el estado de la grabación.
        try { Marks.add(this, recording.id, t, text); } catch (Exception ignored) { /* al terminar se reescriben todas */ }
        if (fromNotification) confirm();
        refresh();
    }
    /** Vibración corta de confirmación (desde la notificación no hay botón que vibre). Sin permiso o sin motor, no hace nada. */
    private void confirm() {
        try {
            Vibrator v = Build.VERSION.SDK_INT >= 31 ? getSystemService(VibratorManager.class).getDefaultVibrator() : getSystemService(Vibrator.class);
            if (v == null || !v.hasVibrator()) return;
            v.vibrate(Build.VERSION.SDK_INT >= 29 ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) : VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) { }
    }
    private static JSONArray marksJson() {
        JSONArray a = new JSONArray();
        synchronized (MARKS) { for (Mark m : MARKS) try { a.put(new JSONObject().put("t", m.t).put("label", m.label)); } catch (Exception ignored) { } }
        return a;
    }
    private static void clearMarks() { synchronized (MARKS) { MARKS.clear(); lastMark = 0; } }
    /** Borra el audio y todo lo asociado (estado con ★ incluido) de una grabación que no se guarda. */
    private void discard(String id) {
        synchronized (FilesStore.LOCK) {
            File[] files = Recording.directory(this).listFiles((dir, name) -> name.startsWith(id + "."));
            if (files != null) for (File f : files) f.delete();
        }
    }

    /** El micrófono de las llamadas (VOICE_COMMUNICATION) o el normal. Separado para probarlo. */
    static int audioSource(boolean quiet) { return quiet ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC; }
    /** ¿Esta grabación usa el micrófono de las llamadas? Se anota en su estado al guardarla ("recNoise"). */
    private boolean quietMic;
    private MediaRecorder newRecorder(boolean quiet) {
        MediaRecorder m = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        m.setAudioSource(audioSource(quiet));
        // 0.9.6: AAC en ADTS, no MPEG-4. Un .m4a guarda su índice (moov) recién al detener: si Android mataba el proceso a
        // la hora de grabar, el archivo quedaba sin índice y no se podía oír. ADTS son cuadros sueltos que sirven hasta el
        // último escrito; al detener (o al abrir la app, si quedó uno a medias) se pasa a .m4a sin recodificar (seal).
        m.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        m.setAudioEncodingBitRate(96000); m.setAudioSamplingRate(44100); m.setAudioChannels(1);
        m.setOutputFile(partial(this, recording.id).getAbsolutePath());
        m.setOnErrorListener((r,w,e) -> { error = Lang.str(this, R.string.eng_rec_err_mic); Diagnostics.event("recorder_failure", activeId, "what", w, "extra", e); finishRecording(); if (savingId == null) stopSelf(); });
        return m;
    }

    private void resetMeter() { level = 0; micWarning = null; seconds = quietSeconds = clipSeconds = muteRun = secondPeak = 0; secondStart = 0; lastDiskCheck = SystemClock.elapsedRealtime(); silenced = warned = lowSpace = false; }
    /** Audio en curso (ADTS) de una grabación: «id.aac». Al terminar pasa a «id.m4a». */
    static File partial(Context c, String id) { return new File(Recording.directory(c), id + ".aac"); }
    /** Grabación que se está pasando a .m4a tras detener (unos segundos para una hora). La pantalla y las pruebas la esperan. */
    static volatile String savingId;
    /** Grabaciones que alguien está sellando ahora (este servicio o la recuperación al abrir): nadie más las toca. */
    private static final java.util.Set<String> SEALING = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Menos que esto no es una grabación aprovechable (cabeceras y un par de segundos a 96 kb/s). */
    static final long MIN_BYTES = 32 * 1024;

    private void finishRecording() {
        if (recorder == null) return;
        long duration = elapsed(); boolean stopped = false;
        try { recorder.stop(); stopped = true; }
        catch (RuntimeException e) { Diagnostics.event("recorder_stop_failed", activeId, "error_class", e.getClass().getSimpleName()); }
        finally { recorder.release(); recorder = null; handler.removeCallbacks(keepAwake); handler.removeCallbacks(meter); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        File audio = recording == null ? null : partial(this, recording.id);
        boolean hasData = audio != null && audio.length() > MIN_BYTES;
        // 0.9.6: si stop() falla (micrófono tomado por otra app, servidor de medios reiniciado…) ya no se borra lo grabado:
        // el ADTS sirve hasta el último cuadro. Solo se descarta si de verdad no hay audio.
        boolean valid = stopped || hasData;
        if (!stopped) error = hasData ? Lang.str(this, R.string.eng_rec_err_mic) : Lang.str(this, R.string.eng_rec_err_short);
        // Un toque accidental (menos de 3 s) no se guarda ni se envía a transcribir: es un error, no una grabación. Sus ★ se van con ella.
        if (valid && recording != null && duration < MIN_MS) {
            valid = false; discard(recording.id); notice = Lang.str(this, R.string.eng_rec_too_short);
            Diagnostics.event("recording_discarded", recording.id, "duration_ms", duration);
        }
        else if (recording != null && !valid) discard(recording.id);
        final Recording saved = valid ? recording : null;
        // Antes de soltar activeId: quien espera que termine (pantalla, pruebas) ve «guardando» sin un instante en blanco.
        if (saved != null) { savingId = saved.id; SEALING.add(saved.id); }
        final boolean quiet = quietMic; final JSONArray marks = marksJson(); final JSONObject measured = meterJson();
        clearMarks(); activeId = null; activeTitle = null; paused = false; recording = null; micWarning = null; level = 0;
        if (saved == null) { stopForeground(STOP_FOREGROUND_REMOVE); Pipeline.schedule(this, false); return; }
        // El paso a .m4a lee todo el archivo: fuera del hilo principal (es el mismo de la pantalla). Mientras, la notificación
        // sigue y el servicio vive; al terminar se avisa a la pantalla (lastSavedId) y se encola la transcripción.
        new Thread(() -> {
            try { saved.duration = duration; store(this, saved, quiet, marks, measured); }
            catch (Throwable e) { Diagnostics.crash(e); error = Lang.str(this, R.string.eng_rec_err_title); }
            finally {
                SEALING.remove(saved.id);
                handler.post(() -> {
                    savingId = null; lastSavedId = saved.id; FilesStore.version.incrementAndGet();
                    Pipeline.afterRecording(this, saved.id); WaveData.warm(this, saved); Pipeline.schedule(this, false);
                    if (recorder == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
                });
            }
        }, "VozLocal-seal").start();
    }
    /** Pasa el ADTS a .m4a, ajusta la duración a la del archivo y guarda datos, ★, micrófono y medición. */
    private static void store(Context c, Recording r, boolean quiet, JSONArray marks, JSONObject measured) {
        long fileMs = seal(c, r.id);
        // La duración sale del reloj (sin pausas); si el archivo dice otra cosa (más de 2 s), manda el archivo: es lo que se oye.
        if (fileMs > 0 && Math.abs(fileMs - r.duration) > 2000) { Diagnostics.event("recording_duration_fixed", r.id, "clock_ms", r.duration, "file_ms", fileMs); r.duration = fileMs; }
        try { r.save(c); } catch (Exception e) { error = Lang.str(c, R.string.eng_rec_err_title); }
        try { FilesStore.update(c, r.id, s -> { if (quiet) s.put("recNoise", true); if (measured.length() > 0) s.put("level", measured); }); } catch (Exception e) { Diagnostics.event("recording_state_failed", r.id, "error_class", e.getClass().getSimpleName()); }
        if (marks.length() > 0) { try { Marks.setAll(c, r.id, marks); } catch (Exception ignored) { } Diagnostics.event("recording_marks", r.id, "count", marks.length()); }
    }
    /**
     * «id.aac» → «id.m4a» sin recodificar. Si el paso falla, el ADTS se renombra igual a .m4a (Android lo reproduce y lo
     * transcribe por su contenido, no por la extensión): nunca se pierde el audio. Devuelve la duración del archivo (0 si no se sabe).
     */
    static long seal(Context c, String id) {
        File aac = partial(c, id), m4a = new File(Recording.directory(c), id + ".m4a");
        if (!aac.exists()) return m4a.exists() ? safeDuration(m4a) : 0;
        long began = SystemClock.elapsedRealtime();
        try { AudioConvert.seal(aac, m4a); Diagnostics.event("recording_sealed", id, "bytes", m4a.length(), "elapsed_ms", SystemClock.elapsedRealtime() - began); }
        catch (Exception e) {
            Diagnostics.event("recording_seal_failed", id, "error_class", e.getClass().getSimpleName());
            synchronized (FilesStore.LOCK) { if (!m4a.exists() && !aac.renameTo(m4a)) Diagnostics.event("recording_seal_failed", id, "reason", "rename"); }
        }
        return safeDuration(m4a);
    }
    private static long safeDuration(File f) { try { return AudioConvert.duration(f); } catch (Exception e) { return 0; } }
    /**
     * Grabaciones que quedaron a medias (Android mató el proceso, se acabó la batería, se quitó el permiso del micrófono):
     * se pasan a .m4a con lo que alcanzó a grabarse y aparecen en la Biblioteca. Corre al abrir la app, en segundo plano.
     */
    static void recoverOrphans(Context c) {
        File[] files = Recording.directory(c).listFiles((dir, name) -> name.endsWith(".aac"));
        if (files == null) return;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            // La grabación en curso (o una que se está sellando) escribe su .aac ahora mismo: no se toca.
            if (id.equals(activeId) || id.equals(savingId) || System.currentTimeMillis() - f.lastModified() < 5000 || !SEALING.add(id)) continue;
            try {
                if (f.length() <= MIN_BYTES) {
                    synchronized (FilesStore.LOCK) { File[] rest = Recording.directory(c).listFiles((dir, name) -> name.startsWith(id + ".")); if (rest != null) for (File x : rest) x.delete(); }
                    Diagnostics.event("recording_orphan_dropped", id, "bytes", f.length()); continue;
                }
                long modified = f.lastModified(), ms = seal(c, id);
                if (!new File(Recording.directory(c), id + ".json").exists()) {
                    long created = modified - ms;
                    Recording r = new Recording(id, Recording.defaultTitle(created), created, ms);
                    try { r.save(c); } catch (Exception ignored) { }
                }
                FilesStore.version.incrementAndGet();
                Diagnostics.event("recording_recovered", id, "duration_ms", ms);
                notice = Lang.str(c, R.string.eng_rec_recovered);
                Pipeline.afterRecording(c, id);
            } catch (Throwable e) { Diagnostics.crash(e); }
            finally { SEALING.remove(id); }
        }
    }
    @Override public void onDestroy() { finishRecording(); instance = null; super.onDestroy(); }
}
