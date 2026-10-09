# Verbapp (antes Voz local) para Android

Grabadora y transcriptor para Android 8 o posterior: graba sin internet y transcribe con **OpenRouter**, con una sola clave. Versión **0.9.6**, en prueba cerrada de Google Play (paquete `cl.verbapp.app`), en español, inglés, portugués y alemán. Licencia MIT. Letra Outfit incluida bajo la SIL Open Font License (`app/src/main/assets/fonts/OFL-Outfit.txt`).

[Historial](CHANGELOG.md) · [Google Play](docs/play-store/README.md) · [Auditoría 0.9.5](docs/auditoria-0.9.5.md) · [Criterios de diseño](docs/diseno/CRITERIOS.md)

<img src="docs/diseno/capturas/01-grabar.png" width="200"> <img src="docs/diseno/capturas/04-biblioteca.png" width="200"> <img src="docs/diseno/capturas/06-detalle-transcrito.png" width="200">

## Qué hace

- Tres pestañas en una barra flotante: **Grabar** (botón principal, importar, última grabación y «Tu semana»), **Biblioteca** (búsqueda, filtros por estado, agrupación por fecha) y **Ajustes**. Identidad verde con tarjetas de vidrio y letra Outfit, en modo claro y oscuro.
- **Bienvenida** la primera vez: tu nombre, micrófono, permiso para transcribir con el teléfono bloqueado y tu clave de OpenRouter.
- **Tus métricas** (Ajustes): tiempo hablado, rachas, a qué hora grabas, con quién conversas y gasto por modelo (cobrado y estimado). Se calculan en el teléfono.
- Graba sin internet con un toque, con pausa y servicio de micrófono para continuar con la pantalla bloqueada. Onda en vivo según el volumen real.
- Desde la 0.9.6 la grabación sobrevive a un corte: se graba en AAC (ADTS) y se pasa a M4A al detener; si Android cierra la app, se acaba la batería o se quita el permiso, lo grabado se recupera al abrirla. Avisa si el micrófono queda mudo o falta espacio, y pregunta antes de transcribir una grabación casi en silencio o cara.
- Importa archivos de grabadoras o usa **Compartir → Verbapp** desde WhatsApp/otras apps que compartan audio. Convierte a AAC/M4A con los decodificadores de Android. M4A, MP3, WAV y OGG/Opus dependen del soporte del teléfono y del archivo.
- Recorte no destructivo: elige inicio y final con dos manijas (o en segundos) y escucha el tramo. Se crea una grabación nueva; el archivo original queda intacto. No es un editor de ondas completo. Mantén abierta la pantalla mientras se prepara el archivo.
- Título opcional mientras grabas o al terminar; resumen al guardar con el siguiente paso.
- Pantalla de detalle: reproductor (±15 s, velocidad), estado y transcripción. Tocar el tiempo de una intervención reproduce desde ahí.
- Transcripción manual o automática al guardar/importar, en primer plano (sigue con el teléfono bloqueado). Detalles del proceso con progreso, condiciones de red/cargador y bitácora de cada paso.
- Separación de hablantes con un modelo compatible: Persona 1, Persona 2…; nombres editables en todas sus intervenciones.
- Copiar texto, compartir texto o `.txt`, y **Guardar en…** cualquier ubicación (incluida Google Drive si su app está instalada). Compartir audio M4A.
- Carpeta elegida mediante el selector de Android, con audio, información y transcripción `.txt`/`.json`. Las carpetas por grabación usan un identificador estable; el título está en `informacion.txt`.
- Diagnóstico local limitado, informe exportable y conteos de acciones. Sin telemetría remota.
- Sin inicio de sesión de Google: para usar Drive se elige su carpeta en el selector de Android. Desde la 0.9.2 usa Google Play solo para avisar de versiones nuevas (`app-update`).

## Instalar y configurar

