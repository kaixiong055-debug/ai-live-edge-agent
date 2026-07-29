[CmdletBinding()]
param(
    [string]$ProjectPath,
    [string]$OutputPath,
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',
    [string]$RuntimeIdentifier = 'win-x64'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$agentBuildScript = Join-Path $PSScriptRoot 'build-agent.ps1'
if ([string]::IsNullOrWhiteSpace($ProjectPath)) {
    $ProjectPath = Join-Path $repoRoot 'desktop\src\AiLiveEdge.Desktop\AiLiveEdge.Desktop.csproj'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot '.build\desktop-publish'
}

$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if (-not $dotnet) {
    throw 'dotnet was not found. Install the .NET 8 SDK and retry.'
}
if (-not (Test-Path -LiteralPath $ProjectPath -PathType Leaf)) {
    throw "Desktop project not found: $ProjectPath"
}
if (-not (Test-Path -LiteralPath $agentBuildScript -PathType Leaf)) {
    throw "Agent build script not found: $agentBuildScript"
}

$ProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

Write-Host 'Building Agent JAR...'
& $agentBuildScript

if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null

Write-Host "Restoring Desktop: $ProjectPath"
& $dotnet.Source restore $ProjectPath
if ($LASTEXITCODE -ne 0) {
    throw "dotnet restore failed with exit code $LASTEXITCODE."
}

Write-Host "Publishing Desktop: $OutputPath"
& $dotnet.Source publish $ProjectPath `
    -c $Configuration `
    -r $RuntimeIdentifier `
    --self-contained false `
    --no-restore `
    -o $OutputPath
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed with exit code $LASTEXITCODE."
}

$desktopExe = Join-Path $OutputPath 'AI Live Edge.exe'
$agentJar = Join-Path $OutputPath 'agent\ai-live-edge-agent.jar'
if (-not (Test-Path -LiteralPath $desktopExe -PathType Leaf)) {
    throw "Desktop entry point is missing after publish: $desktopExe"
}
if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent JAR is missing after publish: $agentJar"
}

Write-Host "Desktop publish ready: $OutputPath"
