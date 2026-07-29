[CmdletBinding()]
param(
    [string]$DistPath,
    [string]$InstallerScriptPath,
    [string]$IsccPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($DistPath)) {
    $DistPath = Join-Path $repoRoot 'dist\AI Live Edge'
}
if ([string]::IsNullOrWhiteSpace($InstallerScriptPath)) {
    $InstallerScriptPath = Join-Path $repoRoot 'installer\AI-Live-Edge.iss'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot 'dist\installer'
}

$DistPath = (Resolve-Path -LiteralPath $DistPath -ErrorAction Stop).Path
$InstallerScriptPath = (Resolve-Path -LiteralPath $InstallerScriptPath -ErrorAction Stop).Path
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

$required = @(
    (Join-Path $DistPath 'AI Live Edge.exe'),
    (Join-Path $DistPath 'agent\ai-live-edge-agent.jar'),
    (Join-Path $DistPath 'agent\runtime\bin\java.exe'),
    (Join-Path $DistPath 'agent\runtime\bin\javaw.exe'),
    (Join-Path $DistPath 'version.json')
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "dist 不完整，缺少：$path"
    }
}

if ([string]::IsNullOrWhiteSpace($IsccPath)) {
    $candidates = @()
    if ($env:INNO_SETUP_ISCC) { $candidates += $env:INNO_SETUP_ISCC }
    if (${env:ProgramFiles(x86)}) { $candidates += (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe') }
    if ($env:ProgramFiles) { $candidates += (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe') }
    $IsccPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($IsccPath) -or -not (Test-Path -LiteralPath $IsccPath -PathType Leaf)) {
    throw '未找到 Inno Setup 6 ISCC.exe。请通过 -IsccPath 或 INNO_SETUP_ISCC 指定。'
}

New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null
$version = Get-Content -LiteralPath (Join-Path $DistPath 'version.json') -Raw | ConvertFrom-Json
$appVersion = if ($version.desktopVersion) { [string]$version.desktopVersion } else { '0.1.0' }

& $IsccPath `
    "/DSourceDir=$DistPath" `
    "/DOutputDir=$OutputPath" `
    "/DAppVersion=$appVersion" `
    $InstallerScriptPath
if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup 编译失败，退出代码：$LASTEXITCODE"
}

$installer = Join-Path $OutputPath 'AI-Live-Edge-Setup.exe'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "安装器编译完成但未找到：$installer"
}
Write-Host "Installer ready: $installer"
