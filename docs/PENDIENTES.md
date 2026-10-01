# Pendientes para la próxima versión

> Lista de mejoras acordadas con el usuario. Al implementar una, muévela al CHANGELOG y, si es una decisión de diseño, regístrala en `diseno/CRITERIOS.md`.

## Diagnóstico 2026-10-01: audio de 4 min que tardó 1:15 (informe del usuario, 0.8.0 adelanto, vivo V2318, Android 16)
Grabación df1c8d5d, solo texto, MAI-Transcribe 2. El trabajo real fue de 1 minuto. El resto se fue en esperas:
1. **27 min esperando Wi-Fi** (10:12 → 10:40). El usuario estaba con datos móviles y «Solo con Wi-Fi» viene activado por defecto. La bitácora lo decía, pero no se vio.
2. **45 min con la tarea pausada por Android** (10:42 → 11:27). La app estaba cerrada, así que no se pudo pasar a primer plano (`ForegroundServiceStartNotAllowedException`, Android 12+) y corrió como tarea de fondo.
   - La conversión a FLAC tardó **150 s** en segundo plano (31–43 s en primer plano).
   - Al subir, el teléfono cortó la red: «Software caused connection abort».
   - JobScheduler la detuvo con STOP_REASON 4 (DEVICE_STATE) y no volvió hasta que el usuario abrió la app.
   - El informe dice «Optimización de batería: activada». La excepción de batería que el usuario había dado se perdió: la bienvenida aparece en el informe, así que probablemente hubo una instalación nueva.
3. **La conversión a FLAC es lenta.**
   - Una parte de 9 min tardó 292 s en primer plano.
   - La parte 2 tardó 912 s, pero con 10 min de app congelada.
   - Hay que bajarla al menos 5 veces: float en vez de double, menos coeficientes (para voz bastan ~12–16 por lado), decimación entera 48k→16k y una comprobación del FLAC más barata (o ninguna). El porcentaje de «Preparando audio» debe verse.

Buenas noticias del mismo informe:
- Las **anclas funcionan con audio real**: «reconoció 1 de 1 voz conocida» en la parte 1 y «2 de 2» en la parte 2 de una reunión de 18 min.
- Costo real: transcribir 18 min costó US$0,031, y la nota con Claude Sonnet, US$0,042. **La nota cuesta más que la transcripción.**

Qué hacer (ronda 3 de la 0.8):
- **Esperando Wi-Fi:** en el detalle, un botón grande «Usar datos móviles ahora (≈X MB)» y una notificación con esa acción. Evaluar un tope por defecto: permitir datos móviles para envíos de menos de ~10 MB.
- **Transferencia iniciada por el usuario** (Android 14+): `JobInfo.Builder.setUserInitiated(true)` con el permiso `RUN_USER_INITIATED_JOBS`. Google la recomienda para transferencias que el usuario pidió, y no sufre las cuotas de las tareas de fondo. Debe programarse con la app visible, al tocar «Transcribir».
- **La bienvenida pide «Transcribir con el teléfono bloqueado»** (permiso de batería, con la guía de vivo). Hoy una instalación nueva queda optimizada.
- **Conversión más rápida** (ver el punto 3) y avance visible.

## Para la 0.9: métricas de uso en Ajustes (pedido 2026-09-30)
> «En Ajustes, alguna sección con métricas de uso, de conversión, de tokens usados y cuánto equivale en USD… un poco más de métricas en general, eso se ve bonito. El público objetivo es alguien que habla mucho y quiere cargar información transcrita en su segundo cerebro.»

Todo se calcula en el teléfono con lo que la app ya guarda, sin enviar nada:
- **Datos por grabación** (`.sync.json`): `audioMs`, `costUsd` (real, OpenRouter), `inTokens`/`outTokens`/`usageSec`, `provider`/`model`, `bytesSent`, `inboxAt`.
- **Transcripciones**: palabras y personas.
- **Notas** (`.note.json`): modelo y, si se guarda, su uso.
- **Momentos ★.**

Ideas de contenido:
- **Tu voz en números:**
  - horas grabadas y palabras transcritas (semana, mes, total);
  - ritmo al hablar (palabras por minuto);
  - «tiempo ahorrado» frente a escribir a mano (≈40 palabras por minuto al teclear);
  - racha de días grabando.
