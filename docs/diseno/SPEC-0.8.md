# SPEC 0.8.0: OpenRouter, bienvenida e ícono de Biblioteca (para implementar en paralelo)

**Pedido del usuario (30-09-2026):**
1. «Aplica lo de OpenRouter para la nueva versión»: una sola clave, con lista de modelos que se actualiza sola.
2. «Un ONBOARDING para los nuevos usuarios la primera vez que lo instalan».
3. «Cámbiame el ícono de BIBLIOTECA»: ya hecho (tres libros, `ic_tab_library*.xml`).

**Fuentes:**
- `docs/PLAN-openrouter.md`: investigación verificada de la API de OpenRouter.
- `docs/diseno/mapas-0.8/*.md`: mapas del motor, ajustes/notas y audio/pruebas, con archivo:línea. **Lee el de tu área antes de tocar código.**

## Reglas

- Java sin librerías de runtime. La UI se arma con el kit `Ui`/`Sheet`/`Screen`/`Glass` y los tokens de `AppTheme` (estética Verbapp, `docs/diseno/CRITERIOS.md`).
- Textos en español de Chile, claros y sin jerga.
- **No se pierde nada de lo que ya funciona.** OpenAI directo y «Servidor compatible» siguen igual: OpenRouter es un tercer proveedor. Los contratos que fijan las pruebas actuales (firmas de `OpenAiClient`, `HttpApi.request`, `ProviderConfig`, `Pricing.estimate`, `Transcript.fromParts`, `Notes.OPENAI_URL`…) no se rompen.
- **Nada contra la red real.**
  - No hay clave de OpenRouter para probar: todo se prueba con `HttpApi` simulado (subclase que sobreescribe `request`), como en `FeatureChecks`/`NotesChecks`.
  - El código debe ser defensivo con lo no confirmado y dejar rastro útil en la bitácora y en `Diagnostics`.
  - En la bitácora y el diagnóstico nunca van nombres, títulos ni claves.
- Privacidad: la clave solo viaja a `https://openrouter.ai`. Nunca se registra.
- Cada parte edita SOLO sus archivos. Las firmas de la fase 0 son el contrato: se pueden agregar métodos, no cambiar ni quitar los documentados.
- No uses el emulador ni adb: es uno solo y lo usa la integración. Compila con el comando de la sección «Verificación». Tus pruebas instrumentadas las corre la integración: déjalas deterministas y sin red.

## Decisiones de diseño

1. **Proveedor `"openrouter"`**:
   - Preferencia `provider`.
   - Clave con prefijo `openrouter_`: `Settings.prefix()`, `hasOpenRouterKey()`, `openRouterKey()`, `saveKeyFor(provider, clave)`.
   - Base `Models.BASE`.
   - `Settings.config(speakers)` ya devuelve `ProviderConfig("openrouter", BASE, Models.chosen(...), clave, speakers && receta.diarizes)`.
2. **Un solo formato de audio para OpenRouter: FLAC mono de 16 kHz y 16 bits** (WAV si el teléfono no puede codificar FLAC).
   - Lo aceptan todos los modelos que separan voces (MAI-Transcribe 2 rechaza m4a).
   - Lo produce `OrAudio.build(...)`.
   - Se envía en **JSON con base64 en streaming**. No se usa multipart: descarta `provider.options`.
