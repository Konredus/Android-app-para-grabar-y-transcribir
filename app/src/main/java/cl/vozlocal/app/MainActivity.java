package cl.vozlocal.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private int BG, INK, MUTED, GREEN, RED, SURFACE;
    private AppTheme.Palette palette;
    private BottomNav bottomNav;
    private ScrollView scroll;
    private TextView heading, intro;
    private int homeScroll, libraryScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private LinearLayout library, homePanel, libraryPanel; private TextView processing; private boolean showLibrary;
    private TextView timer, status, count;
    private Button record, pause;
    private LevelView meter;
    private String lastState = "";
    private ArrayList<Recording> recordings = new ArrayList<>();
    private String filter = "";
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private AlertDialog playbackDialog;
    private Runnable playbackTick;
    private boolean starting;
    private int loadVersion;
    private int dataVersion=-1;
    private String pendingTitle="";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean active = RecorderService.activeId != null;
            timer.setText(active ? Recording.time(RecorderService.elapsed()) : "00:00");
            String state = active + ":" + RecorderService.paused;
            if (!state.equals(lastState)) {
                lastState = state; starting = false;
                record.setEnabled(true); record.setText(active ? "Detener y guardar" : "Grabar audio");
                record.setBackgroundTintList(ColorStateList.valueOf(active ? 0xFFB6293A : palette.primary));
                pause.setVisibility(active ? View.VISIBLE : View.GONE);
                pause.setText(RecorderService.paused ? "Continuar grabación" : "Pausar");
                status.setText(active ? (RecorderService.paused ? "En pausa" : "Grabando en tu teléfono") : "Listo para grabar");status.setTextColor(active?RED:GREEN);
                loadLibrary();
            }
            if (RecorderService.error != null) { String message = RecorderService.error; RecorderService.error = null; starting = false; record.setEnabled(true); showError(message); }
            meter.sample(active && !RecorderService.paused ? RecorderService.amplitude() / 32767f : 0);
            int version=FilesStore.version.get();if(dataVersion!=version){dataVersion=version;loadLibrary();}
            handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate(Bundle saved) {
        palette=AppTheme.apply(this);BG=palette.background;INK=palette.ink;MUTED=palette.muted;GREEN=palette.accent;SURFACE=palette.surface;RED=palette.danger;
        super.onCreate(saved);
        if(saved!=null){pendingTitle=saved.getString("pendingTitle","");filter=saved.getString("filter","");homeScroll=saved.getInt("homeScroll");libraryScroll=saved.getInt("libraryScroll");}
        showLibrary=saved!=null?saved.getBoolean("library"):getIntent().getBooleanExtra("library",false);
        AppTheme.window(this,palette);
        LinearLayout root=column();root.setBackgroundColor(BG);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page=column();page.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        bottomNav=new BottomNav(this,palette,showLibrary?1:0,destination->{if(destination==2){closePlayer();startActivity(new Intent(this,SettingsActivity.class));}else section(destination==1);});root.addView(bottomNav,new LinearLayout.LayoutParams(-1,-2));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });setContentView(root);root.requestApplyInsets();
        heading=text("Voz local",34,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);page.addView(heading);
        intro=text("Graba una idea. Guarda una conversación.",16,MUTED);intro.setPadding(0,dp(8),0,dp(24));page.addView(intro);
        homePanel=column();page.addView(homePanel);libraryPanel=column();page.addView(libraryPanel);
        LinearLayout studio = column(); studio.setPadding(dp(20),dp(24),dp(20),dp(20)); studio.setBackground(round(SURFACE,24)); homePanel.addView(studio);
        status = text("Listo para grabar",14,GREEN); status.setGravity(Gravity.CENTER);  status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); studio.addView(status);
        timer = text("00:00",48,INK); timer.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); timer.setGravity(Gravity.CENTER); timer.setPadding(0,dp(12),0,0); studio.addView(timer);
        meter = new LevelView(); meter.setContentDescription("Nivel de entrada del micrófono"); studio.addView(meter,new LinearLayout.LayoutParams(-1,dp(64)));
        record = button("Grabar audio",true); record.setOnClickListener(v -> {
            if (RecorderService.activeId != null) send("STOP"); else prepareRecording();
        }); studio.addView(record);
        pause = button("Pausar",false); pause.setVisibility(View.GONE); pause.setOnClickListener(v -> send("PAUSE")); studio.addView(pause);
        TextView local = text("Guardado local, incluso sin internet",13,MUTED); local.setGravity(Gravity.CENTER); local.setPadding(0,dp(12),0,0); studio.addView(local);
        Button upload=button("Importar audio",false);upload.setOnClickListener(v->{if(RecorderService.activeId!=null){showError("Guarda primero la grabación en curso.");return;}startActivity(new Intent(this,ImportActivity.class));});homePanel.addView(upload);
        TextView formats=text("Grabadora, archivos y audios de WhatsApp",14,MUTED);formats.setGravity(Gravity.CENTER);formats.setPadding(0,dp(6),0,dp(20));homePanel.addView(formats);
        processing=text("",15,GREEN);processing.setPadding(dp(18),dp(18),dp(18),dp(18));processing.setBackground(round(SURFACE,18));processing.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);homePanel.addView(processing);
        count = text("Tus grabaciones",18,INK); count.setTypeface(null,Typeface.BOLD); count.setPadding(0,0,0,dp(16)); libraryPanel.addView(count);
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(16); search.setHint("Buscar por título"); search.setContentDescription("Buscar grabaciones por título"); search.setTextColor(INK); search.setHintTextColor(MUTED); search.setPadding(dp(16),dp(12),dp(16),dp(12)); search.setBackground(round(SURFACE,12)); libraryPanel.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        search.setText(filter);search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int before,int c) { filter=s.toString(); renderLibrary(); } public void afterTextChanged(Editable e) {} });
        library = column(); libraryPanel.addView(library);
        processing.setOnClickListener(v->section(true));processing.setContentDescription("Estado del procesamiento. Abrir biblioteca");section(showLibrary);
    }
    @Override protected void onResume() { super.onResume();if(palette.dark!=AppTheme.isDark(this)){recreate();return;}handler.post(tick); loadLibrary(); Pipeline.schedule(this,false); }
    @Override protected void onPause() { handler.removeCallbacks(tick); closePlayer(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); disk.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out){out.putString("pendingTitle",pendingTitle);out.putBoolean("library",showLibrary);out.putString("filter",filter);if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();out.putInt("homeScroll",homeScroll);out.putInt("libraryScroll",libraryScroll);super.onSaveInstanceState(out);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);section(intent.getBooleanExtra("library",false));}
    @Override public void onBackPressed(){if(showLibrary)section(false);else super.onBackPressed();}
    private void section(boolean libraryVisible){
        if(showLibrary!=libraryVisible){if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();}
        showLibrary=libraryVisible;bottomNav.select(libraryVisible?1:0);heading.setText(libraryVisible?"Biblioteca":"Voz local");intro.setText(libraryVisible?"Tus audios y transcripciones, siempre a mano.":"Graba una idea. Guarda una conversación.");
        homePanel.setVisibility(libraryVisible?View.GONE:View.VISIBLE);libraryPanel.setVisibility(libraryVisible?View.VISIBLE:View.GONE);scroll.post(()->scroll.scrollTo(0,libraryVisible?libraryScroll:homeScroll));Diagnostics.event("section_open",null,"screen",libraryVisible?"library":"home");
    }
    private void prepareRecording(){
        pendingTitle="";
        if(!new Settings(this).askTitle()){begin();return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView label=text("Título opcional",16,INK);box.addView(label);
        EditText title=new EditText(this);title.setHint("Ej. Conversación del domingo");title.setSingleLine(true);title.setContentDescription("Título opcional antes de grabar");title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});box.addView(title);
        TextView hint=text("Si lo dejas vacío usaremos la fecha y hora. Puedes cambiarlo después.",14,MUTED);box.addView(hint);
        new AlertDialog.Builder(this).setTitle("Nueva grabación").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Empezar a grabar",(d,w)->{pendingTitle=title.getText().toString().trim();begin();}).show();
    }
    private void begin() {
        if (starting) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("Permitir el micrófono").setMessage("Voz local necesita el micrófono para guardar tus grabaciones en este teléfono.")
                .setNegativeButton("Ahora no",null).setPositiveButton("Continuar",(d,w) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).show(); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("notificationAsked",false)) {
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11); return;
        }
        closePlayer(); starting = true; record.setEnabled(false);
        try { startForegroundService(new Intent(this,RecorderService.class).setAction("START").putExtra("title",pendingTitle)); }
        catch (RuntimeException e) { starting=false; record.setEnabled(true); showError("No se pudo iniciar el micrófono. Vuelve a intentarlo con la app abierta."); }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == 11) begin();
        else if (request == 10) {
            if (results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) begin();
            else new AlertDialog.Builder(this).setTitle("El micrófono está desactivado").setMessage("Activa el permiso del micrófono en Ajustes para grabar.").setNegativeButton("Cerrar",null).setPositiveButton("Abrir ajustes",(d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
        }
    }
    private void send(String action) { startService(new Intent(this,RecorderService.class).setAction(action)); }
    private void loadLibrary() {
        int version = ++loadVersion;
        disk.execute(() -> { ArrayList<Recording> list = Recording.list(getApplicationContext()); runOnUiThread(() -> { if (!isDestroyed() && version == loadVersion) { recordings=list; renderLibrary(); } }); });
    }
    private void renderLibrary() {
        if (library == null) return;
        String summary="Listo para capturar tu próxima idea.";int queued=0,failed=0;for(Recording item:recordings){org.json.JSONObject state=FilesStore.state(this,item.id);if(state.optBoolean("requested")){queued++;summary=state.optString("status");}if(state.optBoolean("failed"))failed++;}if(queued>0)summary=queued+" en proceso / espera\\n"+summary;else if(failed>0)summary=failed+" transcripción(es) necesitan atención. Revisa la biblioteca.";else if(!recordings.isEmpty())summary="Última grabación · "+FilesStore.label(this,recordings.get(0).id);processing.setText(summary.replace("\\n","\n"));processing.setTextColor(failed>0&&queued==0?RED:GREEN);library.removeAllViews(); count.setText(recordings.size()+ (recordings.size()==1?" grabación":" grabaciones"));
        int visible=0;
        for (Recording r: recordings) {
            if (!r.title.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
            visible++;
            LinearLayout card=column(); card.setPadding(dp(16),dp(16),dp(16),dp(10)); card.setBackground(round(SURFACE,16));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.topMargin=dp(12); library.addView(card,cp);
            TextView title=text(r.title,18,INK); title.setTypeface(null,Typeface.BOLD); card.addView(title);
            String date=new SimpleDateFormat("dd MMM yyyy · HH:mm",new Locale("es","CL")).format(new Date(r.created));
            TextView detail=text(date+"  /  "+Recording.time(r.duration),13,MUTED); detail.setPadding(0,dp(6),0,dp(10)); card.addView(detail);
            TextView state=text(FilesStore.label(this,r.id),14,GREEN);state.setPadding(0,0,0,dp(8));card.addView(state);String localCopy=FilesStore.state(this,r.id).optString("localStatus");if(!localCopy.isEmpty())card.addView(text(localCopy,13,MUTED));
            LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); card.addView(actions);
            Button play=button("Escuchar",false); play.setContentDescription("Escuchar " + r.title); play.setOnClickListener(v -> play(r)); actions.addView(play,new LinearLayout.LayoutParams(0,-2,1));
            Button more=button("Opciones",false); more.setContentDescription("Opciones de " + r.title); more.setOnClickListener(v -> options(r)); actions.addView(more,new LinearLayout.LayoutParams(0,-2,1));
            boolean transcribed=Transcript.exists(this,r.id);
            Button transcript=button(transcribed?"Ver transcripción":"Transcribir audio",false);
            transcript.setOnClickListener(v->{if(transcribed)startActivity(new Intent(this,TranscriptActivity.class).putExtra("id",r.id));else queue(r);});card.addView(transcript);
        }
        if (visible==0) {
            TextView empty=text(recordings.isEmpty()?"Tu próxima idea empieza aquí.\nToca Grabar audio para guardar la primera.":"No hay grabaciones con ese título.",16,MUTED);
            empty.setPadding(dp(8),dp(28),dp(8),dp(16)); empty.setGravity(Gravity.CENTER); library.addView(empty);
        }
    }
    private void options(Recording r) {
        new AlertDialog.Builder(this).setTitle(r.title).setItems(new String[]{"Cambiar título","Compartir audio","Eliminar grabación","Reintentar transcripción","Cancelar procesamiento pendiente","Recortar una copia"},(d,which) -> {
            Diagnostics.event("recording_option",r.id,"action",which);if(which==5){startActivity(new Intent(this,ImportActivity.class).putExtra("sourceId",r.id));return;}if(which==0) rename(r);
            else if(which==1) {
                Uri uri=Uri.parse("content://cl.vozlocal.app.audio/"+r.id+".m4a");
                Intent share=new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,r.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newRawUri(r.title,uri)); startActivity(Intent.createChooser(share,"Compartir grabación"));
            } else if(which==3){queue(r);}else if(which==4){try{Pipeline.cancel(this,r.id);loadLibrary();}catch(Exception e){showError("No se pudo cancelar el trabajo.");}}else new AlertDialog.Builder(this).setTitle("¿Eliminar esta grabación?").setMessage(r.title+"\n\nSe borrarán el audio y la transcripción de este teléfono. Las copias en la carpeta elegida se conservan. Esta acción no se puede deshacer.")
                .setNegativeButton("Conservar",null).setPositiveButton("Eliminar",(a,b) -> { closePlayer(); if(!r.delete(this)) showError("No se pudo eliminar el audio."); loadLibrary(); }).show();
        }).show();
    }
    private void rename(Recording r) {
        EditText input=new EditText(this); input.setText(r.title); input.setSingleLine(true); input.setSelectAllOnFocus(true); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)}); input.setContentDescription("Título de la grabación");
        LinearLayout box=column(); box.setPadding(dp(24),dp(8),dp(24),0); box.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Cambiar título").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title=input.getText().toString().trim(); if(title.isEmpty()) {input.setError("Escribe un título"); return;}
            Diagnostics.event("title_edited",r.id);String previous=r.title; r.title=title;
            try {r.save(this); Pipeline.edited(this,r.id); loadLibrary(); dialog.dismiss();} catch(Exception e){r.title=previous; input.setError("No se pudo guardar el título");}
        })); dialog.show();
    }
    private void queue(Recording r){
        if(!Transcript.exists(this,r.id) && !new Settings(this).hasKey()){showError("Primero agrega tu clave de API en Ajustes.");return;}
        if(Transcript.exists(this,r.id)){showError("Esta grabación ya tiene transcripción. Puedes abrirla o recortar una copia para crear otra.");return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);try{Pipeline.request(this,r.id);loadLibrary();Toast.makeText(this,"En cola. Se respetarán tus ajustes de conexión y carga.",Toast.LENGTH_LONG).show();}catch(Exception e){showError("No se pudo agregar a la cola.");}
    }
    private void play(Recording r) { Diagnostics.event("playback_open",r.id);
        if(RecorderService.activeId!=null) {showError("Guarda la grabación actual antes de reproducir un audio."); return;}
        closePlayer();
        LinearLayout box=column(); box.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView progress=text("Preparando audio…",16,MUTED); box.addView(progress);
        SeekBar seek=new SeekBar(this); seek.setContentDescription("Posición de reproducción"); seek.setEnabled(false); box.addView(seek);
        Button toggle=button("Preparando…",true); toggle.setEnabled(false); box.addView(toggle);
        playbackDialog=new AlertDialog.Builder(this).setTitle(r.title).setView(box).setNegativeButton("Cerrar",null).create();
        playbackDialog.setOnDismissListener(d -> releasePlayer()); playbackDialog.show();
        try {
            player=new MediaPlayer();
            AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(); player.setAudioAttributes(attr);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).setOnAudioFocusChangeListener(change -> { if(change<0 && player!=null && player.isPlaying()){player.pause(); toggle.setText("Continuar");} }).build();
            player.setDataSource(r.audio(this).getAbsolutePath());
            player.setOnPreparedListener(p -> {
                seek.setMax(p.getDuration()); seek.setEnabled(true); toggle.setEnabled(true); toggle.setText("Reproducir");
                if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){p.start();toggle.setText("Pausar");}
                playbackTick=new Runnable(){public void run(){if(player==null)return; int pos=player.getCurrentPosition(); seek.setProgress(pos);progress.setText(Recording.time(pos)+" / "+Recording.time(player.getDuration()));handler.postDelayed(this,300);}}; handler.post(playbackTick);
            });
            player.setOnCompletionListener(p -> toggle.setText("Reproducir otra vez"));
            player.setOnErrorListener((p,w,e) -> {closePlayer();showError("No se pudo reproducir este audio. Puede haberse interrumpido al grabar.");return true;});
            toggle.setOnClickListener(v -> {if(player==null)return;if(player.isPlaying()){player.pause();toggle.setText("Continuar");}else if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){player.start();toggle.setText("Pausar");}});
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){if(user && player!=null)player.seekTo(value);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
            player.prepareAsync();
        }catch(Exception e){closePlayer();showError("No se pudo abrir el audio.");}
    }
    private void closePlayer(){if(playbackDialog!=null){playbackDialog.dismiss();playbackDialog=null;}releasePlayer();}
    private void releasePlayer(){if(playbackTick!=null){handler.removeCallbacks(playbackTick);playbackTick=null;}if(player!=null){player.release();player=null;}if(focus!=null){getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}}
    private void showError(String message){if(!isFinishing())new AlertDialog.Builder(this).setTitle("Voz local").setMessage(message).setPositiveButton("Entendido",null).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String label,boolean primary){Button b=new Button(this){@Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen","MainActivity","action",label);return super.performClick();}};b.setText(label);AppTheme.styleButton(b,palette,primary);return b;}
    private class LevelView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final float[] history=new float[27];void sample(float level){System.arraycopy(history,1,history,0,26);history[26]=Math.min(1,(float)Math.sqrt(Math.max(0,level)));invalidate();}
        LevelView(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(GREEN);float step=getWidth()/33f;for(int i=0;i<27;i++){float h=dp(4)+history[i]*dp(48);float x=step*(i+3);canvas.drawRoundRect(x,getHeight()/2f-h/2,x+dp(3),getHeight()/2f+h/2,dp(2),dp(2),paint);}}
    }
}
