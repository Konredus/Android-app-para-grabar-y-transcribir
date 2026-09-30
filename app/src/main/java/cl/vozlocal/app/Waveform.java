package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Onda en vivo de Verbapp (0.7.0), el sello de la pantalla Grabar: se dibuja en blanco sobre el verde intenso, como en
 * la referencia.
 * - Grabando: las barras nacen en un cabezal vertical (a ~62 % del ancho) con el volumen real del micrófono y avanzan
 *   hacia la izquierda. Detrás del cabezal, una sombra verde profunda en forma de cono que se aclara hacia la izquierda;
 *   a la derecha, marcas tenues parejas de lo que falta.
 * - En pausa: todo se atenúa y la onda se congela.
 * - En reposo no se dibuja nada (no inventa actividad).
 * Cada momento ★ queda como una estrellita blanca sobre la barra en que se tocó y avanza con ella.
 * La vista puede ser más alta que la onda: la onda mide como máximo MAX_DP y va un poco bajo el centro (más verde).
 * Además: {@link Decor} (ondas grises junto al micrófono en reposo) y {@link Mini} (resumen de lo recién grabado).
 */
final class Waveform extends View {
    static final int IDLE=0,LIVE=1,PAUSED=2;
    /** Alto máximo de la onda (dp): en pantallas altas la vista crece, la onda no se estira. */
    static final float MAX_DP=150;
    /** Barras guardadas: más de las que caben a la izquierda del cabezal en una pantalla ancha. */
    private static final int CAP=240;
    /** Cabezal: fracción del ancho donde nacen las barras. */
    private static final float HEAD=0.62f;
    private final float[] history=new float[CAP];private final boolean[] marks=new boolean[CAP];private int count;
    private final Paint bar=new Paint(Paint.ANTI_ALIAS_FLAG),tick=new Paint(Paint.ANTI_ALIAS_FLAG),head=new Paint(Paint.ANTI_ALIAS_FLAG),shade=new Paint(Paint.ANTI_ALIAS_FLAG),star=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int deep;private int mode=IDLE;
    private final Path starPath=new Path(),shadePath=new Path();

