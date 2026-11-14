package cl.vozlocal.app;

import android.content.Context;
import java.io.File;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 0.9.7: une las voces repetidas. En un audio largo cada parte vuelve con sus propias etiquetas y la misma persona
 * aparece como «Persona 3», «Persona 7» y «Persona 12» (en una reunión de 1 h 22 llegaron a sobrar 15 voces). Aquí se
 * compara cómo suena cada voz y se dejan tantas personas como dijo quien grabó («¿Cuántas personas hablan?»), o las
 * que resulten claramente distintas si no lo dijo.
 *
 * Todo pasa en el teléfono, sin enviar nada y sin costo:
 * 1. De cada voz se toman hasta 90 s de sus tramos más largos (a lo más 20 min de audio en total) y se resume su timbre:
 *    16 coeficientes cepstrales (MFCC) por cuadro de 32 ms, de los que se guarda la cuenta, la suma y los productos
 *    ({@link Print}). Con eso se arma una gaussiana por voz, y dos huellas se suman para tener la del grupo.
 * 2. Se unen de a dos las voces que menos se distinguen ({@link #glr}: cuánto empeora describir a las dos con una sola
 *    gaussiana) hasta llegar a la cantidad pedida. Dos voces conocidas distintas («Mi voz» y otra guardada) nunca se unen.
 * 3. Las voces que casi no hablan no cuentan como persona: se suman a la persona a la que más se parecen.
 *
 * Probado con reuniones reales de 4 personas grabadas con micrófono lejano (corpus AMI), partidas en bloques de 11 min
 * con voces fantasma y 8 % de tramos mal atribuidos: de unas 20 voces recupera las 4 personas con 90–93 % del tiempo
 * bien atribuido (el techo, dado lo que entrega el modelo, es 94–95 %). Sin decir la cantidad, {@link #AUTO_LAMBDA}
 * la encuentra sola en casi todos los casos y, ante la duda, deja una voz de más en vez de juntar a dos personas.
 */
final class VoiceMatch {
    private VoiceMatch(){}
    static final int RATE=OrAudio.RATE,NFFT=512,HOP=256,NMEL=26,NCEP=16;
    private static final double LO=100,HI=7000;
    /** Audio que se escucha de cada voz y en total (s). Más no mejora y alarga la espera. */
    static final double PER_VOICE_S=90,TOTAL_S=20*60;
    /** Un tramo aporta a lo más esto (s): así una voz se oye en varios momentos y no en un solo monólogo. */
    static final double PICK_MAX_S=30;
    /** Cuadros (de 16 ms) para que una voz cuente como persona: unos 6 s de habla, y al menos el 1,5 % del total. */
    static final int BIG_FRAMES=400;static final double BIG_SHARE=0.015;
    /** Sin cantidad dicha: se deja de unir cuando la mejor unión ya «cuesta» más que esta penalización (BIC). */
    static final double AUTO_LAMBDA=5;

    // ---------- Huella de una voz ----------
    static final class Print {
        double n;final double[] sum=new double[NCEP];final double[] outer=new double[NCEP*(NCEP+1)/2];
        void add(double[] c){
            n++;int k=0;
            for(int i=0;i<NCEP;i++){sum[i]+=c[i];for(int j=i;j<NCEP;j++)outer[k++]+=c[i]*c[j];}
        }
        Print plus(Print o){
            Print p=new Print();p.n=n+o.n;
            for(int i=0;i<NCEP;i++)p.sum[i]=sum[i]+o.sum[i];
            for(int i=0;i<outer.length;i++)p.outer[i]=outer[i]+o.outer[i];
            return p;
        }
        double[] mean(){double[] m=new double[NCEP];for(int i=0;i<NCEP;i++)m[i]=sum[i]/n;return m;}
        /** Factor de Cholesky (triangular inferior) de la covarianza, con un piso que la deja siempre invertible. */
        double[][] cholesky(){
            double[] m=mean();double ridge=1e-3;
            for(int attempt=0;attempt<6;attempt++,ridge*=10){
                double[][] l=new double[NCEP][NCEP];boolean ok=true;int k=0;
                double[][] c=new double[NCEP][NCEP];
                for(int i=0;i<NCEP;i++)for(int j=i;j<NCEP;j++){double v=outer[k++]/n-m[i]*m[j];c[i][j]=v;c[j][i]=v;}
                for(int i=0;i<NCEP&&ok;i++)for(int j=0;j<=i;j++){
                    double s=c[i][j]+(i==j?ridge:0);for(int x=0;x<j;x++)s-=l[i][x]*l[j][x];
                    if(i==j){if(s<=1e-12){ok=false;break;}l[i][i]=Math.sqrt(s);}else l[i][j]=s/l[j][j];
                }
                if(ok)return l;
            }
            double[][] l=new double[NCEP][NCEP];for(int i=0;i<NCEP;i++)l[i][i]=1;return l;
        }
        double logdet(){double[][] l=cholesky();double s=0;for(int i=0;i<NCEP;i++)s+=Math.log(l[i][i]);return 2*s;}
        JSONObject json()throws JSONException{
            JSONArray s=new JSONArray(),o=new JSONArray();for(double v:sum)s.put(round(v));for(double v:outer)o.put(round(v));
            return new JSONObject().put("n",n).put("sum",s).put("outer",o);
        }
        static Print of(JSONObject j){
            if(j==null)return null;JSONArray s=j.optJSONArray("sum"),o=j.optJSONArray("outer");Print p=new Print();
            if(s==null||o==null||s.length()!=p.sum.length||o.length()!=p.outer.length)return null;
            p.n=j.optDouble("n",0);for(int i=0;i<p.sum.length;i++)p.sum[i]=s.optDouble(i);for(int i=0;i<p.outer.length;i++)p.outer[i]=o.optDouble(i);
            return p.n>0?p:null;
        }
        private static double round(double v){return Math.round(v*1000d)/1000d;}
    }
    /** Cuánto empeora describir las dos voces con una sola gaussiana (ΔBIC sin penalización). Menor = más parecidas. */
    static double glr(Print a,Print b){
        Print m=a.plus(b);return (m.n*m.logdet()-a.n*a.logdet()-b.n*b.logdet())/2;
    }
    /** Distancia de una voz con poco audio a un grupo: qué tan lejos queda su promedio, medido con la forma del grupo. */
    static double mahalanobis(Print small,Print group){
        double[] a=small.mean(),b=group.mean();double[][] l=group.cholesky();double[] y=new double[NCEP];double d=0;
        for(int i=0;i<NCEP;i++){double s=a[i]-b[i];for(int x=0;x<i;x++)s-=l[i][x]*y[x];y[i]=s/l[i][i];d+=y[i]*y[i];}
        return d;
    }

    // ---------- Agrupar ----------
    /**
     * Agrupa las voces. prints: huella de cada voz. people: cuántas personas hablan (0 = no se sabe). known: voces que
     * no se pueden unir entre sí (las conocidas: son personas distintas con certeza). order: orden de aparición, para
     * elegir a quién representa cada grupo. Devuelve voz → voz que la representa (cada grupo, su voz conocida o la
     * primera que apareció); las que no cambian apuntan a sí mismas.
     */
    static Map<String,String> cluster(Map<String,Print> prints,int people,Set<String> known,List<String> order){
        List<String> names=new ArrayList<>();for(String id:order)if(prints.containsKey(id))names.add(id);
        for(String id:prints.keySet())if(!names.contains(id))names.add(id);
        Map<String,String> out=new LinkedHashMap<>();for(String id:names)out.put(id,id);
        if(names.size()<2)return out;
        double total=0;for(String id:names)total+=prints.get(id).n;
        double floor=Math.max(BIG_FRAMES,BIG_SHARE*total);
        List<String> big=new ArrayList<>();
        // Una voz conocida siempre cuenta como persona, aunque hable poco.
        for(String id:names)if(prints.get(id).n>=floor||known.contains(id))big.add(id);
        if(people>0&&big.size()<people){
            // Menos voces «grandes» que personas: suben las que más hablan entre las chicas.
            List<String> rest=new ArrayList<>(names);rest.removeAll(big);rest.sort((a,b)->Double.compare(prints.get(b).n,prints.get(a).n));
            for(String id:rest){if(big.size()>=people)break;if(prints.get(id).n>=40)big.add(id);}
        }
        if(big.isEmpty())return out;
        // Grupos: huella sumada y miembros.
        List<Print> sums=new ArrayList<>();List<List<String>> members=new ArrayList<>();
        for(String id:names)if(big.contains(id)){sums.add(prints.get(id));List<String> m=new ArrayList<>();m.add(id);members.add(m);}
        double penalty=0.5*(NCEP+NCEP*(NCEP+1)/2d);
        while(sums.size()>1&&(people<=0||sums.size()>people)){
            int bi=-1,bj=-1;double best=Double.MAX_VALUE;
            for(int i=0;i<sums.size();i++)for(int j=i+1;j<sums.size();j++){
                if(knownIn(members.get(i),known)&&knownIn(members.get(j),known))continue;
                double d=glr(sums.get(i),sums.get(j));
                if(people<=0)d-=AUTO_LAMBDA*penalty*Math.log(sums.get(i).n+sums.get(j).n);
                if(d<best){best=d;bi=i;bj=j;}
            }
            if(bi<0||(people<=0&&best>0))break;
            sums.set(bi,sums.get(bi).plus(sums.get(bj)));members.get(bi).addAll(members.get(bj));sums.remove(bj);members.remove(bj);
        }
        // Las que casi no hablan: a la persona a la que más se parecen.
        for(String id:names){
            if(big.contains(id))continue;Print p=prints.get(id);int to=0;double best=Double.MAX_VALUE;
            for(int g=0;g<sums.size();g++){double d=mahalanobis(p,sums.get(g));if(d<best){best=d;to=g;}}
            members.get(to).add(id);
        }
        for(List<String> group:members){
            String rep=null;for(String id:names)if(group.contains(id)&&known.contains(id)){rep=id;break;}
            if(rep==null)for(String id:names)if(group.contains(id)){rep=id;break;}
            for(String id:group)out.put(id,rep);
        }
        return out;
    }
    private static boolean knownIn(List<String> group,Set<String> known){for(String id:group)if(known.contains(id))return true;return false;}

    // ---------- Escuchar cada voz ----------
    /** Tramo elegido de una voz. */
    private static final class Pick{final String voice;final double from,to;Pick(String voice,double from,double to){this.voice=voice;this.from=from;this.to=to;}}
    /**
     * Huella de cada voz original de la transcripción (ver {@link Transcript#raw}), escuchando sus tramos en el audio.
     * Una voz sin ningún tramo aprovechable no aparece en el resultado.
     */
    static Map<String,Print> prints(File audio,JSONArray segments,HttpApi cancel)throws Exception{
        Map<String,List<double[]>> byVoice=new LinkedHashMap<>();
        for(int i=0;i<segments.length();i++){
            JSONObject s=segments.optJSONObject(i);if(s==null)continue;
            double from=s.optDouble("start",0),to=s.optDouble("end",from);if(to-from<0.3)continue;
            byVoice.computeIfAbsent(Transcript.raw(s),k->new ArrayList<>()).add(new double[]{from,to});
        }
        double each=Math.min(PER_VOICE_S,TOTAL_S/Math.max(1,byVoice.size()));
        List<Pick> picks=new ArrayList<>();
        for(Map.Entry<String,List<double[]>> e:byVoice.entrySet()){
            List<double[]> list=e.getValue();list.sort((a,b)->Double.compare(b[1]-b[0],a[1]-a[0]));double used=0;
            for(double[] r:list){
                if(used>=each)break;
                // Los bordes de un tramo suelen traer la voz de al lado: se recortan si el tramo da para eso.
                double from=r[0],to=r[1];if(to-from>=1.2){from+=0.2;to-=0.2;}
                to=Math.min(to,from+Math.min(PICK_MAX_S,each-used+1));
                if(to-from<0.3)continue;
                picks.add(new Pick(e.getKey(),from,to));used+=to-from;
            }
        }
        picks.sort((a,b)->Double.compare(a.from,b.from));
        long[] starts=new long[picks.size()],ends=new long[picks.size()];
        for(int i=0;i<picks.size();i++){starts[i]=(long)(picks.get(i).from*1_000_000);ends[i]=(long)(picks.get(i).to*1_000_000);}
        Map<String,Print> out=new LinkedHashMap<>();
        final int[] current={-1};final short[][] buffer={new short[RATE*4]};final int[] held={0};
        Runnable flush=()->{
            if(current[0]>=0&&held[0]>=NFFT){String voice=picks.get(current[0]).voice;Print p=out.get(voice);if(p==null){p=new Print();out.put(voice,p);}analyze(buffer[0],held[0],p);}
            held[0]=0;
        };
        OrAudio.decodeRanges(audio,starts,ends,cancel,(range,data,count)->{
            if(range!=current[0]){flush.run();current[0]=range;}
            if(held[0]+count>buffer[0].length)buffer[0]=Arrays.copyOf(buffer[0],Math.max(buffer[0].length*2,held[0]+count));
            System.arraycopy(data,0,buffer[0],held[0],count);held[0]+=count;
        });
        flush.run();
        out.values().removeIf(p->p.n<1);
        return out;
    }

    // ---------- Timbre (MFCC) ----------
    private static final double[] WINDOW=new double[NFFT],COS=new double[NFFT/2],SIN=new double[NFFT/2];
    private static final double[][] MEL=new double[NMEL][NFFT/2+1],DCT=new double[NCEP][NMEL];
    private static final int[] MEL_FROM=new int[NMEL],MEL_TO=new int[NMEL];
    static{
        for(int i=0;i<NFFT;i++)WINDOW[i]=0.5-0.5*Math.cos(2*Math.PI*i/NFFT);
        for(int i=0;i<NFFT/2;i++){COS[i]=Math.cos(2*Math.PI*i/NFFT);SIN[i]=-Math.sin(2*Math.PI*i/NFFT);}
        double lo=mel(LO),hi=mel(HI);double[] bins=new double[NMEL+2];
        for(int i=0;i<bins.length;i++)bins[i]=hertz(lo+(hi-lo)*i/(NMEL+1))*NFFT/RATE;
        for(int i=0;i<NMEL;i++){
            double a=bins[i],b=bins[i+1],c=bins[i+2];MEL_FROM[i]=NFFT/2;MEL_TO[i]=0;
            for(int k=(int)Math.floor(a);k<=(int)Math.ceil(c);k++){
                if(k<0||k>NFFT/2)continue;double w=0;
                if(k>=a&&k<=b&&b>a)w=(k-a)/(b-a);else if(k>b&&k<=c&&c>b)w=(c-k)/(c-b);
                MEL[i][k]=w;if(w>0){MEL_FROM[i]=Math.min(MEL_FROM[i],k);MEL_TO[i]=Math.max(MEL_TO[i],k);}
            }
        }
        for(int j=0;j<NCEP;j++)for(int i=0;i<NMEL;i++)DCT[j][i]=Math.cos(Math.PI*(j+1)*(i+0.5)/NMEL);
    }
    private static double mel(double f){return 2595*Math.log10(1+f/700);}
    private static double hertz(double m){return 700*(Math.pow(10,m/2595)-1);}
    /**
     * Suma a la huella los cuadros con voz de este tramo: se descarta el 30 % más silencioso (pausas y respiraciones).
     * Cada cuadro: sin su promedio, con preénfasis y ventana de Hann → espectro → 26 bandas mel → 16 cepstros.
     */
    static void analyze(short[] x,int count,Print into){
        int frames=1+(count-NFFT)/HOP;if(frames<=0)return;
        double[][] cep=new double[frames][];double[] energy=new double[frames];
        double[] re=new double[NFFT],im=new double[NFFT],power=new double[NFFT/2+1],bands=new double[NMEL];
        for(int f=0;f<frames;f++){
            int at=f*HOP;double mean=0;for(int i=0;i<NFFT;i++)mean+=x[at+i];mean/=NFFT;
            double previous=0;
            for(int i=0;i<NFFT;i++){double v=x[at+i]-mean;re[i]=(i==0?v:v-0.97*previous)*WINDOW[i];previous=v;im[i]=0;}
            fft(re,im);double sum=0;
            for(int k=0;k<=NFFT/2;k++){power[k]=re[k]*re[k]+im[k]*im[k];sum+=power[k];}
            // rfft de numpy suma una sola vez cada banda: igual que el prototipo con que se eligieron los umbrales.
            energy[f]=Math.log(sum+1e-3);
            for(int i=0;i<NMEL;i++){double s=0;for(int k=MEL_FROM[i];k<=MEL_TO[i];k++)s+=MEL[i][k]*power[k];bands[i]=Math.log(s+1e-3);}
            double[] c=new double[NCEP];for(int j=0;j<NCEP;j++){double s=0;for(int i=0;i<NMEL;i++)s+=DCT[j][i]*bands[i];c[j]=s;}
            cep[f]=c;
        }
        double[] sorted=energy.clone();Arrays.sort(sorted);
        // Percentil 30 con interpolación lineal (como numpy.percentile).
        double pos=0.3*(frames-1);int lo=(int)Math.floor(pos);double threshold=sorted[lo]+(lo+1<frames?(pos-lo)*(sorted[lo+1]-sorted[lo]):0);
        for(int f=0;f<frames;f++)if(energy[f]>threshold)into.add(cep[f]);
    }
    /** FFT en el lugar (radix 2). */
    private static void fft(double[] re,double[] im){
        int n=re.length;
        for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
        for(int len=2;len<=n;len<<=1){
            int step=n/len;
            for(int i=0;i<n;i+=len)for(int k=0;k<len/2;k++){
                double wr=COS[k*step],wi=SIN[k*step];int a=i+k,b=i+k+len/2;
                double xr=re[b]*wr-im[b]*wi,xi=re[b]*wi+im[b]*wr;
                re[b]=re[a]-xr;im[b]=im[a]-xi;re[a]+=xr;im[a]+=xi;
            }
        }
    }

    // ---------- Sobre una transcripción ----------
    /** Resultado de {@link #unify}: cuántas voces había y cuántas quedaron. */
    static final class Result{final int before,after;final long elapsedMs;Result(int before,int after,long elapsedMs){this.before=before;this.after=after;this.elapsedMs=elapsedMs;}}
    private static File store(Context c,String id){return FilesStore.file(c,id,".voices.json");}
    /**
     * Marca de la transcripción de la que salieron las huellas: la voz original y los tiempos de cada tramo. Corregir a
     * mano quién habla o poner nombres no la cambia; transcribir de nuevo, sí.
     */
    static String signature(Transcript t){
        long h=1125899906842597L;JSONArray s=t.segments();
        for(int i=0;i<s.length();i++){
            JSONObject seg=s.optJSONObject(i);if(seg==null)continue;
            String line=Transcript.raw(seg)+"@"+Math.round(seg.optDouble("start",0)*100)+"-"+Math.round(seg.optDouble("end",0)*100);
            for(int k=0;k<line.length();k++)h=31*h+line.charAt(k);
        }
        return s.length()+":"+Long.toHexString(h);
    }
    /**
     * Huellas de las voces de esta transcripción: las guardadas, si son de estas mismas voces; si no, se escucha el audio
     * (segundos a un par de minutos, según cuántas voces haya) y se guardan. Así cambiar después la cantidad de personas
     * es inmediato. No toma el candado de los archivos mientras escucha.
     */
    static Map<String,Print> ensure(Context c,String id,File audio,Transcript t,HttpApi cancel)throws Exception{
        Set<String> raw=t.rawVoices();Map<String,Print> out=new LinkedHashMap<>();String signature=signature(t);
        try{
            JSONObject saved;synchronized(FilesStore.LOCK){saved=FilesStore.read(store(c,id));}
            JSONObject j=saved.optJSONObject("prints");
            // Solo sirven si son de ESTA transcripción: tras «Volver a transcribir» (o volver a la versión anterior) las
            // mismas etiquetas («block1:B») pueden ser otras personas.
            if(j!=null&&signature.equals(saved.optString("of"))){
                for(Iterator<String> it=j.keys();it.hasNext();){String k=it.next();Print p=Print.of(j.optJSONObject(k));if(p!=null)out.put(k,p);}
                return out;
            }
        }catch(Exception ignored){}
        out=prints(audio,t.segments(),cancel);
        try{
            JSONObject j=new JSONObject();for(Map.Entry<String,Print> e:out.entrySet())j.put(e.getKey(),e.getValue().json());
            JSONObject file=new JSONObject().put("of",signature).put("voices",new JSONArray(raw)).put("prints",j);
            synchronized(FilesStore.LOCK){if(FilesStore.file(c,id,".m4a").exists())FilesStore.write(store(c,id),file);}
        }catch(Exception ignored){}
        return out;
    }
    /**
     * Une las voces repetidas de esta transcripción con esas huellas (rápido; no la guarda: lo hace quien llama).
     * people: cuántas personas hablan (0 = no se sabe). Devuelve null si no había nada que unir.
     */
    static Result apply(Transcript t,Map<String,Print> prints,int people)throws Exception{
        long started=android.os.SystemClock.elapsedRealtime();
        LinkedHashSet<String> raw=t.rawVoices();if(raw.size()<2)return null;
        Set<String> known=new HashSet<>();for(String v:raw)if(v.startsWith(Voices.TARGET))known.add(v);
        Map<String,String> into=cluster(prints,people,known,new ArrayList<>(raw));
        // Una voz sin huella (ni un tramo de 0,3 s, p. ej. un «ajá»): con la cantidad dicha se suma a quien habla justo
        // antes (o justo después); sin cantidad, queda como está.
        if(people>0){
            JSONArray s=t.segments();String last=null;List<String> waiting=new ArrayList<>();
            for(int i=0;i<s.length();i++){
                String r=Transcript.raw(s.getJSONObject(i));
                if(prints.containsKey(r)){last=into.get(r);for(String w:waiting)into.put(w,last);waiting.clear();}
                else if(!into.containsKey(r)){if(last!=null)into.put(r,last);else if(!waiting.contains(r))waiting.add(r);}
            }
        }
        t.unify(into,people);
        return new Result(raw.size(),t.speakers().size(),android.os.SystemClock.elapsedRealtime()-started);
    }
}
