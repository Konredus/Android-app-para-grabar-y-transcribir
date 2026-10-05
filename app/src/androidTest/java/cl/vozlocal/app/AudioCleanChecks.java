package cl.vozlocal.app;

import android.app.job.JobParameters;
import android.content.Context;
import android.media.MediaRecorder;
import java.io.File;
import java.util.Random;
import java.util.UUID;

/**
 * 0.9.3: «Ruido de fondo». La limpieza del audio que se envía (AudioClean: zumbidos, nivelador y reductor de ruido) con
 * señales sintéticas medidas por frecuencia; que OrAudio y el audio guardado (Kept) la respeten; el micrófono de las
 * llamadas; «Realzar voces al escuchar» (VoiceBoost); y el arreglo del informe del Xiaomi (seguir al tiro tras un corte de
 * Android con Verbapp a la vista).
 */
final class AudioCleanChecks {
    private AudioCleanChecks(){}
    private static final int RATE=OrAudio.RATE;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    static void run(Context c,Recording r)throws Exception{
        hum();level();noise();lengths();
        orAudio(c,r);settings(c);recorder();resume();boost(c);
    }

    // ---------- AudioClean ----------
    /** El zumbido de 50 Hz baja al menos 18 dB (pasa-altos de 4.º orden a 100 Hz); un tono de voz a 1 kHz no se toca. */
    private static void hum()throws Exception{
        int n=6*RATE;short[] in=new short[n];Random rnd=new Random(1);
        for(int i=0;i<n;i++)in[i]=(short)Math.round(6000*Math.sin(2*Math.PI*50*i/RATE)+600*Math.sin(2*Math.PI*1000*i/RATE)+rnd.nextGaussian()*10);
        short[] out=clean(in,AudioClean.LEVEL,1000);
        double humIn=tone(in,3*RATE,n,50),humOut=tone(out,3*RATE,n,50),voiceIn=tone(in,3*RATE,n,1000),voiceOut=tone(out,3*RATE,n,1000);
        check(humOut<humIn*0.126,"Hum not removed: "+Math.round(humIn)+" -> "+Math.round(humOut));
        // Un tono continuo no es «voz» para el nivelador (no tiene pausas): el volumen no cambia.
        check(Math.abs(voiceOut/voiceIn-1)<0.1,"A steady 1 kHz tone should pass unchanged: "+Math.round(voiceIn)+" -> "+Math.round(voiceOut));
    }
    /** Una voz baja (con pausas) sube hasta +12 dB; una muy fuerte baja; nada pasa del techo. */
    private static void level()throws Exception{
        short[] quiet=bursts(8*RATE,400,15,2),out=clean(quiet,AudioClean.LEVEL,777);
        double gain=tone(out,5*RATE,8*RATE,1000)/tone(quiet,5*RATE,8*RATE,1000);
        check(gain>2.5&&gain<4.4,"A quiet voice should be raised (up to +12 dB): x"+gain);
        short[] loud=bursts(8*RATE,20000,15,3),down=clean(loud,AudioClean.LEVEL,4096);
        double cut=tone(down,5*RATE,8*RATE,1000)/tone(loud,5*RATE,8*RATE,1000);
        check(cut>0.4&&cut<0.75,"A very loud voice should be lowered: x"+cut);
        int peak=0;for(short s:down)peak=Math.max(peak,Math.abs(s));check(peak<=32767,"Clipped");
    }
    /** El ruido constante baja al menos 6 dB y la voz queda igual (±30 %). */
    private static void noise()throws Exception{
        int n=8*RATE;short[] in=new short[n];Random rnd=new Random(4);
        for(int i=0;i<n;i++){double v=rnd.nextGaussian()*800;if(i>=4*RATE&&(i%(RATE/2))<RATE*3/10)v+=4000*Math.sin(2*Math.PI*1000*i/RATE);in[i]=(short)Math.max(-32768,Math.min(32767,Math.round(v)));}
        short[] out=clean(in,AudioClean.NOISE,1500);
        double noiseIn=rms(in,RATE*3/2,RATE*7/2),noiseOut=rms(out,RATE*3/2,RATE*7/2);
        check(noiseOut<noiseIn*0.5,"Background noise not reduced: "+Math.round(noiseIn)+" -> "+Math.round(noiseOut));
        double voice=tone(out,6*RATE,n,1000)/tone(in,6*RATE,n,1000);
        check(voice>0.7&&voice<1.3,"The voice should pass the noise reducer: x"+voice);
    }
    /** Ninguna combinación cambia el número de muestras (con trozos de cualquier tamaño), ni deja valores raros. */
    private static void lengths()throws Exception{
        Random rnd=new Random(7);
        for(int mask=1;mask<=3;mask++)for(int n:new int[]{1,255,256,257,RATE*2+123}){
            short[] in=new short[n];for(int i=0;i<n;i++)in[i]=(short)(rnd.nextGaussian()*3000);
            short[] out=clean(in,mask,1+rnd.nextInt(5000));
            check(out.length==n,"Sample count changed with mask "+mask+": "+n+" -> "+out.length);
        }
        // Sin nada que limpiar, el reductor (con su retraso compensado) devuelve lo mismo, muestra a muestra.
        short[] silence=new short[RATE];short[] out=clean(silence,AudioClean.NOISE,333);
        for(short s:out)check(s==0,"Silence should stay silence");
    }