3. **«Anclas» = voces conocidas + coherencia entre bloques.**
   - El motor ya maneja «referencias» `{nombre enviado, data URL del clip, id global, descripción}`: voces guardadas, correcciones y muestras automáticas de la parte 1.
   - Con OpenRouter, el cliente **antepone esos clips al audio**, separados por 1 s de silencio, y pide separación de voces.
   - En la respuesta mira qué hablante suena en la ventana de tiempo de cada ancla: a ese hablante lo devuelve con el **nombre enviado** de la referencia.
   - Después borra del resultado todo lo que cae en la zona de anclas y resta `leadMs` a los tiempos.
   - Así `_known` y `Transcript.fromParts` funcionan sin cambios, y el flujo «parte 1 sola → muestras automáticas → resto en paralelo» sirve tal cual.
   - Emparejamiento:
     - Por cada ancla, el hablante con más segundos dentro de su ventana, con al menos 1 s o el 40 % del ancla.
     - Un hablante solo puede quedar con un ancla (la de mayor coincidencia).
     - Si dos anclas caen en el mismo hablante, gana la de mayor coincidencia y la otra queda sin emparejar.
     - Un ancla sin emparejar no es error.
   - Es una técnica **sin probar con audio real**. Por eso:
     - registra en `Diagnostics` cuántas anclas se enviaron y cuántas se emparejaron (claves `anchors`, `matched`);
     - registra en la bitácora «reconoció X de Y voces conocidas»;
     - nunca debe hacer fallar una transcripción.
4. **Separación de voces según la receta** (`Models.Recipe.diarize`):
   - `AZURE`: `provider.options.azure.diarization.enabled=true`.
   - `DEEPGRAM`: `provider.options.deepgram.diarize=true`.
   - `GENERIC`: `diarize:true`.
   - `INLINE`: marcas `<|speaker:N|>` dentro del texto.
   - Siempre `response_format:"verbose_json"` y `timestamp_granularities:["segment","word"]` si `recipe.verbose`.
   - Si el servidor responde 400 por `response_format`, `timestamp_granularities` o `diarize`, se reintenta una vez en modo simple (`json`, sin voces), con aviso en la bitácora.
5. **Normalización de la respuesta** al contrato de `TranscribeClient`:
   - Tramos con `speaker` → tal cual, con el índice convertido a texto.
   - Si los hablantes solo vienen en `words[]`, agrupar palabras seguidas del mismo hablante en tramos. Cortar también si hay una pausa de más de 1,2 s o el tramo pasa de ~45 s.
   - Marcas `<|speaker:N|>` → partir el texto por marcas. Tiempos de los tramos si existen; si no, repartidos.
   - Sin voces → tramos `speaker:"text"` con sus tiempos reales si vienen, `_diarized:false`.
   - Nunca tramos sin alguna de las 4 claves; los de texto vacío se descartan.
6. **Costo real:**
   - El cliente devuelve `usage:{"type":"duration","seconds":N,"cost":US$}` a partir de `usage.seconds`/`duration` y `usage.cost`.
   - El motor suma `costUsd` en el estado: clave nueva, incluida en `Retranscribe.BEFORE` y en el reinicio por cambio de perfil.
   - Las pantallas muestran el costo real cuando existe. El estimado sale de `Pricing.estimate("openrouter", modelo, ms)`, que usa `Models.perMinute`.
7. **Bloques:**
   - Con voces, `min(12 min, receta.maxMs)`, como hoy.
   - `«Sin cortar»` (SINGLE) se permite si el audio cabe en `receta.maxMs` y el FLAC estimado es ≤ 24 MB.
   - Si el servidor responde 413, se reduce el bloque a la mitad una vez (clave de estado `orHalf`) y se reintenta sin gastar intento.
   - El perfil incluye el formato y el tamaño, para no mezclar puntos de control.
8. **Errores de OpenRouter:**
   - 402 → `UserAction` «No queda saldo en OpenRouter. Carga créditos en openrouter.ai y pulsa Reintentar.»
   - 404 de modelo → «Ese modelo ya no está disponible en OpenRouter. Elige otro en Ajustes.»
   - 401 → clave inválida. 429, 5xx, 524 y 529 → reintento.
   - El cuerpo es `{"error":{"code":N,"message":"…","metadata":{…}}}`.
9. **Catálogo que se actualiza solo** (`Models`):
   - `GET BASE/models?output_modalities=transcription&sort=newest`, sin clave, guardado en un archivo.
   - Se refresca al abrir la hoja de modelos si tiene más de 24 h, y con el botón «Actualizar lista».
   - Cada modelo muestra: nombre, «Separa voces» / «Solo texto», precio por hora si se puede calcular, «Nuevo · sin probar» si no está en la tabla y «Se retira el …» si tiene fecha.
   - **«Automático (recomendado)»** es la primera opción y la elegida por defecto. Se resuelve con un orden de preferencia de modelos verificados que estén en el catálogo y no venzan en 30 días. Queda en las preferencias `orAutoSpeakers` / `orAutoText`.
