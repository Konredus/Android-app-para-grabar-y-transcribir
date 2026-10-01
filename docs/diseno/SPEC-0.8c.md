# SPEC 0.8.0 (tercera ronda): que nada quede esperando sin avisar, y empalmes

Fuente: el **diagnóstico del 2026-10-01** en `docs/PENDIENTES.md`. Un audio de 4 min tardó 1:15 en total, con solo 1 minuto de trabajo. Se perdieron 27 min esperando Wi-Fi y 45 min con la tarea pausada por Android, y la conversión fue lenta. Además se cierran los pendientes cruzados que dejaron las partes de la segunda ronda (informes en la tarea de cada agente).

Reglas de siempre: las de `SPEC-0.8.md` y `SPEC-0.8b.md`. No hay red real ni emulador; los cambios se prueban con pruebas instrumentadas deterministas.

## Decisiones

1. **Conversión al menos 5 veces más rápida.**
   - Hoy un bloque de 4 min tarda 31–43 s en primer plano y 150 s en segundo plano; uno de 9 min, 292 s.
   - Cambios: cálculo en float; menos coeficientes (para voz bastan 12–16 por lado, con corte bajo 7,5 kHz); camino directo para 48k→16k (decimación entera ×3) y 32k→16k; y una comprobación del FLAC barata (cabecera, cuadros y conteo de muestras) en vez de decodificarlo entero.
   - Calidad mínima: un tono de 1 kHz se conserva y uno de 12 kHz se elimina, como prueban hoy las pruebas.
   - La etapa «Preparando audio» muestra **avance en %**.
   - Las pruebas miden el tiempo y fallan si empeora mucho.
2. **«Usar datos móviles ahora».**
   - Si una transcripción espera Wi-Fi, el detalle muestra un botón grande «Usar datos móviles ahora (≈X MB)», con el tamaño estimado del envío.
   - La notificación de «esperando Wi-Fi» trae esa acción.
   - Vale solo para esa grabación (clave de estado `mobileOk`). La preferencia general «Solo con Wi-Fi» no cambia.
3. **Transferencia iniciada por el usuario (Android 14+).**
   - Al tocar «Transcribir», con la app visible, se programa un trabajo `JobInfo.Builder.setUserInitiated(true)`, con el permiso `RUN_USER_INITIATED_JOBS` (ya está en el manifiesto) y la red que corresponda.
   - Así sigue aunque la app pase a segundo plano o se espere la red, sin las cuotas de las tareas de fondo.
   - Exige notificación (`JobService.setNotification`) dentro de los primeros segundos.
   - En Android < 14 o si falla, todo sigue como hoy: servicio en primer plano y, si no, tarea de fondo.
   - El trabajo automático (al guardar, sin toque) sigue como hoy.
4. **La bienvenida pide «Transcribir con el teléfono bloqueado».**
   - Paso o tarjeta con el permiso de batería (`Battery.request`) y la guía del fabricante (`Battery.makerHint`, p. ej. vivo).
   - Muestra ✓ si ya está permitido. Se puede saltar.
5. **Avisos al rendirse.** `PipelineJob.timedOut`, al quinto intento, avisa con notificación igual que el motor (`Transcriber.attention`).

## Propiedad de archivos

| Parte | Archivos |
|---|---|
| **engine** | `OrAudio.java`, `Transcriber.java`, `Pipeline.java`, `PipelineJob.java`, `TranscribeService.java`, `OpenRouterClient.java`, `HttpApi.java`, `Retranscribe.java`, `Pricing.java`, `Transcript.java`, `AudioParts.java`, `RecordingActivity.java`, `androidTest/OrAudioChecks.java`, `androidTest/OpenRouterChecks.java`, `androidTest/EngineChecks.java` |
| **onboarding** | `OnboardingActivity.java`, `MainActivity.java`, `Battery.java`, `androidTest/OnboardingChecks.java` |
| **settings** | `Settings.java`, `SettingsActivity.java`, `Models.java`, `Notes.java`, `RecordingActions.java`, `RetranscribeSheet.java`, `Metrics.java`, `MetricsActivity.java`, `androidTest/ModelsChecks.java`, `androidTest/NotesChecks.java`, `androidTest/MetricsChecks.java` |

Compartidos que no edita nadie: `AndroidManifest.xml`, `Diagnostics.java`, el kit visual, `RecorderSmokeTest.java`, `novedades.json` y los docs. Lo que necesites de ellos va en tu informe.
