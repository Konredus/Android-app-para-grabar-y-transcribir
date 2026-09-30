# MAPA audio-tests

# Mapa: audio y pruebas (rama `feature/0.8.0`, sin código de OpenRouter todavía)

Raíces (todas las citas son relativas a ellas):
- MAIN = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/`
- TEST = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/androidTest/java/cl/vozlocal/app/`
- Plan: `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/docs/PLAN-openrouter.md`

Estado de partida: "openrouter" solo aparece en `docs/PLAN-openrouter.md` y `CHANGELOG.md:51`. En MAIN no hay nada de FLAC, WAV, `Base64OutputStream`, remuestreo ni concatenación (grep sin resultados). `minSdk 26`, `compileSdk 35`, Java 17, sin dependencias (`app/build.gradle`). El manifiesto no declara `largeHeap`.

## 0. Formatos de audio que existen hoy

| Origen | Dónde | Formato |
|---|---|---|
| Grabación | `RecorderService.java:133-138` | `MediaRecorder`, MIC, contenedor MPEG_4, AAC, 96000 bps, 44100 Hz, 1 canal → `filesDir/recordings/<uuid>.m4a`. `MIN_MS=3000` (l.29) |
| Muestra de voz grabada en Ajustes | `SettingsActivity.java:731-736` | Igual: AAC 96 kbps, 44,1 kHz, mono → `cacheDir/voice-take.m4a`, luego `Voices.add`/`replaceAudio` la MUEVE |
| Muestra de voz recortada de una grabación | `RecordingActivity.java:1224-1229` | `AudioConvert.convert(r.audio, tmp, from, min(to, from+Voices.MAX_MS), new HttpApi())` → AAC-LC 96 kbps con la frecuencia y canales (1 o 2) DEL ORIGEN |
| Importación | `ImportService.java:52` | `AudioConvert.convert(...)` a 96 kbps. Si el origen ya es AAC y se importa entero, se remultiplexa sin tocar (`AudioConvert.java:25`): conserva perfil, frecuencia y canales originales (puede ser 48 kHz, estéreo o más canales) |
| Bloques | `AudioParts.java:55-57` | `filesDir/blocks/<id>/block-<i>.m4a` (temporal `block-<i>.tmp` + rename), copia AAC sin recodificar |

Muestras de voz (`Voices.java`): archivo `filesDir/voices/<id>.m4a`, índice `filesDir/voices/voices.json` = `[{id,name,me,createdAt,use}]` (AtomicFile). `MIN_MS=3000`, `MAX_MS=9500`, `MIN_BYTES=1000` (l.32-34). Id `"me"` o `[a-z0-9]{8}` (validación `[a-z0-9]{6,12}`, l.57). Constantes: `MINE="voz_propia"`, `ME="voice:me"`, `ME_ID="me"`, `TARGET="voice:"`. A 96 kbps una muestra de 9,5 s pesa ~114 KB.

## 1. Utilidades existentes (firmas exactas)

### AudioConvert.java
- `interface Progress { void update(String stage,long positionMs,long totalMs); }` (l.10). Etapas: "Convirtiendo audio", "Preparando audio sin reconvertir", "Verificando audio".
- `static long duration(File file)throws Exception` (l.11): `MediaMetadataRetriever.METADATA_KEY_DURATION`, en ms.
- `static void convert(File source,File target,long startMs,long endMs,HttpApi cancel)throws Exception` (l.12)
- `static void convert(File source,File target,long startMs,long endMs,HttpApi cancel,Progress progress)throws Exception` (l.13, 96000 bps)
- `static void convert(File source,File target,long startMs,long endMs,HttpApi cancel,Progress progress,int bitrate)throws Exception` (l.15)
- `private static void remux(MediaExtractor extractor,MediaFormat format,File target,long duration,HttpApi cancel,Progress progress)throws Exception` (l.66)

Qué hace `convert`: decodifica cualquier pista `audio/*` con `MediaCodec` y recodifica a AAC-LC en MP4. Salida con la MISMA frecuencia y canales que el origen: no remuestrea ni mezcla a mono.
- Validaciones: origen distinto del destino (l.16); `startMs>=0`, `endMs-startMs>=500`, `endMs<=duración+100` (l.17); canales 1 o 2 y solo PCM de 16 bits, si no `IOException("Unsupported PCM")` (l.39); menos de medio segundo → "Audio too short" (l.63).
- Recorte exacto por muestra según el pts del decodificador (l.42-43); `seekTo(startMs*1000, SEEK_TO_PREVIOUS_SYNC)` (l.28).
- Atajo sin recodificar si `bitrate>=96000`, el origen es `audio/mp4a-latm`, `startMs==0` y `endMs>=length-100` (l.25).
- Vigilancia: 60 s sin avance de pts → "Audio decoder stopped advancing" (l.61). Borra el destino si falla (l.64). Cancelación con `cancel.check()`.
- El bucle decodificar→PCM está en línea (l.34-62), acoplado al codificador AAC: no hay una abstracción de "destino de PCM" reutilizable.

