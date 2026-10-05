# Historial de versiones

## 0.9.3 — 2026-10-04 · «Menos ruido, más voz»

**Ruido de fondo** (Ajustes → «Ruido de fondo»; todas apagadas por defecto, para probarlas de a una, pedido del usuario)
- **Grabar con reducción de ruido del teléfono:** usa el micrófono de las llamadas (`VOICE_COMMUNICATION`), ideal para una persona cerca del teléfono.
  - Si el teléfono no lo deja preparar, graba con el micrófono normal.
  - La grabación queda marcada con `recNoise`.
- **Quitar zumbidos y nivelar el volumen** (`AudioClean.LEVEL`), sobre la copia que se envía a transcribir:
  - un pasa-altos Butterworth de 4.º orden a 100 Hz baja el zumbido de 50 Hz en 24 dB;
  - un nivelador lento lleva la voz hacia −20 dBFS (de −6 a +12 dB) y solo se ajusta mientras hay voz.
- **Reducir el ruido de fondo (experimental)** (`AudioClean.NOISE`): filtro de Wiener por bandas.
  - Trabaja con una FFT de 512 y la mitad de solape.
  - El ruido se estima por estadística de mínimos (~1,5 s), con «decision-directed» y un tope de −12 dB.
- **Las dos limpiezas:**
  - Trabajan en streaming y no cambian el número de muestras, así que los tiempos y las anclas siguen exactos.
  - La grabación original nunca se toca.
  - La limpieza viaja con el trabajo (`HttpApi.audioClean`) y entra en la clave del audio guardado (`Kept`).
- **Realzar voces al escuchar** (`VoiceBoost`): ecualizador (menos graves, más 1–4,5 kHz) y realce de volumen de 6 dB sobre la sesión del reproductor. Solo cambia lo que se oye.
- El detalle de cada grabación dice con qué opciones se grabó y se transcribió («Audio: …»). Para comparar, sirve «Volver a transcribir».

**Arreglos del informe del 2026-10-04** (un Xiaomi con Android 16 y la batería optimizada)
- **Se sigue al tiro tras un corte:** si Android corta la transferencia con una pantalla de Verbapp a la vista, sigue en primer plano (`PipelineJob.resumeNow`, `Screen.visible`). Antes quedó «En cola…» 42 s, hasta volver a Grabar.
  - No se aplica si la cortó la propia app ni si falta red, cargador, batería o espacio.
- **Texto del corte:** el código 13 ya no dice «detenida desde Ajustes de Android». Ahora dice que lo cortó el ahorro de batería o el administrador de tareas del teléfono.
- **«Nota lista» sin repetir:** se escribía dos veces en la bitácora (una con «8 s» y otra con «00:07»), y parecía un segundo intento.

## 0.9.2 — 2026-10-03 · «Te avisa cuando hay versión nueva»

**Aviso de versión nueva** (`Updates`, con la librería oficial de Google `com.google.android.play:app-update` 2.1.0)
- **Qué hace:** cuando Google Play tiene una versión nueva, Grabar lo dice en la píldora bajo el saludo, solo si nada más pide atención.
  - Al tocar, Play pide confirmar (con el tamaño) y descarga mientras sigues usando la app.
  - Cuando termina, aparece «Versión nueva lista para instalar». Verbapp se cierra un momento y vuelve a abrirse actualizada.
  - No se ofrece instalar mientras grabas o importas. Una transcripción en curso sigue sola después.
- **Ajustes → Ayuda y soporte → «Buscar actualizaciones»:** revisa cuando quieras y dice en qué va (la última versión, descargando con su %, lista para instalar).
- **Qué versión ofrece:** la que Play le asigna a cada persona (prueba cerrada o pública), así que nunca avisa de una que Google aún no aprueba. La revisión automática se hace como mucho cada 30 minutos.
- **Solo con Google Play:** una copia instalada a mano (APK) no puede actualizarse sola. Ajustes lo explica y advierte que desinstalarla borra las grabaciones.
- Es la primera dependencia de la app. Trae piezas de Google Play services y de AndroidX, y la APK de prueba pasa de ~1,7 MB a ~3 MB.

