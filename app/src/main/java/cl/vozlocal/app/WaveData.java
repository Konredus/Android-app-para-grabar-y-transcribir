package cl.vozlocal.app;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Envolvente del audio para la onda del reproductor: {@link #N} valores de 0 a 1, guardados en
 * {@code <id>.wave.json} ({"n","v"}; además "bytes", el tamaño del audio, para detectar un caché viejo).
 * Se calcula una sola vez decodificando todo el audio (MediaExtractor + MediaCodec): energía RMS por tramo,
 * escala perceptual (raíz) y normalización al percentil 98, para que un golpe aislado no aplaste el resto.
 * Si el formato no se puede decodificar, devuelve null (la pantalla usa su barra de siempre).
 */
final class WaveData {
    private WaveData(){}
    static final int N=600;
    /** Avance del cálculo, desde el hilo que calcula: envolvente parcial ya normalizada (lo pendiente queda en 0) y fracción hecha (0..1). Devolver false lo cancela. */
    interface Progress { boolean update(float[] partial,float done); }
    /** Referencia mínima de la normalización (≈ -33 dBFS RMS en escala raíz): un audio casi mudo no se estira hasta parecer fuerte. */
    private static final float REF_FLOOR=0.15f;
    /** Muestras por tramo que bastan para estimar su energía; en audios largos se toma 1 de cada «stride». */
    private static final int SAMPLES_PER_BUCKET=2048,MAX_STRIDE=128;
    private static final long STALL_MS=15_000,PARTIAL_EVERY_MS=250;

    /** Rápido, sin decodificar: la envolvente guardada o null. */
    static float[] cached(Context c,Recording r){
        try{
            File file=FilesStore.file(c,r.id,".wave.json");if(!file.isFile())return null;
            JSONObject j=FilesStore.read(file);JSONArray a=j.optJSONArray("v");if(a==null)return null;
            int n=j.optInt("n",a.length());if(n<=0||a.length()!=n)return null;
            long bytes=j.optLong("bytes",-1);if(bytes>=0&&bytes!=r.audio(c).length())return null;
            float[] v=new float[n];for(int i=0;i<n;i++)v[i]=clamp((float)a.optDouble(i,0));
            return v;
        }catch(Exception e){return null;}
    }
    /** BLOQUEA (hilo de fondo): decodifica, guarda y devuelve la envolvente; null si no se pudo. */
    static float[] compute(Context c,Recording r){return compute(c,r,null);}
    /**
     * Como {@link #compute(Context,Recording)}, avisando el avance para dibujar la onda mientras se calcula.
     * Si otro hilo ya calcula esta grabación, espera ese resultado (y muestra su avance) en vez de decodificar dos veces.
     * Se cancela si progress devuelve false o si el hilo se interrumpe; nunca deja un caché a medias.
     */
    static float[] compute(Context c,Recording r,Progress progress){
        if(c==null||r==null)return null;
        float[] ready=cached(c,r);if(ready!=null)return ready;
        if(r.id.equals(RecorderService.activeId))return null; // el audio todavía se está escribiendo
        for(int attempt=0;attempt<2;attempt++){
            Job job;boolean owner;
            synchronized(RUNNING){job=RUNNING.get(r.id);owner=job==null;if(owner){job=new Job();RUNNING.put(r.id,job);}}
            if(owner){
                try{job.result=decode(c,r,job,progress);}
                finally{synchronized(RUNNING){RUNNING.remove(r.id);}job.finished.countDown();}
                return job.result;
            }
            float[] seen=null;
            try{
                while(!job.finished.await(150,TimeUnit.MILLISECONDS)){
                    float[] part=job.partial;
                    if(progress!=null&&part!=null&&part!=seen){seen=part;if(!progress.update(part,job.done))return null;}
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
            if(job.result!=null){if(progress!=null)progress.update(job.result,1f);return job.result;}
            float[] again=cached(c,r);if(again!=null)return again;
            if(!job.cancelled)return null; // falló de verdad (formato no soportado): no insistir
        }
        return null;
    }
    /** Calcula en segundo plano, con prioridad baja, si todavía no está guardada (p. ej. al terminar de grabar). */
    static void warm(Context c,Recording r){
        if(c==null||r==null)return;
        Context app=c.getApplicationContext();
        try{WARM.execute(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);if(cached(app,r)==null)compute(app,r,null);});}
        catch(RuntimeException ignored){}
    }

    private static final class Job{
        final CountDownLatch finished=new CountDownLatch(1);
        volatile float[] partial,result;volatile float done;volatile boolean cancelled;
    }
    private static final HashMap<String,Job> RUNNING=new HashMap<>();
    private static final ExecutorService WARM=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"voz-onda");t.setDaemon(true);return t;});

    private static float[] decode(Context c,Recording r,Job job,Progress progress){
        File audio=r.audio(c);if(!audio.isFile()||audio.length()==0)return null;
        long bytes=audio.length();
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;boolean started=false;
        try{
            extractor.setDataSource(audio.getPath());
            int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)return null;
            extractor.selectTrack(track);
            long durationUs=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
            if(durationUs<=0&&r.duration>0)durationUs=r.duration*1000;
            if(durationUs<=0){durationUs=lastSampleUs(extractor);extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);}
            if(durationUs<=0)return null;
            int rate=format.containsKey(MediaFormat.KEY_SAMPLE_RATE)?format.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?format.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();started=true;
            double[] sum=new double[N];int[] count=new int[N];
            if(!report(job,progress,envelope(sum,count),0f))return null; // primer dibujo: la línea base
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false;long lastOutput=SystemClock.elapsedRealtime(),lastPartial=lastOutput,reachedUs=0;
            while(true){
                if(Thread.currentThread().isInterrupted()){job.cancelled=true;return null;}
                if(!inputDone)for(int k=0;k<8;k++){
                    int in=codec.dequeueInputBuffer(0);if(in<0)break;
                    ByteBuffer buffer=codec.getInputBuffer(in);int size=buffer==null?-1:extractor.readSampleData(buffer,0);
                    if(size<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;break;}
                    codec.queueInputBuffer(in,0,size,Math.max(0,extractor.getSampleTime()),0);extractor.advance();
                }
                int out=codec.dequeueOutputBuffer(info,5000);long now=SystemClock.elapsedRealtime();
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=codec.getOutputFormat();
                    if(f.containsKey(MediaFormat.KEY_SAMPLE_RATE))rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                }else if(out>=0){
                    lastOutput=now;
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        ByteBuffer data=codec.getOutputBuffer(out);
                        if(data!=null){
                            if(!accumulate(data,info,encoding,Math.max(1,channels),rate>0?rate:44100,durationUs,sum,count))return null; // PCM que no sabemos leer
                            reachedUs=Math.max(reachedUs,info.presentationTimeUs);
                        }
                    }
                    boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(out,false);
                    if(end)break;
                }else if(now-lastOutput>STALL_MS)return null; // el decodificador dejó de avanzar
                if(now-lastPartial>=PARTIAL_EVERY_MS){lastPartial=now;if(!report(job,progress,envelope(sum,count),Math.min(1f,(float)reachedUs/durationUs)))return null;}
            }
            boolean any=false;for(int n:count)if(n>0){any=true;break;}
            if(!any)return null;
            float[] v=envelope(sum,count);
            store(c,r,v,bytes);
            if(progress!=null)progress.update(v,1f);
            return v;
        }catch(Exception e){return null;}
        finally{
            if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
            extractor.release();
        }
    }
    /** Publica el avance para quien espere esta grabación. false si hay que cancelar. */
    private static boolean report(Job job,Progress progress,float[] partial,float done){
        job.done=done;job.partial=partial;
        if(progress!=null&&!progress.update(partial,done)){job.cancelled=true;return false;}
        return true;
    }
    /** Suma la energía de un bloque de PCM en sus tramos. false si la codificación no se puede leer. */
    private static boolean accumulate(ByteBuffer data,MediaCodec.BufferInfo info,int encoding,int channels,int rate,long durationUs,double[] sum,int[] count){
        data.position(info.offset);data.limit(info.offset+info.size);
        ByteBuffer pcm=data.slice().order(ByteOrder.nativeOrder());
        int width;
        switch(encoding){
            case AudioFormat.ENCODING_PCM_16BIT:width=2;break;
            case AudioFormat.ENCODING_PCM_FLOAT:width=4;break;
            case AudioFormat.ENCODING_PCM_8BIT:width=1;break;
            case AudioFormat.ENCODING_PCM_32BIT:width=4;break;
            default:return false;
        }
        int frames=info.size/(width*channels);if(frames<=0)return true;
        long framesPerBucket=durationUs*rate/1_000_000L/N;
        int stride=(int)Math.max(1,Math.min(MAX_STRIDE,framesPerBucket/SAMPLES_PER_BUCKET));
        ShortBuffer s16=encoding==AudioFormat.ENCODING_PCM_16BIT?pcm.asShortBuffer():null;
        FloatBuffer f32=encoding==AudioFormat.ENCODING_PCM_FLOAT?pcm.asFloatBuffer():null;
        IntBuffer i32=encoding==AudioFormat.ENCODING_PCM_32BIT?pcm.asIntBuffer():null;
        long pts=info.presentationTimeUs;
        for(int j=0;j<frames;j+=stride){
            long t=pts+(long)j*1_000_000L/rate;if(t<0)continue;
            int b=(int)(t*N/durationUs);if(b>=N)b=N-1;
            int base=j*channels;double e=0;
            for(int ch=0;ch<channels;ch++){
                double x;
                if(s16!=null)x=s16.get(base+ch)/32768d;
                else if(f32!=null)x=f32.get(base+ch);
                else if(i32!=null)x=i32.get(base+ch)/2147483648d;
                else x=((pcm.get(base+ch)&0xff)-128)/128d;
                e+=x*x;
            }
            sum[b]+=e/channels;count[b]++;
        }
        return true;
    }
    /** Energía por tramo → raíz del RMS (escala perceptual) → normalizada al percentil 98 → 0..1. */
    static float[] envelope(double[] sum,int[] count){
        int n=sum.length;float[] v=new float[n],sorted=new float[n];int filled=0,last=-1;
        for(int b=0;b<n;b++)if(count[b]>0)last=b;
        float previous=0;
        for(int b=0;b<n;b++){
            if(count[b]>0){double ms=sum[b]/count[b];v[b]=(float)Math.sqrt(Math.sqrt(Math.max(0,ms)));previous=v[b];sorted[filled++]=v[b];}
            else if(b<last)v[b]=previous; // tramo sin muestras dentro del audio: repite el anterior
        }
        if(filled==0)return v;
        Arrays.sort(sorted,0,filled);
        float ref=Math.max(REF_FLOOR,sorted[(int)Math.floor(0.98*(filled-1))]);
        for(int b=0;b<n;b++)v[b]=clamp(v[b]/ref);
        return v;
    }
    private static float clamp(float x){return x!=x?0f:Math.max(0f,Math.min(1f,x));}
    private static long lastSampleUs(MediaExtractor extractor){
        long last=0;
        try{while(extractor.getSampleTime()>=0){last=Math.max(last,extractor.getSampleTime());if(!extractor.advance())break;}}catch(RuntimeException ignored){}
        return last;
    }
    /** Guarda de forma atómica; no escribe si el audio se borró o cambió mientras se calculaba. */
    private static void store(Context c,Recording r,float[] v,long bytes){
        try{
            JSONArray a=new JSONArray();for(float x:v)a.put(Math.round(x*1000)/1000d);
            byte[] json=new JSONObject().put("n",v.length).put("v",a).put("bytes",bytes).toString().getBytes(StandardCharsets.UTF_8);
            File audio=r.audio(c),file=FilesStore.file(c,r.id,".wave.json");
            synchronized(FilesStore.LOCK){
                if(!audio.isFile()||audio.length()!=bytes)return;
                AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
                try{out=atomic.startWrite();out.write(json);atomic.finishWrite(out);}
                catch(IOException e){if(out!=null)atomic.failWrite(out);}
            }
        }catch(Exception ignored){}
    }
}
