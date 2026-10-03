package cl.vozlocal.app;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.graphics.drawable.Icon;
import android.net.*;
import android.os.BatteryManager;
import android.os.Build;
import org.json.*;

/**
 * Cola de transcripciones: pedir, cancelar y decidir por dónde corre el trabajo.
 *
 * 0.8.0, tercera ronda (docs/diseno/SPEC-0.8c.md, decisiones 2 y 3), tras un audio de 4 min que tardó 1:15 por esperas:
 * - «Usar datos móviles ahora»: con «Solo con Wi-Fi» y datos móviles, una grabación puede usar los datos móviles SOLO
 *   ella (clave de estado "mobileOk"); la preferencia general no cambia. El detalle muestra el botón con el tamaño
 *   estimado del envío y la notificación «Esperando Wi-Fi» trae la misma acción.
 * - Transferencia iniciada por el usuario (Android 14+): al tocar «Transcribir» con la app visible se programa un trabajo
 *   setUserInitiated(true). Sigue aunque la app pase a segundo plano o espere la red, sin las cuotas de las tareas de
 *   fondo. Si no se puede (Android < 14, sin permiso, app no visible o falla), todo sigue como antes: servicio en primer
 *   plano y, si Android no lo deja, tarea de fondo. El trabajo automático (al guardar, sin toque) sigue como antes.
 */
