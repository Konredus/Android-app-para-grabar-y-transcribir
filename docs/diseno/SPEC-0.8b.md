# SPEC 0.8.0 (segunda ronda): solo OpenRouter, métricas y correcciones de la revisión

**Pedidos del usuario (2026-10-01):**
1. **Solo OpenRouter.** «Que la API sí o sí pase por OpenRouter (lo mismo en el onboarding). Nada de configurar otras APIs; enfoquémonos todo en OpenRouter, que es más cómodo y más fácil para elegir varios modelos.»
2. **Métricas en Ajustes.** «Métricas de uso, de conversión, de tokens usados y cuánto equivale en USD… un poco más de métricas en general, eso se ve bonito.» El público objetivo es alguien que habla mucho y quiere cargar lo transcrito a su segundo cerebro. Ideas en `docs/PENDIENTES.md` («Para la 0.9: métricas de uso en Ajustes»).
3. **Informe de soporte con fecha y hora en el nombre.** Ya está hecho en la fase 0b: `Diagnostics.reportName/isReport`, AudioProvider y SettingsActivity.
4. **Una transcripción de un audio cortito quedó «bloqueada» más de 1 hora.** Todavía no llega el informe. Mientras tanto se refuerza lo que podría explicarlo (ver la parte engine).

Además:
- se corrigen los **hallazgos verificados de la revisión** de la primera ronda: `docs/diseno/revision-0.8.json`, con el campo `area`;
- se corrige la frase «Todo queda en tu teléfono» de la bienvenida, que es falsa: el audio se envía a OpenRouter para transcribirse.

Reglas de siempre: ver `docs/diseno/SPEC-0.8.md` («Reglas»).
- No hay red ni clave real.
- No se usa el emulador.
- Privacidad en la bitácora y en Diagnostics.
- Estética Verbapp.
- Cada parte edita solo lo suyo.

## Decisiones

1. **Interfaz solo con OpenRouter.**
   - Ajustes, la bienvenida y la nota muestran y configuran únicamente OpenRouter.
   - Desaparecen de la interfaz:
     - la elección de proveedor;
     - OpenAI directo, con su clave, sus modelos y «Comprobar» de OpenAI;
     - el servidor compatible;
     - la clave de Anthropic;
     - la elección de proveedor de la nota.
   - El **código** de OpenAI y del servidor compatible puede quedar (lo cubren pruebas), pero no se llega a él desde la interfaz.
   - `Settings.provider()` vale `"openrouter"` por defecto.
2. **Migración (VozApp, esquema 5)**, para quien ya tenía la app:
   - `provider` pasa a `"openrouter"` y se borra la preferencia `noteProvider`, así la nota usa OpenRouter.
   - Las claves viejas cifradas no se borran: se dejan sin uso y la interfaz no las muestra.
   - Si no hay clave de OpenRouter, la app lo pide donde ya lo pide cuando falta la clave: el estado de Grabar («Configurar transcripción») abre la clave de OpenRouter en Ajustes.
   - Las grabaciones, transcripciones, voces y notas no se tocan.
   - Las transcripciones en cola se hacen con OpenRouter. Si falta la clave, quedan para «Reintentar» con el mensaje de siempre.
3. **Nota:** siempre por OpenRouter. El modelo es un alias de `Models.NOTE_MODELS` o «Otro modelo de OpenRouter…».
4. **Métricas:**
   - Pantalla propia `MetricsActivity` («Tus métricas»).
   - Se entra desde una tarjeta resumen en Ajustes, que muestra `Metrics.summaryLine`, y desde «Tu semana» en Grabar.
   - Todo se calcula en el teléfono y fuera del hilo principal.
5. **Etapa «Preparando audio»** con OpenRouter: mientras se convierte el bloque, la pantalla y la bitácora lo dicen, en vez de «Enviando… 0 %». El vigilante de subida no corta durante la conversión: su tiempo cuenta aparte.

## Propiedad de archivos

| Parte | Archivos |
|---|---|
| **settings** | `Settings.java`, `SettingsActivity.java`, `Models.java`, `Notes.java`, `VozApp.java`, `RecordingActions.java`, `RetranscribeSheet.java`, `androidTest/ModelsChecks.java`, `androidTest/NotesChecks.java` |
| **onboarding** | `OnboardingActivity.java`, `MainActivity.java`, `androidTest/OnboardingChecks.java` |
| **engine** | `OpenRouterClient.java`, `TranscribeClient.java`, `HttpApi.java`, `Transcriber.java`, `Transcript.java`, `OrAudio.java`, `Retranscribe.java`, `Pricing.java`, `AudioParts.java`, `RecordingActivity.java`, `androidTest/OpenRouterChecks.java`, `androidTest/OrAudioChecks.java` |
| **metrics** | `Metrics.java`, `MetricsActivity.java`, `androidTest/MetricsChecks.java`, drawables `metrics_*` |

Compartidos que no edita nadie: `AndroidManifest.xml` (ya registra `MetricsActivity`), `Diagnostics.java`, `AudioProvider.java`, el kit visual (`AppTheme`, `Ui`, `Glass`, `Screen`, `Sheet`, `BottomNav`), `RecorderSmokeTest.java` (ya llama a `MetricsChecks`), `assets/novedades.json` y los docs. Si necesitas algo de ellos, pídelo en tu informe.

## Contratos de la fase 0b (ya compilan)

- `Metrics.summaryLine(Context)`: una línea para la tarjeta de Ajustes. Lee disco.
- `MetricsActivity.open(Context)`.
- `Diagnostics.reportName(long)` / `isReport(String)`.

