package cl.vozlocal.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Audio para OpenRouter (0.8.0): convierte un bloque (AAC/.m4a u otro formato que Android decodifique) a mono de
 * 16 kHz y 16 bits, en FLAC (o WAV si el teléfono no tiene codificador FLAC), que aceptan todos los modelos que
 * separan voces. Opcionalmente antepone «anclas»: clips de voces conocidas separados por 1 s de silencio, para que el
 * modelo las separe como hablantes y la app sepa quién es quién por el momento en que suena cada una.
 *
 * Cómo está hecho (ver docs/diseno/SPEC-0.8.md, parte «audio»):
 * - Todo pasa en streaming: decodificador → mezcla a mono → remuestreo con filtro → codificador. El bloque nunca está
 *   entero en memoria; solo las anclas (clips de segundos) se guardan un momento para medir su volumen.
 * - Los tiempos (anclas, leadMs, durationMs) salen de CONTAR las muestras escritas, no de la duración que declara el
 *   contenedor: el AAC agrega relleno y esa cifra no es exacta.
 * - El FLAC sale del codificador del teléfono y se comprueba recorriendo sus cuadros (cabecera, CRC de cada cuadro y
 *   total de muestras): si no cuadra con lo que se le entregó, o falla cualquier paso, el bloque se rehace en WAV. El
 *   envío nunca queda con un audio dudoso.
 * - Archivo de salida: [ancla 1][1 s de silencio][ancla 2][1 s]…[audio]. Un WAV pesa 32 kB por segundo (1,92 MB por
 *   minuto); el FLAC, más o menos la mitad con voz.
 *
 * Contrato de la fase 0: build(audio, anchors, outBase, cancel) y Built. Agregados de esta parte: build(…, flac) para
 * forzar WAV, decode(…) (cualquier audio → mono 16 kHz por partes), Sink, ANCHOR_MIN_MS/ANCHOR_MAX_MS y flacIssue.
 * Segunda ronda: TooShort y NoTrack (errores que reintentar no arregla) y diskFull (disco lleno según la causa del error).
 * Tercera ronda (SPEC-0.8c, decisión 1): la conversión era lenta (un bloque de 4 min tardaba 31–43 s con la app abierta y
 * 150 s en segundo plano). Lo que más pesaba no era el filtro sino la espera entre el programa y el decodificador: el
 * lazo esperaba hasta 2 ms por cada cuadro AAC (unos 10 mil en 4 min) y, en segundo plano, Android alarga esas esperas.
 * Ahora el decodificador trabaja en tubería (se le entregan todos los búferes libres y se recoge todo lo listo, y solo se
 * espera cuando no hay nada que hacer), el filtro usa menos coeficientes (16 cruces por lado, corte en 7,28 kHz), 48 y
 * 32 kHz tienen un camino directo (decimación entera ×3 y ×2, con la mitad de las multiplicaciones), el FLAC se comprueba
 * sin decodificarlo entero y el avance se informa en % (HttpApi.prepared) para la etapa «Preparando audio».
 * Revisión r3: «el códec dejó de avanzar» se mide por espera real (Stall), no por reloj: una app congelada no lo dispara.
 * Deja en Diagnostics «or_audio» (formato, duración, bytes, anclas usadas, tiempo) y «or_audio_fallback» (por qué no hubo FLAC).
 */
final class OrAudio {
    /** Frecuencia de salida (Hz), mono, 16 bits. */
    static final int RATE=16000;
    /** Silencio entre anclas y antes del audio (ms). */
    static final long GAP_MS=1000;
    /** Un ancla más corta que esto no le sirve al modelo para reconocer una voz (se omite); más larga, se corta aquí. */
    static final long ANCHOR_MIN_MS=500,ANCHOR_MAX_MS=15_000;
    /** Por qué el último intento de FLAC terminó en WAV (texto técnico, sin datos del usuario); null si nunca pasó. Lo leen las pruebas. */
    static volatile String flacIssue;
    /** Si el FLAC falló una vez en este teléfono, no se insiste durante el resto del proceso: cada intento fallido cuesta convertir el bloque dos veces. */
    private static volatile boolean flacOff;

    // Filtro del remuestreo: sinc con ventana de Kaiser. El corte queda justo bajo la mitad de la frecuencia más baja
    // (7,28 kHz al bajar a 16 kHz) y la ventana atenúa unos 70 dB: lo que quedaría «doblado» dentro de la voz (aliasing)
    // desaparece. Para voz bastan 16 cruces por lado (0.8.0, tercera ronda; antes 24): 90 coeficientes a 44,1 kHz en vez
    // de 134. La transición se ensancha hacia los 8,3 kHz, donde la voz ya no tiene nada que el modelo necesite.
    private static final int HALF_TAPS=16,MAX_PHASES=2048;
    private static final double CUTOFF=0.455,BETA=6.76;
    /** Espera máxima por el decodificador cuando no hay nada que hacer (µs). No es un retardo: vuelve apenas haya salida. */
    private static final long CODEC_WAIT_US=10_000;
    /** Anclas: tramos de 20 ms para medir el volumen, subida máxima (×8 ≈ 18 dB), techo de pico (−1 dBFS) y entrada/salida suave de 5 ms. */
    private static final int FRAME=RATE/50,FADE=RATE/200;
    private static final double MAX_BOOST=8,CEILING=29204;
    /** El volumen del audio se mide en sus primeros 20 s: basta para comparar y cuesta una fracción de segundo. */
    private static final long LEVEL_MS=20_000;
    /** Un códec que en este tiempo de espera REAL no toma ni entrega nada se da por trabado (ver Stall). */
    private static final long STALL_MS=30_000;

