# MAPA settings-notes

# Mapa 0.8 · proveedores, claves, ajustes, notas, novedades, bienvenida y diagnóstico

Raíz: `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir`
- `SRC` = `<raíz>/app/src/main/java/cl/vozlocal/app`
- `TEST` = `<raíz>/app/src/androidTest/java/cl/vozlocal/app`
- Rama `feature/0.8.0`, sin commits propios todavía (HEAD = merge de 0.7.0). `app/build.gradle:9-10` sigue en `versionCode 12`, `versionName '0.7.0'`.
- Hay cambios sin commitear en `app/src/main/res/drawable/ic_tab_library.xml` e `ic_tab_library_fill.xml` (tres libros, comentario «Biblioteca (0.8.0)»). En HEAD ese ícono es el reloj «history» de Material. Alguien ya lo está tocando.

## 1. Claves, proveedores, modelos, verificación

### 1.1 Settings (`SRC/Settings.java`)
Preferencias en el archivo `"settings"` (`:12`), campo público `final SharedPreferences prefs`.

**Cifrado de claves**
- AES/GCM/NoPadding con clave de AndroidKeyStore, alias `"voz-local-openai"`: `private static synchronized SecretKey key()` (`:78-87`). Una sola clave de Keystore sirve para todos los proveedores.
- Se guardan dos strings por clave: `<prefijo>keyEncrypted` (Base64 NO_WRAP del texto cifrado) y `<prefijo>keyIv` (Base64 NO_WRAP del IV). Al descifrar se usa `GCMParameterSpec(128, iv)`.
- Prefijos existentes:
  - `""` → `keyEncrypted` / `keyIv` (OpenAI)
  - `"custom_"` → servidor propio
  - `"anthropic_"` → Anthropic
- Validación al guardar: `trim()`; vacío borra ambas entradas con `commit()`; más de 8192 caracteres o cualquier espacio lanza `IllegalArgumentException("La clave no debe contener espacios ni saltos de línea.")`; si `commit()` falla, `IOException("No se pudo guardar la clave.")`.

**Métodos exactos**
- `String provider()` (`:18`) → `prefs.getString("provider","openai")`. Valores que se escriben hoy: `"openai"` y `"custom"`.
- `String prefix()` (`:19`) → `provider().equals("openai")?"":"custom_"`. Todo lo que no es `"openai"` cae en `custom_`.
- `boolean hasKey()` (`:20`) → `prefs.contains(prefix()+"keyEncrypted")`.
- `void saveKey(String value) throws Exception` (`:88-95`) y `String apiKey() throws Exception` (`:96-101`): usan `prefix()`; `apiKey()` devuelve `""` si no hay clave.
- `private void encrypt(String prefixKey,String value)` (`:65-72`) y `private String decrypt(String prefixKey)` (`:73-77`): versión genérica por prefijo. Hoy solo la usa Anthropic y `openAiKey()`. `saveKey`/`apiKey` duplican la misma lógica.
- `boolean hasOpenAiKey()` (`:52`) → `prefs.contains("keyEncrypted")`, sin mirar el proveedor. `String openAiKey()` (`:53`) → `decrypt("")`.
- `boolean hasAnthropicKey()` (`:54`), `String anthropicKey()` (`:55`), `void saveAnthropicKey(String)` (`:56`).

**Proveedor y modelo de transcripción**
- `String textModel()` (`:26`) → `"openaiTextModel"`, por defecto `"gpt-transcribe"`.
- `String speakersMode()` (`:24`) → `"speakersMode"`: `"ask"` (por defecto), `"always"`, `"never"`.
- `boolean canSeparate()` (`:28`) → `provider().equals("openai")||prefs.getBoolean("customSpeakers",false)`.
- `boolean defaultSpeakers()` (`:30`) → `canSeparate()&&!speakersMode().equals("never")`.
- `String customBase()` (`:33`) → `"customBase"` con trim, `""` si falta.
- `boolean needsServer()` (`:35`) → `!provider().equals("openai")&&customBase().isEmpty()`.
- `static final String NO_SERVER="Configura la dirección de tu servidor en Ajustes."` (`:36`).
- `ProviderConfig config() throws Exception` (`:31`) = `config(defaultSpeakers())`.
- `ProviderConfig config(boolean speakers) throws Exception` (`:37-43`):
  - OpenAI: `new ProviderConfig("openai","https://api.openai.com/v1", speakers?"gpt-4o-transcribe-diarize":textModel(), apiKey(), speakers)`.
  - Otro: si `customBase()` está vacío lanza `HttpApi.UserAction(NO_SERVER)`; si no, `new ProviderConfig(provider(), customBase(), prefs.getString("customModel","whisper-1"), apiKey(), speakers&&customSpeakers)`.
- `String language()` (`:17`) → `"language"`, por defecto `"es"`; `""` = detección automática.

**Nota**
- `boolean noteAuto()` (`:46`) → `"noteAuto"`, por defecto `true`.
- `String noteProvider()` (`:48`) → `"noteProvider"`, por defecto `"openai"`; el otro valor es `"anthropic"`.
- `String noteModel()` (`:50`) → `"noteModel"`, `""` = recomendado.

**Otras claves de preferencias**
`automatic`, `wifi` (por defecto true), `charging`, `askTitle`, `datePrefix`, `customModel`, `customSpeakers`, `saveTree`, `saveTreeName`, `lastSaveUri`, `localTree`, `lastSeenVersion` (int), `bitacoraOpen`, `welcomed`, `myVoiceName`, `appearance`, `schema` (migraciones en `SRC/VozApp.java:7-13`, hoy en 4), `verifyAt`, `verifyOk`, `verifyMs`, `verifyFor`, `verifyMsg`.

### 1.2 ProviderConfig (`SRC/ProviderConfig.java`)
- `ProviderConfig(String provider,String base,String model,String key,boolean speakers) throws Exception` (`:7-12`). Campos finales `provider, base, model, key, speakers`.
- Valida: esquema https, host presente, sin userinfo, query ni fragmento; modelo con `[A-Za-z0-9_.:/-]{1,120}` (acepta `/`, no acepta `~`). Quita las `/` finales de `base`.
- `String fingerprint()` (`:13`) = hash de `provider|base|model|speakers`. Entra en el `profile` de los puntos de control (`SRC/Transcriber.java:176`).

### 1.3 Cliente y HTTP
**`SRC/OpenAiClient.java`**
- `void verify(String key)` (`:18-21`): `GET https://api.openai.com/v1/models/gpt-4o-transcribe-diarize`.
- `void verify(ProviderConfig config)` (`:22`): `GET config.base+"/models/"+URLEncoder.encode(config.model,"UTF-8")` con Bearer; luego `HttpApi.require(response,"Proveedor")`.
- `JSONObject transcribe(File audio,ProviderConfig config,String language,List<String[]> references,Delta delta)` (`:28-53`):
  - multipart, tope de 25.000.000 bytes, archivo fijo `filename="recording.m4a"` con `Content-Type: audio/mp4`;
  - con voces: `response_format=diarized_json`, `chunking_strategy=auto`, `language`, `known_speaker_names[]` / `known_speaker_references[]`;
  - `gpt-transcribe` con OpenAI: `languages[]` y `stream=true`;
  - resto: `response_format=json` + `language`;
  - devuelve el JSON con `_diarized` agregado; sin voces arma un segmento sintético `{speaker:"text",start:0,end:0,text}`.
