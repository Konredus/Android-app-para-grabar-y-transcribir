# SPEC 0.6.0: contratos entre partes (para implementar en paralelo)

Fuente de requisitos: `docs/diseno/PROPUESTA-0.6.md` (incluida la sección «Ajustes tras la revisión crítica») y `docs/PENDIENTES.md` («Para la próxima versión»).
Reglas del proyecto:
- Java sin librerías de runtime.
- UI programática solo con el kit `Ui`/`Sheet`/`Screen` y los tokens de `AppTheme`.
- Material 3 nativo.
- Textos en español de Chile, claros y sin jerga (ver `docs/diseno/CRITERIOS.md` §7).
- **No se pierde ninguna capacidad actual:** grabar, pausar, título durante la grabación, importar y recortar, transcribir con o sin voces, «Mi voz», correcciones de voces con Deshacer, guardado rápido, «Guardar en…», copiar, compartir, .txt, carpeta de copias, informe de soporte, etc.

## Propiedad de archivos (cada parte edita SOLO lo suyo)

| Parte | Archivos que puede editar o crear |
|---|---|
| **engine** | `Transcriber.java`, `AudioParts.java`, `Pipeline.java`, `TranscribeService.java`, `PipelineJob.java`, `OpenAiClient.java`, `Transcript.java`, `Retranscribe.java`, `Recording.java`, `androidTest/EngineChecks.java` |
| **notes** | `Notes.java`, `Inbox.java`, `LocalStorage.java`, `TranscriptExport.java`, `androidTest/NotesChecks.java` |
| **media** | `RecorderService.java`, `Marks.java`, `WaveData.java`, `androidTest/MediaChecks.java` |
| **home** | `MainActivity.java`, `ImportActivity.java`, `ImportService.java`, `ImportSession.java`, `Waveform.java`, `RecordButton.java`, `BottomNav.java`, `RangeView.java` |
| **detail** | `RecordingActivity.java` |
| **sheets** | `RecordingActions.java`, `NameVoices.java`, `RetranscribeSheet.java` |
| **settings** | `SettingsActivity.java`, `Novedades.java`, `app/src/main/assets/novedades.json` |
| **design** | `Ui.java`, `AppTheme.java`, `Sheet.java`, `Screen.java`, `res/drawable/*` (íconos nuevos o rediseñados), `androidTest/UiChecks.java` |

- Compartidos que NO edita nadie durante la fase paralela: `Settings.java`, `Diagnostics.java`, `AndroidManifest.xml`, `FilesStore.java`, `build.gradle` y los tests existentes. Si necesitas un cambio en uno de ellos, descríbelo en tu informe final y se aplica en la integración.
- Los stubs (firmas) ya existen y compilan: **no cambies una firma pública de un stub ajeno**. Si tu parte es dueña de un stub, puedes AGREGAR métodos, pero no romper las firmas documentadas aquí.

## Estado por grabación (`FilesStore.state(c,id)`, archivo `<id>.sync.json`)

Claves nuevas (todas opcionales):

| Clave | Contenido |
|---|---|
| `marks` | `[{"t": ms, "label": ""}]`, momentos ★ (escribe **media** vía `Marks`) |
| `inboxUri` | documento en la carpeta rápida |
| `inboxAt` | ms de la última escritura |
| `inboxKind` | `"md"` o `"txt"` |
| `snippet` | ~120 caracteres del comienzo de la transcripción («Konrad: la idea es…»); lo escribe **engine** al guardar la transcripción |
| `noteState` | `"working"`, `"ready"` o `"failed"`; `noteError` lleva el texto |
| `retranscribe` | `{"mode":"CORRECTIONS|SINGLE|SPEAKERS|TEXT","at":ms}` mientras se repite una transcripción |
| `suggestedTitle` | título que sugiere la nota (lo escribe **notes**) |
| `opened` | ms en que el usuario abrió la grabación después de «lista» (lo escribe **detail**; **home** lo usa para el punto «nuevo») |

## Archivos por grabación

- `<id>.note.json` (**notes**):

  ```json
  {"version":1,"provider":"openai|anthropic","model":"…","createdAt":ms,"title":"…","summary":"…","decisions":["…"],"tasks":[{"text":"…","who":"S1","when":"viernes","done":false}],"quotes":[{"t":seg,"text":"…","who":"S2"}],"tags":["…"],"speakers":{"S1":"<id de voz>","S2":"…"}}
  ```

  Los textos pueden contener `{S1}` o `{S2}`, que se reemplazan por el nombre actual de esa voz (`Notes.resolve`). Así la nota sigue siendo válida después de nombrar o corregir voces.
- `<id>.transcript.prev.json` (**engine**): versión anterior al volver a transcribir.
- `<id>.wave.json` (**media**): `{"n":600,"v":[0..1 …]}`, la envolvente del audio.

## Contratos (stubs ya creados; el dueño los implementa)

- **Settings** (ya implementado en la fase 0):
  - `noteAuto()` (por defecto true);
  - `noteProvider()` → `"openai"` o `"anthropic"`;
  - `noteModel()`;
  - `hasAnthropicKey()`, `anthropicKey()`, `saveAnthropicKey(v)`;
  - `inboxTree()` (el uri o `""`), `inboxName()`;
  - `lastSeenVersion()` / `setLastSeenVersion(int)`;
  - `bitacoraOpen()` / `setBitacoraOpen(b)`.
