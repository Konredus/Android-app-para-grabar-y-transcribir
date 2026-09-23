$ErrorActionPreference = 'Stop'
$env:ANDROID_HOME = Join-Path $PSScriptRoot '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = Join-Path $PSScriptRoot '.tools/avd'
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.tools/android-user'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
if (-not (Test-Path $emulator)) { throw 'No se encuentra el emulador preparado en .tools.' }
$devices = & $adb devices
if (-not ($devices -match 'emulator-5554\s+device')) {
    Write-Host 'Abriendo Android. El primer inicio puede tardar un minuto...'
    Start-Process -FilePath $emulator -ArgumentList '-avd VozLocalTest -port 5554 -no-boot-anim -no-snapshot -gpu swiftshader_indirect'
}
$ready = $false
# adb escribe avisos en stderr ("device not found" al arrancar, "Activity not started" si ya está abierta);
# en Windows PowerShell 5.1 eso detendría el script con 'Stop'. Desde aquí se revisa $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
for ($attempt = 0; $attempt -lt 180; $attempt++) {
    $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null
    if ("$boot".Trim() -eq '1') { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Android aún no termina de iniciar. Espera un momento y vuelve a abrir este archivo.' }
& $adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
& $adb -s emulator-5554 shell wm dismiss-keyguard
& $adb -s emulator-5554 shell am start -n cl.vozlocal.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'No se pudo abrir Voz local en el emulador.' }
Write-Host 'Voz local está abierta en la ventana de Android.'