### AudioParts.java
- `static final class Part {final File file;final double offset;final long durationMs; Part(File file,double offset); Part(File file,double offset,long durationMs)}` (l.18). `offset` en segundos.
- `interface Log{void line(String message);}` (l.19); `static final long SINGLE_MAX_MS=12*60_000` (l.22)
- `static File blockDir(Context c,String id)` (l.25); `static void clearBlocks(Context c,String id)` (l.26): borra TODOS los archivos de la carpeta, cualquier extensión.
- `static List<Part> plan(Context c,Recording r,HttpApi http,long targetMs,JSONArray cached,List<Long> cutsOut,Log log)throws Exception` (l.33)
  - `targetMs` mínimo 60 s. Solo corta si `total>SINGLE_MAX_MS` o el archivo pesa más de 20 MB (l.39).
  - Un corte por cada múltiplo de `targetMs`, en `quietest(source,t,10_000,http)`, si queda a más de 30 s del anterior y a más de 20 s del final (l.41-44).
  - Resguardo: un tramo de más de 20 MB (estimado con bytes/ms del m4a) se parte por la mitad (l.48-49).
  - Una sola parte → `Part(source,0,total)`: es el archivo original, no una copia (l.52).
  - Los cortes son contiguos `[a,b)`: no existe solape entre bloques. Quedan en el estado como `"cuts"` (arreglo de ms, con 0 y el total).
- `static void remuxRange(File source,File target,long fromMs,long toMs,HttpApi http)throws Exception` (l.64): copia AAC→AAC con `seekTo(fromMs*1000, SEEK_TO_CLOSEST_SYNC)` y tiempos rebasados a 0. Si el origen no es AAC, cae a `AudioConvert.convert` (l.70).
- `static long quietest(File source,long centerMs,long spanMs,HttpApi http)` (l.88): decodifica solo centro±margen, energía en ventanas de 250 ms; asume PCM de 16 bits little-endian; tope 20 s; ante cualquier error devuelve `centerMs`.
- `static final class Clip{final String label;final double start,end;}` (l.119); `static final double MIN_TALK_S=10` (l.121)
- `static List<Clip> pickReferences(JSONArray segments,int limit,Set<String> exclude)` (l.132): lógica pura sobre `segments[{speaker,start,end,text}]`. Tramos de 2,5 s o más con 3 palabras o más, sin otra voz a ±0,5 s; recorta `start=a+0.4`, `end=min(b-0.4,start+9.5)`; solo personas con 10 s o más de habla; con 1 o 2 personas, dos muestras de cada una.
- `static List<String[]> references(Context c,Recording r,JSONObject first,HttpApi http,int limit,Set<String> exclude)` (l.163): devuelve `{nombre enviado ("voz_1","voz_1b"), "data:audio/mp4;base64,…", "block0:<label>", "Persona N (mm:ss–mm:ss)"}`. Recorta con `AudioConvert.convert` a `cacheDir/<id>-ref-<n>.m4a`, lee todo el archivo a memoria y lo borra.
- Divisor antiguo por bytes, solo para pruebas de regresión: `static List<Part> prepare(Context c,Recording r,HttpApi http)` (l.180) y `prepare(Context,Recording,HttpApi,long singleLimit,long partLimit)` (l.183).

### Voices.java (lo que toca audio)
- `static File dir(Context c)` (l.54); `static File file(Context c,String id)` (l.58); `static File file(Context c)` (l.66, la tuya)
- `static List<Voice> list(Context c)` (l.78); `static List<Voice> used(Context c)` (l.91); `static List<Voice> selected(Context c)` (l.93, las usadas hasta `Transcriber.MAX_KNOWN`=4, la tuya primero y luego por nombre)
- `static Voice add(Context c,String name,File clip,boolean me)throws IOException` (l.99); `static void replaceAudio(Context c,String id,File clip)throws IOException` (l.139); `static boolean remove(Context c,String id)` (l.128)
- `static List<String[]> references(Context c)` (l.150) y `static String[] reference(Context c)` (l.74): `{sentName, "data:audio/mp4;base64,…", target, label}`
- `static String fingerprint(Context c)` (l.164): `"none"`; con solo tu voz `"<bytes>-<lastModified>"`; si no `"id:bytes-lastModified,…"`.
- `Voice`: `target()` → `"voice:me"` o `"voice:<id>"`; `sentName()` → `"voz_propia"` o `"voz_<id>"`; `label()`.
- Las voces conocidas solo se envían si el proveedor es OpenAI: `Transcriber.java:170`.

