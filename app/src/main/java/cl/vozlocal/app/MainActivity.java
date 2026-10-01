package cl.vozlocal.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Pestañas "Grabar" y "Biblioteca" (0.7.0, Verbapp «Bosque de vidrio», ver docs/diseno/PROPUESTA-0.7.md §3.1–3.4).
 * Grabar va sobre el fondo intenso (blanco arriba, verde abajo) y tiene dos composiciones que se funden entre sí:
 * - En reposo: logo y estado arriba, saludo con tu nombre, el micrófono verde con su halo entre ondas decorativas,
 *   «Importar audio», la «Última grabación» con su siguiente paso y «Tu semana».
 * - Grabando (pantalla completa, sin barra de pestañas): el título en grande, «● Grabando», el cronómetro enorme, la onda
 *   en vivo sobre el verde y los controles [■ Detener] [❚❚ Pausa] (★).
 * Es una pantalla fija (sin desplazamiento): si no cabe, primero se oculta «Tu semana» y después se compacta por niveles.
 * Biblioteca (fondo suave): búsqueda, filtros por lo que pide acción y filas de 3 líneas (título, comienzo del texto,
 * personas y si ya está en 0-Inbox) en grupos de vidrio. Lo terminado va en silencio; se destaca solo lo que pide algo.
 */
