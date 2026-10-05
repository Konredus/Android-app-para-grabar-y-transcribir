# Google Play: ficha y declaraciones de Verbapp

Lo que se cargó en Play Console el 2026-10-02 (app «Verbapp: graba y transcribe», paquete `cl.verbapp.app`, gratuita).

## Ficha principal (español, es-419)
- **Nombre:** Verbapp: graba y transcribe
- **Descripción breve:** Tu voz, directo a tu segundo cerebro: graba, transcribe con IA y arma notas.
- **Descripción completa:** `ficha-es.txt`
- **Ícono:** `icono-512.png` (mismas formas y colores que el ícono adaptativo de la app)
- **Gráfico destacado:** `grafico-destacado-1024x500.png`
- **Capturas de teléfono (1080×1920):** `capturas-telefono/01.png` a `07.png`
- **Capturas de tablet 7" y 10" (1920×1080):** `capturas-tablet/tablet-01.png` a `04.png`
- Los gráficos se generan con `store_assets.py` y `store_tablet.py` a partir de capturas del emulador.

## Traducciones de la ficha (0.9.0, cargadas el 2026-10-02)
- **Inglés (en-US):** nombre «Verbapp: record & transcribe»; breve «Your voice, straight to your second brain: record, AI transcripts and notes.»; completa en `ficha-en.txt`.
- **Portugués (pt-BR):** nombre «Verbapp: grave e transcreva»; breve «Sua voz direto para o seu segundo cérebro: grave, transcreva com IA, crie notas.»; completa en `ficha-pt.txt`.
- Pedido del dueño: que quede clarísimo que la app nació para el **segundo cerebro (Second Brain)**. Las tres fichas parten con eso y su primera sección es «Pensada para tu segundo cerebro» (nota en Markdown para Obsidian).
- Las capturas son las de la ficha en español (Google las usa para los idiomas sin capturas propias).

## Configuración de la tienda
- Categoría: Productividad.
- Correo público: latribumaker@gmail.com.
- Sitio web: https://konredus.github.io/Android-app-para-grabar-y-transcribir/

## Política de privacidad
- Español: https://konredus.github.io/Android-app-para-grabar-y-transcribir/privacidad.html
- Inglés: https://konredus.github.io/Android-app-para-grabar-y-transcribir/privacy.html
- Portugués: https://konredus.github.io/Android-app-para-grabar-y-transcribir/privacidade.html
- Se publica desde la rama `gh-pages` (copia en `web/`).

## Contenido de la app (10 declaraciones)
1. Política de privacidad: la URL de arriba.
2. Anuncios: no tiene.
3. Detalles de acceso: «OpenRouter API key for transcription», con instrucciones en inglés. **Falta que el dueño pegue la clave de prueba** (con tope de gasto) en el campo «Contraseña».
4. Clasificación (IARC): «El resto de los tipos de app». Contenido en línea: sí (texto generado por IA a partir de lo que graba el usuario); sin violencia, sexo, lenguaje ofensivo, drogas, compras, apuestas, ubicación compartida ni interacción entre usuarios. Resultado: todas las edades (PEGI 3, ESRB Everyone).
5. Público objetivo: mayores de 18 años.
6. Seguridad de los datos: recopila «Grabaciones de voz o sonido» y «Otro contenido generado por usuarios»; opcionales, no efímeros, solo para funciones de la app; no se comparten; cifrados en tránsito; sin cuentas.
7. ID de publicidad: no lo usa.
8. App gubernamental: no.
9. Funciones financieras: ninguna.
10. Apps de salud: ninguna función de salud.

## Prueba cerrada
- Segmento «Prueba cerrada - Alpha».
  - Versión **14 (0.9.0)**: enviada a revisión el 2026-10-02 y publicada el 2026-10-03.
  - Versión **15 (0.9.1)**: enviada a revisión y publicada el 2026-10-03. Trae reintentos que esperan cuando OpenRouter falla y «Probar con otro modelo».
  - Versión **16 (0.9.2)**: enviada a revisión el 2026-10-03. Avisa cuando Google Play tiene una versión nueva (librería oficial `app-update`) y abre el teclado solo en las hojas con un campo.
  - Cada AAB está en `entrega/Verbapp-<versión>.aab`, firmado con la clave de subida, con notas de la versión en es-419, en-US y pt-BR.
- Enlace para unirse a la prueba: https://play.google.com/apps/testing/cl.verbapp.app
  - Cada tester lo abre con la cuenta de Google de su lista, acepta y luego instala desde Play.
  - Quien instaló una APK de prueba (firma de depuración) debe desinstalarla antes.
- Países: todos (178).
- Testers: lista «Testers Verbapp», con 4 correos al 2026-10-03. Google pide **al menos 12** que la usen **14 días seguidos** antes de poder pedir producción.
- **Permisos de servicios en primer plano** (declaración que pidió la versión): sincronización de datos (procesamiento en la red: «Otro», el envío del audio a OpenRouter para transcribir; procesamiento local: «Importación y exportación»), procesamiento multimedia («Transcodificación multimedia») y micrófono («Entrada de audio en segundo plano»). Video de demostración (no listado): https://www.youtube.com/watch?v=gdiARACfbIk
- Advertencia sin importancia: no hay archivo de desofuscación (la app no ofusca su código).
- **Política de privacidad:** desde la 0.9.2 dice que, para buscar actualizaciones, la app le pregunta a Google Play.
  - Google Play recibe la versión instalada y datos técnicos del teléfono, nunca grabaciones ni texto.
  - Según Google, la librería usa esos datos solo para saber si hay una actualización. Van cifrados y no se comparten con terceros.
  - No encajan en ninguna categoría del formulario de Seguridad de los datos, que quedó igual.
- **Detalles de acceso:** la clave de prueba de OpenRouter (con tope) ya está cargada.

## Pendiente
- Que Google apruebe la 0.9.2 y que los testers acepten la invitación desde el enlace de la prueba.
- Juntar 12 testers y esperar 14 días; después, pedir acceso a producción.
- Capturas de pantalla en inglés y portugués (opcional).