### WaveData.java
Decodificación con `MediaExtractor`+`MediaCodec` solo para la envolvente (600 valores). Todo lo de PCM es privado y calcula energía, no entrega muestras:
- `private static float[] decode(Context c,Recording r,Job job,Progress progress)` (l.90)
- `private static boolean accumulate(ByteBuffer data,MediaCodec.BufferInfo info,int encoding,int channels,int rate,long durationUs,double[] sum,int[] count)` (l.226): lee PCM de 16 bits, float, 32 bits y 8 bits, con `ByteOrder.nativeOrder()`.
- `private static boolean windowed(...)` (l.166): desde 90 s mide ventanas con seek + `codec.flush()`.
- Públicos del paquete: `cached`, `compute(Context,Recording)`, `compute(Context,Recording,Progress)`, `warm`, `envelope(double[],int[])`.
- Dato de rendimiento en el propio código (l.110): decodificar 10 min completos tardaba 2,5 min en el emulador.

### Resumen de (1)
- Existe: decodificar a PCM (tres bucles distintos, ninguno reutilizable), recortar con recodificación a AAC, recortar o remultiplexar AAC sin recodificar, buscar pausas.
- No existe: concatenar, mezclar a mono, remuestrear, escribir WAV, codificar FLAC, base64 en streaming.

## 2. Qué falta

### (a) Bloque .m4a → WAV PCM 16 bits mono 16 kHz
1. Un decodificador con destino de PCM: extraer el bucle de `AudioConvert.java:28-49`. Debe leer frecuencia y canales de `INFO_OUTPUT_FORMAT_CHANGED` (no del formato del contenedor) y aceptar también PCM float (como `WaveData.accumulate`).
2. Mezcla a mono: promedio de canales por cuadro. Hoy no existe. Un AAC importado por el atajo de remux puede traer más de 2 canales.
3. Remuestreo a 16 kHz, con estado entre búferes. Orígenes reales: 44,1 kHz (razón 160/441, no entera), 48 kHz (3:1), 16 kHz (copia). Necesita filtro pasa-bajos antes de decimar; escrito a mano, porque no hay librerías.
4. Escritor WAV: cabecera de 44 bytes con el mismo formato que ya generan las pruebas (`FeatureChecks.java:60`). El largo de datos no se conoce hasta terminar: parchear la cabecera al final.
5. Lo de siempre en el proyecto: temporal + rename, borrar si falla, `cancel.check()`, vigilancia de avance.
6. Devolver el número exacto de muestras escritas (la duración nominal del m4a difiere por el relleno de AAC).

### (b) FLAC con MediaCodec ("audio/flac")
No hay nada en el repo. Lo que sigue es conocimiento de la plataforma, no verificado en este proyecto ni en el emulador:
- `MediaCodec.createEncoderByType("audio/flac")` con `MediaFormat.createAudioFormat("audio/flac",16000,1)` y `KEY_FLAC_COMPRESSION_LEVEL`; algunos equipos exigen `KEY_BIT_RATE`. Entrada PCM de 16 bits.
- `MediaMuxer` no sirve para un `.flac` nativo: hay que escribir los búferes de salida en crudo, incluido el primero (`BUFFER_FLAG_CODEC_CONFIG`), que trae `fLaC` + STREAMINFO. `AudioConvert.java:56` hoy descarta ese búfer: es el comportamiento opuesto.
- El STREAMINFO sale antes de codificar, así que el total de muestras y el MD5 pueden quedar en 0.
- Reutiliza todo lo de (a) salvo el escritor.

### (c) Anclas con 1 s de silencio y tiempos exactos
- No existe concatenación. No se puede hacer por remux AAC: las muestras pueden diferir del bloque en frecuencia y canales, y el relleno de AAC (cuadros de 1024 muestras) impide tiempos exactos.
- Hay que concatenar en PCM: por cada voz de `Voices.selected(c)`, decodificar `Voices.file(c,id)` a mono 16 kHz con (a), escribir sus muestras y luego 16000 ceros; al final, el bloque.
- Los tiempos salen de contar muestras escritas: ancla i = `[inicio_i/16000, fin_i/16000)`, y el bloque empieza en `T0 = Σ(muestras_i + 16000)/16000`. No usar `AudioConvert.duration()`.
- Máximo agregado: 4 × 9,5 s + 4 s = 42 s por bloque (1.344.000 bytes de WAV).
- Falta también el posproceso: asignar a cada ancla el hablante dominante en su ventana, descartar lo anterior a `T0` y restar `T0`.
- El estado no guarda nada de anclas. El perfil de reutilización (`Transcriber.java:176`) solo incluye `Voices.fingerprint(c)` cuando `saved` no está vacío, y `saved` solo se llena con OpenAI (l.170).

## 3. Cómo están hechas las pruebas

### Arnés
- `TEST/RecorderSmokeTest.java`: `public class RecorderSmokeTest extends Instrumentation`, declarado como `testInstrumentationRunner` en `app/build.gradle:11`. Sin JUnit ni Mockito, sin manifiesto propio en `androidTest`.
- Compilar: `gradlew assembleDebug assembleDebugAndroidTest lintDebug`. Ejecutar: `adb shell am instrument -w cl.vozlocal.app.test/cl.vozlocal.app.RecorderSmokeTest`; `-e long false` salta `LongImportChecks` (l.13-14).
- Resultado en `report.putString("stream", "PASS: …")` (l.93) o `"FAIL: "+stacktrace` (l.96). El primer `AssertionError` aborta toda la corrida.
- `onStart()` graba de verdad ~5-6 s con el micrófono del emulador y obtiene `Recording r` ("Prueba de voz local", AAC mono 44,1 kHz). Luego llama en orden (l.82-91): `FeatureChecks`, `ExportChecks`, `EngineChecks`, `NotesChecks`, `MediaChecks`, `UiChecks`, `IntegrationChecks`, `VoicesChecks`, y `LongImportChecks` si corresponde.

