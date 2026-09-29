# Voz local: propuesta de interfaz para que se sienta más profesional (0.6.0 y siguientes)

## Visión

Voz local ya graba bien y rápido: llegas al botón rojo en 0,9–1,3 s después de abrir la app. Para que se sienta profesional no hacen falta más colores ni más funciones. Hace falta que la app entienda tu recorrido completo como una sola historia: **grabar → esperar la transcripción → poner nombres a las voces → guardar en 0-Inbox**. Hoy cada paso es una pantalla aparte, y tienes que ir a buscar el estado y la salida.

La idea es que cada pantalla:
- responda sola a «¿en qué va?»;
- te deje el siguiente paso a un toque.

En lo visual, se aplica Material 3 Expressive con disciplina. Lo expresivo va en las formas, el tamaño, el movimiento y la vibración. El color sigue reservado para lo que significa algo:
- azul: acción;
- rojo: grabar o error;
- un color por persona.

La onda del audio pasa a ser el sello de la app. Y tu segundo cerebro pasa a ser el destino natural de cada grabación.

## Qué dicen tus datos (y sus límites)

**Qué se revisó:**
- 999 eventos entre el 22 y el 28 de septiembre, en las versiones 0.3 a 0.4.4, con unas 13 grabaciones.
- Una auditoría en vivo de todas las pantallas en el emulador.
- Una revisión de apps de referencia: Grabadora de Google con Material 3 Expressive, Otter, Just Press Record, Notas de Voz y Granola.

**Límites de los datos:**
- Hay un solo usuario, tú.
- Alrededor del 30 % de los eventos son pruebas de versiones nuevas.
- Aún no hay informe de la 0.5.0, así que no sé cómo usas «Mi voz» ni la corrección de voces.

**Tu recorrido real:** grabas cosas largas (5, 8, 26, 32 y 52 min) → miras el avance una y otra vez → nombras las voces en varias pasadas → guardas en 0-Inbox.

## Ranking

El puntaje es (impacto en la sensación profesional + fuerza de la evidencia) ÷ esfuerzo:
- Impacto y evidencia van de 1 a 5.
- Esfuerzo: S = 1 (pequeño, unas 1–2 sesiones de trabajo), M = 2 (mediano, una versión), L = 3 (grande).

| # | Idea | Grupo | Impacto | Evidencia | Esfuerzo | Puntaje |
|---|---|---|---|---|---|---|
| 1 | «Guardar en 0-Inbox» como salida principal | A | 4 | 5 | S | 9,0 |
| 2 | Un solo lenguaje: íconos, palabras y modo oscuro | C | 4 | 3 | S | 7,0 |
| 3 | Ajustes que no hace falta visitar | A | 3 | 3 | S | 6,0 |
| 4 | El avance a la vista | A | 5 | 5 | M | 5,0 |
| 5 | Nombrar voces en una sola pasada | B | 4 | 5 | M | 4,5 |
| 6 | De «Detener» directo a tu grabación | B | 4 | 5 | M | 4,5 |
| 7 | El detalle como un documento | B | 5 | 4 | M | 4,5 |
| 8 | Notificaciones que llevan al lugar correcto | A | 4 | 4 | M | 4,0 |
| 9 | Una Biblioteca que se lee sola | B | 4 | 4 | M | 4,0 |
| 10 | Grabar, una pantalla viva, con ★ Marcar momento | B | 5 | 3 | M | 4,0 |
| 11 | La onda como sello de la app | B | 5 | 4 | L | 3,0 |
| 12 | Movimiento y vibración con significado | C | 4 | 2 | M | 3,0 |

Grupos: A = cambios que se notan de inmediato · B = pantallas rediseñadas · C = detalles que dan sensación profesional.

---

## A. Cambios que se notan de inmediato

### #1 «Guardar en 0-Inbox» como salida principal (S)