final class Pipeline {
    static final int JOB_ID=4102;
    /** La transferencia iniciada por el usuario tiene su propio id: reprogramar la tarea de fondo no la cancela. */
    static final int USER_JOB_ID=4103;
    /** Aviso «Esperando Wi-Fi» con la acción «Usar datos móviles». (14 es el de Importar.) */
    static final int WIFI_NOTIFICATION=15;
    static void request(Context c,String id)throws Exception{request(c,id,new Settings(c).defaultSpeakers());}
    /** speakers: separar voces en esta grabación (se elige al transcribir). Lo pide la persona con un toque. */
    static void request(Context c,String id,boolean speakers)throws Exception{request(c,id,speakers,true);}
    /**
     * byUser: lo pidió la persona con un toque («Transcribir», «Reintentar», «Volver a transcribir»); si además la app
     * está visible, en Android 14+ va como transferencia iniciada por el usuario. false: el trabajo automático al guardar.
     */
    static void request(Context c,String id,boolean speakers,boolean byUser)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo"))throw new HttpApi.UserAction(Lang.str(c,R.string.eng_err_demo));
        if(Transcript.exists(c,id)){LocalStorage.enqueue(c,id);return;}
        // "mobileOk" vale para un pedido: un «Reintentar» o «Volver a transcribir» vuelve a respetar «Solo con Wi-Fi».
        FilesStore.update(c,id,s -> {s.put("requested",true).put("failed",false).put("attempts",0).put("retries",0).put("localCuts",0).put("queuedAt",System.currentTimeMillis()).put("log",new JSONArray()).put("upSent",0).put("upTotal",0).put("speakers",speakers).put("liveChars",0);s.remove("lastError");s.remove("mobileOk");s.remove("prepPct");});
        Diagnostics.event("job_queued",id);
        // «Volver a transcribir»: la bitácora dice qué alternativa se usó (para aprender cuál funciona mejor). Cuántas
        // personas, no quiénes: la bitácora viaja (sin títulos ni nombres) en el informe de soporte.
        JSONObject state=FilesStore.state(c,id);Retranscribe.Mode again=Retranscribe.mode(state);
        if(again!=null){JSONArray fixed=state.optJSONArray("fixedRefs");int people=Retranscribe.people(fixed);String label=Retranscribe.label(again);
            log(c,id,again==Retranscribe.Mode.CORRECTIONS&&people>0?Lang.plural(c,R.plurals.eng_log_again_people,people,label,people):Lang.str(c,R.string.eng_log_again,label));}
        String blocker=blocker(c,id);
        log(c,id,queuedLine(blocker,behind(id)));
        // Esperando Wi-Fi con datos móviles a mano: se avisa con la salida («Usar datos móviles»). Antes solo lo decía la
        // bitácora, y nadie lo veía (27 min de espera en el diagnóstico del 2026-10-01).
        if(waitsForWifi(c,id))waitingWifi(c,id);
        start(c,byUser);
    }
    /** Al guardar una grabación. «Transcribir automáticamente» solo envía el audio si ya se aceptó el aviso de envío (Consent). */
    static void afterRecording(Context c,String id){LocalStorage.enqueue(c,id);Diagnostics.event("audio_saved",id);try{if(new Settings(c).automatic()&&Consent.given(c))request(c,id,new Settings(c).defaultSpeakers(),false);}catch(Exception ignored){}}
    static void edited(Context c,String id)throws Exception{
        if(FilesStore.state(c,id).optBoolean("demo")){FilesStore.version.incrementAndGet();return;}
        LocalStorage.enqueue(c,id);Diagnostics.event("recording_edited",id);
    }
    static boolean pending(Context c){for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("requested"))return true;return false;}
    /** Hay grabaciones pedidas que aún no tuvieron ningún intento (recién encoladas). */
    static boolean hasFresh(Context c){for(Recording r:Recording.list(c)){JSONObject s=FilesStore.state(c,r.id);if(s.optBoolean("requested")&&s.optInt("attempts",0)==0)return true;}return false;}
    /** ¿Alguna grabación pedida tiene permiso para usar datos móviles («Usar datos móviles ahora»)? */
    static boolean anyMobileOk(Context c){for(Recording r:Recording.list(c)){JSONObject s=FilesStore.state(c,r.id);if(s.optBoolean("requested")&&s.optBoolean("mobileOk"))return true;}return false;}
    static void schedule(Context c,boolean replace){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(replace)scheduler.cancel(JOB_ID);
        if(!pending(c)){if(replace)scheduler.cancel(JOB_ID);return;}
        if(!replace && scheduler.getPendingJob(JOB_ID)!=null)return;
        Settings settings=new Settings(c);
        JobInfo info=jobInfo(c,settings,anyMobileOk(c));
        scheduler.schedule(info);
    }
    /** La tarea de fondo según los ajustes (Wi-Fi, cargador). */
    static JobInfo jobInfo(Context c,Settings settings){return jobInfo(c,settings,false);}
    /** anyNetwork: alguna grabación pedida puede usar datos móviles, así que la tarea no espera Wi-Fi. */
    static JobInfo jobInfo(Context c,Settings settings,boolean anyNetwork){
        return new JobInfo.Builder(JOB_ID,new ComponentName(c,PipelineJob.class))
            .setRequiredNetworkType(settings.wifiOnly()&&!anyNetwork?JobInfo.NETWORK_TYPE_UNMETERED:JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(settings.charging()).setRequiresBatteryNotLow(true)
            .setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
    }

    // ---------- Por dónde corre el trabajo (0.8.0, tercera ronda) ----------
    /** USER_JOB: transferencia iniciada por el usuario. RUNNING: ya hay un trabajo andando, que lo toma en su próxima vuelta. CLASSIC: como antes. */
    enum Route{USER_JOB,RUNNING,CLASSIC}
    /**
     * La decisión, separada del teléfono para poder probarla. sdk: versión de Android. byUser: lo pidió un toque. visible:
     * la app está a la vista (Android solo acepta programar la transferencia así). permitted: la app tiene el permiso
     * RUN_USER_INITIATED_JOBS. working: el servicio en primer plano o una transferencia ya están trabajando (programar otra
     * con el mismo id detendría la que está subiendo).
     */
    static Route route(int sdk,boolean byUser,boolean visible,boolean permitted,boolean working){
        if(working)return Route.RUNNING;
        return sdk>=34&&byUser&&visible&&permitted?Route.USER_JOB:Route.CLASSIC;
    }
    /** El servicio en primer plano o la transferencia iniciada por el usuario están trabajando. Lo usan las pantallas. */
    static boolean working(){return TranscribeService.running||PipelineJob.userRunning;}
    /**
     * ¿El trabajo en curso tiene ESTA grabación? La está transcribiendo (Transcriber.currentId) o espera para reintentarla
     * (Transcriber.retryingId). Una pedida que no cumple esto está en cola. La regla única de las pantallas.
     */
    static boolean processing(String id){return id!=null&&working()&&(id.equals(Transcriber.currentId)||id.equals(Transcriber.retryingId));}
    /**
     * ¿El trabajo en curso está con OTRA grabación (la transcribe o espera para reintentarla)? Entonces esta, recién pedida,
     * espera su turno: no se arranca otro trabajo (Route.RUNNING).
     */
    static boolean behind(String id){String cur=Transcriber.currentId,again=Transcriber.retryingId;return working()&&(cur!=null&&!cur.equals(id)||again!=null&&!again.equals(id));}
    /**
     * La línea de la bitácora (y el titular del detalle) al pedir: qué espera (blocker), o que espera su turno detrás de la
     * transcripción en curso (behind), como dicen el aviso, la nota y el botón. Antes decía «En cola · empezando» también
     * detrás de otra. Separada del teléfono para poder probarla.
     */
    static String queuedLine(String blocker,boolean behind){
        if(blocker!=null)return Lang.str(R.string.eng_queued_wait,blocker);
        return Lang.str(behind?R.string.eng_queued_behind:R.string.eng_queued_starting);
    }
    /** Arranca lo pedido por el camino que corresponda. byUser: viene de un toque de la persona. */
    static void start(Context c,boolean byUser){
        Route route=route(Build.VERSION.SDK_INT,byUser,visible(),canRunUserJobs(c),working());
        if(route==Route.RUNNING)return;
        if(route==Route.USER_JOB&&scheduleUserJob(c))return;
        if(blocker(c)!=null||!startForeground(c))schedule(c,true);
    }
    /** ¿La app está a la vista? (una pantalla abierta; un servicio en primer plano no cuenta). */
    static boolean visible(){
        ActivityManager.RunningAppProcessInfo info=new ActivityManager.RunningAppProcessInfo();
        try{ActivityManager.getMyMemoryState(info);}catch(RuntimeException e){return false;}
        return info.importance==ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND||info.importance==ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }
    /** Android 14+ con el permiso RUN_USER_INITIATED_JOBS (está en el manifiesto; Android puede quitarlo). */
    static boolean canRunUserJobs(Context c){
        if(Build.VERSION.SDK_INT<34)return false;
        try{return c.getSystemService(JobScheduler.class).canRunUserInitiatedJobs();}catch(RuntimeException e){return false;}
    }
    /** Cuándo se programó la última transferencia iniciada por el usuario (ms). */
    private static volatile long userJobAt;
    /** Tiempo en que una transferencia recién programada tiene preferencia: Android la empieza al instante si hay red. */
    static final long USER_JOB_GRACE_MS=2*60_000;
    /**
     * La transferencia iniciada por el usuario: mismas condiciones que la tarea de fondo (Wi-Fi según los ajustes y el
     * permiso de cada grabación, cargador si se pidió), prioridad máxima y el tamaño estimado del envío.
     * anyNetwork: alguna grabación pedida puede usar datos móviles. uploadBytes: lo que se estima subir (0 = no se sabe).
     */
    static JobInfo userJobInfo(Context c,Settings settings,boolean anyNetwork,long uploadBytes,boolean persisted){
        if(Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("Android 14+");
        JobInfo.Builder b=new JobInfo.Builder(USER_JOB_ID,new ComponentName(c,PipelineJob.class))
            .setUserInitiated(true).setPriority(JobInfo.PRIORITY_MAX)
            .setRequiredNetworkType(settings.wifiOnly()&&!anyNetwork?JobInfo.NETWORK_TYPE_UNMETERED:JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(settings.charging()).setRequiresBatteryNotLow(true);
        // Persistida: si el teléfono se reinicia mientras espera la red, sigue pedida.
        if(persisted)b.setPersisted(true);
        // Orden de Android: (bajada, subida). Se sube el audio (MB) y se baja solo la respuesta (unos KB).
        if(uploadBytes>0)b.setEstimatedNetworkBytes(64*1024,uploadBytes);
        return b.build();
    }
    /**
     * Programa la transferencia iniciada por el usuario. false si Android no la acepta: entonces sigue el camino de antes.
     * Deja en Diagnostics «user_job» con el resultado (y la clase del error si falló), sin datos del usuario.
     */
    static boolean scheduleUserJob(Context c){
        if(Build.VERSION.SDK_INT<34)return false;
        try{
            JobScheduler scheduler=c.getSystemService(JobScheduler.class);Settings settings=new Settings(c);boolean any=anyMobileOk(c);long bytes=pendingUploadBytes(c);
            JobInfo info;
            // Si este Android no acepta una transferencia persistida, se pide sin persistir (mejor eso que perderla).
            try{info=userJobInfo(c,settings,any,bytes,true);}catch(IllegalArgumentException e){info=userJobInfo(c,settings,any,bytes,false);}
            int result=scheduler.schedule(info);
            Diagnostics.event("user_job",null,"result",result==JobScheduler.RESULT_SUCCESS?"scheduled":"refused","net",networkName(c),"bytes",bytes);
            if(result!=JobScheduler.RESULT_SUCCESS)return false;
            userJobAt=System.currentTimeMillis();return true;
        }catch(RuntimeException e){Diagnostics.event("user_job",null,"result","failed","error_class",e.getClass().getSimpleName());return false;}
    }
    /**
     * Ajustes cambió «Red para enviar audio» o «Solo mientras carga» (llamar con la pantalla a la vista). Una transferencia
     * iniciada por el usuario que aún espera se vuelve a programar con las condiciones nuevas (mismo id: la reemplaza); si
     * Android no la acepta, se cancela para no bloquear el otro camino. Después, la tarea de fondo con los ajustes nuevos.
     * Sin «Solo con Wi-Fi», el aviso «Esperando Wi-Fi» ya no corresponde. Con «Solo con Wi-Fi» y datos móviles, la primera
     * grabación pedida que ahora espera Wi-Fi lo recibe (con «Usar datos móviles»), como promete Ajustes: si ningún
     * trabajo corre, nadie más lo pondría y la espera sería silenciosa. Nunca la que se está enviando (ver wifiNoticeFor).
     */
    static void settingsChanged(Context c){
        if(new Settings(c).wifiOnly()){
            java.util.List<String> ids=new java.util.ArrayList<>();for(Recording r:Recording.list(c))ids.add(r.id);
            String id=wifiNoticeFor(ids,working()?Transcriber.currentId:null,x->waitsForWifi(c,x));if(id!=null)waitingWifi(c,id);
        }
        else clearWaitingWifi(c);
        if(Build.VERSION.SDK_INT>=34&&!working()&&pending(c)){
            try{JobScheduler js=c.getSystemService(JobScheduler.class);
                if(js.getPendingJob(USER_JOB_ID)!=null&&!scheduleUserJob(c))js.cancel(USER_JOB_ID);}
            catch(RuntimeException ignored){}
        }
        schedule(c,true);
    }
    /**
     * De qué grabación es el aviso «Esperando Wi-Fi» al pasar a «Solo con Wi-Fi»: la primera que ahora espera Wi-Fi (waits),
     * salvo la que el servicio o la transferencia están enviando (sending): el Wi-Fi se revisa antes de cada parte, la parte
     * en curso termina igual y, si era la última, el aviso quedaba puesto en una grabación ya transcrita. Si le quedan partes,
     * el motor lo pone al llegar a la siguiente (Transcriber.waitWifi). La tarea de fondo no cuenta (sending null): la
     * reprogramación la corta y esa grabación sí queda esperando. Separada del teléfono para poder probarla.
     */
    static String wifiNoticeFor(java.util.Collection<String> ids,String sending,java.util.function.Predicate<String> waits){
        for(String id:ids)if(!id.equals(sending)&&waits.test(id))return id;
        return null;
    }
    /**
     * Hay una transferencia iniciada por el usuario recién programada que todavía no empieza Y que podría empezar ya: el
     * primer plano le cede el turno. Una que espera Wi-Fi o el cargador con las condiciones de cuando se programó (p. ej.
     * después se eligió «Wi-Fi y datos móviles») no va a empezar: cederle el turno dejaba todo quieto.
     */
    static boolean userJobFresh(Context c){
        if(Build.VERSION.SDK_INT<34||System.currentTimeMillis()-userJobAt>USER_JOB_GRACE_MS)return false;
        try{
            JobInfo job=c.getSystemService(JobScheduler.class).getPendingJob(USER_JOB_ID);
            return job!=null&&couldStart(job,unmetered(c),c.getSystemService(BatteryManager.class).isCharging());
        }catch(RuntimeException e){return false;}
    }
    /** ¿Se cumplen ahora la red y el cargador que exige la tarea? (separado del teléfono para poder probarlo). */
    static boolean couldStart(JobInfo job,boolean unmetered,boolean charging){
        NetworkRequest net=Build.VERSION.SDK_INT>=28?job.getRequiredNetwork():null;
        boolean wifi=Build.VERSION.SDK_INT>=28?net!=null&&net.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED):job.getNetworkType()==JobInfo.NETWORK_TYPE_UNMETERED;
        return (!wifi||unmetered)&&(!job.isRequireCharging()||charging);
    }
    /** Cancela la transferencia iniciada por el usuario que espera (Android 14+). */
    private static void cancelUserJob(Context c){if(Build.VERSION.SDK_INT>=34){try{c.getSystemService(JobScheduler.class).cancel(USER_JOB_ID);}catch(RuntimeException ignored){}}}
    /**
     * Intenta transcribir en primer plano (sigue con el teléfono bloqueado). Android 12+ solo lo permite
     * mientras la app está visible o desde ciertas acciones del usuario; si no se puede, devuelve false.
     */
    static boolean startForeground(Context c){return startForeground(c,false);}
    /**
     * now: «Empezar ahora», un toque explícito. No le cede el turno a una transferencia que Android tiene retenida (por
     * ejemplo, por el cargador): empieza en primer plano y la cancela; si Android no lo permite, vuelve a programar la tarea.
     */
    static boolean startForeground(Context c,boolean now){
        if(working())return true;
        if(!pending(c)||blocker(c)!=null)return false;
        // La transferencia iniciada por el usuario recién programada lo hará: dos trabajadores se pisarían.
        if(!now&&userJobFresh(c))return true;
        try{c.startForegroundService(new Intent(c,TranscribeService.class));}
        catch(RuntimeException e){Diagnostics.event("transcribe_fgs_denied",null,"error_class",e.getClass().getSimpleName());if(now)schedule(c,true);return false;}
        // Se pasó por delante de una transferencia que esperaba (retenida, o con las condiciones de cuando se programó): se
        // cancela, para que no quede en cola con condiciones viejas. Solo si el primer plano partió: si Android no lo deja
        // (app cerrada), esa transferencia es el mejor camino que queda.
        cancelUserJob(c);
        return true;
    }
    /** Mensaje de espera de Wi-Fi, en el idioma de la app (la Biblioteca corta lo que va entre paréntesis: StatusText.queuedLine). */
    static String wifiWait(){return Lang.str(R.string.wait_wifi);}
    /** ¿Este motivo de espera es el del Wi-Fi? (en cualquiera de los tres idiomas: puede venir de antes de cambiar el idioma). */
    static boolean isWifiWait(String blocker){return Lang.isAny(R.string.wait_wifi,blocker);}
    /**
     * Motivo por el que aún no puede empezar ningún trabajo (según tus ajustes), o null si alguno puede empezar ya. En el
     * idioma de la app; StatusText lo reconoce en cualquiera de los tres (eng_wait_*, wait_wifi).
     */
    static String blocker(Context c){return blocker(c,anyMobileOk(c));}
    /** Lo mismo para una grabación: con «Usar datos móviles ahora» no espera Wi-Fi. */
    static String blocker(Context c,String id){return blocker(c,FilesStore.state(c,id).optBoolean("mobileOk"));}
    private static String blocker(Context c,boolean mobileOk){
        Settings settings=new Settings(c);Network network=network(c);
        if(network==null)return Lang.str(c,R.string.eng_wait_internet);
        if(settings.wifiOnly()&&!mobileOk&&!unmetered(c))return wifiWait();
        BatteryManager battery=c.getSystemService(BatteryManager.class);
        if(settings.charging()&&!battery.isCharging())return Lang.str(c,R.string.eng_wait_charger);
        if(!battery.isCharging()&&battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)<=15)return Lang.str(c,R.string.eng_wait_battery);
        return null;
    }
    static Network network(Context c){ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)?n:null;}
    static boolean unmetered(Context c){ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);}
    /** "wifi", "mobile" o "none", para el registro técnico. */
    static String networkName(Context c){return network(c)==null?"none":unmetered(c)?"wifi":"mobile";}

    // ---------- «Usar datos móviles ahora» (0.8.0, tercera ronda) ----------
    /**
     * ¿Esta grabación espera Wi-Fi teniendo datos móviles? (pedida, «Solo con Wi-Fi», hay internet pero no es Wi-Fi, y no se
     * permitieron los datos móviles para ella). Separado del teléfono para poder probarlo.
     */
    static boolean waitsForWifi(boolean wifiOnly,boolean online,boolean unmetered,JSONObject state){
        return state!=null&&state.optBoolean("requested")&&wifiOnly&&online&&!unmetered&&!state.optBoolean("mobileOk");
    }
    static boolean waitsForWifi(Context c,String id){return waitsForWifi(new Settings(c).wifiOnly(),network(c)!=null,unmetered(c),FilesStore.state(c,id));}
    /**
     * Bytes que faltan por subir (estimado). OpenRouter recibe FLAC en base64: ~20 KB por segundo de audio en FLAC
     * (Retranscribe.OR_BYTES_PER_MS) más un tercio por el base64, con las muestras de voz de cada envío (Pricing.billedMs).
     * Otro proveedor recibe el m4a tal cual. audioMs: duración; doneMs: lo ya transcrito (no se vuelve a subir).
     */
    static long uploadBytes(String provider,String model,long audioMs,long doneMs,int anchors,long fileBytes){
        long left=Math.max(0,audioMs-Math.max(0,doneMs));
        if("openrouter".equals(provider))return HttpApi.base64Length(Pricing.billedMs(provider,model,left,anchors,false)*Retranscribe.OR_BYTES_PER_MS);
        return audioMs<=0?Math.max(0,fileBytes):(long)(Math.max(0,fileBytes)*(left/(double)audioMs));
    }
    /** Lo mismo para una grabación, con su estado y lo elegido en Ajustes. */
    static long uploadBytes(Context c,String id){Recording r=FilesStore.recording(c,id);return r==null?0:uploadBytes(c,r);}
    /** Igual, con la grabación ya leída (el detalle la tiene a mano: no recorre la biblioteca cada 3 s). */
    static long uploadBytes(Context c,Recording r){
        try{
            String id=r.id;JSONObject st=FilesStore.state(c,id);Settings s=new Settings(c);
            boolean speakers=st.optBoolean("speakers",s.defaultSpeakers());String provider=s.provider();
            // Lo ya transcrito solo se descuenta si el próximo intento lo reutiliza: el mismo intento en curso ("profile", que
            // «Volver a transcribir» borra) y el mismo proveedor. Si no, el estado de una pasada terminada decía que no
            // faltaba nada («Usar datos móviles (≈0,1 MB)» para subir el audio entero).
            boolean resume=st.has("profile")&&provider.equals(st.optString("provider"));
            // Sin intento en curso el estado aún no dice el modelo: el que se usaría hoy (solo importa con OpenRouter).
            String model=resume&&st.has("model")?st.optString("model"):"openrouter".equals(provider)?Models.chosen(s,speakers):"";
            int anchors=speakers&&TranscribeClient.knowsVoices(provider)?Voices.selected(c).size():0;
            return uploadBytes(provider,model,st.optLong("audioMs",r.duration),resume?st.optLong("doneAudioMs",0):0,anchors,r.audio(c).length());
        }catch(RuntimeException e){return 0;}
    }
    /** Lo que falta subir de todas las grabaciones pedidas (para avisarle a Android el tamaño de la transferencia). */
    static long pendingUploadBytes(Context c){long sum=0;for(Recording r:Recording.list(c))if(FilesStore.state(c,r.id).optBoolean("requested"))sum+=uploadBytes(c,r.id);return sum;}
    /** «≈6,4 MB», «≈26 MB», «≈0,3 MB» (nunca menos de 0,1), con la coma o el punto decimal del idioma de la app. */
    static String megabytes(long bytes){
        double mb=Math.max(0,bytes)/1e6;
        return "≈"+(mb<10?String.format(Lang.locale(),"%.1f",Math.max(0.1,mb)):String.valueOf(Math.round(mb)))+" MB";
    }
    /**
     * «Usar datos móviles ahora» para una grabación: solo ella (la preferencia «Solo con Wi-Fi» no cambia). Devuelve false
     * si ya no está pedida o ya tenía el permiso. No arranca nada: quien la llama (con la app visible) llama a start().
     */
    static boolean allowMobile(Context c,String id){
        JSONObject st=FilesStore.state(c,id);if(!st.optBoolean("requested")||st.optBoolean("mobileOk"))return false;
        long bytes=uploadBytes(c,id);
        try{FilesStore.update(c,id,s->s.put("mobileOk",true));}catch(Exception e){return false;}
        // Sin « »: el informe de soporte tapa lo que va entre comillas angulares.
        log(c,id,Lang.str(c,R.string.eng_log_mobile_ok,megabytes(bytes)));
        Diagnostics.event("mobile_ok",id,"bytes",bytes,"net",networkName(c));
        clearWaitingWifi(c,id);
        return true;
    }
    /**
     * Aviso «Esperando Wi-Fi» de una grabación, con la acción «Usar datos móviles (≈X MB)». Las dos abren su detalle; la
     * acción, además, permite los datos móviles para ella (RecordingActivity lee el extra "mobileOk"). Un solo aviso a la vez.
     */
    static void waitingWifi(Context c,String id){
        try{
            Recording r=FilesStore.recording(c,id);if(r==null)return;long bytes=uploadBytes(c,id);String size=megabytes(bytes);
            NotificationManager m=c.getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("processing",Lang.str(c,R.string.eng_channel_processing),NotificationManager.IMPORTANCE_LOW));
            Intent open=new Intent(c,RecordingActivity.class).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Códigos propios (bit 30): los de Transcriber usan los bits 0 a 29 y, con el mismo código, Android reutilizaría su PendingIntent.
            int code=0x40000000|((id.hashCode()&0x0fffffff)<<1);
            PendingIntent tap=PendingIntent.getActivity(c,code,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent use=PendingIntent.getActivity(c,code|1,new Intent(open).putExtra("mobileOk",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            String text=Lang.str(c,R.string.eng_notif_wifi_text,r.title,size);
            Notification n=new Notification.Builder(c,"processing").setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6B58).setContentTitle(Lang.str(c,R.string.eng_notif_wifi_title)).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STATUS)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c,R.drawable.ic_upload),Lang.str(c,R.string.eng_notif_wifi_action,size),use).build()).build();
            m.notify(WIFI_NOTIFICATION,n);wifiNoticeId=id;
            Diagnostics.event("wifi_wait",id,"bytes",bytes);
        }catch(RuntimeException ignored){}
    }
    /** De qué grabación es el aviso «Esperando Wi-Fi» que está a la vista (null: ninguno, o no se sabe tras reiniciar la app). */
    private static volatile String wifiNoticeId;
    static void clearWaitingWifi(Context c){try{c.getSystemService(NotificationManager.class).cancel(WIFI_NOTIFICATION);wifiNoticeId=null;}catch(RuntimeException ignored){}}
    /** Quita el aviso si es de esta grabación (o si no se sabe de cuál es): el de otra que sigue esperando se queda. */
    static void clearWaitingWifi(Context c,String id){String shown=wifiNoticeId;if(shown==null||shown.equals(id))clearWaitingWifi(c);}

    /** Bitácora visible del proceso: cada paso con su hora (máx. 80 entradas). También actualiza el estado actual. Una línea idéntica a la anterior no se repite. */
    static void log(Context c,String id,String message){
        try{FilesStore.update(c,id,s->{JSONArray log=s.optJSONArray("log");if(log==null)log=new JSONArray();JSONObject last=log.length()>0?log.optJSONObject(log.length()-1):null;if(last!=null&&message.equals(last.optString("m"))){s.put("since",System.currentTimeMillis());return;}log.put(new JSONObject().put("t",System.currentTimeMillis()).put("m",message));while(log.length()>80)log.remove(0);s.put("log",log).put("status",message).put("since",System.currentTimeMillis());});}catch(Exception ignored){}
    }
    /** Cancela y, si era «Volver a transcribir» y la nueva versión aún no estaba, vuelve sola la anterior. */
    static void cancel(Context c,String id)throws Exception{cancel(c,id,true);}
    /** restorePrevious: false al eliminar la grabación (no tiene sentido restaurar algo que se va a borrar). */
    static void cancel(Context c,String id,boolean restorePrevious)throws Exception{cancel(c,id,restorePrevious,Lang.str(c,R.string.eng_log_cancelled));}
    /** line: lo que dice la bitácora («Transcripción cancelada»; «Detenida desde la notificación» si fue desde ahí). */
    static void cancel(Context c,String id,boolean restorePrevious,String line)throws Exception{
        Diagnostics.event("job_cancelled",id);FilesStore.update(c,id,s -> {s.put("requested",false);s.remove("mobileOk");s.remove("prepPct");});log(c,id,line);AudioParts.clearBlocks(c,id);
        // El envío en curso se corta ya, lo haga el servicio en primer plano o la transferencia iniciada por el usuario.
        for(HttpApi active:new HttpApi[]{TranscribeService.current,PipelineJob.current})if(active!=null&&id.equals(active.jobId))active.cancel();
        // También las partes de esta grabación que se están preparando o subiendo, aunque las envíe la tarea de fondo (su
        // conexión no está a mano): antes seguían subiendo hasta terminar. El trabajo sigue con las demás grabaciones.
        for(HttpApi part:Transcriber.SENDING)if(id.equals(part.jobId))part.cancel();
        // Si el trabajo esperaba para reintentarla, ya no la tiene (Pipeline.processing).
        if(id.equals(Transcriber.retryingId))Transcriber.retryingId=null;
        clearWaitingWifi(c,id);
        // La grabación nunca queda sin transcripción por cancelar una versión nueva.
        if(restorePrevious&&Retranscribe.hasPrevious(c,id)&&!Transcript.exists(c,id)){
            try{Retranscribe.restore(c,id,Lang.str(c,R.string.eng_log_keep_previous),"retranscribe_cancelled");}catch(Exception e){Diagnostics.event("retranscribe_restore_failed",id,"error_class",e.getClass().getSimpleName());}
        }
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);scheduler.cancel(JOB_ID);
        // Sin nada más pedido, la transferencia que esperaba la red ya no tiene qué hacer (si estaba trabajando, termina sola).
        if(!pending(c)&&!PipelineJob.userRunning)scheduler.cancel(USER_JOB_ID);
        schedule(c,true);
    }
}
