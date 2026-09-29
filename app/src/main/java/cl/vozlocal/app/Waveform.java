package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Onda en vivo (0.6.0), el sello de la pantalla Grabar: barras redondeadas que avanzan de derecha a izquierda con el
 * volumen real del micrófono, sobre una tarjeta.
 * - Grabando: lo ya grabado va en onSurfaceVariant y lo más reciente en el rojo de grabar.
 * - En pausa: la onda se congela en gris.
 * - En reposo no se dibuja nada (no inventa actividad).
 * Cada momento ★ queda marcado sobre la barra en que se tocó y avanza con ella.
 * La vista puede ser más alta que la onda: la onda mide como máximo MAX_DP y va centrada.
 */
final class Waveform extends View {
    static final int IDLE=0,LIVE=1,PAUSED=2;
    /** Alto máximo de la tarjeta de la onda (dp). */
    static final float MAX_DP=140;
    /** Barras guardadas: más de las que caben en una pantalla ancha. */
    private static final int CAP=200;
    /** Cuántas barras (las más nuevas) van en el rojo de grabar. */
    private static final int RECENT=6;
    private final float[] history=new float[CAP];private final boolean[] marks=new boolean[CAP];private int count;
    private final Paint bar=new Paint(Paint.ANTI_ALIAS_FLAG),card=new Paint(Paint.ANTI_ALIAS_FLAG),star=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int past,live,frozen,marked,empty;private int mode=IDLE;
    private final RectF rect=new RectF();private final Path starPath=new Path();

    Waveform(Context c,AppTheme.Palette p){
        super(c);past=p.onSurfaceVariant;live=p.record;frozen=p.outline;marked=p.primary;empty=p.outlineVariant;
        card.setColor(p.card);star.setColor(p.primary);star.setPathEffect(new CornerPathEffect(dp(1)));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    /** IDLE (limpia la onda), LIVE o PAUSED (congela). */
    void setMode(int mode){
        if(this.mode==mode)return;
        if(mode==IDLE){java.util.Arrays.fill(history,0);java.util.Arrays.fill(marks,false);count=0;}
        this.mode=mode;invalidate();
    }
    int mode(){return mode;}
    /** Convierte la amplitud cruda (0..32767) a una escala perceptual en dB. */
    static float normalize(int amplitude){if(amplitude<=0)return 0;double db=20*Math.log10(amplitude/32767d);return (float)Math.max(0,Math.min(1,(db+48)/48));}
    /** Agrega el nivel más reciente (0..1). En pausa no avanza. */
    void push(float level){
        if(mode!=LIVE)return;
        System.arraycopy(history,1,history,0,CAP-1);System.arraycopy(marks,1,marks,0,CAP-1);
        history[CAP-1]=level;marks[CAP-1]=false;count=Math.min(CAP,count+1);invalidate();
    }
    /** Marca un momento ★ en la barra más reciente. */
    void mark(){if(mode==IDLE)return;marks[CAP-1]=true;if(count==0)count=1;invalidate();}

    @Override protected void onDraw(Canvas canvas){
        if(mode==IDLE)return;
        float w=getWidth(),h=getHeight(),band=Math.min(h,dp(MAX_DP)),top=(h-band)/2f;if(band<dp(24))return;
        rect.set(0,top,w,top+band);float radius=Math.min(dp(20),band/2f);canvas.drawRoundRect(rect,radius,radius,card);
        float padX=dp(14),starRoom=dp(16),padY=dp(10);
        float innerTop=top+starRoom,innerBottom=top+band-padY,inner=innerBottom-innerTop,mid=(innerTop+innerBottom)/2f;
        float barW=dp(4),gap=dp(3),step=barW+gap,min=dp(4);int n=(int)((w-2*padX+gap)/step);
        for(int k=0;k<n;k++){
            int i=CAP-1-k;float x=w-padX-barW-k*step;if(x<padX-0.5f)break;
            boolean has=k<count;float v=has?history[i]:0;float height=has?Math.max(min,v*inner):min;
            int color=!has?empty:mode==PAUSED?frozen:marks[i]?marked:k<RECENT?live:past;
            bar.setColor(color);
            // Lo más antiguo se desvanece un poco para dar sensación de tiempo.
            bar.setAlpha(!has?90:mode==PAUSED?170:(int)(255-110f*k/Math.max(1,n)));
            canvas.drawRoundRect(x,mid-height/2f,x+barW,mid+height/2f,barW/2f,barW/2f,bar);
            if(has&&marks[i]){star.setAlpha(mode==PAUSED?150:255);canvas.drawPath(star(starPath,x+barW/2f,top+starRoom/2f+dp(1),dp(6)),star);}
        }
    }
    /** Estrella de 5 puntas (★) centrada en (cx, cy) con radio exterior r. La usan la onda y el botón Marcar. */
    static Path star(Path out,float cx,float cy,float r){
        out.reset();
        for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float rr=i%2==0?r:r*0.47f;float x=cx+(float)(Math.cos(a)*rr),y=cy+(float)(Math.sin(a)*rr);if(i==0)out.moveTo(x,y);else out.lineTo(x,y);}
        out.close();return out;
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