- Único punto de llamada: `SRC/Transcriber.java:368` y `:372`.

**`SRC/HttpApi.java`**
- `Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra) throws Exception` (`:46`).
  - `Authorization: Bearer <token>` solo si `token` no está vacío.
  - Solo HTTPS, sin redirecciones, conexión 30 s, lectura `readTimeoutMs` (240 s por defecto).
  - Respuesta máxima de 8 MB. `requestId` sale del header `x-request-id`.
  - SSE solo si `onEvent != null`; exige un evento cuyo `type` termine en `.done`.
- `interface Body{long length();void write(OutputStream)}`; `static Body json(JSONObject)` (`:76`) arma todo en memoria; `Body file(File)` y `void copy(File,OutputStream)` (`:77-78`) son los únicos que reportan `onProgress`.
- `static void require(Response,String service)` (`:79-95`): 401 → «La clave del proveedor no es válida o fue revocada…»; 403; 429 con `insufficient_quota` → sin saldo; 408/429/5xx → `IOException` (se reintenta); resto → `UserAction` con «Revisa el formato del audio…».
- `static String safeToken(String)` (`:98`) acepta `[A-Za-z0-9_.:/-]{1,120}`; `static String safeReason(Exception)` (`:97`).
- `static class UserAction extends Exception`: errores que se muestran a la persona y no se reintentan.
- Las pruebas falsifican HTTP con una subclase anónima de `HttpApi` que sobrescribe `request(...)` (`TEST/NotesChecks.java:165`).

### 1.4 SettingsActivity (`SRC/SettingsActivity.java`)
- `static final String[] MODELS={"gpt-transcribe","gpt-4o-transcribe","gpt-4o-mini-transcribe","whisper-1"}` (`:40`)
- `MODEL_NAMES={"GPT Transcribe","GPT-4o Transcribe","GPT-4o Mini","Whisper"}` (`:41`), `MODEL_DETAILS` (`:42`)
- `SPEAKER_MODES={"ask","always","never"}`, `SPEAKER_NAMES` (`:43`)
- `static String modelName(Settings s)` (`:53`) y `static String modelSummary(Settings s)` (`:54`): «OpenAI · X + separación de voces» o «Tu servidor · X». `modelSummary` también se usa en `SRC/RecordingActivity.java:502`.

**Extras del intent** (`:35-36`, `:58`, `:62`, `openFrom` `:64-70`)
- `focusKey`: abre `keySheet()` solo si `!settings.hasKey()`.
- `voice`: voces conocidas o grabar mi voz; al terminar hace `finish()`.
- `back`: Atrás vuelve a la pantalla anterior.
- `inbox`: abre `saveSheet()` y hace `finish()` al elegir carpeta.
- `noteAi`: abre `noteAiSheet()`.
- Quién los envía: `SRC/MainActivity.java:407`, `:702`, `:729`; `SRC/RecordingActions.java:184`, `:255`, `:281`; `SRC/RecordingActivity.java:931`.

**Sección «Servicio de transcripción»** (`render()` `:101-114`)
- Proveedor (`:103`): valor «OpenAI» o «Tu servidor» → `providerSheet()` (`:385-387`), dos `choice` que hacen `set("provider","openai")` o `set("provider","custom")`. Subtítulo de la hoja: «Cada proveedor usa su propia clave.»
- «Modelo de texto» (`:104`, solo OpenAI) → `modelSheet()` (`:388-389`): recorre `MODELS` con `Pricing.perMinute(m)` y guarda con `set("openaiTextModel",m)`.
- «Servidor y modelo» (`:105`, solo custom) → `custom()` (`:469-480`): campos `customBase`, `customModel` y casilla `customSpeakers`. Si cambia la URL a otro servidor hace `settings.saveKey("")`. Si era la primera dirección y ya hay clave, lanza `verify()`.
- «Separar voces» (`:106`, si `canSeparate()`) → `speakersSheet()` (`:390-392`).
- «Clave de API» (`:107`) → `keySheet()` (`:393-397`): reemplazar o eliminar; eliminar hace `saveKey("")` y cancela `Pipeline.JOB_ID`.
- `keyInput()` (`:402-406`) → `secretInput(title,message,hint,description,Secret secret,Runnable saved)` (`:409-415`). `interface Secret{void save(String value)throws Exception;}` (`:407`). Campo de contraseña, sin autocompletar, hoja con `FLAG_SECURE`. Al guardar: evento `setting_changed action=api_key`, `Pipeline.schedule`, `render()` y después `custom()` si `needsServer()`, o `verify()`.
- «Comprobar conexión» (`:108`) → `verify()`.
- «Idioma del audio» (`:109-111`).
- Textos de pie distintos para OpenAI y servidor propio (`:112-114`).

**Helpers**
- `set(String key,String value)` (`:188`) = guardar + `changed(key)`.
- `toggle(String key,boolean on)` (`:189`).
- `changed(String key)` (`:190`) = `Diagnostics.event("setting_changed",null,"action",key)` + `Pipeline.schedule(this,true)` + `render()`.

**Tarjeta de estado** `statusCard()` (`:154-186`)
- `ready = hasKey && !needsServer && !verifyFailed`.
- Textos: «Listo para transcribir» + `modelSummary` / «No se pudo conectar» / «Falta un paso para transcribir».
- Toque: `verifyError(prefs "verifyMsg")`, `custom()` o `keySheet()`.

**Verificación de conexión** (`:417-459`)
- `private String verifyTarget()` (`:419-423`):
  - OpenAI: `"openai|"+textModel()+"|"+defaultSpeakers()+"|"+hash`
  - custom: `provider+"|"+customBase+"|"+customModel+"|"+customSpeakers+"|"+hash`
  - `hash` = `prefs.getString(prefix()+"keyEncrypted","").hashCode()`.
- `verifyValid()` (`:424`): `verifyAt>0 && verifyTarget().equals(prefs "verifyFor")`.
- `verifyFailed()` (`:425`); `verifyText()` (`:426-433`): «Comprobando…», «Primero agrega tu clave», «Confirma que tu clave funciona», «✓ Conectado · 0,9 s · hace 5 min», «No se pudo conectar · …».
- `verify()` (`:435-452`):
  - sin clave → `keySheet()`; con `needsServer()` → `custom()`;
  - en el hilo `io`: `new OpenAiClient(call).verify(settings.config())`;
  - guarda `verifyAt` (long), `verifyOk` (boolean), `verifyMs` (long), `verifyFor` (String), `verifyMsg` (String);
  - evento `setting_changed action=verify result=<bool> elapsed_ms`;
  - mensaje de fallo: el de `UserAction`, o uno genérico según `openai`.
- `verifyError(String reason)` (`:454-459`): si el texto contiene «clave» ofrece «Revisar la clave»; si no, «Reintentar».
- No se consulta el saldo en ningún lado.