- **Embudo hacia tu segundo cerebro:** grabadas → transcritas → con nota → guardadas en 0-Inbox, con el % de cada paso y lo que quedó a medio camino (con acceso directo a esas grabaciones).
- **Costos:**
  - US$ del mes y del total;
  - costo por hora de audio;
  - desglose por proveedor y modelo;
  - tokens de las notas;
  - real (OpenRouter informa el costo) frente a estimado (tarifas públicas de OpenAI), siempre rotulado.
  - Con OpenRouter, además el saldo de la clave.
- **Gráficos:**
  - barras por semana (minutos grabados y US$);
  - mapa de días y horas en que más grabas;
  - personas con las que más conversas (voces conocidas).
- **Diseño:** una pantalla propia («Tus métricas»), a la que se entra desde una tarjeta resumen en Ajustes, con la estética Verbapp (tarjetas de vidrio, cifras grandes en Outfit, gráficos en verde). Cargar la guía `dataviz` antes de dibujar gráficos.

## Abierto tras integrar la 0.8.0 (OpenRouter y bienvenida)
Nada de la 0.8.0 se probó contra OpenRouter real (no había clave) ni con audio real. Lo primero es la lista «qué probar con tu clave» de cada parte. Además quedó anotado:
- **Etapa «Preparando audio» con OpenRouter.** La conversión a FLAC de cada bloque ocurre antes del envío y puede tardar decenas de segundos en un teléfono; mientras tanto la pantalla dice «Enviando…» en 0 %. Falta una etapa propia y contar ese tiempo en `Transcriber.sendEstimate` (tarea de fondo).
- **Si un proveedor rechaza el FLAC.** `OrAudio.build(…, false)` sabe armar WAV, pero el cliente no lo usa como respaldo ante un error de formato: hoy solo cae a WAV cuando el teléfono no puede codificar FLAC.
- **Tres o más personas con MAI-Transcribe 2.** No se envía un máximo de hablantes; si con la clave real salen solo 2 voces, hay que agregarlo en `OpenRouterClient.body`.
- **Tope del envío en JSON.** No está documentado; la única defensa es el 413, que baja los bloques a la mitad una vez (`orHalf`).
- **`HTTP-Referer`.** Es la página pública del repositorio (`OpenRouterClient.REFERER`), la misma para transcribir, para la nota y para comprobar la clave. Confirmar que es la dirección que se quiere mostrar en OpenRouter.
- **Clave de OpenAI comprobada en la bienvenida.** Queda como comprobada en Ajustes solo si el modelo que se usaría hoy es el de voces (lo normal en una instalación nueva); si no, Ajustes sigue pidiendo «Confirma que tu clave funciona».
- **Saldo en «Comprobar conexión».** `/credits` puede exigir otra clase de clave: si OpenRouter no lo entrega, solo se ve «Clave válida».
- **Pruebas instrumentadas.** Los empalmes entre partes (encabezados y nombre de servicio únicos, `Models.checkKey`, costo real, hoja del modelo de la nota, comprobación de la bienvenida en Ajustes) solo se compilaron: hay que volver a correr `ModelsChecks`, `NotesChecks`, `OnboardingChecks` y `OpenRouterChecks` en el emulador, y mirar a la vista la hoja «Modelo de la nota» con OpenRouter («Otro modelo de OpenRouter…»).

## Hechas en la 0.4.3
- ✅ Confirmar antes de cancelar una transcripción.
- ✅ Fecha delante del nombre (`2026-09-27 Nombre`), activada por defecto, con opción para aplicarla a las grabaciones existentes.
- ✅ "OpenAI está transcribiendo" en la bitácora.
- ✅ Guardado rápido en una carpeta fija (p. ej. Drive/0-Inbox).

## Detalle de lo pedido (2026-09-23, ya implementado)

### 1. Confirmar antes de cancelar una transcripción
- **Problema:** en el detalle de una grabación en proceso, "Cancelar transcripción" queda justo encima de "Ver detalles del proceso". Da miedo tocarlo sin querer y perder el trabajo.
- **Qué hacer:**
  - Mostrar una hoja de confirmación: "¿Cancelar la transcripción?", con el botón destructivo "Cancelar transcripción" y el botón "Seguir transcribiendo".
  - Explicar en la hoja que los bloques ya listos se conservan si vuelves a transcribir con la misma opción de voces (hoy los puntos de control por bloque se mantienen).
  - Dar al botón el estilo de acción destructiva (texto en color `error`) y separarlo más de "Ver detalles del proceso", para que no parezcan acciones del mismo tipo.
- **Principio:** toda acción destructiva o que pierde trabajo pide confirmación y no queda pegada a una acción frecuente.

