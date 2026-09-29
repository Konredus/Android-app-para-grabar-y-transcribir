# Plan: voces que se confunden en «Prueba con la Fran»

> Propuesto el 2026-09-28. **Etapas 1 y 2 implementadas en la 0.5.0** (con bloques de hasta 12 min en vez de 15, para no alargar el primer bloque). El usuario confirmó escuchando que «Fueron dos horas» es la Fran y «Estoy terminando mi CV» es Konrad: los nombres de esa grabación quedaron cruzados por el error del bloque 1. Investigación: documentación de OpenAI, revisión del código, análisis de la transcripción y verificación adversarial de las hipótesis.

## 1) Qué pasó

**En una frase:** la confusión del minuto 1 o 2 no la causaron los cortes cada 5 minutos. Ocurre dentro del primer bloque (00:00 a ~05:00), que OpenAI procesó de una sola vez y sin ninguna muestra de voz. Ahí su modelo mezcló las dos voces al principio. Los cortes y nuestro código explican otra cosa: que aparezcan 4 personas en vez de 2.

**Confirmado (en el código, el .txt y la bitácora):**
- **El primer bloque termina entre 4:50 y 5:10.** Se envía solo, sin muestras de voz, y la app muestra sus etiquetas tal como llegan. Lo que cambió en el minuto 1 o 2 lo decidió el modelo de OpenAI; nuestro código no toca esa parte.
- **«Persona 4» no existe.** Aparece una sola vez, en 07:19, y sin texto. Hay más líneas vacías en 02:39, 08:54, 09:24 y 09:31. Hoy la app convierte esos trozos vacíos en intervenciones, y a veces en «personas».
- **«Persona 3» no aparece en el .txt con ese nombre.** Eso quiere decir que le diste un nombre que ya usabas (seguramente «Fran»). Es una voz del bloque 2 que la app no reconoció con las muestras del bloque 1.
- **Los 26 minutos no caben en un solo envío.** El modelo rechaza audios de más de 1400 s (unos 23 min). Lo sabemos por el mensaje de error real del servidor; la documentación no lo dice. El mínimo son 2 bloques.
- **OpenAI no deja ayudar al modelo de otra forma.** Este modelo no acepta «cuántas personas hablan» ni instrucciones escritas. Lo único que acepta son muestras de voz: hasta 4 personas, de 2 a 10 s cada una.
- **OpenAI anunció que retira este modelo el 26 de febrero de 2027.** No encontramos que su reemplazo separe voces. Por eso conviene invertir en cosas que sirvan con cualquier proveedor, como las correcciones a mano.

**Probable (sacado del texto, sin escuchar el audio):**
- **Pudo haber fallado solo el arranque.** El modelo habría puesto mal las primeras frases (00:10 a 00:36), y desde ~01:30 las dos etiquetas quedan bastante estables. Si elegiste los nombres mirando esas primeras frases, puede que hayan quedado cruzados. Hay pistas: «estoy segura» (08:36) está en «Persona 1», así que Persona 1 sería la Fran. «¿A qué hora, mami?» (02:27) está en la etiqueta que llamaste «Fran», así que esa serías tú. Si es así, basta con cambiar los nombres y solo el primer minuto queda mal. **Hay que confirmarlo escuchando** (sección 3).
- **El corte de los 5:00 se ve bien unido.** La misma historia (cotización, CV) sigue con la misma etiqueta en 04:58 y en 05:02.
- **Hay dos puntos débiles en nuestro código que no causaron este caso, pero pueden causar otros:**
  - Las muestras de voz para los bloques 2 a 5 se cortan de lo que dijo el bloque 1, sin revisar que en ese trozo hable una sola persona. Si el bloque 1 se equivocó, el error se copia a todo el audio.
  - Enviamos las muestras con los nombres «A» y «B», las mismas letras que el modelo usa para voces que no reconoce. Eso puede mezclar en silencio una voz nueva con otra. Es una deducción nuestra; no está documentado.
- **Después del minuto 12 hay muy poco texto** (1 a 4 líneas por minuto). Puede que hablaran poco, o que el modelo se saltara partes (hay reportes de que omite frases). Lo revisamos en el paso 0.

