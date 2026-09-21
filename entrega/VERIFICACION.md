# Verificación de Voz local 0.1.0

Fecha: 20 de septiembre de 2026.

- `assembleDebug`: correcto.
- `lintDebug`: sin errores; advertencias de localización, política de backup y recomendaciones de almacenamiento/wakelock. Interfaz intencionalmente en español.
- Firma APK: validada con `apksigner verify --verbose`, esquema v2.
- Instalación: correcta en emulador Android 15 / API 35 x86_64.
- Prueba instrumental `RecorderSmokeTest`: PASS.

La prueba verifica inicio de grabación, exclusión de la grabación activa en la biblioteca, pausa sin aumento del contador, reanudación, continuidad al ir al inicio de Android y apagar la pantalla, detención, M4A legible, duración del archivo, persistencia de título, acceso de lectura mediante el proveedor de archivos, rechazo de escritura, recuperación sin metadatos, borrado y formato de duración superior a una hora.

Se inspeccionó visualmente la pantalla de biblioteca. No se detectaron excepciones de AndroidRuntime en el emulador durante la prueba.

El emulador se ejecutó sin audio del anfitrión. Esto valida el flujo y el contenedor de audio; no verifica la calidad de una voz real ni las restricciones de batería específicas de un teléfono físico. Tampoco se ha probado en todas las versiones de Android admitidas.

APK SHA-256:

`FEBC5337E6B50EEE6A5AA4AE2714272BB911337C5A012C1F8F388CBB54E2E851`

## Repetir la prueba en un emulador descartable

Construir `assembleDebugAndroidTest`, instalar el APK principal con permisos (`adb install -g`), instalar `app-debug-androidTest.apk` y ejecutar:

```text
adb shell am instrument -w cl.vozlocal.app.test/cl.vozlocal.app.RecorderSmokeTest
```

La prueba crea una grabación de muestra. No ejecutarla como prueba sobre datos personales en un teléfono de uso diario.
