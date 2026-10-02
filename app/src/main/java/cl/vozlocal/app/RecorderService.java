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
    private final Runnable keepAwake = new Runnable() { @Override public void run() { PowerManager.WakeLock w = wakeLock; if (recorder != null && w != null) { w.acquire(WAKE_MS); handler.postDelayed(this, WAKE_RENEW_MS); } } };
    private static RecorderService instance;
    static int amplitude() { try { return instance != null && instance.recorder != null && !paused ? instance.recorder.getMaxAmplitude() : 0; } catch (RuntimeException e) { return 0; } }
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
        else if ("STOP".equals(action)) { finishRecording(); stopSelf(); }
        // Un toque que llega sin grabación en curso (p. ej. ★ desde una notificación vieja) no deja el servicio vivo.
        if (recorder == null && !"STOP".equals(action)) stopSelf(startId);
        return START_NOT_STICKY;
    }

    /** Notificación de grabación: tiempo en vivo, Pausar/Reanudar, ★ Marcar y Detener, también en la pantalla de bloqueo. */
    static Notification notification(Context c, boolean isPaused, long elapsedMs, int marks) {
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
            .setContentText(marked != null ? marked : Lang.str(c, R.string.eng_rec_text))
            .setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - Math.max(0, elapsedMs));
        b.addAction(action(c, isPaused ? cl.vozlocal.app.R.drawable.ic_play : cl.vozlocal.app.R.drawable.ic_pause, Lang.str(c, isPaused ? R.string.eng_rec_resume : R.string.eng_rec_pause), toggle))
            .addAction(action(c, drawable(c, "ic_star"), Lang.str(c, R.string.eng_rec_mark), mark))
            .addAction(action(c, drawable(c, "ic_stop"), Lang.str(c, R.string.eng_rec_stop), stop));
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
    /** Ícono opcional (lo puede agregar la parte de diseño); 0 si no existe. */
    @SuppressWarnings("DiscouragedApi")
    private static int drawable(Context c, String name) { try { return c.getResources().getIdentifier(name, "drawable", c.getPackageName()); } catch (RuntimeException e) { return 0; } }
    private Notification notification() { return notification(this, paused, elapsed(), marksCount()); }
    private void refresh() {
        if (recorder == null) return;
        try { getSystemService(NotificationManager.class).notify(7, notification()); } catch (RuntimeException ignored) { }
    }

    private void startRecording(String title) {
        error = null; paused = false; accumulated = 0; clearMarks();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(7, notification());
            if (Recording.directory(this).getUsableSpace() < 20L * 1024 * 1024) throw new java.io.IOException("No hay espacio suficiente en el teléfono.");
            long now = System.currentTimeMillis();
            recording = new Recording(UUID.randomUUID().toString(), Recording.defaultTitle(now), now, 0);
            if(title!=null && !title.trim().isEmpty())recording.title=title.trim().substring(0,Math.min(120,title.trim().length()));
            activeId = recording.id; activeTitle = title != null && !title.trim().isEmpty() ? recording.title : null;
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96000); recorder.setAudioSamplingRate(44100); recorder.setAudioChannels(1);
            recorder.setOutputFile(recording.audio(this).getAbsolutePath());
            recorder.setOnErrorListener((r,w,e) -> { error = Lang.str(this, R.string.eng_rec_err_mic); finishRecording(); stopSelf(); });
            recorder.prepare(); recorder.start(); started = SystemClock.elapsedRealtime(); startedAtMs = started;Diagnostics.event("recording_started",activeId);
            refresh(); // el cronómetro de la notificación parte ahora, no al preparar el micrófono
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
        try { long duration = elapsed(); recorder.pause(); accumulated = duration; paused = true; refresh(); }
        catch (RuntimeException e) { error = Lang.str(this, R.string.eng_rec_err_pause); }
    }
    private void resume() {
        if (!paused) return;
        try { recorder.resume(); started = SystemClock.elapsedRealtime(); paused = false; refresh(); }
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

    private void finishRecording() {
        if (recorder == null) return;
        long duration = elapsed(); boolean valid = false;
        try { recorder.stop(); valid = true; }
        catch (RuntimeException e) { error = Lang.str(this, R.string.eng_rec_err_short); }
        finally { recorder.release(); recorder = null; handler.removeCallbacks(keepAwake); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        // Un toque accidental (menos de 3 s) no se guarda ni se envía a transcribir: es un error, no una grabación. Sus ★ se van con ella.
        if (valid && recording != null && duration < MIN_MS) {
            valid = false; discard(recording.id); notice = Lang.str(this, R.string.eng_rec_too_short);
            Diagnostics.event("recording_discarded", recording.id, "duration_ms", duration);
        }
        else if (recording != null) {
            if (valid) {
                recording.duration = duration; try { recording.save(this); } catch (Exception e) { error = Lang.str(this, R.string.eng_rec_err_title); }
                JSONArray marks = marksJson();
                if (marks.length() > 0) { try { Marks.setAll(this, recording.id, marks); } catch (Exception ignored) { } Diagnostics.event("recording_marks", recording.id, "count", marks.length()); }
            }
            else discard(recording.id);
        }
        Recording saved = valid ? recording : null;
        if (saved != null) lastSavedId = saved.id;
        clearMarks(); activeId = null; activeTitle = null; paused = false; recording = null; stopForeground(STOP_FOREGROUND_REMOVE);
        if (saved != null) { Pipeline.afterRecording(this, saved.id); WaveData.warm(this, saved); }
        Pipeline.schedule(this,false);
    }
    @Override public void onDestroy() { finishRecording(); instance = null; super.onDestroy(); }
}
