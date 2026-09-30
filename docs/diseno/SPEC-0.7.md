# SPEC 0.7.0: rediseño Verbapp (para implementar en paralelo)

Fuente de diseño: `docs/diseno/PROPUESTA-0.7.md`. Imagen de referencia: `docs/diseno/referencias/0.7-referencia-convora.webp`.

## Reglas

- Java sin librerías de runtime.
- La UI es programática, hecha con el kit `Ui`/`Sheet`/`Screen`/`Glass` y los tokens de `AppTheme`.
- Los textos van en español de Chile, claros y sin jerga (`docs/diseno/CRITERIOS.md` §7).
- **SOLO cambia la interfaz.**
  - No cambian la lógica, los datos, los archivos, las claves de estado, los servicios ni los intents.
  - Se conserva todo lo que hoy hace cada pantalla: las mismas acciones, estados y mensajes.
  - Se conserva la accesibilidad: `contentDescription`, rol de botón, regiones en vivo y áreas táctiles de al menos 48 dp.
  - Se sigue respetando «Quitar animaciones» (`AppTheme.motion()`).
  - Siguen funcionando el modo oscuro y los textos largos.
- Si dudas entre «más bonito» y «pierde algo», gana conservar la capacidad. Cuéntalo en tu informe.
- La base visual ya está hecha y compila (fase 0). **No edites** estos archivos:
  - `AppTheme.java`, `Ui.java`, `Glass.java`, `Screen.java`, `Sheet.java` y `BottomNav.java`;
  - `res/*`, `AndroidManifest.xml`, `Settings.java`, `Diagnostics.java` y `FilesStore.java`;
  - los servicios y los tests.

  Si necesitas un componente nuevo, créalo como clase o método **privado dentro de tus archivos**. Si crees que debería ir al kit, dilo en tu informe final.
- El nombre visible ya se cambió de «Voz local» a «Verbapp» en todo el código. El paquete `cl.vozlocal.app` NO cambia.

## Propiedad de archivos (cada parte edita SOLO lo suyo)

| Parte | Archivos |
|---|---|
| **home** | `MainActivity.java`, `Waveform.java`, `RecordButton.java`, `ImportActivity.java`, `RangeView.java` |
| **detail** | `RecordingActivity.java` |
| **settings** | `SettingsActivity.java`, `Novedades.java`, `app/src/main/assets/novedades.json` |
| **sheets** | `RecordingActions.java`, `NameVoices.java`, `RetranscribeSheet.java` |

Puedes **crear** un drawable nuevo solo si su nombre empieza con tu parte (p. ej. `res/drawable/home_bell.xml`). Los vectores son Material Symbols Rounded, 24 dp y `fillColor #FF000000`: el color se aplica con tinte.

## Kit disponible (fase 0, ya implementado)

**Paleta** `p` (`AppTheme.Palette`). Siguen los roles de Material 3; lo que cambia es su valor:
- `primary` es el verde de marca #2F6B58.
- `primaryContainer` es menta.
- `card` es vidrio translúcido.
- `record` es naranja rojizo y solo marca el punto de «Grabando» y los avisos.

Roles nuevos:
- `ink` / `onInk`: el botón principal negro.
- `brand` / `brandDeep` / `onBrand`: el verde del micrófono.
- `glass` / `glassStroke`.
- `glassOnVivid` / `glassOnVividStroke` / `onVivid` / `onVividVariant`: el contenido y las píldoras sobre el verde intenso.
- `highlight`: el recuadro menta de una palabra.
- `gradTop` / `gradMid` / `gradBottom` / `softMid` / `softBottom`.
- `dotGrid`, `waveIdle` (barras decorativas grises) y `shadow`.

**Tipos** (`AppTheme.Type`). En Outfit: `DISPLAY_LARGE` 68 (cronómetro), `DISPLAY_MEDIUM` 44, `DISPLAY_SMALL` 36, `HEADLINE_*`, `TITLE_*`, `ITEM` 16 (filas) y `LABEL_*`. En Roboto: `BODY_*`, para texto de lectura.

Utilidades de letra:
- `AppTheme.outfit(ctx, Weight)` o `outfit(ctx, 100..900)` da la letra Outfit directa.
- `Ui.tabular(tv)` da cifras de ancho fijo, para todo número que cambia.

**Estilos de botón** (`Ui.Style`):

| Estilo | Uso |
|---|---|
| `PRIMARY` | Tinta: lo principal de cada pantalla |
| `TONAL` | Menta |
| `SECONDARY` | Gris suave (Cancelar, Listo) |
| `PLAIN` | Texto verde |
| `DESTRUCTIVE` | Acciones destructivas |
| `RECORD` | Verde de marca |
| `VIVID` | Vidrio blanco sobre el verde intenso |

