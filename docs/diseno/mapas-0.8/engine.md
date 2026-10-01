# MAPA engine

# Mapa del motor de transcripción (rama feature/0.8.0, versionName 0.7.0 / versionCode 12)

Raíz del código: `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/` (abajo solo nombro el archivo). Pruebas: `.../app/src/androidTest/java/cl/vozlocal/app/`. Plan: `.../docs/PLAN-openrouter.md`.

Solo leí; no ejecuté la app ni las pruebas.

## 0. Clases y firmas exactas

**ProviderConfig.java** (14 líneas)
- `final String provider,base,model,key; final boolean speakers;`
- `ProviderConfig(String provider,String base,String model,String key,boolean speakers) throws Exception` (:7). Valida HTTPS sin userinfo, query ni fragment; modelo con regex `[A-Za-z0-9_.:/-]{1,120}` (acepta `/`, no acepta `~`); quita las `/` finales de `base`.
- `String fingerprint()` (:13) = Base64(SHA-256(`provider|base|model|speakers`)) vía `PipelineJob.hash`.

**Settings.java** (SharedPreferences `"settings"`)
- `String provider()` → pref `"provider"`, por defecto `"openai"` (:18). Valores que se escriben hoy: `"openai"` y `"custom"` (SettingsActivity.java:386-387).
- `String prefix()` → `""` si es openai; `"custom_"` para cualquier otro valor (:19).
- `boolean hasKey()` → `prefs.contains(prefix()+"keyEncrypted")` (:20).
- `String speakersMode()` → pref `"speakersMode"`: `"ask"` (defecto), `"always"` o `"never"` (:24).
- `String textModel()` → pref `"openaiTextModel"`, defecto `"gpt-transcribe"` (:26).
- `boolean canSeparate()` → `provider()=="openai" || prefs "customSpeakers"` (:28).
- `boolean defaultSpeakers()` → `canSeparate() && speakersMode()!="never"` (:30). Con "ask" y transcripción automática, separa voces.
- `String customBase()` → pref `"customBase"` (:33). `boolean needsServer()` → `!openai && customBase vacío` (:35). `NO_SERVER` (:36).
- `ProviderConfig config()` = `config(defaultSpeakers())` (:31).
- `ProviderConfig config(boolean speakers)` (:37-43):
  - openai → `new ProviderConfig("openai","https://api.openai.com/v1", speakers?"gpt-4o-transcribe-diarize":textModel(), apiKey(), speakers)`.
  - otro → lanza `UserAction(NO_SERVER)` si no hay base; si no, `new ProviderConfig(provider(), customBase(), prefs "customModel" (defecto "whisper-1"), apiKey(), speakers && customSpeakers)`.
- `String language()` → pref `"language"`, defecto `"es"`; `""` = detección automática (:17).
- Claves: `saveKey(String)` y `apiKey()` usan `prefix()+"keyEncrypted"` y `prefix()+"keyIv"` (:88-101). `hasOpenAiKey()`/`openAiKey()` leen siempre el prefijo `""` (:52-53). `anthropic_keyEncrypted`/`anthropic_keyIv` (:54-56). Cifrado AES/GCM con alias de Keystore `"voz-local-openai"` (:80), compartido por todas las claves.
- Otras prefs del motor: `"automatic"`, `"wifi"` (defecto true), `"charging"`, `"noteAuto"`, `"noteProvider"` (`"openai"`/`"anthropic"`), `"noteModel"`, `"myVoiceName"`, `"schema"` (hoy 4, VozApp.java:8-12), `"verifyAt"`, `"verifyOk"`, `"verifyMs"`, `"verifyFor"`, `"verifyMsg"` (SettingsActivity.java:419-446).

**HttpApi.java** (clase no final; las pruebas la subclasean y sobreescriben `request`)
- `Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra) throws Exception` (:46).
  - Solo HTTPS (:48). `setInstanceFollowRedirects(false)`. Connect timeout 30 s; read timeout `readTimeoutMs` (defecto 240000).
  - `Authorization: Bearer <token>` solo si el token no es vacío (:52). `extra` = encabezados adicionales.
  - El cuerpo va con `setFixedLengthStreamingMode(body.length())` (:55): el largo debe conocerse antes de escribir. Tras escribirlo llama `touch()` y `onUploaded.run()`.
  - Streaming (:58-65): solo si `code<400 && onEvent!=null && Content-Type contiene "event-stream"`. Lee líneas `data:`, ignora `[DONE]`, llama `onEvent.event(json)` y guarda el evento cuyo `type` termina en `.done`. Devuelve ese evento como `Response.text`. Sin evento final: `IOException`.
  - No streaming: lee hasta 8 MB (:67); más que eso lanza `IOException("Respuesta demasiado grande")`.
  - `requestId` sale del encabezado `x-request-id` (:63,:68).
  - Si el vigilante cortó (`stalled!=null`), la excepción se envuelve en `HttpApi.Stalled` (:71).
- `interface Body{long length(); void write(OutputStream out) throws Exception;}` (:36). Fábricas: `static Body bytes(byte[])`, `static Body json(JSONObject)` (todo en memoria), `Body file(File)`.
- `void copy(File,OutputStream)` (:78): 64 KB por vuelta, con `check()`, `touch()` y `onProgress.update(sent,total)`. Es el único camino que mueve `lastActivity` durante la subida; `touch()` es privado (:30).
- Campos: `volatile boolean cancelled; String jobId; Runnable onUploaded; volatile int readTimeoutMs; volatile Progress onProgress; volatile Events onEvent; volatile long lastActivity; volatile String stalled`.
- `HttpApi child()` (:35), `void cancel()` (:44), `void check()` (:45), `void abortStalled(String why)` (:29).
- `static class Response{int code; String text,location,requestId,jobId; JSONObject json()}`.
- `static class UserAction extends Exception` (no se reintenta). `static class Stalled extends IOException`.
- `static void require(Response,String service)` (:79-95):
  - 2xx pasa. Lee `error.code`, `error.type`, `error.param`, `error.message`.
  - 401 → `UserAction` «La clave del proveedor no es válida…». 403 → `UserAction`.
  - 429 con el texto `insufficient_quota` en el cuerpo → `UserAction` (sin saldo).
  - 408, 429 y ≥500 → `IOException` (se reintenta).
  - El resto (400, 402, 404, 413, 422…) → `UserAction(service+" · HTTP "+code+". "+reason…)`.
- `static String safeReason(Exception)`, `static String safeToken(String)` (regex `[A-Za-z0-9_.:/-]{1,120}`).

**OpenAiClient.java** (55 líneas; es el único cliente de transcripción)
- `interface Delta{void text(int characters);}` (:17).
- `void verify(String key)` → `GET https://api.openai.com/v1/models/gpt-4o-transcribe-diarize` (:18-21).
- `void verify(ProviderConfig config)` → `GET config.base+"/models/"+URLEncoder.encode(config.model,"UTF-8")` (:22). Lo usa SettingsActivity.java:442.
- `JSONObject transcribe(File audio,String key,String language)` (:23): atajo openai + diarize.
- `JSONObject transcribe(File audio,ProviderConfig config,String language)` (:26).
- `JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,Delta delta)` (:28-53). `references` = `{nombre enviado, data URL}`.