### Registrar un check nuevo
1. Crear `TEST/<Nombre>Checks.java`, `final class` en el paquete `cl.vozlocal.app` (ve todo lo package-private de MAIN), con `static void run(Context c,Recording r)throws Exception`.
2. Agregar `<Nombre>Checks.run(c,r);` en `RecorderSmokeTest.java` entre las líneas 82 y 89.
3. Opcional: ampliar el texto de PASS (l.93).

Convenciones: cada clase define su `check(boolean,String)` que lanza `AssertionError`; las preferencias y carpetas reales se guardan antes y se restauran en `finally`.

### Cómo se simula la API
No hay servidor HTTP falso. `HttpApi.request` exige HTTPS (`HttpApi.java:48`) y el manifiesto tiene `usesCleartextTraffic="false"`. Todo se simula con subclases anónimas de `HttpApi` (clase no final) que sobrescriben:

`Response request(String method,String url,String token,String contentType,Body body,Map<String,String> extra)throws Exception` (`HttpApi.java:46`)

y devuelven `new Response(int code,String text,String location)` (l.39).

Patrón para inspeccionar el cuerpo: `ByteArrayOutputStream out; body.write(out); out.size()==body.length()`; multipart se lee como ISO-8859-1 y JSON como UTF-8.

| Ejemplo | Dónde | Qué comprueba |
|---|---|---|
| Contrato diarize | `FeatureChecks.java:40-48` | URL, `diarized_json`, `chunking_strategy`, `language` |
| Servidor propio | `FeatureChecks.java:53-57` | URL propia, sin opciones de voces, respuesta `{"text":…}` |
| gpt-transcribe | `FeatureChecks.java:72-80` | `languages[]`, `stream`; el falso devuelve directo el evento final |
| Muestras de voz | `FeatureChecks.java:82-87` | `known_speaker_names[]` / `known_speaker_references[]` |
| Errores | `FeatureChecks.java:49-51` | `HttpApi.require` con 401, 429 `insufficient_quota`, 503 |
| Notas, Chat Completions | `NotesChecks.java:165-181`, helper `openAiReply` l.146 | Método, URL, token, tipo, `extra==null`, cuerpo JSON, `noteState`; respuesta con `choices[0].message.content` y `usage.prompt_tokens/completion_tokens` |
| Notas, Anthropic | `NotesChecks.java:207-217` | Token vacío, clave en `x-api-key` |
| Carrera, 401, basura | `NotesChecks.java:231`, `241`, `245` | `Notes.Discarded`, `UserAction`, `Notes.BadAnswer` |

Puntos de inyección en producción:
- `new OpenAiClient(HttpApi)` con `transcribe(File audio,ProviderConfig config,String language,List<String[]> references,Delta delta)` (`OpenAiClient.java:28`).
- `Notes.generate(Context c,Recording r,HttpApi http,String provider,String model,String key)` (`Notes.java:127`), pensada para pruebas.
- Lógica pura sin red: `Notes.openaiBody`, `Notes.parseAnswer`, `Notes.normalize`, `Transcript.fromParts(List<JSONObject>,List<Double>)`, `AudioParts.pickReferences`, `Transcriber.fixedPlan`, `Transcriber.speakerBlockMs`, `Transcriber.profile`, `Retranscribe.reason(Facts,Mode)`, `Retranscribe.prepare(...)`.

Ninguna prueba pasa por `Transcriber.process`/`block`. `Transcriber.block` hace `HttpApi h=http.child()` (`Transcriber.java:347`), y `child()` crea un `HttpApi` real con constructor privado (`HttpApi.java:34-35`): un falso inyectado en `Transcriber` no llega a los envíos salvo que también sobrescriba `child()`.

### Fixtures de audio (todos privados de su clase)
- WAV sintético 16 kHz mono 16 bits, tono de 440 Hz: en línea en `FeatureChecks.java:60`; `MediaChecks.writeWav(File,int rate,int loudSeconds,int quietSeconds)` (l.117, tono y luego silencio); `LongImportChecks.writeWav(File,int seconds)` (l.39).
- WAV → m4a real: `AudioConvert.convert(wav,crop,1000,2500,new HttpApi())` (`FeatureChecks.java:61`, `MediaChecks.java:80`).
- AAC largo repitiendo un cuadro: `MediaChecks.repeatFrame(File seed,File destination,long durationUs)` (l.124, 10 min); `LongImportChecks.hourFixture(File seed,File destination)` (l.33, 1 h).
- Grabación desechable copiando `r`: `EngineChecks.copy` (l.239), `NotesChecks.fixture` (l.256), `IntegrationChecks.fixture` (l.43), `MediaChecks.copy` (l.200).
- `VoicesChecks.clip(Context,int bytes)` (l.18) son bytes al azar con extensión .m4a: no se pueden decodificar.
- `VoicesChecks.run` aparta la carpeta real `filesDir/voices` a `voices-backup-check` y la restaura en `finally` (l.25-39).
- Tolerancias usadas: recortes ±300 ms (`FeatureChecks.java:59,61`), remux de tramo ±400 ms (l.94), onda de 10 min en menos de 60 s (`MediaChecks.java:109`).

