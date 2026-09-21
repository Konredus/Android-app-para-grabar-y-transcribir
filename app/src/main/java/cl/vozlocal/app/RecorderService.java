package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.*;
import java.util.UUID;

public class RecorderService extends Service {
    static volatile String activeId;
    static volatile boolean paused;
    static volatile String error;
    private static volatile long accumulated, started;
    private MediaRecorder recorder;
    private Recording recording;
    private PowerManager.WakeLock wakeLock;
    private static RecorderService instance;
    static int amplitude() { try { return instance != null && instance.recorder != null && !paused ? instance.recorder.getMaxAmplitude() : 0; } catch (RuntimeException e) { return 0; } }
    static long elapsed() { return accumulated + (activeId != null && !paused ? SystemClock.elapsedRealtime() - started : 0); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel channel = new NotificationChannel("recording", "Grabación en curso", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "STOP" : intent.getAction();
        if ("START".equals(action) && recorder == null) startRecording(intent.getStringExtra("title"));
        else if ("PAUSE".equals(action) && recorder != null) togglePause();
        else if ("STOP".equals(action)) { finishRecording(); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecorderService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "recording").setSmallIcon(cl.vozlocal.app.R.drawable.ic_notification)
            .setContentTitle(paused ? "Grabación en pausa" : "Voz local está grabando")
            .setContentText("Audio guardado en este teléfono").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Detener y guardar", stop).build()).build();
    }
    private void startRecording(String title) {
        error = null; paused = false; accumulated = 0;
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(7, notification());
            if (Recording.directory(this).getUsableSpace() < 20L * 1024 * 1024) throw new java.io.IOException("No hay espacio suficiente en el teléfono.");
            long now = System.currentTimeMillis();
            recording = new Recording(UUID.randomUUID().toString(), Recording.defaultTitle(now), now, 0);
            if(title!=null && !title.trim().isEmpty())recording.title=title.trim().substring(0,Math.min(120,title.trim().length()));
            activeId = recording.id;
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96000); recorder.setAudioSamplingRate(44100); recorder.setAudioChannels(1);
            recorder.setOutputFile(recording.audio(this).getAbsolutePath());
            recorder.setOnErrorListener((r,w,e) -> { error = "El micrófono se interrumpió. Revisa el audio guardado."; finishRecording(); stopSelf(); });
            recorder.prepare(); recorder.start(); started = SystemClock.elapsedRealtime();
            getSystemService(android.app.job.JobScheduler.class).cancel(Pipeline.JOB_ID);
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VozLocal:Recording");
            wakeLock.acquire();
        } catch (Exception e) {
            error = "No se pudo iniciar la grabación. Revisa el permiso del micrófono y el espacio disponible.";
            if (recorder != null) { recorder.release(); recorder = null; }
            if (recording != null) recording.audio(this).delete();
            activeId = null; recording = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private void togglePause() {
        try {
            if (paused) { recorder.resume(); started = SystemClock.elapsedRealtime(); paused = false; }
            else { long duration = elapsed(); recorder.pause(); accumulated = duration; paused = true; }
            getSystemService(NotificationManager.class).notify(7, notification());
        } catch (RuntimeException e) { error = "No se pudo cambiar la pausa. Detén y guarda la grabación."; }
    }
    private void finishRecording() {
        if (recorder == null) return;
        long duration = elapsed(); boolean valid = false;
        try { recorder.stop(); valid = true; }
        catch (RuntimeException e) { error = "La grabación fue demasiado corta o se interrumpió. No se pudo guardar."; }
        finally { recorder.release(); recorder = null; if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        if (recording != null) {
            if (valid) { recording.duration = duration; try { recording.save(this); } catch (Exception e) { error = "El audio se guardó, pero no su título. Aparecerá como audio recuperado."; } }
            else recording.audio(this).delete();
        }
        String savedId=valid && recording!=null?recording.id:null;
        activeId = null; paused = false; recording = null; stopForeground(STOP_FOREGROUND_REMOVE);
        if(savedId!=null)Pipeline.afterRecording(this,savedId);
        Pipeline.schedule(this,false);
    }
    @Override public void onDestroy() { finishRecording(); instance = null; super.onDestroy(); }
}