10. **Notas por OpenRouter:**
    - `noteProvider="openrouter"` usa chat completions en `BASE/chat/completions` con la clave de OpenRouter.
    - Modelos alias `Models.NOTE_MODELS` (`~anthropic/claude-sonnet-latest`…): siempre la última versión.
    - La nota guarda el `model` que respondió y lo muestra.
    - Si el proveedor de transcripción es OpenRouter y el usuario nunca eligió IA para la nota, la nota usa OpenRouter por defecto.
11. **Bienvenida** (`OnboardingActivity`):
    - Solo aparece en la primera instalación (`welcomed=false`). Reemplaza la hoja de bienvenida de `MainActivity`.
    - A quien actualiza desde 0.7 no le aparece: ve «Novedades».
    - Se puede volver a ver desde Ajustes → Ayuda («Ver la bienvenida»).

## Propiedad de archivos

| Parte | Archivos |
|---|---|
| **audio** | `OrAudio.java`, `androidTest/OrAudioChecks.java` |
| **engine** | `OpenRouterClient.java`, `TranscribeClient.java`, `HttpApi.java`, `Transcriber.java`, `Retranscribe.java`, `Transcript.java`, `Pricing.java`, `AudioParts.java`, `OpenAiClient.java` (solo lo imprescindible), `androidTest/OpenRouterChecks.java` |
| **catalog** | `Models.java`, `Settings.java`, `ProviderConfig.java`, `SettingsActivity.java`, `VozApp.java`, `androidTest/ModelsChecks.java` |
| **notes** | `Notes.java`, `RecordingActions.java`, `RetranscribeSheet.java`, `RecordingActivity.java`, `androidTest/NotesChecks.java` |
| **onboarding** | `OnboardingActivity.java`, `MainActivity.java`, `androidTest/OnboardingChecks.java`, drawables `onboarding_*` |

Compartidos que no edita nadie (si necesitas un cambio, descríbelo en tu informe): `AndroidManifest.xml`, `Diagnostics.java`, `AppTheme`/`Ui`/`Glass`/`Screen`/`Sheet`/`BottomNav`, `RecorderSmokeTest.java`, `build.gradle`, `assets/novedades.json` y los docs.

## Contratos de la fase 0 (ya compilan; léelos en el código)

- `TranscribeClient` (interfaz + `of(Context, HttpApi, ProviderConfig)`): contrato de salida documentado en su cabecera.
- `OpenRouterClient(Context, HttpApi)`: lo implementa **engine**.
- `OrAudio.build(File audio, List<File> anchors, File outBase, HttpApi cancel) → Built{file, format, leadMs, anchors[][], durationMs}`: lo implementa **audio**; lo usa **engine**.
- `Models`: lo implementa **catalog**. Lo usan engine (`recipe`, `perMinute`), notes (`NOTE_*`), onboarding (`checkKey`) y Settings (`chosen`).
  - `BASE`, `AUTO`, `DEFAULT_*`.
  - `Diarize`, `Unit`, `Recipe`, `recipe(id)`, `chosen(Settings, speakers)`.
  - `Model`, `cached(c)`, `fetchedAt(c)`, `refresh(c, http)`, `perMinute(c, id)`.
  - `KeyInfo`, `checkKey(http, key)`.
  - `NOTE_MODELS` / `NOTE_NAMES` / `NOTE_DEFAULT`.
