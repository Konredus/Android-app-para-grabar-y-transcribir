# Historial de versiones

## 0.7.0 — 2026-09-30 · «Verbapp: nueva cara, el mismo cerebro»

La app pasa a llamarse **Verbapp** («las palabras vuelan, lo escrito permanece»; lema: «Tus palabras, para siempre»). Esta versión **solo cambia la interfaz**: grabar, transcribir, voces, notas, 0-Inbox y todo lo demás funciona igual. Diseño en `docs/diseno/PROPUESTA-0.7.md`, basado en la referencia que eligió el usuario (`docs/diseno/referencias/`).

**Identidad nueva: «Bosque de vidrio»**
- **Verde de marca** (`#2F6B58`) con fondo en degradado de blanco a verde y una textura de puntitos arriba. Es **intenso** en Grabar y **suave** en listas y documentos.
- **Tarjetas de vidrio:** blanco translúcido con borde fino y esquinas de 24 dp.
- **Botón principal en tinta** (negro verdoso) y secundario en gris suave, como «Guardar / Cancelar» de la referencia.
- **Letra Outfit** (geométrica, licencia OFL) en títulos, botones, números y el cronómetro. El texto largo sigue en Roboto, porque se lee mejor. Los números que cambian usan cifras de ancho fijo.
- **Palabra destacada:** la última palabra de los títulos grandes va sobre un recuadro menta.
- **Ícono nuevo:** barras de onda blancas y un destello menta sobre verde. Las notificaciones salen en verde.
- **Barra de navegación flotante:** una cápsula de vidrio donde la pestaña activa se abre como píldora negra con su nombre.
- **Modo oscuro «bosque de noche»**, con los mismos degradados.
- Se retira «Colores de tu fondo de pantalla» (Material You): la identidad verde es parte de la app.

**Pantallas**
- **Grabar en reposo:**
  - saludo según la hora con tu nombre (de «Mi voz»);
  - micrófono verde con halo que respira entre ondas;
  - «Importar audio» como píldora;
  - «Última grabación» con su siguiente paso;
  - **«Tu semana»**: grabaciones, tiempo grabado y notas de los últimos 7 días.
  - El estado (clave, transcribiendo, error, por revisar) va en un botón redondo arriba y en una píldora bajo el saludo.
- **Grabando, a pantalla completa:**
  - título grande con la palabra destacada;
  - «● Grabando»;
  - **cronómetro gigante**;
  - onda blanca que nace en un cabezal, sobre el verde;
  - píldoras de vidrio [■ Detener] [❚❚ Pausa] y ★ con contador.
- **«Nombra esta grabación»:** hoja con **mini reproductor** para escuchar lo recién grabado antes de ponerle nombre, y los botones [Listo] · [Ver grabación].
- **Biblioteca, Detalle, Ajustes, Importar y todas las hojas** con la nueva estética:
  - en el Detalle, un reproductor flotante y una barra de cuánto habló cada persona;
  - en Ajustes, una tarjeta con la marca y el tema elegido con vistas previas;
  - un menú de acciones con accesos rápidos;
  - hojas con el fondo desenfocado (Android 12+).
- Pantallas de borde a borde: el degradado pasa por detrás de las barras del sistema.

**Para la próxima versión**
- Investigación verificada para transcribir y armar notas con **una sola key de OpenRouter**, con una lista de modelos que se actualiza sola: `docs/PLAN-openrouter.md`.

## 0.6.0 — 2026-09-29 · «Del botón rojo a tu segundo cerebro»

Rediseño de la experiencia basado en el uso real: 999 acciones registradas, una auditoría de cada pantalla y apps de referencia. Detalle en `docs/diseno/PROPUESTA-0.6.md`; contratos técnicos en `docs/diseno/SPEC-0.6.md`.

