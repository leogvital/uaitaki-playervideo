<#
.SYNOPSIS
    Gera o APK (ou AAB) do U.Ai.TAKi Player Vídeo e, opcionalmente, instala no celular conectado.

.DESCRIPTION
    Esta máquina não tem JAVA_HOME/JDK configurado globalmente — o gradlew precisa do JBR embutido
    no Android Studio. Este script já cuida disso, além de localizar o artefato gerado e (com
    -Install) instalá-lo via adb.

.PARAMETER Release
    Gera o APK de release (assinado, se keystore.properties existir) em vez do debug.

.PARAMETER Bundle
    Gera o AAB de release (para subir na Play Store) em vez do APK. Ignora -Release.

.PARAMETER Install
    Depois de gerar, instala o APK no celular via adb. Não se aplica a -Bundle (AAB não é
    instalável via adb).

.PARAMETER DeviceSerial
    Serial do dispositivo alvo do -Install (ver `adb devices`). Obrigatório só se houver mais de um
    dispositivo/emulador conectado.

.EXAMPLE
    .\scripts\build-apk.ps1
    Gera o APK debug.

.EXAMPLE
    .\scripts\build-apk.ps1 -Release -Install
    Gera o APK de release e instala no único dispositivo conectado.

.EXAMPLE
    .\scripts\build-apk.ps1 -Install -DeviceSerial RXCX90B20BE
    Gera o APK debug e instala num dispositivo específico (útil com emulador + celular conectados).
#>
[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$Bundle,
    [switch]$Install,
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

# JAVA_HOME: sem JDK no PATH desta máquina, o gradlew.bat falha com "JAVA_HOME is not set" sem isto.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $studioJbr = "E:\Program Files\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $studioJbr "bin\java.exe")) {
        $env:JAVA_HOME = $studioJbr
    } else {
        throw "JAVA_HOME nao esta configurado e o JBR do Android Studio nao foi encontrado em '$studioJbr'. Ajuste o caminho no script ou defina JAVA_HOME manualmente."
    }
}

if ($Bundle -and $Install) {
    throw "-Install nao se aplica a -Bundle (AAB nao e instalavel via adb). Use -Release -Install para gerar e instalar um APK de release."
}

$task = if ($Bundle) { "bundleRelease" } elseif ($Release) { "assembleRelease" } else { "assembleDebug" }
$outputDir = if ($Bundle) {
    "app\build\outputs\bundle\release"
} elseif ($Release) {
    "app\build\outputs\apk\release"
} else {
    "app\build\outputs\apk\debug"
}
$artifactExtension = if ($Bundle) { "*.aab" } else { "*.apk" }

Write-Host "Gerando ($task)..." -ForegroundColor Cyan
& ".\gradlew.bat" $task --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Build falhou (gradlew retornou codigo $LASTEXITCODE)."
}

$artifact = Get-ChildItem -Path $outputDir -Filter $artifactExtension -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $artifact) {
    throw "Build reportou sucesso, mas nenhum artefato '$artifactExtension' foi encontrado em '$outputDir'."
}
Write-Host "Gerado: $($artifact.FullName)" -ForegroundColor Green

if (-not $Install) {
    return
}

$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    throw "adb nao encontrado em '$adbPath'. Ajuste o caminho no script ou instale via Android Studio > SDK Manager."
}

$deviceLines = & $adbPath devices | Select-String "\tdevice$"
$serials = $deviceLines | ForEach-Object { ($_ -split "\t")[0] }

if (-not $DeviceSerial) {
    if ($serials.Count -eq 0) {
        throw "Nenhum dispositivo/emulador conectado (verifique 'adb devices' e a depuracao USB)."
    } elseif ($serials.Count -gt 1) {
        throw "Mais de um dispositivo conectado ($($serials -join ', ')) - especifique -DeviceSerial <serial>."
    }
    $DeviceSerial = $serials[0]
}

Write-Host "Instalando em $DeviceSerial..." -ForegroundColor Cyan
& $adbPath -s $DeviceSerial install -r $artifact.FullName
if ($LASTEXITCODE -ne 0) {
    throw "Instalacao falhou (adb retornou codigo $LASTEXITCODE)."
}
Write-Host "Instalado com sucesso em $DeviceSerial." -ForegroundColor Green