**«IA de la nota»** (`:344-382`)
- `claude()` (`:345`) = `noteProvider().equals("anthropic")`.
- `noteReady()` (`:347`) = `Notes.canGenerate(this)`.
- `noteMissing()` (`:348`): «Falta la clave de Anthropic» / «Falta la clave de OpenAI» / «Con tu servidor, elige Claude».
- `noteAiValue()` (`:349`): `(claude?"Claude":"OpenAI")+" · "+modelo`.
- `noteAiSheet()` (`:352-361`): choice «OpenAI» (detalle según `provider().equals("openai")&&hasKey()`; con servidor propio dice «Solo si transcribes con OpenAI»), choice «Claude (Anthropic)», acción «Modelo: …» y acción de clave de Anthropic.
- `setNoteProvider(String provider)` (`:363`): guarda `noteProvider` y borra `noteModel`.
- `noteModelSheet()` (`:364-372`): campo libre validado con `[A-Za-z0-9_.:/-]{1,120}`; evento `note_model` con `result` = `default` o `custom` (no registra el nombre).
- `anthropicKeySheet()` (`:373-378`), `anthropicKeyInput()` (`:379-382`) → `secretInput(..., settings::saveAnthropicKey, ...)`.
- La fila de la nota se arma en `render()` `:84-88`.

### 1.5 Precios y costos mostrados
**`SRC/Pricing.java`**
- `static double perMinute(String model)` (`:13-21`): tabla fija (gpt-transcribe 0,0045; 4o-mini 0,003; 4o / diarize / whisper-1 0,006); -1 si no se conoce.
- `static double estimate(String model,long audioMs)` (`:22`).
- `static double estimate(String provider,String model,long audioMs)` (`:24`): solo calcula si `"openai".equals(provider)`.
- `static String usd(double)` (`:26-31`): «US$0,012», «< US$0,001», «—» si es negativo.
- `REVIEWED="23-09-2026"` (`:11`).

**Dónde se muestra**
- `SRC/RecordingActions.java:264` y `:274-278`: hoja «¿Separar voces?».
- `SRC/RecordingActivity.java:503-506`: píldoras en «Transcribe este audio», solo con OpenAI.
- `SRC/RecordingActivity.java:662-666`: grilla de métricas. Lee del estado `provider`, `model`, `inTokens`, `outTokens`, `usageSec`.
- `SRC/RecordingActivity.java:676`: «tarifa pública al …».
- `SRC/RecordingActivity.java:786`: pie «≈ US$…», con `transcript.data.provider` y `model`.
- `SRC/Retranscribe.java:55`, `SRC/RetranscribeSheet.java:44` y `:60` (`paid = provider().equals("openai")`).
- `modelSheet` (`SRC/SettingsActivity.java:389`).
- El uso real se acumula en `SRC/Transcriber.java:381-388`: si `usage.type=="duration"` suma `usageSec += usage.seconds`; si no, `inTokens` / `outTokens`. No existe una clave de estado de costo real.

### 1.6 RecordingActions (`SRC/RecordingActions.java`)
- `static void transcribe(Screen s,Recording r,Runnable changed)` (`:244-250`): ya transcrita → `RetranscribeSheet.show`; `!hasKey()` → `missingKey(s)`; `canSeparate()` con modo `"ask"` → `askSpeakers`; si no, `start(s,r,changed,settings.defaultSpeakers())`.
- `static void missingKey(Screen s)` (`:252-256`): hoja «Falta tu clave de API» con el texto «…(por ejemplo OpenAI)…» y botón «Configurar ahora» → `SettingsActivity` con `focusKey=true`.
- `static void askSpeakers(Screen s,Recording r,Runnable changed,Settings settings)` (`:261-284`):
  - modelos de `settings.config(true).model` y `config(false).model`;
  - `live = provider.equals("openai")&&text.equals("gpt-transcribe")`;
  - voces conocidas solo si `provider.equals("openai")` (`:266`); la opción «Grabar mi voz…» también (`:281`);
  - cualquier excepción cae a `start(...defaultSpeakers())`.
- `static void start(Screen s,Recording r,Runnable changed,boolean speakers)` (`:285-289`) → `Pipeline.request(s,r.id,speakers)`.
- `allowBackground` (`:297`) dice «cortar el envío a OpenAI».
- Otras comprobaciones de `hasKey()`: `SRC/MainActivity.java:406` (chip «Configurar transcripción»), `:614`, `:701`; `SRC/ImportActivity.java:90`; `SRC/RetranscribeSheet.java:54`; `SRC/Transcriber.java:161` (`config.key.isEmpty()` → «Agrega una clave de API en Ajustes y pulsa Reintentar.»).
- Otras compuertas `provider().equals("openai")`: `SRC/Transcriber.java:170`, `:174`, `:354`; `SRC/Retranscribe.java:44`, `:80`, `:139`; `SRC/RecordingActivity.java:1213`; `SRC/SettingsActivity.java:95` (fila «Voces conocidas»).

## 2. Notes (`SRC/Notes.java`)

**Constantes**
- `OPENAI_MODEL="gpt-6-luna"` (`:32`), `ANTHROPIC_MODEL="claude-sonnet-5-5"` (`:39`), `REVIEWED="2026-09-29"` (`:40`)
- `OPENAI_URL="https://api.openai.com/v1/chat/completions"` (`:41`)
- `ANTHROPIC_URL="https://api.anthropic.com/v1/messages"` (`:42`)
- `MAX_TRANSCRIPT_CHARS=400_000` (`:44`), `SYSTEM` (`:46-61`), `WORKING_MAX_MS=7*60_000` (`:66`)

**Consultas**
- `static boolean canGenerate(Context c)` (`:80`): `noteProvider=="anthropic" ? hasAnthropicKey() : hasOpenAiKey()`.
- `static String provider(Settings s)` (`:82`) → `"anthropic"` o `"openai"`; cualquier otro valor se normaliza a `"openai"`.
- `static String defaultModel(String provider)` (`:83`).
- `static String model(Settings s,String provider)` (`:85-90`): acepta `noteModel` solo si cumple `[A-Za-z0-9._:-]{2,80}` (sin `/` ni `~`) y si `m.startsWith("claude")` coincide con que el proveedor sea anthropic; si no, usa el recomendado.
- `static String service(String provider)` (`:91`) → «Claude» o «OpenAI».
- `static boolean working(JSONObject state)` (`:71-75`): `noteState=="working"` y `noteStartedAt` entre -60 s y `WORKING_MAX_MS`.
- `static JSONObject load(Context,String id)` (`:78`), `static boolean exists(...)` (`:64`).

**Costos**
- `static double[] rates(String model)` (`:95-106`): tabla por nombre exacto.
- `static double usd(String model,long input,long output)` (`:107`): -1 si no hay tarifa.
- `static double estimateUsd(String model,long audioMs)` (`:109`).

