package cl.vozlocal.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;

/**
 * Botón de grabar de Verbapp (0.7.0): círculo verde de marca (~80 dp) con el micrófono blanco y un brillo suave arriba
 * a la izquierda (como el ícono de la app), rodeado por dos anillos de halo que respiran lento en reposo. Solo respiran
 * si Android permite animaciones y el botón está a la vista (así no gasta batería detrás de otra pantalla).
 * Al empezar a grabar, el micrófono se transforma en ■ (convención de grabadoras: «círculo = grabar, cuadrado =
 * detener») mientras Grabar pasa a la vista de grabación. La vista deja espacio alrededor del círculo para el halo.
 */
final class RecordButton extends View {
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),halo=new Paint(Paint.ANTI_ALIAS_FLAG),glyph=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mic;private final int brand,light,deep;
    /** morph: 0 = círculo con micrófono, 1 = cuadrado con ■. breath: 0..1, fase de la respiración del halo. */
    private float morph,level,shownLevel,breath=0.5f;private boolean recording,shown;private ValueAnimator animator,breathing;
    private final RectF rect=new RectF(),box=new RectF();

    RecordButton(Context c,AppTheme.Palette p){
        super(c);brand=p.brand;deep=p.brandDeep;light=AppTheme.blend(p.brand,p.onBrand,0.16f);
        fill.setColor(p.brand);halo.setColor(p.brand);glyph.setColor(p.onBrand);
        mic=c.getDrawable(R.drawable.ic_mic_fill).mutate();mic.setTint(p.onBrand);
        setClickable(true);setFocusable(true);setBackground(null);Ui.pressable(this);
        // Sombra suave que sigue la forma (círculo → cuadrado redondeado), en verde muy oscuro en vez de gris.
        setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){shape(box);o.setRoundRect(Math.round(box.left),Math.round(box.top),Math.round(box.right),Math.round(box.bottom),corner(box));}});
        setElevation(p.dark?0:dp(6));if(Build.VERSION.SDK_INT>=28&&!p.dark){setOutlineSpotShadowColor(p.shadow);setOutlineAmbientShadowColor(p.shadow);}
    }
    void setRecording(boolean value,boolean animate){
        if(recording==value&&animator==null)return;recording=value;if(animator!=null)animator.cancel();syncBreath();
        float target=value?1f:0f;if(!animate||!AppTheme.motion()){morph=target;invalidate();invalidateOutline();return;}
        animator=ValueAnimator.ofFloat(morph,target);animator.setDuration(AppTheme.MOTION_SLOW);animator.setInterpolator(AppTheme.EMPHASIZED);
        animator.addUpdateListener(a->{morph=(float)a.getAnimatedValue();invalidate();invalidateOutline();});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){animator=null;}});animator.start();
    }
    /** Nivel 0..1 del micrófono (ya normalizado): al grabar, el halo sigue el volumen real. */
    void setLevel(float value){level=value;float next=shownLevel+(level-shownLevel)*0.35f;if(Math.abs(next-shownLevel)>0.004f){shownLevel=next;invalidate();}}

    // ---------- Respiración del halo ----------
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncBreath();}
    @Override protected void onDetachedFromWindow(){shown=false;syncBreath();super.onDetachedFromWindow();}
    @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);shown=visible;syncBreath();}
    /** Respira solo en reposo, a la vista y con animaciones; si no, el halo queda quieto a medio camino. */
    private void syncBreath(){
        boolean want=shown&&isAttachedToWindow()&&!recording&&AppTheme.motion();
        if(want&&breathing==null){
            breathing=ValueAnimator.ofFloat(0f,1f);breathing.setDuration(2600);breathing.setRepeatMode(ValueAnimator.REVERSE);breathing.setRepeatCount(ValueAnimator.INFINITE);breathing.setInterpolator(new AccelerateDecelerateInterpolator());
            // Se redibuja por pasos de 1/48: el movimiento se ve continuo y la pantalla no se repinta en cada cuadro.
            breathing.addUpdateListener(a->{float b=Math.round((float)a.getAnimatedValue()*48f)/48f;if(b!=breath){breath=b;invalidate();}});
            breathing.start();
        }else if(!want&&breathing!=null){breathing.cancel();breathing=null;breath=0.5f;invalidate();}
    }

    // ---------- Dibujo ----------
    /** El círculo: hasta 80 dp, centrado; lo que sobra alrededor es para el halo. */
    private void shape(RectF out){float cx=getWidth()/2f,cy=getHeight()/2f,half=Math.max(dp(20),Math.min(dp(40),Math.min(cx,cy)-dp(30)));out.set(cx-half,cy-half,cx+half,cy+half);}
    private float corner(RectF s){float half=s.width()/2f;return half+(Math.min(half,dp(24))-half)*morph;}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        super.onSizeChanged(w,h,ow,oh);invalidateOutline();
        // Brillo de arriba a la izquierda hacia el verde profundo abajo a la derecha (volumen, como el ícono).
        shape(rect);fill.setShader(new LinearGradient(rect.left,rect.top,rect.right,rect.bottom,new int[]{light,brand,AppTheme.blend(brand,deep,0.35f)},new float[]{0f,0.5f,1f},Shader.TileMode.CLAMP));
    }
    @Override protected void onDraw(Canvas canvas){
        shape(rect);float cx=rect.centerX(),cy=rect.centerY(),r=rect.width()/2f,corner=corner(rect);
        float room=Math.min(getWidth(),getHeight())/2f-r;
        if(!recording||morph<1f){
            // Dos anillos (discos translúcidos) que respiran en fases opuestas; se apagan al transformarse en ■.
            float fade=1f-morph,outer=r+room*(0.8f+0.14f*breath),inner=r+room*(0.42f+0.08f*(1f-breath));
            halo.setAlpha(Math.round((20+10*breath)*fade));canvas.drawCircle(cx,cy,outer,halo);
            halo.setAlpha(Math.round((38+8*(1f-breath))*fade));canvas.drawCircle(cx,cy,inner,halo);
        }
        if(recording&&shownLevel>0.02f){float grow=dp(4)+shownLevel*dp(10);halo.setAlpha((int)(45+shownLevel*60));box.set(rect);box.inset(-grow,-grow);canvas.drawRoundRect(box,corner+grow,corner+grow,halo);}
        canvas.drawRoundRect(rect,corner,corner,fill);
        // Ícono: micrófono en reposo, ■ al grabar (se funden durante la transformación).
        int size=Math.round(Math.min(dp(32),r*0.8f));mic.setBounds(Math.round(cx-size/2f),Math.round(cy-size/2f),Math.round(cx+size/2f),Math.round(cy+size/2f));mic.setAlpha((int)(255*(1f-morph)));if(morph<1f)mic.draw(canvas);
        if(morph>0f){float s=r*0.32f;glyph.setAlpha((int)(255*morph));canvas.drawRoundRect(cx-s,cy-s,cx+s,cy+s,dp(4),dp(4),glyph);}
    }
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.6f);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    @Override public boolean performClick(){Diagnostics.event("ui_action",null,"screen","MainActivity","action",recording?"stop":"record");return super.performClick();}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