### Contrato de respuesta que ya consume la app
- `Transcript.fromParts` (`Transcript.java:223-244`): cada segmento debe traer `speaker`, `start`, `end`, `text`, o lanza `IOException("La transcripción recibida está incompleta.")`. `speaker` nulo → `"unknown"`. Texto vacío se descarta.
- Marcas por respuesta: `_known` (objeto `{nombre enviado → voz destino}`), `_prefix`, `_diarized`.
- Ids resultantes: el destino de `_known` si coincide; si no, `prefix + (una parte ? speaker : "block<p>:"+speaker)`.
- Punto de control por bloque: `<id>.part<i>.json` (`Transcriber.java:337,380`).
- Uso (`Transcriber.java:385-388`): `usage.type=="duration"` suma `usage.seconds` en `"usageSec"`; si no, `input_tokens`/`output_tokens`. No hay clave de estado para `usage.cost`.

## 4. Memoria y tamaños

| Medida | Valor |
|---|---|
| AAC grabado | 12 kB/s → 12 min ≈ 8,64 MB |
| WAV 16 kHz mono 16 bits | 32.000 B/s = 1,92 MB/min |
| WAV de 12 min | 23.040.044 bytes → base64 30.720.060 caracteres |
| WAV de 15 min (tamaño del plan) | 28.800.044 bytes → base64 38.400.060 caracteres |
| Tope multipart de 25 MB en WAV | 781 s = 13 min 1 s |

- Hoy el audio nunca se carga entero: `OpenAiClient.java:46` arma un `HttpApi.Body` con prefijo + `http.copy(audio,out)` + sufijo; `HttpApi.copy` usa un búfer de 64 KB (l.78).
- `HttpApi.request` usa `setFixedLengthStreamingMode(body.length())` (l.55): el largo debe ser exacto por adelantado. Para base64 sin saltos de línea: `4*((n+2)/3)`.
- `HttpApi.json(JSONObject)` (l.76) hace `json.toString().getBytes()`: con un audio de 12 min serían cuatro copias simultáneas (bytes del WAV 23 MB, texto base64 ~31 MB, texto JSON ~31 MB, bytes UTF-8 ~31 MB), más de 110 MB por bloque.
- `Transcriber.PARALLEL=3` (l.26): hasta tres bloques a la vez, sin `largeHeap`.
- Las respuestas se leen enteras a memoria con tope de 8 MB (`HttpApi.java:67`).
- Únicas cargas completas de audio hoy: muestras de voz (~114 KB) en `Voices.java:156`, `AudioParts.java:170`, `Transcriber.java:311`.
- Disco: `ImportService.java:50` ya comprueba espacio libre antes de convertir; una reunión de 90 min convertida entera a WAV serían ~173 MB.
- Umbrales calculados sobre bytes del m4a y el tope de 25 MB de OpenAI: `AudioParts.java:39,48-49` (20 MB), `Transcriber.speakerBlockMs` (19 MB, l.37), `OpenAiClient.java:29` (25 MB), `Retranscribe.SINGLE_MAX_BYTES=24_000_000`.
- Tiempos ligados al tamaño del envío: `Transcriber.sendEstimate(partMs)=90_000+0.6*partMs` (l.45), `JOB_SEND_LIMIT_MS=9 min` (l.43), `UPLOAD_STALL_MS=90 s` (l.62), `readTimeoutMs=min(20 min,max(240 s,120 s+partMs))` (l.349).

# PUNTOS DE EXTENSION