**Por qué salieron 4 personas:** 2 del bloque 1, más 1 voz no reconocida en el bloque 2 (P3), más 1 trozo vacío (P4).

**Lo que puedes hacer hoy, sin esperar cambios:** escucha 02:58 y 03:53, pon los nombres según lo que oigas y da por perdido el primer minuto.

---

## 2) Plan por etapas

Esfuerzo: S = medio día o menos · M = 1 a 2 días · L = 3 días o más.

### Paso 0: diagnóstico con los datos originales (S, antes de tocar código)
- **Para ti:** nada visible. Las 5 respuestas originales de OpenAI siguen guardadas en tu teléfono.
- **Qué nos dice:**
  - de qué minutos salieron las muestras de voz;
  - qué letra devolvió cada bloque;
  - qué era P3;
  - si los bloques 3 a 5 devolvieron poco texto.
- **Qué pide:** conectar el teléfono al PC por USB una vez. Si no se puede, seguimos igual.

### Etapa 1: arreglos de raíz en la transcripción (en orden de construcción)

1. **Chao personas fantasma (S)**
   - **Para ti:** desaparecen «Persona 4» y las líneas vacías, también en las transcripciones que ya tienes.
   - **Verificación:** prueba automática con un trozo vacío. En esta grabación deben quedar como máximo 3 etiquetas y ninguna línea vacía.

2. **Nombres internos que no se confunden (S, invisible, es la base del punto 3)**
   - **Para ti:** si el modelo no reconoce una voz, aparece como persona nueva (que podrás unir con otra) en vez de mezclarse en silencio con Persona 1. El error se ve en lugar de esconderse.
   - **Verificación:** pruebas automáticas, incluida una donde OpenAI devuelve «A» para una voz desconocida. Las transcripciones antiguas se siguen abriendo igual.

3. **«Mi voz»: tu muestra en todos los bloques, también el primero (M). Es lo más importante.**
   - **Para ti:** una sola vez, en Ajustes, grabas 10 s leyendo un texto. Desde ahí tus intervenciones salen directo como «Konrad» en todos los bloques. El modelo tiene tu voz como referencia desde el segundo 0, que es justo donde hoy falló. Como tú estás en casi todas tus grabaciones, esto ataca directamente lo que viste.
   - **Honestidad:** OpenAI no garantiza el acierto. Esto reduce el error, no lo elimina.
   - **Privacidad:** la muestra queda en tu teléfono y viaja a OpenAI solo junto con los audios que transcribes.
   - **Verificación:** una prueba automática revisa que el envío del bloque 1 lleve tu muestra. En la prueba real, «Recortar una copia» de 00:00 a 05:00 de esta grabación y transcribirla dos veces, sin y con «Mi voz». Comparamos escuchando 3 puntos. El modelo no siempre responde igual, así que esto orienta pero no demuestra; por eso también está la prueba nueva de la sección 3.

4. **Muestras automáticas más limpias y a la vista (S–M)**
   - **Para ti:** la muestra de la Fran (u otras personas) que la app saca del bloque 1 se elige de un trozo donde habla una sola persona, sin interrupciones. Cuando son 2 personas, se toman 2 muestras de cada una. La bitácora dice de dónde salió cada muestra («Persona 2: 03:53–04:02»), para que la escuches si algo te parece raro.
   - **Verificación:** pruebas automáticas con conversaciones inventadas (los trozos que se pisan deben descartarse). En la prueba real, escuchas las muestras que indique la bitácora.

5. **Menos cortes: bloques parejos de hasta ~15 min cuando se separan voces (M)**
   - **Para ti:** 26 min pasa a ser 2 bloques de 13 min (1 unión en vez de 4), así hay menos oportunidades de que alguien cambie de etiqueta.
   - **Costo:** el primer bloque tarda más (hoy tarda 1:36 con 5 min) y el total puede subir un poco.
   - **Límite:** no arregla errores dentro de un bloque, como el del minuto 1 o 2.
   - **Verificación:** pruebas automáticas de dónde se corta (26 min da 2 bloques, 60 min da 4). Prueba real con la pantalla bloqueada: la versión 0.4.4 limita cada turno en segundo plano a 6 min y hay que confirmar que no choque.

