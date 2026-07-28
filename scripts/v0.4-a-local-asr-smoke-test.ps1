param(
    [string]$BaseUrl = "http://127.0.0.1:18081",
    [string]$WavPath = ""
)

$ErrorActionPreference = "Stop"

function Assert-True($condition, $message) {
    if (-not $condition) {
        Write-Error $message
        exit 1
    }
}

$runtime = Invoke-RestMethod -Uri "$BaseUrl/local-api/runtime" -TimeoutSec 5
Assert-True ($runtime.asrProvider -eq "SHERPA_ONNX") "Runtime provider is not SHERPA_ONNX"
Assert-True ($runtime.asrStatus -ne "CONNECTED" -or $runtime.asrProvider -eq "TENCENT") "Tencent appears to be active in Sherpa mode"

$console = Invoke-WebRequest -Uri "$BaseUrl/console/index.html" -TimeoutSec 5
Assert-True ($console.StatusCode -eq 200) "Console is not 200"

$renderer = Invoke-WebRequest -Uri "$BaseUrl/renderer/index.html" -TimeoutSec 5
Assert-True ($renderer.StatusCode -eq 200) "Renderer is not 200"

$commands = Invoke-RestMethod -Uri "$BaseUrl/local-api/commands" -TimeoutSec 5
Assert-True ($null -ne $commands) "commands API failed"

$audioTest = Invoke-RestMethod -Method Post -Uri "$BaseUrl/local-api/audio/test" -TimeoutSec 5
Assert-True ($audioTest.status -eq "RUNNING" -or $audioTest.status -ne $null) "audio test failed"

if ($WavPath -and (Test-Path $WavPath)) {
    $form = @{ file = Get-Item $WavPath }
    $wav = Invoke-RestMethod -Method Post -Uri "$BaseUrl/local-api/asr/test/file" -Form $form -TimeoutSec 30
    Assert-True ($null -ne $wav.success) "WAV test failed"
}

Write-Host "V0.4-A smoke test passed."