**Del audio a tu segundo cerebro**
- **Un botón principal que avanza** con cada grabación: Transcribir → Revisar voces → Guardar en 0-Inbox → ✓ En 0-Inbox · hh:mm. «Actualizar» reemplaza el **mismo archivo**, sin copias «(1)». Las demás salidas quedan en ▾: copiar, compartir, .txt, .md, otra carpeta y volver a transcribir.
- **Nota para tu segundo cerebro:** al terminar, una IA arma un resumen con decisiones, tareas (marcables), frases clave, etiquetas y momentos ★. Se guarda como .md para Obsidian, con frontmatter y la transcripción al final.
  - Puede armarla OpenAI (`gpt-6-luna`, con la misma clave) o Claude (`claude-sonnet-5-5`, con clave de Anthropic).
  - La nota nombra a las personas con marcas {S1} y pone los nombres actuales al mostrarse, así sigue siendo válida después de corregir voces.
  - Si la grabación tenía el título automático, la nota le pone uno.
- **★ Marcar momentos** mientras grabas, también desde la notificación con el teléfono bloqueado (con vibración). Después aparecen en la onda, en la transcripción y en la nota.
- **Volver a transcribir** con alternativas que cambian algo:
  - segunda pasada con tus correcciones, que usa las voces corregidas como muestras en todo el audio;
  - separar voces sin cortar (hasta 23 min);
  - separar voces de nuevo;
  - solo el texto.

  La versión anterior se guarda: al terminar eliges «Quedarme con la nueva» o «Volver a la anterior». Si se cancela o falla, vuelve sola.

**Pantallas rediseñadas**
- **Grabar:** onda grande, tiempo destacado y tres controles fijos (Pausa · Detener · ★ Marcar). Detener se ignora el primer segundo, para evitar grabaciones accidentales. La tarjeta «Última grabación» muestra su siguiente paso. La cabecera dice «✓ Listo para transcribir» o «Revisar · «título»».
- **Detalle como documento:**
  - arriba, la ficha con las personas y su %, la nota, los momentos ★ y la transcripción a todo el ancho, que sigue al audio;
  - abajo, fijos, la onda del reproductor (con el color de quién habla y las ★), los controles y el botón que avanza.
- **El avance a la vista:** «3 de 5 partes listas», tiempo estimado y la última línea de la bitácora como titular. La app recuerda si dejaste la bitácora abierta, y «Cancelar» pasa al menú ⋮.
- **Biblioteca que se lee sola:**
  - filas con el comienzo del texto, quién habla, la duración en palabras y «✓ En 0-Inbox» o «Por guardar»;
  - anillo de avance, «Reintentar» en la misma fila y un punto «nuevo»;
  - filtros Por guardar / En proceso / Sin transcribir / Con error.
- **Voces conocidas:** la biblioteca guarda tu voz y la de otras personas (p. ej. la Fran), grabadas en Ajustes o con «Guardar la voz de X» desde una transcripción. Hasta 4 se envían en cada audio, así cada persona aparece con su nombre desde el inicio. Se pueden escuchar, renombrar, volver a grabar, apagar o eliminar.
- **Nombrar voces en una sola pasada:** cada voz con ▶ para escucharla, su frase, su % y nombres sugeridos. Si dos llevan el mismo nombre, se unen.
- **Ajustes reordenados:**
  - «Tu flujo» primero: 0-Inbox, nota e IA de la nota, automático, fecha y voces;
  - «Comprobar conexión» muestra el resultado en la misma fila;
  - nueva sección **Novedades y versiones** (y una hoja con las novedades al actualizar).
- **Importar:** abre directo el selector, salir no cancela la importación, el título va sin extensión y la ayuda de WhatsApp está aquí.
- **Notificación «Transcripción lista»:** suena, abre la grabación y ofrece «Revisar voces» o «Guardar en 0-Inbox». La notificación de grabación trae cronómetro, Pausar/Reanudar, ★ Marcar y Detener.

**Terminaciones**
- Íconos Material Symbols con un solo estilo.
- Interruptores reales de Material 3.
- Botón de dos partes con estado de carga que se convierte en ✓.
- Vibraciones con significado.
- Movimiento enfatizado; se respeta «Quitar animaciones».
- Duraciones en palabras, sin jerga técnica a la vista.

