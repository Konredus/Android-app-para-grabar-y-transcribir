# Voz local para Android

Grabadora personal para Android 8.0 o posterior. Versión 0.2.0: grabación local, configuración, transcripción con separación de voces y sincronización con Google Drive.

## Instalar

Descarga el APK de la sección [Releases](https://github.com/Konredus/Android-app-para-grabar-y-transcribir/releases). Los instaladores no se guardan en el historial del código.

1. Copia `entrega/Voz-local-0.2.0.apk` a tu teléfono (por USB o tu método habitual).
2. Abre el archivo desde la app Archivos de Android.
3. Si Android lo pide, permite instalar aplicaciones desde esa app de archivos y vuelve a abrir el APK.
4. Instala **Voz local**, ábrela y toca **Grabar audio**.
5. Acepta el permiso del micrófono. Las notificaciones permiten ver y detener una grabación con la pantalla apagada.

El APK es una compilación de desarrollo firmada, para instalación personal; no está publicado en Play Store.

## Incluye

- Audio AAC mono en contenedor M4A, guardado en el almacenamiento privado de la app.
- Grabación sin internet y servicio de micrófono en primer plano para continuar al bloquear la pantalla.
- Pausar, continuar y detener/guardar; contador que excluye las pausas.
- Biblioteca persistente, búsqueda por título y cambio de nombre.
- Reproducción con pausa y desplazamiento dentro del audio.
- Compartir audio con otras aplicaciones mediante permiso temporal de lectura.
- Borrado con confirmación. Archivos de audio separados de sus metadatos; si faltan metadatos, el audio sigue apareciendo en la biblioteca.
- Interfaz en español, controles nativos y soporte para tamaño de letra del sistema.
- Título opcional antes de grabar, configurable desde Configuración.
- Clave personal de OpenAI cifrada con AES-GCM y Android Keystore; botones para guardarla, verificar acceso al modelo y eliminarla.
- Transcripción con `gpt-4o-transcribe-diarize`, respuesta `diarized_json` y detección de voces. Español o detección automática de idioma.
- Texto por intervenciones con tiempos y etiquetas Persona 1, Persona 2… Edición persistente del nombre de cada hablante en todas sus intervenciones.
- Cola persistente mediante JobScheduler: manual o automática para grabaciones nuevas, red no medida o datos móviles, opcionalmente solo cargando; espera con batería baja.
- Autorización nativa de Google, selección de carpetas autorizadas o creación de una carpeta propia, y carga de texto o texto más audio.
- Actualización del mismo documento de Drive al renombrar participantes. IDs remotos persistentes para evitar duplicados al reintentar.
- Ejemplo de tres voces editable, marcado como demostración, disponible sin clave y sin llamadas a servicios.

## Configurar y probar

1. Entra en **Configuración**. Ingresa tu clave personal de OpenAI y toca **Guardar clave**. La app no incluye ninguna clave.
2. Usa **Comprobar conexión** para verificar acceso al modelo. Esto consulta los metadatos del modelo; no transcribe ni comprueba saldo. Una clave restringida puede necesitar permiso de lectura de modelos para esta comprobación, además del permiso de transcripción para transcribir.
3. Elige conexión y carga. Por defecto: procesamiento manual, red no medida, sin requisito de cargador y solo texto a Drive.
4. Graba. El título previo es opcional. Después pulsa **Transcribir con voces**. El trabajo queda en cola si las condiciones todavía no se cumplen.
5. Abre **Ver transcripción y voces → Nombrar hablantes**. Los nombres se aplican a todas las intervenciones de esa etiqueta. Con procesamiento automático activo y Drive vinculado, los cambios se encolan; en modo manual, pulsa **Sincronizar con Drive**.
6. Para explorar sin consumir API: **Configuración → Probar edición de hablantes**.

La grabación siempre es local. Para transcribir, la app envía el audio a OpenAI usando tu clave; elegir «solo transcripción» afecta a Drive, no al envío requerido por OpenAI. Las llamadas se facturan a tu cuenta de API.

El audio largo se divide en bloques de tamaño compatible. Los bloques completados se conservan como puntos de recuperación. Las identidades de hablantes son independientes entre bloques: si la misma persona aparece con varias etiquetas, asígnales el mismo nombre. La división se hace entre muestras AAC, no necesariamente entre frases. Revisa la transcripción, especialmente en límites de bloques, ruido o voces superpuestas.

Un corte después de enviar una solicitud a OpenAI pero antes de recibir la respuesta puede requerir repetir ese bloque y generar otro cargo. No se repiten los bloques cuya respuesta ya quedó guardada. Las cargas de Drive usan IDs estables; una carga interrumpida puede volver a transmitir el contenido sin crear otro archivo.

## Google Drive: activación única

La integración está implementada, pero necesita credenciales OAuth de la aplicación en Google Cloud. Una clave de OpenAI no habilita Drive.

1. En un proyecto de Google Cloud, habilita **Google Drive API**.
2. Configura la pantalla de consentimiento. Si el proyecto está en pruebas, agrega tu cuenta como usuario de prueba.
3. Crea un cliente OAuth de tipo **Android**, con paquete `cl.vozlocal.app` y la huella SHA-1 del APK. La app muestra ambos datos en **Configuración → Ayuda para activar Google Drive**.
4. En un teléfono con Google Play Services, pulsa **Vincular Google Drive** y acepta el permiso de Google.

El permiso solicitado es `drive.file`. La app puede crear sus archivos y carpetas y listar los autorizados para ella. No ofrece acceso general a todo tu Drive ni selección de cualquier carpeta existente sin autorización. La carpeta predeterminada «Voz local» se crea en la primera subida. Cambiar de carpeta deja intactas las copias anteriores y las siguientes sincronizaciones crean copias en el nuevo destino.

«Desvincular en este teléfono» detiene el uso local de Drive, sin borrar los archivos remotos. Para revocar el consentimiento de Google, utiliza la sección de aplicaciones conectadas de tu cuenta de Google.

El emulador AOSP preparado en `.tools/` no incluye Google Play Services. Permite probar la interfaz, grabación y edición de voces, pero el acceso real a Google requiere un teléfono con Google Play o un emulador con Google APIs, cuenta y proyecto OAuth configurados. Mburu queda fuera del alcance.

Documentación de referencia: [OpenAI: transcripción](https://developers.openai.com/api/docs/guides/speech-to-text), [Google: autorización Android](https://developer.android.com/identity/authorization), [Drive: cargas](https://developers.google.com/workspace/drive/api/guides/manage-uploads).

## Conservar tus audios

Usa **Opciones → Compartir audio** para exportar las grabaciones que quieras conservar fuera del teléfono. **Desinstalar la app o borrar sus datos elimina sus grabaciones locales.** Actualizar con un APK de la misma firma conserva los datos.

Android o el fabricante pueden interrumpir una grabación si fuerzas el cierre de la app, apagas el teléfono, revocas el micrófono o se agota el almacenamiento. Los audios interrumpidos pueden no ser reproducibles. La reproducción se detiene al salir de la app; la grabación usa su propio servicio y continúa.

## Compilar

Proyecto Android nativo en Java 17, con Google Play Services Auth 22.0.0 para autorización. Android Gradle Plugin 8.9.2, Gradle 8.11.1, compile/target SDK 35, min SDK 26.

En este computador se prepararon herramientas portables en `.tools/`. Ejecuta:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

En otro computador, abre esta carpeta en Android Studio, instala Android SDK 35 y usa Java 17 / Gradle 8.11.1. `local.properties` contiene la ruta local al SDK y no se versiona.

La firma de desarrollo debe conservarse para instalar futuras versiones sin desinstalar. Antes de distribuir públicamente, configura una firma de lanzamiento propia y un proceso de respaldo de esa clave.

## Validación en un celular

1. Graba 30 segundos en modo avión; bloquea la pantalla durante parte de la grabación.
2. Pausa y continúa: comprueba que el contador no suma la pausa.
3. Detén y reproduce; verifica que se escuche lo que grabaste.
4. Cambia el título, cierra/abre la app y comprueba que persiste.
5. Comparte el M4A con otra app y comprueba que lo abre.
6. Rechaza el borrado y verifica que se conserva; luego borra una grabación de prueba.

La prueba con el micrófono y las restricciones de batería de tu modelo de teléfono sigue siendo necesaria aunque el APK compile y pase las verificaciones del proyecto.

Para revisar el código: `Settings` protege la clave; `Pipeline` y `PipelineJob` gestionan la cola; `OpenAiClient` implementa la solicitud de diarización; `AudioParts` divide archivos largos; `Transcript` conserva identidades y nombres; `DriveClient` autoriza y sincroniza archivos. Las pruebas instrumentales usan respuestas controladas para OpenAI y Drive: no contienen ni consumen claves reales.
