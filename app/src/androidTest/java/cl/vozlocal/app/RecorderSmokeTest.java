package cl.vozlocal.app;

import android.app.*;
import android.content.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import java.io.File;

/** Device integration test. Run only on a disposable emulator with microphone permission granted. */
public class RecorderSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void command(Context context, String action) {
        runOnMainSync(() -> {
            Intent intent = new Intent(context, RecorderService.class).setAction(action).putExtra("title","Título antes de grabar");
            if (action.equals("START")) context.startForegroundService(intent); else context.startService(intent);
        });
        waitForIdleSync();
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        Context c = getTargetContext();
        try {
            Activity activity = startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); Thread.sleep(700);
            command(c, "START"); Thread.sleep(1500);
            check(RecorderService.activeId != null, "Recorder did not start: " + RecorderService.error);
            String id = RecorderService.activeId;
            check(Recording.list(c).stream().noneMatch(r -> r.id.equals(id)), "Active recording exposed in library");
            command(c, "PAUSE"); long pausedAt = RecorderService.elapsed(); Thread.sleep(700);
            check(RecorderService.paused && RecorderService.elapsed() == pausedAt, "Pause counted elapsed time");
            command(c, "PAUSE"); Thread.sleep(900);
            check(!RecorderService.paused && RecorderService.elapsed() >= pausedAt + 700, "Resume did not advance timer");
            getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close(); Thread.sleep(600);
            check(RecorderService.activeId != null, "Recording stopped when app went to background");
            getUiAutomation().executeShellCommand("input keyevent KEYCODE_SLEEP").close(); Thread.sleep(700);
            check(RecorderService.activeId != null, "Recording stopped with screen off");
            getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP").close();
            getUiAutomation().executeShellCommand("wm dismiss-keyguard").close();
            command(c, "STOP"); Thread.sleep(500);
            check(RecorderService.activeId == null && RecorderService.error == null, "Recorder stop failed: " + RecorderService.error);
            Recording r = Recording.list(c).stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow();
            check(r.audio(c).length() > 100, "Audio file is empty");
            check(r.title.equals("Título antes de grabar"),"Pre-recording title was not saved");
            try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                metadata.setDataSource(r.audio(c).getPath());
                long mediaDuration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                check(mediaDuration > 2000, "Recorded audio duration invalid");
                check(Math.abs(mediaDuration-r.duration) < 1500, "Saved duration differs from actual media");
            }
            r.title = "Prueba de voz local"; r.save(c);
            check(Recording.list(c).stream().anyMatch(item -> item.id.equals(id) && item.title.equals(r.title)), "Title did not persist");
            Uri uri = Uri.parse("content://cl.vozlocal.app.audio/" + id + ".m4a");
            try (ParcelFileDescriptor file = c.getContentResolver().openFileDescriptor(uri,"r")) { check(file.getStatSize() > 100, "Sharing provider cannot read audio"); }
            boolean rejected = false;
            try { c.getContentResolver().openFileDescriptor(uri,"w"); } catch (java.io.FileNotFoundException expected) { rejected = true; }
            check(rejected, "Sharing provider allowed writing");
            new File(Recording.directory(c), id+".json").delete();
            check(Recording.list(c).stream().anyMatch(item -> item.id.equals(id) && item.duration > 0), "Audio missing after metadata loss");
            r.save(c);
            Recording disposable = new Recording(java.util.UUID.randomUUID().toString(), "Delete test", System.currentTimeMillis(), 0);
            java.nio.file.Files.copy(r.audio(c).toPath(), disposable.audio(c).toPath()); disposable.save(c);
            check(disposable.delete(c) && !disposable.audio(c).exists(), "Deletion failed");
            check("1:01:01".equals(Recording.time(3661000)), "Long duration format failed");
            FeatureChecks.run(c,r);
            report.putString("stream", "PASS: recorder regression, pre-recording title, Keystore credentials, diarization multipart contract, speaker naming persistence, block speaker isolation, HTTP error classification, Drive resumable contract, valid long-audio splitting. Live OpenAI/Google credentials were not used.\n");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        }
    }
}
