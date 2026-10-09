package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
import android.text.style.ReplacementSpan;
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
 *
 * Estética 0.7.0 (Verbapp, PROPUESTA-0.7 §3.5): documento sobre el fondo suave; título grande en Outfit con la última
 * palabra en menta; cada bloque (personas, nota, momentos, transcripción, avance) en una tarjeta de vidrio con su
 * ícono verde y su título; menta para lo que destaca; el reproductor en una cápsula flotante casi opaca.
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
    private boolean intentHandled,markedOpened,correctionOpened,noteBusy,saving,detailsOpen,suppressTask;
    private long[] marksMs=new long[0];private long demoStamp;
    /**
     * «Nueva versión lista» ya ofrecida: el «at» de esa vuelta a transcribir (-1 = ninguna). Así cada versión nueva se
     * ofrece una vez, también si vuelves a transcribir sin salir de la pantalla.
     */
    private long offeredKeepAt=-1;private boolean keepPending;
    /** Hojas abiertas que se cierran al destruir la pantalla (giro, tema): «Nombrar voces» guarda lo escrito al cerrarse. */
    private Sheet keepSheet,nameSheet;

    // Reproductor (fijo abajo)
    private MediaPlayer player;private AudioFocusRequest focus;private boolean prepared;private float rate=1f;
    private Scrubber scrubber;private TextView timeText,speedChip;private View speedBox;private ImageButton play;
    private Ui.Split primary;private Next next;
    /** Seguir al audio: la intervención que suena se trae a la vista, salvo que te hayas movido a mano hace poco. */
    private long lastUserScroll;private boolean followPending;

    private final Runnable progress=new Runnable(){public void run(){
        if(recording!=null){
            if(player!=null&&prepared){int pos=player.getCurrentPosition();
                if(scrubber!=null&&!scrubber.dragging){scrubber.setPosition(pos);showTime(pos);}
                tickPlayback(pos);}
            if(!demo)checkChanges();
            tickProcess();updateFollowChip();
        }
        handler.postDelayed(this,250);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);demo=getIntent().getBooleanExtra("demo",false);id=getIntent().getStringExtra("id");prefs=getSharedPreferences("detail",MODE_PRIVATE);if(!demo&&id!=null)Transcriber.clearDone(this,id);
        if(state!=null){intentHandled=true;offeredKeepAt=state.getLong("offeredKeepAt",-1);markedOpened=state.getBoolean("markedOpened");correctionOpened=state.getBoolean("correcting");onlySpeaker=state.getString("onlySpeaker");saveAsNote=state.getBoolean("saveAsNote");}
        shell(getString(demo?R.string.nav_settings:R.string.nav_library),-1);
        try{
            if(demo){recording=new Recording("00000000-0000-0000-0000-000000000000",getString(R.string.detail_demo_title),System.currentTimeMillis(),27000);java.io.File f=demoFile();transcript=f.exists()?new Transcript(FilesStore.read(f)):example();demoStamp=f.exists()?f.lastModified():0;}
            else{recording=FilesStore.recording(this,id);if(recording==null)throw new java.io.FileNotFoundException();}
        }catch(Exception e){recording=null;largeTitle(page,getString(R.string.detail_missing_title),getString(R.string.detail_missing_body));return;}
        // ← y ⋯ son los dos botones redondos de vidrio de la barra (el menú es el mismo de siempre).
        if(!demo)barButton(R.drawable.ic_more,getString(R.string.detail_more_options),v->RecordingActions.menu(this,recording,this::reload,this::releasePlayer));
        else if(barActions!=null)barActions.addView(ui.space(48)); // el ejemplo no tiene ⋯: el hueco mantiene centrado el título de la barra
        // El título grande se va con el contenido; al desplazarte aparece centrado en la barra (Outfit), entre los dos botones.
        barTitle=ui.oneLine(ui.text(recording.title,Type.TITLE_MEDIUM,p.onSurface));barTitle.setTextSize(17);barTitle.setGravity(Gravity.CENTER);barTitle.setPadding(ui.dp(S3),0,ui.dp(S3),0);
        barTitle.setAlpha(0f);barTitle.setTranslationY(ui.dp(S2));barTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(bar!=null&&bar.getChildCount()>2){bar.removeViewAt(1);bar.addView(barTitle,1,new LinearLayout.LayoutParams(0,-2,1));}
        // La zona desplazable lleva encima el chip flotante «Volver a lo que suena».
        int at=root.indexOfChild(scroll);root.removeView(scroll);FrameLayout stage=new FrameLayout(this);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        followChip=ui.text("↓ "+getString(R.string.detail_follow),Type.LABEL_LARGE,p.onInk);followChip.setGravity(Gravity.CENTER);followChip.setMinHeight(ui.dp(48));followChip.setPadding(ui.dp(S5),0,ui.dp(S5),0);
        // Píldora de tinta que flota (como la barra de pestañas): se distingue de cualquier texto que pase por detrás.
        followChip.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onInk)),shape(this,p.ink,R_FULL),null));followChip.setElevation(ui.dp(6));lift(followChip);
        followChip.setAccessibilityDelegate(Ui.buttonRole());followChip.setVisibility(View.GONE);Ui.pressable(followChip);
        followChip.setOnClickListener(v->{lastUserScroll=0;if(playingTurn!=null)followTo(playingTurn,true);followChip.setVisibility(View.GONE);Diagnostics.event("follow_resume",demo?null:id);});
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);fp.bottomMargin=ui.dp(S3);stage.addView(followChip,fp);
        root.addView(stage,Math.max(0,at),new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_MOVE)lastUserScroll=SystemClock.uptimeMillis();return false;});
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{updateBarTitle();updateFollowChip();});
        content=ui.column();page.addView(content,Ui.fill());
        buildDock();build();
        if(!demo)loadWave();
        // La acción «Usar datos móviles» de la notificación «Esperando Wi-Fi» abre esta pantalla con el extra "mobileOk".
        if(!demo&&state==null&&getIntent().getBooleanExtra("mobileOk",false))handler.post(this::useMobile);
    }
    @Override protected void onResume(){
        super.onResume();handler.post(progress);
        if(!demo&&recording!=null){Pipeline.startForeground(this);
            // Al volver de Ajustes (carpeta rápida, IA de la nota, clave), lo que depende de eso se pone al día.
            if(lastSettings!=null&&!settingsKey().equals(lastSettings))reload();else refreshPrimary();}
    }
    private String lastSettings;
    /** Incluye lo de OpenRouter (0.8.0): su clave y sus modelos cambian los costos, las voces conocidas y la IA de la nota. */
    private String settingsKey(){Settings s=new Settings(this);return s.inboxTree()+"|"+s.hasKey()+"|"+s.provider()+"|"+s.textModel()+"|"+Notes.provider(s)+"|"+s.hasAnthropicKey()+"|"+s.noteAuto()
        +"|"+s.hasOpenRouterKey()+"|"+s.hasOpenAiKey()+"|"+s.orSpeakersModel()+"|"+s.orTextModel();}
    @Override protected void onPause(){handler.removeCallbacks(progress);if(player!=null&&player.isPlaying()){player.pause();setPlaying(false);}super.onPause();}
    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);releasePlayer();
        // Android quita las ventanas de las hojas sin cerrarlas: sin esto, lo escrito en «Nombrar voces» se perdía y su
        // reproductor de muestras quedaba vivo.
        if(nameSheet!=null){nameSheet.dismiss();nameSheet=null;}
        if(keepSheet!=null){keepSheet.dismiss();keepSheet=null;}
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        // «Nueva versión lista» sin responder se vuelve a ofrecer al recrear la pantalla.
        boolean offerOpen=keepPending||(keepSheet!=null&&keepSheet.dialog.isShowing());
        out.putLong("offeredKeepAt",offerOpen?-1:offeredKeepAt);out.putBoolean("markedOpened",markedOpened);out.putBoolean("correcting",correctionOpened);if(onlySpeaker!=null)out.putString("onlySpeaker",onlySpeaker);
        // «Guardar en otra carpeta…»: el selector puede volver a una pantalla nueva; así sigue sabiendo si escribir la nota o el texto.
        out.putBoolean("saveAsNote",saveAsNote);
        super.onSaveInstanceState(out);
    }

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
    private void addMarks(){View m=marksRow();if(m!=null)content.addView(m,gap());}
    /** Separación entre tarjetas: 12 dp, el mismo ritmo en toda la pantalla. */
    private LinearLayout.LayoutParams gap(){LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S3);return lp;}
    /** Sombra verde muy oscura en vez de gris (Android 9+), como la barra de pestañas y los botones de vidrio. */
    private void lift(View v){if(Build.VERSION.SDK_INT>=28&&!p.dark){v.setOutlineSpotShadowColor(p.shadow);v.setOutlineAmbientShadowColor(p.shadow);}}
    /** Pedidos que llegan con la apertura (notificación «lista», «Nueva versión lista») y el punto «nuevo» de la Biblioteca. */
    private void afterBuild(JSONObject st){
        if(demo)return;
        JSONObject again=st.optJSONObject("retranscribe");long at=again==null?0:again.optLong("at");
        boolean offer=offeredKeepAt!=at&&transcript!=null&&(again!=null||getIntent().getBooleanExtra("retranscribed",false))&&Retranscribe.hasPrevious(this,id);
        Runnable asked=null;boolean onlyNew=false;
        if(!intentHandled){intentHandled=true;Intent in=getIntent();
            if(transcript!=null&&in.getBooleanExtra("names",false)){asked=this::openNameVoices;onlyNew=true;}
            else if(transcript!=null&&in.getBooleanExtra("save",false))asked=this::inboxSave;}
        if(offer){
            // Con una versión nueva por elegir, primero se elige y después se hace lo pedido: si no, «Nombrar voces» quedaba
            // debajo de «Nueva versión lista» y, tras «Volver a la anterior», sus nombres iban a las voces de la otra versión.
            // Nombrar sigue solo si te quedas con la nueva (era la que pedía revisar); guardar, con la que elijas.
            offeredKeepAt=at;keepPending=true;Runnable then=asked;boolean newOnly=onlyNew;
            handler.post(()->{keepPending=false;if(isDestroyed())return;
                try{keepSheet=RetranscribeSheet.offerKeep(this,recording,this::reload,false,then==null?null:kept->{if(kept||!newOnly)then.run();});}catch(RuntimeException ignored){}});
        }else if(asked!=null)handler.post(asked);
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
        // Título grande en Outfit con la última palabra en un recuadro menta: el sello de la referencia (igual que al grabar).
        titleView=ui.heading("",Type.HEADLINE_MEDIUM);ui.highlightLast(titleView,recording.title);titleView.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);titleView.setPadding(0,ui.dp(S2),ui.dp(S1),ui.dp(S1));row.addView(titleView,new LinearLayout.LayoutParams(0,-2,1));
        if(!demo){titleView.setOnClickListener(v->rename());
            // El lápiz queda a la altura de la primera línea del título, aunque este ocupe varias.
            ImageButton edit=ui.iconButton(R.drawable.ic_edit,getString(R.string.detail_rename_title),p.onSurfaceVariant,0,48);edit.setOnClickListener(v->rename());LinearLayout.LayoutParams el=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));el.topMargin=ui.dp(2);row.addView(edit,el);}
        content.addView(row,Ui.fill());
        // Fecha · hora · duración en Outfit con cifras fijas (se leen como datos, no como frase).
        LinearLayout metaRow=ui.row();metaRow.setPadding(ui.dp(S1),0,0,ui.dp(S1));metaRow.setMinimumHeight(ui.dp(32));
        metaRow.addView(Ui.tabular(ui.text(metaText(),Type.LABEL_LARGE,p.onSurfaceVariant)),new LinearLayout.LayoutParams(0,-2,1));
        // Lo terminado va en silencio: el estado solo se muestra cuando pide atención o está en curso.
        if(demo||mode!=Mode.DONE){RecState state=RecState.of(st,false);TextView chip=ui.chip(demo?getString(R.string.detail_demo_chip):state.label,demo?p.onPrimaryContainer:state.onBg(p),demo?p.primaryContainer:state.bg(p));
            LinearLayout.LayoutParams cl=Ui.wrap();cl.setMarginStart(ui.dp(S2));metaRow.addView(chip,cl);}
        content.addView(metaRow,Ui.fill());
        if(!demo){String suggested=st.optString("suggestedTitle","").trim();
            if(!suggested.isEmpty()&&!sameTitle(suggested,recording.title)){
                TextView chip=chip(getString(R.string.detail_use_suggested,quote(suggested,48)),R.drawable.ic_sparkle,p.primary,p.onSurface,0,true);chip.setContentDescription(getString(R.string.detail_suggested_desc,suggested));
                chip.setOnClickListener(v->useSuggested(suggested));content.addView(chip,Ui.wrap());}}
    }
    private void rename(){RecordingActions.rename(this,recording,this::reload);}
    private String metaText(){return dayLabel(recording.created)+" · "+clock(recording.created)+" · "+Ui.humanDuration(recording.duration);}
    /** «29 sept» («Sep 29» en inglés, «29 de set» en portugués); con el año si no es el actual. */
    private String dayLabel(long ms){Calendar now=Calendar.getInstance(),c=Calendar.getInstance();c.setTimeInMillis(ms);
        return new SimpleDateFormat(getString(now.get(Calendar.YEAR)==c.get(Calendar.YEAR)?R.string.detail_date_day:R.string.detail_date_day_year),Lang.locale(this)).format(new Date(ms)).replaceAll("(?<=\\p{L})\\.","");}
    /**
     * Hora del día: «16:05» con el reloj de 24 horas del teléfono; con el de 12 horas, la del idioma (detail_time_12h:
     * «4:05 PM» en inglés). En español siempre «16:05», como antes.
     */
    private String clock(long ms){return new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this)?"HH:mm":getString(R.string.detail_time_12h),Lang.locale(this)).format(new Date(ms));}
    private static boolean sameTitle(String a,String b){return strip(a).equalsIgnoreCase(strip(b));}
    private static String strip(String t){return (t==null?"":t.trim()).replaceFirst("^\\d{4}-\\d{2}-\\d{2}\\s*","").trim();}
    /** El título sugerido por la nota nunca pisa uno escrito: se ofrece y se puede deshacer. */
    private void useSuggested(String suggested){
        String previous=recording.title;
        try{recording.title=suggested;recording.save(this);Pipeline.edited(this,id);Diagnostics.event("title_suggestion_used",id);Ui.haptic(content,Ui.Haptic.CONFIRM);reload();
            snackbar(getString(R.string.detail_title_changed),getString(R.string.detail_undo),()->{try{recording.title=previous;recording.save(this);Pipeline.edited(this,id);reload();}catch(Exception e){message(getString(R.string.detail_title),getString(R.string.detail_undo_change_failed));}});}
        catch(Exception e){recording.title=previous;message(getString(R.string.detail_title),getString(R.string.detail_title_failed));}
    }

    // ---------- Zona fija de abajo: onda, controles y botón que avanza ----------
    /**
     * Reproductor en una cápsula flotante (como la barra de pestañas): casi opaca, para que el texto que se desplaza
     * detrás no se mezcle con los controles. Arriba ▶ junto a la onda (como el mini reproductor de la referencia);
     * al medio el tiempo, ±15 y la velocidad; abajo el botón que avanza.
     */
    private void buildDock(){
        bottom.removeAllViews();bottom.setVisibility(View.VISIBLE);bottom.setBackgroundColor(0);bottom.setClipToPadding(false);bottom.setClipChildren(false);bottom.setPadding(ui.dp(S3),0,ui.dp(S3),ui.dp(S3));
        LinearLayout dock=ui.column();GradientDrawable capsule=shape(this,dockColor(),28);capsule.setStroke(Math.max(1,ui.dp(1)),p.glassStroke);dock.setBackground(capsule);
        dock.setElevation(ui.dp(p.dark?0:6));lift(dock);dock.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));
        if(!demo){
            LinearLayout wave=ui.row();
            play=ui.iconButton(R.drawable.ic_play,getString(R.string.detail_play),p.onBrand,p.brand,48);play.setOnClickListener(v->toggle());wave.addView(play);
            scrubber=new Scrubber(this,p,dockColor());scrubber.setDuration(recording.duration);scrubber.listener=this::onScrub;
            LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,ui.dp(48),1);sl.setMarginStart(ui.dp(S2));wave.addView(scrubber,sl);
            dock.addView(wave,Ui.fill());
            LinearLayout controls=ui.row();
            timeText=Ui.tabular(ui.oneLine(ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant)));timeText.setPadding(ui.dp(S1),0,ui.dp(S2),0);showTime(0);
            controls.addView(timeText,new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(skipButton(false));controls.addView(skipButton(true));controls.addView(speedBox());
            dock.addView(controls,Ui.fill()); // las dos filas de 48 dp ya traen su aire: la cápsula no crece más que el reproductor de antes
        }
        primary=ui.split(getString(demo?R.string.voices_title:R.string.detail_transcribe),demo?R.drawable.ic_people:R.drawable.ic_sparkle,v->onPrimary(),v->moreSheet());
        LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(demo?0:S2);dock.addView(primary,lp);
        LinearLayout.LayoutParams dl=Ui.fill();dl.topMargin=ui.dp(S2);bottom.addView(dock,dl);
    }
    /** Fondo de la cápsula: blanco casi opaco en claro (blanco con alfa, como el vidrio de la barra); gris verdoso en oscuro. */
    private int dockColor(){return p.dark?p.surfaceContainerHigh:0xF2FFFFFF;}
    /** Tiempo actual (en tinta) / duración (en gris), con cifras fijas para que no bailen mientras suena. */
    private void showTime(long ms){
        if(timeText==null)return;String now=Recording.time(ms);SpannableStringBuilder b=new SpannableStringBuilder(now).append(" / ").append(Recording.time(total()));
        b.setSpan(new ForegroundColorSpan(p.onSurface),0,now.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);timeText.setText(b);
    }
    private View skipButton(boolean forward){
        // Íconos redondos con el «15» adentro: se entienden de un vistazo y ocupan lo mismo que el texto de antes.
        ImageButton b=new ImageButton(this);b.setImageResource(forward?R.drawable.ic_forward_15:R.drawable.ic_replay_15);b.setImageTintList(ColorStateList.valueOf(p.onSurface));b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),null,oval(0xFF000000)));b.setContentDescription(getString(forward?R.string.detail_forward_15:R.string.detail_back_15));
        b.setOnClickListener(v->skip(forward?15000:-15000));Ui.pressable(b);b.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));return b;
    }
    private View speedBox(){
        FrameLayout box=new FrameLayout(this);box.setMinimumWidth(ui.dp(56));
        // Velocidad como píldora de vidrio: borde fino sobre la cápsula, cifras fijas en Outfit.
        speedChip=Ui.tabular(ui.chip(speedLabel(),p.onSurface,0));speedChip.setBackground(outline(this,p.dark?p.glass:p.surfaceContainerLow,p.outlineVariant,R_FULL,false));speedChip.setMinWidth(ui.dp(48));speedChip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(speedChip,new FrameLayout.LayoutParams(-2,ui.dp(32),Gravity.CENTER));
        box.setClickable(true);box.setFocusable(true);box.setContentDescription(getString(R.string.detail_speed_desc,speedLabel()));box.setAccessibilityDelegate(Ui.buttonRole());box.setOnClickListener(v->cycleSpeed());Ui.pressable(box);
        box.setLayoutParams(new LinearLayout.LayoutParams(-2,ui.dp(48)));speedBox=box;return box;
    }
    /** El botón principal según el siguiente paso de la grabación (Transcribir → Revisar voces → Guardar → ✓). */
    private void refreshPrimary(){
        if(primary==null)return;
        if(demo){primary.setLabel(getString(R.string.voices_title));primary.setIcon(R.drawable.ic_people);primary.setTonal(false);primary.setBusy(false);return;}
        Next n;try{n=Next.of(this,recording);}catch(Exception e){n=null;}
        next=n;
        if(saving){primary.setLabel(getString(R.string.detail_saving));primary.setBusy(true);return;}
        if(n==null){primary.setLabel(getString(transcript!=null?R.string.detail_save_or_share:R.string.detail_transcribe));primary.setTonal(false);primary.setBusy(false);return;}
        // Se repinta cada 3 s mientras está en cola (renderConditions): solo si cambió algo, para no rehacer el ícono.
        if(primary.main.busy()&&n.step==Next.Step.WORKING&&n.label.contentEquals(primary.main.label.getText()))return;
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
            // Igual que en Inicio y Biblioteca: abre directo la elección de carpeta y, al elegirla, vuelve aquí.
            case CHOOSE_FOLDER:RecordingActions.chooseFolder(this);break;
        }
    }
    /** ▾: todas las salidas siguen a mano (copiar, compartir, .txt, nota, otra carpeta, volver a transcribir). */
    private void moreSheet(){
        if(transcript==null){if(!demo)RecordingActions.menu(this,recording,this::reload,this::releasePlayer);return;}
        Sheet s=sheet(getString(R.string.detail_save_or_share),null);
        s.action(R.drawable.ic_copy,getString(R.string.detail_copy_text),false,this::copy);
        s.action(R.drawable.ic_share,getString(R.string.detail_share),false,this::shareText);
        s.action(R.drawable.ic_doc,getString(R.string.detail_txt_file),false,this::shareTxt);
        if(!demo){
            if(Notes.exists(this,id))s.action(R.drawable.ic_sparkle,getString(R.string.detail_share_note_md),false,this::shareNote);
            Next.Step step=next==null?null:next.step;
            if(Inbox.configured(this)&&step!=Next.Step.SAVE&&step!=Next.Step.UPDATE){int verb=step==Next.Step.SAVED?R.string.detail_save_again_in:R.string.detail_save_in;
                privateAction(s,R.drawable.ic_save,getString(verb,getString(R.string.detail_quick_folder)),getString(verb,Inbox.folderName(this)),this::inboxSave);}
            s.action(R.drawable.ic_folder,getString(R.string.detail_save_elsewhere),false,this::saveAs);
            s.action(R.drawable.ic_refresh,getString(R.string.detail_retranscribe),false,()->RetranscribeSheet.show(this,recording,this::reload));
        }
        s.show();
    }
    /**
     * Opción de hoja cuyo texto lleva un nombre de persona o de carpeta. En pantalla se lee completo, pero el registro
     * técnico (Diagnóstico, informe de soporte) guarda solo la acción genérica: «Guardar la voz», nunca «… de mamá».
     */
    private static void privateAction(Sheet s,int icon,String logged,String shown,Runnable run){s.action(icon,logged,false,run);relabel(s.body,logged,shown);}
    /** Cambia el texto visible (y el que lee TalkBack) de la opción recién agregada; el registro de la hoja no cambia. */
    private static void relabel(View v,String from,String to){
        if(v.getContentDescription()!=null&&from.contentEquals(v.getContentDescription()))v.setContentDescription(to);
        if(v instanceof TextView&&from.contentEquals(((TextView)v).getText()))((TextView)v).setText(to);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)relabel(g.getChildAt(i),from,to);}
    }
    private void savedSheet(){
        String folder=Inbox.folderName(this);long at=Inbox.savedAt(this,id);
        sheet(getString(R.string.detail_saved_title,folder),at>0?getString(R.string.detail_saved_body_at,clock(at)):getString(R.string.detail_saved_body))
            .primary(getString(R.string.detail_save_again),this::inboxSave).secondary(getString(R.string.detail_more_options),this::moreSheet).show();
    }

    // ---------- Reproductor ----------
    private long total(){return player!=null&&prepared?player.getDuration():recording.duration;}
    /** Las opciones de «Ruido de fondo» con que se grabó ("recNoise") y se transcribió ("audioClean"), o "" si ninguna. */
    private String audioUsed(JSONObject st){
        List<String> used=new ArrayList<>();int clean=st.optInt("audioClean");
        if(st.optBoolean("recNoise"))used.add(getString(R.string.detail_audio_rec_noise));
        if((clean&AudioClean.LEVEL)!=0)used.add(getString(R.string.detail_audio_clean_level));
        if((clean&AudioClean.NOISE)!=0)used.add(getString(R.string.detail_audio_clean_noise));
        return android.text.TextUtils.join(" · ",used);
    }
    private void ensurePlayer(){
        if(player!=null||demo)return;
        if(RecorderService.activeId!=null){message(getString(R.string.detail_recording_now),getString(R.string.detail_recording_now_body));return;}
        try{
            player=new MediaPlayer();AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();player.setAudioAttributes(attr);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).setOnAudioFocusChangeListener(c->{if(c<0&&player!=null&&player.isPlaying()){player.pause();setPlaying(false);}}).build();
            player.setDataSource(recording.audio(this).getAbsolutePath());player.prepare();prepared=true;if(scrubber!=null)scrubber.setDuration(player.getDuration());
            // 0.9.3: «Realzar voces al escuchar» (VoiceBoost): solo lo que se oye; el archivo no cambia.
            boost=VoiceBoost.attach(this,player.getAudioSessionId());
            player.setOnCompletionListener(mp->{setPlaying(false);if(playUntil>0){playUntil=0;if(tramoEnded!=null)tramoEnded.run();}});
            player.setOnErrorListener((mp,w,e)->{releasePlayer();message(getString(R.string.detail_play_failed),getString(R.string.detail_play_failed_cut));return true;});
            Diagnostics.event("playback_open",id);
        }catch(Exception e){releasePlayer();message(getString(R.string.detail_play_failed),getString(R.string.detail_play_failed_open));}
    }
    private void toggle(){ensurePlayer();if(!prepared)return;
        if(player.isPlaying()){player.pause();setPlaying(false);}
        else if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){applySpeed();player.start();setPlaying(true);}}
    /** Forma que se muestra ahora en ▶ (true = sonando) y su animación, para no rehacerla si el estado no cambió. */
    private boolean playShown;private ValueAnimator playMorph;
    /**
     * ▶ blanco en un círculo verde de marca; al sonar, ❚❚ en un cuadrado redondeado del mismo verde. La forma cuenta el
     * estado (Material 3 Expressive) y el verde se queda: es el botón de la marca. El cambio de forma usa la curva
     * emphasized, sin rebote; con «Quitar animaciones» es inmediato.
     */
    private void setPlaying(boolean on){
        if(play==null)return;
        play.setImageResource(on?R.drawable.ic_pause:R.drawable.ic_play);play.setContentDescription(getString(on?R.string.detail_pause:R.string.detail_play));play.setImageTintList(ColorStateList.valueOf(p.onBrand));
        float round=ui.dp(24),square=ui.dp(R_CONTROL),to=on?square:round,from=playShown?square:round;boolean changed=on!=playShown;playShown=on;
        GradientDrawable fill=new GradientDrawable(),mask=new GradientDrawable();fill.setColor(p.brand);mask.setColor(0xFF000000);fill.setCornerRadius(to);mask.setCornerRadius(to);
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onBrand)),fill,mask));
        if(playMorph!=null){playMorph.cancel();playMorph=null;}
        if(changed&&AppTheme.motion()&&play.isAttachedToWindow()){
            fill.setCornerRadius(from);mask.setCornerRadius(from);playMorph=ValueAnimator.ofFloat(from,to);playMorph.setDuration(MOTION_BASE);playMorph.setInterpolator(EMPHASIZED);
            playMorph.addUpdateListener(a->{float r=(float)a.getAnimatedValue();fill.setCornerRadius(r);mask.setCornerRadius(r);});playMorph.start();
        }
        labelPlaying(on);
    }
    private void skip(int ms){ensurePlayer();if(!prepared)return;int to=Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+ms));player.seekTo(to);if(scrubber!=null)scrubber.setPosition(to);showTime(to);}
    private void onScrub(long ms,boolean done){
        showTime(ms);
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
        if(playingTurn!=null){playingTurn.setBackground(null);Object[] old=turnOf(playingTurn);if(old!=null){((TextView)old[3]).setText((String)old[4]);((TextView)old[3]).setTextColor(p.onSurfaceVariant);}}
        playingTurn=turn;
        if(turn!=null){
            // Recuadro menta con esquinas concéntricas a las de la tarjeta (24 − 4 dp de margen); aparece con un fundido corto.
            GradientDrawable glow=shape(this,p.highlight,R_CARD-S1);turn.setBackground(glow);
            if(AppTheme.motion()){ValueAnimator in=ValueAnimator.ofInt(0,255);in.setDuration(MOTION_BASE);in.setInterpolator(EMPHASIZED_DECELERATE);in.addUpdateListener(a->glow.setAlpha((int)a.getAnimatedValue()));in.start();}
            if(restoringPlaying)restoringPlaying=false;else followPending=true;labelPlaying(player!=null&&prepared&&player.isPlaying());}
    }
    private boolean restoringPlaying;
    /** La hora de la intervención que suena dice «· sonando» y se pone verde. */
    private void labelPlaying(boolean on){Object[] t=playingTurn==null?null:turnOf(playingTurn);if(t!=null){((TextView)t[3]).setText(on?getString(R.string.detail_turn_playing,t[4]):(String)t[4]);((TextView)t[3]).setTextColor(on?p.primary:p.onSurfaceVariant);}}
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
        if(show){String back=getString(R.string.detail_follow),label=(below?"↓":"↑")+" "+back;if(!label.contentEquals(followChip.getText())){followChip.setText(label);followChip.setContentDescription(back);}}
        boolean visible=followChip.getVisibility()==View.VISIBLE;
        if(show!=visible){followChip.setVisibility(show?View.VISIBLE:View.GONE);if(show)ui.fadeIn(followChip);}
    }
    private void updateBarTitle(){
        if(barTitle==null||titleView==null||!titleView.isAttachedToWindow())return;
        Rect r=new Rect();titleView.getDrawingRect(r);try{scroll.offsetDescendantRectToMyCoords(titleView,r);}catch(IllegalArgumentException e){return;}
        boolean show=scroll.getScrollY()>r.bottom-ui.dp(S2);
        // Entra subiendo 8 dp y se va bajando, más rápido (curvas emphasized de entrada y salida).
        if(show!=barTitleShown){barTitleShown=show;barTitle.animate().alpha(show?1f:0f).translationY(show?0f:ui.dp(S2)).setDuration(show?MOTION_BASE:MOTION_FAST+50).setInterpolator(show?EMPHASIZED_DECELERATE:EMPHASIZED_ACCELERATE).start();}
    }
    /** «1,25×» (con el separador decimal del idioma: «1.25×» en inglés). */
    private String speedLabel(){java.text.NumberFormat f=java.text.NumberFormat.getNumberInstance(Lang.locale(this));f.setMaximumFractionDigits(2);return f.format(rate)+"×";}
    private void cycleSpeed(){float[] rates={1f,1.25f,1.5f,2f,0.75f};int i=0;for(int k=0;k<rates.length;k++)if(Math.abs(rates[k]-rate)<0.01f)i=k;rate=rates[(i+1)%rates.length];
        speedChip.setText(speedLabel());speedBox.setContentDescription(getString(R.string.detail_speed_desc,speedLabel()));applySpeed();}
    private void applySpeed(){if(player!=null&&prepared)try{boolean playing=player.isPlaying();player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));if(!playing&&player.isPlaying())player.pause();}catch(Exception ignored){}}
    private VoiceBoost boost;
    private void releasePlayer(){prepared=false;if(boost!=null){boost.release();boost=null;}if(player!=null){player.release();player=null;}if(focus!=null){getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}setPlaying(false);}
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
        // Con OpenRouter, «Proveedor: …» nombra el modelo según la lista guardada: se deja leída (queda en memoria) para
        // que un modelo nuevo salga con su nombre y no con el final de su identificador.
        if(s.openRouter())Models.cached(this);
        card.addView(sparkTile(44));
        TextView h=ui.heading(getString(R.string.detail_new_title),Type.TITLE_LARGE);h.setPadding(0,ui.dp(S4),0,ui.dp(S1));card.addView(h);
        // Sin clave se nombra a OpenRouter (0.8.0: es el único proveedor que ofrece la app) y se dice que el audio viaja allá.
        card.addView(ui.text(s.hasKey()?getString(R.string.detail_new_body,SettingsActivity.modelSummary(s))
            :getString(R.string.detail_new_body_nokey),Type.BODY_MEDIUM,p.onSurfaceVariant));
        // Costos con el proveedor y los modelos reales (0.8.0: también OpenRouter, con el precio de su catálogo). Un servidor
        // propio no tiene tarifa conocida y no muestra nada; un modelo que no separa voces muestra solo el del texto.
        // Con voces y OpenRouter, el estimado cuenta las muestras de voz que viajan con cada parte (Pricing.billedMs, la regla única).
        if(s.hasKey()){String provider=s.provider();
            double voices=s.canSeparate()?Pricing.estimate(this,provider,RecordingActions.model(s,true),Pricing.billedMs(this,recording.duration,true)):-1,text=Pricing.estimate(this,provider,RecordingActions.model(s,false),recording.duration);
            // Los dos costos como píldoras, una bajo la otra (juntas no caben en un teléfono angosto): se comparan de un vistazo.
            if(voices>=0){TextView both=Ui.tabular(ui.chip(getString(R.string.detail_cost_voices,Pricing.usd(voices)),p.onPrimaryContainer,p.primaryContainer));LinearLayout.LayoutParams bl=Ui.wrap();bl.topMargin=ui.dp(S3);card.addView(both,bl);}
            if(text>=0){TextView plain=Ui.tabular(ui.chip(getString(R.string.detail_cost_text,Pricing.usd(text)),p.onSurfaceVariant,0));plain.setBackground(outline(this,chipFill(),p.outlineVariant,R_FULL,false));
                LinearLayout.LayoutParams pl=Ui.wrap();pl.topMargin=ui.dp(voices>=0?S2:S3);card.addView(plain,pl);}}
        content.addView(card,gap());
    }

    // ---------- En curso: titular, partes, etapas, estimación y bitácora (se actualiza en su lugar) ----------
    private TextView progHeadline,progParts,progEstimate,progNote,phaseElapsed,upText;private Meter progBar,upBar;private LinearLayout progStages,conditions;private View startNow;
    private long phaseSince,conditionsAt,lastEstimateAt;private String shownBlocker;
    private void resetLiveViews(){progHeadline=progParts=progEstimate=progNote=phaseElapsed=upText=null;progBar=upBar=null;progStages=conditions=null;startNow=null;liveTotal=liveRemaining=null;liveState=null;logList=statsHolder=null;conditionsAt=0;shownBlocker=null;}
    private void showQueued(JSONObject st){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S4),ui.dp(S5),ui.dp(S4));
        LinearLayout head=ui.row();head.setGravity(Gravity.TOP);
        // La fila se alinea arriba (el titular puede ocupar dos líneas); el indicador y el titular bajan 5 dp para quedar
        // centrados con la píldora de la estimación, que mide 32 dp.
        ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ui.dp(22),ui.dp(22));sp.topMargin=ui.dp(6);head.addView(spin,sp);head.addView(ui.space(S3));
        progHeadline=ui.text("",Type.TITLE_MEDIUM,p.onSurface);progHeadline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);progHeadline.setPadding(0,ui.dp(5),0,0);head.addView(progHeadline,new LinearLayout.LayoutParams(0,-2,1));
        // La estimación («≈ 2–4 min», «casi lista») en una píldora menta: es lo que más se mira mientras espera.
        progEstimate=Ui.tabular(ui.chip("",p.onPrimaryContainer,p.primaryContainer));LinearLayout.LayoutParams el=Ui.wrap();el.setMarginStart(ui.dp(S2));head.addView(progEstimate,el);
        card.addView(head,Ui.fill());
        phaseElapsed=ui.text("",Type.BODY_SMALL,p.onSurfaceVariant);phaseElapsed.setFontFeatureSettings("tnum");phaseElapsed.setPadding(ui.dp(22+S3),ui.dp(2),0,0);card.addView(phaseElapsed);
        if(st.has("retranscribe")){TextView r=ui.text(getString(R.string.detail_again_note),Type.BODY_MEDIUM,p.onSurfaceVariant);r.setPadding(0,ui.dp(S3),0,0);card.addView(r);}
        progParts=Ui.tabular(ui.text("",Type.TITLE_MEDIUM,p.onSurface));progParts.setPadding(0,ui.dp(S4),0,ui.dp(S2));card.addView(progParts);
        // Barras en verde sobre menta: avanzan solo con lo terminado de verdad.
        progBar=new Meter(this,p.primary,p.primaryContainer);card.addView(progBar,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        upText=ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant);upText.setFontFeatureSettings("tnum");upText.setPadding(0,ui.dp(S4),0,ui.dp(S2));card.addView(upText);
        upBar=new Meter(this,p.primary,p.primaryContainer);card.addView(upBar,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);progStages=ui.row();hs.addView(progStages);card.addView(hs,ui.top(S4));
        conditions=ui.column();conditions.setPadding(0,ui.dp(S3),0,0);card.addView(conditions,Ui.fill());
        startNow=ui.button(getString(R.string.detail_start_now),R.drawable.ic_play,Ui.Style.TONAL,v->{if(Pipeline.startForeground(this,true))toast(getString(R.string.detail_start_now_ok));else message(getString(R.string.detail_start_now),getString(R.string.detail_start_now_denied));});
        card.addView(startNow,ui.top(S3));
        View div=new View(this);div.setBackgroundColor(p.outlineVariant);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(1)));dl.topMargin=ui.dp(S4);card.addView(div,dl);
        progNote=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);progNote.setPadding(0,ui.dp(S3),0,0);card.addView(progNote);
        card.addView(details(st,true),Ui.fill());
        content.addView(card,gap());
        liveState=st;renderConditions();updateProgress(st);
    }
    /** Actualiza la tarjeta en su lugar con el estado nuevo (sin reconstruir la pantalla). */
    private void updateProgress(JSONObject st){
        if(progHeadline==null)return;
        liveState=st;phaseSince=st.optLong("since",System.currentTimeMillis());
        // «Preparando el audio» con su avance en % (0.8.0, tercera ronda): la conversión ya no parece detenida.
        int prep=prepPercent(st);String head=human(st.optString("status",""));
        progHeadline.setText(prep>=0&&StatusText.preparing(head)?head+" · "+getString(R.string.detail_percent,prep):head);
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone");boolean parts=blocks>1;
        progParts.setVisibility(parts?View.VISIBLE:View.GONE);progBar.setVisibility(parts?View.VISIBLE:View.GONE);
        if(parts){progParts.setText(getString(R.string.detail_parts_done,done,blocks));progBar.set(done/(float)blocks);}
        long sent=st.optLong("upSent"),total=st.optLong("upTotal");boolean uploading=total>0&&sent<total,preparing=!uploading&&prep>=0;
        upText.setVisibility(uploading||preparing?View.VISIBLE:View.GONE);upBar.setVisibility(uploading||preparing?View.VISIBLE:View.GONE);
        if(uploading){upText.setText(getString(R.string.detail_sending_mb,sent/1e6,total/1e6));upBar.set(sent/(float)total);}
        else if(preparing){upText.setText(getString(R.string.detail_preparing_pct,prep));upBar.set(prep/100f);}
        renderStages(st);refreshWaiting(shownBlocker);
        if(statsHolder!=null){statsHolder.removeAllViews();statsHolder.addView(stats(st,true),Ui.fill());}
        if(logList!=null){JSONArray log=st.optJSONArray("log");int n=log==null?0:log.length();JSONObject last=n==0?null:log.optJSONObject(n-1);long lastT=last==null?0:last.optLong("t");
            if(n!=logCount||lastT!=logLast){logCount=n;logLast=lastT;renderLog(logList,log);}}
    }
    /**
     * Etapas reales: (Preparar audio) · Subido · Partes x/n · Unir voces · Nota · Lista. La barra avanza solo con partes
     * terminadas de verdad. «Preparar audio» (0.8.0) es la conversión que hace OpenRouter antes de cada envío: mientras
     * dura, «Subido» no aparece en curso (antes se veía «Enviando… 0 %» con el audio todavía convirtiéndose).
     */
    private void renderStages(JSONObject st){
        if(progStages==null)return;progStages.removeAllViews();
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone");long sent=st.optLong("upSent"),total=st.optLong("upTotal");
        boolean uploaded=done>0||(total>0&&sent>=total)||StatusText.uploadedInLog(st),partsDone=blocks>0&&done>=blocks;
        boolean preparing=st.optInt("prepping")>0||StatusText.preparing(st.optString("status"));int prep=prepPercent(st);
        if("openrouter".equals(st.optString("provider")))stage(preparing&&prep>=0?getString(R.string.detail_stage_prepare_pct,prep):getString(R.string.detail_stage_prepare),preparing?1:(uploaded||st.optInt("prepCount")>0)?2:0);
        // «Subido» en curso solo si se está enviando ESTA (con otra en curso, esta sigue en cola).
        stage(getString(R.string.detail_stage_uploaded),uploaded?2:mine()&&!preparing?1:0);
        stage(blocks>1?getString(R.string.detail_stage_parts,done,blocks):getString(R.string.detail_stage_text),partsDone?2:uploaded?1:0);
        if(st.optBoolean("speakers")&&blocks>1)stage(getString(R.string.detail_stage_join),partsDone?1:0);
        if(new Settings(this).noteAuto()&&Notes.canGenerate(this))stage(getString(R.string.detail_stage_note),0);
        stage(getString(R.string.detail_stage_done),0);
    }
    /**
     * state: 0 pendiente, 1 en curso, 2 listo. Píldoras: lo listo en menta con ✓, lo que está en curso con borde verde
     * (lo que se mira ahora) y lo pendiente solo con un borde tenue.
     */
    private void stage(String label,int state){
        TextView t=Ui.tabular(ui.text(state==2?"✓ "+label:label,Type.LABEL_MEDIUM,state==2?p.onPrimaryContainer:state==1?p.primary:p.onSurfaceVariant));
        t.setGravity(Gravity.CENTER);t.setMinHeight(ui.dp(28));t.setPadding(ui.dp(S3),0,ui.dp(S3),0);
        t.setBackground(state==2?shape(this,p.primaryContainer,R_FULL):state==1?outline(this,chipFill(),p.primary,R_FULL,false):outline(this,0x00000000,p.outlineVariant,R_FULL,false));
        t.setContentDescription(getString(state==2?R.string.detail_stage_state_done:state==1?R.string.detail_stage_state_now:R.string.detail_stage_state_pending,label));
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(6));progStages.addView(t,lp);
    }
    /** Avance de «Preparando el audio» (0–100) si alguna parte se está convirtiendo ahora; -1 si no. */
    private static int prepPercent(JSONObject st){return st.optInt("prepping")>0&&st.has("prepPct")?Math.max(0,Math.min(100,st.optInt("prepPct"))):-1;}
    /** Qué pasa ahora: tranquilidad si trabaja, o por qué espera; «Empezar ahora» solo si Android la está demorando. */
    private void refreshWaiting(String blocker){
        if(progNote==null)return;boolean running=Pipeline.working(),mine=mine();
        // Un paso que lleva mucho en lo mismo (0.8.0): se dice, y qué va a pasar. Solo si ESTA grabación está en ese paso.
        boolean slow=mine&&System.currentTimeMillis()-phaseSince>15*60_000L;
        progNote.setText(waitingNote(mine,running,RecordingActions.behind(running,mine,Transcriber.currentId,Transcriber.retryingId),slow,blocker));
        startNow.setVisibility(!running&&blocker==null?View.VISIBLE:View.GONE);
        refreshEstimate();
    }
    /**
     * ¿ESTA grabación es la que se procesa ahora? La regla única Pipeline.processing: la transcribe o espera para
     * reintentarla. Pipeline.working() dice que hay un trabajo andando, pero puede estar con otra grabación mientras esta
     * sigue en cola o esperando Wi-Fi. Con solo Transcriber.currentId (en null entre intentos), cada espera para reintentar
     * pasaba el botón a «En cola…» y escondía la estimación.
     */
    private boolean mine(){return Pipeline.processing(id);}
    /** La nota de la tarjeta según qué pasa con ESTA grabación (ver StatusText.waitingNote). */
    static String waitingNote(boolean mine,boolean running,boolean other,boolean slow,String blocker){return StatusText.waitingNote(mine,running,other,slow,blocker);}
    /** La estimación solo para la grabación que se procesa: una en cola no tiene un «≈ 2–4 min» que cumplir. */
    private void refreshEstimate(){if(progEstimate==null)return;String est=mine()&&liveState!=null?estimate(liveState):"";progEstimate.setText(est);progEstimate.setVisibility(est.isEmpty()?View.GONE:View.VISIBLE);lastEstimateAt=System.currentTimeMillis();}
    /**
     * Estimación honesta y como rango. Con varias partes, por lo que tardaron las ya listas (eso ya incluye preparar el
     * audio); con una sola (≤ 12 min), por tiempo: la separación de voces tarda ~0,15× la duración del audio, y con
     * OpenRouter se suma lo que tarda prepararlo (Transcriber.prepEstimate, o lo medido si ya se preparó).
     */
    private String estimate(JSONObject st){
        long now=System.currentTimeMillis(),audio=st.optLong("audioMs",recording.duration),sum=st.optLong("blockMsSum");int blocks=st.optInt("blocks"),done=st.optInt("blocksDone"),count=st.optInt("blockCount");
        long left;
        if(blocks>1&&count>0){if(done>=blocks)return getString(R.string.detail_almost_ready);int rounds=(blocks-done+Transcriber.PARALLEL-1)/Transcriber.PARALLEL;long avg=sum/count;left=Math.max(avg/5,rounds*avg-(now-st.optLong("since",now)));}
        else{
            double factor=st.optBoolean("speakers",true)?0.16:0.08;int prepared=st.optInt("prepCount");
            long prep="openrouter".equals(st.optString("provider"))?(prepared>0?st.optLong("prepMsSum")/prepared:Transcriber.prepEstimate(audio)):0;
            left=(long)(audio*factor)+20000+prep-(now-startedAt(st));
        }
        if(left<=20000)return left>-120000?getString(R.string.detail_almost_ready):"";
        long lo=Math.max(1,Math.round(left*0.8/60000.0)),hi=Math.max(lo+1,Math.round(left*1.3/60000.0));
        return hi>=60?"≈ "+Ui.humanDuration(left):"≈ "+lo+"–"+hi+" min";
    }
    /** Cuándo empezó a trabajar de verdad (no el tiempo esperando Wi-Fi o el cargador). */
    private static long startedAt(JSONObject st){return StatusText.startedAt(st);}
    /** La última línea de la bitácora en palabras simples («parte» en vez de «bloque», sin tiempos técnicos). */
    static String human(String m){return StatusText.human(m);}
    /** Un texto de espera de Wi-Fi tal como se ve en el detalle (ver StatusText.inDetail). */
    static String inDetail(String m){return StatusText.inDetail(m);}
    /** Condiciones reales del teléfono. Si todo está bien, una sola línea tranquila; si algo falta, cada condición con su salida. */
    private void renderConditions(){
        if(conditions==null)return;
        // Las condiciones de ESTA grabación: con «Usar datos móviles ahora» no espera Wi-Fi aunque las demás sí.
        String blocker=Pipeline.blocker(this,id);
        // Si una condición cambió (p. ej. conectaste el cargador), se intenta empezar ya.
        if(conditionsAt>0&&blocker==null&&!Objects.equals(blocker,shownBlocker))Pipeline.startForeground(this);
        shownBlocker=blocker;conditions.removeAllViews();conditionsAt=System.currentTimeMillis();Settings s=new Settings(this);
        boolean online=Pipeline.network(this)!=null,wifi=Pipeline.unmetered(this);android.os.BatteryManager bm=getSystemService(android.os.BatteryManager.class);boolean charging=bm.isCharging();int level=bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        JSONObject st=FilesStore.state(this,id);boolean mobileOk=st.optBoolean("mobileOk");
        boolean netOk=online&&(!s.wifiOnly()||wifi||mobileOk),chargeOk=!s.charging()||charging,batteryOk=charging||level>15,free=Battery.unrestricted(this);
        if(netOk&&chargeOk&&batteryOk&&free)condition(true,getString(R.string.detail_cond_all_ok,wifi?"Wi-Fi":getString(mobileOk&&s.wifiOnly()?R.string.detail_cond_mobile_this:R.string.detail_cond_mobile),level));
        else{
            condition(netOk,getString(!online?R.string.detail_cond_offline:s.wifiOnly()?(wifi?R.string.detail_cond_wifi_on:mobileOk?R.string.detail_cond_mobile_this:R.string.detail_cond_wifi_needed):(wifi?R.string.detail_cond_via_wifi:R.string.detail_cond_via_mobile)));
            // Espera Wi-Fi teniendo datos móviles: la salida a la vista, grande, con lo que pesaría el envío (0.8.0, tercera
            // ronda). Vale solo para esta grabación; «Solo con Wi-Fi» sigue igual para las demás.
            if(Pipeline.waitsForWifi(s.wifiOnly(),online,wifi,st)&&!mine()){
                String size=Pipeline.megabytes(Pipeline.uploadBytes(this,recording));
                Ui.Btn mobile=ui.button(getString(R.string.detail_use_mobile,size),R.drawable.ic_upload,Ui.Style.PRIMARY,v->useMobile());
                // TalkBack lee «≈6,4 MB» como «aproximadamente 6,4 MB».
                mobile.setContentDescription(getString(R.string.detail_use_mobile_desc,size.startsWith("≈")?getString(R.string.detail_about,size.substring(1).trim()):size));
                conditions.addView(mobile,ui.top(S3));
                TextView why=ui.text(getString(R.string.detail_use_mobile_why),Type.BODY_SMALL,p.onSurfaceVariant);why.setPadding(0,ui.dp(S2),0,ui.dp(S1));conditions.addView(why);
            }
            condition(chargeOk,getString(s.charging()?(charging?R.string.detail_cond_charging:R.string.detail_cond_charger_needed):R.string.detail_cond_charger_optional));
            condition(batteryOk,getString(batteryOk?R.string.detail_cond_battery:R.string.detail_cond_battery_low,level));
            // Con la optimización activa, algunos teléfonos congelan la app con la pantalla bloqueada y cortan la conexión.
            condition(free,getString(free?R.string.detail_cond_background_ok:R.string.detail_cond_background_limited));
            if(!free){Ui.Btn allow=ui.button(getString(R.string.detail_allow_background),R.drawable.ic_battery,Ui.Style.TONAL,v->RecordingActions.allowBackground(this));conditions.addView(allow,ui.top(S2));}
        }
        refreshWaiting(blocker);
        // El botón de abajo dice lo mismo (Next.working): «Esperando Wi-Fi…» pasa a «Transcribiendo…» cuando esta empieza,
        // aunque eso no cambie el estado guardado (llegó el Wi-Fi, terminó la otra).
        refreshPrimary();
    }
    /**
     * «Usar datos móviles ahora»: permite los datos móviles solo para esta grabación y arranca ya (la app está a la vista,
     * así que en Android 14+ va como transferencia iniciada por el usuario). Lo llaman el botón y la acción de la notificación.
     */
    private void useMobile(){
        if(demo||id==null)return;
        if(Pipeline.allowMobile(this,id)){
            Diagnostics.event("ui_action",id,"screen","RecordingActivity","action","mobile_ok");
            Ui.haptic(content,Ui.Haptic.CONFIRM);Pipeline.start(this,true);toast(getString(R.string.detail_using_mobile));
        }
        conditionsAt=0;renderConditions();
    }
    private void condition(boolean ok,String text){LinearLayout r=ui.row();r.setPadding(0,ui.dp(3),0,ui.dp(3));r.addView(ui.icon(ok?R.drawable.ic_check_circle:R.drawable.ic_clock,ok?p.primary:p.error,18));r.addView(ui.space(S2));r.addView(ui.text(text,Type.BODY_MEDIUM,ok?p.onSurfaceVariant:p.onSurface),new LinearLayout.LayoutParams(0,-2,1));conditions.addView(r);}
    /** Cada 250 ms: cronómetros; cada segundo la estimación; cada 3 s las condiciones. */
    private void tickProcess(){
        if(liveTotal!=null&&liveState!=null&&liveTotal.isAttachedToWindow()){liveTotal.setText(Recording.time(System.currentTimeMillis()-liveState.optLong("queuedAt",System.currentTimeMillis())));if(liveRemaining!=null)liveRemaining.setText(remainingText(liveState));}
        if(mode!=Mode.QUEUED||phaseElapsed==null||!phaseElapsed.isAttachedToWindow())return;
        long now=System.currentTimeMillis();
        phaseElapsed.setText(getString(R.string.detail_phase_elapsed,Recording.time(now-phaseSince)));
        if(now-lastEstimateAt>=1000)refreshEstimate();
        if(now-conditionsAt>3000)renderConditions();
    }

    // ---------- Métricas y bitácora (detalles del proceso) ----------
    private TextView liveTotal,liveRemaining;private JSONObject liveState;private LinearLayout logList,statsHolder;private int logCount=-1;private long logLast;
    /** Grilla de métricas. live=true: tiempo total y restante se actualizan cada 250 ms. El nombre del modelo vive solo aquí. */
    private View stats(JSONObject st,boolean live){
        // Panel interior más claro que el vidrio que lo rodea (como los datos de «Tu semana»).
        LinearLayout grid=ui.column();grid.setBackground(shape(this,chipFill(),R_CONTROL+S1));grid.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        long now=System.currentTimeMillis(),queued=st.optLong("queuedAt",now),audio=st.optLong("audioMs",recording.duration),doneAudio=st.optLong("doneAudioMs");
        long elapsed=live?now-queued:st.optLong("doneIn",now-queued);String model=st.optString("model","");
        List<String[]> cells=new ArrayList<>();
        cells.add(new String[]{getString(R.string.detail_stat_total),Recording.time(elapsed)});
        if(live)cells.add(new String[]{getString(R.string.detail_stat_left),remainingText(st)});
        cells.add(new String[]{getString(R.string.detail_stat_audio),getString(R.string.detail_of,Recording.time(live?doneAudio:audio),Recording.time(audio))});
        long speedBase=live?doneAudio:audio;if(speedBase>0&&elapsed>0)cells.add(new String[]{getString(R.string.detail_stat_speed),getString(R.string.detail_stat_speed_value,speedBase/(double)elapsed)});
        // OpenRouter: cuánto tomó convertir el audio antes de enviarlo (la etapa «Preparar audio»), sumado entre partes.
        long prepMs=st.optLong("prepMsSum");int prepN=st.optInt("prepCount");
        if(prepMs>0){String took=prepMs<1000?"< 1 s":Recording.time(prepMs);cells.add(new String[]{getString(R.string.detail_stat_prep),prepN>1?getString(R.string.detail_stat_prep_parts,took,prepN):took});}
        // Con OpenRouter se cobra también lo que suenan las muestras de voz que van antes de cada parte (Pricing.billedMs, la
        // regla única), con el proveedor y el modelo de ESTA transcripción; «sin cortar» las lleva una sola vez.
        String provider=st.optString("provider","openai");
        int anchors=st.optBoolean("speakers")&&TranscribeClient.knowsVoices(provider)?Voices.selected(this).size():0;
        long billed=Pricing.billedMs(provider,model,audio,anchors,Retranscribe.mode(st)==Retranscribe.Mode.SINGLE);
        double spent=Pricing.estimate(this,provider,model,live?doneAudio:billed),total=Pricing.estimate(this,provider,model,billed);
        // Costo real (0.8.0): OpenRouter informa lo que cobró cada envío y el motor lo suma en "costUsd". Si existe, va en
        // vez del estimado (sin «≈»); mientras se transcribe, junto al total estimado si se conoce.
        double real=Pricing.real(st);
        if(real>=0)cells.add(new String[]{getString(live?R.string.detail_stat_cost_so_far:R.string.detail_stat_cost),live&&total>=0?getString(R.string.detail_stat_cost_total,Pricing.usd(real),Pricing.usd(total)):Pricing.usd(real)});
        else if(total>=0)cells.add(new String[]{getString(live?R.string.detail_stat_cost_so_far:R.string.detail_stat_cost_estimated),live?getString(R.string.detail_stat_cost_total,Pricing.usd(spent),Pricing.usd(total)):Pricing.usd(spent)});
        long in=st.optLong("inTokens"),out=st.optLong("outTokens");double secs=st.optDouble("usageSec",0);
        if(in+out>0)cells.add(new String[]{getString(R.string.detail_stat_tokens),getString(R.string.detail_stat_tokens_value,in+out,in)});
        else if(secs>0)cells.add(new String[]{getString(R.string.detail_stat_billed),Recording.time((long)(secs*1000))});
        long sent=st.optLong("bytesSent")+(live?st.optLong("upSent"):0);if(sent>0)cells.add(new String[]{getString(R.string.detail_stat_sent),String.format(Lang.locale(this),"%.1f MB",sent/1e6)});
        int chars=st.optInt("liveChars");if(live&&chars>0)cells.add(new String[]{getString(R.string.detail_stat_received),getResources().getQuantityString(R.plurals.detail_stat_chars,chars,chars)});
        int retries=st.optInt("retries",st.optInt("attempts")),cuts=st.optInt("localCuts");if(retries>0)cells.add(new String[]{getString(R.string.detail_stat_retries),cuts>0?getString(R.string.detail_stat_retries_phone,retries,cuts):String.valueOf(retries)});
        for(int i=0;i<cells.size();i+=2){
            LinearLayout line=ui.row();line.setGravity(Gravity.TOP);line.setPadding(0,ui.dp(S1),0,ui.dp(S1));
            for(int k=i;k<Math.min(i+2,cells.size());k++){LinearLayout cell=ui.column();cell.addView(ui.text(cells.get(k)[0],Type.LABEL_MEDIUM,p.onSurfaceVariant));TextView v=ui.text(cells.get(k)[1],Type.TITLE_SMALL,p.onSurface);v.setFontFeatureSettings("tnum");cell.addView(v);line.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
                if(live&&k==0)liveTotal=v;if(live&&k==1)liveRemaining=v;}
            if(cells.size()-i==1)line.addView(ui.flex());grid.addView(line,Ui.fill());
        }
        // De dónde sale el costo: informado por el proveedor (real), del catálogo de OpenRouter o de la tabla pública de OpenAI.
        String priced=real>=0?getString(R.string.detail_cost_reported,RecordingActions.providerName(provider)):total<0?"":"openrouter".equals(provider)?getString(R.string.detail_cost_catalog):getString(R.string.detail_cost_public,Pricing.REVIEWED);
        if(!model.isEmpty()){TextView m=ui.text(getString(R.string.detail_model,model)+(st.optBoolean("speakers")?" · "+getString(R.string.detail_model_voices):"")+(priced.isEmpty()?"":" · "+priced),Type.BODY_SMALL,p.onSurfaceVariant);m.setPadding(0,ui.dp(S2),0,0);grid.addView(m);}
        // 0.9.3: con qué opciones de «Ruido de fondo» se grabó y se transcribió (para comparar con «Volver a transcribir»).
        String noiseUsed=audioUsed(st);if(!noiseUsed.isEmpty()){TextView a=ui.text(getString(R.string.detail_audio_used,noiseUsed),Type.BODY_SMALL,p.onSurfaceVariant);a.setPadding(0,ui.dp(S1),0,0);grid.addView(a);}
        if(live)liveState=st;return grid;
    }
    /**
     * «Restante (aprox.)» con la regla de la estimación de arriba (refreshEstimate): solo la grabación que se procesa tiene
     * un restante que cumplir. Una en cola o esperando Wi-Fi dice «en espera»; antes la cuenta bajaba igual hasta un
     * mínimo y se quedaba ahí durante toda la espera, junto a «Esperando: Wi-Fi».
     */
    private String remainingText(JSONObject st){return mine()?remaining(st):getString(R.string.detail_waiting);}
    /** Estimación: promedio por bloque × rondas restantes (bloques de a PARALLEL en paralelo). */
    private String remaining(JSONObject st){
        int blocks=st.optInt("blocks"),done=st.optInt("blocksDone"),count=st.optInt("blockCount");long sum=st.optLong("blockMsSum");
        if(blocks<=0||count==0)return getString(R.string.detail_calculating);if(done>=blocks)return getString(R.string.detail_almost_done);
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
        if(live)body.setPadding(0,ui.dp(S2),0,0);else{body.setBackground(glass(this,p,R_CARD));body.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S4),ui.dp(S3));}
        LinearLayout statsBox=ui.column();body.addView(statsBox,Ui.fill());if(live||st.has("model"))statsBox.addView(stats(st,live),Ui.fill());
        TextView h=ui.text(getString(R.string.detail_log),Type.TITLE_SMALL,p.primary);h.setPadding(0,ui.dp(S4),0,ui.dp(S1));body.addView(h);
        LinearLayout list=ui.column();body.addView(list,Ui.fill());renderLog(list,log);
        if(live){statsHolder=statsBox;logList=list;logCount=log==null?0:log.length();JSONObject last=logCount==0?null:log.optJSONObject(logCount-1);logLast=last==null?0:last.optLong("t");}
        Settings settings=new Settings(this);boolean open=live?settings.bitacoraOpen():detailsOpen;
        Ui.Btn toggle=ui.button(getString(open?R.string.detail_hide_details:R.string.detail_show_details),R.drawable.ic_info,Ui.Style.PLAIN,null);body.setVisibility(open?View.VISIBLE:View.GONE);
        toggle.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);toggle.setText(getString(show?R.string.detail_hide_details:R.string.detail_show_details));if(live)settings.setBitacoraOpen(show);else detailsOpen=show;});
        LinearLayout.LayoutParams tl=Ui.wrap();tl.topMargin=ui.dp(live?S1:S2);box.addView(toggle,tl);box.addView(body,Ui.fill());return box;
    }
    private void renderLog(LinearLayout list,JSONArray log){
        list.removeAllViews();if(log==null)return;SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);
        for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;long t=e.optLong("t");JSONObject n=i+1<log.length()?log.optJSONObject(i+1):null;long next=n==null?0:n.optLong("t");
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(S1),0,ui.dp(S1));TextView time=ui.text(f.format(new Date(t)),Type.BODY_SMALL,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");r.addView(time,new LinearLayout.LayoutParams(ui.dp(64),-2));
            TextView m=ui.text(inDetail(e.optString("m"))+(next>0&&next-t>=1000?"  ("+Recording.time(next-t)+")":""),Type.BODY_SMALL,p.onSurface);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));list.addView(r);}
    }
    /** Vidrio como las demás tarjetas; el error se distingue por el círculo rojo con la alerta, sin teñir toda la tarjeta. */
    private void showFailed(JSONObject st){
        // 0.9.1: si se rindió porque OpenRouter estuvo caído (Transcriber.outcome, "lastErrorKind"), se dice así y se ofrece
        // probar con otro modelo: en Ajustes no hay nada que revisar.
        boolean server="server".equals(st.optString("lastErrorKind"));List<Models.Model> others=server?otherModels(st):Collections.emptyList();
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S4));
        card.addView(ui.tile(R.drawable.ic_alert,p.onErrorContainer,p.errorContainer,44,24));
        TextView h=ui.heading(getString(server?R.string.detail_server_title:R.string.detail_failed_title),Type.TITLE_LARGE);h.setPadding(0,ui.dp(S4),0,ui.dp(S1));card.addView(h);
        card.addView(ui.text(st.optString("status",getString(R.string.detail_failed_title)),Type.BODY_MEDIUM,p.onSurface));
        TextView hint=ui.text(getString(server?R.string.detail_server_body:R.string.detail_failed_hint),Type.BODY_SMALL,p.onSurfaceVariant);hint.setPadding(0,ui.dp(S2),0,0);card.addView(hint);
        if(Retranscribe.hasPrevious(this,id))card.addView(ui.button(getString(R.string.detail_restore_previous),R.drawable.ic_refresh,Ui.Style.TONAL,v->restorePrevious()),ui.top(S4));
        if(!others.isEmpty())card.addView(ui.button(getString(R.string.detail_try_other_model),R.drawable.ic_sparkle,Ui.Style.TONAL,v->otherModelSheet(st)),ui.top(S4));
        if(!server)card.addView(ui.button(getString(R.string.detail_check_settings),0,Ui.Style.PLAIN,v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))),ui.top(S1));
        content.addView(card,gap());content.addView(details(st,false));
    }
    /**
     * 0.9.1: «Probar con otro modelo». Lo que falta se envía con el modelo elegido; las partes ya listas se conservan y no se
     * vuelven a cobrar (Pipeline.request con fallback). Solo modelos que «Automático» podría usar y que aceptan las partes
     * ya cortadas (Models.fallbacks).
     */
    private void otherModelSheet(JSONObject st){
        List<Models.Model> list=otherModels(st);
        if(list.isEmpty()){message(getString(R.string.detail_other_model_title),getString(R.string.detail_other_model_none));return;}
        Sheet s=sheet(getString(R.string.detail_other_model_title),getString(R.string.detail_other_model_body));
        for(Models.Model m:list)s.option(R.drawable.ic_sparkle,Models.label(m),m.perHour>0?getString(R.string.detail_other_model_price,SettingsActivity.hourPrice(m.perHour)):null,()->useModel(st,m));
        s.secondary(getString(R.string.common_cancel),null).show();
    }
    /** Los modelos que sirven para seguir esta transcripción: sin el que falló y aceptando la parte más larga ya cortada. */
    private List<Models.Model> otherModels(JSONObject st){
        String failing=st.optString("fallbackModel","");if(failing.isEmpty())failing=st.optString("model","");
        return Models.fallbacks(this,speakersOf(st),failing,maxPartMs(st));
    }
    private boolean speakersOf(JSONObject st){return st.has("speakers")?st.optBoolean("speakers"):new Settings(this).defaultSpeakers();}
    /** La parte más larga ya cortada, en ms (los cortes, "cuts", se reutilizan al seguir); 0 si aún no hay cortes. */
    static long maxPartMs(JSONObject st){JSONArray cuts=st==null?null:st.optJSONArray("cuts");long max=0;if(cuts!=null)for(int i=0;i+1<cuts.length();i++)max=Math.max(max,cuts.optLong(i+1)-cuts.optLong(i));return max;}
    private void useModel(JSONObject st,Models.Model m){
        boolean speakers=speakersOf(st);
        Consent.ensure(this,()->{
            RecordingActions.askNotifications(this);
            try{Pipeline.request(this,id,speakers,true,m.id);Diagnostics.event("ui_action",id,"screen","RecordingActivity","action","other_model");
                Ui.haptic(getWindow().getDecorView(),Ui.Haptic.CONFIRM);reload();toast(getString(R.string.detail_other_model_started,Models.label(m)));}
            catch(Exception e){message(getString(R.string.act_queue_failed),e instanceof HttpApi.UserAction?e.getMessage():getString(R.string.act_try_again));}
        });
    }
    private void restorePrevious(){
        try{Retranscribe.restorePrevious(this,id);Diagnostics.event("retranscribe_restored",id);reload();snackbar(getString(R.string.retr_restored),null,null);}
        catch(Exception e){message(getString(R.string.detail_previous_title),getString(R.string.detail_previous_failed));}
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
            if(diarized&&!names.isEmpty())content.addView(ficha(names),gap());
            if(demo)content.addView(note(R.drawable.ic_info,getString(R.string.detail_demo_note)));
            // Aviso honesto: la separación automática puede equivocarse; se oculta cuando el usuario ya revisó las voces.
            if(diarized&&!transcript.reviewed()&&segments.length()>0)content.addView(note(R.drawable.ic_info,getString(R.string.detail_auto_voices_note)));
            else if(!diarized&&transcript.data.optInt("parts",1)>1)content.addView(note(R.drawable.ic_info,getString(R.string.detail_parts_note)));
            if(!demo&&segments.length()>0)content.addView(noteCard(st,names));
            addMarks();
            // La transcripción es una tarjeta de vidrio con poco margen: las intervenciones van casi a todo el ancho (se leen
            // mejor) y el resaltado de la que suena queda con esquinas concéntricas a las de la tarjeta.
            LinearLayout doc=ui.card();doc.setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(S2));
            LinearLayout head=cardHeader(doc,R.drawable.ic_transcribe,getString(R.string.detail_transcript),null);head.setPadding(ui.dp(S3),0,ui.dp(S3),ui.dp(S1));
            if(onlySpeaker!=null){
                // Filtro activo en una franja menta, con la salida a mano.
                LinearLayout f=ui.row();f.setBackground(shape(this,p.primaryContainer,R_FULL));f.setPadding(ui.dp(S4),0,ui.dp(S1),0);f.setMinimumHeight(ui.dp(48));
                View dot=new View(this);Integer only=colors.get(onlySpeaker);dot.setBackground(oval(only==null?p.primary:only));f.addView(dot,new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8)));f.addView(ui.space(S2));
                // Sin cortar: un nombre largo baja a una segunda línea (la franja crece) en vez de perderse con «…».
                f.addView(ui.text(getString(R.string.detail_only_showing,names.get(onlySpeaker)),Type.LABEL_LARGE,p.onPrimaryContainer),new LinearLayout.LayoutParams(0,-2,1));
                f.addView(ui.button(getString(R.string.detail_show_all),0,Ui.Style.PLAIN,v->{onlySpeaker=null;reload(true);}));
                LinearLayout.LayoutParams fl=Ui.fill();fl.setMargins(ui.dp(S2),ui.dp(S1),ui.dp(S2),ui.dp(S2));doc.addView(f,fl);}
            if(segments.length()==0){TextView none=ui.text(getString(R.string.export_no_speech),Type.BODY_LARGE,p.onSurfaceVariant);none.setPadding(ui.dp(S3),ui.dp(S2),ui.dp(S3),ui.dp(S3));doc.addView(none);}
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
            content.addView(doc,gap());
            // Pie centrado y discreto, en Outfit: cierra el documento.
            if(!demo){String foot=footer(st);if(!foot.isEmpty()){TextView t=Ui.tabular(ui.text(foot,Type.LABEL_MEDIUM,p.onSurfaceVariant));t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(S4),ui.dp(S5),ui.dp(S4),0);content.addView(t,Ui.fill());}
                if(st.has("log"))content.addView(details(st,false));}
            updateStrip(segments,diarized);
        }catch(Exception e){content.addView(ui.text(getString(R.string.detail_transcript_unreadable),Type.BODY_LARGE,p.error));}
    }
    /**
     * Pie en palabras simples: «Transcrito el 23 sept · tardó 2 min · ≈ US$0,02». El modelo queda en los detalles.
     * Con el costo real del proveedor (estado "costUsd", 0.8.0) va sin «≈»: es lo que se cobró, no un estimado.
     */
    private String footer(JSONObject st){
        List<String> parts=new ArrayList<>();long at=transcribedAt(st);if(at>0)parts.add(getString(R.string.detail_footer_transcribed,dayLabel(at)));
        long took=st.optLong("doneIn");if(took>0)parts.add(getString(R.string.detail_footer_took,Ui.humanDuration(took)));
        double real=Pricing.real(st),cost=real>=0?real:Pricing.estimate(this,transcript.data.optString("provider","openai"),transcript.data.optString("model"),recording.duration);
        if(cost>=0&&took>0)parts.add((real>=0?"":"≈ ")+Pricing.usd(cost));
        return TextUtils.join(" · ",parts);
    }
    private long transcribedAt(JSONObject st){
        JSONArray log=st.optJSONArray("log");if(log!=null)for(int i=log.length()-1;i>=0;i--){JSONObject e=log.optJSONObject(i);if(e!=null&&StatusText.transcriptionDone(e.optString("m")))return e.optLong("t");}
        long q=st.optLong("queuedAt"),d=st.optLong("doneIn");if(q>0&&d>0)return q+d;
        java.io.File f=FilesStore.file(this,id,".transcript.json");return f.isFile()?f.lastModified():0;
    }
    /**
     * Ficha en una tarjeta de vidrio: arriba «Personas» y si las voces ya están revisadas; al medio, una barra con el
     * reparto del tiempo de habla (un tramo por persona, en su color); abajo, cada persona como píldora con su punto y su %.
     * La persona que filtra la transcripción («Ver solo sus intervenciones») va en tinta, como un filtro elegido.
     */
    private View ficha(Map<String,String> names)throws JSONException{
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S3));
        String people=getString(R.string.detail_people);LinearLayout head=cardHeader(card,R.drawable.ic_people,people,null);
        boolean reviewed=transcript.reviewed();String state=getString(reviewed?R.string.voices_reviewed:R.string.detail_voices_unreviewed);
        TextView status=chip(state,reviewed?R.drawable.ic_check:R.drawable.ic_voice,p.primary,reviewed?p.onSurfaceVariant:p.onPrimaryContainer,reviewed?0:p.primaryContainer,reviewed);
        status.setContentDescription(getString(R.string.detail_voices_status_desc,state));status.setOnClickListener(v->openNameVoices());
        // El estado va junto al título solo si los dos caben enteros. Con letra grande le quitaba el ancho al título y partía
        // «Personas»: en ese caso va al final de la fila de personas, como en 0.6.
        android.text.TextPaint probe=new android.text.TextPaint();probe.setTypeface(AppTheme.font(this,Type.TITLE_MEDIUM));probe.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,Type.TITLE_MEDIUM.size,getResources().getDisplayMetrics()));
        status.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
        boolean beside=probe.measureText(people)+status.getMeasuredWidth()+ui.dp(S2)<=getResources().getDisplayMetrics().widthPixels-ui.dp(2*S4+2*S4+20+S3);
        if(beside)head.addView(status,Ui.wrap());
        Map<String,Double> share=transcript.talkShare();
        float[] parts=new float[names.size()];int[] tones=new int[names.size()];int n=0;
        for(String key:names.keySet()){Double v=share.get(key);parts[n]=v==null?0f:v.floatValue();tones[n]=colors.get(key);n++;}
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,ui.dp(8));bl.topMargin=ui.dp(S2);card.addView(new ShareBar(this,parts,tones),bl);
        // Las píldoras se desplazan de borde a borde de la tarjeta (sin cortarse en su margen).
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout chips=ui.row();chips.setPadding(ui.dp(S4),0,ui.dp(S2),0);hs.addView(chips);
        for(String key:names.keySet()){int color=colors.get(key);boolean only=key.equals(onlySpeaker);String pct=percent(share.get(key));
            LinearLayout c=ui.row();c.setBackground(pillBackground(only?shape(this,p.ink,R_FULL):outline(this,chipFill(),p.outlineVariant,R_FULL,false)));c.setPadding(ui.dp(S3),0,ui.dp(S4),0);c.setMinimumHeight(ui.dp(48));
            GradientDrawable mark=oval(color);if(only)mark.setStroke(Math.max(1,ui.dp(1.5f)),p.onInk);View dot=new View(this);dot.setBackground(mark);c.addView(dot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));c.addView(ui.space(S2));
            c.addView(ui.text(names.get(key),Type.LABEL_LARGE,only?p.onInk:p.onSurface));
            if(!pct.isEmpty()){TextView pc=Ui.tabular(ui.text(pct,Type.LABEL_LARGE,only?withAlpha(p.onInk,0xB3):p.onSurfaceVariant));pc.setPadding(ui.dp(6),0,0,0);c.addView(pc);}
            c.setClickable(true);c.setFocusable(true);c.setContentDescription(pct.isEmpty()?getString(R.string.detail_person_desc,names.get(key)):getString(R.string.detail_person_desc_share,names.get(key),pct));c.setAccessibilityDelegate(Ui.buttonRole());c.setOnClickListener(v->personSheet(key));Ui.pressable(c);
            LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));chips.addView(c,lp);}
        if(!beside)chips.addView(status,Ui.wrap());
        LinearLayout.LayoutParams hl=Ui.fill();hl.topMargin=ui.dp(S2);hl.setMarginStart(-ui.dp(S4));hl.setMarginEnd(-ui.dp(S4));card.addView(hs,hl);
        return card;
    }
    /** «46 %», «<1 %» («46%» en inglés y portugués). */
    private String percent(Double share){if(share==null)return "";long r=Math.round(share*100);return r<1?getString(R.string.detail_percent_less,1):getString(R.string.detail_percent,r);}
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
    /** Aviso en menta (lo que destaca): ícono verde y texto en el verde profundo del contenedor. */
    private View note(int icon,String value){
        LinearLayout n=ui.row();n.setGravity(Gravity.TOP);n.setBackground(shape(this,p.primaryContainer,R_CONTROL+S1));n.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        ImageView i=ui.icon(icon,p.primary,18);((LinearLayout.LayoutParams)i.getLayoutParams()).topMargin=ui.dp(1);n.addView(i);n.addView(ui.space(S3));
        n.addView(ui.text(value,Type.BODY_MEDIUM,p.onPrimaryContainer),new LinearLayout.LayoutParams(0,-2,1));n.setLayoutParams(gap());return n;
    }
    /**
     * Encabezado de tarjeta: ícono verde de 20 dp + título en Outfit (como «Your Weekly Activity» en la referencia).
     * Devuelve la fila, para agregar acciones a la derecha.
     */
    private LinearLayout cardHeader(LinearLayout card,int icon,String title,String subtitle){return cardHeader(card,ui.icon(icon,p.primary,20),title,subtitle);}
    private LinearLayout cardHeader(LinearLayout card,View mark,String title,String subtitle){
        LinearLayout head=ui.row();head.setMinimumHeight(ui.dp(48));
        mark.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));head.addView(mark);head.addView(ui.space(S3));
        // Cortes de línea equilibrados: «Nota para tu / segundo cerebro» en vez de dejar una palabra sola abajo.
        LinearLayout titles=ui.column();TextView t=ui.heading(title,Type.TITLE_MEDIUM);t.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);titles.addView(t);
        if(subtitle!=null){TextView s=ui.text(subtitle,Type.BODY_SMALL,p.onSurfaceVariant);s.setPadding(0,ui.dp(2),0,0);titles.addView(s);}
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));card.addView(head,Ui.fill());return head;
    }
    /** Círculo menta con el destello ✦ verde de la marca (lo que la IA hará o hizo). */
    private FrameLayout sparkTile(int sizeDp){
        FrameLayout f=new FrameLayout(this);f.setBackground(oval(p.primaryContainer));f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int icon=Math.round(sizeDp*0.5f);f.addView(new Spark(this,p.primary),new FrameLayout.LayoutParams(ui.dp(icon),ui.dp(icon),Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(sizeDp),ui.dp(sizeDp)));return f;
    }
    /** Relleno de una píldora sobre vidrio: blanco con alfa en claro (como los datos de «Tu semana»), vidrio en oscuro. */
    private int chipFill(){return p.dark?p.glass:0xB3FFFFFF;}
    /** Píldora de 36 dp visibles dentro de un área táctil de 48 dp. */
    private Drawable pillBackground(GradientDrawable fill){int v=ui.dp(6);return new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(fill,0,v,0,v),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,v,0,v));}
    /** Píldora tocable (como ui.chip / ui.outlinedChip, pero con 48 dp de área táctil): de vidrio con borde, o rellena con bg. */
    private TextView chip(String text,int icon,int iconColor,int fg,int bg,boolean outlined){
        TextView t=ui.oneLine(ui.text(text,Type.LABEL_LARGE,fg));t.setGravity(Gravity.CENTER_VERTICAL);t.setMinHeight(ui.dp(48));
        t.setBackground(pillBackground(outlined?outline(this,chipFill(),p.outlineVariant,R_FULL,false):shape(this,bg,R_FULL)));t.setPadding(ui.dp(icon!=0?S3:S4),0,ui.dp(S4),0); // después del fondo: un fondo con márgenes reemplaza el padding
        if(icon!=0){Drawable d=getDrawable(icon).mutate();d.setTint(iconColor);d.setBounds(0,0,ui.dp(18),ui.dp(18));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(ui.dp(6));}
        t.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(t);return t;
    }

    // ---------- Momentos ★ ----------
    private long[] markTimes(){JSONArray m=Marks.list(this,id);long[] out=new long[m.length()];for(int i=0;i<m.length();i++){JSONObject o=m.optJSONObject(i);out[i]=o==null?0:o.optLong("t");}Arrays.sort(out);return out;}
    private boolean hasMark(double start,double end){long a=(long)(start*1000)-500,b=(long)(end*1000)+500;for(long m:marksMs)if(m>=a&&m<=b)return true;return false;}
    /**
     * «Momentos ★» en una tarjeta de vidrio: cada ★ es una píldora menta con su estrella verde y la hora (toca para
     * escuchar, mantén para nombrarlo). La línea de ayuda hace visible el toque largo, que antes solo anunciaba TalkBack.
     */
    private View marksRow(){
        if(demo||marksMs.length==0)return null;JSONArray marks=Marks.list(this,id);
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S3));
        cardHeader(card,R.drawable.ic_star_fill,getString(R.string.detail_marks),getString(R.string.detail_marks_hint)); // la ★ del nombre es el ícono
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout row=ui.row();row.setPadding(ui.dp(S4),0,ui.dp(S2),0);hs.addView(row);
        for(int i=0;i<marks.length();i++){JSONObject m=marks.optJSONObject(i);if(m==null)continue;long t=m.optLong("t");String label=m.optString("label","").trim();int index=i;
            TextView c=Ui.tabular(chip(Recording.time(t)+(label.isEmpty()?"":" · "+label),R.drawable.ic_star_fill,p.primary,p.onPrimaryContainer,p.primaryContainer,false));
            c.setContentDescription(label.isEmpty()?getString(R.string.detail_mark_desc,Recording.time(t)):getString(R.string.detail_mark_desc_label,Recording.time(t),label));
            c.setOnClickListener(v->{Diagnostics.event("mark_played",id);playAt(t);});c.setOnLongClickListener(v->{markSheet(index,t,label);return true;});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));row.addView(c,lp);}
        LinearLayout.LayoutParams hl=Ui.fill();hl.topMargin=ui.dp(S1);hl.setMarginStart(-ui.dp(S4));hl.setMarginEnd(-ui.dp(S4));card.addView(hs,hl);return card;
    }
    private void markSheet(int index,long t,String label){
        Sheet s=sheet(getString(R.string.detail_mark_title,Recording.time(t)),getString(R.string.detail_mark_body));
        String name=getString(R.string.detail_mark_name),mark=getString(R.string.detail_mark);
        EditText f=ui.field(name,name);f.setText(label);f.setSingleLine(true);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});s.add(f);
        s.primary(getString(R.string.detail_save),Ui.Style.PRIMARY,()->{try{Marks.rename(this,id,index,f.getText().toString().trim());Diagnostics.event("mark_renamed",id);reload();}catch(Exception e){message(mark,getString(R.string.detail_mark_name_failed));}return true;}).secondary(getString(R.string.common_cancel),null);
        s.action(R.drawable.ic_trash,getString(R.string.detail_mark_delete),true,()->{try{Marks.remove(this,id,index);Diagnostics.event("mark_removed",id);reload();
            snackbar(getString(R.string.detail_mark_deleted),getString(R.string.detail_undo),()->{try{Marks.add(this,id,t,label);reload();}catch(Exception e){message(mark,getString(R.string.detail_undo_failed));}});}catch(Exception e){message(mark,getString(R.string.detail_mark_delete_failed));}});
        s.show();
    }

    // ---------- Nota para tu segundo cerebro ----------
    /**
     * «Armando la nota…» solo vale si empezó hace poco. Si la app se cerró mientras se armaba, el estado queda en
     * «working» y nadie lo termina: pasado este plazo (la IA espera como máximo 5 min) se ofrece reintentar.
     */
    private static final long NOTE_STALE_MS=7*60_000;
    private final Runnable noteExpired=()->{if(!isDestroyed())reload();};
    private View noteCard(JSONObject st,Map<String,String> names){
        String state=st.optString("noteState","");JSONObject note=Notes.load(this,id);
        long age=Math.abs(System.currentTimeMillis()-st.optLong("noteStartedAt"));
        boolean working="working".equals(state),stale=working&&age>=NOTE_STALE_MS;
        if(noteBusy||working&&!stale){
            // Si nada la termina, al vencer el plazo la tarjeta pasa sola a «Reintentar».
            if(!noteBusy){handler.removeCallbacks(noteExpired);handler.postDelayed(noteExpired,NOTE_STALE_MS-age+1000);}
            return noteSkeleton();
        }
        String error="failed".equals(state)?st.optString("noteError",""):null;
        if(note!=null)return noteFull(note,names,stale?getString(R.string.detail_note_interrupted_short):error);
        if(stale)return noteMessage(false,getString(R.string.detail_note_failed_title),getString(R.string.detail_note_interrupted),getString(R.string.detail_retry),R.drawable.ic_refresh);
        if(error!=null)return noteMessage(false,getString(R.string.detail_note_failed_title),error.isEmpty()?getString(R.string.detail_try_again_soon):error,getString(R.string.detail_retry),R.drawable.ic_refresh);
        if(Notes.canGenerate(this))return noteMessage(true,null,getString(R.string.detail_note_pitch),getString(R.string.detail_note_make),R.drawable.ic_sparkle);
        // Sin IA para la nota: una pista corta que lleva directo a configurarla.
        LinearLayout box=ui.row();TextView hint=chip(getString(R.string.detail_note_setup),R.drawable.ic_sparkle,p.primary,p.onSurface,0,true);
        hint.setOnClickListener(v->openNoteAi());box.addView(hint);box.setLayoutParams(gap());return box;
    }
    /** Ajustes con la hoja «IA de la nota» abierta; Atrás vuelve aquí. */
    private void openNoteAi(){startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true).putExtra("noteAi",true));}
    /** Título de la nota con el destello ✦ verde del logo: lo que armó la IA lleva la misma chispa que la marca. */
    private LinearLayout noteHeader(LinearLayout card,String subtitle){return noteHeader(card,subtitle,null);}
    /** 0.9.5: «Resumen de la sesión» si la nota (o, mientras se arma, Ajustes) es de «Sesión con cliente». */
    private LinearLayout noteHeader(LinearLayout card,String subtitle,JSONObject note){
        String kind=note!=null?note.optString("kind",Notes.BRAIN):new Settings(this).noteKind();
        return cardHeader(card,new Spark(this,p.primary),getString(Notes.CLIENT.equals(kind)?R.string.detail_note_client_title:R.string.detail_note_title),subtitle);
    }
    private LinearLayout noteSurface(){LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S2),ui.dp(S4));card.setLayoutParams(gap());return card;}
    private View noteSkeleton(){
        LinearLayout card=noteSurface();noteHeader(card,null);
        TextView w=ui.text(getString(R.string.detail_note_working),Type.BODY_MEDIUM,p.onSurfaceVariant);w.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);w.setPadding(0,0,0,ui.dp(S3));card.addView(w);
        // Líneas menta que laten mientras la IA escribe.
        for(float width:new float[]{1f,0.92f,0.7f}){LinearLayout line=ui.row();View bar=new View(this);bar.setBackground(shape(this,p.primaryContainer,R_FULL));line.addView(bar,new LinearLayout.LayoutParams(0,ui.dp(12),width));if(width<1f)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f-width));
            LinearLayout.LayoutParams lp=Ui.fill();lp.bottomMargin=ui.dp(S2);lp.setMarginEnd(ui.dp(S2));card.addView(line,lp);pulse(bar);}
        return card;
    }
    /** Latido suave mientras se arma (respeta «Quitar animaciones» de Android: el animador se detiene solo). */
    private static void pulse(View v){
        ValueAnimator a=ValueAnimator.ofFloat(1f,0.45f);a.setDuration(900);a.setRepeatMode(ValueAnimator.REVERSE);a.setRepeatCount(ValueAnimator.INFINITE);a.addUpdateListener(x->v.setAlpha((float)x.getAnimatedValue()));
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){a.start();}public void onViewDetachedFromWindow(View view){a.cancel();}});
    }
    /**
     * brand: con el encabezado de la nota (✦ y «Nota para tu segundo cerebro»; title no se usa). Si no, algo falló: title
     * va con la alerta roja. Antes se decidía por si el título empezaba con «Nota», que no sirve en otros idiomas.
     */
    private View noteMessage(boolean brand,String title,String text,String action,int icon){
        LinearLayout card=noteSurface();
        if(brand)noteHeader(card,null);else cardHeader(card,ui.icon(R.drawable.ic_alert,p.error,20),title,null);
        TextView d=ui.text(text,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,0,ui.dp(S2),0);card.addView(d);
        LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S3);card.addView(ui.button(action,icon,Ui.Style.TONAL,v->generateNote()),lp);
        return card;
    }
    private View noteFull(JSONObject note,Map<String,String> names,String error){
        Map<String,String> who=noteNames(note,names);
        LinearLayout card=noteSurface();LinearLayout head=noteHeader(card,getString(R.string.detail_note_ai),note);
        ImageButton more=ui.iconButton(R.drawable.ic_more,getString(R.string.detail_note_options),p.onSurfaceVariant,0,48);more.setOnClickListener(v->noteMenu());head.addView(more);
        ImageButton fold=ui.iconButton(R.drawable.ic_chevron_down,getString(R.string.detail_note_collapse),p.onSurfaceVariant,0,48);head.addView(fold);
        LinearLayout body=ui.column();body.setPadding(0,0,ui.dp(S2),0);
        String summary=Notes.resolve(note.optString("summary",""),who).trim();
        if(!summary.isEmpty()){TextView s=ui.text(summary,Type.BODY_LARGE,p.onSurface);s.setLineSpacing(ui.dp(3),1f);s.setTextIsSelectable(true);s.setPadding(0,ui.dp(S1),0,0);body.addView(s,Ui.fill());}
        // 0.9.5, «Sesión con cliente»: lo que contó el cliente, lo que se trabajó, acuerdos, próximos pasos y puntos a vigilar.
        for(int k=0;k<Notes.CLIENT_LISTS.length;k++)bullets(body,getString(Notes.CLIENT_TITLES[k]),note.optJSONArray(Notes.CLIENT_LISTS[k]),who);
        bullets(body,getString(R.string.detail_note_decisions),note.optJSONArray("decisions"),who);
        tasks(body,note.optJSONArray("tasks"),who);
        quotes(body,note.optJSONArray("quotes"),who);
        // Etiquetas como píldoras menta que se reparten en líneas como palabras (nunca se cortan por la mitad).
        JSONArray tags=note.optJSONArray("tags");if(tags!=null&&tags.length()>0){SpannableStringBuilder b=new SpannableStringBuilder();
            for(int i=0;i<tags.length();i++){String tag=tags.optString(i).trim().replaceFirst("^#","");if(tag.isEmpty())continue;if(b.length()>0)b.append("  ");int from=b.length();b.append('#').append(tag);b.setSpan(new TagSpan(p.primaryContainer,p.onPrimaryContainer,ui.dp(10),ui.dp(5),getResources().getDisplayMetrics().widthPixels-ui.dp(72)),from,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            if(b.length()>0){TextView t=ui.text("",Type.LABEL_MEDIUM,p.onPrimaryContainer);t.setText(b);t.setLineSpacing(ui.dp(S2),1f);t.setPadding(0,ui.dp(S4),0,0);body.addView(t,Ui.fill());}}
        if(error!=null&&!error.isEmpty()){TextView e=ui.text(getString(R.string.detail_note_rewrite_failed,error),Type.BODY_SMALL,p.error);e.setPadding(0,ui.dp(S3),0,0);body.addView(e);}
        // Con qué IA se armó (0.8.0): el modelo que respondió de verdad (con OpenRouter se pide un alias «el más nuevo» y así
        // se sabe qué versión fue) y lo que costó. Discreto, al pie de la nota.
        String credit=Notes.credit(note);
        if(!credit.isEmpty()){TextView made=Ui.tabular(ui.text(credit,Type.BODY_SMALL,p.onSurfaceVariant));made.setPadding(0,ui.dp(S4),0,0);body.addView(made,Ui.fill());}
        card.addView(body,Ui.fill());
        TextView collapsed=ui.text(summary.isEmpty()?getString(R.string.detail_note_tap):summary,Type.BODY_MEDIUM,p.onSurfaceVariant);collapsed.setMaxLines(2);collapsed.setEllipsize(TextUtils.TruncateAt.END);collapsed.setPadding(0,0,ui.dp(S2),0);card.addView(collapsed);
        // ⌄ para abrir y ⌃ para cerrar; al tocarla, la flecha gira (sin animación si están quitadas).
        Runnable apply=()->{boolean open=prefs.getBoolean("noteOpen",true);body.setVisibility(open?View.VISIBLE:View.GONE);collapsed.setVisibility(open?View.GONE:View.VISIBLE);
            float angle=open?180f:0f;if(AppTheme.motion()&&fold.isAttachedToWindow())fold.animate().rotation(angle).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED).start();else fold.setRotation(angle);
            fold.setContentDescription(getString(open?R.string.detail_note_collapse:R.string.detail_note_expand));};
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
    /** Subtítulo de sección de la nota: verde, en Outfit (como los subtítulos de Ajustes). */
    private TextView noteLabel(LinearLayout body,String title){TextView t=ui.text(title,Type.TITLE_SMALL,p.primary);t.setPadding(0,ui.dp(S5),0,ui.dp(S1));if(Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);body.addView(t);return t;}
    private void bullets(LinearLayout body,String title,JSONArray items,Map<String,String> who){
        if(items==null||items.length()==0)return;noteLabel(body,title);
        for(int i=0;i<items.length();i++){String text=Notes.resolve(items.optString(i),who).trim();if(text.isEmpty())continue;
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(2),0,ui.dp(2));TextView dot=ui.text("•",Type.BODY_MEDIUM,p.primary);dot.setPadding(ui.dp(S1),0,ui.dp(S2),0);r.addView(dot);
            TextView t=ui.text(text,Type.BODY_MEDIUM,p.onSurface);t.setTextIsSelectable(true);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));body.addView(r,Ui.fill());}
    }
    private void tasks(LinearLayout body,JSONArray tasks,Map<String,String> who){
        if(tasks==null||tasks.length()==0)return;noteLabel(body,getString(R.string.detail_note_tasks));
        for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t==null)continue;int index=i;
            String text=Notes.resolve(t.optString("text"),who).trim(),person=whoName(t.optString("who",""),who),when=t.optString("when","").trim();if(text.isEmpty())continue;
            // Casilla redonda: anillo gris vacío; al marcarla se llena de verde con ✓ blanco. Sigue siendo un CheckBox (TalkBack igual).
            CheckBox cb=new CheckBox(this);cb.setButtonDrawable(new RoundCheck(this,p.primary,p.onPrimary,p.outline));cb.setChecked(t.optBoolean("done"));
            AppTheme.type(cb,Type.BODY_MEDIUM);cb.setMinHeight(ui.dp(48));cb.setPadding(0,ui.dp(S1),0,ui.dp(S1));
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
        catch(Exception e){suppressTask=true;b.setChecked(!on);suppressTask=false;message(getString(R.string.detail_note),getString(R.string.detail_task_failed));}
    }
    private void quotes(LinearLayout body,JSONArray quotes,Map<String,String> who){
        if(quotes==null||quotes.length()==0)return;noteLabel(body,getString(R.string.detail_note_quotes));
        for(int i=0;i<quotes.length();i++){JSONObject q=quotes.optJSONObject(i);if(q==null)continue;String text=Notes.resolve(q.optString("text"),who).trim();if(text.isEmpty())continue;
            long ms=(long)(q.optDouble("t",0)*1000);String person=whoName(q.optString("who",""),who);
            // Cita con una barra verde a la izquierda; debajo, ▶ hora (toca para escuchar ahí) y quién la dijo.
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);
            View rule=new View(this);rule.setBackground(shape(this,p.primary,R_FULL));LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(ui.dp(3),-1);rl.topMargin=ui.dp(S1);rl.bottomMargin=ui.dp(S1);r.addView(rule,rl);
            // Sin recorte: la onda del toque de la hora ocupa los 4 dp que el margen negativo le quita (así ▶ se alinea con la cita).
            LinearLayout col=ui.column();col.setPadding(ui.dp(S3),0,0,0);col.setClipToPadding(false);
            TextView t=ui.text(getString(R.string.detail_quoted,text),Type.BODY_MEDIUM,p.onSurface);t.setLineSpacing(ui.dp(2),1f);t.setPadding(0,ui.dp(S1),0,0);t.setTextIsSelectable(true);col.addView(t,Ui.fill());
            LinearLayout by=ui.row();by.setClipChildren(false);
            TextView time=Ui.tabular(ui.text(Recording.time(ms),Type.LABEL_LARGE,p.primary));time.setGravity(Gravity.CENTER_VERTICAL);time.setMinHeight(ui.dp(48));time.setPadding(ui.dp(S1),0,ui.dp(S2),0);
            Drawable go=getDrawable(R.drawable.ic_play).mutate();go.setTint(p.primary);go.setBounds(0,0,ui.dp(16),ui.dp(16));time.setCompoundDrawablesRelative(go,null,null,null);time.setCompoundDrawablePadding(ui.dp(2));
            time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription(getString(R.string.detail_listen_from,Recording.time(ms)));time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->playAt(ms));
            LinearLayout.LayoutParams tl=Ui.wrap();tl.setMarginStart(-ui.dp(S1));by.addView(time,tl);
            if(!person.isEmpty())by.addView(ui.oneLine(ui.text("· "+person,Type.LABEL_MEDIUM,p.onSurfaceVariant)),new LinearLayout.LayoutParams(0,-2,1));
            col.addView(by,Ui.fill());r.addView(col,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams ql=Ui.fill();ql.topMargin=ui.dp(S2);body.addView(r,ql);}
    }
    private void noteMenu(){
        sheet(getString(R.string.detail_note_title),null)
            .action(R.drawable.ic_refresh,getString(R.string.detail_note_rewrite),false,()->confirm(getString(R.string.detail_note_rewrite_q),getString(R.string.detail_note_rewrite_body),getString(R.string.detail_note_rewrite_action),false,this::generateNote))
            .action(R.drawable.ic_copy,getString(R.string.detail_note_copy),false,this::copyNote)
            .action(R.drawable.ic_share,getString(R.string.detail_share_note_md),false,this::shareNote)
            .show();
    }
    /** Arma la nota en segundo plano; mientras tanto se ve «Armando la nota…». */
    private void generateNote(){
        if(noteBusy||demo)return;
        // 0.8.0: la nota se arma con OpenRouter, con la misma clave con que se transcribe.
        if(!Notes.canGenerate(this)){sheet(getString(R.string.detail_note_nokey_title),getString(R.string.detail_note_nokey_body)).primary(getString(R.string.detail_go_settings),this::openNoteAi).secondary(getString(R.string.detail_not_now),null).show();return;}
        noteBusy=true;reload();Diagnostics.event("note_requested",id);
        Context app=getApplicationContext();Recording r=recording;
        new Thread(()->{String error=null;
            try{Notes.generate(app,r,new HttpApi());}
            catch(UnsupportedOperationException e){error=getString(R.string.detail_note_unavailable);}
            catch(HttpApi.UserAction e){error=e.getMessage();}
            catch(Exception e){error=getString(R.string.detail_note_failed_net);}
            String failed=error;
            runOnUiThread(()->{noteBusy=false;if(isDestroyed())return;reload();
                if(failed!=null){Ui.haptic(content,Ui.Haptic.REJECT);message(getString(R.string.detail_note_title),failed);}else Ui.haptic(content,Ui.Haptic.CONFIRM);});
        },"note").start();
    }
    private void copyNote(){try{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.detail_note),Notes.markdown(this,recording)));if(Build.VERSION.SDK_INT<33)toast(getString(R.string.detail_note_copied));}catch(Exception e){message(getString(R.string.detail_note_copy),getString(R.string.detail_note_copy_failed));}}
    /** Comparte la nota en Markdown como texto (Obsidian y la mayoría de las apps de notas la reciben así). */
    private void shareNote(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,recording.title).putExtra(Intent.EXTRA_TEXT,Notes.markdown(this,recording)),getString(R.string.detail_note_share)));}catch(Exception e){message(getString(R.string.detail_note_share),getString(R.string.detail_note_share_failed));}}

    // ---------- Exportar y guardar ----------
    private String exportText()throws Exception{if(demo)return transcript.text(recording);Recording r=FilesStore.recording(this,id);return Transcript.load(this,id).text(r==null?recording:r);}
    private void copy(){try{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.detail_transcript),exportText()));if(Build.VERSION.SDK_INT<33)toast(getString(R.string.detail_text_copied));}catch(Exception e){message(getString(R.string.detail_copy),getString(R.string.detail_copy_failed));}}
    private void shareText(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,recording.title).putExtra(Intent.EXTRA_TEXT,exportText()),getString(R.string.detail_share_transcript)));}catch(Exception e){message(getString(R.string.detail_share),getString(R.string.detail_share_failed));}}
    private void shareTxt(){try{Transcript t=demo?transcript:Transcript.load(this,id);Uri uri=TranscriptExport.create(this,recording,t);startActivity(Intent.createChooser(TranscriptExport.shareIntent(uri),getString(R.string.detail_share_txt)));}catch(Exception e){message(getString(R.string.detail_file),getString(R.string.detail_file_failed));}}
    /**
     * Guardar en 0-Inbox (carpeta rápida): la nota .md o el .txt, con un toque. «Actualizar» reemplaza el mismo archivo.
     * Mientras guarda, el botón queda ocupado; al terminar vibra y lo confirma.
     */
    private void inboxSave(){
        if(demo||saving||transcript==null)return;
        if(!Inbox.configured(this)){RecordingActions.chooseFolder(this);return;}
        saving=true;refreshPrimary();String folder=Inbox.folderName(this);Recording r=recording;Context app=getApplicationContext();
        new Thread(()->{Exception error=null;
            try{try{Inbox.save(app,r);}catch(UnsupportedOperationException notYet){legacyQuickSave(r);}Diagnostics.event("transcript_quick_saved",r.id);}
            catch(Exception e){error=e;Diagnostics.event("transcript_quick_save_failed",r.id,"error_class",e.getClass().getSimpleName());}
            Exception failed=error;
            runOnUiThread(()->{saving=false;if(isDestroyed())return;refreshPrimary();
                if(failed==null){Ui.haptic(primary,Ui.Haptic.CONFIRM);snackbar(getString(R.string.detail_saved_in,folder),null,null);}
                else{Ui.haptic(primary,Ui.Haptic.REJECT);
                    sheet(getString(R.string.detail_save_failed_in,folder),failed instanceof HttpApi.UserAction?failed.getMessage():getString(R.string.detail_save_failed_body))
                        .primary(getString(R.string.detail_choose_folder_again),()->RecordingActions.chooseFolder(this)).secondary(getString(R.string.detail_other_folder),this::saveAs).show();}});
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
        startActivityForResult(pick,SAVE_AS);}catch(ActivityNotFoundException e){message(getString(R.string.detail_save_to),getString(R.string.detail_no_picker));}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SAVE_AS&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri target=data.getData();new Settings(this).prefs.edit().putString("lastSaveUri",target.toString()).apply();boolean note=saveAsNote;
            new Thread(()->{try{LocalStorage.writeText(this,target,note?Notes.markdown(this,recording):exportText());Diagnostics.event("transcript_saved_as",id);runOnUiThread(()->{Ui.haptic(content,Ui.Haptic.CONFIRM);toast(getString(note?R.string.detail_note_saved:R.string.detail_transcript_saved));});}
                catch(Exception e){Diagnostics.event("transcript_save_as_failed",id,"error_class",e.getClass().getSimpleName());runOnUiThread(()->message(getString(R.string.detail_save_to),getString(R.string.detail_write_failed)));}}).start();}
    }

    // ---------- Intervenciones y corrección de voces ----------
    /** Una intervención: nombre (toca para corregir quién habla), ★ si hay un momento marcado y la hora (toca para escuchar ahí mismo). */
    private View turn(int a,int b,String speaker,double start,double end,Map<String,String> names,boolean diarized,boolean first)throws JSONException{
        // Dentro de la tarjeta (4 dp de margen), 12 dp más: nombre, hora y texto quedan alineados con el título «Transcripción».
        // Sin recorte en la fila del nombre: las ondas del nombre y de la hora usan los 4 dp de sus márgenes negativos.
        LinearLayout t=ui.column();t.setPadding(ui.dp(S3),first?0:ui.dp(S1),ui.dp(S3),ui.dp(S3));t.setClipToPadding(false);
        LinearLayout h=ui.row();h.setClipChildren(false);String when=Recording.time((long)(start*1000));
        if(diarized){int color=colors.containsKey(speaker)?colors.get(speaker):p.speaker(transcript.colorIndex(speaker));
            // Nombre en Outfit con su punto de color (el mismo de la ficha); el subrayado de puntos avisa que se puede tocar.
            DottedName name=new DottedName(this,color);name.setText(names.get(speaker));AppTheme.type(name,Type.LABEL_LARGE);name.setTextColor(color);name.setGravity(Gravity.CENTER_VERTICAL);name.setMinHeight(ui.dp(48));
            GradientDrawable dot=oval(color);dot.setSize(ui.dp(8),ui.dp(8));name.setCompoundDrawablesRelativeWithIntrinsicBounds(dot,null,null,null);name.setCompoundDrawablePadding(ui.dp(S2));
            // 4 dp de aire para la onda del toque, compensados con un margen negativo: el punto queda alineado con el texto.
            name.setBackground(ui.ripple(null,R_SMALL));name.setPadding(ui.dp(S1),0,ui.dp(S2),0);name.setContentDescription(getString(R.string.detail_turn_desc,names.get(speaker),when));name.setAccessibilityDelegate(Ui.buttonRole());name.setOnClickListener(v->whoSheet(a,b));
            LinearLayout.LayoutParams nl=Ui.wrap();nl.setMarginStart(-ui.dp(S1));h.addView(name,nl);}
        if(hasMark(start,end)){TextView star=ui.text("★",Type.LABEL_LARGE,p.primary);star.setPadding(ui.dp(S1),0,0,0);star.setContentDescription(getString(R.string.detail_marked));h.addView(star);}
        View flex=new View(this);h.addView(flex,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        TextView time=Ui.tabular(ui.text(when,Type.LABEL_MEDIUM,p.onSurfaceVariant));time.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);time.setMinHeight(ui.dp(48));time.setPadding(ui.dp(S2),0,ui.dp(S1),0);
        if(!demo){time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription(getString(R.string.detail_listen_from,when));time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->playTurn(t,start));}
        LinearLayout.LayoutParams wl=Ui.wrap();wl.setMarginEnd(-ui.dp(S1));h.addView(time,wl);t.addView(h,Ui.fill());
        // El texto sigue en Roboto BODY_LARGE: en párrafos largos se lee mejor que una letra geométrica.
        JSONArray segments=transcript.segments();
        for(int k=a;k<b;k++){if(k>a)t.addView(ui.space(S2));TextView text=ui.text(segments.getJSONObject(k).getString("text").trim(),Type.BODY_LARGE,p.onSurface);text.setTextIsSelectable(true);text.setLineSpacing(ui.dp(4),1f);t.addView(text,Ui.fill());}
        turns.add(new Object[]{t,(long)(start*1000),(long)(end*1000),time,when});
        return t;
    }
    /** Separador discreto donde empieza cada parte (solo al corregir voces). */
    private View blockDivider(int index,double startS){
        LinearLayout r=ui.row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S2));
        View a=new View(this);a.setBackgroundColor(p.outlineVariant);r.addView(a,new LinearLayout.LayoutParams(0,Math.max(1,ui.dp(1)),1));
        // La etiqueta va en una píldora de vidrio entre las dos líneas.
        TextView label=Ui.tabular(ui.text(getString(R.string.detail_part_from,index+1,Recording.time((long)(startS*1000))),Type.LABEL_MEDIUM,p.onSurfaceVariant));label.setBackground(outline(this,chipFill(),p.outlineVariant,R_FULL,false));label.setPadding(ui.dp(S3),ui.dp(S1),ui.dp(S3),ui.dp(S1));
        LinearLayout.LayoutParams ll=Ui.wrap();ll.setMargins(ui.dp(S2),0,ui.dp(S2),0);r.addView(label,ll);
        View b=new View(this);b.setBackgroundColor(p.outlineVariant);r.addView(b,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        return r;
    }
    private static String quote(String text,int max){String t=text.trim().replaceAll("\\s+"," ");return t.length()>max?t.substring(0,max-1).trim()+"…":t;}
    /** Botón "Escuchar" dentro de una hoja: reproduce solo ese tramo y no cierra la hoja. */
    private void listenButton(Sheet s,String label,double from,double to){
        if(demo)return;Ui.Btn listen=ui.button(label,R.drawable.ic_play,Ui.Style.TONAL,null);
        listen.setOnClickListener(v->{if(playUntil>0){stopTramo();listen.setText(label);}else if(playRange(from,to))listen.setText(getString(R.string.detail_stop));});
        tramoEnded=()->listen.setText(label);LinearLayout.LayoutParams lp=Ui.wrap();lp.bottomMargin=ui.dp(S2);s.body.addView(listen,lp);
        s.onDismiss(()->{tramoEnded=null;if(playUntil>0)stopTramo();});
    }
    /** «Nombrar voces» en una sola pasada (hoja de la parte «sheets»); si no está disponible, la hoja clásica. */
    private void openNameVoices(){
        if(transcript==null)return;correctionOpened=true;
        try{nameSheet=NameVoices.show(this,demo?recording.id:id,demo,transcript,()->{if(demo)syncDemo();reload();});}
        catch(RuntimeException e){editSpeakers();}
    }
    /** «¿Quién habla aquí?»: escuchar el tramo y elegir a la persona correcta, o intercambiar dos voces desde aquí. */
    private void whoSheet(int a,int b){try{
        correctionOpened=true;
        JSONArray segs=transcript.segments();JSONObject first=segs.getJSONObject(a),last=segs.getJSONObject(b-1);String current=first.getString("speaker");
        double from=first.getDouble("start"),to=last.optDouble("end",from);Map<String,String> names=transcript.speakers();
        StringBuilder said=new StringBuilder();for(int k=a;k<b&&said.length()<120;k++)said.append(said.length()==0?"":" ").append(segs.getJSONObject(k).getString("text").trim());
        Sheet s=sheet(getString(R.string.detail_who_title),getString(R.string.detail_who_body,Recording.time((long)(from*1000)),Recording.time((long)(to*1000)),quote(said.toString(),90)));
        listenButton(s,getString(R.string.detail_listen_clip),from,to);
        for(String key:names.keySet()){boolean now=key.equals(current);String name=names.get(key);
            s.choice(name,now?getString(R.string.detail_who_current):null,now,p.speaker(transcript.colorIndex(key)),()->applyEdit(t->t.assign(a,b,key),getString(R.string.detail_now_says,name),"reassign"));}
        s.choice(getString(R.string.detail_other_person),getString(R.string.detail_other_person_detail),false,0,()->applyEdit(t->t.assign(a,b,t.newPerson()),getString(R.string.detail_now_new_person),"new_person"));
        List<String> others=new ArrayList<>(names.keySet());others.remove(current);
        if(!others.isEmpty())privateAction(s,R.drawable.ic_swap,getString(R.string.detail_swap_here),others.size()==1?getString(R.string.detail_swap_pair_here,names.get(current),names.get(others.get(0))):getString(R.string.detail_swap_other_here,names.get(current)),()->swapPartner(a,current));
        if(b-a>1)s.action(R.drawable.ic_edit,getString(R.string.detail_fix_one),false,()->pickSentence(a,b));
        s.show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_turn_open_failed));}}
    private void pickSentence(int a,int b){try{
        JSONArray segs=transcript.segments();Sheet s=sheet(getString(R.string.detail_which_sentence),getString(R.string.detail_which_sentence_body));
        for(int k=a;k<b;k++){JSONObject seg=segs.getJSONObject(k);int index=k;s.choice(Recording.time((long)(seg.getDouble("start")*1000))+" · "+quote(seg.getString("text"),70),null,false,()->whoSheet(index,index+1));}
        s.secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_sentences_failed));}}
    private void swapPartner(int a,String x){try{
        Map<String,String> names=transcript.speakers();List<String> others=new ArrayList<>(names.keySet());others.remove(x);
        if(others.size()==1){swapScope(a,x,others.get(0));return;}
        Sheet s=sheet(getString(R.string.detail_swap_with_title,names.get(x)),getString(R.string.detail_swap_with_body));
        for(String y:others)s.choice(names.get(y),null,false,p.speaker(transcript.colorIndex(y)),()->swapScope(a,x,y));
        s.secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.voices_load_failed));}}
    /** Intercambiar dos voces desde este punto: hasta el final o solo hasta el fin de la parte. */
    private void swapScope(int a,String x,String y){try{
        Map<String,String> names=transcript.speakers();String nx=names.get(x),ny=names.get(y);double from=transcript.segments().getJSONObject(a).getDouble("start");String when=Recording.time((long)(from*1000));
        List<Double> blocks=transcript.blockStarts(demo?null:FilesStore.state(this,id));int k=0;for(int i=0;i<blocks.size();i++)if(blocks.get(i)<=from+0.05)k=i;double blockEnd=k+1<blocks.size()?blocks.get(k+1):-1;int blockNo=k+1;
        Sheet s=sheet(getString(R.string.detail_swap_title,nx,ny),getString(R.string.detail_swap_body,when,nx,ny));
        s.option(R.drawable.ic_swap,getString(R.string.detail_swap_to_end),getString(R.string.detail_until,Recording.time(recording.duration)),()->applyEdit(t->t.swap(x,y,from,Double.MAX_VALUE),getString(R.string.detail_swapped_from,when),"swap"));
        if(blockEnd>from)s.option(R.drawable.ic_swap,getString(R.string.detail_swap_in_part,blockNo),getString(R.string.detail_until,Recording.time((long)(blockEnd*1000))),()->applyEdit(t->t.swap(x,y,from,blockEnd),getString(R.string.detail_swapped_in_part,blockNo),"swap_block"));
        s.secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_swap_failed));}}
    /** Hoja de una persona (desde su chip): escuchar, cambiar nombre, unir con otra, ver solo sus intervenciones. */
    private void personSheet(String key){try{
        correctionOpened=true;
        Map<String,String> names=transcript.speakers();String name=names.get(key);JSONArray segs=transcript.segments();
        int count=0;double talk=0,firstAt=-1,bestLen=0,bestFrom=0,bestTo=0;
        for(int i=0;i<segs.length();i++){JSONObject s=segs.getJSONObject(i);if(!s.getString("speaker").equals(key))continue;double a=s.getDouble("start"),b=s.optDouble("end",a);count++;talk+=Math.max(0,b-a);if(firstAt<0)firstAt=a;if(b-a>bestLen&&b-a<=15){bestLen=b-a;bestFrom=a;bestTo=b;}}
        Sheet s=sheet(name,getResources().getQuantityString(R.plurals.detail_person_summary,count,count,Recording.time((long)(talk*1000)),Recording.time((long)(Math.max(0,firstAt)*1000))));
        if(bestLen>0)listenButton(s,getString(R.string.detail_listen_sample),bestFrom,bestTo);
        s.action(R.drawable.ic_edit,getString(R.string.detail_rename),false,()->renameOne(key));
        if(names.size()>1)s.action(R.drawable.ic_merge,getString(R.string.detail_same_person),false,()->mergeSheet(key));
        boolean only=key.equals(onlySpeaker);s.action(R.drawable.ic_filter,getString(only?R.string.detail_show_everyone:R.string.detail_show_only),false,()->{onlySpeaker=only?null:key;reload(true);});
        s.action(R.drawable.ic_voice,getString(R.string.detail_name_all),false,this::openNameVoices);
        // Guardar su voz para reconocerla en los próximos audios: con un nombre puesto por el usuario, si aún no es una voz
        // conocida y hay un tramo limpio (sin otra voz encima) de 3 s o más. Solo si el servicio usa voces conocidas:
        // OpenAI (muestras) u OpenRouter (anclas, 0.8.0); con un servidor propio no servirían de nada.
        double[] clean=!demo&&transcript.diarized()&&!key.startsWith(Voices.TARGET)&&!name.equals(transcript.defaultLabel(key))&&!name.trim().isEmpty()
            &&TranscribeClient.knowsVoices(new Settings(this).provider())&&recording.audio(this).isFile()?NameVoices.sample(segs,key):null;
        if(clean!=null&&(clean[1]-clean[0])*1000>=Voices.MIN_MS)privateAction(s,R.drawable.ic_mic_fill,getString(R.string.detail_save_voice),getString(R.string.detail_save_voice_of,name),()->saveVoice(name,clean));
        s.show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_voice_open_failed));}}
    /** Guarda el tramo limpio de esta voz como voz conocida (si ya hay una con ese nombre, pregunta antes de reemplazarla). */
    private void saveVoice(String name,double[] range){
        Voices.Voice same=Voices.findByName(this,name);
        if(same==null){cutVoice(name,range,null);return;}
        confirm(getString(R.string.detail_replace_voice_q,same.name),getString(R.string.detail_replace_voice_body,Math.round(Math.min(range[1]-range[0],Voices.MAX_MS/1000d))),getString(R.string.detail_replace),false,()->cutVoice(name,range,same.id));
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
                if(saved){Ui.haptic(content,Ui.Haptic.CONFIRM);toast(getString(R.string.detail_voice_saved,name));}
                else message(getString(R.string.detail_save_voice),getString(R.string.detail_voice_cut_failed,name));});
        },"voice").start();
    }
    private void mergeSheet(String x){try{
        Map<String,String> names=transcript.speakers();String nx=names.get(x);
        Sheet s=sheet(getString(R.string.detail_merge_title,nx),getString(R.string.detail_merge_body));
        for(String y:names.keySet()){if(y.equals(x))continue;String ny=names.get(y);s.choice(ny,null,false,p.speaker(transcript.colorIndex(y)),()->applyEdit(t->t.merge(x,y),getString(R.string.detail_merged,nx,ny),"merge"));}
        s.secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.voices_load_failed));}}
    private void renameOne(String key){try{
        Map<String,String> names=transcript.speakers();String current=names.get(key);
        Sheet s=sheet(getString(R.string.detail_rename),getString(R.string.detail_rename_body));
        EditText f=ui.field(current,getString(R.string.detail_name));if(!current.equals(transcript.defaultLabel(key)))f.setText(current);f.setSingleLine(true);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});s.add(f);
        s.primary(getString(R.string.detail_save),Ui.Style.PRIMARY,()->{Map<String,String> one=new LinkedHashMap<>();one.put(key,f.getText().toString().trim());saveNames(one);return true;}).secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_name_load_failed));}}
    /** Aplica una corrección, guarda y ofrece "Deshacer". La pantalla conserva su posición. */
    private void applyEdit(Transcript.Edit op,String done,String action){
        try{JSONObject before;String saved=null;
            if(demo){before=new JSONObject(transcript.data.toString());op.apply(transcript);writeDemo();}
            else synchronized(FilesStore.LOCK){before=Transcript.edit(this,id,op);saved=NameVoices.stamp(this,id);}
            Diagnostics.event("transcript_edited",demo?null:id,"action",action);
            String stamp=saved;reload();snackbar(done,getString(R.string.detail_undo),()->undo(before,stamp));
        }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_change_failed));}
    }
    /** saved: marca de la transcripción tras el cambio. Si otra versión llegó entretanto, «Deshacer» la pisaría: no se hace. */
    private void undo(JSONObject before,String saved){
        try{if(demo){transcript=new Transcript(before);writeDemo();}
            else synchronized(FilesStore.LOCK){
                if(saved!=null&&!saved.equals(NameVoices.stamp(this,id))){message(getString(R.string.detail_undo),getString(R.string.voices_undo_stale));return;}
                Transcript.replace(this,id,before);}
            Diagnostics.event("transcript_edited",demo?null:id,"action","undo");reload();}
        catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.detail_undo_change_failed));}
    }
    private java.io.File demoFile(){return new java.io.File(getFilesDir(),"demo-transcript.json");}
    private void writeDemo()throws Exception{java.io.File f=demoFile();FilesStore.write(f,transcript.data);demoStamp=f.lastModified();}
    /** Si otra hoja guardó el ejemplo (p. ej. «Nombrar voces»), se toma esa versión. */
    private void syncDemo(){java.io.File f=demoFile();if(f.exists()&&f.lastModified()!=demoStamp){try{transcript=new Transcript(FilesStore.read(f));}catch(Exception ignored){}demoStamp=f.lastModified();}}
    /** Guarda nombres; las voces con el mismo nombre se unen (con "Deshacer"). */
    private void saveNames(Map<String,String> names){
        hideSnackbar();
        try{int[] merged={0};JSONObject before;String saved=null;
            if(demo){before=new JSONObject(transcript.data.toString());merged[0]=transcript.applyNames(names);writeDemo();}
            else synchronized(FilesStore.LOCK){before=Transcript.edit(this,id,t->merged[0]=t.applyNames(names));saved=NameVoices.stamp(this,id);}
            String stamp=saved;reload();
            if(merged[0]>0)snackbar(getString(merged[0]==1?R.string.detail_merged_two:R.string.detail_merged_many),getString(R.string.detail_undo),()->undo(before,stamp));else toast(getString(R.string.voices_names_saved));
        }catch(Exception e){message(getString(R.string.voices_names_failed),getString(R.string.voices_try_again));}
    }
    /** Hoja clásica «Nombrar voces» (respaldo si la hoja nueva no está disponible). */
    private void editSpeakers(){try{
        correctionOpened=true;
        Map<String,String> current=transcript.speakers();Map<String,EditText> fields=new LinkedHashMap<>();
        Sheet s=sheet(getString(R.string.voices_title),getString(R.string.detail_name_voices_body));
        for(String key:current.keySet()){LinearLayout row=ui.row();row.setPadding(0,ui.dp(S1),0,ui.dp(S1));View dot=new View(this);dot.setBackground(oval(p.speaker(transcript.colorIndex(key))));row.addView(dot,new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)));row.addView(ui.space(S3));
            String label=transcript.defaultLabel(key);EditText f=ui.field(label,getString(R.string.voices_name_for,label));String value=current.get(key);if(!value.equals(label))f.setText(value);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});row.addView(f,new LinearLayout.LayoutParams(0,-2,1));fields.put(key,f);s.add(row);}
        if(transcript.edited()){Ui.Btn restore=ui.button(getString(R.string.voices_restore),R.drawable.ic_refresh,Ui.Style.PLAIN,v->{s.dismiss();confirm(getString(R.string.voices_restore_q),getString(R.string.voices_restore_body),getString(R.string.voices_restore_action),false,()->applyEdit(Transcript::restore,getString(R.string.voices_restored),"restore"));});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S2);s.body.addView(restore,lp);}
        s.primary(getString(R.string.detail_save_names),Ui.Style.PRIMARY,()->{
            Map<String,String> names=new LinkedHashMap<>();for(Map.Entry<String,EditText> f:fields.entrySet())names.put(f.getKey(),f.getValue().getText().toString().trim());
            saveNames(names);return true;
        }).secondary(getString(R.string.common_cancel),null).show();
    }catch(Exception e){message(getString(R.string.detail_transcript),getString(R.string.voices_load_failed));}}

    /** La conversación de ejemplo (27 s, tres voces), en el idioma de la app. */
    static Transcript example()throws Exception{
        String[] who={"A","B","C","A"};int[] at={0,7,14,20,27};int[] lines={R.string.detail_demo_line1,R.string.detail_demo_line2,R.string.detail_demo_line3,R.string.detail_demo_line4};
        JSONArray segments=new JSONArray();for(int i=0;i<lines.length;i++)segments.put(new JSONObject().put("speaker",who[i]).put("start",at[i]).put("end",at[i+1]).put("text",Lang.str(lines[i])));
        return new Transcript(new JSONObject().put("demo",true).put("names",new JSONObject()).put("segments",segments));
    }

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
     * Destello de la marca (el ✦ del logo, Glass.sparkle) con uno chico al lado, como el ícono de «Your Weekly Activity»
     * de la referencia. Marca lo que armó la IA.
     */
    private static final class Spark extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
        Spark(Context c,int color){super(c);paint.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),s=Math.min(w,h);
            canvas.drawPath(Glass.sparkle(path,w*0.42f,h*0.58f,s*0.42f),paint);
            canvas.drawPath(Glass.sparkle(path,w*0.8f,h*0.2f,s*0.18f),paint);
        }
    }
    /**
     * Reparto del tiempo de habla: una barra redondeada con un tramo por persona, en su color, separados por 2 dp.
     * Solo es un dibujo (el % de cada persona lo lee TalkBack en su píldora).
     */
    private static final class ShareBar extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private final RectF rect=new RectF();private final float[] parts;private final int[] tones;private final float density;
        ShareBar(Context c,float[] parts,int[] tones){super(c);this.parts=parts;this.tones=tones;density=c.getResources().getDisplayMetrics().density;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float sum=0;int shown=0,firstShown=-1,lastShown=-1;for(int i=0;i<parts.length;i++)if(parts[i]>0){sum+=parts[i];shown++;if(firstShown<0)firstShown=i;lastShown=i;}
            if(sum<=0)return;float gap=2*density,w=getWidth()-gap*(shown-1),h=getHeight(),full=h/2f,inner=1.5f*density,x=0;
            for(int i=0;i<parts.length;i++){if(parts[i]<=0)continue;
                float seg=Math.max(inner*2,w*parts[i]/sum),l=i==firstShown?full:inner,r=i==lastShown?full:inner;
                rect.set(x,0,Math.min(getWidth(),x+seg),h);path.reset();path.addRoundRect(rect,new float[]{l,l,r,r,r,r,l,l},Path.Direction.CW);
                paint.setColor(tones[i]);canvas.drawPath(path,paint);x+=seg+gap;}
        }
    }
    /**
     * Casilla redonda de las tareas: anillo vacío (off) o círculo lleno (on) con un ✓ del color onMark. Al marcarla, el
     * relleno crece desde el centro (curva emphasized); con «Quitar animaciones», cambia al instante.
     */
    private static final class RoundCheck extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path tick=new Path();private final int on,onMark,off;private final float density;
        private boolean checked,drawn;private float t;private ValueAnimator anim;
        RoundCheck(Context c,int on,int onMark,int off){density=c.getResources().getDisplayMetrics().density;this.on=on;this.onMark=onMark;this.off=off;paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override public boolean isStateful(){return true;}
        @Override protected boolean onStateChange(int[] state){
            boolean c=false;for(int s:state)if(s==android.R.attr.state_checked)c=true;if(c==checked)return false;checked=c;
            if(anim!=null)anim.cancel();float target=c?1f:0f;
            if(!drawn||!AppTheme.motion()){t=target;invalidateSelf();return true;}
            anim=ValueAnimator.ofFloat(t,target);anim.setDuration(MOTION_BASE);anim.setInterpolator(EMPHASIZED);anim.addUpdateListener(a->{t=(float)a.getAnimatedValue();invalidateSelf();});anim.start();return true;
        }
        /** 32 dp de ancho: el círculo de 20 dp y el espacio hasta el texto. */
        @Override public int getIntrinsicWidth(){return Math.round(32*density);}
        @Override public int getIntrinsicHeight(){return Math.round(24*density);}
        @Override public void draw(Canvas canvas){
            drawn=true;Rect b=getBounds();float cx=b.left+11*density,cy=b.exactCenterY(),r=10*density,sw=2*density;
            if(t<1f){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(sw);paint.setColor(off);paint.setAlpha(Math.round(255*(1f-t)));canvas.drawCircle(cx,cy,r-sw/2f,paint);}
            if(t>0f){paint.setStyle(Paint.Style.FILL);paint.setColor(on);paint.setAlpha(255);canvas.drawCircle(cx,cy,r*(0.4f+0.6f*t),paint);
                tick.reset();tick.moveTo(cx-4.2f*density,cy+0.2f*density);tick.lineTo(cx-1.2f*density,cy+3.2f*density);tick.lineTo(cx+4.6f*density,cy-3.2f*density);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);paint.setColor(onMark);paint.setAlpha(Math.round(255*Math.max(0f,t*2f-1f)));canvas.drawPath(tick,paint);}
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    /**
     * Etiqueta (#tema) como píldora dentro del texto. A diferencia de Glass.Highlight (que abraza las letras de un título
     * grande), aquí la píldora tiene aire arriba y abajo, y la línea crece para que dos filas de etiquetas no se toquen.
     */
    private static final class TagSpan extends ReplacementSpan {
        private final int bg,fg;private final float padH,padV,maxW;private final RectF rect=new RectF();
        /** maxW: ancho de la línea. Una etiqueta más larga (p. ej. con letra muy grande) se acorta con «…» en vez de salirse. */
        TagSpan(int bg,int fg,float padH,float padV,float maxW){this.bg=bg;this.fg=fg;this.padH=padH;this.padV=padV;this.maxW=maxW;}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt fm){
            if(fm!=null){Paint.FontMetricsInt m=paint.getFontMetricsInt();int v=Math.round(padV);fm.ascent=m.ascent-v;fm.top=m.top-v;fm.descent=m.descent+v;fm.bottom=m.bottom+v;}
            return Math.round(Math.min(maxW,paint.measureText(text,start,end)+2*padH));
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint paint){
            Paint.FontMetrics m=paint.getFontMetrics();CharSequence shown=text.subSequence(start,end);float w=paint.measureText(shown,0,shown.length());int old=paint.getColor();
            if(w+2*padH>maxW&&paint instanceof android.text.TextPaint){shown=TextUtils.ellipsize(shown,(android.text.TextPaint)paint,Math.max(0,maxW-2*padH),TextUtils.TruncateAt.END);w=paint.measureText(shown,0,shown.length());}
            rect.set(x,y+m.ascent-padV*0.5f,x+w+2*padH,y+m.descent+padV*0.5f);float r=rect.height()/2f;
            paint.setColor(bg);canvas.drawRoundRect(rect,r,r,paint);paint.setColor(fg);canvas.drawText(shown,0,shown.length(),x+padH,y,paint);paint.setColor(old);
        }
    }
    /**
     * La onda del audio como barra para moverse: lo escuchado en verde (primary) y lo que falta en el gris de las ondas
     * de la referencia (waveIdle), debajo una franja con el color de quién habla y arriba los ★ verdes. La posición es una
     * perilla redonda de tinta con un anillo del color de la cápsula (crece mientras la arrastras). Tocar o arrastrar
     * salta; al pasar por un ★ se siente un tic. Mientras la onda no está lista, es una barra simple.
     */
    private static final class Scrubber extends View {
        interface Listener{void seek(long ms,boolean done);}
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),star=new Paint(Paint.ANTI_ALIAS_FLAG);private final int played,rest,track,knob,ring;private final float density;
        private float[] env;private long duration=1,position,last;private long[] marks=new long[0],runFrom=new long[0],runTo=new long[0];private int[] runColor=new int[0];
        boolean dragging;Listener listener;
        Scrubber(Context c,Palette p,int ring){super(c);density=c.getResources().getDisplayMetrics().density;played=p.primary;rest=p.waveIdle;track=p.surfaceContainerHighest;knob=p.ink;this.ring=ring;
            star.setColor(p.primary);star.setTextAlign(Paint.Align.CENTER);star.setTextSize(11*density);setClickable(true);setFocusable(true);describe();}
        void setDuration(long d){duration=Math.max(1,d);position=clamp(position);describe();invalidate();}
        void setPosition(long ms){long v=clamp(ms);if(v!=position){position=v;invalidate();}}
        void setEnvelope(float[] v){env=v;invalidate();}
        void setMarks(long[] m){marks=m==null?new long[0]:m;invalidate();}
        void setStrip(long[] from,long[] to,int[] colors){runFrom=from==null?new long[0]:from;runTo=to==null?new long[0]:to;runColor=colors==null?new int[0]:colors;invalidate();}
        private long clamp(long ms){return Math.max(0,Math.min(duration,ms));}
        private float dp(float n){return n*density;}
        /** 10 dp a cada lado: la perilla (con su anillo) cabe entera también al principio y al final. */
        private float left(){return dp(10);}
        private float right(){return getWidth()-dp(10);}
        private float x(long ms){return left()+(right()-left())*(ms/(float)duration);}
        private long ms(float x){return clamp((long)((x-left())/Math.max(1f,right()-left())*duration));}
        @Override protected void onDraw(Canvas canvas){
            float top=dp(13),waveH=getHeight()-top-dp(10),mid=top+waveH/2f,stripY=top+waveH+dp(4),stripH=dp(4),px=x(position);
            if(env==null||env.length==0){float h=dp(4);paint.setColor(rest);canvas.drawRoundRect(left(),mid-h/2,right(),mid+h/2,h/2,h/2,paint);
                paint.setColor(played);canvas.drawRoundRect(left(),mid-h/2,Math.max(left()+h,px),mid+h/2,h/2,h/2,paint);}
            else{float step=dp(4),bar=dp(2.5f);int bars=Math.max(1,(int)((right()-left())/step));
                for(int i=0;i<bars;i++){int a=(int)((long)i*env.length/bars),b=Math.max(a+1,(int)((long)(i+1)*env.length/bars));float v=0;for(int k=a;k<b&&k<env.length;k++)v=Math.max(v,env[k]);
                    float h=Math.max(dp(2),Math.min(1f,v)*waveH),bx=left()+i*step;paint.setColor(bx+bar/2<=px?played:rest);canvas.drawRoundRect(bx,mid-h/2,bx+bar,mid+h/2,bar/2,bar/2,paint);}
                // Cabezal: una línea fina de tinta que atraviesa la onda.
                paint.setColor(knob);canvas.drawRoundRect(px-dp(0.75f),top-dp(1),px+dp(0.75f),top+waveH+dp(1),dp(0.75f),dp(0.75f),paint);}
            if(runColor.length>0){paint.setColor(track);canvas.drawRoundRect(left(),stripY,right(),stripY+stripH,stripH/2,stripH/2,paint);
                for(int i=0;i<runColor.length;i++){float a=x(runFrom[i]),b=Math.max(a+dp(1),x(runTo[i]));paint.setColor(runColor[i]);canvas.drawRect(a,stripY,b,stripY+stripH,paint);}}
            for(long m:marks)canvas.drawText("★",x(m),top-dp(2),star);
            // Perilla redonda de tinta con un anillo del color de la cápsula (la separa de las barras); crece al arrastrarla.
            float r=dragging?dp(8):dp(6);paint.setColor(ring);canvas.drawCircle(px,mid,r+dp(2),paint);paint.setColor(knob);canvas.drawCircle(px,mid,r,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!isEnabled())return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);dragging=true;last=ms(e.getX());position=last;invalidate();if(listener!=null)listener.seek(position,false);return true;
                case MotionEvent.ACTION_MOVE:{long v=ms(e.getX()),lo=Math.min(last,v),hi=Math.max(last,v);for(long m:marks)if(m>lo&&m<=hi){Ui.haptic(this,Ui.Haptic.TICK);break;}
                    last=v;position=v;invalidate();if(listener!=null)listener.seek(v,false);return true;}
                case MotionEvent.ACTION_UP:dragging=false;describe();invalidate();if(listener!=null)listener.seek(position,true);performClick();return true;
                case MotionEvent.ACTION_CANCEL:dragging=false;invalidate();return true;
            }
            return super.onTouchEvent(e);
        }
        @Override public boolean performClick(){return super.performClick();}
        /** Su contexto es la pantalla, que ya va en el idioma de la app. */
        private void describe(){setContentDescription(getContext().getString(R.string.detail_scrubber_desc,Recording.time(position),Recording.time(duration)));}
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
