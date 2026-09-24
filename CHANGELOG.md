# Historial de versiones

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