- `Settings`: `openRouter()`, `prefix(provider)`, `orSpeakersModel()`, `orTextModel()`, `hasOpenRouterKey()`, `openRouterKey()`, `saveKeyFor(provider, clave)`; `noteProvider()` puede valer `"openrouter"`.
- `OnboardingActivity.shouldShow(c)` y `open(c, replay)`: lo implementa **onboarding**; **catalog** agrega la fila «Ver la bienvenida» en Ajustes con `OnboardingActivity.open(this, true)`.
- `Diagnostics` ya admite las claves `cost`, `format`, `anchors` y `matched`.
- Las pruebas nuevas se registran solas: `OrAudioChecks`, `OpenRouterChecks`, `ModelsChecks` y `OnboardingChecks` ya se llaman desde `RecorderSmokeTest` (`run(Context, Recording)`).

## Qué hace cada parte

### audio: `OrAudio`
- Decodificar con `MediaExtractor` + `MediaCodec` (hay un lazo de referencia en `AudioConvert.convert` y en `WaveData`). Mezclar a mono.
- Remuestrear a 16 kHz con calidad: filtro pasa-bajos antes de bajar la frecuencia (FIR windowed-sinc o polifásico). No usar interpolación lineal sin filtro.
- Todo en streaming: nunca el audio completo en memoria.
- Salida FLAC:
  - `MediaCodec` «audio/flac» (`KEY_FLAC_COMPRESSION_LEVEL` 5, mono, 16 kHz, PCM de 16 bits).
  - Se escribe el flujo tal cual: la configuración del códec (`fLaC` + STREAMINFO) y luego los cuadros.
  - Verificar que el archivo empiece con `fLaC`; si no, anteponer la cabecera correcta.
  - Si no hay codificador, o falla → WAV con cabecera RIFF de 44 bytes.
- Anclas: `[ancla1][1 s de silencio][ancla2][1 s]…[audio]`. Cada ancla se normaliza de volumen de forma suave (sin recortar) para que no quede mucho más baja que el audio. `Built.anchors` con tiempos exactos en ms y `leadMs`.
- Escribir a `.tmp` y renombrar. Respetar `cancel.check()`.
- Pruebas (`OrAudioChecks`), con audio sintético generado en la prueba (tonos; mira cómo generan fixtures `MediaChecks`/`FeatureChecks`):
  - duración correcta (±50 ms);
  - formato legible por `MediaExtractor` (FLAC) o cabecera válida (WAV);
  - 16 kHz mono;
  - anclas en los tiempos declarados (energía donde corresponde y silencio en los huecos);
  - un tono de 1 kHz se conserva y uno de 12 kHz desaparece (filtro anti-alias);
  - un ancla ilegible se omite con `{-1,-1}`;
  - cancelación.

### engine: cliente, HTTP y motor
- `HttpApi`:
  - Cuerpo JSON con base64 en streaming y largo conocido: prefijo + `4*ceil(n/3)` + sufijo. Debe avanzar el vigilante y el progreso igual que `copy()`.
  - `require()` con 402/404 de OpenRouter (decisión 8), sin cambiar lo que fijan las pruebas de OpenAI.
- `OpenRouterClient.transcribe(...)`:
  1. Decodifica las data URL de las referencias a archivos temporales.
  2. `OrAudio.build`.
  3. Arma el JSON: `model`, `input_audio{data, format}`, `language` si no es vacío, `response_format`, `timestamp_granularities`, separación de voces según la receta. Encabezados `HTTP-Referer` y `X-OpenRouter-Title: Verbapp`.
  4. Respuesta → normalización (decisión 5) → anclas (decisión 3) → `usage` (decisión 6).
  5. Limpia los temporales.
  6. Reintento simple ante 400 de formato (decisión 4).
- `Transcriber`:
  - `TranscribeClient.of(c, h, config)` en vez de `new OpenAiClient(h)`.
  - Abrir las compuertas `provider=="openai"` de voces guardadas y correcciones también a openrouter.
  - Tamaño de bloque y SINGLE por receta (decisión 7). 413 (decisión 7). `costUsd` (decisión 6). Perfil.
  - Texto de bitácora «OpenRouter está transcribiendo».
  - Tiempos de espera razonables: los proveedores cortan a ~60 s de proceso.
