package cl.vozlocal.app;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

final class AudioParts {
    static final class Part {final File file;final double offset;Part(File file,double offset){this.file=file;this.offset=offset;}}
    static List<Part> prepare(Context c,Recording r,HttpApi http)throws Exception{
        return prepare(c,r,http,24_000_000,18_000_000);
    }
    static List<Part> prepare(Context c,Recording r,HttpApi http,long singleLimit,long partLimit)throws Exception{
        if(r.audio(c).length()<=singleLimit)return java.util.Collections.singletonList(new Part(r.audio(c),0));
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
                if(muxer==null || partBytes>=partLimit){
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
        }finally{extractor.release();if(muxer!=null)try{muxer.release();}catch(Exception ignored){}}
    }
}
