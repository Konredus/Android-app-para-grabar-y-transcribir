# Pendientes para la próxima versión (0.4.3)

> Lista de mejoras acordadas con el usuario. Al implementar una, muévela al CHANGELOG y, si es una decisión de diseño, regístrala en `diseno/CRITERIOS.md`.

## Pedidas por el usuario (2026-09-23)

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

## Propuestas pendientes de confirmar
- Decir **"OpenAI está transcribiendo el bloque X"** en vez de "esperando respuesta del proveedor" cuando el proveedor es OpenAI (se entiende mejor dónde está la demora).
- Agregar una sección **"Lecciones"** en `diseno/CRITERIOS.md` con los aprendizajes de las iteraciones 0.3 → 0.4.2.
- Publicar las versiones en **GitHub Releases** (hoy la última publicada es la 0.2.0).

## Para medir con la primera transcripción larga real (0.4.2)
- Tiempo por bloque ("Bloque X de 10 listo · tardó Y") y tiempo total, para saber si el paralelo aceleró de verdad y si conviene cambiar el tamaño de los bloques o cuántos se envían a la vez.
- Si OpenAI aceptó las muestras de voz o se reenvió sin ellas (queda en la bitácora).
- "Buscando pausas" tardó 32 s en un audio de 51 min: evaluar acotar la búsqueda (menos margen alrededor de cada corte) para que sea más rápida.
