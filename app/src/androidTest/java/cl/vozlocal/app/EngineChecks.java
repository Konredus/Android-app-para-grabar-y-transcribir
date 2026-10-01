package cl.vozlocal.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import org.json.*;
import java.io.File;
import java.util.*;
import static cl.vozlocal.app.Retranscribe.Mode.*;

/**
 * Pruebas de la parte «engine» de la 0.6.0 (ver docs/diseno/SPEC-0.6.md): volver a transcribir (disponibilidad, muestras
 * de la segunda pasada, versión anterior), comienzo guardado, notificación «lista» y borrado completo. Sin llamadas
 * reales a APIs: nada de esto encola trabajo (Retranscribe.prepare hace todo lo de start() menos encolar).
 */
final class EngineChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static boolean has(String text,String part){return text!=null&&text.contains(part);}
    private static JSONObject seg(String who,double a,double b,String text)throws JSONException{return new JSONObject().put("speaker",who).put("start",a).put("end",b).put("text",text);}

    static void run(Context c,Recording r)throws Exception{
        availability();
        sizing();
        corrections();
        server(c);
        route();
        mobileData(c,r);
        userJob(c);
        Recording d=copy(c,r);
        try{versions(c,d);snippet(c,d);notification(c,d);}
        catch(Throwable failure){try{d.delete(c);}catch(Exception ignored){}throw failure;}
        deleteAll(c,d);
    }

    // ---------- 0.8.0, tercera ronda: por dónde corre el trabajo ----------
    /** La decisión de usar la transferencia iniciada por el usuario, sin depender del teléfono. */
    static void route(){
        check(Pipeline.route(34,true,true,true,false)==Pipeline.Route.USER_JOB&&Pipeline.route(35,true,true,true,false)==Pipeline.Route.USER_JOB,"A tap with the app visible on Android 14+ should be a user-initiated transfer");
        check(Pipeline.route(33,true,true,true,false)==Pipeline.Route.CLASSIC&&Pipeline.route(26,true,true,true,false)==Pipeline.Route.CLASSIC,"Android 13 or older must keep the foreground-service path");
        check(Pipeline.route(35,false,true,true,false)==Pipeline.Route.CLASSIC,"Automatic work (no tap) must stay as before");
        check(Pipeline.route(35,true,false,true,false)==Pipeline.Route.CLASSIC,"With the app not visible Android refuses it: the old path must be used");
        check(Pipeline.route(35,true,true,false,false)==Pipeline.Route.CLASSIC,"Without RUN_USER_INITIATED_JOBS the old path must be used");
        // Con un trabajador andando no se programa otro: con el mismo id, Android detendría la subida en curso.
        check(Pipeline.route(35,true,true,true,true)==Pipeline.Route.RUNNING&&Pipeline.route(30,false,false,false,true)==Pipeline.Route.RUNNING,"A running worker would be replaced");
    }

    // ---------- 0.8.0, tercera ronda: «Usar datos móviles ahora» ----------
    static void mobileData(Context c,Recording r)throws Exception{
        JSONObject waiting=new JSONObject().put("requested",true);
        check(Pipeline.waitsForWifi(true,true,false,waiting),"A requested recording on mobile data with Wi-Fi only should wait for Wi-Fi");
        check(!Pipeline.waitsForWifi(true,true,true,waiting)&&!Pipeline.waitsForWifi(false,true,false,waiting)&&!Pipeline.waitsForWifi(true,false,false,waiting),"Wi-Fi wait offered on Wi-Fi, without the preference, or offline (mobile data cannot help there)");
        check(!Pipeline.waitsForWifi(true,true,false,new JSONObject(waiting.toString()).put("mobileOk",true))&&!Pipeline.waitsForWifi(true,true,false,new JSONObject())&&!Pipeline.waitsForWifi(true,true,false,null),"A recording with mobile data allowed, or not requested, still waits for Wi-Fi");
        // Tamaño estimado del envío: FLAC (~20 KB por segundo) en base64 con las muestras de voz; lo ya transcrito no se sube.
        String mai="microsoft/mai-transcribe-2";long four=Pipeline.uploadBytes("openrouter",mai,4*60_000,0,0,3_000_000);
        check(four==HttpApi.base64Length(4*60_000L*Retranscribe.OR_BYTES_PER_MS)&&Pipeline.megabytes(four).equals("≈6,4 MB"),"Upload estimate for 4 min wrong: "+four+" "+Pipeline.megabytes(four));
        check(Pipeline.uploadBytes("openrouter",mai,4*60_000,3*60_000,0,3_000_000)*4<four+16&&Pipeline.uploadBytes("openrouter",mai,4*60_000,0,2,3_000_000)>four,"Upload estimate ignores the parts already done or the voice samples");
        check(Pipeline.uploadBytes("openai","gpt-transcribe",60_000,30_000,0,1_000_000)==500_000&&Pipeline.uploadBytes("openrouter",mai,60_000,90_000,0,1)==0,"Upload estimate for the m4a, or for nothing left, wrong");
        check(Pipeline.megabytes(26_400_000).equals("≈26 MB")&&Pipeline.megabytes(10).equals("≈0,1 MB"),"Megabytes format wrong");
        // En una grabación desechable: el permiso es de ESA grabación, una sola vez, queda en la bitácora sin el título y
        // cancelar lo quita. La ventana con "requested" es mínima (nada la envía: no hay runner ni red en esta prueba).
        Recording d=new Recording(UUID.randomUUID().toString(),"Prueba datos móviles",System.currentTimeMillis(),r.duration);
        java.nio.file.Files.copy(r.audio(c).toPath(),d.audio(c).toPath());d.save(c);
        try{
            check(!Pipeline.allowMobile(c,d.id),"Mobile data allowed for a recording that is not requested");
            FilesStore.update(c,d.id,s->s.put("requested",true));
            boolean allowed=Pipeline.allowMobile(c,d.id);JSONObject st=FilesStore.state(c,d.id);boolean again=Pipeline.allowMobile(c,d.id);
            String blocker=Pipeline.blocker(c,d.id);
            Pipeline.cancel(c,d.id);JSONObject after=FilesStore.state(c,d.id);
            String log=st.optJSONArray("log")==null?"":st.optJSONArray("log").toString();
            check(allowed&&st.optBoolean("mobileOk")&&!again,"Use mobile data now not stored once");
            check(has(log,"Usarás datos móviles")&&!has(log,d.title)&&!has(log,"«"),"Mobile data log line wrong or not private: "+log);
            check(blocker==null||!blocker.startsWith("esperando Wi-Fi"),"A recording with mobile data allowed still waits for Wi-Fi");
            check(!after.optBoolean("requested")&&!after.has("mobileOk"),"Cancelling kept the mobile data permission");
        }finally{d.delete(c);}
    }

    // ---------- 0.8.0, tercera ronda: transferencia iniciada por el usuario (Android 14+) ----------
    static void userJob(Context c){
        Settings s=new Settings(c);boolean wifi=s.wifiOnly(),charging=s.charging();
        try{
            s.prefs.edit().putBoolean("wifi",true).putBoolean("charging",false).commit();
            // La tarea de fondo deja de esperar Wi-Fi si alguna grabación pedida tiene permiso para datos móviles.
            check(Pipeline.jobInfo(c,s).getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_UNMETERED&&Pipeline.jobInfo(c,s,true).getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY,"Background job network with mobile data allowed wrong");
            if(android.os.Build.VERSION.SDK_INT>=34){
                // Persistida si este Android lo acepta (Pipeline.scheduleUserJob hace lo mismo: si no, la pide sin persistir).
                android.app.job.JobInfo job;boolean persisted=true;
                try{job=Pipeline.userJobInfo(c,s,false,6_400_000,true);}
                catch(IllegalArgumentException e){persisted=false;job=Pipeline.userJobInfo(c,s,false,6_400_000,false);android.util.Log.i("VozLocalTest","User-initiated job not persisted on this Android: "+e.getMessage());}
                check(job.isUserInitiated()&&job.getId()==Pipeline.USER_JOB_ID&&job.getId()!=Pipeline.JOB_ID&&job.getPriority()==android.app.job.JobInfo.PRIORITY_MAX
                    &&job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_UNMETERED&&job.getEstimatedNetworkUploadBytes()==6_400_000&&job.isPersisted()==persisted,"User-initiated transfer job wrong");
                check(Pipeline.userJobInfo(c,s,true,0,false).getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY,"User-initiated transfer waits for Wi-Fi although mobile data was allowed");
            }
        }finally{s.prefs.edit().putBoolean("wifi",wifi).putBoolean("charging",charging).commit();}
    }

    // ---------- Disponibilidad y motivos ----------
    /** Grabación transcrita con voces, corregida, con clave de OpenAI: todo disponible. */
    private static Retranscribe.Facts ready(){
        Retranscribe.Facts f=new Retranscribe.Facts();f.transcribed=true;f.hasKey=true;f.openai=true;f.canSeparate=true;f.diarized=true;f.confirmed=true;
        f.samples=2;f.durationMs=10*60_000;f.bytes=6_000_000;f.parts=2;return f;
    }
    static void availability(){
        for(Retranscribe.Mode m:Retranscribe.Mode.values())check(Retranscribe.reason(ready(),m)==null,"Retranscribe mode unavailable in the ideal case: "+m);
        Retranscribe.Facts f=ready();f.busy=true;check(has(Retranscribe.reason(f,SPEAKERS),"Ya se está transcribiendo"),"Busy recording could be retranscribed");
        f=ready();f.demo=true;check(has(Retranscribe.reason(f,TEXT),"ejemplo"),"Demo could be retranscribed");
        f=ready();f.exists=false;check(has(Retranscribe.reason(f,TEXT),"audio"),"Missing audio not explained");
        f=ready();f.transcribed=false;check(has(Retranscribe.reason(f,SPEAKERS),"«Transcribir»"),"Untranscribed recording not sent to Transcribir");
        f.previous=true;check(has(Retranscribe.reason(f,SPEAKERS),"vuelve a la anterior"),"Unfinished new version not explained");
        // Nueva versión lista y la anterior sin elegir: otra repetición borraría la anterior (solo se guarda una).
        f=ready();f.previous=true;for(Retranscribe.Mode m:Retranscribe.Mode.values())check(Retranscribe.CHOOSE_FIRST.equals(Retranscribe.reason(f,m)),"Retranscribe offered before choosing a version: "+m);
        f=ready();f.noteWorking=true;check(has(Retranscribe.reason(f,TEXT),"nota"),"Retranscribe offered while the note is being made");
        f=ready();f.hasKey=false;check(has(Retranscribe.reason(f,TEXT),"clave"),"Missing key not explained");
        // Segunda pasada con tus correcciones: necesita voces separadas, corregidas o nombradas, OpenAI y tramos limpios.
        f=ready();f.confirmed=false;check(has(Retranscribe.reason(f,CORRECTIONS),"nombra o corrige")&&Retranscribe.reason(f,SPEAKERS)==null,"Second pass allowed without corrections");
        f=ready();f.openai=false;check(has(Retranscribe.reason(f,CORRECTIONS),"OpenAI"),"Second pass allowed with a provider without voice samples");
        f=ready();f.diarized=false;check(has(Retranscribe.reason(f,CORRECTIONS),"voces separadas"),"Second pass allowed on a text-only version");
        f=ready();f.samples=0;check(has(Retranscribe.reason(f,CORRECTIONS),"muestra"),"Second pass allowed without clean samples");
        // Proveedor propio que no separa voces: solo el texto.
        f=ready();f.openai=false;f.canSeparate=false;
        check(has(Retranscribe.reason(f,SPEAKERS),"no separa voces")&&has(Retranscribe.reason(f,SINGLE),"no separa voces")&&Retranscribe.reason(f,TEXT)==null,"Custom provider limits wrong");
        check(!Retranscribe.speakers(TEXT)&&Retranscribe.speakers(SPEAKERS)&&Retranscribe.speakers(SINGLE)&&Retranscribe.speakers(CORRECTIONS),"Speaker choice per mode wrong");
        for(Retranscribe.Mode m:Retranscribe.Mode.values())check(!Retranscribe.label(m).isEmpty(),"Mode without a label: "+m);
    }

    // ---------- Sin cortar (un solo envío) ----------
    static void sizing(){
        Retranscribe.Facts f=ready();f.durationMs=Retranscribe.SINGLE_MAX_MS;check(Retranscribe.reason(f,SINGLE)==null,"23-minute audio rejected for a single request");
        f.durationMs=Retranscribe.SINGLE_MAX_MS+1000;check(has(Retranscribe.reason(f,SINGLE),"23 min"),"Single request allowed over 23 minutes");
        f=ready();f.bytes=30_000_000;check(has(Retranscribe.reason(f,SINGLE),"24 MB"),"Single request allowed over the size limit");
        f=ready();f.parts=1;check(has(Retranscribe.reason(f,SINGLE),"sin cortar"),"Single request offered when the version already was one piece");
        f.diarized=false;check(Retranscribe.reason(f,SINGLE)==null,"Single request refused for a text-only version");
        check(Retranscribe.fitsSingle(1_380_000,10_000_000)&&!Retranscribe.fitsSingle(1_381_000,10_000_000)&&!Retranscribe.fitsSingle(600_000,25_000_000)&&!Retranscribe.fitsSingle(0,1000),"Single-request sizing wrong");
        // La tarea de fondo (corte de Android a los 10 min) no manda un envío de 20 min; sí las partes normales de hasta 12 min.
        check(Transcriber.sendEstimate(20*60_000)>Transcriber.JOB_SEND_LIMIT_MS&&Transcriber.sendEstimate(Transcriber.SPEAKER_BLOCK_MAX_MS)<=Transcriber.JOB_SEND_LIMIT_MS,"Background job send limit wrong");
        // Cada intento cambia el perfil (las partes de otro intento no se reutilizan); sin repetir, el perfil no cambia
        // (una transcripción en curso al actualizar la app no pierde las partes ya listas).
        check(Transcriber.profile("base",0,null).equals("base"),"Profile changed for normal transcriptions");
        check(!Transcriber.profile("base",1,SPEAKERS).equals(Transcriber.profile("base",2,SPEAKERS))&&!Transcriber.profile("base",1,SINGLE).equals(Transcriber.profile("base",1,SPEAKERS)),"Retranscribe attempt not in the profile");
    }

    // ---------- Servidor propio sin dirección ----------
    /** Sin dirección no hay configuración: nunca una dirección por defecto a la que irían la clave y el audio. */
    static void server(Context c)throws Exception{
        Settings s=new Settings(c);String provider=s.prefs.getString("provider",null),base=s.prefs.getString("customBase",null);
        try{
            s.prefs.edit().putString("provider","custom").remove("customBase").commit();
            String why=null;try{s.config(false);}catch(HttpApi.UserAction e){why=e.getMessage();}
            check(Settings.NO_SERVER.equals(why)&&s.needsServer(),"Custom provider without a server address would send the key somewhere");
            s.prefs.edit().putString("customBase","https://transcribe.test/v1").commit();
            check(!s.needsServer()&&s.config(false).base.equals("https://transcribe.test/v1"),"Custom server address not used");
        }finally{
            android.content.SharedPreferences.Editor e=s.prefs.edit();
            if(provider==null)e.remove("provider");else e.putString("provider",provider);
            if(base==null)e.remove("customBase");else e.putString("customBase",base);
            e.commit();
        }
    }

    // ---------- Segunda pasada: muestras y nombres ----------
    static void corrections()throws Exception{
        // La Fran (nombrada), una voz sin nombre y el usuario (voice:me, nombrado).
        JSONArray segs=new JSONArray()
            .put(seg(Voices.ME,0,8,"hola esto es una prueba larga de mi voz"))
            .put(seg("block0:B",20,28,"la persona sin nombre habla un rato largo"))
            .put(seg("block0:A",40,48,"la fran habla tranquila un rato bastante largo"))
            .put(seg(Voices.ME,55,63,"otra vez hablo yo con calma y sin apuro"))
            .put(seg("block0:B",70,77,"otra vez la persona sin nombre habla aquí"))
            .put(seg("block0:A",90,97,"otra vez la fran habla aquí muy tranquila"));
        Transcript t=new Transcript(new JSONObject().put("diarized",true).put("reviewed",true).put("parts",2)
            .put("names",new JSONObject().put("block0:A","Fran").put(Voices.ME,"Konrad")).put("segments",segs));
        // Con «Mi voz»: la voz del usuario no necesita muestra; máx. 3 más; primero las nombradas.
        JSONArray fixed=Retranscribe.pickFixed(t,"Konrad");
        check(fixed.length()==3,"Second pass should use 3 samples next to my voice, got "+fixed.length());
        check(fixed.getJSONObject(0).getString("id").equals("block0:A")&&fixed.getJSONObject(0).getString("name").equals("Fran"),"Named voice not preferred");
        for(int i=0;i<fixed.length();i++)check(!fixed.getJSONObject(i).getString("id").equals(Voices.ME),"My voice sampled although «Mi voz» exists");
        // Sin «Mi voz»: también se toma muestra del usuario; las nombradas primero, la sin nombre al final.
        JSONArray all=Retranscribe.pickFixed(t,null);
        check(all.length()==3&&all.getJSONObject(2).getString("id").equals("block0:B")&&all.getJSONObject(2).getString("name").isEmpty(),"Samples without my voice wrong");
        Set<String> named=new HashSet<>(Arrays.asList(all.getJSONObject(0).getString("id"),all.getJSONObject(1).getString("id")));
        check(named.contains(Voices.ME)&&named.contains("block0:A"),"Named voices not first");
        // Un nombre igual al de «Mi voz» en otra voz también es el usuario.
        Transcript other=new Transcript(new JSONObject(t.data.toString()));other.data.getJSONObject("names").put("block0:B","konrad");
        JSONArray skip=Retranscribe.pickFixed(other,"Konrad");for(int i=0;i<skip.length();i++)check(!skip.getJSONObject(i).getString("id").equals("block0:B"),"Voice named like me was sampled");
        check(Retranscribe.pickFixed(new Transcript(new JSONObject().put("diarized",false).put("segments",segs)),null).length()==0,"Samples from a text-only version");
        check(Retranscribe.who(fixed).startsWith("Fran"),"Sample summary wrong");

        // Nombres enviados: únicos y distintos de las letras del modelo.
        List<String[]> plan=Transcriber.fixedPlan(fixed,true);
        check(plan.size()==3&&plan.get(0)[0].equals("voz_1")&&plan.get(0)[1].equals("block0:A")&&plan.get(0)[2].equals("Fran")&&plan.get(1)[0].equals("voz_2")&&plan.get(2)[0].endsWith("b"),"Sent sample names wrong");
        JSONArray withMe=new JSONArray().put(new JSONObject().put("id",Voices.ME).put("start",0).put("end",5)).put(new JSONObject().put("id","block0:A").put("name","Fran").put("start",40).put("end",47));
        check(Transcriber.fixedPlan(withMe,true).size()==1&&Transcriber.fixedPlan(withMe,false).size()==2,"My voice sampled twice");
        JSONArray many=new JSONArray();for(int i=0;i<6;i++)many.put(new JSONObject().put("id","block0:"+(char)('A'+i)).put("start",i*10).put("end",i*10+5));
        check(Transcriber.fixedPlan(many,true).size()==Transcriber.MAX_KNOWN-1&&Transcriber.fixedPlan(many,false).size()==Transcriber.MAX_KNOWN,"More than 4 known voices sent");

        // Respuesta del modelo: las voces reconocidas vuelven con el MISMO id de antes; una voz nueva "A" no se pega a la "A" anterior.
        JSONObject known=new JSONObject();for(String[] p:plan)known.put(p[0],p[1]);known.put(Voices.MINE,Voices.ME);
        JSONObject one=new JSONObject().put("_known",known).put("_prefix","pass1:").put("segments",new JSONArray()
            .put(seg("voz_1",0,5,"Hola, soy la Fran")).put(seg("A",5,9,"Yo soy nueva")).put(seg("voz_2",9,12,"Y yo sigo")).put(seg(Voices.MINE,12,15,"Y yo soy Konrad")));
        Transcript fresh=Transcript.fromParts(Collections.singletonList(one),Collections.singletonList(0d));JSONArray s=fresh.segments();
        check(s.getJSONObject(0).getString("speaker").equals("block0:A")&&s.getJSONObject(1).getString("speaker").equals("pass1:A")
            &&s.getJSONObject(2).getString("speaker").equals("block0:B")&&s.getJSONObject(3).getString("speaker").equals(Voices.ME),"Second pass ids not mapped to the previous ones");
        check(fresh.speakers().size()==4,"New voice merged with a known one");
        Transcriber.prefill(fresh,fixed);
        check(fresh.speakers().get("block0:A").equals("Fran")&&fresh.speakers().get("block0:B").startsWith("Persona ")&&!fresh.reviewed(),"Names not prefilled from the previous version");
        // En varias partes: todas reconocen desde el inicio y las voces nuevas de cada parte siguen separadas.
        JSONObject two=new JSONObject().put("_known",known).put("_prefix","pass1:").put("segments",new JSONArray().put(seg("voz_1",0,4,"Sigo yo")).put(seg("A",4,8,"Otra nueva")));
        Transcript joined=Transcript.fromParts(Arrays.asList(new JSONObject(one.toString()),two),Arrays.asList(0d,600d));JSONArray j=joined.segments();
        check(j.getJSONObject(1).getString("speaker").equals("pass1:block0:A")&&j.getJSONObject(4).getString("speaker").equals("block0:A")&&j.getJSONObject(5).getString("speaker").equals("pass1:block1:A"),"Multi-part second pass ids wrong");
    }

    // ---------- Versión anterior: conservar, volver, quedarse, cancelar ----------
    private static Transcript version(String text)throws Exception{
        JSONObject response=new JSONObject().put("segments",new JSONArray().put(seg("A",0,3,text)).put(seg("B",3,6,"Respuesta de otra persona")));
        return Transcript.fromParts(Collections.singletonList(response),Collections.singletonList(0d));
    }
    private static String first(Context c,String id)throws Exception{return Transcript.load(c,id).segments().getJSONObject(0).getString("text");}
    static void versions(Context c,Recording d)throws Exception{
        String id=d.id;File prev=FilesStore.file(c,id,".transcript.prev.json"),note=FilesStore.file(c,id,".note.json"),notePrev=FilesStore.file(c,id,".note.prev.json");
        boolean refused=false;try{Retranscribe.start(c,d,SPEAKERS);}catch(HttpApi.UserAction e){refused=true;}
        check(refused&&!Retranscribe.hasPrevious(c,id),"Retranscribing an untranscribed recording was allowed");
        version("Versión uno").save(c,id);
        FilesStore.write(note,new JSONObject().put("version",1).put("title","Nota uno"));
        FilesStore.update(c,id,s->s.put("noteState","ready").put("suggestedTitle","Título uno").put("cuts",new JSONArray().put(0).put(1000)).put("model","modelo-uno"));
        FilesStore.write(FilesStore.file(c,id,".part0.json"),new JSONObject().put("segments",new JSONArray()));
        File block=new File(AudioParts.blockDir(c,id),"block-0.m4a");check(block.createNewFile()||block.exists(),"Test block not created");

        Retranscribe.prepare(c,d,SPEAKERS,null);JSONObject st=FilesStore.state(c,id);
        check(!Transcript.exists(c,id)&&prev.isFile()&&notePrev.isFile()&&!note.isFile()&&Retranscribe.hasPrevious(c,id),"Previous version not kept aside");
        check(!FilesStore.file(c,id,".part0.json").exists()&&!block.exists(),"Old part checkpoints or blocks would be reused");
        check(st.optInt("attempt")==1&&Retranscribe.mode(st)==SPEAKERS&&!st.has("noteState")&&!st.has("cuts")&&!st.has("snippet")&&!st.has("fixedRefs"),"Retranscribe state wrong: "+st);
        check(has(Retranscribe.reason(c,d,SPEAKERS),"anterior"),"Unfinished new version not explained on the device");

        // Llega la nueva versión (con su nota) y el usuario vuelve a la anterior.
        version("Versión dos").save(c,id);FilesStore.write(note,new JSONObject().put("version",1).put("title","Nota dos"));
        FilesStore.update(c,id,s->s.put("model","modelo-dos").put("noteState","ready").put("suggestedTitle","Título dos"));
        Retranscribe.restorePrevious(c,id);st=FilesStore.state(c,id);
        check(first(c,id).equals("Versión uno"),"Previous transcript not restored");
        check(FilesStore.read(note).optString("title").equals("Nota uno"),"Previous note not restored");
        check(!prev.isFile()&&!notePrev.isFile()&&!Retranscribe.hasPrevious(c,id)&&!st.has("retranscribe"),"Previous files or state left after restoring");
        check(st.optString("suggestedTitle").equals("Título uno")&&st.optString("model").equals("modelo-uno")&&st.optJSONArray("cuts")!=null,"Process data of the previous version not restored");
        check(has(st.optString("snippet"),"Versión uno"),"Snippet not refreshed after restoring");

        // Quedarse con la nueva (sin nota nueva: la nota anterior no reaparece). Una nota «armándose» no se guarda como parte
        // de la versión anterior (volvería como un «Armando la nota…» sin fin).
        FilesStore.update(c,id,s->s.put("noteState","working").put("noteStartedAt",System.currentTimeMillis()));
        Retranscribe.prepare(c,d,TEXT,null);st=FilesStore.state(c,id);check(st.optInt("attempt")==2,"Attempt counter not bumped");
        check(!st.getJSONObject("retranscribe").getJSONObject("before").has("noteState")&&!st.has("noteStartedAt"),"A note in progress was kept with the previous version: "+st);
        Retranscribe.keepNew(c,id);check(Retranscribe.hasPrevious(c,id)&&!Transcript.exists(c,id),"keepNew without a new version deleted the only transcript");
        version("Versión tres").save(c,id);
        Retranscribe.keepNew(c,id);st=FilesStore.state(c,id);
        check(first(c,id).equals("Versión tres")&&!prev.isFile()&&!notePrev.isFile()&&!note.isFile()&&!st.has("retranscribe"),"keepNew left the previous version behind");

        // Segunda pasada: las muestras elegidas quedan en el estado; cancelar antes de terminar devuelve la anterior sola.
        JSONArray fixed=new JSONArray().put(new JSONObject().put("id","A").put("name","Fran").put("label","Fran").put("start",0.4).put("end",2.6));
        Retranscribe.prepare(c,d,CORRECTIONS,fixed);st=FilesStore.state(c,id);
        check(Retranscribe.mode(st)==CORRECTIONS&&st.optJSONArray("fixedRefs")!=null&&st.getJSONArray("fixedRefs").length()==1,"Second-pass samples not stored");
        Pipeline.cancel(c,id);st=FilesStore.state(c,id);
        check(Transcript.exists(c,id)&&first(c,id).equals("Versión tres")&&!Retranscribe.hasPrevious(c,id)&&!st.has("retranscribe")&&!st.has("fixedRefs")&&!st.optBoolean("requested"),"Cancelled retranscription did not bring the previous version back");
    }

    // ---------- Comienzo guardado ("snippet") ----------
    static void snippet(Context c,Recording d)throws Exception{
        Transcript t=version("Hola, esta es la prueba del comienzo");t.save(c,d.id);
        String saved=FilesStore.state(c,d.id).optString("snippet");
        check(!saved.isEmpty()&&saved.equals(t.snippet(Transcript.SNIPPET))&&saved.startsWith("Persona 1: Hola"),"Saving a transcript did not store its snippet: "+saved);
        Map<String,String> names=new HashMap<>();names.put("A","Fran");Transcript.rename(c,d.id,names);
        check(FilesStore.state(c,d.id).optString("snippet").startsWith("Fran: Hola"),"Snippet not updated after naming voices");
        FilesStore.update(c,d.id,s->s.remove("snippet"));
        check(Transcript.snippetOf(c,d.id).startsWith("Fran: Hola")&&FilesStore.state(c,d.id).has("snippet"),"Snippet not rebuilt for an older transcript");
    }

    // ---------- «Transcripción lista» ----------
    static void notification(Context c,Recording d)throws Exception{
        version("Hola").save(c,d.id);
        Notification n=Transcriber.buildDone(c,d,false);
        check(Transcriber.DONE_CHANNEL.equals(n.getChannelId())&&c.getSystemService(NotificationManager.class).getNotificationChannel(Transcriber.DONE_CHANNEL)!=null,"Ready notification not on its own channel");
        check("Transcripción lista".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))&&n.contentIntent!=null,"Ready notification title or tap wrong");
        check(n.actions!=null&&n.actions.length==1&&"Revisar voces".equals(n.actions[0].title.toString()),"Unreviewed voices should offer «Revisar voces»");
        Map<String,String> names=new HashMap<>();names.put("A","Fran");names.put("B","Konrad");Transcript.rename(c,d.id,names);
        Settings settings=new Settings(c);String tree=settings.prefs.getString("saveTree",null),name=settings.prefs.getString("saveTreeName",null);
        try{
            settings.prefs.edit().putString("saveTree","content://cl.vozlocal.test/tree").putString("saveTreeName","0-Inbox").commit();
            n=Transcriber.buildDone(c,d,true);
            check("Nueva versión lista".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE))),"Retranscription ready title wrong");
            saveAction(c,n);
            settings.prefs.edit().remove("saveTree").commit();
            n=Transcriber.buildDone(c,d,false);saveAction(c,n);
        }finally{
            android.content.SharedPreferences.Editor e=settings.prefs.edit();
            if(tree==null)e.remove("saveTree");else e.putString("saveTree",tree);
            if(name==null)e.remove("saveTreeName");else e.putString("saveTreeName",name);
            e.commit();
        }
        check(Transcriber.doneId(d.id)!=Transcriber.NOTIFICATION&&Transcriber.doneId(d.id)>=1000,"Ready notification id collides with progress");
    }
    /** Voces ya revisadas: «Guardar en <carpeta>» si la carpeta rápida está lista (según Inbox); si no, ninguna acción. */
    private static void saveAction(Context c,Notification n){
        if(Inbox.configured(c))check(n.actions!=null&&n.actions.length==1&&("Guardar en "+Inbox.folderName(c)).equals(n.actions[0].title.toString()),"Reviewed voices should offer saving to the quick folder");
        else check(n.actions==null||n.actions.length==0,"Save action offered without a quick folder");
    }

    // ---------- Grabación desechable ----------
    private static Recording copy(Context c,Recording r)throws Exception{
        Recording d=new Recording(UUID.randomUUID().toString(),"Prueba volver a transcribir",System.currentTimeMillis(),r.duration);
        java.nio.file.Files.copy(r.audio(c).toPath(),d.audio(c).toPath());d.save(c);return d;
    }
    /** Eliminar borra también la versión anterior, las notas, la onda y las partes. */
    private static void deleteAll(Context c,Recording d)throws Exception{
        String[] extra={".transcript.prev.json",".note.json",".note.prev.json",".wave.json",".part3.json"};
        for(String suffix:extra)FilesStore.write(FilesStore.file(c,d.id,suffix),new JSONObject().put("test",true));
        check(d.delete(c),"Disposable recording not deleted");
        for(String suffix:Recording.FILES)check(!new File(Recording.directory(c),d.id+suffix).exists(),"Left behind after delete: "+suffix);
        for(String suffix:extra)check(!new File(Recording.directory(c),d.id+suffix).exists(),"Left behind after delete: "+suffix);
    }
}
