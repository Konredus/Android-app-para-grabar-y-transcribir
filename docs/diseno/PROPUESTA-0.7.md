# Propuesta 0.7.0 · Verbapp, «Bosque de vidrio»

> **Pedido (29-09-2026):** «Una nueva versión donde SOLO cambie la UX/UI en base a esta imagen. El color me gusta este mismo verde y todo así, con la mismísima estética. Dónde están los botones puede cambiar según lo que ya tenemos. Sé creativo, inventa, prioriza que se vea MUY estético, bonito, llamativo y con ganas de usarlo».
> **Nombre elegido:** Verbapp (del latín *verba volant, scripta manent*: «las palabras vuelan, lo escrito permanece»). **Lema:** «Tus palabras, para siempre».
> **Tipografía:** Outfit (Google Fonts, licencia OFL), incluida en `app/src/main/assets/fonts/`.
> **Referencia:** `docs/diseno/referencias/0.7-referencia-convora.webp` (tres pantallas de la app «Convora»: inicio, grabando y nombrar la grabación).

## 1. Qué tomamos de la referencia

| Rasgo | En la referencia | En Verbapp |
|---|---|---|
| Fondo | Blanco arriba con una grilla de puntitos; degradado a un verde bosque (#3E7262 aprox.) abajo | `Glass.Backdrop`: **intenso** en Grabar y **suave** (verde apenas insinuado) en Biblioteca, Detalle y Ajustes, para que las listas largas se lean sobre claro |
| Tarjetas | Vidrio: blanco translúcido, borde blanco fino y esquinas muy redondas | `ui.card()` / `ui.group()` → `AppTheme.glass()` (24 dp) |
| Botón principal | Píldora **negra** («Save») | `Style.PRIMARY` = tinta (#121815); en oscuro, blanco menta |
| Botón secundario | Píldora gris clara («Cancel») | `Style.SECONDARY` |
| Controles sobre verde | Píldoras de vidrio con texto blanco («Stop», «Pause») | `Style.VIVID` |
| Botón de micrófono | Círculo verde con halo suave, flanqueado por ondas grises | `RecordButton` en verde de marca (`p.brand`) |
| Tipografía | Geométrica (tipo Gilroy); cronómetro enorme; títulos con la última palabra destacada en un recuadro menta | Outfit en títulos, botones, etiquetas y números; `ui.highlightLast()` |
| Navegación | Cápsula flotante con botones redondos; el activo, negro | `BottomNav`: cápsula de vidrio; el activo se abre en una píldora de tinta con ícono y nombre |
| Botones de ícono | Círculos blancos con sombra suave (volver, campana) | `ui.glassButton()` / `Screen.barButton()` |
| Marca | Barras de onda + destello, nombre en cursiva | `ui.brand()` (Glass.BrandMark + «Verbapp» en Outfit inclinado) e ícono de la app nuevo |
| Indicador «Grabando» | Punto naranja rojizo | `p.record` (#E4572E); el rojo ya no es el color del botón de grabar |

Lo que **no** copiamos: el selector «Default mic» (no hay elección de micrófono; en ese lugar va el título de la grabación) ni el avatar (no hay cuentas).

## 2. Tokens nuevos (fuente única: `AppTheme.java`)

- **Verde de marca** `#2F6B58` (primary y brand), profundo `#1E4D3F`, menta `#D4E9DF` (primaryContainer), destacado `#D8EDE3`.
- **Tinta** `#121815` con texto blanco: lo principal de cada pantalla. En oscuro se invierte (`#E3F0EA` con texto casi negro).
- **Vidrio** `#C7FFFFFF` + borde `#F2FFFFFF` (claro); `#17FFFFFF` + `#26FFFFFF` (oscuro).
- **Degradado**: arriba `#FFFFFF`, medio `#F1F6F4`, abajo `#3E7262` (intenso) o `#D3E4DC` (suave). Oscuro: `#0D1311` → `#1F4A3D`.
- **Formas** más redondas: control 16 · tarjeta 24 · hoja 32 · píldora.
- **Escala tipográfica**: DISPLAY_LARGE 68 (cronómetro) · DISPLAY_MEDIUM 44 · DISPLAY_SMALL 36 (saludo, título al grabar) · HEADLINE 32/28/24 · TITLE 22/16/14 · ITEM 16 (filas) · LABEL 14/12/11: todo en Outfit. BODY 16/14/12 sigue en Roboto (transcripciones y explicaciones, porque se leen mejor).
- **Números** que cambian (cronómetro, duraciones): `Ui.tabular()` (Outfit trae cifras de ancho fijo, «tnum»).
- **Material You** (colores del fondo de pantalla) se retira: Verbapp tiene identidad propia.

## 3. Pantallas

### 3.1 Grabar, en reposo (fondo intenso, barra flotante visible)
1. **Cabecera**: logo + «Verbapp» a la izquierda; a la derecha, un botón redondo de vidrio con el **estado** (llave si falta la clave, anillo girando si transcribe, alerta con punto si algo falló, documento si hay algo listo para revisar, ✓ si todo va bien). Tocarlo hace lo mismo que el chip de estado de 0.6.
2. **Saludo**: «Buenas noches,» (Outfit 16, gris) y debajo el **nombre** en grande (DISPLAY_SMALL), tomado de «Mi voz» (`Voices.name`). Sin nombre: «¿Qué grabamos hoy?» con «hoy?» destacado. Si algo pide atención, debajo va una píldora de vidrio con el texto del estado («Transcribiendo «Reunión» · 2 de 4»).
3. **Centro**: el botón de micrófono **verde** (76 dp, halo de dos anillos suaves que respiran) entre dos ondas decorativas grises que se desvanecen hacia los bordes. Debajo, «Toca para grabar · funciona sin internet» y una píldora de vidrio chica «Importar audio».
4. **Última grabación** (tarjeta de vidrio): el siguiente paso de 0.6 con su botón (Transcribir → Revisar voces → Guardar en 0-Inbox → ✓).
5. **Tu semana** (tarjeta de vidrio con tres datos, `ui.stat`): grabaciones, tiempo grabado y notas de los últimos 7 días. Reemplaza a «Recientes» (la Biblioteca queda a un toque). En pantallas bajas se oculta primero.

### 3.2 Grabar, grabando (pantalla completa: sin barra de pestañas)
- Arriba: ← redondo de vidrio («Ir a Biblioteca · la grabación sigue») y el logo a la derecha.
- Píldora de vidrio con ✎ y el **título** («Añadir título» si no tiene): en el lugar del «Default mic».
- **Título grande** centrado (DISPLAY_SMALL, hasta 3 líneas) con la última palabra destacada. Sin título: «Nueva grabación».
- «● Grabando» (punto naranja que late) o «● En pausa».
- **Cronómetro enorme** (DISPLAY_LARGE, cifras fijas).
- **Onda en vivo** sobre el verde: barras blancas que nacen en un cabezal vertical (a ~62 % del ancho) y avanzan hacia la izquierda, con una sombra en degradado detrás del cabezal; a la derecha, marcas tenues de lo que falta. Las ★ se ven como estrellitas blancas sobre su barra. En pausa, todo se atenúa.
- **Controles** abajo: [■ Detener] [❚❚ Pausa] (píldoras de vidrio) y ★ redondo con contador. Detener ignora el primer segundo (0.6). Mantener ★ = anotar una palabra.
- Línea tenue: «Puedes bloquear el teléfono: la grabación sigue. Toca ★ para marcar un momento.» (o el conteo de ★).

### 3.3 Nombra esta grabación (hoja al detener)
- Título «Nombra esta grabación» con ✕ redondo (cierra y guarda lo escrito, igual que antes).
- **Mini reproductor**: ▶ redondo de tinta, la onda de lo recién grabado (la que se dibujó en vivo, sin volver a leer el audio) y «● 12:48».
- Campo con la fecha fija delante (si está activa) y el nombre seleccionado con el teclado abierto.
- Botones lado a lado: [Listo] gris · [Ver grabación] tinta.

### 3.4 Biblioteca (fondo suave)
Título grande «Biblioteca» + resumen; búsqueda en una píldora blanca; filtros como píldoras (activo = tinta); secciones por fecha en verde; filas dentro de grupos de vidrio. Ícono inicial en un círculo de vidrio (verde si pide acción, anillo de avance si transcribe). Mientras grabas, arriba va una cápsula de tinta «● 12:34 · Grabando · Detener».

### 3.5 Detalle (fondo suave)
Barra con ← y ⋯ redondos de vidrio. Título grande (Outfit) + fecha/duración. Ficha, nota, momentos ★ y transcripción en tarjetas de vidrio. Reproductor fijo abajo en una cápsula de vidrio: onda (verde lo escuchado), ▶ verde de marca, tiempos con cifras fijas, ±15, velocidad y el botón que avanza (tinta).

### 3.6 Ajustes (fondo suave)
Título grande, tarjeta de estado con el logo, grupos de vidrio, subtítulos verdes. Se quita «Colores de tu fondo de pantalla». Pie: «Verbapp 0.7.0 · Software libre · Licencia MIT».

### 3.7 Hojas
Blancas, esquinas de 32 dp, título en Outfit, lo de atrás desenfocado (Android 12+). Dos botones cortos lado a lado; si no caben, apilados.

### 3.8 Ícono y nombre
Ícono adaptable: degradado verde con brillo arriba a la izquierda, cuatro barras de onda blancas y un destello menta. Nombre visible «Verbapp». El paquete interno (`cl.vozlocal.app`) **no cambia**: así la actualización se instala encima y no se pierde ninguna grabación.

## 4. Lo que NO cambia (regla de la versión)
Solo cambia la interfaz. Se conservan todas las capacidades de 0.6.0: grabar, pausar, ★ (también desde la notificación), título al grabar, importar y recortar, transcribir con o sin voces, voces conocidas, correcciones con Deshacer, nota para el segundo cerebro, volver a transcribir, guardado rápido y «Actualizar», copiar/compartir/.txt/.md, carpeta de copias, informe de soporte, novedades, modo oscuro y accesibilidad (lector de pantalla, «Quitar animaciones», áreas táctiles de 48 dp).
