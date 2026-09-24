package cl.vozlocal.app;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.*;
import org.json.*;

/**
 * Preparación de bloques para enviar al proveedor.
 * - plan(): corta en PAUSAS cercanas al tamaño objetivo (no a mitad de frase), para enviar bloques en paralelo.
 * - references(): extrae muestras de voz (2–10 s) de cada persona del primer bloque.
 */
final class AudioParts {
    static final class Part {final File file;final double offset;final long durationMs;Part(File file,double offset){this(file,offset,0);}Part(File file,double offset,long durationMs){this.file=file;this.offset=offset;this.durationMs=durationMs;}}
    interface Log{void line(String message);}

    /** Hasta esta duración se envía un solo bloque (bajo el límite de 1400 s del modelo con voces). */
    static final long SINGLE_MAX_MS=12*60_000;

    static List<Part> plan(Context c,Recording r,HttpApi http,long targetMs,boolean compress,Log log)throws Exception{
        File source=r.audio(c);long total=r.duration>0?r.duration:AudioConvert.duration(source);
        List<Long> cuts=new ArrayList<>();cuts.add(0L);
        if(total>SINGLE_MAX_MS||source.length()>20_000_000){
            if(log!=null)log.line("Buscando pausas para cortar sin partir frases");
            for(long t=targetMs;t<total-targetMs*0.4;t+=targetMs){
                long q=quietest(source,t,15_000,http);long last=cuts.get(cuts.size()-1);
                if(q>last+30_000&&q<total-20_000)cuts.add(q);
            }
        }
        cuts.add(total);
        // Resguardo de tamaño (25 MB por envío): si un tramo pesaría más de 20 MB se divide por la mitad.
        double bytesPerMs=source.length()/(double)Math.max(1,total);
        for(int i=0;i+1<cuts.size();i++){long a=cuts.get(i),b=cuts.get(i+1);if(!compress&&(b-a)*bytesPerMs>20_000_000){cuts.add(i+1,a+(b-a)/2);i--;}}
        if(cuts.size()==2&&!compress)return Collections.singletonList(new Part(source,0,total));
        List<Part> parts=new ArrayList<>();
        try{
            for(int i=0;i+1<cuts.size();i++){
                http.check();long a=cuts.get(i),b=cuts.get(i+1);File file=new File(c.getCacheDir(),r.id+"-block-"+i+".m4a");
                if(compress)AudioConvert.convert(source,file,a,b,http,(s,p,t)->{},32000);else remuxRange(source,file,a,b,http);
                parts.add(new Part(file,a/1000d,b-a));
            }
            return parts;
        }catch(Exception e){for(Part part:parts)part.file.delete();throw e;}
    }