    // ---------- OrAudio, Kept y Ajustes ----------
    /** El archivo para OpenRouter dura lo mismo con o sin limpieza, y el audio guardado distingue la limpieza. */
    private static void orAudio(Context c,Recording r)throws Exception{
        File dir=new File(c.getCacheDir(),"audio-clean-check");dir.mkdirs();
        try{
            HttpApi plain=new HttpApi(),cleaned=new HttpApi();cleaned.audioClean=AudioClean.LEVEL|AudioClean.NOISE;
            OrAudio.Built a=OrAudio.build(r.audio(c),null,new File(dir,"plain"),plain),b=OrAudio.build(r.audio(c),null,new File(dir,"clean"),cleaned);
            check(a.durationMs==b.durationMs&&b.file.length()>0,"Cleaning changed the duration: "+a.durationMs+" vs "+b.durationMs);
            String id=UUID.randomUUID().toString();
            String k0=OpenRouterClient.Kept.of(dir,id,r.audio(c),null,false,0).base().getName(),k3=OpenRouterClient.Kept.of(dir,id,r.audio(c),null,false,3).base().getName();
            check(!k0.equals(k3),"Kept audio should depend on the cleaning");
        }finally{File[] all=dir.listFiles();if(all!=null)for(File f:all)f.delete();dir.delete();}
    }
    /** Las cuatro opciones vienen apagadas y la máscara de limpieza sale de las dos de «antes de transcribir». */
    private static void settings(Context c){
        Settings s=new Settings(c);boolean level=s.cleanLevel(),noise=s.cleanNoise();
        try{
            s.prefs.edit().remove("cleanLevel").remove("cleanNoise").commit();
            check(s.audioClean()==0,"Cleaning should start off");
            s.prefs.edit().putBoolean("cleanLevel",true).commit();check(s.audioClean()==AudioClean.LEVEL,"Level mask wrong");
            s.prefs.edit().putBoolean("cleanNoise",true).commit();check(s.audioClean()==(AudioClean.LEVEL|AudioClean.NOISE),"Both masks wrong");
        }finally{s.prefs.edit().putBoolean("cleanLevel",level).putBoolean("cleanNoise",noise).commit();}
    }
    private static void recorder(){
        check(RecorderService.audioSource(false)==MediaRecorder.AudioSource.MIC&&RecorderService.audioSource(true)==MediaRecorder.AudioSource.VOICE_COMMUNICATION,"Microphone source wrong");
    }
    /** Informe del Xiaomi: con Verbapp a la vista se sigue al tiro; no si la cortó la app o falta red, cargador, batería o espacio. */
    private static void resume(){
        check(PipelineJob.resumeNow(JobParameters.STOP_REASON_USER,true)&&PipelineJob.resumeNow(JobParameters.STOP_REASON_TIMEOUT,true)&&PipelineJob.resumeNow(JobParameters.STOP_REASON_QUOTA,true),"Should resume with the app on screen");
        check(!PipelineJob.resumeNow(JobParameters.STOP_REASON_USER,false),"Should not resume with the app out of sight");
        for(int reason:new int[]{JobParameters.STOP_REASON_CANCELLED_BY_APP,JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY,JobParameters.STOP_REASON_CONSTRAINT_CHARGING,JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW})
            check(!PipelineJob.resumeNow(reason,true),"Should wait for the condition, reason "+reason);
    }
    /** «Realzar voces al escuchar»: apagada no hace nada; prendida aplica el ecualizador o el realce (si el teléfono los tiene). */
    private static void boost(Context c){
        check(VoiceBoost.level(60)==-900&&VoiceBoost.level(230)==-300&&VoiceBoost.level(910)==450&&VoiceBoost.level(3600)==450&&VoiceBoost.level(14000)==0,"Equalizer curve wrong");
        Settings s=new Settings(c);boolean before=s.playBoost();int session=c.getSystemService(android.media.AudioManager.class).generateAudioSessionId();
        try{
            s.prefs.edit().putBoolean("playBoost",false).commit();check(VoiceBoost.attach(c,session)==null,"Boost should be off");
            s.prefs.edit().putBoolean("playBoost",true).commit();VoiceBoost b=VoiceBoost.attach(c,session);
            boolean available=false;for(android.media.audiofx.AudioEffect.Descriptor d:android.media.audiofx.AudioEffect.queryEffects())available|=d.type.equals(android.media.audiofx.AudioEffect.EFFECT_TYPE_EQUALIZER)||d.type.equals(android.media.audiofx.AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER);
            check(!available||b!=null&&b.active(),"Boost should apply when the phone has the effects");
            if(b!=null)b.release();
        }finally{s.prefs.edit().putBoolean("playBoost",before).commit();}
    }

