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
 *
 * Tercera ronda de la 0.8.0 (revisión de lo que pidió la integración, sin emulador): con TalkBack los gráficos de semanas
 * se ajustan como un deslizador; con letra grande no se recortan la cifra principal, los rótulos de los datos ni las
 * iniciales del mapa de días; desplazar la pantalla sobre un gráfico ya no cambia lo elegido; la pantalla se rehace si
 * cambió el día, el saldo o la lista de modelos (no solo las grabaciones); y «A medio camino» avisa cuando la Biblioteca
 * va a mostrar más grabaciones que las del período.
 */
public class MetricsActivity extends Screen {
    /**
     * Abre «Tus métricas». El botón ← dice a dónde vuelve: Grabar («Tu semana») o Ajustes. Su texto se arma al mostrar la
     * pantalla (no viaja en el Intent): si el sistema la rehace tras cambiar el idioma, sale en el nuevo.
     */
    static void open(Context c){c.startActivity(new Intent(c,MetricsActivity.class).putExtra("fromRecord",c instanceof MainActivity));}

    /**
     * «Hablas más los martes…»: el día en plural con su artículo o preposición en cada idioma (de lunes a domingo). Los
     * nombres de los días salen del idioma (dayNames); estos plurales no, por eso son textos.
     */
    private static final int[] ON_DAY={R.string.met_on_monday,R.string.met_on_tuesday,R.string.met_on_wednesday,R.string.met_on_thursday,R.string.met_on_friday,R.string.met_on_saturday,R.string.met_on_sunday};
    /** Filtros de la Biblioteca (MainActivity): 1 por guardar o transcritas, 2 en proceso, 3 sin transcribir, 4 con error. */
    private static final int LIB_SAVE=1,LIB_WORKING=2,LIB_NEW=3,LIB_FAILED=4;

    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private Metrics.Data data;private String shownKey;private boolean loading;private int restoreScroll;
    private Metrics.Period period=Metrics.Period.MONTH;private int week=Metrics.WEEKS-1;
    private SharedPreferences prefs;private LinearLayout content,periodBox,chips;
    private Bars minutesBars,usdBars;private TextView weekValue,weekTitle,weekUsd,weekUsdLabel;
    private ValueAnimator counter;
    /** Fondo de referencia de los gráficos: el vidrio de las tarjetas sobre el degradado suave (para mezclar los pasos del verde). */
    private int ground;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("metrics",MODE_PRIVATE);
        try{period=Metrics.Period.valueOf(prefs.getString("period",period.name()));}catch(RuntimeException ignored){}
        // Desde la tarjeta «Tu semana» de Grabar se abre en «7 días», lo mismo que esa tarjeta resume (sin cambiar lo guardado).
        if(state==null&&getIntent().getBooleanExtra("fromRecord",false))period=Metrics.Period.WEEK;
        if(state!=null){try{period=Metrics.Period.valueOf(state.getString("period",period.name()));}catch(RuntimeException ignored){}
            week=Math.max(0,Math.min(Metrics.WEEKS-1,state.getInt("week",week)));restoreScroll=state.getInt("screen_scroll",0);}
        ground=blend(p.softMid,0xFFFFFFFF,(p.glass>>>24)/255f);
        shell(getString(getIntent().getBooleanExtra("fromRecord",false)?R.string.nav_record:R.string.nav_settings),-1);
        String name=getString(R.string.metrics_title);TextView title=largeTitle(page,name,getString(R.string.met_subtitle));ui.highlightLast(title,name);
        content=ui.column();page.addView(content,Ui.fill());
        content.addView(loadingView(),Ui.fill());
    }
    @Override protected void onResume(){super.onResume();load();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("week",week);out.putString("period",period.name());super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(counter!=null)counter.cancel();disk.shutdownNow();super.onDestroy();}

    /**
     * Lo que puede cambiar las cifras sin que se toque una grabación, además de las grabaciones mismas (FilesStore.version
     * sube con cada escritura): el día (la racha, «Graba hoy…» y «este mes» cambian a medianoche), la última comprobación
     * de la clave (el saldo de OpenRouter) y la lista de modelos (los estimados salen de sus precios). Antes solo se miraba
     * FilesStore.version: al volver al día siguiente, la racha seguía diciendo lo de ayer.
     */
    private String freshness(){
        long verifyAt=0;try{verifyAt=new Settings(this).prefs.getLong("verifyAt",0);}catch(RuntimeException ignored){}
        return FilesStore.version.get()+"|"+java.time.LocalDate.now().toEpochDay()+"|"+verifyAt+"|"+Models.fetchedAt(this);
    }
    /**
     * Calcula en el hilo de disco. Solo si cambió algo desde lo que se muestra (ver freshness). Mientras recalcula, lo
     * anterior queda a media opacidad: no hay pantallazo en blanco ni saltos.
     */
    private void load(){
        String version=freshness();if(loading||(data!=null&&version.equals(shownKey)))return;
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
                    boolean first=data==null;data=result;shownKey=version;render(first);
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
        TextView h=ui.section(getString(R.string.met_by_period));h.setPadding(ui.dp(S1),ui.dp(S8),ui.dp(S1),ui.dp(S2));content.addView(h);
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
        LinearLayout card=card(R.drawable.ic_sparkle,getString(R.string.met_hero_title),null);
        if(data.streak>0){LinearLayout head=(LinearLayout)card.getChildAt(0);head.addView(streakPill());}
        String figure=words?Metrics.number(all.words):Metrics.hours(all.audioMs);
        TextView big=Ui.tabular(ui.text(figure,Type.DISPLAY_MEDIUM,p.onSurface));big.setMaxLines(1);big.setIncludeFontPadding(false);
        // La cifra cabe siempre en una línea (letra grande del sistema, millones de palabras): se achica sola hasta 24 sp.
        big.setAutoSizeTextTypeUniformWithConfiguration(24,Type.DISPLAY_MEDIUM.size,1,TypedValue.COMPLEX_UNIT_SP);
        // El alto es fijo (el ajuste automático lo necesita), pero nunca menor que el piso de 24 sp con la letra del
        // sistema: con la letra al 200 % esos 24 sp ya miden 48 dp y en 60 dp la cifra quedaba recortada abajo.
        int bigH=Math.max(ui.dp(60),Math.round(sp(big,24)*1.35f));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,bigH);bp.topMargin=ui.dp(S2);card.addView(big,bp);
        card.addView(ui.text(words?label(R.plurals.met_hero_words,all.words):getString(R.string.met_hero_untranscribed),Type.TITLE_MEDIUM,p.onSurfaceVariant));
        String saved=words?getString(R.string.met_hero_saved,span(all.savedMs())):getString(R.string.met_hero_saved_none);
        card.addView(line(words?R.drawable.ic_hourglass:R.drawable.ic_info,saved,Type.BODY_MEDIUM,p.onSurface),ui.top(S4));
        if(data.streak>0&&!data.today)card.addView(line(R.drawable.metrics_flame,count(R.plurals.met_keep_streak,data.streak),Type.BODY_MEDIUM,p.onSurfaceVariant),ui.top(S2));
        LinearLayout stats=ui.row();stats.setGravity(Gravity.TOP);
        addStat(stats,R.drawable.ic_waveform,Metrics.number(data.total),label(R.plurals.met_stat_recordings,data.total));
        if(words)addStat(stats,R.drawable.ic_clock,Metrics.hours(all.audioMs),getString(R.string.met_stat_recorded));else addStat(stats,R.drawable.ic_calendar,Metrics.number(all.days.size()),label(R.plurals.met_stat_days,all.days.size()));
        addStat(stats,R.drawable.metrics_flame,count(R.plurals.met_days,data.best),getString(R.string.met_stat_best_streak));
        card.addView(stats,ui.top(S5));
        // TalkBack: la tarjeta se lee de una vez, en una frase.
        StringBuilder say=new StringBuilder(words?count(R.plurals.met_say_words,all.words,saved):getString(R.string.met_say_recorded,figure));
        say.append(' ').append(count(R.plurals.met_recordings,data.total)).append('.');
        if(data.streak>0)say.append(' ').append(getString(data.today?R.string.met_say_streak:R.string.met_say_streak_keep,count(R.plurals.met_days,data.streak)));
        say.append(' ').append(getString(R.string.met_say_best,count(R.plurals.met_days,data.best)));
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
        String text=data.streak==1&&data.today?getString(R.string.met_streak_today):getResources().getQuantityString(R.plurals.met_streak_days,data.streak,data.streak);
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
        LinearLayout card=card(R.drawable.ic_calendar,getString(R.string.met_weeks_title),getString(R.string.met_weeks_hint));
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
        card.addView(caption(getString(R.string.met_chart_minutes),null,null),ui.top(S4));
        double mMax=niceMinutes(maxMin);
        minutesBars=new Bars(this,p,88);minutesBars.set(minutes,null,labels[0],labels[1],mValues,mMax,maxMin>0?Metrics.hours(Math.round(mMax*60_000)):"",!money);
        minutesBars.colors(p.primary,p.primary,p.highlight);minutesBars.setContentDescription(describeMinutes(labels[2]));
        card.addView(minutesBars,Ui.fill());
        if(money){
            card.addView(caption(getString(R.string.met_chart_spend),anyReal?getString(R.string.met_billed):null,anyEst?getString(R.string.met_estimated):null),ui.top(S4));
            double uMax=niceUsd(maxUsd);
            usdBars=new Bars(this,p,64);usdBars.set(real,est,labels[0],labels[1],uValues,uMax,Pricing.usd(uMax),true);
            usdBars.colors(p.primary,estColor(),p.highlight);usdBars.setContentDescription(describeUsd(labels[2]));
            card.addView(usdBars,Ui.fill());
        }else{
            usdBars=null;TextView none=ui.text(getString(R.string.met_weeks_no_spend),Type.BODY_SMALL,p.onSurfaceVariant);none.setPadding(ui.dp(S1),ui.dp(S2),0,0);card.addView(none);
        }
        Bars.Pick pick=i->{week=i;if(minutesBars!=null)minutesBars.select(i);if(usdBars!=null)usdBars.select(i);paintWeek();};
        minutesBars.onPick(pick);if(usdBars!=null)usdBars.onPick(pick);
        minutesBars.select(week);if(usdBars!=null)usdBars.select(week);paintWeek();
        if(motion){minutesBars.grow();if(usdBars!=null)usdBars.grow();}
        return card;
    }
    /** La lectura de la semana elegida: cuánto grabaste, cuántas grabaciones y cuánto gastaste. */
    private void paintWeek(){
        int i=week;long ms=data.weekMs[i];int recs=data.weekCount[i];double real=data.weekReal[i],est=data.weekEst[i];
        weekValue.setText(ms>0?Ui.humanDuration(ms):getString(R.string.met_unit_min,"0"));
        weekTitle.setText(weekName(i)+" · "+(recs==0?getString(R.string.met_no_recordings):count(R.plurals.met_recordings,recs)));
        weekUsd.setText(real+est>0?(est>0?"≈ ":"")+Pricing.usd(real+est):getString(R.string.met_usd_zero));
        weekUsdLabel.setText(getString(real+est>0?(est>0&&real>0?R.string.met_billed_and_est_lc:est>0?R.string.met_est_lc:R.string.met_billed_lc):R.string.met_no_spend_lc));
        // Con TalkBack, cada gráfico se maneja como un deslizador (Bars): su «valor» es la semana elegida, dicha completa.
        if(android.os.Build.VERSION.SDK_INT>=30){
            String state=weekName(i)+": "+(ms>0?Ui.humanDuration(ms):getString(R.string.met_no_recordings));
            if(minutesBars!=null)minutesBars.setStateDescription(state);
            if(usdBars!=null)usdBars.setStateDescription(weekName(i)+": "+(real+est<=0?getString(R.string.met_no_spend_lc):est>0?getString(R.string.met_say_about,Pricing.usd(real+est)):Pricing.usd(real+est)));
        }
    }
    private String weekName(int i){
        if(i==Metrics.WEEKS-1)return getString(R.string.met_this_week);if(i==Metrics.WEEKS-2)return getString(R.string.met_last_week);
        Date at=new Date(data.weekStarts[i]);return getString(R.string.met_week_of,day(at),month(at,"MMMM"));
    }
    /** Rótulos de las semanas: [con mes cuando cambia, solo el día, completo para TalkBack]. */
    private String[][] weekLabels(){
        int n=Metrics.WEEKS;String[] full=new String[n],shortL=new String[n],spoken=new String[n];
        SimpleDateFormat m=new SimpleDateFormat("M",Locale.ROOT);
        for(int i=0;i<n;i++){
            Date at=new Date(data.weekStarts[i]);boolean newMonth=i==0||!m.format(at).equals(m.format(new Date(data.weekStarts[i-1])));
            shortL[i]=day(at);full[i]=newMonth?dayMonth(at):shortL[i];
            spoken[i]=i==n-1?getString(R.string.met_say_this_week):i==n-2?getString(R.string.met_say_last_week):getString(R.string.met_say_week_of,day(at),month(at,"MMMM"));
        }
        return new String[][]{full,shortL,spoken};
    }
    /**
     * Fechas con los nombres del idioma (Lang.locale): el día del mes y el mes («21», «septiembre» o «sept.»). El orden lo
     * pone cada texto: «21 de septiembre», «September 21».
     */
    private static String day(Date at){return new SimpleDateFormat("d",Lang.locale()).format(at);}
    private static String month(Date at,String pattern){return new SimpleDateFormat(pattern,Lang.locale()).format(at);}
    /** «21 sept» / «Sep 21»: el día y el mes abreviado, sin el punto de la abreviatura (rótulos del eje y del saldo). */
    private String dayMonth(Date at){return getString(R.string.met_day_month,day(at),month(at,"MMM").replace(".",""));}
    private String describeMinutes(String[] spoken){
        StringBuilder b=new StringBuilder(getString(R.string.met_say_minutes_chart)).append(' ');
        for(int i=0;i<spoken.length;i++)b.append(capital(spoken[i])).append(": ").append(data.weekMs[i]>0?Ui.humanDuration(data.weekMs[i]):getString(R.string.met_say_nothing)).append(". ");
        return b.append(getString(R.string.met_say_bars_how)).toString();
    }
    private String describeUsd(String[] spoken){
        StringBuilder b=new StringBuilder(getString(R.string.met_say_spend_chart)).append(' ');
        for(int i=0;i<spoken.length;i++){double r=data.weekReal[i],e=data.weekEst[i];b.append(capital(spoken[i])).append(": ");
            b.append(r+e<=0?getString(R.string.met_no_spend_lc):r>0&&e>0?getString(R.string.met_say_billed_est,Pricing.usd(r),Pricing.usd(e)):r>0?getString(R.string.met_say_billed,Pricing.usd(r)):getString(R.string.met_say_est,Pricing.usd(e)));
            b.append(". ");}
        return b.toString();
    }
    /** Rótulo chico de un gráfico, con la leyenda (cuadrito + texto) a la derecha si hay dos series. */
    private View caption(String title,String a,String b){
        LinearLayout r=ui.row();r.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S2));
        r.addView(ui.text(title,Type.LABEL_MEDIUM,p.onSurfaceVariant),new LinearLayout.LayoutParams(0,-2,1));
        if(a!=null&&b!=null){r.addView(swatch(p.primary,a));r.addView(ui.space(S3));r.addView(swatch(estColor(),b));}
        else if(b!=null)r.addView(ui.text(getString(R.string.met_legend_est),Type.LABEL_MEDIUM,p.onSurfaceVariant));
        return r;
    }
    private View swatch(int color,String label){
        LinearLayout r=ui.row();View box=new View(this);box.setBackground(shape(this,color,2));r.addView(box,new LinearLayout.LayoutParams(ui.dp(10),ui.dp(10)));r.addView(ui.space(6));
        r.addView(ui.text(label,Type.LABEL_MEDIUM,p.onSurfaceVariant));return r;
    }
    /** Lo estimado: un paso más claro del mismo verde (lo cobrado es el verde lleno). */
    private int estColor(){return blend(p.primary,ground,0.5f);}

    // ---------- 3. Por período ----------
    private String chipName(Metrics.Period pe){return getString(pe==Metrics.Period.WEEK?R.string.met_chip_week:pe==Metrics.Period.MONTH?R.string.met_chip_month:R.string.met_chip_all);}
    /** Rótulo del período en cada tarjeta: «Últimos 7 días», el mes en curso con su nombre del idioma, o «Desde el comienzo». */
    private String periodName(){return period==Metrics.Period.WEEK?getString(R.string.met_period_week):period==Metrics.Period.MONTH?capital(month(new Date(data.now),"MMMM")):getString(R.string.met_period_all);}
    /** Período sin grabaciones: una línea amable, en vez de tarjetas con ceros. */
    private View quietPeriod(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.heading(getString(period==Metrics.Period.WEEK?R.string.met_quiet_week:R.string.met_quiet_month),Type.TITLE_MEDIUM));
        TextView t=ui.text(getString(R.string.met_quiet_body,chipName(Metrics.Period.ALL)),Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(0,ui.dp(S1),0,0);card.addView(t);
        return card;
    }

    /** Tu voz en números: grabado, palabras, ritmo y tiempo ahorrado del período, en cuatro datos de vidrio. */
    private View voice(Metrics.Totals t){
        LinearLayout card=card(R.drawable.ic_waveform,getString(R.string.met_voice_title),periodName());
        int wpm=t.wpm();
        LinearLayout a=ui.row();a.setGravity(Gravity.TOP);addStat(a,R.drawable.ic_clock,Metrics.hours(t.audioMs),getString(R.string.met_stat_recorded));addStat(a,R.drawable.ic_doc,t.words>0?Metrics.number(t.words):"—",getString(R.string.met_stat_words));card.addView(a,Ui.fill());
        LinearLayout b=ui.row();b.setGravity(Gravity.TOP);addStat(b,R.drawable.ic_speed,wpm>0?String.valueOf(wpm):"—",getString(R.string.met_stat_wpm));addStat(b,R.drawable.ic_hourglass,t.words>0?"≈ "+Metrics.hours(t.savedMs()):"—",getString(R.string.met_stat_saved));card.addView(b,ui.top(S2));
        StringBuilder more=new StringBuilder(count(R.plurals.met_recordings,t.recordings)).append(" · ").append(count(R.plurals.met_days_with_recordings,t.days.size()));
        if(t.marks>0)more.append(" · ").append(count(R.plurals.met_marks,t.marks));
        TextView m=ui.text(more.toString(),Type.BODY_SMALL,p.onSurfaceVariant);m.setPadding(ui.dp(S1),ui.dp(S3),ui.dp(S1),0);card.addView(m);
        if(t.transcribed<t.recordings&&t.words>0){TextView n=ui.text(getString(R.string.met_voice_note),Type.BODY_SMALL,p.onSurfaceVariant);n.setPadding(ui.dp(S1),ui.dp(S1),ui.dp(S1),0);card.addView(n);}
        return card;
    }

    /**
     * El camino hacia tu segundo cerebro: grabadas → transcritas → con nota → en 0-Inbox, con el % de lo grabado. Las
     * barras usan cuatro pasos del verde (más oscuro = más cerca de la meta). Debajo, lo que quedó a medio camino, con
     * acceso directo: la Biblioteca con su filtro, o la grabación si es una sola.
     */
    private View funnel(Metrics.Totals t,boolean motion){
        LinearLayout card=card(R.drawable.ic_filter,getString(R.string.met_funnel_title),periodName());
        String[] names={getString(R.string.met_funnel_recorded),getString(R.string.met_funnel_transcribed),getString(R.string.met_funnel_note),getString(R.string.met_funnel_inbox)};int[] counts={t.recordings,t.transcribed,t.withNote,t.inInbox};int[] ramp=ramp();int track=blend(ground,p.primary,0.10f);
        for(int i=0;i<4;i++){
            LinearLayout step=ui.column();
            LinearLayout r=ui.row();r.addView(ui.text(names[i],Type.LABEL_LARGE,p.onSurface),new LinearLayout.LayoutParams(0,-2,1));
            int pct=(int)Math.round(100d*counts[i]/Math.max(1,t.recordings));
            r.addView(Ui.tabular(ui.text(Metrics.number(counts[i])+(i==0?"":" · "+getString(R.string.met_percent,pct)),Type.LABEL_LARGE,p.onSurfaceVariant)));step.addView(r,Ui.fill());
            Meter meter=new Meter(this,ramp[i],track,(float)counts[i]/Math.max(1,t.recordings));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,ui.dp(10));mp.topMargin=ui.dp(6);step.addView(meter,mp);
            if(motion)meter.grow(i*60L);
            step.setContentDescription(i==0?names[i]+": "+Metrics.number(counts[i]):getString(R.string.met_say_funnel_step,names[i],Metrics.number(counts[i]),Metrics.number(t.recordings),pct));hideChildren(step,null);
            card.addView(step,i==0?Ui.fill():ui.top(S3));
        }
        // A medio camino: solo lo que existe. Cada fila lleva a esas grabaciones.
        LinearLayout stalls=ui.column();
        stall(stalls,R.drawable.ic_alert,getString(R.string.met_stall_failed),getString(R.string.met_stall_failed_hint),t.failed,data.all.failed.size(),LIB_FAILED);
        // Las pedidas: solo una se transcribe a la vez; las demás esperan su turno, el Wi-Fi o el cargador.
        stall(stalls,R.drawable.ic_hourglass,getString(R.string.met_stall_queued),getString(R.string.met_stall_queued_hint),t.queued,data.all.queued.size(),LIB_WORKING);
        stall(stalls,R.drawable.ic_transcribe,getString(R.string.met_stall_new),getString(R.string.met_stall_new_hint),t.pending,data.all.pending.size(),LIB_NEW);
        stall(stalls,R.drawable.ic_note,getString(R.string.met_stall_no_note),getString(t.noNote.size()>1?R.string.met_stall_no_note_many:R.string.met_stall_no_note_one),t.noNote,-1,-1);
        // «Por guardar» de la Biblioteca también cuenta las que cambiaron después de guardarse: no se promete un total.
        if(data.inboxOn)stall(stalls,R.drawable.ic_inbox,getString(R.string.met_stall_inbox),getString(R.string.met_stall_inbox_hint),t.notSaved,-1,LIB_SAVE);
        else if(t.transcribed>0){Ui.Row r=ui.listRow(R.drawable.ic_inbox,getString(R.string.met_choose_inbox),getString(R.string.met_choose_inbox_hint),null);
            r.onClick(v->startActivity(new Intent(this,SettingsActivity.class).putExtra("inbox",true)));addRow(stalls,r);}
        if(stalls.getChildCount()>0){
            TextView h=ui.text(getString(R.string.met_halfway),Type.LABEL_MEDIUM,p.onSurfaceVariant);h.setPadding(ui.dp(S1),ui.dp(S5),0,ui.dp(S1));card.addView(h);
            LinearLayout group=ui.group();group.addView(stalls,Ui.fill());card.addView(group,Ui.fill());
        }else{
            TextView ok=ui.chip(getString(R.string.met_all_arrived),p.onPrimaryContainer,p.primaryContainer);ok.setSingleLine(false);ok.setMaxLines(3);
            Drawable d=getDrawable(R.drawable.ic_check);if(d!=null){d=d.mutate();d.setTint(p.onPrimaryContainer);d.setBounds(0,0,ui.dp(16),ui.dp(16));ok.setCompoundDrawablesRelative(d,null,null,null);ok.setCompoundDrawablePadding(ui.dp(6));}
            LinearLayout.LayoutParams lp=Ui.wrap();lp.topMargin=ui.dp(S4);card.addView(ok,lp);
        }
        return card;
    }
    /**
     * Una fila de «A medio camino». total: cuántas hay en ese mismo estado desde el comienzo (-1 si no se sabe). La
     * Biblioteca filtra por estado, no por fecha: si el período muestra 2 y al tocar aparecen 5, la fila lo avisa antes
     * («5 en total en la Biblioteca»), así la diferencia no parece un error.
     */
    private void stall(LinearLayout list,int icon,String title,String hint,List<String> ids,int total,int filter){
        if(ids.isEmpty())return;
        boolean more=filter>=0&&ids.size()>1&&total>ids.size();
        String sub=more?getString(R.string.met_stall_total,hint,Metrics.number(total)):hint;
        Ui.Row r=ui.listRow(icon,title,sub,Metrics.number(ids.size()));Ui.tabular(r.value);r.value.setTextColor(p.onSurface);
        r.onClick(v->openStage(ids,filter));addRow(list,r);
        r.setContentDescription(title+": "+Metrics.number(ids.size())+". "+sub);
    }
    private void addRow(LinearLayout list,View row){if(list.getChildCount()>0)list.addView(ui.separator(S4+24+S4));list.addView(row,Ui.fill());}
    /**
     * Una sola grabación: su detalle. Varias: la Biblioteca con el filtro que corresponde. Va en los extras «library»
     * (true) y «filter» (el número del chip de la Biblioteca: LIB_*), que MainActivity lee al abrirse y en onNewIntent;
     * llega por onNewIntent cuando Grabar ya estaba abierta debajo (CLEAR_TOP + SINGLE_TOP, sin apilar otra). Sin filtro
     * que sirva (p. ej. «sin nota»), la más reciente.
     */
    private void openStage(List<String> ids,int filter){
        if(ids.size()==1||filter<0){startActivity(new Intent(this,RecordingActivity.class).putExtra("id",ids.get(0)));return;}
        Diagnostics.event("ui_action",null,"screen","MetricsActivity","action","library_filter","result",filter);
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
        LinearLayout card=card(R.drawable.metrics_cash,getString(R.string.met_costs_title),periodName());double usd=t.usd();
        TextView big=Ui.tabular(ui.text(usd>0?(t.estimated()>0?"≈ ":"")+Pricing.usd(usd):getString(R.string.met_usd_zero),Type.HEADLINE_LARGE,p.onSurface));big.setPadding(ui.dp(S1),0,0,0);card.addView(big);
        String when=getString(period==Metrics.Period.WEEK?R.string.met_when_week:period==Metrics.Period.MONTH?R.string.met_when_month:R.string.met_when_all);
        TextView cap=ui.text(usd>0?getString(t.notes>0?R.string.met_spent_notes:R.string.met_spent,when):getString(R.string.met_no_spend_in,when),Type.BODY_MEDIUM,p.onSurfaceVariant);cap.setPadding(ui.dp(S1),0,0,0);card.addView(cap);
        if(usd>0){
            double hour=t.usdPerHour();
            LinearLayout row=ui.row();row.setGravity(Gravity.TOP);
            addStat(row,R.drawable.ic_check_circle,t.real()>0?Pricing.usd(t.real()):"—",getString(R.string.met_billed));
            addStat(row,R.drawable.ic_info,t.estimated()>0?"≈ "+Pricing.usd(t.estimated()):"—",getString(R.string.met_estimated));
            addStat(row,R.drawable.ic_clock,hour>0?(t.estUsd>0?"≈ ":"")+Pricing.usd(hour):"—",getString(R.string.met_per_audio_hour));
            card.addView(row,ui.top(S4));
        }
        List<Metrics.ModelUse> models=t.models();
        if(!models.isEmpty()){
            card.addView(subhead(getString(R.string.met_by_model)));LinearLayout list=ui.group();
            for(Metrics.ModelUse u:models){
                String value=u.usd()>0?(u.estUsd>0?"≈ ":"")+Pricing.usd(u.usd()):getString(u.unknown>=u.count?R.string.met_no_rate:R.string.met_usd_zero);
                String sub=Metrics.service(u.provider)+" · "+Ui.humanDuration(u.audioMs)+" · "+count(R.plurals.met_transcriptions,u.count);
                addRow(list,costRow(u.name,sub,value));
            }
            card.addView(list,Ui.fill());
        }
        if(t.notes>0){
            card.addView(subhead(getString(R.string.met_notes_title)));LinearLayout list=ui.group();
            double n=t.noteReal+t.noteEst;String value=n>0?(t.noteEst>0?"≈ ":"")+Pricing.usd(n):"—";
            String sub=t.noteIn+t.noteOut>0?getString(R.string.met_note_tokens,Metrics.number(t.noteIn),Metrics.number(t.noteOut)):getString(R.string.met_no_tokens);
            addRow(list,costRow(count(R.plurals.met_notes,t.notes),sub,value));card.addView(list,Ui.fill());
        }
        StringBuilder foot=new StringBuilder();
        if(t.unknown>0)foot.append(unknownNote(t));
        if(!Double.isNaN(data.balance))foot.append(getString(R.string.met_balance,Pricing.usd(data.balance),dayMonth(new Date(data.balanceAt)))).append('\n');
        foot.append(getString(R.string.met_costs_foot));
        TextView f=ui.text(foot.toString(),Type.BODY_SMALL,p.onSurfaceVariant);f.setPadding(ui.dp(S1),ui.dp(S4),ui.dp(S1),0);card.addView(f);
        return card;
    }
    /**
     * Pie de «Costos» para las transcripciones sin costo conocido, por servicio (una línea cada uno, "" si no hay). «Con tu
     * servidor» solo para un servidor propio: en OpenRouter es que no vino el costo y el modelo no tiene tarifa por minuto
     * (p. ej. uno nuevo), y quien solo usó OpenRouter nunca tuvo servidor. Suma el "unknown" de cada modelo (Metrics.spend
     * lo cuenta en el período y en el modelo a la vez).
     */
    static String unknownNote(Metrics.Totals t){
        int router=0,openai=0,server=0;
        for(Metrics.ModelUse u:t.models.values()){if(u.unknown<=0)continue;if("openrouter".equals(u.provider))router+=u.unknown;else if("openai".equals(u.provider))openai+=u.unknown;else server+=u.unknown;}
        StringBuilder s=new StringBuilder();
        if(router>0)s.append(Metrics.count(R.plurals.met_unknown_router,router)).append('\n');
        if(openai>0)s.append(Metrics.count(R.plurals.met_unknown_openai,openai)).append('\n');
        if(server>0)s.append(Metrics.count(R.plurals.met_unknown_server,server)).append('\n');
        return s.toString();
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
        LinearLayout card=card(R.drawable.ic_clock,getString(R.string.met_heat_title),periodName());String[] days=dayNames();
        TextView read=ui.text(insight(t),Type.BODY_MEDIUM,p.onSurface);read.setPadding(ui.dp(S1),0,ui.dp(S1),ui.dp(S3));read.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(read);
        int[] ramp=ramp();int empty=blend(ground,p.outlineVariant,p.dark?0.7f:0.55f);
        Heat map=new Heat(this,p,t.heatMs,ramp,empty,days);
        map.onPick((day,hour)->{long ms=t.heatMs[day][hour];int n=t.heatCount[day][hour];
            read.setText(getString(R.string.met_heat_cell,days[day],clock(hour),clock(hour+1),n==0?getString(R.string.met_no_recordings):getString(R.string.met_duration_in,Ui.humanDuration(ms),count(R.plurals.met_recordings,n))));});
        map.setContentDescription(describeHeat(t,days));card.addView(map,Ui.fill());if(motion)map.grow();
        // Leyenda de la escala: Menos ▢▢▢▢▢ Más
        LinearLayout legend=ui.row();legend.setPadding(ui.dp(S1),ui.dp(S3),0,0);legend.addView(ui.text(getString(R.string.met_less),Type.LABEL_SMALL,p.onSurfaceVariant));legend.addView(ui.space(6));
        int[] all={empty,ramp[0],ramp[1],ramp[2],ramp[3]};
        for(int c:all){View box=new View(this);box.setBackground(shape(this,c,3));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(12),ui.dp(12));lp.setMarginEnd(ui.dp(3));legend.addView(box,lp);}
        legend.addView(ui.space(3));legend.addView(ui.text(getString(R.string.met_more),Type.LABEL_SMALL,p.onSurfaceVariant));legend.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.addView(legend);
        return card;
    }
    /** «Hablas más los martes, sobre todo entre 10:00 y 11:00.» */
    private String insight(Metrics.Totals t){
        long[] perDay=new long[7];int bd=0,bh=0;long best=-1;
        for(int d=0;d<7;d++)for(int h=0;h<24;h++){perDay[d]+=t.heatMs[d][h];if(t.heatMs[d][h]>best){best=t.heatMs[d][h];bd=d;bh=h;}}
        int top=0;for(int d=1;d<7;d++)if(perDay[d]>perDay[top])top=d;
        if(perDay[top]<=0)return getString(R.string.met_heat_hint);
        return bd==top?getString(R.string.met_insight_same,getString(ON_DAY[top]),clock(bh),clock(bh+1)):getString(R.string.met_insight_other,getString(ON_DAY[top]),getString(ON_DAY[bd]),clock(bh));
    }
    private String describeHeat(Metrics.Totals t,String[] days){
        StringBuilder b=new StringBuilder(getString(R.string.met_say_heat_title)).append(' ');
        for(int d=0;d<7;d++){long sum=0;int topH=-1;long topMs=0;for(int h=0;h<24;h++){sum+=t.heatMs[d][h];if(t.heatMs[d][h]>topMs){topMs=t.heatMs[d][h];topH=h;}}
            b.append(days[d]).append(": ").append(sum>0?getString(R.string.met_say_heat_day,Ui.humanDuration(sum),clock(topH)):getString(R.string.met_say_nothing)).append(". ");}
        return b.append(getString(R.string.met_say_heat_how)).toString();
    }
    /**
     * Los días de lunes a domingo con su nombre del idioma y mayúscula inicial («Lunes», «Monday», «Segunda-feira»). Antes
     * eran listas fijas en español; las iniciales del mapa (L, M, M…) salen de aquí mismo.
     */
    private static String[] dayNames(){
        String[] w=java.text.DateFormatSymbols.getInstance(Lang.locale()).getWeekdays(),out=new String[7];
        for(int d=0;d<7;d++)out[d]=capital(w[d==6?Calendar.SUNDAY:Calendar.MONDAY+d]);return out;
    }
    private static String clock(int hour){return String.format(Locale.ROOT,"%02d:00",hour%24==0&&hour>0?24:hour);}

    /**
     * Con quién más conversas: las voces con nombre de tus transcripciones (sin contarte a ti), por tiempo hablado. Los
     * nombres solo se muestran aquí: estas filas no se tocan y nada de esto se registra.
     */
    private View people(Metrics.Totals t,boolean motion){
        LinearLayout card=card(R.drawable.ic_people,getString(R.string.met_people_title),periodName());List<Metrics.Person> list=t.people();
        if(list.isEmpty()){
            TextView hint=ui.text(getString(R.string.met_people_empty),Type.BODY_MEDIUM,p.onSurfaceVariant);hint.setPadding(ui.dp(S1),0,ui.dp(S1),0);card.addView(hint);
        }else{
            long max=Math.max(1,list.get(0).ms);int track=blend(ground,p.primary,0.10f);
            for(int i=0;i<Math.min(5,list.size());i++){
                Metrics.Person person=list.get(i);LinearLayout item=ui.column();
                LinearLayout r=ui.row();TextView name=ui.oneLine(ui.text(person.name,Type.ITEM,p.onSurface));r.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                String value=(person.ms>=60_000?Ui.humanDuration(person.ms):getString(R.string.met_under_min))+" · "+count(R.plurals.met_audios,person.recordings);
                TextView v=Ui.tabular(ui.text(value,Type.LABEL_MEDIUM,p.onSurfaceVariant));v.setPadding(ui.dp(S2),0,0,0);r.addView(v);item.addView(r,Ui.fill());
                Meter m=new Meter(this,p.primary,track,(float)person.ms/max);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,ui.dp(8));mp.topMargin=ui.dp(6);item.addView(m,mp);if(motion)m.grow(i*60L);
                item.setContentDescription(person.name+": "+value);hideChildren(item,null);
                card.addView(item,i==0?Ui.fill():ui.top(S3));
            }
        }
        String voices=data.knownVoices>0?count(R.plurals.met_known_voices,data.knownVoices)+" ":"";
        TextView f=ui.text(voices+getString(R.string.met_people_foot),Type.BODY_SMALL,p.onSurfaceVariant);f.setPadding(ui.dp(S1),ui.dp(S4),ui.dp(S1),0);card.addView(f);
        return card;
    }

    // ---------- Pie, vacío, carga y error ----------
    private View footer(){
        LinearLayout box=ui.column();box.setPadding(0,ui.dp(S5),0,0);
        box.addView(ui.footnote(getString(R.string.met_footer)));
        // «¿Cómo se calcula?»: la explicación completa (met_how_body), con las palabras por minuto al teclear de Metrics.
        Ui.Btn b=ui.button(getString(R.string.met_how_button),0,Ui.Style.PLAIN,v->message(getString(R.string.met_how_title),getString(R.string.met_how_body,Metrics.TYPING_WPM)));b.setMinimumHeight(ui.dp(48));
        LinearLayout.LayoutParams lp=Ui.wrap();lp.setMarginStart(ui.dp(S1));box.addView(b,lp);return box;
    }

    /** Sin grabaciones: qué verá aquí y un solo botón para empezar. Unas barras tenues anticipan el gráfico. */
    private View emptyView(boolean motion){
        LinearLayout card=ui.card();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(ui.dp(S6),ui.dp(S6),ui.dp(S6),ui.dp(S6));
        Bars ghost=new Bars(this,p,72);ghost.set(new double[]{3,5,2,6,4,7,5,8},null,null,null,null,8,"",false);int faint=blend(ground,p.primary,0.28f);ghost.colors(faint,faint,0);
        ghost.setFocusable(false);ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(ghost,new LinearLayout.LayoutParams(ui.dp(220),-2));if(motion)ghost.grow();
        String head=getString(R.string.met_empty_title);TextView title=ui.heading(head,Type.HEADLINE_SMALL);title.setGravity(Gravity.CENTER);title.setPadding(0,ui.dp(S5),0,0);card.addView(title,Ui.fill());ui.highlightLast(title,head);
        TextView body=ui.text(getString(R.string.met_empty_body),Type.BODY_MEDIUM,p.onSurfaceVariant);
        body.setGravity(Gravity.CENTER);body.setPadding(0,ui.dp(S2),0,ui.dp(S5));card.addView(body,Ui.fill());
        card.addView(line(R.drawable.ic_doc,getString(R.string.met_empty_words),Type.BODY_MEDIUM,p.onSurface),Ui.fill());
        card.addView(line(R.drawable.metrics_flame,getString(R.string.met_empty_streak),Type.BODY_MEDIUM,p.onSurface),ui.top(S2));
        card.addView(line(R.drawable.metrics_cash,getString(R.string.met_empty_spend),Type.BODY_MEDIUM,p.onSurface),ui.top(S2));
        Ui.Btn go=ui.button(getString(R.string.met_empty_button),R.drawable.ic_mic,Ui.Style.PRIMARY,v->{navigate(0);finish();});
        LinearLayout.LayoutParams lp=Ui.fill();lp.topMargin=ui.dp(S6);card.addView(go,lp);
        if(motion)ui.fadeIn(card);
        return card;
    }
    private View loadingView(){
        LinearLayout box=ui.row();box.setGravity(Gravity.CENTER);box.setPadding(0,ui.dp(S10),0,ui.dp(S10));
        ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(p.primary));box.addView(spin,new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));
        box.addView(ui.space(S3));box.addView(ui.text(getString(R.string.met_loading),Type.BODY_MEDIUM,p.onSurfaceVariant));
        box.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);return box;
    }
    private View errorView(){
        LinearLayout card=ui.card();card.setPadding(ui.dp(S5),ui.dp(S5),ui.dp(S5),ui.dp(S5));
        card.addView(ui.heading(getString(R.string.met_error_title),Type.TITLE_MEDIUM));
        TextView t=ui.text(getString(R.string.met_error_body),Type.BODY_MEDIUM,p.onSurfaceVariant);t.setPadding(0,ui.dp(S1),0,ui.dp(S4));card.addView(t);
        card.addView(ui.button(getString(R.string.met_retry),R.drawable.ic_refresh,Ui.Style.SECONDARY,v->{content.removeAllViews();content.addView(loadingView(),Ui.fill());load();}),Ui.wrap());
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
     * Un dato de vidrio del kit (ui.stat, el de «Tu semana») a lo ancho que le toque. El rótulo puede ir en hasta tres
     * líneas (con la letra al 200 %, «Ahorrado al no teclear» en un tercio de pantalla no cabía en dos y se cortaba sin
     * aviso) y la cifra se achica si no cabe (letra grande del sistema), en vez de cortarse con «…».
     */
    private void addStat(LinearLayout row,int icon,String value,String label){
        LinearLayout s=ui.stat(icon,value,label);
        for(int k=0;k<s.getChildCount();k++)if(s.getChildAt(k) instanceof TextView){TextView t=(TextView)s.getChildAt(k);if(k==s.getChildCount()-1){t.setSingleLine(false);t.setMaxLines(3);t.setEllipsize(null);}else shrinkToFit(t,12);}
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
    private static String capital(String s){return s==null||s.isEmpty()?"":s.substring(0,1).toUpperCase(Lang.locale())+s.substring(1);}
    /** Cantidad con su plural del idioma («3 grabaciones»): la cifra (con separador de miles) va como %1$s; el resto, desde %2$s. */
    private String count(int plural,long n,Object... more){
        Object[] args=new Object[1+more.length];args[0]=Metrics.number(n);System.arraycopy(more,0,args,1,more.length);
        return getResources().getQuantityString(plural,quantity(n),args);
    }
    /** Solo la palabra según la cantidad («Grabaciones»), para el rótulo bajo una cifra que se muestra aparte. */
    private String label(int plural,long n){return getResources().getQuantityString(plural,quantity(n));}
    private static int quantity(long n){return (int)Math.min(n,Integer.MAX_VALUE);}
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
     * Cuándo un toque sobre un gráfico elige algo. Los gráficos van dentro de una pantalla que se desplaza: antes se
     * elegía apenas el dedo tocaba, así que al desplazar la pantalla empezando sobre un gráfico la semana (o la casilla)
     * elegida cambiaba sola. Ahora:
     * - un toque corto elige al soltar;
     * - un arrastre horizontal elige mientras se mueve, y la pantalla no se desplaza mientras tanto;
     * - un arrastre vertical es para desplazar la pantalla: ella se lo lleva (llega ACTION_CANCEL) y no se elige nada.
     */
    static final class Swipe {
        static final int NONE=0,PICK=1;
        private float x,y;private boolean dragging;
        int track(View v,MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:x=e.getX();y=e.getY();dragging=false;return NONE;
                case MotionEvent.ACTION_MOVE:
                    if(!dragging){
                        float dx=Math.abs(e.getX()-x),dy=Math.abs(e.getY()-y);int slop=android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                        if(dx>slop&&dx>dy){dragging=true;if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(true);}
                    }
                    return dragging?PICK:NONE;
                case MotionEvent.ACTION_UP:return PICK;
                default:return NONE;
            }
        }
    }

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
        private final Swipe swipe=new Swipe();
        @Override public boolean onTouchEvent(MotionEvent e){
            if(solid.length==0||pick==null)return super.onTouchEvent(e);
            int at=swipe.track(this,e);
            if(at==Swipe.PICK){int i=index(e.getX());if(i!=selected){selected=i;invalidate();Ui.haptic(this,Ui.Haptic.TICK);pick.pick(i);}}
            if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
            return true;
        }
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onKeyDown(int code,KeyEvent e){
            int n=solid.length;
            if(n>0&&pick!=null&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)){step(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);return true;}
            return super.onKeyDown(code,e);
        }
        /** Elige la semana vecina (-1 la anterior, +1 la siguiente), sin salirse de las 8. */
        private void step(int by){
            int n=solid.length;if(n==0||pick==null)return;int i=Math.max(0,Math.min(n-1,(selected<0?n-1:selected)+by));
            if(i!=selected){selected=i;invalidate();pick.pick(i);}
        }
        /*
         * TalkBack: las flechas solo sirven con teclado y tocar una barra no elige nada con el lector activo (el toque
         * enfoca). Por eso el gráfico se presenta como un deslizador, igual que la posición del audio en el detalle
         * (RecordingActivity): el gesto de ajustar elige la semana siguiente o la anterior y su lectura queda como el
         * «valor» del deslizador (setStateDescription, en paintWeek). El gráfico decorativo del estado vacío no tiene
         * pick: no se ofrece como control.
         */
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);
            if(pick==null||solid.length==0)return;
            info.setClassName(android.widget.SeekBar.class.getName());
            if(selected<solid.length-1)info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if(selected>0)info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args){
            if(pick!=null&&(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
                step(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);return true;}
            return super.performAccessibilityAction(action,args);
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
        /** Inicial de cada día (L, M, M… / M, T, W…) y rótulos del eje de horas («0 h», «6 h»…), en el idioma de la app. */
        private final String[] initials=new String[7],hours=new String[4];
        private int day=-1,hour=-1;private Pick pick;private float grow=1f;private ValueAnimator anim;
        /** days: los nombres de lunes a domingo (dayNames); cada fila lleva su primera letra. */
        Heat(Context c,AppTheme.Palette p,long[][] ms,int[] ramp,int empty,String[] days){
            super(c);this.p=p;this.ms=ms;this.ramp=ramp;this.empty=empty;setFocusable(true);
            for(long[] row:ms)for(long v:row)max=Math.max(max,v);
            for(int d=0;d<7;d++)initials[d]=days[d].isEmpty()?"":days[d].substring(0,1);
            for(int k=0;k<hours.length;k++)hours[k]=c.getString(R.string.met_axis_hour,k*6);
            label.setTypeface(AppTheme.outfit(c,Weight.MEDIUM));label.setTextSize(sp(this,11));label.setColor(p.onSurfaceVariant);label.setFontFeatureSettings("tnum");
            ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(AppTheme.dp(c,2));ring.setColor(p.onSurface);
        }
        void onPick(Pick pick){this.pick=pick;}
        void grow(){if(!AppTheme.motion(getContext()))return;if(anim!=null)anim.cancel();grow=0f;anim=ValueAnimator.ofFloat(0f,1f);anim.setDuration(600);anim.setInterpolator(EMPHASIZED_DECELERATE);anim.addUpdateListener(a->{grow=(float)a.getAnimatedValue();invalidate();});anim.start();}
        @Override protected void onDetachedFromWindow(){if(anim!=null){anim.cancel();grow=1f;}super.onDetachedFromWindow();}
        /** Ancho de la columna de iniciales: la más ancha del idioma (antes, «M» o «D»). */
        private float left(){float w=0;for(String s:initials)w=Math.max(w,label.measureText(s));return w+AppTheme.dp(getContext(),8);}
        private float cell(float w){float d=AppTheme.dp(getContext(),1);return Math.max(10*d,Math.min(18*d,(w-left())/24f));}
        /**
         * Alto de cada fila: el de la casilla, pero nunca menos que la inicial del día. La letra va en sp y las casillas en
         * dp: con la letra grande del sistema las iniciales (L, M, M…) medían más que la fila y se montaban unas sobre otras.
         * Las casillas quedan entonces algo más altas que anchas.
         */
        private float row(float w){return Math.max(cell(w),-label.ascent()+label.descent()+AppTheme.dp(getContext(),2));}
        @Override protected void onMeasure(int w,int h){int width=getDefaultSize(getSuggestedMinimumWidth(),w);float ch=row(width);setMeasuredDimension(width,Math.round(7*ch+(-label.ascent()+label.descent())+AppTheme.dp(getContext(),8)));}
        /** 0 = vacía; 1 a 4 = cuartos del máximo. */
        private int level(long v){if(v<=0||max<=0)return 0;double f=(double)v/max;return f<=0.25?1:f<=0.5?2:f<=0.75?3:4;}
        @Override protected void onDraw(Canvas canvas){
            float lw=left(),cw=(getWidth()-lw)/24f,ch=row(getWidth()),gap=1f,r=AppTheme.dp(getContext(),3);
            for(int d=0;d<7;d++){
                float cy=d*ch+ch/2f;label.setTextAlign(Paint.Align.LEFT);canvas.drawText(initials[d],0,cy-(label.ascent()+label.descent())/2f,label);
                for(int h=0;h<24;h++){
                    int lv=level(ms[d][h]);fill.setColor(lv==0?empty:ramp[lv-1]);
                    // Aparecen de izquierda a derecha, como una ola (solo con animaciones).
                    float show=Math.max(0f,Math.min(1f,grow*1.6f-h/24f*0.6f));fill.setAlpha(Math.round((fill.getColor()>>>24)*show));
                    rect.set(lw+h*cw+gap,d*ch+gap,lw+(h+1)*cw-gap,(d+1)*ch-gap);canvas.drawRoundRect(rect,r,r,fill);
                }
            }
            if(day>=0&&hour>=0){float i=ring.getStrokeWidth()/2f;rect.set(lw+hour*cw+i,day*ch+i,lw+(hour+1)*cw-i,(day+1)*ch-i);canvas.drawRoundRect(rect,r,r,ring);}
            float y=7*ch-label.ascent()+AppTheme.dp(getContext(),4);label.setTextAlign(Paint.Align.LEFT);
            for(int h=0;h<24;h+=6)canvas.drawText(hours[h/6],lw+h*cw+gap,y,label);
        }
        private final Swipe swipe=new Swipe();
        @Override public boolean onTouchEvent(MotionEvent e){
            if(pick==null)return super.onTouchEvent(e);
            if(swipe.track(this,e)==Swipe.PICK){
                float lw=left(),cw=(getWidth()-lw)/24f,ch=row(getWidth());
                int h=Math.max(0,Math.min(23,(int)((e.getX()-lw)/cw))),d=Math.max(0,Math.min(6,(int)(e.getY()/ch)));
                if(d!=day||h!=hour){day=d;hour=h;invalidate();Ui.haptic(this,Ui.Haptic.TICK);pick.pick(d,h);}
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
            return true;
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