    /** Copia un tramo sin recodificar (AAC → AAC). Si el origen no es AAC, se recodifica. */
    static void remuxRange(File source,File target,long fromMs,long toMs,HttpApi http)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean ok=false,started=false;
        try{
            extractor.setDataSource(source.getPath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)throw new HttpApi.UserAction("El archivo no tiene una pista de audio válida.");
            if(!"audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME))){extractor.release();extractor=null;AudioConvert.convert(source,target,fromMs,toMs,http);ok=true;return;}
            extractor.selectTrack(track);extractor.seekTo(fromMs*1000,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            muxer=new MediaMuxer(target.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int out=muxer.addTrack(format);muxer.start();started=true;
            ByteBuffer bytes=ByteBuffer.allocate(1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long base=-1;
            while(true){
                http.check();long time=extractor.getSampleTime();if(time<0||time>=toMs*1000)break;
                bytes.clear();int size=extractor.readSampleData(bytes,0);if(size<0)break;if(base<0)base=time;
                int flags=(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0;
                info.set(0,size,time-base,flags);muxer.writeSampleData(out,bytes,info);extractor.advance();
            }
            muxer.stop();started=false;ok=true;
        }finally{if(extractor!=null)extractor.release();if(muxer!=null){try{if(started)muxer.stop();}catch(Exception ignored){}muxer.release();}if(!ok)target.delete();}
    }

    /**
     * Momento más silencioso (ventanas de 250 ms) dentro de centro±margen. Decodifica solo ese tramo,
     * así que cuesta pocos segundos aunque el audio dure una hora. Ante cualquier problema devuelve el centro.
     */
    static long quietest(File source,long centerMs,long spanMs,HttpApi http){
        MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null;
        long fromMs=Math.max(0,centerMs-spanMs),toMs=centerMs+spanMs;final long window=250;
        try{
            extractor.setDataSource(source.getPath());MediaFormat format=null;int track=-1;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)return centerMs;
            extractor.selectTrack(track);extractor.seekTo(fromMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            TreeMap<Long,double[]> energy=new TreeMap<>();boolean inputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long guard=System.currentTimeMillis();
            while(System.currentTimeMillis()-guard<20_000){
                http.check();
                if(!inputDone){int in=decoder.dequeueInputBuffer(2000);if(in>=0){ByteBuffer buffer=decoder.getInputBuffer(in);int size=extractor.readSampleData(buffer,0);long pts=extractor.getSampleTime();
                    if(size<0||pts>toMs*1000){decoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(in,0,size,pts,0);extractor.advance();}}}
                int out=decoder.dequeueOutputBuffer(info,2000);
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=decoder.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}
                else if(out>=0){
                    if(info.size>0){ShortBuffer samples=decoder.getOutputBuffer(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();int count=info.size/2;
                        for(int i=0;i<count;i++){long ms=info.presentationTimeUs/1000+(long)(i/channels)*1000L/rate;if(ms<fromMs||ms>=toMs)continue;double v=samples.get(i)/32768d;double[] e=energy.computeIfAbsent(ms/window,k->new double[2]);e[0]+=v*v;e[1]++;}}
                    boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(out,false);if(end)break;
                }
            }
            long best=-1;double min=Double.MAX_VALUE;
            for(Map.Entry<Long,double[]> e:energy.entrySet()){if(e.getValue()[1]<10)continue;double mean=e.getValue()[0]/e.getValue()[1];if(mean<min){min=mean;best=e.getKey();}}
            return best<0?centerMs:best*window+window/2;
        }catch(Exception e){return centerMs;}
        finally{extractor.release();if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}}
    }

    /**
     * Muestras de voz del primer bloque: para cada persona (máx. 4, las que más hablan) se toma una intervención
     * de 2–10 s y se envía como data URL. Así el modelo reconoce a las mismas personas en los demás bloques.
     */
    static List<String[]> references(Context c,Recording r,JSONObject first,HttpApi http){
        List<String[]> refs=new ArrayList<>();JSONArray segments=first.optJSONArray("segments");if(segments==null)return refs;
        Map<String,Double> talk=new HashMap<>();Map<String,JSONObject> best=new HashMap<>();
        for(int i=0;i<segments.length();i++){JSONObject s=segments.optJSONObject(i);if(s==null||s.isNull("speaker"))continue;String who=s.optString("speaker");double d=s.optDouble("end")-s.optDouble("start");talk.merge(who,d,Double::sum);
            if(d>=2.5){JSONObject prior=best.get(who);double pd=prior==null?999:Math.abs(prior.optDouble("end")-prior.optDouble("start")-6);if(Math.abs(d-6)<pd)best.put(who,s);}}
        List<String> order=new ArrayList<>(best.keySet());order.sort((a,b)->Double.compare(talk.get(b),talk.get(a)));
        for(String who:order.subList(0,Math.min(4,order.size()))){
            JSONObject s=best.get(who);double start=s.optDouble("start")+0.2,end=Math.min(s.optDouble("end")-0.1,start+8);if(end-start<2)continue;
            File clip=new File(c.getCacheDir(),r.id+"-ref-"+refs.size()+".m4a");
            try{AudioConvert.convert(r.audio(c),clip,(long)(start*1000),(long)(end*1000),http);byte[] data=java.nio.file.Files.readAllBytes(clip.toPath());
                refs.add(new String[]{who,"data:audio/mp4;base64,"+android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP)});}
            catch(Exception ignored){}finally{clip.delete();}
        }
        return refs;
    }

    /** Divisor anterior por bytes (se conserva para las pruebas de regresión). */
    static List<Part> prepare(Context c,Recording r,HttpApi http)throws Exception{
        return prepare(c,r,http,24_000_000,18_000_000);
    }
    static List<Part> prepare(Context c,Recording r,HttpApi http,long singleLimit,long partLimit)throws Exception{
        if(r.audio(c).length()<=singleLimit && r.duration<=15*60*1000)return java.util.Collections.singletonList(new Part(r.audio(c),0));
        List<Part> parts=new ArrayList<>(); MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;
        try{
            extractor.setDataSource(r.audio(c).getPath());int track=-1;
            for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}
            if(track<0)throw new HttpApi.UserAction("El archivo no tiene una pista de audio válida.");
            MediaFormat format=extractor.getTrackFormat(track);extractor.selectTrack(track);
            ByteBuffer bytes=ByteBuffer.allocate(1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long base=0,partBytes=0;int target=-1;
            while(extractor.getSampleTime()>=0){
                http.check();long time=extractor.getSampleTime();
                if(muxer==null || partBytes>=partLimit || time-base>=15L*60*1_000_000){
                    if(muxer!=null){muxer.stop();muxer.release();muxer=null;}
                    base=time;partBytes=0;File file=new File(c.getCacheDir(),r.id+"-part-"+parts.size()+".m4a");
                    muxer=new MediaMuxer(file.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);target=muxer.addTrack(format);muxer.start();parts.add(new Part(file,base/1_000_000d));
                }
                bytes.clear();int size=extractor.readSampleData(bytes,0);if(size<0)break;
                int flags=(extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0;
                info.set(0,size,time-base,flags);muxer.writeSampleData(target,bytes,info);partBytes+=size;extractor.advance();
            }
            if(muxer!=null){muxer.stop();muxer.release();muxer=null;}
            return parts;
        }catch(Exception e){for(Part part:parts)part.file.delete();throw e;}finally{extractor.release();if(muxer!=null)try{muxer.release();}catch(Exception ignored){}}
    }
}
