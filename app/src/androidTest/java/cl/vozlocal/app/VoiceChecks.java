package cl.vozlocal.app;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.9.7: «¿Cuántas personas hablan?» y unir voces repetidas (VoiceMatch), el guardado automático en la carpeta rápida
 * (AutoSave) y lo nuevo del informe de soporte. Sin llamadas reales a APIs.
 */
final class VoiceChecks {
    private VoiceChecks(){}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JSONObject seg(String who,double a,double b,String text)throws Exception{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}

    static void run(Context c)throws Exception{
        transcript();
        realAudio(c);
        settingsAndSaving(c);
        report(c);
    }

    /** Unir, cambiar la cantidad y deshacer, respetando lo corregido a mano y los nombres. */
    private static void transcript()throws Exception{
        Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",false).put("names",new JSONObject().put("block1:B","Ana")).put("segments",new JSONArray()
            .put(seg("voice:me",0,5,"uno")).put(seg("block0:A",5,10,"dos")).put(seg("block1:A",600,605,"tres")).put(seg("block1:B",605,610,"cuatro")).put(seg("block2:C",1200,1201,"cinco"))));
        check(t.rawVoices().size()==5&&!t.unified()&&t.people()==0,"Fresh transcript should have 5 raw voices");
        Map<String,String> into=new HashMap<>();into.put("block1:A","voice:me");into.put("block1:B","block0:A");into.put("block2:C","block0:A");
        t.unify(into,2);
        check(t.speakers().size()==2&&t.unified()&&t.people()==2&&t.rawVoices().size()==5,"Unify to 2 people failed: "+t.data);
        check(t.speakers().get("block0:A").equals("Ana"),"The name of a joined voice must pass to its person: "+t.speakers());
        check(Transcript.raw(t.segments().getJSONObject(3)).equals("block1:B")&&t.segments().getJSONObject(3).getString("speaker").equals("block0:A"),"Raw voice not kept");
        check(!t.segments().getJSONObject(0).has("raw"),"A voice that stays itself needs no raw mark");
        // Corrección a mano: el tramo 3 lo dijo «yo». Al reagrupar a 3 personas se respeta.
        t.assign(3,4,"voice:me");
        Map<String,String> three=new HashMap<>();three.put("block1:A","voice:me");three.put("block2:C","block1:B");
        t.unify(three,3);
        check(t.speakers().size()==3&&t.segments().getJSONObject(3).getString("speaker").equals("voice:me")&&t.segments().getJSONObject(3).getString("orig").equals("block1:B")&&Transcript.raw(t.segments().getJSONObject(3)).equals("block1:B"),
            "Manual correction lost when regrouping: "+t.data);
        check(t.segments().getJSONObject(4).getString("speaker").equals("block1:B"),"Regroup must start from the raw voices: "+t.data);
        t.separate();
        check(t.speakers().size()==5&&!t.unified()&&t.people()==0&&!t.edited(),"Separate must bring back the model's voices: "+t.data);
        // La nota sigue a la voz unida (marca S2 → block1:B, que ahora dice block0:A).
        t.unify(into,2);
        JSONObject note=new JSONObject().put("speakers",new JSONObject().put("S1","voice:me").put("S2","block1:B"));
        check("Ana".equals(Notes.names(note,t).get("S2")),"The note must follow a joined voice: "+Notes.names(note,t));
    }

    /** «Voz» sintética: ruido filtrado por dos resonadores propios de cada persona, en «sílabas». */
    private static short[] voice(int person,double seconds,long seed){
        Random rnd=new Random(seed);int n=(int)(seconds*44100);short[] out=new short[n];
        double[][] formants={{300,2300},{700,1200}};double f1=formants[person][0],f2=formants[person][1],r=0.985;
        double a1=2*r*Math.cos(2*Math.PI*f1/44100),a2=2*r*Math.cos(2*Math.PI*f2/44100),y1=0,y2=0,z1=0,z2=0;
        for(int i=0;i<n;i++){
            double gate=(i%17640)<13230?1:0.02,x=rnd.nextGaussian()*gate;
            double y=x+a1*y1-r*r*y2;y2=y1;y1=y;double z=x+a2*z1-r*r*z2;z2=z1;z1=z;
            out[i]=(short)Math.max(-30000,Math.min(30000,(y+0.7*z)*60));
        }
        return out;
    }
    private static void wav(File f,short[] x)throws IOException{
        ByteBuffer b=ByteBuffer.allocate(44+x.length*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+x.length*2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(x.length*2);
        for(short s:x)b.putShort(s);
        try(FileOutputStream out=new FileOutputStream(f)){out.write(b.array());}
    }
    /**
     * De punta a punta en el teléfono: un audio AAC con dos «personas» que se turnan; la segunda mitad vuelve con las
     * etiquetas cruzadas (como otra parte del audio). Se escuchan los tramos (OrAudio.decodeRanges), se comparan y con
     * «2 personas» cada una queda con todos sus turnos.
     */
    private static void realAudio(Context c)throws Exception{
        int turns=8;double turn=10;short[] all=new short[(int)(turns*turn*44100)];
        for(int i=0;i<turns;i++){short[] v=voice(i%2,turn,100+i);System.arraycopy(v,0,all,(int)(i*turn*44100),v.length);}
        File source=new File(c.getCacheDir(),"voice-check.wav");wav(source,all);
        Recording r=new Recording(UUID.randomUUID().toString(),"Voces de prueba",System.currentTimeMillis(),(long)(turns*turn*1000));
        try{
            AudioConvert.convert(source,r.audio(c),0,(long)(turns*turn*1000),new HttpApi());r.save(c);
            JSONArray segments=new JSONArray();
            for(int i=0;i<turns;i++){
                int person=i%2;boolean second=i>=turns/2;
                // Segunda mitad: la persona 0 vuelve como «block1:B» y la 1 como «block1:A».
                String label=second?"block1:"+(person==0?"B":"A"):"block0:"+(person==0?"A":"B");
                segments.put(seg(label,i*turn,(i+1)*turn,"turno "+i));
            }
            Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",false).put("names",new JSONObject()).put("segments",segments));
            t.save(c,r.id);
            // Tramos sueltos: cada uno llega completo, a 16 kHz, con su índice.
            long[] starts={2_000_000L,35_000_000L},ends={5_000_000L,36_500_000L};int[] counts=new int[2];
            OrAudio.decodeRanges(r.audio(c),starts,ends,new HttpApi(),(range,data,count)->counts[range]+=count);
            check(Math.abs(counts[0]-3*16000)<1600&&Math.abs(counts[1]-24000)<1600,"decodeRanges returned the wrong amount of audio: "+Arrays.toString(counts));
            Map<String,VoiceMatch.Print> prints=VoiceMatch.ensure(c,r.id,r.audio(c),t,new HttpApi());
            check(prints.size()==4,"Every voice should get a print: "+prints.keySet());
            check(FilesStore.file(c,r.id,".voices.json").isFile(),"Prints must be saved for later regrouping");
            VoiceMatch.Result result=VoiceMatch.apply(t,prints,2);
            check(result!=null&&result.before==4&&result.after==2,"4 voices should become 2 people");
            JSONArray s=t.segments();String first=s.getJSONObject(0).getString("speaker"),second=s.getJSONObject(1).getString("speaker");
            check(!first.equals(second),"The two people were merged into one");
            for(int i=0;i<turns;i++)check(s.getJSONObject(i).getString("speaker").equals(i%2==0?first:second),"Turn "+i+" went to the wrong person: "+t.data);
            // Las huellas guardadas sirven para la próxima vez (sin escuchar de nuevo) y se borran con la grabación.
            long stamp=FilesStore.file(c,r.id,".voices.json").lastModified();
            check(VoiceMatch.ensure(c,r.id,r.audio(c),t,new HttpApi()).size()==4&&FilesStore.file(c,r.id,".voices.json").lastModified()==stamp,"Saved prints were not reused");
            // Sin decir cuántas: voces claramente distintas no se juntan y las repetidas sí.
            Transcript auto=new Transcript(new JSONObject(t.data.toString()));auto.separate();
            VoiceMatch.apply(auto,prints,0);
            check(auto.speakers().size()>=2&&auto.speakers().size()<=3,"Automatic grouping wrong: "+auto.speakers().size()+" people");
            // Estado: la cantidad dicha se guarda y un reintento la conserva.
            Pipeline.people(c,r.id,4);check(FilesStore.state(c,r.id).optInt("people")==4,"People not stored");
            Pipeline.people(c,r.id,0);check(!FilesStore.state(c,r.id).has("people"),"People 0 must clear the state");
        }finally{source.delete();r.delete(c);check(!FilesStore.file(c,r.id,".voices.json").exists(),"Prints left behind after deleting");}
    }

    /**
     * Medición con una reunión real (no es parte de la suite): cache/ami/NOMBRE.wav (16 kHz mono) y NOMBRE.rttm del corpus
     * AMI. Se pasa a AAC como una grabación, se arma la transcripción con las voces renombradas por bloque de 11 min, con
     * voces fantasma y 8 % de tramos mal atribuidos, y se unen. Devuelve cuántas voces había, cuántas personas quedaron,
     * qué parte del tiempo quedó con la persona correcta y cuánto tardó escuchar y comparar.
     */
    static String meeting(Context c,String name)throws Exception{
        File dir=new File(c.getCacheDir(),"ami"),source=new File(dir,name+".wav");
        List<double[]> times=new ArrayList<>();List<String> who=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new FileReader(new File(dir,name+".rttm")))){String line;while((line=reader.readLine())!=null){String[] f=line.trim().split("\\s+");if(f.length<8)continue;double a=Double.parseDouble(f[3]),d=Double.parseDouble(f[4]);if(d<0.6)continue;times.add(new double[]{a,a+d});who.add(f[7]);}}
        List<String> people=new ArrayList<>(new TreeSet<>(who));
        long duration=AudioConvert.duration(source);
        Recording r=new Recording(UUID.randomUUID().toString(),"Reunión real",System.currentTimeMillis(),duration);
        try{
            long t0=android.os.SystemClock.elapsedRealtime();
            AudioConvert.convert(source,r.audio(c),0,duration,new HttpApi());r.save(c);
            long encoded=android.os.SystemClock.elapsedRealtime()-t0;
            Random rnd=new Random(1);JSONArray segments=new JSONArray();
            List<Integer> order=new ArrayList<>();for(int i=0;i<times.size();i++)order.add(i);order.sort((x,y)->Double.compare(times.get(x)[0],times.get(y)[0]));
            for(int i:order){
                int block=(int)(times.get(i)[0]/(11*60));String label=who.get(i);
                if(rnd.nextDouble()<0.08)label=people.get(rnd.nextInt(people.size()));
                String key="block"+block+":"+label;if(rnd.nextDouble()<0.3&&((block+label.hashCode())&1)==0)key+="x";
                segments.put(seg(key,times.get(i)[0],times.get(i)[1],who.get(i)));
            }
            Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",false).put("names",new JSONObject()).put("segments",segments));
            t.save(c,r.id);
            long t1=android.os.SystemClock.elapsedRealtime();
            Map<String,VoiceMatch.Print> prints=VoiceMatch.ensure(c,r.id,r.audio(c),t,new HttpApi());
            long heard=android.os.SystemClock.elapsedRealtime()-t1;
            StringBuilder out=new StringBuilder(name+": audio "+Recording.time(duration)+" · pasar a AAC "+encoded/1000+" s · "+t.rawVoices().size()+" voces, "+prints.size()+" huellas · escuchar y resumir "+heard/1000.0+" s\n");
            for(int say:new int[]{people.size(),0}){
                Transcript copy=new Transcript(new JSONObject(t.data.toString()));long t2=android.os.SystemClock.elapsedRealtime();
                VoiceMatch.Result result=VoiceMatch.apply(copy,prints,say);
                // «text» lleva a la persona real: tiempo por grupo y persona.
                Map<String,Map<String,Double>> groups=new HashMap<>();double total=0;JSONArray s=copy.segments();
                for(int i=0;i<s.length();i++){JSONObject x=s.getJSONObject(i);double d=x.getDouble("end")-x.getDouble("start");groups.computeIfAbsent(x.getString("speaker"),k->new HashMap<>()).merge(x.getString("text"),d,Double::sum);total+=d;}
                double good=0;for(Map<String,Double> g:groups.values())good+=Collections.max(g.values());
                out.append("  ").append(say>0?"diciendo "+say+" personas":"sin decir cuántas").append(": ").append(result.before).append(" voces → ").append(result.after).append(" personas · ").append(Math.round(1000*good/total)/10.0).append(" % del tiempo con la persona correcta · agrupar ").append(android.os.SystemClock.elapsedRealtime()-t2).append(" ms\n");
            }
            return out.toString();
        }finally{r.delete(c);}
    }

    /** Lo nuevo de Ajustes y del guardado automático, sin carpeta elegida. */
    private static void settingsAndSaving(Context c)throws Exception{
        Settings s=new Settings(c);
        boolean hadAsk=s.prefs.contains("askPeople"),ask=s.askPeople(),hadAuto=s.prefs.contains("autoInbox"),auto=s.autoInbox();String tree=s.prefs.getString("saveTree",null);
        try{
            s.prefs.edit().remove("askPeople").remove("autoInbox").remove("saveTree").commit();
            check(s.askPeople()&&s.autoInbox(),"0.9.7 defaults: ask how many people and save by itself");
            check(!AutoSave.on(c)&&"none".equals(Inbox.permission(c))&&"none".equals(Inbox.place(c)),"Without a folder there is nothing to save to");
            Recording r=new Recording(UUID.randomUUID().toString(),"Sin carpeta",System.currentTimeMillis(),5000);
            check(!AutoSave.afterTranscription(c,r),"Auto save must do nothing without a folder");
            // Una carpeta elegida cuyo permiso ya no está (lo que pasó con Drive): se detecta sin tocar la red.
            s.prefs.edit().putString("saveTree","content://com.google.android.apps.docs.storage/tree/abc").commit();
            check("lost".equals(Inbox.permission(c))&&"drive".equals(Inbox.place(c))&&AutoSave.on(c),"A folder without permission must read as lost");
            s.prefs.edit().putBoolean("autoInbox",false).commit();check(!AutoSave.on(c),"Auto save can be turned off");
        }finally{
            android.content.SharedPreferences.Editor e=s.prefs.edit();
            if(hadAsk)e.putBoolean("askPeople",ask);else e.remove("askPeople");if(hadAuto)e.putBoolean("autoInbox",auto);else e.remove("autoInbox");
            if(tree==null)e.remove("saveTree");else e.putString("saveTree",tree);e.commit();
        }
        check(RecordingActions.PEOPLE_MAX==8,"People sheet offers 1 to 8");
    }

    /** El informe de soporte trae los ajustes (sin datos personales). */
    private static void report(Context c)throws Exception{
        File f=Diagnostics.export(c);String text=new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        for(String part:new String[]{"AJUSTES\n","Carpeta rápida: ","preguntar cuántas personas: ","guardar sola al terminar: ","Memoria: ","Espacio libre: ","Permisos: micrófono "})
            check(text.contains(part),"Support report lacks «"+part.trim()+"»");
        check(!text.contains("sk-or-")&&!text.contains("content://"),"Support report must not carry keys or folder paths");
    }
}