Rutas relativas a MAIN = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/` y TEST = `.../app/src/androidTest/java/cl/vozlocal/app/`. Las firmas de esta sección son propuestas para quien diseñe los contratos; no existen hoy.

## Audio: una sola pieza nueva de PCM

Extraer el bucle de `AudioConvert.java:28-49` a un decodificador con destino, y montar encima WAV, FLAC y anclas. `AudioConvert.convert` puede quedar intacto: sus pruebas (`FeatureChecks.java:59-61`, `LongImportChecks`) son la red de seguridad.

- `interface PcmSink { void write(short[] mono16k,int count)throws Exception; }`
- `static long decodeMono16k(File source,long startMs,long endMs,HttpApi cancel,PcmSink sink)throws Exception`: devuelve las muestras entregadas; hace mezcla a mono y remuestreo con estado interno.
- `static long toWav(File source,File target,long startMs,long endMs,HttpApi cancel,Progress progress)throws Exception`: WAV 16 kHz mono 16 bits, temporal + rename, cabecera parcheada al final.
- `static final class Anchored { final long[] startSample,endSample; final long blockStartSample; }` y `static Anchored toWavWithAnchors(List<File> samples,File block,File target,HttpApi cancel)throws Exception`: escribe muestra + 16000 ceros por cada voz y luego el bloque, todo por el mismo escritor.
- `static long toFlac(...)`: mismo decodificador, con codificador `audio/flac` y escritura en crudo. Opcional; conviene una autoverificación (reabrir el archivo y comparar duración) y respaldo a WAV.

Dónde guardar: `AudioParts.blockDir(c,id)` con otro sufijo (`block-<i>.wav`). `AudioParts.clearBlocks` ya borra cualquier archivo de esa carpeta y `Recording.delete` la llama. Conviene convertir cada bloque justo antes de enviarlo y borrarlo al guardar su punto de control, no convertir toda la reunión de una vez.

## Envío: cuerpo JSON con base64 en streaming

- Nuevo `HttpApi.Body` con el patrón de `OpenAiClient.java:45-46`: prefijo JSON hasta `"data":"`, el archivo por `android.util.Base64OutputStream` con `NO_WRAP|NO_CLOSE` alimentado por `http.copy(file,b64)`, y sufijo JSON.
- `length()` = prefijo + `4*((file.length()+2)/3)` + sufijo. Tiene que ser exacto por `setFixedLengthStreamingMode`.
- Así se conservan el progreso de subida (`HttpApi.onProgress`), la vigilancia (`lastActivity`) y la cancelación sin tocar `HttpApi.request`.

## Transcripción: un solo punto de enchufe

- `Transcriber.java:368` y `:372` son las únicas llamadas a `new OpenAiClient(h).transcribe(part.file,config,settings.language(),refs,delta)`. Ahí se elige el cliente según `config.provider`.
- Si el cliente nuevo devuelve el mismo JSON normalizado (`segments[{speaker,start,end,text}]`, `usage`, `_diarized`), el resto de `block()` (punto de control, métricas, bitácora) y `Transcript.fromParts` sirven sin cambios.
- Anclas sin tocar `Transcript.fromParts`: tras el posproceso (quitar la zona anterior a `T0` y restar `T0`), poner `_known` = `{"<etiqueta del hablante del ancla>":"voice:<id>"}`. `fromParts` ya traduce esas etiquetas y deja las demás como `block<p>:<etiqueta>` (`Transcript.java:236`); `Transcriber.prefillVoices` (l.318) ya les pone el nombre guardado.
- `Transcriber.java:170`: hoy `saved` solo se llena con OpenAI. Para anclas hace falta una rama que tome `Voices.selected(c)` como archivos, y que `Voices.fingerprint(c)` más una marca de anclas entren en el perfil (l.176).
- Normalizar los índices enteros de hablante a texto estable en el cliente, no en `fromParts`.
- Costo real: agregar una clave de estado para `usage.cost` junto a `usageSec` (`Transcriber.java:385-388`) e incluirla en `Retranscribe.BEFORE` (`Retranscribe.java:26`) para que vuelva con la versión anterior.
- Solape entre bloques: `AudioParts.plan` no lo tiene. Requiere un campo de solape en `Part`, que el tramo empiece antes del corte y un paso nuevo de unión antes de `fromParts`. Es el cambio más grande de esta área.

## Pruebas sin red

- Nueva `TEST/OpenRouterChecks.java` con `static void run(Context c,Recording r)throws Exception`, registrada en `RecorderSmokeTest.java` entre las líneas 82 y 89 (antes de `LongImportChecks`).
- Contrato de envío: `HttpApi` anónimo que sobrescribe `request(...)`; capturar el cuerpo con `body.write(out)`, comprobar `out.size()==body.length()` (valida el cálculo del base64), parsear como JSON UTF-8 y verificar URL, token, modelo, `input_audio.format`, `response_format`, `language` y `provider.options`. Decodificar `input_audio.data` y comparar con los bytes del archivo. Con `r` (~5 s) el WAV pesa ~160 KB.
- Dentro del `HttpApi` anónimo el helper no puede llamarse `check` (choca con `HttpApi.check()`): usar `expect` o `assertThat`, como `NotesChecks.java:12-13`.
- WAV: convertir `r.audio(c)` y un WAV sintético; leer la cabecera de 44 bytes y comprobar frecuencia 16000, 1 canal, 16 bits y que el largo de datos coincide con las muestras devueltas; duración ±300 ms respecto de `AudioConvert.duration`.
- Anclas: fabricar muestras reales con tonos distintos (WAV sintético → `AudioConvert.convert` → `Voices.add`), apartando la carpeta de voces como `VoicesChecks.java:25-39`. Comprobar que los tiempos devueltos coinciden con el archivo: ceros exactos en cada segundo de silencio, tono correcto en cada ventana, y largo total = Σ(muestra + 16000) + bloque. No sirven los clips de `VoicesChecks.clip`.
- Analizador de respuestas: JSON de ejemplo por proveedor → `segments` normalizados → `Transcript.fromParts`, comprobando ids, tiempos restados y nombres. Es lógica pura, como `EngineChecks.corrections()`.
- Notas: `Notes.generate(c,fixture,fake,provider,model,key)` ya es inyectable; basta una rama o URL nueva y copiar `NotesChecks.java:165-181`.
- Flujo completo por `Transcriber` (opcional): el falso debe sobrescribir también `child()`. Necesita estado `requested`, una clave guardada y publica notificaciones; hoy ninguna prueba lo hace.
- Los helpers de fixtures (`writeWav`, `repeatFrame`, `copy`) son privados: duplicarlos o subirlos a una clase compartida de pruebas.
- Evitar fixtures largos decodificados enteros: 10 min tardaban 2,5 min en el emulador (`WaveData.java:110`). Para probar límites de 12-15 min, probar la aritmética (largos, base64, cortes) y no la conversión real.

