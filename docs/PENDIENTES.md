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

## Etapa 3 de `PLAN-voces.md` (opcional, sin fecha)
- Voces conocidas de otras personas (p. ej. la Fran) y elegir «¿Quiénes hablan?» al transcribir (máx. 4).
- Volver a transcribir (con «Mi voz», separando voces otra vez o solo texto), guardando la versión anterior.
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
