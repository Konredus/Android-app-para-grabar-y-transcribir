package cl.vozlocal.app;

import android.content.*;
import android.content.res.ColorStateList;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Detalle de una grabación: escuchar, ver su estado y leer/compartir la transcripción en un solo lugar.
 * La información va de lo general (título, estado) a lo particular (texto), y las acciones de salida quedan fijas abajo.
 */
public class RecordingActivity extends Screen {
    private static final int SAVE_AS=71;
    private String id;private boolean demo;private Recording recording;private Transcript transcript;
    private TextView title,meta,chip;private LinearLayout content,playerCard;
    private final Handler handler=new Handler(Looper.getMainLooper());private int dataVersion;
    // Reproductor
    private MediaPlayer player;private AudioFocusRequest focus;private SeekBar seek;private TextView elapsed,remaining,speed;private ImageButton play;private boolean prepared;private float rate=1f;
    private final Runnable progress=new Runnable(){public void run(){if(player!=null&&prepared){int pos=player.getCurrentPosition();seek.setProgress(pos);elapsed.setText(Recording.time(pos));remaining.setText("-"+Recording.time(player.getDuration()-pos));}
        int v=FilesStore.version.get();if(!demo&&v!=dataVersion){dataVersion=v;reload();}handler.postDelayed(this,250);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);demo=getIntent().getBooleanExtra("demo",false);id=getIntent().getStringExtra("id");
        shell(demo?"Ajustes":"Biblioteca",-1);
        try{
            if(demo){recording=new Recording("00000000-0000-0000-0000-000000000000","Conversación de ejemplo",System.currentTimeMillis(),27000);java.io.File f=new java.io.File(getFilesDir(),"demo-transcript.json");transcript=f.exists()?new Transcript(FilesStore.read(f)):example();}
            else{recording=FilesStore.recording(this,id);if(recording==null)throw new java.io.FileNotFoundException();}
        }catch(Exception e){largeTitle(page,"No disponible","Esta grabación ya no existe o no se pudo abrir.");return;}
        if(!demo){ImageButton more=ui.iconButton(R.drawable.ic_more,"Más opciones",p.onSurfaceVariant,0,48);more.setOnClickListener(v->RecordingActions.menu(this,recording,this::reload,this::releasePlayer));barActions.addView(more);}
        title=ui.heading(recording.title,Type.HEADLINE_SMALL);title.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),ui.dp(S1));
        if(!demo){title.setOnClickListener(v->RecordingActions.rename(this,recording,this::reload));title.setContentDescription(recording.title+". Toca para cambiar el título");}
        page.addView(title);
        LinearLayout metaRow=ui.row();metaRow.setPadding(ui.dp(S1),0,0,ui.dp(S4));meta=ui.text("",Type.BODY_MEDIUM,p.onSurfaceVariant);metaRow.addView(meta,new LinearLayout.LayoutParams(0,-2,1));chip=ui.chip("",p.onSurfaceVariant,p.surfaceContainerHighest);metaRow.addView(chip);page.addView(metaRow,Ui.fill());
        if(!demo){playerCard=buildPlayer();page.addView(playerCard,Ui.fill());}
        content=ui.column();page.addView(content,Ui.fill());
        dataVersion=FilesStore.version.get();reload();
    }
    @Override protected void onResume(){super.onResume();handler.post(progress);}
    @Override protected void onPause(){handler.removeCallbacks(progress);if(player!=null&&player.isPlaying()){player.pause();play.setImageResource(R.drawable.ic_play);}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy();}

    private void reload(){
        if(recording==null)return;
        if(!demo){Recording latest=FilesStore.recording(this,id);if(latest==null){finish();return;}recording=latest;try{transcript=Transcript.exists(this,id)?Transcript.load(this,id):null;}catch(Exception e){transcript=null;}}
        title.setText(recording.title);
        meta.setText(new SimpleDateFormat("d MMM yyyy · HH:mm",new Locale("es","CL")).format(new Date(recording.created))+" · "+Recording.time(recording.duration));
        RecState state=demo?RecState.of(new JSONObject(),true):RecState.of(this,id);
        chip.setText(demo?"Ejemplo":state.label);chip.setTextColor(demo?p.onSecondaryContainer:state.onBg(p));chip.setBackground(shape(this,demo?p.secondaryContainer:state.bg(p),R_SMALL));
        content.removeAllViews();bottom.removeAllViews();bottom.setVisibility(View.GONE);
        if(transcript!=null)showTranscript();
        else if(state.kind==RecState.Kind.QUEUED)showQueued(state);
        else if(state.kind==RecState.Kind.FAILED)showFailed(state);
        else showCallToAction();
    }

    // ---------- Reproductor ----------
    private LinearLayout buildPlayer(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S4));
        seek=new SeekBar(this);seek.setContentDescription("Posición de reproducción");seek.setProgressTintList(ColorStateList.valueOf(p.primary));seek.setThumbTintList(ColorStateList.valueOf(p.primary));seek.setProgressBackgroundTintList(ColorStateList.valueOf(p.outline));seek.setPadding(ui.dp(S2),ui.dp(S3),ui.dp(S2),ui.dp(S3));seek.setMax(Math.max(1,(int)recording.duration));
        card.addView(seek,Ui.fill());
        LinearLayout times=ui.row();times.setPadding(ui.dp(S2),0,ui.dp(S2),0);elapsed=ui.text("00:00",Type.BODY_MEDIUM,p.onSurfaceVariant);elapsed.setFontFeatureSettings("tnum");remaining=ui.text("-"+Recording.time(recording.duration),Type.BODY_MEDIUM,p.onSurfaceVariant);remaining.setFontFeatureSettings("tnum");times.addView(elapsed);times.addView(ui.flex());times.addView(remaining);card.addView(times,Ui.fill());
        LinearLayout controls=ui.row();controls.setGravity(Gravity.CENTER);controls.setPadding(0,ui.dp(S2),0,0);
        speed=ui.chip("1×",p.onSurface,p.surfaceContainerHighest);speed.setMinWidth(ui.dp(52));speed.setMinHeight(ui.dp(36));speed.setContentDescription("Velocidad 1x");speed.setAccessibilityDelegate(Ui.buttonRole());speed.setOnClickListener(v->cycleSpeed());controls.addView(speed);controls.addView(ui.flex());
        ImageButton back=ui.iconButton(R.drawable.ic_replay,"Retroceder 15 segundos",p.onSurface,0,52);back.setOnClickListener(v->skip(-15000));controls.addView(back);controls.addView(ui.space(S3));
        play=ui.iconButton(R.drawable.ic_play,"Reproducir",p.onPrimary,p.primary,64);play.setOnClickListener(v->toggle());controls.addView(play);controls.addView(ui.space(S3));
        ImageButton fwd=ui.iconButton(R.drawable.ic_replay,"Adelantar 15 segundos",p.onSurface,0,52);fwd.setScaleX(-1f);fwd.setOnClickListener(v->skip(15000));controls.addView(fwd);
        controls.addView(ui.flex());View balance=new View(this);controls.addView(balance,new LinearLayout.LayoutParams(ui.dp(52),ui.dp(36)));
        card.addView(controls,Ui.fill());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean user){if(user){ensurePlayer();if(prepared)player.seekTo(v);elapsed.setText(Recording.time(v));}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        return card;
    }
    private void ensurePlayer(){
        if(player!=null||demo)return;
        if(RecorderService.activeId!=null){message("Grabación en curso","Guarda la grabación actual antes de reproducir.");return;}
        try{
            player=new MediaPlayer();AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();player.setAudioAttributes(attr);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).setOnAudioFocusChangeListener(c->{if(c<0&&player!=null&&player.isPlaying()){player.pause();play.setImageResource(R.drawable.ic_play);}}).build();
            player.setDataSource(recording.audio(this).getAbsolutePath());player.prepare();prepared=true;seek.setMax(player.getDuration());
            player.setOnCompletionListener(mp->{play.setImageResource(R.drawable.ic_play);play.setContentDescription("Reproducir");});
            player.setOnErrorListener((mp,w,e)->{releasePlayer();message("No se pudo reproducir","El audio puede haberse interrumpido al grabar.");return true;});
            Diagnostics.event("playback_open",id);
        }catch(Exception e){releasePlayer();message("No se pudo reproducir","El archivo de audio no se pudo abrir.");}
    }
    private void toggle(){ensurePlayer();if(!prepared)return;
        if(player.isPlaying()){player.pause();play.setImageResource(R.drawable.ic_play);play.setContentDescription("Reproducir");}
        else if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){applySpeed();player.start();play.setImageResource(R.drawable.ic_pause);play.setContentDescription("Pausar");}}
    private void skip(int ms){ensurePlayer();if(prepared)player.seekTo(Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+ms)));}
    private void seekTo(double seconds){ensurePlayer();if(!prepared)return;player.seekTo((int)(seconds*1000));if(!player.isPlaying())toggle();if(playerCard!=null)scroll.smoothScrollTo(0,0);}
    private void cycleSpeed(){float[] rates={1f,1.25f,1.5f,2f,0.75f};int i=0;for(int k=0;k<rates.length;k++)if(Math.abs(rates[k]-rate)<0.01f)i=k;rate=rates[(i+1)%rates.length];String label=(rate==(int)rate?String.valueOf((int)rate):String.valueOf(rate))+"×";speed.setText(label);speed.setContentDescription("Velocidad "+label);applySpeed();}
    private void applySpeed(){if(player!=null&&prepared)try{boolean playing=player.isPlaying();player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));if(!playing&&player.isPlaying())player.pause();}catch(Exception ignored){}}
    private void releasePlayer(){prepared=false;if(player!=null){player.release();player=null;}if(focus!=null){getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}if(play!=null)play.setImageResource(R.drawable.ic_play);}

    // ---------- Estados ----------
    private void showCallToAction(){
        Settings settings=new Settings(this);LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.tile(R.drawable.ic_sparkle,p.primary,p.primaryContainer,44,24));
        TextView h=ui.text("Transcribe este audio",Type.TITLE_LARGE,p.onSurface);h.setPadding(0,ui.dp(S3),0,ui.dp(S1));card.addView(h);
        card.addView(ui.text(settings.hasKey()?"Se enviará a "+SettingsActivity.modelSummary(settings)+". Suele tardar menos que la duración del audio.":"Agrega tu clave de API para transcribir. Solo pagas lo que usas en tu cuenta del proveedor.",Type.BODY_MEDIUM,p.onSurfaceVariant));
        Ui.Btn go=ui.button(settings.hasKey()?"Transcribir":"Configurar transcripción",settings.hasKey()?R.drawable.ic_sparkle:R.drawable.ic_key,Ui.Style.PRIMARY,v->RecordingActions.transcribe(this,recording,this::reload));
        card.addView(go,ui.top(S5));content.addView(card,ui.top(S4));
    }
    private void showQueued(RecState state){
        LinearLayout card=ui.card();LinearLayout row=ui.row();ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));row.addView(spin,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();texts.addView(ui.text("Transcripción en curso",Type.TITLE_MEDIUM,p.onSurface));TextView d=ui.text(state.detail,Type.BODY_MEDIUM,p.onSurfaceVariant);d.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);texts.addView(d);row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,Ui.fill());
        Settings s=new Settings(this);TextView note=ui.text("Puedes salir de la app: te avisaremos con una notificación."+(s.wifiOnly()?" Espera una red Wi-Fi.":"")+(s.charging()?" Espera a que el teléfono esté cargando.":""),Type.BODY_MEDIUM,p.onSurfaceVariant);note.setPadding(0,ui.dp(S3),0,0);card.addView(note);
        card.addView(ui.button("Cancelar transcripción",0,Ui.Style.PLAIN,v->RecordingActions.cancel(this,recording,this::reload)),ui.top(S2));content.addView(card,ui.top(S4));
    }
    private void showFailed(RecState state){
        LinearLayout card=ui.card();card.setBackground(shape(this,p.errorContainer,R_CARD));LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.addView(ui.icon(R.drawable.ic_alert,p.error,24));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();texts.addView(ui.text("No se pudo transcribir",Type.TITLE_MEDIUM,p.onSurface));texts.addView(ui.text(state.detail,Type.BODY_MEDIUM,p.onSurface));row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,Ui.fill());
        card.addView(ui.button("Reintentar",R.drawable.ic_refresh,Ui.Style.PRIMARY,v->RecordingActions.transcribe(this,recording,this::reload)),ui.top(S4));
        card.addView(ui.button("Revisar ajustes",0,Ui.Style.PLAIN,v->startActivity(new Intent(this,SettingsActivity.class))),ui.top(S1));
        content.addView(card,ui.top(S4));
    }
    private void showTranscript(){
        try{
            boolean diarized=transcript.data.optBoolean("diarized",true);Map<String,String> names=transcript.speakers();JSONArray segments=transcript.segments();
            List<String> order=new ArrayList<>(names.keySet());
            LinearLayout head=ui.row();head.setPadding(ui.dp(S1),ui.dp(S6),0,ui.dp(S2));head.addView(ui.heading("Transcripción",Type.TITLE_MEDIUM));head.addView(ui.flex());
            if(diarized&&!names.isEmpty()){Ui.Btn n=ui.button("Nombrar voces",R.drawable.ic_people,Ui.Style.PLAIN,v->editSpeakers());n.setPadding(ui.dp(S2),0,ui.dp(S1),0);head.addView(n);}
            content.addView(head,Ui.fill());
            if(demo)content.addView(note(R.drawable.ic_info,"Texto de demostración: no proviene de la API. Prueba a nombrar las voces."));
            if(diarized&&names.size()>0){
                HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout chips=ui.row();chips.setPadding(0,0,0,ui.dp(S3));hs.addView(chips);
                for(String key:order){int color=p.speaker(order.indexOf(key));LinearLayout c=ui.row();c.setBackground(ui.ripple(outline(this,0x00000000,p.outline,R_SMALL,false),R_SMALL));c.setPadding(ui.dp(S3),ui.dp(6),ui.dp(14),ui.dp(6));c.setMinimumHeight(ui.dp(36));View dot=new View(this);dot.setBackground(oval(color));c.addView(dot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));c.addView(ui.space(S2));c.addView(ui.text(names.get(key),Type.BODY_MEDIUM,p.onSurface));c.setClickable(true);c.setContentDescription("Cambiar nombre de "+names.get(key));c.setAccessibilityDelegate(Ui.buttonRole());c.setOnClickListener(v->editSpeakers());LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));chips.addView(c,lp);}
                content.addView(hs,Ui.fill());
            }
            if(transcript.data.optInt("parts",1)>1)content.addView(note(R.drawable.ic_info,diarized?"Audio procesado en bloques: una misma persona puede aparecer con dos etiquetas. Dales el mismo nombre si corresponde.":"Audio procesado en bloques: los tiempos indican el inicio de cada bloque."));
            LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S2),ui.dp(S4),ui.dp(S4));
            if(segments.length()==0)card.addView(ui.text("No se detectó habla en este audio.",Type.BODY_LARGE,p.onSurfaceVariant));
            String last=null;
            for(int i=0;i<segments.length();i++){
                JSONObject s=segments.getJSONObject(i);String speaker=s.getString("speaker");double start=s.getDouble("start");
                if(!speaker.equals(last)){
                    LinearLayout h=ui.row();h.setPadding(0,ui.dp(last==null?S2:S5),0,ui.dp(S1));
                    if(diarized){TextView name=ui.text(names.get(speaker),Type.BODY_MEDIUM,p.speaker(order.indexOf(speaker)));name.setTypeface(typeface(Weight.MEDIUM));h.addView(name);h.addView(ui.space(S2));}
                    TextView time=ui.text(Recording.time((long)(start*1000)),Type.BODY_MEDIUM,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");
                    if(!demo){time.setTextColor(p.onSurfaceVariant);time.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),ui.dp(S1));time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription("Escuchar desde "+Recording.time((long)(start*1000)));time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->seekTo(start));}
                    h.addView(time);card.addView(h,Ui.fill());last=speaker;
                }else card.addView(ui.space(S2));
                TextView text=ui.text(s.getString("text").trim(),Type.BODY_LARGE,p.onSurface);text.setTextIsSelectable(true);text.setLineSpacing(ui.dp(4),1f);card.addView(text,Ui.fill());
            }
            content.addView(card,Ui.fill());
            TextView model=ui.footnote(transcript.data.has("model")?"Transcrito con "+transcript.data.optString("model"):"");if(!model.getText().toString().isEmpty())content.addView(model);
            LinearLayout bar=ui.row();bar.setBackground(shape(this,p.card,R_CARD));bar.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),ui.dp(S1));
            bar.addView(ui.action(R.drawable.ic_copy,"Copiar",v->copy()),new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(ui.action(R.drawable.ic_share,"Compartir",v->shareText()),new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(ui.action(R.drawable.ic_doc,"Archivo .txt",v->shareTxt()),new LinearLayout.LayoutParams(0,-2,1));
            if(!demo)bar.addView(ui.action(R.drawable.ic_save,"Guardar en…",v->saveAs()),new LinearLayout.LayoutParams(0,-2,1));
            bottom.addView(bar,Ui.fill());bottom.setVisibility(View.VISIBLE);
        }catch(Exception e){content.addView(ui.text("No se pudo leer la transcripción.",Type.BODY_LARGE,p.error));}
    }
    private View note(int icon,String value){LinearLayout n=ui.row();n.setGravity(Gravity.TOP);n.setBackground(shape(this,p.primaryContainer,R_CONTROL));n.setPadding(ui.dp(S3),ui.dp(S3),ui.dp(S3),ui.dp(S3));n.addView(ui.icon(icon,p.primary,18));n.addView(ui.space(S2));n.addView(ui.text(value,Type.BODY_MEDIUM,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams lp=Ui.fill();lp.bottomMargin=ui.dp(S3);n.setLayoutParams(lp);return n;}

    // ---------- Exportar ----------
    private String exportText()throws Exception{if(!demo){recording=FilesStore.recording(this,id);transcript=Transcript.load(this,id);}return transcript.text(recording);}
    private void copy(){try{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Transcripción",exportText()));if(Build.VERSION.SDK_INT<33)toast("Texto copiado");}catch(Exception e){message("Copiar","No se pudo copiar el texto.");}}
    private void shareText(){try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,recording.title).putExtra(Intent.EXTRA_TEXT,exportText()),"Compartir transcripción"));}catch(Exception e){message("Compartir","No se pudo compartir el texto.");}}
    private void shareTxt(){try{exportText();Uri uri=TranscriptExport.create(this,recording,transcript);startActivity(Intent.createChooser(TranscriptExport.shareIntent(uri),"Compartir archivo .txt"));}catch(Exception e){message("Archivo","No se pudo crear el archivo.");}}
    /** Guardar en cualquier ubicación, incluida Google Drive si su app está instalada (sin iniciar sesión en Voz local). */
    private void saveAs(){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,TranscriptExport.filename(recording.title)),SAVE_AS);}catch(ActivityNotFoundException e){message("Guardar en…","Este teléfono no tiene un selector de archivos disponible.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SAVE_AS&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri target=data.getData();
            new Thread(()->{try{LocalStorage.writeText(this,target,exportText());Diagnostics.event("transcript_saved_as",id);runOnUiThread(()->toast("Transcripción guardada"));}
                catch(Exception e){Diagnostics.event("transcript_save_as_failed",id,"error_class",e.getClass().getSimpleName());runOnUiThread(()->message("Guardar en…","No se pudo escribir el archivo en esa ubicación. Prueba otra carpeta."));}}).start();}
    }

    // ---------- Hablantes ----------
    private void editSpeakers(){try{
        Map<String,String> current=transcript.speakers();List<String> order=new ArrayList<>(current.keySet());Map<String,EditText> fields=new LinkedHashMap<>();
        Sheet s=sheet("Nombrar voces","El nombre se aplica a todas las intervenciones de esa voz.");
        for(String key:order){LinearLayout row=ui.row();row.setPadding(0,ui.dp(S1),0,ui.dp(S1));View dot=new View(this);dot.setBackground(oval(p.speaker(order.indexOf(key))));row.addView(dot,new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)));row.addView(ui.space(S3));
            EditText f=ui.field("Persona "+(order.indexOf(key)+1),"Nombre para Persona "+(order.indexOf(key)+1));String value=current.get(key);if(!value.startsWith("Persona "))f.setText(value);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});row.addView(f,new LinearLayout.LayoutParams(0,-2,1));fields.put(key,f);s.add(row);}
        s.primary("Guardar nombres",Ui.Style.PRIMARY,()->{try{
            Map<String,String> names=new LinkedHashMap<>();for(Map.Entry<String,EditText> f:fields.entrySet())names.put(f.getKey(),f.getValue().getText().toString().trim());
            if(demo){JSONObject mapping=new JSONObject();for(Map.Entry<String,String> n:names.entrySet())mapping.put(n.getKey(),n.getValue());transcript.data.put("names",mapping);FilesStore.write(new java.io.File(getFilesDir(),"demo-transcript.json"),transcript.data);}
            else Transcript.rename(this,id,names);
            reload();toast("Nombres guardados");return true;}catch(Exception e){message("No se guardaron los nombres","Vuelve a intentarlo.");return false;}
        }).secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudieron cargar las voces.");}}

    static Transcript example()throws Exception{return new Transcript(new JSONObject("{\"demo\":true,\"names\":{},\"segments\":[{\"speaker\":\"A\",\"start\":0,\"end\":7,\"text\":\"Me gustaría que guardemos las ideas de esta conversación.\"},{\"speaker\":\"B\",\"start\":7,\"end\":14,\"text\":\"Sí, y después podemos revisar juntos lo que dijimos.\"},{\"speaker\":\"C\",\"start\":14,\"end\":20,\"text\":\"Yo puedo ayudar a ordenar los próximos pasos.\"},{\"speaker\":\"A\",\"start\":20,\"end\":27,\"text\":\"Perfecto. Así no se nos pierde ninguna idea.\"}]}"));}
}