**generate**
- `static void generate(Context c,Recording r,HttpApi http) throws Exception` (`:116-124`): elige la clave con `"anthropic".equals(provider)?s.anthropicKey():s.openAiKey()`; sin clave marca `failed` y lanza `UserAction`.
- `static void generate(Context c,Recording r,HttpApi http,String provider,String model,String key) throws Exception` (`:127-176`), la que usan las pruebas:
  - estado `noteState="working"` + `noteStartedAt` (`:134`);
  - arma `Prompt` (`:140`);
  - evento `note_started` con `provider` y `model` (`:141`);
  - timeout de lectura entre 120 y 300 s (`:143`);
  - despacho en `:145`: `"anthropic".equals(provider)?anthropic(...):openai(...)`;
  - agrega a la nota `version=1`, `provider`, `model`, `createdAt`, `speakers` (`:147`), y `usage` + `costUsd` si hay tarifa (`:148`);
  - escribe `<id>.note.json` bajo `FilesStore.LOCK` solo si sigue siendo el mismo pedido (`ours(st,started,attempt)`, `:149-154`);
  - `noteState="ready"` + `suggestedTitle` (`:158`); evento `note_ready` (`:160`).
- Llamadores: `SRC/Transcriber.java:261` (automática, si `noteAuto()&&transcript.hasText()&&canNote()`, `:237`; estado `notePending` `:239`, `:245`, `:274`) y `SRC/RecordingActivity.java:1053` (manual).

**Estados** (en el `state` de la grabación)
- `noteState`: `working` → `ready` | `failed`
- `noteStartedAt`, `noteError`, `suggestedTitle`, `notePending`, `attempt`
- `Discarded` (`:77`) y cancelación quitan `noteState`.
- Lectura en pantalla: `SRC/RecordingActivity.java:912-929` (con `NOTE_STALE_MS=7 min`), `:124` (`settingsKey()` incluye `noteProvider`, `hasAnthropicKey`, `noteAuto`), `:567`, `:1049` («tu clave de OpenAI o Claude»).

**Llamada a OpenAI**
- `static JSONObject openaiBody(String model,Prompt prompt)` (`:244-251`):
  ```
  {"model":M,
   "messages":[{"role":"system","content":SYSTEM},{"role":"user","content":prompt.text}],
   "response_format":{"type":"json_object"},
   "max_completion_tokens":8000}
  ```
  más `"reasoning_effort":"low"` solo si `model.matches("^(gpt-[5-9]|o[1-9]).*")`.
- `static Answer openai(HttpApi http,String key,String model,Prompt prompt)` (`:252-263`):
  - `http.request("POST",OPENAI_URL,key,"application/json",HttpApi.json(body),null)`: Bearer y `extra==null`;
  - `require(res,"OpenAI",model)`;
  - lee `choices[0].message.content`; `message.refusal` no vacío → `UserAction`; `finish_reason=="length"` → `BadAnswer`;
  - uso: `usage.prompt_tokens` / `completion_tokens` → `{input_tokens,output_tokens}`.
  - No lee `model` ni `usage.cost` de la respuesta.

**Llamada a Anthropic**
- `static JSONObject anthropicBody(String model,Prompt prompt)` (`:266-272`):
  ```
  {"model":M,"max_tokens":16000,"system":SYSTEM,
   "messages":[{"role":"user","content":prompt.text}]}
  ```
  más `"output_config":{"effort":"medium"}` si `model.matches("^claude-(sonnet|opus|fable)-[5-9].*")`.
- `static Map<String,String> anthropicHeaders(String key)` (`:274`): `x-api-key`, `anthropic-version: 2023-06-01`.
- `static Answer anthropic(...)` (`:275-286`): token `""` (sin Authorization); toma el primer bloque `content[].type=="text"`; `stop_reason` `refusal` / `max_tokens`; uso = `input_tokens + cache_read_input_tokens + cache_creation_input_tokens` y `output_tokens`.

**Tipos y errores**
- `static final class Answer{final String text;final JSONObject usage;}` (`:241`).
- `static final class Prompt{final String text;final LinkedHashMap<String,String> tokens;final boolean diarized;}` (`:211`).
- `static void require(HttpApi.Response res,String service,String model)` (`:289-307`): 401; 529 u `overloaded_error` (transitorio); 400/404/413/422 según el mensaje (saldo, contexto, «model»); resto → `HttpApi.require`.
- `static String friendly(Exception e,String service)` (`:181-187`).

**Formato de `<id>.note.json`**
`title, summary, decisions[], tasks[{text,who,when,done}], quotes[{text,who,t?}], tags[], version:1, provider, model, createdAt, speakers{S1:"A"}, usage{input_tokens,output_tokens}, costUsd`.
El Markdown usa `nota_ia: "<service(provider)> · <model>"` (`:475`).

**Pruebas que fijan el contrato** (`TEST/NotesChecks.java`)
- `:118-127`: forma de los cuerpos y headers.
- `:137`: `Notes.usd`.
- `:155-158`: `Notes.model`.
- `:165-181`: URL exacta `https://api.openai.com/v1/chat/completions`, `extra==null`, modelo `gpt-6-luna`.
- `:184`: nota guardada con `provider=="openai"`, `costUsd>0`.

## 3. Novedades (`SRC/Novedades.java`, `<raíz>/app/src/main/assets/novedades.json`)

**Formato**: array JSON de objetos
```
{"version":"0.7.0","date":"2026-09-29","title":"Verbapp: nueva cara, el mismo cerebro","items":["Titular: detalle.", ...]}
```
- `version` obligatorio: sin él la entrada se descarta (`parse` `:42-53`).
- `date` en ISO `yyyy-MM-dd`; se muestra como «29 de septiembre de 2026» (`date()` `:192-195`).
- `title`: texto libre; en la hoja su última palabra va destacada (`ui.highlightLast`, no destaca si la palabra tiene menos de 2 o más de 16 letras).
- `items`: strings. Convención «Titular: detalle.»: `headline(item)` (`:189`) corta en el primer `": "` si está en una posición entre 1 y 48; el detalle se capitaliza. Sin ese corte, el punto va como un solo texto.
- Orden en el archivo libre: se ordena por `compare()` numérico (`:55-60`). Las versiones actuales tienen 4 a 6 puntos.

**Cómo se muestra**
- `static void maybeShow(Screen s)` (`:79-94`), llamado desde `MainActivity.onResume` (`SRC/MainActivity.java:169`):
  - compara `versionCode` con `Settings.lastSeenVersion()`;
  - no interrumpe una grabación;
  - `fresh = last==0 && (!hasRecordings || neverUpdated)`: instalación nueva, solo guarda la versión sin mostrar nada;
  - busca la entrada con `entry(s, versionName(s))`, donde `versionName` quita sufijos con `replaceAll("[ -].*$","")`;
  - hoja sin título: `hero` (píldora «Novedades de la X» + titular) y un `bullet` por punto; botones «Entendido» y «Ver todas las versiones»;
  - evento `ui_action screen=Novedades action=shown result=<versionCode>`.
- `static void showAll(Screen s)` (`:99-111`): historial plegable, la versión actual abierta. Se abre desde Ajustes → «Novedades y versiones» (`SRC/SettingsActivity.java:138`).
- `TEST/IntegrationChecks.java:24` exige que `Novedades.current(c)` no esté vacío: al subir `versionName` a 0.8.0 tiene que existir la entrada `"version":"0.8.0"` con puntos.