**Transcriber.java**
- Constantes: `PARALLEL=3`, `BLOCK_TEXT_MS=8*60_000`, `SPEAKER_BLOCK_MAX_MS=12*60_000`, `MAX_KNOWN=4`, `JOB_BUDGET_MS=6 min`, `JOB_SEND_LIMIT_MS=9 min`, `MAX_LOCAL_CUTS=12`, `UPLOAD_STALL_MS=90_000`, `NOTIFICATION=9`, `DONE_NOTIFICATION=10`, `DONE_CHANNEL="done"`.
- `static long speakerBlockMs(long totalMs,long bytes)` (:37): `n=max(ceil(totalMs/12 min), ceil(bytes/19_000_000))`; `n<=1 ? max(1,totalMs) : totalMs/n`.
- `static long sendEstimate(long partMs)` = `90_000+0.6*partMs` (:45).
- `static long responseLimit(long partMs)` = `min(20 min, max(3 min, partMs+1 min))` (:64).
- `static final ReentrantLock RUNNING`; `static volatile String currentId`; `volatile boolean sawLocalCut`.
- `Transcriber(Context c,HttpApi http,long budgetMs)` (:75): `budgetMs>0` es la tarea de fondo; 0 es primer plano.
- `boolean runAll()` (:78); `private void process(Recording r)` (:156).
- `private JSONObject block(Recording r,ProviderConfig config,Settings settings,List<AudioParts.Part> parts,int i,List<String[]> refs,String fresh)` (:336).
- `static String profile(String base,int attempt,Retranscribe.Mode mode)` (:154); `static boolean localCut(Throwable)` (:137).
- `static List<String[]> fixedPlan(JSONArray fixed,Map<String,String> saved)` (:284); `static boolean isSaved(...)` (:299).
- `static void prefillVoices(Context,Transcript)` (:318); `static void prefill(Transcript,JSONArray fixed)` (:326).
- Excepciones internas: `Yield`, `NeedsForeground`.

**AudioParts.java**
- `static final class Part{File file; double offset /*s*/; long durationMs;}` (:18).
- `static final long SINGLE_MAX_MS=12*60_000` (:22).
- `static List<Part> plan(Context c,Recording r,HttpApi http,long targetMs,JSONArray cached,List<Long> cutsOut,Log log)` (:33).
- `static void remuxRange(File source,File target,long fromMs,long toMs,HttpApi http)` (:64): copia AAC sin recodificar; si el origen no es AAC usa `AudioConvert.convert`.
- `static long quietest(File source,long centerMs,long spanMs,HttpApi http)` (:88).
- `static List<Clip> pickReferences(JSONArray segments,int limit,Set<String> exclude)` (:132).
- `static List<String[]> references(Context c,Recording r,JSONObject first,HttpApi http,int limit,Set<String> exclude)` (:163).
- `static File blockDir(Context,String id)` = `filesDir/blocks/<id>/`; archivos `block-<i>.m4a` (:25,:55). `clearBlocks` (:26).

**AudioConvert.java**
- `static long duration(File)` (:11).
- `static void convert(File source,File target,long startMs,long endMs,HttpApi cancel[,Progress progress[,int bitrate]])` (:12-15). Solo produce AAC-LC en MP4 (`MediaMuxer MUXER_OUTPUT_MPEG_4`), a 96 kbps por defecto, con la frecuencia y canales del origen. No hay escritor WAV/FLAC/MP3 ni remuestreo.

**Transcript.java**
- `static Transcript fromParts(List<JSONObject> parts,List<Double> offsets)` (:223-244).
- `load`, `save` (guarda además `"snippet"` en el estado), `segments()`, `diarized()`, `speakers()`, `order()`, `blockStarts(JSONObject state)`, `reviewed()`, `edited()`, `named()`, `clean()`, `text(Recording)`.

**Pipeline.java**
- `static void request(Context c,String id)` (:11) y `request(Context c,String id,boolean speakers)` (:13).
- `afterRecording` (:27), `schedule` (:35), `jobInfo` (:44), `startForeground` (:54), `blocker` (:61), `log(Context,String id,String message)` (:75), `cancel(Context,String id[,boolean restorePrevious])` (:79-89), `networkName` (:73). `JOB_ID=4102`.

**PipelineJob.java** (JobService, `OPEN_APP_NOTIFICATION=11`): `onStartJob` (:17), `onStopJob` (:34), `timedOut(String id)` (:47), `static String hash(byte[])` (:98).

**TranscribeService.java**: `static volatile boolean running; static volatile HttpApi current;` `BACKOFF={20_000,60_000,120_000,300_000}`, `LOCAL_CUT_DELAY=15_000`.

**Retranscribe.java**: `enum Mode{CORRECTIONS,SINGLE,SPEAKERS,TEXT}`; `SINGLE_MAX_MS=1_380_000`; `SINGLE_MAX_BYTES=24_000_000`; `BEFORE[]` (:26-27); `static Mode mode(JSONObject state)`; `fitsSingle(long,long)`; `Facts` con el campo `openai`; `reason(Facts,Mode)`; `start`/`prepare`/`keepNew`/`restore`; `clearCheckpoints`.

**Pricing.java**: `static double perMinute(String model)`; `static double estimate(String model,long audioMs)`; `static double estimate(String provider,String model,long audioMs)` (solo `"openai"`; si no, −1); `static String usd(double)`; `REVIEWED="23-09-2026"`.

**RecState** (RecordingActions.java:83-101): `enum Kind{NEW,QUEUED,FAILED,DONE}`; `static RecState of(JSONObject state,boolean transcribed)`. Orden: `requested` → QUEUED (detalle = `state.status`); `transcribed` → DONE; `failed` → FAILED; si no, NEW. Solo lee `requested`, `failed` y `status`. En el mismo archivo, `Next.of` (:41) lee además `retranscribe`.

## 1. Flujo completo de una transcripción

**a) Quién la pide y cómo se decide «separar voces»**
- Manual: `RecordingActions.transcribe(Screen,Recording,Runnable)` (RecordingActions.java:244-250).
  - Ya transcrita → `RetranscribeSheet.show`. Sin clave (`!hasKey()`) → `missingKey`.
  - `canSeparate() && speakersMode()=="ask"` → `askSpeakers` (:261), que calcula costos con `Pricing.estimate(provider, settings.config(true/false).model, r.duration)` y llama `start(s,r,changed,true|false)`.
  - Si no pregunta: `start(..., settings.defaultSpeakers())`.
  - `start` (:285) → `Pipeline.request(c,id,speakers)`.
- Automática: `Pipeline.afterRecording` (Pipeline.java:27), llamada desde RecorderService.java:239 e ImportService.java:57. Si `automatic()` → `request(c,id)` con `defaultSpeakers()`.
- Volver a transcribir: `Retranscribe.start` (:189) → `prepare` → `Pipeline.request(c,id,Retranscribe.speakers(mode))` (TEXT → false).
- `Pipeline.request` (:13-26):
  - Rechaza grabaciones `demo`. Si ya hay transcripción, solo encola el guardado local.
  - Escribe el estado: `requested=true, failed=false, attempts=0, retries=0, localCuts=0, queuedAt=now, log=[], upSent=0, upTotal=0, speakers=<bool>, liveChars=0` y quita `lastError`.
  - Registra «En cola · …». Si `blocker(c)!=null` o no se puede arrancar el servicio en primer plano → `schedule(c,true)` (JobScheduler).

**b) Quién ejecuta**
- `TranscribeService.onStartCommand` (:33): servicio en primer plano `dataSync`, WakeLock de 3 h y WifiLock.
  - Cancela la tarea de fondo, toma `Transcriber.RUNNING.tryLock(60 s)`.
  - Hasta 40 vueltas de `new Transcriber(this,http,0).runAll()`.
  - Entre vueltas espera `BACKOFF[round++]`, o 15 s si `sawLocalCut`. Si falta red o cargador espera hasta 15 min; pantalla encendida → reintenta ya. Tras 4 esperas cede a `Pipeline.schedule`.
- `PipelineJob.onStartJob` (:17): si no puede pasar a primer plano, corre `new Transcriber(this,http,JOB_BUDGET_MS).runAll()` en un hilo.
  - Si `retry` → `Pipeline.schedule(this,true)` (tarea nueva, sin backoff).
  - Si `waitingForeground()!=null` → aviso «Abre Verbapp para terminar».
  - `onStopJob` con `STOP_REASON_TIMEOUT` → `timedOut(id)`: suma un intento; al quinto deja `failed` y restaura la versión anterior si existe.
