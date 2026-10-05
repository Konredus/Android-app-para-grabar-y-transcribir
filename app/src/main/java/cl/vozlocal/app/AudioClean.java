package cl.vozlocal.app;

import java.util.Arrays;

/**
 * 0.9.3: limpieza opcional del audio que se envía a transcribir (Ajustes → Ruido de fondo, pedido del usuario para probar
 * de a una). Trabaja sobre el mono de 16 kHz que arma OrAudio, en streaming (el bloque nunca está entero en memoria) y
 * sin cambiar el número de muestras: la muestra i de la salida es la i de la entrada, así los tiempos de la respuesta y
 * de las anclas siguen exactos. La grabación original no se toca: esto va solo a la copia que se envía.
 * - LEVEL («Quitar zumbidos y nivelar el volumen»): un pasa-altos Butterworth de 4.º orden a 100 Hz (zumbido de la red de
 *   50/60 Hz −24/−18 dB, motor, aire acondicionado, golpes al teléfono; la voz casi no se toca: −3 dB en 100 Hz)
 *   y un nivelador lento que lleva la voz hacia −20 dBFS: sube hasta 12 dB a quien habla bajo o lejos y baja hasta 6 dB a
 *   quien habla muy fuerte. Solo se ajusta mientras hay voz; en las pausas el volumen se queda quieto, así el ruido no sube.
 * - NOISE («Reducir el ruido de fondo», experimental): filtro de Wiener por bandas sobre ventanas de 32 ms (FFT de 512, la
 *   mitad de solape, ventanas raíz de Hann: sin cambios, la salida es idéntica a la entrada). El ruido de cada banda es el
 *   mínimo de su potencia suavizada en los últimos ~1,5 s (estadística de mínimos); la ganancia sigue la relación
 *   señal/ruido «a priori» (decision-directed), se suaviza entre bandas vecinas y nunca baja de −12 dB: así no deja «ruido
 *   musical» ni se come la voz. Los tramos de silencio digital (los espacios entre anclas) no cuentan para el ruido.
 * Al final, un limitador suave (techo −1 dBFS) evita recortes. flush() entrega lo que el reductor retiene (256 muestras):
 * se llama antes de cerrar el archivo.
 */
final class AudioClean implements OrAudio.Sink {
    static final int LEVEL=1,NOISE=2;
    private final OrAudio.Sink out;
    private final boolean level,noise;
    private final short[] staged=new short[4096];private int stagedCount;

    // ---------- Pasa-altos: Butterworth de 4.º orden a 100 Hz (dos biquads en cascada, Q 0,541 y 1,307) ----------
    private static final double HP_HZ=100;
    private final double[][] hp=new double[2][];private final double[][] hpState=new double[2][4];

    // ---------- Nivelador ----------
    /** Tramos de 20 ms para medir; objetivo −20 dBFS (RMS); la ganancia va de −6 a +12 dB. */
    private static final int FRAME=OrAudio.RATE/50;
    private static final double TARGET=3277,MIN_GAIN=0.5,MAX_GAIN=4.0;
    /** El piso de ruido baja al tiro y sube 2 dB/s; hay voz si un tramo supera el piso en ~10 dB y los −55 dBFS. */
    private static final double FLOOR_RISE=Math.pow(10,0.2/50),VOICE_OVER_FLOOR=3.0,VOICE_MIN=60;
    /** El nivel de la voz sigue a los tramos con voz en ~1 s; la ganancia sube en ~0,5 s y baja en ~0,1 s. */
    private static final double SPEECH_FOLLOW=0.02,GAIN_UP=1.0/(0.5*OrAudio.RATE),GAIN_DOWN=1.0/(0.1*OrAudio.RATE);
    private double frameSum,floor=-1,speech=-1,gain=1,gainTarget=1;private int inFrame;