## 4. Bienvenida actual (`SRC/MainActivity.java`)
- `private boolean welcome()` (`:686-704`):
  - si `prefs.getBoolean("welcomed",false)` devuelve false sin mostrar; si no, escribe `welcomed=true` antes de mostrar (`:688`);
  - arma una `Sheet` «Bienvenido a Verbapp»: logo `Glass.BrandMark` en círculo menta, lema «Tus palabras, para siempre», un párrafo y 3 filas (íconos `ic_mic_fill`, `ic_sparkle`, `ic_folder`; «Graba sin internet», «Transcribe cuando quieras», «Tus archivos son tuyos»);
  - botones: con clave, «Empezar»; sin clave, «Configurar transcripción» (→ `SettingsActivity` con `focusKey=true`) y «Solo grabar por ahora».
- Dónde se decide: `onCreate` `:162`, `if(saved==null)welcomedNow=welcome();`.
- Convivencia con Novedades: `onResume` `:169-170` solo llama `Novedades.maybeShow` si `!welcomedNow`, y luego pone `welcomedNow=false`. `maybeShow` además se salta sola las instalaciones nuevas.
- Única preferencia: `"welcomed"` (boolean, archivo `settings`). No hay pruebas que la toquen.
- `TEST/RecorderSmokeTest.java:35` lanza `MainActivity` directo y manda `START` al servicio 700 ms después.
- Piezas disponibles:
  - `Screen.sheet(title,message)` (`SRC/Screen.java:109`);
  - `Sheet` (`SRC/Sheet.java`): `add(View)`, `closable(Runnable)`, `option(icon,label,detail,run)`, `action(...)`, `choice(...)`, `primary(label,Style,Check)`, `primary(label,Runnable)`, `secondary(label,Runnable)`, `onDismiss(Runnable)`, `secure()`, `show()`, `dismiss()`, campo `body`;
  - `SheetParts.hero/list/item/option` (`SRC/RecordingActions.java:341-443`);
  - `ui.tile(res,fg,bg,sizeDp,iconDp)`, `ui.heading`, `ui.chip`, `ui.button`, `ui.field`.
- Actividades registradas en `<raíz>/app/src/main/AndroidManifest.xml:16-24`; `MainActivity` es la LAUNCHER.
- Pestañas: `Screen.navigate(int)` (`SRC/Screen.java:103-106`); íconos en `SRC/BottomNav.java:23-25` (`ic_tab_library` / `ic_tab_library_fill`).

## 5. Diagnóstico (`SRC/Diagnostics.java`)
- `static void event(String event,String job,Object... fields)` (`:16-28`). Cada fila de `files/diagnostics/events.jsonl`: `{"time","event","version","job", ...campos permitidos}`. `job` es el id completo de la grabación o `""`.
- **Lista blanca exacta** (`:21`), cualquier otra clave se descarta en silencio:
  `stage, provider, model, http, request_id, code, type, param, elapsed_ms, bytes, duration_ms, part, parts, action, screen, result, error_class, count, runner, net, reason, local, display, idle, battery, mode, kind, source, label`
- Solo los valores String de `action` y `label` pasan por `safeAction` (`:148-162`), que corta en la primera palabra personal y a 48 caracteres. El resto de los valores se guarda tal cual: `provider`, `model`, `result`, `reason`, etc. dependen de que el llamador pase solo identificadores.
- No existen claves de costo, segundos facturados ni tokens. `RecordingActions.java:158` manda `"step"`, que no está en la lista y se pierde.
- Rotación: 2 MB → `events.previous.jsonl`; 30 días.
- `export(Context)` (`:30-41`) → `cache/support.txt`. La cabecera todavía dice «VOZ LOCAL — INFORME DE SOPORTE». Incluye `device()`, el último fallo, `timelines()`, frecuencia de eventos y la secuencia cruda.
- `timelines()` (`:54-70`): por grabación, id corto de 8 caracteres, duración, `modelo st.model`, voces, bloques, reintentos, estado y la bitácora pasada por `redact()` (`:78-87`), que tapa todo lo que va entre « » salvo las etiquetas fijas de `Retranscribe.label`, además de títulos y nombres. No imprime `provider`.
- Eventos que ya llevan `provider` / `model`:
  - `job_start` (`SRC/Transcriber.java:184`): provider, model, bytes, duration_ms, net, runner, mode;
  - `note_started`, `note_ready`, `note_failed` (`SRC/Notes.java:141`, `:160`, `:173`);
  - `api_rejected` (`SRC/HttpApi.java:89`, `SRC/Notes.java:304`): http, request_id, code, type, param;
  - `part_complete` (`SRC/Transcriber.java:391`): part, parts, elapsed_ms;
  - `setting_changed` con `action` = `verify`, `api_key`, `anthropic_key`, `note_provider` (`result` = proveedor), `note_model` (`result` = `default` / `custom`), `provider`, `openaiTextModel`.
- `TEST/FeatureChecks.java:62` comprueba que una clave fuera de la lista (`api_key`) no llega al informe.

# PUNTOS DE EXTENSION

Rutas: `SRC` = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app`.

## A. Settings: tercer proveedor con su propia clave
1. **Prefijo de clave por proveedor.** `Settings.prefix()` (`Settings.java:19`) es binario. Cambiarlo a un mapa: `"openai"→""`, `"openrouter"→"openrouter_"`, resto → `"custom_"`. Con eso `hasKey()`, `saveKey()` y `apiKey()` funcionan sin tocarlos.
2. **Unificar el cifrado.** `saveKey`/`apiKey` (`:88-101`) duplican `encrypt`/`decrypt` (`:65-77`). Dejarlos como `encrypt(prefix(),v)` y `decrypt(prefix())`, y agregar `hasOpenRouterKey()`, `openRouterKey()`, `saveOpenRouterKey(String)` al estilo de los de Anthropic (`:54-56`). La nota los necesita aunque se transcriba con otro proveedor.
3. **`config(boolean speakers)`** (`:37-43`): rama `"openrouter"` → `new ProviderConfig("openrouter","https://openrouter.ai/api/v1", <modelo>, apiKey(), speakers&&<el modelo separa voces>)`. Preferencia nueva sugerida: `openrouterModel` (por defecto `microsoft/mai-transcribe-2`, o `"auto"` para «Automático (recomendado)»).
4. **`needsServer()`** (`:35`) y **`canSeparate()`** (`:28`): hoy usan `!provider().equals("openai")`. Convertirlos en `provider().equals("custom")` y en una consulta a la receta del modelo. Conviene un helper `boolean custom()`.
5. `ProviderConfig` acepta `microsoft/mai-transcribe-2` sin cambios. `fingerprint()` ya incluye proveedor, base y modelo, así que cambiar de modelo invalida los puntos de control.

## B. Cliente de transcripción
- Único punto de llamada: `Transcriber.java:368` y `:372`, `new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta)`.
- Menor cambio: una interfaz con `JSONObject transcribe(File,ProviderConfig,String language,List<String[]> refs,Delta)` y `void verify(ProviderConfig)`, elegida por `config.provider`. `OpenRouterClient` devuelve el mismo JSON normalizado que espera `Transcript.fromParts`: `segments[{speaker,start,end,text}]`, `_diarized`, y `usage`.
- `Transcriber.java:381-388` necesita una rama nueva para `usage.cost` y `usage.seconds`, con una clave de estado como `costUsd` (acumulada por bloque). Esa clave también debe entrar en `Retranscribe.BEFORE` (`Retranscribe.java:26-27`) para que sobreviva al cambio de versión.
- `HttpApi.request` sirve tal cual (Bearer, HTTPS). Para el JSON con base64 hace falta un `HttpApi.Body` que escriba en streaming con `length()` calculado de antemano y que llame a `touch()`/`onProgress`, porque hoy solo `copy(File,…)` reporta progreso y alimenta al vigilante de `Transcriber.java:360-366`.

## C. Verificación de conexión
- `SettingsActivity.verify()` (`:442`) llama a `new OpenAiClient(call).verify(settings.config())`. Despachar por proveedor: OpenRouter → `GET https://openrouter.ai/api/v1/key`, que además trae el saldo.
- Guardar el saldo en una preferencia nueva (por ejemplo `verifyBalance`) y mostrarlo en `verifyText()` (`:431`) y en `statusCard()` (`:171`).
- `verifyTarget()` (`:419-423`) necesita una rama propia: `"openrouter|"+openrouterModel+"|"+defaultSpeakers()+"|"+hash`.