- `Transcriber.runAll` (:78-125) recorre `Recording.list` con `state.requested`:
  - Hay grabación en curso → espera. Presupuesto agotado → `retry`.
  - `Yield` → `retry` y se detiene. `NeedsForeground` → queda pedida y la retoma el primer plano.
  - `HttpApi.UserAction` → `requested=false, failed=true`, registra el mensaje y notifica «necesita atención» (salvo que `keepPrevious` restaure la versión anterior).
  - Otra excepción (:100-118):
    - `cuts=localCuts+(localCut?1:0)`; es «local» si `localCut(e) && cuts<=12`.
    - `attempts += local?0:1`; `again = attempts<5`.
    - Escribe `attempts, retries+1, localCuts, requested=again, failed=!again, lastError=describe(e)`.
    - Si se rinde y hay versión anterior sin transcripción nueva → `Retranscribe.restore`.

**c) `process(Recording r)` (Transcriber.java:156-252)**
1. `wantSpeakers = state.has("speakers") ? state.speakers : settings.defaultSpeakers()` (:158). `mode = Retranscribe.mode(state)`.
2. Si no existe `.transcript.json`:
   - `config = settings.config(wantSpeakers)` (:161): aquí se decide proveedor, base, modelo y clave. Clave vacía → `UserAction("Agrega una clave de API en Ajustes y pulsa Reintentar.")`.
   - `bytes = audio.length()`; `audioMs = r.duration` (o `AudioConvert.duration` si es 0 y hay voces).
   - `single = mode==SINGLE && config.speakers && Retranscribe.fitsSingle(audioMs,bytes)` (≤1.380.000 ms y ≤24.000.000 bytes). En tarea de fondo, si `sendEstimate(audioMs)>9 min` → `NeedsForeground`.
   - `target = single ? audioMs : config.speakers ? speakerBlockMs(audioMs,bytes) : BLOCK_TEXT_MS` (:167).
   - Voces guardadas: `saved = config.speakers && provider=="openai" ? Voices.references(c) : []` (:170). Cada una es `{sentName, dataURL "data:audio/mp4;base64,…", target, label}`: tu voz `"voz_propia"`→`"voice:me"`; las demás `"voz_<id>"`→`"voice:<id>"`.
   - Correcciones: `fixed = mode==CORRECTIONS && config.speakers && provider=="openai" ? state.fixedRefs : null` (:174).
   - Perfil (:176): `config.fingerprint()+settings.language()+"|v3|"+target+"|"+(saved.isEmpty()?"none":Voices.fingerprint(c))`, más `"|a"+attempt+"|"+mode.name()` si `attempt>0`.
   - Si el perfil cambió respecto de `state.profile` (:177-182): borra los puntos de control `<id>.partN.json` y los bloques, quita `cuts`, guarda `profile` y pone en cero `blocksDone, doneAudioMs, bytesSent, inTokens, outTokens, usageSec, blockMsSum, blockCount`.
   - Escribe `model=config.model, speakers=config.speakers, audioMs=r.duration, provider=config.provider` (:183). Evento `job_start`.
   - Etapa «Preparando audio». `parts`:
     - Si `single`: `[Part(audio,0,audioMs)]` y `cuts=[0,audioMs]`.
     - Si no: `AudioParts.plan(c,r,http,target,state.cuts,cuts,log)` (:188).
   - Guarda `cuts` (arreglo JSON de ms) y `blocks=n` (:189-191).
3. `AudioParts.plan` (:33-61):
   - `total = r.duration` (o la duración real); `targetMs = max(targetMs,60_000)`.
   - Reutiliza `cached` si trae 2 o más cortes.
   - Si no, `cuts=[0]`. Solo si `total>12 min` o el archivo pesa más de 20.000.000 bytes busca pausas: para `t=target; t<total-0.4*target; t+=target` toma `q=quietest(source,t,10_000)` (ventanas de 250 ms en ±10 s) y lo agrega si `q>last+30 s && q<total-20 s`. Cierra con `total`.
   - Resguardo de tamaño: un tramo que pesaría más de 20.000.000 bytes se parte a la mitad (en pausa).
   - Con 2 cortes devuelve `[Part(source,0,total)]`: el archivo original `.m4a` tal cual.
   - Si no, por cada tramo crea `blocks/<id>/block-i.m4a` con `remuxRange` (copia AAC, escribe a `.tmp` y renombra) y devuelve `Part(file, a/1000d, b-a)`.
   - Los tramos son contiguos `[a,b)`; no hay solape.
4. Envío y voces (:193-232):
   - Con voces guardadas: `references = saved`.
   - Con correcciones (`fixedReferences` no vacío):
     - `fresh = "pass"+attempt+":"` y `references = saved + corrections`.
     - Cada corrección es `{"voz_N"/"voz_Nb", dataURL, id anterior, nombre visible}`, recortada con `AudioConvert.convert` a `cache/<id>-fixed-k.m4a`.
     - Todos los bloques van en paralelo.
   - Sin correcciones, si `config.speakers && n>1` (:211-223):
     - `responses[0]=block(...,0,own,null)` va solo; `from=1`.
     - `auto = AudioParts.references(c,r,responses[0],http,MAX_KNOWN-saved.size(),exclude)`: recorta muestras de 2 a 9,5 s de quienes hablan 10 s o más en la parte 1. Cada una es `{"voz_<persona>"[+"b"], "data:audio/mp4;base64,…", "block0:<etiqueta>", descripción}`.
     - `references = saved + auto`.
     - Esta rama no mira el proveedor: corre también con `custom` que tenga `customSpeakers`.
   - Tarea de fondo: si se pasó del presupuesto y quedan partes sin punto de control → `Yield` (:225).
   - El resto va en `Executors.newFixedThreadPool(3)`, un `block(...)` por parte; la primera excepción se relanza (:226-232).
5. `block(...)` (:336-394):
   - Punto de control: si existe `recordings/<id>.part<i>.json`, lo lee y lo devuelve (no reenvía).
   - Tarea de fondo: `sendEstimate(part)>9 min` → `NeedsForeground`; si ya envió algo y no cabe en el tiempo → `Yield`.
   - `h = http.child()`; `h.readTimeoutMs = min(20 min, max(240 s, 120 s+partMs))` (:349).
   - `h.onProgress` → `progress()`: suma lo subido de los bloques en paralelo y guarda `upSent`/`upTotal` cada ~0,7 s.
   - `h.onUploaded` → registra «Parte N enviada · OpenAI está transcribiendo» si el proveedor es openai; si no, «… · el servidor está transcribiendo» (:354).
   - `delta` → `liveText` guarda `liveChars` cada 0,8 s.
   - Vigilante (:359-366), cada 5 s con `SystemClock.elapsedRealtime`:
     - Un hueco mayor a 30 s entre ticks → registra «Android tuvo la app congelada…» y el evento `app_frozen`.
     - Antes de terminar la subida: `now-h.lastActivity > 90 s` → `h.abortStalled("el envío dejó de avanzar")`.
     - Después de la subida: `idle > responseLimit(partMs)` → `abortStalled("sin respuesta en …")`.
     - Ese corte llega como `HttpApi.Stalled` y cuenta como corte local (no gasta intento hasta 12 veces).
   - Llamada: `new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta)` (:368).
   - Si lanza `UserAction` cuyo mensaje contiene `"known_speaker"` y había `refs` → registra «El proveedor rechazó las muestras de voz…» y reenvía sin ellas (:369-373).
   - Anota en la respuesta (:377-378): `_known = {ref[0] → ref[2]}` (si el ref tiene menos de 3 campos, `"block0:"+ref[0]`), solo si hay refs; `_prefix = fresh` si no es null.
   - Escribe el punto de control con `FilesStore.write(checkpoint,response)`.
   - Métricas en el estado (:382-389):
     - `localCuts=0`, `blocksDone+1`, `doneAudioMs+=partMs`, `bytesSent+=part.file.length()`, `blockMsSum+=took`, `blockCount+1`.
     - Si hay `usage`: con `usage.type=="duration"` → `usageSec+=usage.seconds`; si no → `inTokens+=usage.input_tokens`, `outTokens+=usage.output_tokens`.
   - Bitácora: «Parte i de n lista · tardó mm:ss · reconoció X voces, Y nuevas» (`describeVoices`, :396). Evento `part_complete`.