6. **Textos honestos y bitácora por bloque (S)**
   - **Para ti:** se quita el aviso «las etiquetas son independientes entre bloques», que ya no es cierto. Pasa a decir «Voces separadas automáticamente: pueden tener errores». La bitácora muestra por bloque «reconoció 2 · 1 nueva».
   - **Verificación:** revisar el .txt y la bitácora en la prueba real.

### Etapa 2: herramientas para corregir a mano
Son la red de seguridad, porque ningún modelo acierta siempre, y sirven con cualquier proveedor.

1. **Base: colores y números fijos, más «Deshacer» (S–M)**
   - **Para ti:** al corregir, nadie cambia de número ni de color. Cada cambio se puede deshacer con un aviso abajo que dura 5 s, y existe «Restaurar voces originales».
   - **Verificación:** pruebas automáticas de guardar y deshacer; revisión visual en tema claro y oscuro según CRITERIOS.

2. **«¿Quién habla aquí?» (M)**
   - **Para ti:** tocas el nombre de color de una intervención y se abre una hoja con «Escuchar este trozo» y la lista de personas. Eliges quién es.
   - **Verificación:** en el emulador, corregir las líneas de 00:10 a 00:36 de esta grabación.

3. **«Intercambiar desde aquí» (S)**
   - **Para ti:** «Intercambiar Persona 1 y Fran desde aquí», hasta el final o hasta el fin de ese bloque. Un toque arregla un cruce como el tuyo. Se agrega una rayita discreta «Bloque 2 · desde 05:01».
   - **Verificación:** prueba automática (intercambiar dos veces deja todo como estaba) y prueba en esta grabación.

4. **Unir personas (S)**
   - **Para ti:** dos voces con el mismo nombre se unen de verdad: un solo chip, un solo color, y sus intervenciones seguidas se agrupan. Tocar un chip abre la ficha de esa persona:
     - escuchar una muestra;
     - cambiar el nombre;
     - «Es la misma persona que…»;
     - ver solo sus intervenciones.
   - **Verificación:** en esta grabación, P2 y P3 deben quedar en 1 chip.

5. **Escuchar sin perder el lugar (M)**
   - **Para ti:** tocar la hora reproduce sin saltar al inicio de la pantalla, y se resalta la intervención que está sonando. Así revisas escuchando y corriges donde el nombre no calza con la voz.
   - **Verificación:** prueba en el emulador.

### Etapa 3: opcionales y experimentos
1. **Voces conocidas: la Fran y otras (L)**
   - Guardas la voz de alguien desde un trozo ya corregido y, al transcribir, eliges «¿Quiénes hablan?» (máximo 4).
   - Con las dos voces guardadas, en una charla tuya con la Fran cada intervención sale con nombre y no hay que unir bloques.
   - Verificación: prueba real.
2. **Volver a transcribir (M)**
   - Opciones: con «Mi voz», separando voces otra vez, o solo texto. La versión anterior se guarda.
   - Se cobra de nuevo el audio completo.
3. **«¿Cuántas personas hablan?» (S)**
   - Solo sirve para avisarte («Dijiste 2 y aparecen 3: Revisar voces»). OpenAI no acepta ese dato, así que no mejora la separación.
4. **Experimento: otra forma de dividir el audio en OpenAI (S más el costo de las pruebas)**
   - Probar la opción `server_vad` en lugar de `auto`.
   - No hay evidencia de que ayude. Solo lo comparamos con la misma copia de 5 min.
5. **Limpieza de respaldos (S)**
   - Borrar las respuestas originales cuando termina una transcripción (privacidad y espacio).
   - Solo después del paso 0.
6. **Plan B por el retiro del modelo (feb. 2027)**
   - Vigilar si el reemplazo de OpenAI agrega separación de voces, o evaluar otro proveedor.

**No recomendamos:**
- Enviar todo en un solo envío: no se puede por el límite de ~23 min.
- Adivinar quién habla por palabras como «segura» o «seguro»: no es confiable.
- Dividir una frase entre dos personas: mucho trabajo y poco valor por ahora.

---

## 3) Qué necesitamos de ti