### 2. Agregar la fecha al nombre de cada grabación
- **Qué pidió:** una opción en Ajustes que, al activarla, agregue siempre la fecha delante de cualquier nombre, lo escriba o no el usuario.
- **Formato:** `2026-09-23 Nombre` (fecha ISO año-mes-día y un espacio). Así los archivos se ordenan solos por fecha en cualquier carpeta.
- **Detalles a definir al implementarlo:**
  - La fecha es la **de la grabación** (cuando se grabó o se importó), no la del día en que se renombra.
  - Se aplica al nombre escrito durante la grabación, en la hoja "Grabación guardada", al importar y al renombrar.
  - Si el nombre ya empieza con una fecha en ese formato, no se duplica.
  - Nombre por defecto sin título: `2026-09-23 Grabación 16:00`.
  - Los nombres de archivos exportados (.txt, carpeta de copias) heredan el nombre, así que también quedarán ordenados.
  - Preguntar al usuario si quiere aplicarlo a las grabaciones que ya existen, por ejemplo con un botón "Aplicar a todas" en Ajustes.
- **Ubicación en Ajustes:** sección "Grabación", interruptor "Agregar la fecha al nombre" con el ejemplo `2026-09-23 Reunión` como texto de apoyo.

## Hechas en la 0.6.0
- ✅ Volver a transcribir, con alternativas que cambian algo; la versión anterior se guarda.
- ✅ Novedades de cada versión: hoja al actualizar, más el historial en Ajustes.
- ✅ Nota para tu segundo cerebro (OpenAI o Claude), .md en 0-Inbox; «Actualizar» reemplaza el mismo archivo.
- ✅ ★ Marcar momentos, también desde la notificación.
- ✅ Propuesta de interfaz PRO (`diseno/PROPUESTA-0.6.md`):
  - botón que avanza;
  - Grabar viva;
  - avance a la vista;
  - detalle como documento con onda;
  - Biblioteca de 3 líneas;
  - nombrar voces en una pasada;
  - Ajustes reordenados;
  - íconos, interruptores, vibraciones y movimiento.
- ✅ Importar: salir ya no cancela.
- ✅ Biblioteca de voces conocidas: varias voces guardadas, para editarlas o eliminarlas (pedido del 2026-09-29).

## Por validar en el teléfono (0.6.0)
- Guardar en Drive/0-Inbox y después «Actualizar»: debe reemplazar el mismo archivo, sin crear «(1)».
- La nota con OpenAI (gpt-6-luna) y con Claude (claude-sonnet-5-5), con una clave real.
- ★ desde la notificación con el teléfono bloqueado (vivo).
- La onda de un audio de 60 min: cuánto tarda en el teléfono.

## Hechas en la 0.5.0
- ✅ Etapas 1 y 2 de `PLAN-voces.md`: «Mi voz», muestras limpias con nombres únicos, bloques de hasta 12 min, sin fantasmas, «¿Quién habla aquí?», intercambiar desde aquí, unir personas, Deshacer, restaurar y escuchar sin perder el lugar.

## Por validar con el usuario (0.5.0)
- Grabar «Mi voz» y hacer una conversación nueva de 10 a 15 min con la Fran. Escuchar 5 puntos y marcar si están bien o mal. **Éxito:** 2 personas (o 3 que se unen con el mismo nombre), el usuario siempre con su nombre y como máximo 1 error en 5 puntos.
- Recortar una copia de 00:00–05:00 de «Prueba con la Fran» y transcribirla con «Mi voz» para comparar con el resultado anterior.

## Para la próxima versión (pedido 2026-09-29)

### Volver a transcribir un audio
- **Qué pidió:** al entrar a un audio ya transcrito, poder transcribirlo otra vez, por ejemplo si el resultado no gustó o para hacer una segunda pasada.
- **Dónde:** en el detalle del audio, en el menú ⋮ → «Volver a transcribir…». También puede ir un botón discreto al final de la transcripción. Hoy la app lo bloquea con el mensaje «Ya está transcrito… recorta una copia».
- **La hoja «¿Cómo quieres volver a transcribir?»:**
  - Opciones:
    - «Separando voces», con «Mi voz» si está grabada;
    - «Solo el texto»;
    - «Con otro modelo», si aplica.
  - Mostrar el costo estimado: «Se cobra de nuevo el audio completo (≈ US$0,xx)».
  - Si hay correcciones de voces o nombres, avisar: «Tus correcciones no pasan a la nueva versión».