public class MainActivity extends Screen {
    private static final Locale ES=new Locale("es","CL");
    /** Fecha ISO al comienzo del título (Ajustes → «Fecha en el nombre»). */
    private static final Pattern DATE=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+|$)");
    /** Caja del botón de grabar con su halo (dp): normal (círculo de 76 dp) y compacta. */
    private static final int MIC_BOX=132,MIC_BOX_COMPACT=116;
    /** Aire mínimo alrededor del micrófono antes de achicar «Tu semana» (dp). */
    private static final int CENTER_AIR=8;
    /** Alto mínimo de la onda en vivo antes de compactar la vista de grabación (dp). */
    private static final int WAVE_MIN=88;
    /** Transcripciones ya leídas (fragmento, personas y colores), por archivo: la Biblioteca no relee lo que no cambió. */
    private static final Map<String,Meta> METAS=new ConcurrentHashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private boolean showLibrary;private int homeScroll,libraryScroll;
    /**
     * Bienvenida de la primera instalación (0.8.0). welcoming: OnboardingActivity está abierta encima y Grabar espera
     * debajo, armada pero transparente. welcomeLeft: esta pantalla ya cedió el lugar, así que el próximo onResume es la
     * vuelta de la bienvenida (el primero, antes de que aparezca, no cuenta).
     */
    private boolean welcoming,welcomeLeft;

    // Grabar: dos composiciones apiladas (reposo y grabando) que se funden al cambiar de estado.
    private FrameLayout homePanel;private LinearLayout idleLayer,recLayer;private Flex center,waveZone;
    // Reposo
    private FrameLayout statusButton,micFrame;private ImageView statusIcon,attentionIcon;private Ring statusRing,attentionRing;private View statusBadge;
    private LinearLayout attention,importPill;private TextView hello,nameView,attentionText,prompt,importLabel;
    private Waveform.Decor decor;private RecordButton record;private String myName,shownName;
    // Grabando
    private LinearLayout titlePill,statusRow;private TextView titlePillText,bigTitle,statusLabel,timer,hint,markBadge;private View statusDot;
    private Ui.Btn stop,pause;private ImageButton mark;private Waveform wave;
    // Estado
    private int workingCount,failedCount,workingBlocks,workingDone;private String workingTitle="",workingId,failedId,reviewId,reviewTitle="";
    private String lastState="",shownTitle,shownImport,levelsId;private boolean starting,shownActive;private int shownMarks;
    /** Niveles de la grabación en curso: de aquí sale la onda chica de la hoja al terminar. */
    private final Levels levels=new Levels();
    /**
     * Cuánto de la grabación cubren esos niveles (ms de grabación, sin pausas): solo se juntan con Grabar a la vista, así
     * que bloquear el teléfono o salir de la app deja un tramo sin niveles. levelsLast: tiempo de grabación del último
     * nivel (-1 = ninguno aún). Con cobertura parcial, la onda chica va plana en vez de estirar lo poco que hay.
     */
    private long levelsMs,levelsLast=-1;
    /**
     * Ajuste de la pantalla fija. fitPending: hay que volver a medir si todo cabe (se resuelve con Grabar a la vista y ya
     * medida; nunca en bucle). weekMode: 0 = «Tu semana» completa, 1 = en una franja, 2 = oculta. Cada composición
     * recuerda el alto con que se ajustó (idleFitH / recFitH).
     */
    private boolean fitPending;private int weekMode,compact,recCompact,idleFitH=-1,recFitH=-1;
    // Última grabación
    private LinearLayout lastCard,lastActions;private FrameLayout lastLead;private TextView lastOverline,lastTitle,lastStatus;private Ui.Btn lastButton;private String lastKey="";private boolean savingInbox;
    // Tu semana (tarjeta completa o franja)
    private LinearLayout weekCard,weekHead,weekStats,weekStrip,weekStripStats;private Week week=new Week();private String weekKey="";
    // Hoja «Nombra esta grabación»
    private NamePlayer namePlayer;
    // Biblioteca
    private LinearLayout libraryPanel,list,filters,recPill;private View pillDot;private TextView libraryCount,pillTime;private EditText search;
    private String query="";private int filter;private String rendered="";private boolean imeShown,inboxOn;private String queueBlocker;

    /** Lo que la Biblioteca sabe de una transcripción sin volver a leerla. */
    static final class Meta{
        final long modified,length;String snippet="";boolean diarized,reviewed=true;
        final ArrayList<String> names=new ArrayList<>();final ArrayList<Integer> colors=new ArrayList<>();
        Meta(long modified,long length){this.modified=modified;this.length=length;}
    }
    static final class Item{
        final Recording r;final JSONObject state;final RecState status;final boolean transcribed;
        volatile Meta meta;long savedAt;boolean outdated,fresh,inbox;Next next;
        Item(Recording r,JSONObject state,boolean transcribed){this.r=r;this.state=state;this.transcribed=transcribed;this.status=RecState.of(state,transcribed);}
        boolean done(){return status.kind==RecState.Kind.DONE;}
        /** Voces separadas que el usuario aún no revisó. */
        boolean toReview(){return toReview(meta);}
        /**
         * Lo mismo sobre una lectura ya tomada de meta: el hilo de disco la completa mientras se dibuja, así que quien
         * después usa m (p. ej. cuántas voces) debe decidir con esa misma m.
         */
        boolean toReview(Meta m){return done()&&m!=null&&m.diarized&&m.names.size()>1&&!m.reviewed;}
        /** Lista y aún no guardada (o con cambios) en la carpeta rápida. */
        boolean toSave(){return done()&&inbox&&(savedAt==0||outdated);}
        int blocks(){return state.optInt("blocks");}
        int blocksDone(){return Math.min(blocks(),state.optInt("blocksDone"));}
        /** Avance real 0..1 (partes listas o audio procesado); -1 si todavía no se sabe. Nunca un porcentaje inventado. */
        float progress(){int b=blocks();if(b>1)return blocksDone()/(float)b;long a=state.optLong("audioMs"),d=state.optLong("doneAudioMs");return a>0&&d>0?Math.min(1f,d/(float)a):-1f;}
        String progressText(String blocker){
            int b=blocks();if(b>1)return "Transcribiendo · "+blocksDone()+" de "+b+" partes";
            if(blocker!=null&&!TranscribeService.running)return "En cola · "+blocker.replaceFirst(" \\(.*$","");
            return "Transcribiendo…";
        }
        String snippet(){Meta m=meta;return m!=null&&!m.snippet.isEmpty()?m.snippet:state.optString("snippet","");}
    }
    /** «Tu semana»: grabaciones, tiempo grabado y notas de los últimos 7 días (se cuenta en el hilo de disco). */
    static final class Week{int count,notes;long ms;}
    /** loadVersion: la última lectura pedida (se lee en el hilo de disco para saltarse las que ya quedaron atrás). */
    private ArrayList<Item> items=new ArrayList<>();private volatile int loadVersion;private int dataVersion=-1;

    /** 70 ms al grabar (onda fluida); 400 ms en reposo para ahorrar batería. */
    private final Runnable tick=new Runnable(){@Override public void run(){update();handler.postDelayed(this,RecorderService.activeId!=null||starting?70:400);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null){query=saved.getString("query","");filter=saved.getInt("filter");homeScroll=saved.getInt("homeScroll");libraryScroll=saved.getInt("libraryScroll");}
        showLibrary=saved!=null?saved.getBoolean("library"):getIntent().getBooleanExtra("library",false);
        // Grabar lleva el fondo intenso (verde abajo); la Biblioteca, el suave (se funden al cambiar de pestaña).
        shell(null,showLibrary?1:0,!showLibrary);
        // El teclado nunca se abre solo al volver: solo cuando tocas la búsqueda.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setFocusableInTouchMode(true);
        // Nombre para el saludo mientras el hilo de disco lee el de «Mi voz» (así no parpadea al abrir).
        myName=new Settings(this).prefs.getString("myVoiceName","");
        // Grabar es una pantalla FIJA (sin desplazamiento): va fuera del ScrollView y ocupa el alto disponible.
        homePanel=new FrameLayout(this);homePanel.setClipChildren(false);root.addView(homePanel,0,new LinearLayout.LayoutParams(-1,0,1));buildHome();
        // Si cambia el alto (otra ventana, la barra de pestañas que se esconde al grabar), se vuelve a medir si todo cabe.
        homePanel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)requestFit();});
        libraryPanel=ui.column();page.addView(libraryPanel,Ui.fill());buildLibrary();
        // Tras cada medición: el teclado de la búsqueda y un ajuste de Grabar que esperaba a estar medido (no se sondea).
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{checkIme();if(fitPending&&homePanel.getVisibility()==View.VISIBLE)homePanel.post(this::fitHome);});
        section(showLibrary);root.requestFocus();
        // Primera instalación: la bienvenida va en su propia pantalla, encima de esta (nunca sobre una grabación en curso).
        if(saved==null&&RecorderService.activeId==null&&OnboardingActivity.shouldShow(this))startWelcome();
    }
    @Override void navigate(int tab){if(tab==2)super.navigate(2);else section(tab==1);}
    @Override protected void onResume(){
        super.onResume();lastState="";if(search!=null&&search.hasFocus()){search.clearFocus();root.requestFocus();}
        boolean fromWelcome=welcoming&&welcomeLeft;if(fromWelcome)endWelcome();
        handler.post(tick);load();if(!Pipeline.startForeground(this))Pipeline.schedule(this,false);renderChip();renderGreeting();
        // Novedades de la versión: nunca junto con la bienvenida (ni mientras se abre ni al volver de ella).
        if(!welcoming&&!fromWelcome)try{Novedades.maybeShow(this);}catch(RuntimeException e){Diagnostics.event("novedades_failed",null,"error_class",e.getClass().getSimpleName());}
    }
    @Override protected void onPause(){handler.removeCallbacks(tick);if(namePlayer!=null)namePlayer.release();if(welcoming)welcomeLeft=true;super.onPause();}
    @Override protected void onDestroy(){if(namePlayer!=null)namePlayer.release();handler.removeCallbacksAndMessages(null);disk.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("library",showLibrary);out.putString("query",query);out.putInt("filter",filter);if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();out.putInt("homeScroll",homeScroll);out.putInt("libraryScroll",libraryScroll);super.onSaveInstanceState(out);}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        // Si algo trae esta pantalla al frente por encima de la bienvenida (una pestaña de Ajustes, una notificación), Grabar se muestra.
        if(welcoming)endWelcome();
        section(intent.getBooleanExtra("library",false));
    }
    @Override public void onBackPressed(){if(showLibrary)section(false);else super.onBackPressed();}

    private void section(boolean library){
        if(showLibrary!=library){if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();}
        showLibrary=library;nav.select(library?1:0);
        homePanel.setVisibility(library?View.GONE:View.VISIBLE);libraryPanel.setVisibility(library?View.VISIBLE:View.GONE);scroll.setVisibility(library?View.VISIBLE:View.GONE);
        // Fondo intenso en Grabar y suave en la Biblioteca. Grabando, Grabar es pantalla completa (sin barra de pestañas).
        setVivid(!library,true);setNavHidden(!library&&shownActive);
        if(library)ui.fadeIn(libraryPanel);else{hideKeyboard();requestFit();}
        renderPill();
        scroll.post(()->scroll.scrollTo(0,library?libraryScroll:homeScroll));Diagnostics.event("section_open",null,"screen",library?"library":"home");
    }

    // ================= GRABAR =================
    private void buildHome(){
        idleLayer=layer();recLayer=layer();recLayer.setVisibility(View.INVISIBLE);recLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        homePanel.addView(idleLayer,new FrameLayout.LayoutParams(-1,-1));homePanel.addView(recLayer,new FrameLayout.LayoutParams(-1,-1));
        buildIdle();buildRecording();renderHint(false);renderGreeting();renderWeek();
    }
    /** Columna de una composición: márgenes de 16 dp; lo que sobresale (sombras, la onda de borde a borde) no se corta. */
    private LinearLayout layer(){LinearLayout l=ui.column();l.setPadding(ui.dp(S4),0,ui.dp(S4),ui.dp(S3));l.setClipToPadding(false);l.setClipChildren(false);return l;}

    /** Reposo: cabecera, saludo, el micrófono al centro (toma el espacio libre), «Última grabación» y «Tu semana». */
    private void buildIdle(){
        // Cabecera: logo a la izquierda; a la derecha, el estado en un botón redondo de vidrio (hace lo mismo que el chip de 0.6).
        LinearLayout header=ui.row();header.setPadding(ui.dp(S1),ui.dp(S1),0,ui.dp(S1));header.setMinimumHeight(ui.dp(56));header.setClipChildren(false);header.setClipToPadding(false);
        header.addView(ui.brand(20));header.addView(ui.flex());
        statusButton=new FrameLayout(this);statusButton.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),glassOval(this,p),oval(0xFF000000)));
        statusButton.setElevation(p.dark?0:ui.dp(1));if(Build.VERSION.SDK_INT>=28&&!p.dark){statusButton.setOutlineSpotShadowColor(p.shadow);statusButton.setOutlineAmbientShadowColor(p.shadow);}
        statusRing=new Ring(this,p.primary,p.primaryContainer,ui.dp(2.5f));statusButton.addView(statusRing,new FrameLayout.LayoutParams(ui.dp(30),ui.dp(30),Gravity.CENTER));
        statusIcon=new ImageView(this);statusIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);statusButton.addView(statusIcon,new FrameLayout.LayoutParams(ui.dp(20),ui.dp(20),Gravity.CENTER));
        statusBadge=new View(this);statusBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(ui.dp(10),ui.dp(10),Gravity.TOP|Gravity.END);bp.setMargins(0,ui.dp(10),ui.dp(10),0);statusButton.addView(statusBadge,bp);
        statusButton.setClickable(true);statusButton.setFocusable(true);statusButton.setAccessibilityDelegate(Ui.buttonRole());statusButton.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);Ui.pressable(statusButton);
        header.addView(statusButton,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        idleLayer.addView(header,Ui.fill());

        // Saludo según la hora y tu nombre. Si algo pide atención, debajo va una píldora con el estado (su lugar se reserva
        // siempre: así nada se mueve cuando empieza o termina una transcripción).
        LinearLayout greeting=ui.column();greeting.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),0);greeting.setClipChildren(false);
        hello=ui.text("",Type.ITEM,p.onSurfaceVariant);greeting.addView(hello);
        nameView=ui.heading("",Type.DISPLAY_SMALL);nameView.setMaxLines(2);nameView.setEllipsize(TextUtils.TruncateAt.END);greeting.addView(nameView,Ui.fill());
        attention=ui.row();attention.setBackground(pill());attention.setPadding(ui.dp(S3),0,ui.dp(S4),0);attention.setMinimumHeight(ui.dp(48));
        FrameLayout glyph=new FrameLayout(this);attentionRing=new Ring(this,p.primary,p.primaryContainer,ui.dp(2));glyph.addView(attentionRing,new FrameLayout.LayoutParams(ui.dp(16),ui.dp(16),Gravity.CENTER));
        attentionIcon=ui.icon(R.drawable.ic_check,p.primary,18);glyph.addView(attentionIcon,new FrameLayout.LayoutParams(ui.dp(18),ui.dp(18),Gravity.CENTER));
        attention.addView(glyph,new LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)));attention.addView(ui.space(S2));
        attentionText=ui.oneLine(ui.text("",Type.LABEL_LARGE,p.onSurface));attention.addView(attentionText);
        attention.setClickable(true);attention.setFocusable(true);attention.setAccessibilityDelegate(Ui.buttonRole());Ui.pressable(attention);attention.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams al=Ui.wrap();al.topMargin=ui.dp(S1);greeting.addView(attention,al);
        idleLayer.addView(greeting,Ui.fill());

        // Centro (toma el espacio libre): el micrófono verde entre ondas decorativas, «Toca para grabar» e «Importar audio».
        center=new Flex(this);
        LinearLayout mid=ui.column();mid.setGravity(Gravity.CENTER_HORIZONTAL);mid.setClipChildren(false);
        micFrame=new FrameLayout(this);micFrame.setClipChildren(false);
        decor=new Waveform.Decor(this,p.waveIdle);decor.setGap(ui.dp(MIC_BOX-12));micFrame.addView(decor,new FrameLayout.LayoutParams(-1,ui.dp(72),Gravity.CENTER_VERTICAL));
        record=new RecordButton(this,p);record.setContentDescription("Grabar");record.setOnClickListener(this::onRecordTap);micFrame.addView(record,new FrameLayout.LayoutParams(ui.dp(MIC_BOX),ui.dp(MIC_BOX),Gravity.CENTER));
        // Las ondas llegan hasta el borde de la pantalla (se desvanecen antes).
        LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(-1,ui.dp(MIC_BOX));fl.setMarginStart(-ui.dp(S4));fl.setMarginEnd(-ui.dp(S4));mid.addView(micFrame,fl);
        prompt=ui.text("",Type.ITEM,p.onSurface);prompt.setTextSize(15);prompt.setGravity(Gravity.CENTER);prompt.setText(promptText());prompt.setContentDescription("Toca para grabar. Funciona sin internet.");LinearLayout.LayoutParams pp=Ui.wrap();pp.topMargin=ui.dp(S1);mid.addView(prompt,pp);
        importPill=buildImportPill();LinearLayout.LayoutParams ip=Ui.wrap();ip.topMargin=ui.dp(S1);mid.addView(importPill,ip);
        center.addView(mid,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        idleLayer.addView(center,new LinearLayout.LayoutParams(-1,0,1));

        lastCard=buildLastCard();idleLayer.addView(lastCard,Ui.fill());
        weekCard=buildWeekCard();idleLayer.addView(weekCard,ui.top(S3));
    }
    /**
     * Línea bajo el micrófono: «Toca para grabar» y, más tenue, «funciona sin internet» (0.6 lo decía en su línea de ayuda
     * en reposo y 0.7 lo había perdido). Va en la MISMA línea, así no suma alto a la pantalla fija; si no cabe (letra
     * grande o pantalla angosta) pasa a dos líneas enteras en vez de partirse a mitad de frase. El tono tenue es la misma
     * tinta con transparencia y no onSurfaceVariant: aquí el fondo ya empieza a ponerse verde y el gris perdería contraste.
     */
    private CharSequence promptText(){
        String first="Toca para grabar",line=first+" · funciona sin internet";
        boolean fits=prompt.getPaint().measureText(line)<=getResources().getDisplayMetrics().widthPixels-2*ui.dp(S4)-ui.dp(S2);
        SpannableString s=new SpannableString(fits?line:first+"\nFunciona sin internet");
        s.setSpan(new ForegroundColorSpan(withAlpha(p.onSurface,0xB8)),first.length(),s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
    /** Píldora de vidrio (40 dp visibles dentro de un área táctil de 48 dp) con borde fino, para que se vea sobre el blanco. Ponerla ANTES de setPadding: el InsetDrawable trae su propio relleno y lo reemplazaría. */
    private Drawable pill(){int inset=ui.dp(S1);return new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(outline(this,p.glass,p.outlineVariant,R_FULL,false),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,inset,0,inset));}
    /** «Importar audio»: píldora de vidrio chica; mientras hay una importación en curso, dice su avance. */
    private LinearLayout buildImportPill(){
        LinearLayout row=ui.row();row.setBackground(pill());row.setMinimumHeight(ui.dp(48));row.setPadding(ui.dp(S4),0,ui.dp(S4),0);
        row.addView(ui.icon(R.drawable.ic_upload,p.onSurface,18));row.addView(ui.space(S2));
        importLabel=Ui.tabular(ui.oneLine(ui.text("Importar audio",Type.LABEL_LARGE,p.onSurface)));row.addView(importLabel);
        row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());row.setContentDescription("Importar audio de WhatsApp, grabadoras o archivos");
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action","Importar audio");if(RecorderService.activeId!=null){message("Grabación en curso","Guarda primero la grabación actual.");return;}startActivity(new Intent(this,ImportActivity.class));});
        Ui.pressable(row);return row;
    }

    /** Grabando (pantalla completa): volver, título, «● Grabando», cronómetro, la onda en vivo y los controles. */
    private void buildRecording(){
        LinearLayout top=ui.row();top.setPadding(0,ui.dp(S1),ui.dp(S1),ui.dp(S1));top.setMinimumHeight(ui.dp(56));top.setClipChildren(false);top.setClipToPadding(false);
        ImageButton back=ui.glassButton(R.drawable.ic_arrow_back,"Ir a Biblioteca, la grabación sigue");back.setOnClickListener(v->section(true));top.addView(back);top.addView(ui.flex());top.addView(ui.brand(18));
        recLayer.addView(top,Ui.fill());
        // En el lugar del «Default mic» de la referencia: el título de la grabación (tocar para escribirlo).
        titlePill=ui.row();titlePill.setBackground(pill());titlePill.setPadding(ui.dp(S4),0,ui.dp(S4),0);titlePill.setMinimumHeight(ui.dp(48));
        titlePill.addView(ui.icon(R.drawable.ic_edit,p.onSurface,16));titlePill.addView(ui.space(S2));
        titlePillText=ui.oneLine(ui.text("Añadir título",Type.LABEL_LARGE,p.onSurface));titlePillText.setMaxWidth(ui.dp(220));titlePill.addView(titlePillText);
        titlePill.setClickable(true);titlePill.setFocusable(true);titlePill.setAccessibilityDelegate(Ui.buttonRole());
        titlePill.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action","Añadir título");titleWhileRecording();});Ui.pressable(titlePill);
        LinearLayout.LayoutParams tp=Ui.wrap();tp.gravity=Gravity.CENTER_HORIZONTAL;recLayer.addView(titlePill,tp);
        bigTitle=ui.heading("",Type.DISPLAY_SMALL);bigTitle.setGravity(Gravity.CENTER);bigTitle.setMaxLines(3);bigTitle.setEllipsize(TextUtils.TruncateAt.END);bigTitle.setPadding(ui.dp(S2),ui.dp(S3),ui.dp(S2),0);recLayer.addView(bigTitle,Ui.fill());
        statusRow=ui.row();statusRow.setGravity(Gravity.CENTER);statusDot=new View(this);statusDot.setBackground(oval(p.record));statusRow.addView(statusDot,new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8)));statusRow.addView(ui.space(S2));
        statusLabel=ui.text("Grabando",Type.LABEL_LARGE,p.onSurfaceVariant);statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);statusRow.addView(statusLabel);
        LinearLayout.LayoutParams sl=Ui.wrap();sl.gravity=Gravity.CENTER_HORIZONTAL;sl.topMargin=ui.dp(S4);recLayer.addView(statusRow,sl);
        timer=Ui.tabular(ui.text("00:00",Type.DISPLAY_LARGE,p.onSurface));timer.setGravity(Gravity.CENTER);timer.setIncludeFontPadding(false);timer.setLineSpacing(0,1f);timer.setMaxLines(1);timer.setPadding(0,ui.dp(S1),0,0);shrinkToFit(timer,36);recLayer.addView(timer,Ui.fill());
        // La onda toma el espacio libre sobre el verde, de borde a borde.
        waveZone=new Flex(this);wave=new Waveform(this,p);wave.setMinimumHeight(ui.dp(WAVE_MIN));waveZone.addView(wave,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams wl=new LinearLayout.LayoutParams(-1,0,1);wl.setMarginStart(-ui.dp(S4));wl.setMarginEnd(-ui.dp(S4));wl.topMargin=ui.dp(S2);wl.bottomMargin=ui.dp(S4);recLayer.addView(waveZone,wl);
        // [■ Detener] [❚❚ Pausa] (★): píldoras de vidrio sobre el verde y la ★ redonda con su contador.
        LinearLayout controls=ui.row();controls.setClipChildren(false);
        stop=ui.button("Detener",R.drawable.ic_stop,Ui.Style.VIVID,v->{if(RecorderService.activeId!=null)onRecordTap(v);});stop.setContentDescription("Detener y guardar");
        pause=ui.button("Pausa",R.drawable.ic_pause,Ui.Style.VIVID,v->{if(RecorderService.activeId==null)return;Ui.haptic(v,Ui.Haptic.TICK);send("PAUSE");});pause.setContentDescription("Pausar");
        for(Ui.Btn b:new Ui.Btn[]{stop,pause}){b.setPadding(ui.dp(S3),0,ui.dp(S3),0);ui.oneLine(b.label);shrinkToFit(b.label,12);}
        controls.addView(stop,new LinearLayout.LayoutParams(0,ui.dp(52),1));
        LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(0,ui.dp(52),1);pl.setMarginStart(ui.dp(S3));controls.addView(pause,pl);
        LinearLayout.LayoutParams ml=new LinearLayout.LayoutParams(ui.dp(52),ui.dp(52));ml.setMarginStart(ui.dp(S3));controls.addView(markButton(),ml);
        recLayer.addView(controls,Ui.fill());
        // Hasta 3 líneas: con la letra grande del sistema, la ayuda que también explica la ★ no cabe en 2 y se cortaría.
        hint=ui.text("",Type.BODY_SMALL,p.onVividVariant);hint.setTextSize(13);hint.setGravity(Gravity.CENTER);hint.setMaxLines(3);hint.setEllipsize(TextUtils.TruncateAt.END);hint.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),0);recLayer.addView(hint,Ui.fill());
        renderRecTitle(null);
    }
    /** ★ Marcar: un toque deja un momento con vibración; mantenerlo permite anotar una palabra. El globito dice cuántos van. */
    private View markButton(){
        FrameLayout box=new FrameLayout(this);box.setClipChildren(false);
        mark=ui.iconButton(R.drawable.ic_sparkle,"Marcar momento",p.onVivid,0,52);mark.setImageTintList(null);mark.setImageDrawable(new StarIcon(p.onVivid,ui.dp(24)));
        GradientDrawable glassDisc=oval(p.glassOnVivid);glassDisc.setStroke(Math.max(1,ui.dp(1)),p.glassOnVividStroke);mark.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onVivid)),glassDisc,oval(0xFF000000)));
        mark.setOnClickListener(v->markMoment(v,null,-1));mark.setOnLongClickListener(v->{markWithWord(v);return true;});
        mark.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Marcar con una palabra"));}});
        box.addView(mark,new FrameLayout.LayoutParams(-1,-1));
        markBadge=Ui.tabular(ui.text("",Type.LABEL_SMALL,p.onInk));markBadge.setGravity(Gravity.CENTER);markBadge.setMinWidth(ui.dp(20));markBadge.setPadding(ui.dp(5),0,ui.dp(5),0);
        GradientDrawable bubble=shape(this,p.ink,R_FULL);bubble.setStroke(Math.max(1,ui.dp(1.5f)),p.onVivid);markBadge.setBackground(bubble);markBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);markBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl=new FrameLayout.LayoutParams(-2,ui.dp(20),Gravity.TOP|Gravity.END);bl.setMargins(0,-ui.dp(4),-ui.dp(4),0);box.addView(markBadge,bl);
        return box;
    }
    /**
     * «Última grabación»: título, estado real y UN botón con el siguiente paso (Next). Alto fijo en todos los estados:
     * el avance de una transcripción va como anillo alrededor del ícono (igual que en la Biblioteca), no en otra fila.
     */
    private LinearLayout buildLastCard(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        LinearLayout top=ui.row();
        lastLead=new FrameLayout(this);lastLead.setClipChildren(false);top.addView(lastLead,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));top.addView(ui.space(S3));
        LinearLayout texts=ui.column();
        lastOverline=ui.text("Última grabación",Type.LABEL_MEDIUM,p.onSurfaceVariant);texts.addView(lastOverline);
        lastTitle=ui.oneLine(ui.text("",Type.TITLE_MEDIUM,p.onSurface));texts.addView(lastTitle);
        lastStatus=Ui.tabular(ui.oneLine(ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant)));lastStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);texts.addView(lastStatus);
        top.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(top,Ui.fill());
        // El botón llega con la primera lectura del disco: su alto (48 dp) se reserva desde ya, así la tarjeta no crece
        // después de medir si todo cabe ni empuja «Tu semana» al abrir.
        lastActions=ui.row();lastActions.setMinimumHeight(ui.dp(48));LinearLayout.LayoutParams al=Ui.fill();al.topMargin=ui.dp(S3);card.addView(lastActions,al);
        card.setBackground(ui.ripple(glass(this,p,R_CARD),R_CARD));card.setClickable(true);card.setFocusable(true);card.setAccessibilityDelegate(Ui.buttonRole());
        return card;
    }
    /** Anillo de avance verde con un ícono al centro (lo que se está transcribiendo); gira si aún no se sabe cuánto falta. */
    private FrameLayout progressLead(float fraction,int icon,int iconDp,float strokeDp){
        FrameLayout box=new FrameLayout(this);Ring ring=new Ring(this,p.primary,p.primaryContainer,ui.dp(strokeDp));ring.setFraction(fraction);box.addView(ring,new FrameLayout.LayoutParams(-1,-1));
        box.addView(ui.icon(icon,p.primary,iconDp),new FrameLayout.LayoutParams(ui.dp(iconDp),ui.dp(iconDp),Gravity.CENTER));box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return box;
    }
    /**
     * «Tu semana»: tres datos de los últimos 7 días. Tocarla abre «Tus métricas» (0.8.0, SPEC-0.8b: el resumen invita a ver
     * el resto de los números; la Biblioteca ya está a un toque en la barra). En pantallas bajas se achica primero a una
     * franja de una línea (los mismos tres datos con su ícono) y, si aún no cabe, se oculta.
     */
    private LinearLayout buildWeekCard(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));
        weekHead=ui.row();weekHead.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S3));
        weekHead.addView(ui.icon(R.drawable.ic_sparkle,p.primary,16));weekHead.addView(ui.space(S2));
        weekHead.addView(ui.text("Tu semana",Type.TITLE_SMALL,p.onSurface));weekHead.addView(ui.flex());
        weekHead.addView(ui.text("Últimos 7 días",Type.LABEL_MEDIUM,p.onSurfaceVariant));
        card.addView(weekHead,Ui.fill());
        weekStats=ui.row();weekStats.setGravity(Gravity.TOP);card.addView(weekStats,Ui.fill());
        // Franja (pantallas bajas): ✦ Tu semana ········ [≋ 3] [◷ 12 min] [▤ 1]
        weekStrip=ui.row();weekStrip.setMinimumHeight(ui.dp(40));weekStrip.setVisibility(View.GONE);
        weekStrip.addView(ui.icon(R.drawable.ic_sparkle,p.primary,16));weekStrip.addView(ui.space(S2));
        weekStrip.addView(ui.text("Tu semana",Type.TITLE_SMALL,p.onSurface));weekStrip.addView(ui.flex());
        weekStripStats=ui.row();weekStrip.addView(weekStripStats);card.addView(weekStrip,Ui.fill());
        for(View v:new View[]{weekHead,weekStats,weekStrip})v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.setBackground(ui.ripple(glass(this,p,R_CARD),R_CARD));card.setClickable(true);card.setFocusable(true);card.setAccessibilityDelegate(Ui.buttonRole());
        card.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action","Tu semana");MetricsActivity.open(this);});Ui.pressable(card);
        return card;
    }
    private void renderWeek(){
        Week w=week;String dur=w.ms<=0?"0 min":Ui.humanDuration(w.ms);String key=w.count+"|"+dur+"|"+w.notes;if(key.equals(weekKey))return;weekKey=key;
        String[] values={String.valueOf(w.count),dur,String.valueOf(w.notes)},labels={w.count==1?"Grabación":"Grabaciones","Grabado",w.notes==1?"Nota":"Notas"};
        int[] icons={R.drawable.ic_waveform,R.drawable.ic_clock,R.drawable.ic_note};
        weekStats.removeAllViews();weekStripStats.removeAllViews();
        for(int i=0;i<3;i++){
            LinearLayout s=ui.stat(icons[i],values[i],labels[i]);s.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S2),ui.dp(S3));
            for(int k=0;k<s.getChildCount();k++)if(s.getChildAt(k) instanceof TextView)shrinkToFit((TextView)s.getChildAt(k),11);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.setMarginStart(ui.dp(S2));weekStats.addView(s,lp);
            if(i>0)weekStripStats.addView(ui.space(S3));weekStripStats.addView(ui.icon(icons[i],p.onSurfaceVariant,16));weekStripStats.addView(ui.space(S1));weekStripStats.addView(Ui.tabular(ui.text(values[i],Type.LABEL_LARGE,p.onSurface)));
        }
        weekCard.setContentDescription("Tu semana, últimos 7 días: "+w.count+(w.count==1?" grabación, ":" grabaciones, ")+dur+" grabado, "+w.notes+(w.notes==1?" nota":" notas")+". Toca para ver tus métricas");
    }
    /**
     * Achica un texto de una línea hasta que quepa entero (sin «…» ni saltos), sin bajar de minSp: sirve con la letra
     * grande del sistema, «1 h 04 min» en un dato de «Tu semana» o «1:02:03» en el cronómetro.
     */
    private void shrinkToFit(TextView t,float minSp){
        float min=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,minSp,getResources().getDisplayMetrics());
        t.addOnLayoutChangeListener((v,l,tp,r,b,ol,ot,or,ob)->{
            float avail=t.getWidth()-t.getTotalPaddingLeft()-t.getTotalPaddingRight(),need=t.getPaint().measureText(t.getText().toString()),size=t.getTextSize();
            if(avail>0&&need>avail&&size>min){float next=Math.max(min,size*avail/need*0.98f);t.post(()->t.setTextSize(TypedValue.COMPLEX_UNIT_PX,next));}
        });
    }
    private void whatsappHelp(){
        Sheet s=sheet("Transcribir un audio de WhatsApp",null);
        String[] steps={"Abre el chat y mantén presionado el audio.","Toca Compartir (o ⋮ → Compartir).","Elige Verbapp en la lista de apps.","Revisa el título y toca Guardar audio."};
        for(int i=0;i<steps.length;i++){LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(ui.dp(S1),ui.dp(S2),0,ui.dp(S2));TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onPrimaryContainer);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.primaryContainer));n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(n,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);}
        TextView note=ui.text("También funciona con grabadoras, Telegram y cualquier app que comparta audio.",Type.BODY_MEDIUM,p.onSurfaceVariant);note.setPadding(ui.dp(S1),ui.dp(S3),0,0);s.add(note);
        s.primary("Entendido",()->{}).show();
    }
    /** Saludo según la hora y tu nombre de «Mi voz»; sin nombre, una invitación con «hoy?» destacado. */
    private void renderGreeting(){
        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        setText(hello,hour>=5&&hour<12?"Buenos días,":hour>=12&&hour<20?"Buenas tardes,":"Buenas noches,");
        String n=myName==null?"":myName.trim();boolean named=!n.isEmpty()&&!n.equals("Yo");String shown=named?n:"¿Qué grabamos hoy?";
        if(shown.equals(shownName))return;shownName=shown;nameView.setContentDescription(null);
        if(named)nameView.setText(n);else ui.highlightLast(nameView,shown);
        // Un nombre de dos líneas ocupa más: se vuelve a medir si todo cabe.
        requestFit();
    }
    /**
     * Estado, con esta prioridad: falta la clave > transcribiendo > error > una transcripción lista sin abrir > todo bien.
     * Arriba a la derecha va siempre como un botón redondo (llave, anillo que gira, alerta, documento o ✓); si algo pide
     * atención, además se dice con palabras en la píldora bajo el saludo. Ambos hacen lo mismo que el chip de 0.6.
     * La clave es la de OpenRouter (0.8.0, SPEC-0.8b: solo OpenRouter): sin ella, «Configurar transcripción» abre Ajustes
     * directo en la hoja de esa clave (extra focusKey), aunque quede guardada una clave vieja de OpenAI.
     */
    private void renderChip(){
        boolean ready=new Settings(this).hasOpenRouterKey();String text,description;int icon;boolean spin=false,quiet=false,alert=false,fresh=false;View.OnClickListener click;
        if(!ready){text="Configurar transcripción";icon=R.drawable.ic_key;click=v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action","Configurar transcripción");startActivity(new Intent(this,SettingsActivity.class).putExtra("focusKey",true));};}
        else if(workingCount>0){text=workingCount>1?"Transcribiendo "+workingCount+" audios":"Transcribiendo «"+workingTitle+"»"+(workingBlocks>1?" · "+workingDone+" de "+workingBlocks:"");icon=R.drawable.ic_wave;spin=true;click=v->{if(workingCount>1){filter=2;section(true);render();}else open(workingId);};}
        else if(failedCount>0){text=failedCount>1?failedCount+" necesitan atención":"Revisar transcripción";icon=R.drawable.ic_alert;alert=true;click=v->{if(failedCount>1){filter=4;section(true);render();}else open(failedId);};}
        else if(reviewId!=null){String id=reviewId;text="Revisar · «"+reviewTitle+"»";icon=R.drawable.ic_doc;fresh=true;click=v->open(id);}
        else{text="Listo para transcribir";icon=R.drawable.ic_check;quiet=true;click=v->startActivity(new Intent(this,SettingsActivity.class));}
        description=quiet?"Listo para transcribir. Abrir ajustes":!ready?"Configurar transcripción: agrega tu clave de OpenRouter":text;
        // Botón redondo de la cabecera: el ícono cambia con el estado; el punto marca un error o algo nuevo por revisar.
        statusIcon.setImageResource(icon);statusIcon.setImageTintList(ColorStateList.valueOf(alert?p.error:ready?p.primary:p.onSurface));
        statusIcon.setScaleX(spin?0.7f:1f);statusIcon.setScaleY(spin?0.7f:1f);statusRing.setVisibility(spin?View.VISIBLE:View.GONE);
        statusBadge.setVisibility(alert||fresh?View.VISIBLE:View.GONE);if(alert||fresh){GradientDrawable dot=oval(alert?p.record:p.primary);dot.setStroke(Math.max(1,ui.dp(1.5f)),p.surfaceContainerLowest);statusBadge.setBackground(dot);}
        statusButton.setOnClickListener(click);statusButton.setContentDescription(description);
        // Píldora bajo el saludo: solo si algo pide atención (su lugar queda reservado).
        attention.setVisibility(quiet?View.INVISIBLE:View.VISIBLE);setText(attentionText,text);attention.setOnClickListener(click);attention.setContentDescription(description);
        attentionRing.setVisibility(spin?View.VISIBLE:View.GONE);attentionIcon.setVisibility(spin?View.GONE:View.VISIBLE);
        attentionIcon.setImageResource(icon);attentionIcon.setImageTintList(ColorStateList.valueOf(alert?p.error:p.primary));
    }
    /** Cambia el texto solo si cambió: las regiones «en vivo» no se vuelven a anunciar en cada actualización. */
    private static void setText(TextView t,String value){if(!value.contentEquals(t.getText()))t.setText(value);}

    /** Pide volver a medir si todo cabe (se resuelve en fitHome cuando Grabar está a la vista y medida). */
    private void requestFit(){fitPending=true;if(homePanel.getVisibility()==View.VISIBLE)homePanel.post(this::fitHome);}
    /**
     * Un paso de ajuste. Nunca se vuelve a programar sola mientras espera: con Grabar oculto (Biblioteca) o sin medir
     * (p. ej. la app en segundo plano) queda pendiente y la retoman section(false) o la siguiente medición.
     * Reposo: si el micrófono queda apretado, primero «Tu semana» pasa a una franja y luego se oculta; después se
     * compacta por niveles: 1) el nombre más chico y sin el rótulo «Última grabación»; 2) sin «Toca para grabar» y el
     * micrófono un poco menor; 3) la tarjeta queda en una fila (tocarla abre).
     * Grabando: 1) cronómetro de 52 y título en 2 líneas; 2) título en 1 línea y sin la línea de ayuda; 3) sin el título
     * grande (sigue en la píldora ✎). Al cambiar el título (y al empezar cada grabación) se vuelve a medir desde 0.
     * Cada composición se ajusta al alto con que se ve (reposo con la barra de pestañas; grabando, sin ella) y lo recuerda:
     * esconder o mostrar la barra al grabar y al detener no rehace lo ya medido (nada parpadea).
     */
    private void fitHome(){
        if(!fitPending||homePanel.getVisibility()!=View.VISIBLE||homePanel.getHeight()==0||homePanel.isLayoutRequested())return;
        fitPending=false;int h=homePanel.getHeight();
        if(!shownActive){
            if(h!=idleFitH){idleFitH=h;if(weekMode!=0||compact!=0){weekMode=0;compact=0;applyCompact();requestFit();return;}}
            int free=center.getHeight(),need=center.need;
            if(weekMode<2&&free<need+ui.dp(CENTER_AIR)){weekMode++;applyCompact();requestFit();return;}
            if(free<need&&compact<3){compact++;applyCompact();requestFit();}
        }else{
            if(h!=recFitH){recFitH=h;if(recCompact!=0){recCompact=0;applyRecCompact();requestFit();return;}}
            if(waveZone.getHeight()<waveZone.need&&recCompact<3){recCompact++;applyRecCompact();requestFit();}
        }
    }
    private void applyCompact(){
        weekCard.setVisibility(weekMode>=2?View.GONE:View.VISIBLE);
        weekHead.setVisibility(weekMode==0?View.VISIBLE:View.GONE);weekStats.setVisibility(weekMode==0?View.VISIBLE:View.GONE);weekStrip.setVisibility(weekMode==1?View.VISIBLE:View.GONE);
        weekCard.setPadding(ui.dp(weekMode==1?S4:S3),ui.dp(weekMode==1?S1:S3),ui.dp(weekMode==1?S4:S3),ui.dp(weekMode==1?S1:S3));
        AppTheme.type(nameView,compact>=1?Type.HEADLINE_MEDIUM:Type.DISPLAY_SMALL);lastOverline.setVisibility(compact>=1?View.GONE:View.VISIBLE);
        prompt.setVisibility(compact>=2?View.GONE:View.VISIBLE);
        int box=ui.dp(compact>=2?MIC_BOX_COMPACT:MIC_BOX);micFrame.getLayoutParams().height=box;record.getLayoutParams().width=box;record.getLayoutParams().height=box;decor.setGap(box-ui.dp(12));micFrame.requestLayout();
        lastActions.setVisibility(compact>=3?View.GONE:View.VISIBLE);
    }
    private void applyRecCompact(){
        timer.setTextSize(recCompact>=1?52:68);
        bigTitle.setMaxLines(recCompact>=2?1:recCompact>=1?2:3);bigTitle.setVisibility(recCompact>=3?View.GONE:View.VISIBLE);
        hint.setVisibility(recCompact>=2?View.GONE:View.VISIBLE);
    }
    /** Bucle de UI: refleja el estado real del servicio de grabación. */
    private void update(){
        boolean active=RecorderService.activeId!=null,paused=RecorderService.paused;
        String state=active+":"+paused;
        if(!state.equals(lastState)){boolean animate=!lastState.isEmpty(),was=lastState.startsWith("true");lastState=state;starting=false;record.setEnabled(true);applyState(active,paused,animate,was);}
        if(active){
            long elapsed=RecorderService.elapsed(),now=SystemClock.uptimeMillis();boolean motion=AppTheme.motion();setText(timer,Recording.time(elapsed));
            // «● Grabando» late suave; en pausa el tiempo parpadea. Con «Quitar animaciones», todo queda quieto.
            float beat=motion?0.3f+0.7f*(0.5f+0.5f*(float)Math.cos(2*Math.PI*(now%1400)/1400.0)):1f;
            if(paused){timer.setAlpha(motion?((now/600)%2==0?1f:0.35f):0.55f);statusDot.setAlpha(1f);}
            else{
                timer.setAlpha(1f);float level=Waveform.normalize(RecorderService.amplitude());wave.push(level);levels.add(level);statusDot.setAlpha(beat);
                // Cobertura: cuenta el tramo desde el nivel anterior solo si fue seguido (menos de 1 s). Un salto mayor es
                // tiempo con la pantalla apagada o la app atrás, del que no hay niveles.
                long gap=elapsed-levelsLast;if(levelsLast>=0&&gap>0&&gap<1000)levelsMs+=gap;levelsLast=elapsed;
            }
            int marks=RecorderService.marksCount();if(marks!=shownMarks){boolean added=marks>shownMarks;shownMarks=marks;if(added)wave.mark();renderMarks(added);}
            String t=RecorderService.activeTitle;if(!Objects.equals(t,shownTitle)){shownTitle=t;renderRecTitle(t);}
            if(recPill.getVisibility()==View.VISIBLE){setText(pillTime,Recording.time(elapsed)+(paused?" · En pausa":" · Grabando"));pillDot.setAlpha(paused?1f:beat);}
        }
        if(RecorderService.error!=null){String m=RecorderService.error;RecorderService.error=null;starting=false;record.setEnabled(true);message("Grabación",m);}
        if(RecorderService.notice!=null&&!active){String m=RecorderService.notice;RecorderService.notice=null;toast(m);}
        String saved=RecorderService.lastSavedId;if(saved!=null&&!active){RecorderService.lastSavedId=null;afterSave(saved);}
        renderImport();
        int version=FilesStore.version.get();if(dataVersion!=version){dataVersion=version;load();}
    }
    /** Cambio de estado (reposo, grabando, en pausa): la composición cambia con un fundido; la barra se esconde al grabar. */
    private void applyState(boolean active,boolean paused,boolean animate,boolean was){
        shownActive=active;
        record.setRecording(active,animate);record.setContentDescription(active?"Detener y guardar":"Grabar");
        if(active!=was||!animate)swapLayers(active,animate);
        // Solo cuando el cambio pasa a la vista (no al volver a la app a mitad de una grabación).
        if(active!=was&&animate&&!showLibrary)moveReaderFocus(active);
        // En pausa, «Reanudar» se destaca (píldora blanca); la onda se congela atenuada y el tiempo parpadea.
        pause.label.setTextSize(15);pause.setText(paused?"Reanudar":"Pausa");pause.setIcon(paused?R.drawable.ic_play:R.drawable.ic_pause);pause.setContentDescription(paused?"Reanudar grabación":"Pausar");
        pause.setColors(paused?p.brandDeep:p.onVivid,paused?p.onVivid:p.glassOnVivid);
        statusDot.setBackground(oval(paused?p.onSurfaceVariant:p.record));statusDot.setAlpha(1f);setText(statusLabel,paused?"En pausa":"Grabando");
        timer.setAlpha(1f);if(!active)timer.setText("00:00");
        wave.setMode(active?(paused?Waveform.PAUSED:Waveform.LIVE):Waveform.IDLE);
        if(active&&!was){
            shownMarks=RecorderService.marksCount();shownTitle=null;renderRecTitle(null);renderMarks(false);
            // Los niveles son de ESTA grabación: al volver a la app a mitad de una grabación se conservan.
            if(!Objects.equals(levelsId,RecorderService.activeId)){levels.clear();levelsMs=0;levelsLast=-1;levelsId=RecorderService.activeId;}
        }
        renderHint(paused);
        setNavHidden(active&&!showLibrary);nav.badge(0,active);renderPill();
        if(active!=was)requestFit();
        if(!active||!was)load();
    }
    /** Cambia de composición con un fundido: la nueva entra subiendo apenas; la anterior se desvanece y deja de recibir toques. */
    private void swapLayers(boolean active,boolean animate){
        LinearLayout in=active?recLayer:idleLayer,out=active?idleLayer:recLayer;
        in.animate().cancel();out.animate().cancel();
        in.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);out.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        in.setVisibility(View.VISIBLE);
        if(!animate||!AppTheme.motion()){in.setAlpha(1f);in.setTranslationY(0f);out.setVisibility(View.INVISIBLE);out.setAlpha(1f);out.setTranslationY(0f);return;}
        in.setAlpha(0f);in.setTranslationY(ui.dp(S4));in.animate().alpha(1f).translationY(0f).setStartDelay(MOTION_FAST).setDuration(MOTION_SLOW).setInterpolator(EMPHASIZED_DECELERATE).start();
        out.animate().alpha(0f).translationY(-ui.dp(S2)).setStartDelay(0).setDuration(MOTION_BASE).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(()->{out.setVisibility(View.INVISIBLE);out.setAlpha(1f);out.setTranslationY(0f);}).start();
    }
    /**
     * Lector de pantalla: al cambiar de composición, la capa que sale queda oculta para accesibilidad y el foco que estaba
     * en «Grabar» (o en «Detener») se pierde; además la región en vivo no avisa, porque «Grabando» ya era su texto. En 0.6
     * era el mismo botón y el estado se anunciaba solo. Aquí el foco pasa al control equivalente de la capa nueva y, al
     * empezar, se dice «Grabando» (después del foco, para que TalkBack no lo corte al leer el botón).
     * Se espera al fundido: con alfa 0 la capa aún no cuenta como visible. Al detener se espera igual sin animaciones y se
     * mira el foco de la ventana: si se abrió «Nombra esta grabación», el foco es de la hoja. Sin lector, no hace nada.
     */
    private void moveReaderFocus(boolean active){
        handler.postDelayed(()->{
            if(active!=shownActive||showLibrary||!homePanel.hasWindowFocus())return;
            (active?stop:record).performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);
            if(active&&!RecorderService.paused)stop.announceForAccessibility("Grabando");
        },active&&!AppTheme.motion()?0:MOTION_FAST+MOTION_SLOW);
    }
    /** Título en la píldora ✎ y en grande (con la última palabra destacada). Sin título: «Añadir título» y «Nueva grabación». */
    private void renderRecTitle(String t){
        titlePillText.setText(t==null?"Añadir título":t);titlePill.setContentDescription(t==null?"Añadir título a la grabación":"Título: "+t+". Toca para cambiarlo");
        String big=t==null?"Nueva grabación":t;bigTitle.setContentDescription(null);
        // Un título muy largo va sin recuadro: con «…» al final, la palabra destacada quedaría cortada.
        if(big.length()>48)bigTitle.setText(big);else ui.highlightLast(bigTitle,big);
        // fitHome solo sube de nivel: lo compactado por un título largo se deshace aquí, así un título más corto (o la
        // grabación siguiente) recupera el cronómetro grande y la línea de ayuda. fitHome nunca llama aquí: no hay bucle.
        if(recCompact!=0){recCompact=0;applyRecCompact();}
        requestFit();
    }
    /** Línea de ayuda al grabar. Sin marcas también explica la ★ (en 0.6 llevaba el rótulo «Marcar»; ahora es solo un ícono). */
    private void renderHint(boolean paused){
        String text=paused?"En pausa · toca Reanudar para seguir o Detener para guardar."
            :shownMarks>0?shownMarks+(shownMarks==1?" momento ★":" momentos ★")+" · mantén ★ para anotar una palabra."
            :"Puedes bloquear el teléfono: la grabación sigue. Toca ★ para marcar un momento.";
        setText(hint,text);
    }
    private void renderMarks(boolean pop){
        int n=shownMarks;markBadge.setText(String.valueOf(n));markBadge.setVisibility(n>0?View.VISIBLE:View.GONE);
        mark.setContentDescription(n==0?"Marcar momento":"Marcar momento. "+n+(n==1?" momento marcado":" momentos marcados"));
        if(pop&&n>0){if(AppTheme.motion()){markBadge.setScaleX(1.4f);markBadge.setScaleY(1.4f);markBadge.animate().scaleX(1f).scaleY(1f).setDuration(SPATIAL_FAST.duration).setInterpolator(SPATIAL_FAST).start();}mark.announceForAccessibility("Momento marcado");}
        renderHint(RecorderService.paused);
    }
    /** Avance de una importación en curso en la píldora «Importar audio» (la importación sigue aunque salgas de esa pantalla). */
    private void renderImport(){
        String text=null;ImportSession s=ImportService.session(this);
        if(s!=null&&!s.done&&!s.cancelled&&!s.stale()){if(s.busy)text="Importando…"+(s.total>0?" "+ImportService.percent(s)+" %":"");else if(!s.error.isEmpty())text="Importación pendiente";else if(s.ready)text="Audio listo para guardar";}
        String key=text==null?"":text;if(key.equals(shownImport))return;shownImport=key;
        importLabel.setText(text==null?"Importar audio":text);importLabel.setTextColor(text==null?p.onSurface:p.primary);
        importPill.setContentDescription(text==null?"Importar audio de WhatsApp, grabadoras o archivos":"Importar audio: "+text);
    }
    private void onRecordTap(View v){
        if(RecorderService.activeId!=null){
            // Contra el doble toque accidental: Detener se ignora durante el primer segundo.
            long since=RecorderService.startedAt();
            if(since>0&&SystemClock.elapsedRealtime()-since<1000){Ui.haptic(v,Ui.Haptic.REJECT);Diagnostics.event("stop_ignored",RecorderService.activeId);return;}
            Ui.haptic(v,Ui.Haptic.CONFIRM);send("STOP");
        }else{Ui.haptic(v,Ui.Haptic.CONFIRM);begin();}
    }
    /** ★ Marcar: el servicio guarda el momento (también funciona desde la notificación). at = ms de la grabación si se tomó antes. */
    private void markMoment(View v,String label,long at){
        if(RecorderService.activeId==null)return;Ui.haptic(v,Ui.Haptic.CONFIRM);
        Intent intent=new Intent(this,RecorderService.class).setAction("MARK");if(label!=null)intent.putExtra("label",label);if(at>=0)intent.putExtra("t",at);
        try{startService(intent);}catch(RuntimeException e){toast("No se pudo marcar el momento.");}
    }
    private void markWithWord(View v){
        if(RecorderService.activeId==null)return;long at=RecorderService.elapsed();Ui.haptic(v,Ui.Haptic.TICK);
        EditText input=ui.field("Ej. precio, idea, tarea","Palabra para este momento");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Sheet s=sheet("Marcar con una palabra","Momento "+Recording.time(at)+". La grabación sigue mientras escribes.").add(input);
        Runnable apply=()->{String w=input.getText().toString().trim();markMoment(mark,w.isEmpty()?null:w,at);};
        s.primary("Marcar",apply).secondary("Cancelar",null).show();
        input.setOnEditorActionListener((view,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){s.dismiss();apply.run();return true;}return false;});
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void titleWhileRecording(){
        EditText input=ui.field("Ej. Reunión con el equipo","Título de la grabación");String current=RecorderService.activeTitle;if(current!=null)input.setText(current);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        Sheet s=sheet("Título de la grabación","La grabación sigue mientras escribes.").add(input);
        s.primary("Guardar título",Ui.Style.PRIMARY,()->{String t=input.getText().toString().trim();if(!t.isEmpty())startService(new Intent(this,RecorderService.class).setAction("TITLE").putExtra("title",t));return true;}).secondary("Cancelar",null).show();
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    /**
     * «Nombra esta grabación» (al detener): un mini reproductor con la onda de lo recién grabado, y el nombre ya enfocado
     * con el teclado abierto. La fecha (si está activa en Ajustes) va fija delante y solo se escribe el resto.
     * «Ver grabación» es lo principal; «Listo», ✕ o cerrar la hoja guardan el título igual.
     */
    private void afterSave(String id){
        Recording r=FilesStore.recording(this,id);if(r==null)return;Settings settings=new Settings(this);
        // La onda chica sale de lo que se dibujó en vivo (reducida a ~40 barras), sin volver a leer el audio. Solo si esos
        // niveles cubren la grabación (falta a lo más 1 s o un 10 %): si bloqueaste el teléfono o saliste de la app, lo
        // juntado es de un tramo y estirarlo sobre todo el audio mostraría una onda que no es la que suena. Ahí va plana.
        boolean whole=r.duration-levelsMs<=Math.max(1000,r.duration/10);
        float[] bars=whole?levels.bars(40):new float[0];levels.clear();levelsMs=0;levelsLast=-1;levelsId=null;
        if(!settings.askTitle()){toast("Guardado en Biblioteca · "+Recording.time(r.duration));return;}
        boolean auto=settings.automatic()&&settings.hasOpenRouterKey();int marks=0;try{marks=Marks.list(this,r.id).length();}catch(RuntimeException ignored){}
        String prefix="",rest=r.title==null?"":r.title;
        if(settings.datePrefix()){Matcher m=DATE.matcher(rest);if(m.find()){prefix=m.group(1);rest=rest.substring(m.end()).trim();}}
        if(namePlayer!=null)namePlayer.release();
        Sheet s=sheet("Nombra esta grabación",null);
        // Mini reproductor en una caja gris suave: ▶ redondo de tinta, la onda y «● 12:48».
        LinearLayout player=ui.row();player.setBackground(shape(this,p.dark?p.surfaceContainerHigh:p.surfaceContainer,R_CONTROL+4));player.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S4),ui.dp(S1));
        ImageButton play=ui.iconButton(R.drawable.ic_play,"Escuchar la grabación",p.onInk,0,48);int in=ui.dp(S1);
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onInk)),new InsetDrawable(oval(p.ink),in),new InsetDrawable(oval(0xFF000000),in)));
        player.addView(play,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));player.addView(ui.space(S2));
        Waveform.Mini mini=new Waveform.Mini(this,bars,p.outline,p.primary);player.addView(mini,new LinearLayout.LayoutParams(0,ui.dp(32),1));player.addView(ui.space(S3));
        View dot=new View(this);dot.setBackground(oval(p.record));player.addView(dot,new LinearLayout.LayoutParams(ui.dp(6),ui.dp(6)));player.addView(ui.space(S2));
        TextView time=Ui.tabular(ui.text(Recording.time(r.duration),Type.LABEL_LARGE,p.onSurface));time.setContentDescription("Duración "+Ui.humanDuration(r.duration));player.addView(time);
        s.body.addView(player,Ui.fill());
        NamePlayer np=new NamePlayer(r,play,mini,time);namePlayer=np;play.setOnClickListener(v->np.toggle());
        // El nombre, con la fecha fija delante (si está activa).
        LinearLayout box=ui.row();box.setBackground(ui.fieldBackground());box.setPadding(ui.dp(S4),0,ui.dp(S2),0);box.setMinimumHeight(ui.dp(56));
        if(!prefix.isEmpty()){TextView date=Ui.tabular(ui.text(prefix,Type.BODY_LARGE,p.onSurfaceVariant));date.setContentDescription("Fecha "+prefix+", fija");box.addView(date);box.addView(ui.space(S2));}
        EditText input=ui.field("Nombre","Título de la grabación");input.setBackground(null);input.setPadding(0,ui.dp(S3),0,ui.dp(S3));input.setText(rest);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        s.body.addView(box,ui.top(S4));
        String detail="Guardada en este teléfono"+(marks>0?" · "+marks+(marks==1?" momento ★":" momentos ★"):"")+(auto?" · se transcribirá automáticamente":"");
        TextView d=ui.text(detail,Type.BODY_SMALL,p.onSurfaceVariant);d.setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),0);s.body.addView(d,Ui.fill());
        String date=prefix;
        Runnable saveTitle=()->{
            String t=input.getText().toString().trim();if(t.isEmpty())return;
            String full=date.isEmpty()||DATE.matcher(t).find()?t:date+" "+t;if(full.equals(r.title))return;
            r.title=full;try{r.save(this);Pipeline.edited(this,r.id);Diagnostics.event("title_edited",r.id);}catch(Exception ignored){}load();
        };
        s.closable(saveTitle).primary("Ver grabación",()->{saveTitle.run();open(r.id);}).secondary("Listo",saveTitle).onDismiss(()->{np.release();if(namePlayer==np)namePlayer=null;saveTitle.run();}).show();
        input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){s.dismiss();return true;}return false;});
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    /** Mini reproductor de «Nombra esta grabación» (▶/❚❚): se libera al cerrar la hoja, al salir de la app y en onDestroy. */
    private final class NamePlayer {
        private final Recording r;private final ImageButton play;private final Waveform.Mini mini;private final TextView time;private MediaPlayer mp;private int length=1;
        private final Runnable tick=new Runnable(){@Override public void run(){if(mp==null)return;try{int pos=mp.getCurrentPosition();mini.setPlayed(pos/(float)length);setText(time,Recording.time(pos));if(mp.isPlaying())handler.postDelayed(this,50);}catch(IllegalStateException ignored){}}};
        NamePlayer(Recording r,ImageButton play,Waveform.Mini mini,TextView time){this.r=r;this.play=play;this.mini=mini;this.time=time;}
        void toggle(){
            try{
                if(mp==null){mp=new MediaPlayer();mp.setDataSource(r.audio(MainActivity.this).getPath());mp.prepare();length=Math.max(1,mp.getDuration()>0?mp.getDuration():(int)r.duration);mp.setOnCompletionListener(m->release());}
                if(mp.isPlaying()){mp.pause();handler.removeCallbacks(tick);}else{mp.start();handler.removeCallbacks(tick);handler.post(tick);}
                show(mp.isPlaying());
            }catch(Exception e){release();toast("No se pudo reproducir el audio.");}
        }
        void release(){
            handler.removeCallbacks(tick);if(mp!=null){try{mp.release();}catch(RuntimeException ignored){}mp=null;}
            show(false);mini.setPlayed(0f);setText(time,Recording.time(r.duration));
        }
        private void show(boolean playing){play.setImageResource(playing?R.drawable.ic_pause:R.drawable.ic_play);play.setContentDescription(playing?"Pausar":"Escuchar la grabación");}
    }
    private void begin(){
        if(starting)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            // Verdadero: lo grabado queda en el teléfono, pero para transcribirse se envía a OpenRouter (no «se guarda aquí» a secas).
            sheet("Permitir el micrófono","Verbapp usa el micrófono solo mientras grabas. El audio queda en tu teléfono hasta que lo transcribes.").primary("Continuar",()->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).secondary("Ahora no",null).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        starting=true;record.setEnabled(false);shownMarks=0;
        try{startForegroundService(new Intent(this,RecorderService.class).setAction("START"));}
        catch(RuntimeException e){starting=false;record.setEnabled(true);message("Grabación","No se pudo iniciar el micrófono. Vuelve a intentarlo con la app abierta.");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==11)begin();
        else if(request==10){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)begin();
            else sheet("El micrófono está desactivado","Actívalo en los ajustes de Android para poder grabar.").primary("Abrir ajustes",()->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).secondary("Cerrar",null).show();
        }
    }
    private void send(String action){startService(new Intent(this,RecorderService.class).setAction(action));}
    /**
     * Bienvenida de la primera instalación (0.8.0): OnboardingActivity se abre encima, sin animación de entrada. Reemplaza
     * a la hoja «Bienvenido a Verbapp». Mientras tanto Grabar queda armada pero transparente: detrás se ve el mismo fondo
     * verde, así esta pantalla no asoma antes de la bienvenida y, al volver, su contenido aparece sobre el fondo que ya estaba.
     */
    private void startWelcome(){
        welcoming=true;welcomeLeft=false;for(int i=0;i<root.getChildCount();i++)root.getChildAt(i).setAlpha(0f);
        OnboardingActivity.open(this,false);overridePendingTransition(0,0);
    }
    /**
     * Vuelta de la bienvenida (terminada o saltada; si se salió con Atrás en su primer paso, la app se cerró y esto no
     * corre): Grabar aparece con un fundido, el saludo toma el nombre recién escrito y la versión instalada se da por
     * vista, para que «Novedades» no salga encima de quien recién instaló.
     */
    private void endWelcome(){
        welcoming=false;welcomeLeft=false;boolean motion=AppTheme.motion();
        for(int i=0;i<root.getChildCount();i++){View v=root.getChildAt(i);v.animate().cancel();if(motion)v.animate().alpha(1f).setStartDelay(0).setDuration(MOTION_SLOW).setInterpolator(EMPHASIZED_DECELERATE).start();else v.setAlpha(1f);}
        Settings settings=new Settings(this);int code=Novedades.versionCode(this);if(code>settings.lastSeenVersion())settings.setLastSeenVersion(code);
        myName=settings.prefs.getString("myVoiceName","");
    }

    // ---------- Última grabación ----------
    private void renderLast(){
        Item i=items.isEmpty()?null:items.get(0);lastLead.removeAllViews();
        if(i==null){
            lastLead.addView(leadCircle(R.drawable.ic_mic_fill,1,44,22),new FrameLayout.LayoutParams(-1,-1));
            setText(lastTitle,"Aún no hay grabaciones");setText(lastStatus,"Graba una idea o trae un audio de WhatsApp.");lastStatus.setTextColor(p.onSurfaceVariant);
            lastAction("Cómo traer un audio de WhatsApp",R.drawable.ic_chat,Ui.Style.TONAL,v->whatsappHelp());
            lastCard.setOnClickListener(v->whatsappHelp());lastCard.setContentDescription("Última grabación: aún no hay grabaciones");return;
        }
        // tone: 0 = en calma (vidrio), 1 = pide acción o está en curso (menta), 2 = error.
        Recording r=i.r;String dur=Ui.humanDuration(r.duration);int icon=R.drawable.ic_doc,tone=0;int statusColor=p.onSurfaceVariant;
        String status,label;int actionIcon;Ui.Style style;View.OnClickListener action;float bar=Float.NaN;
        View.OnClickListener transcribe=v->{Ui.haptic(v,Ui.Haptic.CONFIRM);RecordingActions.transcribe(this,r,this::load);};
        switch(i.status.kind){
            case QUEUED:icon=R.drawable.ic_clock;tone=1;status=i.progressText(queueBlocker);statusColor=p.primary;bar=i.progress();label="Ver avance";actionIcon=R.drawable.ic_clock;style=Ui.Style.TONAL;action=v->open(r.id);break;
            case FAILED:icon=R.drawable.ic_alert;tone=2;status="No se pudo transcribir";statusColor=p.error;label="Reintentar";actionIcon=R.drawable.ic_refresh;style=Ui.Style.PRIMARY;action=transcribe;break;
            case NEW:icon=R.drawable.ic_wave;status=dur+" · Sin transcribir";label="Transcribir";actionIcon=R.drawable.ic_sparkle;style=Ui.Style.PRIMARY;action=transcribe;break;
            default:{
                Next.Step step=i.next!=null?i.next.step:derivedStep(i);String next=i.next!=null?i.next.label:null;String folder=folderName();
                switch(step){
                    case REVIEW:{Meta m=i.meta;int n=m==null?0:m.names.size();icon=R.drawable.ic_people;tone=1;status=dur+" · Lista · "+(n>1?n+" voces por revisar":"voces por revisar");label=next!=null?next:"Revisar voces";actionIcon=R.drawable.ic_people;style=Ui.Style.PRIMARY;action=v->startActivity(new Intent(this,RecordingActivity.class).putExtra("id",r.id).putExtra("names",true));break;}
                    case SAVE:icon=R.drawable.ic_inbox;tone=1;status=dur+" · Lista · por guardar";label=next!=null?next:"Guardar en "+folder;actionIcon=R.drawable.ic_save;style=Ui.Style.PRIMARY;action=v->saveInbox(r);break;
                    case UPDATE:icon=R.drawable.ic_refresh;tone=1;status=dur+" · Hay cambios sin guardar";label=next!=null?next:"Actualizar en "+folder;actionIcon=R.drawable.ic_refresh;style=Ui.Style.PRIMARY;action=v->saveInbox(r);break;
                    case CHOOSE_FOLDER:status=dur+" · Transcripción lista";label=next!=null?next:"Elegir carpeta rápida";actionIcon=R.drawable.ic_folder;style=Ui.Style.TONAL;action=v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true).putExtra("inbox",true));break;
                    default:status=dur+" · ✓ "+(step==Next.Step.SAVED&&next!=null?next:"Transcripción lista");label="Abrir";actionIcon=R.drawable.ic_doc;style=Ui.Style.TONAL;action=v->open(r.id);
                }
            }
        }
        // Transcribiendo: el ícono va dentro de un anillo con el avance real (gira si todavía no se sabe).
        lastLead.addView(Float.isNaN(bar)?leadCircle(icon,tone,44,22):progressLead(bar,icon,20,3),new FrameLayout.LayoutParams(-1,-1));if(i.fresh)lastLead.addView(newDot(),new FrameLayout.LayoutParams(ui.dp(12),ui.dp(12),Gravity.TOP|Gravity.END));
        setText(lastTitle,r.title);setText(lastStatus,status);lastStatus.setTextColor(statusColor);
        lastAction(label,actionIcon,style,action);
        lastCard.setOnClickListener(v->open(r.id));lastCard.setContentDescription("Última grabación: "+r.title+". "+status+". Toca para abrirla");
    }
    /** Siguiente paso si Next no respondió (misma lógica: revisar → guardar → actualizar → guardada). */
    private static Next.Step derivedStep(Item i){if(i.toReview())return Next.Step.REVIEW;if(!i.inbox)return Next.Step.CHOOSE_FOLDER;if(i.savedAt==0)return Next.Step.SAVE;if(i.outdated)return Next.Step.UPDATE;return Next.Step.SAVED;}
    /**
     * Un solo botón: se reconstruye solo si cambia el paso (así no parpadea con cada actualización). El primero llega con
     * la lectura del disco, a veces después de que fitHome ya midió: con letra grande mide más que los 48 dp reservados,
     * así que se vuelve a medir si todo cabe (si no, la tarjeta crece y aplasta «Importar audio»).
     */
    private void lastAction(String label,int icon,Ui.Style style,View.OnClickListener click){
        String key=label+"|"+icon+"|"+style;
        if(lastButton==null||!key.equals(lastKey)){lastKey=key;lastActions.removeAllViews();lastButton=ui.button(label,icon,style,click);lastButton.setMinimumHeight(ui.dp(48));lastButton.label.setSingleLine(true);lastButton.label.setEllipsize(TextUtils.TruncateAt.END);lastActions.addView(lastButton,Ui.fill());requestFit();}
        else{lastButton.setOnClickListener(click);if(!savingInbox&&!label.contentEquals(lastButton.label.getText()))lastButton.setText(label);}
        lastButton.setEnabled(!savingInbox);
    }
    /** Guardar en la carpeta rápida (0-Inbox) en segundo plano; el resultado llega como aviso breve y con vibración. */
    private void saveInbox(Recording r){
        if(savingInbox)return;savingInbox=true;if(lastButton!=null){lastButton.setEnabled(false);lastButton.setText("Guardando…");}
        Context app=getApplicationContext();
        new Thread(()->{
            String folder=Inbox.folderName(app),message;boolean ok=false;
            try{Inbox.save(app,r);ok=true;message="✓ Guardado en "+folder;}
            catch(UnsupportedOperationException e){message=e.getMessage()!=null?e.getMessage():"El guardado rápido aún no está disponible.";}
            catch(Exception e){message="No se pudo guardar en "+folder+". Revisa la carpeta rápida en Ajustes.";Diagnostics.event("inbox_save_failed",r.id,"error_class",e.getClass().getSimpleName());}
            boolean done=ok;String text=message;
            runOnUiThread(()->{savingInbox=false;if(isDestroyed())return;toast(text);Ui.haptic(lastCard,done?Ui.Haptic.CONFIRM:Ui.Haptic.REJECT);lastKey="";load();});
        },"Voz-inbox").start();
    }
    private String folderName(){try{return Inbox.folderName(this);}catch(RuntimeException e){return "0-Inbox";}}

    // ================= BIBLIOTECA =================
    private void buildLibrary(){
        largeTitle(libraryPanel,"Biblioteca",null);
        libraryCount=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);libraryCount.setPadding(ui.dp(S1),0,0,ui.dp(S4));libraryPanel.addView(libraryCount);
        ((LinearLayout.LayoutParams)libraryPanel.getChildAt(0).getLayoutParams()).bottomMargin=0;libraryPanel.getChildAt(0).setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(2));
        // Búsqueda en una píldora blanca con sombra suave (como las píldoras de la referencia).
        LinearLayout box=ui.row();GradientDrawable field=ui.fieldBackground();field.setCornerRadius(ui.dp(R_FULL));box.setBackground(field);box.setPadding(ui.dp(S4),0,ui.dp(S1),0);
        if(!p.dark){box.setElevation(ui.dp(1));if(Build.VERSION.SDK_INT>=28){box.setOutlineSpotShadowColor(p.shadow);box.setOutlineAmbientShadowColor(p.shadow);}}
        box.addView(ui.icon(R.drawable.ic_search,p.onSurfaceVariant,20));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Buscar por título");search.setContentDescription("Buscar grabaciones por título");search.setTextColor(p.onSurface);search.setHintTextColor(p.onSurfaceVariant);AppTheme.type(search,Type.BODY_LARGE);search.setBackground(null);search.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S2),ui.dp(S3));search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){hideKeyboard();return true;}return false;});
        search.setOnFocusChangeListener((v,focused)->v.post(this::checkIme));
        box.addView(search,new LinearLayout.LayoutParams(0,ui.dp(52),1));ImageButton clear=ui.iconButton(R.drawable.ic_close,"Borrar búsqueda",p.onSurfaceVariant,0,48);clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);clear.setOnClickListener(v->search.setText(""));box.addView(clear);
        libraryPanel.addView(box,Ui.fill());
        search.setText(query);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);render();}public void afterTextChanged(Editable e){}});
        // Filtros (píldoras) que llegan hasta el borde de la pantalla al desplazarse.
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.setClipToPadding(false);filters=ui.row();filters.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S1));hs.addView(filters);
        LinearLayout.LayoutParams hl=Ui.fill();hl.setMarginStart(-ui.dp(S4));hl.setMarginEnd(-ui.dp(S4));libraryPanel.addView(hs,hl);
        page.setClipToPadding(false);page.setClipChildren(false);libraryPanel.setClipChildren(false);
        list=ui.column();libraryPanel.addView(list,Ui.fill());
        buildPill();
    }
    /** Cápsula de tinta arriba de la Biblioteca mientras grabas: «● 12:34 · Grabando» y «Detener». Tocarla vuelve a Grabar. */
    private void buildPill(){
        recPill=ui.row();recPill.setBackground(ui.ripple(shape(this,p.ink,R_FULL),R_FULL));recPill.setPadding(ui.dp(S5),0,ui.dp(S1),0);recPill.setMinimumHeight(ui.dp(56));
        recPill.setElevation(p.dark?0:ui.dp(6));if(Build.VERSION.SDK_INT>=28&&!p.dark){recPill.setOutlineSpotShadowColor(p.shadow);recPill.setOutlineAmbientShadowColor(p.shadow);}
        pillDot=new View(this);pillDot.setBackground(oval(p.record));recPill.addView(pillDot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));recPill.addView(ui.space(S3));
        pillTime=Ui.tabular(ui.oneLine(ui.text("00:00",Type.LABEL_LARGE,p.onInk)));pillTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);recPill.addView(pillTime,new LinearLayout.LayoutParams(0,-2,1));
        // «Detener»: píldora translúcida dentro de la cápsula (36 dp visibles, 48 dp para tocar).
        TextView stopPill=ui.text("Detener",Type.LABEL_LARGE,p.onInk);stopPill.setGravity(Gravity.CENTER);stopPill.setMinHeight(ui.dp(48));stopPill.setMinWidth(ui.dp(48));
        Drawable square=getDrawable(R.drawable.ic_stop).mutate();square.setTint(p.onInk);square.setBounds(0,0,ui.dp(16),ui.dp(16));stopPill.setCompoundDrawablesRelative(square,null,null,null);stopPill.setCompoundDrawablePadding(ui.dp(6));
        int inset=ui.dp(6);stopPill.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.stateLayer(p.onInk)),new InsetDrawable(shape(this,withAlpha(p.onInk,0x29),R_FULL),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_FULL),0,inset,0,inset)));
        // El relleno va DESPUÉS del fondo (igual que con pill()): el InsetDrawable trae su propio relleno y setBackground
        // reemplaza el que hubiera; antes los lados quedaban en 0 (el ■ y la «r» pegados al borde redondeado).
        stopPill.setPadding(ui.dp(S4),0,ui.dp(S4),0);
        stopPill.setClickable(true);stopPill.setFocusable(true);stopPill.setAccessibilityDelegate(Ui.buttonRole());stopPill.setContentDescription("Detener y guardar la grabación");
        stopPill.setOnClickListener(v->{if(RecorderService.activeId!=null)onRecordTap(v);});recPill.addView(stopPill);
        recPill.setClickable(true);recPill.setFocusable(true);recPill.setAccessibilityDelegate(Ui.buttonRole());recPill.setContentDescription("Grabación en curso. Toca para ir a Grabar");recPill.setOnClickListener(v->section(false));
        LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S2));root.addView(recPill,Math.max(0,root.indexOfChild(scroll)),lp);recPill.setVisibility(View.GONE);
    }
    private void renderPill(){
        if(recPill==null)return;boolean show=showLibrary&&RecorderService.activeId!=null;recPill.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){boolean paused=RecorderService.paused;pillDot.setBackground(oval(paused?p.onInk:p.record));setText(pillTime,Recording.time(RecorderService.elapsed())+(paused?" · En pausa":" · Grabando"));}
    }
    /**
     * La barra de pestañas se esconde mientras el teclado de la búsqueda está abierto (más espacio para los resultados).
     * Solo con la búsqueda: el teclado de una hoja (p. ej. el título) no debe mover la pantalla de Grabar que queda detrás.
     * Al cerrarlo vuelve, salvo en Grabar mientras grabas (pantalla completa).
     */
    private void checkIme(){
        boolean ime;
        if(Build.VERSION.SDK_INT>=30){WindowInsets w=root.getRootWindowInsets();ime=w!=null&&w.isVisible(WindowInsets.Type.ime());}
        else{Rect r=new Rect();root.getWindowVisibleDisplayFrame(r);ime=root.getRootView().getHeight()-r.bottom>ui.dp(160);}
        boolean hide=ime&&showLibrary&&search!=null&&search.hasFocus();
        if(hide==imeShown)return;imeShown=hide;if(nav!=null)nav.setVisibility(hide||(!showLibrary&&shownActive)?View.GONE:View.VISIBLE);
    }
    private void hideKeyboard(){if(search==null)return;InputMethodManager imm=getSystemService(InputMethodManager.class);if(imm!=null)imm.hideSoftInputFromWindow(search.getWindowToken(),0);if(search.hasFocus()){search.clearFocus();root.requestFocus();}}

    /**
     * Relee la lista en el hilo de disco. Durante una transcripción se pide muy seguido: las lecturas en cola que ya
     * quedaron atrás se saltan (solo corre la más reciente) y cada lectura que termina se muestra, en orden.
     * También cuenta «Tu semana» y lee tu nombre para el saludo.
     */
    private void load(){
        int version=++loadVersion;Context app=getApplicationContext();
        disk.execute(()->{
            if(version!=loadVersion)return;
            boolean inbox=false;try{inbox=Inbox.configured(app);}catch(RuntimeException ignored){}
            String blocker=null;try{blocker=Pipeline.blocker(app);}catch(RuntimeException ignored){}
            String name=null;try{name=Voices.name(app);}catch(RuntimeException ignored){}
            long since=newSince(app),weekFrom=System.currentTimeMillis()-7*DateUtils.DAY_IN_MILLIS;ArrayList<Item> loaded=new ArrayList<>(),missing=new ArrayList<>();Week wk=new Week();
            for(Recording r:Recording.list(app)){
                Item i=new Item(r,FilesStore.state(app,r.id),Transcript.exists(app,r.id));i.inbox=inbox;
                if(i.transcribed){
                    File f=FilesStore.file(app,r.id,".transcript.json");long modified=f.lastModified();
                    Meta m=METAS.get(r.id);if(m!=null&&m.modified==modified&&m.length==f.length())i.meta=m;else missing.add(i);
                    // «Nuevo»: transcrita después de instalar esta versión y aún no abierta.
                    i.fresh=!i.state.has("opened")&&modified>since;
                    try{i.savedAt=Inbox.savedAt(app,r.id);i.outdated=i.savedAt>0&&Inbox.outdated(app,r.id);}catch(Exception ignored){}
                }
                if(r.created>=weekFrom){wk.count++;wk.ms+=Math.max(0,r.duration);if(FilesStore.file(app,r.id,".note.json").isFile())wk.notes++;}
                loaded.add(i);
            }
            if(!loaded.isEmpty())try{loaded.get(0).next=Next.of(app,loaded.get(0).r);}catch(Throwable ignored){}
            // Primero la lista rápida; después, lo que hay que leer de las transcripciones (fragmento, personas y colores).
            if(!missing.isEmpty())publish(loaded,inbox,blocker,wk,name);
            // Aunque llegue otra lectura, se termina: lo leído queda en METAS y la siguiente ya no lo relee.
            for(Item i:missing){Meta m=readMeta(app,i.r.id);METAS.put(i.r.id,m);i.meta=m;storeSnippet(app,i,m);}
            publish(loaded,inbox,blocker,wk,name);
        });
    }
    /** Un solo hilo de disco (en orden): lo publicado siempre es más nuevo que lo anterior, así que no se descarta. */
    private void publish(ArrayList<Item> loaded,boolean inbox,String blocker,Week wk,String name){ArrayList<Item> copy=new ArrayList<>(loaded);runOnUiThread(()->{if(!isDestroyed()){items=copy;inboxOn=inbox;queueBlocker=blocker;week=wk;if(name!=null)myName=name;render();}});}
    private static Meta readMeta(Context c,String id){
        File f=FilesStore.file(c,id,".transcript.json");Meta m=new Meta(f.lastModified(),f.length());
        try{Transcript t=Transcript.load(c,id);m.diarized=t.diarized();m.reviewed=t.reviewed();m.snippet=t.snippet(120);
            if(m.diarized)for(Map.Entry<String,String> e:t.speakers().entrySet()){if(m.names.contains(e.getValue()))continue;m.names.add(e.getValue());m.colors.add(Math.max(0,t.colorIndex(e.getKey())));}
        }catch(Exception ignored){}
        return m;
    }
    /** Transcripciones anteriores a 0.6.0: se guarda su fragmento en el estado (una vez) para no tener que calcularlo de nuevo. */
    private static void storeSnippet(Context c,Item i,Meta m){if(m.snippet.isEmpty()||!i.state.optString("snippet").isEmpty())return;try{FilesStore.update(c,i.r.id,s->{if(s.optString("snippet").isEmpty())s.put("snippet",m.snippet);});}catch(Exception ignored){}}
    /** Momento desde el que se marcan transcripciones «nuevas» (la primera vez que corre esta versión), para no marcar las antiguas. */
    private static long newSince(Context c){SharedPreferences sp=c.getSharedPreferences("home",Context.MODE_PRIVATE);long v=sp.getLong("newSince",0);if(v==0){v=System.currentTimeMillis();sp.edit().putLong("newSince",v).apply();}return v;}
    private boolean matches(Item i,int f){switch(f){case 1:return inboxOn?i.toSave():i.done();case 2:return i.status.kind==RecState.Kind.QUEUED;case 3:return i.status.kind==RecState.Kind.NEW;case 4:return i.status.kind==RecState.Kind.FAILED;default:return true;}}
    private String signature(){
        StringBuilder b=new StringBuilder().append(filter).append('|').append(query).append('|').append(inboxOn).append('|').append(queueBlocker).append('|').append(TranscribeService.running).append('|').append(Calendar.getInstance().get(Calendar.DAY_OF_YEAR));
        for(Item i:items){Meta m=i.meta;b.append('\n').append(i.r.id).append(i.r.title).append(i.r.duration).append(i.status.kind).append(i.blocks()).append(i.blocksDone()).append(i.state.optLong("doneAudioMs")).append(i.savedAt).append(i.outdated).append(i.fresh).append(i.state.optString("snippet"));if(m!=null)b.append(m.snippet).append(m.names).append(m.colors).append(m.reviewed);}
        return b.toString();
    }
    private void render(){
        int[] counts=new int[5];int done=0;for(Item i:items){if(i.done())done++;for(int f=0;f<5;f++)if(matches(i,f))counts[f]++;}
        // Grabar: estado, «Última grabación», «Tu semana» y el saludo.
        Item working=null,failed=null,review=null;for(Item i:items){if(i.status.kind==RecState.Kind.QUEUED&&working==null)working=i;if(i.status.kind==RecState.Kind.FAILED&&failed==null)failed=i;if(i.fresh&&review==null)review=i;}
        workingCount=counts[2];failedCount=counts[4];workingTitle=working==null?"":working.r.title;workingId=working==null?null:working.r.id;workingBlocks=working==null?0:working.blocks();workingDone=working==null?0:working.blocksDone();
        failedId=failed==null?null:failed.r.id;reviewId=review==null?null:review.r.id;reviewTitle=review==null?"":review.r.title;renderChip();
        nav.badge(1,counts[2]>0);renderLast();renderWeek();renderGreeting();
        // Biblioteca (solo se rearma si cambió algo: no salta mientras se actualiza el avance de otra grabación).
        String sig=signature();if(sig.equals(rendered))return;rendered=sig;
        libraryCount.setText(items.isEmpty()?"Tus audios y transcripciones":items.size()+(items.size()==1?" grabación":" grabaciones")+(inboxOn&&counts[1]>0?" · "+counts[1]+" por guardar":" · "+done+(done==1?" transcrita":" transcritas")));
        filters.removeAllViews();String[] names={"Todas",inboxOn?"Por guardar":"Transcritas","En proceso","Sin transcribir","Con error"};
        if(filter>0&&counts[filter]==0)filter=0;
        for(int f=0;f<5;f++){if(f>0&&counts[f]==0)continue;int index=f;
            // Tocar el filtro activo lo desactiva (vuelve a «Todas»).
            TextView chip=ui.filter(names[f]+(f>0?" "+counts[f]:""),f==filter,v->{filter=filter==index?0:index;Ui.haptic(v);render();});LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));filters.addView(chip,lp);}
        ((View)filters.getParent()).setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);
        list.removeAllViews();String q=query.toLowerCase(ES).trim();String currentSection=null;LinearLayout group=null;int visible=0;
        for(Item i:items){
            if(!matches(i,filter)||!i.r.title.toLowerCase(ES).contains(q))continue;visible++;
            String sec=dateSection(i.r.created);if(!sec.equals(currentSection)){currentSection=sec;TextView h=ui.section(sec);h.setPadding(ui.dp(S1),ui.dp(S5),0,ui.dp(S2));list.addView(h);group=ui.group();list.addView(group,Ui.fill());}
            addRow(group,row(i));
        }
        if(visible==0)list.addView(empty(items.isEmpty()));
    }
    private void addRow(LinearLayout group,View row){if(group.getChildCount()>0)group.addView(ui.separator(S4+40+S4));group.addView(row,Ui.fill());}
    /** Estado vacío: un círculo menta grande con su ícono dentro de un halo suave (el mismo gesto del micrófono). */
    private View empty(boolean nothing){
        LinearLayout e=ui.column();e.setGravity(Gravity.CENTER_HORIZONTAL);e.setPadding(ui.dp(S6),ui.dp(S8),ui.dp(S6),ui.dp(S6));
        FrameLayout art=new FrameLayout(this);art.setBackground(oval(withAlpha(p.primary,0x14)));art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        art.addView(ui.tile(nothing?R.drawable.ic_mic_fill:R.drawable.ic_search,p.onPrimaryContainer,p.primaryContainer,88,40),new FrameLayout.LayoutParams(ui.dp(88),ui.dp(88),Gravity.CENTER));
        e.addView(art,new LinearLayout.LayoutParams(ui.dp(120),ui.dp(120)));
        TextView t=ui.heading(nothing?"Aún no hay grabaciones":"Sin resultados",Type.HEADLINE_SMALL);t.setPadding(0,ui.dp(S5),0,ui.dp(S2));t.setGravity(Gravity.CENTER);e.addView(t);
        String detail=nothing?"Graba una idea o importa un audio para empezar.":filter>0?"No hay grabaciones con este filtro"+(query.isEmpty()?".":" y esta búsqueda."):"Prueba con otra palabra.";
        TextView d=ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setGravity(Gravity.CENTER);e.addView(d);
        Ui.Btn b=null;
        if(nothing)b=ui.button("Grabar ahora",R.drawable.ic_mic_fill,Ui.Style.PRIMARY,v->section(false));
        else if(filter>0)b=ui.button("Quitar filtro",R.drawable.ic_close,Ui.Style.SECONDARY,v->{filter=0;render();});
        else if(!query.isEmpty())b=ui.button("Borrar búsqueda",R.drawable.ic_close,Ui.Style.SECONDARY,v->search.setText(""));
        if(b!=null){LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S6);e.addView(b,lp);}
        return e;
    }
    /**
     * Fila de 3 líneas: título ··· duración / comienzo del texto (o el estado) / personas y si ya está en 0-Inbox.
     * Lo terminado va en silencio; se destaca lo que pide acción (en proceso, error con «Reintentar», por guardar).
     */
    private View row(Item i){
        boolean top=i.done()||i.status.kind==RecState.Kind.FAILED;
        LinearLayout row=ui.row();row.setGravity(top?Gravity.TOP:Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S1),ui.dp(S3));row.setMinimumHeight(ui.dp(72));
        LinearLayout.LayoutParams ll=new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40));if(top)ll.topMargin=ui.dp(2);row.addView(lead(i),ll);row.addView(ui.space(S4));
        LinearLayout texts=ui.column();
        LinearLayout line1=ui.row();TextView title=ui.oneLine(ui.text(i.r.title,Type.TITLE_MEDIUM,p.onSurface));line1.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView duration=Ui.tabular(ui.text(Ui.humanDuration(i.r.duration),Type.LABEL_MEDIUM,p.onSurfaceVariant));duration.setPadding(ui.dp(S2),0,0,0);line1.addView(duration);
        texts.addView(line1,Ui.fill());
        String second;int secondColor=p.onSurfaceVariant;
        switch(i.status.kind){
            case QUEUED:second=i.progressText(queueBlocker);secondColor=p.primary;break;
            case FAILED:second="No se pudo transcribir";secondColor=p.error;break;
            case NEW:second="Sin transcribir";break;
            default:{String s=i.snippet();second=s.isEmpty()?"Transcripción lista":"«"+s+"»";}
        }
        TextView two=ui.oneLine(ui.text(second,Type.BODY_MEDIUM,secondColor));two.setPadding(0,ui.dp(2),0,0);texts.addView(two,Ui.fill());
        CharSequence meta="";
        if(i.done()){meta=metaLine(i);if(meta.length()>0){TextView m=ui.oneLine(ui.text("",Type.BODY_SMALL,p.onSurfaceVariant));m.setText(meta);m.setPadding(0,ui.dp(2),0,0);texts.addView(m,Ui.fill());}}
        else if(i.status.kind==RecState.Kind.FAILED){
            Ui.Btn retry=ui.button("Reintentar",R.drawable.ic_refresh,Ui.Style.TONAL,v->{Ui.haptic(v,Ui.Haptic.CONFIRM);RecordingActions.transcribe(this,i.r,this::load);});retry.setMinimumHeight(ui.dp(48));retry.setPadding(ui.dp(S4),0,ui.dp(S4),0);retry.setContentDescription("Reintentar transcripción de "+i.r.title);
            LinearLayout.LayoutParams rl=Ui.wrap();rl.topMargin=ui.dp(S2);texts.addView(retry,rl);
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton more=ui.iconButton(R.drawable.ic_more,"Opciones de "+i.r.title,p.onSurfaceVariant,0,48);more.setOnClickListener(v->RecordingActions.menu(this,i.r,this::load,null));row.addView(more);
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);
        row.setContentDescription(i.r.title+". "+Ui.humanDuration(i.r.duration)+". "+second+(meta.length()>0?". "+meta.toString().replace("●",""):"")+(i.fresh?". Nueva":""));row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->open(i.r.id));row.setOnLongClickListener(v->{Ui.haptic(v);RecordingActions.menu(this,i.r,this::load,null);return true;});
        return row;
    }
    /** Ícono inicial: vidrio si está terminado; anillo de avance verde si transcribe; error; menta solo si pide acción. */
    private View lead(Item i){
        FrameLayout f=new FrameLayout(this);f.setClipChildren(false);View base;
        switch(i.status.kind){
            case QUEUED:base=progressLead(i.progress(),R.drawable.ic_wave,18,3);break;
            case FAILED:base=leadCircle(R.drawable.ic_alert,2,40,20);break;
            case NEW:base=leadCircle(R.drawable.ic_wave,0,40,20);break;
            default:{boolean review=i.toReview(),save=i.toSave();base=leadCircle(review?R.drawable.ic_people:save?R.drawable.ic_save:R.drawable.ic_doc,review||save?1:0,40,20);}
        }
        f.addView(base,new FrameLayout.LayoutParams(-1,-1));
        if(i.fresh)f.addView(newDot(),new FrameLayout.LayoutParams(ui.dp(12),ui.dp(12),Gravity.TOP|Gravity.END));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return f;
    }
    /** Ícono en un círculo: tone 0 = vidrio (blanco con borde fino: lo que está en calma) · 1 = menta (pide acción) · 2 = error. */
    private FrameLayout leadCircle(int icon,int tone,int sizeDp,int iconDp){
        FrameLayout f=ui.tile(icon,tone==1?p.onPrimaryContainer:tone==2?p.onErrorContainer:p.onSurfaceVariant,0,sizeDp,iconDp);
        GradientDrawable bg;if(tone==1)bg=oval(p.primaryContainer);else if(tone==2)bg=oval(p.errorContainer);else{bg=glassOval(this,p);bg.setStroke(Math.max(1,ui.dp(1)),p.outlineVariant);}
        f.setBackground(bg);return f;
    }
    /** Punto «nuevo»: transcripción lista que todavía no abriste. */
    private View newDot(){View d=new View(this);GradientDrawable g=oval(p.primary);g.setStroke(ui.dp(2),p.surfaceContainerLowest);d.setBackground(g);d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return d;}
    /** «●● Konrad, Fran · ✓ En 0-Inbox» (los puntos en el color de cada persona). Lo que pide acción va en primary. */
    private CharSequence metaLine(Item i){
        SpannableStringBuilder b=new SpannableStringBuilder();Meta m=i.meta;
        if(m!=null&&m.diarized&&!m.names.isEmpty()){
            int n=m.names.size();for(int k=0;k<Math.min(3,n);k++){int s=b.length();b.append("●");b.setSpan(new ForegroundColorSpan(p.speaker(m.colors.get(k))),s,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            b.append(' ');for(int k=0;k<Math.min(2,n);k++)b.append(k==0?"":", ").append(m.names.get(k));if(n>2)b.append(" +").append(String.valueOf(n-2));
        }
        String action=null;boolean highlight=false;
        if(i.toReview(m)){action=m.names.size()+" voces por revisar";highlight=true;}
        else if(i.inbox){String folder=folderName();if(i.savedAt==0){action="Por guardar";highlight=true;}else if(i.outdated){action="Actualizar en "+folder;highlight=true;}else action="✓ En "+folder;}
        if(action!=null){if(b.length()>0)b.append(" · ");int s=b.length();b.append(action);if(highlight)b.setSpan(new ForegroundColorSpan(p.primary),s,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return b;
    }
    private static String dateSection(long time){
        if(DateUtils.isToday(time))return "Hoy";if(DateUtils.isToday(time+DateUtils.DAY_IN_MILLIS))return "Ayer";
        if(System.currentTimeMillis()-time<7*DateUtils.DAY_IN_MILLIS)return "Esta semana";
        String month=new SimpleDateFormat("MMMM yyyy",ES).format(new Date(time));return Character.toUpperCase(month.charAt(0))+month.substring(1);
    }
    private void open(String id){startActivity(new Intent(this,RecordingActivity.class).putExtra("id",id));}

    /**
     * Zona flexible de la pantalla fija: ocupa el espacio libre y además recuerda cuánto pide su contenido (medido sin
     * límite de alto). fitHome compara ambos para decidir si todo cabe.
     */
    private static final class Flex extends FrameLayout {
        int need;
        Flex(Context c){super(c);setClipChildren(false);}
        @Override protected void onMeasure(int w,int h){
            int n=0;
            for(int i=0;i<getChildCount();i++){View v=getChildAt(i);if(v.getVisibility()==GONE)continue;
                v.measure(getChildMeasureSpec(w,getPaddingLeft()+getPaddingRight(),v.getLayoutParams().width),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));n=Math.max(n,v.getMeasuredHeight());}
            need=n+getPaddingTop()+getPaddingBottom();super.onMeasure(w,h);
        }
    }
    /**
     * Niveles de la grabación en curso, comprimidos en 512 casillas: cuando se llenan, cada par se promedia y cada casilla
     * pasa a cubrir el doble de tiempo. Así una grabación de horas ocupa lo mismo que una de segundos.
     */
    private static final class Levels {
        private final float[] v=new float[512];private int n,stride=1,count;private float sum;
        void clear(){n=0;stride=1;count=0;sum=0;}
        void add(float level){
            sum+=level;if(++count<stride)return;
            if(n==v.length){for(int i=0;i<n/2;i++)v[i]=(v[2*i]+v[2*i+1])/2f;n/=2;stride*=2;}
            v[n++]=sum/count;sum=0;count=0;
        }
        /** Unas target barras (promedio de cada tramo); vacío si no hay niveles (la onda chica queda plana). */
        float[] bars(int target){
            if(n==0)return new float[0];float[] out=new float[target];
            for(int i=0;i<target;i++){int a=i*n/target,b=Math.max(a+1,(i+1)*n/target);float s=0;for(int k=a;k<b&&k<n;k++)s+=v[k];out[i]=s/Math.max(1,Math.min(b,n)-a);}
            return out;
        }
    }
    /** Anillo de avance (Material 3): arco con el avance real; si aún no se sabe, un arco que gira (quieto si Android quita las animaciones). */
    static final class Ring extends View {
        private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG),arc=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF box=new RectF();private float fraction=-1f;
        Ring(Context c,int color,int trackColor,float stroke){
            super(c);for(Paint paint:new Paint[]{track,arc}){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);}
            track.setColor(trackColor);arc.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setFraction(float value){fraction=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float s=track.getStrokeWidth()/2f+1;box.set(s,s,getWidth()-s,getHeight()-s);canvas.drawOval(box,track);
            if(fraction<0){boolean moving=ValueAnimator.areAnimatorsEnabled();float start=moving?(SystemClock.uptimeMillis()%1400)/1400f*360f:-90f;canvas.drawArc(box,start,90,false,arc);if(moving&&isAttachedToWindow())postInvalidateOnAnimation();}
            else if(fraction>0)canvas.drawArc(box,-90,Math.max(6f,360f*Math.min(1f,fraction)),false,arc);
        }
    }
    /** Estrella ★ para el botón Marcar (misma forma que las marcas de la onda). */
    static final class StarIcon extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private final int size;
        StarIcon(int color,int size){this.size=size;paint.setColor(color);paint.setPathEffect(new CornerPathEffect(size*0.08f));}
        @Override public void draw(Canvas canvas){Rect b=getBounds();canvas.drawPath(Waveform.star(path,b.exactCenterX(),b.exactCenterY()+size*0.04f,size/2f),paint);}
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