**El teclado se abre solo**
- En «Nombra esta grabación», «Cambiar título», el título mientras grabas, «Marcar con una palabra» y pegar la clave, el campo queda listo para escribir con el teclado abierto.
- Antes, en Android 13+ (el vivo con Android 16) había que tocar el campo, y ese toque deshacía la selección de todo el nombre (`Sheet.keyboard`, `Ui.showKeyboard`).

## 0.9.1 — 2026-10-03 · «Más paciencia cuando OpenRouter falla»

Arreglos del informe de soporte del 2026-10-03. Una grabación se rindió dos veces porque OpenRouter (o el modelo) respondía 502 y 429. Había tres problemas:
- la app gastaba sus 5 intentos en unos 12 minutos;
- preparaba el audio de nuevo en cada intento (de 17 s a más de 1 minuto);
- al final ofrecía «Revisar ajustes», que no servía de nada.

**Reintentos que esperan**
- Las caídas del servicio (408, 429 y 5xx, `HttpApi.ServerBusy`) ya no gastan los 5 intentos comunes. La app reintenta sola durante hasta una hora desde la primera caída, con un máximo de 20 intentos.
  - Esperas: 30 s, 1, 2, 5, 10 y 15 min. Si el servidor pide más con `Retry-After`, espera eso, hasta 1 h.
  - Cada parte que se termina reinicia la cuenta.
- Mientras espera, la grabación dice «esperando que OpenRouter se recupere (se reintenta sola a las HH:MM)» y el botón dice «OpenRouter con problemas…».
- Si la espera dura más de 5 minutos, la notificación no queda encendida: Android retoma el trabajo a la hora del reintento.

**Sin preparar el audio dos veces**
- Tras una caída, el audio ya preparado de cada parte (FLAC o WAV, con las muestras de voz) queda guardado y el reintento lo reutiliza (`OpenRouterClient.Kept`).
- Se borra cuando la parte se transcribe, al cancelar o ante un error que reintentar no arregla.

**Mensaje claro y «Probar con otro modelo»**
- Si se rinde por una caída, el detalle dice «OpenRouter tiene problemas»: no es el teléfono ni la clave, y lo ya transcrito queda guardado. Ya no aparece «Revisar ajustes».
- **«Probar con otro modelo»** termina lo que falta con el modelo elegido. Las partes listas se conservan y no se vuelven a cobrar (`Pipeline.request` con `fallbackModel`).
  - La lista muestra los modelos que «Automático» podría usar, sin el que falló.
  - Solo aparecen los que separan voces, si se pidió, y los que aceptan las partes ya cortadas (`Models.fallbacks`).

## 0.9.0 — 2026-10-02 · «Verbapp en tu idioma»

Preparada para **Google Play** y en **tres idiomas**: inglés, español y portugués de Brasil. El paquete publicado es `cl.verbapp.app` (se instala al lado de la versión anterior); el código sigue en `cl.vozlocal.app`.

**Tres idiomas**
- Toda la app (pantallas, avisos, notificaciones, errores, bitácora, TXT exportado, la nota con IA y las novedades) está en inglés, español y portugués de Brasil. El español quedó igual que antes.
- Parte en el idioma del teléfono si es uno de los tres; si no, en inglés. Se cambia en la bienvenida (selector bajo el título) o en Ajustes → Idioma, y en Android 13+ también en Ajustes del teléfono → Idiomas de las apps.
- **Idioma del audio:** detección automática, inglés, español o portugués. Si no eliges, se usa el idioma de la app.
- La nota con IA se escribe en el idioma de la app. Fechas, números y montos usan el formato de cada idioma.
- Lo guardado en otro idioma (bitácora, títulos automáticos, «Persona N», el «Yo» de tu voz, el último error de la clave) se sigue reconociendo al cambiar de idioma (`Lang`, `StatusText`).

