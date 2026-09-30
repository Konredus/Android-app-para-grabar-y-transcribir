# Plan 0.8 · Transcribir y armar notas con OpenRouter (una sola key)

> **Pedido (29-09-2026):** «Voy a usar una API key de OpenRouter… que me redirija a OpenAI para las transcripciones o a otro modelo que transcriba mejor. Ayúdame a generar un listado de posibles modelos y versiones que pueda seleccionar y que esté siempre lo último (de forma automática)».
> **Investigación:** dos workflows de investigación con verificación escéptica, del 29 y 30-09-2026. Usaron fuentes primarias: la documentación de openrouter.ai, la API pública sin key y la documentación de cada proveedor. Hay datos marcados como **no confirmados**: se validan con la key real.

## 1. Lo confirmado

**Endpoint de transcripción:** `POST https://openrouter.ai/api/v1/audio/transcriptions`
- Usa la misma key Bearer que el chat. Existe desde el 01-05-2026 y acepta multipart desde el 07-07-2026.
- Se puede mandar de dos formas:
  - JSON: `input_audio {data: base64 sin prefijo, format}`. Sirve para archivos grandes, pero su tope no está documentado.
  - multipart estilo OpenAI: tope de 25 MB. Ojo: el issue docs#576 reporta que en multipart se descartan las `provider.options`, así que la separación de voces falla sin avisar.
- `response_format` acepta solo `json` o `verbose_json`. **No existe `diarized_json`.**
- Parámetros: `language` (`es`), `timestamp_granularities` (segment/word), `diarize` (booleano genérico), `keyterms` (hasta 1000 términos) y `provider.options.<tag>` para opciones propias de cada proveedor.
- Los hablantes llegan como índices enteros en `segments[].speaker` y/o `words[].speaker`/`speaker_label`.
- Los proveedores cortan a los ~60 s de proceso: los audios largos hay que partirlos (la app ya los parte).
- **No hay ruteo automático por calidad.** La app debe indicar el id exacto del modelo. `order`/`only`/`ignore` no se aplican a la transcripción.
- **No hay voces conocidas** (`known_speaker_*`). **`gpt-4o-transcribe-diarize` no está en OpenRouter** (devuelve 404).
- Por su lado, OpenAI **retira `gpt-4o-transcribe-diarize` el 2027-02-26**, sin un reemplazo que separe voces.

**Catálogo público** (sin key; caché de 120 s):
- `GET /api/v1/models?output_modalities=transcription&sort=newest` devuelve **24 modelos**.
- Otros filtros útiles: `zdr=true`, `min_age_days` / `max_age_days` y `region`.
- Detalle de un modelo: `GET /api/v1/model/{autor}/{slug}`. Proveedores con su `tag` y `status`: `links.details` (…/endpoints).
- `created` es la fecha en que se **agregó a OpenRouter**, no la de lanzamiento, y **no mide calidad**.
- `pricing.prompt` no trae unidad: es por segundo en la mayoría, **por hora** en los MAI y por token en gpt-4o-*/gemini.
- `supported_parameters` viene vacío en los modelos de transcripción y no hay benchmarks de voz por API.

**Notas (chat)** — alias que apuntan siempre a la última versión de su familia:
- `~anthropic/claude-sonnet-latest` (hoy es claude-sonnet-5.5).
- `~openai/gpt-luna-latest` (hoy es gpt-6-luna).
- `~google/gemini-flash-latest`.

La respuesta trae en `model` la versión concreta que respondió. Para transcripción no hay alias.

**Costos:**
- Crédito comprado: +5,5 %.
- BYOK: sin comisión hasta US$25.000 al mes.
- El costo real de cada llamada viene en `usage.cost`.

## 2. Matriz (reuniones de 60–90 min, español, 2 a 4 personas)

