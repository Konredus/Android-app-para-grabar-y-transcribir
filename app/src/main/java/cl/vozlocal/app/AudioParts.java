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

    /** Carpeta persistente de bloques: sobreviven a reinicios, así un reintento no repite la preparación. */
    static File blockDir(Context c,String id){File d=new File(c.getFilesDir(),"blocks/"+id);d.mkdirs();return d;}
    static void clearBlocks(Context c,String id){File d=new File(c.getFilesDir(),"blocks/"+id);File[] files=d.listFiles();if(files!=null)for(File f:files)f.delete();d.delete();}

    /**
     * Divide en bloques cortados en pausas. Nunca recodifica (solo copia tramos AAC): es rápido y no depende
     * de los códecs del teléfono. cached: cortes ya calculados en un intento anterior (se reutilizan).
     * Devuelve los bloques; los cortes usados quedan en cutsOut.
     */
    static List<Part> plan(Context c,Recording r,HttpApi http,long targetMs,JSONArray cached,List<Long> cutsOut,Log log)throws Exception{
        return plan(c,r,http,targetMs,cached,cutsOut,log,SINGLE_MAX_MS);
    }
    /**
     * singleMaxMs: hasta esta duración el audio va en un solo bloque. Con OpenAI son 12 min; con OpenRouter lo decide la
     * receta del modelo, y baja si el proveedor respondió que el envío era muy grande (ver Transcriber).
     */
    static List<Part> plan(Context c,Recording r,HttpApi http,long targetMs,JSONArray cached,List<Long> cutsOut,Log log,long singleMaxMs)throws Exception{
        File source=r.audio(c);long total=r.duration>0?r.duration:AudioConvert.duration(source);targetMs=Math.max(targetMs,60_000);
        List<Long> cuts=new ArrayList<>();
        if(cached!=null&&cached.length()>=2){for(int i=0;i<cached.length();i++)cuts.add(cached.getLong(i));if(log!=null)log.line(Lang.plural(c,R.plurals.eng_log_cuts_reused,cuts.size()-1));}
        else{
            cuts.add(0L);
            if(total>singleMaxMs||source.length()>20_000_000){
                if(log!=null)log.line(Lang.str(c,R.string.eng_log_finding_pauses));
                for(long t=targetMs;t<total-targetMs*0.4;t+=targetMs){
                    long q=quietest(source,t,10_000,http);long last=cuts.get(cuts.size()-1);
                    if(q>last+30_000&&q<total-20_000)cuts.add(q);
                }
            }
            cuts.add(total);
            // Resguardo de tamaño (25 MB por envío): si un tramo pesaría más de 20 MB se divide por la mitad.
            double bytesPerMs=source.length()/(double)Math.max(1,total);
            for(int i=0;i+1<cuts.size();i++){long a=cuts.get(i),b=cuts.get(i+1);if((b-a)*bytesPerMs>20_000_000){long mid=a+(b-a)/2,q=quietest(source,mid,10_000,http);cuts.add(i+1,q>a+30_000&&q<b-30_000?q:mid);i--;}}
        }
        cutsOut.clear();cutsOut.addAll(cuts);
        if(cuts.size()==2)return Collections.singletonList(new Part(source,0,total));
        List<Part> parts=new ArrayList<>();File dir=blockDir(c,r.id);
        for(int i=0;i+1<cuts.size();i++){
            http.check();long a=cuts.get(i),b=cuts.get(i+1);File file=new File(dir,"block-"+i+".m4a");
            // Se escribe a un temporal y se renombra: si Android corta a la mitad, no queda un bloque dañado.
            if(!file.exists()||file.length()==0){File tmp=new File(dir,"block-"+i+".tmp");remuxRange(source,tmp,a,b,http);if(!tmp.renameTo(file))throw new IOException(Lang.str(c,R.string.eng_err_save_block));}
            parts.add(new Part(file,a/1000d,b-a));
        }
        return parts;
    }

    /** Copia un tramo sin recodificar (AAC → AAC). Si el origen no es AAC, se recodifica. */
    static void remuxRange(File source,File target,long fromMs,long toMs,HttpApi http)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean ok=false,started=false;
        try{
            extractor.setDataSource(source.getPath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}}
            if(track<0)throw new HttpApi.UserAction(Lang.str(R.string.eng_err_no_track));
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

    /** Muestra de voz elegida: etiqueta del bloque 1 y tramo ya recortado (s). */
    static final class Clip{final String label;final double start,end;Clip(String label,double start,double end){this.label=label;this.start=start;this.end=end;}}
    /** Solo se toma muestra de quien habla al menos esto en el bloque 1 (menos es poco confiable). */
    static final double MIN_TALK_S=10;
    /**
     * Elige muestras LIMPIAS del bloque 1, a lo más limit en total:
     * - tramos con texto (3 palabras o más) de 2,5 s o más;
     * - sin otra voz encima ni pegada (±0,5 s): si el modelo mezcló dos voces ahí, la muestra contagiaría el error a todos los bloques;
     * - de preferencia en medio de una racha de la misma persona o rodeados de pausa, y cerca de 7 s;
     * - solo de personas que hablan 10 s o más; con 1–2 personas, 2 muestras de cada una.
     * exclude: etiquetas que no necesitan muestra (p. ej. la voz del usuario, ya reconocida con "Mi voz").
     * También se usa sobre la transcripción COMPLETA («Segunda pasada con tus correcciones», ver Retranscribe): por eso
     * la búsqueda de voces encimadas trabaja sobre arreglos simples (una transcripción de 52 min tiene ~1500 tramos).
     */
    static List<Clip> pickReferences(JSONArray segments,int limit,Set<String> exclude){
        List<JSONObject> segs=new ArrayList<>();
        if(segments!=null)for(int i=0;i<segments.length();i++){JSONObject s=segments.optJSONObject(i);if(s!=null&&!s.isNull("speaker")&&!s.optString("text").trim().isEmpty())segs.add(s);}
        int count=segs.size();double[] from=new double[count],to=new double[count];int[] voice=new int[count];Map<String,Integer> voices=new HashMap<>();
        for(int i=0;i<count;i++){JSONObject s=segs.get(i);from[i]=s.optDouble("start");to[i]=s.optDouble("end");Integer v=voices.get(s.optString("speaker"));if(v==null){v=voices.size();voices.put(s.optString("speaker"),v);}voice[i]=v;}
        Map<String,Double> talk=new HashMap<>();for(JSONObject s:segs)talk.merge(s.optString("speaker"),s.optDouble("end")-s.optDouble("start"),Double::sum);
        Map<String,List<double[]>> candidates=new HashMap<>();
        for(int i=0;i<count;i++){
            JSONObject s=segs.get(i);String who=s.optString("speaker");if(exclude!=null&&exclude.contains(who))continue;
            double a=from[i],b=to[i],d=b-a;if(d<2.5||s.optString("text").trim().split("\\s+").length<3)continue;
            boolean crowded=false;for(int j=0;j<count;j++)if(j!=i&&voice[j]!=voice[i]&&from[j]<b+0.5&&to[j]>a-0.5){crowded=true;break;}
            if(crowded)continue;
            int prev=i-1,next=i+1<count?i+1:-1;
            boolean calm=(prev<0||voice[prev]==voice[i]||a-to[prev]>1)&&(next<0||voice[next]==voice[i]||from[next]-b>1);
            double start=a+0.4,end=Math.min(b-0.4,start+9.5);if(end-start<2)continue;
            candidates.computeIfAbsent(who,k->new ArrayList<>()).add(new double[]{Math.abs(d-7)-(calm?5:0),start,end});
        }
        List<String> people=new ArrayList<>();for(String who:candidates.keySet())if(talk.getOrDefault(who,0d)>=MIN_TALK_S)people.add(who);
        people.sort((x,y)->Double.compare(talk.get(y),talk.get(x)));
        for(List<double[]> list:candidates.values())list.sort((x,y)->Double.compare(x[0],y[0]));
        List<Clip> out=new ArrayList<>();
        for(String who:people){if(out.size()>=limit)break;double[] c=candidates.get(who).get(0);out.add(new Clip(who,c[1],c[2]));}
        if(people.size()<=2)for(String who:people){if(out.size()>=limit)break;List<double[]> list=candidates.get(who);if(list.size()>1){double[] c=list.get(1);out.add(new Clip(who,c[1],c[2]));}}
        return out;
    }
    /**
     * Muestras de voz del primer bloque para reconocer a las mismas personas en los demás bloques.
     * Devuelve {nombre enviado ("voz_1", "voz_1b"…), data URL, voz destino ("block0:A"), descripción para la bitácora}.
     * Los nombres son únicos a propósito: el modelo llama "A", "B"… a las voces que no reconoce, y enviar esas mismas
     * letras podía pegar una voz nueva a otra persona.
     */
    static List<String[]> references(Context c,Recording r,JSONObject first,HttpApi http,int limit,Set<String> exclude){
        List<String[]> refs=new ArrayList<>();JSONArray segments=first.optJSONArray("segments");if(segments==null||limit<=0)return refs;
        // Número visible de cada voz del bloque 1 (Persona N por orden de aparición), para que la bitácora hable el mismo idioma que la pantalla.
        List<String> appearance=new ArrayList<>();for(int i=0;i<segments.length();i++){JSONObject s=segments.optJSONObject(i);if(s!=null&&!s.isNull("speaker")&&!s.optString("text").trim().isEmpty()&&!appearance.contains(s.optString("speaker")))appearance.add(s.optString("speaker"));}
        Map<String,Integer> taken=new HashMap<>();
        for(Clip clip:pickReferences(segments,limit,exclude)){
            File file=new File(c.getCacheDir(),r.id+"-ref-"+refs.size()+".m4a");
            try{AudioConvert.convert(r.audio(c),file,(long)(clip.start*1000),(long)(clip.end*1000),http);byte[] data=java.nio.file.Files.readAllBytes(file.toPath());
                int person=appearance.indexOf(clip.label)+1;int n=taken.merge(clip.label,1,Integer::sum);
                refs.add(new String[]{"voz_"+person+(n>1?"b":""),"data:audio/mp4;base64,"+android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP),"block0:"+clip.label,
                    Lang.str(c,R.string.speaker_n,person)+" ("+Recording.time((long)(clip.start*1000))+"–"+Recording.time((long)(clip.end*1000))+")"});}
            catch(Exception ignored){}finally{file.delete();}
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
            if(track<0)throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_no_track));
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
