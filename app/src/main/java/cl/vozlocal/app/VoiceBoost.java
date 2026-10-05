package cl.vozlocal.app;

import android.content.Context;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

/**
 * 0.9.3: «Realzar voces al escuchar» (Ajustes → Ruido de fondo). Efectos de Android sobre la sesión del reproductor: el
 * ecualizador baja los graves (bajo 150 Hz: motor, aire acondicionado; un poco hasta 600 Hz) y sube la zona de la voz
 * (0,9–4,5 kHz), y el realce de volumen sube el conjunto 6 dB (con su propio limitador). Solo cambia lo que se oye: el
 * archivo y la transcripción no se tocan. Si el teléfono no tiene alguno de estos efectos, se reproduce igual, sin él.
 * Una sesión propia del reproductor no pide permisos (solo la mezcla general, la sesión 0, los pide).
 */
final class VoiceBoost {
    private Equalizer eq;private LoudnessEnhancer loud;
    private VoiceBoost(){}
    /** Lo aplica a esta sesión si «Realzar voces al escuchar» está prendido; null si está apagado o nada se pudo aplicar. */
    static VoiceBoost attach(Context c,int session){
        if(session==0||!new Settings(c).playBoost())return null;
        VoiceBoost b=new VoiceBoost();
        try{
            Equalizer e=new Equalizer(0,session);short[] range=e.getBandLevelRange();
            for(short band=0;band<e.getNumberOfBands();band++)e.setBandLevel(band,(short)Math.max(range[0],Math.min(range[1],level(e.getCenterFreq(band)/1000))));
            e.setEnabled(true);b.eq=e;
        }catch(RuntimeException ignored){}
        try{LoudnessEnhancer l=new LoudnessEnhancer(session);l.setTargetGain(600);l.setEnabled(true);b.loud=l;}catch(RuntimeException ignored){}
        Diagnostics.event("play_boost",null,"eq",b.eq!=null,"loud",b.loud!=null);
        if(b.eq==null&&b.loud==null)return null;
        return b;
    }
    /** Nivel de una banda del ecualizador (milibelios) según su frecuencia central (Hz). Separado para probarlo. */
    static int level(int hz){return hz<150?-900:hz<600?-300:hz>=900&&hz<=4500?450:0;}
    boolean active(){return eq!=null||loud!=null;}
    void release(){
        try{if(eq!=null)eq.release();}catch(RuntimeException ignored){}
        try{if(loud!=null)loud.release();}catch(RuntimeException ignored){}
        eq=null;loud=null;
    }
}
