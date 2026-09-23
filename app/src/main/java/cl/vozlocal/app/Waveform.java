package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Onda en vivo: barras que avanzan de derecha a izquierda con el volumen real. En reposo muestra una línea
 * punteada tenue (no inventa actividad). Las barras antiguas se desvanecen para dar sensación de tiempo.
 */
final class Waveform extends View {
    private static final int BARS=56;
    private final float[] history=new float[BARS];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int active,idle;private boolean live;

    Waveform(Context c,AppTheme.Palette p){super(c);active=p.record;idle=p.faint;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setColors(int active){this.active=active;invalidate();}
    void setLive(boolean live){this.live=live;if(!live)java.util.Arrays.fill(history,0);invalidate();}
    /** Convierte la amplitud cruda (0..32767) a una escala perceptual en dB. */
    static float normalize(int amplitude){if(amplitude<=0)return 0;double db=20*Math.log10(amplitude/32767d);return (float)Math.max(0,Math.min(1,(db+48)/48));}
    void push(float level){System.arraycopy(history,1,history,0,BARS-1);history[BARS-1]=level;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        float w=getWidth(),h=getHeight(),step=w/BARS,bar=Math.max(dp(2.5f),step*0.5f),mid=h/2f;
        for(int i=0;i<BARS;i++){
            float x=i*step+(step-bar)/2f;float v=live?history[i]:0;float height=Math.max(dp(3),v*h*0.92f);
            paint.setColor(live&&v>0.001f?active:idle);paint.setAlpha(live?(int)(90+165f*i/BARS):110);
            canvas.drawRoundRect(x,mid-height/2,x+bar,mid+height/2,bar/2,bar/2,paint);
        }
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