**Qué cambia:**
- **Detalle, barra inferior.** Hoy hay 5 botones iguales (Copiar, Compartir, .txt, 0-Inbox, Otra carpeta). Pasan a ser un solo botón ancho y relleno, «Guardar en 0-Inbox ▾», de dos partes. La flecha abre una hoja con Copiar, Compartir, Archivo .txt y Otra carpeta.
- **Después de guardar**, el botón cambia a un tono suave y dice «✓ En 0-Inbox · 16:09».
- **Si después corriges voces o nombres**, dice «Actualizar en 0-Inbox» y reemplaza el mismo archivo, sin crear una copia.
- **Con la nota para tu segundo cerebro** (idea que ya elegiste), el botón guarda la nota .md completa: resumen, decisiones, tareas, momentos ★ y al final la transcripción. Es un solo archivo y un solo toque. «Solo la transcripción (.txt)» pasa al menú ▾.
- **En la Biblioteca**, «Guardar en 0-Inbox» se agrega al menú ⋮ de cada fila, y la fila muestra «✓ Inbox».
- **En la notificación «Transcripción lista»**, se agrega la acción «Guardar en 0-Inbox» (ver #8).

**Por qué:**
- Desde que existe (0.4.3), es la única salida que usas: 4 guardados rápidos y 0 veces «Guardar en…».
- Tarda unos 1 s. El selector de archivos tardaba 12–38 s y una vez lo abandonaste.
- El 28-09 a las 16:08:58 tardaste 21 s y 5 pasos en encontrarlo: Más opciones → volver → menú de la fila → volver → 0-Inbox.
- Hoy aparece como uno más entre 5 botones iguales, lo que rompe el principio 1 de CRITERIOS («una acción principal por pantalla»).

**Referencia:**
- El botón de dos partes de Material 3 Expressive (una acción principal más una flecha con las demás).
- Lo que no hay que repetir: a la Grabadora de Google la criticaron en 2025 por esconder «Compartir» en el menú ⋮.

**Riesgo:** bajo.
- «Actualizar» debe escribir sobre el mismo archivo en Drive, sin crear una copia «(1)».
- Si la carpeta rápida no está configurada, el botón dice «Elegir carpeta rápida», para que el error tenga salida.

### #3 Ajustes que no hace falta visitar (S)

**Qué cambia:**
- **Inicio.** Cuando todo está bien, la cabecera muestra una línea tranquila: «✓ Listo para transcribir · Wi-Fi». Solo pasa a chip de color cuando algo pide acción, por ejemplo «Sin conexión: espera Wi-Fi» o «1 transcribiendo».
- **Ajustes, en este orden:**
  1. «Tu flujo»: carpeta rápida 0-Inbox, «Nota para tu segundo cerebro» (automática o con un toque), transcribir automáticamente, fecha en el nombre y Mi voz.
  2. «Servicio de transcripción»: plegado cuando ya funciona. El resultado queda en la misma fila («✓ Conectado · 0,9 s · hace 2 h»). «Comprobar conexión» ya no abre el aviso que había que cerrar con «Entendido».
  3. «Apariencia»: los colores de tu fondo de pantalla con una vista previa en vivo.
  4. «Ayuda y soporte»: novedades y versiones, informe de soporte y borrar registros. Este último deja de estar en rojo compitiendo con todo lo demás.
- **Interruptor real de Material 3.** El riel mide 52×32 dp, la perilla va adentro y muestra un ✓ al activarse. Es lo que más delata hoy una interfaz hecha a mano.
- **Una línea de apoyo por fila.** Los textos de 4 a 7 líneas pasan a «Más información».

**Por qué:**
- Entraste a Ajustes 16 veces y grabaste 13.
- Tocaste «Comprobar conexión» 8 veces, a menudo como lo primero tras instalar (7–16 s después de abrir).
- Tocaste «Entendido» 5 veces solo para cerrar un aviso de éxito.
- 7 de tus 22 visitas fueron durante una transcripción en curso o trabada.
- Cambiaste los colores de tu fondo de pantalla 6 veces, una vez en 1,5 s.

Parte de esto es tu uso como probador, pero la necesidad de saber «¿está funcionando?» es real.

**Referencia:** los grupos de Ajustes de Android 16 y la especificación del interruptor de Material 3.

**Riesgo:** bajo. La clave tiene que seguir fácil de encontrar para cambiarla.

### #4 El avance a la vista (M)

**Qué cambia:**
- **Detalle mientras transcribe.** El avance pasa a ser lo principal, abierto y arriba:
  - **Barra por etapas reales:** Subir · Bloque 1…N · Voces · Nota · Listo. La etapa activa lleva la barra ondulada de Material 3 Expressive.
  - **La etapa en palabras:** «OpenAI está separando las voces del bloque 3 de 5».
  - **Una estimación honesta, como rango:** «≈ 2–4 min restantes». La 0.4.4 transcribe a unas 0,15× la duración del audio: 26 min de audio en 3:59.
  - **Tranquilidad:** «Puedes cerrar la app, te aviso cuando esté lista».
  - **Si está esperando para reintentar:** «Reintento en 0:42» y un botón «Reintentar ahora».
  - **Texto a medida que llega:** el de los bloques listos aparece de inmediato, y lo que falta se ve como líneas grises que laten.
  - **La bitácora y las métricas** quedan como sección secundaria plegable, y la app recuerda si la dejaste abierta.
  - **«Cancelar transcripción»** queda lejos, dentro del menú ⋮.
- **Inicio.** La tarjeta «Desde WhatsApp» (usada 2 veces) se reemplaza por «Última grabación», del mismo tamaño para que la pantalla siga fija. Muestra «Transcribiendo · bloque 3 de 5» con su barra, o «Lista · Guardar en 0-Inbox». La ayuda de WhatsApp pasa a Importar.
- **Biblioteca.** Cada fila muestra el mismo avance (un anillo en el ícono y «Bloque 3/5»), o su lugar en la cola: «En cola · 2.º».

**Por qué (es la evidencia más fuerte):**
- Tocaste «Ver detalles del proceso» 10 veces. 8 de las 9 veces con detalle registrado fueron mientras transcribía, y 1,5–3,5 s después de entrar, porque la tarjeta empieza cerrada en cada visita.
- De las 50 veces que abriste una grabación, cerca del 60 % fueron para mirar el avance.
- El 24-09 la abriste 5 veces en 48 s.
- El 23-09 hiciste 9 toques en 19 s con dos trabajos trabados, y terminaste cancelando ambos.
- Usabas «Cancelar» para reiniciar: hubo 9 cancelaciones en total.

**Referencia:** las barras de progreso onduladas y por etapas de Material 3 Expressive, y las notificaciones de progreso de Android 16.

**Riesgo:** medio.
- La estimación de tiempo puede fallar: se muestra como rango y se va corrigiendo.
- Nunca un porcentaje inventado (principio 2: mostrar el estado real). La barra avanza solo con bloques terminados de verdad.

### #8 Notificaciones que llevan al lugar correcto (M)

**Qué cambia:**
- **Mientras grabas.** La notificación y la pantalla de bloqueo muestran el tiempo en vivo, Pausar/Reanudar, **★ Marcar momento** y Detener. Si detienes desde ahí, al abrir la app sigues el mismo flujo de #6.
- **Mientras transcribe.** La notificación muestra el avance real, por ejemplo «Bloque 3 de 5». En Android 16 puede usar el estilo nuevo de notificación de progreso, que se ve en la pantalla de bloqueo. Esto exige actualizar la versión de Android con que se compila la app; mientras tanto, se usa la barra normal.
- **«Transcripción lista»:**
  - abre esa grabación, no la Biblioteca;
  - suena o vibra una vez;
  - trae las acciones «Guardar en 0-Inbox» (o «Guardar nota») y «Revisar voces»;
  - si hay voces sin revisar, lo dice.
- **Biblioteca.** Un punto «nuevo» en las transcripciones listas que aún no abriste.

**Por qué:**
- Las 13 veces que detuviste una grabación, lo hiciste desde el botón de la app. Volviste a la app solo para detener: tocaste Detener 0,7–8 s después de abrirla.
- La notificación de grabación no muestra el tiempo ni tiene pausa.
- Las 4 veces que entraste desde «lista», terminaste abriendo la grabación 2–10 s después.
- Una transcripción terminó 22 s después de que saliste y volviste recién 2 h más tarde.

**Referencia:** los controles en la notificación de la Grabadora de Google, y las notificaciones de progreso de Android 16.

**Riesgo:** medio.
- Xiaomi y vivo a veces ocultan acciones o silencian notificaciones: hay que probarlo en tu teléfono.
- ★ debe funcionar sin desbloquear el teléfono.
- Hoy los toques en notificaciones no se registran. Hay que empezar a registrarlos para poder medir esto.

---

## B. Pantallas rediseñadas

### #5 Nombrar voces en una sola pasada (M)

**Qué cambia:**
- **En la hoja «Nombrar voces»**, cada voz ocupa una fila con:
  - un círculo con su color e inicial;
  - **▶ para escuchar 3–5 s de un tramo limpio** de esa voz, sin cerrar la hoja;
  - las primeras palabras de su primera intervención, por ejemplo «…entonces lo del presupuesto…»;
  - su porcentaje del tiempo total, por ejemplo «58 %»;
  - nombres sugeridos para elegir con un toque: «Yo» (ligado a Mi voz) y tus nombres frecuentes, como «Fran» o «Konra».
- **Se guarda solo al escribir.** Desaparece el botón «Guardar nombres»; la hoja se cierra con «Listo» y cada cambio se puede deshacer.
- **Siguiente paso sugerido.** Cuando termina una transcripción con voces separadas, la app propone «Revisar voces» antes de guardar en 0-Inbox.
- **En la transcripción**, los nombres se ven tocables, con un subrayado de puntos. La corrección de la 0.5.0 existe, pero hoy no se nota que se puede tocar.

**Por qué:**
- Tocaste «Guardar nombres» 11 veces en 4 grabaciones, casi 3 vueltas por grabación.
- Lo hiciste en ráfagas: 3 guardados en 59 s el 28-09, y el 23-09 guardados intercalados con «Reproducir».
- La hoja ya permite nombrar todas las voces de una vez. El problema es que desde adentro no sabes quién es «Voz A», así que sales, escuchas y vuelves.

**Referencia:** Otter (muestra y porcentaje por persona) y la Grabadora de Google (nombres de quién habla).

**Riesgo:** medio.
- La 0.5.0 cambió mucho el manejo de voces y aún no tengo tus datos de uso de esa versión. Conviene confirmarlo con el primer informe.
- Si una voz casi siempre habla encima de otra, puede no haber un tramo limpio. En ese caso se muestra «Muestra no disponible».

### #6 De «Detener» directo a tu grabación (M)

**Qué cambia:**
- **Al detener**, el botón rojo se transforma y la pantalla pasa al detalle de esa grabación con una transición continua. La hoja «Grabación guardada» desaparece.
- **Arriba del detalle recién grabado:**
  - el título, editable ahí mismo, con el teclado abierto y ya escrito «2026-09-29 »;
  - si la transcripción automática está apagada, un botón principal «Transcribir ahora». Las opciones de voces van en el mismo lugar («Separar voces · con Mi voz» / «Solo el texto») con «≈ 3 min · ≈ US$0,05»;
  - si está encendida, el avance de #4 («Se transcribirá con Wi-Fi», o el avance real);
  - los momentos marcados, por ejemplo «3 momentos ★».
- **«←» te devuelve a Grabar**, listo para otra grabación, en un toque, igual que el «Listo» de hoy.
- **Mientras grabas**, se quita el chip «+ Añadir título», que nunca usaste. Su lugar lo ocupa ★ Marcar (#10).
- **El título lleva un lápiz visible.** Hoy tocarlo renombra sin ninguna pista.

**Por qué:**
- Al terminar de grabar, 6 de 10 veces tocaste «Ver grabación».
- Unas 6 de 8 veces escribiste el título ahí mismo, entre 3 y 17 s después.
- 1,5–3 s después abriste los detalles del proceso. Es un ritual fijo de 3 pasos que hoy ocupa 3 pantallas.
- Después renombraste 4 grabaciones más desde la Biblioteca.
- «Guardar título» durante la grabación: 0 veces en 10 grabaciones.

**Referencia:** Just Press Record (la fecha como título editable) y la Grabadora de Google (palabras sugeridas para el título).

**Riesgo:** medio.
- Si grabas varias cosas seguidas, abrir el detalle agrega un paso. Por eso «←» vuelve a Grabar en un toque y el título se guarda solo.
- El teclado no debe tapar el botón principal.

### #7 El detalle como un documento (M, o L si va junto con #11)

**Qué cambia:**
- **Sin «caja dentro de caja».** La transcripción ocupa todo el ancho, sin tarjeta, como un documento. Cada intervención lleva un punto de color, el nombre y la hora en gris a la derecha.
- **Título grande que se pliega** en la barra superior al desplazarte. Hoy desaparece.
- **Reproductor.** Hoy ocupa unos 350 dp arriba. Pasa a ser un **mini reproductor fijo abajo**, justo sobre «Guardar en 0-Inbox», con:
  - un ▶ grande que cambia de forma al pausar;
  - el tiempo;
  - −15 y +15, con el número dibujado en el ícono;
  - velocidad 1× / 1,5× / 2×.
  Al tocarlo se abre el reproductor completo.
- **La transcripción sigue al audio.** Se desplaza suave hasta la parte que suena. Si te mueves a mano, deja de seguirlo y aparece «↓ Volver a lo que suena». Tocar cualquier frase reproduce desde ahí. Mantenerla pulsada ofrece copiar, corregir la voz o marcar ★.
- **Arriba de la transcripción, en este orden:**
  1. **Ficha de la grabación:** fecha, duración, personas con su color y porcentaje, «Voces revisadas ✓ / pendiente» y si ya está en 0-Inbox.
  2. **Tarjeta «Nota para tu segundo cerebro»:** resumen en 5 líneas, decisiones y tareas que puedes marcar como hechas (qué, quién, para cuándo). Es plegable, lleva la etiqueta «Generada por IA · revísala antes de guardar» y tiene un estilo distinto del texto transcrito, para que se vea qué dijo la máquina.
  3. **Fila «Momentos ★»:** chips como «03:12» o «17:40» que saltan a ese punto. Las intervenciones marcadas llevan ★.
- **Sin saltos de pantalla.** Hoy, cada 250 ms, si cambia cualquier archivo de la app, la pantalla completa se vuelve a armar (`reload()` en `RecordingActivity.java:27-28`). En la auditoría, un toque dirigido a «Persona 3» abrió «Cambiar título» porque la vista había vuelto arriba. El cambio: actualizar solo lo que cambió y conservar la posición.

**Por qué:**
- El reproductor domina la primera pantalla, y la transcripción, que es lo que te importa, empieza a media altura.
- Tocaste Reproducir 10 veces y Adelantar 15 s una vez, casi siempre entre un «Guardar nombres» y otro: escuchas mientras identificas voces. El control de audio tiene que estar siempre a mano.
- Los saltos probablemente causan toques equivocados mientras otro audio se transcribe.

**Referencia:**
- La Grabadora de Google con Material 3 Expressive.
- Notas de Voz y Otter (la transcripción sigue al audio).
- Granola (se distingue lo que escribió la IA de lo que se dijo).

**Riesgo:** alto. Es el cambio de pantalla más grande.
- Una transcripción de 52 min debe seguir moviéndose con fluidez.
- El arreglo de los saltos es una corrección de error: se puede adelantar a la versión en curso (0.5.1).

### #9 Una Biblioteca que se lee sola (M)

**Qué cambia:**
- **Filas de 3 líneas:**
  - el título;
  - el comienzo del texto, por ejemplo «Fran: entonces lo que…»;
  - «32 min · ●● Fran, Konrad · ✓ Inbox».
- **El color se invierte.** Lo terminado va en silencio (ícono neutro). Se destaca solo lo que pide acción:
  - «Transcribiendo · bloque 3/5», con un anillo de avance;
  - «Error», con el botón «Reintentar» en la misma fila;
  - «Sin transcribir».
- **Sin la fecha repetida.** Los grupos ya dicen la fecha. La fila muestra la duración en formato humano («5 s», «52 min»), alineada a la derecha.
- **Deslizar la fila:**
  - a la derecha, guarda en 0-Inbox, con una vibración al pasar el punto de activación;
  - a la izquierda, la elimina, con «Deshacer» y una papelera de 30 días. Hoy se confirma en una hoja y no hay vuelta atrás.
- **El menú ⋮ se ve más suave.**
- **Buscar dentro de las transcripciones**, no solo en el título:
  - cada resultado muestra el fragmento con la palabra resaltada;
  - al abrirlo, el detalle va directo a esa frase, con «2 de 7 ↑ ↓» para pasar a la siguiente;
  - buscar «Fran» trae todo donde ella habló.
- **Grupos por fecha como tarjetas por segmentos**, como en los Ajustes de Android 16.

**Por qué:**
- El menú ⋮ de las filas es el control que más tocaste (19 veces). Al menos 4 veces lo abriste solo para mirar el estado y saliste sin hacer nada.
- Hoy «Transcrito», en el azul de las acciones, aparece en 23 filas, mientras «Sin transcribir» queda en gris: el color destaca justo lo que no importa.
- «00:29 · 00:05» no se entiende: son la hora y la duración con el mismo formato.

**Referencia:**
- Just Press Record (adelanto del texto en la lista).
- La búsqueda de la Grabadora de Google, que busca dentro de las transcripciones.
- Las listas de Material 3.

**Riesgo:** medio.
- Leer muchas transcripciones al abrir la lista puede hacerla lenta. Solución: guardar el fragmento al terminar cada transcripción.
- Deslizar no debe chocar con el gesto «atrás» de Android, que parte desde el borde de la pantalla.

### #10 Grabar, una pantalla viva, con ★ Marcar momento (M)

**Qué cambia:**
- **En reposo:**
  - el botón rojo es lo principal, de 88–96 dp;
  - el «00:00» gigante desaparece o queda pequeño, y en su lugar va la línea de estado de #3;
  - se oculta la línea de puntos gris.
- **Grabando:**
  - una onda alta (120–160 dp) de barras redondeadas que avanzan, y que aparece con una animación al empezar;
  - un tiempo grande y marcado, con dígitos de ancho fijo, y debajo el micrófono en uso («Micrófono del teléfono» o «Audífonos Bluetooth»);
  - tres botones: **[Pausa] [■ Detener] [★ Marcar]**. Detener queda fijo en el centro, exactamente donde estaba el botón de grabar.
  - **★ Marcar** (la segunda idea que elegiste): vibra corto, deja una marca en la onda y muestra un contador «3 momentos». Como opción, mantenerlo pulsado permite escribir o dictar una palabra («precio», «idea»).
- **En pausa:** la onda se congela en gris, el tiempo parpadea y «Reanudar» se destaca. Hoy solo cambia un texto gris.
- **Fuera de Grabar, mientras grabas:** una píldora fija «● 12:34 · Detener». Hoy solo hay un punto rojo diminuto en el ícono.
- **Contra el doble toque accidental:**
  - Detener se ignora durante el primer segundo;
  - al empezar, una vibración y un cambio de forma claros;
  - el aviso «Grabación muy corta» ofrece «Deshacer».

**Por qué:**
- El 28-09 a las 12:58:44 un doble toque grabó 465 ms. Esa grabación se envió a OpenAI (una llamada pagada), tuviste que eliminarla dos veces y volver a grabar.
- La pausa es confusa: 7 toques, 6 de ellos en una misma grabación, y una vez detuviste 2 min después de pausar sin reanudar. Además, hoy el registro no distingue «Pausar» de «Reanudar».
- ★ Marcar momento es una de las dos funciones que elegiste.
- Lo que funciona y se mantiene: llegas a grabar en 0,9–1,3 s.

**Referencia:**
- La Grabadora de Google con Material 3 Expressive: botones grandes de Pausa y Detener, tiempo destacado.
- Otter: botón para marcar mientras graba.

**Riesgo:** medio. En Material 3 Expressive, el botón que presionas se ensancha un poco. Eso no debe mover el lugar donde tocas «Detener»: está en CRITERIOS («ningún elemento cambia de lugar entre estados»), porque ya pasó que el botón se movía y el toque fallaba.

### #11 La onda como sello de la app (L)

**Qué cambia.** Una misma onda de audio, hecha a medida, en tres lugares:
- **Detalle:**
  - la onda es la barra para moverse por el audio y reemplaza a la barra genérica de hoy;
  - lo ya escuchado va en azul y lo que falta en gris;
  - debajo, una franja de 6 dp con el color de quién habla en cada tramo;
  - los ★ aparecen como puntos;
  - tocar salta a ese punto, y al arrastrar se siente un pequeño tic al pasar por una marca o por un cambio de persona;
  - al corregir una voz, la franja cambia al instante, así ves la corrección.
- **Importar, al recortar:**
  - la onda con la zona elegida resaltada;
  - manijas grandes, cada una con su ▶;
  - botones de ±5 s;
  - tiempos en mm:ss y el resumen «1:02:10 – 1:13:42 · 11:32».
- **Importar, en general:**
  - guardar muestra una barra de avance;
  - al entrar se abre directo el selector de archivos (hoy aparece cargado un archivo viejo);
  - salir de la pantalla ya no cancela la importación.

La app guarda el volumen mientras graba, unos 10 valores por segundo (unos 36 KB por hora). Para los audios importados o antiguos, lo calcula una sola vez.

**Por qué:**
- «Escuchar el tramo» es la acción que más tocaste (13 veces): hoy recortas a ciegas.
- Importaste el mismo audio de 89 min 3 veces. La vez que funcionó, fueron 11 toques en 55 s para encontrar los cortes y 50 s guardando sin ver avance.
- En el detalle, la barra de reproducción genérica es de lo que más delata una app básica.

**Referencia:** la onda gruesa como barra de reproducción y las marcas sobre la onda de la Grabadora de Google con Material 3 Expressive; el recorte sobre la onda de Notas de Voz.

**Riesgo:** medio.
- Calcular la onda de un audio de 89 min toma unos segundos. Se hace en segundo plano y la onda se va dibujando a medida que se calcula.
- Las grabaciones antiguas no tienen esos datos de volumen guardados.

---

## C. Detalles que dan sensación profesional (movimiento, vibración, textos)

### #2 Un solo lenguaje: íconos, palabras y modo oscuro (S)

**Qué cambia:**
- **Íconos: un solo estilo en toda la app,** Material Symbols Rounded de contorno. Se copian al código, sin agregar librerías. Cada acción tiene su propio ícono:
  - unir;
  - nombrar a todas;
  - ver solo sus intervenciones (un ícono de filtro, no una lupa);
  - idioma;
  - comprobar conexión;
  - resumen.
- **Sin jerga en la lectura:**
  - se quitan «Bloque 2 · desde 05:00» y «Transcrito con gpt-4o-transcribe-diarize»;
  - al final queda un pie en lenguaje normal: «Transcrito el 23 sept · tardó 2 min · ≈ US$0,02»;
  - los bloques y el nombre del modelo quedan en «Detalles del proceso».
- **Personas numeradas en el orden en que aparecen.** Hoy sale «Persona 1 / Persona 3» sin la 2, y parece un error.
- **Duraciones en formato humano:** «5 s», «52 min», «1 h 04 min».
- **Importar:**
  - sin el texto técnico «si el formato no es AAC… copia M4A»;
  - el título pierde la extensión «.m4a».
- **Modo oscuro:**
  - el chip y los íconos de «Transcrito» pasan a un azul oscuro suave, en lugar del azul saturado de hoy;
  - la barra de navegación usa el mismo fondo que la pantalla (hoy se ve una franja).
- **Terminaciones que hoy delatan una app sin terminar:**
  - los chips de filtro llegan hasta el borde;
  - tocar un chip activo lo desactiva;
  - «Sin resultados» trae un botón «Quitar filtro»;
  - la barra de navegación se esconde con el teclado;
  - al volver a la Biblioteca no se abre el teclado solo.

**Por qué:** la auditoría encontró que cada uno de estos detalles es pequeño, pero juntos son los que «delatan una app hecha a mano». CRITERIOS §7 ya prohíbe la jerga.

**Referencia:** Material Symbols, las listas de Material 3 y los Ajustes de Android 16.

**Riesgo:** muy bajo. Hay que revisar las descripciones de los íconos para el lector de pantalla.

### #12 Movimiento y vibración con significado (M)

**Qué cambia:**
- **Animaciones con efecto resorte**, como en Material 3 Expressive, en vez de duraciones fijas:
  - un rebote leve cuando algo cambia de posición o de forma;
  - sin rebote en los cambios de color o transparencia;
  - se usa en grabar ↔ detener, ▶ ↔ pausa, las hojas, los avisos inferiores y el chip de la cabecera.
- **Continuidad entre pantallas:**
  - la fila de la Biblioteca se expande hasta convertirse en el detalle;
  - el botón rojo se transforma en el detalle al detener (#6).
- **Respuesta al tocar:** los botones se hunden un poco al presionarlos (se achican a 0,96 y cierran un poco las esquinas).
- **Vibraciones distintas según lo que pasa.** Hoy todo vibra igual.

  | Momento | Tipo de vibración |
  |---|---|
  | Empezar o detener, ★ Marcar, transcripción lista, guardado en 0-Inbox | Confirmación |
  | Error | Rechazo |
  | Interruptores | Encender / apagar |
  | Pasar por un ★ o por un cambio de persona al arrastrar la onda | Tic corto |

  En versiones de Android más antiguas se mantiene la vibración de hoy.
- **Confirmación visible:** al guardar en 0-Inbox, el indicador de carga se transforma en un ✓.
- **Respeta la opción «Quitar animaciones» de Android.**

**Por qué:**
- La auditoría encontró que guardar no tiene ninguna respuesta visual ni física.
- Con una vibración clara al empezar, el doble toque accidental habría sido evidente.
- Es la capa que hace que todo lo anterior se sienta caro.

**Referencia:** el movimiento de Material 3 Expressive (animaciones con efecto resorte) y las recomendaciones de vibración de Android.

**Riesgo:** medio.
- Si todo rebota, cansa.
- Parte de los valores de las animaciones que encontré hay que confirmarlos.
- Hay que probarlo en tu teléfono, porque los motores de vibración baratos se sienten distinto.

---

## Orden propuesto

**Antes, en la versión en curso (0.5.1):** corregir los saltos del detalle (#7, `reload()`). Es un error, no un cambio de diseño, y hoy puede provocar toques equivocados mientras corriges voces.

**0.6.0 — «Del botón rojo a tu segundo cerebro»:** tus dos funciones elegidas, más lo que tiene mejor puntaje y menor esfuerzo.
- **#1** «Guardar en 0-Inbox» como salida principal, guardando la **nota para tu segundo cerebro** en .md.
- **#10** Grabar viva con **★ Marcar momento**.
- **#8** Notificaciones: controles con ★ al grabar, y «lista» que abre la grabación con la acción «Guardar en 0-Inbox».
- **#4** El avance a la vista, en el detalle, el inicio y la Biblioteca.
- **#2** Un solo lenguaje, y **#3** Ajustes con el estado en el inicio.
- **Parte de #7**, lo justo para que quepan las dos funciones nuevas: la tarjeta «Nota para tu segundo cerebro» y la fila «Momentos ★» arriba de la transcripción.
- **Parte de #12:** la tabla de vibraciones. Es pequeña y le da peso físico a ★ y al guardado.
- La hoja «Novedades de la 0.6.0» (ya pendiente) es la forma de presentarte todo esto.

**0.7.0 — «Leer y revisar como un documento»:**
- **#7** completo, **#6**, **#11** (reproductor y recortador), **#9** con la búsqueda dentro de las transcripciones, **#5** y el resto de **#12** (animaciones y transiciones).
- **#5** va aquí porque primero quiero ver el informe de uso de la 0.5.0/0.6.0. Si todavía guardas los nombres varias veces por grabación, sube a una 0.6.x.

**Más adelante:**
- Botón «Grabar» en el panel de ajustes rápidos, widget y accesos directos. No urge: ya llegas a grabar en unos 1 s.
- El estilo de progreso de Android 16 en la notificación, que requiere actualizar la versión de Android con que se compila la app.
- Títulos sugeridos a partir de la transcripción.
- Papelera de 30 días.
- Exportar en formato de subtítulos (SRT).

**Lo que no haremos:**
- Chat o «pregúntale a tus grabaciones»: ya lo haces con otra IA.
- Transcripción en vivo: tiene un costo extra de API.
- Más colores: lo expresivo va en la forma y el movimiento, no en el color.
- Esconder acciones frecuentes en el menú ⋮.

## Cómo sabremos que funcionó (en el próximo informe de soporte)

- «Ver detalles del proceso» durante una transcripción baja de 10 a casi 0, y abres menos el detalle solo para mirar el avance.
- De «lista» a «guardado en 0-Inbox» pasa a 1 paso (desde la notificación) y menos de 10 s.
- «Guardar nombres» baja de casi 3 veces por grabación a 1.
- Entras a Ajustes menos veces de las que grabas.
- Ninguna grabación accidental llega a OpenAI.
- **Registros nuevos que hay que agregar para medir esto:** pausar y reanudar por separado, tocar una frase para reproducir, toques en las notificaciones, y los ★ marcados por grabación.

**Fuentes principales:**
- [Grabadora de Google con Material 3 Expressive](https://9to5google.com/2025/08/23/pixel-recorder-material-3-expressive/)
- [Críticas al rediseño de la Grabadora de Google](https://www.androidauthority.com/pixel-recorder-expessive-redesign-out-now-3590800/)
- [Componentes de Material 3 Expressive](https://supercharge.design/blog/material-3-expressive)
- [Indicadores de progreso de Material 3](https://m3.material.io/components/progress-indicators/guidelines)
- [Notificaciones de progreso de Android 16](https://developer.android.com/about/versions/16/features/progress-centric-notifications)
- [Principios de vibración de Android](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- [Otter](https://zapier.com/blog/otter-ai/)
- [Just Press Record](https://www.macstories.net/reviews/just-press-record-refreshes-its-ios-design-and-adds-powerful-features-to-its-watch-app/)
- [Granola](https://docs.granola.ai/help-center/taking-notes/ai-enhanced-notes)

---

## Ajustes tras la revisión crítica (2026-09-29)

Una segunda revisión contrastó cada idea con el código y los datos. Cambios al plan:

- **Varias cosas ya existen desde la 0.5.0.** Por ejemplo, el tiempo restante, «Bloques listos», la muestra por persona y los nombres que se pueden tocar. El trabajo es hacerlas visibles, no crearlas de nuevo.
- **#4 Avance:** hay que recordar si la bitácora está abierta y mostrar su última línea como titular. Se dice «3 de 5 partes listas» (las partes terminan en cualquier orden). La estimación se hace por tiempo, porque un audio de 12 min o menos es un solo bloque. No se muestra «texto a medida que llega».
- **#1 Guardar en 0-Inbox:** «Compartir» se mantiene a un toque. «Actualizar en 0-Inbox» es de esfuerzo M: hay que guardar el enlace del archivo en Drive y sobrescribirlo sin crear duplicados.
- **#2 Un solo lenguaje:** los separadores de bloque se mantienen, pero se ven solo al corregir voces. La numeración de personas se ordena una vez al terminar, nunca después de corregir. El esfuerzo es M (unos 46 íconos).
- **#6:** la animación del botón al detalle queda fuera. Versión barata: la hoja posterior con el título ya enfocado y «Ver grabación» como botón principal.
- **#8:** si hay voces sin revisar, la acción de la notificación es «Revisar voces»; «Guardar» se ofrece solo cuando no hay voces separadas.
- **#9:** quedan fuera deslizar (sin librerías choca con el gesto atrás) y buscar dentro de las transcripciones (esas consultas ya se hacen con otra IA).
- **#10:** «Deshacer» en «muy corta» queda fuera, porque desde la 0.4.4 ya se descartan las grabaciones de menos de 3 s.
- **#11:** el recortador con la onda se posterga. La onda con ★ en el detalle va junto con #7.
- **#12:** la tabla de vibraciones y los botones que se hunden son de esfuerzo S. La animación resorte requiere una curva propia. La fila que se expande queda fuera.

**Ideas nuevas:**
- **Título sugerido por IA** en la misma llamada que arma la nota. Se ofrece como chip «Usar «…»» y nunca pisa un título que el usuario escribió.
- **Un solo botón principal que avanza** con la grabación: Transcribir → Revisar voces → Guardar en 0-Inbox → ✓ En 0-Inbox · hh:mm. Más un contador «Por guardar».
- **Aclarar «Transcripción lista»:** el chip de inicio dice «Listo para transcribir». Si hay una grabación sin revisar, dice «Revisar · título» y abre esa grabación.

**Arreglos rápidos que pasan a la 0.5.1:**
- `reload()` reacciona solo a cambios de esta grabación y conserva la posición;
- la notificación «lista» abre la grabación y suena;
- Detener se ignora durante el primer segundo;
- se recuerda si la bitácora está abierta;
- salir de Importar no cancela;
- nuevo texto del chip de inicio.

**Maquetas:** el lienzo «Voz local · Propuesta PRO» (Artifact) tiene 6 pantallas: Inicio, Grabando, Transcribiendo, Transcrito, Nombrar voces y Biblioteca.
