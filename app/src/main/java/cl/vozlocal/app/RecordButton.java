package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Button;

/**
 * Botón de grabar: anillo + círculo rojo que se transforma en cuadrado redondeado al grabar (convención de
 * grabadoras: "círculo = grabar, cuadrado = detener"). Un halo reacciona al volumen real del micrófono,
 * así se ve que el micrófono está captando sonido.
 */
final class RecordButton extends View {
    private final Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float morph,level,shownLevel;private boolean recording;private ValueAnimator animator;
    private final RectF rect=new RectF();

    RecordButton(Context c,AppTheme.Palette p){
        super(c);ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(dp(3.5f));ring.setColor(p.dark?0x66FFFFFF:0x331C1C1E);
        fill.setColor(p.record);glow.setColor(p.record);setClickable(true);setFocusable(true);
        setBackground(null);Ui.pressable(this);
    }
    void setRecording(boolean value,boolean animate){
        if(recording==value&&animator==null)return;recording=value;if(animator!=null)animator.cancel();
        float target=value?1f:0f;if(!animate){morph=target;invalidate();return;}
        animator=ValueAnimator.ofFloat(morph,target);animator.setDuration(AppTheme.MOTION_SLOW);animator.setInterpolator(new PathInterpolator(0.2f,0f,0f,1f));
        animator.addUpdateListener(a->{morph=(float)a.getAnimatedValue();invalidate();});animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){animator=null;}});animator.start();
    }
    /** Nivel 0..1 del micrófono (ya normalizado). */
    void setLevel(float value){level=value;shownLevel+=(level-shownLevel)*0.35f;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        float cx=getWidth()/2f,cy=getHeight()/2f,outer=Math.min(cx,cy)-dp(12);
        if(recording&&shownLevel>0.02f){glow.setAlpha((int)(60+shownLevel*60));canvas.drawCircle(cx,cy,outer+dp(2)+shownLevel*dp(10),glow);}
        canvas.drawCircle(cx,cy,outer,ring);
        float circle=outer-dp(7),square=outer*0.46f;float half=circle+(square-circle)*morph;float corner=half+(dp(8)-half)*morph;
        rect.set(cx-half,cy-half,cx+half,cy+half);canvas.drawRoundRect(rect,corner,corner,fill);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen","MainActivity","action",recording?"stop":"record");return super.performClick();}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