    // ---------- Señales y medidas ----------
    /** Pasa «in» por AudioClean en trozos de «chunk» y devuelve todo lo que salió (con flush). */
    private static short[] clean(short[] in,int mask,int chunk)throws Exception{
        short[][] out={new short[in.length+8192]};int[] n={0};
        AudioClean ac=new AudioClean((d,k)->{if(n[0]+k>out[0].length)out[0]=java.util.Arrays.copyOf(out[0],(n[0]+k)*2);System.arraycopy(d,0,out[0],n[0],k);n[0]+=k;},mask);
        short[] part=new short[chunk];
        for(int at=0;at<in.length;at+=chunk){int k=Math.min(chunk,in.length-at);System.arraycopy(in,at,part,0,k);ac.write(part,k);}
        ac.flush();return java.util.Arrays.copyOf(out[0],n[0]);
    }
    /** Voz sintética: tono de 1 kHz en ráfagas (300 ms sí, 200 ms no) sobre ruido suave. */
    private static short[] bursts(int n,double amplitude,double noise,long seed){
        short[] s=new short[n];Random rnd=new Random(seed);
        for(int i=0;i<n;i++){double v=rnd.nextGaussian()*noise;if((i%(RATE/2))<RATE*3/10)v+=amplitude*Math.sin(2*Math.PI*1000*i/RATE);s[i]=(short)Math.max(-32768,Math.min(32767,Math.round(v)));}
        return s;
    }
    /** Amplitud de una frecuencia (Goertzel) entre from y to. */
    private static double tone(short[] s,int from,int to,double hz){
        double w=2*Math.PI*hz/RATE,k=2*Math.cos(w),q1=0,q2=0;
        for(int i=from;i<to;i++){double q0=k*q1-q2+s[i];q2=q1;q1=q0;}
        return Math.sqrt(Math.max(0,q1*q1+q2*q2-k*q1*q2))/((to-from)/2.0);
    }
    private static double rms(short[] s,int from,int to){double sum=0;for(int i=from;i<to;i++)sum+=(double)s[i]*s[i];return Math.sqrt(sum/(to-from));}
}
