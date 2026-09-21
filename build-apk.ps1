$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
Push-Location $projectPath
try {
    $portableJava = Join-Path $projectPath '.tools/jdk/jdk-17.0.18+8'
    if (Test-Path $portableJava) { $env:JAVA_HOME = $portableJava }
    $env:GRADLE_USER_HOME = Join-Path $projectPath '.tools/gradle-cache'
    $env:ANDROID_USER_HOME = Join-Path $projectPath '.tools/android-user'
    New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
    $gradle = Join-Path $projectPath '.tools/gradle-8.11.1/bin/gradle.bat'
    if (-not (Test-Path $gradle)) { throw 'Abre el proyecto en Android Studio o prepara Java 17, Gradle 8.11.1 y Android SDK 35. Consulta README.md.' }
    & $gradle --no-daemon assembleDebug lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o la revisión de Android falló.' }
    New-Item -ItemType Directory -Force (Join-Path $projectPath 'entrega') | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectPath 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $projectPath 'entrega/Voz-local-0.2.0.apk') -Force
    Get-FileHash -LiteralPath (Join-Path $projectPath 'entrega/Voz-local-0.2.0.apk') -Algorithm SHA256
} finally { Pop-Location }
