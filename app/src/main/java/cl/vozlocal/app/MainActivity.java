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
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
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
 * Pestañas "Grabar" y "Biblioteca".
 * Grabar (pantalla fija, sin desplazamiento): el botón rojo es lo principal. Al grabar, la pantalla «vive»: onda alta,
 * tiempo grande y tres controles fijos [Pausa] [Detener] [★ Marcar]; lo secundario se desvanece sin soltar su lugar
 * (modo foco), así Detener queda exactamente donde estaba Grabar. Debajo, «Última grabación» con su siguiente paso.
 * Biblioteca: búsqueda, filtros por lo que pide acción y filas de 3 líneas (título, comienzo del texto, personas y
 * si ya está en 0-Inbox). Lo terminado va en silencio; se destaca solo lo que necesita algo.
 */
public class MainActivity extends Screen {
    private static final Locale ES=new Locale("es","CL");
    /** Fecha ISO al comienzo del título (Ajustes → «Fecha en el nombre»). */
    private static final Pattern DATE=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+|$)");
    /** Tamaños fijos de los controles de grabación (dp): Detener no se mueve entre estados. */
    private static final int RECORD_DP=120,SIDE_DP=64,SIDE_COLUMN_DP=84;
    /** Alto que necesita la onda completa y el mínimo antes de compactar la pantalla (dp). */
    private static final int STAGE_FULL=150,STAGE_MIN=64;
    /** Transcripciones ya leídas (fragmento, personas y colores), por archivo: la Biblioteca no relee lo que no cambió. */
    private static final Map<String,Meta> METAS=new ConcurrentHashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private boolean showLibrary,welcomedNow;private int homeScroll,libraryScroll;

    // Grabar
    private LinearLayout homePanel,hero,statusRow,subRow,bottomSection,recentSection,recentList,statusChip,pauseBox,markBox,importRow;private FrameLayout stage;
    /** fitPending: hay que volver a medir si todo cabe (se resuelve con Grabar a la vista y ya medida; nunca en bucle). */
    private boolean recentsFit=true,fitPending;private int compact;
    private TextView statusLabel,timer,idlePrompt,hint,readyChip,pauseLabel,markBadge,importMeta;private Ui.Btn titleButton;
    private int workingCount,failedCount;private String workingTitle="",workingId,failedId,reviewId,reviewTitle="";
    private View statusDot;private ProgressBar chipSpinner;private ImageView chipIcon;
    private RecordButton record;private Waveform wave;private ImageButton pause,mark;
    private String lastState="",shownTitle,shownImport;private boolean starting;private int shownMarks;
    // Última grabación
    private LinearLayout lastCard,lastActions;private FrameLayout lastLead;private TextView lastOverline,lastTitle,lastStatus;private ProgressBar lastBar;private Ui.Btn lastButton;private String lastKey="";private boolean savingInbox;
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
    /** loadVersion: la última lectura pedida (se lee en el hilo de disco para saltarse las que ya quedaron atrás). */
    private ArrayList<Item> items=new ArrayList<>();private volatile int loadVersion;private int dataVersion=-1;

    /** 70 ms al grabar (onda fluida); 400 ms en reposo para ahorrar batería. */
    private final Runnable tick=new Runnable(){@Override public void run(){update();handler.postDelayed(this,RecorderService.activeId!=null||starting?70:400);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null){query=saved.getString("query","");filter=saved.getInt("filter");homeScroll=saved.getInt("homeScroll");libraryScroll=saved.getInt("libraryScroll");}
        showLibrary=saved!=null?saved.getBoolean("library"):getIntent().getBooleanExtra("library",false);
        shell(null,showLibrary?1:0);
        // El teclado nunca se abre solo al volver: solo cuando tocas la búsqueda.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setFocusableInTouchMode(true);
        // Grabar es una pantalla FIJA (sin desplazamiento): va fuera del ScrollView y ocupa el alto disponible.
        homePanel=ui.column();homePanel.setPadding(ui.dp(S4),0,ui.dp(S4),ui.dp(S3));root.addView(homePanel,0,new LinearLayout.LayoutParams(-1,0,1));buildHome();
        homePanel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot){recentsFit=true;if(compact!=0){compact=0;applyCompact();}requestFit();}});
        // Un ajuste que esperaba a que la zona de grabación se midiera se retoma con su siguiente medición (no sondeando).
        hero.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(fitPending)v.post(this::fitHome);});
        libraryPanel=ui.column();page.addView(libraryPanel,Ui.fill());buildLibrary();
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::checkIme);
        section(showLibrary);root.requestFocus();
        if(saved==null)welcomedNow=welcome();
    }
    @Override void navigate(int tab){if(tab==2)super.navigate(2);else section(tab==1);}
    @Override protected void onResume(){
        super.onResume();lastState="";if(search!=null&&search.hasFocus()){search.clearFocus();root.requestFocus();}
        handler.post(tick);load();if(!Pipeline.startForeground(this))Pipeline.schedule(this,false);renderChip();
        // Novedades de la versión: después de la bienvenida (nunca las dos juntas).
        if(!welcomedNow)try{Novedades.maybeShow(this);}catch(RuntimeException e){Diagnostics.event("novedades_failed",null,"error_class",e.getClass().getSimpleName());}
        welcomedNow=false;
    }
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);disk.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("library",showLibrary);out.putString("query",query);out.putInt("filter",filter);if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();out.putInt("homeScroll",homeScroll);out.putInt("libraryScroll",libraryScroll);super.onSaveInstanceState(out);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);section(intent.getBooleanExtra("library",false));}
    @Override public void onBackPressed(){if(showLibrary)section(false);else super.onBackPressed();}

    private void section(boolean library){
        if(showLibrary!=library){if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();}
        showLibrary=library;nav.select(library?1:0);
        homePanel.setVisibility(library?View.GONE:View.VISIBLE);libraryPanel.setVisibility(library?View.VISIBLE:View.GONE);scroll.setVisibility(library?View.VISIBLE:View.GONE);
        if(library)ui.fadeIn(libraryPanel);else{hideKeyboard();requestFit();}
        renderPill();
        scroll.post(()->scroll.scrollTo(0,library?libraryScroll:homeScroll));Diagnostics.event("section_open",null,"screen",library?"library":"home");
    }

    // ================= GRABAR =================
    private void buildHome(){
        LinearLayout header=ui.row();header.setPadding(ui.dp(S1),ui.dp(S1),0,ui.dp(S1));header.setMinimumHeight(ui.dp(56));
        header.addView(ui.heading("Verbapp",Type.HEADLINE_SMALL));header.addView(ui.flex());
        // Estado en la cabecera: una línea tranquila si todo está bien; chip de color solo si algo pide acción.
        statusChip=ui.row();statusChip.setPadding(ui.dp(S2),0,ui.dp(S3),0);statusChip.setMinimumHeight(ui.dp(48));statusChip.setClickable(true);statusChip.setFocusable(true);statusChip.setAccessibilityDelegate(Ui.buttonRole());statusChip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        chipSpinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);chipSpinner.setIndeterminateTintList(ColorStateList.valueOf(p.onSecondaryContainer));statusChip.addView(chipSpinner,new LinearLayout.LayoutParams(ui.dp(16),ui.dp(16)));
        chipIcon=ui.icon(R.drawable.ic_check,p.primary,18);statusChip.addView(chipIcon);statusChip.addView(ui.space(S2));
        readyChip=ui.oneLine(ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant));readyChip.setMaxWidth(ui.dp(220));statusChip.addView(readyChip);
        header.addView(statusChip);homePanel.addView(header,Ui.fill());

        // Zona de grabación. Todo tiene alto fijo salvo la onda, que toma el espacio libre: nada cambia de lugar al grabar.
        hero=ui.column();hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(0,ui.dp(S1),0,ui.dp(S3));
        statusRow=ui.row();statusRow.setGravity(Gravity.CENTER);statusDot=new View(this);statusDot.setBackground(oval(p.record));statusRow.addView(statusDot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));statusRow.addView(ui.space(S2));
        statusLabel=ui.text("Grabando",Type.TITLE_SMALL,p.record);statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);statusRow.addView(statusLabel);statusRow.setVisibility(View.INVISIBLE);hero.addView(statusRow,Ui.wrap());
        timer=ui.text("00:00",Type.BODY_LARGE,p.onSurface);timer.setTextSize(64);timer.setTypeface(typeface(Weight.LIGHT));timer.setLetterSpacing(-0.02f);timer.setFontFeatureSettings("tnum");timer.setGravity(Gravity.CENTER);timer.setIncludeFontPadding(false);timer.setLineSpacing(0,1f);timer.setPadding(0,ui.dp(S2),0,ui.dp(S1));timer.setVisibility(View.INVISIBLE);hero.addView(timer,Ui.wrap());
        subRow=ui.row();subRow.setGravity(Gravity.CENTER);
        titleButton=ui.button("Añadir título",R.drawable.ic_edit,Ui.Style.PLAIN,v->titleWhileRecording());titleButton.setMinimumHeight(ui.dp(48));titleButton.label.setSingleLine(true);titleButton.label.setEllipsize(TextUtils.TruncateAt.END);titleButton.label.setMaxWidth(ui.dp(240));
        subRow.addView(titleButton,Ui.wrap());subRow.setVisibility(View.INVISIBLE);hero.addView(subRow,Ui.wrap());
        stage=new FrameLayout(this);wave=new Waveform(this,p);stage.addView(wave,new FrameLayout.LayoutParams(-1,-1));
        idlePrompt=ui.text("Toca para grabar",Type.TITLE_MEDIUM,p.onSurfaceVariant);idlePrompt.setGravity(Gravity.CENTER);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ip.bottomMargin=ui.dp(S1);stage.addView(idlePrompt,ip);
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,0,1);sl.topMargin=ui.dp(S2);sl.bottomMargin=ui.dp(S3);hero.addView(stage,sl);
        // [Pausa] [Grabar/Detener] [★ Marcar]: columnas laterales de ancho fijo para que el botón central nunca se corra.
        LinearLayout controls=ui.row();controls.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        pause=ui.iconButton(R.drawable.ic_pause,"Pausar",p.onSurface,p.surfaceContainerHighest,SIDE_DP);pause.setOnClickListener(v->{if(RecorderService.activeId==null)return;Ui.haptic(v,Ui.Haptic.TICK);send("PAUSE");});
        pauseLabel=controlLabel("Pausa");pauseBox=control(pause,pauseLabel);controls.addView(pauseBox,new LinearLayout.LayoutParams(ui.dp(SIDE_COLUMN_DP),-2));
        record=new RecordButton(this,p);record.setContentDescription("Grabar");record.setOnClickListener(this::onRecordTap);controls.addView(record,new LinearLayout.LayoutParams(ui.dp(RECORD_DP),ui.dp(RECORD_DP)));
        markBox=control(markButton(),controlLabel("Marcar"));controls.addView(markBox,new LinearLayout.LayoutParams(ui.dp(SIDE_COLUMN_DP),-2));
        hero.addView(controls,Ui.wrap());
        // Dos líneas fijas: el texto cambia con el estado, el alto no (si no, los controles subirían o bajarían).
        hint=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);hint.setGravity(Gravity.CENTER);hint.setPadding(ui.dp(S6),ui.dp(S3),ui.dp(S6),0);hint.setLines(2);hint.setEllipsize(TextUtils.TruncateAt.END);hero.addView(hint,Ui.fill());
        homePanel.addView(hero,new LinearLayout.LayoutParams(-1,0,1));

        bottomSection=ui.column();bottomSection.setPadding(0,ui.dp(S1),0,0);
        lastCard=buildLastCard();bottomSection.addView(lastCard,Ui.fill());
        importRow=buildImportRow();bottomSection.addView(importRow,ui.top(S2));
        homePanel.addView(bottomSection,Ui.fill());

        recentSection=ui.column();LinearLayout rh=ui.row();rh.setPadding(ui.dp(S1),ui.dp(S3),0,0);TextView rt=ui.heading("Recientes",Type.TITLE_MEDIUM);rh.addView(rt);rh.addView(ui.flex());
        Ui.Btn all=ui.button("Ver todo",0,Ui.Style.PLAIN,v->section(true));all.setPadding(ui.dp(S3),0,ui.dp(S1),0);rh.addView(all);recentSection.addView(rh,Ui.fill());
        recentList=ui.group();recentSection.addView(recentList,Ui.fill());recentSection.setVisibility(View.GONE);homePanel.addView(recentSection,Ui.fill());
        renderHint(false,false);
    }
    private TextView controlLabel(String text){TextView t=ui.text(text,Type.LABEL_MEDIUM,p.onSurfaceVariant);t.setGravity(Gravity.CENTER);t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return t;}
    /** Control lateral: botón redondo centrado a la altura del botón de grabar, con su etiqueta debajo. */
    private LinearLayout control(View button,TextView label){
        LinearLayout c=ui.column();c.setGravity(Gravity.CENTER_HORIZONTAL);c.setPadding(0,ui.dp((RECORD_DP-SIDE_DP)/2f),0,0);
        c.addView(button,new LinearLayout.LayoutParams(ui.dp(SIDE_DP),ui.dp(SIDE_DP)));LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S1);c.addView(label,lp);
        c.setVisibility(View.INVISIBLE);return c;
    }
    /** ★ Marcar: un toque deja un momento con vibración; mantenerlo permite anotar una palabra. El número muestra cuántos van. */
    private View markButton(){
        FrameLayout box=new FrameLayout(this);box.setClipChildren(false);
        mark=ui.iconButton(R.drawable.ic_sparkle,"Marcar momento",p.onPrimaryContainer,p.primaryContainer,SIDE_DP);mark.setImageTintList(null);mark.setImageDrawable(new StarIcon(p.onPrimaryContainer,ui.dp(26)));
        mark.setOnClickListener(v->markMoment(v,null,-1));mark.setOnLongClickListener(v->{markWithWord(v);return true;});
        mark.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Marcar con una palabra"));}});
        box.addView(mark,new FrameLayout.LayoutParams(-1,-1));
        markBadge=ui.text("",Type.LABEL_SMALL,p.onPrimary);markBadge.setGravity(Gravity.CENTER);markBadge.setMinWidth(ui.dp(20));markBadge.setPadding(ui.dp(5),0,ui.dp(5),0);markBadge.setBackground(shape(this,p.primary,R_FULL));markBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);markBadge.setVisibility(View.GONE);
        box.addView(markBadge,new FrameLayout.LayoutParams(-2,ui.dp(20),Gravity.TOP|Gravity.END));
        return box;
    }
    /** «Última grabación»: título, estado real y UN botón con el siguiente paso (Next). Alto fijo en todos los estados. */
    private LinearLayout buildLastCard(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        LinearLayout top=ui.row();
        lastLead=new FrameLayout(this);top.addView(lastLead,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)));top.addView(ui.space(S3));
        LinearLayout texts=ui.column();
        lastOverline=ui.text("Última grabación",Type.LABEL_MEDIUM,p.onSurfaceVariant);texts.addView(lastOverline);
        lastTitle=ui.oneLine(ui.text("",Type.TITLE_MEDIUM,p.onSurface));texts.addView(lastTitle);
        lastStatus=ui.oneLine(ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant));lastStatus.setFontFeatureSettings("tnum");lastStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);texts.addView(lastStatus);
        top.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(top,Ui.fill());
        lastBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);lastBar.setMax(1000);lastBar.setProgressTintList(ColorStateList.valueOf(p.primary));lastBar.setIndeterminateTintList(ColorStateList.valueOf(p.primary));lastBar.setProgressBackgroundTintList(ColorStateList.valueOf(p.secondaryContainer));lastBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);lastBar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,ui.dp(4));bl.topMargin=ui.dp(S2);card.addView(lastBar,bl);
        lastActions=ui.row();LinearLayout.LayoutParams al=Ui.fill();al.topMargin=ui.dp(S2);card.addView(lastActions,al);
        card.setBackground(ui.ripple(shape(this,p.card,R_CARD),R_CARD));card.setClickable(true);card.setFocusable(true);card.setAccessibilityDelegate(Ui.buttonRole());
        return card;
    }
    /** Importar: una fila con borde; a la derecha dice de dónde (o el avance de una importación en curso). */
    private LinearLayout buildImportRow(){
        LinearLayout row=ui.row();row.setMinimumHeight(ui.dp(48));row.setPadding(ui.dp(S4),0,ui.dp(S4),0);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),outline(this,0x00000000,p.outline,R_FULL,false),shape(this,0xFF000000,R_FULL)));
        row.addView(ui.icon(R.drawable.ic_upload,p.onSurface,20));row.addView(ui.space(S2));
        TextView t=ui.text("Importar audio",Type.LABEL_LARGE,p.onSurface);t.setTextSize(15);row.addView(t);row.addView(ui.flex());
        importMeta=ui.oneLine(ui.text("WhatsApp, archivos…",Type.BODY_SMALL,p.onSurfaceVariant));importMeta.setPadding(ui.dp(S2),0,0,0);row.addView(importMeta);
        row.setClickable(true);row.setFocusable(true);row.setAccessibilityDelegate(Ui.buttonRole());row.setContentDescription("Importar audio de WhatsApp, grabadoras o archivos");
        row.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action","Importar audio");if(RecorderService.activeId!=null){message("Grabación en curso","Guarda primero la grabación actual.");return;}startActivity(new Intent(this,ImportActivity.class));});
        Ui.pressable(row);return row;
    }
    private void whatsappHelp(){
        Sheet s=sheet("Transcribir un audio de WhatsApp",null);
        String[] steps={"Abre el chat y mantén presionado el audio.","Toca Compartir (o ⋮ → Compartir).","Elige Verbapp en la lista de apps.","Revisa el título y toca Guardar audio."};
        for(int i=0;i<steps.length;i++){LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(ui.dp(S1),ui.dp(S2),0,ui.dp(S2));TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onSecondaryContainer);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.secondaryContainer));n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(n,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);}
        TextView note=ui.text("También funciona con grabadoras, Telegram y cualquier app que comparta audio.",Type.BODY_MEDIUM,p.onSurfaceVariant);note.setPadding(ui.dp(S1),ui.dp(S3),0,0);s.add(note);
        s.primary("Entendido",()->{}).show();
    }
    /**
     * Cabecera. Prioridad: falta la clave > transcribiendo > error > una transcripción lista sin abrir > todo bien.
     * «Todo bien» es una línea tranquila (sin fondo); el color queda para lo que pide acción.
     */
    private void renderChip(){
        boolean ready=new Settings(this).hasKey();String text,description;int fg,bg,icon;boolean spin=false,quiet=false;View.OnClickListener click;
        if(!ready){text="Configurar transcripción";fg=p.onSecondaryContainer;bg=p.secondaryContainer;icon=R.drawable.ic_key;click=v->startActivity(new Intent(this,SettingsActivity.class).putExtra("focusKey",true));}
        else if(workingCount>0){text=workingCount>1?"Transcribiendo "+workingCount+" audios":"Transcribiendo «"+workingTitle+"»";fg=p.onSecondaryContainer;bg=p.secondaryContainer;icon=0;spin=true;click=v->{if(workingCount>1){filter=2;section(true);render();}else open(workingId);};}
        else if(failedCount>0){text=failedCount>1?failedCount+" necesitan atención":"Revisar transcripción";fg=p.onErrorContainer;bg=p.errorContainer;icon=R.drawable.ic_alert;click=v->{if(failedCount>1){filter=4;section(true);render();}else open(failedId);};}
        else if(reviewId!=null){String id=reviewId;text="Revisar · «"+reviewTitle+"»";fg=p.onSecondaryContainer;bg=p.secondaryContainer;icon=R.drawable.ic_doc;click=v->open(id);}
        else{text="Listo para transcribir";fg=p.onSurfaceVariant;bg=0;icon=R.drawable.ic_check;quiet=true;click=v->startActivity(new Intent(this,SettingsActivity.class));}
        description=quiet?"Listo para transcribir. Abrir ajustes":text;
        setText(readyChip,text);readyChip.setTextColor(fg);chipSpinner.setVisibility(spin?View.VISIBLE:View.GONE);chipIcon.setVisibility(icon==0?View.GONE:View.VISIBLE);
        if(icon!=0){chipIcon.setImageResource(icon);chipIcon.setImageTintList(ColorStateList.valueOf(quiet?p.primary:fg));}
        statusChip.setBackground(quiet?ui.ripple(null,R_SMALL):chipBackground(bg));
        statusChip.setOnClickListener(click);statusChip.setContentDescription(description);
    }
    /** Cambia el texto solo si cambió: las regiones «en vivo» no se vuelven a anunciar en cada actualización. */
    private static void setText(TextView t,String value){if(!value.contentEquals(t.getText()))t.setText(value);}
    /** Chip de 32 dp visibles dentro de un área táctil de 48 dp. */
    private Drawable chipBackground(int color){int inset=ui.dp(S2);return new RippleDrawable(ColorStateList.valueOf(p.ripple),new InsetDrawable(shape(this,color,R_SMALL),0,inset,0,inset),new InsetDrawable(shape(this,0xFF000000,R_SMALL),0,inset,0,inset));}

    /**
     * Pantalla fija: si no cabe todo, primero se ocultan «Recientes»; después se compacta por niveles:
     * 1) sin la línea de ayuda ni el rótulo de la tarjeta, tiempo más chico; 2) sin «Añadir título» al grabar si
     * «Nombrar al terminar» está activo (el título se pone al terminar); 3) pantallas muy bajas: sin la línea «Grabando»
     * y la tarjeta queda en una fila (tocarla abre).
     * Se decide por el tamaño de la pantalla, nunca por el estado, así nada cambia de lugar entre grabar y detener.
     */
    private void requestFit(){fitPending=true;if(homePanel.getVisibility()==View.VISIBLE)homePanel.post(this::fitHome);}
    /**
     * Un paso de ajuste. Nunca se vuelve a programar sola mientras espera: con Grabar oculto (Biblioteca) o sin medir
     * (p. ej. la app en segundo plano) queda pendiente y la retoman section(false) o la siguiente medición de la zona.
     */
    private void fitHome(){
        if(!fitPending||homePanel.getVisibility()!=View.VISIBLE||hero.getWidth()==0||hero.isLayoutRequested())return;
        fitPending=false;
        int free=stage.getHeight();
        if(recentSection.getVisibility()!=View.GONE&&free<ui.dp(STAGE_FULL)){recentsFit=false;applyRecents();requestFit();return;}
        if(free<ui.dp(STAGE_MIN)&&compact<3){compact++;applyCompact();requestFit();}
    }
    private void applyCompact(){
        hint.setVisibility(compact>=1?View.GONE:View.VISIBLE);lastOverline.setVisibility(compact>=1?View.GONE:View.VISIBLE);
        timer.setTextSize(compact>=1?48:64);
        boolean active=RecorderService.activeId!=null;
        renderTitleRow(active);
        statusRow.setVisibility(compact>=3?View.GONE:active?View.VISIBLE:View.INVISIBLE);
        lastActions.setVisibility(compact>=3?View.GONE:View.VISIBLE);if(compact>=3)lastBar.setVisibility(View.GONE);else if(lastBar.getVisibility()==View.GONE)lastBar.setVisibility(View.INVISIBLE);
    }
    /**
     * «Añadir título» al grabar. En pantallas compactas (nivel 2+) se quita solo si el título se pone al terminar
     * («Nombrar al terminar»); si no, es la única forma de ponerlo durante la grabación. Si vuelve a ocupar lugar, se
     * vuelve a medir (compactar más si hace falta).
     */
    private void renderTitleRow(boolean active){
        boolean hide=compact>=2&&new Settings(this).askTitle(),wasGone=subRow.getVisibility()==View.GONE;
        subRow.setVisibility(hide?View.GONE:active?View.VISIBLE:View.INVISIBLE);
        if(wasGone&&!hide)requestFit();
    }
    private void applyRecents(){
        boolean recording=RecorderService.activeId!=null;
        recentSection.setVisibility(items.size()<2||!recentsFit?View.GONE:recording?View.INVISIBLE:View.VISIBLE);if(!recording)recentSection.setAlpha(1f);
        if(recentSection.getVisibility()!=View.GONE)requestFit();
    }
    /** Bucle de UI: refleja el estado real del servicio de grabación. */
    private void update(){
        boolean active=RecorderService.activeId!=null,paused=RecorderService.paused;
        String state=active+":"+paused;
        if(!state.equals(lastState)){boolean animate=!lastState.isEmpty(),was=lastState.startsWith("true");lastState=state;starting=false;record.setEnabled(true);applyState(active,paused,animate,was);}
        if(active){
            long elapsed=RecorderService.elapsed();timer.setText(Recording.time(elapsed));boolean blink=(SystemClock.uptimeMillis()/600)%2==0;
            if(paused){timer.setAlpha(blink?1f:0.35f);statusDot.setAlpha(1f);record.setLevel(0);}
            else{timer.setAlpha(1f);float level=Waveform.normalize(RecorderService.amplitude());wave.push(level);record.setLevel(level);statusDot.setAlpha(blink?1f:0.25f);}
            int marks=RecorderService.marksCount();if(marks!=shownMarks){boolean added=marks>shownMarks;shownMarks=marks;if(added)wave.mark();renderMarks(added);}
            String t=RecorderService.activeTitle;if(!Objects.equals(t,shownTitle)){shownTitle=t;titleButton.setText(t==null?"Añadir título":t);titleButton.setContentDescription(t==null?"Añadir título a la grabación":"Título: "+t+". Toca para cambiarlo");}
            if(recPill.getVisibility()==View.VISIBLE){pillTime.setText(Recording.time(elapsed)+(paused?" · En pausa":" · Grabando"));pillDot.setAlpha(paused||blink?1f:0.3f);}
        }
        if(RecorderService.error!=null){String m=RecorderService.error;RecorderService.error=null;starting=false;record.setEnabled(true);message("Grabación",m);}
        if(RecorderService.notice!=null&&!active){String m=RecorderService.notice;RecorderService.notice=null;toast(m);}
        String saved=RecorderService.lastSavedId;if(saved!=null&&!active){RecorderService.lastSavedId=null;afterSave(saved);}
        renderImport();
        int version=FilesStore.version.get();if(dataVersion!=version){dataVersion=version;load();}
    }
    /** Cambio de estado (reposo, grabando, en pausa): lo que se ve cambia, el lugar de cada control no. */
    private void applyState(boolean active,boolean paused,boolean animate,boolean was){
        record.setRecording(active,animate);record.setContentDescription(active?"Detener y guardar":"Grabar");
        show(pauseBox,active,animate);show(markBox,active,animate);
        // En pausa, «Reanudar» se destaca (tonal); la onda se congela en gris y el tiempo parpadea.
        pause.setImageResource(paused?R.drawable.ic_play:R.drawable.ic_pause);pause.setImageTintList(ColorStateList.valueOf(paused?p.onSecondaryContainer:p.onSurface));
        pause.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),oval(paused?p.secondaryContainer:p.surfaceContainerHighest),oval(0xFF000000)));
        pause.setContentDescription(paused?"Reanudar grabación":"Pausar");pauseLabel.setText(paused?"Reanudar":"Pausa");pauseLabel.setTextColor(paused?p.onSurface:p.onSurfaceVariant);pauseLabel.setTypeface(typeface(paused?Weight.BOLD:Weight.MEDIUM));
        if(compact<3)statusRow.setVisibility(active?View.VISIBLE:View.INVISIBLE);statusDot.setBackground(oval(paused?p.onSurfaceVariant:p.record));
        statusLabel.setText(paused?"En pausa":"Grabando");statusLabel.setTextColor(paused?p.onSurfaceVariant:p.record);
        timer.setVisibility(active?View.VISIBLE:View.INVISIBLE);timer.setAlpha(1f);if(!active)timer.setText("00:00");
        renderTitleRow(active);
        idlePrompt.setVisibility(active?View.GONE:View.VISIBLE);
        wave.setMode(active?(paused?Waveform.PAUSED:Waveform.LIVE):Waveform.IDLE);
        if(active&&!was&&animate&&ValueAnimator.areAnimatorsEnabled()){wave.setAlpha(0f);wave.setScaleY(0.6f);wave.animate().alpha(1f).scaleY(1f).setDuration(MOTION_SLOW).start();}
        if(active&&!was){shownMarks=RecorderService.marksCount();shownTitle=null;titleButton.setText("Añadir título");renderMarks(false);}
        renderHint(active,paused);
        focusMode(active);nav.badge(0,active);renderPill();
        if(!active||!was)load();
    }
    private void show(View v,boolean visible,boolean animate){
        v.animate().cancel();
        if(!visible){v.setVisibility(View.INVISIBLE);v.setAlpha(1f);return;}
        boolean was=v.getVisibility()==View.VISIBLE;v.setVisibility(View.VISIBLE);
        if(animate&&!was){v.setAlpha(0f);v.animate().alpha(1f).setDuration(MOTION_BASE).start();}else v.setAlpha(1f);
    }
    private void renderHint(boolean active,boolean paused){
        String text=!active?"Funciona sin internet. Al grabar aparecen Pausa y ★ Marcar."
            :paused?"En pausa · toca Reanudar para seguir o Detener para guardar."
            :shownMarks>0?shownMarks+(shownMarks==1?" momento ★":" momentos ★")+" · mantén ★ para anotar una palabra."
            :"Puedes bloquear el teléfono: la grabación sigue.";
        hint.setText(text);
    }
    private void renderMarks(boolean pop){
        int n=shownMarks;markBadge.setText(String.valueOf(n));markBadge.setVisibility(n>0?View.VISIBLE:View.GONE);
        mark.setContentDescription(n==0?"Marcar momento":"Marcar momento. "+n+(n==1?" momento marcado":" momentos marcados"));
        if(pop&&n>0){markBadge.setScaleX(1.4f);markBadge.setScaleY(1.4f);markBadge.animate().scaleX(1f).scaleY(1f).setDuration(MOTION_BASE).start();mark.announceForAccessibility("Momento marcado");}
        renderHint(RecorderService.activeId!=null,RecorderService.paused);
    }
    /** Avance de una importación en curso en la fila «Importar audio» (la importación sigue aunque salgas de esa pantalla). */
    private void renderImport(){
        String text="WhatsApp, archivos…";ImportSession s=ImportService.session(this);
        if(s!=null&&!s.done&&!s.cancelled&&!s.stale()){if(s.busy)text=(s.total>0?ImportService.percent(s)+" % · ":"")+"importando…";else if(!s.error.isEmpty())text="Importación pendiente";else if(s.ready)text="Audio listo para guardar";}
        if(text.equals(shownImport))return;shownImport=text;importMeta.setText(text);importMeta.setTextColor(text.startsWith("WhatsApp")?p.onSurfaceVariant:p.primary);
    }
    /** Modo foco al grabar: lo secundario se desvanece pero conserva su espacio, así el botón de detener no se mueve. */
    private void focusMode(boolean on){
        for(View v:new View[]{bottomSection,recentSection}){if(v==recentSection&&(items.size()<2||!recentsFit)){v.setVisibility(View.GONE);continue;}
            v.animate().cancel();if(on){v.animate().alpha(0f).setDuration(MOTION_BASE).withEndAction(()->v.setVisibility(View.INVISIBLE)).start();}else{v.setVisibility(View.VISIBLE);v.animate().alpha(1f).setDuration(MOTION_BASE).start();}}
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
     * Después de guardar: el título ya enfocado con el teclado abierto. La fecha (si está activa en Ajustes) va fija
     * delante y solo se escribe el resto. «Ver grabación» es lo principal; «Listo» (o cerrar) guarda el título igual.
     */
    private void afterSave(String id){
        Recording r=FilesStore.recording(this,id);if(r==null)return;Settings settings=new Settings(this);
        if(!settings.askTitle()){toast("Guardado en Biblioteca · "+Recording.time(r.duration));return;}
        boolean auto=settings.automatic()&&settings.hasKey();int marks=0;try{marks=Marks.list(this,r.id).length();}catch(RuntimeException ignored){}
        String prefix="",rest=r.title==null?"":r.title;
        if(settings.datePrefix()){Matcher m=DATE.matcher(rest);if(m.find()){prefix=m.group(1);rest=rest.substring(m.end()).trim();}}
        LinearLayout box=ui.row();box.setBackground(shape(this,p.surfaceContainerHighest,R_CONTROL));box.setPadding(ui.dp(S4),0,ui.dp(S2),0);box.setMinimumHeight(ui.dp(56));
        if(!prefix.isEmpty()){TextView date=ui.text(prefix,Type.BODY_LARGE,p.onSurfaceVariant);date.setFontFeatureSettings("tnum");date.setContentDescription("Fecha "+prefix+", fija");box.addView(date);box.addView(ui.space(S2));}
        EditText input=ui.field("Nombre","Título de la grabación");input.setBackground(null);input.setPadding(0,ui.dp(S3),0,ui.dp(S3));input.setText(rest);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        String detail=Recording.time(r.duration)+" · guardada en este teléfono"+(marks>0?" · "+marks+(marks==1?" momento ★":" momentos ★"):"")+(auto?" · se transcribirá automáticamente":"");
        Sheet s=sheet("Grabación guardada",detail).add(box);String date=prefix;
        Runnable saveTitle=()->{
            String t=input.getText().toString().trim();if(t.isEmpty())return;
            String full=date.isEmpty()||DATE.matcher(t).find()?t:date+" "+t;if(full.equals(r.title))return;
            r.title=full;try{r.save(this);Pipeline.edited(this,r.id);Diagnostics.event("title_edited",r.id);}catch(Exception ignored){}load();
        };
        s.primary("Ver grabación",()->{saveTitle.run();open(r.id);}).secondary("Listo",saveTitle).onDismiss(saveTitle).show();
        input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){s.dismiss();return true;}return false;});
        input.requestFocus();if(s.dialog.getWindow()!=null)s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void begin(){
        if(starting)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            sheet("Permitir el micrófono","Verbapp usa el micrófono solo mientras grabas. El audio se guarda en este teléfono.").primary("Continuar",()->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).secondary("Ahora no",null).show();return;
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
    /** Bienvenida de la primera instalación. Devuelve true si se mostró. */
    private boolean welcome(){
        Settings settings=new Settings(this);if(settings.prefs.getBoolean("welcomed",false))return false;settings.prefs.edit().putBoolean("welcomed",true).apply();
        Sheet s=sheet("Bienvenido a Verbapp","Graba o importa audio y transcríbelo con tu propia clave: pagas centavos por uso, sin suscripción.");
        int[] icons={R.drawable.ic_mic_fill,R.drawable.ic_sparkle,R.drawable.ic_folder};
        String[][] rows={{"Graba sin internet","El audio queda en tu teléfono, incluso con la pantalla bloqueada."},{"Transcribe cuando quieras","Con separación de voces: Persona 1, Persona 2… y nombres editables."},{"Tus archivos son tuyos","Copias opcionales en una carpeta del teléfono o de Drive."}};
        for(int i=0;i<3;i++){LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(S2),0,ui.dp(S2));r.addView(ui.tile(icons[i],p.onPrimaryContainer,p.primaryContainer,40,22));r.addView(ui.space(S3));LinearLayout t=ui.column();t.addView(ui.text(rows[i][0],Type.TITLE_MEDIUM,p.onSurface));TextView d=ui.text(rows[i][1],Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);t.addView(d);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));s.add(r);}
        if(settings.hasKey())s.primary("Empezar",()->{});
        else s.primary("Configurar transcripción",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("focusKey",true))).secondary("Solo grabar por ahora",null);
        s.show();return true;
    }

    // ---------- Última grabación ----------
    private void renderLast(){
        Item i=items.isEmpty()?null:items.get(0);lastLead.removeAllViews();
        if(i==null){
            lastLead.addView(ui.tile(R.drawable.ic_mic_fill,p.onSurfaceVariant,p.surfaceContainerHighest,40,20),new FrameLayout.LayoutParams(-1,-1));
            setText(lastTitle,"Aún no hay grabaciones");setText(lastStatus,"Graba una idea o trae un audio de WhatsApp.");lastStatus.setTextColor(p.onSurfaceVariant);lastBar.setVisibility(compact>=3?View.GONE:View.INVISIBLE);
            lastAction("Cómo traer un audio de WhatsApp",R.drawable.ic_chat,Ui.Style.TONAL,v->whatsappHelp());
            lastCard.setOnClickListener(v->whatsappHelp());lastCard.setContentDescription("Última grabación: aún no hay grabaciones");return;
        }
        Recording r=i.r;String dur=Ui.humanDuration(r.duration);int icon=R.drawable.ic_doc,fg=p.onSurfaceVariant,bg=p.surfaceContainerHighest;int statusColor=p.onSurfaceVariant;
        String status,label;int actionIcon;Ui.Style style;View.OnClickListener action;float bar=Float.NaN;
        View.OnClickListener transcribe=v->{Ui.haptic(v,Ui.Haptic.CONFIRM);RecordingActions.transcribe(this,r,this::load);};
        switch(i.status.kind){
            case QUEUED:icon=R.drawable.ic_clock;fg=p.onSecondaryContainer;bg=p.secondaryContainer;status=i.progressText(queueBlocker);statusColor=p.primary;bar=i.progress();label="Ver avance";actionIcon=R.drawable.ic_clock;style=Ui.Style.TONAL;action=v->open(r.id);break;
            case FAILED:icon=R.drawable.ic_alert;fg=p.onErrorContainer;bg=p.errorContainer;status="No se pudo transcribir";statusColor=p.error;label="Reintentar";actionIcon=R.drawable.ic_refresh;style=Ui.Style.PRIMARY;action=transcribe;break;
            case NEW:icon=R.drawable.ic_wave;status=dur+" · Sin transcribir";label="Transcribir";actionIcon=R.drawable.ic_sparkle;style=Ui.Style.PRIMARY;action=transcribe;break;
            default:{
                Next.Step step=i.next!=null?i.next.step:derivedStep(i);String next=i.next!=null?i.next.label:null;String folder=folderName();
                switch(step){
                    case REVIEW:{Meta m=i.meta;int n=m==null?0:m.names.size();icon=R.drawable.ic_people;fg=p.onPrimaryContainer;bg=p.primaryContainer;status=dur+" · Lista · "+(n>1?n+" voces por revisar":"voces por revisar");label=next!=null?next:"Revisar voces";actionIcon=R.drawable.ic_people;style=Ui.Style.PRIMARY;action=v->startActivity(new Intent(this,RecordingActivity.class).putExtra("id",r.id).putExtra("names",true));break;}
                    case SAVE:icon=R.drawable.ic_inbox;fg=p.onPrimaryContainer;bg=p.primaryContainer;status=dur+" · Lista · por guardar";label=next!=null?next:"Guardar en "+folder;actionIcon=R.drawable.ic_save;style=Ui.Style.PRIMARY;action=v->saveInbox(r);break;
                    case UPDATE:icon=R.drawable.ic_refresh;fg=p.onPrimaryContainer;bg=p.primaryContainer;status=dur+" · Hay cambios sin guardar";label=next!=null?next:"Actualizar en "+folder;actionIcon=R.drawable.ic_refresh;style=Ui.Style.PRIMARY;action=v->saveInbox(r);break;
                    case CHOOSE_FOLDER:status=dur+" · Transcripción lista";label=next!=null?next:"Elegir carpeta rápida";actionIcon=R.drawable.ic_folder;style=Ui.Style.TONAL;action=v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true).putExtra("inbox",true));break;
                    default:status=dur+" · ✓ "+(step==Next.Step.SAVED&&next!=null?next:"Transcripción lista");label="Abrir";actionIcon=R.drawable.ic_doc;style=Ui.Style.TONAL;action=v->open(r.id);
                }
            }
        }
        lastLead.addView(ui.tile(icon,fg,bg,40,20),new FrameLayout.LayoutParams(-1,-1));if(i.fresh)lastLead.addView(newDot(),new FrameLayout.LayoutParams(ui.dp(12),ui.dp(12),Gravity.TOP|Gravity.END));
        setText(lastTitle,r.title);setText(lastStatus,status);lastStatus.setTextColor(statusColor);
        if(compact>=3)lastBar.setVisibility(View.GONE);else if(Float.isNaN(bar))lastBar.setVisibility(View.INVISIBLE);else{lastBar.setVisibility(View.VISIBLE);lastBar.setIndeterminate(bar<0);if(bar>=0)lastBar.setProgress(Math.round(bar*1000));}
        lastAction(label,actionIcon,style,action);
        lastCard.setOnClickListener(v->open(r.id));lastCard.setContentDescription("Última grabación: "+r.title+". "+status+". Toca para abrirla");
    }
    /** Siguiente paso si Next no respondió (misma lógica: revisar → guardar → actualizar → guardada). */
    private static Next.Step derivedStep(Item i){if(i.toReview())return Next.Step.REVIEW;if(!i.inbox)return Next.Step.CHOOSE_FOLDER;if(i.savedAt==0)return Next.Step.SAVE;if(i.outdated)return Next.Step.UPDATE;return Next.Step.SAVED;}
    /** Un solo botón: se reconstruye solo si cambia el paso (así no parpadea con cada actualización). */
    private void lastAction(String label,int icon,Ui.Style style,View.OnClickListener click){
        String key=label+"|"+icon+"|"+style;
        if(lastButton==null||!key.equals(lastKey)){lastKey=key;lastActions.removeAllViews();lastButton=ui.button(label,icon,style,click);lastButton.setMinimumHeight(ui.dp(48));lastButton.label.setSingleLine(true);lastButton.label.setEllipsize(TextUtils.TruncateAt.END);lastActions.addView(lastButton,Ui.fill());}
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
        libraryCount=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);libraryCount.setPadding(ui.dp(S1),0,0,ui.dp(S3));libraryPanel.addView(libraryCount);
        ((LinearLayout.LayoutParams)libraryPanel.getChildAt(0).getLayoutParams()).bottomMargin=0;libraryPanel.getChildAt(0).setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(2));
        LinearLayout box=ui.row();box.setBackground(shape(this,p.surfaceContainerHighest,R_FULL));box.setPadding(ui.dp(S4),0,ui.dp(S1),0);box.addView(ui.icon(R.drawable.ic_search,p.onSurfaceVariant,20));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Buscar por título");search.setContentDescription("Buscar grabaciones por título");search.setTextColor(p.onSurface);search.setHintTextColor(p.onSurfaceVariant);AppTheme.type(search,Type.BODY_LARGE);search.setBackground(null);search.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S2),ui.dp(S3));search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){hideKeyboard();return true;}return false;});
        search.setOnFocusChangeListener((v,focused)->v.post(this::checkIme));
        box.addView(search,new LinearLayout.LayoutParams(0,ui.dp(52),1));ImageButton clear=ui.iconButton(R.drawable.ic_close,"Borrar búsqueda",p.onSurfaceVariant,0,48);clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);clear.setOnClickListener(v->search.setText(""));box.addView(clear);
        libraryPanel.addView(box,Ui.fill());
        search.setText(query);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);render();}public void afterTextChanged(Editable e){}});
        // Chips que llegan hasta el borde de la pantalla al desplazarse.
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.setClipToPadding(false);filters=ui.row();filters.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S1));hs.addView(filters);
        LinearLayout.LayoutParams hl=Ui.fill();hl.setMarginStart(-ui.dp(S4));hl.setMarginEnd(-ui.dp(S4));libraryPanel.addView(hs,hl);
        page.setClipToPadding(false);page.setClipChildren(false);libraryPanel.setClipChildren(false);
        list=ui.column();libraryPanel.addView(list,Ui.fill());
        buildPill();
    }
    /** Píldora fija arriba de la Biblioteca mientras grabas: «● 12:34 · Grabando · Detener». Tocarla vuelve a Grabar. */
    private void buildPill(){
        recPill=ui.row();recPill.setBackground(ui.ripple(shape(this,p.inverseSurface,R_FULL),R_FULL));recPill.setPadding(ui.dp(S4),0,ui.dp(S1),0);recPill.setMinimumHeight(ui.dp(48));
        pillDot=new View(this);pillDot.setBackground(oval(p.record));recPill.addView(pillDot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));recPill.addView(ui.space(S3));
        pillTime=ui.oneLine(ui.text("00:00",Type.LABEL_LARGE,p.inverseOnSurface));pillTime.setFontFeatureSettings("tnum");pillTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);recPill.addView(pillTime,new LinearLayout.LayoutParams(0,-2,1));
        TextView stop=ui.text("Detener",Type.LABEL_LARGE,p.inversePrimary);stop.setGravity(Gravity.CENTER);stop.setMinHeight(ui.dp(48));stop.setMinWidth(ui.dp(48));stop.setPadding(ui.dp(S4),0,ui.dp(S4),0);stop.setBackground(ui.ripple(null,R_FULL));stop.setClickable(true);stop.setFocusable(true);stop.setAccessibilityDelegate(Ui.buttonRole());stop.setContentDescription("Detener y guardar la grabación");
        stop.setOnClickListener(v->{if(RecorderService.activeId!=null)onRecordTap(v);});recPill.addView(stop);
        recPill.setClickable(true);recPill.setFocusable(true);recPill.setAccessibilityDelegate(Ui.buttonRole());recPill.setContentDescription("Grabación en curso. Toca para ir a Grabar");recPill.setOnClickListener(v->section(false));
        LinearLayout.LayoutParams lp=Ui.fill();lp.setMargins(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S1));root.addView(recPill,Math.max(0,root.indexOfChild(scroll)),lp);recPill.setVisibility(View.GONE);
    }
    private void renderPill(){
        if(recPill==null)return;boolean show=showLibrary&&RecorderService.activeId!=null;recPill.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){boolean paused=RecorderService.paused;pillDot.setBackground(oval(paused?p.inverseOnSurface:p.record));pillTime.setText(Recording.time(RecorderService.elapsed())+(paused?" · En pausa":" · Grabando"));}
    }
    /**
     * La barra de pestañas se esconde mientras el teclado de la búsqueda está abierto (más espacio para los resultados).
     * Solo con la búsqueda: el teclado de una hoja (p. ej. el título) no debe mover la pantalla de Grabar que queda detrás.
     */
    private void checkIme(){
        boolean ime;
        if(Build.VERSION.SDK_INT>=30){WindowInsets w=root.getRootWindowInsets();ime=w!=null&&w.isVisible(WindowInsets.Type.ime());}
        else{Rect r=new Rect();root.getWindowVisibleDisplayFrame(r);ime=root.getRootView().getHeight()-r.bottom>ui.dp(160);}
        boolean hide=ime&&showLibrary&&search!=null&&search.hasFocus();
        if(hide==imeShown)return;imeShown=hide;if(nav!=null)nav.setVisibility(hide?View.GONE:View.VISIBLE);
    }
    private void hideKeyboard(){if(search==null)return;InputMethodManager imm=getSystemService(InputMethodManager.class);if(imm!=null)imm.hideSoftInputFromWindow(search.getWindowToken(),0);if(search.hasFocus()){search.clearFocus();root.requestFocus();}}

    /**
     * Relee la lista en el hilo de disco. Durante una transcripción se pide muy seguido: las lecturas en cola que ya
     * quedaron atrás se saltan (solo corre la más reciente) y cada lectura que termina se muestra, en orden.
     */
    private void load(){
        int version=++loadVersion;Context app=getApplicationContext();
        disk.execute(()->{
            if(version!=loadVersion)return;
            boolean inbox=false;try{inbox=Inbox.configured(app);}catch(RuntimeException ignored){}
            String blocker=null;try{blocker=Pipeline.blocker(app);}catch(RuntimeException ignored){}
            long since=newSince(app);ArrayList<Item> loaded=new ArrayList<>(),missing=new ArrayList<>();
            for(Recording r:Recording.list(app)){
                Item i=new Item(r,FilesStore.state(app,r.id),Transcript.exists(app,r.id));i.inbox=inbox;
                if(i.transcribed){
                    File f=FilesStore.file(app,r.id,".transcript.json");long modified=f.lastModified();
                    Meta m=METAS.get(r.id);if(m!=null&&m.modified==modified&&m.length==f.length())i.meta=m;else missing.add(i);
                    // «Nuevo»: transcrita después de instalar esta versión y aún no abierta.
                    i.fresh=!i.state.has("opened")&&modified>since;
                    try{i.savedAt=Inbox.savedAt(app,r.id);i.outdated=i.savedAt>0&&Inbox.outdated(app,r.id);}catch(Exception ignored){}
                }
                loaded.add(i);
            }
            if(!loaded.isEmpty())try{loaded.get(0).next=Next.of(app,loaded.get(0).r);}catch(Throwable ignored){}
            // Primero la lista rápida; después, lo que hay que leer de las transcripciones (fragmento, personas y colores).
            if(!missing.isEmpty())publish(loaded,inbox,blocker);
            // Aunque llegue otra lectura, se termina: lo leído queda en METAS y la siguiente ya no lo relee.
            for(Item i:missing){Meta m=readMeta(app,i.r.id);METAS.put(i.r.id,m);i.meta=m;storeSnippet(app,i,m);}
            publish(loaded,inbox,blocker);
        });
    }
    /** Un solo hilo de disco (en orden): lo publicado siempre es más nuevo que lo anterior, así que no se descarta. */
    private void publish(ArrayList<Item> loaded,boolean inbox,String blocker){ArrayList<Item> copy=new ArrayList<>(loaded);runOnUiThread(()->{if(!isDestroyed()){items=copy;inboxOn=inbox;queueBlocker=blocker;render();}});}
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
        // Grabar: cabecera, «Última grabación» y recientes.
        Item working=null,failed=null,review=null;for(Item i:items){if(i.status.kind==RecState.Kind.QUEUED&&working==null)working=i;if(i.status.kind==RecState.Kind.FAILED&&failed==null)failed=i;if(i.fresh&&review==null)review=i;}
        workingCount=counts[2];failedCount=counts[4];workingTitle=working==null?"":working.r.title;workingId=working==null?null:working.r.id;failedId=failed==null?null:failed.r.id;reviewId=review==null?null:review.r.id;reviewTitle=review==null?"":review.r.title;renderChip();
        nav.badge(1,counts[2]>0);renderLast();
        recentList.removeAllViews();for(int k=1;k<Math.min(3,items.size());k++)addRow(recentList,row(items.get(k)));
        applyRecents();
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
    private View empty(boolean nothing){
        LinearLayout e=ui.column();e.setGravity(Gravity.CENTER_HORIZONTAL);e.setPadding(ui.dp(S6),ui.dp(S10),ui.dp(S6),ui.dp(S6));
        e.addView(nothing?ui.tile(R.drawable.ic_mic_fill,p.onPrimaryContainer,p.primaryContainer,64,30):ui.tile(R.drawable.ic_search,p.onSurfaceVariant,p.surfaceContainerHighest,64,30));
        TextView t=ui.text(nothing?"Aún no hay grabaciones":"Sin resultados",Type.TITLE_LARGE,p.onSurface);t.setPadding(0,ui.dp(S4),0,ui.dp(S1));t.setGravity(Gravity.CENTER);e.addView(t);
        String detail=nothing?"Graba una idea o importa un audio para empezar.":filter>0?"No hay grabaciones con este filtro"+(query.isEmpty()?".":" y esta búsqueda."):"Prueba con otra palabra.";
        TextView d=ui.text(detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setGravity(Gravity.CENTER);e.addView(d);
        Ui.Btn b=null;
        if(nothing)b=ui.button("Grabar ahora",R.drawable.ic_mic_fill,Ui.Style.TONAL,v->section(false));
        else if(filter>0)b=ui.button("Quitar filtro",R.drawable.ic_close,Ui.Style.TONAL,v->{filter=0;render();});
        else if(!query.isEmpty())b=ui.button("Borrar búsqueda",R.drawable.ic_close,Ui.Style.TONAL,v->search.setText(""));
        if(b!=null){LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S5);e.addView(b,lp);}
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
        TextView duration=ui.text(Ui.humanDuration(i.r.duration),Type.BODY_SMALL,p.onSurfaceVariant);duration.setFontFeatureSettings("tnum");duration.setPadding(ui.dp(S2),0,0,0);line1.addView(duration);
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
    /** Ícono inicial: neutro si está terminado; anillo de avance si transcribe; error; tonal solo si pide acción. */
    private View lead(Item i){
        FrameLayout f=new FrameLayout(this);View base;
        switch(i.status.kind){
            case QUEUED:{Ring ring=new Ring(this,p.primary,p.secondaryContainer,ui.dp(4));ring.setFraction(i.progress());base=ring;break;}
            case FAILED:base=ui.tile(R.drawable.ic_alert,p.onErrorContainer,p.errorContainer,40,20);break;
            case NEW:base=ui.tile(R.drawable.ic_wave,p.onSurfaceVariant,p.surfaceContainerHighest,40,20);break;
            default:{boolean review=i.toReview(),save=i.toSave(),act=review||save;base=ui.tile(review?R.drawable.ic_people:save?R.drawable.ic_save:R.drawable.ic_doc,act?p.onPrimaryContainer:p.onSurfaceVariant,act?p.primaryContainer:p.surfaceContainerHighest,40,20);}
        }
        f.addView(base,new FrameLayout.LayoutParams(-1,-1));
        if(i.fresh)f.addView(newDot(),new FrameLayout.LayoutParams(ui.dp(12),ui.dp(12),Gravity.TOP|Gravity.END));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return f;
    }
    /** Punto «nuevo»: transcripción lista que todavía no abriste. */
    private View newDot(){View d=new View(this);GradientDrawable g=oval(p.primary);g.setStroke(ui.dp(2),p.card);d.setBackground(g);d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return d;}
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
