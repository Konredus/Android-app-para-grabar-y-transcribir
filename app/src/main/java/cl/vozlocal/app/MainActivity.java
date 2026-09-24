package cl.vozlocal.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Pestañas "Grabar" y "Biblioteca".
 * Grabar: la acción principal ocupa el centro; importar y recientes quedan debajo y se ocultan al grabar (modo foco).
 * Biblioteca: búsqueda, filtros por estado y lista agrupada por fecha. Tocar una fila abre el detalle.
 */
public class MainActivity extends Screen {
    private static final Locale ES=new Locale("es","CL");
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private boolean showLibrary;private int homeScroll,libraryScroll;

    // Grabar
    private LinearLayout homePanel,hero,importSection,recentSection,recentList,statusChip;private boolean recentsFit=true;
    private TextView statusLabel,timer,hint,titleChip,readyChip;private int workingCount,failedCount;private String workingTitle="",workingId,failedId;
    private View statusDot;private ProgressBar chipSpinner;private ImageView chipIcon;
    private RecordButton record;private Waveform wave;private ImageButton pause;
    private String lastState="";private boolean starting;

    // Biblioteca
    private LinearLayout libraryPanel,list,filters;private TextView libraryCount;private EditText search;
    private String query="";private int filter;

    static final class Item{final Recording r;final JSONObject state;final RecState status;Item(Recording r,JSONObject state,boolean transcribed){this.r=r;this.state=state;this.status=RecState.of(state,transcribed);}}
    private ArrayList<Item> items=new ArrayList<>();private int loadVersion,dataVersion=-1;

