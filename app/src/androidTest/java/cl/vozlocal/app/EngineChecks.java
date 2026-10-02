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
        waits(c);
        languages(c);
        stopFromNotification(c,r);
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
        // ¿Lo único pedido espera Wi-Fi? Se mira en vivo: si entretanto se permitieron los datos móviles (o volvió el Wi-Fi),
        // el trabajo en curso hace otra vuelta en vez de cederle la grabación a la tarea de fondo.
        Set<String> none=Collections.emptySet(),a=Collections.singleton("A");List<String> onlyA=Collections.singletonList("A");
        check(Transcriber.onlyWaitingWifi(onlyA,a,none,id->true)&&Transcriber.onlyWaitingWifi(Arrays.asList("A","F"),a,Collections.singleton("F"),id->true),"Only Wi-Fi waits left, but the worker keeps retrying");
        check(!Transcriber.onlyWaitingWifi(onlyA,a,none,id->false),"Mobile data allowed while another recording was sent, but the worker gave the recording to the background job");
        check(!Transcriber.onlyWaitingWifi(Arrays.asList("A","B"),a,none,id->true)&&!Transcriber.onlyWaitingWifi(onlyA,none,none,id->true),"Worker stopped with another recording still to send");
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
            // Se fue el Wi-Fi a mitad de la grabación: no cuenta como «solo queda esperar Wi-Fi», así que el servicio o la
            // transferencia iniciada por el usuario esperan ahí (TranscribeService.waitBeforeRetry) en vez de cederla a la tarea
            // de fondo que Android pausa. Ceder de inmediato queda solo para la que esperaba Wi-Fi al comenzar la ronda.
            Transcriber t=new Transcriber(c,new HttpApi(),0);t.waitWifi(d,true);Pipeline.clearWaitingWifi(c,d.id);
            // waitWifi a mitad también pone la notificación de avance en «Esperando Wi-Fi»: la prueba no la deja puesta.
            if(!Pipeline.working())c.getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);
            check(t.lostWifi&&!t.onlyWaitingWifi(),"Wi-Fi lost midway, but the worker hands the recording to the background job");
            // Queda para reintentar en el mismo trabajo: si vuelve el Wi-Fi durante la espera, las pantallas la ven en proceso.
            check(t.retrying().contains(d.id),"Wi-Fi lost midway, but the recording is not held for the retry");
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
                    &&job.getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_UNMETERED&&job.getEstimatedNetworkUploadBytes()==6_400_000&&job.isPersisted()==persisted,
                    "User-initiated transfer job wrong: userInitiated="+job.isUserInitiated()+" id="+job.getId()+" priority="+job.getPriority()+" network="+job.getNetworkType()+" upload="+job.getEstimatedNetworkUploadBytes()+" persisted="+job.isPersisted()+"/"+persisted);
                check(Pipeline.userJobInfo(c,s,true,0,false).getNetworkType()==android.app.job.JobInfo.NETWORK_TYPE_ANY,"User-initiated transfer waits for Wi-Fi although mobile data was allowed");
                // El primer plano le cede el turno solo si podría empezar ya (Pipeline.userJobFresh): con datos móviles, una
                // que exige Wi-Fi no empieza, y cederle el turno dejaba todo quieto.
                check(Pipeline.couldStart(job,true,false)&&!Pipeline.couldStart(job,false,true),"User-initiated transfer waiting for Wi-Fi treated as ready on mobile data");
            }
            // La misma regla con el cargador, en la tarea de fondo (cualquier Android).
            check(Pipeline.couldStart(Pipeline.jobInfo(c,s,true),false,false)&&!Pipeline.couldStart(Pipeline.jobInfo(c,s),false,true),"Job network constraint not read");
            // Android detuvo la transferencia por el Wi-Fi que ya no se pide («Wi-Fi y datos móviles», o datos móviles permitidos
            // a una grabación): la retoma la tarea de fondo con los ajustes de ahora. Si exigía cualquier red (se perdió toda
            // conexión), o los ajustes siguen pidiendo Wi-Fi, no.
            int net=android.app.job.JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY,power=android.app.job.JobParameters.STOP_REASON_CONSTRAINT_CHARGING;
            android.app.job.JobInfo wifiJob=Pipeline.jobInfo(c,s),anyJob=Pipeline.jobInfo(c,s,true);
            check(PipelineJob.staleStop(net,wifiJob,false,false)&&!PipelineJob.staleStop(net,wifiJob,true,false),"Transfer stopped for Wi-Fi no longer required not handed over");
            check(!PipelineJob.staleStop(net,anyJob,false,false)&&!PipelineJob.staleStop(power,anyJob,false,false),"Transfer stopped for a condition it never required treated as stale");
            check(PipelineJob.staleStop(net,null,false,false)&&!PipelineJob.staleStop(net,null,true,false)&&!PipelineJob.staleStop(android.app.job.JobParameters.STOP_REASON_TIMEOUT,wifiJob,false,false),"Stale stop without the job, or for another reason, wrong");
            s.prefs.edit().putBoolean("charging",true).commit();android.app.job.JobInfo plugged=Pipeline.jobInfo(c,s,true);
            check(!Pipeline.couldStart(plugged,true,false)&&Pipeline.couldStart(plugged,false,true),"Job charger constraint not read");
            // Lo mismo con «Solo mientras carga» quitado mientras trabajaba.
            check(PipelineJob.staleStop(power,plugged,false,false)&&!PipelineJob.staleStop(power,plugged,false,true),"Transfer stopped for a charger no longer required not handed over");
        }finally{s.prefs.edit().putBoolean("wifi",wifi).putBoolean("charging",charging).commit();}
    }

    // ---------- 0.8.0, revisión r4: lo que se dice mientras se espera ----------
    private static String title(Notification n){return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE));}
    static void waits(Context c){
        // Titular al pedir: lo que espera, o su turno detrás de la que se transcribe (como el aviso y el botón).
        check(Pipeline.queuedLine(null,false).equals("En cola · empezando")&&Pipeline.queuedLine(null,true).equals("En cola · empieza cuando termine la transcripción en curso")
            &&Pipeline.queuedLine("esperando que conectes el cargador",true).equals("En cola · esperando que conectes el cargador"),"Queued headline wrong");
        // «Reintento en…» solo en la que la ronda dejó para reintentar y sigue lista; nunca en una recién pedida.
        check("A".equals(TranscribeService.retryTarget(Arrays.asList("A","B"),id->true))&&"B".equals(TranscribeService.retryTarget(Arrays.asList("A","B"),"B"::equals))
            &&TranscribeService.retryTarget(Collections.<String>emptyList(),id->true)==null&&TranscribeService.retryTarget(Collections.singletonList("A"),id->false)==null,"Retry target wrong");
        // Aviso «Esperando Wi-Fi» al pasar a «Solo con Wi-Fi»: nunca para la que se está enviando (su parte en curso termina
        // igual y el aviso quedaba en una grabación ya transcrita); sí para la siguiente que espera.
        List<String> ids=Arrays.asList("A","B","C");
        check("B".equals(Pipeline.wifiNoticeFor(ids,"A",id->true))&&"A".equals(Pipeline.wifiNoticeFor(ids,null,id->true))&&"C".equals(Pipeline.wifiNoticeFor(ids,"A",id->!id.equals("B")))
            &&Pipeline.wifiNoticeFor(Collections.singletonList("A"),"A",id->true)==null&&Pipeline.wifiNoticeFor(ids,null,id->false)==null,"Wi-Fi notice target wrong");
        // La ronda se detiene por el cargador, la batería o internet (sin gastar un intento); no por el Wi-Fi, que es de cada
        // grabación, ni en la tarea de fondo, a la que Android ya retiene por eso mismo.
        check(Transcriber.holds("esperando que conectes el cargador",0)&&Transcriber.holds("esperando conexión a internet",0)&&Transcriber.holds("batería baja: Android espera a que cargues",0),"Round not held for the charger, the battery or the network");
        check(!Transcriber.holds(Pipeline.wifiWait(),0)&&!Transcriber.holds(null,0)&&!Transcriber.holds("esperando que conectes el cargador",Transcriber.JOB_BUDGET_MS),"Round held for Wi-Fi, for nothing, or in the background job");
        // Una espera no se titula «Transcribiendo» ni lleva la barra ocupada; un envío sí.
        Notification wait=Transcriber.build(c,Transcriber.wifiWaitText(),true,-1),send=Transcriber.build(c,"Enviando parte 1 de 3",true,40);
        check("En pausa".equals(title(wait))&&wait.extras.getInt(Notification.EXTRA_PROGRESS_MAX)==0&&!wait.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),"Waiting notification titled as working or with a busy bar");
        check("Transcribiendo".equals(title(send))&&send.extras.getInt(Notification.EXTRA_PROGRESS)==40&&send.extras.getInt(Notification.EXTRA_PROGRESS_MAX)==100,"Progress notification lost its title or bar");
        check(Transcriber.waiting("Intento 2 de 5 no resultó · se reintenta solo")&&Transcriber.waiting("Esperando que conectes el cargador · se retoma sola al cumplirse")
            &&!Transcriber.waiting("Preparando…")&&!Transcriber.waiting("Transcribiendo · 2 de 3 partes listas")&&!Transcriber.waiting("Enviando parte 1 de 3"),"Waiting notification texts wrong");
    }

    // ---------- 0.9.0: los textos del motor en los tres idiomas ----------
    private static JSONObject line(long t,String m)throws JSONException{return new JSONObject().put("t",t).put("m",m);}
    /** El mensaje de HttpApi.require para ese código (null si no lanza un UserAction). */
    private static String keyError(String service,int code){try{HttpApi.require(new HttpApi.Response(code,"",null),service);return null;}catch(HttpApi.UserAction e){return e.getMessage();}catch(Exception e){return null;}}
    /** El mensaje de un error que se reintenta (IOException) de HttpApi.require. */
    private static String retryError(String service,int code){try{HttpApi.require(new HttpApi.Response(code,"",null),service);return null;}catch(java.io.IOException e){return e.getMessage();}catch(Exception e){return null;}}
    /** El mensaje de Models.checkKey cuando OpenRouter responde ese código (sin red: un HttpApi falso). */
    private static String checkKeyError(int code){
        HttpApi fake=new HttpApi(){@Override Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra){return new Response(code,"{}",null);}};
        try{Models.checkKey(fake,"sk-or-prueba-no-es-una-clave-real");return null;}catch(Exception e){return e.getMessage();}
    }
    /**
     * El motor escribe en el idioma de la app y StatusText (y Transcriber.waiting) lo vuelve a leer en cualquiera de los
     * tres: lo guardado (la bitácora, el estado) puede venir de antes de cambiar el idioma. En cada idioma se arman los
     * textos con los mismos recursos que usa el motor y se leen de vuelta; con la app en inglés se lee una bitácora escrita
     * en español, y al revés. Siempre vuelve al español, el idioma de las demás pruebas.
     */
    static void languages(Context c)throws Exception{
        // Español: el texto de siempre, letra por letra.
        check("Esperando Wi-Fi · se retoma sola cuando vuelva".equals(Transcriber.wifiWaitText())&&"No queda espacio en el teléfono para preparar el audio. Libera espacio y pulsa Reintentar.".equals(OrAudio.noSpace())
            &&"Android agotó el tiempo que le da a la app en segundo plano".equals(PipelineJob.stopReason(android.app.job.JobParameters.STOP_REASON_QUOTA))&&"≈6,4 MB".equals(Pipeline.megabytes(6_400_000)),"Spanish engine texts changed");
        check("1 voz".equals(Lang.plural(c,R.plurals.eng_voices,1))&&"3 voces".equals(Lang.plural(c,R.plurals.eng_voices,3))&&"★ 2 momentos marcados".equals(Lang.plural(c,R.plurals.eng_rec_marks,2)),"Spanish plurals wrong");
        String es401=keyError(HttpApi.OPENROUTER,401);
        check("La clave del proveedor no es válida o fue revocada. Revísala en Ajustes.".equals(es401)&&"La clave de OpenRouter no es válida o fue revocada. Revísala en Ajustes.".equals(checkKeyError(401)),"Spanish key errors changed: "+es401);
        try{
            for(String lang:new String[]{Lang.EN,Lang.PT,Lang.ES}){Lang.override(lang);readBack(c,lang);}
            // Una bitácora escrita en español, leída con la app en inglés.
            Lang.override(Lang.EN);
            check("Parte 2 de 3 lista · reconoció 2 voces".equals(StatusText.human("Parte 2 de 3 lista · tardó 01:23 · reconoció 2 voces"))&&StatusText.transcriptionDone("Transcripción lista · tiempo total 05:12")
                &&"En espera de Wi-Fi · ahora hay datos móviles".equals(StatusText.inDetail("En espera de Wi-Fi · ahora hay datos móviles: puedes usarlos para esta grabación desde su detalle")),"Spanish log not read with the app in English");
            check(Transcriber.waiting("Intento 2 de 5 no resultó · se reintenta solo")&&Transcriber.waiting("Esperando que conectes el cargador · se retoma sola al cumplirse")&&!Transcriber.waiting("Enviando parte 1 de 3")
                &&Lang.str(c,R.string.eng_btn_wait_charger).equals(StatusText.working(false,false,"esperando que conectes el cargador")),"Spanish waits not read with the app in English");
            JSONObject es=new JSONObject().put("log",new JSONArray().put(line(1,"En cola · esperando que conectes el cargador")).put(line(2,"En pausa: batería baja: Android espera a que cargues · se retoma sola al cumplirse"))
                .put(line(3,"Preparando audio")).put(line(4,"Audio enviado · OpenRouter está transcribiendo")));
            check(StatusText.startedAt(es)==3&&StatusText.uploadedInLog(es)&&StatusText.preparing("Preparando el audio de la parte 1 de 2")&&StatusText.aboutKey(es401)
                &&"La clave del proveedor no es válida o fue revocada.".equals(StatusText.withoutSettingsHint(es401)),"Spanish state not read with the app in English");
        }finally{Lang.override(Lang.ES);}
        // Y al revés: líneas en inglés y en portugués, con la app en español.
        check("Part 2 of 3 ready".equals(StatusText.human("Part 2 of 3 ready · took 01:23"))&&StatusText.transcriptionDone("Transcript ready · total time 05:12")&&StatusText.transcriptionDone("Transcrição pronta · tempo total 05:12")
            &&Transcriber.waiting("Attempt 2 of 5 didn't work · retrying automatically")&&Transcriber.waiting("Aguardando Wi-Fi · retoma sozinha quando voltar"),"English or Portuguese log not read with the app in Spanish");
    }
    /** Con la app en ese idioma: los textos del motor, armados con sus mismos recursos, se leen de vuelta. */
    private static void readBack(Context c,String lang)throws Exception{
        String wifi=Pipeline.wifiWait(),charger=Lang.str(c,R.string.eng_wait_charger),internet=Lang.str(c,R.string.eng_wait_internet),battery=Lang.str(c,R.string.eng_wait_battery),word=Lang.str(c,R.string.eng_waiting_word)+" ";
        // El botón dice qué espera; la nota del detalle y la Biblioteca, sin la palabra de espera ni el paréntesis.
        check(Lang.str(c,R.string.eng_btn_wait_wifi).equals(StatusText.working(false,false,wifi))&&Lang.str(c,R.string.eng_btn_wait_charger).equals(StatusText.working(false,false,charger))
            &&Lang.str(c,R.string.eng_btn_wait_connection).equals(StatusText.working(false,false,internet))&&Lang.str(c,R.string.eng_btn_battery_low).equals(StatusText.working(true,false,battery))
            &&Lang.str(c,R.string.eng_btn_queued).equals(StatusText.working(false,false,null))&&Lang.str(c,R.string.eng_btn_working_again).equals(StatusText.working(true,true,wifi)),lang+": waiting button wrong");
        for(String b:new String[]{wifi,charger,internet,battery}){
            String note=StatusText.waitingNote(false,true,true,true,b),queued=StatusText.queuedLine(b);
            check(!note.contains("(")&&!note.contains(word)&&!queued.contains("(")&&queued.startsWith(Lang.str(c,R.string.eng_queued)),lang+": wait not simplified: «"+note+"» «"+queued+"»");
        }
        check(Transcriber.holds(charger,0)&&Transcriber.holds(battery,0)&&Transcriber.holds(internet,0)&&!Transcriber.holds(wifi,0),lang+": round held for the wrong waits");
        // La bitácora: el titular salta los detalles técnicos (tardó, en paralelo, tiempo total) y lo que manda al detalle.
        String took=Lang.str(c,R.string.eng_frag_took,"01:23"),voices=" · "+Lang.plural(c,R.plurals.eng_voices,2),ready=Lang.str(c,R.string.eng_log_part_ready,2,3,took,voices),answer=Lang.str(c,R.string.eng_log_answer,took,"");
        check((ready.substring(0,ready.indexOf(" · "))+voices).equals(StatusText.human(ready))&&answer.substring(0,answer.indexOf(" · ")).equals(StatusText.human(answer)),lang+": part line not simplified: "+StatusText.human(ready));
        String done=Lang.str(c,R.string.eng_log_done,Lang.str(c,R.string.eng_frag_total,"05:12")),split=Lang.str(c,R.string.eng_log_split,"26:00",3,8,Lang.str(c,R.string.eng_frag_parallel,3));
        check(Lang.str(c,R.string.eng_done_title).equals(StatusText.human(done))&&StatusText.transcriptionDone(done)&&split.substring(0,split.lastIndexOf(" · ")).equals(StatusText.human(split)),lang+": done or split line not simplified: "+StatusText.human(split));
        // «bloque» → «parte» solo en bitácoras viejas y en palabras completas: «pantalla bloqueada» no se toca.
        String frozen=Lang.str(c,R.string.eng_log_frozen,"01:23"),locked=Lang.str(c,R.string.eng_log_continues_foreground);
        check(frozen.equals(StatusText.human(frozen))&&locked.equals(StatusText.human(locked))&&"Parte 2 de 3 lista".equals(StatusText.human("Bloque 2 de 3 listo · tardó 01:23")),lang+": headline rewrote a word it should keep: "+StatusText.human(frozen)+" / "+StatusText.human(locked));
        String hint=Lang.str(c,R.string.eng_log_mobile_hint),waiting=Lang.str(c,R.string.eng_log_wifi_wait,hint),lost=Lang.str(c,R.string.eng_log_wifi_lost,hint);
        check(Lang.str(c,R.string.eng_log_wifi_wait,"").equals(StatusText.inDetail(waiting))&&Lang.str(c,R.string.eng_log_wifi_lost,"").equals(StatusText.inDetail(lost))
            &&Lang.str(c,R.string.eng_log_paused,wifi.substring(0,wifi.indexOf(" ("))).equals(StatusText.inDetail(Lang.str(c,R.string.eng_log_paused,wifi))),lang+": Wi-Fi details not removed: "+StatusText.inDetail(waiting));
        // Estado y bitácora guardados: cuándo empezó a trabajar de verdad, si ya se envió algo, si se prepara el audio.
        JSONObject st=new JSONObject().put("queuedAt",0).put("log",new JSONArray().put(line(1,Pipeline.queuedLine(charger,false))).put(line(2,Lang.str(c,R.string.eng_log_paused,battery)))
            .put(line(3,Lang.str(c,R.string.eng_log_hold_recording))).put(line(4,waiting)).put(line(5,Lang.str(c,R.string.eng_st_preparing_audio))).put(line(6,Lang.str(c,R.string.eng_log_part_sent,1,HttpApi.OPENROUTER))));
        check(StatusText.startedAt(st)==5&&StatusText.uploadedInLog(st)&&!StatusText.uploadedInLog(new JSONObject().put("log",new JSONArray().put(line(1,Pipeline.queuedLine(null,true))))),lang+": log reading wrong, started at "+StatusText.startedAt(st));
        check(StatusText.preparing(Lang.str(c,R.string.eng_st_preparing_audio))&&StatusText.preparing(Lang.str(c,R.string.eng_st_preparing_part,1,2))&&StatusText.preparing(Lang.str(c,R.string.eng_st_preparing_send))
            &&!StatusText.preparing(Lang.str(c,R.string.eng_st_sending_audio)),lang+": preparing status not recognized");
        // La notificación de avance: una espera se titula «En pausa» y no lleva la barra ocupada.
        String hold=Lang.str(c,R.string.eng_notif_hold,Character.toUpperCase(charger.charAt(0))+charger.substring(1));
        check(Transcriber.waiting(Transcriber.wifiWaitText())&&Transcriber.waiting(hold)&&Transcriber.waiting(Lang.str(c,R.string.eng_notif_cut_retry))&&Transcriber.waiting(Lang.str(c,R.string.eng_notif_attempt_retry,2,5))
            &&!Transcriber.waiting(Lang.str(c,R.string.eng_notif_preparing))&&!Transcriber.waiting(Lang.str(c,R.string.eng_notif_parts_ready,2,3))&&!Transcriber.waiting(Lang.str(c,R.string.eng_st_sending_part,1,3)),lang+": notification waits wrong");
        check(Lang.str(c,R.string.eng_notif_title_paused).equals(title(Transcriber.build(c,hold,true,-1,null))),lang+": waiting notification title wrong");
        // Errores de la clave: dicen la palabra «clave» del idioma y terminan con «Revísala en Ajustes.», que la bienvenida quita.
        String fix=" "+Lang.str(c,R.string.key_fix_in_settings);
        for(String m:new String[]{keyError(OpenAiClient.provider(),401),checkKeyError(403),checkKeyError(401)})
            check(m!=null&&m.endsWith(fix)&&m.contains(Lang.str(c,R.string.key_word))&&StatusText.aboutKey(m)&&!StatusText.withoutSettingsHint(m).endsWith(fix.trim()),lang+": key error wrong: "+m);
        // Los errores que se reintentan empiezan con el nombre del servicio (Notes y Ajustes lo miran) y no hablan de la clave.
        String down=retryError(HttpApi.OPENROUTER,503),odd=checkKeyError(400),busy=retryError(HttpApi.OPENROUTER,429);
        for(String m:new String[]{down,odd,busy})check(m!=null&&m.startsWith(HttpApi.OPENROUTER)&&!StatusText.aboutKey(m),lang+": service error wrong: "+m);
    }

    // ---------- 0.9.0: «Detener» en la notificación de avance ----------
    static void stopFromNotification(Context c,Recording r)throws Exception{
        Recording d=new Recording(UUID.randomUUID().toString(),"Prueba detener",System.currentTimeMillis(),r.duration);
        java.nio.file.Files.copy(r.audio(c).toPath(),d.audio(c).toPath());d.save(c);
        HttpApi part=new HttpApi(),other=new HttpApi();part.jobId=d.id;other.jobId="otra";
        try{
            // La acción va solo en la notificación de avance, y solo con una grabación a la que apuntar.
            Notification with=Transcriber.build(c,"Enviando parte 1 de 3",true,40,d.id),without=Transcriber.build(c,"Preparando…",true,-1,null),finished=Transcriber.build(c,"Lista",false,-1,d.id);
            check(with.actions!=null&&with.actions.length==1&&"Detener".equals(with.actions[0].title.toString())&&with.actions[0].actionIntent!=null
                &&(without.actions==null||without.actions.length==0)&&(finished.actions==null||finished.actions.length==0),"Stop action missing, or offered without a recording");
            // Lo mismo que «Cancelar transcripción»: queda sin pedir (se puede volver a transcribir), la bitácora lo dice y se
            // cortan las partes de ESA grabación que se están subiendo, aunque las suba la tarea de fondo. Una notificación
            // vieja (ya terminó o ya se detuvo) no hace nada.
            check(!TranscribeService.stop(c,d.id),"Stop acted on a recording that was not requested");
            FilesStore.update(c,d.id,s->s.put("requested",true));Transcriber.SENDING.add(part);Transcriber.SENDING.add(other);
            boolean stopped=TranscribeService.stop(c,d.id);JSONObject st=FilesStore.state(c,d.id);
            check(stopped&&!st.optBoolean("requested")&&!st.optBoolean("failed")&&"Detenida desde la notificación".equals(st.optString("status"))&&!Transcript.exists(c,d.id),"Stop from the notification did not cancel: "+st);
            check(part.cancelled&&!other.cancelled,"Stop did not cut the parts of that recording being sent");
            check(!TranscribeService.stop(c,d.id)&&Next.of(c,d).step==Next.Step.TRANSCRIBE,"After Stop the recording should wait for «Transcribir», untouched");
            // Wake locks con plazo: se renuevan antes de vencer.
            check(RecorderService.WAKE_RENEW_MS<RecorderService.WAKE_MS&&TranscribeService.WAKE_RENEW_MS<TranscribeService.WAKE_MS&&RecorderService.WAKE_MS<=10*60_000L,"Wake lock renewed after it expires, or held too long");
        }finally{
            Transcriber.SENDING.remove(part);Transcriber.SENDING.remove(other);d.delete(c);
            if(!Pipeline.working())c.getSystemService(NotificationManager.class).cancel(Transcriber.NOTIFICATION);
        }
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
        f=ready();f.previous=true;for(Retranscribe.Mode m:Retranscribe.Mode.values())check(Retranscribe.chooseFirst().equals(Retranscribe.reason(f,m)),"Retranscribe offered before choosing a version: "+m);
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
            check(Settings.noServer().equals(why)&&s.needsServer(),"Custom provider without a server address would send the key somewhere");
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

        // La pasada anterior terminó: todo su audio está hecho (en partes).
        String provider=new Settings(c).provider();long duration=d.duration;
        FilesStore.update(c,id,s->s.put("profile","prueba").put("provider",provider).put("audioMs",duration).put("doneAudioMs",duration).put("blocks",3).put("blocksDone",3));
        long finished=Pipeline.uploadBytes(c,d);

        Retranscribe.prepare(c,d,SPEAKERS,null);JSONObject st=FilesStore.state(c,id);
        check(!Transcript.exists(c,id)&&prev.isFile()&&notePrev.isFile()&&!note.isFile()&&Retranscribe.hasPrevious(c,id),"Previous version not kept aside");
        check(!FilesStore.file(c,id,".part0.json").exists()&&!block.exists(),"Old part checkpoints or blocks would be reused");
        check(st.optInt("attempt")==1&&Retranscribe.mode(st)==SPEAKERS&&!st.has("noteState")&&!st.has("cuts")&&!st.has("snippet")&&!st.has("fixedRefs"),"Retranscribe state wrong: "+st);
        // Lo hecho por la pasada anterior no es avance de la nueva: ni para el tamaño del envío («Usar datos móviles (≈X MB)»),
        // ni para la biblioteca, ni para Models.resume (que retenía el modelo del que se movió «Automático»).
        check(!st.has("blocks")&&!st.has("blocksDone")&&!st.has("doneAudioMs")&&(duration<=0||finished==0&&Pipeline.uploadBytes(c,d)>0),"Progress of the finished pass read as progress of the new one: "+finished+" "+Pipeline.uploadBytes(c,d)+" "+st);
        check(has(Retranscribe.reason(c,d,SPEAKERS),"anterior"),"Unfinished new version not explained on the device");

        // Llega la nueva versión (con su nota) y el usuario vuelve a la anterior.
        version("Versión dos").save(c,id);FilesStore.write(note,new JSONObject().put("version",1).put("title","Nota dos"));
        FilesStore.update(c,id,s->s.put("model","modelo-dos").put("noteState","ready").put("suggestedTitle","Título dos"));
        Retranscribe.restorePrevious(c,id);st=FilesStore.state(c,id);
        check(first(c,id).equals("Versión uno"),"Previous transcript not restored");
        check(FilesStore.read(note).optString("title").equals("Nota uno"),"Previous note not restored");
        check(!prev.isFile()&&!notePrev.isFile()&&!Retranscribe.hasPrevious(c,id)&&!st.has("retranscribe"),"Previous files or state left after restoring");
        check(st.optString("suggestedTitle").equals("Título uno")&&st.optString("model").equals("modelo-uno")&&st.optJSONArray("cuts")!=null
            &&st.optInt("blocks")==3&&st.optInt("blocksDone")==3&&st.optLong("doneAudioMs")==duration,"Process data of the previous version not restored");
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