- **La versión anterior no se pierde:**
  - se guarda como `transcript.prev.json`;
  - al terminar, permitir «Volver a la versión anterior»;
  - de preferencia, también comparar las dos.
- **Detalle técnico:** las respuestas guardadas por bloque (`<id>.partN.json`) y los cortes (`cuts`) se reutilizan si el perfil no cambia. Con la misma opción saldría el mismo resultado sin llamar a OpenAI. Hay que borrarlos o agregar un id de intento al `profile` en `Transcriber.process`.
- **Relacionado:** ofrecer «Grabar mi voz» antes de repetir, si todavía no está grabada.

#### Que la segunda pasada salga distinta, y mejor (pedido 2026-09-29)
Repetir exactamente lo mismo no sirve: el modelo varía un poco solo, pero no de forma confiable. La segunda pasada debe **cambiar algo que importe**. Opciones de la hoja, de la más recomendada a la menos:

1. **«Segunda pasada con tus correcciones»** (recomendada si ya corregiste o nombraste voces).
   - Se toman muestras limpias de cada persona a partir de la transcripción corregida: tramos que el usuario confirmó (con nombre o corregidos) y sin otra voz encima.
   - Se envían como voces conocidas (máx. 4, incluida «Mi voz») a **todos** los bloques, también el primero.
   - Así el modelo sabe desde el segundo 0 quién es la Fran y quién eres tú, y los nombres salen puestos.
   - Es la alternativa con más probabilidad de mejorar de verdad.
2. **«Separar voces sin cortar el audio»** (audios de hasta ~23 min).
   - Un solo envío en vez de bloques: no hay uniones donde las voces se crucen.
   - Es más lento. Arriba de 23 min no se puede (límite de 1400 s del modelo).
3. **«Con Mi voz»**, si la primera vez no estaba grabada.
4. **«Solo el texto con otro modelo»** (gpt-4o-transcribe o GPT Transcribe).
   - Sirve cuando lo que falló fueron las palabras y no las voces.
   - Pierde la separación de voces.
5. **«Idioma: detección automática»**, para audios con mezcla de idiomas.

Al terminar:
- mostrar **«Nueva versión lista»** con «Quedarme con la nueva» / «Volver a la anterior»;
- si se puede, una vista simple que marque las intervenciones cuya persona cambió entre las dos versiones.

Para cada opción:
- mostrar el costo estimado;
- registrar en la bitácora qué alternativa se usó (para aprender cuál funciona mejor).

### Nota lista para tu segundo cerebro (elegida 2026-09-29)
- **Qué hace:** al terminar cada transcripción, la app arma una nota en Markdown compatible con Obsidian. La nota lleva:
  - resumen (5 líneas);
  - decisiones;
  - tareas (qué, quién, para cuándo);
  - frases clave con su hora;
  - etiquetas;
  - al final, la transcripción completa.
- **Dónde queda:** se guarda sola en la carpeta de guardado rápido (0-Inbox) o con un toque, según una opción en Ajustes («Guardar la nota automáticamente»).
- **Formato:** frontmatter con fecha, duración, personas y enlace al audio si hay carpeta de copias. Nombre `2026-09-29 Título.md`.
- **Con qué IA:**
  - por defecto, con la clave de OpenAI que ya existe (sin configurar nada nuevo);
  - opcional, Claude (mejor redacción y matiz en español chileno; requiere una clave de Anthropic).
  - Mostrar el costo estimado, que es bajo porque es solo texto.
- **En pantalla:** una tarjeta «Resumen» arriba de la transcripción, con tareas marcables y el botón «Guardar nota».
- **Lo que no hace:** consultas tipo chat sobre todos los audios. El usuario ya las hace con otra IA sobre su segundo cerebro.

### Marcar momentos mientras grabas (elegida 2026-09-29)
- **Botón ★:** mientras se graba, deja una marca con la hora exacta (con vibración corta como confirmación). También funciona desde la notificación de grabación, con el teléfono bloqueado.
- **Después:**
  - las marcas aparecen en el reproductor (puntos en la barra) y en la transcripción (intervención resaltada);
  - un toque salta a cada momento;
  - la nota del segundo cerebro las destaca en «Momentos marcados».
- **Opcional:** escribir o dictar una palabra para la marca («precio», «idea»).

### Novedades de cada versión (pedido 2026-09-29)
- **Al abrir la app después de actualizar,** aparece una hoja «Novedades de la 0.x.x»:
  - 3 a 5 puntos en lenguaje simple (qué cambió para el usuario, no detalles técnicos);
  - botones «Entendido» y «Ver todas las versiones».
  - Se muestra una sola vez por versión: se compara `versionCode` con el último visto.
  - No se muestra en la primera instalación, donde ya está la bienvenida.
