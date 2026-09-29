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
    private final Runnable progress=new Runnable(){public void run(){if(player!=null&&prepared){int pos=player.getCurrentPosition();seek.setProgress(pos);elapsed.setText(Recording.time(pos));remaining.setText("-"+Recording.time(player.getDuration()-pos));tickPlayback(pos);}
        int v=FilesStore.version.get();if(!demo&&v!=dataVersion){dataVersion=v;reload();}tickProcess();handler.postDelayed(this,250);}};

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
    @Override protected void onResume(){super.onResume();handler.post(progress);if(!demo)Pipeline.startForeground(this);}
    @Override protected void onPause(){handler.removeCallbacks(progress);if(player!=null&&player.isPlaying()){player.pause();play.setImageResource(R.drawable.ic_play);}super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy();}

    private void reload(){
        if(recording==null)return;dataVersion=FilesStore.version.get();
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
            player.setOnCompletionListener(mp->{play.setImageResource(R.drawable.ic_play);play.setContentDescription("Reproducir");if(playUntil>0){playUntil=0;if(tramoEnded!=null)tramoEnded.run();}});
            player.setOnErrorListener((mp,w,e)->{releasePlayer();message("No se pudo reproducir","El audio puede haberse interrumpido al grabar.");return true;});
            Diagnostics.event("playback_open",id);
        }catch(Exception e){releasePlayer();message("No se pudo reproducir","El archivo de audio no se pudo abrir.");}
    }
    private void toggle(){ensurePlayer();if(!prepared)return;
        if(player.isPlaying()){player.pause();play.setImageResource(R.drawable.ic_play);play.setContentDescription("Reproducir");}
        else if(getSystemService(AudioManager.class).requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){applySpeed();player.start();play.setImageResource(R.drawable.ic_pause);play.setContentDescription("Pausar");}}
    private void skip(int ms){ensurePlayer();if(prepared)player.seekTo(Math.max(0,Math.min(player.getDuration(),player.getCurrentPosition()+ms)));}
    /** Escuchar sin perder el lugar: no sube al reproductor; la intervención que suena se resalta. Tocar de nuevo la misma hora pausa. */
    private void playTurn(View turn,double seconds){
        ensurePlayer();if(!prepared)return;
        if(player.isPlaying()&&playingTurn==turn&&playUntil==0){toggle();return;}
        playUntil=0;player.seekTo((int)(seconds*1000));if(!player.isPlaying())toggle();markPlaying(turn);
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
    }
    private void markPlaying(View turn){if(turn==playingTurn)return;if(playingTurn!=null)playingTurn.setBackground(null);playingTurn=turn;if(turn!=null)turn.setBackground(shape(this,p.primaryContainer,R_CONTROL));}
    private void cycleSpeed(){float[] rates={1f,1.25f,1.5f,2f,0.75f};int i=0;for(int k=0;k<rates.length;k++)if(Math.abs(rates[k]-rate)<0.01f)i=k;rate=rates[(i+1)%rates.length];String label=(rate==(int)rate?String.valueOf((int)rate):String.valueOf(rate))+"×";speed.setText(label);speed.setContentDescription("Velocidad "+label);applySpeed();}
    private void applySpeed(){if(player!=null&&prepared)try{boolean playing=player.isPlaying();player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));if(!playing&&player.isPlaying())player.pause();}catch(Exception ignored){}}
    private void releasePlayer(){prepared=false;if(player!=null){player.release();player=null;}if(focus!=null){getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}if(play!=null)play.setImageResource(R.drawable.ic_play);}

    // ---------- Estados ----------
    private void showCallToAction(){
        Settings settings=new Settings(this);LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.tile(R.drawable.ic_sparkle,p.primary,p.primaryContainer,44,24));
        TextView h=ui.text("Transcribe este audio",Type.TITLE_LARGE,p.onSurface);h.setPadding(0,ui.dp(S3),0,ui.dp(S1));card.addView(h);
        card.addView(ui.text(settings.hasKey()?"Proveedor: "+SettingsActivity.modelSummary(settings)+". Al transcribir eliges si separar voces.":"Agrega tu clave de API para transcribir. Solo pagas lo que usas en tu cuenta del proveedor.",Type.BODY_MEDIUM,p.onSurfaceVariant));
        Ui.Btn go=ui.button(settings.hasKey()?"Transcribir":"Configurar transcripción",settings.hasKey()?R.drawable.ic_sparkle:R.drawable.ic_key,Ui.Style.PRIMARY,v->RecordingActions.transcribe(this,recording,this::reload));
        card.addView(go,ui.top(S5));content.addView(card,ui.top(S4));
    }
    // ---------- Proceso en curso: estado actual, progreso, condiciones y bitácora ----------
    private String shownBlocker;private TextView phaseElapsed,upText;private ProgressBar upBar;private long phaseSince;private LinearLayout conditions;private long conditionsAt;private boolean detailsOpen;
    private void showQueued(RecState state){
        JSONObject st=FilesStore.state(this,id);
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S4));
        LinearLayout row=ui.row();row.setGravity(Gravity.TOP);ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));row.addView(spin,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));row.addView(ui.space(S4));
        LinearLayout texts=ui.column();texts.addView(ui.text("Transcripción en curso",Type.TITLE_MEDIUM,p.onSurface));
        TextView d=ui.text(state.detail,Type.BODY_MEDIUM,p.onSurface);d.setPadding(0,ui.dp(2),0,0);d.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);texts.addView(d);
        phaseSince=st.optLong("since",System.currentTimeMillis());phaseElapsed=ui.text("",Type.BODY_SMALL,p.onSurfaceVariant);phaseElapsed.setFontFeatureSettings("tnum");phaseElapsed.setPadding(0,ui.dp(2),0,0);texts.addView(phaseElapsed);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,Ui.fill());
        card.addView(stats(st,true),ui.top(S4));
        int blocks=st.optInt("blocks",0),done=st.optInt("blocksDone",0);
        if(blocks>1){TextView b=ui.text("Bloques listos: "+done+" de "+blocks,Type.LABEL_LARGE,p.onSurfaceVariant);b.setPadding(0,ui.dp(S4),0,ui.dp(S1));card.addView(b);card.addView(bar(done*100/blocks),new LinearLayout.LayoutParams(-1,ui.dp(6)));}
        long sent=st.optLong("upSent"),total=st.optLong("upTotal");
        upText=ui.text("",Type.LABEL_LARGE,p.onSurfaceVariant);upText.setFontFeatureSettings("tnum");upText.setPadding(0,ui.dp(S4),0,ui.dp(S1));upBar=bar(0);card.addView(upText);card.addView(upBar,new LinearLayout.LayoutParams(-1,ui.dp(6)));
        boolean uploading=total>0&&sent<total;upText.setVisibility(uploading?View.VISIBLE:View.GONE);upBar.setVisibility(uploading?View.VISIBLE:View.GONE);
        if(uploading){upText.setText(String.format(Locale.ROOT,"Enviando %.1f de %.1f MB",sent/1e6,total/1e6));upBar.setProgress((int)(sent*100/total));}
        conditions=ui.column();conditions.setPadding(0,ui.dp(S4),0,0);card.addView(conditions,Ui.fill());renderConditions();
        String blocker=Pipeline.blocker(this);
        if(!TranscribeService.running&&blocker==null)card.addView(ui.button("Empezar ahora",R.drawable.ic_play,Ui.Style.TONAL,v->{if(Pipeline.startForeground(this))toast("Transcribiendo en primer plano");else message("Empezar ahora","Android no permitió empezar todavía. Se hará automáticamente.");}),ui.top(S4));
        TextView note=ui.text(TranscribeService.running?"Sigue funcionando con el teléfono bloqueado. Verás el avance en la notificación.":"Esperando las condiciones configuradas. Puedes salir de la app.",Type.BODY_SMALL,p.onSurfaceVariant);note.setPadding(0,ui.dp(S3),0,0);card.addView(note);
        // Acción destructiva: color de error y separada de "Ver detalles del proceso"; pide confirmación.
        Ui.Btn stop=ui.button("Cancelar transcripción",R.drawable.ic_close,Ui.Style.PLAIN,v->RecordingActions.cancel(this,recording,this::reload));stop.label.setTextColor(p.error);stop.glyph.setImageTintList(ColorStateList.valueOf(p.error));
        card.addView(stop,ui.top(S3));
        content.addView(card,ui.top(S4));View details=timeline(st,true);((LinearLayout)details).setPadding(0,ui.dp(S4),0,0);content.addView(details);
    }
    private ProgressBar bar(int value){ProgressBar b=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);b.setMax(100);b.setProgress(value);b.setProgressTintList(ColorStateList.valueOf(p.primary));b.setProgressBackgroundTintList(ColorStateList.valueOf(p.secondaryContainer));return b;}
    /** Condiciones reales del teléfono ahora mismo (no un texto fijo): red, cargador y batería. */
    private void renderConditions(){
        if(conditions==null)return;
        // Si una condición cambió (p. ej. conectaste el cargador), se redibuja la tarjeta y se intenta empezar ya.
        String blocker=Pipeline.blocker(this);if(conditionsAt>0&&!java.util.Objects.equals(blocker,shownBlocker)){shownBlocker=blocker;conditionsAt=0;if(blocker==null)Pipeline.startForeground(this);reload();return;}
        shownBlocker=blocker;conditions.removeAllViews();conditionsAt=System.currentTimeMillis();Settings s=new Settings(this);
        boolean online=Pipeline.network(this)!=null,wifi=Pipeline.unmetered(this);android.os.BatteryManager bm=getSystemService(android.os.BatteryManager.class);boolean charging=bm.isCharging();int level=bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        condition(online&&(!s.wifiOnly()||wifi),!online?"Sin conexión a internet":s.wifiOnly()?(wifi?"Wi-Fi conectado":"Se requiere Wi-Fi · ahora usas datos móviles"):(wifi?"Conectado por Wi-Fi":"Conectado por datos móviles"));
        condition(!s.charging()||charging,s.charging()?(charging?"Cargando":"Se requiere conectar el cargador"):"Cargador no requerido");
        condition(charging||level>15,"Batería "+level+" %"+(charging||level>15?"":" · Android espera a que cargues"));
        // Con la optimización activa, algunos teléfonos congelan la app con la pantalla bloqueada y cortan la conexión.
        boolean free=Battery.unrestricted(this);
        condition(free,free?"Puede trabajar con la pantalla bloqueada":"Android optimiza la batería de Voz local · puede cortar la transcripción al bloquear");
        if(!free){Ui.Btn allow=ui.button("Permitir en segundo plano",R.drawable.ic_battery,Ui.Style.TONAL,v->RecordingActions.allowBackground(this));conditions.addView(allow,ui.top(S2));}
    }
    private void condition(boolean ok,String text){LinearLayout r=ui.row();r.setPadding(0,ui.dp(3),0,ui.dp(3));r.addView(ui.icon(ok?R.drawable.ic_check_circle:R.drawable.ic_clock,ok?p.primary:p.error,18));r.addView(ui.space(S2));r.addView(ui.text(text,Type.BODY_MEDIUM,ok?p.onSurfaceVariant:p.onSurface));conditions.addView(r);}
    // ---------- Métricas: tiempo, velocidad, costo estimado, tokens y datos ----------
    private TextView liveTotal,liveRemaining;private JSONObject liveState;
    /** Grilla de métricas. live=true: tiempo total y restante se actualizan cada 250 ms. */
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
    /** Actualización en vivo (cada 250 ms): cronómetros y, cada 3 s, las condiciones. */
    private void tickProcess(){
        if(liveTotal!=null&&liveState!=null&&liveTotal.isAttachedToWindow()){liveTotal.setText(Recording.time(System.currentTimeMillis()-liveState.optLong("queuedAt",System.currentTimeMillis())));if(liveRemaining!=null)liveRemaining.setText(remaining(liveState));}
        if(phaseElapsed==null||!phaseElapsed.isAttachedToWindow())return;
        phaseElapsed.setText("En este paso hace "+Recording.time(System.currentTimeMillis()-phaseSince));
        if(System.currentTimeMillis()-conditionsAt>3000)renderConditions();
    }
    /** Bitácora: cada paso con su hora y cuánto duró. Plegable para no abrumar. */
    private View timeline(JSONObject st,boolean collapsed){return timeline(st,collapsed,null);}
    private View timeline(JSONObject st,boolean collapsed,View header){
        JSONArray log=st.optJSONArray("log");LinearLayout box=ui.column();if(log==null||log.length()==0)return box;
        LinearLayout list=ui.column();list.setBackground(shape(this,p.card,R_CARD));list.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));
        if(header!=null){list.addView(header,Ui.fill());TextView h=ui.text("Bitácora",Type.TITLE_SMALL,p.primary);h.setPadding(0,ui.dp(S4),0,ui.dp(S1));list.addView(h);}
        SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);
        for(int i=0;i<log.length();i++){JSONObject e=log.optJSONObject(i);if(e==null)continue;long t=e.optLong("t");JSONObject n=i+1<log.length()?log.optJSONObject(i+1):null;long next=n==null?0:n.optLong("t");
            LinearLayout r=ui.row();r.setGravity(Gravity.TOP);r.setPadding(0,ui.dp(S1),0,ui.dp(S1));TextView time=ui.text(f.format(new Date(t)),Type.BODY_SMALL,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");r.addView(time,new LinearLayout.LayoutParams(ui.dp(64),-2));
            TextView m=ui.text(e.optString("m")+(next>0&&next-t>=1000?"  ("+Recording.time(next-t)+")":""),Type.BODY_SMALL,p.onSurface);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));list.addView(r);}
        Ui.Btn toggle=ui.button("Ver detalles del proceso",R.drawable.ic_info,Ui.Style.PLAIN,null);
        toggle.setOnClickListener(v->{boolean show=list.getVisibility()!=View.VISIBLE;list.setVisibility(show?View.VISIBLE:View.GONE);toggle.setText(show?"Ocultar detalles":"Ver detalles del proceso");detailsOpen=show;});
        boolean open=!collapsed||detailsOpen;list.setVisibility(open?View.VISIBLE:View.GONE);if(open)toggle.setText("Ocultar detalles");
        LinearLayout.LayoutParams tl=Ui.wrap();tl.topMargin=ui.dp(S2);box.addView(toggle,tl);box.addView(list,Ui.fill());return box;
    }
    private void showFailed(RecState state){
        LinearLayout card=ui.card();card.setBackground(shape(this,p.errorContainer,R_CARD));LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.addView(ui.icon(R.drawable.ic_alert,p.onErrorContainer,24));row.addView(ui.space(S3));
        LinearLayout texts=ui.column();texts.addView(ui.text("No se pudo transcribir",Type.TITLE_MEDIUM,p.onErrorContainer));texts.addView(ui.text(state.detail,Type.BODY_MEDIUM,p.onErrorContainer));row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,Ui.fill());
        card.addView(ui.button("Reintentar",R.drawable.ic_refresh,Ui.Style.PRIMARY,v->RecordingActions.transcribe(this,recording,this::reload)),ui.top(S4));
        card.addView(ui.button("Revisar ajustes",0,Ui.Style.PLAIN,v->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))),ui.top(S1));
        content.addView(card,ui.top(S4));content.addView(timeline(FilesStore.state(this,id),true));
    }
    // ---------- Transcripción y corrección de voces ----------
    /** Si no es null, solo se muestran las intervenciones de esta voz ("Ver solo sus intervenciones"). */
    private String onlySpeaker;
    /** Intervenciones en pantalla: {vista, inicio ms, fin ms}, para resaltar la que está sonando. */
    private final List<Object[]> turns=new ArrayList<>();private View playingTurn;
    /** Fin (ms) del tramo que se escucha desde una hoja; 0 = reproducción normal. */
    private long playUntil;private Runnable tramoEnded;
    /** Color de cada voz en esta vista (se calcula una vez por render: las transcripciones largas tienen miles de tramos). */
    private final Map<String,Integer> colors=new HashMap<>();

    private void showTranscript(){
        try{
            boolean diarized=transcript.diarized();Map<String,String> names=transcript.speakers();JSONArray segments=transcript.segments();
            if(onlySpeaker!=null&&!names.containsKey(onlySpeaker))onlySpeaker=null;
            List<String> order=transcript.order();colors.clear();for(String key:names.keySet())colors.put(key,p.speaker(order.indexOf(key)));
            LinearLayout head=ui.row();head.setPadding(ui.dp(S1),ui.dp(S6),0,ui.dp(S2));head.addView(ui.heading("Transcripción",Type.TITLE_MEDIUM));head.addView(ui.flex());
            if(diarized&&!names.isEmpty()){Ui.Btn n=ui.button("Nombrar voces",R.drawable.ic_people,Ui.Style.PLAIN,v->editSpeakers());n.setPadding(ui.dp(S2),0,ui.dp(S1),0);head.addView(n);}
            content.addView(head,Ui.fill());
            if(demo)content.addView(note(R.drawable.ic_info,"Texto de demostración: no proviene de la API. Prueba a nombrar las voces."));
            if(diarized&&names.size()>0){
                HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout chips=ui.row();chips.setPadding(0,0,0,ui.dp(S3));hs.addView(chips);
                for(String key:names.keySet()){int color=colors.get(key);boolean only=key.equals(onlySpeaker);LinearLayout c=ui.row();
                    c.setBackground(ui.ripple(only?shape(this,p.secondaryContainer,R_SMALL):outline(this,0x00000000,p.outline,R_SMALL,false),R_SMALL));c.setPadding(ui.dp(S3),ui.dp(6),ui.dp(14),ui.dp(6));c.setMinimumHeight(ui.dp(36));
                    View dot=new View(this);dot.setBackground(oval(color));c.addView(dot,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));c.addView(ui.space(S2));c.addView(ui.text(names.get(key),Type.BODY_MEDIUM,only?p.onSecondaryContainer:p.onSurface));
                    c.setClickable(true);c.setContentDescription("Opciones de "+names.get(key));c.setAccessibilityDelegate(Ui.buttonRole());c.setOnClickListener(v->personSheet(key));LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));chips.addView(c,lp);}
                content.addView(hs,Ui.fill());
            }
            // Aviso honesto: la separación automática puede equivocarse; se oculta cuando el usuario ya revisó las voces.
            if(diarized&&!transcript.reviewed()&&segments.length()>0)content.addView(note(R.drawable.ic_info,"Voces separadas automáticamente: pueden tener errores. Toca el nombre de una intervención para escuchar y corregir quién habla."));
            else if(!diarized&&transcript.data.optInt("parts",1)>1)content.addView(note(R.drawable.ic_info,"Audio procesado en bloques: los tiempos indican el inicio de cada bloque."));
            if(onlySpeaker!=null){LinearLayout f=ui.row();f.setPadding(ui.dp(S1),0,0,ui.dp(S2));f.addView(ui.text("Mostrando solo a "+names.get(onlySpeaker),Type.BODY_MEDIUM,p.onSurfaceVariant),new LinearLayout.LayoutParams(0,-2,1));
                f.addView(ui.button("Ver todo",0,Ui.Style.PLAIN,v->{onlySpeaker=null;reload();}));content.addView(f,Ui.fill());}
            LinearLayout card=ui.card();card.setPadding(ui.dp(S2),ui.dp(S1),ui.dp(S2),ui.dp(S3));
            if(segments.length()==0)card.addView(ui.text("No se detectó habla en este audio.",Type.BODY_LARGE,p.onSurfaceVariant));
            List<Double> blocks=diarized?transcript.blockStarts(demo?null:FilesStore.state(this,id)):new ArrayList<>();
            turns.clear();playingTurn=null;int block=0;boolean first=true;
            for(int i=0;i<segments.length();){
                JSONObject s=segments.getJSONObject(i);String speaker=s.getString("speaker");double start=s.getDouble("start");
                int b=block;while(b+1<blocks.size()&&start>=blocks.get(b+1)-0.05)b++;
                if(b!=block){block=b;if(onlySpeaker==null){card.addView(blockDivider(b,blocks.get(b)),Ui.fill());first=true;}}
                // Una intervención = tramos seguidos de la misma voz, sin pausas largas y dentro del mismo bloque.
                int j=i+1;double end=s.optDouble("end",start);
                while(j<segments.length()){JSONObject n=segments.getJSONObject(j);double ns=n.getDouble("start");if(!n.getString("speaker").equals(speaker)||ns-end>Transcript.TURN_GAP_S||(block+1<blocks.size()&&ns>=blocks.get(block+1)-0.05))break;end=Math.max(end,n.optDouble("end",ns));j++;}
                if(onlySpeaker==null||onlySpeaker.equals(speaker)){card.addView(turn(i,j,speaker,start,end,names,diarized,first),Ui.fill());first=false;}
                i=j;
            }
            content.addView(card,Ui.fill());
            JSONObject st=demo?new JSONObject():FilesStore.state(this,id);long took=st.optLong("doneIn");
            double cost=Pricing.estimate(transcript.data.optString("provider","openai"),transcript.data.optString("model"),recording.duration);
            TextView model=ui.footnote((transcript.data.has("model")?"Transcrito con "+transcript.data.optString("model"):"")+(took>0?" · tardó "+Recording.time(took):"")+(cost>=0&&took>0?" · ≈"+Pricing.usd(cost):""));if(!model.getText().toString().isEmpty())content.addView(model);
            if(!demo&&st.has("log"))content.addView(timeline(st,true,st.has("model")?stats(st,false):null));
            LinearLayout bar=ui.row();bar.setBackground(shape(this,p.card,R_CARD));bar.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),ui.dp(S1));
            bar.addView(ui.action(R.drawable.ic_copy,"Copiar",v->copy()),new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(ui.action(R.drawable.ic_share,"Compartir",v->shareText()),new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(ui.action(R.drawable.ic_doc,"Archivo .txt",v->shareTxt()),new LinearLayout.LayoutParams(0,-2,1));
            if(!demo){
                // Guardado rápido: un toque guarda en la carpeta elegida en Ajustes (p. ej. Drive/0-Inbox).
                Settings st2=new Settings(this);String quick=st2.prefs.getString("saveTree","");
                if(!quick.isEmpty()){String name=st2.prefs.getString("saveTreeName","Carpeta");LinearLayout q=ui.action(R.drawable.ic_save,shortLabel(name),v->quickSave());q.setContentDescription("Guardar en "+name);bar.addView(q,new LinearLayout.LayoutParams(0,-2,1));}
                bar.addView(ui.action(R.drawable.ic_folder,quick.isEmpty()?"Guardar en…":"Otra carpeta",v->saveAs()),new LinearLayout.LayoutParams(0,-2,1));
            }
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
    private static String shortLabel(String name){return name.length()>12?name.substring(0,11)+"…":name;}
    /** Guarda el .txt directo en la carpeta de guardado rápido, sin abrir el selector. */
    private void quickSave(){
        Settings s=new Settings(this);String tree=s.prefs.getString("saveTree","");String name=s.prefs.getString("saveTreeName","la carpeta");if(tree.isEmpty()){saveAs();return;}
        new Thread(()->{try{Uri t=Uri.parse(tree);Uri dir=android.provider.DocumentsContract.buildDocumentUriUsingTree(t,android.provider.DocumentsContract.getTreeDocumentId(t));
                Uri doc=android.provider.DocumentsContract.createDocument(getContentResolver(),dir,"text/plain",TranscriptExport.filename(recording.title));if(doc==null)throw new java.io.IOException();
                LocalStorage.writeText(this,doc,exportText());Diagnostics.event("transcript_quick_saved",id);runOnUiThread(()->toast("Guardado en "+name));}
            catch(Exception e){Diagnostics.event("transcript_quick_save_failed",id,"error_class",e.getClass().getSimpleName());runOnUiThread(()->sheet("No se pudo guardar en "+name,"Puede que Android haya retirado el permiso a esa carpeta. Elígela de nuevo en Ajustes o usa \"Otra carpeta\".").primary("Ir a Ajustes",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("back",true))).secondary("Otra carpeta",this::saveAs).show());}}).start();
    }
    private void saveAs(){try{Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,TranscriptExport.filename(recording.title));
        // Abre directamente donde guardaste la última vez (p. ej. tu carpeta Inbox de Drive).
        String last=new Settings(this).prefs.getString("lastSaveUri","");if(!last.isEmpty())pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(last));
        startActivityForResult(pick,SAVE_AS);}catch(ActivityNotFoundException e){message("Guardar en…","Este teléfono no tiene un selector de archivos disponible.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SAVE_AS&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri target=data.getData();new Settings(this).prefs.edit().putString("lastSaveUri",target.toString()).apply();
            new Thread(()->{try{LocalStorage.writeText(this,target,exportText());Diagnostics.event("transcript_saved_as",id);runOnUiThread(()->toast("Transcripción guardada"));}
                catch(Exception e){Diagnostics.event("transcript_save_as_failed",id,"error_class",e.getClass().getSimpleName());runOnUiThread(()->message("Guardar en…","No se pudo escribir el archivo en esa ubicación. Prueba otra carpeta."));}}).start();}
    }

    /** Una intervención: nombre (toca para corregir quién habla), hora (toca para escuchar ahí mismo) y sus frases. */
    private View turn(int a,int b,String speaker,double start,double end,Map<String,String> names,boolean diarized,boolean first)throws JSONException{
        LinearLayout t=ui.column();t.setPadding(ui.dp(S2),ui.dp(first?S1:S3),ui.dp(S2),ui.dp(S2));
        LinearLayout h=ui.row();String when=Recording.time((long)(start*1000));
        if(diarized){TextView name=ui.text(names.get(speaker),Type.BODY_MEDIUM,colors.containsKey(speaker)?colors.get(speaker):p.speaker(transcript.colorIndex(speaker)));name.setTypeface(typeface(Weight.MEDIUM));name.setGravity(Gravity.CENTER_VERTICAL);name.setMinHeight(ui.dp(48));name.setPadding(ui.dp(S1),0,ui.dp(S1),0);
            name.setBackground(ui.ripple(null,R_SMALL));name.setContentDescription(names.get(speaker)+", "+when+". Cambiar quién habla");name.setAccessibilityDelegate(Ui.buttonRole());name.setOnClickListener(v->whoSheet(a,b));h.addView(name);}
        TextView time=ui.text(when,Type.BODY_MEDIUM,p.onSurfaceVariant);time.setFontFeatureSettings("tnum");time.setGravity(Gravity.CENTER_VERTICAL);time.setMinHeight(ui.dp(48));time.setPadding(ui.dp(S1),0,ui.dp(S1),0);
        if(!demo){time.setBackground(ui.ripple(null,R_SMALL));time.setContentDescription("Escuchar desde "+when);time.setAccessibilityDelegate(Ui.buttonRole());time.setOnClickListener(v->playTurn(t,start));}
        h.addView(time);t.addView(h,Ui.fill());
        JSONArray segments=transcript.segments();
        for(int k=a;k<b;k++){if(k>a)t.addView(ui.space(S2));TextView text=ui.text(segments.getJSONObject(k).getString("text").trim(),Type.BODY_LARGE,p.onSurface);text.setTextIsSelectable(true);text.setLineSpacing(ui.dp(4),1f);text.setPadding(ui.dp(S1),0,ui.dp(S1),0);t.addView(text,Ui.fill());}
        turns.add(new Object[]{t,(long)(start*1000),(long)(end*1000)});
        return t;
    }
    /** Separador discreto donde empieza cada bloque: ahí es donde una voz puede cruzarse entre bloques. */
    private View blockDivider(int index,double startS){
        LinearLayout r=ui.row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(ui.dp(S2),ui.dp(S4),ui.dp(S2),ui.dp(S1));
        View a=new View(this);a.setBackgroundColor(p.outlineVariant);r.addView(a,new LinearLayout.LayoutParams(0,ui.dp(1),1));
        TextView label=ui.text("Bloque "+(index+1)+" · desde "+Recording.time((long)(startS*1000)),Type.BODY_SMALL,p.onSurfaceVariant);label.setPadding(ui.dp(S2),0,ui.dp(S2),0);r.addView(label);
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
    /** «¿Quién habla aquí?»: escuchar el tramo y elegir a la persona correcta, o intercambiar dos voces desde aquí. */
    private void whoSheet(int a,int b){try{
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
    /** Intercambiar dos voces desde este punto: hasta el final o solo hasta el fin del bloque. */
    private void swapScope(int a,String x,String y){try{
        Map<String,String> names=transcript.speakers();String nx=names.get(x),ny=names.get(y);double from=transcript.segments().getJSONObject(a).getDouble("start");String when=Recording.time((long)(from*1000));
        List<Double> blocks=transcript.blockStarts(demo?null:FilesStore.state(this,id));int k=0;for(int i=0;i<blocks.size();i++)if(blocks.get(i)<=from+0.05)k=i;double blockEnd=k+1<blocks.size()?blocks.get(k+1):-1;int blockNo=k+1;
        Sheet s=sheet("Intercambiar "+nx+" y "+ny,"Desde "+when+", lo que dice "+nx+" pasa a "+ny+" y al revés. Si más adelante vuelven a cruzarse, repite desde ese punto.");
        s.option(R.drawable.ic_swap,"Hasta el final","Hasta "+Recording.time(recording.duration),()->applyEdit(t->t.swap(x,y,from,Double.MAX_VALUE),"Intercambiadas desde "+when,"swap"));
        if(blockEnd>from)s.option(R.drawable.ic_swap,"Solo en el bloque "+blockNo,"Hasta "+Recording.time((long)(blockEnd*1000)),()->applyEdit(t->t.swap(x,y,from,blockEnd),"Intercambiadas en el bloque "+blockNo,"swap_block"));
        s.secondary("Cancelar",null).show();
    }catch(Exception e){message("Transcripción","No se pudo preparar el intercambio.");}}
    /** Hoja de una persona (desde su chip): escuchar, cambiar nombre, unir con otra, ver solo sus intervenciones. */
    private void personSheet(String key){try{
        Map<String,String> names=transcript.speakers();String name=names.get(key);JSONArray segs=transcript.segments();
        int count=0;double talk=0,firstAt=-1,bestLen=0,bestFrom=0,bestTo=0;
        for(int i=0;i<segs.length();i++){JSONObject s=segs.getJSONObject(i);if(!s.getString("speaker").equals(key))continue;double a=s.getDouble("start"),b=s.optDouble("end",a);count++;talk+=Math.max(0,b-a);if(firstAt<0)firstAt=a;if(b-a>bestLen&&b-a<=15){bestLen=b-a;bestFrom=a;bestTo=b;}}
        Sheet s=sheet(name,count+(count==1?" frase":" frases")+" · "+Recording.time((long)(talk*1000))+" en total · desde "+Recording.time((long)(Math.max(0,firstAt)*1000)));
        if(bestLen>0)listenButton(s,"Escuchar una muestra",bestFrom,bestTo);
        s.action(R.drawable.ic_edit,"Cambiar nombre",false,()->renameOne(key));
        if(names.size()>1)s.action(R.drawable.ic_people,"Es la misma persona que…",false,()->mergeSheet(key));
        boolean only=key.equals(onlySpeaker);s.action(R.drawable.ic_search,only?"Ver todas las intervenciones":"Ver solo sus intervenciones",false,()->{onlySpeaker=only?null:key;reload();});
        s.action(R.drawable.ic_people,"Nombrar todas las voces",false,this::editSpeakers);
        s.show();
    }catch(Exception e){message("Transcripción","No se pudo abrir esta voz.");}}
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
    /** Aplica una corrección, guarda y ofrece "Deshacer". */
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
    private void writeDemo()throws Exception{FilesStore.write(new java.io.File(getFilesDir(),"demo-transcript.json"),transcript.data);}
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

    // ---------- Hablantes ----------
    private void editSpeakers(){try{
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
}
