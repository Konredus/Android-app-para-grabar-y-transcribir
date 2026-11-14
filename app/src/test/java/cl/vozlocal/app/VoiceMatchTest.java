package cl.vozlocal.app;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.Assume;
import org.junit.Test;

/**
 * 0.9.7: unir voces repetidas (VoiceMatch). Dos niveles:
 * - siempre: voces sintéticas (cada «persona», un filtro distinto sobre ruido) repartidas en varias etiquetas, que el
 *   agrupamiento debe volver a juntar, con la cantidad dicha y sin decirla;
 * - opcional, con reuniones reales: si la variable de entorno VERBAPP_AMI apunta a una carpeta con «X.wav» (16 kHz mono)
 *   y «X.rttm» del corpus AMI (https://groups.inf.ed.ac.uk/ami/corpus/, micrófono «Array1-01»), se parte la reunión en
 *   bloques de 11 min, se renombran las voces por bloque, se agregan voces fantasma y tramos mal atribuidos, y se mide
 *   cuánto tiempo queda con la persona correcta. Con ES2004b e IS1009b da 90–93 % (techo 94–95 %).
 */
public class VoiceMatchTest {
    /** «Voz» sintética: ruido blanco pasado por dos resonadores (formantes) propios de cada persona, con pausas. */
    private static short[] voice(int person,int seconds,long seed){
        Random rnd=new Random(seed);int n=seconds*VoiceMatch.RATE;short[] out=new short[n];
        double[][] formants={{300,2300},{700,1200},{450,1700},{600,2800},{350,900}};
        double f1=formants[person][0],f2=formants[person][1];
        double r=0.97,a1=2*r*Math.cos(2*Math.PI*f1/VoiceMatch.RATE),a2=2*r*Math.cos(2*Math.PI*f2/VoiceMatch.RATE);
        double y1=0,y2=0,z1=0,z2=0;
        for(int i=0;i<n;i++){
            // «sílabas»: 300 ms de sonido y 100 ms de pausa
            double gate=(i%(VoiceMatch.RATE*4/10))<VoiceMatch.RATE*3/10?1:0.02;
            double x=rnd.nextGaussian()*gate;
            double y=x+a1*y1-r*r*y2;y2=y1;y1=y;
            double z=x+a2*z1-r*r*z2;z2=z1;z1=z;
            out[i]=(short)Math.max(-32000,Math.min(32000,(y+0.7*z)*120));
        }
        return out;
    }
    private static VoiceMatch.Print print(int person,int seconds,long seed){
        VoiceMatch.Print p=new VoiceMatch.Print();short[] x=voice(person,seconds,seed);VoiceMatch.analyze(x,x.length,p);return p;
    }

    @Test public void repeatedVoicesAreJoined(){
        // 4 personas, cada una partida en 3 etiquetas (como vuelven de 3 partes del audio), más una voz conocida.
        Map<String,VoiceMatch.Print> prints=new LinkedHashMap<>();List<String> order=new ArrayList<>();
        for(int block=0;block<3;block++)for(int person=0;person<4;person++){
            String id=person==0?(block==0?"voice:me":"block"+block+":me"):"block"+block+":"+(char)('A'+person);
            prints.put(id,print(person,20,block*10+person));order.add(id);
        }
        Set<String> known=Collections.singleton("voice:me");
        for(int people:new int[]{4,0}){
            Map<String,String> into=VoiceMatch.cluster(prints,people,known,order);
            Set<String> reps=new HashSet<>(into.values());
            assertEquals("people="+people+" → "+into,4,reps.size());
            for(int block=0;block<3;block++){
                assertEquals("voice:me",into.get(block==0?"voice:me":"block"+block+":me"));
                for(int person=1;person<4;person++)assertEquals("people="+people,"block0:"+(char)('A'+person),into.get("block"+block+":"+(char)('A'+person)));
            }
        }
        // Con menos personas de las que hay, se juntan las más parecidas; con más, no se inventa nadie.
        assertEquals(2,new HashSet<>(VoiceMatch.cluster(prints,2,known,order).values()).size());
        assertEquals(4,new HashSet<>(VoiceMatch.cluster(prints,4,known,order).values()).size());
    }

    @Test public void knownVoicesNeverMergeAndSmallVoicesFollowTheirPerson(){
        Map<String,VoiceMatch.Print> prints=new LinkedHashMap<>();
        prints.put("voice:me",print(0,20,1));prints.put("voice:abc123",print(0,20,2));   // suenan igual, pero son dos voces conocidas
        prints.put("block1:A",print(1,20,3));prints.put("block2:C",print(1,1,4));      // un «ajá» de la persona 1
        List<String> order=new ArrayList<>(prints.keySet());Set<String> known=new HashSet<>(Arrays.asList("voice:me","voice:abc123"));
        Map<String,String> into=VoiceMatch.cluster(prints,2,known,order);
        assertNotEquals(into.get("voice:me"),into.get("voice:abc123"));
        assertEquals(into.get("block1:A"),into.get("block2:C"));
        // Huella: se guarda y se lee igual.
        VoiceMatch.Print p=prints.get("block1:A");
        try{VoiceMatch.Print q=VoiceMatch.Print.of(new org.json.JSONObject(p.json().toString()));assertEquals(p.n,q.n,0);assertEquals(p.logdet(),q.logdet(),0.01);}
        catch(org.json.JSONException e){fail(e.toString());}
    }