    /**
     * Cuánto se ha esperado de verdad a un códec sin que tome ni entregue nada (revisión r3). Antes se medía con el reloj
     * desde la última salida, y una app congelada 30 s o más (el congelador de vivo y las pausas de Android, ver
     * docs/PENDIENTES.md) se leía como un códec trabado: el bloque fallaba y, en el FLAC, se apagaba para los siguientes.
     * Ahora solo suman las esperas con plazo que vuelven vacías, cada una con lo que duró pero nunca más que su plazo más
     * SLACK_NS: en segundo plano Android alarga los temporizadores hasta 40 ms y una espera de 10 ms dura de verdad 20–50 ms
     * (contarla como 10 llevaba un códec trabado a fallar recién tras 60–150 s). Un congelamiento en medio de una espera
     * cuenta a lo más 60 ms, y fuera de las esperas no cuenta nada. Cualquier entrada entregada o salida recogida vuelve la
     * cuenta a cero. Un códec trabado de verdad falla tras unos STALL_MS de espera real, con la app abierta o en segundo
     * plano. Sin dormir nada: no frena la tubería.
     */
    static final class Stall{
        /** Holgura sobre el plazo de cada espera: cubre los 40 ms que Android alarga los temporizadores en segundo plano. */
        private static final long SLACK_NS=50_000_000L;
        private final long limitNs;private long idleNs;
        Stall(long limitMs){limitNs=limitMs*1_000_000L;}
        /** El códec tomó o entregó algo. */
        void moved(){idleNs=0;}
        /** Una espera de hasta «timeoutUs» volvió vacía tras «tookNs» de reloj (sin plazo no cuenta). Devuelve over(). */
        boolean waited(long timeoutUs,long tookNs){if(timeoutUs>0)idleNs+=Math.max(0,Math.min(tookNs,timeoutUs*1000+SLACK_NS));return over();}
        boolean over(){return idleNs>limitNs;}
    }

    /** Resultado de build(). */
    static final class Built{
        /** Archivo listo para enviar. */
        final File file;
        /** "flac" o "wav": el valor de input_audio.format. */
        final String format;
        /** Milisegundos que ocupan las anclas y sus silencios al comienzo: se restan de los tiempos de la respuesta. 0 sin anclas. */
        final long leadMs;
        /** Por cada ancla, en el mismo orden en que se pidieron: {inicio ms, fin ms} dentro del archivo. */
        final long[][] anchors;
        /** Duración total del archivo (ms), con anclas. */
        final long durationMs;
        Built(File file,String format,long leadMs,long[][] anchors,long durationMs){this.file=file;this.format=format;this.leadMs=leadMs;this.anchors=anchors;this.durationMs=durationMs;}
    }
    /** Destino de PCM mono de 16 kHz y 16 bits. Debe consumir los datos antes de volver: el arreglo se reutiliza. */
    interface Sink{void write(short[] data,int count)throws Exception;}
    /** Archivo de salida: finish() lo cierra y devuelve las muestras que de verdad quedaron en él. */
    private interface Out extends Sink{long finish()throws Exception;void release();}
    /** El FLAC no se pudo hacer o no pasó la comprobación: el bloque se rehace en WAV. */
    private static final class FlacFailed extends IOException{FlacFailed(String message,Throwable cause){super(message,cause);}}
    /** 0.8.0: el audio dura menos de una décima de segundo (no hay nada que transcribir). El cliente lo trata como silencio. */
    static final class TooShort extends IOException{TooShort(){super("Audio too short");}}
    /** 0.8.0: el archivo no tiene una pista de audio. Reintentar no lo arregla: el cliente se lo dice al usuario. */
    static final class NoTrack extends IOException{NoTrack(){super("No audio track");}}
    /** Lo que se le dice a la persona con el disco lleno, en el idioma de la app. */
    static String noSpace(){return Lang.str(R.string.eng_err_no_space);}
    /** Disco lleno de verdad (ENOSPC en la cadena de causas), no «poco espacio libre medido después de limpiar». */
    static boolean diskFull(Throwable e){
        for(Throwable t=e;t!=null;t=t.getCause()){
            if(t instanceof android.system.ErrnoException&&((android.system.ErrnoException)t).errno==android.system.OsConstants.ENOSPC)return true;
            String m=t.getMessage();if(m!=null&&(m.contains("ENOSPC")||m.contains("No space left")))return true;
        }
        return false;
    }

