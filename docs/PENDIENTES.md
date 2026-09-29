# Pendientes para la próxima versión

> Lista de mejoras acordadas con el usuario. Al implementar una, muévela al CHANGELOG y, si es una decisión de diseño, regístrala en `diseno/CRITERIOS.md`.

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
