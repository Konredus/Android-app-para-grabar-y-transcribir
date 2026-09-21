# Verificación 0.2.0

20 de septiembre de 2026.

- APK compilado e instalado como actualización en el emulador Android 15, conservando las grabaciones anteriores.
- `assembleDebug` y `lintDebug`: correctos, cero errores y 17 advertencias (localización, escrituras síncronas de preferencias y recomendaciones de wakelock/espacio).
- Prueba instrumental: PASS. Regresión de grabadora, título previo, cifrado/lectura/borrado de clave, condiciones de red y cargador, solicitud multipart de diarización, nombres persistentes en todas las intervenciones, separación de identidades entre bloques, clasificación de errores, protocolo de carga de Drive con ID estable y división en M4A válidos.
- Pantalla Configuración revisada visualmente en el emulador. Sin excepciones AndroidRuntime durante la prueba.

Las pruebas de OpenAI y Drive usan un transporte simulado dentro del APK de pruebas. La aplicación normal usa los endpoints reales mediante HTTPS. No se ha ejecutado una transcripción real ni una autorización/carga real de Drive: no se recibieron credenciales del usuario ni se configuró su proyecto OAuth. El emulador actual no incluye Google Play Services.

El ejemplo de voces de la interfaz está claramente marcado como demostración y no se sincroniza. Los nombres se pueden cambiar y persistir sin una clave.

SHA-256 del APK:

`E5C3D903FC94794A29818D050549F2519925CA71A294761A4030AD17AFB33505`