    // ---------- Reductor de ruido (STFT) ----------
    private static final int N=512,HOP=N/2,BINS=N/2+1;
    /** Mínimos por bandas: 6 sub-ventanas de 16 cuadros (16 ms cada uno): ~1,5 s en total. */
    private static final int SUBS=6,SUB_FRAMES=16;
    /** Suavizado de la potencia, corrección del sesgo del mínimo, «decision-directed» y atenuación máxima (−12 dB). */
    private static final double SMOOTH=0.8,NOISE_BIAS=1.6,DD=0.98,FLOOR_GAIN=0.25;
    /** Bajo esto (RMS de 2 sobre 32767) el cuadro es silencio digital: no cuenta para estimar el ruido. */
    private static final double SILENT=4;
    private double[] window,cos,sin;private int[] reverse;
    private double[] frame,ola,re,im,power,subMin,lastGain,lastPost,gains;private double[][] mins;
    private int hopFilled,subFrames,frames;private long received,emitted,skip=HOP;

    // ---------- Limitador ----------
    private static final double CEILING=29204,FULL=32767;

    AudioClean(OrAudio.Sink out,int mask){
        this.out=out;level=(mask&LEVEL)!=0;noise=(mask&NOISE)!=0;
        double w0=2*Math.PI*HP_HZ/OrAudio.RATE,cw=Math.cos(w0);
        for(int s=0;s<2;s++){double q=1/(2*Math.cos(Math.PI*(2*s+1)/8)),alpha=Math.sin(w0)/(2*q),a0=1+alpha;hp[s]=new double[]{(1+cw)/2/a0,-(1+cw)/a0,(1+cw)/2/a0,-2*cw/a0,(1-alpha)/a0};}
        if(noise){
            window=new double[N];for(int n=0;n<N;n++)window[n]=Math.sqrt(0.5*(1-Math.cos(2*Math.PI*n/N)));
            cos=new double[N/2];sin=new double[N/2];for(int k=0;k<N/2;k++){cos[k]=Math.cos(2*Math.PI*k/N);sin[k]=-Math.sin(2*Math.PI*k/N);}
            reverse=new int[N];int bits=Integer.numberOfTrailingZeros(N);for(int i=0;i<N;i++)reverse[i]=Integer.reverse(i)>>>(32-bits);
            frame=new double[N];ola=new double[N];re=new double[N];im=new double[N];
            power=new double[BINS];subMin=new double[BINS];lastGain=new double[BINS];lastPost=new double[BINS];gains=new double[BINS];
            mins=new double[SUBS][BINS];for(double[] m:mins)Arrays.fill(m,Double.MAX_VALUE);Arrays.fill(subMin,Double.MAX_VALUE);Arrays.fill(lastGain,1);
        }
    }

    @Override public void write(short[] data,int count)throws Exception{
        for(int i=0;i<count;i++){
            double v=data[i];
            if(level)for(int s=0;s<2;s++){double[] k=hp[s],z=hpState[s];double y=k[0]*v+k[1]*z[0]+k[2]*z[1]-k[3]*z[2]-k[4]*z[3];z[1]=z[0];z[0]=v;z[3]=z[2];z[2]=y;v=y;}
            if(noise){received++;denoise(v);}else finish(v);
        }
        forward();
    }
    /** Entrega lo que el reductor de ruido todavía retiene (rellena con silencio), sin pasarse de las muestras recibidas. */
    void flush()throws Exception{
        if(noise)while(emitted<received)denoise(0);
        forward();
    }

