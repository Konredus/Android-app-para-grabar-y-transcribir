package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Button;

/**
 * Botón de grabar (0.6.0): círculo rojo con micrófono que se transforma en un cuadrado redondeado con ■ al grabar
 * (convención de grabadoras: «círculo = grabar, cuadrado = detener»). La forma cambia, el tamaño y el lugar no:
 * Detener queda exactamente donde estaba Grabar.
 * En reposo lleva un halo tonal suave; grabando, el halo reacciona al volumen real del micrófono.
 */
final class RecordButton extends View {
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),halo=new Paint(Paint.ANTI_ALIAS_FLAG),glyph=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mic;
    private float morph,level,shownLevel;private boolean recording;private ValueAnimator animator;
    private final RectF rect=new RectF(),box=new RectF();

    RecordButton(Context c,AppTheme.Palette p){
        super(c);fill.setColor(p.record);halo.setColor(p.record);glyph.setColor(0xFFFFFFFF);
        mic=c.getDrawable(R.drawable.ic_mic_fill).mutate();mic.setTint(0xFFFFFFFF);
        setClickable(true);setFocusable(true);setBackground(null);Ui.pressable(this);
        // Sombra suave que sigue la forma (círculo → cuadrado redondeado).
        setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){shape(box);o.setRoundRect(Math.round(box.left),Math.round(box.top),Math.round(box.right),Math.round(box.bottom),corner(box));}});
        setElevation(dp(3));
    }
    void setRecording(boolean value,boolean animate){
        if(recording==value&&animator==null)return;recording=value;if(animator!=null)animator.cancel();
        float target=value?1f:0f;if(!animate||!ValueAnimator.areAnimatorsEnabled()){morph=target;invalidate();invalidateOutline();return;}
        animator=ValueAnimator.ofFloat(morph,target);animator.setDuration(AppTheme.MOTION_SLOW);animator.setInterpolator(new PathInterpolator(0.2f,0f,0f,1f));
        animator.addUpdateListener(a->{morph=(float)a.getAnimatedValue();invalidate();invalidateOutline();});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){animator=null;}});animator.start();
    }
    /** Nivel 0..1 del micrófono (ya normalizado). */
    void setLevel(float value){level=value;shownLevel+=(level-shownLevel)*0.35f;invalidate();}
    /** La forma roja: mismo tamaño en ambos estados. */
    private void shape(RectF out){float cx=getWidth()/2f,cy=getHeight()/2f,half=Math.min(cx,cy)-dp(14);out.set(cx-half,cy-half,cx+half,cy+half);}
    private float corner(RectF s){float half=s.width()/2f;return half+(Math.min(half,dp(28))-half)*morph;}
    @Override protected void onDraw(Canvas canvas){
        shape(rect);float cx=rect.centerX(),cy=rect.centerY(),corner=corner(rect);
        // Halo: tonal en reposo; con el volumen real al grabar.
        float grow=recording?(shownLevel>0.02f?dp(4)+shownLevel*dp(10):0):dp(10)*(1f-morph);
        if(grow>0){halo.setAlpha(recording?(int)(45+shownLevel*60):(int)(30*(1f-morph)));box.set(rect);box.inset(-grow,-grow);canvas.drawRoundRect(box,corner+grow,corner+grow,halo);}
        canvas.drawRoundRect(rect,corner,corner,fill);
        // Ícono: micrófono en reposo, ■ al grabar (se funden durante la transformación).
        int size=Math.round(dp(34));mic.setBounds(Math.round(cx-size/2f),Math.round(cy-size/2f),Math.round(cx+size/2f),Math.round(cy+size/2f));mic.setAlpha((int)(255*(1f-morph)));if(morph<1f)mic.draw(canvas);
        if(morph>0f){float s=dp(13);glyph.setAlpha((int)(255*morph));canvas.drawRoundRect(cx-s,cy-s,cx+s,cy+s,dp(5),dp(5),glyph);}
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);invalidateOutline();}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.6f);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen","MainActivity","action",recording?"stop":"record");return super.performClick();}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