- `Retranscribe`: `Facts`, `reason` y `BEFORE` para openrouter.
- `Transcript`: mensajes que dicen «OpenAI».
- `Pricing.estimate("openrouter", …)`. Pricing no tiene Context: agrega una sobrecarga con Context, o un registro estático que `Models` alimente; decide lo más simple y documenta.
- Pruebas (`OpenRouterChecks`, con `HttpApi` simulado y `OrAudio` real o el archivo tal cual):
  - cuerpo JSON correcto por receta (azure, deepgram, genérico);
  - base64 válido y largo exacto;
  - normalización de los 4 formatos de respuesta;
  - anclas: mapeo, borrado de la zona y resta de tiempos; dos anclas al mismo hablante; ancla sin emparejar;
  - 402, 404 y 413;
  - reintento simple ante 400;
  - `usage.cost`;
  - integración con `Transcript.fromParts` y `_known`.

### catalog: `Models`, ajustes y proveedor
- `Models.refresh`/`cached`/`perMinute`/`checkKey`. Esquema real del catálogo y de `/key` en `docs/PLAN-openrouter.md` y en el mapa.
  - Normaliza el precio a US$/hora con la unidad de la receta: SECOND ×3600, HOUR tal cual, TOKEN/UNKNOWN → -1.
  - Descarta ids con `~` o `:` y los que vencen en menos de 30 días para «Automático».
  - Tolera campos faltantes.
- `SettingsActivity`:
  - Proveedor con tres opciones: **OpenRouter** (primera, «Una sola clave para transcribir y para tus notas»), OpenAI y Servidor compatible.
  - Con OpenRouter:
    - fila «Clave de OpenRouter»;
    - «Comprobar conexión» usa `Models.checkKey` y muestra el saldo si viene («Clave válida · quedan US$ 4,20»);
    - fila «Modelo con voces» y fila «Modelo solo texto», que abren la hoja de modelos desde el catálogo («Automático (recomendado)» primero, botón «Actualizar lista», fecha de la última actualización, estados de carga y error con reintento);
    - fila «Separar voces» como hoy.
  - IA de la nota: tercera opción «OpenRouter», con los modelos `NOTE_NAMES`.
  - Voces conocidas visibles también con OpenRouter; ajusta los textos que dicen «se envían a OpenAI».
  - Fila «Ver la bienvenida» en Ayuda.
  - `verifyTarget()` con las preferencias nuevas.
- `VozApp`: migración de esquema si hace falta. **Nunca** cambiar el proveedor de quien ya usa OpenAI.
- Pruebas (`ModelsChecks`): parseo de un catálogo de ejemplo (incluye modelo desconocido, vencido, alias `~`, precio por hora/segundo/token), «Automático», caché, `checkKey` con respuestas simuladas (válida con y sin límite, 401), `Settings.config` con openrouter, prefijos de clave que no se pisan.

### notes: nota por OpenRouter y compuertas en pantallas
- `Notes`:
  - Proveedor `"openrouter"`: URL `Models.BASE+"/chat/completions"`, clave `settings.openRouterKey()`, modelo alias.
  - Revisa `provider()`, `model()` (la validación hoy rechaza `/` y `~`), `canGenerate`, `rates`, `openaiBody`.
  - Guarda el modelo que respondió (`json.model`) en la nota.
  - Por defecto, si `provider()=="openrouter"` y no hay preferencia `noteProvider`, usa openrouter.
  - No rompas `NotesChecks` existente.
- `RecordingActions`, `RetranscribeSheet`, `RecordingActivity`: todas las compuertas `equals("openai")` que listan los mapas (costos en `askSpeakers` y `showNew`, «Grabar mi voz», «Guardar la voz», texto en vivo solo OpenAI, razones de `Retranscribe`).
  - Mostrar el costo real `costUsd` del estado cuando exista, en vez del estimado.
  - Mostrar en la nota el modelo que respondió.
  - Textos «OpenAI» → el nombre del proveedor real.
