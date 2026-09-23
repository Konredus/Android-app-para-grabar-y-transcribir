package cl.vozlocal.app;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;

/** Streaming conversion. Originals are read-only and an incomplete destination is always removed. */
final class AudioConvert {
    interface Progress { void update(String stage,long positionMs,long totalMs); }
    static long duration(File file)throws Exception{try(MediaMetadataRetriever m=new MediaMetadataRetriever()){m.setDataSource(file.getPath());return Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}}
    static void convert(File source,File target,long startMs,long endMs,HttpApi cancel)throws Exception{convert(source,target,startMs,endMs,cancel,(s,p,t)->{});}
    static void convert(File source,File target,long startMs,long endMs,HttpApi cancel,Progress progress)throws Exception{
        if(source.getCanonicalFile().equals(target.getCanonicalFile()))throw new IOException("Source must be preserved");
        long length=duration(source);if(startMs<0||endMs-startMs<500||endMs>length+100)throw new IOException("Invalid audio interval");
        MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null,encoder=null;MediaMuxer muxer=null;boolean muxStarted=false,success=false;
        try{
            cancel.check();extractor.setDataSource(source.getPath());int track=-1;MediaFormat sourceFormat=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;sourceFormat=f;break;}}
            if(track<0)throw new IOException("No audio track");extractor.selectTrack(track);
            // A full AAC recording already has the format we need. Repacking its samples avoids
            // decoding and encoding an hour of audio, and preserves the original sound quality.
            if("audio/mp4a-latm".equals(sourceFormat.getString(MediaFormat.KEY_MIME))&&startMs==0&&endMs>=length-100){
                remux(extractor,sourceFormat,target,length,cancel,progress);success=true;return;
            }
            if(startMs>0)extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            decoder=MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME));decoder.configure(sourceFormat,null,null,0);decoder.start();
            muxer=new MediaMuxer(target.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo decoded=new MediaCodec.BufferInfo(),encoded=new MediaCodec.BufferInfo();
            boolean sourceEnd=false,decodeEnd=false,encodeEnd=false;byte[] pending=null;int position=0,rate=0,channels=0,outputTrack=-1;long frames=0,lastMediaUs=-1,lastProgress=SystemClock.elapsedRealtime(),lastUi=0;
            progress.update("Convirtiendo audio",0,endMs-startMs);
            while(true){
                cancel.check();
                if(!sourceEnd&&!decodeEnd){int input=decoder.dequeueInputBuffer(1000);if(input>=0){ByteBuffer buffer=decoder.getInputBuffer(input);buffer.clear();int size=extractor.readSampleData(buffer,0);long pts=extractor.getSampleTime();if(size<0||pts>endMs*1000+1_000_000){decoder.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sourceEnd=true;}else{decoder.queueInputBuffer(input,0,size,pts,0);extractor.advance();}}}
                if(pending==null&&!decodeEnd){int output=decoder.dequeueOutputBuffer(decoded,1000);
                    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat pcm=decoder.getOutputFormat();rate=pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if(channels<1||channels>2||(pcm.containsKey(MediaFormat.KEY_PCM_ENCODING)&&pcm.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT))throw new IOException("Unsupported PCM");
                        if(encoder!=null)throw new IOException("Audio format changed");MediaFormat aac=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);aac.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);aac.setInteger(MediaFormat.KEY_BIT_RATE,96000);aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");encoder.configure(aac,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
                    }else if(output>=0){
                        if(decoded.size>0){if(encoder==null)throw new IOException("Missing PCM format");int frameBytes=channels*2;long begin=Math.max(0,(startMs*1000-decoded.presentationTimeUs)*rate/1_000_000);long finish=Math.min(decoded.size/frameBytes,(endMs*1000-decoded.presentationTimeUs)*rate/1_000_000);
                            if(finish>begin){ByteBuffer pcm=decoder.getOutputBuffer(output);pcm.position(decoded.offset+(int)begin*frameBytes);pending=new byte[(int)(finish-begin)*frameBytes];pcm.get(pending);position=0;}
                            if(decoded.presentationTimeUs>lastMediaUs){lastMediaUs=decoded.presentationTimeUs;lastProgress=SystemClock.elapsedRealtime();}
                            if(decoded.presentationTimeUs>=endMs*1000)decodeEnd=true;
                        }
                        if((decoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)decodeEnd=true;decoder.releaseOutputBuffer(output,false);
                    }
                }
                if(encoder!=null){
                    if(pending!=null||(decodeEnd&&!encodeEnd)){int input=encoder.dequeueInputBuffer(1000);if(input>=0){ByteBuffer buffer=encoder.getInputBuffer(input);buffer.clear();long pts=frames*1_000_000/rate;
                        if(pending!=null){int n=Math.min(buffer.remaining(),pending.length-position);n-=n%(channels*2);if(n==0)throw new IOException("Encoder buffer too small");buffer.put(pending,position,n);encoder.queueInputBuffer(input,0,n,pts,0);frames+=n/(channels*2);position+=n;if(position==pending.length)pending=null;}
                        else{encoder.queueInputBuffer(input,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);encodeEnd=true;}}}
                    int output=encoder.dequeueOutputBuffer(encoded,1000);
                    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(muxStarted)throw new IOException("Output format changed");outputTrack=muxer.addTrack(encoder.getOutputFormat());muxer.start();muxStarted=true;}
                    else if(output>=0){if(encoded.size>0&&(encoded.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){if(!muxStarted)throw new IOException("Muxer unavailable");ByteBuffer data=encoder.getOutputBuffer(output);data.position(encoded.offset);data.limit(encoded.offset+encoded.size);muxer.writeSampleData(outputTrack,data,encoded);}
                        boolean done=(encoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(output,false);if(done)break;}
                }else if(decodeEnd)throw new IOException("No decodable audio");
                long now=SystemClock.elapsedRealtime();if(now-lastUi>=200){progress.update("Convirtiendo audio",Math.min(endMs-startMs,Math.max(0,lastMediaUs/1000-startMs)),endMs-startMs);lastUi=now;}
                // A dequeue operation alone is not evidence of progress: timestamps must advance.
                if(now-lastProgress>60000)throw new IOException("Audio decoder stopped advancing");
            }
            cancel.check();if(frames<rate/2)throw new IOException("Audio too short");muxer.stop();muxStarted=false;success=true;progress.update("Verificando audio",endMs-startMs,endMs-startMs);
        }finally{extractor.release();if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}if(muxer!=null){try{if(muxStarted)muxer.stop();}catch(Exception ignored){}muxer.release();}if(!success)target.delete();}
    }
    private static void remux(MediaExtractor extractor,MediaFormat format,File target,long duration,HttpApi cancel,Progress progress)throws Exception{
        MediaMuxer muxer=new MediaMuxer(target.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);boolean started=false;
        try{
            int track=muxer.addTrack(format);muxer.start();started=true;ByteBuffer data=ByteBuffer.allocateDirect(65536);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long first=-1,last=-1,count=0,lastUi=0;
            progress.update("Preparando audio sin reconvertir",0,duration);
            while(true){cancel.check();long size=extractor.getSampleSize();if(size<0)break;if(size>16_000_000)throw new IOException("Audio sample too large");if(size>data.capacity())data=ByteBuffer.allocateDirect((int)size);data.clear();int n=extractor.readSampleData(data,0);if(n<0)break;long pts=extractor.getSampleTime();if((extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("Encrypted audio unsupported");if(first<0)first=pts;if(pts<last)throw new IOException("Audio timestamps out of order");last=pts;
                info.set(0,n,pts-first,(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);data.position(0);data.limit(n);muxer.writeSampleData(track,data,info);count++;extractor.advance();long now=SystemClock.elapsedRealtime();if(now-lastUi>=200){progress.update("Preparando audio sin reconvertir",Math.min(duration,(pts-first)/1000),duration);lastUi=now;}
            }
            cancel.check();if(count==0||last-first<400000)throw new IOException("Audio too short");muxer.stop();started=false;progress.update("Verificando audio",duration,duration);
        }finally{try{if(started)muxer.stop();}catch(Exception ignored){}muxer.release();}
    }
}