## Qué hace cada parte

### settings
- **Ajustes solo con OpenRouter.** Una sección «Tu IA (OpenRouter)» con:
  - la clave, con el enlace «¿Cómo consigo una clave?» a `https://openrouter.ai/keys`;
  - «Comprobar conexión», con el saldo;
  - «Modelo con voces» y «Modelo solo texto», con la hoja actual;
  - «Separar voces»;
  - «IA de la nota», con los modelos de OpenRouter.

  El resto de Ajustes queda igual. Revisa todos los textos que mencionen OpenAI, Anthropic, «proveedor» o «servidor».
- **Tarjeta «Tus métricas»** cerca del inicio de Ajustes: vidrio, cifra grande y `Metrics.summaryLine`, cargada en segundo plano. Al tocarla, `MetricsActivity.open`.
- Migración de la decisión 2 en `VozApp`.
- `Notes`: solo OpenRouter, sin romper las pruebas que fijan los otros caminos.
- `RecordingActions` / `RetranscribeSheet`: textos y compuertas coherentes con «solo OpenRouter».
- Corrige los hallazgos de las áreas `catalog-settings` y los de `notes-onboarding` que tocan tus archivos. Si un hallazgo dejó de tener sentido porque ya no hay otros proveedores, dilo («ya no aplica») y explica por qué.

### onboarding
- **Paso «Conecta tu IA» solo con OpenRouter:**
  - sin la tarjeta de OpenAI;
  - campo de la clave con «Pegar» y «¿Cómo consigo una clave?»;
  - una **explicación clara y honesta**: «Para transcribir, tu audio se envía a OpenRouter y al modelo que elijas; tu clave paga solo lo que usas».
- La frase «Todo queda en tu teléfono» y cualquier otra parecida pasa a algo verdadero, p. ej. «Grabas sin internet; el audio queda en tu teléfono hasta que lo transcribes».
- `MainActivity`:
  - «Tu semana» abre `MetricsActivity`;
  - el estado «Configurar transcripción» lleva a la clave de OpenRouter;
  - revisa los textos de la bienvenida vieja que hayan quedado.
- Corrige los hallazgos de `notes-onboarding` que tocan tus archivos.

### engine
- Corrige los hallazgos de las áreas `client`, `engine` y `audio`.
- **Etapa «Preparando audio»** (decisión 5):
  - el estado o la bitácora indican la conversión;
  - `RecordingActivity` la muestra como etapa propia;
  - el vigilante no la cuenta como subida detenida;
  - `sendEstimate` y el presupuesto de la tarea de fondo consideran su tiempo.
- **Robustez ante el «bloqueo»** del usuario: revisa todos los caminos en que una transcripción corta podría quedar en cola o reintentando por más de una hora sin avisar.
  - Ejemplos: conversión que falla siempre, vigilante que corta en bucle, `NeedsForeground` sin servicio, cortes locales que no gastan intento, 429/5xx de OpenRouter.
  - Cada caso debe terminar con un aviso claro o avanzar.
  - Deja en la bitácora y en Diagnostics lo necesario para diagnosticar el próximo caso desde el informe, sin datos personales.
- Si un proveedor rechaza el FLAC (400 por formato de audio), reintenta una vez en WAV (`OrAudio.build(…, false)`).

### metrics
- `Metrics`: cálculo con lo que ya existe.
  - Estado `.sync.json`: `audioMs`, `costUsd`, `inTokens`, `outTokens`, `usageSec`, `provider`, `model`, `inboxAt`, `doneAt`, `queuedAt`, `marks`.
  - Transcripciones: palabras, personas y voces conocidas.
  - Notas (`.note.json`).
  - Grabaciones (`created`, `duration`).
- **Contenido:**
  - **Tu voz en números:** horas grabadas, palabras transcritas, palabras por minuto, «tiempo ahorrado» frente a escribir a mano (40 palabras/min al teclear) y racha de días con grabaciones; por semana, mes y total.
  - **Embudo:** grabadas → transcritas → con nota → en 0-Inbox, con el % de cada paso y acceso a las que quedaron a medio camino (abre la Biblioteca con el filtro que corresponda, si existe, o el detalle).
  - **Costos:** US$ del mes y del total, US$ por hora de audio, desglose por modelo y tokens de las notas. Real (`costUsd`) frente a estimado (`Pricing`), siempre rotulado.
  - **Gráficos:** barras por semana (minutos grabados y US$, últimas 8 semanas), mapa de días y horas (7×24 o por franjas) y personas con las que más conversas (voces conocidas o nombres de las transcripciones, solo en pantalla).
- **Diseño:** con la estética Verbapp. **Carga la guía `dataviz` (Skill) antes de dibujar gráficos.** Gráficos propios (Views con Canvas), legibles en claro y en oscuro, con descripción para TalkBack, y que respeten «Quitar animaciones».
  - Estado vacío amable: sin grabaciones todavía.
  - Rendimiento: el cálculo de cientos de grabaciones fuera del hilo principal, con caché por archivo si hace falta.
- `MetricsChecks`: cálculo con datos sintéticos en una carpeta temporal o con grabaciones de prueba (totales, embudo, costos reales frente a estimados, semanas, racha) sin tocar los datos reales.

## Verificación de cada parte
Igual que en `SPEC-0.8.md` («Verificación»): `git merge --ff-only <fase 0b>`, `local.properties`, compilar con lint y commit con `git commit -F`.

El informe debe decir:
- qué hiciste;
- qué hallazgos corregiste (y cuáles «ya no aplican» y por qué);
- supuestos;
- qué necesitas de otros.
