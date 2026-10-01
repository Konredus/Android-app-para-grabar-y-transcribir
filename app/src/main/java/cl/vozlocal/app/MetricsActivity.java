package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static cl.vozlocal.app.AppTheme.*;

/**
 * «Tus métricas» (0.8.0): tu voz en números, el camino hacia tu segundo cerebro, costos y gráficos. Pedido del dueño
 * («eso se ve bonito»), pensado para quien habla mucho y carga lo transcrito a su segundo cerebro: que motive (palabras,
 * tiempo ahorrado, racha) y que ayude a controlar el gasto (US$ por mes, por hora de audio y por modelo).
 *
 * Orden de la pantalla (jerarquía, de lo que motiva a lo que se revisa):
 * 1. Tarjeta principal (todo el historial): palabras transcritas en grande, tiempo ahorrado y racha.
 * 2. Últimas 8 semanas: minutos grabados y gasto, dos gráficos chicos con el mismo eje de semanas (nunca dos escalas en
 *    un mismo gráfico). Tocar una semana la elige en los dos y muestra sus cifras arriba.
 * 3. «Por período» (7 días · este mes · todo): una sola fila de filtros que manda sobre todo lo que viene debajo: tu voz
 *    en números, el embudo hacia tu segundo cerebro (con acceso a lo que quedó a medio camino), costos, cuándo grabas
 *    (mapa de días y horas) y con quién más conversas.
 *
 * Gráficos propios (Canvas), siguiendo la guía dataviz adaptada a la paleta de AppTheme: un solo verde de marca; barras
 * finas (≤ 24 dp) con la punta redondeada (4 dp) y la base recta; 2 dp de aire entre segmentos; líneas guía de 1 px
 * recesivas; los textos siempre en tinta (onSurface / onSurfaceVariant), nunca del color del dato; rótulos selectivos (la
 * semana elegida). Escalas ordenadas (embudo, mapa de calor) en pasos de un mismo verde, de claro a oscuro (en oscuro se
 * invierten: más = más claro); se validaron con validate_palette.js --ordinal en ambos temas. Cada gráfico tiene su
 * descripción para TalkBack con todos sus valores (la «tabla» accesible), se maneja con las flechas del teclado y respeta
 * «Quitar animaciones».
 *
 * El cálculo (Metrics.compute) va en un hilo de disco. Al volver a la pantalla, si algo cambió, se recalcula manteniendo
 * lo anterior a media opacidad (sin parpadeo ni saltos).
 */
public class MetricsActivity extends Screen {
    /** Abre «Tus métricas». El botón ← dice a dónde vuelve: Grabar («Tu semana») o Ajustes. */
    static void open(Context c){c.startActivity(new Intent(c,MetricsActivity.class).putExtra("from",c instanceof MainActivity?"Grabar":"Ajustes"));}

