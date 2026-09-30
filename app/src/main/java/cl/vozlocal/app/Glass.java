package cl.vozlocal.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;
import android.view.View;
import static cl.vozlocal.app.AppTheme.*;

/**
 * Piezas propias de la estética «Bosque de vidrio» de Verbapp (0.7.0), sin librerías:
 * - {@link Backdrop}: el fondo de cada pantalla, un degradado blanco → verde con una textura de puntitos arriba.
 * - {@link Highlight}: destaca una palabra con un rectángulo menta detrás (como «Simple» en la referencia).
 * - {@link BrandMark}: el logo (barras de onda + destello) que acompaña al nombre «Verbapp».
 * Ver docs/diseno/PROPUESTA-0.7.md.
 */
final class Glass {
    private Glass(){}

    /**
     * Fondo de pantalla completo (pasa por detrás de las barras del sistema).
     * VIVID (Grabar): blanco arriba, verde intenso abajo, como la referencia. SOFT (Biblioteca, Detalle, Ajustes): el
     * verde apenas se insinúa abajo, así las listas largas se leen sobre claro.
     * Arriba lleva una grilla de puntitos que se desvanece; abajo, un brillo suave que da profundidad al verde.
     * El nivel del verde se puede animar (setVivid) para pasar de una pantalla suave a una intensa sin corte.
     */
    static final class Backdrop extends Drawable {
        private final AppTheme.Palette p;private final float density;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),dots=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float vivid;private int shaderHeight=-1;private float shaderVivid=-1f;
        Backdrop(Context c,AppTheme.Palette p,boolean vivid){this.p=p;this.density=c.getResources().getDisplayMetrics().density;this.vivid=vivid?1f:0f;dots.setColor(p.dotGrid);}
        /** 0 = suave, 1 = intenso. */
        void setVivid(float value){value=Math.max(0f,Math.min(1f,value));if(value!=vivid){vivid=value;invalidateSelf();}}
        float vivid(){return vivid;}
        /** Color que queda justo detrás de la barra de navegación del sistema (para elegir el color de sus íconos). */
        int bottomColor(){return blend(p.softBottom,p.gradBottom,vivid);}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();if(b.isEmpty())return;int h=b.height();
            if(shaderHeight!=h||shaderVivid!=vivid){
                shaderHeight=h;shaderVivid=vivid;
                int mid=blend(p.softMid,p.gradMid,vivid),bottom=bottomColor();
                // Intenso: el verde empieza cerca de la mitad (como en la referencia); suave: solo en el último tercio.
                float greenFrom=0.36f+0.28f*(1f-vivid);
                int[] colors={p.gradTop,p.gradTop,mid,blend(mid,bottom,0.55f),bottom};
                float[] stops={0f,0.18f,greenFrom,Math.min(0.97f,greenFrom+0.26f),1f};
                paint.setShader(new LinearGradient(0,b.top,0,b.bottom,colors,stops,Shader.TileMode.CLAMP));
                // Brillo abajo al centro: un poco más claro que el verde, así el fondo no se ve plano.
                int glowColor=withAlpha(p.dark?0xFF3F8C72:0xFF9CC5B5,Math.round(110*vivid));
                glow.setShader(new RadialGradient(b.exactCenterX(),b.bottom-h*0.12f,Math.max(1f,b.width()*0.75f),new int[]{glowColor,withAlpha(glowColor,0)},null,Shader.TileMode.CLAMP));
            }
            canvas.drawRect(b,paint);
            if(vivid>0.01f)canvas.drawRect(b,glow);
            // Grilla de puntitos en la parte alta, desvaneciéndose hacia abajo.
            float step=16*density,r=0.9f*density,until=b.top+h*0.34f;int base=Color.alpha(p.dotGrid);
            for(float y=b.top+step/2f;y<until;y+=step){
                float fade=1f-(y-b.top)/(until-b.top);dots.setAlpha(Math.round(base*fade*fade));
                for(float x=b.left+step/2f;x<b.right;x+=step)canvas.drawCircle(x,y,r,dots);
            }
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }

    /**
     * Palabra destacada: un rectángulo menta redondeado detrás del texto (no cambia el color del texto). La palabra no
     * se corta entre líneas. Usar solo en títulos grandes y con una sola palabra (ver {@link Ui#highlightLast}).
     *
     * Si el TextView corta el título con «…» y la palabra queda dentro de lo cortado, Android 10+ deja de llamar a este
     * span. Android 8 y 9 lo siguen llamando, con el texto ya reemplazado («…» y, después, caracteres de ancho cero): ahí
     * se dibuja solo ese texto, sin recuadro. Antes quedaba un recuadro menta vacío tras el «…», o rodeándolo.
     */
    static final class Highlight extends ReplacementSpan {
        private final int color;private final float padH,radius;private final RectF rect=new RectF();
        Highlight(int color,float padH,float radius){this.color=color;this.padH=padH;this.radius=radius;}
        /** ¿El tramo quedó dentro de los puntos suspensivos del TextView? (Android 8–9: «…» y relleno U+FEFF, sin ancho.) */
        private static boolean cut(CharSequence text,int start,int end){if(end<=start)return true;char first=text.charAt(start);return first=='…'||first=='\uFEFF';}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt fm){
            if(fm!=null){Paint.FontMetricsInt m=paint.getFontMetricsInt();fm.ascent=m.ascent;fm.descent=m.descent;fm.top=m.top;fm.bottom=m.bottom;}
            if(cut(text,start,end))return Math.round(paint.measureText(text,start,end));
            return Math.round(paint.measureText(text,start,end)+2*padH);
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint paint){
            if(cut(text,start,end)){canvas.drawText(text,start,end,x,y,paint);return;}
            float w=paint.measureText(text,start,end);Paint.FontMetrics m=paint.getFontMetrics();
            int old=paint.getColor();
            // El rectángulo abraza la altura de las letras (de la línea de base hacia arriba), no el interlineado.
            rect.set(x,y+m.ascent*0.86f,x+w+2*padH,y+m.descent*0.7f);
            paint.setColor(color);canvas.drawRoundRect(rect,radius,radius,paint);
            paint.setColor(old);canvas.drawText(text,start,end,x+padH,y,paint);
        }
    }

    /**
     * Logo de Verbapp: cuatro barras de onda redondeadas (en tinta) y un destello de cuatro puntas (en verde de marca)
     * arriba a la derecha: «tu voz» + «la IA que la ordena». Se dibuja con Paint, así sirve a cualquier tamaño y tema.
     */
    static final class BrandMark extends View {
        private final Paint bars=new Paint(Paint.ANTI_ALIAS_FLAG),spark=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
        BrandMark(Context c,int barColor,int sparkColor){super(c);bars.setColor(barColor);bars.setStrokeCap(Paint.Cap.ROUND);bars.setStyle(Paint.Style.STROKE);spark.setColor(sparkColor);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),u=Math.min(w,h)/24f;bars.setStrokeWidth(2.2f*u);
            float[] xs={3.5f,8.5f,13.5f,18f},tops={10f,5f,8f,12.5f},bottoms={16f,21f,18f,15f};
            float ox=(w-24*u)/2f,oy=(h-24*u)/2f;
            for(int i=0;i<xs.length;i++)canvas.drawLine(ox+xs[i]*u,oy+tops[i]*u,ox+xs[i]*u,oy+bottoms[i]*u,bars);
            sparkle(path,ox+20.5f*u,oy+4.5f*u,3.6f*u);canvas.drawPath(path,spark);
        }
    }
    /** Destello de cuatro puntas (✦) centrado en (cx, cy) con radio r. */
    static Path sparkle(Path out,float cx,float cy,float r){
        float k=r*0.28f;out.reset();
        out.moveTo(cx,cy-r);out.quadTo(cx+k*0.3f,cy-k*0.3f,cx+r,cy);out.quadTo(cx+k*0.3f,cy+k*0.3f,cx,cy+r);
        out.quadTo(cx-k*0.3f,cy+k*0.3f,cx-r,cy);out.quadTo(cx-k*0.3f,cy-k*0.3f,cx,cy-r);out.close();
        return out;
    }
}