    Waveform(Context c,AppTheme.Palette p){
        super(c);deep=p.brandDeep;
        for(Paint paint:new Paint[]{bar,tick,head,star})paint.setColor(p.onVivid);
        star.setPathEffect(new CornerPathEffect(dp(1)));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    /** IDLE (limpia la onda), LIVE o PAUSED (congela y atenúa). */
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

    /** Geometría de la onda: alto útil, arriba y centro (algo sobre el centro de la vista, cerca del cronómetro, como en la referencia). */
    private float band(){return Math.min(getHeight(),dp(MAX_DP));}
    private float top(){return (getHeight()-band())*0.38f;}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        super.onSizeChanged(w,h,ow,oh);
        // La sombra solo depende del tamaño: se arma una vez (no en cada cuadro).
        float band=band(),mid=top()+band/2f,inner=band-2*dp(16),headX=Math.round(w*HEAD),len=Math.min(headX,w*0.42f);
        shadePath.reset();shadePath.moveTo(headX,mid-inner/2f-dp(6));shadePath.lineTo(headX-len,mid-inner*0.2f);shadePath.lineTo(headX-len,mid+inner*0.2f);shadePath.lineTo(headX,mid+inner/2f+dp(6));shadePath.close();
        shade.setShader(len<=0?null:new LinearGradient(headX,0,headX-len,0,AppTheme.withAlpha(deep,0x4D),AppTheme.withAlpha(deep,0),Shader.TileMode.CLAMP));
    }
    @Override protected void onDraw(Canvas canvas){
        if(mode==IDLE)return;
        float w=getWidth(),band=band();if(band<dp(24))return;
        float top=top(),mid=top+band/2f,headX=Math.round(w*HEAD),inner=band-2*dp(16),min=dp(3);
        boolean paused=mode==PAUSED;float dim=paused?0.45f:1f;
        // Sombra detrás del cabezal (debajo de las barras).
        shade.setAlpha(Math.round(255*(paused?0.5f:1f)));canvas.drawPath(shadePath,shade);
        float barW=dp(3),step=barW+dp(3.5f),lead=dp(8);
        // A la derecha del cabezal: marcas tenues y parejas de lo que falta.
        tick.setAlpha(Math.round(0x4D*(paused?0.7f:1f)));
        for(float x=headX+lead;x+barW<=w;x+=step)canvas.drawRoundRect(x,mid-dp(6),x+barW,mid+dp(6),barW/2f,barW/2f,tick);
        // Barras: la más nueva junto al cabezal; avanzan hacia la izquierda y se desvanecen un poco con el tiempo.
        int n=(int)((headX-lead)/step)+1;
        for(int k=0;k<n&&k<count;k++){
            int i=CAP-1-k;float x=headX-lead-barW-k*step;if(x<-barW)break;
            float height=Math.max(min,history[i]*inner);
            bar.setAlpha(Math.round((255-125f*k/Math.max(1,n))*dim));
            canvas.drawRoundRect(x,mid-height/2f,x+barW,mid+height/2f,barW/2f,barW/2f,bar);
            if(marks[i]){star.setAlpha(Math.round(255*(paused?0.6f:1f)));canvas.drawPath(star(starPath,x+barW/2f,mid-height/2f-dp(9),dp(6)),star);}
        }
        // Cabezal: línea vertical blanca con extremos redondeados, un poco más alta que la barra más alta.
        float hw=dp(3);head.setAlpha(Math.round(255*(paused?0.55f:1f)));
        canvas.drawRoundRect(headX-hw/2f,top+dp(4),headX+hw/2f,top+band-dp(4),hw/2f,hw/2f,head);
    }
    /** Estrella de 5 puntas (★) centrada en (cx, cy) con radio exterior r. La usan la onda y el botón Marcar. */
    static Path star(Path out,float cx,float cy,float r){
        out.reset();
        for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float rr=i%2==0?r:r*0.47f;float x=cx+(float)(Math.cos(a)*rr),y=cy+(float)(Math.sin(a)*rr);if(i==0)out.moveTo(x,y);else out.lineTo(x,y);}
        out.close();return out;
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}

    /**
     * Ondas decorativas grises a ambos lados del micrófono (en reposo), como en la referencia: quietas, casi simétricas
     * y desvaneciéndose hacia los bordes. No representan audio (no inventan actividad): solo acompañan al botón.
     * gap: ancho libre al centro, donde va el micrófono con su halo.
     */
    static final class Decor extends View {
        private static final float[] PATTERN={0.46f,0.72f,0.5f,0.94f,0.62f,0.38f,0.82f,0.55f,1f,0.48f,0.7f,0.34f,0.64f,0.86f,0.44f,0.6f,0.3f,0.52f,0.4f,0.28f};
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private float gap;
        Decor(Context c,int color){super(c);paint.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void setGap(float px){if(gap!=px){gap=px;invalidate();}}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),cx=w/2f,mid=h/2f,barW=dp(2.5f),step=dp(6.5f);
            int n=(int)((cx-gap/2f)/step);if(n<=0||h<=0)return;
            for(int side=-1;side<=1;side+=2)for(int k=0;k<n;k++){
                // t = 0 junto al micrófono, 1 en el borde: más bajas y más tenues hacia afuera.
                float t=k/(float)Math.max(1,n-1),x=side<0?cx-gap/2f-k*step-barW:cx+gap/2f+k*step;
                float bh=Math.max(dp(4),h*PATTERN[(k+(side>0?5:0))%PATTERN.length]*(1f-0.4f*t));
                paint.setAlpha(Math.round(230*(1f-t)*(1f-t)+18));
                canvas.drawRoundRect(x,mid-bh/2f,x+barW,mid+bh/2f,barW/2f,barW/2f,paint);
            }
        }
        private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    }

    /**
     * Onda chica de lo recién grabado (hoja «Nombra esta grabación»): los niveles que se dibujaron en vivo, reducidos a
     * unas 40 barras (sin volver a leer el audio). Sin niveles, barras planas. Lo ya escuchado va en el color «done».
     */
    static final class Mini extends View {
        private static final int FLAT=40;
        private final float[] bars;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int base,done;private float played;
        Mini(Context c,float[] bars,int base,int done){super(c);this.bars=bars==null?new float[0]:bars;this.base=base;this.done=done;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        /** Avance de la reproducción (0..1). */
        void setPlayed(float value){value=Math.max(0f,Math.min(1f,value));if(value!=played){played=value;invalidate();}}
        @Override protected void onDraw(Canvas canvas){
            int n=bars.length==0?FLAT:bars.length;float w=getWidth(),h=getHeight(),mid=h/2f,step=w/n,barW=Math.min(dp(2.5f),step*0.6f),min=dp(3);if(w<=0)return;
            for(int i=0;i<n;i++){
                // Más contraste que el nivel crudo: el silencio queda bajo y la voz alta.
                float v=bars.length==0?0f:Math.max(0f,Math.min(1f,(bars[i]-0.12f)/0.78f)),bh=Math.max(min,v*h),x=i*step+(step-barW)/2f;
                paint.setColor(x+barW/2f<=played*w?done:base);
                canvas.drawRoundRect(x,mid-bh/2f,x+barW,mid+bh/2f,barW/2f,barW/2f,paint);
            }
        }
        private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    }
}