6. Qué se envía (OpenAiClient.java:28-53). `POST config.base+"/audio/transcriptions"`, `Authorization: Bearer key`, `Content-Type: multipart/form-data; boundary=VozLocal<uuid>`.
   - Pre-chequeo: `audio.length()>25_000_000` → `UserAction` (:29).
   - Campos en orden: `model`. Luego:
     - Si `config.speakers`: `response_format=diarized_json`, `chunking_strategy=auto`, `language` (si no es vacío) y, por cada referencia, `known_speaker_names[]=<nombre>` + `known_speaker_references[]=<data URL>`.
     - Si `fast` (`provider=="openai" && model=="gpt-transcribe"`): `languages[]=<idioma>` (nunca `language`), `stream=true` si hay delta, y sin `response_format`.
     - Resto: `response_format=json` + `language`.
   - Al final el archivo: `name="file"; filename="recording.m4a"`, `Content-Type: audio/mp4`, copiado con `http.copy`.
   - Streaming (fast): `http.onEvent` cuenta los caracteres de los eventos `type=="transcript.text.delta"` (campo `delta`). La respuesta final es el evento `*.done`; la prueba usa `{"type":"transcript.text.done","text":"…","usage":{"type":"tokens","input_tokens":120,"output_tokens":8}}` (FeatureChecks.java:77).
7. Qué respuesta se espera:
   - Con voces (`diarized_json`): `{"segments":[{"speaker":"A","start":0.0,"end":1.0,"text":"…"},…],"usage":{…}}`. El código solo lee `segments[].speaker|start|end|text` y `usage`.
   - Sin voces: debe traer `"text"` (si no, `UserAction("El proveedor no devolvió texto en el formato esperado.")`). El cliente fabrica `segments=[{"speaker":"text","start":0,"end":0,"text":<text>}]`, o `[]` si el texto está vacío (:51).
   - Siempre agrega `_diarized=config.speakers` (:52).
   - Contrato de hecho del cliente hacia el motor: un `JSONObject` con `segments[{speaker,start,end,text}]`, `usage` opcional y `_diarized`. El motor añade `_known` y `_prefix`.
8. Unión: `Transcript.fromParts(Arrays.asList(responses), offsets(parts))` (Transcriber.java:233; Transcript.java:223-244). Por cada parte `p`:
   - `prefix = response._prefix` (o `""`).
   - `_known` objeto → `map{nombre enviado → id de voz}`. `_known` arreglo (hasta 0.4.4) → `legacy` (letras).
   - `segments==null` → `IOException("OpenAI no devolvió los segmentos de hablantes esperados.")`.
   - Cada tramo debe tener las claves `speaker`, `start`, `end`, `text`; si falta una → `IOException("La transcripción recibida está incompleta.")`. Los tramos con texto vacío se descartan.
   - `speaker = isNull ? "unknown" : getString("speaker")`.
   - Id final:
     - Si `map.containsKey(speaker)` → `map.get(speaker)`.
     - Si no → `prefix + (parts.size()<=1 ? speaker : (p>0 && legacy.contains(speaker) ? "block0:"+speaker : "block"+p+":"+speaker))`.
   - `start` y `end` se suman con `offsets.get(p)` (segundos).
   - Datos: `{segments, parts:n, names:{}, diarized: parts.isEmpty() || parts[0]._diarized (defecto true), reviewed:false}` y, si `n>1`, `blocks:[offsets]`.
   - Después (Transcriber.java:233-239): `data.provider`, `data.model`, `data.pass=mode.name()` si es repetición; `prefillVoices` (nombres de `voice:*` desde Voices), `prefill` (nombres de `fixedRefs`). `notePending=true` si `noteAuto && hasText && Notes.canGenerate`. `transcript.save` bajo `FilesStore.LOCK` tras `check(r)`. Luego `AudioParts.clearBlocks`.
   - Formatos de id de voz en uso: `"A"` (una parte sin refs), `"text"` (sin voces), `"blockP:A"`, `"block0:A"` (reconocida por muestra de la parte 1), `"voice:me"`, `"voice:<id>"`, `"passN:…"` (voz nueva en la segunda pasada), `"manual:K"`, `"unknown"`.
9. Cierre (:244-251):
   - Si `notePending` → `Notes.generate(c,r,http)`; nunca hace fallar la transcripción.
   - Estado: `requested=false, failed=false, attempts=0, doneIn=now-queuedAt, upSent=0, upTotal=0, doneAudioMs=r.duration, doneAt=now`.
   - Bitácora «Transcripción lista · tiempo total …»; `LocalStorage.enqueue`; notificación `buildDone`; evento `job_complete`.

**d) Claves de estado (`recordings/<id>.sync.json`, vía `FilesStore.state`/`update`)**
- Cola y reintentos: `requested`, `failed`, `attempts`, `retries`, `localCuts`, `queuedAt`, `lastError`.
- Bitácora: `log` (`[{"t":ms,"m":"texto"}]`, máx. 80, sin repetir la línea anterior), `status` (última línea), `since`.
- Elección: `speakers` (bool), `model`, `provider`, `audioMs`, `profile`.
- Bloques: `cuts` (`[ms…]`), `blocks`, `blocksDone`, `doneAudioMs`, `blockMsSum`, `blockCount`.
- Envío: `upSent`, `upTotal`, `bytesSent`, `liveChars`.
- Uso: `inTokens`, `outTokens`, `usageSec`.
- Fin: `doneIn`, `doneAt`, `snippet`, `notePending`.
- Repetición: `attempt`, `retranscribe` (`{mode,at,before:{…claves BEFORE…}}`), `fixedRefs` (`[{id,name,label,start,end}]`).
- Nota: `noteState`, `noteError`, `noteStartedAt`, `suggestedTitle`.
- Otras: `demo`, `opened`, `inboxUri`, `inboxAt`, `inboxKind`.
- Archivos auxiliares: `recordings/<id>.part<i>.json` (respuesta cruda + `_known`/`_prefix`/`_diarized`), `filesDir/blocks/<id>/block-<i>.m4a`, `recordings/<id>.transcript.prev.json`, `recordings/<id>.note(.prev).json`.

**e) Bitácora y diagnóstico**
- `Pipeline.log(c,id,msg)` (Pipeline.java:75). La pantalla la lee en RecordingActivity.java:545-616 (`human()`, `logHas(st,"enviado")`, `startedAt`).
- `Diagnostics.event(event,job,k,v…)` solo guarda claves de la lista blanca (Diagnostics.java:21): `stage, provider, model, http, request_id, code, type, param, elapsed_ms, bytes, duration_ms, part, parts, action, screen, result, error_class, count, runner, net, reason, local, display, idle, battery, mode, kind, source, label`.
- Eventos del motor: `job_queued, job_start, prepare_done, http_start, http_end, http_failure, api_rejected, part_complete, job_retry, job_rejected, job_needs_foreground, job_timeout, job_complete, job_cancelled, job_interrupted, app_frozen, runner_round, note_auto`.
- El informe de soporte usa `st.model`, `st.speakers`, `blocksDone/blocks`, `retries`, `localCuts` (Diagnostics.java:64-66).

**f) Costos y métricas en pantalla**
- `Pricing.perMinute`: `gpt-transcribe` 0,0045; `gpt-4o-mini-transcribe` 0,003; `gpt-4o-transcribe`, `gpt-4o-transcribe-diarize` y `whisper-1` 0,006; otro → −1.
- `estimate(provider,model,ms)` devuelve −1 si el proveedor no es `"openai"`.
- Quién lo usa:
  - RecordingActivity.java:503: fijo `"openai","gpt-4o-transcribe-diarize"` y `textModel()`.
  - RecordingActivity.java:662-663: `st.provider` (defecto `"openai"`), `st.model`.
  - RecordingActivity.java:676: texto «tarifa pública al REVIEWED».
  - RecordingActivity.java:786: `transcript.data.provider/model`.
  - RecordingActions.java:264 (`askSpeakers`), RetranscribeSheet.java:44, Retranscribe.java:54-56 (`cost`), SettingsActivity.java:389 (`modelSheet`).