    /**
     * Arma el archivo para enviar.
     * @param audio   el bloque a transcribir.
     * @param anchors clips de voz a anteponer, en orden (puede ser null o vacío). Un clip que no se pueda leer se omite
     *                y su entrada en Built.anchors queda {-1,-1}.
     * @param outBase ruta de salida SIN extensión (se agrega ".flac" o ".wav"); se escribe a ".tmp" y se renombra.
     * @param cancel  para cancelar (cancel.check()) durante la conversión; puede ser null.
     */
    static Built build(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception{return build(audio,anchors,outBase,cancel,!flacOff);}
    /** Igual, eligiendo el formato: flac=false va directo a WAV (p. ej. si un proveedor rechazara el FLAC); con true, el WAV sigue siendo el respaldo. */
    static Built build(File audio,List<File> anchors,File outBase,HttpApi cancel,boolean flac)throws Exception{
        if(audio==null||!audio.isFile()||audio.length()==0)throw new IOException("Audio not found");
        alive(cancel);long started=SystemClock.elapsedRealtime();
        File tmp=new File(outBase.getPath()+".tmp"),dir=tmp.getAbsoluteFile().getParentFile();if(dir!=null)dir.mkdirs();
        // Los originales no se tocan: si la salida cayera sobre el audio o sobre una muestra de voz, se pisarían al guardar.
        for(String ext:new String[]{".tmp",".flac",".wav"}){
            File mine=new File(outBase.getPath()+ext).getCanonicalFile();boolean clash=mine.equals(audio.getCanonicalFile());
            if(anchors!=null)for(File anchor:anchors)clash|=anchor!=null&&mine.equals(anchor.getCanonicalFile());
            if(clash)throw new IOException("Source must be preserved");
        }
        try{
            if(flac)try{return write(audio,anchors,outBase,tmp,cancel,true,started);}
                catch(FlacFailed e){
                    // Disco lleno no es culpa del codificador: no se apaga el FLAC ni se intenta el WAV (pesa el doble). Se decide
                    // por la causa del error: medir el espacio libre aquí no sirve, porque write() ya borró el .tmp a medias.
                    if(diskFull(e)){Diagnostics.event("or_audio_fallback",cancel==null?null:cancel.jobId,"reason","disk_full");throw new HttpApi.UserAction(noSpace());}
                    Throwable cause=e.getCause();flacIssue=e.getMessage()+(cause==null?"":" · "+cause.getClass().getSimpleName()+": "+cause.getMessage());
                    flacOff=true;
                    Diagnostics.event("or_audio_fallback",cancel==null?null:cancel.jobId,"format","wav","reason",HttpApi.safeReason(e),"error_class",cause==null?"":cause.getClass().getSimpleName());
                }
            return write(audio,anchors,outBase,tmp,cancel,false,started);
        }catch(InterruptedIOException e){throw e;}
        catch(IOException e){if(!diskFull(e))throw e;}
        // Reintentar solo no arregla la falta de espacio: se le pide al usuario, en vez de gastar los cinco intentos.
        throw new HttpApi.UserAction(noSpace());
    }

    private static Built write(File audio,List<File> anchors,File outBase,File tmp,HttpApi cancel,boolean flac,long started)throws Exception{
        int asked=anchors==null?0:anchors.size(),used=0;long[][] marks=new long[asked][];Out out=null;boolean ok=false;
        try{
            // Referencia de volumen para las anclas: se mide antes de abrir la salida, porque en el archivo van primero.
            double reference=asked>0?level(audio,cancel):0;
            out=flac?new FlacOut(tmp,cancel):new WavOut(tmp);
            long lead=0;short[] quiet=new short[4096];final long gap=GAP_MS*RATE/1000;
            for(int a=0;a<asked;a++){
                marks[a]=new long[]{-1,-1};short[] clip=anchor(anchors.get(a),reference,cancel);if(clip==null)continue;
                out.write(clip,clip.length);for(long left=gap;left>0;left-=quiet.length)out.write(quiet,(int)Math.min(left,quiet.length));
                // Exactos: cada ancla mide un número entero de milisegundos (ver anchor()) y el silencio también.
                marks[a][0]=lead*1000/RATE;marks[a][1]=(lead+clip.length)*1000/RATE;lead+=clip.length+gap;used++;
            }
            // El avance de la etapa «Preparando audio» sale de cuánto del bloque ya se leyó (las anclas son segundos, no cuentan).
            // Llega hasta 99: el 100 se dice solo cuando el archivo quedó listo y comprobado.
            Advance advance=cancel==null?null:(doneUs,totalUs)->{if(totalUs>0)cancel.prepared((int)Math.min(99,doneUs*100/totalUs));};
            long body=decode(audio,Long.MAX_VALUE,cancel,out,advance);if(body<RATE/10)throw new TooShort();
            long total=out.finish();alive(cancel);
            String format=flac?"flac":"wav";File target=new File(outBase.getPath()+"."+format);
            target.delete();if(!tmp.renameTo(target))throw new IOException("Could not save converted audio");ok=true;
            // Si quedó el otro formato de un intento anterior, se quita: que nadie envíe un archivo viejo por error.
            new File(outBase.getPath()+(flac?".wav":".flac")).delete();
            long durationMs=(total*1000+RATE/2)/RATE;
            Diagnostics.event("or_audio",cancel==null?null:cancel.jobId,"format",format,"duration_ms",durationMs,"bytes",target.length(),"anchors",used,"elapsed_ms",SystemClock.elapsedRealtime()-started);
            if(cancel!=null)cancel.prepared(100);
            return new Built(target,format,lead*1000/RATE,marks,durationMs);
        }finally{if(out!=null)out.release();if(!ok)tmp.delete();}
    }

    // ---------- Anclas ----------
    /** Volumen del habla al comienzo del audio (RMS de los tramos con sonido); 0 si no se pudo medir. */
    private static double level(File audio,HttpApi cancel)throws InterruptedIOException{
        Meter meter=new Meter();
        try{decode(audio,LEVEL_MS*RATE/1000,cancel,meter);}catch(InterruptedIOException e){throw e;}catch(Exception e){return 0;}
        return meter.active();
    }
    /**
     * Un ancla lista para escribir, o null si no se puede leer o es demasiado corta (el bloque se envía sin ella).
     * Volumen: si suena más bajo que el audio, se sube hasta igualarlo (máximo ×8) sin que ningún pico llegue a
     * recortarse; nunca se baja. Así el modelo no la pasa por alto por venir de una grabación más débil.
     */
    private static short[] anchor(File file,double reference,HttpApi cancel)throws InterruptedIOException{
        if(file==null||!file.isFile())return null;
        Clip clip=new Clip();
        try{decode(file,ANCHOR_MAX_MS*RATE/1000,cancel,clip);}catch(InterruptedIOException e){throw e;}catch(Exception e){return null;}
        // Se recorta a milisegundos enteros (16 muestras) para que los tiempos que recibe el motor sean exactos.
        int n=clip.count-clip.count%(RATE/1000);if(n<ANCHOR_MIN_MS*RATE/1000)return null;
        Meter meter=new Meter();meter.write(clip.data,n);double level=meter.active(),gain=1;
        if(level>0&&reference>level)gain=Math.min(MAX_BOOST,reference/level);
        if(meter.peak*gain>CEILING)gain=Math.max(1,CEILING/meter.peak);
        short[] out=new short[n];
        // Entrada y salida suaves: un corte seco contra el silencio suena como un clic y puede confundir al modelo.
        for(int i=0;i<n;i++){double v=clip.data[i]*gain;int edge=Math.min(i,n-1-i);if(edge<FADE)v*=(edge+1d)/(FADE+1);out[i]=(short)Math.round(v);}
        return out;
    }
    /** Guarda un clip corto en memoria (el tope lo pone decode() con su límite). */
    private static final class Clip implements Sink{
        short[] data=new short[RATE*4];int count;
        @Override public void write(short[] d,int n){if(count+n>data.length)data=Arrays.copyOf(data,Math.max(data.length*2,count+n));System.arraycopy(d,0,data,count,n);count+=n;}
    }
    /** Mide volumen: energía por tramos de 20 ms y pico. «Activo» = tramos a menos de 30 dB del más fuerte, para no promediar los silencios. */
    private static final class Meter implements Sink{
        private double[] frames=new double[1024];private int count,inFrame;private double sum;int peak;
        @Override public void write(short[] d,int n){
            for(int i=0;i<n;i++){int v=d[i];sum+=(double)v*v;if(v<0)v=-v;if(v>peak)peak=v;
                if(++inFrame==FRAME){if(count==frames.length)frames=Arrays.copyOf(frames,count*2);frames[count++]=sum/FRAME;sum=0;inFrame=0;}}
        }
        double active(){
            double max=0;for(int i=0;i<count;i++)max=Math.max(max,frames[i]);
            double floor=Math.max(max/1000,900),total=0;int used=0; // 900 = 30²: bajo −60 dBFS es ruido de fondo, no voz
            for(int i=0;i<count;i++)if(frames[i]>=floor){total+=frames[i];used++;}
            return used==0?0:Math.sqrt(total/used);
        }
    }

    // ---------- Decodificar a mono de 16 kHz ----------
    /**
     * Decodifica la primera pista de audio y la entrega a «sink» en mono de 16 kHz, por partes. Se detiene al llegar a
     * «limit» muestras (Long.MAX_VALUE = todo). Devuelve las muestras entregadas.
     * La frecuencia, los canales y el tipo de PCM se leen de la salida del decodificador, no del contenedor: en un
     * HE-AAC el contenedor declara la mitad de la frecuencia real.
     */
    static long decode(File source,long limit,HttpApi cancel,Sink sink)throws Exception{return decode(source,limit,cancel,sink,null);}
    /** Avance de la lectura: microsegundos ya entregados al decodificador y la duración que declara el archivo (0 si no la dice). */
    interface Advance{void at(long doneUs,long totalUs);}
    /**
     * Igual, informando el avance a «advance» (puede ser null).
     * El decodificador trabaja en tubería: en cada vuelta se le entregan TODOS los búferes de entrada libres y se recoge
     * TODO lo que tenga listo, sin esperar. Solo si no hubo nada que hacer se espera su próxima salida (y se vuelve apenas
     * llega). Antes se recogía una salida por vuelta y se esperaban hasta 2 ms por una entrada que no se liberaba porque las
     * salidas no se recogían: miles de esperas por bloque, más largas aún con la app en segundo plano.
     */
    static long decode(File source,long limit,HttpApi cancel,Sink sink,Advance advance)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(source.getPath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)throw new NoTrack();extractor.selectTrack(track);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            int rate=number(format,MediaFormat.KEY_SAMPLE_RATE,0),channels=number(format,MediaFormat.KEY_CHANNEL_COUNT,0),encoding=number(format,MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            long totalUs=format.containsKey(MediaFormat.KEY_DURATION)?Math.max(0,format.getLong(MediaFormat.KEY_DURATION)):0;
            final long[] done={0};Sink capped=(data,count)->{int n=(int)Math.min(count,limit-done[0]);if(n>0){sink.write(data,n);done[0]+=n;}};
            Resampler resampler=null;Mixer mixer=new Mixer();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false,outputDone=false;Stall stall=new Stall(STALL_MS);
            while(!outputDone&&done[0]<limit){
                alive(cancel);
                // 1. Entrada: todos los búferes libres, sin esperar ninguno.
                boolean fed=false;
                while(!inputDone){
                    int in=codec.dequeueInputBuffer(0);if(in<0)break;
                    ByteBuffer buffer=codec.getInputBuffer(in);int size=buffer==null?-1:extractor.readSampleData(buffer,0);
                    if(size<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                    else{long at=extractor.getSampleTime();codec.queueInputBuffer(in,0,size,Math.max(0,at),0);extractor.advance();if(advance!=null&&at>=0)advance.at(at,totalUs);}
                    fed=true;
                }
                if(fed)stall.moved();
                // 2. Salida: todo lo que esté listo. Si no se pudo entregar nada, el decodificador está ocupado: se espera su
                // próxima salida (vuelve apenas existe; el plazo es solo un tope). Solo esa espera, si vuelve vacía, cuenta
                // como «trabado»: el tiempo que la app pasó congelada fuera de ella no cuenta.
                long waitUs=fed?0:CODEC_WAIT_US,asked=waitUs>0?SystemClock.elapsedRealtimeNanos():0;
                int out=codec.dequeueOutputBuffer(info,waitUs);
                if(out==MediaCodec.INFO_TRY_AGAIN_LATER&&waitUs>0&&stall.waited(waitUs,SystemClock.elapsedRealtimeNanos()-asked))throw new IOException("Audio decoder stopped advancing");
                while(out!=MediaCodec.INFO_TRY_AGAIN_LATER){
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        stall.moved();
                        MediaFormat f=codec.getOutputFormat();int newRate=number(f,MediaFormat.KEY_SAMPLE_RATE,rate);
                        channels=number(f,MediaFormat.KEY_CHANNEL_COUNT,channels);encoding=number(f,MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                        // Cambio de frecuencia a mitad de camino (raro): se cierra el filtro actual y se parte con otro.
                        if(resampler!=null&&newRate!=rate){resampler.finish(capped);resampler=null;}
                        rate=newRate;
                    }else if(out>=0){
                        stall.moved();
                        // Se revisa la cancelación en cada búfer: al cancelar, la conversión se detiene en el acto.
                        try{alive(cancel);}catch(InterruptedIOException e){codec.releaseOutputBuffer(out,false);throw e;}
                        if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                            ByteBuffer data=codec.getOutputBuffer(out);
                            if(data!=null){
                                if(rate<=0||channels<=0)throw new IOException("Unknown PCM format");
                                data.position(info.offset);data.limit(info.offset+info.size);
                                int frames=mixer.mix(data.slice().order(ByteOrder.nativeOrder()),encoding,channels);
                                if(resampler==null)resampler=new Resampler(rate);
                                resampler.push(mixer.mono,frames,capped);
                            }
                        }
                        boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(out,false);
                        if(end){outputDone=true;break;}
                        if(done[0]>=limit)break;
                    }
                    // (INFO_OUTPUT_BUFFERS_CHANGED no importa: los búferes se piden por índice.)
                    out=codec.dequeueOutputBuffer(info,0);
                }
            }
            if(resampler!=null&&done[0]<limit)resampler.finish(capped);
            return done[0];
        }finally{extractor.release();if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}}
    }
    private static int number(MediaFormat f,String key,int fallback){return f.containsKey(key)?f.getInteger(key):fallback;}
    private static void alive(HttpApi cancel)throws InterruptedIOException{
        if(cancel!=null)cancel.check();else if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Trabajo pausado");
    }

    /** Pasa un búfer del decodificador (PCM de 8, 16, 24 o 32 bits, o float; cualquier número de canales) a mono, en unidades de 16 bits. */
    private static final class Mixer{
        float[] mono=new float[0];private short[] shorts=new short[0];private float[] floats=new float[0];
        int mix(ByteBuffer pcm,int encoding,int channels)throws IOException{
            int width=encoding==AudioFormat.ENCODING_PCM_16BIT?2:encoding==AudioFormat.ENCODING_PCM_FLOAT||encoding==AudioFormat.ENCODING_PCM_32BIT?4:encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED?3:encoding==AudioFormat.ENCODING_PCM_8BIT?1:0;
            if(width==0)throw new IOException("Unsupported PCM");
            int frames=pcm.remaining()/(width*channels),count=frames*channels;if(mono.length<frames)mono=new float[frames];
            if(encoding==AudioFormat.ENCODING_PCM_16BIT){
                // Se copia de una vez a un arreglo: leer muestra por muestra del búfer nativo es varias veces más lento.
                if(shorts.length<count)shorts=new short[count];pcm.asShortBuffer().get(shorts,0,count);
                if(channels==1)for(int i=0;i<frames;i++)mono[i]=shorts[i];
                else for(int i=0,p=0;i<frames;i++){int sum=0;for(int ch=0;ch<channels;ch++)sum+=shorts[p++];mono[i]=(float)sum/channels;}
            }else if(encoding==AudioFormat.ENCODING_PCM_FLOAT){
                if(floats.length<count)floats=new float[count];pcm.asFloatBuffer().get(floats,0,count);
                for(int i=0,p=0;i<frames;i++){float sum=0;for(int ch=0;ch<channels;ch++)sum+=floats[p++];float v=sum*32768f/channels;mono[i]=v!=v?0:v;}
            }else for(int i=0,p=0;i<frames;i++){
                double sum=0;
                for(int ch=0;ch<channels;ch++,p+=width)sum+=width==1?((pcm.get(p)&0xff)-128)*256d:width==3?((pcm.get(p)&0xff)|(pcm.get(p+1)&0xff)<<8|pcm.get(p+2)<<16)/256d:pcm.getInt(p)/65536d;
                mono[i]=(float)(sum/channels);
            }
            return frames;
        }
    }

    /**
     * Cambio de frecuencia con filtro polifásico. La salida n corresponde al instante n·down/up de la entrada; para cada
     * fracción posible hay una fila de coeficientes ya calculada. El filtro va centrado (mira «half» muestras atrás y
     * adelante), así que la salida no queda corrida en el tiempo: los instantes de la entrada y de la salida coinciden.
     * Guarda estado entre búferes; de una entrada de N muestras salen exactamente ceil(N·up/down).
     * Con frecuencias raras (más de 2048 fracciones distintas) se usa la fila más cercana.
     */
    private static final class Resampler{
        private final int up,half,taps,phases,stepWhole,stepPart;private final boolean same,whole;private final float[] table,fold;
        private float[] window;private int fill,offset,part;
        private final short[] block=new short[4096];private int held;
        Resampler(int inRate){
            int g=gcd(inRate,RATE),down=inRate/g;up=RATE/g;same=up==down;stepWhole=down/up;stepPart=down%up;
            // Decimación entera (48 → 16 kHz es ×3; 32 → 16 kHz, ×2): una sola fila de coeficientes.
            whole=!same&&up==1;
            half=(int)Math.ceil(HALF_TAPS*Math.max(1d,(double)inRate/RATE));taps=2*half;phases=Math.min(up,MAX_PHASES);
            table=same?null:new float[phases*taps];window=same?null:new float[taps+8192];
            // Antes de la primera muestra hay silencio: «half-1» ceros, para que la salida 0 quede sobre la entrada 0.
            fill=half-1;if(same){fold=null;return;}
            double cutoff=CUTOFF*Math.min(inRate,RATE)/inRate,norm=bessel(BETA);double[] row=new double[taps];
            for(int p=0;p<phases;p++){
                double shift=phases==up?(double)p/up:(p+0.5)/phases,sum=0;
                for(int k=0;k<taps;k++){
                    double tau=shift+half-1-k,u=tau/half,x=2*cutoff*tau;
                    double sinc=Math.abs(x)<1e-9?1:Math.sin(Math.PI*x)/(Math.PI*x),kaiser=u*u>=1?0:bessel(BETA*Math.sqrt(1-u*u))/norm;
                    row[k]=sinc*kaiser;sum+=row[k];
                }
                // Cada fila suma 1: el volumen no cambia ni varía de una fracción a otra.
                for(int k=0;k<taps;k++)table[p*taps+k]=(float)(row[k]/sum);
            }
            // Camino directo: la fila es simétrica alrededor de su centro (half-1) y el último coeficiente vale 0, así que
            // fold[j] sirve para las dos muestras a distancia j del centro.
            if(whole){fold=new float[half];for(int j=0;j<half;j++)fold[j]=table[half-1-j];}else fold=null;
        }
        void push(float[] in,int count,Sink sink)throws Exception{
            if(same){for(int i=0;i<count;i++)emit(in[i],sink);return;}
            if(fill+count>window.length){
                System.arraycopy(window,offset,window,0,fill-offset);fill-=offset;offset=0;
                if(fill+count>window.length)window=Arrays.copyOf(window,fill+count+8192);
            }
            System.arraycopy(in,0,window,fill,count);fill+=count;
            if(whole){
                // Se suman primero las dos muestras que comparten coeficiente y se multiplica una sola vez: la mitad de las
                // multiplicaciones del caso general. Dos sumas en paralelo, por la misma razón que abajo.
                final int mid=half-1;
                while(offset+taps<=fill){
                    int c=offset+mid,j=1;float a=fold[0]*window[c],b=0;
                    for(;j+1<=mid;j+=2){a+=fold[j]*(window[c-j]+window[c+j]);b+=fold[j+1]*(window[c-j-1]+window[c+j+1]);}
                    if(j<=mid)a+=fold[j]*(window[c-j]+window[c+j]);
                    emit(a+b,sink);offset+=stepWhole;
                }
                return;
            }
            while(offset+taps<=fill){
                int row=(phases==up?part:(int)((long)part*phases/up))*taps,at=offset,k=0;float a=0,b=0,c=0,d=0;
                // Cuatro sumas en paralelo: es el lazo que más pesa (más de mil millones de productos por bloque de 12 min)
                // y así el procesador no tiene que esperar cada suma para empezar la siguiente.
                for(;k+3<taps;k+=4){a+=window[at+k]*table[row+k];b+=window[at+k+1]*table[row+k+1];c+=window[at+k+2]*table[row+k+2];d+=window[at+k+3]*table[row+k+3];}
                for(;k<taps;k++)a+=window[at+k]*table[row+k];
                emit(a+b+c+d,sink);offset+=stepWhole;part+=stepPart;if(part>=up){part-=up;offset++;}
            }
        }
        /** Fin de la entrada: «half» ceros empujan las últimas salidas y se entrega lo que quedaba retenido. */
        void finish(Sink sink)throws Exception{if(!same)push(new float[half],half,sink);if(held>0){sink.write(block,held);held=0;}}
        private void emit(float value,Sink sink)throws Exception{
            int v=Math.round(value);block[held++]=(short)(v>32767?32767:v<-32768?-32768:v);
            if(held==block.length){sink.write(block,held);held=0;}
        }
    }
    /**
     * Pasa a 16 kHz un PCM mono ya en memoria (en trozos de 4096, como llegan del decodificador). Solo para las pruebas:
     * miden el filtro solo, sin el decodificador del teléfono. Devuelve las muestras entregadas.
     */
    static long resample(int inRate,float[] input,Sink sink)throws Exception{
        Resampler resampler=new Resampler(inRate);long[] count={0};Sink counting=(d,n)->{count[0]+=n;sink.write(d,n);};
        float[] chunk=new float[4096];
        for(int at=0;at<input.length;at+=chunk.length){int n=Math.min(chunk.length,input.length-at);System.arraycopy(input,at,chunk,0,n);resampler.push(chunk,n,counting);}
        resampler.finish(counting);return count[0];
    }
    private static int gcd(int a,int b){while(b!=0){int t=a%b;a=b;b=t;}return a;}
    /** Función de Bessel modificada I0 (serie), para la ventana de Kaiser. */
    private static double bessel(double x){double sum=1,term=1,q=x*x/4;for(int k=1;k<80;k++){term*=q/((double)k*k);sum+=term;if(term<sum*1e-13)break;}return sum;}

    // ---------- Salida WAV ----------
    /** WAV de 16 kHz, mono y 16 bits: cabecera RIFF de 44 bytes, que se completa al final porque el largo no se sabe antes. */
    private static final class WavOut implements Out{
        private final File file;private OutputStream out;private long count;private byte[] bytes=new byte[8192];
        WavOut(File file)throws IOException{this.file=file;out=new BufferedOutputStream(new FileOutputStream(file),65536);out.write(new byte[44]);}
        @Override public void write(short[] data,int n)throws IOException{
            if(bytes.length<n*2)bytes=new byte[n*2];
            for(int i=0;i<n;i++){bytes[2*i]=(byte)data[i];bytes[2*i+1]=(byte)(data[i]>>8);}
            out.write(bytes,0,n*2);count+=n;
        }
        @Override public long finish()throws IOException{
            out.close();out=null;
            ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put(ascii("RIFF")).putInt((int)(36+count*2)).put(ascii("WAVEfmt ")).putInt(16).putShort((short)1).putShort((short)1).putInt(RATE).putInt(RATE*2).putShort((short)2).putShort((short)16).put(ascii("data")).putInt((int)(count*2));
            try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){raf.write(h.array());}
            return count;
        }
        @Override public void release(){if(out!=null){try{out.close();}catch(IOException ignored){}out=null;}}
    }
    private static byte[] ascii(String text){return text.getBytes(StandardCharsets.US_ASCII);}

    // ---------- Salida FLAC ----------
    /**
     * FLAC con el codificador del teléfono (MediaCodec «audio/flac», nivel 5). MediaMuxer no escribe .flac, así que el
     * flujo se guarda tal cual sale: primero la configuración del códec («fLaC» + STREAMINFO) y después los cuadros.
     * Cualquier problema se convierte en FlacFailed, y el bloque se rehace en WAV.
     */
    private static final class FlacOut implements Out{
        private final File file;private final HttpApi cancel;private MediaCodec codec;private OutputStream out;private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        private long fed;private boolean begun,ended;private byte[] header,csd;
        /** Espera real sin que el codificador tome ni entregue nada (un congelamiento de la app no cuenta). */
        private final Stall stall=new Stall(STALL_MS);
        FlacOut(File file,HttpApi cancel)throws IOException{
            this.file=file;this.cancel=cancel;
            try{
                MediaFormat f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC,RATE,1);
                f.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL,5);f.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                // FLAC no tiene tasa de bits, pero algunos teléfonos no configuran un codificador sin este dato.
                f.setInteger(MediaFormat.KEY_BIT_RATE,RATE*16);
                codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
                out=new BufferedOutputStream(new FileOutputStream(file),65536);
            }catch(Exception e){release();throw new FlacFailed("flac-encoder-unavailable",e);}
        }
        @Override public void write(short[] data,int count)throws IOException{
            try{
                int position=0;
                while(position<count){
                    alive(cancel);int index=codec.dequeueInputBuffer(0);
                    if(index>=0){
                        ByteBuffer buffer=codec.getInputBuffer(index);if(buffer==null)throw new IOException("No encoder input buffer");
                        buffer.clear();int n=Math.min(count-position,buffer.remaining()/2);if(n<=0)throw new IOException("Encoder buffer too small");
                        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(data,position,n);
                        codec.queueInputBuffer(index,0,n*2,fed*1_000_000L/RATE,0);fed+=n;position+=n;stall.moved();
                        drain(false);
                    }else{
                        // Sin entrada libre, el codificador está ocupado o espera que se recojan sus salidas: se espera su
                        // próxima salida (vuelve apenas existe), en vez de dormir a ciegas esperando una entrada.
                        drain(false,CODEC_WAIT_US);
                        if(stall.over())throw new IOException("Encoder stopped taking audio");
                    }
                }
            }catch(InterruptedIOException e){throw e;}catch(Exception e){throw new FlacFailed("flac-encode",e);}
        }
        private void drain(boolean toEnd)throws Exception{drain(toEnd,0);}
        /**
         * Guarda lo que el codificador tenga listo (la primera consulta puede esperar firstWaitUs); con toEnd espera hasta el
         * final del flujo. Las esperas que vuelven vacías se anotan en «stall»: sin toEnd, quien llama decide con stall.over().
         */
        private void drain(boolean toEnd,long firstWaitUs)throws Exception{
            boolean first=true;
            while(!ended){
                long waitUs=toEnd?CODEC_WAIT_US:first?firstWaitUs:0,asked=waitUs>0?SystemClock.elapsedRealtimeNanos():0;
                int index=codec.dequeueOutputBuffer(info,waitUs);first=false;
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    stall.moved();
                    ByteBuffer c=codec.getOutputFormat().getByteBuffer("csd-0");if(c!=null){c=c.duplicate();c.rewind();csd=new byte[c.remaining()];c.get(csd);}
                }else if(index>=0){
                    stall.moved();
                    if(info.size>0){ByteBuffer data=codec.getOutputBuffer(index);if(data!=null){byte[] chunk=new byte[info.size];data.position(info.offset);data.limit(info.offset+info.size);data.get(chunk);store(chunk,(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0);}}
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)ended=true;
                    codec.releaseOutputBuffer(index,false);
                }else{
                    if(waitUs>0)stall.waited(waitUs,SystemClock.elapsedRealtimeNanos()-asked);
                    if(!toEnd)return;
                    alive(cancel);if(stall.over())throw new IOException("Encoder did not finish");
                }
            }
        }
        /**
         * La cabecera se retiene hasta el primer cuadro, para poder arreglarla: el archivo debe empezar con «fLaC». Según
         * el teléfono puede venir como búfer de configuración, solo en el formato de salida (csd-0) o, en teoría, no venir.
         */
        private void store(byte[] chunk,boolean config)throws IOException{
            if(config){if(!begun)header=header==null?chunk:join(header,chunk);return;}
            if(!begun){begun=true;if(!magic(chunk))out.write(head(header!=null?header:csd,chunk));}
            out.write(chunk);
        }
        @Override public long finish()throws IOException{
            try{
                // El fin va en un búfer vacío y aparte: hay codificadores (Android 8 y 9) que descartan los datos que llegan junto con él.
                while(true){
                    alive(cancel);int index=codec.dequeueInputBuffer(0);
                    if(index>=0){codec.queueInputBuffer(index,0,0,fed*1_000_000L/RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);stall.moved();break;}
                    drain(false,CODEC_WAIT_US);
                    if(stall.over())throw new IOException("Encoder stopped taking audio");
                }
                drain(true);out.close();out=null;release();
                if(!begun)throw new IOException("Encoder produced no audio");
                stamp(file,fed);
                // Autoverificación barata (0.8.0, tercera ronda): antes se decodificaba el FLAC entero de vuelta, lo que costaba
                // casi lo mismo que convertirlo. Ahora se recorren sus cuadros sin decodificarlos: cada cabecera y la CRC de
                // cada cuadro deben estar bien y las muestras deben sumar exactamente lo entregado. Si falta el final (pasa en
                // codificadores antiguos, que no vacían el último cuadro) o un cuadro llegó dañado, no se envía: los tiempos
                // de las anclas y del bloque dependen de esto.
                long counted=flacSamples(file);
                if(counted!=fed)throw new IOException("FLAC length differs by "+(counted-fed)+" samples");
                return fed;
            }catch(InterruptedIOException e){throw e;}catch(Exception e){throw new FlacFailed("flac-verify",e);}
        }
        @Override public void release(){
            if(out!=null){try{out.close();}catch(IOException ignored){}out=null;}
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}
        }
    }
    private static boolean magic(byte[] b){return b.length>=4&&b[0]=='f'&&b[1]=='L'&&b[2]=='a'&&b[3]=='C';}
    private static byte[] join(byte[] a,byte[] b){byte[] all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
    private static int length24(byte[] b,int at){return (b[at]&0xff)<<16|(b[at+1]&0xff)<<8|(b[at+2]&0xff);}
    /**
     * Cabecera para un flujo que no empezó con «fLaC». Se aprovecha lo que haya entregado el codificador (bloques de
     * metadatos sin la marca, o el STREAMINFO suelto de 34 bytes); si no entregó nada, se arma un STREAMINFO mínimo con
     * el tamaño de bloque que declara el primer cuadro. El total de muestras lo anota después stamp().
     */
    private static byte[] head(byte[] given,byte[] frame)throws IOException{
        byte[] mark=ascii("fLaC");
        if(given!=null){
            if(magic(given))return given;
            if(given.length>=38&&(given[0]&0x7f)==0&&length24(given,1)==34)return join(mark,given);
            if(given.length==34)return join(join(mark,new byte[]{(byte)0x80,0,0,34}),given);
        }
        if(frame.length<8||(frame[0]&0xff)!=0xff||(frame[1]&0xfe)!=0xf8)throw new IOException("Unknown FLAC stream");
        int code=(frame[2]>>4)&15,size;
        if(code==1)size=192;else if(code>=2&&code<=5)size=576<<(code-2);else if(code>=8)size=256<<(code-8);
        else if(code==6||code==7){
            // El tamaño viene después del número de cuadro, que ocupa de 1 a 7 bytes según su primer byte (como UTF-8).
            int first=frame[4]&0xff,at=4+(first<0x80?1:first>=0xfe?7:first>=0xfc?6:first>=0xf8?5:first>=0xf0?4:first>=0xe0?3:2);
            if(frame.length<at+2)throw new IOException("Unknown FLAC stream");
            size=code==6?(frame[at]&0xff)+1:((frame[at]&0xff)<<8|(frame[at+1]&0xff))+1;
        }else throw new IOException("Unknown FLAC stream");
        boolean variable=(frame[1]&1)!=0;
        return ByteBuffer.allocate(42).put(mark).put((byte)0x80).put((byte)0).put((byte)0).put((byte)34).putShort((short)(variable?16:size)).putShort((short)(variable?65535:size)).put(new byte[6])
            .put((byte)(RATE>>12)).put((byte)(RATE>>4)).put((byte)((RATE&15)<<4)).put((byte)0xf0).put(new byte[20]).array();
    }
    /**
     * Revisa que el archivo empiece con «fLaC» y un STREAMINFO de 16 kHz, mono y 16 bits, y anota en él el total de
     * muestras: el codificador lo deja en 0 («desconocido») porque escribe la cabecera antes de codificar, y con el
     * total quien reciba el archivo sabe cuánto dura sin tener que recorrerlo.
     */
    private static void stamp(File file,long samples)throws IOException{
        try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){
            byte[] h=new byte[42];raf.readFully(h);
            if(!magic(h)||(h[4]&0x7f)!=0||length24(h,5)!=34)throw new IOException("FLAC header missing");
            int rate=(h[18]&0xff)<<12|(h[19]&0xff)<<4|(h[20]&0xff)>>4,channels=((h[20]>>1)&7)+1,bits=((h[20]&1)<<4|(h[21]&0xff)>>4)+1;
            if(rate!=RATE||channels!=1||bits!=16)throw new IOException("FLAC format is "+rate+" Hz, "+channels+" ch, "+bits+" bits");
            // El total ocupa 36 bits: los 4 bajos del byte 21 y los bytes 22 a 25.
            raf.seek(21);raf.write((h[21]&0xf0)|(int)((samples>>>32)&0x0f));raf.writeInt((int)samples);
        }
    }

    // ---------- Comprobación barata del FLAC ----------
    /** Tablas de las dos sumas de control de FLAC: CRC-8 de cada cabecera (x⁸+x²+x+1) y CRC-16 de cada cuadro (x¹⁶+x¹⁵+x²+1). */
    private static final int[] CRC8=new int[256],CRC16=new int[256];
    static{
        for(int i=0;i<256;i++){
            int a=i;for(int k=0;k<8;k++)a=(a&0x80)!=0?((a<<1)^0x07)&0xff:(a<<1)&0xff;CRC8[i]=a;
            int b=i<<8;for(int k=0;k<8;k++)b=(b&0x8000)!=0?((b<<1)^0x8005)&0xffff:(b<<1)&0xffff;CRC16[i]=b;
        }
    }
    /**
     * Muestras de un FLAC de OrAudio (16 kHz, mono, 16 bits) contadas cuadro por cuadro, SIN decodificarlo: recorre los
     * metadatos y después cada cuadro, revisando su cabecera (formato, número de orden y CRC-8) y que la CRC-16 del cuadro
     * entero dé cero justo donde empieza el siguiente. Cuesta una lectura del archivo (milisegundos), no una decodificación.
     * Lanza IOException con el motivo (técnico, sin datos del usuario) si algo no cuadra.
     */
    static long flacSamples(File file)throws IOException{
        try(RandomAccessFile raf=new RandomAccessFile(file,"r");java.nio.channels.FileChannel channel=raf.getChannel()){
            long length=channel.size();if(length<42||length>Integer.MAX_VALUE)throw new IOException("FLAC size "+length);
            // Mapeado y no copiado: un bloque de 12 min pesa ~14 MB y pueden ir tres a la vez.
            ByteBuffer b=channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,length);int size=(int)length;
            if(b.get(0)!='f'||b.get(1)!='L'||b.get(2)!='a'||b.get(3)!='C')throw new IOException("FLAC header missing");
            int p=4;boolean last=false;long[] head=new long[4];
            while(!last){
                if(p+4>size)throw new IOException("FLAC metadata cut");
                int h=b.get(p)&0xff;
                // Un codificador que no marcó el último bloque de metadatos: si aquí ya empieza un cuadro (0xFF 0xF8 no puede
                // ser la cabecera de un bloque de metadatos, sería el tipo 127), los metadatos terminaron.
                if(p>4&&h==0xff&&frameHeader(b,p,size,head))break;
                last=(h&0x80)!=0;p+=4+((b.get(p+1)&0xff)<<16|(b.get(p+2)&0xff)<<8|(b.get(p+3)&0xff));
            }
            if(p>=size||!frameHeader(b,p,size,head))throw new IOException("FLAC has no valid first frame");
            boolean variable=head[3]==1;long samples=0,frames=0;int start=p;
            while(true){
                // Cuadro que empieza en «start»: mismo modo de numeración que el primero y en orden (no falta ninguno).
                if((head[3]==1)!=variable||head[2]!=(variable?samples:frames))throw new IOException("FLAC frame "+frames+" out of order");
                samples+=head[1];frames++;
                // CRC-16 corrida desde el comienzo del cuadro: con sus dos bytes finales incluidos da cero. El siguiente cuadro
                // empieza donde la suma da cero Y hay una cabecera válida (una coincidencia dentro de los datos es casi imposible).
                int i=start,minEnd=start+(int)head[0]+3,crc=0;
                for(;i<size;i++){
                    if(crc==0&&i>=minEnd&&(b.get(i)&0xff)==0xff&&i+1<size&&(b.get(i+1)&0xfe)==0xf8&&frameHeader(b,i,size,head))break;
                    crc=((crc<<8)&0xffff)^CRC16[((crc>>8)^(b.get(i)&0xff))&0xff];
                }
                if(i>=size){if(crc!=0)throw new IOException("FLAC last frame damaged or cut");return samples;}
                start=i;
            }
        }
    }
    /**
     * Cabecera de cuadro FLAC válida en «at» (sincronía, campos posibles para 16 kHz mono de 16 bits y CRC-8 correcta).
     * Deja en out: {largo de la cabecera, muestras del cuadro, número (de cuadro o de muestra), 1 si la numeración es por muestra}.
     */
    private static boolean frameHeader(ByteBuffer b,int at,int size,long[] out){
        if(at+6>size)return false;
        int b1=b.get(at+1)&0xff,b2=b.get(at+2)&0xff,b3=b.get(at+3)&0xff;
        if((b.get(at)&0xff)!=0xff||(b1&0xfe)!=0xf8)return false;
        int sizeCode=b2>>4,rateCode=b2&15,channels=b3>>4,bits=(b3>>1)&7;
        if(sizeCode==0||rateCode==15||(b3&1)!=0)return false;
        // Lo que escribe OrAudio: un canal, 16 bits y 16 kHz (o «lo que diga el STREAMINFO»).
        if(channels!=0||(bits!=0&&bits!=4)||(rateCode!=0&&rateCode!=5&&rateCode<12))return false;
        int p=at+4,first=b.get(p)&0xff,extra;long number;
        // Número de cuadro (o de muestra) en el formato de UTF-8, de 1 a 7 bytes.
        if(first<0x80){extra=0;number=first;}
        else if(first>=0xc0&&first<0xe0){extra=1;number=first&0x1f;}
        else if(first>=0xe0&&first<0xf0){extra=2;number=first&0x0f;}
        else if(first>=0xf0&&first<0xf8){extra=3;number=first&0x07;}
        else if(first>=0xf8&&first<0xfc){extra=4;number=first&0x03;}
        else if(first>=0xfc&&first<0xfe){extra=5;number=first&0x01;}
        else if(first==0xfe){extra=6;number=0;}
        else return false;
        if(p+1+extra>size)return false;
        for(int k=1;k<=extra;k++){int c=b.get(p+k)&0xff;if((c&0xc0)!=0x80)return false;number=(number<<6)|(c&0x3f);}
        p+=1+extra;
        int block;
        if(sizeCode==1)block=192;
        else if(sizeCode<=5)block=576<<(sizeCode-2);
        else if(sizeCode==6){if(p>=size)return false;block=(b.get(p++)&0xff)+1;}
        else if(sizeCode==7){if(p+1>=size)return false;block=((b.get(p)&0xff)<<8|(b.get(p+1)&0xff))+1;p+=2;}
        else block=256<<(sizeCode-8);
        if(rateCode==12){if(p>=size||(b.get(p++)&0xff)*1000!=RATE)return false;}
        else if(rateCode==13||rateCode==14){if(p+1>=size)return false;int v=(b.get(p)&0xff)<<8|(b.get(p+1)&0xff);p+=2;if((rateCode==13?v:v*10)!=RATE)return false;}
        if(p>=size)return false;
        int crc=0;for(int k=at;k<p;k++)crc=CRC8[(crc^(b.get(k)&0xff))&0xff];
        if(crc!=(b.get(p)&0xff))return false;
        out[0]=p+1-at;out[1]=block;out[2]=number;out[3]=b1&1;
        return true;
    }
    private OrAudio(){}
}
