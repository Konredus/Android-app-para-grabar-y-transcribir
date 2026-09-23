# Voz local para Android

Grabadora y transcriptor local para Android 8 o posterior. Versión **0.4.1** (preliminar), licencia MIT.

[Descargar APKs](https://github.com/Konredus/Android-app-para-grabar-y-transcribir/releases) · [Historial](CHANGELOG.md) · [Criterios de diseño](docs/diseno/CRITERIOS.md)

<img src="docs/diseno/capturas/01-grabar.png" width="200"> <img src="docs/diseno/capturas/04-biblioteca.png" width="200"> <img src="docs/diseno/capturas/06-detalle-transcrito.png" width="200">

## Qué hace

- Tres pestañas: **Grabar** (botón principal, importar y recientes), **Biblioteca** (búsqueda, filtros por estado, agrupación por fecha) y **Ajustes**. Diseño Material 3 con modo claro, oscuro y colores del fondo de pantalla (Material You, Android 12+).
- Graba sin internet con un toque, con pausa y servicio de micrófono para continuar con la pantalla bloqueada. Onda en vivo según el volumen real.
- Importa archivos de grabadoras o usa **Compartir → Voz local** desde WhatsApp/otras apps que compartan audio. Convierte a AAC/M4A con los decodificadores de Android. M4A, MP3, WAV y OGG/Opus dependen del soporte del teléfono y del archivo.
- Recorte no destructivo: elige inicio y final con dos manijas (o en segundos) y escucha el tramo. Se crea una grabación nueva; el archivo original queda intacto. No es un editor de ondas completo. Mantén abierta la pantalla mientras se prepara el archivo.
- Título opcional mientras grabas o al terminar; resumen al guardar con el siguiente paso.
- Pantalla de detalle: reproductor (±15 s, velocidad), estado y transcripción. Tocar el tiempo de una intervención reproduce desde ahí.
- Transcripción manual o automática al guardar/importar, en primer plano (sigue con el teléfono bloqueado). Detalles del proceso con progreso, condiciones de red/cargador y bitácora de cada paso.
- Separación de hablantes con un modelo compatible: Persona 1, Persona 2…; nombres editables en todas sus intervenciones.
- Copiar texto, compartir texto o `.txt`, y **Guardar en…** cualquier ubicación (incluida Google Drive si su app está instalada). Compartir audio M4A.
- Carpeta elegida mediante el selector de Android, con audio, información y transcripción `.txt`/`.json`. Las carpetas por grabación usan un identificador estable; el título está en `informacion.txt`.
- Diagnóstico local limitado, informe exportable y conteos de acciones. Sin telemetría remota.
- Sin inicio de sesión de Google: para usar Drive se elige su carpeta en el selector de Android. No necesita servicios de Google Play.

## Instalar y configurar

1. Descarga el APK de Releases, ábrelo desde Archivos y permite la instalación si Android lo solicita.
2. En **Ajustes**, selecciona proveedor y modelo. Ingresa tu propia clave. Se cifra con Android Keystore y deja de mostrarse al guardarla.
3. **Comprobar conexión** consulta los metadatos del modelo. No prueba la carga de audio, saldo ni calidad. Algunos servidores compatibles no implementan esa consulta.
4. Elige cuándo transcribir. Por defecto es manual, solo red no medida, sin exigir cargador. Android puede retrasar la tarea; también espera si hay poca batería o una grabación en curso.
5. Graba/importa, entra a **Biblioteca → Transcribir audio** y luego abre el resultado.
6. Para conservar copias fuera de la app, selecciona una carpeta del **almacenamiento del dispositivo** en Ajustes. Android permite crearla y ponerle nombre. No elijas un proveedor de nube si deseas mantener todo local.

Para probar nombres sin API usa **Ajustes → Probar edición de hablantes**. Este texto está rotulado como demostración.

## Proveedores y modelos

La clave autentica la cuenta; el modelo se selecciona por separado.

| Integración | Transcripción | Separación de voces |
| --- | --- | --- |
| OpenAI `gpt-4o-transcribe-diarize` | Sí | `diarized_json` |
| OpenAI `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`, `whisper-1` | Sí | No en esta integración |
| Servidor personalizado HTTPS | Si implementa `/audio/transcriptions` con multipart y respuesta JSON `{text: ...}` | Solo si implementa también `diarized_json` y `chunking_strategy` |

No se promete compatibilidad universal con APIs de chat. Claude u otro proveedor necesitan una API de audio apropiada o un adaptador específico. Cambiar de URL personalizada elimina la clave anterior para evitar enviarla a un servidor distinto accidentalmente.

Los parámetros siguen la [documentación de transcripción de OpenAI](https://developers.openai.com/api/docs/guides/speech-to-text). La transcripción envía audio al proveedor elegido y puede generar cargos en tu cuenta. La grabación y la importación son locales.

Los audios de más de 15 minutos o de tamaño grande se dividen por duración y tamaño en muestras AAC. No hay reconocimiento de identidad entre bloques: una persona puede tener varias etiquetas; asígnales el mismo nombre si corresponde. Los modelos de texto no proporcionan tiempos precisos por intervención; se muestra el comienzo del bloque. Revisa ruido, solapamientos y límites de bloques.

Se reutilizan bloques completados con el mismo proveedor/modelo/idioma. Un cambio de configuración invalida esos resultados parciales. Una interrupción después del envío pero antes de guardar la respuesta puede repetir el bloque y su cargo. No se reemplazan transcripciones completas ni nombres ya editados al reintentar.

## Errores e informes de soporte

El HTTP 400 reportado en la versión anterior no se atribuye automáticamente al tamaño. La app ahora conserva código, parámetro y referencia de solicitud cuando el servidor los entrega; clasifica indicios de formato, duración, modelo, cuota o autenticación. No guarda el cuerpo completo del error, que puede contener información privada. Los errores permanentes requieren intervención; los temporales se reintentan hasta cinco veces.

**Ajustes → Compartir informe de soporte** genera un `.txt` con versión/Android, frecuencia de acciones y secuencia de etapas, tiempos, tamaños, reintentos y códigos. El último fallo no controlado incluye la clase de excepción y las ubicaciones del código de la app, sin el mensaje de la excepción.

Los eventos se guardan solo en este teléfono: dos archivos de aproximadamente 2 MB cada uno, con hasta los últimos 30 días disponibles en el informe. No se registran claves, títulos, rutas, audio ni texto transcrito. El informe solo se envía si eliges compartirlo. Puedes borrar los registros desde Ajustes.

La validación automática usa respuestas controladas: **no demuestra una transcripción real ni resuelve por sí sola el HTTP 400 de tu cuenta**. Para confirmarlo, prueba un audio real con tu clave y comparte el informe si vuelve a fallar.

## Tus archivos

La copia principal está en el almacenamiento privado de la app. Desinstalar o borrar sus datos elimina esa copia. Actualizar con un APK de la misma firma conserva los datos. La carpeta elegida por el usuario conserva sus copias al desinstalar; borrar una grabación en la biblioteca no borra esas copias.

La importación acepta hasta 1 GB y requiere espacio libre para preparar la copia. El soporte de códecs varía por teléfono. Si Android mata la app durante una conversión, vuelve a importar el original. Forzar el cierre/apagar el dispositivo durante una grabación puede dejar un audio incompleto.

## Desarrollo

Java 17, Android SDK 35, minSdk 26, AGP 8.9.2, Gradle 8.11.1. Sin dependencias de ejecución externas. Abre el proyecto en Android Studio o configura `ANDROID_HOME` y ejecuta:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
```

En Linux/macOS: `bash gradlew assembleDebug assembleDebugAndroidTest lintDebug`.

En el computador de desarrollo, `build-apk.ps1` usa las herramientas portables de `.tools/` y copia el APK a `entrega/`. `.tools`, archivos de usuario, claves y compilaciones no se versionan. `Abrir-grabadora.ps1` abre el emulador local preparado. La automatización de GitHub compila y publica artefactos de prueba en cada cambio.

Para ejecutar la regresión **solo en un emulador de prueba** con la app instalada y permisos de micrófono/notificaciones:

```text
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cl.vozlocal.app.test/cl.vozlocal.app.RecorderSmokeTest
```

Busca `PASS` en el resultado; el código de salida de adb no basta. La prueba crea una grabación de demostración y usa APIs simuladas. Comprueba grabación/pausa/bloqueo, credenciales cifradas, contratos API, voces, división, conversión/recorte y diagnóstico. Prueba además micrófono, batería, carpetas y compartir con apps reales en tu teléfono.

Los APK de Releases son compilaciones de desarrollo para pruebas. Conserva la firma local para futuras actualizaciones. Los APK generados por GitHub tienen otra firma de desarrollo; no sustituyen una instalación de Releases. Para distribución estable, configura una firma de lanzamiento privada y respáldala fuera del repositorio.