# RIESGOS

Rutas relativas a MAIN = `C:/Users/Konra/Desktop/Digital Home/Casa Digital 2026/Proyectos 2026/14-App grabar y transcribir/app/src/main/java/cl/vozlocal/app/` y TEST = `.../app/src/androidTest/java/cl/vozlocal/app/`.

## Supuestos de OpenAI que se rompen

1. **Formato del envío.** `OpenAiClient.java:44` fija `filename="recording.m4a"` y `audio/mp4`. El plan dice que MAI-2 responde 400 con m4a: sin la conversión de (a) el modelo por defecto no funciona. Y en multipart OpenRouter descarta `provider.options` (la separación de voces falla sin avisar): el camino obligado es JSON con base64.
2. **`diarized_json`, `chunking_strategy`, `known_speaker_*`** (`OpenAiClient.java:33-36`) no existen en OpenRouter. El reintento sin muestras de `Transcriber.java:369-373` busca el texto "known_speaker" en el error.
3. **Voces conocidas atadas a `provider.equals("openai")`**: `Transcriber.java:170,174`, `Retranscribe.java:44,80,103,139`, `RecordingActions.java:264-281`, `RecordingActivity.java:503,1213`, `RetranscribeSheet.java:60`. La segunda pasada con correcciones y "Guardar la voz" quedarían desactivadas con OpenRouter.
4. **Referencias automáticas del bloque 1** (`AudioParts.references`, `Transcriber.java:211-222`): sin voces conocidas en el proveedor, los hablantes de cada bloque quedan como personas distintas (`block0:0`, `block1:0`…), salvo anclas o unión por solape. Y no hay solape en `AudioParts.plan`.
5. **Tamaños y duraciones de bloque.** Los umbrales de 19, 20, 24 y 25 MB y `SPEAKER_BLOCK_MAX_MS=12 min` salen del límite de 25 MB y 1400 s de OpenAI, medidos en bytes de m4a. El plan pide ~15 min con solape y dice que los proveedores cortan a los ~60 s de proceso. Un WAV de 15 min pesa 28,8 MB (38,4 MB en base64) y el tope del camino JSON no está documentado: hay que validarlo con la key real.
6. **Tiempos de subida.** El WAV es ~2,7 veces el m4a y en base64 ~3,5 veces. `sendEstimate`, `JOB_SEND_LIMIT_MS` (9 min) y `UPLOAD_STALL_MS` están calibrados para m4a: con datos móviles más bloques caerán en `NeedsForeground` o `Yield`. `bytesSent` (`Transcriber.java:381`) usa `part.file.length()` y quedaría corto.
7. **Uso y costo.** `Transcriber.java:385-388` solo entiende `usage.type=="duration"` o tokens. `Pricing.estimate(provider,…)` devuelve -1 fuera de OpenAI (`Pricing.java:24`). `pricing.prompt` del catálogo no trae unidad.
8. **Errores.** `HttpApi.require` (`HttpApi.java:79-95`): un 402 (sin crédito) caería en el mensaje genérico "Revisa el formato del audio…"; solo trata como saldo el 429 con `insufficient_quota`. El id de soporte se lee de la cabecera `x-request-id`. `Transcript.java:230` dice "OpenAI no devolvió…".
9. **Claves y preferencias.** `Settings.prefix()` (l.19) devuelve `"custom_"` para todo lo que no sea "openai": un proveedor nuevo compartiría la clave del servidor propio. `Settings.config` (l.37-43) exige `customBase` a todo lo que no sea OpenAI. `canSeparate()` (l.28) solo es cierto con OpenAI o `customSpeakers`. `hasOpenAiKey()` usa la clave sin prefijo.
10. **Notas.** `Notes.OPENAI_URL` es constante (l.41). `Notes.model()` valida con `[A-Za-z0-9._:-]{2,80}` (l.87): rechaza "/" y "~", así que `~anthropic/claude-sonnet-latest` caería al modelo por defecto. `reasoning_effort` solo se agrega si el id empieza con `gpt-[5-9]` u `o[1-9]` (l.249). `Notes.rates()` no conoce ids de OpenRouter. La nota guarda el modelo pedido, no el `model` de la respuesta (l.147). `ProviderConfig` acepta "/" pero no "~".
11. **Diagnóstico.** `Diagnostics.event` descarta sin avisar toda clave fuera de su lista (`Diagnostics.java:21`): campos nuevos como costo o formato no se guardarían.

