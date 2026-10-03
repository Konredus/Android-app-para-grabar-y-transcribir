package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/**
 * Selector de tramo con dos manijas (inicio / final) para recortar un audio sin escribir segundos.
 * 0.7.0 (Verbapp): el riel es una regla de marcas finas en gris (p.waveIdle); el tramo elegido se vuelve verde de marca,
 * más alto y sobre una banda menta. Las manijas son píldoras verticales verdes con borde claro y un halo mientras se
 * arrastran. Las marcas son parejas, una regla de tiempo: no dibujan el audio (no inventan actividad).
 * Todo sale de los roles de AppTheme (modo oscuro incluido). Los campos numéricos de la pantalla siguen disponibles para
 * el ajuste preciso y para los lectores de pantalla.
 */
final class RangeView extends View {
    interface Listener{void changed(long from,long to);}
    private final Paint mark=new Paint(Paint.ANTI_ALIAS_FLAG),band=new Paint(Paint.ANTI_ALIAS_FLAG),thumb=new Paint(Paint.ANTI_ALIAS_FLAG),border=new Paint(Paint.ANTI_ALIAS_FLAG),halo=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int idle,selected;
    private long duration=1,from,to=1;private int dragging=-1;private Listener listener;
    static final long MIN_GAP=500;

    RangeView(Context c,AppTheme.Palette p){
        super(c);idle=p.waveIdle;selected=p.brand;
        band.setColor(p.primaryContainer);thumb.setColor(p.brand);
        border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(2.5f));border.setColor(p.surfaceContainerLowest);
        halo.setColor(p.brand);halo.setAlpha(0x33);
        setMinimumHeight((int)dp(48));
    }
    void setListener(Listener l){listener=l;}
    void set(long duration,long from,long to){this.duration=Math.max(1,duration);this.from=clamp(from,0,this.duration);this.to=clamp(to,this.from,this.duration);describe();invalidate();}
    /** Margen a cada lado: deja espacio para el halo de la manija en los extremos. */
    private float pad(){return dp(20);}
    private float x(long ms){return pad()+(getWidth()-2*pad())*ms/(float)duration;}
    private long ms(float x){return clamp((long)((x-pad())/(getWidth()-2*pad())*duration),0,duration);}
    private static long clamp(long v,long lo,long hi){return Math.max(lo,Math.min(hi,v));}
    @Override protected void onDraw(Canvas canvas){
        float mid=getHeight()/2f,a=x(from),b=x(to),w=dp(2),step=dp(5);
        // Banda menta detrás del tramo elegido.
        float bh=dp(30);canvas.drawRoundRect(a,mid-bh/2f,b,mid+bh/2f,dp(10),dp(10),band);
        // Regla de marcas: grises fuera del tramo; verdes y más altas dentro.
        for(float x=pad();x<=getWidth()-pad()+0.5f;x+=step){
            boolean in=x>=a&&x<=b;float h=in?dp(16):dp(10);
            mark.setColor(in?selected:idle);canvas.drawRoundRect(x-w/2f,mid-h/2f,x+w/2f,mid+h/2f,w/2f,w/2f,mark);
        }
        drawThumb(canvas,a,mid,dragging==0);drawThumb(canvas,b,mid,dragging==1);
    }
    /** Manija: píldora vertical verde con borde claro; mientras se arrastra, un halo la agranda (más fácil de ver bajo el dedo). */
    private void drawThumb(Canvas canvas,float cx,float cy,boolean active){
        if(active)canvas.drawCircle(cx,cy,dp(20),halo);
        float hw=dp(5),hh=dp(16);canvas.drawRoundRect(cx-hw,cy-hh,cx+hw,cy+hh,hw,hw,thumb);canvas.drawRoundRect(cx-hw,cy-hh,cx+hw,cy+hh,hw,hw,border);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float ex=e.getX();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:dragging=Math.abs(ex-x(from))<=Math.abs(ex-x(to))?0:1;if(from==to)dragging=ex<x(from)?0:1;getParent().requestDisallowInterceptTouchEvent(true);move(ex);return true;
            case MotionEvent.ACTION_MOVE:move(ex);return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:dragging=-1;invalidate();performClick();return true;
        }
        return super.onTouchEvent(e);
    }
    @Override public boolean performClick(){return super.performClick();}
    private void move(float ex){long v=ms(ex);if(dragging==0)from=clamp(v,0,Math.max(0,to-MIN_GAP));else if(dragging==1)to=clamp(v,Math.min(duration,from+MIN_GAP),duration);describe();invalidate();if(listener!=null)listener.changed(from,to);}
    /** Se llama en cada movimiento: el contexto es la pantalla (ya en el idioma de la app), sin consultar el idioma cada vez. */
    private void describe(){setContentDescription(getContext().getString(R.string.marks_range_desc,Recording.time(from),Recording.time(to)));}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
}