1. **Hoy, 1 minuto:** en la grabación, toca 02:58 («Fueron dos horas») y 03:53 («estoy terminando mi CV») y dinos quién habla en cada una. Si puedes, también 00:17 y 00:19. Con eso sabemos si el error fue solo el primer minuto.
2. **Una pregunta:** después del minuto 12, ¿siguieron conversando o quedó en silencio o con la tele?
3. **No borres esa grabación.** La usaremos para la prueba con la copia de 5 min.
4. **Opcional:** conectar el teléfono al PC por USB una vez (paso 0).
5. **Cuando esté lista la Etapa 1:**
   - graba tu «Mi voz» (10 s) y una conversación nueva de 10 a 15 min con la Fran;
   - escucha 5 puntos que te indicaremos y marca bien o mal.
   - **Éxito:** 2 personas (o 3 que se unen poniéndoles el mismo nombre), tú siempre con tu nombre, y como máximo 1 error en 5 puntos.

---

## 4) Apéndice técnico (para quien lo implemente)

Rutas relativas a `app/src/main/java/cl/vozlocal/app/`.

**Hechos de la API que usamos**
- **Confirmados (referencia oficial):**
  - `known_speaker_names[]` son identificadores cortos, máximo 4.
  - Cada referencia dura de 2 a 10 s (el servidor acepta de 1.2 a 10.0) y va como data URL.
  - `prompt`, `include` y `logprobs` no están soportados con este modelo, y no existe ningún parámetro de número de personas.
  - `chunking_strategy` es obligatorio para audios de más de 30 s.
- **Confirmado solo por el error real del servidor:** el límite de 1400 s por envío.
- **No confirmado:**
  - qué etiqueta recibe una voz no reconocida cuando se envían nombres (se supone «A», «B»…);
  - si las etiquetas se mantienen dentro de un mismo envío largo;
  - si el modelo respeta `temperature`;
  - si se aceptan dos referencias con el mismo nombre. Por eso usamos nombres distintos que apuntan a la misma etiqueta.

**Paso 0**
- Comando: `adb exec-out run-as cl.vozlocal.app cat files/recordings/3ff13111-a219-4957-b9fb-ff3aaa1d5075.partN.json` (N = 0..4) y `.transcript.json`.
- `part0`: recalcular qué trozos eligió `references()` (el de d ≥ 2.5 más cercano a 6 s por etiqueta, `AudioParts.java:125-126`) para que el usuario los escuche.
- `part1..4`: revisar `_known`, las letras crudas, cuántos trozos vacíos hay y cuánto texto devolvieron los bloques 3 a 5.
- Hacerlo antes de cualquier limpieza de respaldos.

**E1.1 Fantasmas**
- `Transcript.fromParts` (L51-57): omitir trozos con `text.trim().isEmpty()`.
- Si `speaker` es null y hay texto: usar el id del trozo anterior del mismo bloque; si no hay, `block<p>:unknown`.
- Transcripciones existentes: filtrar en `segments()`/`load()`.
- `AudioParts.references` (L125): no contar trozos vacíos en `talk` ni como candidatos.
- Ojo: los números «Persona N» de voces sin nombre pueden correrse; E2.1 los congela.

**E1.2 Nombres y mapa**
- Las referencias pasan a ser `{name, target, dataUrl, start, end}`. Nombres enviados: `voz_1`, `voz_1b`, `voz_2`, `voz_propia`, nunca una mayúscula sola.
- `OpenAiClient.java:36` envía `name`.
- `Transcriber.java:182`: `_known` pasa a ser un objeto `{name: target}`, donde target es `"block0:A"` o `"voice:me"`.
- `fromParts` (L49, L55):
  - si `_known` es un objeto: `id = map.has(sp) ? map.getString(sp) : (single ? sp : "block"+p+":"+sp)`;
  - se aplica también con p=0 y con un solo bloque;
  - rama heredada: `_known` como arreglo mantiene la lógica actual.
- Tests: actualizar `androidTest/.../FeatureChecks.java:84-91` y agregar:
  - letra «A» desconocida en el bloque 2 queda como `block1:A`;
  - checkpoint antiguo con arreglo;
  - mapa con un solo bloque.