**Google Play**
- **Aviso antes del primer envío:** qué se envía a OpenRouter, para qué, y la política de privacidad; se acepta con «Acepto» antes de guardar la clave o de la primera transcripción (`Consent`).
- **Política de privacidad** a un toque en Ajustes → Ayuda y soporte, en el idioma de la app.
- Android 16 (API 36). Sin el permiso para pedir quitar la optimización de batería: el botón abre la ficha de la app.
- Los bloqueos de suspensión (wake locks) de grabar, importar y transcribir tienen límite de tiempo y se renuevan mientras trabajan.
- El AAB lleva los tres idiomas a todos los teléfonos; firma de publicación desde `.tools/signing` (fuera de git).

**Más**
- **«Detener»** en la notificación de la transcripción: la corta sin abrir la app y deja la grabación lista para «Transcribir» de nuevo.
- Cancelar una transcripción corta también las partes que se estaban enviando.
- Arreglo: la bitácora simplificada ya no cambia «pantalla bloqueada» por «pantalla parteada».

## 0.8.0 — 2026-10-01 · «Una sola clave para todo»

Todo pasa por **OpenRouter**, con una sola clave, y llegan **«Tus métricas»**, una **bienvenida** para quien instala por primera vez y los arreglos del diagnóstico del 2026-10-01: una grabación corta quedó más de 1 hora sin transcribir. Diseño en `docs/diseno/SPEC-0.8.md`, `SPEC-0.8b.md` y `SPEC-0.8c.md`; plan en `docs/PLAN-openrouter.md`.

**OpenRouter, y solo OpenRouter**
- Una clave transcribe y arma la nota. Ya no se elige proveedor; al actualizar, la app pasa a OpenRouter sola (esquema 5). Las claves antiguas quedan guardadas, sin uso.
- **Catálogo vivo:** los modelos de transcripción se piden a `GET /models?output_modalities=transcription` y se guardan. Cada uno dice si separa voces y cuánto cuesta por hora. «Automático» elige el recomendado y no cambia de modelo a mitad de una transcripción (regla única: `Models.resume`).
- **Audio liviano:** se envía en FLAC mono de 16 kHz (WAV si el teléfono no tiene codificador), en partes si es largo, y se reintenta por mitades ante un 413.
- **Voces conocidas:** tus muestras de voz van delante del audio, con 1 s de silencio entre ellas. La voz que suena en cada muestra se asocia a su nombre y después esa zona se quita del texto.
- **Costo real:** cuando OpenRouter lo informa, se guarda el costo de cada transcripción y de cada nota. El audio cobrado con muestras se calcula con una sola regla (`Pricing.billedMs`).
- **Notas con la última versión** de Claude, GPT o Gemini (alias `~…-latest`); la nota dice qué modelo respondió.
- **Saldo:** «Comprobar conexión» muestra el saldo de la cuenta, y avisa si aún no tiene créditos.

**Tus métricas** (Ajustes, o tocando «Tu semana» en Grabar, que abre en «7 días»)
- Por período (7 días, este mes, todo): tiempo hablado, grabaciones, palabras transcritas y notas.
- 8 semanas en barras, racha de días y mejor racha, y un mapa de a qué hora grabas.
- Con quién conversas (voces reconocidas).
- Gasto por modelo: real y estimado por separado, con US$ por hora. También el saldo de OpenRouter de la última comprobación.
- Todo se calcula en el teléfono. Nada se envía.

**Bienvenida** (la primera vez; se repite desde Ajustes → Ayuda y soporte)
- Cuatro pasos: qué hace la app; «Tú» (nombre, micrófono y batería); tu clave de OpenRouter, con comprobación y saldo; y listo.
- En «Tú» pide el permiso para **transcribir con el teléfono bloqueado**, con los pasos según la marca (vivo y otras). Muestra ✓ si ya está dado.