- Grilla `stats()` (RecordingActivity.java:652-678): Tiempo total, Restante, Audio procesado, Velocidad, Costo, Tokens (`inTokens+outTokens`) o «Audio facturado» (`usageSec`), Datos enviados (`bytesSent+upSent`), Texto recibido (`liveChars`), Reintentos.
- Estimación de tiempo (:594-602): con `speakers` usa factor 0,16; sin voces 0,08; con varias partes usa el promedio por bloque y `Transcriber.PARALLEL`.

## 2. Formato exacto de `<id>.transcript.json`

Archivo `filesDir/recordings/<uuid>.transcript.json`, escritura atómica con `AtomicFile`. Ejemplo mínimo (2 partes, con voces):
```json
{
  "segments":[
    {"speaker":"voice:me","start":0.4,"end":4.2,"text":"Hola, partamos."},
    {"speaker":"block0:A","start":4.5,"end":9.1,"text":"Dale."},
    {"speaker":"block1:B","start":726.3,"end":731.0,"text":"Otra cosa."}
  ],
  "parts":2,
  "names":{"voice:me":"Konrad"},
  "diarized":true,
  "reviewed":false,
  "blocks":[0,720.5],
  "provider":"openai",
  "model":"gpt-4o-transcribe-diarize"
}
```
- `segments[]`: `speaker` (String, id de voz), `start` y `end` (double, segundos absolutos ya con el offset del bloque), `text`. `orig` es opcional: etiqueta original si el usuario corrigió quién habla.
- `parts` (int). `blocks` (inicios en segundos) solo si `parts>1`.
- `names` (`{id → nombre}`), `diarized` (bool; false → todas las voces se muestran como «Texto»), `reviewed` (bool).
- Opcionales: `pass` (`"CORRECTIONS"|"SINGLE"|"SPEAKERS"|"TEXT"`), `order` (`[ids]`, congelado en la primera corrección), `inherited` (`{id → nombre}`), `demo` (bool).
- Sin voces: un tramo por bloque, `{"speaker":"text","start":<offset>,"end":<offset>,"text":"…"}` (`"blockP:text"` con varias partes) y `diarized:false`. `Transcript.text()` avisa que los tiempos son del bloque, no de la frase (Transcript.java:200).
- Lectores que dependen del formato: `speakers()`, `order()`, `talkShare()`, `snippet()`, `text()`, `blockStarts()`, `AudioParts.pickReferences`, `Retranscribe.pickKnown`, `Notes.prompt`, `Next.needsReview`, `Transcriber.buildDone`.

## 3. Cómo se eligen «separar voces» y el modelo

- Proveedor: pref `"provider"` (`"openai"` | `"custom"`), hoja `providerSheet` (SettingsActivity.java:385-387).
- Modelo:
  - OpenAI con voces: siempre `gpt-4o-transcribe-diarize`, fijo en Settings.java:39.
  - OpenAI sin voces: pref `"openaiTextModel"`. Lista fija `SettingsActivity.MODELS={"gpt-transcribe","gpt-4o-transcribe","gpt-4o-mini-transcribe","whisper-1"}` con `MODEL_NAMES` y `MODEL_DETAILS` (SettingsActivity.java:40-42); hoja `modelSheet` (:388).
  - Custom: prefs `"customBase"`, `"customModel"`, `"customSpeakers"` (casilla «Admite diarized_json y chunking_strategy»); hoja `custom()` (:469-480). Si cambia la URL se borra la clave.
- Separar voces:
  - Pref `"speakersMode"`, hoja `speakersSheet` (:390); la fila se muestra solo si `canSeparate()` (:106).
  - La decisión por grabación queda en el estado `speakers` (Pipeline.java:16).
  - El valor efectivo lo resuelve `Settings.config(boolean)` y se reescribe en el estado (`speakers=config.speakers`, Transcriber.java:183).
- Resúmenes para pantalla: `SettingsActivity.modelName(Settings)` y `modelSummary(Settings)` (:53-54), usados en RecordingActivity.java:502.
- Comprobar conexión: `verify()` (SettingsActivity.java:435-452) → `new OpenAiClient(call).verify(settings.config())`. `verifyTarget()` (:419-423) arma la huella con proveedor, modelo y el hash de la clave cifrada.
- Migración de esquema: VozApp.java:8-12 (`schema<4`: `openaiModel` → `openaiTextModel`/`speakersMode`).

## 4. Todo lo que asume OpenAI

**URLs fijas**
- `https://api.openai.com/v1`: Settings.java:39, OpenAiClient.java:19 y :24.
- Notas: `Notes.OPENAI_URL="https://api.openai.com/v1/chat/completions"` y `ANTHROPIC_URL` (Notes.java:41-42).
- Verificación: `GET base/models/<modelo con URLEncoder>` (OpenAiClient.java:22).

**Modelos fijos**
- `gpt-4o-transcribe-diarize`: Settings.java:39, OpenAiClient.java:19 y :24, RecordingActivity.java:503, VozApp.java:10-11.
- `gpt-transcribe`: Settings.java:26, OpenAiClient.java:31, RecordingActions.java:264.
- `whisper-1`: defecto de `customModel` (Settings.java:42).
- Tabla de Pricing y `SettingsActivity.MODELS`.

**Parámetros y formato**
- Multipart con `response_format=diarized_json`, `chunking_strategy=auto`, `known_speaker_names[]`, `known_speaker_references[]` (data URL `audio/mp4`), `languages[]` y `stream=true` para gpt-transcribe, `response_format=json` para el resto.
- Archivo `recording.m4a` / `audio/mp4`.
- Eventos SSE `transcript.text.delta` y `*.done`.
- `usage.type=="duration"` con `seconds`, o `input_tokens`/`output_tokens`.
- Etiquetas de hablante como letras `"A"`, `"B"` (los comentarios y los nombres únicos `voz_N` existen para no chocar con ellas).
- La respuesta de voces trae `segments[{speaker,start,end,text}]` directo.

**Límites**
- 25.000.000 bytes por envío (OpenAiClient.java:29).
- 1400 s por envío con voces → `SPEAKER_BLOCK_MAX_MS=12 min`, `AudioParts.SINGLE_MAX_MS=12 min`, `Retranscribe.SINGLE_MAX_MS=1.380.000`, `SINGLE_MAX_BYTES=24.000.000`.
- Umbrales de 19.000.000 (`speakerBlockMs`) y 20.000.000 (`AudioParts.plan`).
- `BLOCK_TEXT_MS=8 min`. `MAX_KNOWN=4` voces conocidas por envío.
- Respuesta de hasta 8 MB (HttpApi.java:67).

**Tiempos**
- `responseLimit = partMs+1 min` (comentario «OpenAI suele tardar la mitad»).
- `readTimeout = 120 s+partMs` (mín. 240 s, máx. 20 min).
- `sendEstimate = 90 s+0,6×partMs`. Factores 0,16 y 0,08 de la estimación en pantalla.

**Errores**
- `insufficient_quota` en un 429; encabezado `x-request-id`.
- El reintento sin muestras se activa por el texto `known_speaker` en el mensaje de error.
- Forma `error.{code,type,param,message}`.

**Compuertas `provider().equals("openai")`**
- Transcriber.java:170, :174, :354.
- Retranscribe.java:44, :80, :139 (y `Facts.openai` en `reason`, :103).
- RecordingActions.java:264, :266, :281.
- RecordingActivity.java:503, :662 (defecto), :786 (defecto), :1213.
- RetranscribeSheet.java:60.
- SettingsActivity.java:53-54, :78, :95, :103-114, :348, :353-354, :385, :403-404, :421, :440-443.
- Settings.java:19, :28, :35, :38.
- Pricing.java:24.