    // ---------- Reuniones reales (opcional) ----------
    @Test public void realMeetings()throws Exception{
        String dir=System.getenv("VERBAPP_AMI");Assume.assumeTrue("VERBAPP_AMI no está definida",dir!=null&&new File(dir).isDirectory());
        StringBuilder report=new StringBuilder();
        for(File wav:Objects.requireNonNull(new File(dir).listFiles((d,n)->n.endsWith(".wav")))){
            File rttm=new File(dir,wav.getName().replace(".wav",".rttm"));if(!rttm.exists())continue;
            short[] audio=wav(wav);List<double[]> segs=new ArrayList<>();List<String> who=new ArrayList<>();
            for(String line:Files.readAllLines(rttm.toPath(),StandardCharsets.UTF_8)){String[] f=line.trim().split("\\s+");if(f.length<8)continue;double a=Double.parseDouble(f[3]),d=Double.parseDouble(f[4]);if(d<0.6)continue;segs.add(new double[]{a,a+d});who.add(f[7]);}
            List<String> people=new ArrayList<>(new TreeSet<>(who));double sum=0;int worstOk=1;double worst=1;
            for(int seed=1;seed<=4;seed++){
                Random rnd=new Random(seed);Map<String,List<Integer>> units=new LinkedHashMap<>();
                for(int i=0;i<segs.size();i++){
                    int block=(int)(segs.get(i)[0]/(11*60));String label=who.get(i);
                    if(rnd.nextDouble()<0.08)label=people.get(rnd.nextInt(people.size()));
                    String key="block"+block+":"+label;
                    if(rnd.nextDouble()<0.3&&((block+label.hashCode()+seed)&1)==0)key+="'";
                    units.computeIfAbsent(key,k->new ArrayList<>()).add(i);
                }
                Map<String,VoiceMatch.Print> prints=new LinkedHashMap<>();Map<String,Map<String,Double>> truth=new LinkedHashMap<>();
                for(Map.Entry<String,List<Integer>> e:units.entrySet()){
                    List<Integer> list=new ArrayList<>(e.getValue());list.sort((x,y)->Double.compare(segs.get(y)[1]-segs.get(y)[0],segs.get(x)[1]-segs.get(x)[0]));
                    VoiceMatch.Print p=new VoiceMatch.Print();double used=0;
                    for(int i:list){if(used>=VoiceMatch.PER_VOICE_S)break;double a=segs.get(i)[0]+0.2,b=segs.get(i)[1]-0.2;if(b-a<0.4)continue;
                        int from=(int)(a*VoiceMatch.RATE),to=Math.min(audio.length,(int)(b*VoiceMatch.RATE));if(to-from<VoiceMatch.NFFT)continue;
                        VoiceMatch.analyze(Arrays.copyOfRange(audio,from,to),to-from,p);used+=b-a;}
                    if(p.n<60)continue;prints.put(e.getKey(),p);
                    Map<String,Double> t=new HashMap<>();for(int i:e.getValue())t.merge(who.get(i),segs.get(i)[1]-segs.get(i)[0],Double::sum);truth.put(e.getKey(),t);
                }
                Map<String,String> into=VoiceMatch.cluster(prints,people.size(),Collections.emptySet(),new ArrayList<>(prints.keySet()));
                Map<String,Map<String,Double>> groups=new HashMap<>();double total=0;
                for(String u:prints.keySet()){Map<String,Double> g=groups.computeIfAbsent(into.get(u),k->new HashMap<>());for(Map.Entry<String,Double> t:truth.get(u).entrySet()){g.merge(t.getKey(),t.getValue(),Double::sum);total+=t.getValue();}}
                double good=0;Set<String> owners=new HashSet<>();
                for(Map<String,Double> g:groups.values()){String owner=Collections.max(g.entrySet(),Map.Entry.comparingByValue()).getKey();owners.add(owner);good+=g.get(owner);}
                double purity=good/total;sum+=purity;worst=Math.min(worst,purity);if(owners.size()!=people.size())worstOk=0;
                int auto=new HashSet<>(VoiceMatch.cluster(prints,0,Collections.emptySet(),new ArrayList<>(prints.keySet())).values()).size();
                report.append(String.format(Locale.ROOT,"%s seed %d: %d voces → %d personas, %.1f %% bien atribuido; sin decir cuántas: %d%n",wav.getName(),seed,prints.size(),groups.size(),100*purity,auto));
            }
            report.append(String.format(Locale.ROOT,"%s: media %.1f %%, peor %.1f %%%n",wav.getName(),100*sum/4,100*worst));
            assertTrue(report.toString(),sum/4>0.85&&worst>0.75&&worstOk==1);
        }
        Files.write(new File(dir,"java-result.txt").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static short[] wav(File f)throws IOException{
        byte[] all=Files.readAllBytes(f.toPath());int at=12;
        while(at+8<=all.length){String id=new String(all,at,4,StandardCharsets.US_ASCII);int size=ByteBuffer.wrap(all,at+4,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if(id.equals("data")){int n=Math.min(size,all.length-at-8)/2;short[] out=new short[n];ByteBuffer.wrap(all,at+8,n*2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);return out;}
            at+=8+size+(size&1);}
        throw new IOException("WAV sin datos");
    }
}