**E1.3 «Mi voz»**
- Fila en `SettingsActivity`: grabar 10 s con el grabador existente, guardar en `files/voices/me.m4a`, más el nombre de la persona.
- `Transcriber.process` (L120-128):
  - el bloque 1 se envía con `mine` en vez de `null` (L123);
  - el caso de un solo bloque también lleva `mine`;
  - los bloques 2..N llevan `mine` + las referencias automáticas, sin incluir la etiqueta del bloque 1 que ya fue reconocida como `voice:me`, con un máximo de 4.
- Prellenar `names["voice:me"]`.
- Agregar un hash de la muestra al `profile` (L101) para no mezclar checkpoints hechos con otra muestra.
- El fallback que reenvía sin referencias (L176-178) se mantiene y queda en la bitácora.

**E1.4 `AudioParts.references` (L122-136)**
- Candidatos: con texto, al menos 3 palabras, d ≥ 2.5 s.
- Descartar un candidato si algún trozo de otra persona cumple `start < s.end+0.5 && end > s.start-0.5`.
- Preferir candidatos cuyos vecinos sean de la misma persona o con una pausa de más de 1 s.
- Recortar 0.4 s en cada lado y limitar a 9.5 s.
- Exigir que esa persona hable 10 s o más en el bloque.
- Con 2 personas o menos: 2 muestras sin solape por persona.
- Registrar los rangos en la bitácora y en `data.blocks[0].refs`.

**E1.5 Bloques**
- Con voces: `target = total / ceil(total / 15 min)` (26:01 da 13:00). Revisar `SINGLE_MAX_MS` (`AudioParts.java:22`, hoy 12 min).
- El `profile` incluye el target, así que cambiarlo invalida los checkpoints en curso.
- Revisar:
  - `responseLimit` (`Transcriber.java:33`): con 13 min da 14 min;
  - `readTimeoutMs` (L162): con 13 min da 15 min;
  - `JOB_BUDGET_MS` = 6 min (L24, `PipelineJob:25`): que un bloque 1 de 13 min con la pantalla bloqueada no entre en un ciclo de reintentos.

**E1.6 Textos**
- `Transcript.text` (L36-38): el encabezado.
- `RecordingActivity.java:231`: la nota en pantalla.
- Conteo por bloque en `Transcriber.block`: letras que están en el mapa contra letras nuevas.

**E2 Modelo de datos**
- Campos:
  - `segments[i].orig` (se guarda solo en la primera edición);
  - `data.order[]` y `data.labels{}` congelados;
  - `data.blocks[{from, to}]`; para transcripciones antiguas se sacan de `state.cuts`.
- Operaciones:
  - reasignar un trozo;
  - intercambiar A y B en `[t, fin del alcance)`;
  - unir X en Y: reescribir los ids y borrar `names[X]`;
  - restaurar desde `orig`.
- Persistencia con el mismo patrón que `Transcript.rename` (L24-31): lock, cargar lo último, modificar, guardar y llamar a `Pipeline.edited`.
- UI, `RecordingActivity.showTranscript` (L218-245):
  - nombre tocable de 48 dp;
  - agrupar por nombre mostrado (L237 hoy compara ids);
  - color por el id resultante tras unir;
  - los chips (L227) abren la ficha de la persona;
  - `editSpeakers` (L293-304) une las voces con el mismo nombre al guardar.
- El aviso con «Deshacer» necesita los roles `inverseSurface`/`inverseOnSurface` en `AppTheme`.
- Reproducción por rangos con el MediaPlayer existente, y `seekTo` sin volver al inicio de la pantalla.

**E3 Notas**
- Volver a transcribir exige borrar `<id>.partN.json` o agregar un id de intento al `profile` (L101-104). Si no, se reutilizan las respuestas guardadas y sale el mismo resultado.
- Guardar la versión anterior en `transcript.prev.json`.
- Con `server_vad` hay un reporte de error «chunking_strategy is required» con audios de más de 30 s; probarlo antes de usarlo.

Archivos de evidencia: `C:/Users/Konra/Desktop/2026-09-28 Prueba con la Fran.txt` y `C:/Users/Konra/Desktop/support (1).txt` (bitácora «Grabación 3ff13111», líneas 12-35 y 1287-1324).