**Textos con «OpenAI»**
- Transcript.java:230; Transcriber.java:354; RecordingActions.java:253 y :297; SettingsActivity.java:113-114, :563, :647; Voices.java (comentarios).

**Pruebas que fijan el contrato actual**
- FeatureChecks.java:40-57 y :72-91: URL exacta, contenido del multipart, `languages[]`, `known_speaker_*`, respuesta fast.
- FeatureChecks.java:107: `Pricing.estimate("gpt-transcribe",60_000)`.
- FeatureChecks.java:135-136: `speakerBlockMs`.
- NotesChecks.java:167: URL de chat de OpenAI.
- EngineChecks.java:133-150 y VoicesChecks.java:161-166: `fromParts` con `_known` y `_prefix`.

# PUNTOS DE EXTENSION

# Dónde enchufar OpenRouter con el menor cambio

Archivos en `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/`. Las firmas y claves nuevas que nombro son propuestas, no existen hoy.

## 1. Costura principal: el cliente de transcripción (2 líneas)
- Las únicas instancias para transcribir están en Transcriber.java:368 y :372: `new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta)`. La otra instancia, para verificar, está en SettingsActivity.java:442.
- Abstraer una interfaz con esa misma firma: `JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,OpenAiClient.Delta delta) throws Exception`.
- Elegir la implementación por `config.provider` (`"openrouter"` → cliente nuevo; el resto → `OpenAiClient` intacto, así las pruebas de FeatureChecks siguen pasando).
- Contrato de salida que el motor ya consume sin tocar `fromParts`:
  `{"segments":[{"speaker":<String>,"start":<s>,"end":<s>,"text":<String>}], "usage":{…}, "_diarized":<bool>}`.
- El cliente OpenRouter debe normalizar ahí adentro el `verbose_json` de cada proveedor (Azure, Deepgram, `diarize` genérico, marcas `<|speaker:N|>` de Fish): agrupar `words[]` en tramos si no vienen `segments[].speaker`, y pasar los índices enteros a String.
- Sin voces también puede devolver tramos reales con tiempos (hoy es un solo tramo `speaker:"text"` con start=end=0). Para que `Transcript.speakers()` muestre «Texto», debe usar `speaker:"text"` y `_diarized:false`.

## 2. Configuración (Settings.java y ProviderConfig.java)
- `Settings.provider()` admite un tercer valor, `"openrouter"`. Hay que tocar:
  - `prefix()` (:19): hoy todo lo que no es openai usa `"custom_"`. Hace falta un prefijo propio (p. ej. `"openrouter_"` → `openrouter_keyEncrypted`/`openrouter_keyIv`). `saveKey`, `apiKey` y `hasKey` ya son genéricos por prefijo.
  - `canSeparate()` (:28), `needsServer()` (:35) y `config(boolean)` (:37-43): rama nueva `new ProviderConfig("openrouter","https://openrouter.ai/api/v1", speakers?<modelo con voces>:<modelo de texto>, apiKey(), speakers)`.
- Prefs nuevas a definir: modelo con voces, modelo solo texto, «Automático», y caché del catálogo con su fecha.
- `ProviderConfig`:
  - El constructor de 5 argumentos lo usan las pruebas: mantenerlo.
  - La regex de modelo ya acepta `microsoft/mai-transcribe-2`.
  - Si se agrega la «receta» (tag del proveedor, formato de audio, cómo pedir voces, duración y bytes máximos por envío, solape), conviene que entre en `fingerprint()` (:13) para invalidar puntos de control cuando cambie.
- Migración: VozApp.java:8-12 (`schema` hoy 4 → 5 si hay que mover prefs).

## 3. HTTP (HttpApi.java)
- `request(...)` sirve tal cual: Bearer, `extra` para `HTTP-Referer`/`X-Title`, y `contentType="application/json"`.
- Falta un `Body` que transmita el JSON con el audio en base64 en streaming:
  - `length()` calculable de antemano: `prefijo + 4*ceil(n/3) + sufijo`.
  - Dentro de `write`, llamar al equivalente de `copy()` para que haga `check()`, `touch()` y `onProgress`. Propuesta: `void copyBase64(File,OutputStream)` en HttpApi, porque `touch()` es privado.
  - `HttpApi.json(JSONObject)` arma todo en memoria: no sirve para 20-40 MB.
- `require()` (:79-95): agregar 402 (sin créditos en OpenRouter) como `UserAction` con texto de saldo. Revisar `error.code` numérico y `error.metadata`.
- Verificación de la clave: método nuevo (el plan dice `GET https://openrouter.ai/api/v1/key`, que además da el saldo), llamado desde `SettingsActivity.verify()` (:442) según el proveedor. `verifyTarget()` (:419-423) necesita su rama.
- El catálogo (`GET /api/v1/models?output_modalities=transcription`) es público: `request("GET",url,null,null,null,null)` funciona sin token.

## 4. Bloques y formato de audio (Transcriber.process y AudioParts)
- Tamaño de bloque: Transcriber.java:167 (`target`). La rama OpenRouter debería sacarlo de la receta (el plan habla de ~15 min con solape).
- `single` (:164) y `Retranscribe.fitsSingle` dependen de 23 min / 24 MB: parametrizar por receta.
- Solape:
  - `AudioParts.plan` (:33-61) solo hace tramos contiguos. Haría falta un parámetro `overlapMs`, o una función nueva, que cree los bloques `[a-overlap, b)` con `Part.offset=(a-overlap)/1000`.
  - Además, un paso previo a `fromParts` (Transcriber.java:233) que recorte los tramos duplicados de la zona solapada.
- Formato: los bloques son `.m4a` (copia AAC). Para modelos que no aceptan m4a (MAI-2) hay que agregar en `AudioConvert` un decodificador a WAV PCM 16 kHz mono (o FLAC) por bloque. Ya existe el lazo decodificador → PCM en `AudioConvert.convert` (:29-49) y en `AudioParts.quietest` (:96-108); falta el remuestreo, la mezcla a mono y la cabecera WAV. Guardar en `blocks/<id>/block-i.wav` con el mismo patrón `.tmp` + rename.
- Compuertas de voces conocidas ya existentes: Transcriber.java:170 y :174.
- Compuerta que falta: la rama «parte 1 sola + muestras automáticas» (:211-223) no mira el proveedor. Para OpenRouter hay que saltarla (todas las partes en paralelo, `from=0`) o reemplazarla por el mecanismo nuevo.

## 5. Unir hablantes entre bloques sin tocar `fromParts`
- `_known` es un mapa genérico `{etiqueta en la respuesta del bloque → id global}` (Transcript.java:228, :236).
- Un unificador nuevo (por solape o por «anclas») puede calcular, después de tener todas las respuestas y antes de Transcriber.java:233, un `_known` por bloque. Ejemplo: bloque 2 `{"0":"block0:1","1":"block0:0"}`. `fromParts` lo aplica tal cual.
- Las voces no mapeadas quedan como `blockP:<índice>`.
- Para «anclas» con voces guardadas, mapear a `voice:me` / `voice:<id>`: `prefillVoices` (Transcriber.java:318) les pone el nombre solo.
- El punto de control `.partN.json` se escribe dentro de `block()` (:380), antes de la unión. Un `_known` calculado después debe aplicarse sobre `responses[]` en memoria, y recalcularse al reanudar desde puntos de control.

## 6. Costos y métricas
- `Pricing.estimate(String provider,String model,long audioMs)` (:24) es el único punto que consultan las pantallas: agregar la rama `"openrouter"` (tabla o catálogo en caché, normalizando la unidad).
- Costo real: `block()` (:381-389) ya lee `response.usage`.
  - Opción A: el cliente normaliza a `{"type":"duration","seconds":N}` para reutilizar `usageSec`.
  - Opción B: clave de estado nueva (p. ej. `costUsd`) sumando `usage.cost`, y mostrarla en `RecordingActivity.stats()` (:662-663) en vez del estimado.
  - Si se agrega una clave nueva: incluirla en `Retranscribe.BEFORE` (:26-27) y en el reinicio por cambio de perfil (Transcriber.java:181).
