package cl.vozlocal.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pruebas de la parte «audio» de la 0.8.0 (ver docs/diseno/SPEC-0.8.md). Sin red ni claves reales.
 * Todo el audio es sintético (tonos puros escritos aquí como WAV), así que cada resultado se puede calcular de
 * antemano: cuántas muestras deben salir, qué tono debe sonar en cada tramo y con qué volumen.
 * Lo que produce OrAudio se lee por un camino propio (cabecera WAV a mano; FLAC con MediaExtractor + MediaCodec),
 * no con el decodificador de OrAudio, para que un error suyo no se tape a sí mismo.
 */
final class OrAudioChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Valor de la onda (en unidades de 16 bits) en el canal y el instante dados. */
    private interface Wave{double at(int channel,double t);}

    static void run(Context c,Recording r)throws Exception{
        File dir=new File(c.getCacheDir(),"or-audio-check");clear(dir);check(dir.mkdirs(),"Could not create the OrAudio test folder");
        long started=SystemClock.elapsedRealtime();
        try{
            resampling(dir);
            formats(dir);
            recording(dir,r.audio(c));
            anchors(dir);
            failures(dir);
            secondRound(dir);
            thirdRound(dir);
            android.util.Log.i("VozLocalTest","OrAudio checks: "+(SystemClock.elapsedRealtime()-started)+" ms");
        }finally{clear(dir);}
    }

    // ---------- Duración, 16 kHz mono y filtro anti-alias ----------
    private static void resampling(File dir)throws Exception{
        // 44,1 kHz → 16 kHz (razón 160/441, la de las grabaciones de la app). El tono de 1 kHz debe pasar intacto; el de
        // 12 kHz queda sobre la mitad de la frecuencia nueva y, sin filtro, reaparecería como un tono falso de 4 kHz.
        File mix=wav(new File(dir,"mix.wav"),44100,1,3000,(ch,t)->tone(1000,10000,t)+tone(12000,10000,t));long size=mix.length();
        OrAudio.Built b=OrAudio.build(mix,null,new File(dir,"out-mix"),new HttpApi());short[] s=samples(b);
        check(b.leadMs==0&&b.anchors!=null&&b.anchors.length==0,"Audio without anchors has lead time or anchors");
        check(Math.abs(b.durationMs-3000)<=50,"44.1 kHz duration wrong: "+b.durationMs+" ms");
        check(Math.abs(b.durationMs*16-s.length)<=8,"Declared duration differs from the file: "+b.durationMs+" ms vs "+s.length+" samples");
        double kept=amplitude(s,1600,46400,1000),alias=amplitude(s,1600,46400,4000),level=rms(s,1600,46400);
        check(Math.abs(kept-10000)<300,"1 kHz tone not preserved: "+kept);
        check(alias<50,"12 kHz tone aliased into 4 kHz: "+alias);
        check(Math.abs(level-10000/Math.sqrt(2))<220,"Level after filtering wrong (12 kHz not removed?): "+level);
        check(mix.length()==size&&!new File(dir,"out-mix.tmp").exists(),"Source modified or temporary file left behind");
        // 48 kHz estéreo (3:1) con el tono solo en el canal izquierdo: en mono debe quedar a la mitad.
        File stereo=wav(new File(dir,"stereo.wav"),48000,2,2000,(ch,t)->ch==0?tone(1000,12000,t):0);
        b=OrAudio.build(stereo,null,new File(dir,"out-stereo"),new HttpApi());s=samples(b);
        check(Math.abs(b.durationMs-2000)<=50&&Math.abs(b.durationMs*16-s.length)<=8,"48 kHz duration wrong: "+b.durationMs+" ms, "+s.length+" samples");
        double half=amplitude(s,1600,30400,1000);check(Math.abs(half-6000)<180,"Stereo not mixed to mono by averaging: "+half);
        // 16 kHz mono ya es el formato final: debe salir igual, muestra por muestra.
        File same=wav(new File(dir,"same.wav"),16000,1,1000,(ch,t)->tone(440,8000,t));
        b=OrAudio.build(same,null,new File(dir,"out-same"),new HttpApi());s=samples(b);
        check(s.length==16000&&b.durationMs==1000,"16 kHz audio changed length: "+s.length+" samples, "+b.durationMs+" ms");
        for(int i=0;i<s.length;i++)check(s[i]==(short)Math.round(tone(440,8000,i/16000d)),"16 kHz audio not copied exactly at sample "+i);
    }

    // ---------- FLAC y WAV ----------
    private static void formats(File dir)throws Exception{
        File mix=new File(dir,"mix.wav");
        OrAudio.Built auto=OrAudio.build(mix,null,new File(dir,"auto"),new HttpApi()),forced=OrAudio.build(mix,null,new File(dir,"forced"),new HttpApi(),false);
        check(forced.format.equals("wav")&&forced.file.equals(new File(dir,"forced.wav")),"Forced WAV produced "+forced.format+" at "+forced.file.getName());
        check(forced.file.length()==44+forced.durationMs*32,"WAV size does not match its duration: "+forced.file.length());
        boolean encoder=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC,16000,1))!=null;
        android.util.Log.i("VozLocalTest","OrAudio format: "+auto.format+" · FLAC encoder on this device: "+encoder+" · "+auto.file.length()+" bytes vs "+forced.file.length()+" as WAV");
        // Donde hay codificador FLAC, lo normal debe ser FLAC: caer a WAV sin que nadie lo note dejaría cada envío al doble de tamaño.
        // Se exige desde Android 10: el codificador anterior (8 y 9) puede no vaciar el último cuadro, y ahí el respaldo WAV es lo correcto.
        // Al revés no se exige nada: si la lista de códecs no lo anuncia pero funcionó, mejor.
        if(encoder&&Build.VERSION.SDK_INT>=29)check(auto.format.equals("flac"),"FLAC encoder present but OrAudio fell back to WAV: "+OrAudio.flacIssue);
        check(auto.format.equals("flac")||auto.format.equals("wav"),"Unknown format: "+auto.format);
        check(auto.file.equals(new File(dir,"auto."+auto.format)),"Output name wrong: "+auto.file.getName());
        // FLAC no pierde nada: leído de vuelta debe dar exactamente el mismo audio que el WAV.
        short[] a=samples(auto),w=samples(forced);
        check(Arrays.equals(a,w)&&auto.durationMs==forced.durationMs,"FLAC and WAV do not hold the same audio ("+a.length+" vs "+w.length+" samples)");
        if(auto.format.equals("flac"))check(auto.file.length()<forced.file.length(),"FLAC is not smaller than WAV");
        check(!new File(dir,"auto.tmp").exists()&&!new File(dir,"forced.tmp").exists(),"Temporary file left behind");
        // Un archivo viejo con el mismo nombre (de cualquiera de los dos formatos) no sobrevive: queda solo el nuevo.
        File oldFlac=new File(dir,"stale.flac"),oldWav=new File(dir,"stale.wav");Files.write(oldFlac.toPath(),new byte[]{1,2,3});Files.write(oldWav.toPath(),new byte[]{1,2,3});
        OrAudio.Built fresh=OrAudio.build(mix,null,new File(dir,"stale"),new HttpApi());
        check(fresh.file.length()>1000&&(oldFlac.exists()^oldWav.exists()),"Stale output with the same name survived");
        check(Arrays.equals(samples(fresh),w),"Rebuilt file differs");
    }

    // ---------- La grabación real de la prueba (AAC del micrófono del emulador) ----------
    private static void recording(File dir,File audio)throws Exception{
        long size=audio.length(),nominal=AudioConvert.duration(audio);
        OrAudio.Built b=OrAudio.build(audio,null,new File(dir,"recording"),new HttpApi());short[] s=samples(b);
        // El contenedor AAC declara su duración con relleno: por eso aquí la tolerancia es mayor que con los tonos.
        check(Math.abs(b.durationMs-nominal)<300,"Recording duration wrong: "+b.durationMs+" vs "+nominal);
        check(Math.abs(b.durationMs*16-s.length)<=8&&b.leadMs==0,"Recording: declared duration differs from the file");
        check(audio.length()==size,"Conversion modified the original recording");
    }

    // ---------- Anclas: tiempos exactos, silencios, volumen y clips ilegibles ----------
    private static void anchors(File dir)throws Exception{
        File block=wav(new File(dir,"block.wav"),44100,1,3000,(ch,t)->tone(300,8000,t));
        // 0: muestra como las reales (AAC .m4a), 600 Hz. 1: ilegible. 2: baja (2000), debe subir ×4 hasta el volumen del audio.
        // 3: no existe. 4: null. 5: más fuerte que el audio, no se toca. 6: muy baja (500), sube solo hasta el tope ×8.
        // 7: baja pero con un pico de 30000: no se sube, porque el pico se recortaría.
        File voice=new File(dir,"voice.m4a");AudioConvert.convert(wav(new File(dir,"voice.wav"),44100,1,2000,(ch,t)->tone(600,8000,t)),voice,0,2000,new HttpApi());
        File bad=new File(dir,"bad.m4a");try(FileOutputStream out=new FileOutputStream(bad)){for(int i=0;i<300;i++)out.write("esto no es audio ".getBytes(StandardCharsets.UTF_8));}
        File low=wav(new File(dir,"low.wav"),16000,1,1500,(ch,t)->tone(900,2000,t));
        File loud=wav(new File(dir,"loud.wav"),16000,1,1000,(ch,t)->tone(900,20000,t));
        File faint=wav(new File(dir,"faint.wav"),16000,1,1000,(ch,t)->tone(900,500,t));
        File spike=wav(new File(dir,"spike.wav"),16000,1,1000,(ch,t)->Math.round(t*16000)==8000?30000:tone(900,1000,t));
        List<File> list=Arrays.asList(voice,bad,low,new File(dir,"missing.m4a"),null,loud,faint,spike);
        OrAudio.Built b=OrAudio.build(block,list,new File(dir,"anchored"),new HttpApi());short[] s=samples(b);
        check(b.anchors.length==8,"Anchors array size wrong: "+b.anchors.length);
        for(int i:new int[]{1,3,4})check(b.anchors[i].length==2&&b.anchors[i][0]==-1&&b.anchors[i][1]==-1,"Unreadable anchor "+i+" not marked {-1,-1}");
        // Cadena de tiempos: cada ancla empieza justo donde termina el segundo de silencio de la anterior.
        int[] valid={0,2,5,6,7};long[] length={-1,1500,1000,1000,1000};double[] expected={8000,8000,20000,4000,1000},margin={1600,600,300,300,60};
        long cursor=0;
        for(int k=0;k<valid.length;k++){
            int i=valid[k];long from=b.anchors[i][0],to=b.anchors[i][1];
            check(b.anchors[i].length==2&&from==cursor&&to>from,"Anchor "+i+" at "+from+"–"+to+" ms, expected to start at "+cursor);
            // La muestra AAC trae el relleno del códec (decenas de ms); las WAV deben medir exactamente lo que duran.
            if(length[k]<0)check(Math.abs(to-from-2000)<=250,"AAC anchor length wrong: "+(to-from)+" ms");
            else check(to-from==length[k],"Anchor "+i+" length "+(to-from)+" ms, expected "+length[k]);
            check(to+OrAudio.GAP_MS<=s.length/16&&silent(s,to,to+OrAudio.GAP_MS),"No exact second of silence after anchor "+i);
            double tone=amplitude(s,from+100,to-100,i==0?600:900),other=amplitude(s,from+100,to-100,300);
            check(Math.abs(tone-expected[k])<=margin[k],"Anchor "+i+" level "+tone+", expected "+expected[k]);
            check(other<100,"The block's tone leaked into anchor "+i+": "+other);
            // En las WAV el sonido llega hasta los bordes declarados (salvo los 5 ms de entrada y salida suaves).
            if(i!=0)check(rms(s,(int)from*16,(int)from*16+320)>expected[k]*0.3&&rms(s,(int)to*16-320,(int)to*16)>expected[k]*0.3,"Anchor "+i+" does not fill its declared window");
            cursor=to+OrAudio.GAP_MS;
        }
        check(peak(s,(int)b.anchors[7][0]*16,(int)b.anchors[7][1]*16)==30000,"Peak of the spiky anchor changed: clipped or boosted");
        check(b.leadMs==cursor,"leadMs "+b.leadMs+" but the anchors end at "+cursor);
        // El bloque empieza exactamente en leadMs, con su propio tono y su duración completa.
        check(Math.abs(b.durationMs-(b.leadMs+3000))<=1&&Math.abs(b.durationMs*16-s.length)<=8,"Anchored duration wrong: "+b.durationMs+" ms, lead "+b.leadMs+", "+s.length+" samples");
        int lead=(int)b.leadMs*16;
        check(s[lead-1]==0&&rms(s,lead,lead+320)>2000,"The block does not start at leadMs");
        double own=amplitude(s,b.leadMs+100,b.leadMs+2900,300);
        check(Math.abs(own-8000)<240,"Block tone after the anchors wrong: "+own);
        check(amplitude(s,b.leadMs+100,b.leadMs+2900,600)<100&&amplitude(s,b.leadMs+100,b.leadMs+2900,900)<100,"Anchor tones leaked into the block");
        check(peak(s,0,s.length)<=30000,"Something was boosted into clipping");
    }

    // ---------- Audio ilegible y cancelación ----------
    private static void failures(File dir)throws Exception{
        File mix=new File(dir,"mix.wav"),bad=new File(dir,"bad.m4a");
        // Audio que no se puede leer: error claro (no un archivo vacío) y nada queda a medias.
        boolean failed=false;try{OrAudio.build(bad,null,new File(dir,"broken"),new HttpApi());}catch(InterruptedIOException e){throw e;}catch(Exception e){failed=true;}
        check(failed&&leftovers(dir,"broken")==0,"Unreadable audio did not fail cleanly");
        // La salida nunca puede caer sobre el original (aquí «mix» + «.wav» sería el propio audio): se rechaza sin tocarlo.
        long size=mix.length();boolean refused=false;try{OrAudio.build(mix,null,new File(dir,"mix"),new HttpApi());}catch(IOException e){refused=true;}
        check(refused&&mix.length()==size&&!new File(dir,"mix.flac").exists()&&!new File(dir,"mix.tmp").exists(),"Output over the source audio was not refused");
        // Todas las anclas ilegibles: el bloque sale igual, sin zona de anclas.
        OrAudio.Built b=OrAudio.build(mix,Arrays.asList(bad,bad),new File(dir,"plain"),new HttpApi());
        check(b.leadMs==0&&b.anchors.length==2&&b.anchors[0][0]==-1&&b.anchors[1][1]==-1&&Math.abs(b.durationMs-3000)<=50,"Block with only unreadable anchors is wrong");
        // Cancelado antes de empezar.
        HttpApi before=new HttpApi();before.cancel();boolean stopped=false;
        try{OrAudio.build(mix,null,new File(dir,"cancel-a"),before);}catch(InterruptedIOException e){stopped=true;}
        check(stopped&&leftovers(dir,"cancel-a")==0,"Cancelled conversion ran or left files");
        // Cancelado a mitad de camino: 10 s de audio dan decenas de vueltas del decodificador; aquí se corta en la cuarta
        // revisión, cuando el archivo temporal ya está abierto. No debe quedar ni el temporal ni un archivo a medias.
        File longer=wav(new File(dir,"long.wav"),44100,1,10_000,(ch,t)->tone(500,6000,t));
        HttpApi midway=new HttpApi(){int checks;@Override void check()throws InterruptedIOException{if(++checks>3)this.cancelled=true;super.check();}};
        stopped=false;try{OrAudio.build(longer,null,new File(dir,"cancel-b"),midway);}catch(InterruptedIOException e){stopped=true;}
        check(stopped&&leftovers(dir,"cancel-b")==0,"Cancelling midway did not stop or left files");
        // El decodificador se detiene apenas se cancela: sin cancelar entregaría unas 40 tandas.
        HttpApi token=new HttpApi();int[] writes={0};stopped=false;
        try{OrAudio.decode(longer,Long.MAX_VALUE,token,(data,count)->{writes[0]++;token.cancel();});}catch(InterruptedIOException e){stopped=true;}
        check(stopped&&writes[0]>=1&&writes[0]<=8,"Decoder kept going after cancel: "+writes[0]+" writes");
    }

    // ---------- Segunda ronda: disco lleno, audio vacío y preparación cortada por el vigilante ----------
    private static void secondRound(File dir)throws Exception{
        // Disco lleno se decide por la causa del error (ENOSPC), no por el espacio libre medido después de limpiar.
        check(OrAudio.diskFull(new IOException("write failed: ENOSPC (No space left on device)"))
            &&OrAudio.diskFull(new IOException("flac-encode",new IOException("x",new android.system.ErrnoException("write",android.system.OsConstants.ENOSPC))))
            &&!OrAudio.diskFull(new IOException("No audio track"))&&!OrAudio.diskFull(new IOException("Audio decoder stopped advancing"))&&!OrAudio.diskFull(null),"Disk-full detection wrong");
        // Menos de una décima de segundo de audio: un error propio (el cliente se lo dice al usuario en vez de reintentar), sin restos.
        File blip=wav(new File(dir,"blip.wav"),16000,1,50,(ch,t)->tone(440,8000,t));boolean tooShort=false;
        try{OrAudio.build(blip,null,new File(dir,"blip-out"),new HttpApi());}catch(OrAudio.TooShort e){tooShort=true;}
        check(tooShort&&leftovers(dir,"blip-out")==0,"Near-empty audio not reported as too short, or left files");
        // El vigilante corta una preparación trabada: el error pasa tal cual (InterruptedIOException), no queda nada a medias
        // y el FLAC NO se apaga para los bloques siguientes (no fue culpa del codificador).
        File longer=new File(dir,"long.wav");if(!longer.isFile())longer=wav(longer,44100,1,10_000,(ch,t)->tone(500,6000,t));
        HttpApi slow=new HttpApi(){int checks;@Override void check()throws InterruptedIOException{if(++checks==4)abortPreparing("preparar el audio tardó más de 03:00");super.check();}};
        slow.startPreparing();boolean stalled=false;
        try{OrAudio.build(longer,null,new File(dir,"stalled"),slow);}catch(HttpApi.PrepareStalled e){stalled=true;}
        check(stalled&&leftovers(dir,"stalled")==0,"Stalled preparation did not stop cleanly");
        slow.endPreparing(false);
        boolean encoder=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC,16000,1))!=null;
        OrAudio.Built after=OrAudio.build(new File(dir,"mix.wav"),null,new File(dir,"after-stall"),new HttpApi());
        if(encoder&&Build.VERSION.SDK_INT>=29)check(after.format.equals("flac"),"A stalled preparation turned FLAC off: "+OrAudio.flacIssue);
    }

    // ---------- Tercera ronda: velocidad, avance en % y comprobación barata del FLAC ----------
    /**
     * Topes holgados para el emulador (lo esperado es varias veces menos). Antes de la tercera ronda un bloque de 4 min
     * tardaba 31–43 s en un teléfono con la app abierta (~8 s por minuto): estos topes fallan si la conversión vuelve a
     * ese orden. El tiempo medido queda en el registro («VozLocalTest») y en el mensaje si falla.
     * El decodificador AAC del aparato pone un piso que no depende de la app: en el emulador, decodificar 60 s tarda de 5
     * a 14 s según su carga. Por eso ese piso se mide en la misma corrida y el tope es para lo que la app suma encima
     * (filtro, FLAC y verificación), con un 50 % de margen para el ruido entre una medición y otra (en el emulador, lo que
     * la app suma encima varió de 3 a 6 s entre dos corridas). Lo de antes de la tercera ronda sumaba decenas de segundos.
     */
    static final long OVERHEAD_LIMIT_MS=5_000,FILTER_LIMIT_MS=2_000;
    private static void thirdRound(File dir)throws Exception{
        // 1. Caminos directos 48 → 16 kHz (×3) y 32 → 16 kHz (×2): la misma calidad que el polifásico. 12 kHz, sin filtro,
        // reaparecería como un tono falso de 4 kHz en los dos casos.
        for(int rate:new int[]{48000,32000}){
            File mix=wav(new File(dir,"mix"+rate+".wav"),rate,1,2000,(ch,t)->tone(1000,10000,t)+tone(12000,10000,t));
            OrAudio.Built b=OrAudio.build(mix,null,new File(dir,"out-"+rate),new HttpApi());short[] s=samples(b);
            check(Math.abs(b.durationMs-2000)<=50&&Math.abs(b.durationMs*16-s.length)<=8,rate+" Hz duration wrong: "+b.durationMs+" ms, "+s.length+" samples");
            double kept=amplitude(s,1600,30400,1000),alias=amplitude(s,1600,30400,4000);
            check(Math.abs(kept-10000)<300,rate+" Hz: 1 kHz tone not preserved: "+kept);
            check(alias<50,rate+" Hz: 12 kHz tone aliased into 4 kHz: "+alias);
        }
        // 2. El filtro solo (sin el decodificador): 60 s a 44,1 kHz dan exactamente 960 000 muestras de 16 kHz.
        float[] pcm=new float[44100*60];for(int i=0;i<pcm.length;i++)pcm[i]=(float)tone(440,8000,i/44100d);
        long started=SystemClock.elapsedRealtime();long produced=OrAudio.resample(44100,pcm,(data,count)->{});long filter=SystemClock.elapsedRealtime()-started;
        android.util.Log.i("VozLocalTest","OrAudio filter: 60 s at 44.1 kHz in "+filter+" ms");
        check(produced==960_000,"Resampler output length wrong: "+produced);
        check(filter<FILTER_LIMIT_MS,"Resampling filter too slow: 60 s took "+filter+" ms (limit "+FILTER_LIMIT_MS+" ms)");
        // 3. De punta a punta, como graba la app (AAC a 44,1 kHz), con el avance en % que ve la etapa «Preparando audio».
        File source=wav(new File(dir,"speech60.wav"),44100,1,60_000,(ch,t)->tone(220,6000,t)+tone(1800,2000,t));
        File aac=new File(dir,"speech60.m4a");AudioConvert.convert(source,aac,0,60_000,new HttpApi());source.delete();
        started=SystemClock.elapsedRealtime();OrAudio.decode(aac,Long.MAX_VALUE,new HttpApi(),(data,count)->{},null);long floor=SystemClock.elapsedRealtime()-started;
        List<Integer> seen=new ArrayList<>();HttpApi watch=new HttpApi();watch.onPrepareProgress=seen::add;watch.startPreparing();
        started=SystemClock.elapsedRealtime();OrAudio.Built b=OrAudio.build(aac,null,new File(dir,"speed"),watch);long took=SystemClock.elapsedRealtime()-started;
        watch.endPreparing(true);
        long limit=floor*3/2+OVERHEAD_LIMIT_MS;
        android.util.Log.i("VozLocalTest","OrAudio speed: 60 s AAC 44.1 kHz -> "+b.format+" in "+took+" ms (decoder alone "+floor+" ms, limit "+limit+" ms)");
        check(Math.abs(b.durationMs-60_000)<300,"60 s conversion duration wrong: "+b.durationMs);
        check(took<limit,"Conversion too slow: 60 s of audio took "+took+" ms; the decoder alone took "+floor+" ms (limit "+limit+" ms)");
        boolean rising=true;for(int i=1;i<seen.size();i++)rising&=seen.get(i)>seen.get(i-1);
        check(seen.size()>=5&&rising&&seen.get(seen.size()-1)==100,"Preparation progress not reported in rising % ending at 100: "+seen);
        // 4. La comprobación barata cuenta lo mismo que un decodificador de verdad, y nota un final cortado o un byte dañado.
        if(b.format.equals("flac")){
            check(OrAudio.flacSamples(b.file)==samples(b).length,"Cheap FLAC check counts a different length than the decoder");
            byte[] all=Files.readAllBytes(b.file.toPath());long whole=OrAudio.flacSamples(b.file);
            File cut=new File(dir,"cut.flac");Files.write(cut.toPath(),Arrays.copyOf(all,all.length-200));
            boolean caught=false;try{caught=OrAudio.flacSamples(cut)!=whole;}catch(IOException e){caught=true;}
            check(caught,"Cut FLAC passed the cheap check");
            byte[] bad=all.clone();bad[bad.length/2]^=0x5a;File broken=new File(dir,"broken.flac");Files.write(broken.toPath(),bad);
            caught=false;try{caught=OrAudio.flacSamples(broken)!=whole;}catch(IOException e){caught=true;}
            check(caught,"Damaged FLAC frame passed the cheap check");
        }
    }

    // ---------- Utilidades ----------
    private static double tone(double hz,double amplitude,double t){return amplitude*Math.sin(2*Math.PI*hz*t);}
    /** WAV PCM de 16 bits con la onda dada. */
    private static File wav(File file,int rate,int channels,int ms,Wave wave)throws Exception{
        int frames=(int)((long)rate*ms/1000),bytes=frames*channels*2;ByteBuffer pcm=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
        pcm.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+bytes).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for(int i=0;i<frames;i++)for(int ch=0;ch<channels;ch++)pcm.putShort((short)Math.round(wave.at(ch,(double)i/rate)));
        try(FileOutputStream out=new FileOutputStream(file)){out.write(pcm.array());}
        return file;
    }
    /** Las muestras del archivo que produjo OrAudio. De paso comprueba el formato: nombre, cabecera, 16 kHz, mono, 16 bits. */
    private static short[] samples(OrAudio.Built b)throws Exception{
        check(b.file.isFile()&&b.file.getName().endsWith("."+b.format),"Output file missing or extension wrong: "+b.file.getName()+" ("+b.format+")");
        if(b.format.equals("wav"))return wavSamples(b.file);
        check(b.format.equals("flac"),"Unknown format: "+b.format);
        byte[] mark=new byte[4];try(FileInputStream in=new FileInputStream(b.file)){check(in.read(mark)==4&&new String(mark,StandardCharsets.US_ASCII).equals("fLaC"),"FLAC file does not start with fLaC");}
        return flacSamples(b.file);
    }
    private static short[] wavSamples(File file)throws Exception{
        byte[] all=Files.readAllBytes(file.toPath());check(all.length>=44,"WAV shorter than its header");ByteBuffer h=ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        check(new String(all,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(all,8,8,StandardCharsets.US_ASCII).equals("WAVEfmt ")&&new String(all,36,4,StandardCharsets.US_ASCII).equals("data"),"WAV header tags wrong");
        check(h.getInt(4)==all.length-8&&h.getInt(16)==16&&h.getShort(20)==1&&h.getShort(22)==1&&h.getInt(24)==16000&&h.getInt(28)==32000&&h.getShort(32)==2&&h.getShort(34)==16,"WAV is not PCM 16 kHz mono 16 bits");
        check(h.getInt(40)==all.length-44&&(all.length-44)%2==0,"WAV data size wrong: "+h.getInt(40)+" of "+all.length);
        short[] s=new short[(all.length-44)/2];h.position(44);h.asShortBuffer().get(s);return s;
    }
    /** FLAC leído como lo leería cualquier otro programa del teléfono: MediaExtractor + MediaCodec. */
    private static short[] flacSamples(File file)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(file.getPath());check(extractor.getTrackCount()==1,"FLAC not readable by MediaExtractor");
            MediaFormat format=extractor.getTrackFormat(0);extractor.selectTrack(0);
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)==16000&&format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==1,"FLAC is not 16 kHz mono: "+format);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputDone=false,floats=false;short[] all=new short[16000*8];int n=0;long deadline=SystemClock.elapsedRealtime()+60_000;
            while(true){
                check(SystemClock.elapsedRealtime()<deadline,"FLAC decoding timed out");
                if(!inputDone){int in=codec.dequeueInputBuffer(2000);if(in>=0){int size=extractor.readSampleData(codec.getInputBuffer(in),0);
                    if(size<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(in,0,size,Math.max(0,extractor.getSampleTime()),0);extractor.advance();}}}
                int out=codec.dequeueOutputBuffer(info,2000);
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=codec.getOutputFormat();check(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)==16000&&f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==1,"Decoded FLAC is not 16 kHz mono: "+f);
                    int encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    floats=encoding==AudioFormat.ENCODING_PCM_FLOAT;check(floats||encoding==AudioFormat.ENCODING_PCM_16BIT,"Unexpected PCM from the FLAC decoder: "+encoding);
                }else if(out>=0){
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        ByteBuffer data=codec.getOutputBuffer(out);data.position(info.offset);data.limit(info.offset+info.size);ByteBuffer pcm=data.slice().order(ByteOrder.nativeOrder());
                        int count=info.size/(floats?4:2);if(n+count>all.length)all=Arrays.copyOf(all,Math.max(all.length*2,n+count));
                        if(floats){FloatBuffer fb=pcm.asFloatBuffer();for(int i=0;i<count;i++)all[n++]=(short)Math.max(-32768,Math.min(32767,Math.round(fb.get(i)*32768f)));}
                        else{pcm.asShortBuffer().get(all,n,count);n+=count;}
                    }
                    boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(out,false);if(end)break;
                }
            }
            return Arrays.copyOf(all,n);
        }finally{extractor.release();if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}}
    }
    /** Amplitud del tono de «hz» entre dos muestras (correlación directa). Con un número entero de ciclos, los demás tonos dan cero. */
    private static double amplitude(short[] s,int from,int to,double hz){
        double re=0,im=0;for(int i=from;i<to;i++){double a=2*Math.PI*hz*i/16000;re+=s[i]*Math.cos(a);im+=s[i]*Math.sin(a);}
        return 2*Math.sqrt(re*re+im*im)/(to-from);
    }
    /** Lo mismo entre dos instantes (ms), con el largo ajustado a múltiplos de 10 ms: ciclos enteros de 300, 600 y 900 Hz. */
    private static double amplitude(short[] s,long fromMs,long toMs,double hz){int from=(int)fromMs*16,length=(int)(toMs-fromMs)/10*160;return amplitude(s,from,from+length,hz);}
    private static double rms(short[] s,int from,int to){double sum=0;for(int i=from;i<to;i++)sum+=(double)s[i]*s[i];return Math.sqrt(sum/(to-from));}
    private static int peak(short[] s,int from,int to){int max=0;for(int i=from;i<to;i++)max=Math.max(max,Math.abs(s[i]));return max;}
    private static boolean silent(short[] s,long fromMs,long toMs){for(int i=(int)fromMs*16;i<(int)toMs*16;i++)if(s[i]!=0)return false;return true;}
    /** Cuántos de los archivos posibles de una salida existen: temporal, FLAC y WAV. */
    private static int leftovers(File dir,String name){int n=0;for(String ext:new String[]{".tmp",".flac",".wav"})if(new File(dir,name+ext).exists())n++;return n;}
    private static void clear(File f){if(f==null||!f.exists())return;File[] kids=f.listFiles();if(kids!=null)for(File k:kids)clear(k);f.delete();}
}