## Trampas de audio

1. **Memoria.** Armar el cuerpo con `HttpApi.json(new JSONObject().put("data",base64))` para 12-15 min son más de 110 MB por bloque; con `PARALLEL=3` y sin `largeHeap` es una caída segura por falta de memoria. El streaming con largo exacto es obligatorio, y cualquier diferencia de un byte hace fallar el envío.
2. **Remuestreo 44,1 → 16 kHz.** La razón no es entera. Una interpolación lineal sin filtro mete aliasing; hay que llevar estado entre búferes o aparecen clics cada ~23 ms.
3. **Canales y PCM.** Un AAC importado por el atajo de remux puede traer más de 2 canales o HE-AAC. `AudioConvert.convert` lanza "Unsupported PCM" con más de 2 canales o PCM que no sea de 16 bits; `AudioParts.quietest` asume 16 bits. Usar siempre el formato de salida del decodificador, no el del contenedor.
4. **Tiempos.** La duración del contenedor y las muestras decodificadas difieren por el relleno de AAC. `remuxRange` empieza en el cuadro más cercano (hasta ~23 ms) mientras `Part.offset` usa el corte nominal. Los tiempos de anclas deben salir del conteo de muestras.
5. **Anclas (experimental).**
   - El modelo puede unir el ancla y el inicio del bloque en un mismo segmento: conviene pedir tiempos por palabra y decidir por solape con cada ventana.
   - Si hay pocas palabras en un ancla, el índice puede no ser fiable. Una muestra de 3 s (mínimo de `Voices.MIN_MS`) da poco material.
   - Dos anclas pueden recibir el mismo índice: hay que definir qué se hace.
   - Agregan hasta 42 s facturados por bloque y acercan el bloque a los límites del proveedor.
   - Las muestras que están apagadas (`use=false`) o pasan del tope de 4 no deben ir.
   - Si una muestra no se decodifica, el bloque debe enviarse sin ella, no fallar.
6. **FLAC.** No está verificado en este proyecto: variación entre equipos, STREAMINFO sin total de muestras ni MD5, y no se puede usar `MediaMuxer`. El camino seguro es WAV; FLAC solo como optimización con autoverificación y respaldo.
7. **Rendimiento.** Decodificar un bloque de 12 min es lento en el emulador (10 min tardaban 2,5 min según `WaveData.java:110`). Tres conversiones en paralelo compiten por CPU. Si la conversión ocurre dentro de la tarea de fondo, consume parte de sus ~10 min. Hoy `plan()` es solo copia y tarda segundos.
8. **Disco.** 23 MB por bloque de WAV: comprobar espacio libre antes (como `ImportService.java:50`) y limpiar en los caminos de error y cancelación. `Pipeline.cancel` ya llama a `AudioParts.clearBlocks`.
9. **Reutilización de puntos de control.** El perfil (`Transcriber.java:176`) no cambia si cambian las anclas o el modo de anclas con un proveedor que no es OpenAI: se mezclarían partes hechas con y sin anclas.
10. **Índices de hablante enteros.** `getString("speaker")` de Android convierte números a texto, pero "0" y "1" chocan entre bloques igual que las letras y no se distinguen de un índice de ancla si no se normalizan antes de `fromParts`.

## Trampas de pruebas

1. No hay servidor falso ni se puede levantar uno local: `HttpApi` solo acepta HTTPS y el manifiesto prohíbe tráfico en claro. Todo va por subclase de `HttpApi`.
2. `HttpApi.child()` crea un `HttpApi` real: un falso pasado a `Transcriber` haría llamadas de red reales desde `block()` si no sobrescribe `child()`.
3. `FeatureChecks.java:31` deja a `r` como "demo" con transcripción de ejemplo: `Pipeline.request` y `Notes.generate` lo rechazan. Usar siempre una copia con UUID nuevo; `FilesStore.file` exige ids con forma `[a-f0-9-]{36}`.
4. Los clips de `VoicesChecks` no son audio. La biblioteca real de voces y las preferencias deben apartarse y restaurarse en `finally`.
5. El audio de `r` es ruido del micrófono del emulador: sirve para formato y duración, no para contenido. Para verificar contenido, tonos sintéticos.
6. Una prueba lenta alarga toda la regresión, y el primer fallo aborta el resto.
7. Todo lo que el plan marca como no confirmado (tope del JSON, forma exacta de `verbose_json` por proveedor, nombres de `speaker`/`speaker_label`, `usage.cost`/`usage.seconds`) solo se puede fijar en las pruebas como supuesto: las respuestas simuladas deben salir de capturas reales con la key antes de darlas por contrato.