    // ---------- Reductor de ruido ----------
    private void denoise(double v)throws Exception{
        frame[N-HOP+hopFilled++]=v;
        if(hopFilled<HOP)return;
        hopFilled=0;process();
        // La salida va HOP muestras atrasada (el solape): las primeras HOP son el relleno inicial y se descartan.
        for(int n=0;n<HOP;n++){
            if(skip>0){skip--;continue;}
            if(emitted<received){finish(ola[n]);emitted++;}
        }
        System.arraycopy(ola,HOP,ola,0,N-HOP);Arrays.fill(ola,N-HOP,N,0);
        System.arraycopy(frame,HOP,frame,0,N-HOP);
    }
    private void process(){
        double energy=0;
        for(int n=0;n<N;n++){double v=frame[n];energy+=v*v;re[n]=v*window[n];im[n]=0;}
        fft(re,im,false);
        // El primer cuadro trae el relleno inicial; el silencio digital no dice nada del ruido.
        boolean track=frames>0&&energy/N>=SILENT;
        for(int k=0;k<BINS;k++){
            double p=re[k]*re[k]+im[k]*im[k];
            power[k]=frames==0?p:SMOOTH*power[k]+(1-SMOOTH)*p;
            if(track)subMin[k]=Math.min(subMin[k],power[k]);
            double floorPower=subMin[k];for(int u=0;u<SUBS;u++)floorPower=Math.min(floorPower,mins[u][k]);
            if(floorPower==Double.MAX_VALUE){gains[k]=1;lastPost[k]=0;continue;}
            double post=p/(floorPower*NOISE_BIAS+1e-9);
            double prior=DD*lastGain[k]*lastGain[k]*lastPost[k]+(1-DD)*Math.max(post-1,0);
            gains[k]=Math.max(FLOOR_GAIN,prior/(1+prior));lastPost[k]=post;
        }
        if(track&&++subFrames==SUB_FRAMES){
            subFrames=0;double[] oldest=mins[SUBS-1];System.arraycopy(mins,0,mins,1,SUBS-1);mins[0]=oldest;
            System.arraycopy(subMin,0,mins[0],0,BINS);Arrays.fill(subMin,Double.MAX_VALUE);
        }
        frames++;
        for(int k=0;k<BINS;k++){
            // Bandas vecinas suavizadas (¼ ½ ¼): menos «ruido musical».
            double g=k==0||k==BINS-1?gains[k]:0.25*gains[k-1]+0.5*gains[k]+0.25*gains[k+1];
            lastGain[k]=gains[k];
            re[k]*=g;im[k]*=g;
            if(k>0&&k<N/2){re[N-k]*=g;im[N-k]*=g;}
        }
        fft(re,im,true);
        for(int n=0;n<N;n++)ola[n]+=re[n]*window[n];
    }
    /** FFT compleja en el lugar (radix 2). inverse: la inversa, ya dividida por N. */
    private void fft(double[] r,double[] m,boolean inverse){
        for(int i=0;i<N;i++){int j=reverse[i];if(j>i){double t=r[i];r[i]=r[j];r[j]=t;t=m[i];m[i]=m[j];m[j]=t;}}
        for(int size=2;size<=N;size<<=1){
            int half=size>>1,step=N/size;
            for(int start=0;start<N;start+=size)for(int k=0;k<half;k++){
                double c=cos[k*step],s=inverse?-sin[k*step]:sin[k*step];
                int a=start+k,b=a+half;double tr=r[b]*c-m[b]*s,ti=r[b]*s+m[b]*c;
                r[b]=r[a]-tr;m[b]=m[a]-ti;r[a]+=tr;m[a]+=ti;
            }
        }
        if(inverse)for(int i=0;i<N;i++){r[i]/=N;m[i]/=N;}
    }

    // ---------- Nivelador y limitador ----------
    private void finish(double v)throws Exception{
        if(level){
            frameSum+=v*v;
            if(++inFrame==FRAME){measure(Math.sqrt(frameSum/FRAME));frameSum=0;inFrame=0;}
            gain+=(gainTarget-gain)*(gainTarget>gain?GAIN_UP:GAIN_DOWN);
            v*=gain;
        }
        double a=Math.abs(v);
        if(a>CEILING)a=CEILING+(FULL-CEILING)*Math.tanh((a-CEILING)/(FULL-CEILING));
        staged[stagedCount++]=(short)Math.max(-32768,Math.min(32767,Math.round(Math.copySign(a,v))));
        if(stagedCount==staged.length)forward();
    }
    private void measure(double rms){
        floor=floor<0?rms:Math.min(floor*FLOOR_RISE,rms);
        if(rms>Math.max(floor*VOICE_OVER_FLOOR,VOICE_MIN)){
            speech=speech<0?rms:speech+SPEECH_FOLLOW*(rms-speech);
            gainTarget=Math.max(MIN_GAIN,Math.min(MAX_GAIN,TARGET/Math.max(1,speech)));
        }
    }
    private void forward()throws Exception{if(stagedCount>0){out.write(staged,stagedCount);stagedCount=0;}}
}