## D. Ajustes (interfaz)
- `providerSheet()` (`:385-387`): agregar `choice("OpenRouter", …, ()->set("provider","openrouter"))`. El subtítulo «Cada proveedor usa su propia clave.» sigue siendo cierto.
- `render()` (`:78`, `:103-105`, `:112-114`): el booleano `openai` decide todo. Pasar a tres ramas: valor de la fila Proveedor, fila de modelo (hoja nueva que lista el catálogo con precio por hora y marca «Nuevo · sin probar»), y texto de pie.
- `modelName()` y `modelSummary()` (`:53-54`): rama OpenRouter («OpenRouter · MAI Transcribe 2»). `modelSummary` se reutiliza en `RecordingActivity.java:502` y en la tarjeta de estado.
- `keyInput()` (`:403-404`): textos por proveedor (pista `sk-or-…`, «Créala en openrouter.ai → Keys»). `secretInput` y la interfaz `Secret` se reutilizan sin cambios.
- La fila «Voces conocidas» (`:95`) depende de `openai`: decidir si se oculta con OpenRouter, que no acepta `known_speaker_*`.
- `openFrom` con `focusKey` (`:66`) ya cubre «Configurar transcripción». Para que el onboarding lleve directo a elegir proveedor, agregar un extra nuevo (por ejemplo `provider`) que abra `providerSheet()`.

## E. Notas: tercer proveedor con formato OpenAI
1. `Settings.noteProvider()` (`:48`): aceptar `"openrouter"`.
2. `Notes.provider(Settings)` (`Notes.java:82`): hoy todo lo que no es `"anthropic"` se vuelve `"openai"`. Devolver tres valores.
3. `Notes.canGenerate` (`:80`): rama `hasOpenRouterKey()`.
4. `Notes.generate(Context,Recording,HttpApi)` (`:117-120`): elegir la clave y el texto de «falta la clave» por proveedor.
5. `Notes.defaultModel` (`:83`): `"openrouter"` → `"~openai/gpt-luna-latest"`. Ofrecer también `~anthropic/claude-sonnet-latest` y `~google/gemini-flash-latest`.
6. `Notes.model(Settings,String)` (`:85-90`): validar por proveedor. Para OpenRouter, una regex que acepte `~` y `/`, y sin la regla del prefijo `claude`.
7. Despacho en `Notes.generate` `:145`: parametrizar `openai(...)` con URL y nombre de servicio: `openai(http,url,service,key,model,prompt)`, con `OPENROUTER_URL="https://openrouter.ai/api/v1/chat/completions"`. El parseo de `choices[0].message.content`, `refusal` y `finish_reason` sirve igual.
8. `Notes.Answer` (`:241`): agregar `model` (de `json.optString("model")`) y el costo (de `usage.cost`). Guardar en la nota (`:147-148`) `model` = alias pedido, un campo nuevo `modelUsed` = versión que respondió, y `costUsd` = `usage.cost` cuando exista.
9. `Notes.service(provider)` (`:91`): caso «OpenRouter». Afecta los mensajes de `friendly` y `require`, y `nota_ia` en el Markdown (`:475`), que podría mostrar `modelUsed`.
10. `openaiBody` (`:244-251`): decidir qué campos se mandan a OpenRouter (ver riesgos).
11. `Notes.require` (`:289-307`): agregar 402 (sin créditos) y los mensajes de OpenRouter.
12. Interfaz: `noteAiSheet()` (`SettingsActivity.java:352-361`), `noteMissing()` (`:348`), `noteAiValue()` (`:349`), `noteModelSheet()` (`:364-372`, su regex) y `RecordingActivity.java:1049`. Con proveedor de transcripción OpenRouter lo natural es que la nota use la misma clave sin configurar nada más.
13. Las firmas que usan las pruebas (`openaiBody`, `anthropicBody`, `anthropicHeaders`, `generate` de 6 argumentos, `model`, `usd`, `estimateUsd`, `working`) deben conservarse o las pruebas actualizarse (`NotesChecks.java:118-127`, `:155-184`).

## F. Precios y costos
- `Pricing.estimate(String provider,String model,long audioMs)` (`Pricing.java:24`) es el único punto que decide si hay costo estimado. Extenderlo con una tarifa por hora por modelo de OpenRouter, guardada junto al catálogo y corregida con el promedio observado de `usage.cost / usage.seconds`.
- Todos los lugares que muestran costo ya pasan por ahí; no hace falta tocarlos para que aparezca el estimado.
- Para mostrar costo real en vez de estimado: en `RecordingActivity.stats` (`:662-666`) preferir `st.costUsd` si existe, y cambiar el texto «tarifa pública al…» (`:676`).

## G. Catálogo que se actualiza solo
- No existe nada parecido hoy. Abstraer una clase nueva (por ejemplo `Models`):
  - caché en un archivo propio (por ejemplo `files/openrouter-models.json`) con fecha de descarga;
  - `GET https://openrouter.ai/api/v1/models?output_modalities=transcription&sort=newest` sin token (`HttpApi.request` con token `""` no manda Authorization);
  - receta por modelo: cómo pedir voces (`provider.options.azure.diarization.enabled`, `provider.options.deepgram.diarize`), formato de audio, límite;
  - respaldo embebido con `microsoft/mai-transcribe-2` y `deepgram/nova-3` para cuando no hay red.
- Disparo diario: `MainActivity.onResume` o al abrir la hoja de modelo, en un hilo de fondo. No usar `JobScheduler` con `Pipeline.JOB_ID`.

## H. Novedades 0.8.0
- Agregar al inicio de `<raíz>/app/src/main/assets/novedades.json` una entrada `{"version":"0.8.0","date":"2026-09-30","title":"…","items":[…]}` de 4 a 6 puntos «Titular: detalle.», con el titular de 48 caracteres o menos antes de `": "` y la última palabra del título de 2 a 16 letras.
- Subir `app/build.gradle:9-10` a `versionCode 13` y `versionName '0.8.0'`. Sin la entrada falla `IntegrationChecks.java:24`.

