param(
    [string]$Version = "1.12.10"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$NativeDir = Join-Path $Root "runtime\native\windows-x86_64"
$ModelDir = Join-Path $Root "data\models\sherpa-onnx\streaming-paraformer-zh-en"

$ApiJarName = "sherpa-onnx-v$Version.jar"
$NativeJarName = "sherpa-onnx-native-lib-win-x64-v$Version.jar"
$ApiJar = Join-Path $NativeDir $ApiJarName
$NativeJar = Join-Path $NativeDir $NativeJarName

$ReleaseBase = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$Version"
$ModelBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main"

New-Item -ItemType Directory -Force -Path $NativeDir | Out-Null
New-Item -ItemType Directory -Force -Path $ModelDir | Out-Null

Write-Host "Sherpa-ONNX setup target: $Root"
Write-Host "Version: $Version"
Write-Host "Native dir: $NativeDir"
Write-Host "Model dir: $ModelDir"

function Download-IfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile
    )
    if ((Test-Path $OutFile) -and (Get-Item $OutFile).Length -gt 0) {
        Write-Host "Exists: $OutFile"
        return
    }
    Write-Host "Downloading: $Url"
    Invoke-WebRequest -Uri $Url -OutFile $OutFile
    if (-not (Test-Path $OutFile) -or (Get-Item $OutFile).Length -le 0) {
        throw "Download failed or empty: $OutFile"
    }
}

Download-IfMissing "$ReleaseBase/$ApiJarName" $ApiJar
Download-IfMissing "$ReleaseBase/$NativeJarName" $NativeJar
Download-IfMissing "$ModelBase/encoder.int8.onnx" (Join-Path $ModelDir "encoder.int8.onnx")
Download-IfMissing "$ModelBase/decoder.int8.onnx" (Join-Path $ModelDir "decoder.int8.onnx")
Download-IfMissing "$ModelBase/tokens.txt" (Join-Path $ModelDir "tokens.txt")

$required = @(
    $ApiJar,
    $NativeJar,
    (Join-Path $ModelDir "encoder.int8.onnx"),
    (Join-Path $ModelDir "decoder.int8.onnx"),
    (Join-Path $ModelDir "tokens.txt")
)

$missing = $required | Where-Object { -not (Test-Path $_) -or (Get-Item $_).Length -le 0 }
if ($missing.Count -gt 0) {
    Write-Host "Sherpa-ONNX required files are still missing. Check network access and retry."
    $missing | ForEach-Object { Write-Host "MISSING: $_" }
    exit 2
}

Write-Host "Sherpa-ONNX files are ready."