**Correcciones**
- **Revisión independiente de toda la versión:** 27 problemas confirmados y corregidos. Los principales:
  - la pantalla Grabar hacía girar el procesador sin parar mientras se veía la Biblioteca (gastaba batería y alteraba la grabación);
  - volver a transcribir dos veces sin elegir versión podía borrar la original;
  - una nota podía quedar «Armando…» para siempre;
  - la opción «sin cortar» podía reenviarse (y cobrarse) varias veces desde la tarea de fondo;
  - un servidor propio sin dirección podía recibir la clave.
- **Privacidad del informe de soporte:** ya no incluye nombres de personas ni títulos de grabaciones. Se ocultan en las etiquetas de botones y en las bitácoras.
- La pantalla de detalle ya no se reconstruye con cambios de otras grabaciones y conserva la posición.
- La onda de audios largos se calcula midiendo ventanas: tarda lo mismo con 5 o 90 minutos.

## 0.5.0 — 2026-09-28

Voces que se confunden menos y que se corrigen con un toque. Parte de una prueba real: una conversación de 26 min entre 2 personas salió con 4 personas y con las etiquetas cruzadas en el primer minuto. El diagnóstico completo está en `docs/PLAN-voces.md`.

**Menos errores al separar voces**
- **Mi voz:** en Ajustes grabas 10 s leyendo un texto. Al separar voces, tu muestra va en todos los bloques, también el primero, así que apareces con tu nombre desde el segundo 0. Queda en el teléfono y viaja a OpenAI solo junto con los audios que transcribes.
- **Muestras de voz limpias:** se toman de tramos donde habla una sola persona, sin otra voz encima ni pegada, y solo de quien habla 10 s o más. Con 1 o 2 personas se toman 2 muestras de cada una. La bitácora dice de qué minutos salieron.
- **Nombres únicos para las muestras** (`voz_1`, `voz_propia`) en vez de las letras del modelo, que podían pegar una voz nueva a otra persona.
- **Bloques parejos de hasta 12 min** al separar voces (antes 5 min), para que haya menos uniones entre bloques. Un audio de 26 min va en 3 bloques en vez de 5.
- **Sin personas fantasma:** los tramos vacíos se descartan, también en las transcripciones anteriores.
- **Bitácora por bloque:** «reconoció 2 voces, 1 nueva».

**Corregir a mano**
- **«¿Quién habla aquí?»:** tocas el nombre de una intervención. Ahí puedes:
  - escuchar solo ese tramo;
  - elegir a la persona correcta u «Otra persona»;
  - corregir una sola frase.
- **«Intercambiar A y B desde aquí»**, hasta el final o solo en ese bloque: arregla un cruce con un toque.
- **Ficha de cada persona** (al tocar su chip):
  - escuchar una muestra;
  - cambiar el nombre;
  - «Es la misma persona que…»;
  - ver solo sus intervenciones.
- **Mismo nombre = misma persona:** al nombrar dos voces igual, se unen de verdad, con un solo chip y un solo color.
- **Deshacer** después de cada cambio (snackbar) y **Restaurar voces originales** en «Nombrar voces». Números y colores fijos al corregir.
- **Escuchar sin perder el lugar:** tocar la hora reproduce ahí mismo y resalta la intervención que suena. Un separador marca dónde empieza cada bloque.
- **.txt más legible:** junta en un párrafo los tramos seguidos de la misma persona. Cambia el aviso «etiquetas independientes entre bloques» por «Voces separadas automáticamente: pueden tener errores», que desaparece al revisar.

## 0.4.4 — 2026-09-28

Transcripción confiable con la pantalla bloqueada, a partir del informe de un vivo V2318 (audio de 2 min que tardó 1 h: el teléfono cortó la conexión tres veces y congeló la app hasta 36 min).

