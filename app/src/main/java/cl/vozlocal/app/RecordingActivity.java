package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Detalle de una grabación, como un documento (0.6.0): título con lápiz, ficha (personas y si las voces están
 * revisadas), «Nota para tu segundo cerebro», «Momentos ★» y la transcripción a todo el ancho. Abajo queda fijo el
 * reproductor (la onda sirve para moverse) y UN botón principal que avanza con la grabación (ver {@link Next}).
 *
 * Estabilidad: la pantalla solo se vuelve a armar cuando cambian los archivos de ESTA grabación, y conserva la
 * posición. El avance de una transcripción en curso se actualiza en su lugar, sin reconstruir nada.
 */
public class RecordingActivity extends Screen {
    private static final int SAVE_AS=71;
    /** Después de moverte a mano por la transcripción, cuánto se espera antes de volver a seguir al audio. */
    private static final long FOLLOW_IDLE_MS=4000;
    /** Archivos de ESTA grabación que cambian lo que se ve. El primero es el estado (.sync.json). */
    private static final String[] WATCHED={".sync.json",".json",".transcript.json",".note.json",".transcript.prev.json"};
    private enum Mode{NEW,QUEUED,FAILED,DONE}

    private String id;private boolean demo;private Recording recording;private Transcript transcript;private Mode mode=Mode.NEW;
    private LinearLayout content;private TextView titleView,barTitle,followChip;private boolean barTitleShown;
    private final Handler handler=new Handler(Looper.getMainLooper());private SharedPreferences prefs;
    /** Firma de los archivos de esta grabación y «forma» del estado con que se armó la pantalla. */
    private int dataVersion=-1;private String[] lastSig;private String lastKey="";
    private boolean intentHandled,offeredKeep,markedOpened,correctionOpened,noteBusy,saving,detailsOpen,suppressTask;
    private long[] marksMs=new long[0];private long demoStamp;

    // Reproductor (fijo abajo)
    private MediaPlayer player;private AudioFocusRequest focus;private boolean prepared;private float rate=1f;
    private Scrubber scrubber;private TextView timeText,speedChip;private View speedBox;private ImageButton play;
    private Ui.Split primary;private Next next;
    /** Seguir al audio: la intervención que suena se trae a la vista, salvo que te hayas movido a mano hace poco. */
    private long lastUserScroll;private boolean followPending;