## I. Onboarding de primera instalación
- Punto único de decisión: `MainActivity.java:162` (`if(saved==null)welcomedNow=welcome();`) y `welcome()` `:686-704`.
- Reemplazo mínimo: mantener la firma `boolean welcome()` y cambiar su contenido por el onboarding. Así la lógica de `welcomedNow` y `Novedades.maybeShow` (`:169-170`) sigue intacta.
- Conservar la preferencia `"welcomed"`: los usuarios actuales ya la tienen en true y no verán el onboarding, que es lo pedido («la primera vez que lo instalan»).
- Si el onboarding tiene varios pasos (micrófono, proveedor y clave, carpeta 0-Inbox), marcar `welcomed` al terminar o saltar, y guardar el paso en una preferencia aparte para poder retomarlo.
- Si se hace como actividad nueva, registrarla en `AndroidManifest.xml:16-24` y lanzarla desde `onCreate`.
- Destinos ya disponibles para los pasos:
  - `SettingsActivity` con `focusKey=true` (clave);
  - `SettingsActivity` con `back=true` + `inbox=true` (carpeta rápida; hace `finish()` al elegir);
  - `SettingsActivity` con `voice=true` (grabar mi voz; hace `finish()` al guardar);
  - permiso de micrófono: códigos de solicitud 10 y 11 en `MainActivity.java:677-684`;
  - permiso de notificaciones: `RecordingActions.askNotifications` (`RecordingActions.java:291-293`).
- Registrar en el diagnóstico con claves ya permitidas: `Diagnostics.event("ui_action",null,"screen","Onboarding","action","step","result",n)`.

## J. Ícono de Biblioteca
- Solo dos archivos: `<raíz>/app/src/main/res/drawable/ic_tab_library.xml` (contorno) e `ic_tab_library_fill.xml` (relleno), referenciados en `BottomNav.java:23` y `:25`.
- Ya tienen un cambio sin commitear (tres libros). Si se reemplaza el dibujo, mantener los nombres y el viewport de 24×24; el color lo pone `setImageTintList`.

## K. Diagnóstico
- Para registrar costo y segundos hay que agregar las claves a la lista de `Diagnostics.java:21`, por ejemplo `cost_usd` y `seconds` (numéricas). Con eso pueden ir en `part_complete` (`Transcriber.java:391`), `job_complete` (`:251`) y `note_ready` (`Notes.java:160`).
- `provider` y `model` ya están permitidos. Conviene un saneador para `model` (regex que acepte `~`, `/`, `.`, `:`, `-`) antes de registrarlo.
- Opcional: agregar `st.provider` a la línea de `timelines()` (`:64`) y actualizar la cabecera «VOZ LOCAL» (`:32`).

# RIESGOS