- **Permiso de batería:** en la tarjeta de la transcripción y en Ajustes ("Con la pantalla bloqueada") se ve si Android optimiza la batería de Voz local, con un botón que abre el permiso del sistema y la ruta exacta para vivo, Xiaomi, Samsung, Huawei y OPPO.
- **Vigilante de conexión:** si el envío deja de avanzar 90 s, o la respuesta tarda más que la duración del audio más 1 min (mín. 3, máx. 20), se corta y se reintenta. Mide con un reloj que sigue contando aunque Android congele la app, así que no queda colgado media hora.
- **Los cortes del teléfono no gastan intentos** ("Software caused connection abort" o el vigilante): se reintentan a los 15 s sin avanzar la escala de esperas (hasta 12 cortes).
- **Reintento inmediato al encender la pantalla** o desbloquear, en vez de esperar la cuenta completa.
- **Wi-Fi despierto** durante la transcripción (bloqueo de Wi-Fi, como las apps de música).
- **Grabaciones de menos de 3 s se descartan** (toque accidental): no se guardan ni se envían a transcribir; aparece un aviso breve.
- Bitácora: "El teléfono cortó la conexión…", "Android tuvo la app congelada X min" y "Pantalla encendida · se reintenta ahora". El registro técnico agrega pantalla, reposo profundo y estado de la batería en cada fallo.
- Informe de soporte: "reintentos (cortes del teléfono)" en vez de "intentos", que volvía a 0 al terminar.
- Nota: el permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` no está permitido en Google Play para esta categoría de app; Voz local se instala desde el APK.

## 0.4.3 — 2026-09-27

Corrige el caso de un audio de 32 min que tardó más de 7 h (OpenAI lo transcribió en 5 min; el resto se perdió en la preparación y en reintentos en segundo plano).

- **Sin recompresión:** los bloques se copian sin recodificar (rápido y sin depender de los códecs del teléfono). La compresión con datos móviles de la 0.4.2 fallaba en algunos teléfonos (`CodecException`) y alargaba la preparación.
- **La preparación no se repite:** los cortes y los bloques se guardan entre intentos; un reintento continúa donde quedó.
- **Reintentos dentro del servicio en primer plano** (20 s, 1 min, 2 min, 5 min; espera la red si se cae) en vez de pasar a una tarea de fondo que Android frena con la app cerrada. Al abrir la app, el trabajo pasa a primer plano aunque una tarea de fondo lo esté haciendo.
- Si con la app cerrada Android solo permite la tarea de fondo, una notificación sugiere abrir la app para acelerar.
- **Guardado rápido:** en Ajustes eliges una carpeta (p. ej. Drive/0-Inbox) y cada transcripción tiene un botón que guarda el .txt allí con un toque. "Guardar en…" abre en la última carpeta usada.
- **Fecha delante del nombre** (`2026-09-27 Nombre`), activada por defecto, sin duplicar; opción para aplicarla a las grabaciones existentes.
- **Confirmación antes de cancelar** una transcripción; el botón queda en color de error y separado de "Ver detalles del proceso".
- Bitácora más clara: "OpenAI está transcribiendo", motivo por el que Android pausa la tarea de fondo, sin líneas repetidas.
- **Informe de soporte ampliado:** incluye las bitácoras de las últimas 5 transcripciones, teléfono, estado de optimización de batería y restricción de segundo plano; el registro técnico agrega red (wifi/datos), quién ejecuta (primer plano/fondo), motivo de las pausas de Android y mensaje técnico de los fallos de red.

## 0.4.2 — 2026-09-23

Transcripción más rápida y con información completa del proceso.

- Modelo nuevo **gpt-transcribe** (predeterminado para texto): más rápido, más económico (US$0,0045/min) y con el texto llegando en vivo (streaming). Usa `languages[]` como exige la API.
- Pregunta **"¿Separar voces?"** al transcribir, con velocidad y costo estimado de cada opción. En Ajustes: preguntar, siempre o nunca.
- Separación de voces más rápida: bloques de ~5 min cortados en **pausas** (no a mitad de frase), **3 en paralelo**, con **muestras de voz** del primer bloque para mantener a cada persona con el mismo nombre en todos los bloques. Si el proveedor rechaza las muestras, el bloque se reenvía sin ellas.
- Con datos móviles, los bloques se comprimen a 32 kbps (~3× menos datos); con Wi-Fi se envían sin recodificar.
- **Métricas** en vivo: tiempo total y restante, audio procesado, velocidad, costo estimado (hasta ahora y total), tokens, datos enviados y texto recibido. Resumen al terminar dentro de "Ver detalles del proceso".
- La tarjeta de proceso se actualiza sola cuando cambia una condición (p. ej. al conectar el cargador) y empieza de inmediato.

## 0.4.1 — 2026-09-23

Transcripción más confiable y transparente, a partir de la primera prueba real (audio de 8 min que tardó ~15 min).

- Transcripción en **primer plano** con notificación de progreso: sigue con el teléfono bloqueado. La tarea diferida queda para esperar Wi-Fi o cargador.
- **Detalles del proceso**: paso actual con cronómetro, progreso de envío y de bloques, condiciones reales (red, cargador, batería), "Empezar ahora" y bitácora con horas.
- La espera de respuesta del proveedor escala con la duración del bloque (antes eran 4 min fijos, lo que provocaba reenvíos y cobros repetidos en audios largos). Entre bloques, la tarea diferida cede el turno para no ser cortada por Android.
- Pantalla Grabar fija (sin desplazamiento); el estado de transcripción va en el chip de la cabecera.

## 0.4.0 — 2026-09-23

Rediseño completo de UX/UI. Criterios y decisiones en [docs/diseno/CRITERIOS.md](docs/diseno/CRITERIOS.md).

- Design system **Material 3** implementado de forma nativa (sin dependencias): roles de color desde un solo color semilla, escala tipográfica, formas y movimiento, en claro y oscuro. Material You opcional en Android 12+.
- Componentes Material: barra de navegación con indicador, barra superior con ← y ⋮, chips de filtro, listas con íconos monocromos, botones en forma de píldora y hojas inferiores con botones de radio.
- Grabar con un toque: botón círculo→cuadrado, onda en vivo en dB, punto REC, pausa, título durante la grabación y resumen al guardar. Modo foco sin mover el botón.
- Biblioteca con búsqueda, filtros por estado, secciones por fecha y menú por grabación.
- Nueva pantalla de detalle: reproductor (±15 s, velocidad), estado, transcripción con colores por hablante, tiempos que reproducen y barra de exportación.
- "Guardar en…" para exportar la transcripción a cualquier ubicación, incluida Google Drive vía su app. Copia a carpeta compatible con proveedores que solo aceptan escritura "w".
- Importar con selector de tramo de dos manijas.
- Ajustes como lista agrupada con tarjeta de estado; bienvenida en el primer uso.
- Ícono adaptativo nuevo e ícono de notificación monocromo.
- Correcciones: cierre al importar en Android 8 (API 28), errores de compilación de `setClipData` y CI de GitHub.

## 0.3.0 — 2026-09-21

- Inicio centrado en grabar/importar, biblioteca separada, paleta clara y azul.
- Eliminación de Google Drive y su dependencia de Google Play.
- Importación mediante selector y compartir desde otras apps; conversión AAC y recorte de una copia.
- Modelos OpenAI seleccionables, servidor compatible personalizado y claves cifradas independientes.
- Campo de clave oculto después de configurar; comprobación de conexión conservada.
- Carpeta local elegible, exportación de texto/archivo y edición de nombres preservada.
- Estados/notificaciones de procesamiento, bloques de hasta 15 minutos y diagnóstico HTTP más preciso.
- Registro técnico local acotado, frecuencia de acciones e informe de soporte sin contenido personal.
- Gradle wrapper y compilación automatizada en GitHub.

Versión preliminar. Las llamadas de API se validan con respuestas controladas; falta confirmar con una solicitud real el error HTTP 400 reportado por el usuario. La compatibilidad de archivos importados depende de los decodificadores Android. El recorte es por rango temporal, no un editor de ondas.

## 0.2.0 — 2026-09-20

- Grabación local, pausa, reproducción, títulos y biblioteca.
- Configuración de OpenAI con clave cifrada en Android Keystore.
- Transcripción con separación de voces y nombres editables.
- Cola con condiciones de red y carga, división de audios largos.
- Integración de Google Drive que requiere configuración OAuth propia.
- Ejemplo local de edición de hablantes y pruebas instrumentales.

Esta publicación conserva la versión previa al rediseño. Las pruebas de red usan respuestas controladas; las integraciones requieren validación con credenciales del usuario. Se ha reportado un error HTTP 400 cuyo diagnóstico está pendiente para la siguiente versión.

## 0.1.0 — 2026-09-20

- Primera grabadora local sin servicios de transcripción.
