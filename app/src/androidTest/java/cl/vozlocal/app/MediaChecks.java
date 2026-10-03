package cl.vozlocal.app;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.media.*;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Pruebas de la parte «media» de la 0.6.0 (★ marcas, onda, notificación de grabación). Sin llamadas reales a APIs. */
final class MediaChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void run(Context c,Recording r)throws Exception{
        marks(c,r);
        notificationBuilder(c);
        wave(c,r);
        recorder(c);
        importTexts(c);
    }

    // ---------- Importar: etapas y textos según el idioma (0.9.0) ----------
    private static void importTexts(Context c){
        // Las etapas del conversor son códigos (no textos en un idioma): solo la verificación final se muestra como «Casi listo».
        check(ImportService.friendlyStage(AudioConvert.VERIFY)==R.string.imp_stage_almost&&ImportService.friendlyStage(AudioConvert.REMUX)==R.string.imp_stage_saving&&ImportService.friendlyStage(AudioConvert.CONVERT)==R.string.imp_stage_saving,"Import stage codes mapped wrong");
        ImportSession s=new ImportSession(c);s.bytesProgress=true;s.position=12_500_000;s.total=100_000_000;
        check("12,5 MB de 100,0 MB".equals(ImportService.progressText(s)),"Import progress (es): "+ImportService.progressText(s));
        s.bytesProgress=false;s.position=5_000;s.total=60_000;
        check("00:05 de 01:00".equals(ImportService.progressText(s)),"Import time progress (es): "+ImportService.progressText(s));
        s.total=0;check("Tu archivo original se conserva".equals(ImportService.progressText(s)),"Import progress without total (es)");
        try{
            Lang.override(Lang.EN);s.bytesProgress=true;s.position=12_500_000;s.total=100_000_000;
            check("12.5 MB of 100.0 MB".equals(ImportService.progressText(s)),"Import progress (en): "+ImportService.progressText(s));
            check(ImportSession.titleFrom("PTT-20260929-WA0003.opus").startsWith("WhatsApp audio Sep")&&ImportSession.titleFrom("").equals("Imported audio"),"Import titles (en): "+ImportSession.titleFrom("PTT-20260929-WA0003.opus"));
            Lang.override(Lang.PT);
            check(ImportSession.titleFrom("PTT-20260929-WA0003.opus").startsWith("Áudio do WhatsApp 29 ")&&ImportSession.titleFrom("Reunião.m4a").equals("Reunião"),"Import titles (pt): "+ImportSession.titleFrom("PTT-20260929-WA0003.opus"));
        }finally{Lang.override(Lang.ES);}
    }

    // ---------- Marks ----------
    private static void marks(Context c,Recording r)throws Exception{
        Recording d=copy(c,r,"Prueba de marcas");
        try{
            check(Marks.list(c,d.id).length()==0,"New recording has marks");
            Marks.add(c,d.id,5000,"precio");Marks.add(c,d.id,1000,"");Marks.add(c,d.id,3000,"  idea  ");
            JSONArray m=Marks.list(c,d.id);
            check(m.length()==3,"Marks add failed: "+m);
            check(m.getJSONObject(0).getLong("t")==1000&&m.getJSONObject(1).getLong("t")==3000&&m.getJSONObject(2).getLong("t")==5000,"Marks not sorted by t: "+m);
            check(m.getJSONObject(0).getString("label").isEmpty()&&m.getJSONObject(1).getString("label").equals("idea")&&m.getJSONObject(2).getString("label").equals("precio"),"Mark labels wrong: "+m);
            Marks.add(c,d.id,3000,"");check(Marks.count(c,d.id)==3&&Marks.list(c,d.id).getJSONObject(1).getString("label").equals("idea"),"Same-instant mark duplicated or lost its label");
            Marks.add(c,d.id,3000,"otra");check(Marks.count(c,d.id)==3&&Marks.list(c,d.id).getJSONObject(1).getString("label").equals("otra"),"Same-instant mark did not take the new label");
            Marks.rename(c,d.id,0,"inicio");check(Marks.list(c,d.id).getJSONObject(0).getString("label").equals("inicio"),"Mark rename failed");
            Marks.rename(c,d.id,0,new String(new char[200]).replace('\0','x'));check(Marks.list(c,d.id).getJSONObject(0).getString("label").length()==Marks.LABEL_MAX,"Long mark label not trimmed");
            Marks.remove(c,d.id,1);m=Marks.list(c,d.id);check(m.length()==2&&m.getJSONObject(0).getLong("t")==1000&&m.getJSONObject(1).getLong("t")==5000,"Mark remove failed: "+m);
            Marks.remove(c,d.id,9);Marks.rename(c,d.id,-1,"nada");check(Marks.count(c,d.id)==2,"Out-of-range index changed marks");
            Marks.add(c,d.id,-40,"");check(Marks.list(c,d.id).getJSONObject(0).getLong("t")==0,"Negative mark time not clamped");
            long[] times=Marks.times(c,d.id);check(times.length==3&&times[0]==0&&times[2]==5000,"Mark times wrong");
            FilesStore.update(c,d.id,s->s.put("marks",new JSONArray("[{\"t\":9000},\"basura\",{\"label\":\"sin t\"},{\"t\":2000,\"label\":\"x\"}]")));
            m=Marks.list(c,d.id);check(m.length()==2&&m.getJSONObject(0).getLong("t")==2000&&m.getJSONObject(1).getString("label").isEmpty(),"Damaged marks not cleaned/sorted: "+m);
            Marks.setAll(c,d.id,new JSONArray("[{\"t\":700,\"label\":\"b\"},{\"t\":300,\"label\":\"a\"}]"));
            m=Marks.list(c,d.id);check(m.length()==2&&m.getJSONObject(0).getString("label").equals("a"),"Marks setAll failed: "+m);
            check(FilesStore.state(c,d.id).optJSONArray("marks").getJSONObject(0).getLong("t")==300,"Marks not stored sorted");
            check(Marks.describe(1).equals("1 momento marcado")&&Marks.describe(3).equals("3 momentos marcados"),"Marks text wrong");
        }finally{d.delete(c);}
        check(Marks.count(c,UUID.randomUUID().toString())==0,"Unknown recording has marks");
    }

    // ---------- Notificación de grabación ----------
    private static void notificationBuilder(Context c){
        Notification n=RecorderService.notification(c,false,65_000,0);
        check(n.actions!=null&&n.actions.length==3,"Recording notification needs 3 actions");
        check("Pausar".contentEquals(n.actions[0].title)&&"★ Marcar".contentEquals(n.actions[1].title)&&"Detener y guardar".contentEquals(n.actions[2].title),"Recording notification actions wrong");
        check(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),"Recording notification has no live timer");
        check(Math.abs(System.currentTimeMillis()-65_000-n.when)<5000,"Chronometer base wrong");
        check(n.visibility==Notification.VISIBILITY_PUBLIC&&(n.flags&Notification.FLAG_ONGOING_EVENT)!=0,"Recording notification hidden on lock screen or dismissible");
        check("Audio guardado en este teléfono".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TEXT)),"Recording notification text wrong");
        n=RecorderService.notification(c,true,65_000,3);
        check("Reanudar".contentEquals(n.actions[0].title),"Paused notification should offer Reanudar");
        check(!n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),"Paused notification keeps running timer");
        check("En pausa · 01:05".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)),"Paused notification title wrong: "+n.extras.getCharSequence(Notification.EXTRA_TITLE));
        check(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("3 momentos marcados"),"Marks count missing in notification");
        n=RecorderService.notification(c,false,3_700_000,1);
        check(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("1 momento marcado"),"Singular marks text missing");
    }

    // ---------- Onda ----------
    private static void wave(Context c,Recording r)throws Exception{
        // Audio conocido: 2 s de tono fuerte y 2 s de silencio, convertido a AAC como cualquier importación.
        File wav=new File(c.getCacheDir(),"test-wave-envelope.wav");
        Recording d=new Recording(UUID.randomUUID().toString(),"Prueba de onda",System.currentTimeMillis(),0);
        Recording bad=new Recording(UUID.randomUUID().toString(),"Audio dañado",System.currentTimeMillis(),4000);
        Recording big=null;
        try{
            writeWav(wav,16000,2,2);AudioConvert.convert(wav,d.audio(c),0,4000,new HttpApi());d.duration=AudioConvert.duration(d.audio(c));
            check(WaveData.cached(c,d)==null,"Fresh recording has a cached wave");
            // Cancelar desde el primer aviso no deja caché.
            check(WaveData.compute(c,d,(part,done)->false)==null,"Cancelled wave returned data");
            check(!FilesStore.file(c,d.id,".wave.json").exists(),"Cancelled wave left a cache file");
            int[] updates={0};float[] lastDone={-1};
            float[] v=WaveData.compute(c,d,(part,done)->{check(part.length==WaveData.N,"Partial wave size wrong");updates[0]++;lastDone[0]=done;return true;});
            checkWave(v,"synthetic");
            check(updates[0]>=2&&lastDone[0]==1f,"Wave progress not reported to the end");
            double loud=mean(v,0,(int)(WaveData.N*0.4)),quiet=mean(v,(int)(WaveData.N*0.6),WaveData.N);
            check(loud>0.6&&quiet<0.15,"Wave does not follow the audio: loud="+loud+" quiet="+quiet);
            float[] again=WaveData.cached(c,d);check(again!=null&&again.length==v.length,"Wave not cached");
            for(int i=0;i<v.length;i++)check(Math.abs(again[i]-v[i])<0.001f,"Cached wave differs at "+i);
            int[] recomputed={0};float[] reused=WaveData.compute(c,d,(part,done)->{recomputed[0]++;return true;});
            check(reused!=null&&reused.length==WaveData.N&&recomputed[0]==0,"Cached wave not reused");
            // Un caché de otro audio (tamaño distinto) no se usa.
            File cache=FilesStore.file(c,d.id,".wave.json");JSONObject stale=FilesStore.read(cache).put("bytes",1);FilesStore.write(cache,stale);
            check(WaveData.cached(c,d)==null,"Stale wave cache used");
            // Audio que no se puede decodificar: null, sin caerse ni dejar caché.
            try(FileOutputStream out=new FileOutputStream(bad.audio(c))){out.write("esto no es audio".getBytes(StandardCharsets.UTF_8));}
            check(WaveData.compute(c,bad)==null&&!FilesStore.file(c,bad.id,".wave.json").exists(),"Broken audio produced a wave");
            // La grabación real de la prueba.
            float[] real=WaveData.compute(c,r);checkWave(real,"recording");
            float[] realCached=WaveData.cached(c,r);check(realCached!=null&&realCached.length==WaveData.N,"Recording wave not cached");
            // Rendimiento: 10 minutos de AAC.
            big=new Recording(UUID.randomUUID().toString(),"Prueba de onda larga",System.currentTimeMillis(),600_000);
            repeatFrame(r.audio(c),big.audio(c),600_000_000L);
            long started=SystemClock.elapsedRealtime();float[] longWave=WaveData.compute(c,big);long took=SystemClock.elapsedRealtime()-started;
            android.util.Log.i("VozLocalTest","WaveData 10 min: "+took+" ms");
            checkWave(longWave,"10-minute");check(took<60_000,"Wave of 10 minutes too slow: "+took+" ms");
        }finally{wav.delete();d.delete(c);bad.delete(c);if(big!=null)big.delete(c);}
    }
    private static void checkWave(float[] v,String what){
        check(v!=null&&v.length==WaveData.N,"Wave ("+what+") missing or wrong size");
        for(float x:v)check(x>=0f&&x<=1f,"Wave ("+what+") value out of 0..1: "+x);
    }
    private static double mean(float[] v,int from,int to){double s=0;for(int i=from;i<to;i++)s+=v[i];return s/Math.max(1,to-from);}
    private static void writeWav(File file,int rate,int loudSeconds,int quietSeconds)throws Exception{
        int samples=rate*(loudSeconds+quietSeconds);ByteBuffer pcm=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        pcm.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);
        for(int i=0;i<samples;i++)pcm.putShort(i<rate*loudSeconds?(short)(Math.sin(i*2*Math.PI*440/rate)*12000):0);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(pcm.array());}
    }
    /** AAC largo repitiendo un cuadro real (igual que el audio de una hora de LongImportChecks, pero más corto). */
    private static void repeatFrame(File seed,File destination,long durationUs)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean started=false;
        try{extractor.setDataSource(seed.getPath());MediaFormat format=extractor.getTrackFormat(0);extractor.selectTrack(0);int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            ByteBuffer data=ByteBuffer.allocate(65536);extractor.advance();int bytes=extractor.readSampleData(data,0);if(bytes<=0){extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);bytes=extractor.readSampleData(data,0);}check(bytes>0,"AAC seed empty");
            muxer=new MediaMuxer(destination.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int track=muxer.addTrack(format);muxer.start();started=true;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(long frame=0;frame*1024_000_000L/rate<durationUs;frame++){data.position(0);data.limit(bytes);info.set(0,bytes,frame*1024_000_000L/rate,MediaCodec.BUFFER_FLAG_KEY_FRAME);muxer.writeSampleData(track,data,info);}
            muxer.stop();started=false;
        }finally{extractor.release();if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}}
    }

    // ---------- Grabadora: ★ en vivo, pausa/reanudar y guardado de marcas ----------
    private static void recorder(Context c)throws Exception{
        waitFor(()->RecorderService.activeId==null,20000);check(RecorderService.activeId==null,"Recorder busy before media checks");
        // Estas grabaciones de prueba se borran: Inicio no debe ofrecer «siguientes pasos» ni avisos por ellas.
        String savedBefore=RecorderService.lastSavedId,noticeBefore=RecorderService.notice;
        try{recorderFlow(c);}finally{RecorderService.lastSavedId=savedBefore;RecorderService.notice=noticeBefore;}
    }
    private static void recorderFlow(Context c)throws Exception{
        // Grabación que se guarda, con ★ desde la notificación (vibra), doble toque, ★ en pausa y ★ con palabra.
        String id=start(c);
        try{
            Thread.sleep(500);
            send(c,new Intent("MARK").putExtra("label","idea").putExtra("source","notification"));
            waitFor(()->RecorderService.marksCount()==1,3000);check(RecorderService.marksCount()==1,"MARK not registered");
            check(RecorderService.lastMarkAt()>0&&RecorderService.lastMarkAt()<=RecorderService.elapsed(),"lastMarkAt out of range: "+RecorderService.lastMarkAt());
            waitFor(()->Marks.count(c,id)==1,3000);
            JSONArray live=Marks.list(c,id);check(live.length()==1&&live.getJSONObject(0).getString("label").equals("idea"),"Mark not persisted while recording: "+live);
            send(c,new Intent("MARK"));Thread.sleep(400);check(RecorderService.marksCount()==1,"Double tap created two marks");
            Thread.sleep(1100);
            send(c,new Intent("PAUSE").putExtra("toggle",false));waitFor(()->RecorderService.paused,3000);check(RecorderService.paused,"PAUSE did not pause");
            long pausedAt=RecorderService.elapsed();
            send(c,new Intent("PAUSE").putExtra("toggle",false));Thread.sleep(400);check(RecorderService.paused,"Non-toggle PAUSE resumed");
            send(c,new Intent("MARK").putExtra("source","notification"));waitFor(()->RecorderService.marksCount()==2,3000);
            check(RecorderService.marksCount()==2&&RecorderService.lastMarkAt()==pausedAt,"Mark while paused not at pause time");
            send(c,new Intent("RESUME"));waitFor(()->!RecorderService.paused,3000);check(!RecorderService.paused,"RESUME did not resume");
            send(c,new Intent("RESUME"));Thread.sleep(300);check(!RecorderService.paused,"RESUME toggled pause");
            waitFor(()->RecorderService.elapsed()>3600,8000);
            long wanted=RecorderService.elapsed()-200;
            send(c,new Intent("MARK").putExtra("t",wanted).putExtra("label","precio"));waitFor(()->RecorderService.marksCount()==3,3000);
            check(RecorderService.marksCount()==3&&RecorderService.lastMarkAt()==wanted,"Mark at explicit time failed");
            check(RecorderService.markTimes().length==3,"markTimes wrong");
        }finally{stop(c);}
        check(RecorderService.error==null,"Recorder stop failed: "+RecorderService.error);
        check(RecorderService.marksCount()==0&&RecorderService.lastMarkAt()==0,"Marks not cleared after stop");
        Recording saved=FilesStore.recording(c,id);
        try{
            check(saved!=null&&saved.duration>=3000,"Marked recording not saved");
            JSONArray m=Marks.list(c,id);
            check(m.length()==3,"Marks not persisted after stop: "+m);
            check(m.getJSONObject(0).getString("label").equals("idea")&&m.getJSONObject(2).getString("label").equals("precio"),"Mark labels lost: "+m);
            for(int i=1;i<m.length();i++)check(m.getJSONObject(i).getLong("t")>=m.getJSONObject(i-1).getLong("t"),"Persisted marks not sorted");
            check(m.getJSONObject(2).getLong("t")<=saved.duration,"Mark after the end of the recording");
        }finally{if(saved!=null)saved.delete(c);}
        // Grabación corta (menos de 3 s): se descarta con sus ★, sin dejar el estado huérfano.
        String shortId=start(c);
        Thread.sleep(300);send(c,new Intent("MARK"));waitFor(()->RecorderService.marksCount()==1,3000);
        Thread.sleep(500);stop(c);
        check(FilesStore.recording(c,shortId)==null,"Short recording was kept");
        check(!FilesStore.file(c,shortId,".sync.json").exists()&&!FilesStore.file(c,shortId,".m4a").exists(),"Short recording left its marks/audio behind");
        // ★ sin grabación: se ignora y no queda nada.
        send(c,new Intent("MARK"));Thread.sleep(300);check(RecorderService.marksCount()==0&&RecorderService.activeId==null,"MARK without recording did something");
    }
    private static String start(Context c)throws Exception{
        c.startForegroundService(new Intent(c,RecorderService.class).setAction("START"));
        waitFor(()->RecorderService.activeId!=null||RecorderService.error!=null,10000);
        check(RecorderService.activeId!=null,"Recorder did not start for media checks: "+RecorderService.error);
        return RecorderService.activeId;
    }
    private static void stop(Context c)throws Exception{
        send(c,new Intent("STOP"));waitFor(()->RecorderService.activeId==null,20000);
        check(RecorderService.activeId==null,"Recorder did not stop");
    }
    private static void send(Context c,Intent action){c.startService(new Intent(action).setClass(c,RecorderService.class));}
    private static void waitFor(BooleanSupplier condition,long ms)throws InterruptedException{
        long deadline=SystemClock.elapsedRealtime()+ms;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
    }
    private static Recording copy(Context c,Recording r,String title)throws Exception{
        Recording d=new Recording(UUID.randomUUID().toString(),title,System.currentTimeMillis(),r.duration);
        Files.copy(r.audio(c).toPath(),d.audio(c).toPath());return d;
    }
}