- Pruebas: en `NotesChecks`, nota por OpenRouter con `HttpApi` simulado (URL, encabezados, modelo alias, modelo respondido guardado, 402).

### onboarding: `OnboardingActivity` y entrada en `MainActivity`
Pantalla completa con fondo intenso (`shell(null,-1,true)`), sin barra de pestañas, con la estética Verbapp: Outfit, vidrio, palabra destacada, logo. Indicador de pasos arriba, «Saltar» siempre visible, botón principal en tinta abajo, ← para volver. Transiciones suaves que respetan «Quitar animaciones». Funciona en oscuro, con letra grande y con TalkBack. El estado sobrevive al giro.

Pasos (pocos y cortos):
1. **Bienvenida**: logo grande, «Tus palabras, para siempre» y tres beneficios en tarjetas de vidrio: graba sin internet; transcribe separando quién habla; nota lista para tu segundo cerebro.
2. **Tú**: «¿Cómo te llamas?». Campo opcional; se guarda con `Voices.setName` y se usa en el saludo. Botón «Permitir micrófono»: pide RECORD_AUDIO y, en Android 13+, notificaciones. Muestra ✓ cuando está concedido; se puede seguir sin concederlo.
3. **Conecta tu IA**:
   - Dos tarjetas: **OpenRouter** (recomendada, «Una sola clave para todo») y **OpenAI**.
   - Campo para pegar la clave, con botón «Pegar» desde el portapapeles y «¿Cómo consigo una clave?», que abre el navegador en `https://openrouter.ai/keys` o `https://platform.openai.com/api-keys`.
   - Al continuar:
     - guarda con `settings.saveKeyFor(...)` y fija `provider`;
     - comprueba en segundo plano (`Models.checkKey` u `OpenAiClient.verify`) y muestra «Clave válida» o el error, sin bloquear;
     - para OpenRouter deja `noteProvider="openrouter"`.
   - «Ahora no, solo grabar» salta el paso.
   - La clave nunca se muestra después ni se registra.
4. **Listo**: «Todo listo, <nombre>», con dos accesos opcionales:
   - «Grabar mi voz»: abre Ajustes con el extra que hoy abre ese flujo; revisa `SettingsActivity.openFrom`.
   - «Elegir mi carpeta 0-Inbox»: extra `inbox`.
   - Botón principal «Hacer mi primera grabación».

Además:
- `MainActivity`: si `OnboardingActivity.shouldShow(this)`, abre la bienvenida en vez de la hoja `welcome()`, que se elimina. Al volver, Grabar se ve normal, y «Novedades» no aparece encima de quien recién instaló (marca `lastSeenVersion`).
- `welcomed=true` al terminar o saltar. Con `replay=true` no se toca `welcomed` ni las claves ya guardadas (los campos parten vacíos y solo se guarda si el usuario escribe algo).
- Pruebas (`OnboardingChecks`): lógica sin UI (p. ej. `shouldShow`, que `replay` no pisa claves, validaciones de la clave).

## Verificación que cada parte hace antes de terminar

1. Tu worktree parte de `main`: primero `git merge --ff-only <commit de la fase 0>` (te lo dan en la tarea) y copia `local.properties` desde el repositorio principal, sin agregarlo al commit.
2. Compilar hasta que pase (rutas absolutas del repositorio principal):
   ```powershell
   $env:JAVA_HOME="<repo>/.tools/jdk/jdk-17.0.18+8"; $env:GRADLE_USER_HOME="<repo>/.tools/gradle-cache"; $env:ANDROID_USER_HOME="<repo>/.tools/android-user"; & "<repo>/.tools/gradle-8.11.1/bin/gradle.bat" --no-daemon -q assembleDebug assembleDebugAndroidTest lintDebug
   ```
3. Commit en tu rama con mensaje en español, escrito en un archivo (`git commit -F`), que termine con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
4. Informe: qué hiciste, qué supuestos no confirmados quedaron, qué necesitas de otra parte o de los archivos compartidos, y qué debería probar el usuario con su clave real.