**Que nada quede esperando sin avisar** (diagnóstico del 2026-10-01)
- **Preparar el audio es varias veces más rápido:** filtro en float con menos coeficientes, caminos directos 48→16 kHz y 32→16 kHz, y una comprobación barata del FLAC (cuadros y CRC) en vez de decodificarlo entero. Muestra el avance en %.
- **«Usar datos móviles ahora (≈X MB)»:** si una transcripción espera Wi-Fi, un botón en el detalle y en la notificación la envía con datos, solo esa vez.
- **Transferencia iniciada por el usuario (Android 14+):** al tocar «Transcribir», el trabajo sigue aunque la app pase a segundo plano, sin las cuotas de las tareas de fondo.
- Si una transcripción se rinde tras varios intentos, avisa con una notificación.
- **Si el Wi-Fi se corta a mitad** (con «Solo con Wi-Fi»), el trabajo espera con el aviso «Esperando Wi-Fi» y su acción «Usar datos móviles», en vez de seguir por datos o quedarse callado. «Solo con Wi-Fi» se revisa antes de enviar cada parte.
- **Cancelar** cierra la transferencia y su notificación; lo demás pedido sigue.
- Cambiar «Red para enviar audio» o «Solo mientras carga» en Ajustes reprograma lo que espera, con las condiciones nuevas.
- **«Empezar ahora»** empieza de verdad aunque Android tenga retenida la transferencia.
- Un congelamiento de la app (vivo) ya no se confunde con un códec trabado al preparar el audio.

**Que las pantallas digan la verdad** (revisiones adversariales de la 0.8.0)
- Una grabación en cola dice «En cola…» o «Esperando Wi-Fi…»; solo la que se procesa dice «Transcribiendo…». La píldora de Grabar, la tarjeta «Última grabación», el detalle y los avisos dicen lo mismo (regla única: `Pipeline.processing`).
- El tamaño de «Usar datos móviles» es el real también al volver a transcribir.
- Una clave rechazada se muestra igual en la bienvenida, en Ajustes y en Grabar. Ajustes no guarda claves de otros servicios (OpenAI, Anthropic): no se envían a OpenRouter.
- «Tus métricas» fecha lo cobrado de una pasada en curso cuando se cobró, y no dice «con tu servidor» para OpenRouter.
- «Tu semana» no cuenta las grabaciones de ejemplo, igual que «Tus métricas».

**Otros**
- Informe de soporte con fecha y hora en el nombre (`Verbapp-soporte-AAAA-MM-DD-HHMM.txt`).
- Ícono nuevo para la Biblioteca (tres libros).

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

**Revisión antes de entregar**
Una revisión independiente del código (cuatro revisores y un verificador por área) confirmó 22 hallazgos, todos corregidos. Los principales:
- Sobre Grabar, los botones del sistema quedaban blancos encima de la hoja blanca. Ahora cada hoja pide sus propios íconos oscuros.
- «Cambiar título» había cambiado de comportamiento (fecha fija y la tecla del teclado guardaba). Volvió a funcionar como en 0.6.
- Con letra grande se cortaban textos: el título «Personas», el nombre filtrado, las etiquetas de la nota, los botones lado a lado de las hojas, los nombres de los temas y los nombres de voces en «Nueva versión lista».
- El lector de pantalla no anunciaba «Grabando» al empezar, y el ✕ de las hojas medía menos de 48 dp.
- La pantalla Grabar podía medirse antes de tener el botón de «Última grabación» y aplastar «Importar audio».
- La onda del mini reproductor se dibuja plana si no se alcanzó a registrar toda la grabación (teléfono bloqueado), en vez de estirar un tramo.
- Volvieron dos textos útiles de 0.6: «funciona sin internet» y la pista de ★.

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