    /** 70 ms al grabar (onda fluida); 400 ms en reposo para ahorrar batería. */
    private final Runnable tick=new Runnable(){@Override public void run(){update();handler.postDelayed(this,RecorderService.activeId!=null||starting?70:400);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null){query=saved.getString("query","");filter=saved.getInt("filter");homeScroll=saved.getInt("homeScroll");libraryScroll=saved.getInt("libraryScroll");}
        showLibrary=saved!=null?saved.getBoolean("library"):getIntent().getBooleanExtra("library",false);
        shell(null,showLibrary?1:0);
        // Grabar es una pantalla FIJA (sin desplazamiento): va fuera del ScrollView y ocupa el alto disponible.
        homePanel=ui.column();homePanel.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S3));root.addView(homePanel,0,new LinearLayout.LayoutParams(-1,0,1));buildHome();
        homePanel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot){recentsFit=true;v.post(this::fitHome);}});
        libraryPanel=ui.column();page.addView(libraryPanel,Ui.fill());buildLibrary();
        section(showLibrary);
        if(saved==null)welcome();
    }
    @Override void navigate(int tab){if(tab==2)super.navigate(2);else section(tab==1);}
    @Override protected void onResume(){super.onResume();lastState="";handler.post(tick);load();if(!Pipeline.startForeground(this))Pipeline.schedule(this,false);refreshReady();}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);disk.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("library",showLibrary);out.putString("query",query);out.putInt("filter",filter);if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();out.putInt("homeScroll",homeScroll);out.putInt("libraryScroll",libraryScroll);super.onSaveInstanceState(out);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);section(intent.getBooleanExtra("library",false));}
    @Override public void onBackPressed(){if(showLibrary)section(false);else super.onBackPressed();}

    private void section(boolean library){
        if(showLibrary!=library){if(showLibrary)libraryScroll=scroll.getScrollY();else homeScroll=scroll.getScrollY();}
        showLibrary=library;nav.select(library?1:0);
        homePanel.setVisibility(library?View.GONE:View.VISIBLE);libraryPanel.setVisibility(library?View.VISIBLE:View.GONE);scroll.setVisibility(library?View.VISIBLE:View.GONE);
        if(library)ui.fadeIn(libraryPanel);
        scroll.post(()->scroll.scrollTo(0,library?libraryScroll:homeScroll));Diagnostics.event("section_open",null,"screen",library?"library":"home");
    }

    // ================= GRABAR =================
    private void buildHome(){
        LinearLayout header=ui.row();header.setPadding(ui.dp(S1),ui.dp(S2),0,ui.dp(S2));
        TextView brand=ui.heading("Voz local",Type.HEADLINE_SMALL);header.addView(brand);header.addView(ui.flex());
        // Chip de estado: configuración, transcripción en curso o error. Vive en la cabecera para no desplazar nada.
        statusChip=ui.row();statusChip.setPadding(ui.dp(S2),ui.dp(6),ui.dp(S3),ui.dp(6));statusChip.setMinimumHeight(ui.dp(32));statusChip.setClickable(true);statusChip.setAccessibilityDelegate(Ui.buttonRole());statusChip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        chipSpinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);chipSpinner.setIndeterminateTintList(ColorStateList.valueOf(p.onSecondaryContainer));statusChip.addView(chipSpinner,new LinearLayout.LayoutParams(ui.dp(16),ui.dp(16)));
        chipIcon=ui.icon(R.drawable.ic_check,p.primary,18);statusChip.addView(chipIcon);statusChip.addView(ui.space(S2));
        readyChip=ui.oneLine(ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant));readyChip.setMaxWidth(ui.dp(210));statusChip.addView(readyChip);
        header.addView(statusChip);
        homePanel.addView(header,Ui.fill());

        hero=ui.column();hero.setGravity(Gravity.CENTER);hero.setPadding(0,ui.dp(S2),0,ui.dp(S4));
        LinearLayout status=ui.row();status.setGravity(Gravity.CENTER);statusDot=new View(this);statusDot.setBackground(oval(p.record));status.addView(statusDot,new LinearLayout.LayoutParams(ui.dp(8),ui.dp(8)));status.addView(ui.space(S2));
        statusLabel=ui.text("Listo para grabar",Type.BODY_MEDIUM,p.onSurfaceVariant);statusLabel.setTypeface(typeface(Weight.MEDIUM));statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.addView(statusLabel);hero.addView(status,Ui.wrap());
        timer=ui.text("00:00",Type.BODY_LARGE,p.onSurface);timer.setTextSize(64);timer.setTypeface(typeface(Weight.LIGHT));timer.setLetterSpacing(-0.02f);timer.setFontFeatureSettings("tnum");timer.setGravity(Gravity.CENTER);timer.setPadding(0,ui.dp(S1),0,0);hero.addView(timer,Ui.wrap());
        titleChip=ui.chip("+ Añadir título",p.primary,p.primaryContainer);titleChip.setTextSize(15);titleChip.setMinHeight(ui.dp(36));titleChip.setPadding(ui.dp(14),ui.dp(6),ui.dp(14),ui.dp(6));titleChip.setMaxWidth(ui.dp(280));titleChip.setVisibility(View.INVISIBLE);titleChip.setAccessibilityDelegate(Ui.buttonRole());titleChip.setOnClickListener(v->titleWhileRecording());
        hero.addView(titleChip,Ui.wrap());
        wave=new Waveform(this,p);LinearLayout.LayoutParams wl=new LinearLayout.LayoutParams(-1,ui.dp(64));wl.topMargin=ui.dp(S4);wl.bottomMargin=ui.dp(S4);hero.addView(wave,wl);
        LinearLayout controls=ui.row();controls.setGravity(Gravity.CENTER);
        pause=ui.iconButton(R.drawable.ic_pause,"Pausar",p.onSurface,p.surfaceContainerHighest,56);pause.setOnClickListener(v->{Ui.haptic(v);send("PAUSE");});controls.addView(pause);
        controls.addView(ui.space(S6));
        record=new RecordButton(this,p);record.setContentDescription("Grabar");record.setOnClickListener(v->{Ui.haptic(v);if(RecorderService.activeId!=null)send("STOP");else begin();});controls.addView(record,new LinearLayout.LayoutParams(ui.dp(108),ui.dp(108)));
        controls.addView(ui.space(S6));View mirror=new View(this);controls.addView(mirror,new LinearLayout.LayoutParams(ui.dp(56),ui.dp(56)));
        hero.addView(controls,Ui.wrap());
        hint=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);hint.setGravity(Gravity.CENTER);hint.setPadding(ui.dp(S6),ui.dp(S4),ui.dp(S6),0);hero.addView(hint,Ui.fill());
        homePanel.addView(hero,new LinearLayout.LayoutParams(-1,0,1));

        importSection=ui.row();importSection.setGravity(Gravity.TOP);
        importSection.addView(tile(R.drawable.ic_upload,"Importar audio","MP3, M4A, WAV, OGG",v->{if(RecorderService.activeId!=null){message("Grabación en curso","Guarda primero la grabación actual.");return;}startActivity(new Intent(this,ImportActivity.class));}),new LinearLayout.LayoutParams(0,-2,1));
        importSection.addView(ui.space(S3));
        importSection.addView(tile(R.drawable.ic_chat,"Desde WhatsApp","Compartir → Voz local",v->whatsappHelp()),new LinearLayout.LayoutParams(0,-2,1));
        homePanel.addView(importSection,Ui.fill());

        recentSection=ui.column();LinearLayout rh=ui.row();rh.setPadding(ui.dp(S1),ui.dp(S3),0,0);TextView rt=ui.heading("Recientes",Type.TITLE_MEDIUM);rh.addView(rt);rh.addView(ui.flex());
        Ui.Btn all=ui.button("Ver todo",0,Ui.Style.PLAIN,v->section(true));all.setPadding(ui.dp(S3),0,ui.dp(S1),0);rh.addView(all);recentSection.addView(rh,Ui.fill());
        recentList=ui.group();recentSection.addView(recentList,Ui.fill());recentSection.setVisibility(View.GONE);homePanel.addView(recentSection,Ui.fill());
    }
    private LinearLayout tile(int icon,String title,String subtitle,View.OnClickListener click){
        LinearLayout t=ui.card();t.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S4),ui.dp(S4));t.addView(ui.tile(icon,p.primary,p.primaryContainer,40,22));
        TextView a=ui.text(title,Type.TITLE_MEDIUM,p.onSurface);a.setPadding(0,ui.dp(S3),0,ui.dp(2));t.addView(a);t.addView(ui.text(subtitle,Type.BODY_MEDIUM,p.onSurfaceVariant));
        t.setBackground(ui.ripple(shape(this,p.card,R_CARD),R_CARD));t.setClickable(true);t.setFocusable(true);t.setContentDescription(title+". "+subtitle);t.setAccessibilityDelegate(Ui.buttonRole());
        t.setOnClickListener(v->{Diagnostics.event("ui_action",null,"screen","MainActivity","action",title);click.onClick(v);});Ui.pressable(t);return t;
    }
    private void whatsappHelp(){
        Sheet s=sheet("Transcribir un audio de WhatsApp",null);
        String[] steps={"Abre el chat y mantén presionado el audio.","Toca Compartir (o ⋮ → Compartir).","Elige Voz local en la lista de apps.","Revisa el título y toca Guardar audio."};
        for(int i=0;i<steps.length;i++){LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(ui.dp(S1),ui.dp(S2),0,ui.dp(S2));TextView n=ui.text(String.valueOf(i+1),Type.LABEL_MEDIUM,p.onPrimary);n.setGravity(Gravity.CENTER);n.setBackground(oval(p.primary));r.addView(n,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));r.addView(ui.space(S3));r.addView(ui.text(steps[i],Type.BODY_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));s.add(r);}
        TextView note=ui.text("También funciona con grabadoras, Telegram y cualquier app que comparta audio.",Type.BODY_MEDIUM,p.onSurfaceVariant);note.setPadding(ui.dp(S1),ui.dp(S3),0,0);s.add(note);
        s.primary("Entendido",()->{}).show();
    }
    private void refreshReady(){renderChip();}
    /** Prioridad: falta la clave > transcribiendo > necesita atención > todo listo. */
    private void renderChip(){
        boolean ready=new Settings(this).hasKey();String text;int fg,bg,icon;boolean spin=false,outlined=false;View.OnClickListener click;
        if(!ready){text="Configurar transcripción";fg=p.onSecondaryContainer;bg=p.secondaryContainer;icon=R.drawable.ic_key;click=v->startActivity(new Intent(this,SettingsActivity.class).putExtra("focusKey",true));}
        else if(workingCount>0){text=workingCount>1?"Transcribiendo "+workingCount+" audios":"Transcribiendo «"+workingTitle+"»";fg=p.onSecondaryContainer;bg=p.secondaryContainer;icon=0;spin=true;click=v->{if(workingCount>1){filter=2;section(true);render();}else open(workingId);};}
        else if(failedCount>0){text=failedCount>1?failedCount+" necesitan atención":"Revisar transcripción";fg=p.onErrorContainer;bg=p.errorContainer;icon=R.drawable.ic_alert;click=v->{if(failedCount>1){filter=4;section(true);render();}else open(failedId);};}
        else{text="Transcripción lista";fg=p.onSurfaceVariant;bg=0;icon=R.drawable.ic_check;outlined=true;click=v->startActivity(new Intent(this,SettingsActivity.class));}
        readyChip.setText(text);readyChip.setTextColor(fg);chipSpinner.setVisibility(spin?View.VISIBLE:View.GONE);chipIcon.setVisibility(icon==0?View.GONE:View.VISIBLE);
        if(icon!=0){chipIcon.setImageResource(icon);chipIcon.setImageTintList(ColorStateList.valueOf(outlined?p.primary:fg));}
        statusChip.setBackground(ui.ripple(outlined?outline(this,0x00000000,p.outline,R_SMALL,false):shape(this,bg,R_SMALL),R_SMALL));
        statusChip.setOnClickListener(click);statusChip.setContentDescription(text);
    }
    /** Si la pantalla es baja, se ocultan "Recientes" para que Grabar nunca necesite desplazamiento. */
    private void fitHome(){
        if(hero.getWidth()==0||hero.isLayoutRequested()){homePanel.post(this::fitHome);return;}
        hero.measure(View.MeasureSpec.makeMeasureSpec(hero.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        if(hero.getHeight()<hero.getMeasuredHeight()&&recentsFit){recentsFit=false;applyRecents();}
    }
    private void applyRecents(){boolean recording=RecorderService.activeId!=null;recentSection.setVisibility(items.isEmpty()||!recentsFit?View.GONE:recording?View.INVISIBLE:View.VISIBLE);if(!recording)recentSection.setAlpha(1f);if(recentSection.getVisibility()!=View.GONE)homePanel.post(this::fitHome);}
    /** Bucle de UI: refleja el estado real del servicio de grabación. */
    private void update(){
        boolean active=RecorderService.activeId!=null,paused=RecorderService.paused;
        timer.setText(active?Recording.time(RecorderService.elapsed()):"00:00");
        String state=active+":"+paused;
        if(!state.equals(lastState)){
            boolean animate=!lastState.isEmpty();lastState=state;starting=false;record.setEnabled(true);
            record.setRecording(active,animate);record.setContentDescription(active?"Detener y guardar":"Grabar");
            pause.setVisibility(active?View.VISIBLE:View.INVISIBLE);pause.setImageResource(paused?R.drawable.ic_play:R.drawable.ic_pause);pause.setContentDescription(paused?"Continuar grabación":"Pausar");
            statusDot.setVisibility(active?View.VISIBLE:View.GONE);statusDot.setBackground(oval(paused?p.onSurfaceVariant:p.record));
            statusLabel.setText(active?(paused?"En pausa":"Grabando"):"Listo para grabar");statusLabel.setTextColor(active?(paused?p.onSurfaceVariant:p.record):p.onSurfaceVariant);
            hint.setText(active?(paused?"Toca ▶ para continuar o ■ para guardar.":"Puedes bloquear el teléfono: la grabación continúa."):"Toca el botón para grabar. Funciona sin internet.");
            wave.setLive(active);wave.setColors(paused?p.outline:p.record);titleChip.setVisibility(active?View.VISIBLE:View.INVISIBLE);
            focusMode(active);
            nav.badge(0,active);load();
        }
        if(active){String t=RecorderService.activeTitle;titleChip.setText(t==null?"+ Añadir título":t);
            float level=paused?0:Waveform.normalize(RecorderService.amplitude());wave.push(level);record.setLevel(level);
            if(paused)statusDot.setAlpha(1f);else statusDot.setAlpha((SystemClock.uptimeMillis()/600)%2==0?1f:0.25f);}
        if(RecorderService.error!=null){String m=RecorderService.error;RecorderService.error=null;starting=false;record.setEnabled(true);message("Grabación",m);}
        String saved=RecorderService.lastSavedId;if(saved!=null&&!active){RecorderService.lastSavedId=null;afterSave(saved);}
        int version=FilesStore.version.get();if(dataVersion!=version){dataVersion=version;load();}
    }
    /** Modo foco al grabar: lo secundario se desvanece pero conserva su espacio, así el botón de detener no se mueve. */
    private void focusMode(boolean on){
        for(View v:new View[]{importSection,recentSection}){if(v==recentSection&&(items.isEmpty()||!recentsFit)){v.setVisibility(View.GONE);continue;}
            v.animate().cancel();if(on){v.animate().alpha(0f).setDuration(MOTION_BASE).withEndAction(()->v.setVisibility(View.INVISIBLE)).start();}else{v.setVisibility(View.VISIBLE);v.animate().alpha(1f).setDuration(MOTION_BASE).start();}}
    }
    private void titleWhileRecording(){
        EditText input=ui.field("Ej. Reunión con el equipo","Título de la grabación");String current=RecorderService.activeTitle;if(current!=null)input.setText(current);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        Sheet s=sheet("Título de la grabación","La grabación sigue mientras escribes.").add(input);
        s.primary("Guardar título",Ui.Style.PRIMARY,()->{String t=input.getText().toString().trim();if(!t.isEmpty())startService(new Intent(this,RecorderService.class).setAction("TITLE").putExtra("title",t));return true;}).secondary("Cancelar",null).show();
        input.requestFocus();s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    /** Después de guardar: confirmar, nombrar y decidir el siguiente paso en un solo lugar. */
    private void afterSave(String id){
        Recording r=FilesStore.recording(this,id);if(r==null)return;Settings settings=new Settings(this);
        if(!settings.askTitle()){toast("Guardado en Biblioteca · "+Recording.time(r.duration));return;}
        boolean auto=settings.automatic()&&settings.hasKey();
        EditText input=ui.field("Título","Título de la grabación");input.setText(r.title);input.setSelectAllOnFocus(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        Sheet s=sheet("Grabación guardada",Recording.time(r.duration)+" · guardada en este teléfono"+(auto?" · se transcribirá automáticamente":"")).add(input);
        Runnable saveTitle=()->{String t=input.getText().toString().trim();if(!t.isEmpty()&&!t.equals(r.title)){r.title=t;try{r.save(this);Pipeline.edited(this,r.id);}catch(Exception ignored){}}};
        if(auto)s.primary("Ver grabación",()->{saveTitle.run();open(r.id);});
        else if(settings.hasKey())s.primary("Transcribir ahora",()->{saveTitle.run();RecordingActions.transcribe(this,r,this::load);});
        else s.primary("Ver grabación",()->{saveTitle.run();open(r.id);});
        s.secondary("Listo",saveTitle).show();
    }
    private void begin(){
        if(starting)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            sheet("Permitir el micrófono","Voz local usa el micrófono solo mientras grabas. El audio se guarda en este teléfono.").primary("Continuar",()->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).secondary("Ahora no",null).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        starting=true;record.setEnabled(false);
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
    private void welcome(){
        Settings settings=new Settings(this);if(settings.prefs.getBoolean("welcomed",false))return;settings.prefs.edit().putBoolean("welcomed",true).apply();
        Sheet s=sheet("Bienvenido a Voz local","Graba o importa audio y transcríbelo con tu propia clave: pagas centavos por uso, sin suscripción.");
        int[] icons={R.drawable.ic_mic_fill,R.drawable.ic_sparkle,R.drawable.ic_folder};
        String[][] rows={{"Graba sin internet","El audio queda en tu teléfono, incluso con la pantalla bloqueada."},{"Transcribe cuando quieras","Con separación de voces: Persona 1, Persona 2… y nombres editables."},{"Tus archivos son tuyos","Copias opcionales en una carpeta del teléfono o de Drive."}};
        for(int i=0;i<3;i++){LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(S2),0,ui.dp(S2));r.addView(ui.tile(icons[i],p.primary,p.primaryContainer,40,22));r.addView(ui.space(S3));LinearLayout t=ui.column();t.addView(ui.text(rows[i][0],Type.TITLE_MEDIUM,p.onSurface));TextView d=ui.text(rows[i][1],Type.BODY_MEDIUM,p.onSurfaceVariant);d.setPadding(0,ui.dp(2),0,0);t.addView(d);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));s.add(r);}
        if(settings.hasKey())s.primary("Empezar",()->{});
        else s.primary("Configurar transcripción",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("focusKey",true))).secondary("Solo grabar por ahora",null);
        s.show();
    }

    // ================= BIBLIOTECA =================
    private void buildLibrary(){
        largeTitle(libraryPanel,"Biblioteca",null);
        libraryCount=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);libraryCount.setPadding(ui.dp(S1),0,0,ui.dp(S3));libraryPanel.addView(libraryCount);
        ((LinearLayout.LayoutParams)libraryPanel.getChildAt(0).getLayoutParams()).bottomMargin=0;libraryPanel.getChildAt(0).setPadding(ui.dp(S1),ui.dp(S2),ui.dp(S1),ui.dp(2));
        LinearLayout box=ui.row();box.setBackground(shape(this,p.surfaceContainerHighest,R_CONTROL));box.setPadding(ui.dp(S3),0,ui.dp(S1),0);box.addView(ui.icon(R.drawable.ic_search,p.onSurfaceVariant,20));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Buscar por título");search.setContentDescription("Buscar grabaciones por título");search.setTextColor(p.onSurface);search.setHintTextColor(p.onSurfaceVariant);AppTheme.type(search,Type.BODY_LARGE);search.setBackground(null);search.setPadding(ui.dp(S2),ui.dp(S3),ui.dp(S2),ui.dp(S3));search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.addView(search,new LinearLayout.LayoutParams(0,ui.dp(46),1));ImageButton clear=ui.iconButton(R.drawable.ic_close,"Borrar búsqueda",p.onSurfaceVariant,0,40);clear.setVisibility(View.GONE);clear.setOnClickListener(v->search.setText(""));box.addView(clear);
        libraryPanel.addView(box,Ui.fill());
        search.setText(query);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);render();}public void afterTextChanged(Editable e){}});
        HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.setClipToPadding(false);filters=ui.row();filters.setPadding(0,ui.dp(S3),0,ui.dp(S1));hs.addView(filters);libraryPanel.addView(hs,Ui.fill());
        list=ui.column();libraryPanel.addView(list,Ui.fill());
    }
    private void load(){
        int version=++loadVersion;Context app=getApplicationContext();
        disk.execute(()->{ArrayList<Item> loaded=new ArrayList<>();for(Recording r:Recording.list(app))loaded.add(new Item(r,FilesStore.state(app,r.id),Transcript.exists(app,r.id)));
            runOnUiThread(()->{if(!isDestroyed()&&version==loadVersion){items=loaded;render();}});});
    }
    private boolean matches(Item i,int f){switch(f){case 1:return i.status.kind==RecState.Kind.DONE;case 2:return i.status.kind==RecState.Kind.QUEUED;case 3:return i.status.kind==RecState.Kind.NEW;case 4:return i.status.kind==RecState.Kind.FAILED;default:return true;}}
    private void render(){
        int[] counts=new int[5];for(Item i:items)for(int f=0;f<5;f++)if(matches(i,f))counts[f]++;
        // Grabar: tarjeta de procesamiento y recientes
        Item working=null,failed=null;for(Item i:items){if(i.status.kind==RecState.Kind.QUEUED&&working==null)working=i;if(i.status.kind==RecState.Kind.FAILED&&failed==null)failed=i;}
        workingCount=counts[2];failedCount=counts[4];workingTitle=working==null?"":working.r.title;workingId=working==null?null:working.r.id;failedId=failed==null?null:failed.r.id;renderChip();
        nav.badge(1,counts[2]>0);
        recentList.removeAllViews();for(int i=0;i<Math.min(2,items.size());i++)ui.addRow(recentList,row(items.get(i)));
        applyRecents();
        // Biblioteca
        libraryCount.setText(items.isEmpty()?"Tus audios y transcripciones":items.size()+(items.size()==1?" grabación":" grabaciones")+" · "+counts[1]+(counts[1]==1?" transcrita":" transcritas"));
        filters.removeAllViews();String[] names={"Todas","Transcritas","En proceso","Sin transcribir","Con error"};
        if(filter>0&&counts[filter]==0)filter=0;
        for(int f=0;f<5;f++){if(f>0&&counts[f]==0)continue;int index=f;TextView chip=ui.filter(names[f]+(f>0?" "+counts[f]:""),f==filter,v->{filter=index;Ui.haptic(v);render();});LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));filters.addView(chip,lp);}
        ((View)filters.getParent()).setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);
        list.removeAllViews();String q=query.toLowerCase(ES).trim();String currentSection=null;LinearLayout group=null;int visible=0;
        for(Item i:items){
            if(!matches(i,filter)||!i.r.title.toLowerCase(ES).contains(q))continue;visible++;
            String sec=dateSection(i.r.created);if(!sec.equals(currentSection)){currentSection=sec;TextView h=ui.section(sec);h.setPadding(ui.dp(S1),ui.dp(S5),0,ui.dp(S2));list.addView(h);group=ui.group();list.addView(group,Ui.fill());}
            ui.addRow(group,row(i));
        }
        if(visible==0)list.addView(empty(items.isEmpty()));
    }
    private View empty(boolean nothing){
        LinearLayout e=ui.column();e.setGravity(Gravity.CENTER_HORIZONTAL);e.setPadding(ui.dp(S6),ui.dp(S10),ui.dp(S6),ui.dp(S6));
        e.addView(ui.tile(nothing?R.drawable.ic_mic_fill:R.drawable.ic_search,p.primary,p.primaryContainer,64,30));
        TextView t=ui.text(nothing?"Aún no hay grabaciones":"Sin resultados",Type.TITLE_LARGE,p.onSurface);t.setPadding(0,ui.dp(S4),0,ui.dp(S1));t.setGravity(Gravity.CENTER);e.addView(t);
        TextView d=ui.text(nothing?"Graba una idea o importa un audio para empezar.":"Prueba con otra palabra o quita el filtro.",Type.BODY_MEDIUM,p.onSurfaceVariant);d.setGravity(Gravity.CENTER);e.addView(d);
        if(nothing){Ui.Btn b=ui.button("Grabar ahora",R.drawable.ic_mic_fill,Ui.Style.TONAL,v->section(false));LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S5);e.addView(b,lp);}
        return e;
    }
    /** Fila de grabación: ícono de estado, título, duración + estado coloreado, y menú. */
    private View row(Item i){
        LinearLayout row=ui.row();row.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S1),ui.dp(S3));row.setMinimumHeight(ui.dp(68));
        row.addView(ui.tile(i.status.icon(),i.status.onBg(p),i.status.bg(p),40,20));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();TextView title=ui.oneLine(ui.text(i.r.title,Type.TITLE_MEDIUM,p.onSurface));texts.addView(title);
        String when=new SimpleDateFormat(DateUtils.isToday(i.r.created)?"HH:mm":"d MMM · HH:mm",ES).format(new Date(i.r.created));
        SpannableStringBuilder meta=new SpannableStringBuilder(when+" · "+Recording.time(i.r.duration)+" · ");int start=meta.length();meta.append(i.status.kind==RecState.Kind.QUEUED?i.status.detail:i.status.label);meta.setSpan(new ForegroundColorSpan(i.status.kind==RecState.Kind.NEW?p.onSurfaceVariant:i.status.fg(p)),start,meta.length(),0);
        TextView m=ui.oneLine(ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant));m.setText(meta);m.setPadding(0,ui.dp(2),0,0);texts.addView(m);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton more=ui.iconButton(R.drawable.ic_more,"Opciones de "+i.r.title,p.onSurfaceVariant,0,44);more.setOnClickListener(v->RecordingActions.menu(this,i.r,this::load,null));row.addView(more);
        row.setBackground(ui.ripple(null,0));row.setClickable(true);row.setFocusable(true);row.setContentDescription(i.r.title+". "+Recording.time(i.r.duration)+". "+i.status.label);row.setAccessibilityDelegate(Ui.buttonRole());
        row.setOnClickListener(v->open(i.r.id));row.setOnLongClickListener(v->{Ui.haptic(v);RecordingActions.menu(this,i.r,this::load,null);return true;});
        return row;
    }
    private static String dateSection(long time){
        if(DateUtils.isToday(time))return "Hoy";if(DateUtils.isToday(time+DateUtils.DAY_IN_MILLIS))return "Ayer";
        if(System.currentTimeMillis()-time<7*DateUtils.DAY_IN_MILLIS)return "Esta semana";
        String month=new SimpleDateFormat("MMMM yyyy",ES).format(new Date(time));return Character.toUpperCase(month.charAt(0))+month.substring(1);
    }
    private void open(String id){startActivity(new Intent(this,RecordingActivity.class).putExtra("id",id));}
}