Rutas: `SRC` = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app`.

## 1. «Todo lo que no es openai es servidor propio»
Si solo se escribe `provider="openrouter"` sin tocar nada más:
- `Settings.prefix()` (`Settings.java:19`) devuelve `custom_`: la clave de OpenRouter compartiría el espacio de la del servidor propio y la pisaría.
- `needsServer()` (`:35`) da true con `customBase` vacío: la tarjeta de estado dice «Configura la dirección de tu servidor», y `verify()` y `keyInput()` abren `custom()`.
- `config()` (`:41-42`) lanza `NO_SERVER`, o manda audio y clave al `customBase` que hubiera quedado guardado.
- `canSeparate()` (`:28`) depende de `customSpeakers`.
- `custom()` (`SettingsActivity.java:476`) hace `saveKey("")` al cambiar la URL, con el prefijo del proveedor activo.
- `verifyTarget()` (`:421`) usa `customBase` / `customModel`.
- `modelName` / `modelSummary` (`:53-54`) dicen «Tu servidor».

Compuertas que hay que revisar una por una:
- `provider.equals("openai")`: `RecordingActions.java:264`, `:266`, `:281`; `Transcriber.java:170`, `:174`, `:354`; `Retranscribe.java:44`, `:80`, `:139`; `RetranscribeSheet.java:60`; `RecordingActivity.java:503`, `:1213`; `SettingsActivity.java:78`, `:95`, `:353-354`, `:403`, `:440`; `OpenAiClient.java:31`.
- `optString("provider","openai")` como valor por defecto: `RecordingActivity.java:662` y `:786`.

## 2. Verificación
- `OpenAiClient.verify(ProviderConfig)` (`OpenAiClient.java:22`) hace `GET base+"/models/"+URLEncoder.encode(model)`. Con `microsoft/mai-transcribe-2` queda `microsoft%2Fmai-transcribe-2`, y además la ruta de detalle de OpenRouter es `/api/v1/model/{autor}/{slug}` (singular). Hay que usar `GET /api/v1/key`.
- `verifyError` (`SettingsActivity.java:457`) decide el botón buscando la palabra «clave» en el mensaje: los textos nuevos deben respetar eso.

## 3. Cliente de transcripción atado a OpenAI (`OpenAiClient.java:28-53`)
- Siempre multipart. El plan (docs#576) dice que en multipart OpenRouter descarta `provider.options`, así que la separación de voces fallaría sin avisar: hay que usar JSON con `input_audio`.
- `response_format=diarized_json` y `chunking_strategy` no existen en OpenRouter (solo `json` / `verbose_json`).
- Nombre y tipo fijos `recording.m4a` / `audio/mp4`. MAI-2 rechaza m4a con 400 y necesita WAV, MP3 o FLAC.
- Tope fijo de 25 MB (`:29`). Un WAV de 16 kHz mono de 15 min pesa unos 29 MB, y en base64 unos 38 MB.
- `known_speaker_*` no existe en OpenRouter. El reintento de `Transcriber.java:369-373` busca el texto `known_speaker` en el error.
- `Transcript.fromParts` espera `segments[].speaker` como string. OpenRouter manda índices enteros, y pueden venir en `words[].speaker` o `speaker_label`, o como marcas `<|speaker:N|>` en el texto (Fish).
- El streaming de `gpt-transcribe` (`languages[]`, SSE, «Texto en vivo» en `RecordingActions.java:264`) no aplica.
- La unión de voces entre bloques hoy depende de las muestras de voz que acepta OpenAI (`Transcriber.java:211-223`). Con OpenRouter hay que unir por solape o por «anclas», y el tamaño de bloque (`speakerBlockMs`, `BLOCK_TEXT_MS`) está pensado para OpenAI.
- Los proveedores cortan a unos 60 s de proceso y el vigilante (`responseLimit`, `Transcriber.java:353-366`) está calibrado para los tiempos de OpenAI.

## 4. HttpApi
- `HttpApi.json(JSONObject)` (`HttpApi.java:76`) arma todo el cuerpo en memoria: con base64 de 30 a 40 MB hay riesgo de quedarse sin memoria. El cuerpo tiene que escribirse en streaming con `length()` exacto, porque se usa `setFixedLengthStreamingMode` (`:55`).
- Solo `copy(File,…)` (`:78`) llama a `touch()` y `onProgress`. Un cuerpo nuevo que no lo haga deja `lastActivity` quieto y el vigilante aborta por «el envío dejó de avanzar»; tampoco avanza el progreso de subida en pantalla.
- `requestId` sale de `x-request-id` (`:63`, `:68`). No está confirmado que OpenRouter lo mande.
- `HttpApi.require` (`:79-95`) no conoce el 402 de OpenRouter (sin créditos): caería en «Proveedor · HTTP 402. Revisa el formato del audio y los parámetros del modelo.» `Notes.require` (`Notes.java:296`) tampoco trata el 402. El formato de error de OpenRouter es `{"error":{"code":<número>,"message","metadata"}}`, sin `type`.
- La detección de saldo (`:92`) busca `insufficient_quota` en un 429, que es propio de OpenAI.
- Respuesta limitada a 8 MB (`:67`): el catálogo filtrado cabe; un `verbose_json` con palabras de un audio muy largo habría que medirlo.
- `setInstanceFollowRedirects(false)` (`:51`): un 3xx no se sigue.

## 5. Notas
- `Notes.model()` (`Notes.java:87`) usa `[A-Za-z0-9._:-]{2,80}`: rechaza `/` y `~`, así que cualquier id de OpenRouter cae en silencio al modelo por defecto. Y la regla `m.startsWith("claude")` (`:88-89`) no reconoce `~anthropic/claude-sonnet-latest`.
- `noteModelSheet` (`SettingsActivity.java:368`), `ProviderConfig.java:10` y `HttpApi.safeToken` (`HttpApi.java:98`) no aceptan `~`.
- `openaiBody` (`Notes.java:244-251`):
  - `reasoning_effort` solo se agrega si el modelo empieza por `gpt-[5-9]` u `o[1-9]`. Con `~openai/gpt-luna-latest` no coincide y el modelo razonaría con su esfuerzo por defecto: más lento y más caro.
  - `max_completion_tokens` y `response_format:{"type":"json_object"}` hay que validarlos contra OpenRouter y contra cada alias. `parseAnswer` tolera texto alrededor del JSON, pero un 400 por parámetro no soportado no se recupera solo.
- `rates()` (`:95-106`) va por nombre exacto: con alias no hay `costUsd` y `estimateUsd` devuelve -1. `output=model.startsWith("claude-sonnet")?2500:1500` (`:109`) tampoco coincide. Hay que usar `usage.cost`.
- `Answer` no guarda el `model` de la respuesta: sin ese cambio no se puede mostrar qué versión respondió.
- `Notes.provider()` (`:82`) convierte cualquier valor desconocido en `"openai"`: si se guarda `noteProvider="openrouter"` sin tocarlo, se mandaría la clave de OpenAI a `api.openai.com`. `service()` (`:91`) diría «OpenAI» en errores y en `nota_ia`.
- `canGenerate` con `noteProvider="openai"` mira `keyEncrypted` aunque el proveedor de transcripción sea otro. Un usuario que migra a OpenRouter y borra su clave de OpenAI se queda sin nota. `setNoteProvider` borra `noteModel`: una migración automática también debe hacerlo.
- `friendly()` (`:185`) solo deja pasar mensajes que empiezan con el nombre del servicio.
- Las pruebas fijan la URL exacta de OpenAI y `extra==null` (`NotesChecks.java:167`), el modelo `gpt-6-luna` (`:171`, `:184`) y `costUsd>0`. Refactorizar `openai(...)` sin mantener el camino OpenAI idéntico las rompe.

## 6. Costos
- `Pricing.estimate(provider,…)` devuelve -1 para todo lo que no sea `"openai"`: desaparecen en silencio las píldoras de costo de `askSpeakers`, las de «Volver a transcribir» y el pie «≈ US$».
- `RetranscribeSheet.java:60` diría «Se envía de nuevo» en vez de «Se cobra de nuevo».
- `pricing.prompt` del catálogo no trae unidad (por segundo, por hora en los MAI, por token en gpt-4o y gemini): no se puede convertir a US$/hora sin una tabla por modelo.
- `Transcriber.java:386` asume que `usage.type=="duration"` viene con `seconds`. El `usage` de OpenRouter (`cost`, `seconds`) puede no traer `type`, y entonces caería en la rama de tokens con ceros.
- Textos fijos que nombran a OpenAI: `RecordingActions.java:253` y `:297`; `Transcriber.java:354`; `SettingsActivity.java:113`, `:563`, `:647`; `RecordingActivity.java:1049`.

## 7. Diagnóstico y privacidad
- La lista blanca es por clave, no por valor (`Diagnostics.java:21`). Solo `action` y `label` se sanean. Si se agregan `cost_usd` o `seconds`, pasar solo números.
- No registrar nunca el saldo de la cuenta, la etiqueta de la clave que devuelve `/api/v1/key`, el id de generación ni el cuerpo de un error: `reason` pasa por `safeReason` solo si el llamador lo usa.
- `model` se guarda tal cual: un modelo escrito a mano llega entero al informe.
- Las bitácoras de `Pipeline.log` viajan en el informe pasando por `redact()`, que tapa todo lo que va entre « »: no poner ahí datos personales fuera de comillas. El nombre del modelo sí puede ir.
- Una clave no permitida se pierde sin aviso (ya pasa con `step` en `RecordingActions.java:158`): una prueba nueva debería comprobar que `cost_usd` sí llega. `FeatureChecks.java:62` comprueba el caso contrario.

## 8. Onboarding y novedades
- `welcome()` escribe `welcomed=true` antes de mostrar (`MainActivity.java:688`). Si el onboarding tiene varios pasos y el usuario sale a mitad, no vuelve a aparecer.
- Solo se evalúa con `saved==null` (`:162`): una rotación no lo repite, pero tampoco restaura una hoja a medio camino.
- `Novedades.maybeShow` llama a `setLastSeenVersion` antes de mostrar y trata como «fresh» solo cuando `last==0`. Si el onboarding lanza otra actividad, al volver `onResume` ya tiene `welcomedNow=false` y llamará a `maybeShow`: en instalación nueva no muestra nada, pero conviene una prueba.
- `RecorderSmokeTest.java:35-38` lanza `MainActivity` y manda `START` al servicio a los 700 ms. Hoy la bienvenida es un diálogo encima y no estorba. Un onboarding que reemplace el contenido, lance otra actividad o pida permisos al abrir puede romper esa prueba, o mostrarse durante una grabación. `maybeShow` ya evita interrumpir con `RecorderService.activeId!=null`; `welcome()` no.
- `Sheet` es un `Dialog` sin estado guardado: no sirve tal cual como asistente de varias páginas con «Atrás».
- Subir a 0.8.0 sin la entrada en `novedades.json` rompe `IntegrationChecks.java:24` y deja a los que actualizan sin hoja de novedades.

## 9. Datos del plan sin confirmar
Marcados así en `docs/PLAN-openrouter.md`; no fijarlos en contratos sin probar con la key real:
- tope del JSON con `input_audio`;
- forma exacta de `verbose_json` por proveedor (segmentos frente a palabras, nombre del campo de hablante);
- si `GET /api/v1/key` devuelve el saldo con el formato esperado;
- `gemini-3.5-transcribe` tiene tope de 30 min con voces;
- `gpt-4o-transcribe-diarize` devuelve 404 en OpenRouter y OpenAI lo retira el 2027-02-26: el camino de OpenAI directo con voces conocidas tiene fecha de término.

## 10. Estado del árbol
`ic_tab_library.xml` e `ic_tab_library_fill.xml` tienen cambios sin commitear: otra tarea los está editando, riesgo de pisarse. `feature/0.8.0` no tiene commits propios todavía.