- `bytesSent` suma `part.file.length()` (:381). Si se quiere exactitud con base64 o WAV, sumar el tamaño real del cuerpo.
- Diagnostics.java:21: las claves nuevas de eventos (p. ej. `cost`) deben entrar en la lista blanca o se descartan en silencio.

## 7. Pantallas (solo compuertas, sin rediseño)
- SettingsActivity:
  - `providerSheet` (:385-387): tercera opción.
  - `render` (:78, :95, :103-114): fila de modelo, fila de voces, textos de ayuda.
  - `modelSheet` (:388): hoy lista fija `MODELS`; para OpenRouter, lista desde el catálogo.
  - `keyInput` (:402-406): texto y hint.
  - `modelName`/`modelSummary` (:53-54).
  - Nota: `noteMissing`/`noteAiSheet` (:348-357).
- RecordingActions.askSpeakers (:261-284): `live` (texto en vivo) y `known` dependen de openai; los costos salen de `Pricing.estimate(provider,…)`.
- RecordingActivity.showNew (:503): costos fijos de OpenAI → usar `settings.config(true/false).model` con el proveedor real.
- Retranscribe: `Facts.openai` y `reason()` (:103: «Solo funciona con OpenAI…»), `detail()` (:44), `savedVoices()` (:139). RetranscribeSheet.java:60 (`paid`).
- RecordingActivity.java:1213: «Guardar la voz» solo con openai.
- Textos: Transcriber.java:354 («OpenAI está transcribiendo» / «el servidor está transcribiendo») y Transcript.java:230 (mensaje con «OpenAI»).

## 8. Notas (fuera de esta área, pero el motor las dispara)
- Transcriber.java:237 usa `Notes.canGenerate(c)` y :245 `Notes.generate(c,r,http)`.
- `Notes.provider()` (:82) reduce todo a `"openai"|"anthropic"`.
- `Notes.openai(http,key,model,prompt)` (:252) ya habla chat completions. Para OpenRouter basta parametrizar la URL (`https://openrouter.ai/api/v1/chat/completions`), la clave (`Settings` con prefijo openrouter) y el nombre del servicio.
- Ajustes necesarios: `Notes.model()` (:85-90), `canGenerate` (:80), `rates()` (:95), `openaiBody` (:249, `reasoning_effort`). Guardar `json.model` (la versión que respondió) en la nota.

## 9. Lista que se actualiza sola
- No existe nada parecido hoy: `MODELS` es un arreglo estático.
- Lugar natural: una clase nueva (catálogo con caché en prefs o archivo), consultada por `Settings.config()` y por la hoja de modelos.
- El refresco diario puede colgarse de `VozApp.onCreate` o de la apertura de Ajustes; usa `HttpApi.request` en un hilo (SettingsActivity tiene `io`, un executor de un hilo).

# RIESGOS

# Supuestos ligados a OpenAI que se romperían, y trampas

Archivos en `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/`. Todo sale de leer el código y el plan; nada se probó contra OpenRouter.

1. **El vigilante mata subidas que no pasen por `HttpApi.copy`.**
   - `lastActivity` solo se actualiza en `request()` al abrir, al terminar el cuerpo, dentro de `copy()` y al leer SSE (HttpApi.java:30, :49, :55, :61, :78).
   - Un `Body` JSON/base64 propio que no llame a algo equivalente deja `lastActivity` quieto. A los 90 s, Transcriber.java:364 hace `abortStalled("el envío dejó de avanzar")`.
   - Eso cuenta como corte local: hasta 12 reintentos «gratis» que vuelven a subir el audio (y quizá a cobrarlo).
   - Tampoco habría `onProgress`: `upSent`/`upTotal` quedarían en 0 y la etapa «Subido» dependería solo de que la bitácora contenga «enviado» (RecordingActivity.java:563).

2. **Memoria.**
   - `HttpApi.json()`/`bytes()` materializan el cuerpo completo.
   - 15 min de WAV a 16 kHz mono son ~28,8 MB, ~38,4 MB en base64; como String de Java ocupa el doble. Con 3 bloques en paralelo (`PARALLEL=3`) es un OOM seguro.
   - Hay que transmitir en streaming, y `setFixedLengthStreamingMode` exige conocer el largo exacto antes (HttpApi.java:55).

3. **Formato de audio.**
   - Todo el motor produce y envía `.m4a` AAC: `AudioParts.plan` hace copia AAC; con una sola parte se envía el archivo original; las muestras son `data:audio/mp4`.
   - Según el plan, `microsoft/mai-transcribe-2` da 400 con m4a. No existe conversor a WAV/FLAC ni remuestreo en `AudioConvert`.
   - El pre-chequeo de 25.000.000 bytes (OpenAiClient.java:29) y los umbrales de 19/20/24 MB están calibrados para AAC y multipart. Con WAV el tamaño por minuto es ~2,7 veces mayor que AAC a 96 kbps, y el tope del JSON de OpenRouter no está documentado.

4. **No existe `diarized_json` ni `known_speaker_*` en OpenRouter.**
   - Un envío con voces que reutilizara OpenAiClient mandaría `response_format=diarized_json` y `chunking_strategy`, que OpenRouter no acepta.
   - En multipart, además, el plan cita el issue docs#576: las `provider.options` se descartan y las voces fallan sin avisar.
   - El reintento «sin muestras» depende de que el mensaje de error contenga `known_speaker` (Transcriber.java:370): con otro proveedor no se activa.

5. **La rama de muestras automáticas no está protegida por proveedor** (Transcriber.java:211-223).
   - Con cualquier `config.speakers && n>1`: manda la parte 1 sola (pierde el paralelismo), recorta muestras con `AudioConvert.convert`, las pasa como `refs` y escribe `_known={"voz_1":"block0:A"}` en cada punto de control.
   - Con un proveedor que no reconoce esas etiquetas, el mapeo no sirve: cada bloque queda con voces `blockP:<n>` sin unir y aparecen personas duplicadas por bloque.
   - `describeVoices` diría «reconoció 0 voces».

6. **Hablantes como enteros.**
   - `fromParts` usa `segment.getString("speaker")` (Transcript.java:235). Mi entendimiento es que el `org.json` de Android convierte números a texto; no lo verifiqué en ejecución, y fuera de Android (pruebas JVM) lanzaría excepción. Normalizar a String en el cliente evita la duda.
   - Si el proveedor entrega hablantes solo en `words[]`, o sin `segments`, `fromParts` lanza «OpenAI no devolvió los segmentos de hablantes esperados.» (una `IOException`, así que se reintenta 5 veces).
   - Un tramo sin alguna de las claves `speaker/start/end/text` lanza «La transcripción recibida está incompleta.» (:233). Un proveedor que omita `speaker` en tramos de silencio rompe la parte entera.
   - `speaker:null` se acepta como `"unknown"`.

7. **Solape y `blocks`.**
   - `fromParts` suma `offsets.get(p)` y no deduplica: con solape habría texto repetido.
   - `data.blocks` se usa como inicios de bloque en `Transcript.blockStarts()`; con solape los inicios ya no coinciden con `cuts`.
   - Los cortes se guardan en el estado (`cuts`) y se reutilizan entre intentos (`AudioParts.plan`, `cached`).
   - El perfil (Transcriber.java:176) solo incluye `target`. Si cambian el solape o el formato sin cambiar el perfil, se reutilizan puntos de control y bloques viejos. Conviene subir `"|v3|"` o meter esos parámetros en `fingerprint()`.

8. **Puntos de control por parte.**
   - `<id>.part<i>.json` guarda la respuesta ya normalizada, con `_known`, `_prefix` y `_diarized`.
   - Si cambia el contrato interno del cliente (p. ej. se empiezan a guardar `words`), los puntos de control de un intento a medias tras actualizar la app podrían mezclarse. Invalidar por perfil.
   - El tamaño importa poco en disco, pero `verbose_json` con palabras puede acercarse al tope de 8 MB de respuesta (HttpApi.java:67), que lanza «Respuesta demasiado grande» y se reintenta 5 veces.