    private static final String[] DAYS={"L","M","M","J","V","S","D"};
    private static final String[] DAY_NAMES={"Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo"};
    private static final String[] DAY_PLURAL={"los lunes","los martes","los miércoles","los jueves","los viernes","los sábados","los domingos"};
    /** Filtros de la Biblioteca (MainActivity): 1 por guardar o transcritas, 2 en proceso, 3 sin transcribir, 4 con error. */
    private static final int LIB_SAVE=1,LIB_WORKING=2,LIB_NEW=3,LIB_FAILED=4;

    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private Metrics.Data data;private int shownVersion=-1;private boolean loading;private int restoreScroll;
    private Metrics.Period period=Metrics.Period.MONTH;private int week=Metrics.WEEKS-1;
    private SharedPreferences prefs;private LinearLayout content,periodBox,chips;
    private Bars minutesBars,usdBars;private TextView weekValue,weekTitle,weekUsd,weekUsdLabel;
    private ValueAnimator counter;
    /** Fondo de referencia de los gráficos: el vidrio de las tarjetas sobre el degradado suave (para mezclar los pasos del verde). */
    private int ground;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("metrics",MODE_PRIVATE);
        try{period=Metrics.Period.valueOf(prefs.getString("period",period.name()));}catch(RuntimeException ignored){}
        if(state!=null){week=Math.max(0,Math.min(Metrics.WEEKS-1,state.getInt("week",week)));restoreScroll=state.getInt("screen_scroll",0);}
        ground=blend(p.softMid,0xFFFFFFFF,(p.glass>>>24)/255f);
        String from=getIntent().getStringExtra("from");shell(from==null||from.isEmpty()?"Ajustes":from,-1);
        TextView title=largeTitle(page,"Tus métricas","Calculadas en tu teléfono con tus grabaciones. Nada se envía.");ui.highlightLast(title,"Tus métricas");
        content=ui.column();page.addView(content,Ui.fill());
        content.addView(loadingView(),Ui.fill());
    }
    @Override protected void onResume(){super.onResume();load();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("week",week);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(counter!=null)counter.cancel();disk.shutdownNow();super.onDestroy();}

    /**
     * Calcula en el hilo de disco. Solo si cambió algo desde lo que se muestra (FilesStore.version sube con cada
     * escritura). Mientras recalcula, lo anterior queda a media opacidad: no hay pantallazo en blanco ni saltos.
     */
    private void load(){
        int version=FilesStore.version.get();if(loading||(data!=null&&version==shownVersion))return;
        loading=true;Context app=getApplicationContext();long t0=SystemClock.elapsedRealtime();
        if(data!=null)content.animate().alpha(0.6f).setDuration(MOTION_FAST).start();
        try{
            disk.execute(()->{
                Metrics.Data d=null;
                try{d=Metrics.compute(app,true);}catch(RuntimeException e){Diagnostics.event("metrics_failed",null,"screen","MetricsActivity","error_class",e.getClass().getSimpleName());}
                // Solo cuántas grabaciones y cuánto tardó: sirve para ver el rendimiento en el informe, sin datos personales.
                if(d!=null)Diagnostics.event("metrics_shown",null,"screen","MetricsActivity","count",d.total,"elapsed_ms",SystemClock.elapsedRealtime()-t0);
                Metrics.Data result=d;
                runOnUiThread(()->{
                    loading=false;if(isDestroyed())return;content.animate().cancel();content.setAlpha(1f);
                    if(result==null){if(data==null){content.removeAllViews();content.addView(errorView(),Ui.fill());}return;}
                    boolean first=data==null;data=result;shownVersion=version;render(first);
                });
            });
        }catch(RuntimeException e){loading=false;}
    }

    // ---------- Armado ----------
    private void render(boolean first){
        boolean motion=first&&AppTheme.motion(this);
        content.removeAllViews();if(counter!=null)counter.cancel();
        if(data.total==0){content.addView(emptyView(motion),Ui.fill());return;}
        content.addView(hero(motion),Ui.fill());
        content.addView(weeks(motion),ui.top(S3));
        // Una sola fila de filtros, arriba de todo lo que filtra (guía dataviz): lo de arriba es de siempre.
        TextView h=ui.section("Por período");h.setPadding(ui.dp(S1),ui.dp(S8),ui.dp(S1),ui.dp(S2));content.addView(h);
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);strip.setClipToPadding(false);
        chips=ui.row();strip.addView(chips);content.addView(strip,Ui.fill());
        periodBox=ui.column();content.addView(periodBox,Ui.fill());renderPeriod(motion);
        content.addView(footer(),Ui.fill());
        if(first&&restoreScroll>0){int y=restoreScroll;restoreScroll=0;scroll.post(()->scroll.scrollTo(0,y));}
    }
    /** Rehace los filtros y las tarjetas del período elegido (lo de arriba no cambia). */
    private void renderPeriod(boolean motion){
        chips.removeAllViews();
        for(Metrics.Period pe:Metrics.Period.values()){
            TextView chip=ui.filter(chipName(pe),pe==period,v->{if(period==pe)return;period=pe;try{prefs.edit().putString("period",pe.name()).apply();}catch(RuntimeException ignored){}Ui.haptic(v);renderPeriod(AppTheme.motion(this));});
            LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginEnd(ui.dp(S2));chips.addView(chip,lp);
        }
        periodBox.removeAllViews();Metrics.Totals t=data.of(period);
        if(t.recordings==0){periodBox.addView(quietPeriod(),ui.top(S3));}
        else{periodBox.addView(voice(t),ui.top(S3));periodBox.addView(funnel(t,motion),ui.top(S3));}
        periodBox.addView(costs(t),ui.top(S3));
        if(t.recordings>0){periodBox.addView(heat(t,motion),ui.top(S3));periodBox.addView(people(t,motion),ui.top(S3));}
        if(motion)ui.fadeIn(periodBox);
    }

    // ---------- 1. Tarjeta principal ----------
    /**
     * Lo que más motiva, en grande: las palabras que Verbapp escribió por ti (o las horas grabadas, si aún no transcribes)
     * y el tiempo que te ahorraste de teclear. La racha va en una píldora menta arriba a la derecha. Abajo, tres datos.
     */
    private View hero(boolean motion){
        Metrics.Totals all=data.all;boolean words=all.words>0;
        LinearLayout card=card(R.drawable.ic_sparkle,"Tu voz en Verbapp",null);
        if(data.streak>0){LinearLayout head=(LinearLayout)card.getChildAt(0);head.addView(streakPill());}
        String figure=words?Metrics.number(all.words):Metrics.hours(all.audioMs);
        TextView big=Ui.tabular(ui.text(figure,Type.DISPLAY_MEDIUM,p.onSurface));big.setMaxLines(1);big.setIncludeFontPadding(false);
        // La cifra cabe siempre en una línea (letra grande del sistema, millones de palabras): se achica sola hasta 24 sp.
        big.setAutoSizeTextTypeUniformWithConfiguration(24,Type.DISPLAY_MEDIUM.size,1,TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,ui.dp(60));bp.topMargin=ui.dp(S2);card.addView(big,bp);
        card.addView(ui.text(words?(all.words==1?"palabra transcrita":"palabras transcritas"):"grabadas, todavía sin transcribir",Type.TITLE_MEDIUM,p.onSurfaceVariant));
        String saved=words?"≈ "+span(all.savedMs())+" que no tuviste que escribir a mano":"Transcribe tus audios y aquí verás cuántas palabras llevas.";
        card.addView(line(words?R.drawable.ic_hourglass:R.drawable.ic_info,saved,Type.BODY_MEDIUM,p.onSurface),ui.top(S4));
        if(data.streak>0&&!data.today)card.addView(line(R.drawable.metrics_flame,"Graba hoy para seguir tu racha de "+Metrics.count(data.streak,"día","días")+".",Type.BODY_MEDIUM,p.onSurfaceVariant),ui.top(S2));
        LinearLayout stats=ui.row();stats.setGravity(Gravity.TOP);
        addStat(stats,R.drawable.ic_waveform,Metrics.number(data.total),data.total==1?"Grabación":"Grabaciones");
        if(words)addStat(stats,R.drawable.ic_clock,Metrics.hours(all.audioMs),"Grabado");else addStat(stats,R.drawable.ic_calendar,Metrics.number(all.days.size()),all.days.size()==1?"Día grabando":"Días grabando");
        addStat(stats,R.drawable.metrics_flame,Metrics.count(data.best,"día","días"),"Mejor racha");
        card.addView(stats,ui.top(S5));
        // TalkBack: la tarjeta se lee de una vez, en una frase.
        StringBuilder say=new StringBuilder(words?Metrics.number(all.words)+" palabras transcritas. "+saved+". ":figure+" grabadas. ");
        say.append(Metrics.count(data.total,"grabación","grabaciones")).append(". ");
        if(data.streak>0)say.append("Racha actual: ").append(Metrics.count(data.streak,"día","días")).append(data.today?". ":", graba hoy para seguirla. ");
        say.append("Mejor racha: ").append(Metrics.count(data.best,"día","días")).append('.');
        hideChildren(card,say.toString());
        if(motion&&words)countUp(big,all.words);
        return card;
    }
    /** Las cifras suben desde 0 (900 ms). El tamaño de la letra se fija primero con la cifra final: así no salta. */
    private void countUp(TextView big,long target){
        big.setAlpha(0f);
        // Tras la primera medición (ya con el tamaño que eligió el ajuste automático para la cifra final).
        big.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){@Override public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){
            big.removeOnLayoutChangeListener(this);
            big.post(()->{
                float px=big.getTextSize();big.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);big.setTextSize(TypedValue.COMPLEX_UNIT_PX,px);
                big.setText("0");big.setAlpha(1f);
                counter=ValueAnimator.ofFloat(0f,1f);counter.setDuration(900);counter.setInterpolator(EMPHASIZED_DECELERATE);
                counter.addUpdateListener(a->big.setText(Metrics.number(Math.round(target*(double)(float)a.getAnimatedValue()))));
                counter.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationCancel(android.animation.Animator a){big.setText(Metrics.number(target));big.setAlpha(1f);}});
                counter.start();
            });
        }});
    }
    private TextView streakPill(){
        String text=data.streak==1?(data.today?"Grabaste hoy":"1 día seguido"):data.streak+" días seguidos";
        TextView t=ui.chip(text,p.onPrimaryContainer,p.primaryContainer);AppTheme.type(t,Type.LABEL_MEDIUM);Ui.tabular(t);
        Drawable d=getDrawable(R.drawable.metrics_flame);
        if(d!=null){d=d.mutate();d.setTint(p.onPrimaryContainer);d.setBounds(0,0,ui.dp(16),ui.dp(16));t.setCompoundDrawablesRelative(d,null,null,null);t.setCompoundDrawablePadding(ui.dp(6));}
        return t;
    }

    // ---------- 2. Últimas 8 semanas ----------
    /**
     * Dos gráficos chicos con el mismo eje de semanas: minutos grabados y gasto. Son medidas distintas, así que van en dos
     * gráficos (nunca dos escalas en uno). El gasto separa lo cobrado (verde) de lo estimado (un paso más claro, encima,
     * con 2 dp de aire) y lleva leyenda. Arriba, la lectura de la semana elegida (por defecto, esta).
     */
    private View weeks(boolean motion){
        LinearLayout card=card(R.drawable.ic_calendar,"Últimas 8 semanas","Toca una semana");
        LinearLayout read=ui.row();read.setGravity(Gravity.BOTTOM);
        LinearLayout left=ui.column();weekValue=Ui.tabular(ui.text("",Type.TITLE_LARGE,p.onSurface));weekTitle=ui.text("",Type.BODY_SMALL,p.onSurfaceVariant);left.addView(weekValue);left.addView(weekTitle);
        read.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout right=ui.column();right.setGravity(Gravity.END);weekUsd=Ui.tabular(ui.text("",Type.TITLE_MEDIUM,p.onSurface));weekUsd.setGravity(Gravity.END);
        weekUsdLabel=ui.text("",Type.BODY_SMALL,p.onSurfaceVariant);weekUsdLabel.setGravity(Gravity.END);right.addView(weekUsd);right.addView(weekUsdLabel);
        LinearLayout.LayoutParams rp=Ui.wrap();rp.setMarginStart(ui.dp(S3));read.addView(right,rp);
        read.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(read,Ui.fill());

        int n=Metrics.WEEKS;double[] minutes=new double[n],real=new double[n],est=new double[n];String[] mValues=new String[n],uValues=new String[n];
        double maxMin=0,maxUsd=0;boolean anyReal=false,anyEst=false;
        for(int i=0;i<n;i++){
            minutes[i]=data.weekMs[i]/60_000d;maxMin=Math.max(maxMin,minutes[i]);mValues[i]=data.weekMs[i]>0?Metrics.hours(data.weekMs[i]):"";
            real[i]=data.weekReal[i];est[i]=data.weekEst[i];maxUsd=Math.max(maxUsd,real[i]+est[i]);anyReal|=real[i]>0;anyEst|=est[i]>0;
            uValues[i]=real[i]+est[i]>0?(est[i]>0?"≈":"")+Pricing.usd(real[i]+est[i]):"";
        }
        String[][] labels=weekLabels();boolean money=maxUsd>0;
        card.addView(caption("Minutos grabados",null,null),ui.top(S4));
        double mMax=niceMinutes(maxMin);
        minutesBars=new Bars(this,p,88);minutesBars.set(minutes,null,labels[0],labels[1],mValues,mMax,maxMin>0?Metrics.hours(Math.round(mMax*60_000)):"",!money);
        minutesBars.colors(p.primary,p.primary,p.highlight);minutesBars.setContentDescription(describeMinutes(labels[2]));
        card.addView(minutesBars,Ui.fill());
        if(money){
            card.addView(caption("Gasto",anyReal?"Cobrado":null,anyEst?"Estimado":null),ui.top(S4));
            double uMax=niceUsd(maxUsd);
            usdBars=new Bars(this,p,64);usdBars.set(real,est,labels[0],labels[1],uValues,uMax,Pricing.usd(uMax),true);
            usdBars.colors(p.primary,estColor(),p.highlight);usdBars.setContentDescription(describeUsd(labels[2]));
            card.addView(usdBars,Ui.fill());
        }else{
            usdBars=null;TextView none=ui.text("Sin gastos en estas 8 semanas.",Type.BODY_SMALL,p.onSurfaceVariant);none.setPadding(ui.dp(S1),ui.dp(S2),0,0);card.addView(none);
        }
        Bars.Pick pick=i->{week=i;if(minutesBars!=null)minutesBars.select(i);if(usdBars!=null)usdBars.select(i);paintWeek();};
        minutesBars.onPick(pick);if(usdBars!=null)usdBars.onPick(pick);
        minutesBars.select(week);if(usdBars!=null)usdBars.select(week);paintWeek();
        if(motion){minutesBars.grow();if(usdBars!=null)usdBars.grow();}
        return card;
    }
    /** La lectura de la semana elegida: cuánto grabaste, cuántas grabaciones y cuánto gastaste. */
    private void paintWeek(){
        int i=week;long ms=data.weekMs[i];int count=data.weekCount[i];double real=data.weekReal[i],est=data.weekEst[i];
        weekValue.setText(ms>0?Ui.humanDuration(ms):"0 min");
        weekTitle.setText(weekName(i)+" · "+(count==0?"sin grabaciones":Metrics.count(count,"grabación","grabaciones")));
        weekUsd.setText(real+est>0?(est>0?"≈ ":"")+Pricing.usd(real+est):"US$0");
        weekUsdLabel.setText(real+est>0?(est>0&&real>0?"cobrado y estimado":est>0?"estimado":"cobrado"):"sin gasto");
    }
    private String weekName(int i){
        if(i==Metrics.WEEKS-1)return "Esta semana";if(i==Metrics.WEEKS-2)return "Semana pasada";
        return "Semana del "+new SimpleDateFormat("d 'de' MMMM",Metrics.CL).format(new Date(data.weekStarts[i]));
    }
    /** Rótulos de las semanas: [con mes cuando cambia, solo el día, completo para TalkBack]. */
    private String[][] weekLabels(){
        int n=Metrics.WEEKS;String[] full=new String[n],shortL=new String[n],spoken=new String[n];
        SimpleDateFormat dm=new SimpleDateFormat("d MMM",Metrics.CL),d=new SimpleDateFormat("d",Metrics.CL),long_=new SimpleDateFormat("d 'de' MMMM",Metrics.CL),m=new SimpleDateFormat("M",Locale.ROOT);
        for(int i=0;i<n;i++){
            Date at=new Date(data.weekStarts[i]);boolean month=i==0||!m.format(at).equals(m.format(new Date(data.weekStarts[i-1])));
            shortL[i]=d.format(at);full[i]=month?dm.format(at).replace(".",""):shortL[i];
            spoken[i]=i==n-1?"esta semana":i==n-2?"la semana pasada":"la semana del "+long_.format(at);
        }
        return new String[][]{full,shortL,spoken};
    }
    private String describeMinutes(String[] spoken){
        StringBuilder b=new StringBuilder("Gráfico de minutos grabados por semana. ");
        for(int i=0;i<spoken.length;i++)b.append(capital(spoken[i])).append(": ").append(data.weekMs[i]>0?Ui.humanDuration(data.weekMs[i]):"nada").append(". ");
        return b.append("Usa las flechas o toca una semana para elegirla.").toString();
    }
    private String describeUsd(String[] spoken){
        StringBuilder b=new StringBuilder("Gráfico de gasto por semana. ");
        for(int i=0;i<spoken.length;i++){double r=data.weekReal[i],e=data.weekEst[i];b.append(capital(spoken[i])).append(": ");
            if(r+e<=0)b.append("sin gasto");else{if(r>0)b.append(Pricing.usd(r)).append(" cobrado");if(r>0&&e>0)b.append(" y ");if(e>0)b.append("unos ").append(Pricing.usd(e)).append(" estimado");}
            b.append(". ");}
        return b.toString();
    }
    /** Rótulo chico de un gráfico, con la leyenda (cuadrito + texto) a la derecha si hay dos series. */
    private View caption(String title,String a,String b){
        LinearLayout r=ui.row();r.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S2));
        r.addView(ui.text(title,Type.LABEL_MEDIUM,p.onSurfaceVariant),new LinearLayout.LayoutParams(0,-2,1));
        if(a!=null&&b!=null){r.addView(swatch(p.primary,a));r.addView(ui.space(S3));r.addView(swatch(estColor(),b));}
        else if(b!=null)r.addView(ui.text("≈ estimado",Type.LABEL_MEDIUM,p.onSurfaceVariant));
        return r;
    }
    private View swatch(int color,String label){
        LinearLayout r=ui.row();View box=new View(this);box.setBackground(shape(this,color,2));r.addView(box,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));r.addView(ui.space(6));
        r.addView(ui.text(label,Type.LABEL_MEDIUM,p.onSurfaceVariant));return r;
    }
    /** Lo estimado: un paso más claro del mismo verde (lo cobrado es el verde lleno). */
    private int estColor(){return blend(p.primary,ground,0.5f);}

    // ---------- 3. Por período ----------
    private static String chipName(Metrics.Period pe){return pe==Metrics.Period.WEEK?"7 días":pe==Metrics.Period.MONTH?"Este mes":"Todo";}
    private String periodName(){return period==Metrics.Period.WEEK?"Últimos 7 días":period==Metrics.Period.MONTH?capital(new SimpleDateFormat("MMMM",Metrics.CL).format(new Date(data.now))):"Desde el comienzo";}
    /** Período sin grabaciones: una línea amable, en vez de tarjetas con ceros. */
    private View quietPeriod(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.heading(period==Metrics.Period.WEEK?"No grabaste en los últimos 7 días":"Aún no grabas este mes",Type.TITLE_MEDIUM));
        TextView t=ui.text("Toca «Todo» para ver tu historial, o graba algo y aquí aparece al tiro.",Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(0,ui.dp(S1),0,0);card.addView(t);
        return card;
    }

    /** Tu voz en números: grabado, palabras, ritmo y tiempo ahorrado del período, en cuatro datos de vidrio. */
    private View voice(Metrics.Totals t){
        LinearLayout card=card(R.drawable.ic_waveform,"Tu voz en números",periodName());
        int wpm=t.wpm();
        LinearLayout a=ui.row();a.setGravity(Gravity.TOP);addStat(a,R.drawable.ic_clock,Metrics.hours(t.audioMs),"Grabado");addStat(a,R.drawable.ic_doc,t.words>0?Metrics.number(t.words):"—","Palabras");card.addView(a,Ui.fill());
        LinearLayout b=ui.row();b.setGravity(Gravity.TOP);addStat(b,R.drawable.ic_speed,wpm>0?String.valueOf(wpm):"—","Palabras por minuto");addStat(b,R.drawable.ic_hourglass,t.words>0?"≈ "+Metrics.hours(t.savedMs()):"—","Ahorrado al no teclear");card.addView(b,ui.top(S2));
        StringBuilder more=new StringBuilder(Metrics.count(t.recordings,"grabación","grabaciones")).append(" · ").append(Metrics.count(t.days.size(),"día con grabaciones","días con grabaciones"));
        if(t.marks>0)more.append(" · ").append(Metrics.count(t.marks,"momento ★","momentos ★"));
        TextView m=ui.text(more.toString(),Type.BODY_SMALL,p.onSurfaceVariant);m.setPadding(ui.dp(S1),ui.dp(S3),ui.dp(S1),0);card.addView(m);
        if(t.transcribed<t.recordings&&t.words>0){TextView n=ui.text("Palabras y ritmo cuentan solo lo transcrito.",Type.BODY_SMALL,p.onSurfaceVariant);n.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),0);card.addView(n);}
        return card;
    }

    /**
     * El camino hacia tu segundo cerebro: grabadas → transcritas → con nota → en 0-Inbox, con el % de lo grabado. Las
     * barras usan cuatro pasos del verde (más oscuro = más cerca de la meta). Debajo, lo que quedó a medio camino, con
     * acceso directo: la Biblioteca con su filtro, o la grabación si es una sola.
     */
    private View funnel(Metrics.Totals t,boolean motion){
        LinearLayout card=card(R.drawable.ic_filter,"Hacia tu segundo cerebro",periodName());
        String[] names={"Grabadas","Transcritas","Con nota","En 0-Inbox"};int[] counts={t.recordings,t.transcribed,t.withNote,t.inInbox};int[] ramp=ramp();int track=blend(ground,p.primary,0.10f);
        for(int i=0;i<4;i++){
            LinearLayout step=ui.column();
            LinearLayout r=ui.row();r.addView(ui.text(names[i],Type.LABEL_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));
            int pct=(int)Math.round(100d*counts[i]/Math.max(1,t.recordings));
            r.addView(Ui.tabular(ui.text(Metrics.number(counts[i])+(i==0?"":" · "+pct+" %"),Type.LABEL_LARGE,p.onSurfaceVariant)));step.addView(r,Ui.fill());
            Meter meter=new Meter(this,ramp[i],track,(float)counts[i]/Math.max(1,t.recordings));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,ui.dp(10));mp.topMargin=ui.dp(6);step.addView(meter,mp);
            if(motion)meter.grow(i*60L);
            step.setContentDescription(names[i]+": "+Metrics.number(counts[i])+(i==0?"":" de "+Metrics.number(t.recordings)+", "+pct+" por ciento"));hideChildren(step,null);
            card.addView(step,i==0?Ui.fill():ui.top(S3));
        }
        // A medio camino: solo lo que existe. Cada fila lleva a esas grabaciones.
        LinearLayout stalls=ui.column();
        stall(stalls,R.drawable.ic_alert,"Con error","Revisa qué pasó y reintenta",t.failed,LIB_FAILED);
        stall(stalls,R.drawable.ic_hourglass,"En proceso","Transcribiéndose ahora",t.queued,LIB_WORKING);
        stall(stalls,R.drawable.ic_transcribe,"Sin transcribir","Solo audio, por ahora",t.pending,LIB_NEW);
        stall(stalls,R.drawable.ic_note,"Transcritas sin nota",t.noNote.size()>1?"Abre la más reciente":"Ábrela para armar la nota",t.noNote,-1);
        if(data.inboxOn)stall(stalls,R.drawable.ic_inbox,"Por guardar en 0-Inbox","Guárdalas con un toque",t.notSaved,LIB_SAVE);
        else if(t.transcribed>0){Ui.Row r=ui.listRow(R.drawable.ic_inbox,"Elige tu carpeta 0-Inbox","Para guardar cada nota con un toque",null);
            r.onClick(v->startActivity(new Intent(this,SettingsActivity.class).putExtra("inbox",true)));addRow(stalls,r);}
        if(stalls.getChildCount()>0){
            TextView h=ui.text("A medio camino",Type.LABEL_MEDIUM,p.onSurfaceVariant);h.setPadding(ui.dp(S1),ui.dp(S5),0,ui.dp(S1));card.addView(h);
            LinearLayout group=ui.group();group.addView(stalls,Ui.fill());card.addView(group,Ui.fill());
        }else{
            TextView ok=ui.chip("Todo lo grabado llegó a tu segundo cerebro",p.onPrimaryContainer,p.primaryContainer);ok.setSingleLine(false);ok.setMaxLines(3);
            Drawable d=getDrawable(R.drawable.ic_check);if(d!=null){d=d.mutate();d.setTint(p.onPrimaryContainer);d.setBounds(0,0,ui.dp(16),ui.dp(16));ok.setCompoundDrawablesRelative(d,null,null,null);ok.setCompoundDrawablePadding(ui.dp(6));}
            LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S4);card.addView(ok,lp);
        }
        return card;
    }
    private void stall(LinearLayout list,int icon,String title,String hint,List<String> ids,int filter){
        if(ids.isEmpty())return;
        Ui.Row r=ui.listRow(icon,title,hint,Metrics.number(ids.size()));Ui.tabular(r.value);r.value.setTextColor(p.onSurface);
        r.onClick(v->openStage(ids,filter));addRow(list,r);
    }
    private void addRow(LinearLayout list,View row){if(list.getChildCount()>0)list.addView(ui.separator(S4+24+S4));list.addView(row,Ui.fill());}
    /**
     * Una sola grabación: su detalle. Varias: la Biblioteca con el filtro que corresponde (extra «filter», que lee
     * MainActivity); sin filtro que sirva (p. ej. «sin nota»), la más reciente.
     */
    private void openStage(List<String> ids,int filter){
        if(ids.size()==1||filter<0){startActivity(new Intent(this,RecordingActivity.class).putExtra("id",ids.get(0)));return;}
        startActivity(new Intent(this,MainActivity.class).putExtra("library",true).putExtra("filter",filter).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    /** Cuatro pasos de un mismo verde, de claro a oscuro (en tema oscuro, de oscuro a claro): escala ordenada validada. */
    private int[] ramp(){float[] t={0.55f,0.40f,0.22f,0f};int[] out=new int[4];for(int i=0;i<4;i++)out[i]=blend(p.primary,ground,t[i]);return out;}

    /**
     * Costos del período: el total en grande (con «≈» si parte es estimada), cobrado frente a estimado, US$ por hora de
     * audio, el desglose por modelo y lo que costaron las notas. Si OpenRouter informó el saldo al comprobar la clave, va
     * al final. Lo cobrado y lo estimado siempre van rotulados.
     */
    private View costs(Metrics.Totals t){
        LinearLayout card=card(R.drawable.metrics_cash,"Costos",periodName());double usd=t.usd();
        TextView big=Ui.tabular(ui.text(usd>0?(t.estimated()>0?"≈ ":"")+Pricing.usd(usd):"US$0",Type.HEADLINE_LARGE,p.onSurface));big.setPadding(ui.dp(S1),0,0,0);card.addView(big);
        String when=period==Metrics.Period.WEEK?"en los últimos 7 días":period==Metrics.Period.MONTH?"este mes":"desde el comienzo";
        TextView cap=ui.text(usd>0?"gastado "+when+(t.notes>0?", contando las notas":""):"Sin gastos "+when+".",Type.BODY_MEDIUM,p.onSurfaceVariant);cap.setPadding(ui.dp(S1),0,0,0);card.addView(cap);
        if(usd>0){
            double hour=t.usdPerHour();
            LinearLayout row=ui.row();row.setGravity(Gravity.TOP);
            addStat(row,R.drawable.ic_check_circle,t.real()>0?Pricing.usd(t.real()):"—","Cobrado");
            addStat(row,R.drawable.ic_info,t.estimated()>0?"≈ "+Pricing.usd(t.estimated()):"—","Estimado");
            addStat(row,R.drawable.ic_clock,hour>0?(t.estUsd>0?"≈ ":"")+Pricing.usd(hour):"—","Por hora de audio");
            card.addView(row,ui.top(S4));
        }
        List<Metrics.ModelUse> models=t.models();
        if(!models.isEmpty()){
            card.addView(subhead("Por modelo"));LinearLayout list=ui.group();
            for(Metrics.ModelUse u:models){
                String value=u.usd()>0?(u.estUsd>0?"≈ ":"")+Pricing.usd(u.usd()):u.unknown>=u.count?"Sin tarifa":"US$0";
                String sub=Metrics.service(u.provider)+" · "+Ui.humanDuration(u.audioMs)+" · "+Metrics.count(u.count,"transcripción","transcripciones");
                addRow(list,costRow(u.name,sub,value));
            }
            card.addView(list,Ui.fill());
        }
        if(t.notes>0){
            card.addView(subhead("Notas para tu segundo cerebro"));LinearLayout list=ui.group();
            double n=t.noteReal+t.noteEst;String value=n>0?(t.noteEst>0?"≈ ":"")+Pricing.usd(n):"—";
            String sub=t.noteIn+t.noteOut>0?Metrics.number(t.noteIn)+" tokens de entrada · "+Metrics.number(t.noteOut)+" de salida":"Sin datos de tokens";
            addRow(list,costRow(Metrics.count(t.notes,"nota","notas"),sub,value));card.addView(list,Ui.fill());
        }
        StringBuilder foot=new StringBuilder();
        if(t.unknown>0)foot.append(Metrics.count(t.unknown,"transcripción","transcripciones")).append(" con tu servidor: sin tarifa conocida.\n");
        if(!Double.isNaN(data.balance))foot.append("Saldo en OpenRouter: ").append(Pricing.usd(data.balance)).append(" (al comprobar tu clave el ").append(new SimpleDateFormat("d MMM",Metrics.CL).format(new Date(data.balanceAt)).replace(".","")).append(").\n");
        foot.append("Cobrado: lo que informó OpenRouter. ≈ Estimado: duración × tarifa pública del modelo; el cobro final lo ves en tu cuenta.");
        TextView f=ui.text(foot.toString(),Type.BODY_SMALL,p.onSurfaceVariant);f.setPadding(ui.dp(S1),ui.dp(S4),ui.dp(S1),0);card.addView(f);
        return card;
    }
    /** Fila de costo: nombre y detalle a la izquierda, monto (cifras fijas) a la derecha. No se toca: nada se registra. */
    private View costRow(String title,String sub,String value){
        LinearLayout r=ui.row();r.setPadding(ui.dp(S4),ui.dp(S3),ui.dp(S4),ui.dp(S3));r.setMinimumHeight(ui.dp(56));
        LinearLayout texts=ui.column();texts.addView(ui.text(title,Type.ITEM,p.onSurface));TextView s=ui.text(sub,Type.BODY_SMALL,p.onSurfaceVariant);s.setPadding(0,ui.dp(2),0,0);texts.addView(s);
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        TextView v=Ui.tabular(ui.text(value,Type.LABEL_LARGE,p.onSurface));v.setPadding(ui.dp(S3),0,0,0);r.addView(v);
        r.setContentDescription(title+", "+sub+": "+value);hideChildren(r,null);return r;
    }

    /**
     * Cuándo grabas: mapa de 7 días × 24 horas con los minutos grabados (según la hora en que empezaste). Cuatro pasos del
     * verde por cuartos del máximo, casillas vacías en gris tenue. Tocar una casilla dice su detalle debajo; si no, una
     * frase con el momento en que más hablas.
     */
    private View heat(Metrics.Totals t,boolean motion){
        LinearLayout card=card(R.drawable.ic_clock,"Cuándo grabas",periodName());
        TextView read=ui.text(insight(t),Type.BODY_MEDIUM,p.onSurface);read.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S3));read.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(read);
        int[] ramp=ramp();int empty=blend(ground,p.outlineVariant,p.dark?0.7f:0.55f);
        Heat map=new Heat(this,p,t.heatMs,ramp,empty);
        map.onPick((day,hour)->{long ms=t.heatMs[day][hour];int n=t.heatCount[day][hour];
            read.setText(DAY_NAMES[day]+" de "+clock(hour)+" a "+clock(hour+1)+" · "+(n==0?"sin grabaciones":Ui.humanDuration(ms)+" en "+Metrics.count(n,"grabación","grabaciones")));});
        map.setContentDescription(describeHeat(t));card.addView(map,Ui.fill());if(motion)map.grow();
        // Leyenda de la escala: Menos ▢▢▢▢▢ Más
        LinearLayout legend=ui.row();legend.setPadding(ui.dp(S1),ui.dp(S3),0,0);legend.addView(ui.text("Menos",Type.LABEL_SMALL,p.onSurfaceVariant));legend.addView(ui.space(6));
        int[] all={empty,ramp[0],ramp[1],ramp[2],ramp[3]};
        for(int c:all){View box=new View(this);box.setBackground(shape(this,c,3));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12));lp.setMarginEnd(ui.dp(3));legend.addView(box,lp);}
        legend.addView(ui.space(3));legend.addView(ui.text("Más",Type.LABEL_SMALL,p.onSurfaceVariant));legend.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.addView(legend);
        return card;
    }
    /** «Hablas más los martes, sobre todo entre 10:00 y 11:00.» */
    private String insight(Metrics.Totals t){
        long[] perDay=new long[7];int bd=0,bh=0;long best=-1;
        for(int d=0;d<7;d++)for(int h=0;h<24;h++){perDay[d]+=t.heatMs[d][h];if(t.heatMs[d][h]>best){best=t.heatMs[d][h];bd=d;bh=h;}}
        int top=0;for(int d=1;d<7;d++)if(perDay[d]>perDay[top])top=d;
        if(perDay[top]<=0)return "Toca una casilla para ver su detalle.";
        return "Hablas más "+DAY_PLURAL[top]+(bd==top?", sobre todo entre "+clock(bh)+" y "+clock(bh+1):"; tu hora con más voz: "+DAY_PLURAL[bd]+" a las "+clock(bh))+".";
    }
    private String describeHeat(Metrics.Totals t){
        StringBuilder b=new StringBuilder("Mapa de días y horas en que grabas. ");
        for(int d=0;d<7;d++){long sum=0;int topH=-1;long topMs=0;for(int h=0;h<24;h++){sum+=t.heatMs[d][h];if(t.heatMs[d][h]>topMs){topMs=t.heatMs[d][h];topH=h;}}
            b.append(DAY_NAMES[d]).append(": ").append(sum>0?Ui.humanDuration(sum)+", más a las "+clock(topH):"nada").append(". ");}
        return b.append("Toca o usa las flechas para recorrer las horas.").toString();
    }
    private static String clock(int hour){return String.format(Locale.ROOT,"%02d:00",hour%24==0&&hour>0?24:hour);}

    /**
     * Con quién más conversas: las voces con nombre de tus transcripciones (sin contarte a ti), por tiempo hablado. Los
     * nombres solo se muestran aquí: estas filas no se tocan y nada de esto se registra.
     */
    private View people(Metrics.Totals t,boolean motion){
        LinearLayout card=card(R.drawable.ic_people,"Con quién más conversas",periodName());List<Metrics.Person> list=t.people();
        if(list.isEmpty()){
            TextView hint=ui.text("Ponle nombre a las voces de tus transcripciones y aquí verás con quién más conversas.",Type.BODY_MEDIUM,p.onSurfaceVariant);hint.setPadding(ui.dp(S1),0,ui.dp(S1),0);card.addView(hint);
        }else{
            long max=Math.max(1,list.get(0).ms);int track=blend(ground,p.primary,0.10f);
            for(int i=0;i<Math.min(5,list.size());i++){
                Metrics.Person person=list.get(i);LinearLayout item=ui.column();
                LinearLayout r=ui.row();TextView name=ui.oneLine(ui.text(person.name,Type.ITEM,p.onSurface));r.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                String value=(person.ms>=60_000?Ui.humanDuration(person.ms):"< 1 min")+" · "+Metrics.count(person.recordings,"audio","audios");
                TextView v=Ui.tabular(ui.text(value,Type.LABEL_MEDIUM,p.onSurfaceVariant));v.setPadding(ui.dp(S2),0,0,0);r.addView(v);item.addView(r,Ui.fill());
                Meter m=new Meter(this,p.primary,track,(float)person.ms/max);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,ui.dp(8));mp.topMargin=ui.dp(6);item.addView(m,mp);if(motion)m.grow(i*60L);
                item.setContentDescription(person.name+": "+value);hideChildren(item,null);
                card.addView(item,i==0?Ui.fill():ui.top(S3));
            }
        }
        String voices=data.knownVoices>0?Metrics.count(data.knownVoices,"voz conocida guardada","voces conocidas guardadas")+". ":"";
        TextView f=ui.text(voices+"Según cuánto habla cada persona con nombre. Los nombres solo se ven aquí.",Type.BODY_SMALL,p.onSurfaceVariant);f.setPadding(ui.dp(S1),ui.dp(S4),ui.dp(S1),0);card.addView(f);
        return card;
    }

    // ---------- Pie, vacío, carga y error ----------
    private View footer(){
        LinearLayout box=ui.column();box.setPadding(0,ui.dp(S5),0,0);
        box.addView(ui.footnote("Todo se calcula en este teléfono con lo que Verbapp guarda de cada grabación. Nada de esto se envía."));
        Ui.Btn b=ui.button("¿Cómo se calcula?",0,Ui.Style.PLAIN,v->message("Cómo se calculan tus métricas",HOW));b.setMinimumHeight(ui.dp(48));
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginStart(ui.dp(S1));box.addView(b,lp);return box;
    }
    private static final String HOW=
        "Todo se calcula en este teléfono con lo que Verbapp ya guarda de cada grabación. Nada se envía.\n\n"
        +"Grabado: la duración de tus grabaciones, en el día en que grabaste.\n"
        +"Palabras: las de tus transcripciones (la versión vigente).\n"
        +"Palabras por minuto: palabras ÷ minutos de audio transcrito.\n"
        +"Ahorrado al no teclear: lo que tardarías en escribir esas palabras a mano, a "+Metrics.TYPING_WPM+" palabras por minuto.\n"
        +"Racha: días seguidos con al menos una grabación. Si hoy aún no grabas, sigue viva hasta medianoche.\n\n"
        +"Hacia tu segundo cerebro: de lo grabado en el período, cuánto se transcribió, cuánto tiene nota y cuánto guardaste en tu carpeta 0-Inbox.\n\n"
        +"Costos: van en el día en que se transcribió o se armó la nota. «Cobrado» es lo que informó OpenRouter. «≈ Estimado» es la duración por la tarifa pública del modelo y puede diferir de lo que te cobran. Si volviste a transcribir, mientras guardes la versión anterior también se cuenta lo que costó.\n\n"
        +"Personas: el tiempo que habla cada voz a la que le pusiste nombre, sin contarte a ti. Los nombres solo se ven en esta pantalla.";

    /** Sin grabaciones: qué verá aquí y un solo botón para empezar. Unas barras tenues anticipan el gráfico. */
    private View emptyView(boolean motion){
        LinearLayout card=ui.card();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(ui.dp(S6),ui.dp(S6),ui.dp(S6),ui.dp(S6));
        Bars ghost=new Bars(this,p,72);ghost.set(new double[]{3,5,2,6,4,7,5,8},null,null,null,null,8,"",false);int faint=blend(ground,p.primary,0.28f);ghost.colors(faint,faint,0);
        ghost.setFocusable(false);ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(ghost,new LinearLayout.LayoutParams(ui.dp(220),-2));if(motion)ghost.grow();
        TextView title=ui.heading("Aquí verás tu voz en números",Type.HEADLINE_SMALL);title.setGravity(Gravity.CENTER);title.setPadding(0,ui.dp(S5),0,0);card.addView(title,Ui.fill());ui.highlightLast(title,"Aquí verás tu voz en números");
        TextView body=ui.text("Cuando grabes, verás cuánto hablas, cuántas palabras escribe Verbapp por ti, cuánto llega a tu segundo cerebro y cuánto gastas al transcribir.",Type.BODY_MEDIUM,p.onSurfaceVariant);
        body.setGravity(Gravity.CENTER);body.setPadding(0,ui.dp(S2),0,ui.dp(S5));card.addView(body,Ui.fill());
        card.addView(line(R.drawable.ic_doc,"Palabras transcritas y tiempo ahorrado",Type.BODY_MEDIUM,p.onSurface),Ui.fill());
        card.addView(line(R.drawable.metrics_flame,"Tu racha de días grabando",Type.BODY_MEDIUM,p.onSurface),ui.top(S2));
        card.addView(line(R.drawable.metrics_cash,"Tu gasto, mes a mes y por modelo",Type.BODY_MEDIUM,p.onSurface),ui.top(S2));
        Ui.Btn go=ui.button("Hacer mi primera grabación",R.drawable.ic_mic,Ui.Style.PRIMARY,v->{navigate(0);finish();});
        LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S6);card.addView(go,lp);
        if(motion)ui.fadeIn(card);
        return card;
    }
    private View loadingView(){
        LinearLayout box=ui.row();box.setGravity(Gravity.CENTER);box.setPadding(0,ui.dp(S10),0,ui.dp(S10));
        ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));box.addView(spin,new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));
        box.addView(ui.space(S3));box.addView(ui.text("Contando tus palabras…",Type.BODY_MEDIUM,p.onSurfaceVariant));
        box.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);return box;
    }
    private View errorView(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.heading("No se pudieron calcular tus métricas",Type.TITLE_MEDIUM));
        TextView t=ui.text("Tus grabaciones están bien. Vuelve a intentarlo en un momento.",Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(0,ui.dp(S1),0,ui.dp(S4));card.addView(t);
        card.addView(ui.button("Reintentar",R.drawable.ic_refresh,Ui.Style.SECONDARY,v->{content.removeAllViews();content.addView(loadingView(),Ui.fill());load();}),Ui.wrap());
        return card;
    }

    // ---------- Piezas ----------
    /** Tarjeta de vidrio con cabecera como «Tu semana»: ícono verde chico, título y un rótulo gris a la derecha. */
    private LinearLayout card(int icon,String title,String right){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S4),ui.dp(S4),ui.dp(S4),ui.dp(S4));
        LinearLayout head=ui.row();head.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S3));
        head.addView(ui.icon(icon,p.primary,16));head.addView(ui.space(S2));
        head.addView(ui.heading(title,Type.TITLE_SMALL),new LinearLayout.LayoutParams(0,-2,1));
        if(right!=null){TextView r=ui.text(right,Type.LABEL_MEDIUM,p.onSurfaceVariant);r.setPadding(ui.dp(S2),0,0,0);head.addView(r);}
        card.addView(head,Ui.fill());return card;
    }
    private TextView subhead(String text){TextView h=ui.text(text,Type.LABEL_MEDIUM,p.onSurfaceVariant);h.setPadding(ui.dp(S1),ui.dp(S5),0,ui.dp(S2));return h;}
    /** Ícono chico + texto, en una fila (frases de apoyo de la tarjeta principal y del estado vacío). */
    private View line(int icon,String text,Type type,int color){
        // El ícono (18 dp, verde) va alineado con la primera línea del texto, aunque la frase ocupe dos.
        LinearLayout r=ui.row();r.setGravity(Gravity.TOP);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(18),ui.dp(18));lp.topMargin=ui.dp(1);lp.setMarginEnd(ui.dp(S2));
        r.addView(ui.icon(icon,p.primary,18),lp);r.addView(ui.text(text,type,color),new LinearLayout.LayoutParams(0,-2,1));return r;
    }
    /**
     * Un dato de vidrio del kit (ui.stat, el de «Tu semana») a lo ancho que le toque. El rótulo puede ir en dos líneas y
     * la cifra se achica si no cabe (letra grande del sistema), en vez de cortarse con «…».
     */
    private void addStat(LinearLayout row,int icon,String value,String label){
        LinearLayout s=ui.stat(icon,value,label);
        for(int k=0;k<s.getChildCount();k++)if(s.getChildAt(k) instanceof TextView){TextView t=(TextView)s.getChildAt(k);if(k==s.getChildCount()-1){t.setSingleLine(false);t.setMaxLines(2);t.setEllipsize(null);}else shrinkToFit(t,12);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(row.getChildCount()>0)lp.setMarginStart(ui.dp(S2));row.addView(s,lp);
    }
    /** Achica un texto de una línea hasta que quepa entero, sin bajar de minSp (igual que «Tu semana» en Grabar). */
    private void shrinkToFit(TextView t,float minSp){
        float min=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,minSp,getResources().getDisplayMetrics());
        t.addOnLayoutChangeListener((v,l,tp,r,b,ol,ot,or,ob)->{
            float avail=t.getWidth()-t.getTotalPaddingLeft()-t.getTotalPaddingRight(),need=t.getPaint().measureText(t.getText().toString()),size=t.getTextSize();
            if(avail>0&&need>avail&&size>min){float next=Math.max(min,size*avail/need*0.98f);t.post(()->t.setTextSize(TypedValue.COMPLEX_UNIT_PX,next));}
        });
    }
    /** El lector de pantalla lee el contenedor de una vez (su descripción) en vez de cada pedazo. */
    private static void hideChildren(LinearLayout box,String say){
        if(say!=null)box.setContentDescription(say);box.setFocusable(true);box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for(int k=0;k<box.getChildCount();k++)box.getChildAt(k).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    /** Duración larga legible: «53 h» desde 10 h; si no, «1 h 04 min». */
    private static String span(long ms){return ms>=36_000_000L?Metrics.hours(ms):Ui.humanDuration(ms);}
    private static String capital(String s){return s==null||s.isEmpty()?"":s.substring(0,1).toUpperCase(Metrics.CL)+s.substring(1);}
    /** Tope «redondo» del eje de minutos (una línea guía): 5, 10, 15, 30 min, 1 h, 1,5 h, 2 h… */
    static double niceMinutes(double v){
        double[] steps={5,10,15,20,30,45,60,90,120,180,240,300,360,480,600,720,900,1200,1500,1800,2400,3000,3600};
        for(double s:steps)if(v<=s)return s;return Math.ceil(v/600)*600;
    }
    /** Tope «redondo» del eje de US$: 1, 2 o 5 por una potencia de 10. */
    static double niceUsd(double v){
        if(v<=0)return 1;double e=Math.pow(10,Math.floor(Math.log10(v))),f=v/e;
        return (f<=1?1:f<=2?2:f<=5?5:10)*e;
    }

    // ---------- Gráficos propios (Canvas) ----------
    static float sp(View v,float n){return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,n,v.getResources().getDisplayMetrics());}

    /**
     * Barras verticales por semana (una serie, o dos apiladas: lo cobrado abajo y lo estimado encima, con 2 dp de aire).
     * Barras de hasta 24 dp, punta redondeada de 4 dp y base recta sobre una línea base de 1 px; una sola línea guía en el
     * tope redondo, con su valor. La semana elegida lleva un fondo menta suave y su valor encima (rótulo selectivo).
     * Un valor chico pero no nulo se dibuja con al menos 3 dp, para que no parezca vacío; un cero, con una marca gris.
     */
    static final class Bars extends View {
        interface Pick{void pick(int index);}
        private final AppTheme.Palette p;private final float plot;
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),label=new Paint(Paint.ANTI_ALIAS_FLAG),value=new Paint(Paint.ANTI_ALIAS_FLAG),rule=new Paint();
        private final RectF rect=new RectF();private final Path path=new Path();private final float[] radii=new float[8];
        private double[] solid=new double[0],light;private String[] labels,shortLabels,values;private String grid="";
        private double max=1;private int selected=-1,solidColor,lightColor,band;private boolean axis;private float grow=1f;private Pick pick;private ValueAnimator anim;
        Bars(Context c,AppTheme.Palette p,float plotDp){
            super(c);this.p=p;plot=AppTheme.dp(c,plotDp);setFocusable(true);
            label.setTypeface(AppTheme.outfit(c,Weight.MEDIUM));label.setTextSize(sp(this,11));label.setTextAlign(Paint.Align.CENTER);label.setFontFeatureSettings("tnum");
            value.setTypeface(AppTheme.outfit(c,Weight.SEMIBOLD));value.setTextSize(sp(this,12));value.setTextAlign(Paint.Align.CENTER);value.setFontFeatureSettings("tnum");value.setColor(p.onSurface);
            // Líneas guía de un pelo (como los divisores del kit): recesivas, nunca punteadas.
            rule.setColor(p.outlineVariant);rule.setStrokeWidth(Math.max(1f,AppTheme.dp(c,0.5f)));
            solidColor=lightColor=p.primary;band=p.highlight;
        }
        void set(double[] solid,double[] light,String[] labels,String[] shortLabels,String[] values,double max,String grid,boolean axis){
            this.solid=solid;this.light=light;this.labels=labels;this.shortLabels=shortLabels;this.values=values;this.max=max>0?max:1;this.grid=grid==null?"":grid;this.axis=axis&&labels!=null;requestLayout();invalidate();
        }
        void colors(int solid,int light,int band){solidColor=solid;lightColor=light;this.band=band;invalidate();}
        void onPick(Pick pick){this.pick=pick;}
        void select(int i){if(i!=selected){selected=i;invalidate();}}
        /** Las barras crecen desde la base (500 ms). Sin animaciones del sistema, quedan como están. */
        void grow(){if(!AppTheme.motion(getContext()))return;if(anim!=null)anim.cancel();grow=0f;anim=ValueAnimator.ofFloat(0f,1f);anim.setDuration(500);anim.setStartDelay(80);anim.setInterpolator(EMPHASIZED_DECELERATE);anim.addUpdateListener(a->{grow=(float)a.getAnimatedValue();invalidate();});anim.start();}
        @Override protected void onDetachedFromWindow(){if(anim!=null){anim.cancel();grow=1f;}super.onDetachedFromWindow();}
        private float top(){return -value.ascent()+value.descent()+AppTheme.dp(getContext(),6);}
        private float axisHeight(){return axis?-label.ascent()+label.descent()+AppTheme.dp(getContext(),10):AppTheme.dp(getContext(),4);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(),w),Math.round(top()+plot+axisHeight()));}
        private int index(float x){int n=solid.length;return Math.max(0,Math.min(n-1,(int)(x/(getWidth()/(float)n))));}
        @Override protected void onDraw(Canvas canvas){
            int n=solid.length;if(n==0)return;float w=getWidth(),d=AppTheme.dp(getContext(),1),slot=w/n,ceil=top(),base=ceil+plot;
            // Fondo de la semana elegida (como el «resaltar» de un tooltip): menta suave de borde a borde del espacio.
            if(selected>=0&&band!=0){fill.setColor(band);rect.set(slot*selected+2*d,0,slot*(selected+1)-2*d,getHeight());canvas.drawRoundRect(rect,12*d,12*d,fill);}
            if(!grid.isEmpty())canvas.drawLine(0,ceil,w,ceil,rule);
            canvas.drawLine(0,base,w,base,rule);
            float bw=Math.min(24*d,slot*0.56f),valueTop=Float.MAX_VALUE,valueX=0;
            for(int i=0;i<n;i++){
                float cx=slot*(i+0.5f),l=cx-bw/2f,r=cx+bw/2f;double a=Math.max(0,solid[i]),b=light==null?0:Math.max(0,light[i]);
                float ha=a>0?Math.max(3*d,(float)(a/max*plot)):0,hb=b>0?Math.max(3*d,(float)(b/max*plot)):0;ha*=grow;hb*=grow;
                if(a+b<=0){fill.setColor(p.outlineVariant);rect.set(cx-3*d,base-2*d,cx+3*d,base);canvas.drawRoundRect(rect,d,d,fill);if(i==selected){valueTop=base-2*d;valueX=cx;}continue;}
                float y=base;
                if(ha>0){bar(canvas,l,y-ha,r,y,hb<=0,solidColor);y-=ha;}
                if(hb>0){if(ha>0)y-=2*d;bar(canvas,l,y-hb,r,y,true,lightColor);y-=hb;}
                if(i==selected){valueTop=y;valueX=cx;}
            }
            // Valor de la semana elegida sobre su barra (dentro del ancho del gráfico).
            if(selected>=0&&values!=null&&selected<values.length&&values[selected]!=null&&!values[selected].isEmpty()&&grow>=1f){
                String v=values[selected];float tw=value.measureText(v);float x=Math.max(tw/2f,Math.min(w-tw/2f,valueX)),y=Math.max(-value.ascent(),valueTop-4*d);
                canvas.drawText(v,x,y,value);
                // La línea guía lleva su valor arriba a la izquierda, salvo que se tope con el del elegido (los dos rectángulos).
                if(!grid.isEmpty()){float gw=label.measureText(grid),gb=ceil-4*d,gt=gb+label.ascent();
                    boolean hit=x-tw/2f<gw+6*d&&y+value.descent()>gt-2*d&&y+value.ascent()<gb+label.descent()+2*d;if(!hit)gridText(canvas,ceil);}
            }else if(!grid.isEmpty())gridText(canvas,ceil);
            if(axis){
                float y=getHeight()-label.descent()-2*d;
                for(int i=0;i<n;i++){
                    String t=labels[i];if(label.measureText(t)>slot-4*d&&shortLabels!=null)t=shortLabels[i];
                    label.setColor(i==selected?p.onSurface:p.onSurfaceVariant);canvas.drawText(t,slot*(i+0.5f),y,label);
                }
            }
        }
        private void gridText(Canvas canvas,float ceil){label.setColor(p.onSurfaceVariant);label.setTextAlign(Paint.Align.LEFT);canvas.drawText(grid,0,ceil-AppTheme.dp(getContext(),4),label);label.setTextAlign(Paint.Align.CENTER);}
        /** Barra con la punta redondeada (4 dp, o menos si es muy baja o angosta) y la base recta. */
        private void bar(Canvas canvas,float l,float t,float r,float b,boolean round,int color){
            float rad=round?Math.min(AppTheme.dp(getContext(),4),Math.min((r-l)/2f,b-t)):0;
            for(int k=0;k<8;k++)radii[k]=k<4?rad:0;
            path.reset();rect.set(l,t,r,b);path.addRoundRect(rect,radii,Path.Direction.CW);fill.setColor(color);canvas.drawPath(path,fill);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(solid.length==0||pick==null)return super.onTouchEvent(e);
            int a=e.getActionMasked();
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_MOVE){int i=index(e.getX());if(i!=selected){selected=i;invalidate();Ui.haptic(this,Ui.Haptic.TICK);pick.pick(i);}return true;}
            if(a==MotionEvent.ACTION_UP){performClick();return true;}
            return a==MotionEvent.ACTION_CANCEL||super.onTouchEvent(e);
        }
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onKeyDown(int code,KeyEvent e){
            int n=solid.length;
            if(n>0&&pick!=null&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)){int i=Math.max(0,Math.min(n-1,(selected<0?n-1:selected)+(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1)));if(i!=selected){selected=i;invalidate();pick.pick(i);}return true;}
            return super.onKeyDown(code,e);
        }
    }

    /**
     * Mapa de calor 7 × 24: filas lunes a domingo, columnas de 0 a 23 h. Cada casilla, un paso del verde según su cuarto
     * del máximo (vacía: gris tenue); 2 px de aire entre casillas. La elegida lleva un anillo de tinta.
     */
    static final class Heat extends View {
        interface Pick{void pick(int day,int hour);}
        private final AppTheme.Palette p;private final long[][] ms;private final int[] ramp;private final int empty;private long max;
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),label=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF rect=new RectF();
        private int day=-1,hour=-1;private Pick pick;private float grow=1f;private ValueAnimator anim;
        Heat(Context c,AppTheme.Palette p,long[][] ms,int[] ramp,int empty){
            super(c);this.p=p;this.ms=ms;this.ramp=ramp;this.empty=empty;setFocusable(true);
            for(long[] row:ms)for(long v:row)max=Math.max(max,v);
            label.setTypeface(AppTheme.outfit(c,Weight.MEDIUM));label.setTextSize(sp(this,11));label.setColor(p.onSurfaceVariant);label.setFontFeatureSettings("tnum");
            ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(AppTheme.dp(c,2));ring.setColor(p.onSurface);
        }
        void onPick(Pick pick){this.pick=pick;}
        void grow(){if(!AppTheme.motion(getContext()))return;if(anim!=null)anim.cancel();grow=0f;anim=ValueAnimator.ofFloat(0f,1f);anim.setDuration(600);anim.setInterpolator(EMPHASIZED_DECELERATE);anim.addUpdateListener(a->{grow=(float)a.getAnimatedValue();invalidate();});anim.start();}
        @Override protected void onDetachedFromWindow(){if(anim!=null){anim.cancel();grow=1f;}super.onDetachedFromWindow();}
        private float left(){return Math.max(label.measureText("M"),label.measureText("D"))+AppTheme.dp(getContext(),8);}
        private float cell(float w){float d=AppTheme.dp(getContext(),1);return Math.max(10*d,Math.min(18*d,(w-left())/24f));}
        @Override protected void onMeasure(int w,int h){int width=getDefaultSize(getSuggestedMinimumWidth(),w);float ch=cell(width);setMeasuredDimension(width,Math.round(7*ch+(-label.ascent()+label.descent())+AppTheme.dp(getContext(),8)));}
        /** 0 = vacía; 1 a 4 = cuartos del máximo. */
        private int level(long v){if(v<=0||max<=0)return 0;double f=(double)v/max;return f<=0.25?1:f<=0.5?2:f<=0.75?3:4;}
        @Override protected void onDraw(Canvas canvas){
            float lw=left(),cw=(getWidth()-lw)/24f,ch=cell(getWidth()),gap=1f,r=AppTheme.dp(getContext(),3);
            for(int d=0;d<7;d++){
                float cy=d*ch+ch/2f;label.setTextAlign(Paint.Align.LEFT);canvas.drawText(DAYS[d],0,cy-(label.ascent()+label.descent())/2f,label);
                for(int h=0;h<24;h++){
                    int lv=level(ms[d][h]);fill.setColor(lv==0?empty:ramp[lv-1]);
                    // Aparecen de izquierda a derecha, como una ola (solo con animaciones).
                    float show=Math.max(0f,Math.min(1f,grow*1.6f-h/24f*0.6f));fill.setAlpha(Math.round((fill.getColor()>>>24)*show));
                    rect.set(lw+h*cw+gap,d*ch+gap,lw+(h+1)*cw-gap,(d+1)*ch-gap);canvas.drawRoundRect(rect,r,r,fill);
                }
            }
            if(day>=0&&hour>=0){float i=ring.getStrokeWidth()/2f;rect.set(lw+hour*cw+i,day*ch+i,lw+(hour+1)*cw-i,(day+1)*ch-i);canvas.drawRoundRect(rect,r,r,ring);}
            float y=7*ch-label.ascent()+AppTheme.dp(getContext(),4);label.setTextAlign(Paint.Align.LEFT);
            for(int h=0;h<24;h+=6)canvas.drawText(h+" h",lw+h*cw+gap,y,label);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(pick==null)return super.onTouchEvent(e);int a=e.getActionMasked();
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_MOVE){
                float lw=left(),cw=(getWidth()-lw)/24f,ch=cell(getWidth());
                int h=Math.max(0,Math.min(23,(int)((e.getX()-lw)/cw))),d=Math.max(0,Math.min(6,(int)(e.getY()/ch)));
                if(d!=day||h!=hour){day=d;hour=h;invalidate();Ui.haptic(this,Ui.Haptic.TICK);pick.pick(d,h);}
                return true;
            }
            if(a==MotionEvent.ACTION_UP){performClick();return true;}
            return a==MotionEvent.ACTION_CANCEL||super.onTouchEvent(e);
        }
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onKeyDown(int code,KeyEvent e){
            if(pick==null)return super.onKeyDown(code,e);
            int d=day<0?0:day,h=hour<0?0:hour;
            switch(code){case KeyEvent.KEYCODE_DPAD_LEFT:h--;break;case KeyEvent.KEYCODE_DPAD_RIGHT:h++;break;case KeyEvent.KEYCODE_DPAD_UP:d--;break;case KeyEvent.KEYCODE_DPAD_DOWN:d++;break;default:return super.onKeyDown(code,e);}
            if(d<0||d>6||h<0||h>23)return super.onKeyDown(code,e);
            day=d;hour=h;invalidate();pick.pick(d,h);return true;
        }
    }
    /**
     * Medidor horizontal: riel del mismo verde muy tenue (pastilla) y relleno con la punta redondeada (4 dp) y la base
     * recta a la izquierda, recortado por el riel. Crece desde la izquierda si hay animaciones.
     */
    static final class Meter extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF rect=new RectF();private final Path clip=new Path(),bar=new Path();private final float[] radii=new float[8];
        private final int fill,track;private final float fraction;private float grow=1f;private ValueAnimator anim;
        Meter(Context c,int fill,int track,float fraction){super(c);this.fill=fill;this.track=track;this.fraction=Math.max(0f,Math.min(1f,fraction));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void grow(long delay){if(!AppTheme.motion(getContext()))return;grow=0f;anim=ValueAnimator.ofFloat(0f,1f);anim.setDuration(600);anim.setStartDelay(120+delay);anim.setInterpolator(EMPHASIZED_DECELERATE);anim.addUpdateListener(a->{grow=(float)a.getAnimatedValue();invalidate();});anim.start();}
        @Override protected void onDetachedFromWindow(){if(anim!=null){anim.cancel();grow=1f;}super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),r=h/2f;if(w<=0||h<=0)return;
            clip.reset();rect.set(0,0,w,h);clip.addRoundRect(rect,r,r,Path.Direction.CW);paint.setColor(track);canvas.drawPath(clip,paint);
            if(fraction<=0)return;
            float fw=Math.max(h,w*fraction)*grow;if(fw<=0)return;float rad=Math.min(AppTheme.dp(getContext(),4),Math.min(fw/2f,r));
            for(int k=0;k<8;k++)radii[k]=k>=2&&k<6?rad:0;
            canvas.save();canvas.clipPath(clip);bar.reset();rect.set(0,0,fw,h);bar.addRoundRect(rect,radii,Path.Direction.CW);paint.setColor(fill);canvas.drawPath(bar,paint);canvas.restore();
        }
    }
}