- **En Ajustes → Ayuda y soporte,** una fila «Novedades y versiones» con la versión actual como valor. Abre una pantalla con el historial:
  - la más nueva arriba;
  - cada versión con su fecha y sus puntos, plegable.
- **Fuente:** un archivo propio de novedades para el usuario (p. ej. `assets/novedades.json`), escrito en español simple. Es distinto del `CHANGELOG.md` técnico.
- **Proceso:** agregar la entrada de novedades pasa a ser un paso obligatorio de cada versión, en el checklist de `diseno/CRITERIOS.md`.
- **Opcional:** una etiqueta «Nuevo» junto a las funciones recién agregadas (p. ej. «Mi voz»), que desaparece tras usarlas una vez.

## Etapa 3 de `PLAN-voces.md` (opcional, sin fecha)
- Voces conocidas de otras personas (p. ej. la Fran) y elegir «¿Quiénes hablan?» al transcribir (máx. 4).
- ~~Volver a transcribir~~ → pasó a «Para la próxima versión».
- «¿Cuántas personas hablan?», solo para avisar si aparecen más de las esperadas.
- Plan B por el retiro de gpt-4o-transcribe-diarize (26-02-2027).

## Hechas en la 0.4.4
- ✅ Descartar grabaciones de menos de 3 s (sin "Deshacer" por ahora: el aviso solo informa).
- ✅ Permiso de batería en la tarjeta de proceso y en Ajustes, con la ruta por marca (vivo, Xiaomi, Samsung, Huawei, OPPO).
- ✅ Vigilante de conexión, los cortes del teléfono no gastan intentos, reintento al encender la pantalla y Wi-Fi despierto.
- ✅ Informe: "reintentos (cortes del teléfono)" y eventos con pantalla/reposo/batería.

## Pendiente de revisar
- Importar un audio largo y tocar "Volver" durante la conversión: en la 0.4.2 cancelaba la importación (`InterruptedIOException`). Verificar si sigue pasando.
- "Deshacer" en el aviso de grabación corta, si alguna vez se descarta algo intencional.

## Detalle del pedido (2026-09-28, ya implementado)

### Descartar grabaciones accidentales de menos de 3 segundos
- **Problema:** si se escapa el dedo, queda guardada una grabación de 1–2 s que no sirve y ensucia Inicio y Biblioteca.
- **Qué hacer:**
  - Al detener, si la grabación dura menos de 3 s, no guardarla: borrar el archivo y avisar con un toast "Grabación muy corta, no se guardó".
  - No pedir confirmación: es un error, no una decisión.
- **Detalles a definir al implementarlo:**
  - Contar solo el tiempo grabado, sin las pausas.
  - Ofrecer "Deshacer" en el aviso durante unos segundos, por si era intencional.
  - Las importaciones no se filtran, solo las grabaciones hechas en la app.
  - Actualizar `RecorderSmokeTest`, que hoy graba pocos segundos, para que su grabación dure más de 3 s.

## Propuestas pendientes de confirmar
- Agregar una sección **"Lecciones"** en `diseno/CRITERIOS.md` con los aprendizajes de las iteraciones 0.3 → 0.4.2.
- Publicar las versiones en **GitHub Releases** (hoy la última publicada es la 0.2.0).

## Para medir con la primera transcripción larga real (0.4.2)
- Tiempo por bloque ("Bloque X de 10 listo · tardó Y") y tiempo total, para saber si el paralelo aceleró de verdad y si conviene cambiar el tamaño de los bloques o cuántos se envían a la vez.
- Si OpenAI aceptó las muestras de voz o se reenvió sin ellas (queda en la bitácora).
- "Buscando pausas" tardó 32 s en un audio de 51 min: evaluar acotar la búsqueda (menos margen alrededor de cada corte) para que sea más rápida.

## Ideas para después de la 0.4.3
- Guardar automáticamente en la carpeta de guardado rápido al terminar cada transcripción (sin tocar nada).
- Opción de formato Markdown (.md) para el guardado, compatible con un "segundo cerebro" (Obsidian).
- Ofrecer en Ajustes quitar la optimización de batería para Voz local (el informe ya muestra si está activa).
- Evaluar los trabajos iniciados por el usuario de Android 14+ ("user-initiated data transfer") para seguir con la app cerrada sin depender de la tarea de fondo.
