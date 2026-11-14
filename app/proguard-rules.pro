# Verbapp: reglas de R8 para la versión de publicación (0.9.6).
#
# Sin renombrar: Diagnostics.crash guarda la clase y las líneas de las clases propias (cl.vozlocal.app.*) en el informe
# de soporte. Con nombres ofuscados ese informe no serviría. R8 igual quita el código y los recursos que no se usan.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# La app no usa reflexión ni mapeo automático de JSON (org.json es del sistema). Actividades, servicios, receptores, el
# proveedor y PipelineJob se conservan solos porque están en el manifiesto. La librería de actualizaciones de Google Play
# (app-update) trae sus propias reglas.
