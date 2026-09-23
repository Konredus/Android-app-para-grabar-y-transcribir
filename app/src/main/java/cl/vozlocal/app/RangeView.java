package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/**
 * Selector de tramo con dos manijas (inicio / final) para recortar un audio sin escribir segundos.
 * Los campos numéricos de la pantalla siguen disponibles para ajuste preciso y lectores de pantalla.
 */
final class RangeView extends View {
    interface Listener{void changed(long from,long to);}
    private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG),selected=new Paint(Paint.ANTI_ALIAS_FLAG),thumb=new Paint(Paint.ANTI_ALIAS_FLAG),border=new Paint(Paint.ANTI_ALIAS_FLAG);
    private long duration=1,from,to=1;private int dragging=-1;private Listener listener;
    static final long MIN_GAP=500;

    RangeView(Context c,AppTheme.Palette p){
        super(c);track.setColor(p.fill);selected.setColor(p.accent);thumb.setColor(0xFFFFFFFF);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(2));border.setColor(p.accent);
        setMinimumHeight((int)dp(48));
    }
    void setListener(Listener l){listener=l;}
    void set(long duration,long from,long to){this.duration=Math.max(1,duration);this.from=clamp(from,0,this.duration);this.to=clamp(to,this.from,this.duration);describe();invalidate();}
    private float pad(){return dp(14);}
    private float x(long ms){return pad()+(getWidth()-2*pad())*ms/(float)duration;}
    private long ms(float x){return clamp((long)((x-pad())/(getWidth()-2*pad())*duration),0,duration);}
    private static long clamp(long v,long lo,long hi){return Math.max(lo,Math.min(hi,v));}
    @Override protected void onDraw(Canvas canvas){
        float mid=getHeight()/2f,h=dp(6);
        canvas.drawRoundRect(pad(),mid-h/2,getWidth()-pad(),mid+h/2,h/2,h/2,track);
        canvas.drawRoundRect(x(from),mid-h/2,x(to),mid+h/2,h/2,h/2,selected);
        drawThumb(canvas,x(from),mid);drawThumb(canvas,x(to),mid);
    }
    private void drawThumb(Canvas canvas,float cx,float cy){canvas.drawCircle(cx,cy,dp(12),thumb);canvas.drawCircle(cx,cy,dp(12),border);}
    @Override public boolean onTouchEvent(MotionEvent e){
        float ex=e.getX();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:dragging=Math.abs(ex-x(from))<=Math.abs(ex-x(to))?0:1;if(from==to)dragging=ex<x(from)?0:1;getParent().requestDisallowInterceptTouchEvent(true);move(ex);return true;
            case MotionEvent.ACTION_MOVE:move(ex);return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:dragging=-1;performClick();return true;
        }
        return super.onTouchEvent(e);
    }
    @Override public boolean performClick(){return super.performClick();}
    private void move(float ex){long v=ms(ex);if(dragging==0)from=clamp(v,0,Math.max(0,to-MIN_GAP));else if(dragging==1)to=clamp(v,Math.min(duration,from+MIN_GAP),duration);describe();invalidate();if(listener!=null)listener.changed(from,to);}
    private void describe(){setContentDescription("Tramo seleccionado desde "+Recording.time(from)+" hasta "+Recording.time(to)+". Usa los campos de segundos para ajustarlo con precisión.");}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