9. **Tiempos.**
   - El plan dice que los proveedores de OpenRouter cortan a los ~60 s de proceso.
   - `readTimeoutMs` (mín. 240 s), `responseLimit` (mín. 3 min) y `sendEstimate` están pensados para OpenAI. Un 504 o timeout del proveedor cae en `IOException` (reintento con backoff 20 s, 1, 2 y 5 min; 5 intentos), reenviando y cobrando el bloque cada vez.
   - En tarea de fondo: `JOB_SEND_LIMIT_MS=9 min` y `sendEstimate=90 s+0,6×partMs`. Con base64 (+33 %) y WAV, la subida por datos móviles puede no caber y caer en `NeedsForeground`.

10. **Clasificación de errores** (HttpApi.require).
    - 402 (sin créditos) no está contemplado: sale como `UserAction` genérico «Proveedor · HTTP 402. Revisa el formato del audio…», un mensaje engañoso.
    - 429 sin el texto `insufficient_quota` se reintenta.
    - Un 404 de modelo retirado (p. ej. `gpt-4o-transcribe-diarize`, que no está en OpenRouter) es `UserAction`: `failed` sin reintento. Es correcto, pero el texto no dice «modelo no disponible» salvo que `error.message` contenga «model».
    - `requestId` lee `x-request-id`; no comprobé qué encabezado usa OpenRouter.
    - `setInstanceFollowRedirects(false)`: un 3xx termina en `UserAction`.

11. **Claves y prefijos.**
    - `Settings.prefix()` devuelve `"custom_"` para todo lo que no sea openai: sin cambiarlo, OpenRouter pisaría la clave del servidor propio.
    - `custom()` borra la clave al cambiar la URL (SettingsActivity.java:476).
    - `hasOpenAiKey()` y `openAiKey()` leen siempre el prefijo `""`. `Notes.canGenerate` depende de eso: con proveedor openrouter y sin clave de OpenAI, la nota automática no se arma (`canNote()` false) aunque haya clave de OpenRouter.
    - `needsServer()` sería true para openrouter (customBase vacío) y `verify()` abriría la hoja de servidor.
    - El alias de Keystore `voz-local-openai` es compartido; no renombrarlo (se perderían las claves guardadas).

12. **Verificación.**
    - `OpenAiClient.verify(ProviderConfig)` hace `GET base/models/<URLEncoder(model)>`. Con `microsoft/mai-transcribe-2` la barra queda como `%2F`, y la ruta de OpenRouter es otra (`/api/v1/model/{autor}/{slug}`, sin «s», o `/key`).
    - `verifyTarget()` no conoce las prefs nuevas: una comprobación vieja seguiría «válida» tras cambiar de modelo.

13. **Costos.**
    - `Pricing.estimate(provider,…)` devuelve −1 para todo lo que no sea `"openai"`: desaparecen todas las píldoras de costo, y `askSpeakers` y RetranscribeSheet muestran «—» o nada.
    - RecordingActivity.java:503 usa costos de OpenAI fijos y solo se muestra con `provider=="openai"`.
    - `st.optString("provider","openai")` y `transcript.data.optString("provider","openai")` asumen openai para datos antiguos (correcto), pero un modelo de OpenRouter con nombre igual a uno de la tabla no se estimaría.
    - `pricing.prompt` del catálogo no trae unidad (por segundo, por hora o por token, según el plan).
    - `usage` de OpenRouter sin `type:"duration"` cae en la rama de tokens (Transcriber.java:386-387) y suma 0: se pierde `seconds` y `cost`.
    - Una clave de estado nueva que no esté en `Retranscribe.BEFORE` no se restaura al «Volver a la anterior» y mostraría el costo de la versión descartada.
    - El texto «tarifa pública al 23-09-2026» (RecordingActivity.java:676) es de la tabla de OpenAI.

14. **Volver a transcribir.**
    - `CORRECTIONS` exige `f.openai` (mensaje «Solo funciona con OpenAI…»).
    - `SINGLE` asume 23 min / 24 MB y un proveedor que aguante un envío largo. Con el corte a ~60 s de proceso puede fallar siempre; según el plan, solo deepgram/nova-3 haría una reunión completa en un envío.
    - `fixedRefs`, `_prefix` y `prefill` solo operan con openai.
    - `Retranscribe.savedVoices` y `facts.saved` valen 0 fuera de openai.

15. **Voces conocidas en pantalla.**
    - La fila «Voces conocidas» (SettingsActivity.java:95), «Grabar mi voz» (RecordingActions.java:281) y «Guardar la voz» (RecordingActivity.java:1213) se ocultan fuera de openai.
    - Si se implementan las «anclas», hay que abrir esas compuertas y cambiar los textos que dicen «se envían a OpenAI» (SettingsActivity.java:563 y :647).
    - Con anclas antepuestas al audio, los tiempos deben restarse antes de `fromParts`, y el texto del ancla no debe llegar a `segments` (contaminaría el comienzo, la nota y las muestras de `pickReferences`).

16. **Streaming y texto en vivo.**
    - Solo existe para `provider=="openai" && model=="gpt-transcribe"` (OpenAiClient.java:31; `live` en RecordingActions.java:264).
    - `HttpApi` activa SSE solo si `onEvent!=null` y el Content-Type contiene `event-stream`, y exige un evento cuyo `type` termine en `.done`. Un SSE de otro proveedor sin ese evento lanza «La respuesta en streaming terminó sin el evento final».

17. **Idioma.**
    - `settings.language()` vale `""` para detección automática. OpenAiClient omite el campo si es vacío; el cliente nuevo debe hacer lo mismo (no enviar `"language":""`).

18. **Pruebas instrumentadas que fijan el contrato actual** (no romper firmas).
    - `new OpenAiClient(http).transcribe(File,String,String)` y las sobrecargas con `ProviderConfig`.
    - `HttpApi.request(...)` sobreescrito en subclases anónimas (debe seguir siendo no final y con esa firma).
    - `new ProviderConfig(String,String,String,String,boolean)`.
    - `Pricing.estimate(String,long)`, `Transcriber.speakerBlockMs(long,long)`, `Transcript.fromParts(List,List)` con `_known` objeto o arreglo y `_prefix`.
    - `HttpApi.require(Response,"OpenAI")` con 401, 429+insufficient_quota y 503.
    - Archivos: FeatureChecks.java:40-136, EngineChecks.java:133-150, VoicesChecks.java:161-166, NotesChecks.java:167.

19. **Diagnóstico.**
    - La lista blanca de Diagnostics.java:21 descarta claves nuevas en silencio.
    - `model` y `provider` viajan en el informe de soporte; los ids con `/` pasan.
    - La bitácora viaja en el informe: no escribir en `Pipeline.log` nombres ni contenido (convención en Transcriber.java:197).

20. **Notas con alias de OpenRouter** (área vecina).
    - `Notes.model()` valida con `[A-Za-z0-9._:-]{2,80}`: rechaza `/` y `~` y cae al modelo por defecto en silencio. Decide Claude u OpenAI con `m.startsWith("claude")`.
    - `ProviderConfig` tampoco acepta `~`.
    - `openaiBody` agrega `reasoning_effort` solo si el modelo calza con `^(gpt-[5-9]|o[1-9]).*` (no calza con `openai/gpt-…` ni `~openai/…`).
    - `rates()` es por nombre exacto.
    - `Notes.OPENAI_URL` es constante, y NotesChecks.java:167 la afirma.

21. **Retiro de `gpt-4o-transcribe-diarize` el 2027-02-26** (según el plan).
    - Settings.java:39 lo fija como único modelo con voces de OpenAI y `canSeparate()` devuelve true para openai sin condiciones.
    - Tras el retiro, toda transcripción con voces por OpenAI directo fallará con `UserAction` (404).