**Componentes**:
- `ui.card()` y `ui.group()`: vidrio de 24 dp.
- `ui.stat(icon, valor, rótulo)`: dato de resumen.
- `ui.glassButton(res, desc)`: botón redondo de vidrio.
- `ui.highlightLast(tv, texto)`: destaca la última palabra.
- `ui.brand(textSp)`: logo + «Verbapp».
- `ui.fieldBackground()`.
- `ui.filter` (píldoras: tinta si está elegida), `ui.chip` (píldora) y `ui.outlinedChip` (vidrio).
- `ui.listRow` y `ui.switchRow` (título en Outfit `ITEM`).
- `ui.split` (tinta; `setTonal` pasa a menta).
- `AppTheme.glass(c, p, radio)` / `glassOval(c, p)`: fondos de vidrio.
- `Glass.BrandMark` y `Glass.sparkle(path, cx, cy, r)` (✦).

**Pantalla** (`Screen`):
- `shell(back, tab, vivid)`: `vivid=true` pone el fondo intenso; si no, va el suave.
- `setVivid(bool, animate)`: fundido del fondo.
- `setNavHidden(bool)`: esconde la barra flotante.
- `barButton(res, desc, click)`: botón redondo de vidrio en la barra superior (a la derecha).
- `largeTitle` en Outfit 32.
- `bar` ya trae el ← redondo de vidrio.
- `bottom` es transparente.
- El fondo pasa por detrás de las barras del sistema (borde a borde) y el contenido ya se corre según las medidas de esas barras (insets).

**Hojas** (`Sheet`):
- Son blancas, con título en Outfit 22 y fondo desenfocado en Android 12+.
- `closable(onClose)` agrega un ✕ redondo junto al título.
- Si hay un `primary` y un `secondary` cortos (≤ 14 letras), van lado a lado: el gris a la izquierda y la tinta a la derecha. Si no, se apilan.
- `secondary` ahora es una píldora gris, no texto.
- `option()` usa círculos menta.

**Barra flotante** (`BottomNav`): la misma API que antes (`select`, `badge`).

## Qué hace cada parte

Ver PROPUESTA-0.7 §3. En resumen:
- **home**:
  - Grabar en reposo y grabando (§3.1–3.2), incluida la pantalla completa sin barra al grabar.
  - Hoja «Nombra esta grabación» con mini reproductor (§3.3).
  - Biblioteca (§3.4).
  - `RecordButton` pasa a verde de marca con halo que respira; `Waveform` queda blanco sobre verde, con cabezal y barrido.
  - Importar (`ImportActivity`, `RangeView`) con la nueva estética.
  - Bienvenida «Bienvenido a Verbapp».
- **detail**: el Detalle como documento sobre fondo suave (§3.5), con reproductor fijo en cápsula de vidrio, tarjetas de vidrio y título en Outfit.
- **settings**:
  - Ajustes (§3.6): quitar «Colores de tu fondo de pantalla» y su vista previa (`dynamicColor` ya devuelve false), y agregar una tarjeta de estado con el logo.
  - Entrada **0.7.0** en `novedades.json` (primera de la lista; mismo formato), titulada «Verbapp: nueva cara, el mismo cerebro».
  - Revisar que `Novedades` (la hoja) se vea bien con el kit nuevo.
- **sheets**: las hojas de acciones, «Nombrar voces» y «Volver a transcribir» con el kit nuevo. Cambiar a colores y tipos nuevos lo que esté armado a mano: círculos tonales en menta, títulos en Outfit, reproductores de muestra en filas de vidrio, etc.

## Verificación que cada parte hace antes de terminar

1. Compilar con:
   ```powershell
   $env:JAVA_HOME=(Resolve-Path '.tools/jdk/jdk-17.0.18+8').Path; $env:GRADLE_USER_HOME=(Resolve-Path '.tools/gradle-cache').Path; $env:ANDROID_USER_HOME=(Resolve-Path '.tools/android-user').Path; & .tools/gradle-8.11.1/bin/gradle.bat --no-daemon -q assembleDebug assembleDebugAndroidTest
   ```
   Si tu worktree no tiene `.tools`, usa las rutas absolutas del repositorio principal.
2. **No uses el emulador**: es uno solo y lo usa la integración. La revisión visual se hace después, con capturas.
3. Hacer commit en tu rama con un mensaje en español.
4. En el informe final:
   - qué cambiaste;
   - lo que dudaste;
   - cualquier capacidad que no pudiste conservar (idealmente ninguna);
   - componentes que propones subir al kit.