- **Notes** (notes):
  - `exists(c,id)`, `load(c,id)` → JSONObject o null;
  - `generate(c,r,http) throws Exception`: lee la transcripción y las marcas, llama al proveedor, guarda la nota, deja `suggestedTitle` y `noteState`;
  - `canGenerate(c)`;
  - `resolve(text,names)`;
  - `markdown(c,r)` → String: la nota completa .md con frontmatter; sin nota, solo transcripción;
  - `setTaskDone(c,id,index,done)`;
  - `delete(c,id)`.
- **Inbox** (notes):
  - `configured(c)`, `folderName(c)`;
  - `save(c,r) throws Exception` → Uri: escribe la nota .md, o el .txt si no hay nota, en la carpeta rápida. Si ya existe `inboxUri`, **reemplaza el mismo documento** (modo "wt", y "w" como respaldo), sin crear duplicados. Actualiza `inboxAt`, `inboxUri` e `inboxKind`;
  - `savedAt(c,id)` → ms o 0;
  - `outdated(c,id)` → hay cambios en la transcripción o la nota posteriores a `inboxAt`.
- **Retranscribe** (engine):
  - `enum Mode{CORRECTIONS,SINGLE,SPEAKERS,TEXT}`;
  - `available(c,r,mode)` (bool) y `reason(c,r,mode)` (texto si no está disponible);
  - `start(c,r,mode) throws Exception`: mueve la transcripción a prev, marca el estado y encola;
  - `hasPrevious(c,id)`, `keepNew(c,id)`, `restorePrevious(c,id)`.
- **Marks** (media):
  - `list(c,id)` → JSONArray ordenado por t;
  - `add(c,id,ms,label)`, `remove(c,id,index)`, `rename(c,id,index,label)`.
- **WaveData** (media):
  - `cached(c,r)` → `float[]` o null (rápido, sin decodificar);
  - `compute(c,r)` → `float[]`, que BLOQUEA: se llama desde un hilo de fondo; decodifica, cachea y devuelve `n` valores de 0 a 1.
- **RecorderService** (media):
  - nuevas acciones de intent: `"MARK"` (extra opcional `label`) y `"RESUME"`;
  - `static int marksCount()`, `static long lastMarkAt()`;
  - `static long startedAt()` (elapsedRealtime del inicio), para ignorar Detener durante el primer segundo.
- **NameVoices** (sheets): `show(Screen s, String id, boolean demo, Transcript t, Runnable changed)`.
- **RetranscribeSheet** (sheets):
  - `show(Screen s, Recording r, Runnable changed)`;
  - `offerKeep(Screen s, Recording r, Runnable changed)`: hoja «Nueva versión lista», con «Quedarme con la nueva» / «Volver a la anterior».
- **Novedades** (settings):
  - `maybeShow(Screen s)`: una vez por versión, no en la primera instalación;
  - `showAll(Screen s)`;
  - `current(c)` → la lista de puntos de la versión actual.
- **Ui** (design; la fase 0 dejó versiones básicas que funcionan):
  - `enum Haptic{CONFIRM,REJECT,TICK,TOGGLE_ON,TOGGLE_OFF}` y `static void haptic(View,Haptic)`;
  - `Ui.Split split(String label,int icon,View.OnClickListener main,View.OnClickListener more)`: botón de dos partes con `setLabel`, `setIcon`, `setTonal(boolean)` y `setBusy(boolean)`;
  - `static String humanDuration(long ms)`: «5 s», «4 min», «52 min», «1 h 04 min».

## Intents entre pantallas

- `RecordingActivity` recibe extras: `id` (ya existe), `names:true` (abrir «Nombrar voces»), `save:true` (guardar en 0-Inbox al abrir) y `retranscribed:true` (ofrecer quedarse o volver).
- La notificación «lista» (engine) abre `RecordingActivity` con `id`. Sus acciones:
  - «Revisar voces» (`names:true`), si hay voces separadas sin revisar;
  - si no, «Guardar en 0-Inbox» (`save:true`), si la carpeta rápida está configurada.

  Usa el canal nuevo `"done"`, con importancia DEFAULT (suena una vez).
- `MainActivity` llama `Novedades.maybeShow(this)` en `onResume`, después de la bienvenida.

## Calidad

- Compila con `assembleDebug assembleDebugAndroidTest`.
- Pruebas de tu lógica pura en tu `androidTest/*Checks.java`. `RecorderSmokeTest` ya llama a `EngineChecks.run`, `NotesChecks.run`, `MediaChecks.run` y `UiChecks.run`.
- Nada de llamadas reales a APIs en las pruebas: usa un `HttpApi` falso, como en `FeatureChecks`.
- Accesibilidad:
  - toques de 48 dp;
  - `contentDescription` en lo que es solo ícono;
  - al cambiar el texto de un `Ui.Btn`, usar `setText`.
- Modo oscuro: solo roles de `AppTheme`, sin hex sueltos.
