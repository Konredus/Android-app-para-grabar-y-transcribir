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
    if (-not (Test-Path $gradle)) { throw 'Abre el proyecto en Android Studio o prepara Java 17, Gradle 8.11.1 y Android SDK 36. Consulta README.md.' }
    & $gradle --no-daemon assembleDebug lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o la revisión de Android falló.' }
    # 0.9.6: el nombre del APK sale de versionName (app/build.gradle); antes decía 0.9.3 fijo.
    $version = ([regex]::Match((Get-Content -Raw (Join-Path $projectPath 'app/build.gradle')), "versionName '([^']+)'")).Groups[1].Value
    New-Item -ItemType Directory -Force (Join-Path $projectPath 'entrega') | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectPath 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $projectPath "entrega/Verbapp-$version.apk") -Force
    Get-FileHash -LiteralPath (Join-Path $projectPath "entrega/Verbapp-$version.apk") -Algorithm SHA256
} finally { Pop-Location }