| Modelo (id) | US$/hora | Separa voces vía OpenRouter | Límite | Formatos | Notas |
|---|---|---|---|---|---|
| **microsoft/mai-transcribe-2** | **0,10** (promoción hasta fin de 2026) | **Sí, documentado:** `provider.options.azure.diarization.enabled` | < 2 h / 250 MB | **WAV/MP3/FLAC (m4a da 400)** | Mejor AA-WER (2,0 %), muy rápido y ZDR. En preview |
| deepgram/nova-3 | 0,26 | **Sí, documentado:** `provider.options.deepgram.diarize` | No publicado (25 MB en multipart) | **m4a directo** | Una reunión completa en una sola request (hablantes coherentes); AA-WER 5,2 %; ZDR |
| google/gemini-3.5-transcribe | ~0,30 | Sí en el modelo; método sin documentar en OpenRouter | **30 min con voces** | m4a probable | Con 3 o más personas es experimental; retiene 55 días |
| mistralai/voxtral-mini-transcribe (Transcribe 2) | 0,18 | No confirmado | ~3 h (Mistral) | — | AA-WER 3,6 %; retiene 30 días |
| fish-audio/transcribe-1-pro | 0,36 | Sí, con marcas `<\|speaker:N\|>` en el texto | 60 min / 20 MB | m4a probable | Español sin medir |
| x-ai/grok-stt-1.0 | 0,10 | No confirmado | — | m4a | La versión 1.0 se retira pronto; retiene 30 días |
| openai/gpt-transcribe | 0,27 | **No** | 25 MB | m4a | Solo texto |
| assemblyai/universal-3-5-pro | 0,23 | No (Sync API) | **120 s** | Solo WAV | Descartado |
| meta/muse-voice-transcribe-1.0 | 0,18 | No confirmado | **10 min** | Solo WAV 16 kHz | Descartado |
| mai-transcribe-1.5, fish transcribe-1, voxtral 2507, qwen3-asr, nemotron | 0,01–0,36 | No | — | — | Solo para un modo «solo texto» |

Referencia: hoy, con OpenAI directo (gpt-4o-transcribe-diarize, US$0,006/min), 90 min cuestan ≈ US$0,54. Con MAI-2 costarían ≈ US$0,15.

## 3. Arquitectura propuesta
1. **Proveedor «OpenRouter»** en Ajustes: una sola key, verificada con `GET /api/v1/key`, que además muestra el saldo.
2. **Transcripción**:
   - Por defecto `microsoft/mai-transcribe-2`; respaldo `deepgram/nova-3`.
   - Conversión a WAV 16 kHz mono (o FLAC) dentro de `AudioConvert` para MAI-2.
   - Trozos de ~15 min con 45–60 s de solape.
   - Parser de `verbose_json` para Azure, Deepgram, el `diarize` genérico y las marcas de Fish.
   - Unir los hablantes entre trozos por el solape.
3. **Voces conocidas 2.0 («anclas», experimental)**:
   - Antes de cada trozo se anteponen las muestras guardadas (8–10 s cada una, con 1 s de silencio).
   - Se lee qué índice de hablante le tocó a cada ancla y así se sabe quién es cada índice.
   - Después se borra esa zona del texto y se restan los tiempos.
   - Hay que medir cuánto acierta con audios reales.
4. **Lista que se actualiza sola**:
   - Descubrimiento diario desde el catálogo.
   - Receta por tag de proveedor (cómo pedir voces, qué formato mandar, qué límite respetar).
   - Los modelos nuevos aparecen como «Nuevo · sin probar».
   - Opción **«Automático (recomendado)»** = el mejor modelo verificado. Solo se promueve otro si mejora en una **prueba canaria** en español con 3 voces (costo < US$0,01).
   - El precio real sale de `usage.cost / usage.seconds`.
5. **Notas** con los alias `~…-latest`: la nota muestra qué versión respondió.
6. **Opcional**: mantener OpenAI directo (con voces conocidas exactas) hasta el 2027-02-26, si el usuario conserva esa key.