    private final Runnable progress=new Runnable(){public void run(){
        if(recording!=null){
            if(player!=null&&prepared){int pos=player.getCurrentPosition();
                if(scrubber!=null&&!scrubber.dragging){scrubber.setPosition(pos);timeText.setText(Recording.time(pos)+" / "+Recording.time(total()));}
                tickPlayback(pos);}
            if(!demo)checkChanges();
            tickProcess();updateFollowChip();
        }
        handler.postDelayed(this,250);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);demo=getIntent().getBooleanExtra("demo",false);id=getIntent().getStringExtra("id");prefs=getSharedPreferences("detail",MODE_PRIVATE);if(!demo&&id!=null)Transcriber.clearDone(this,id);
        if(state!=null){intentHandled=true;offeredKeep=state.getBoolean("offeredKeep");markedOpened=state.getBoolean("markedOpened");correctionOpened=state.getBoolean("correcting");onlySpeaker=state.getString("onlySpeaker");}
        shell(demo?"Ajustes":"Biblioteca",-1);
        try{
            if(demo){recording=new Recording("00000000-0000-0000-0000-000000000000","Conversación de ejemplo",System.currentTimeMillis(),27000);java.io.File f=demoFile();transcript=f.exists()?new Transcript(FilesStore.read(f)):example();demoStamp=f.exists()?f.lastModified():0;}
            else{recording=FilesStore.recording(this,id);if(recording==null)throw new java.io.FileNotFoundException();}
        }catch(Exception e){recording=null;largeTitle(page,"No disponible","Esta grabación ya no existe o no se pudo abrir.");return;}
        if(!demo){ImageButton more=ui.iconButton(R.drawable.ic_more,"Más opciones",p.onSurfaceVariant,0,48);more.setOnClickListener(v->RecordingActions.menu(this,recording,this::reload,this::releasePlayer));barActions.addView(more);}
        // El título grande se va con el contenido; al desplazarte aparece en la barra superior.
        barTitle=ui.oneLine(ui.text(recording.title,Type.TITLE_LARGE,p.onSurface));barTitle.setAlpha(0f);barTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(bar!=null&&bar.getChildCount()>2){bar.removeViewAt(1);bar.addView(barTitle,1,new LinearLayout.LayoutParams(0,-2,1));}
        // La zona desplazable lleva encima el chip flotante «Volver a lo que suena».
        int at=root.indexOfChild(scroll);root.removeView(scroll);FrameLayout stage=new FrameLayout(this);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        followChip=ui.text("↓ Volver a lo que suena",Type.LABEL_LARGE,p.onPrimaryContainer);followChip.setGravity(Gravity.CENTER);followChip.setMinHeight(ui.dp(48));followChip.setPadding(ui.dp(S5),0,ui.dp(S5),0);
        followChip.setBackground(ui.ripple(shape(this,p.primaryContainer,R_FULL),R_FULL));followChip.setElevation(ui.dp(3));followChip.setAccessibilityDelegate(Ui.buttonRole());followChip.setVisibility(View.GONE);
        followChip.setOnClickListener(v->{lastUserScroll=0;if(playingTurn!=null)followTo(playingTurn,true);followChip.setVisibility(View.GONE);Diagnostics.event("follow_resume",demo?null:id);});
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);fp.bottomMargin=ui.dp(S3);stage.addView(followChip,fp);
        root.addView(stage,Math.max(0,at),new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_MOVE)lastUserScroll=SystemClock.uptimeMillis();return false;});
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{updateBarTitle();updateFollowChip();});
        content=ui.column();page.addView(content,Ui.fill());
        buildDock();build();
        if(!demo)loadWave();
    }
    @Override protected void onResume(){
        super.onResume();handler.post(progress);
        if(!demo&&recording!=null){Pipeline.startForeground(this);
            // Al volver de Ajustes (carpeta rápida, IA de la nota, clave), lo que depende de eso se pone al día.
            if(lastSettings!=null&&!settingsKey().equals(lastSettings))reload();else refreshPrimary();}
    }
    private String lastSettings;
    private String settingsKey(){Settings s=new Settings(this);return s.inboxTree()+"|"+s.hasKey()+"|"+s.provider()+"|"+s.textModel()+"|"+s.noteProvider()+"|"+s.hasAnthropicKey()+"|"+s.noteAuto();}
    @Override protected void onPause(){handler.removeCallbacks(progress);if(player!=null&&player.isPlaying()){player.pause();setPlaying(false);}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("offeredKeep",offeredKeep);out.putBoolean("markedOpened",markedOpened);out.putBoolean("correcting",correctionOpened);if(onlySpeaker!=null)out.putString("onlySpeaker",onlySpeaker);super.onSaveInstanceState(out);}

    // ---------- Armar la pantalla (solo cuando cambió ESTA grabación) ----------
    /** Vuelve a armar el contenido conservando la posición (las correcciones no te devuelven arriba). */
    private void reload(){reload(false);}
    private void reload(boolean toTop){
        if(recording==null||content==null||isDestroyed())return;
        int y=toTop?0:scroll.getScrollY();
        // Mientras se arma, el alto no baja: así el ScrollView no recorta la posición antes de restaurarla.
        content.setMinimumHeight(toTop?0:content.getHeight());
        build();keepScroll(y);
    }
    private void keepScroll(int y){
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){@Override public void onGlobalLayout(){
            if(scroll.getViewTreeObserver().isAlive())scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            scroll.scrollTo(0,y);content.setMinimumHeight(0);updateBarTitle();}});
    }
    private void build(){
        if(recording==null)return;
        JSONObject st;
        if(!demo){
            dataVersion=FilesStore.version.get();lastSig=signature();
            Recording latest=FilesStore.recording(this,id);if(latest==null){finish();return;}recording=latest;
            try{transcript=Transcript.exists(this,id)?Transcript.load(this,id):null;}catch(Exception e){transcript=null;}
            st=FilesStore.state(this,id);
        }else{syncDemo();st=new JSONObject();}
        mode=transcript!=null?Mode.DONE:st.optBoolean("requested")?Mode.QUEUED:st.optBoolean("failed")?Mode.FAILED:Mode.NEW;
        lastKey=structureKey(st);marksMs=demo?new long[0]:markTimes();if(!demo)lastSettings=settingsKey();
        // Si algo suena, al volver a resaltar su intervención no se desplaza (corregir mientras escuchas no mueve la vista).
        restoringPlaying=playingTurn!=null&&player!=null&&prepared&&player.isPlaying();
        content.removeAllViews();turns.clear();playingTurn=null;resetLiveViews();
        header(st);
        switch(mode){
            case DONE:showTranscript(st);break;
            case QUEUED:showQueued(st);addMarks();break;
            case FAILED:showFailed(st);addMarks();break;
            default:showNew();addMarks();
        }
        if(scrubber!=null){scrubber.setMarks(marksMs);if(mode!=Mode.DONE)scrubber.setStrip(null,null,null);}
        if(barTitle!=null)barTitle.setText(recording.title);
        refreshPrimary();afterBuild(st);
    }
    private void addMarks(){View m=marksRow();if(m!=null)content.addView(m,Ui.fill());}
    /** Pedidos que llegan con la apertura (notificación «lista», «Nueva versión lista») y el punto «nuevo» de la Biblioteca. */
    private void afterBuild(JSONObject st){
        if(demo)return;
        if(!intentHandled){intentHandled=true;Intent in=getIntent();
            if(transcript!=null&&in.getBooleanExtra("names",false))handler.post(this::openNameVoices);
            else if(transcript!=null&&in.getBooleanExtra("save",false))handler.post(this::inboxSave);}
        if(!offeredKeep&&transcript!=null&&(st.has("retranscribe")||getIntent().getBooleanExtra("retranscribed",false))&&Retranscribe.hasPrevious(this,id)){
            offeredKeep=true;handler.post(()->{try{RetranscribeSheet.offerKeep(this,recording,this::reload);}catch(RuntimeException ignored){}});}
        if(!markedOpened&&transcript!=null){markedOpened=true;long now=System.currentTimeMillis();
            try{FilesStore.update(this,id,s->s.put("opened",now));}catch(Exception ignored){}
            dataVersion=FilesStore.version.get();lastSig=signature();}
    }
    private String[] signature(){
        String[] s=new String[WATCHED.length];
        for(int i=0;i<WATCHED.length;i++){java.io.File f=FilesStore.file(this,id,WATCHED[i]);s[i]=f.isFile()?f.lastModified()+":"+f.length():"";}
        return s;
    }
    /** Lo que decide la ESTRUCTURA de la pantalla. Si solo cambió otra parte del estado (p. ej. el avance), no se reconstruye. */
    private String structureKey(JSONObject st){
        String kind=transcript!=null?"D":st.optBoolean("requested")?"Q":st.optBoolean("failed")?"F":"N";
        StringBuilder k=new StringBuilder(kind).append('|').append(st.optString("noteState")).append('|').append(st.optString("noteError")).append('|')
            .append(st.optString("suggestedTitle")).append('|').append(st.optJSONArray("marks")).append('|').append(st.has("retranscribe"));
        if(kind.equals("F"))k.append('|').append(st.optString("status"));
        if(kind.equals("D")){JSONArray log=st.optJSONArray("log");k.append('|').append(st.optLong("doneIn")).append('|').append(log==null?0:log.length());}
        return k.toString();
    }
    /** Cada 250 ms: ¿cambió algo de ESTA grabación? Otras grabaciones que se transcriben no mueven esta pantalla. */
    private void checkChanges(){
        int v=FilesStore.version.get();if(v==dataVersion)return;dataVersion=v;
        String[] sig=signature();if(Arrays.equals(sig,lastSig))return;
        boolean onlyState=lastSig!=null;for(int i=1;i<sig.length&&onlyState;i++)if(!sig[i].equals(lastSig[i]))onlyState=false;
        lastSig=sig;
        if(onlyState){JSONObject st=FilesStore.state(this,id);if(structureKey(st).equals(lastKey)){refreshLive(st);return;}}
        reload();
    }
    private void refreshLive(JSONObject st){if(mode==Mode.QUEUED)updateProgress(st);refreshPrimary();}

    // ---------- Cabecera: título, fecha, título sugerido ----------
    private void header(JSONObject st){
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.setPadding(ui.dp(S1),0,0,0);
        titleView=ui.heading(recording.title,Type.HEADLINE_SMALL);titleView.setPadding(0,ui.dp(S2),ui.dp(S1),ui.dp(S1));row.addView(titleView,new LinearLayout.LayoutParams(0,-2,1));
        if(!demo){titleView.setOnClickListener(v->rename());
            ImageButton edit=ui.iconButton(R.drawable.ic_edit,"Cambiar título",p.onSurfaceVariant,0,48);edit.setOnClickListener(v->rename());row.addView(edit);}
        content.addView(row,Ui.fill());
        LinearLayout metaRow=ui.row();metaRow.setPadding(ui.dp(S1),0,0,ui.dp(S3));
        metaRow.addView(ui.text(metaText(),Type.BODY_MEDIUM,p.onSurfaceVariant),new LinearLayout.LayoutParams(0,-2,1));
        // Lo terminado va en silencio: el estado solo se muestra cuando pide atención o está en curso.
        if(demo||mode!=Mode.DONE){RecState state=RecState.of(st,false);TextView chip=ui.chip(demo?"Ejemplo":state.label,demo?p.onSecondaryContainer:state.onBg(p),demo?p.secondaryContainer:state.bg(p));metaRow.addView(chip);}
        content.addView(metaRow,Ui.fill());
        if(!demo){String suggested=st.optString("suggestedTitle","").trim();
            if(!suggested.isEmpty()&&!sameTitle(suggested,recording.title)){
                TextView chip=chip("Usar «"+quote(suggested,48)+"»",R.drawable.ic_sparkle,p.onSurfaceVariant,0,true);chip.setContentDescription("Título sugerido: "+suggested+". Toca para usarlo");
                chip.setOnClickListener(v->useSuggested(suggested));LinearLayout.LayoutParams lp=Ui.wrap();lp.bottomMargin=ui.dp(S1);content.addView(chip,lp);}}
    }
    private void rename(){RecordingActions.rename(this,recording,this::reload);}
    private String metaText(){return dayLabel(recording.created)+" · "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(recording.created))+" · "+Ui.humanDuration(recording.duration);}
    /** «29 sept»; con el año si no es el actual. */
    private static String dayLabel(long ms){Calendar now=Calendar.getInstance(),c=Calendar.getInstance();c.setTimeInMillis(ms);
        return new SimpleDateFormat(now.get(Calendar.YEAR)==c.get(Calendar.YEAR)?"d MMM":"d MMM yyyy",new Locale("es","CL")).format(new Date(ms)).replace(".","");}
    private static boolean sameTitle(String a,String b){return strip(a).equalsIgnoreCase(strip(b));}
    private static String strip(String t){return (t==null?"":t.trim()).replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s*","").trim();}
    /** El título sugerido por la nota nunca pisa uno escrito: se ofrece y se puede deshacer. */
    private void useSuggested(String suggested){
        String previous=recording.title;
        try{recording.title=suggested;recording.save(this);Pipeline.edited(this,id);Diagnostics.event("title_suggestion_used",id);Ui.haptic(content,Ui.Haptic.CONFIRM);reload();
            snackbar("Título cambiado","Deshacer",()->{try{recording.title=previous;recording.save(this);Pipeline.edited(this,id);reload();}catch(Exception e){message("Título","No se pudo deshacer el cambio.");}});}
        catch(Exception e){recording.title=previous;message("Título","No se pudo cambiar el título.");}
    }

    // ---------- Zona fija de abajo: onda, controles y botón que avanza ----------
    private void buildDock(){
        bottom.removeAllViews();bottom.setVisibility(View.VISIBLE);bottom.setBackgroundColor(0);bottom.setClipToPadding(false);bottom.setPadding(ui.dp(S3),0,ui.dp(S3),ui.dp(S3));
        LinearLayout dock=ui.column();dock.setBackground(shape(this,p.card,R_SHEET));dock.setElevation(ui.dp(3));dock.setPadding(ui.dp(S3),ui.dp(S2),ui.dp(S3),ui.dp(S3));
        if(!demo){
            scrubber=new Scrubber(this,p);scrubber.setDuration(recording.duration);scrubber.listener=this::onScrub;dock.addView(scrubber,new LinearLayout.LayoutParams(-1,ui.dp(48)));
            LinearLayout controls=ui.row();
            play=ui.iconButton(R.drawable.ic_play,"Reproducir",p.onPrimary,p.primary,48);play.setOnClickListener(v->toggle());controls.addView(play);
            timeText=ui.oneLine(ui.text(Recording.time(0)+" / "+Recording.time(recording.duration),Type.BODY_MEDIUM,p.onSurfaceVariant));timeText.setFontFeatureSettings("tnum");timeText.setPadding(ui.dp(S3),0,ui.dp(S1),0);
            controls.addView(timeText,new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(skipButton(false));controls.addView(skipButton(true));controls.addView(speedBox());
            dock.addView(controls,Ui.fill());
        }
        primary=ui.split(demo?"Nombrar voces":"Transcribir",demo?R.drawable.ic_people:R.drawable.ic_sparkle,v->onPrimary(),v->moreSheet());
        LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(demo?S1:S2);dock.addView(primary,lp);
        LinearLayout.LayoutParams dl=Ui.fill();dl.topMargin=ui.dp(S2);bottom.addView(dock,dl);
    }
    private View skipButton(boolean forward){
        TextView b=ui.text(forward?"+15":"−15",Type.LABEL_LARGE,p.onSurface);b.setGravity(Gravity.CENTER);b.setFontFeatureSettings("tnum");
        b.setBackground(ui.ripple(null,R_FULL));b.setContentDescription(forward?"Adelantar 15 segundos":"Retroceder 15 segundos");b.setAccessibilityDelegate(Ui.buttonRole());
        b.setOnClickListener(v->skip(forward?15000:-15000));Ui.pressable(b);b.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));return b;
    }
    private View speedBox(){
        FrameLayout box=new FrameLayout(this);box.setMinimumWidth(ui.dp(56));
        speedChip=ui.chip(speedLabel(),p.onSurface,p.surfaceContainerHighest);speedChip.setMinWidth(ui.dp(44));speedChip.setFontFeatureSettings("tnum");speedChip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(speedChip,new FrameLayout.LayoutParams(-2,ui.dp(32),Gravity.CENTER));
        box.setClickable(true);box.setFocusable(true);box.setContentDescription("Velocidad "+speedLabel());box.setAccessibilityDelegate(Ui.buttonRole());box.setOnClickListener(v->cycleSpeed());Ui.pressable(box);
        box.setLayoutParams(new LinearLayout.LayoutParams(-2,ui.dp(48)));speedBox=box;return box;
    }
    /** El botón principal según el siguiente paso de la grabación (Transcribir → Revisar voces → Guardar → ✓). */
    private void refreshPrimary(){
        if(primary==null)return;
        if(demo){primary.setLabel("Nombrar voces");primary.setIcon(R.drawable.ic_people);primary.setTonal(false);primary.setBusy(false);return;}
        Next n;try{n=Next.of(this,recording);}catch(Exception e){n=null;}
        next=n;
        if(saving){primary.setLabel("Guardando…");primary.setBusy(true);return;}
        if(n==null){primary.setLabel(transcript!=null?"Guardar o compartir":"Transcribir");primary.setTonal(false);primary.setBusy(false);return;}
        primary.setLabel(n.label);primary.setIcon(n.icon);primary.setTonal(n.step==Next.Step.SAVED);primary.setBusy(n.step==Next.Step.WORKING);
    }
    private void onPrimary(){
        if(demo){openNameVoices();return;}
        if(next==null){if(transcript!=null)moreSheet();else RecordingActions.transcribe(this,recording,this::reload);return;}
        switch(next.step){
            case TRANSCRIBE:case RETRY:RecordingActions.transcribe(this,recording,this::reload);break;
            case WORKING:scroll.smoothScrollTo(0,0);break;
            case REVIEW:openNameVoices();break;
            case SAVE:case UPDATE:inboxSave();break;
            case SAVED:savedSheet();break;
            case CHOOSE_FOLDER:startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true));break;
        }
    }
    /** ▾: todas las salidas siguen a mano (copiar, compartir, .txt, nota, otra carpeta, volver a transcribir). */
    private void moreSheet(){
        if(transcript==null){if(!demo)RecordingActions.menu(this,recording,this::reload,this::releasePlayer);return;}
        Sheet s=sheet("Guardar o compartir",null);
        s.action(R.drawable.ic_copy,"Copiar texto",false,this::copy);
        s.action(R.drawable.ic_share,"Compartir",false,this::shareText);
        s.action(R.drawable.ic_doc,"Archivo .txt",false,this::shareTxt);
        if(!demo){
            if(Notes.exists(this,id))s.action(R.drawable.ic_sparkle,"Compartir nota .md",false,this::shareNote);
            Next.Step step=next==null?null:next.step;
            if(Inbox.configured(this)&&step!=Next.Step.SAVE&&step!=Next.Step.UPDATE)s.action(R.drawable.ic_save,(step==Next.Step.SAVED?"Guardar de nuevo en ":"Guardar en ")+Inbox.folderName(this),false,this::inboxSave);
            s.action(R.drawable.ic_folder,"Guardar en otra carpeta…",false,this::saveAs);
            s.action(R.drawable.ic_refresh,"Volver a transcribir…",false,()->RetranscribeSheet.show(this,recording,this::reload));
        }
        s.show();
    }
    private void savedSheet(){
        String folder=Inbox.folderName(this);long at=Inbox.savedAt(this,id);
        sheet("Ya está en "+folder,(at>0?"Guardado a las "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(at))+". ":"")+"Si cambiaste algo, vuelve a guardarlo: se reemplaza el mismo archivo, sin crear copias.")
            .primary("Guardar de nuevo",this::inboxSave).secondary("Más opciones",this::moreSheet).show();
    }

    // ---------- Reproductor ----------
    private long total(){return player!=null&&prepared?player.getDuration():recording.duration;}
    private void ensurePlayer(){
        if(player!=null||demo)return;
        if(RecorderService.activeId!=null){message("Grabación en curso","Guarda la grabación actual antes de reproducir.");return;}
        try{
            player=new MediaPlayer();AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();player.setAudioAttributes(attr);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).setOnAudioFocusChangeListener(c->{if(c<0&&player!=null&&player.isPlaying()){player.pause();setPlaying(false);}}).build();
            player.setDataSource(recording.audio(this).getAbsolutePath());player.prepare();prepared=true;if(scrubber!=null)scrubber.setDuration(player.getDuration());
            player.setOnCompletionListener(mp->{setPlaying(false);if(playUntil>0){playUntil=0;if(tramoEnded!=null)tramoEnded.run();}});
            player.setOnErrorListener((mp,w,e)->{releasePlayer();message("No se pudo reproducir","El audio puede haberse interrumpido al grabar.");return true;});
            Diagnostics.event("playback_open",id);
        }catch(Exception e){releasePlayer();message("No se pudo reproducir","El archivo de audio no se pudo abrir.");}
    }
    private void toggle(){ensurePlayer();if(!prepared)return;
        if(player.isPlaying()){player.pause();setPlaying(false);}
        else if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){applySpeed();player.start();setPlaying(true);}}
    /** ▶ lleno y redondo en pausa; al sonar, ‖ en un contenedor tonal más cuadrado (cambia de forma, como en Material 3 Expressive). */
    private void setPlaying(boolean on){
        if(play==null)return;
        play.setImageResource(on?R.drawable.ic_pause:R.drawable.ic_play);play.setContentDescription(on?"Pausar":"Reproducir");play.setImageTintList(ColorStateList.valueOf(on?p.onPrimaryContainer:p.onPrimary));
        int radius=on?R_CARD:R_FULL;play.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),shape(this,on?p.primaryContainer:p.primary,radius),shape(this,0xFF000000,radius)));
        labelPlaying(on);
    }
    private void skip(int ms){ensurePlayer();if(!prepared)return;int to=Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+ms));player.seekTo(to);if(scrubber!=null)scrubber.setPosition(to);timeText.setText(Recording.time(to)+" / "+Recording.time(total()));}
    private void onScrub(long ms,boolean done){
        if(timeText!=null)timeText.setText(Recording.time(ms)+" / "+Recording.time(total()));
        if(!done)return;ensurePlayer();if(!prepared)return;playUntil=0;player.seekTo((int)ms);lastUserScroll=0;followPending=true;
    }
    /** Salta a un momento (★, frase clave) y reproduce desde ahí; la transcripción lo sigue. */
    private void playAt(long ms){
        ensurePlayer();if(!prepared)return;playUntil=0;player.seekTo((int)Math.max(0,Math.min(ms,player.getDuration())));if(!player.isPlaying())toggle();
        if(scrubber!=null)scrubber.setPosition(ms);lastUserScroll=0;followPending=true;
    }
    /** Escuchar sin perder el lugar: no sube al reproductor; la intervención que suena se resalta. Tocar de nuevo la misma hora pausa. */
    private void playTurn(View turn,double seconds){
        ensurePlayer();if(!prepared)return;
        if(player.isPlaying()&&playingTurn==turn&&playUntil==0){toggle();return;}
        // La elegiste tú y está a la vista: no hace falta desplazar.
        playUntil=0;player.seekTo((int)(seconds*1000));if(!player.isPlaying())toggle();markPlaying(turn);followPending=false;Diagnostics.event("turn_play",id);
    }
    /** Reproduce solo [from, to] (s) y se detiene sola. Devuelve false si no se pudo reproducir. */
    private boolean playRange(double from,double to){
        ensurePlayer();if(!prepared)return false;
        player.seekTo((int)(from*1000));playUntil=(long)(to*1000)+200;if(!player.isPlaying())toggle();
        if(!player.isPlaying()){playUntil=0;return false;}return true;
    }
    private void stopTramo(){playUntil=0;if(player!=null&&prepared&&player.isPlaying())toggle();}
    private void tickPlayback(int pos){
        if(playUntil>0&&pos>=playUntil){playUntil=0;if(player.isPlaying())toggle();if(tramoEnded!=null)tramoEnded.run();}
        if(!player.isPlaying())return;
        View now=null;for(Object[] turn:turns){if(pos>=(long)turn[1]-300&&pos<(long)turn[2]+300){now=(View)turn[0];break;}}
        if(now!=null)markPlaying(now);
        if(followPending&&playingTurn!=null&&SystemClock.uptimeMillis()-lastUserScroll>FOLLOW_IDLE_MS){followPending=false;followTo(playingTurn,false);}
    }
    private void markPlaying(View turn){
        if(turn==playingTurn)return;
        if(playingTurn!=null){playingTurn.setBackground(null);Object[] old=turnOf(playingTurn);if(old!=null)((TextView)old[3]).setText((String)old[4]);}
        playingTurn=turn;
        if(turn!=null){turn.setBackground(shape(this,p.primaryContainer,R_CARD));if(restoringPlaying)restoringPlaying=false;else followPending=true;labelPlaying(player!=null&&prepared&&player.isPlaying());}
    }
    private boolean restoringPlaying;
    private void labelPlaying(boolean on){Object[] t=playingTurn==null?null:turnOf(playingTurn);if(t!=null)((TextView)t[3]).setText(on?t[4]+" · sonando":(String)t[4]);}
    private Object[] turnOf(View v){for(Object[] t:turns)if(t[0]==v)return t;return null;}
    /** Trae la intervención a la vista con un desplazamiento suave (solo si no está ya en la zona cómoda de lectura). */
    private void followTo(View turn,boolean force){
        if(turn==null||!turn.isAttachedToWindow())return;
        Rect r=new Rect();turn.getDrawingRect(r);try{scroll.offsetDescendantRectToMyCoords(turn,r);}catch(IllegalArgumentException e){return;}
        int y=scroll.getScrollY(),h=scroll.getHeight();
        if(!force&&r.top>=y+ui.dp(S2)&&r.top<=y+h*0.6f)return;
        scroll.smoothScrollTo(0,Math.max(0,r.top-(int)(h*0.2f)));
    }
    private void updateFollowChip(){
        if(followChip==null)return;
        boolean show=false,below=true;
        if(player!=null&&prepared&&player.isPlaying()&&playingTurn!=null&&playingTurn.isAttachedToWindow()){
            Rect r=new Rect();playingTurn.getDrawingRect(r);
            try{scroll.offsetDescendantRectToMyCoords(playingTurn,r);int y=scroll.getScrollY(),h=scroll.getHeight();if(r.bottom<=y||r.top>=y+h){show=true;below=r.top>=y+h;}}catch(IllegalArgumentException ignored){}
        }
        if(show){String label=(below?"↓":"↑")+" Volver a lo que suena";if(!label.contentEquals(followChip.getText())){followChip.setText(label);followChip.setContentDescription("Volver a lo que suena");}}
        boolean visible=followChip.getVisibility()==View.VISIBLE;
        if(show!=visible){followChip.setVisibility(show?View.VISIBLE:View.GONE);if(show)ui.fadeIn(followChip);}
    }
    private void updateBarTitle(){
        if(barTitle==null||titleView==null||!titleView.isAttachedToWindow())return;
        Rect r=new Rect();titleView.getDrawingRect(r);try{scroll.offsetDescendantRectToMyCoords(titleView,r);}catch(IllegalArgumentException e){return;}
        boolean show=scroll.getScrollY()>r.bottom-ui.dp(S2);
        if(show!=barTitleShown){barTitleShown=show;barTitle.animate().alpha(show?1f:0f).setDuration(MOTION_FAST).start();}
    }
    private String speedLabel(){String v=rate==(int)rate?String.valueOf((int)rate):String.valueOf(rate).replace('.',',');return v+"×";}
    private void cycleSpeed(){float[] rates={1f,1.25f,1.5f,2f,0.75f};int i=0;for(int k=0;k<rates.length;k++)if(Math.abs(rates[k]-rate)<0.01f)i=k;rate=rates[(i+1)%rates.length];
        speedChip.setText(speedLabel());speedBox.setContentDescription("Velocidad "+speedLabel());applySpeed();}
    private void applySpeed(){if(player!=null&&prepared)try{boolean playing=player.isPlaying();player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));if(!playing&&player.isPlaying())player.pause();}catch(Exception ignored){}}
    private void releasePlayer(){prepared=false;if(player!=null){player.release();player=null;}if(focus!=null){getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}setPlaying(false);}
    /** La envolvente guardada se dibuja al tiro; si no existe, se calcula en segundo plano y la onda aparece al terminar. */
    private void loadWave(){
        if(scrubber==null)return;
        float[] cached=null;try{cached=WaveData.cached(this,recording);}catch(Exception ignored){}
        if(cached!=null){scrubber.setEnvelope(cached);return;}
        Context app=getApplicationContext();Recording r=recording;
        new Thread(()->{float[] v=null;try{v=WaveData.compute(app,r);}catch(Throwable ignored){}float[] env=v;
            if(env!=null)runOnUiThread(()->{if(!isDestroyed()&&scrubber!=null)scrubber.setEnvelope(env);});},"wave").start();
    }

    // ---------- Sin transcribir ----------
    private void showNew(){
        Settings s=new Settings(this);LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.tile(R.drawable.ic_sparkle,p.primary,p.primaryContainer,44,24));
        TextView h=ui.text("Transcribe este audio",Type.TITLE_LARGE,p.onSurface);h.setPadding(0,ui.dp(S3),0,ui.dp(S1));card.addView(h);
        card.addView(ui.text(s.hasKey()?"Toca «Transcribir» abajo. Al transcribir eliges si separar voces. Proveedor: "+SettingsActivity.modelSummary(s)+".":"Agrega tu clave de API para transcribir: toca «Transcribir» abajo. Solo pagas lo que usas en tu cuenta del proveedor.",Type.BODY_MEDIUM,p.onSurfaceVariant));
        if(s.hasKey()&&s.provider().equals("openai")){double voices=Pricing.estimate("openai","gpt-4o-transcribe-diarize",recording.duration),text=Pricing.estimate("openai",s.textModel(),recording.duration);
            if(voices>=0&&text>=0){TextView c=ui.text("≈ "+Pricing.usd(voices)+" separando voces · ≈ "+Pricing.usd(text)+" solo el texto",Type.BODY_SMALL,p.onSurfaceVariant);c.setPadding(0,ui.dp(S2),0,0);card.addView(c);}}
        content.addView(card,ui.top(S2));
    }

    // ---------- En curso: titular, partes, etapas, estimación y bitácora (se actualiza en su lugar) ----------
    private TextView progHeadline,progParts,progEstimate,progNote,phaseElapsed,upText;private Meter progBar,upBar;private LinearLayout progStages,conditions;private View startNow;
    private long phaseSince,conditionsAt,lastEstimateAt;private String shownBlocker;
    private void resetLiveViews(){progHeadline=progParts=progEstimate=progNote=phaseElapsed=upText=null;progBar=upBar=null;progStages=conditions=null;startNow=null;liveTotal=liveRemaining=null;liveState=null;logList=statsHolder=null;conditionsAt=0;shownBlocker=null;}
    private void showQueued(JSONObject st){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S4));
        LinearLayout head=ui.row();head.setGravity(Gravity.TOP);
        ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ui.dp(22),ui.dp(22));sp.topMargin=ui.dp(1);head.addView(spin,sp);head.addView(ui.space(S3));
        progHeadline=ui.text("",Type.TITLE_MEDIUM,p.onSurface);progHeadline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);head.addView(progHeadline,new LinearLayout.LayoutParams(0,-2,1));
        progEstimate=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);progEstimate.setFontFeatureSettings("tnum");progEstimate.setPadding(ui.dp(S2),ui.dp(1),0,0);head.addView(progEstimate);
        card.addView(head,Ui.fill());
        phaseElapsed=ui.text("",Type.BODY_SMALL,p.onSurfaceVariant);phaseElapsed.setFontFeatureSettings("tnum");phaseElapsed.setPadding(ui.dp(22+S3),ui.dp(2),0,0);card.addView(phaseElapsed);
        if(st.has("retranscribe")){TextView r=ui.text("Volviendo a transcribir · tu versión anterior se conserva",Type.BODY_MEDIUM,p.onSurfaceVariant);r.setPadding(0,ui.dp(S3),0,0);card.addView(r);}
        progParts=ui.text("",Type.BODY_LARGE,p.onSurface);progParts.setPadding(0,ui.dp(S4),0,ui.dp(S2));card.addView(progParts);
        progBar=new Meter(this,p.primary,p.secondaryContainer);card.addView(progBar,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        upText=ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant);upText.setFontFeatureSettings("tnum");upText.setPadding(0,ui.dp(S4),0,ui.dp(S2));card.addView(upText);
        upBar=new Meter(this,p.primary,p.secondaryContainer);card.addView(upBar,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);progStages=ui.row();hs.addView(progStages);card.addView(hs,ui.top(S4));
        conditions=ui.column();conditions.setPadding(0,ui.dp(S3),0,0);card.addView(conditions,Ui.fill());
        startNow=ui.button("Empezar ahora",R.drawable.ic_play,Ui.Style.TONAL,v->{if(Pipeline.startForeground(this))toast("Transcribiendo en primer plano");else message("Empezar ahora","Android no permitió empezar todavía. Se hará automáticamente.");});
        card.addView(startNow,ui.top(S3));
        View div=new View(this);div.setBackgroundColor(p.outlineVariant);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(1)));dl.topMargin=ui.dp(S4);card.addView(div,dl);
        progNote=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);progNote.setPadding(0,ui.dp(S3),0,0);card.addView(progNote);
        card.addView(details(st,true),Ui.fill());
        content.addView(card,ui.top(S2));
        liveState=st;renderConditions();updateProgress(st);
    }
    /** Actualiza la tarjeta en su lugar con el estado nuevo (sin reconstruir la pantalla). */
    private void updateProgress(JSONObject st){
        if(progHeadline==null)return;
        liveState=st;phaseSince=st.optLong("since",System.currentTimeMillis());
        progHeadline.setText(human(st.optString("status","")));
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone");boolean parts=blocks>1;
        progParts.setVisibility(parts?View.VISIBLE:View.GONE);progBar.setVisibility(parts?View.VISIBLE:View.GONE);
        if(parts){progParts.setText(done+" de "+blocks+" partes listas");progBar.set(done/(float)blocks);}
        long sent=st.optLong("upSent"),total=st.optLong("upTotal");boolean uploading=total>0&&sent<total;
        upText.setVisibility(uploading?View.VISIBLE:View.GONE);upBar.setVisibility(uploading?View.VISIBLE:View.GONE);
        if(uploading){upText.setText(String.format(Locale.ROOT,"Enviando %.1f de %.1f MB",sent/1e6,total/1e6).replace('.',','));upBar.set(sent/(float)total);}
        renderStages(st);refreshWaiting(shownBlocker);
        if(statsHolder!=null){statsHolder.removeAllViews();statsHolder.addView(stats(st,true),Ui.fill());}
        if(logList!=null){JSONArray log=st.optJSONArray("log");int n=log==null?0:log.length();JSONObject last=n==0?null:log.optJSONObject(n-1);long lastT=last==null?0:last.optLong("t");
            if(n!=logCount||lastT!=logLast){logCount=n;logLast=lastT;renderLog(logList,log);}}
    }
    /** Etapas reales: Subido · Partes x/n · Unir voces · Nota · Lista. La barra avanza solo con partes terminadas de verdad. */
    private void renderStages(JSONObject st){
        if(progStages==null)return;progStages.removeAllViews();
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone");long sent=st.optLong("upSent"),total=st.optLong("upTotal");
        boolean uploaded=done>0||(total>0&&sent>=total)||logHas(st,"enviado"),partsDone=blocks>0&&done>=blocks;
        stage("Subido",uploaded?2:TranscribeService.running?1:0);
        stage(blocks>1?"Partes "+done+"/"+blocks:"Texto",partsDone?2:uploaded?1:0);
        if(st.optBoolean("speakers")&&blocks>1)stage("Unir voces",partsDone?1:0);
        if(new Settings(this).noteAuto()&&Notes.canGenerate(this))stage("Nota",0);
        stage("Lista",0);
    }
    /** state: 0 pendiente, 1 en curso, 2 listo. */
    private void stage(String label,int state){
        TextView t=ui.text(state==2?"✓ "+label:label,Type.LABEL_MEDIUM,state==2?p.primary:state==1?p.onSecondaryContainer:p.onSurfaceVariant);
        t.setGravity(Gravity.CENTER);t.setMinHeight(ui.dp(28));t.setPadding(ui.dp(10),0,ui.dp(10),0);
        t.setBackground(state==2?shape(this,p.surfaceContainerLow,R_SMALL):state==1?shape(this,p.secondaryContainer,R_SMALL):outline(this,0x00000000,p.outlineVariant,R_SMALL,false));
        t.setContentDescription(label+(state==2?": listo":state==1?": en curso":": pendiente"));
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(6));progStages.addView(t,lp);
    }
    private static boolean logHas(JSONObject st,String word){JSONArray log=st.optJSONArray("log");if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e!=null&&e.optString("m").contains(word))return true;}return false;}
    /** Qué pasa ahora: tranquilidad si trabaja, o por qué espera; «Empezar ahora» solo si Android la está demorando. */
    private void refreshWaiting(String blocker){
        if(progNote==null)return;boolean running=TranscribeService.running;
        progNote.setText(running?"Puedes cerrar la app: te aviso cuando esté lista.":blocker!=null?"Esperando: "+blocker+". Empieza sola cuando se cumpla; puedes cerrar la app.":"Android aún no la empieza. Se hará sola, o toca «Empezar ahora».");
        startNow.setVisibility(!running&&blocker==null?View.VISIBLE:View.GONE);
        refreshEstimate();
    }
    private void refreshEstimate(){if(progEstimate==null)return;String est=TranscribeService.running&&liveState!=null?estimate(liveState):"";progEstimate.setText(est);progEstimate.setVisibility(est.isEmpty()?View.GONE:View.VISIBLE);lastEstimateAt=System.currentTimeMillis();}
    /**
     * Estimación honesta y como rango. Con varias partes, por lo que tardaron las ya listas; con una sola (≤ 12 min),
     * por tiempo: la separación de voces tarda ~0,15× la duración del audio.
     */
    private String estimate(JSONObject st){
        long now=System.currentTimeMillis(),audio=st.optLong("audioMs",recording.duration),sum=st.optLong("blockMsSum");int blocks=st.optInt("blocks"),done=st.optInt("blocksDone"),count=st.optInt("blockCount");
        long left;
        if(blocks>1&&count>0){if(done>=blocks)return "casi lista";int rounds=(blocks-done+Transcriber.PARALLEL-1)/Transcriber.PARALLEL;long avg=sum/count;left=Math.max(avg/5,rounds*avg-(now-st.optLong("since",now)));}
        else{double factor=st.optBoolean("speakers",true)?0.16:0.08;left=(long)(audio*factor)+20000-(now-startedAt(st));}
        if(left<=20000)return left>-120000?"casi lista":"";
        long lo=Math.max(1,Math.round(left*0.8/60000.0)),hi=Math.max(lo+1,Math.round(left*1.3/60000.0));
        return hi>=60?"≈ "+Ui.humanDuration(left):"≈ "+lo+"–"+hi+" min";
    }
    /** Cuándo empezó a trabajar de verdad (no el tiempo esperando Wi-Fi o el cargador). */
    private static long startedAt(JSONObject st){
        JSONArray log=st.optJSONArray("log");
        if(log!=null)for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;String m=e.optString("m");if(!m.startsWith("En cola")&&!m.startsWith("En espera")&&!m.contains("esperando"))return e.optLong("t");}
        return st.optLong("queuedAt",System.currentTimeMillis());
    }
    /** La última línea de la bitácora en palabras simples («parte» en vez de «bloque», sin tiempos técnicos). */
    static String human(String m){
        if(m==null||m.trim().isEmpty())return "En cola";
        String s=m.replaceAll("Bloque (\\d+) de (\\d+) listo","Parte $1 de $2 lista").replaceAll("Bloque (\\d+) enviado","Parte $1 enviada")
            .replace("El bloque","La parte").replace("del bloque","de la parte").replace("el bloque","la parte").replace("los bloques","las partes").replace("bloques","partes").replace("Bloque","Parte").replace("bloque","parte");
        String[] parts=s.split(" · ");StringBuilder b=new StringBuilder(parts[0].trim());
        for(int i=1;i<parts.length;i++){String x=parts[i].trim();if(x.isEmpty()||x.startsWith("tardó")||x.startsWith("se envían")||x.startsWith("tiempo total"))continue;if(b.length()+x.length()>110)break;b.append(" · ").append(x);}
        return b.toString();
    }
    /** Condiciones reales del teléfono. Si todo está bien, una sola línea tranquila; si algo falta, cada condición con su salida. */
    private void renderConditions(){
        if(conditions==null)return;
        String blocker=Pipeline.blocker(this);
        // Si una condición cambió (p. ej. conectaste el cargador), se intenta empezar ya.
        if(conditionsAt>0&&blocker==null&&!Objects.equals(blocker,shownBlocker))Pipeline.startForeground(this);
        shownBlocker=blocker;conditions.removeAllViews();conditionsAt=System.currentTimeMillis();Settings s=new Settings(this);
        boolean online=Pipeline.network(this)!=null,wifi=Pipeline.unmetered(this);android.os.BatteryManager bm=getSystemService(android.os.BatteryManager.class);boolean charging=bm.isCharging();int level=bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean netOk=online&&(!s.wifiOnly()||wifi),chargeOk=!s.charging()||charging,batteryOk=charging||level>15,free=Battery.unrestricted(this);
        if(netOk&&chargeOk&&batteryOk&&free)condition(true,(wifi?"Wi-Fi":"Datos móviles")+" · batería "+level+" % · sigue con la pantalla bloqueada");
        else{
            condition(netOk,!online?"Sin conexión a internet":s.wifiOnly()?(wifi?"Wi-Fi conectado":"Se requiere Wi-Fi · ahora usas datos móviles"):(wifi?"Conectado por Wi-Fi":"Conectado por datos móviles"));
            condition(chargeOk,s.charging()?(charging?"Cargando":"Se requiere conectar el cargador"):"Cargador no requerido");
            condition(batteryOk,"Batería "+level+" %"+(batteryOk?"":" · Android espera a que cargues"));
            // Con la optimización activa, algunos teléfonos congelan la app con la pantalla bloqueada y cortan la conexión.
            condition(free,free?"Puede trabajar con la pantalla bloqueada":"Android optimiza la batería de Voz local · puede cortar la transcripción al bloquear");
            if(!free){Ui.Btn allow=ui.button("Permitir en segundo plano",R.drawable.ic_battery,Ui.Style.TONAL,v->RecordingActions.allowBackground(this));conditions.addView(allow,ui.top(S2));}
        }
        refreshWaiting(blocker);
    }
    private void condition(boolean ok,String text){LinearLayout r=ui.row();r.setPadding(0,ui.dp(3),0,ui.dp(3));r.addView(ui.icon(ok?R.drawable.ic_check_circle:R.drawable.ic_clock,ok?p.primary:p.error,18));r.addView(ui.space(S2));r.addView(ui.text(text,Type.BODY_MEDIUM,ok?p.onSurfaceVariant:p.onSurface),new LinearLayout.LayoutParams(0,-2,1));conditions.addView(r);}
    /** Cada 250 ms: cronómetros; cada segundo la estimación; cada 3 s las condiciones. */
    private void tickProcess(){
        if(liveTotal!=null&&liveState!=null&&liveTotal.isAttachedToWindow()){liveTotal.setText(Recording.time(System.currentTimeMillis()-liveState.optLong("queuedAt",System.currentTimeMillis())));if(liveRemaining!=null)liveRemaining.setText(remaining(liveState));}
        if(mode!=Mode.QUEUED||phaseElapsed==null||!phaseElapsed.isAttachedToWindow())return;
        long now=System.currentTimeMillis();
        phaseElapsed.setText("En este paso hace "+Recording.time(now-phaseSince));
        if(now-lastEstimateAt>=1000)refreshEstimate();
        if(now-conditionsAt>3000)renderConditions();
    }

    // ---------- Métricas y bitácora (detalles del proceso) ----------
    private TextView liveTotal,liveRemaining;private JSONObject liveState;private LinearLayout logList,statsHolder;private int logCount=-1;private long logLast;
    /** Grilla de métricas. live=true: tiempo total y restante se actualizan cada 250 ms. El nombre del modelo vive solo aquí. */
    private View stats(JSONObject st,boolean live){
        LinearLayout grid=ui.column();grid.setBackground(shape(this,p.surfaceContainerLow,R_CONTROL));grid.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        long now=System.currentTimeMillis(),queued=st.optLong("queuedAt",now),audio=st.optLong("audioMs",recording.duration),doneAudio=st.optLong("doneAudioMs");
        long elapsed=live?now-queued:st.optLong("doneIn",now-queued);String model=st.optString("model","");
        List<String[]> cells=new ArrayList<>();
        cells.add(new String[]{"Tiempo total",Recording.time(elapsed)});
        if(live)cells.add(new String[]{"Restante (aprox.)",remaining(st)});
        cells.add(new String[]{"Audio procesado",Recording.time(live?doneAudio:audio)+" de "+Recording.time(audio)});
        long speedBase=live?doneAudio:audio;if(speedBase>0&&elapsed>0)cells.add(new String[]{"Velocidad",String.format(Locale.ROOT,"%.1f",speedBase/(double)elapsed).replace('.',',')+"× tiempo real"});
        String provider=st.optString("provider","openai");double spent=Pricing.estimate(provider,model,live?doneAudio:audio),total=Pricing.estimate(provider,model,audio);
        if(total>=0)cells.add(new String[]{live?"Costo hasta ahora":"Costo estimado",Pricing.usd(spent)+(live?" · total ≈"+Pricing.usd(total):"")});
        long in=st.optLong("inTokens"),out=st.optLong("outTokens");double secs=st.optDouble("usageSec",0);
        if(in+out>0)cells.add(new String[]{"Tokens",String.format(Locale.ROOT,"%,d",in+out).replace(',','.')+" ("+String.format(Locale.ROOT,"%,d",in).replace(',','.')+" entrada)"});
        else if(secs>0)cells.add(new String[]{"Audio facturado",Recording.time((long)(secs*1000))});
        long sent=st.optLong("bytesSent")+(live?st.optLong("upSent"):0);if(sent>0)cells.add(new String[]{"Datos enviados",String.format(Locale.ROOT,"%.1f MB",sent/1e6).replace('.',',')});
        int chars=st.optInt("liveChars");if(live&&chars>0)cells.add(new String[]{"Texto recibido",String.format(Locale.ROOT,"%,d",chars).replace(',','.')+" caracteres"});
        int retries=st.optInt("retries",st.optInt("attempts"));if(retries>0)cells.add(new String[]{"Reintentos",retries+(st.optInt("localCuts")>0?" · "+st.optInt("localCuts")+" por el teléfono":"")});
        for(int i=0;i<cells.size();i+=2){
            LinearLayout line=ui.row();line.setGravity(Gravity.TOP);line.setPadding(0,ui.dp(S1),0,ui.dp(S1));
            for(int k=i;k<Math.min(i+2,cells.size());k++){LinearLayout cell=ui.column();cell.addView(ui.text(cells.get(k)[0],Type.BODY_SMALL,p.onSurfaceVariant));TextView v=ui.text(cells.get(k)[1],Type.TITLE_SMALL,p.onSurface);v.setFontFeatureSettings("tnum");cell.addView(v);line.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
                if(live&&k==0)liveTotal=v;if(live&&k==1)liveRemaining=v;}
            if(cells.size()-i==1)line.addView(ui.flex());grid.addView(line,Ui.fill());
        }
        if(!model.isEmpty()){TextView m=ui.text("Modelo: "+model+(st.optBoolean("speakers")?" · separa voces":"")+(total>=0?" · tarifa pública al "+Pricing.REVIEWED+", el cobro real puede variar":""),Type.BODY_SMALL,p.onSurfaceVariant);m.setPadding(0,ui.dp(S2),0,0);grid.addView(m);}
        if(live)liveState=st;return grid;
    }
    /** Estimación: promedio por bloque × rondas restantes (bloques de a PARALLEL en paralelo). */
    private String remaining(JSONObject st){
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone"),count=st.optInt("blockCount");long sum=st.optLong("blockMsSum");
        if(blocks<=0||count==0)return "calculando…";if(done>=blocks)return "casi listo";
        int left=blocks-done;int rounds=(left+Transcriber.PARALLEL-1)/Transcriber.PARALLEL;long avg=sum/count;
        long est=Math.max(avg/5,rounds*avg-(System.currentTimeMillis()-st.optLong("since",System.currentTimeMillis())));return "≈ "+Recording.time(est);
    }
    /**
     * «Ver detalles del proceso»: métricas y bitácora con la hora y duración de cada paso.
     * live=true (transcripción en curso): la app recuerda si la dejaste abierta.
     */
    private View details(JSONObject st,boolean live){
        LinearLayout box=ui.column();JSONArray log=st.optJSONArray("log");
        if(!live&&(log==null||log.length()==0))return box;
        LinearLayout body=ui.column();
        if(live)body.setPadding(0,ui.dp(S2),0,0);else{body.setBackground(shape(this,p.card,R_CARD));body.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));}
        LinearLayout statsBox=ui.column();body.addView(statsBox,Ui.fill());if(live||st.has("model"))statsBox.addView(stats(st,live),Ui.fill());
        TextView h=ui.text("Bitácora",Type.TITLE_SMALL,p.primary);h.setPadding(0,ui.dp(S4),0,ui.dp(S1));body.addView(h);
        LinearLayout list=ui.column();body.addView(list,Ui.fill());renderLog(list,log);
        if(live){statsHolder=statsBox;logList=list;logCount=log==null?0:log.length();JSONObject last=logCount==0?null:log.optJSONObject(logCount-1);logLast=last==null?0:last.optLong("t");}
        Settings settings=new Settings(this);boolean open=live?settings.bitacoraOpen():detailsOpen;
        Ui.Btn toggle=ui.button(open?"Ocultar detalles":"Ver detalles del proceso",R.drawable.ic_info,Ui.Style.PLAIN,null);body.setVisibility(open?View.VISIBLE:View.GONE);
        toggle.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);toggle.setText(show?"Ocultar detalles":"Ver detalles del proceso");if(live)settings.setBitacoraOpen(show);else detailsOpen=show;});
        LinearLayout.LayoutParams tl=Ui.wrap();tl.topMargin=ui.dp(live?S1:S2);box.addView(toggle,tl);box.addView(body,Ui.fill());return box;
    }
    private void renderLog(LinearLayout list,JSONArray log){
        list.removeAllViews();if(log==null)return;SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);
        for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;long t=e.optLong("t");JSONObject n=i+1<log.length()?log.optJSONObject(i+1):null;long next=n==null?0:n.optLong("t");
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(S1),0,ui.dp(S1));TextView time=ui.text(f.format(new Date(t)),Type.BODY_SMALL,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");r.addView(time,new LinearLayout.LayoutParams(ui.dp(64),-2));
            TextView m=ui.text(e.optString("m")+(next>0&&next-t>=1000?"  ("+Recording.time(next-t)+")":""),Type.BODY_SMALL,p.onSurface);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));list.addView(r);}
    }
    private void showFailed(JSONObject st){
        LinearLayout card=ui.card();card.setBackground(shape(this,p.errorContainer,R_CARD));LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.addView(ui.icon(R.drawable.ic_alert,p.onErrorContainer,24));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();texts.addView(ui.text("No se pudo transcribir",Type.TITLE_MEDIUM,p.onErrorContainer));texts.addView(ui.text(st.optString("status","No se pudo transcribir"),Type.BODY_MEDIUM,p.onErrorContainer));
        TextView hint=ui.text("Cuando lo revises, toca «Reintentar» abajo.",Type.BODY_SMALL,p.onErrorContainer);hint.setPadding(0,ui.dp(S2),0,0);texts.addView(hint);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,Ui.fill());
        if(Retranscribe.hasPrevious(this,id))card.addView(ui.button("Volver a la versión anterior",R.drawable.ic_refresh,Ui.Style.TONAL,v->restorePrevious()),ui.top(S4));
        card.addView(ui.button("Revisar ajustes",0,Ui.Style.PLAIN,v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))),ui.top(S1));
        content.addView(card,ui.top(S2));content.addView(details(st,false));
    }
    private void restorePrevious(){
        try{Retranscribe.restorePrevious(this,id);Diagnostics.event("retranscribe_restored",id);reload();snackbar("Volviste a la versión anterior",null,null);}
        catch(Exception e){message("Versión anterior","No se pudo recuperar la versión anterior.");}
    }

    // ---------- Transcripción como documento ----------
    /** Si no es null, solo se muestran las intervenciones de esta voz ("Ver solo sus intervenciones"). */
    private String onlySpeaker;
    /** Intervenciones en pantalla: {vista, inicio ms, fin ms, vista de la hora, texto de la hora}. */
    private final List<Object[]> turns=new ArrayList<>();private View playingTurn;
    /** Fin (ms) del tramo que se escucha desde una hoja; 0 = reproducción normal. */
    private long playUntil;private Runnable tramoEnded;
    /** Color de cada voz en esta vista (se calcula una vez por render: las transcripciones largas tienen miles de tramos). */
    private final Map<String,Integer> colors=new HashMap<>();

    private void showTranscript(JSONObject st){
        try{
            boolean diarized=transcript.diarized();Map<String,String> names=transcript.speakers();JSONArray segments=transcript.segments();
            if(onlySpeaker!=null&&!names.containsKey(onlySpeaker))onlySpeaker=null;
            List<String> order=transcript.order();colors.clear();for(String key:names.keySet())colors.put(key,p.speaker(order.indexOf(key)));
            if(diarized&&!names.isEmpty())content.addView(ficha(names),Ui.fill());
            if(demo)content.addView(note(R.drawable.ic_info,"Texto de demostración: no proviene de la API. Prueba a nombrar las voces."));
            // Aviso honesto: la separación automática puede equivocarse; se oculta cuando el usuario ya revisó las voces.
            if(diarized&&!transcript.reviewed()&&segments.length()>0)content.addView(note(R.drawable.ic_info,"Voces separadas automáticamente: pueden tener errores. Toca el nombre de una intervención para escuchar y corregir quién habla."));
            else if(!diarized&&transcript.data.optInt("parts",1)>1)content.addView(note(R.drawable.ic_info,"Audio procesado en partes: los tiempos indican el inicio de cada parte."));
            if(!demo&&segments.length()>0)content.addView(noteCard(st,names));
            addMarks();
            TextView heading=ui.text("Transcripción",Type.TITLE_SMALL,p.primary);heading.setPadding(ui.dp(S2),ui.dp(S6),ui.dp(S2),ui.dp(S1));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);content.addView(heading);
            if(onlySpeaker!=null){LinearLayout f=ui.row();f.setPadding(ui.dp(S2),0,0,ui.dp(S1));f.addView(ui.text("Mostrando solo a "+names.get(onlySpeaker),Type.BODY_MEDIUM,p.onSurfaceVariant),new LinearLayout.LayoutParams(0,-2,1));
                f.addView(ui.button("Ver todo",0,Ui.Style.PLAIN,v->{onlySpeaker=null;reload(true);}));content.addView(f,Ui.fill());}
            LinearLayout doc=ui.column();
            if(segments.length()==0)doc.addView(ui.text("No se detectó habla en este audio.",Type.BODY_LARGE,p.onSurfaceVariant));
            // Los separadores de parte solo se ven al corregir voces: ahí es donde una voz puede cruzarse entre partes.
            boolean dividers=correctionOpened||transcript.edited();
            List<Double> blocks=diarized?transcript.blockStarts(demo?null:st):new ArrayList<>();
            int block=0;boolean first=true;
            for(int i=0;i<segments.length();){
                JSONObject s=segments.getJSONObject(i);String speaker=s.getString("speaker");double start=s.getDouble("start");
                int b=block;while(b+1<blocks.size()&&start>=blocks.get(b+1)-0.05)b++;
                if(b!=block){block=b;if(onlySpeaker==null&&dividers){doc.addView(blockDivider(b,blocks.get(b)),Ui.fill());first=true;}}
                // Una intervención = tramos seguidos de la misma voz, sin pausas largas y dentro del mismo bloque.
                int j=i+1;double end=s.optDouble("end",start);
                while(j<segments.length()){JSONObject n=segments.getJSONObject(j);double ns=n.getDouble("start");if(!n.getString("speaker").equals(speaker)||ns-end>Transcript.TURN_GAP_S||(block+1<blocks.size()&&ns>=blocks.get(block+1)-0.05))break;end=Math.max(end,n.optDouble("end",ns));j++;}
                if(onlySpeaker==null||onlySpeaker.equals(speaker)){doc.addView(turn(i,j,speaker,start,end,names,diarized,first),Ui.fill());first=false;}
                i=j;
            }
            content.addView(doc,Ui.fill());
            if(!demo){String foot=footer(st);if(!foot.isEmpty()){TextView t=ui.text(foot,Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(ui.dp(S2),ui.dp(S5),ui.dp(S2),0);content.addView(t);}
                if(st.has("log"))content.addView(details(st,false));}
            updateStrip(segments,diarized);
        }catch(Exception e){content.addView(ui.text("No se pudo leer la transcripción.",Type.BODY_LARGE,p.error));}
    }
    /** Pie en palabras simples: «Transcrito el 23 sept · tardó 2 min · ≈ US$0,02». El modelo queda en los detalles. */
    private String footer(JSONObject st){
        List<String> parts=new ArrayList<>();long at=transcribedAt(st);if(at>0)parts.add("Transcrito el "+dayLabel(at));
        long took=st.optLong("doneIn");if(took>0)parts.add("tardó "+Ui.humanDuration(took));
        double cost=Pricing.estimate(transcript.data.optString("provider","openai"),transcript.data.optString("model"),recording.duration);if(cost>=0&&took>0)parts.add("≈ "+Pricing.usd(cost));
        return TextUtils.join(" · ",parts);
    }
    private long transcribedAt(JSONObject st){
        JSONArray log=st.optJSONArray("log");if(log!=null)for(int i=log.length()-1;i>=0;i--){JSONObject e=log.optJSONObject(i);if(e!=null&&e.optString("m").startsWith("Transcripción lista"))return e.optLong("t");}
        long q=st.optLong("queuedAt"),d=st.optLong("doneIn");if(q>0&&d>0)return q+d;
        java.io.File f=FilesStore.file(this,id,".transcript.json");return f.isFile()?f.lastModified():0;
    }
    /** Ficha: cada persona con su color y su parte del tiempo, y si las voces ya están revisadas. */
    private View ficha(Map<String,String> names)throws JSONException{
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout chips=ui.row();hs.addView(chips);
        Map<String,Double> share=transcript.talkShare();
        for(String key:names.keySet()){int color=colors.get(key);boolean only=key.equals(onlySpeaker);String pct=percent(share.get(key));
            LinearLayout c=ui.row();c.setBackground(chipBackground(only?shape(this,p.secondaryContainer,R_SMALL):outline(this,0x00000000,p.outline,R_SMALL,false)));c.setPadding(ui.dp(S3),0,ui.dp(S3),0);c.setMinimumHeight(ui.dp(48));
            View dot=new View(this);dot.setBackground(oval(color));c.addView(dot,new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8)));c.addView(ui.space(S2));
            c.addView(ui.text(names.get(key)+(pct.isEmpty()?"":"  "+pct),Type.LABEL_LARGE,only?p.onSecondaryContainer:p.onSurface));
            c.setClickable(true);c.setFocusable(true);c.setContentDescription(names.get(key)+(pct.isEmpty()?"":", "+pct+" del tiempo")+". Opciones");c.setAccessibilityDelegate(Ui.buttonRole());c.setOnClickListener(v->personSheet(key));Ui.pressable(c);
            LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));chips.addView(c,lp);}
        boolean reviewed=transcript.reviewed();
        TextView status=chip(reviewed?"Voces revisadas":"Voces sin revisar",reviewed?R.drawable.ic_check:R.drawable.ic_people,reviewed?p.onSurfaceVariant:p.onSecondaryContainer,reviewed?0:p.secondaryContainer,reviewed);
        status.setContentDescription((reviewed?"Voces revisadas":"Voces sin revisar")+". Nombrar voces");status.setOnClickListener(v->openNameVoices());chips.addView(status,Ui.wrap());
        return hs;
    }
    private static String percent(Double share){if(share==null)return "";long r=Math.round(share*100);return r<1?"<1 %":r+" %";}
    /** Franja de la onda con el color de quién habla en cada tramo (al corregir una voz, cambia al instante). */
    private void updateStrip(JSONArray segs,boolean diarized){
        if(scrubber==null)return;if(!diarized){scrubber.setStrip(null,null,null);return;}
        List<long[]> runs=new ArrayList<>();List<Integer> cols=new ArrayList<>();
        for(int i=0;i<segs.length();i++){JSONObject s=segs.optJSONObject(i);if(s==null)continue;Integer col=colors.get(s.optString("speaker"));if(col==null)continue;
            long a=(long)(s.optDouble("start")*1000),b=(long)(s.optDouble("end",s.optDouble("start"))*1000);int n=runs.size();
            if(n>0&&cols.get(n-1).equals(col)&&a-runs.get(n-1)[1]<1500)runs.get(n-1)[1]=Math.max(runs.get(n-1)[1],b);else{runs.add(new long[]{a,b});cols.add(col);}}
        long[] from=new long[runs.size()],to=new long[runs.size()];int[] c=new int[runs.size()];
        for(int i=0;i<runs.size();i++){from[i]=runs.get(i)[0];to[i]=runs.get(i)[1];c[i]=cols.get(i);}
        scrubber.setStrip(from,to,c);
    }
    private View note(int icon,String value){LinearLayout n=ui.row();n.setGravity(Gravity.TOP);n.setBackground(shape(this,p.primaryContainer,R_CONTROL));n.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));n.addView(ui.icon(icon,p.primary,18));n.addView(ui.space(S2));n.addView(ui.text(value,Type.BODY_MEDIUM,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S2);lp.bottomMargin=ui.dp(S1);n.setLayoutParams(lp);return n;}
    /** Chip de 36 dp visibles dentro de un área táctil de 48 dp. */
    private Drawable chipBackground(GradientDrawable fill){int v=ui.dp(6);return new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(fill,0,v,0,v),new InsetDrawable(shape(this,0xFF000000,R_SMALL),0,v,0,v));}
    private TextView chip(String text,int icon,int fg,int bg,boolean outlined){
        TextView t=ui.oneLine(ui.text(text,Type.LABEL_LARGE,fg));t.setGravity(Gravity.CENTER_VERTICAL);t.setMinHeight(ui.dp(48));t.setPadding(ui.dp(icon!=0?S2:S3),0,ui.dp(S3),0);
        t.setBackground(chipBackground(outlined?outline(this,0x00000000,p.outline,R_SMALL,false):shape(this,bg,R_SMALL)));
        if(icon!=0){Drawable d=getDrawable(icon).mutate();d.setTint(outlined?p.primary:fg);d.setBounds(0,0,ui.dp(18),ui.dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(ui.dp(S2));}
        t.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(t);return t;
    }

    // ---------- Momentos ★ ----------
    private long[] markTimes(){JSONArray m=Marks.list(this,id);long[] out=new long[m.length()];for(int i=0;i<m.length();i++){JSONObject o=m.optJSONObject(i);out[i]=o==null?0:o.optLong("t");}Arrays.sort(out);return out;}
    private boolean hasMark(double start,double end){long a=(long)(start*1000)-500,b=(long)(end*1000)+500;for(long m:marksMs)if(m>=a&&m<=b)return true;return false;}
    private View marksRow(){
        if(demo||marksMs.length==0)return null;JSONArray marks=Marks.list(this,id);
        LinearLayout box=ui.column();TextView h=ui.text("Momentos ★",Type.TITLE_SMALL,p.primary);h.setPadding(ui.dp(S2),ui.dp(S4),0,0);if(Build.VERSION.SDK_INT>=28)h.setAccessibilityHeading(true);box.addView(h);
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout row=ui.row();hs.addView(row);
        for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;long t=m.optLong("t");String label=m.optString("label","").trim();int index=i;
            TextView c=chip("★ "+Recording.time(t)+(label.isEmpty()?"":" · "+label),0,p.onSecondaryContainer,p.secondaryContainer,false);c.setFontFeatureSettings("tnum");
            c.setContentDescription("Momento "+Recording.time(t)+(label.isEmpty()?"":", "+label)+". Toca para escuchar; mantén pulsado para editar");
            c.setOnClickListener(v->{Diagnostics.event("mark_played",id);playAt(t);});c.setOnLongClickListener(v->{markSheet(index,t,label);return true;});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));row.addView(c,lp);}
        box.addView(hs,Ui.fill());return box;
    }
    private void markSheet(int index,long t,String label){
        Sheet s=sheet("Momento "+Recording.time(t),"Ponle un nombre para encontrarlo después («precio», «idea»…).");
        EditText f=ui.field("Nombre del momento","Nombre del momento");f.setText(label);f.setSingleLine(true);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});s.add(f);
        s.primary("Guardar",Ui.Style.PRIMARY,()->{try{Marks.rename(this,id,index,f.getText().toString().trim());Diagnostics.event("mark_renamed",id);reload();}catch(Exception e){message("Momento","No se pudo guardar el nombre.");}return true;}).secondary("Cancelar",null);
        s.action(R.drawable.ic_trash,"Eliminar momento",true,()->{try{Marks.remove(this,id,index);Diagnostics.event("mark_removed",id);reload();
            snackbar("Momento eliminado","Deshacer",()->{try{Marks.add(this,id,t,label);reload();}catch(Exception e){message("Momento","No se pudo deshacer.");}});}catch(Exception e){message("Momento","No se pudo eliminar el momento.");}});
        s.show();
    }

    // ---------- Nota para tu segundo cerebro ----------
    private View noteCard(JSONObject st,Map<String,String> names){
        String state=st.optString("noteState","");JSONObject note=Notes.load(this,id);
        if(noteBusy||"working".equals(state))return noteSkeleton();
        if(note!=null)return noteFull(note,names,"failed".equals(state)?st.optString("noteError",""):null);
        if("failed".equals(state))return noteMessage("No se pudo armar la nota",st.optString("noteError","").isEmpty()?"Vuelve a intentarlo en un momento.":st.optString("noteError"),"Reintentar",R.drawable.ic_refresh);
        if(Notes.canGenerate(this))return noteMessage("Nota para tu segundo cerebro","Resumen, decisiones, tareas y frases clave de esta grabación, listos para guardar.","Armar nota",R.drawable.ic_sparkle);
        // Sin IA para la nota: una pista corta que lleva a configurarla.
        LinearLayout box=ui.row();box.setPadding(0,ui.dp(S1),0,0);TextView hint=chip("Configura la IA de la nota en Ajustes",R.drawable.ic_sparkle,p.onSurfaceVariant,0,true);
        hint.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true)));box.addView(hint);return box;
    }
    private LinearLayout noteHeader(LinearLayout card,String subtitle){
        LinearLayout head=ui.row();head.setMinimumHeight(ui.dp(48));head.addView(ui.icon(R.drawable.ic_sparkle,p.primary,20));head.addView(ui.space(S3));
        LinearLayout titles=ui.column();TextView t=ui.text("Nota para tu segundo cerebro",Type.TITLE_MEDIUM,p.primary);if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);titles.addView(t);
        if(subtitle!=null)titles.addView(ui.text(subtitle,Type.BODY_SMALL,p.onSurfaceVariant));
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));card.addView(head,Ui.fill());return head;
    }
    private LinearLayout noteSurface(){LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S2),ui.dp(S4));LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S2);card.setLayoutParams(lp);return card;}
    private View noteSkeleton(){
        LinearLayout card=noteSurface();noteHeader(card,null);
        TextView w=ui.text("Armando la nota…",Type.BODY_MEDIUM,p.onSurfaceVariant);w.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);w.setPadding(0,0,0,ui.dp(S3));card.addView(w);
        for(float width:new float[]{1f,0.92f,0.7f}){LinearLayout line=ui.row();View bar=new View(this);bar.setBackground(shape(this,p.surfaceContainerHighest,R_SMALL));line.addView(bar,new LinearLayout.LayoutParams(0,ui.dp(12),width));if(width<1f)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f-width));
            LinearLayout.LayoutParams lp=Ui.fill();lp.bottomMargin=ui.dp(S2);lp.setMarginEnd(ui.dp(S2));card.addView(line,lp);pulse(bar);}
        return card;
    }
    /** Latido suave mientras se arma (respeta «Quitar animaciones» de Android: el animador se detiene solo). */
    private static void pulse(View v){
        ValueAnimator a=ValueAnimator.ofFloat(1f,0.45f);a.setDuration(900);a.setRepeatMode(ValueAnimator.REVERSE);a.setRepeatCount(ValueAnimator.INFINITE);a.addUpdateListener(x->v.setAlpha((float)x.getAnimatedValue()));
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){a.start();}public void onViewDetachedFromWindow(View view){a.cancel();}});
    }
    private View noteMessage(String title,String text,String action,int icon){
        LinearLayout card=noteSurface();
        if(title.startsWith("Nota"))noteHeader(card,null);else{TextView t=ui.text(title,Type.TITLE_MEDIUM,p.onSurface);t.setPadding(0,ui.dp(S2),0,ui.dp(S1));card.addView(t);}
        TextView d=ui.text(text,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,0,ui.dp(S2),0);card.addView(d);
        LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S3);card.addView(ui.button(action,icon,Ui.Style.TONAL,v->generateNote()),lp);
        return card;
    }
    private View noteFull(JSONObject note,Map<String,String> names,String error){
        Map<String,String> who=noteNames(note,names);
        LinearLayout card=noteSurface();LinearLayout head=noteHeader(card,"Generada por IA · revísala");
        ImageButton more=ui.iconButton(R.drawable.ic_more,"Opciones de la nota",p.onSurfaceVariant,0,48);more.setOnClickListener(v->noteMenu());head.addView(more);
        ImageButton fold=ui.iconButton(R.drawable.ic_arrow_back,"Contraer la nota",p.onSurfaceVariant,0,48);head.addView(fold);
        LinearLayout body=ui.column();body.setPadding(0,0,ui.dp(S2),0);
        String summary=Notes.resolve(note.optString("summary",""),who).trim();
        if(!summary.isEmpty()){TextView s=ui.text(summary,Type.BODY_LARGE,p.onSurface);s.setLineSpacing(ui.dp(3),1f);s.setTextIsSelectable(true);s.setPadding(0,ui.dp(S1),0,0);body.addView(s,Ui.fill());}
        bullets(body,"Decisiones",note.optJSONArray("decisions"),who);
        tasks(body,note.optJSONArray("tasks"),who);
        quotes(body,note.optJSONArray("quotes"),who);
        JSONArray tags=note.optJSONArray("tags");if(tags!=null&&tags.length()>0){StringBuilder b=new StringBuilder();for(int i=0;i<tags.length();i++){String tag=tags.optString(i).trim().replaceFirst("^#","");if(!tag.isEmpty())b.append(b.length()==0?"":"   ").append('#').append(tag);}
            if(b.length()>0){TextView t=ui.text(b.toString(),Type.BODY_SMALL,p.onSurfaceVariant);t.setPadding(0,ui.dp(S4),0,0);body.addView(t);}}
        if(error!=null&&!error.isEmpty()){TextView e=ui.text("No se pudo volver a armar: "+error,Type.BODY_SMALL,p.error);e.setPadding(0,ui.dp(S3),0,0);body.addView(e);}
        card.addView(body,Ui.fill());
        TextView collapsed=ui.text(summary.isEmpty()?"Toca para ver la nota":summary,Type.BODY_MEDIUM,p.onSurfaceVariant);collapsed.setMaxLines(2);collapsed.setEllipsize(TextUtils.TruncateAt.END);collapsed.setPadding(0,0,ui.dp(S2),0);card.addView(collapsed);
        Runnable apply=()->{boolean open=prefs.getBoolean("noteOpen",true);body.setVisibility(open?View.VISIBLE:View.GONE);collapsed.setVisibility(open?View.GONE:View.VISIBLE);fold.setRotation(open?90:-90);fold.setContentDescription(open?"Contraer la nota":"Expandir la nota");};
        apply.run();
        View.OnClickListener toggle=v->{prefs.edit().putBoolean("noteOpen",!prefs.getBoolean("noteOpen",true)).apply();apply.run();};
        fold.setOnClickListener(toggle);collapsed.setOnClickListener(toggle);
        return card;
    }
    /** Nombres para la nota: {S1}/{S2} y los ids de voz → el nombre actual de esa persona (sirve tras corregir voces). */
    private static Map<String,String> noteNames(JSONObject note,Map<String,String> names){
        Map<String,String> map=new LinkedHashMap<>(names);JSONObject sp=note.optJSONObject("speakers");
        if(sp!=null)for(Iterator<String> it=sp.keys();it.hasNext();){String k=it.next();String n=names.get(sp.optString(k));if(n!=null)map.put(k,n);}
        return map;
    }
    private static String whoName(String key,Map<String,String> who){
        if(key==null||key.trim().isEmpty())return "";String k=key.trim().replaceAll("[{}]","");String n=who.get(k);if(n!=null)return n;
        return k.matches("S\\d+")?"":k;
    }
    private TextView noteLabel(LinearLayout body,String title){TextView t=ui.text(title,Type.LABEL_LARGE,p.onSurfaceVariant);t.setPadding(0,ui.dp(S4),0,ui.dp(S1));body.addView(t);return t;}
    private void bullets(LinearLayout body,String title,JSONArray items,Map<String,String> who){
        if(items==null||items.length()==0)return;noteLabel(body,title);
        for(int i=0;i<items.length();i++){String text=Notes.resolve(items.optString(i),who).trim();if(text.isEmpty())continue;
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(2),0,ui.dp(2));TextView dot=ui.text("•",Type.BODY_MEDIUM,p.primary);dot.setPadding(ui.dp(S1),0,ui.dp(S2),0);r.addView(dot);
            TextView t=ui.text(text,Type.BODY_MEDIUM,p.onSurface);t.setTextIsSelectable(true);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));body.addView(r,Ui.fill());}
    }
    private void tasks(LinearLayout body,JSONArray tasks,Map<String,String> who){
        if(tasks==null||tasks.length()==0)return;noteLabel(body,"Tareas");
        for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t==null)continue;int index=i;
            String text=Notes.resolve(t.optString("text"),who).trim(),person=whoName(t.optString("who",""),who),when=t.optString("when","").trim();if(text.isEmpty())continue;
            CheckBox cb=new CheckBox(this);cb.setChecked(t.optBoolean("done"));cb.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{p.primary,p.onSurfaceVariant}));
            AppTheme.type(cb,Type.BODY_MEDIUM);cb.setMinHeight(ui.dp(48));cb.setPadding(ui.dp(S2),ui.dp(S1),0,ui.dp(S1));
            SpannableStringBuilder label=new SpannableStringBuilder(text);String meta=(person.isEmpty()?"":" · "+person)+(when.isEmpty()?"":" · "+when);
            if(!meta.isEmpty()){int from=label.length();label.append(meta);label.setSpan(new ForegroundColorSpan(p.onSurfaceVariant),from,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            cb.setText(label);styleTask(cb,cb.isChecked());
            cb.setOnCheckedChangeListener((b,on)->toggleTask(b,index,on));body.addView(cb,Ui.fill());}
    }
    private void styleTask(CompoundButton b,boolean done){b.setTextColor(done?p.onSurfaceVariant:p.onSurface);b.setPaintFlags(done?b.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG:b.getPaintFlags()&~Paint.STRIKE_THRU_TEXT_FLAG);}
    private void toggleTask(CompoundButton b,int index,boolean on){
        if(suppressTask)return;
        try{Notes.setTaskDone(this,id,index,on);
            // Es un cambio propio y ya se ve: no hace falta volver a armar la pantalla.
            dataVersion=FilesStore.version.get();lastSig=signature();styleTask(b,on);Ui.haptic(b,on?Ui.Haptic.TOGGLE_ON:Ui.Haptic.TOGGLE_OFF);Diagnostics.event("note_task_toggled",id);}
        catch(Exception e){suppressTask=true;b.setChecked(!on);suppressTask=false;message("Nota","No se pudo guardar la tarea.");}
    }
    private void quotes(LinearLayout body,JSONArray quotes,Map<String,String> who){
        if(quotes==null||quotes.length()==0)return;noteLabel(body,"Frases clave");
        for(int i=0;i<quotes.length();i++){JSONObject q=quotes.optJSONObject(i);if(q==null)continue;String text=Notes.resolve(q.optString("text"),who).trim();if(text.isEmpty())continue;
            long ms=(long)(q.optDouble("t",0)*1000);String person=whoName(q.optString("who",""),who);
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);
            TextView time=ui.text(Recording.time(ms),Type.LABEL_LARGE,p.primary);time.setFontFeatureSettings("tnum");time.setGravity(Gravity.CENTER_VERTICAL);time.setMinHeight(ui.dp(48));time.setPadding(ui.dp(S1),0,ui.dp(S2),0);
            time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription("Escuchar desde "+Recording.time(ms));time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->playAt(ms));r.addView(time);
            TextView t=ui.text("«"+text+"»"+(person.isEmpty()?"":" — "+person),Type.BODY_MEDIUM,p.onSurface);t.setPadding(0,ui.dp(S3),0,ui.dp(S2));t.setTextIsSelectable(true);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));
            body.addView(r,Ui.fill());}
    }
    private void noteMenu(){
        sheet("Nota para tu segundo cerebro",null)
            .action(R.drawable.ic_refresh,"Volver a armar la nota",false,()->confirm("¿Volver a armar la nota?","Se reemplaza la nota actual, incluidas las tareas marcadas. El texto se vuelve a enviar a la IA.","Volver a armar",false,this::generateNote))
            .action(R.drawable.ic_copy,"Copiar nota",false,this::copyNote)
            .action(R.drawable.ic_share,"Compartir nota .md",false,this::shareNote)
            .show();
    }
    /** Arma la nota en segundo plano; mientras tanto se ve «Armando la nota…». */
    private void generateNote(){
        if(noteBusy||demo)return;
        if(!Notes.canGenerate(this)){sheet("Falta configurar la IA de la nota","Elige en Ajustes con qué IA se arma la nota (tu clave de OpenAI o Claude).").primary("Ir a Ajustes",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))).secondary("Ahora no",null).show();return;}
        noteBusy=true;reload();Diagnostics.event("note_requested",id);
        Context app=getApplicationContext();Recording r=recording;
        new Thread(()->{String error=null;
            try{Notes.generate(app,r,new HttpApi());}
            catch(UnsupportedOperationException e){error="La nota todavía no está disponible en esta versión.";}
            catch(HttpApi.UserAction e){error=e.getMessage();}
            catch(Exception e){error="No se pudo armar la nota. Revisa tu conexión y vuelve a intentarlo.";}
            String failed=error;
            runOnUiThread(()->{noteBusy=false;if(isDestroyed())return;reload();
                if(failed!=null){Ui.haptic(content,Ui.Haptic.REJECT);message("Nota para tu segundo cerebro",failed);}else Ui.haptic(content,Ui.Haptic.CONFIRM);});
        },"note").start();
    }
    private void copyNote(){try{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Nota",Notes.markdown(this,recording)));if(Build.VERSION.SDK_INT<33)toast("Nota copiada");}catch(Exception e){message("Copiar nota","No se pudo copiar la nota.");}}
    /** Comparte la nota en Markdown como texto (Obsidian y la mayoría de las apps de notas la reciben así). */
    private void shareNote(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,recording.title).putExtra(Intent.EXTRA_TEXT,Notes.markdown(this,recording)),"Compartir nota"));}catch(Exception e){message("Compartir nota","No se pudo compartir la nota.");}}

    // ---------- Exportar y guardar ----------
    private String exportText()throws Exception{if(demo)return transcript.text(recording);Recording r=FilesStore.recording(this,id);return Transcript.load(this,id).text(r==null?recording:r);}
    private void copy(){try{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Transcripción",exportText()));if(Build.VERSION.SDK_INT<33)toast("Texto copiado");}catch(Exception e){message("Copiar","No se pudo copiar el texto.");}}
    private void shareText(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,recording.title).putExtra(Intent.EXTRA_TEXT,exportText()),"Compartir transcripción"));}catch(Exception e){message("Compartir","No se pudo compartir el texto.");}}
    private void shareTxt(){try{Transcript t=demo?transcript:Transcript.load(this,id);Uri uri=TranscriptExport.create(this,recording,t);startActivity(Intent.createChooser(TranscriptExport.shareIntent(uri),"Compartir archivo .txt"));}catch(Exception e){message("Archivo","No se pudo crear el archivo.");}}
    /**
     * Guardar en 0-Inbox (carpeta rápida): la nota .md o el .txt, con un toque. «Actualizar» reemplaza el mismo archivo.
     * Mientras guarda, el botón queda ocupado; al terminar vibra y lo confirma.
     */
    private void inboxSave(){
        if(demo||saving||transcript==null)return;
        if(!Inbox.configured(this)){startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true));return;}
        saving=true;refreshPrimary();String folder=Inbox.folderName(this);Recording r=recording;Context app=getApplicationContext();
        new Thread(()->{Exception error=null;
            try{try{Inbox.save(app,r);}catch(UnsupportedOperationException notYet){legacyQuickSave(r);}Diagnostics.event("transcript_quick_saved",r.id);}
            catch(Exception e){error=e;Diagnostics.event("transcript_quick_save_failed",r.id,"error_class",e.getClass().getSimpleName());}
            Exception failed=error;
            runOnUiThread(()->{saving=false;if(isDestroyed())return;refreshPrimary();
                if(failed==null){Ui.haptic(primary,Ui.Haptic.CONFIRM);snackbar("Guardado en "+folder,null,null);}
                else{Ui.haptic(primary,Ui.Haptic.REJECT);
                    sheet("No se pudo guardar en "+folder,failed instanceof HttpApi.UserAction?failed.getMessage():"Puede que Android haya retirado el permiso a esa carpeta. Elígela de nuevo en Ajustes o usa «Otra carpeta».")
                        .primary("Ir a Ajustes",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))).secondary("Otra carpeta",this::saveAs).show();}});
        },"inbox").start();
    }
    /** Respaldo mientras Inbox no esté disponible: crea el .txt en la carpeta rápida, como en la 0.5. */
    private void legacyQuickSave(Recording r)throws Exception{
        String tree=new Settings(this).inboxTree();if(tree.isEmpty())throw new java.io.IOException("Sin carpeta rápida");
        Uri t=Uri.parse(tree);Uri dir=android.provider.DocumentsContract.buildDocumentUriUsingTree(t,android.provider.DocumentsContract.getTreeDocumentId(t));
        Uri doc=android.provider.DocumentsContract.createDocument(getContentResolver(),dir,"text/plain",TranscriptExport.filename(r.title));if(doc==null)throw new java.io.IOException();
        LocalStorage.writeText(this,doc,exportText());
    }
    private boolean saveAsNote;
    /** Guardar en cualquier ubicación, incluida Google Drive si su app está instalada. Con nota, guarda la nota .md completa. */
    private void saveAs(){try{
        saveAsNote=!demo&&Notes.exists(this,id);String name=TranscriptExport.filename(recording.title);if(saveAsNote)name=name.substring(0,name.length()-4)+".md";
        Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(saveAsNote?"text/markdown":"text/plain").putExtra(Intent.EXTRA_TITLE,name);
        // Abre directamente donde guardaste la última vez (p. ej. tu carpeta Inbox de Drive).
        String last=new Settings(this).prefs.getString("lastSaveUri","");if(!last.isEmpty())pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(last));
        startActivityForResult(pick,SAVE_AS);}catch(ActivityNotFoundException e){message("Guardar en…","Este teléfono no tiene un selector de archivos disponible.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SAVE_AS&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri target=data.getData();new Settings(this).prefs.edit().putString("lastSaveUri",target.toString()).apply();boolean note=saveAsNote;
            new Thread(()->{try{LocalStorage.writeText(this,target,note?Notes.markdown(this,recording):exportText());Diagnostics.event("transcript_saved_as",id);runOnUiThread(()->{Ui.haptic(content,Ui.Haptic.CONFIRM);toast(note?"Nota guardada":"Transcripción guardada");});}
                catch(Exception e){Diagnostics.event("transcript_save_as_failed",id,"error_class",e.getClass().getSimpleName());runOnUiThread(()->message("Guardar en…","No se pudo escribir el archivo en esa ubicación. Prueba otra carpeta."));}}).start();}
    }

    // ---------- Intervenciones y corrección de voces ----------
    /** Una intervención: nombre (toca para corregir quién habla), ★ si hay un momento marcado y la hora (toca para escuchar ahí mismo). */
    private View turn(int a,int b,String speaker,double start,double end,Map<String,String> names,boolean diarized,boolean first)throws JSONException{
        LinearLayout t=ui.column();t.setPadding(ui.dp(S2),first?0:ui.dp(S1),ui.dp(S2),ui.dp(S3));
        LinearLayout h=ui.row();String when=Recording.time((long)(start*1000));
        if(diarized){int color=colors.containsKey(speaker)?colors.get(speaker):p.speaker(transcript.colorIndex(speaker));
            DottedName name=new DottedName(this,color);name.setText(names.get(speaker));AppTheme.type(name,Type.TITLE_SMALL);name.setTextColor(color);name.setGravity(Gravity.CENTER_VERTICAL);name.setMinHeight(ui.dp(48));name.setPadding(ui.dp(S1),0,ui.dp(S1),0);
            name.setBackground(ui.ripple(null,R_SMALL));name.setContentDescription(names.get(speaker)+", "+when+". Cambiar quién habla");name.setAccessibilityDelegate(Ui.buttonRole());name.setOnClickListener(v->whoSheet(a,b));h.addView(name);}
        if(hasMark(start,end)){TextView star=ui.text("★",Type.LABEL_LARGE,p.primary);star.setPadding(ui.dp(S1),0,0,0);star.setContentDescription("Momento marcado");h.addView(star);}
        View flex=new View(this);h.addView(flex,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        TextView time=ui.text(when,Type.BODY_SMALL,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");time.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);time.setMinHeight(ui.dp(48));time.setPadding(ui.dp(S2),0,ui.dp(S1),0);
        if(!demo){time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription("Escuchar desde "+when);time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->playTurn(t,start));}
        h.addView(time);t.addView(h,Ui.fill());
        JSONArray segments=transcript.segments();
        for(int k=a;k<b;k++){if(k>a)t.addView(ui.space(S2));TextView text=ui.text(segments.getJSONObject(k).getString("text").trim(),Type.BODY_LARGE,p.onSurface);text.setTextIsSelectable(true);text.setLineSpacing(ui.dp(4),1f);text.setPadding(ui.dp(S1),0,ui.dp(S1),0);t.addView(text,Ui.fill());}
        turns.add(new Object[]{t,(long)(start*1000),(long)(end*1000),time,when});
        return t;
    }
    /** Separador discreto donde empieza cada parte (solo al corregir voces). */
    private View blockDivider(int index,double startS){
        LinearLayout r=ui.row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(ui.dp(S2),ui.dp(S4),ui.dp(S2),ui.dp(S1));
        View a=new View(this);a.setBackgroundColor(p.outlineVariant);r.addView(a,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        TextView label=ui.text("Parte "+(index+1)+" · desde "+Recording.time((long)(startS*1000)),Type.BODY_SMALL,p.onSurfaceVariant);label.setPadding(ui.dp(S2),0,ui.dp(S2),0);r.addView(label);
        View b=new View(this);b.setBackgroundColor(p.outlineVariant);r.addView(b,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        return r;
    }
    private static String quote(String text,int max){String t=text.trim().replaceAll("\\s+"," ");return t.length()>max?t.substring(0,max-1).trim()+"…":t;}
    /** Botón "Escuchar" dentro de una hoja: reproduce solo ese tramo y no cierra la hoja. */
    private void listenButton(Sheet s,String label,double from,double to){
        if(demo)return;Ui.Btn listen=ui.button(label,R.drawable.ic_play,Ui.Style.TONAL,null);
        listen.setOnClickListener(v->{if(playUntil>0){stopTramo();listen.setText(label);}else if(playRange(from,to))listen.setText("Detener");});
        tramoEnded=()->listen.setText(label);LinearLayout.LayoutParams lp=Ui.wrap();lp.bottomMargin=ui.dp(S2);s.body.addView(listen,lp);
        s.onDismiss(()->{tramoEnded=null;if(playUntil>0)stopTramo();});
    }
    /** «Nombrar voces» en una sola pasada (hoja de la parte «sheets»); si no está disponible, la hoja clásica. */
    private void openNameVoices(){
        if(transcript==null)return;correctionOpened=true;
        try{NameVoices.show(this,demo?recording.id:id,demo,transcript,()->{if(demo)syncDemo();reload();});}
        catch(RuntimeException e){editSpeakers();}
    }
    /** «¿Quién habla aquí?»: escuchar el tramo y elegir a la persona correcta, o intercambiar dos voces desde aquí. */
    private void whoSheet(int a,int b){try{
        correctionOpened=true;
        JSONArray segs=transcript.segments();JSONObject first=segs.getJSONObject(a),last=segs.getJSONObject(b-1);String current=first.getString("speaker");
        double from=first.getDouble("start"),to=last.optDouble("end",from);Map<String,String> names=transcript.speakers();
        StringBuilder said=new StringBuilder();for(int k=a;k<b&&said.length()<120;k++)said.append(said.length()==0?"":" ").append(segs.getJSONObject(k).getString("text").trim());
        Sheet s=sheet("¿Quién habla aquí?",Recording.time((long)(from*1000))+" – "+Recording.time((long)(to*1000))+" · «"+quote(said.toString(),90)+"»");
        listenButton(s,"Escuchar este tramo",from,to);
        for(String key:names.keySet()){boolean now=key.equals(current);String name=names.get(key);
            s.choice(name,now?"Así está ahora":null,now,p.speaker(transcript.colorIndex(key)),()->applyEdit(t->t.assign(a,b,key),"Ahora lo dice "+name,"reassign"));}
        s.choice("Otra persona","Una voz que no está en la lista",false,0,()->applyEdit(t->t.assign(a,b,t.newPerson()),"Ahora lo dice una persona nueva","new_person"));
        List<String> others=new ArrayList<>(names.keySet());others.remove(current);
        if(!others.isEmpty())s.action(R.drawable.ic_swap,others.size()==1?"Intercambiar "+names.get(current)+" y "+names.get(others.get(0))+" desde aquí":"Intercambiar "+names.get(current)+" con otra voz desde aquí",false,()->swapPartner(a,current));
        if(b-a>1)s.action(R.drawable.ic_edit,"Corregir solo una frase",false,()->pickSentence(a,b));
        s.show();
    }catch(Exception e){message("Transcripción","No se pudo abrir esta intervención.");}}
    private void pickSentence(int a,int b){try{
        JSONArray segs=transcript.segments();Sheet s=sheet("¿Qué frase?","Elige la frase que dijo otra persona.");
        for(int k=a;k<b;k++){JSONObject seg=segs.getJSONObject(k);int index=k;s.choice(Recording.time((long)(seg.getDouble("start")*1000))+" · "+quote(seg.getString("text"),70),null,false,()->whoSheet(index,index+1));}
        s.secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar las frases.");}}
    private void swapPartner(int a,String x){try{
        Map<String,String> names=transcript.speakers();List<String> others=new ArrayList<>(names.keySet());others.remove(x);
        if(others.size()==1){swapScope(a,x,others.get(0));return;}
        Sheet s=sheet("¿Con quién se cruzó "+names.get(x)+"?","Elige la otra voz que quedó cruzada.");
        for(String y:others)s.choice(names.get(y),null,false,p.speaker(transcript.colorIndex(y)),()->swapScope(a,x,y));
        s.secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar las voces.");}}
    /** Intercambiar dos voces desde este punto: hasta el final o solo hasta el fin de la parte. */
    private void swapScope(int a,String x,String y){try{
        Map<String,String> names=transcript.speakers();String nx=names.get(x),ny=names.get(y);double from=transcript.segments().getJSONObject(a).getDouble("start");String when=Recording.time((long)(from*1000));
        List<Double> blocks=transcript.blockStarts(demo?null:FilesStore.state(this,id));int k=0;for(int i=0;i<blocks.size();i++)if(blocks.get(i)<=from+0.05)k=i;double blockEnd=k+1<blocks.size()?blocks.get(k+1):-1;int blockNo=k+1;
        Sheet s=sheet("Intercambiar "+nx+" y "+ny,"Desde "+when+", lo que dice "+nx+" pasa a "+ny+" y al revés. Si más adelante vuelven a cruzarse, repite desde ese punto.");
        s.option(R.drawable.ic_swap,"Hasta el final","Hasta "+Recording.time(recording.duration),()->applyEdit(t->t.swap(x,y,from,Double.MAX_VALUE),"Intercambiadas desde "+when,"swap"));
        if(blockEnd>from)s.option(R.drawable.ic_swap,"Solo en la parte "+blockNo,"Hasta "+Recording.time((long)(blockEnd*1000)),()->applyEdit(t->t.swap(x,y,from,blockEnd),"Intercambiadas en la parte "+blockNo,"swap_block"));
        s.secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudo preparar el intercambio.");}}
    /** Hoja de una persona (desde su chip): escuchar, cambiar nombre, unir con otra, ver solo sus intervenciones. */
    private void personSheet(String key){try{
        correctionOpened=true;
        Map<String,String> names=transcript.speakers();String name=names.get(key);JSONArray segs=transcript.segments();
        int count=0;double talk=0,firstAt=-1,bestLen=0,bestFrom=0,bestTo=0;
        for(int i=0;i<segs.length();i++){JSONObject s=segs.getJSONObject(i);if(!s.getString("speaker").equals(key))continue;double a=s.getDouble("start"),b=s.optDouble("end",a);count++;talk+=Math.max(0,b-a);if(firstAt<0)firstAt=a;if(b-a>bestLen&&b-a<=15){bestLen=b-a;bestFrom=a;bestTo=b;}}
        Sheet s=sheet(name,count+(count==1?" frase":" frases")+" · "+Recording.time((long)(talk*1000))+" en total · desde "+Recording.time((long)(Math.max(0,firstAt)*1000)));
        if(bestLen>0)listenButton(s,"Escuchar una muestra",bestFrom,bestTo);
        s.action(R.drawable.ic_edit,"Cambiar nombre",false,()->renameOne(key));
        if(names.size()>1)s.action(R.drawable.ic_merge,"Es la misma persona que…",false,()->mergeSheet(key));
        boolean only=key.equals(onlySpeaker);s.action(R.drawable.ic_filter,only?"Ver todas las intervenciones":"Ver solo sus intervenciones",false,()->{onlySpeaker=only?null:key;reload(true);});
        s.action(R.drawable.ic_voice,"Nombrar todas las voces",false,this::openNameVoices);
        // Guardar su voz para reconocerla en los próximos audios: con un nombre puesto por el usuario, si aún no es una voz
        // conocida y hay un tramo limpio (sin otra voz encima) de 3 s o más.
        double[] clean=!demo&&transcript.diarized()&&!key.startsWith(Voices.TARGET)&&!name.equals(transcript.defaultLabel(key))&&!name.trim().isEmpty()
            &&new Settings(this).provider().equals("openai")&&recording.audio(this).isFile()?NameVoices.sample(segs,key):null;
        if(clean!=null&&(clean[1]-clean[0])*1000>=Voices.MIN_MS)s.action(R.drawable.ic_mic_fill,"Guardar la voz de "+name,false,()->saveVoice(name,clean));
        s.show();
    }catch(Exception e){message("Transcripción","No se pudo abrir esta voz.");}}
    /** Guarda el tramo limpio de esta voz como voz conocida (si ya hay una con ese nombre, pregunta antes de reemplazarla). */
    private void saveVoice(String name,double[] range){
        Voices.Voice same=Voices.findByName(this,name);
        if(same==null){cutVoice(name,range,null);return;}
        confirm("¿Reemplazar la voz guardada de "+same.name+"?","Se usará este tramo de "+Math.round(Math.min(range[1]-range[0],Voices.MAX_MS/1000d))+" s en lugar de la muestra anterior.","Reemplazar",false,()->cutVoice(name,range,same.id));
    }
    /** Recorta el tramo del audio original en segundo plano (máx. 9,5 s) y lo guarda en la biblioteca de voces. */
    private void cutVoice(String name,double[] range,String replaceId){
        Context app=getApplicationContext();Recording r=recording;long from=(long)(range[0]*1000),to=Math.min((long)(range[1]*1000),from+Voices.MAX_MS);
        new Thread(()->{java.io.File tmp=new java.io.File(app.getCacheDir(),"voice-cut-"+System.nanoTime()+".m4a");boolean ok=false;
            try{
                AudioConvert.convert(r.audio(app),tmp,from,to,new HttpApi());
                if(replaceId!=null&&Voices.get(app,replaceId)!=null)Voices.replaceAudio(app,replaceId,tmp);else Voices.add(app,name,tmp,false);
                ok=true;Diagnostics.event("voice_saved",r.id,"result",replaceId!=null?"replaced":"added","duration_ms",to-from);
            }catch(Exception e){Diagnostics.event("voice_saved",r.id,"result","failed","error_class",e.getClass().getSimpleName());}
            finally{tmp.delete();}
            boolean saved=ok;
            runOnUiThread(()->{if(isDestroyed())return;
                if(saved){Ui.haptic(content,Ui.Haptic.CONFIRM);toast("Voz de "+name+" guardada · la reconocerá en tus próximos audios");}
                else message("Guardar la voz","No se pudo recortar la voz de "+name+" de este audio. Vuelve a intentarlo.");});
        },"voice").start();
    }
    private void mergeSheet(String x){try{
        Map<String,String> names=transcript.speakers();String nx=names.get(x);
        Sheet s=sheet("¿Con quién unir a "+nx+"?","Sus intervenciones pasan a esa persona. Puedes deshacerlo.");
        for(String y:names.keySet()){if(y.equals(x))continue;String ny=names.get(y);s.choice(ny,null,false,p.speaker(transcript.colorIndex(y)),()->applyEdit(t->t.merge(x,y),nx+" se unió con "+ny,"merge"));}
        s.secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar las voces.");}}
    private void renameOne(String key){try{
        Map<String,String> names=transcript.speakers();String current=names.get(key);
        Sheet s=sheet("Cambiar nombre","Si le pones el nombre de otra voz, se unen en una sola persona.");
        EditText f=ui.field(current,"Nombre");if(!current.equals(transcript.defaultLabel(key)))f.setText(current);f.setSingleLine(true);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});s.add(f);
        s.primary("Guardar",Ui.Style.PRIMARY,()->{Map<String,String> one=new LinkedHashMap<>();one.put(key,f.getText().toString().trim());saveNames(one);return true;}).secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudo cargar el nombre.");}}
    /** Aplica una corrección, guarda y ofrece "Deshacer". La pantalla conserva su posición. */
    private void applyEdit(Transcript.Edit op,String done,String action){
        try{JSONObject before;
            if(demo){before=new JSONObject(transcript.data.toString());op.apply(transcript);writeDemo();}
            else before=Transcript.edit(this,id,op);
            Diagnostics.event("transcript_edited",demo?null:id,"action",action);
            reload();snackbar(done,"Deshacer",()->undo(before));
        }catch(Exception e){message("Transcripción","No se pudo guardar el cambio. Vuelve a intentarlo.");}
    }
    private void undo(JSONObject before){
        try{if(demo){transcript=new Transcript(before);writeDemo();}else Transcript.replace(this,id,before);Diagnostics.event("transcript_edited",demo?null:id,"action","undo");reload();}
        catch(Exception e){message("Transcripción","No se pudo deshacer el cambio.");}
    }
    private java.io.File demoFile(){return new java.io.File(getFilesDir(),"demo-transcript.json");}
    private void writeDemo()throws Exception{java.io.File f=demoFile();FilesStore.write(f,transcript.data);demoStamp=f.lastModified();}
    /** Si otra hoja guardó el ejemplo (p. ej. «Nombrar voces»), se toma esa versión. */
    private void syncDemo(){java.io.File f=demoFile();if(f.exists()&&f.lastModified()!=demoStamp){try{transcript=new Transcript(FilesStore.read(f));}catch(Exception ignored){}demoStamp=f.lastModified();}}
    /** Guarda nombres; las voces con el mismo nombre se unen (con "Deshacer"). */
    private void saveNames(Map<String,String> names){
        hideSnackbar();
        try{int[] merged={0};JSONObject before;
            if(demo){before=new JSONObject(transcript.data.toString());merged[0]=transcript.applyNames(names);writeDemo();}
            else before=Transcript.edit(this,id,t->merged[0]=t.applyNames(names));
            reload();
            if(merged[0]>0)snackbar(merged[0]==1?"Se unieron 2 voces con el mismo nombre":"Se unieron las voces con el mismo nombre","Deshacer",()->undo(before));else toast("Nombres guardados");
        }catch(Exception e){message("No se guardaron los nombres","Vuelve a intentarlo.");}
    }
    /** Hoja clásica «Nombrar voces» (respaldo si la hoja nueva no está disponible). */
    private void editSpeakers(){try{
        correctionOpened=true;
        Map<String,String> current=transcript.speakers();Map<String,EditText> fields=new LinkedHashMap<>();
        Sheet s=sheet("Nombrar voces","El nombre se aplica a todas las intervenciones de esa voz. Si dos voces son la misma persona, dales el mismo nombre y se unen.");
        for(String key:current.keySet()){LinearLayout row=ui.row();row.setPadding(0,ui.dp(S1),0,ui.dp(S1));View dot=new View(this);dot.setBackground(oval(p.speaker(transcript.colorIndex(key))));row.addView(dot,new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)));row.addView(ui.space(S3));
            String label=transcript.defaultLabel(key);EditText f=ui.field(label,"Nombre para "+label);String value=current.get(key);if(!value.equals(label))f.setText(value);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});row.addView(f,new LinearLayout.LayoutParams(0,-2,1));fields.put(key,f);s.add(row);}
        if(transcript.edited()){Ui.Btn restore=ui.button("Restaurar voces originales",R.drawable.ic_refresh,Ui.Style.PLAIN,v->{s.dismiss();confirm("¿Restaurar voces originales?","Se deshacen todas las correcciones de quién habla. Los nombres se mantienen.","Restaurar",false,()->applyEdit(Transcript::restore,"Voces originales restauradas","restore"));});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S2);s.body.addView(restore,lp);}
        s.primary("Guardar nombres",Ui.Style.PRIMARY,()->{
            Map<String,String> names=new LinkedHashMap<>();for(Map.Entry<String,EditText> f:fields.entrySet())names.put(f.getKey(),f.getValue().getText().toString().trim());
            saveNames(names);return true;
        }).secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar las voces.");}}

    static Transcript example()throws Exception{return new Transcript(new JSONObject("{\"demo\":true,\"names\":{},\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":7,\"text\":\"Me gustaría que guardemos las ideas de esta conversación.\"},{\"speaker\":\"B\",\"start\":7,\"end\":14,\"text\":\"Sí, y después podemos revisar juntos lo que dijimos.\"},{\"speaker\":\"C\",\"start\":14,\"end\":20,\"text\":\"Yo puedo ayudar a ordenar los próximos pasos.\"},{\"speaker\":\"A\",\"start\":20,\"end\":27,\"text\":\"Perfecto. Así no se nos pierde ninguna idea.\"}]}"));}

    // ---------- Vistas propias ----------
    /** Nombre de una persona con subrayado de puntos: se nota que se puede tocar (abre «¿Quién habla aquí?»). */
    private static final class DottedName extends TextView {
        private final Paint dots=new Paint(Paint.ANTI_ALIAS_FLAG);private final float density;
        DottedName(Context c,int color){super(c);density=c.getResources().getDisplayMetrics().density;dots.setColor(color);dots.setAlpha(170);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);Layout l=getLayout();if(l==null||l.getLineCount()==0)return;
            float y=getTotalPaddingTop()+l.getLineBaseline(0)+3.5f*density,x0=getTotalPaddingLeft()+l.getLineLeft(0),x1=getTotalPaddingLeft()+l.getLineRight(0),r=0.8f*density;
            for(float x=x0+r;x<=x1;x+=3.5f*density)canvas.drawCircle(x,y,r,dots);
        }
    }
    /** Barra de avance con extremos redondos (sin porcentajes inventados: solo lo terminado de verdad). */
    private static final class Meter extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int fill,track;private float value;
        Meter(Context c,int fill,int track){super(c);this.fill=fill;this.track=track;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(float v){value=Math.max(0,Math.min(1,v));invalidate();}
        @Override protected void onDraw(Canvas c){float h=getHeight(),w=getWidth(),r=h/2f;paint.setColor(track);c.drawRoundRect(0,0,w,h,r,r,paint);if(value>0){paint.setColor(fill);c.drawRoundRect(0,0,Math.max(h,w*value),h,r,r,paint);}}
    }
    /**
     * La onda del audio como barra para moverse: lo escuchado en primary y lo que falta en outlineVariant, debajo una
     * franja con el color de quién habla y arriba los ★. Tocar o arrastrar salta; al pasar por un ★ se siente un tic.
     * Mientras la onda no está lista, es una barra simple.
     */
    private static final class Scrubber extends View {
        interface Listener{void seek(long ms,boolean done);}
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),star=new Paint(Paint.ANTI_ALIAS_FLAG);private final int played,rest,track;private final float density;
        private float[] env;private long duration=1,position,last;private long[] marks=new long[0],runFrom=new long[0],runTo=new long[0];private int[] runColor=new int[0];
        boolean dragging;Listener listener;
        Scrubber(Context c,Palette p){super(c);density=c.getResources().getDisplayMetrics().density;played=p.primary;rest=p.outlineVariant;track=p.surfaceContainerHighest;
            star.setColor(p.primary);star.setTextAlign(Paint.Align.CENTER);star.setTextSize(11*density);setClickable(true);setFocusable(true);describe();}
        void setDuration(long d){duration=Math.max(1,d);position=clamp(position);describe();invalidate();}
        void setPosition(long ms){long v=clamp(ms);if(v!=position){position=v;invalidate();}}
        void setEnvelope(float[] v){env=v;invalidate();}
        void setMarks(long[] m){marks=m==null?new long[0]:m;invalidate();}
        void setStrip(long[] from,long[] to,int[] colors){runFrom=from==null?new long[0]:from;runTo=to==null?new long[0]:to;runColor=colors==null?new int[0]:colors;invalidate();}
        private long clamp(long ms){return Math.max(0,Math.min(duration,ms));}
        private float dp(float n){return n*density;}
        private float left(){return dp(4);}
        private float right(){return getWidth()-dp(4);}
        private float x(long ms){return left()+(right()-left())*(ms/(float)duration);}
        private long ms(float x){return clamp((long)((x-left())/Math.max(1f,right()-left())*duration));}
        @Override protected void onDraw(Canvas canvas){
            float top=dp(13),waveH=getHeight()-top-dp(10),mid=top+waveH/2f,stripY=top+waveH+dp(4),stripH=dp(4),px=x(position);
            if(env==null||env.length==0){float h=dp(4);paint.setColor(rest);canvas.drawRoundRect(left(),mid-h/2,right(),mid+h/2,h/2,h/2,paint);
                paint.setColor(played);canvas.drawRoundRect(left(),mid-h/2,Math.max(left()+h,px),mid+h/2,h/2,h/2,paint);canvas.drawCircle(px,mid,dp(6),paint);}
            else{float step=dp(4),bar=dp(2.5f);int bars=Math.max(1,(int)((right()-left())/step));
                for(int i=0;i<bars;i++){int a=(int)((long)i*env.length/bars),b=Math.max(a+1,(int)((long)(i+1)*env.length/bars));float v=0;for(int k=a;k<b&&k<env.length;k++)v=Math.max(v,env[k]);
                    float h=Math.max(dp(2),Math.min(1f,v)*waveH),bx=left()+i*step;paint.setColor(bx+bar/2<=px?played:rest);canvas.drawRoundRect(bx,mid-h/2,bx+bar,mid+h/2,bar/2,bar/2,paint);}
                paint.setColor(played);canvas.drawRoundRect(px-dp(1),top-dp(1),px+dp(1),top+waveH+dp(1),dp(1),dp(1),paint);}
            if(runColor.length>0){paint.setColor(track);canvas.drawRoundRect(left(),stripY,right(),stripY+stripH,stripH/2,stripH/2,paint);
                for(int i=0;i<runColor.length;i++){float a=x(runFrom[i]),b=Math.max(a+dp(1),x(runTo[i]));paint.setColor(runColor[i]);canvas.drawRect(a,stripY,b,stripY+stripH,paint);}}
            for(long m:marks)canvas.drawText("★",x(m),top-dp(2),star);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!isEnabled())return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);dragging=true;last=ms(e.getX());position=last;invalidate();if(listener!=null)listener.seek(position,false);return true;
                case MotionEvent.ACTION_MOVE:{long v=ms(e.getX()),lo=Math.min(last,v),hi=Math.max(last,v);for(long m:marks)if(m>lo&&m<=hi){Ui.haptic(this,Ui.Haptic.TICK);break;}
                    last=v;position=v;invalidate();if(listener!=null)listener.seek(v,false);return true;}
                case MotionEvent.ACTION_UP:dragging=false;describe();if(listener!=null)listener.seek(position,true);performClick();return true;
                case MotionEvent.ACTION_CANCEL:dragging=false;return true;
            }
            return super.onTouchEvent(e);
        }
        @Override public boolean performClick(){return super.performClick();}
        private void describe(){setContentDescription("Posición del audio: "+Recording.time(position)+" de "+Recording.time(duration));}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(SeekBar.class.getName());
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args){
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
                position=clamp(position+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?15000:-15000));describe();invalidate();if(listener!=null)listener.seek(position,true);return true;}
            return super.performAccessibilityAction(action,args);
        }
    }
    /** Abierta desde la notificación «lista» (sin nada debajo): Atrás lleva a la Biblioteca en vez de salir de la app. */
    @Override public void onBackPressed(){if(isTaskRoot()&&!demo){startActivity(new Intent(this,MainActivity.class).putExtra("library",true));finish();return;}super.onBackPressed();}
}
