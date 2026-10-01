package cl.vozlocal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pruebas de la parte «engine» de la 0.8.0 (ver docs/diseno/SPEC-0.8.md). Sin red ni claves reales: OpenRouter es un
 * HttpApi simulado (contesta lo que se le deja en una cola) y el audio lo «convierte» un OrAudio falso, que entrega un
 * archivo chico de bytes conocidos con las anclas en tiempos conocidos. Todo es determinista: no hay azar ni esperas.
 *
 * Las respuestas simuladas siguen lo que dice la documentación de OpenRouter; la forma exacta de cada proveedor no se
 * pudo confirmar sin una clave. Si al probar con la clave real difiere, estas pruebas se corrigen con una captura real.
 */
final class OpenRouterChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Mismo check con otro nombre: dentro de un HttpApi falso, «check» sería HttpApi.check(). */
    static void expect(boolean ok,String text){check(ok,text);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-6;}
    private static boolean has(String text,String part){return text!=null&&text.contains(part);}

    static void run(Context c,Recording r)throws Exception{
        base64(c.getCacheDir());
        bodies();
        normalize();
        anchors();
        usage();
        client(c);
        errors(c);
        fallbacks(c);
        preparing(c);
        gate(c);
        memory();
        rules();
        retries();
        device(c,r);
    }

    // ---------- Utilidades ----------
    /** Bytes conocidos (sin azar: la prueba da siempre lo mismo). */
    private static byte[] bytes(int n){byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)(i*31+7);return b;}
    private static File write(File f,byte[] data)throws IOException{try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}return f;}
    private static String url(byte[] clip){return "data:audio/mp4;base64,"+Base64.encodeToString(clip,Base64.NO_WRAP);}
    private static void deleteTree(File f){if(f==null||!f.exists())return;File[] kids=f.listFiles();if(kids!=null)for(File k:kids)deleteTree(k);f.delete();}
    /** Tramo de una respuesta; who: índice entero o texto (null = sin hablante). */
    private static JSONObject seg(Object who,double a,double b,String text)throws JSONException{JSONObject o=new JSONObject().put("start",a).put("end",b).put("text",text);if(who!=null)o.put("speaker",who);return o;}
    private static JSONObject word(Object who,double a,double b,String text)throws JSONException{JSONObject o=new JSONObject().put("word",text).put("start",a).put("end",b);if(who!=null)o.put("speaker",who);return o;}
    private static JSONArray list(JSONObject... items){JSONArray a=new JSONArray();for(JSONObject o:items)a.put(o);return a;}
    private static String speaker(JSONArray s,int i)throws JSONException{return s.getJSONObject(i).getString("speaker");}
    private static String text(JSONArray s,int i)throws JSONException{return s.getJSONObject(i).getString("text");}
    private static double start(JSONArray s,int i)throws JSONException{return s.getJSONObject(i).getDouble("start");}
    private static double end(JSONArray s,int i)throws JSONException{return s.getJSONObject(i).getDouble("end");}
    private static Models.Recipe recipe(Models.Diarize how,boolean verbose,long maxMinutes){return new Models.Recipe("prueba/"+how.name().toLowerCase(Locale.ROOT),how,verbose,maxMinutes*60_000L,Models.Unit.SECOND,false);}
    private static ProviderConfig config(String model,boolean speakers)throws Exception{return new ProviderConfig("openrouter",Models.BASE,model,"sk-or-prueba",speakers);}
    /** Contrato de TranscribeClient: todo tramo trae las 4 claves, el hablante es texto y no hay tramos vacíos ni al revés. */
    private static JSONArray contract(JSONObject out)throws JSONException{
        JSONArray s=out.getJSONArray("segments");check(out.has("_diarized"),"Response without _diarized");
        for(int i=0;i<s.length();i++){JSONObject o=s.getJSONObject(i);
            check(o.has("speaker")&&o.has("start")&&o.has("end")&&o.has("text")&&o.get("speaker") instanceof String,"Segment breaks the TranscribeClient contract: "+o);
            check(!o.getString("text").trim().isEmpty()&&!o.getString("text").contains("<|")&&o.getDouble("start")>=0&&o.getDouble("end")>=o.getDouble("start"),"Empty, marked or backwards segment: "+o);}
        return s;
    }
    /** Respuesta → tramos, sin anclas. */
    private static JSONObject plain(JSONObject json,boolean voices,long durationMs)throws JSONException{return OpenRouterClient.finish(OpenRouterClient.read(json,voices,durationMs),0,null,null);}

    /** OpenRouter simulado: guarda lo que se le envía y contesta lo que se le dejó en la cola. Nunca usa la red. */
    private static final class Fake extends HttpApi{
        final ArrayDeque<Response> replies=new ArrayDeque<>();final List<JSONObject> sent=new ArrayList<>();
        int calls;long lastLength;String method,url,token,type;Map<String,String> extra;
        Fake reply(int code,String text){replies.add(new Response(code,text,null));return this;}
        /** El motor pide una conexión hija por bloque: aquí es la misma, para que tampoco salga a la red. */
        @Override HttpApi child(){return this;}
        @Override Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra)throws Exception{
            calls++;this.method=method;this.url=url;this.token=token;this.type=contentType;this.extra=extra;
            ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);lastLength=body.length();
            expect(out.size()==body.length(),"Declared body length differs from what was written: "+out.size()+" vs "+body.length());
            sent.add(new JSONObject(out.toString("UTF-8")));
            Runnable uploaded=onUploaded;if(uploaded!=null)uploaded.run();
            expect(!replies.isEmpty(),"Unexpected extra request to OpenRouter");
            return replies.poll();
        }
    }
    /**
     * OrAudio simulado: no convierte nada. Entrega un archivo con bytes conocidos; con n anclas, las pone en las primeras
     * n ventanas dadas y deja 1 s de silencio antes del audio, como el de verdad.
     */
    private static class FakeAudio implements OpenRouterClient.Builder{
        final byte[] data=bytes(70_001);final long blockMs;final long[][] windows;
        /** Cuántas anclas se pidieron en cada llamada, los clips recibidos y los archivos creados. */
        final List<Integer> asked=new ArrayList<>();final List<byte[]> clips=new ArrayList<>();final List<File> made=new ArrayList<>(),given=new ArrayList<>();
        /** En qué paso decía estar el envío mientras se armaba el audio (debe ser PREPARE), y cuántas veces se pidió WAV. */
        final List<Integer> phases=new ArrayList<>();int wavs;
        FakeAudio(long blockMs,long[]... windows){this.blockMs=blockMs;this.windows=windows;}
        @Override public OrAudio.Built build(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception{return make(audio,anchors,outBase,cancel,"flac");}
        @Override public OrAudio.Built wav(File audio,List<File> anchors,File outBase,HttpApi cancel)throws Exception{wavs++;return make(audio,anchors,outBase,cancel,"wav");}
        OrAudio.Built make(File audio,List<File> anchors,File outBase,HttpApi cancel,String format)throws Exception{
            int n=anchors==null?0:anchors.size();asked.add(n);phases.add(cancel.phase);
            if(anchors!=null)for(File f:anchors){clips.add(java.nio.file.Files.readAllBytes(f.toPath()));given.add(f);}
            File out=write(new File(outBase.getPath()+"."+format),data);made.add(out);
            if(n==0)return new OrAudio.Built(out,format,0,new long[0][],blockMs);
            long lead=windows[n-1][1]+OrAudio.GAP_MS;
            return new OrAudio.Built(out,format,lead,Arrays.copyOf(windows,n),lead+blockMs);
        }
    }
    /** OrAudio simulado que falla siempre con el error dado (para la etapa «Preparando audio»). */
    private static final class FailingAudio extends FakeAudio{
        final Exception error;FailingAudio(Exception error){super(5000);this.error=error;}
        @Override OrAudio.Built make(File audio,List<File> anchors,File outBase,HttpApi cancel,String format)throws Exception{asked.add(anchors==null?0:anchors.size());phases.add(cancel.phase);throw error;}
    }
    private static OpenRouterClient client(Context c,HttpApi http,OpenRouterClient.Builder audio,List<String> lines){
        OpenRouterClient client=new OpenRouterClient(c,http);client.builder=audio;client.log=lines::add;return client;
    }

    // ---------- Cuerpo en base64, en streaming ----------
    static void base64(File dir)throws Exception{
        byte[] prefix="{\"a\":\"".getBytes(StandardCharsets.UTF_8),suffix="\"}".getBytes(StandardCharsets.UTF_8);
        File f=new File(dir,"or-check-b64.bin");
        try{
            // Tamaños alrededor de los bordes: relleno de 0, 1 y 2 bytes, y justo antes, en y después de una vuelta de 48 KB.
            for(int n:new int[]{0,1,2,3,4,49151,49152,49153,98304,100003}){
                byte[] data=bytes(n);write(f,data);String whole=Base64.encodeToString(data,Base64.NO_WRAP);
                HttpApi http=new HttpApi();long[] seen={-1,-1};http.onProgress=(sent,total)->{seen[0]=sent;seen[1]=total;};
                HttpApi.Body body=http.base64(prefix,f,suffix);
                ByteArrayOutputStream out=new ByteArrayOutputStream();body.write(out);
                check(out.size()==body.length(),"Base64 body length wrong for "+n+" bytes: wrote "+out.size()+", declared "+body.length());
                check(HttpApi.base64Length(n)==whole.length()&&body.length()==prefix.length+whole.length()+suffix.length,"Base64 length formula wrong for "+n+" bytes");
                String sent=out.toString("UTF-8");
                check(sent.equals("{\"a\":\""+whole+"\"}"),"Streaming base64 differs from encoding the whole file ("+n+" bytes)");
                check(Arrays.equals(Base64.decode(sent.substring(prefix.length,sent.length()-suffix.length),Base64.DEFAULT),data),"Base64 does not decode back to the file ("+n+" bytes)");
                // El envío avanza el progreso y el vigilante, igual que copy(): sin eso el motor lo cortaría a los 90 s.
                check(n==0?seen[0]==-1:(seen[0]==n&&seen[1]==n&&http.lastActivity>0),"Upload progress or watchdog not advanced ("+n+" bytes)");
            }
            // El largo se declara antes de escribir: si el archivo cambió, se falla en vez de mandar un JSON cortado.
            write(f,bytes(10));HttpApi.Body stale=new HttpApi().base64(prefix,f,suffix);write(f,bytes(11));
            boolean refused=false;try{stale.write(new ByteArrayOutputStream());}catch(IOException e){refused=true;}
            check(refused,"A file that changed after sizing the body was sent anyway");
            write(f,bytes(100));HttpApi stopped=new HttpApi();HttpApi.Body late=stopped.base64(prefix,f,suffix);stopped.cancel();
            boolean cancelled=false;try{late.write(new ByteArrayOutputStream());}catch(InterruptedIOException e){cancelled=true;}
            check(cancelled,"A cancelled upload kept going");
        }finally{f.delete();}
    }

    // ---------- Cuerpo JSON según la receta ----------
    static void bodies()throws Exception{
        JSONObject azure=OpenRouterClient.body("microsoft/mai-transcribe-2","flac","es",recipe(Models.Diarize.AZURE,true,110),true,true);
        check(azure.getString("model").equals("microsoft/mai-transcribe-2")&&azure.getJSONObject("input_audio").getString("format").equals("flac")&&azure.getJSONObject("input_audio").has("data"),"Model or input_audio missing: "+azure);
        check(azure.getString("language").equals("es")&&azure.getString("response_format").equals("verbose_json"),"Language or response format wrong: "+azure);
        JSONArray g=azure.getJSONArray("timestamp_granularities");check(g.length()==2&&g.getString(0).equals("segment")&&g.getString(1).equals("word"),"Timestamp granularities wrong: "+g);
        check(azure.getJSONObject("provider").getJSONObject("options").getJSONObject("azure").getJSONObject("diarization").getBoolean("enabled")&&!azure.has("diarize"),"Azure diarization recipe wrong: "+azure);
        JSONObject deepgram=OpenRouterClient.body("deepgram/nova-3","wav","es",recipe(Models.Diarize.DEEPGRAM,true,110),true,true);
        check(deepgram.getJSONObject("provider").getJSONObject("options").getJSONObject("deepgram").getBoolean("diarize")&&!deepgram.has("diarize")&&deepgram.getJSONObject("input_audio").getString("format").equals("wav"),"Deepgram diarization recipe wrong: "+deepgram);
        JSONObject generic=OpenRouterClient.body("prueba/generico","flac","es",recipe(Models.Diarize.GENERIC,true,30),true,true);
        check(generic.getBoolean("diarize")&&!generic.has("provider")&&generic.getString("response_format").equals("verbose_json"),"Generic diarization recipe wrong: "+generic);
        JSONObject inline=OpenRouterClient.body("prueba/marcas","flac","es",recipe(Models.Diarize.INLINE,true,60),true,true);
        check(!inline.has("diarize")&&!inline.has("provider")&&inline.getString("response_format").equals("verbose_json"),"Inline-marks recipe should not ask for anything: "+inline);
        // Sin voces no se pide nada de voces; idioma vacío (detección automática) no se envía.
        JSONObject textOnly=OpenRouterClient.body("prueba/texto","flac","",recipe(Models.Diarize.AZURE,true,110),false,true);
        check(!textOnly.has("provider")&&!textOnly.has("diarize")&&!textOnly.has("language")&&textOnly.has("timestamp_granularities"),"Text-only body wrong: "+textOnly);
        // Modo simple (tras un 400 por formato): json, sin tiempos ni voces.
        JSONObject simple=OpenRouterClient.body("prueba/texto","flac","es",recipe(Models.Diarize.AZURE,true,110),false,false);
        check(simple.getString("response_format").equals("json")&&!simple.has("timestamp_granularities")&&!simple.has("provider")&&!simple.has("diarize"),"Simple body wrong: "+simple);
        Map<String,String> headers=OpenRouterClient.headers();
        check("Verbapp".equals(headers.get("X-OpenRouter-Title"))&&headers.get("HTTP-Referer")!=null&&headers.get("HTTP-Referer").startsWith("https://"),"OpenRouter headers wrong: "+headers);
        check(OpenRouterClient.formatRejected("{\"error\":{\"message\":\"Unsupported response_format\"}}")&&OpenRouterClient.formatRejected("timestamp_granularities is not allowed")&&OpenRouterClient.formatRejected("Diarization is not available")
            &&OpenRouterClient.formatRejected("Unrecognized key(s) in object: 'provider'")&&!OpenRouterClient.formatRejected("Audio file is corrupted")&&!OpenRouterClient.formatRejected("Audio duration too long")&&!OpenRouterClient.formatRejected(null),"Format rejection detection wrong");
        // Las pruebas del cliente dependen de estas dos recetas de la tabla y de que un modelo desconocido no separe voces.
        check(Models.recipe("microsoft/mai-transcribe-2").diarize==Models.Diarize.AZURE&&Models.recipe("deepgram/nova-3").diarize==Models.Diarize.DEEPGRAM&&!Models.recipe("prueba/modelo-nuevo").diarizes&&Models.recipe("prueba/modelo-nuevo").verbose,"Recipes the client checks rely on changed");
    }

    // ---------- Los 4 formatos de respuesta → tramos ----------
    static void normalize()throws Exception{
        // 1. Tramos con hablante (índices enteros → texto); un tramo sin texto se descarta y uno sin hablante es "unknown".
        JSONObject withSpeakers=new JSONObject().put("text","Hola a todos. Buenas.").put("segments",list(seg(0,0.0,2.5," Hola a todos."),seg(1,2.9,4.0," Buenas."),seg(1,4.0,4.5,"  "),seg(null,4.6,5.0,"Ya")));
        JSONObject out=plain(withSpeakers,true,5000);JSONArray s=contract(out);
        check(s.length()==3&&out.getBoolean("_diarized")&&!out.has("_anchors"),"Speaker segments not kept as they came: "+out);
        check(speaker(s,0).equals("0")&&speaker(s,1).equals("1")&&speaker(s,2).equals("unknown")&&text(s,0).equals("Hola a todos.")&&near(start(s,1),2.9)&&near(end(s,1),4.0),"Speaker segments wrong: "+out);
        // Etiquetas de texto y otros nombres de campo.
        JSONObject labels=new JSONObject().put("segments",list(new JSONObject().put("start",0).put("end",1).put("text","Uno").put("speaker_label","Guest-1"),new JSONObject().put("start",1).put("end",2).put("text","Dos").put("speaker_id",3)));
        s=contract(plain(labels,true,2000));check(speaker(s,0).equals("Guest-1")&&speaker(s,1).equals("3"),"Speaker label fields not read");
        // Tiempos en milisegundos (muy por encima de la duración del archivo): pasan a segundos.
        s=contract(plain(new JSONObject().put("segments",list(seg(0,0,2500,"Hola"),seg(1,2600,4800,"Chao"))),true,5000));
        check(near(end(s,0),2.5)&&near(start(s,1),2.6),"Millisecond timestamps not converted");

        // 2. Hablantes solo en las palabras: se agrupan; corta al cambiar de voz y en pausas de más de 1,2 s.
        JSONObject words=new JSONObject().put("text","Hola, qué tal. Bien. Sigo yo").put("segments",list(seg(null,0,4,"Hola, qué tal. Bien. Sigo yo")))
            .put("words",list(word(0,0.1,0.4,"hola").put("punctuated_word","Hola,"),word(0,0.5,0.7,"qué"),word(0,0.7,1.0,"tal").put("punctuated_word","tal."),
                word(1,1.3,1.6,"bien").put("punctuated_word","Bien."),word(1,3.5,3.8,"Sigo"),word(1,3.9,4.0,"yo")));
        out=plain(words,true,5000);s=contract(out);
        check(s.length()==3&&out.getBoolean("_diarized"),"Words not grouped into turns: "+out);
        check(speaker(s,0).equals("0")&&text(s,0).equals("Hola, qué tal.")&&near(start(s,0),0.1)&&near(end(s,0),1.0)&&speaker(s,1).equals("1")&&text(s,1).equals("Bien.")
            &&speaker(s,2).equals("1")&&text(s,2).equals("Sigo yo")&&near(start(s,2),3.5)&&near(end(s,2),4.0),"Word grouping wrong: "+out);
        // Un turno largo sin pausas se corta a los ~45 s: 100 palabras seguidas de medio segundo son 2 tramos.
        JSONArray many=new JSONArray();for(int i=0;i<100;i++)many.put(word(0,i*0.5,i*0.5+0.5,"p"+i));
        s=contract(plain(new JSONObject().put("words",many),true,50_000));
        check(s.length()==2&&near(end(s,0),45.0)&&near(start(s,1),45.0)&&near(end(s,1),50.0)&&text(s,1).startsWith("p90 "),"Long turn not split near 45 s: "+s.length());

        // 3. Marcas <|speaker:N|> dentro del texto: con tiempos por tramo, cada tramo se reparte entre sus marcas.
        JSONObject marked=new JSONObject().put("segments",list(seg(null,0,10,"<|speaker:1|>Hola a todos<|speaker:2|>Buenas tardes"),seg(null,10,14,"sigo yo<|speaker:1|> Vale")));
        OpenRouterClient.Parsed parsed=OpenRouterClient.read(marked,true,14_000);out=OpenRouterClient.finish(parsed,0,null,null);s=contract(out);
        check(parsed.timed&&parsed.diarized&&s.length()==4,"Inline marks with segment times wrong: "+out);
        check(speaker(s,0).equals("1")&&text(s,0).equals("Hola a todos")&&near(end(s,0),4.8)&&speaker(s,1).equals("2")&&near(start(s,1),4.8)&&near(end(s,1),10)
            &&speaker(s,2).equals("2")&&text(s,2).equals("sigo yo")&&speaker(s,3).equals("1")&&text(s,3).equals("Vale")&&near(end(s,3),14),"Inline marks not split by voice: "+out);
        // Solo texto con marcas: los tiempos se reparten en la duración (y quedan marcados como no reales).
        JSONObject loose=new JSONObject().put("text","<|speaker:1|>Uno dos<|speaker:2|>tres");
        parsed=OpenRouterClient.read(loose,true,11_000);out=OpenRouterClient.finish(parsed,0,null,null);s=contract(out);
        check(!parsed.timed&&s.length()==2&&speaker(s,0).equals("1")&&speaker(s,1).equals("2")&&near(start(s,0),0)&&near(end(s,0),7)&&near(end(s,1),11),"Inline marks without times wrong: "+out);
        // Sin pedir voces, las marcas se quitan del texto.
        s=contract(plain(loose,false,11_000));check(s.length()==1&&speaker(s,0).equals("text")&&text(s,0).equals("Uno dos tres"),"Marks left in a text-only transcript");

        // 4. Sin voces: tramos "text" con sus tiempos reales si vienen; si no, un solo tramo.
        out=plain(new JSONObject().put("text","Hola mundo. Chao.").put("segments",list(seg(null,0.5,1.8,"Hola mundo."),seg(null,2.0,2.6,"Chao."))),false,3000);s=contract(out);
        check(s.length()==2&&!out.getBoolean("_diarized")&&speaker(s,0).equals("text")&&near(start(s,1),2.0)&&near(end(s,1),2.6),"Text segments lost their real times: "+out);
        out=plain(new JSONObject().put("text","Hola mundo"),false,3000);s=contract(out);
        check(s.length()==1&&speaker(s,0).equals("text")&&text(s,0).equals("Hola mundo")&&near(start(s,0),0)&&near(end(s,0),0)&&!out.getBoolean("_diarized"),"Plain text response wrong: "+out);
        // Datos de hablante que llegan sin haberlos pedido se ignoran.
        out=plain(withSpeakers,false,5000);s=contract(out);check(!out.getBoolean("_diarized")&&speaker(s,0).equals("text"),"Speakers used although voices were not requested");
        // Se pidieron voces y no vinieron: queda como texto (y no como una voz inventada).
        parsed=OpenRouterClient.read(new JSONObject().put("segments",list(seg(null,0,2,"Hola"))),true,2000);
        check(!parsed.diarized&&parsed.timed&&parsed.pieces.get(0).who.equals("text"),"Missing speakers not reported as a text-only answer");
        // Respuesta vacía o con null: ningún tramo, ningún texto "null".
        check(contract(plain(new JSONObject().put("text",""),true,1000)).length()==0,"Empty text produced a segment");
        JSONObject nulls=new JSONObject("{\"text\":null,\"segments\":[{\"start\":0,\"end\":1,\"text\":null,\"speaker\":null}]}");
        check(contract(plain(nulls,true,1000)).length()==0,"JSON null became text");
        // Palabras dentro de los tramos (no arriba) también sirven.
        JSONObject nested=new JSONObject().put("segments",list(seg(null,0,2,"hola chao").put("words",list(word(5,0.1,0.5,"hola"),word(6,1.0,1.5,"chao")))));
        s=contract(plain(nested,true,2000));check(s.length()==2&&speaker(s,0).equals("5")&&speaker(s,1).equals("6"),"Words nested in segments not read");
    }

    // ---------- Anclas: emparejar, borrar la zona y restar tiempos ----------
    /** Dos muestras (8 s cada una, 1 s de silencio) y después el audio, que empieza a los 18 s. */
    private static JSONObject anchored()throws JSONException{
        return new JSONObject().put("text","(no se usa)").put("segments",list(
            seg(1,0.2,7.6,"Esta es mi voz de muestra para la prueba"),seg(0,9.3,16.5,"Y esta es la otra muestra de voz"),
            seg(0,18.4,22.0,"Hola, partamos la reunión"),seg(1,22.5,25.0,"Dale, yo anoto"),seg(2,25.5,27.0,"Yo soy nueva")))
            .put("usage",new JSONObject().put("seconds",48).put("cost",0.0013));
    }
    static void anchors()throws Exception{
        long[][] two={{0,8000},{9000,17000}};long lead=18_000;
        List<String[]> refs=Arrays.asList(new String[]{Voices.MINE,"",Voices.ME,"Tú"},new String[]{"voz_1","","block0:0","Persona 1"});
        // 1. Cada ancla queda con el hablante de su ventana, que vuelve con el nombre enviado; lo demás, con su índice.
        JSONObject out=OpenRouterClient.finish(OpenRouterClient.read(anchored(),true,48_000),lead,two,refs);JSONArray s=contract(out);
        check(s.length()==3&&speaker(s,0).equals("voz_1")&&speaker(s,1).equals(Voices.MINE)&&speaker(s,2).equals("2"),"Anchors not mapped to the sent names: "+out);
        check(near(start(s,0),0.4)&&near(end(s,0),4.0)&&near(start(s,1),4.5)&&near(end(s,1),7.0)&&near(start(s,2),7.5)&&near(end(s,2),9.0),"Lead time not subtracted: "+out);
        check(out.getInt("_anchors")==2&&out.getInt("_matched")==2&&out.getBoolean("_diarized"),"Anchor counts wrong: "+out);
        // 2. Dos anclas en el mismo hablante: gana la de mayor coincidencia y la otra queda sin emparejar.
        JSONObject same=new JSONObject().put("segments",list(seg(0,0.2,7.6,"Primera muestra de voz"),seg(0,9.3,14.0,"Segunda muestra de voz"),seg(0,18.5,21.0,"Hola a todos"),seg(1,21.5,23.0,"Buenas")));
        out=OpenRouterClient.finish(OpenRouterClient.read(same,true,48_000),lead,two,refs);s=contract(out);
        check(s.length()==2&&speaker(s,0).equals(Voices.MINE)&&speaker(s,1).equals("1")&&out.getInt("_anchors")==2&&out.getInt("_matched")==1,"Two anchors on one speaker not resolved: "+out);
        // … y si las dos muestras son de la misma persona, es una persona reconocida.
        List<String[]> twice=Arrays.asList(new String[]{"voz_1","","block0:0","Persona 1"},new String[]{"voz_1b","","block0:0","Persona 1"});
        out=OpenRouterClient.finish(OpenRouterClient.read(same,true,48_000),lead,two,twice);s=contract(out);
        check(speaker(s,0).equals("voz_1")&&out.getInt("_anchors")==1&&out.getInt("_matched")==1,"Two samples of one person miscounted: "+out);
        // 3. Ancla sin emparejar (casi nadie habla en su ventana): no es un error.
        JSONObject quiet=new JSONObject().put("segments",list(seg(1,0.2,7.6,"Primera muestra de voz"),seg(3,9.3,9.8,"eh"),seg(1,18.5,21.0,"Hola a todos"),seg(0,21.5,23.0,"Buenas")));
        out=OpenRouterClient.finish(OpenRouterClient.read(quiet,true,48_000),lead,two,refs);s=contract(out);
        check(s.length()==2&&speaker(s,0).equals(Voices.MINE)&&speaker(s,1).equals("0")&&out.getInt("_anchors")==2&&out.getInt("_matched")==1,"Unmatched anchor mishandled: "+out);
        // 4. Regla «1 s o el 40 % del ancla»: en un ancla de 2 s bastan 0,8 s; con 0,7 s no se empareja.
        long[][] brief={{0,2000}};List<String[]> fran=Collections.singletonList(new String[]{"voz_abc12345","","voice:abc12345","Fran"});
        out=OpenRouterClient.finish(OpenRouterClient.read(new JSONObject().put("segments",list(seg(0,0.5,1.4,"Hola hola"),seg(0,3.2,5.0,"Sigo yo"))),true,10_000),3000,brief,fran);s=contract(out);
        check(s.length()==1&&speaker(s,0).equals("voz_abc12345")&&near(start(s,0),0.2)&&near(end(s,0),2.0)&&out.getInt("_matched")==1,"40 % rule not applied: "+out);
        out=OpenRouterClient.finish(OpenRouterClient.read(new JSONObject().put("segments",list(seg(0,0.5,1.2,"Hola hola"),seg(0,3.2,5.0,"Sigo yo"))),true,10_000),3000,brief,fran);s=contract(out);
        check(s.length()==1&&speaker(s,0).equals("0")&&out.getInt("_matched")==0,"Anchor matched with too little speech: "+out);
        // 5. Un ancla que OrAudio omitió ({-1,-1}) no cuenta ni desordena las demás.
        long[][] skipped={{-1,-1},{0,8000}};
        out=OpenRouterClient.finish(OpenRouterClient.read(new JSONObject().put("segments",list(seg(4,0.3,7.5,"Muestra de voz"),seg(4,9.4,12.0,"Hola"))),true,40_000),9000,skipped,refs);s=contract(out);
        check(s.length()==1&&speaker(s,0).equals("voz_1")&&near(start(s,0),0.4)&&out.getInt("_anchors")==1&&out.getInt("_matched")==1,"Skipped anchor shifted the others: "+out);
        // 6. El modelo unió el final del ancla con el comienzo del audio en un tramo: con las palabras queda solo lo de después.
        JSONObject straddle=new JSONObject().put("segments",list(seg(1,0.5,2.6,"Esta es mi voz"),seg(0,9.3,19.5,"Y esta es otra muestra Hola partamos"),seg(1,20.0,21.0,"Dale")))
            .put("words",list(word(1,0.5,1.0,"Esta"),word(1,1.1,1.4,"es"),word(1,1.5,1.8,"mi"),word(1,1.9,2.6,"voz"),
                word(0,9.3,9.6,"Y"),word(0,9.7,10.2,"esta"),word(0,10.3,10.6,"es"),word(0,10.7,11.2,"otra"),word(0,11.3,12.0,"muestra"),
                word(0,18.3,18.7,"Hola"),word(0,18.8,19.5,"partamos"),word(1,20.0,21.0,"Dale")));
        out=OpenRouterClient.finish(OpenRouterClient.read(straddle,true,48_000),lead,two,refs);s=contract(out);
        check(s.length()==2&&text(s,0).equals("Hola partamos")&&speaker(s,0).equals("voz_1")&&near(start(s,0),0)&&near(end(s,0),1.5)&&speaker(s,1).equals(Voices.MINE)&&near(start(s,1),2.0),"Straddling segment not cut with its words: "+out);
        // … y sin palabras, en proporción al tiempo.
        JSONObject blind=new JSONObject().put("segments",list(seg(1,0.5,7.5,"Esta es mi voz"),seg(0,16.0,20.0,"uno dos tres cuatro cinco seis siete ocho")));
        out=OpenRouterClient.finish(OpenRouterClient.read(blind,true,48_000),lead,two,refs);s=contract(out);
        check(s.length()==1&&text(s,0).equals("cuatro cinco seis siete ocho")&&near(start(s,0),0)&&near(end(s,0),2.0)&&speaker(s,0).equals("voz_1"),"Straddling segment not cut in proportion: "+out);
        // … pero un tramo que empieza en el silencio entre la última ancla (fin 17 s) y el audio (18 s) no trae nada del
        // ancla: no pierde palabras (antes se iba «Hola»).
        JSONObject quietStart=new JSONObject().put("segments",list(seg(1,0.2,7.6,"Esta es mi voz"),seg(0,9.3,16.5,"Y esta es la otra"),seg(0,17.2,19.0,"Hola partamos ya mismo")));
        out=OpenRouterClient.finish(OpenRouterClient.read(quietStart,true,48_000),lead,two,refs);s=contract(out);
        check(s.length()==1&&text(s,0).equals("Hola partamos ya mismo")&&near(start(s,0),0)&&near(end(s,0),1.0)&&speaker(s,0).equals("voz_1"),"Segment starting in the silence after the anchors lost real words: "+out);
        // 7. La respuesta no trae voces: igual se borra la zona de anclas y se restan los tiempos.
        JSONObject mute=new JSONObject().put("segments",list(seg(null,0.5,7.5,"Esta es mi voz"),seg(null,9.2,16.0,"Otra muestra"),seg(null,18.2,20.0,"Hola a todos")));
        OpenRouterClient.Parsed parsed=OpenRouterClient.read(mute,true,48_000);out=OpenRouterClient.finish(parsed,lead,two,refs);s=contract(out);
        check(!parsed.diarized&&parsed.timed&&s.length()==1&&speaker(s,0).equals("text")&&near(start(s,0),0.2)&&!out.getBoolean("_diarized")&&out.getInt("_anchors")==2&&out.getInt("_matched")==0,"Anchor zone not removed from a text-only answer: "+out);
        // 8. Hablantes solo en las palabras (Deepgram): la ventana se mide con las palabras.
        JSONObject byWord=new JSONObject().put("words",list(word(0,0.5,1.5,"Muestra"),word(0,1.6,2.8,"larga"),word(0,9.5,10.0,"Hola"),word(0,10.1,10.6,"equipo"),word(1,10.9,11.4,"Buenas")));
        out=OpenRouterClient.finish(OpenRouterClient.read(byWord,true,40_000),9000,new long[][]{{0,8000}},Collections.singletonList(refs.get(0)));s=contract(out);
        check(s.length()==2&&speaker(s,0).equals(Voices.MINE)&&text(s,0).equals("Hola equipo")&&near(start(s,0),0.5)&&near(end(s,0),1.6)&&speaker(s,1).equals("1")&&text(s,1).equals("Buenas"),"Word-level anchors wrong: "+out);
        // 9. El corte se mide con el silencio real entre la última ancla y el audio, aunque no sea de 1 s.
        out=OpenRouterClient.finish(OpenRouterClient.read(new JSONObject().put("segments",list(seg(0,0.5,7.9,"Muestra de voz"),seg(0,8.5,10.0,"Hola"))),true,20_000),8400,new long[][]{{0,8000}},fran);s=contract(out);
        check(s.length()==1&&speaker(s,0).equals("voz_abc12345")&&text(s,0).equals("Hola")&&near(start(s,0),0.1)&&near(end(s,0),1.6),"Cut not placed in the real gap after the last anchor: "+out);
    }

    // ---------- Uso y costo real ----------
    static void usage()throws Exception{
        JSONObject u=OpenRouterClient.usage(new JSONObject().put("usage",new JSONObject().put("seconds",48).put("cost",0.0013)),60_000,0);
        check(u.getString("type").equals("duration")&&near(u.getDouble("seconds"),48)&&near(u.getDouble("cost"),0.0013),"usage.seconds/cost not read: "+u);
        u=OpenRouterClient.usage(new JSONObject().put("usage",new JSONObject().put("duration",30).put("cost","0.5")),60_000,0);
        check(near(u.getDouble("seconds"),30)&&near(u.getDouble("cost"),0.5),"usage.duration or a cost sent as text not read: "+u);
        u=OpenRouterClient.usage(new JSONObject().put("duration",12.5),60_000,0);
        check(near(u.getDouble("seconds"),12.5)&&!u.has("cost"),"Top-level duration not used: "+u);
        // Sin datos de uso: lo que dura el archivo enviado, y sin costo (la pantalla mostrará el estimado).
        u=OpenRouterClient.usage(new JSONObject(),42_000,0);
        check(u.getString("type").equals("duration")&&near(u.getDouble("seconds"),42)&&!u.has("cost"),"Usage fallback wrong: "+u);
        // El costo de un envío descartado del mismo bloque se suma.
        u=OpenRouterClient.usage(new JSONObject().put("usage",new JSONObject().put("seconds",10).put("cost",0.001)),10_000,0.002);
        check(near(u.getDouble("cost"),0.003),"Discarded request cost not added: "+u);
        u=OpenRouterClient.usage(new JSONObject().put("usage",new JSONObject().put("input_tokens",120).put("output_tokens",8).put("cost",JSONObject.NULL)),5_000,0);
        check(u.getLong("input_tokens")==120&&u.getLong("output_tokens")==8&&!u.has("cost")&&near(u.getDouble("seconds"),5),"Token usage not passed through: "+u);
    }

    // ---------- El cliente de punta a punta (OpenRouter y audio simulados) ----------
    static void client(Context c)throws Exception{
        File audio=write(new File(c.getCacheDir(),"or-check-audio.m4a"),bytes(2000));
        try{
            // 1. Con voces y dos muestras: MAI-Transcribe 2 (receta Azure).
            Fake http=new Fake().reply(200,anchored().toString());FakeAudio fake=new FakeAudio(30_000,new long[]{0,8000},new long[]{9000,17000});
            List<String> lines=new ArrayList<>();byte[] one=bytes(1500),two=bytes(2100);
            List<String[]> refs=Arrays.asList(new String[]{Voices.MINE,url(one),Voices.ME,"Tú"},new String[]{"voz_1",url(two),"block0:0","Persona 1"});
            JSONObject out=client(c,http,fake,lines).transcribe(audio,config("microsoft/mai-transcribe-2",true),"es",refs,null);
            check(http.calls==1&&"POST".equals(http.method)&&"https://openrouter.ai/api/v1/audio/transcriptions".equals(http.url)&&"sk-or-prueba".equals(http.token)&&"application/json".equals(http.type),"OpenRouter request line wrong: "+http.method+" "+http.url+" "+http.type);
            check(http.extra!=null&&"Verbapp".equals(http.extra.get("X-OpenRouter-Title"))&&http.extra.containsKey("HTTP-Referer"),"OpenRouter headers missing");
            JSONObject sent=http.sent.get(0),input=sent.getJSONObject("input_audio");
            check(sent.getString("model").equals("microsoft/mai-transcribe-2")&&sent.getString("language").equals("es")&&sent.getString("response_format").equals("verbose_json")&&sent.getJSONArray("timestamp_granularities").length()==2,"OpenRouter body fields wrong");
            check(sent.getJSONObject("provider").getJSONObject("options").getJSONObject("azure").getJSONObject("diarization").getBoolean("enabled"),"Azure diarization not requested");
            check(input.getString("format").equals("flac")&&Arrays.equals(Base64.decode(input.getString("data"),Base64.DEFAULT),fake.data),"Audio not sent as the base64 of the converted file");
            check(fake.asked.equals(Collections.singletonList(2))&&Arrays.equals(fake.clips.get(0),one)&&Arrays.equals(fake.clips.get(1),two),"Voice samples not decoded to files, in order");
            JSONArray s=contract(out);
            check(s.length()==3&&speaker(s,0).equals("voz_1")&&speaker(s,1).equals(Voices.MINE)&&speaker(s,2).equals("2")&&near(start(s,0),0.4)&&near(end(s,0),4.0)&&near(start(s,2),7.5),"Client did not map anchors or shift times");
            check(out.getBoolean("_diarized")&&out.getInt("_anchors")==2&&out.getInt("_matched")==2,"Anchor counts missing from the response");
            JSONObject usage=out.getJSONObject("usage");
            check(usage.getString("type").equals("duration")&&near(usage.getDouble("seconds"),48)&&near(usage.getDouble("cost"),0.0013),"Usage with the real cost not returned: "+usage);
            check(out.getLong("_bytes")==http.lastLength&&out.getString("_format").equals("flac")&&lines.isEmpty(),"Sent size or format missing, or an unexpected log line: "+lines);
            // Los temporales (audio convertido y muestras) no quedan en el teléfono.
            for(File f:fake.made)check(!f.exists(),"Converted audio left behind");
            for(File f:fake.given)check(!f.exists(),"Voice sample file left behind");
            check(audio.exists(),"The recording itself was deleted");

            // 2. Lo que hace el motor después: _known une cada nombre enviado con su voz, igual que con OpenAI.
            JSONObject known=new JSONObject();for(String[] ref:refs)known.put(ref[0],ref[2]);out.put("_known",known);
            Transcript alone=Transcript.fromParts(Collections.singletonList(out),Collections.singletonList(0d));JSONArray j=alone.segments();
            check(j.length()==3&&speaker(j,0).equals("block0:0")&&speaker(j,1).equals(Voices.ME)&&speaker(j,2).equals("2")&&alone.diarized(),"_known not applied to the OpenRouter response");
            JSONObject first=new JSONObject().put("_diarized",true).put("segments",list(seg("0",0,5,"Parto yo"),seg("1",5,9,"Y sigo yo")));
            Transcript joined=Transcript.fromParts(Arrays.asList(first,new JSONObject(out.toString())),Arrays.asList(0d,600d));j=joined.segments();
            check(j.length()==5&&speaker(j,0).equals("block0:0")&&speaker(j,1).equals("block0:1")&&speaker(j,2).equals("block0:0")&&speaker(j,3).equals(Voices.ME)&&speaker(j,4).equals("block1:2"),"Blocks not joined through the anchors");
            check(near(start(j,2),600.4)&&joined.speakers().size()==4&&joined.data.getJSONArray("blocks").length()==2,"Block offset or speaker count wrong after joining");

            // 3. Deepgram: hablantes solo en las palabras, sin muestras y con idioma automático.
            JSONObject words=new JSONObject().put("words",list(word(0,0.1,0.4,"hola").put("punctuated_word","Hola,"),word(0,0.5,0.7,"qué"),word(0,0.7,1.0,"tal").put("punctuated_word","tal."),word(1,1.3,1.6,"bien").put("punctuated_word","Bien.")));
            http=new Fake().reply(200,words.toString());fake=new FakeAudio(5000);
            out=client(c,http,fake,lines).transcribe(audio,config("deepgram/nova-3",true),"",null,null);sent=http.sent.get(0);
            check(sent.getJSONObject("provider").getJSONObject("options").getJSONObject("deepgram").getBoolean("diarize")&&!sent.has("language")&&!sent.has("diarize"),"Deepgram body wrong");
            s=contract(out);
            check(fake.asked.equals(Collections.singletonList(0))&&s.length()==2&&text(s,0).equals("Hola, qué tal.")&&speaker(s,0).equals("0")&&speaker(s,1).equals("1")&&!out.has("_anchors"),"Deepgram words not grouped by the client");
            check(near(out.getJSONObject("usage").getDouble("seconds"),5)&&!out.getJSONObject("usage").has("cost"),"Usage without provider data should fall back to the audio length");

            // 4. Modelo que no está en la tabla («Nuevo · sin probar»): solo texto aunque se pidan voces; las muestras no viajan.
            http=new Fake().reply(200,"{\"text\":\"Hola mundo\",\"duration\":12.5}");fake=new FakeAudio(5000,new long[]{0,8000});
            out=client(c,http,fake,lines).transcribe(audio,config("prueba/modelo-nuevo",true),"es",refs,null);sent=http.sent.get(0);s=contract(out);
            check(!sent.has("provider")&&!sent.has("diarize")&&sent.getString("response_format").equals("verbose_json")&&fake.asked.equals(Collections.singletonList(0)),"Unknown model was asked for voices or got samples");
            check(s.length()==1&&speaker(s,0).equals("text")&&text(s,0).equals("Hola mundo")&&!out.getBoolean("_diarized")&&near(out.getJSONObject("usage").getDouble("seconds"),12.5),"Text-only answer wrong");
            Transcript textOnly=Transcript.fromParts(Collections.singletonList(out),Collections.singletonList(0d));
            check(!textOnly.diarized()&&textOnly.speakers().containsValue("Texto"),"Text-only transcript fabricated speakers");

            // 5. Una muestra ilegible se omite y la otra sigue emparejándose con su propio nombre.
            JSONObject single=new JSONObject().put("segments",list(seg(0,0.5,7.5,"Muestra de voz"),seg(0,9.4,12.0,"Hola")));
            http=new Fake().reply(200,single.toString());fake=new FakeAudio(30_000,new long[]{0,8000});
            List<String[]> mixed=Arrays.asList(new String[]{"voz_rota","esto no es una data URL","block0:9","Persona 9"},new String[]{"voz_1",url(two),"block0:0","Persona 1"});
            out=client(c,http,fake,lines).transcribe(audio,config("microsoft/mai-transcribe-2",true),"es",mixed,null);s=contract(out);
            check(fake.asked.equals(Collections.singletonList(1))&&Arrays.equals(fake.clips.get(0),two)&&s.length()==1&&speaker(s,0).equals("voz_1")&&near(start(s,0),0.4)&&out.getInt("_anchors")==1,"Unreadable sample broke the matching of the others");
            check(lines.isEmpty(),"Unexpected log lines: "+lines);
        }finally{audio.delete();}
    }

    // ---------- Errores de OpenRouter ----------
    /** Lo que lanza el cliente ante esa respuesta (null si no lanza nada). */
    private static Exception failure(Context c,File audio,Fake http,boolean speakers){
        try{client(c,http,new FakeAudio(5000),new ArrayList<>()).transcribe(audio,config("microsoft/mai-transcribe-2",speakers),"es",null,null);return null;}
        catch(Exception e){return e;}
    }
    private static boolean action(Exception e){return e instanceof HttpApi.UserAction&&!(e instanceof HttpApi.TooLarge);}
    static void errors(Context c)throws Exception{
        File audio=write(new File(c.getCacheDir(),"or-check-audio.m4a"),bytes(2000));
        try{
            Fake http=new Fake().reply(402,"{\"error\":{\"code\":402,\"message\":\"Insufficient credits. Add more using https://openrouter.ai/credits\"}}");
            Exception e=failure(c,audio,http,true);
            check(action(e)&&has(e.getMessage(),"saldo")&&has(e.getMessage(),"OpenRouter")&&http.calls==1,"402 should ask the user to add credits: "+e);
            e=failure(c,audio,new Fake().reply(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found for microsoft/mai-transcribe-2.\"}}"),true);
            check(action(e)&&has(e.getMessage(),"ya no está disponible")&&has(e.getMessage(),"Ajustes"),"404 should say the model is gone: "+e);
            e=failure(c,audio,new Fake().reply(404,"{\"error\":{\"code\":404,\"message\":\"No endpoints found matching your data policy\"}}"),true);
            check(action(e)&&has(e.getMessage(),"privacidad"),"404 by data policy not explained: "+e);
            e=failure(c,audio,new Fake().reply(401,"{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}"),true);
            check(action(e)&&has(e.getMessage(),"clave"),"401 should ask for the key: "+e);
            // 413: error con clase propia, para que el motor achique los bloques.
            e=failure(c,audio,new Fake().reply(413,""),true);check(e instanceof HttpApi.TooLarge,"413 not reported as TooLarge: "+e);
            // Se reintentan solos: límite de ritmo y caídas del proveedor.
            for(int code:new int[]{408,429,500,502,503,524,529}){
                e=failure(c,audio,new Fake().reply(code,"{\"error\":{\"code\":"+code+",\"message\":\"Provider returned error\"}}"),true);
                check(e instanceof IOException&&!(e instanceof InterruptedIOException),"HTTP "+code+" should be retried: "+e);
            }
            // OpenRouter a veces responde 200 con el error adentro.
            e=failure(c,audio,new Fake().reply(200,"{\"error\":{\"code\":402,\"message\":\"Insufficient credits\"}}"),true);
            check(action(e)&&has(e.getMessage(),"saldo"),"Error inside a 200 response ignored: "+e);
            e=failure(c,audio,new Fake().reply(200,"{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}"),true);
            check(e instanceof IOException,"Provider error inside a 200 response should be retried: "+e);
            // Una respuesta ilegible se reintenta, y su contenido no llega al mensaje (la bitácora viaja en el informe).
            e=failure(c,audio,new Fake().reply(200,"<html>texto privado</html>"),true);
            check(e instanceof IOException&&!has(e.getMessage(),"privado"),"Unreadable response mishandled: "+e);
            // Una respuesta con otra forma (sin texto, tramos ni palabras) no se guarda como transcripción vacía ni se reintenta.
            http=new Fake().reply(200,"{\"id\":\"gen-123\",\"status\":\"queued\"}");e=failure(c,audio,http,true);
            check(action(e)&&has(e.getMessage(),"formato")&&http.calls==1,"Unknown response shape accepted as an empty transcript: "+e);
            // Silencio sí es una respuesta válida: texto vacío, ningún tramo.
            JSONObject silent=client(c,new Fake().reply(200,"{\"text\":\"\"}"),new FakeAudio(5000),new ArrayList<>()).transcribe(audio,config("microsoft/mai-transcribe-2",false),"es",null,null);
            check(contract(silent).length()==0,"Silence should give an empty transcript, not an error");
            // Un 400 que no es por el formato (ni de la respuesta ni del audio) no se reintenta. (Hasta la primera ronda esta
            // prueba usaba «Audio file is corrupted»; desde la segunda, ese 400 apunta al FLAC y se repite una vez en WAV:
            // ver wav() en fallbacks.)
            http=new Fake().reply(400,"{\"error\":{\"code\":400,\"message\":\"Audio duration exceeds the maximum allowed\"}}");e=failure(c,audio,http,true);
            check(action(e)&&http.calls==1,"Unrelated 400 was retried: "+e);
            // 429 de OpenRouter: se reintenta, con un mensaje que dice qué pasó.
            e=failure(c,audio,new Fake().reply(429,"{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}"),true);
            check(e instanceof IOException&&has(e.getMessage(),"pidió esperar"),"OpenRouter 429 not explained: "+e);
            // OpenAI y el servidor propio siguen igual: los textos de OpenRouter solo salen con su nombre de servicio.
            String openai=null;try{HttpApi.require(new HttpApi.Response(402,"",null),"OpenAI");}catch(HttpApi.UserAction x){openai=x.getMessage();}
            check(has(openai,"HTTP 402")&&!has(openai,"OpenRouter"),"402 from another provider changed: "+openai);
            String custom=null;try{HttpApi.require(new HttpApi.Response(404,"",null),"Proveedor");}catch(HttpApi.UserAction x){custom=x.getMessage();}
            check(has(custom,"HTTP 404")&&!has(custom,"OpenRouter"),"404 from another provider changed: "+custom);
            boolean large=false;try{HttpApi.require(new HttpApi.Response(413,"",null),"Proveedor");}catch(HttpApi.UserAction x){large=x instanceof HttpApi.TooLarge&&has(x.getMessage(),"HTTP 413");}
            check(large,"413 should still be a user action for every provider");
            HttpApi.require(new HttpApi.Response(200,"{}",null),HttpApi.OPENROUTER);
        }finally{audio.delete();}
    }

    // ---------- Reintentos del cliente: modo simple y sin anclas ----------
    static void fallbacks(Context c)throws Exception{
        File audio=write(new File(c.getCacheDir(),"or-check-audio.m4a"),bytes(2000));
        String rejected="{\"error\":{\"code\":400,\"message\":\"Provider returned error\",\"metadata\":{\"raw\":\"response_format 'verbose_json' is not supported by this model\",\"provider_name\":\"Azure\"}}}";
        List<String[]> refs=Collections.singletonList(new String[]{"voz_1",url(bytes(900)),"block0:0","Persona 1"});
        String mai="microsoft/mai-transcribe-2";
        try{
            // 1. 400 por el formato: una vez más en modo simple (json, sin voces, sin tiempos y sin anclas).
            Fake http=new Fake().reply(400,rejected).reply(200,"{\"text\":\"Hola mundo\",\"usage\":{\"seconds\":30,\"cost\":0.001}}");
            FakeAudio fake=new FakeAudio(30_000,new long[]{0,8000});List<String> lines=new ArrayList<>();
            JSONObject out=client(c,http,fake,lines).transcribe(audio,config(mai,true),"es",refs,null);JSONArray s=contract(out);
            JSONObject again=http.sent.get(1);
            check(http.calls==2&&http.sent.get(0).has("provider")&&again.getString("response_format").equals("json")&&!again.has("timestamp_granularities")&&!again.has("provider")&&!again.has("diarize")&&again.getString("model").equals(mai),"Simple retry body wrong");
            // Las anclas no pueden ir en modo simple (sin tiempos no se podrían quitar): el audio se arma de nuevo sin ellas.
            check(fake.asked.equals(Arrays.asList(1,0))&&s.length()==1&&speaker(s,0).equals("text")&&text(s,0).equals("Hola mundo")&&!out.getBoolean("_diarized")&&!out.has("_anchors"),"Simple retry result wrong: "+out);
            check(near(out.getJSONObject("usage").getDouble("cost"),0.001)&&lines.size()==1&&has(lines.get(0),"solo el texto"),"Simple retry not logged once, or cost wrong: "+lines);
            for(File f:fake.made)check(!f.exists(),"Converted audio left behind after a retry");
            // … y se recuerda: el bloque siguiente con ese modelo va directo en modo simple (un solo envío, sin anclas ni aviso nuevo).
            check(OpenRouterClient.PLAIN.contains(mai),"Simple mode not remembered for the model");
            http=new Fake().reply(200,"{\"text\":\"Sigo\"}");fake=new FakeAudio(30_000,new long[]{0,8000});
            out=client(c,http,fake,lines).transcribe(audio,config(mai,true),"es",refs,null);
            check(http.calls==1&&http.sent.get(0).getString("response_format").equals("json")&&!http.sent.get(0).has("provider")&&fake.asked.equals(Collections.singletonList(0))&&lines.size()==1&&text(contract(out),0).equals("Sigo"),"Next block of a simple-mode model uploaded twice: "+http.calls+" "+lines);
            OpenRouterClient.PLAIN.remove(mai);
            // 2. Si el modo simple también falla, no hay un tercer envío.
            http=new Fake().reply(400,rejected).reply(400,rejected);Exception e=null;
            try{client(c,http,new FakeAudio(5000),lines).transcribe(audio,config(mai,true),"es",null,null);}catch(HttpApi.UserAction x){e=x;}
            check(e!=null&&http.calls==2,"Simple retry repeated more than once");
            OpenRouterClient.PLAIN.remove(mai);
            // 3. Solo texto con tiempos rechazados: el mismo archivo se reenvía (no hay anclas que quitar).
            http=new Fake().reply(400,"{\"error\":{\"code\":400,\"message\":\"timestamp_granularities is not supported\"}}").reply(200,"{\"text\":\"Hola\"}");fake=new FakeAudio(5000);
            out=client(c,http,fake,new ArrayList<>()).transcribe(audio,config(mai,false),"es",null,null);
            check(http.calls==2&&fake.asked.equals(Collections.singletonList(0))&&text(contract(out),0).equals("Hola"),"Text-only simple retry wrong");
            OpenRouterClient.PLAIN.remove(mai);

            // 4. Con anclas y una respuesta sin tiempos no se sabe dónde terminan las muestras: se reenvía sin ellas.
            String model="deepgram/nova-3";
            try{
                http=new Fake().reply(200,"{\"text\":\"esta es mi voz hola a todos\",\"usage\":{\"cost\":0.002}}").reply(200,"{\"text\":\"hola a todos\",\"usage\":{\"seconds\":30,\"cost\":0.001}}").reply(200,"{\"text\":\"otra vez\"}");
                fake=new FakeAudio(30_000,new long[]{0,8000});lines=new ArrayList<>();OpenRouterClient client=client(c,http,fake,lines);
                out=client.transcribe(audio,config(model,true),"es",refs,null);s=contract(out);
                check(http.calls==2&&fake.asked.equals(Arrays.asList(1,0))&&http.sent.get(1).has("provider"),"Answer without times was not resent without the samples");
                check(s.length()==1&&text(s,0).equals("hola a todos")&&!out.getBoolean("_diarized")&&!out.has("_anchors")&&near(out.getJSONObject("usage").getDouble("cost"),0.003),"Resent answer wrong, or the first request's cost was lost: "+out);
                check(lines.size()==2&&has(lines.get(0),"sin las muestras")&&has(lines.get(1),"voces separadas"),"Fallback not explained in the log: "+lines);
                // En esta sesión, ese modelo ya no recibe anclas (no se paga dos veces cada bloque).
                out=client.transcribe(audio,config(model,true),"es",refs,null);
                check(http.calls==3&&fake.asked.equals(Arrays.asList(1,0,0))&&text(contract(out),0).equals("otra vez"),"Model without times still got voice samples");
            }finally{OpenRouterClient.NO_TIMES.remove(model);}

            // 5. Si armar el audio con las anclas falla, se arma sin ellas: una muestra nunca hace fallar la transcripción.
            FakeAudio broken=new FakeAudio(5000,new long[]{0,8000}){
                @Override public OrAudio.Built build(File source,List<File> anchors,File outBase,HttpApi cancel)throws Exception{
                    if(anchors!=null){asked.add(anchors.size());throw new IOException("muestra ilegible");}
                    return super.build(source,null,outBase,cancel);
                }
            };
            http=new Fake().reply(200,new JSONObject().put("segments",list(seg(0,0.2,1.8,"Hola"))).toString());
            out=client(c,http,broken,new ArrayList<>()).transcribe(audio,config("microsoft/mai-transcribe-2",true),"es",refs,null);s=contract(out);
            check(http.calls==1&&broken.asked.equals(Arrays.asList(1,0))&&s.length()==1&&speaker(s,0).equals("0")&&!out.has("_anchors"),"A failing voice sample broke the transcription");
            wav(c,audio);
            untimed(c,audio);
        }finally{audio.delete();OpenRouterClient.PLAIN.remove(mai);}
    }

    // ---------- Segunda ronda: WAV si el proveedor no puede con el FLAC ----------
    private static void wav(Context c,File audio)throws Exception{
        String mai="microsoft/mai-transcribe-2",corrupt="{\"error\":{\"code\":400,\"message\":\"Provider returned error\",\"metadata\":{\"raw\":\"Audio file is corrupted or in an unsupported format\"}}}";
        // Qué 400 apuntan al audio y cuáles no.
        check(OpenRouterClient.audioRejected(corrupt)&&OpenRouterClient.audioRejected("Unsupported audio format: flac")&&OpenRouterClient.audioRejected("Could not decode audio file")
            &&!OpenRouterClient.audioRejected("response_format 'verbose_json' is not supported for this audio")&&!OpenRouterClient.audioRejected("timestamp_granularities is not supported")
            &&!OpenRouterClient.audioRejected("Audio duration exceeds the maximum allowed")&&!OpenRouterClient.audioRejected("Insufficient credits")&&!OpenRouterClient.audioRejected(null),"Audio-format rejection detection wrong");
        try{
            // 1. FLAC rechazado: una vez más en WAV, con las mismas voces; queda en la bitácora y se recuerda para el modelo.
            Fake http=new Fake().reply(400,corrupt).reply(200,"{\"segments\":[{\"speaker\":0,\"start\":0.2,\"end\":1.8,\"text\":\"Hola\"}],\"usage\":{\"seconds\":5,\"cost\":0.001}}");
            FakeAudio fake=new FakeAudio(5000);List<String> lines=new ArrayList<>();
            JSONObject out=client(c,http,fake,lines).transcribe(audio,config(mai,true),"es",null,null);JSONArray s=contract(out);
            check(http.calls==2&&http.sent.get(0).getJSONObject("input_audio").getString("format").equals("flac")&&http.sent.get(1).getJSONObject("input_audio").getString("format").equals("wav")
                &&http.sent.get(1).has("provider")&&fake.wavs==1&&out.getString("_format").equals("wav")&&s.length()==1&&speaker(s,0).equals("0"),"Rejected FLAC not resent as WAV: "+http.calls+" "+out);
            check(lines.size()==1&&has(lines.get(0),"WAV")&&OpenRouterClient.NO_FLAC.contains(mai),"WAV retry not logged or not remembered: "+lines);
            for(File f:fake.made)check(!f.exists(),"Converted audio left behind after the WAV retry");
            // 2. El bloque siguiente con ese modelo va directo en WAV (sin subir el FLAC para que lo rechacen otra vez).
            http=new Fake().reply(200,"{\"text\":\"Sigo\"}");fake=new FakeAudio(5000);
            client(c,http,fake,lines).transcribe(audio,config(mai,false),"es",null,null);
            check(http.calls==1&&http.sent.get(0).getJSONObject("input_audio").getString("format").equals("wav")&&fake.wavs==1,"Model that refused FLAC got FLAC again");
            OpenRouterClient.NO_FLAC.remove(mai);
            // 3. Si el WAV también se rechaza, el error llega al usuario (no hay un tercer envío) y no se recuerda nada.
            http=new Fake().reply(400,corrupt).reply(400,corrupt);Exception e=null;
            try{client(c,http,new FakeAudio(5000),new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(HttpApi.UserAction x){e=x;}
            check(e!=null&&!(e instanceof HttpApi.TooLarge)&&http.calls==2&&!OpenRouterClient.NO_FLAC.contains(mai),"Rejected WAV retried again or remembered: "+e);
            // 4. Un audio que ya salió en WAV (teléfono sin FLAC) no se «reintenta en WAV».
            FakeAudio already=new FakeAudio(5000){@Override public OrAudio.Built build(File a,List<File> anchors,File outBase,HttpApi cancel)throws Exception{return make(a,anchors,outBase,cancel,"wav");}};
            http=new Fake().reply(400,corrupt);e=null;
            try{client(c,http,already,new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(HttpApi.UserAction x){e=x;}
            check(e!=null&&http.calls==1,"A WAV upload was retried as WAV");
        }finally{OpenRouterClient.NO_FLAC.remove(mai);}
    }

    // ---------- Segunda ronda: «sin tiempos» se aprende en la primera respuesta, y el costo descartado no se pierde ----------
    private static void untimed(Context c,File audio)throws Exception{
        String fish="fish-audio/transcribe-1-pro",grok="x-ai/grok-stt-1.0",nova="deepgram/nova-3";
        List<String[]> refs=Collections.singletonList(new String[]{"voz_1",url(bytes(900)),"block0:0","Persona 1"});
        try{
            // 1. Parte 1 sin anclas y con marcas de voz sin tiempos: se anota el modelo ya, y la respuesta lo dice (_timed=false)
            // para que el motor no saque muestras de tiempos repartidos.
            Fake http=new Fake().reply(200,"{\"text\":\"<|speaker:1|>Hola a todos<|speaker:2|>Buenas tardes\"}");FakeAudio fake=new FakeAudio(10_000);
            JSONObject out=client(c,http,fake,new ArrayList<>()).transcribe(audio,config(fish,true),"es",null,null);
            check(OpenRouterClient.NO_TIMES.contains(fish)&&!out.getBoolean("_timed")&&out.getBoolean("_diarized")&&contract(out).length()==2,"Model without times not learned from the first answer: "+out);
            // Las partes que siguen ya no llevan anclas (no se pagan dos veces).
            http=new Fake().reply(200,"{\"text\":\"<|speaker:1|>Sigo\"}");fake=new FakeAudio(10_000,new long[]{0,8000});
            client(c,http,fake,new ArrayList<>()).transcribe(audio,config(fish,true),"es",refs,null);
            check(http.calls==1&&fake.asked.equals(Collections.singletonList(0)),"Parts after an untimed answer still carried anchors");
            // 2. Un silencio (texto vacío) no es prueba de que el modelo no dé tiempos.
            http=new Fake().reply(200,"{\"text\":\"\"}");
            out=client(c,http,new FakeAudio(10_000),new ArrayList<>()).transcribe(audio,config(grok,true),"es",null,null);
            check(!OpenRouterClient.NO_TIMES.contains(grok)&&contract(out).length()==0,"Silence marked the model as untimed");
            // 3. Una respuesta con tiempos lo dice (_timed=true).
            http=new Fake().reply(200,new JSONObject().put("segments",list(seg(0,0.2,1.8,"Hola"))).toString());
            out=client(c,http,new FakeAudio(10_000),new ArrayList<>()).transcribe(audio,config(grok,true),"es",null,null);
            check(out.getBoolean("_timed"),"Timed answer not flagged as timed");
            // 4. Envío cobrado y descartado (sin tiempos, con anclas) cuyo reenvío falla: su costo llega al motor igual.
            List<Double> billed=new ArrayList<>();
            http=new Fake().reply(200,"{\"text\":\"esta es mi voz hola\",\"usage\":{\"cost\":0.002}}").reply(503,"{\"error\":{\"code\":503,\"message\":\"Provider returned error\"}}");
            http.onBilled=billed::add;Exception e=null;
            try{client(c,http,new FakeAudio(10_000,new long[]{0,8000}),new ArrayList<>()).transcribe(audio,config(nova,true),"es",refs,null);}catch(IOException x){e=x;}
            check(e!=null&&http.calls==2&&billed.size()==1&&near(billed.get(0),0.002),"Cost of a discarded request lost when the resend failed: "+billed);
            // … y si el reenvío sale bien, va en la respuesta (no se avisa aparte: se contaría dos veces).
            OpenRouterClient.NO_TIMES.remove(nova);billed.clear();
            http=new Fake().reply(200,"{\"text\":\"esta es mi voz hola\",\"usage\":{\"cost\":0.002}}").reply(200,"{\"text\":\"hola\",\"usage\":{\"cost\":0.001}}");http.onBilled=billed::add;
            out=client(c,http,new FakeAudio(10_000,new long[]{0,8000}),new ArrayList<>()).transcribe(audio,config(nova,true),"es",refs,null);
            check(billed.isEmpty()&&near(out.getJSONObject("usage").getDouble("cost"),0.003),"Discarded cost reported twice: "+billed);
        }finally{OpenRouterClient.NO_TIMES.remove(fish);OpenRouterClient.NO_TIMES.remove(grok);OpenRouterClient.NO_TIMES.remove(nova);}
    }

    // ---------- Segunda ronda: la etapa «Preparando audio» ----------
    static void preparing(Context c)throws Exception{
        File audio=write(new File(c.getCacheDir(),"or-check-audio.m4a"),bytes(2000));String mai="microsoft/mai-transcribe-2";
        try{
            // 1. El cliente avisa cuándo empieza y termina, y el envío dice «preparando» mientras se arma el audio.
            Fake http=new Fake().reply(200,new JSONObject().put("segments",list(seg(0,0.2,1.8,"Hola"))).toString());List<String> events=new ArrayList<>();
            http.onPreparing=(begin,ok)->events.add(begin?"start":ok?"ok":"fail");FakeAudio fake=new FakeAudio(5000);
            client(c,http,fake,new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);
            check(events.equals(Arrays.asList("start","ok"))&&fake.phases.equals(Collections.singletonList(HttpApi.PREPARE))&&http.phase==HttpApi.IDLE&&http.lastActivity>0,"Preparing stage not announced: "+events+" "+fake.phases);
            // 2. Una conversión que falla: se avisa el fin (sin éxito), cuenta como intento (IOException, no UserAction) con un
            // motivo claro, y no se envía nada.
            events.clear();http=new Fake();http.onPreparing=(begin,ok)->events.add(begin?"start":ok?"ok":"fail");
            Exception e=null;try{client(c,http,new FailingAudio(new IllegalStateException("codec")),new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(Exception x){e=x;}
            check(e instanceof IOException&&!(e instanceof InterruptedIOException)&&has(e.getMessage(),"preparar el audio")&&http.calls==0&&events.equals(Arrays.asList("start","fail")),"Failed conversion mishandled: "+e+" "+events);
            // 3. Lo que reintentar no arregla se le dice al usuario de inmediato: audio vacío, sin pista de audio, disco lleno.
            http=new Fake();e=null;try{client(c,http,new FailingAudio(new OrAudio.TooShort()),new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(Exception x){e=x;}
            check(e instanceof HttpApi.UserAction&&has(e.getMessage(),"vacío")&&http.calls==0,"Empty audio should ask the user, not retry: "+e);
            e=null;try{client(c,new Fake(),new FailingAudio(new OrAudio.NoTrack()),new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(Exception x){e=x;}
            check(e instanceof HttpApi.UserAction&&has(e.getMessage(),"pista de audio"),"Audio without a track should ask the user: "+e);
            // Disco lleno con anclas: no se intenta otra vez sin ellas (el problema no son las muestras).
            List<String[]> refs=Collections.singletonList(new String[]{"voz_1",url(bytes(900)),"block0:0","Persona 1"});
            FailingAudio full=new FailingAudio(new HttpApi.UserAction(OrAudio.NO_SPACE));e=null;
            try{client(c,new Fake(),full,new ArrayList<>()).transcribe(audio,config(mai,true),"es",refs,null);}catch(Exception x){e=x;}
            check(e instanceof HttpApi.UserAction&&has(e.getMessage(),"espacio")&&full.asked.equals(Collections.singletonList(1)),"Disk full retried without the anchors: "+full.asked);
            // 4. El vigilante cortó una preparación trabada: llega tal cual (no como falla del FLAC ni como «pausa de Android»).
            e=null;try{client(c,new Fake(),new FailingAudio(new HttpApi.PrepareStalled("preparar el audio tardó más de 03:00")),new ArrayList<>()).transcribe(audio,config(mai,true),"es",null,null);}catch(Exception x){e=x;}
            check(e instanceof HttpApi.PrepareStalled&&!Transcriber.localCut(e),"Stalled preparation not reported as such: "+e);
            // 5. HttpApi: abortPreparing hace fallar el próximo check() solo mientras dura esa preparación.
            HttpApi h=new HttpApi();h.startPreparing();check(h.phase==HttpApi.PREPARE,"Phase not PREPARE while preparing");
            h.abortPreparing("tardó");boolean stopped=false;try{h.check();}catch(HttpApi.PrepareStalled x){stopped=true;}
            check(stopped,"Aborted preparation did not stop the conversion");
            h.endPreparing(false);h.check();check(h.phase==HttpApi.IDLE&&h.lastActivity>0&&HttpApi.PHASES[h.lastPhase].equals("prepare"),"Preparation end not recorded");
        }finally{audio.delete();}
    }

    // ---------- Tercera ronda: «Solo con Wi-Fi» se revisa justo antes de cada envío ----------
    static void gate(Context c)throws Exception{
        // La revisión va antes de abrir la conexión: si dice que no, no sale nada (ni siquiera se intenta conectar).
        HttpApi h=new HttpApi();int[] asked={0};h.beforeSend=()->{asked[0]++;throw new Transcriber.WaitWifi();};
        Exception e=null;try{h.request("POST","https://openrouter.test/api/v1/audio/transcriptions","k","application/json",HttpApi.bytes(new byte[]{1}),null);}catch(Exception x){e=x;}
        check(e instanceof Transcriber.WaitWifi&&asked[0]==1&&h.phase==HttpApi.IDLE,"Wi-Fi check did not stop the request: "+e);
        // Si deja pasar, el envío sigue como siempre (aquí lo frena la regla de solo HTTPS, sin tocar la red).
        h.beforeSend=()->asked[0]++;e=null;try{h.request("GET","http://openrouter.test/x",null,null,null,null);}catch(Exception x){e=x;}
        check(e instanceof SecurityException&&asked[0]==2,"Request after a passing Wi-Fi check wrong: "+e);
        // Con el cliente de OpenRouter y la conexión de verdad: el audio se prepara pero no se envía, y no quedan temporales.
        File audio=write(new File(c.getCacheDir(),"or-check-gate.m4a"),bytes(2000));
        try{
            HttpApi real=new HttpApi();real.beforeSend=()->{throw new Transcriber.WaitWifi();};FakeAudio fake=new FakeAudio(5000);e=null;
            try{client(c,real,fake,new ArrayList<>()).transcribe(audio,config("microsoft/mai-transcribe-2",true),"es",null,null);}catch(Exception x){e=x;}
            boolean left=false;for(File f:fake.made)left|=f.exists();
            check(e instanceof Transcriber.WaitWifi&&fake.made.size()==1&&!left,"OpenRouter client sent, or kept files, after the Wi-Fi check said no: "+e+" "+fake.made.size());
        }finally{audio.delete();}
    }

    // ---------- Segunda ronda: Transcript con partes que vienen con y sin voces ----------
    static void memory()throws Exception{
        // Parte 1 en silencio (sin voces) y parte 2 con voces: la transcripción queda «con voces» (antes, toda como «Texto»).
        JSONObject silent=new JSONObject().put("_diarized",false).put("segments",new JSONArray());
        JSONObject voiced=new JSONObject().put("_diarized",true).put("segments",list(seg("0",0,2,"Hola"),seg("1",2,4,"Chao")));
        Transcript t=Transcript.fromParts(Arrays.asList(silent,voiced),Arrays.asList(0d,600d));
        check(t.diarized()&&t.speakers().size()==2&&!t.speakers().containsValue("Texto"),"A silent first part made the whole transcript text-only");
        JSONObject textOnly=new JSONObject().put("_diarized",false).put("segments",list(seg("text",0,2,"Hola")));
        check(!Transcript.fromParts(Arrays.asList(textOnly,new JSONObject(textOnly.toString())),Arrays.asList(0d,600d)).diarized(),"Text-only parts became diarized");
        // Respuestas de OpenAI (sin "_diarized") siguen como siempre: con voces.
        check(Transcript.fromParts(Collections.singletonList(new JSONObject().put("segments",list(seg("A",0,1,"Hola")))),Collections.singletonList(0d)).diarized(),"OpenAI-style part not diarized");
    }

    // ---------- Reglas del motor (sin teléfono) ----------
    /** Transcrita con voces, corregida, con clave de OpenRouter y un modelo que separa voces: todo disponible. */
    private static Retranscribe.Facts facts(){
        Retranscribe.Facts f=new Retranscribe.Facts();f.transcribed=true;f.hasKey=true;f.openrouter=true;f.canSeparate=true;f.diarized=true;f.confirmed=true;
        f.samples=2;f.durationMs=10*60_000;f.bytes=6_000_000;f.parts=2;f.singleMaxMs=20*60_000L;f.singleMaxBytes=Long.MAX_VALUE;return f;
    }
    static void rules()throws Exception{
        Models.Recipe wide=recipe(Models.Diarize.AZURE,true,110),narrow=recipe(Models.Diarize.GENERIC,true,10);
        // Bloques: 12 min con voces y 8 sin voces, nunca más de lo que acepta el modelo (menos 1 min para las anclas).
        check(Transcriber.orBlockMax(wide,true)==12*60_000L&&Transcriber.orBlockMax(wide,false)==8*60_000L&&Transcriber.orBlockMax(narrow,true)==9*60_000L,"OpenRouter block limit wrong");
        check(Transcriber.orBlockMs(30*60_000L,12*60_000L,false)==10*60_000L&&Transcriber.orBlockMs(10*60_000L,12*60_000L,false)==10*60_000L&&Transcriber.orBlockMs(0,8*60_000L,false)==8*60_000L,"OpenRouter blocks not even");
        // 413: los bloques bajan a la mitad.
        check(Transcriber.orBlockMs(30*60_000L,12*60_000L,true)==5*60_000L&&Transcriber.orBlockMs(10*60_000L,12*60_000L,true)==5*60_000L,"Halved blocks wrong");
        // Espera de la respuesta: con OpenRouter no pasa de 5 min; los demás proveedores, igual que antes.
        check(Transcriber.responseLimit("openrouter",12*60_000L)==5*60_000L&&Transcriber.responseLimit("openrouter",30_000)==3*60_000L
            &&Transcriber.responseLimit("openai",12*60_000L)==13*60_000L&&Transcriber.responseLimit("custom",12*60_000L)==Transcriber.responseLimit(12*60_000L),"Response limit by provider wrong");
        // «Sin cortar»: lo que acepta el modelo y no más de 24 MB de FLAC estimado (20 min).
        check(Retranscribe.singleMaxMs(wide)==20*60_000L&&Retranscribe.singleMaxMs(narrow)==10*60_000L&&Retranscribe.fitsSingle(wide,20*60_000L)&&!Retranscribe.fitsSingle(wide,20*60_000L+1)&&!Retranscribe.fitsSingle(wide,0),"Single-request limit for OpenRouter wrong");
        check(TranscribeClient.knowsVoices("openai")&&TranscribeClient.knowsVoices("openrouter")&&!TranscribeClient.knowsVoices("custom"),"Providers that accept voice samples wrong");
        // «Volver a transcribir» con OpenRouter: la segunda pasada con correcciones ya no es solo de OpenAI.
        for(Retranscribe.Mode m:Retranscribe.Mode.values())check(Retranscribe.reason(facts(),m)==null,"Retranscribe mode unavailable with OpenRouter: "+m);
        Retranscribe.Facts f=facts();f.canSeparate=false;
        check(has(Retranscribe.reason(f,Retranscribe.Mode.CORRECTIONS),"no separa voces")&&has(Retranscribe.reason(f,Retranscribe.Mode.SPEAKERS),"no separa voces")&&Retranscribe.reason(f,Retranscribe.Mode.TEXT)==null,"Text-only OpenRouter model limits wrong");
        f=facts();f.openrouter=false;check(has(Retranscribe.reason(f,Retranscribe.Mode.CORRECTIONS),"OpenRouter u OpenAI"),"Second pass offered to a provider without voice samples");
        // El peso del m4a no limita el envío único (viaja FLAC); la duración sí, con el tope del modelo en el mensaje.
        f=facts();f.bytes=60_000_000;f.durationMs=20*60_000L;check(Retranscribe.reason(f,Retranscribe.Mode.SINGLE)==null,"Heavy m4a refused for a single OpenRouter request");
        f.durationMs=21*60_000L;check(has(Retranscribe.reason(f,Retranscribe.Mode.SINGLE),"20 min"),"Single request over the model limit not explained");
        // Sin voces y en varias partes: si cada frase trae su tiempo real, el .txt no dice que los tiempos son del bloque.
        Recording demo=new Recording(UUID.randomUUID().toString(),"Prueba",0,20_000);
        Transcript timed=new Transcript(new JSONObject().put("diarized",false).put("parts",2).put("segments",list(seg("block0:text",0.5,2.0,"Hola"),seg("block1:text",12.0,14.0,"Chao"))));
        Transcript blocks=new Transcript(new JSONObject().put("diarized",false).put("parts",2).put("segments",list(seg("block0:text",0,0,"Hola"),seg("block1:text",10,10,"Chao"))));
        check(timed.phraseTimes()&&!blocks.phraseTimes()&&!timed.text(demo).contains("Audio procesado en bloques")&&blocks.text(demo).contains("Audio procesado en bloques"),"Block-times warning wrong for phrase-timed text");
        // Costo real del estado.
        check(Pricing.real(null)<0&&Pricing.real(new JSONObject())<0&&near(Pricing.real(new JSONObject().put("costUsd",0.25)),0.25),"Real cost not read from the state");
    }

    // ---------- Segunda ronda: ninguna transcripción da vueltas más de una hora sin avisar ----------
    static void retries()throws Exception{
        long now=1_000_000_000L,min=60_000L;
        // Que el proveedor no responda (el vigilante cortó con el teléfono funcionando) gasta un intento: antes eran 12 «gratis».
        Transcriber.Outcome o=Transcriber.outcome(new JSONObject(),new IOException("x",new HttpApi.Stalled("OpenRouter no respondió en 03:00",false)),now);
        check(!o.cut&&!o.local&&o.attempts==1&&o.again&&!Transcriber.localCut(new HttpApi.Stalled("x",false)),"Provider timeout treated as a phone cut");
        // El envío dejó de avanzar (o Android congeló la app mientras esperaba): no gasta un intento…
        o=Transcriber.outcome(new JSONObject(),new HttpApi.Stalled("el envío dejó de avanzar"),now);
        check(o.cut&&o.local&&o.attempts==0&&o.cuts==1&&o.cutSince==now,"Phone cut not free: "+o.attempts);
        o=Transcriber.outcome(new JSONObject().put("localCuts",2).put("cutSince",now-5*min),new HttpApi.Stalled("x"),now);
        check(o.local&&o.cutSince==now-5*min,"Phone-cut window not kept");
        // … salvo que se repita durante más de 15 min sin avanzar, o más de 12 veces.
        o=Transcriber.outcome(new JSONObject().put("localCuts",3).put("cutSince",now-16*min),new java.net.SocketException("Software caused connection abort"),now);
        check(o.cut&&!o.local&&o.attempts==1&&o.cuts==4&&o.cutSince==now-16*min,"Phone cuts free for more than 15 min");
        o=Transcriber.outcome(new JSONObject().put("localCuts",12).put("cutSince",now-min),new HttpApi.Stalled("x"),now);
        check(!o.local&&o.attempts==1&&o.cuts==13,"More than 12 free phone cuts");
        // Una parte lista vuelve a cero la cuenta (localCuts=0): la ventana empieza de nuevo aunque quede una fecha vieja.
        o=Transcriber.outcome(new JSONObject().put("localCuts",0).put("cutSince",now-60*min),new HttpApi.Stalled("x"),now);
        check(o.local&&o.cutSince==now,"Old phone-cut window reused after progress");
        // Al quinto intento se rinde (y avisa). Una preparación trabada cuenta como intento.
        o=Transcriber.outcome(new JSONObject().put("attempts",4),new IOException("OpenRouter no está disponible temporalmente (502)."),now);
        check(o.attempts==5&&!o.again,"Fifth failure did not give up");
        o=Transcriber.outcome(new JSONObject(),new HttpApi.PrepareStalled("preparar el audio tardó más de 03:00"),now);
        check(!o.cut&&o.attempts==1,"Stalled preparation retried for free");
        // Peor caso de un audio corto al que nunca le responden: 15 min de cortes gratis + 5 intentos de 3 min con sus esperas
        // (20 s, 1, 2 y 5 min). Menos de una hora, y termina con un aviso.
        long worst=Transcriber.LOCAL_CUT_WINDOW_MS+5*Transcriber.responseLimit("openrouter",30_000)+(20+60+120+300)*1000L;
        check(worst<60*min,"Worst case for a short audio still over an hour: "+worst);
        // Tiempos de la etapa «Preparando audio» y del envío con OpenRouter: un bloque de 12 min cabe en la tarea de fondo.
        check(Transcriber.prepEstimate(12*min)==41_000&&Transcriber.prepareLimit(12*min)==6*min&&Transcriber.prepareLimit(30_000)==3*min,"Preparation estimates wrong");
        check(Transcriber.sendEstimate("openrouter",12*min)<=Transcriber.JOB_SEND_LIMIT_MS&&Transcriber.sendEstimate("openrouter",20*min)>Transcriber.JOB_SEND_LIMIT_MS
            &&Transcriber.sendEstimate("openrouter",5*min)>=Transcriber.prepEstimate(5*min)+90_000&&Transcriber.sendEstimate("openai",12*min)==Transcriber.sendEstimate(12*min),"Send estimate by provider wrong");
        // 413 en «sin cortar»: la mitad del audio completo (18 min → 9), no la de un bloque normal (4,5).
        check(Transcriber.orTarget(18*min,12*min,true,true)==9*min&&Transcriber.orTarget(18*min,12*min,true,false)==Transcriber.orBlockMs(18*min,12*min,true)
            &&Transcriber.orTarget(18*min,12*min,false,false)==9*min&&Transcriber.orTarget(18*min,12*min,true,false)==9*min/2,"Halving after a single-request 413 wrong");
        // Lo ya cobrado antes de achicar los bloques no se pierde al reiniciar las métricas.
        JSONObject s=new JSONObject().put("costUsd",0.3).put("costCarry",0.2);Transcriber.restartCost(s);
        check(near(s.optDouble("costUsd",-1),0.2)&&!s.has("costCarry"),"Carried cost lost on restart");
        s=new JSONObject().put("costUsd",0.3);Transcriber.restartCost(s);check(!s.has("costUsd"),"Cost of the discarded pass kept");
        // Costo estimado con las muestras de voz que viajan antes de cada parte (hasta 4 por envío).
        Models.Recipe maiRecipe=Models.recipe("microsoft/mai-transcribe-2");long anchor=Voices.MAX_MS+OrAudio.GAP_MS;
        check(Pricing.orBilledMs(60_000,2,maiRecipe)==60_000+2*anchor&&Pricing.orBilledMs(30*min,1,maiRecipe)==30*min+3*anchor&&Pricing.orBilledMs(60_000,9,maiRecipe)==60_000+4*anchor
            &&Pricing.orBilledMs(60_000,0,maiRecipe)==60_000&&Pricing.orBilledMs(60_000,2,Models.recipe("openai/gpt-transcribe"))==60_000,"Billed estimate with anchors wrong");
        // Tercera ronda: UNA regla (Pricing.billedMs). Por proveedor y modelo; «sin cortar» lleva las muestras una vez; un
        // modelo que no separa voces no las lleva; más de 4 muestras se cuentan como 4 (el tope del motor).
        String mai="microsoft/mai-transcribe-2";
        check(Pricing.billedMs("openrouter",mai,60_000,2,false)==Pricing.orBilledMs(60_000,2,maiRecipe)&&Pricing.billedMs("openrouter",mai,30*min,1,false)==30*min+3*anchor
            &&Pricing.billedMs("openrouter",mai,30*min,1,true)==30*min+anchor&&Pricing.billedMs("openrouter",mai,60_000,9,false)==60_000+4*anchor
            &&Pricing.billedMs("openrouter","openai/gpt-transcribe",60_000,2,false)==60_000&&Pricing.billedMs("openai","gpt-transcribe",60_000,2,false)==60_000
            &&Pricing.billedMs("openrouter",mai,0,2,false)==0&&Pricing.billedMs("openrouter",mai,60_000,0,true)==60_000,"Unified billed audio wrong");
    }

    // ---------- En el teléfono: cliente por proveedor, costos y el motor completo ----------
    static void device(Context c,Recording r)throws Exception{
        check(TranscribeClient.of(c,new HttpApi(),config("deepgram/nova-3",true)) instanceof OpenRouterClient
            &&TranscribeClient.of(c,new HttpApi(),new ProviderConfig("openai","https://api.openai.com/v1","gpt-transcribe","k",false)) instanceof OpenAiClient
            &&TranscribeClient.of(c,new HttpApi(),new ProviderConfig("custom","https://example.com/v1","m","k",false)) instanceof OpenAiClient,"Client not chosen by provider");
        // Estimados: OpenAI por su tabla, servidor propio sin precio, y un modelo de OpenRouter que no está en el catálogo, sin precio.
        check(near(Pricing.estimate(c,"openai","gpt-transcribe",60_000),0.0045)&&near(Pricing.estimate("openai","gpt-transcribe",60_000),0.0045)&&Pricing.estimate(c,"custom","m",60_000)<0&&Pricing.estimate("custom","m",60_000)<0,"Estimates for existing providers changed");
        check(Pricing.estimate(c,"openrouter","prueba/no-esta-en-el-catalogo",60_000)<0&&Pricing.estimate("openrouter","prueba/no-esta-en-el-catalogo",60_000)<0,"Unknown OpenRouter model got a price");
        engine(c,r);
    }
    private static Recording copy(Context c,Recording r,String title)throws Exception{
        Recording d=new Recording(UUID.randomUUID().toString(),title,System.currentTimeMillis(),r.duration);
        java.nio.file.Files.copy(r.audio(c).toPath(),d.audio(c).toPath());d.save(c);return d;
    }
    private static String log(JSONObject state){StringBuilder b=new StringBuilder();JSONArray log=state.optJSONArray("log");for(int i=0;log!=null&&i<log.length();i++)b.append(log.optJSONObject(i).optString("m")).append('\n');return b.toString();}
    /**
     * Lo que la prueba del motor aparta del teléfono y devuelve al final: preferencias (con la clave real), voces guardadas
     * y el catálogo de modelos. Queda escrito en disco (preferencias «orcheck-backup», carpeta «voices-backup-orcheck» y
     * «orcheck-catalog.json») antes de tocar nada: si una corrida murió a mitad (se cerró el emulador, adb la mató), la
     * siguiente lo devuelve primero, en vez de borrar el respaldo con las voces reales (hallazgo de la revisión 0.8).
     */
    private static final String[] KEYS={"provider","orSpeakersModel","orTextModel","orAutoSpeakers","orAutoBeforeSpeakers","speakersMode","noteAuto","openrouter_keyEncrypted","openrouter_keyIv","wifi"};
    private static final class Aside{
        final SharedPreferences prefs,backup;final File voices,aside,catalog,copy;
        Aside(Context c){prefs=new Settings(c).prefs;backup=c.getSharedPreferences("orcheck-backup",Context.MODE_PRIVATE);
            voices=new File(c.getFilesDir(),"voices");aside=new File(c.getFilesDir(),"voices-backup-orcheck");catalog=Models.file(c);copy=new File(c.getFilesDir(),"orcheck-catalog.json");}
        /** Una corrida anterior que no terminó: se devuelve lo que dejó apartado. */
        void recover(){
            if(backup.getBoolean("saved",false))putBack();
            if(aside.exists()){deleteTree(voices);if(!aside.renameTo(voices))throw new AssertionError("Could not recover the voices set aside by a previous run");}
        }
        void save()throws Exception{
            if(catalog.isFile())java.nio.file.Files.copy(catalog.toPath(),copy.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);else copy.delete();
            SharedPreferences.Editor e=backup.edit().clear();Map<String,?> all=prefs.getAll();
            for(String key:KEYS){Object v=all.get(key);if(v instanceof String)e.putString("v_"+key,(String)v);else if(v instanceof Boolean)e.putBoolean("v_"+key,(Boolean)v);}
            if(!e.putBoolean("catalog",catalog.isFile()).putBoolean("saved",true).commit())throw new AssertionError("Could not save the settings backup");
            if(voices.exists()&&!voices.renameTo(aside))throw new AssertionError("Could not set the real voices aside");
        }
        void putBack(){
            Map<String,?> saved=backup.getAll();SharedPreferences.Editor e=prefs.edit();
            for(String key:KEYS){Object v=saved.get("v_"+key);if(v instanceof String)e.putString(key,(String)v);else if(v instanceof Boolean)e.putBoolean(key,(Boolean)v);else e.remove(key);}
            e.commit();
            try{if(backup.getBoolean("catalog",false)&&copy.isFile())java.nio.file.Files.copy(copy.toPath(),catalog.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else if(!backup.getBoolean("catalog",false))new android.util.AtomicFile(catalog).delete();}catch(IOException ignored){}
            Models.forget();
        }
        void restore(){
            putBack();
            deleteTree(voices);if(aside.exists()&&!aside.renameTo(voices))throw new AssertionError("Could not restore the real voices");
            backup.edit().clear().commit();copy.delete();
        }
    }
    /**
     * El motor completo (Transcriber.process) con OpenRouter como proveedor y todo simulado: la primera respuesta es un
     * 413 (envío muy grande) y la segunda, la transcripción. Comprueba el 413 sin gastar intento, el costo real en el
     * estado, el perfil, la etapa «Preparando audio» y la bitácora; que la mitad tras un 413 es solo de ese modelo; y que
     * «Automático» no cambia de modelo a mitad de una transcripción. Lo real del teléfono se aparta y se devuelve (Aside).
     */
    static void engine(Context c,Recording r)throws Exception{
        Settings settings=new Settings(c);SharedPreferences prefs=settings.prefs;String mai="microsoft/mai-transcribe-2",nova="deepgram/nova-3";
        Aside kept=new Aside(c);kept.recover();kept.save();
        OpenRouterClient.Builder real=OpenRouterClient.defaultBuilder;Recording first=null,second=null,third=null;
        try{
            // Sin «Solo con Wi-Fi»: el motor lo revisa antes de cada parte (Transcriber.WaitWifi) y esta prueba no depende de la
            // red del emulador (sus envíos son simulados).
            prefs.edit().putString("provider","openrouter").putString("orSpeakersModel",mai).putBoolean("noteAuto",false).putBoolean("wifi",false).commit();
            settings.saveKeyFor("openrouter","sk-or-prueba-no-es-una-clave-real");
            FakeAudio fake=new FakeAudio(r.duration);OpenRouterClient.defaultBuilder=fake;
            first=copy(c,r,"Prueba OpenRouter");String id=first.id;
            FilesStore.update(c,id,s->s.put("requested",true).put("speakers",true).put("queuedAt",System.currentTimeMillis()));
            JSONObject answer=new JSONObject().put("text","Hola, partamos. Dale, yo anoto.").put("segments",list(seg(0,0.2,1.8,"Hola, partamos."),seg(1,2.1,3.4,"Dale, yo anoto.")))
                .put("usage",new JSONObject().put("seconds",5.2).put("cost",0.0021));
            Fake http=new Fake().reply(413,"{\"error\":{\"code\":413,\"message\":\"Request entity too large\"}}").reply(200,answer.toString());http.jobId=id;
            new Transcriber(c,http,0).process(first);
            JSONObject st=FilesStore.state(c,id);String log=log(st);
            check(http.calls==2&&Transcript.exists(c,id),"Engine did not retry after a 413, or did not save the transcript. Log:\n"+log);
            Transcript t=Transcript.load(c,id);JSONArray segs=t.segments();
            check(segs.length()==2&&speaker(segs,0).equals("0")&&speaker(segs,1).equals("1")&&t.diarized()&&t.data.optString("provider").equals("openrouter")&&t.data.optString("model").equals(mai),"Transcript from OpenRouter wrong: "+t.data);
            // El 413 no gasta un intento: los bloques bajan a la mitad una vez ("orHalf", con el modelo que lo respondió) y sigue de inmediato.
            check(mai.equals(st.optString("orHalf"))&&!st.optBoolean("requested")&&!st.optBoolean("failed")&&st.optInt("attempts")==0&&!st.has("costCarry"),"413 should halve the blocks without spending an attempt: "+st.optString("orHalf")+" "+st.optInt("attempts"));
            // Costo real y métricas del estado.
            check(near(st.optDouble("costUsd",-1),0.0021)&&near(Pricing.real(st),0.0021)&&near(st.optDouble("usageSec",-1),5.2)&&st.optInt("blocksDone")==1&&st.optLong("bytesSent")==http.lastLength,"Real cost or metrics not stored: "+st.optDouble("costUsd",-1)+" "+st.optDouble("usageSec",-1)+" "+st.optLong("bytesSent"));
            check(st.optString("provider").equals("openrouter")&&st.optString("model").equals(mai)&&st.optString("profile").contains(OpenRouterClient.PROFILE)&&st.optString("profile").contains("|half"),"Profile does not carry the OpenRouter format and block size");
            // Etapa «Preparando audio»: en la bitácora (antes de «Enviando…») y en el estado, sin quedar «preparando» al terminar.
            check(has(log,"Preparando el audio para enviarlo")&&has(log,"Enviando audio · preparado en")&&log.indexOf("Preparando el audio para enviarlo")<log.indexOf("Enviando audio · preparado en")
                &&st.optInt("prepCount")==1&&st.optInt("prepping")==0&&st.has("prepMsSum")&&fake.phases.equals(Arrays.asList(HttpApi.PREPARE,HttpApi.PREPARE)),"Preparing stage missing from the log or the state:\n"+log);
            check(has(log,"OpenRouter está transcribiendo")&&has(log,"partes de la mitad")&&has(log,"Transcripción lista")&&!has(log,"sk-or-"),"Engine log wrong:\n"+log);
            // La revisión de «Solo con Wi-Fi» antes de enviar es de cada parte: no queda puesta en la conexión del trabajo.
            check(http.beforeSend==null,"Wi-Fi check left on the worker connection");
            // Lo que se envió: receta Azure con voces, sin anclas (no hay voces guardadas), y el audio «convertido».
            JSONObject sent=http.sent.get(1);
            check(http.url.equals(Models.BASE+"/audio/transcriptions")&&"sk-or-prueba-no-es-una-clave-real".equals(http.token)&&sent.getJSONObject("provider").getJSONObject("options").getJSONObject("azure").getJSONObject("diarization").getBoolean("enabled")
                &&Arrays.equals(Base64.decode(sent.getJSONObject("input_audio").getString("data"),Base64.DEFAULT),fake.data)&&fake.asked.equals(Arrays.asList(0,0)),"Engine request to OpenRouter wrong");
            // «Volver a transcribir» con OpenRouter: disponible, con los topes del modelo.
            Retranscribe.Facts facts=Retranscribe.facts(c,first);
            check(facts.openrouter&&!facts.openai&&facts.hasKey&&facts.canSeparate&&facts.transcribed&&facts.saved==0&&facts.singleMaxMs==20*60_000L&&facts.singleMaxBytes==Long.MAX_VALUE&&Retranscribe.singleMaxMs(c)==20*60_000L,"Retranscribe facts for OpenRouter wrong");
            // El costo real vuelve con la versión anterior, y «orHalf» (ni lo arrastrado) no pasa al intento nuevo.
            FilesStore.update(c,id,s->s.put("costCarry",0.1));
            Retranscribe.prepare(c,first,Retranscribe.Mode.TEXT,null);st=FilesStore.state(c,id);
            check(!st.has("orHalf")&&!st.has("costCarry")&&near(st.getJSONObject("retranscribe").getJSONObject("before").optDouble("costUsd",-1),0.0021),"Real cost not kept with the previous version: "+st.optJSONObject("retranscribe"));
            FilesStore.update(c,id,s->s.put("costUsd",0.5));
            Retranscribe.restorePrevious(c,id);
            check(near(Pricing.real(FilesStore.state(c,id)),0.0021),"Real cost of the previous version not restored");

            // Si con la mitad tampoco cabe, el error llega al usuario (no se sigue partiendo ni reenviando).
            second=copy(c,r,"Prueba OpenRouter 413");String other=second.id;
            FilesStore.update(c,other,s->s.put("requested",true).put("speakers",true).put("queuedAt",System.currentTimeMillis()));
            Fake full=new Fake().reply(413,"").reply(413,"");full.jobId=other;boolean tooLarge=false;
            try{new Transcriber(c,full,0).process(second);}catch(HttpApi.TooLarge e){tooLarge=true;}
            check(tooLarge&&full.calls==2&&!Transcript.exists(c,other)&&mai.equals(FilesStore.state(c,other).optString("orHalf")),"A second 413 should reach the user after one halving");
            // La mitad es cosa de ese modelo: con otro modelo y «Reintentar», los bloques vuelven a su tamaño normal.
            prefs.edit().putString("orSpeakersModel",nova).commit();
            FilesStore.update(c,other,s->s.put("requested",true).put("failed",false));
            JSONObject words=new JSONObject().put("words",list(word(0,0.1,0.4,"Hola"),word(1,1.3,1.6,"Buenas")));
            Fake retry=new Fake().reply(200,words.toString());retry.jobId=other;
            new Transcriber(c,retry,0).process(second);st=FilesStore.state(c,other);
            check(retry.calls==1&&Transcript.exists(c,other)&&nova.equals(st.optString("model"))&&!st.optString("profile").contains("|half")&&retry.sent.get(0).getJSONObject("provider").getJSONObject("options").has("deepgram"),"Halving of one model applied to another: "+st.optString("profile"));

            // «Automático» cambió de recomendación con una parte ya lista: la transcripción termina con el modelo con que empezó.
            // Tercera ronda: una sola regla, la de Models.resume, que exige que refresh() haya anotado de qué modelo se movió
            // «Automático» (orAutoBeforeSpeakers). Si fue la persona quien eligió «Automático», manda su elección (abajo).
            long now=System.currentTimeMillis();
            FilesStore.write(Models.file(c),new JSONObject().put("v",1).put("fetchedAt",now).put("models",new JSONArray().put(new JSONObject().put("id",mai).put("name","Microsoft: MAI Transcribe 2").put("created",1).put("prompt",0.10).put("expires",0))));Models.forget();
            prefs.edit().putString("orSpeakersModel",Models.AUTO).putString("orAutoSpeakers",nova).putString("orAutoBeforeSpeakers",mai).commit();
            third=copy(c,r,"Prueba Automático");String auto=third.id;
            FilesStore.update(c,auto,s->s.put("requested",true).put("speakers",true).put("queuedAt",now).put("provider","openrouter").put("model",mai).put("blocksDone",1));
            Fake pinned=new Fake().reply(200,answer.toString());pinned.jobId=auto;
            new Transcriber(c,pinned,0).process(third);st=FilesStore.state(c,auto);
            check(pinned.calls==1&&mai.equals(st.optString("model"))&&pinned.sent.get(0).getString("model").equals(mai)&&pinned.sent.get(0).getJSONObject("provider").getJSONObject("options").has("azure")&&has(log(st),"termina con el que empezó"),"Automatic switched models halfway through a transcription: "+st.optString("model"));
            // La misma regla que Ajustes: el motor no tiene otra. Sin la anotación de refresh() (la persona pasó a «Automático»
            // a mano), sigue con el recomendado de hoy.
            JSONObject half=new JSONObject().put("provider","openrouter").put("model",mai).put("blocksDone",1).put("speakers",true);
            check(mai.equals(Models.resume(c,new Settings(c),true,half)),"Models.resume and the engine disagree");
            prefs.edit().remove("orAutoBeforeSpeakers").commit();
            check(nova.equals(Models.resume(c,new Settings(c),true,half)),"Automatic kept the old model although the person chose Automatic");
        }finally{
            OpenRouterClient.defaultBuilder=real;
            if(first!=null)first.delete(c);if(second!=null)second.delete(c);if(third!=null)third.delete(c);
            kept.restore();
        }
    }
}