1. Hazte tester con el enlace de la prueba cerrada (`https://play.google.com/apps/testing/cl.verbapp.app`) e instala Verbapp desde Google Play.
2. La bienvenida (o **Ajustes → Conexión con la IA**) pide tu clave de [OpenRouter](https://openrouter.ai/keys). Se cifra con Android Keystore y deja de mostrarse al guardarla.
3. **Comprobar conexión** confirma que la clave funciona y muestra el saldo de la cuenta. No prueba la carga de audio ni la calidad.
4. Elige cuándo transcribir. Por defecto es manual, solo red no medida, sin exigir cargador. Si una transcripción espera Wi-Fi, su detalle y su notificación ofrecen **«Usar datos móviles ahora»** solo para esa grabación. Android puede retrasar las tareas automáticas; también esperan si hay poca batería o una grabación en curso. En Android 14 o más, lo que pides transcribir con un toque sigue aunque cierres la app. Para transcribir con el teléfono bloqueado, permite a Verbapp usar batería en segundo plano (en vivo y otras marcas hay un paso extra; la bienvenida lo explica).
5. Graba/importa, entra a **Biblioteca → Transcribir audio** y luego abre el resultado.
6. Para conservar copias fuera de la app, selecciona una carpeta del **almacenamiento del dispositivo** en Ajustes. Android permite crearla y ponerle nombre. No elijas un proveedor de nube si deseas mantener todo local.

Para probar nombres sin API usa **Ajustes → Probar edición de hablantes**. Este texto está rotulado como demostración.

## OpenRouter y modelos

Desde la 0.8.0 todo pasa por **OpenRouter**: una sola clave y un solo saldo para transcribir y para la nota. Al actualizar desde una versión anterior, la app pasa a OpenRouter sola; las claves antiguas quedan guardadas, sin uso. Detalle técnico en `docs/PLAN-openrouter.md`.

- **Transcripción:** `POST /api/v1/audio/transcriptions`. El audio se envía en FLAC mono de 16 kHz (WAV si el teléfono no tiene codificador FLAC). Si OpenRouter lo rechaza por tamaño (413), se reintenta en mitades.
- **Modelos:** la lista sale de `GET /api/v1/models?output_modalities=transcription` y se actualiza sola una vez al día. Hay dos elecciones: un modelo **con voces** (separa hablantes) y uno **solo texto**. «Automático» usa el recomendado por Verbapp y no cambia de modelo a mitad de una transcripción.
- **Voces conocidas:** OpenRouter no las tiene, así que la app antepone tus muestras de voz al audio (con 1 s de silencio entre ellas), asocia la voz de cada muestra a su nombre y luego quita esa zona del texto. Las muestras se cobran como audio.
- **Nota para tu segundo cerebro:** una familia (Claude, GPT o Gemini) con alias `~…-latest`, siempre la versión más nueva. Solo se envía el texto de la transcripción.
- **Costo:** cuando OpenRouter lo informa, se guarda el costo real de cada transcripción y nota; si no, se muestra un estimado con «≈».

La transcripción envía audio a OpenRouter y al modelo elegido, y genera cargos en tu cuenta. La grabación y la importación son locales.

Al transcribir eliges si separar voces. Los audios largos se dividen en bloques cortados en pausas y enviados de a 3 en paralelo. Con separación de voces, el primer bloque aporta muestras de voz (máx. 4 personas) para reconocer a las mismas personas en los demás; si alguien no coincide, puede aparecer con otra etiqueta y basta con darle el mismo nombre. Los bloques se copian sin recodificar y se conservan entre intentos, así un reintento continúa donde quedó. Los modelos de texto no proporcionan tiempos precisos por intervención; se muestra el comienzo del bloque. Revisa ruido, solapamientos y límites de bloques.

Se reutilizan bloques completados con el mismo proveedor/modelo/idioma. Un cambio de configuración invalida esos resultados parciales. Una interrupción después del envío pero antes de guardar la respuesta puede repetir el bloque y su cargo. No se reemplazan transcripciones completas ni nombres ya editados al reintentar.

## Errores e informes de soporte

El HTTP 400 reportado en la versión anterior no se atribuye automáticamente al tamaño. La app ahora conserva código, parámetro y referencia de solicitud cuando el servidor los entrega; clasifica indicios de formato, duración, modelo, cuota o autenticación. No guarda el cuerpo completo del error, que puede contener información privada. Los errores permanentes requieren intervención; los temporales se reintentan hasta cinco veces.

**Ajustes → Compartir informe de soporte** genera un `.txt` con fecha y hora en el nombre (`Verbapp-soporte-AAAA-MM-DD-HHMM.txt`) y versión/Android, frecuencia de acciones y secuencia de etapas, tiempos, tamaños, reintentos y códigos. El último fallo no controlado incluye la clase de excepción y las ubicaciones del código de la app, sin el mensaje de la excepción.

Los eventos se guardan solo en este teléfono: dos archivos de aproximadamente 2 MB cada uno, con hasta los últimos 30 días disponibles en el informe. No se registran claves, títulos, rutas, audio ni texto transcrito. El informe solo se envía si eliges compartirlo. Puedes borrar los registros desde Ajustes.

La validación automática usa respuestas controladas: **no demuestra una transcripción real ni resuelve por sí sola el HTTP 400 de tu cuenta**. Para confirmarlo, prueba un audio real con tu clave y comparte el informe si vuelve a fallar.

## Tus archivos

La copia principal está en el almacenamiento privado de la app. Desinstalar o borrar sus datos elimina esa copia. Actualizar con un APK de la misma firma conserva los datos. La carpeta elegida por el usuario conserva sus copias al desinstalar; borrar una grabación en la biblioteca no borra esas copias.

La importación acepta hasta 1 GB y requiere espacio libre para preparar la copia. El soporte de códecs varía por teléfono. Si Android mata la app durante una conversión, vuelve a importar el original. Forzar el cierre/apagar el dispositivo durante una grabación puede dejar un audio incompleto.

## Desarrollo

Java 17, Android SDK 36 (targetSdk 36), minSdk 26, AGP 8.9.2, Gradle 8.11.1. Sin dependencias de ejecución externas. Abre el proyecto en Android Studio o configura `ANDROID_HOME` y ejecuta:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
```

En Linux/macOS: `bash gradlew assembleDebug assembleDebugAndroidTest lintDebug`.

En el computador de desarrollo, `build-apk.ps1` usa las herramientas portables de `.tools/` y copia el APK a `entrega/`. `.tools`, archivos de usuario, claves y compilaciones no se versionan. `Abrir-grabadora.ps1` abre el emulador local preparado. La automatización de GitHub compila y publica artefactos de prueba en cada cambio.

Para ejecutar la regresión **solo en un emulador de prueba** con la app instalada y permisos de micrófono/notificaciones:

```text
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e long false cl.verbapp.app.test/cl.vozlocal.app.RecorderSmokeTest
```

Busca `PASS` en el resultado; el código de salida de adb no basta. La prueba crea una grabación de demostración y usa APIs simuladas. Comprueba grabación/pausa/bloqueo, credenciales cifradas, contratos API, voces, división, conversión/recorte y diagnóstico. Prueba además micrófono, batería, carpetas y compartir con apps reales en tu teléfono.

Los APK de Releases son compilaciones de desarrollo para pruebas. Conserva la firma local para futuras actualizaciones. Los APK generados por GitHub tienen otra firma de desarrollo; no sustituyen una instalación de Releases. Para distribución estable, configura una firma de lanzamiento privada y respáldala fuera del repositorio.

### Publicar en Google Play (0.9.0)

El paquete publicado es `cl.verbapp.app` (el código sigue en `cl.vozlocal.app`). La clave de subida vive solo en el computador de desarrollo, en `.tools/signing/` (fuera de git): `upload-keystore.jks` y `keystore.properties` con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`. **Respáldala** (por ejemplo, en un gestor de contraseñas): sin ella no se pueden publicar actualizaciones hasta pedir a Google que la cambie. Con ese archivo presente:

```powershell
.\gradlew.bat bundleRelease
```

deja el AAB firmado en `app/build/outputs/bundle/release/app-release.aab`, listo para subir a Play Console (Google firma la versión final con Play App Signing). `-e long false` salta la prueba de importación de una hora.
