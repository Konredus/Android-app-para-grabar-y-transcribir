package cl.vozlocal.app;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real one-hour AAC container plus codec, cleanup, and interrupted-import recovery regressions. */
final class LongImportChecks {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run(Context c,Recording original)throws Exception{
        File hour=new File(c.getCacheDir(),"test-hour.m4a"),output=new File(c.getCacheDir(),"test-hour-copy.m4a"),crop=new File(c.getCacheDir(),"test-hour-end.m4a"),wav=new File(c.getCacheDir(),"test-wave-import.wav");
        try{
            hourFixture(original.audio(c),hour);long duration=AudioConvert.duration(hour),size=hour.length();check(Math.abs(duration-3_600_000)<200,"One-hour fixture has invalid duration");AtomicBoolean repacked=new AtomicBoolean();long[] reached={0};
            AudioConvert.convert(hour,output,0,duration,new HttpApi(),(stage,position,total)->{if(stage.contains("sin reconvertir"))repacked.set(true);reached[0]=Math.max(reached[0],position);});
            check(repacked.get(),"Full AAC was unnecessarily transcoded");check(reached[0]>=duration-100,"Long import progress did not reach the end");check(Math.abs(AudioConvert.duration(output)-duration)<150,"One-hour import was truncated");check(hour.length()==size,"Import modified source");
            long started=SystemClock.elapsedRealtime();AudioConvert.convert(hour,crop,duration-3000,duration-1000,new HttpApi());check(Math.abs(AudioConvert.duration(crop)-2000)<300,"Crop near end of one-hour audio invalid");check(SystemClock.elapsedRealtime()-started<60000,"End crop decoded the full hour before seeking");
            output.delete();HttpApi token=new HttpApi();boolean cancelled=false;try{AudioConvert.convert(hour,output,0,duration,token,(stage,position,total)->token.cancel());}catch(InterruptedIOException expected){cancelled=true;}check(cancelled&&!output.exists(),"Cancellation left an incomplete audio file");check(hour.length()==size,"Cancellation damaged original");
            writeWav(wav,3);AudioConvert.convert(wav,crop,1000,2500,new HttpApi());check(Math.abs(AudioConvert.duration(crop)-1500)<300,"WAV import regression");
            recovery(c,original.audio(c));
        }finally{hour.delete();output.delete();crop.delete();wav.delete();}
    }
    private static void recovery(Context c,File audio)throws Exception{
        File state=ImportSession.stateFile(c);byte[] before=state.exists()?Files.readAllBytes(state.toPath()):null;ImportSession s=new ImportSession(c);
        try{Files.copy(audio.toPath(),s.source.toPath());s.ready=true;s.busy=true;s.duration=AudioConvert.duration(audio);s.to=s.duration;s.persist();try(FileOutputStream out=new FileOutputStream(s.encoded)){out.write(1);}
            ImportSession recovered=ImportSession.restore(c);check(recovered!=null&&recovered.ready&&!recovered.busy&&!recovered.error.isEmpty(),"Interrupted import cannot be retried");check(!recovered.encoded.exists()&&recovered.source.exists(),"Recovery lost source or kept partial output");recovered.cancel();recovered.clean();check(!s.source.exists()&&!s.encoded.exists(),"Cancel did not clean temporary source/output");
        }finally{s.clean();if(before!=null){state.getParentFile().mkdirs();Files.write(state.toPath(),before);}}
    }
    private static void hourFixture(File seed,File destination)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean started=false;
        try{extractor.setDataSource(seed.getPath());MediaFormat format=extractor.getTrackFormat(0);extractor.selectTrack(0);check("audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME)),"AAC seed expected");int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);ByteBuffer data=ByteBuffer.allocate(65536);int bytes=extractor.readSampleData(data,0);check(bytes>0,"AAC seed empty");muxer=new MediaMuxer(destination.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int track=muxer.addTrack(format);muxer.start();started=true;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(long frame=0;frame*1024_000_000L/rate<3_600_000_000L;frame++){long time=frame*1024_000_000L/rate;data.position(0);data.limit(bytes);info.set(0,bytes,time,MediaCodec.BUFFER_FLAG_KEY_FRAME);muxer.writeSampleData(track,data,info);}muxer.stop();started=false;
        }finally{extractor.release();if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}}
    }
    private static void writeWav(File file,int seconds)throws Exception{int rate=16000,samples=rate*seconds;ByteBuffer pcm=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);pcm.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);for(int i=0;i<samples;i++)pcm.putShort((short)(Math.sin(i*2*Math.PI*440/rate)*8000));try(FileOutputStream out=new FileOutputStream(file)){out.write(pcm.array());}}
}
