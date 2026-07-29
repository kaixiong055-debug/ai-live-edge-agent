[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JavaRuntimePath,

    [Parameter(Mandatory = $true)]
    [string]$AgentJarPath,

    [Parameter(Mandatory = $true)]
    [string]$DesktopPublishPath,

    [string]$OutputPath,
    [string]$WebView2BootstrapperPath,
    [string]$WebView2OfflineInstallerPath,
    [string]$DefaultAssetsPath,
    [string]$SherpaNativePath,
    [string]$SherpaModelPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-RequiredPath {
    param([string]$PathValue, [string]$Description, [switch]$Directory)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        throw "$Description 不能为空。"
    }
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Description 不存在：$PathValue"
    }
    if ($Directory -and -not (Test-Path -LiteralPath $resolved.Path -PathType Container)) {
        throw "$Description 不是目录：$PathValue"
    }
    if (-not $Directory -and -not (Test-Path -LiteralPath $resolved.Path -PathType Leaf)) {
        throw "$Description 不是文件：$PathValue"
    }
    return $resolved.Path
}

function Copy-DirectoryContent {
    param([string]$Source, [string]$Destination)
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Read-XmlElementValue {
    param([string]$XmlPath, [string]$XPath)
    [xml]$document = Get-Content -LiteralPath $XmlPath -Raw
    $node = $document.SelectSingleNode($XPath)
    if ($node) { return $node.InnerText.Trim() }
    return $null
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot 'dist\AI Live Edge'
}
if ($WebView2BootstrapperPath -and $WebView2OfflineInstallerPath) {
    throw 'WebView2 Bootstrapper 与 Offline Installer 只能提供一个。'
}

$JavaRuntimePath = Resolve-RequiredPath $JavaRuntimePath 'Java Runtime/JDK 17 路径' -Directory
$AgentJarPath = Resolve-RequiredPath $AgentJarPath 'Agent JAR'
$DesktopPublishPath = Resolve-RequiredPath $DesktopPublishPath 'Desktop publish 目录' -Directory
$desktopExe = Join-Path $DesktopPublishPath 'AI Live Edge.exe'
if (-not (Test-Path -LiteralPath $desktopExe -PathType Leaf)) {
    throw "Desktop publish 目录中缺少 AI Live Edge.exe：$desktopExe"
}

$javaExe = Join-Path $JavaRuntimePath 'bin\java.exe'
$javawExe = Join-Path $JavaRuntimePath 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf) -or
    -not (Test-Path -LiteralPath $javawExe -PathType Leaf)) {
    throw 'Java 路径必须包含 bin\java.exe 和 bin\javaw.exe。'
}

$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null

Copy-DirectoryContent $DesktopPublishPath $OutputPath

$agentDirectory = Join-Path $OutputPath 'agent'
$privateRuntimeDirectory = Join-Path $agentDirectory 'runtime'
New-Item -ItemType Directory -Path $agentDirectory -Force | Out-Null
Copy-Item -LiteralPath $AgentJarPath -Destination (Join-Path $agentDirectory 'ai-live-edge-agent.jar') -Force

$jlinkExe = Join-Path $JavaRuntimePath 'bin\jlink.exe'
$jmodsDirectory = Join-Path $JavaRuntimePath 'jmods'
if ((Test-Path -LiteralPath $jlinkExe -PathType Leaf) -and
    (Test-Path -LiteralPath $jmodsDirectory -PathType Container)) {
    Write-Host 'Detected JDK 17. Creating a private runtime image with jlink...'
    & $jlinkExe `
        --module-path $jmodsDirectory `
        --add-modules ALL-MODULE-PATH `
        --output $privateRuntimeDirectory `
        --strip-debug `
        --no-header-files `
        --no-man-pages `
        --compress=2
    if ($LASTEXITCODE -ne 0) {
        throw "jlink 创建 Java Runtime 失败，退出代码：$LASTEXITCODE"
    }
}
else {
    Write-Host 'Copying the supplied Java runtime image...'
    Copy-DirectoryContent $JavaRuntimePath $privateRuntimeDirectory
}

if (-not (Test-Path -LiteralPath (Join-Path $privateRuntimeDirectory 'bin\java.exe') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $privateRuntimeDirectory 'bin\javaw.exe') -PathType Leaf)) {
    throw '组装后的私有 Java Runtime 不完整。'
}

$resourcesDirectory = Join-Path $OutputPath 'resources'
$defaultsDirectory = Join-Path $resourcesDirectory 'defaults'
$redistDirectory = Join-Path $resourcesDirectory 'redist'
New-Item -ItemType Directory -Path $defaultsDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $redistDirectory -Force | Out-Null

$defaultCommands = Join-Path $repoRoot 'commands.json'
if (-not (Test-Path -LiteralPath $defaultCommands -PathType Leaf)) {
    $defaultCommands = Join-Path $repoRoot 'src\main\resources\commands.json'
}
Copy-Item -LiteralPath $defaultCommands -Destination (Join-Path $defaultsDirectory 'commands.json') -Force

if ($DefaultAssetsPath) {
    $DefaultAssetsPath = Resolve-RequiredPath $DefaultAssetsPath '默认素材目录' -Directory
    Copy-DirectoryContent $DefaultAssetsPath (Join-Path $resourcesDirectory 'default-assets')
}

if ($SherpaNativePath) {
    $SherpaNativePath = Resolve-RequiredPath $SherpaNativePath 'Sherpa native 目录' -Directory
    Copy-DirectoryContent $SherpaNativePath (Join-Path $agentDirectory 'resources\runtime\native\windows-x86_64')
}
if ($SherpaModelPath) {
    $SherpaModelPath = Resolve-RequiredPath $SherpaModelPath 'Sherpa 模型目录' -Directory
    Copy-DirectoryContent $SherpaModelPath (Join-Path $agentDirectory 'resources\models\sherpa-onnx\streaming-paraformer-zh-en')
}

$webView2Source = $null
$webView2Type = $null
if ($WebView2BootstrapperPath) {
    $webView2Source = Resolve-RequiredPath $WebView2BootstrapperPath 'WebView2 Bootstrapper'
    $webView2Type = 'bootstrapper'
}
elseif ($WebView2OfflineInstallerPath) {
    $webView2Source = Resolve-RequiredPath $WebView2OfflineInstallerPath 'WebView2 Offline Installer'
    $webView2Type = 'offline'
}
if ($webView2Source) {
    Copy-Item -LiteralPath $webView2Source -Destination (Join-Path $redistDirectory 'webview2-installer.exe') -Force
    Set-Content -LiteralPath (Join-Path $redistDirectory 'webview2-installer-type.txt') -Value $webView2Type -Encoding UTF8
}

$desktopVersion = Read-XmlElementValue (Join-Path $repoRoot 'desktop\Directory.Build.props') "/*[local-name()='Project']/*[local-name()='PropertyGroup']/*[local-name()='DesktopVersion']"
$agentVersion = Read-XmlElementValue (Join-Path $repoRoot 'pom.xml') "/*[local-name()='project']/*[local-name()='version']"
$versionInfo = [ordered]@{
    desktopVersion = $desktopVersion
    agentVersion = $agentVersion
    buildTime = [DateTimeOffset]::UtcNow.ToString('O')
    architecture = 'win-x64'
}
$versionInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputPath 'version.json') -Encoding UTF8

$forbiddenFiles = Get-ChildItem -LiteralPath $OutputPath -Recurse -Force -File | Where-Object {
    $_.Name -match '^\.env' -or
    $_.Name -match '^application-local\.(yml|yaml)$' -or
    $_.Extension -in @('.log', '.token')
}
if ($forbiddenFiles) {
    $names = ($forbiddenFiles.FullName -join [Environment]::NewLine)
    throw "分发目录包含禁止文件：$([Environment]::NewLine)$names"
}

Write-Host "Windows distribution assembled: $OutputPath"
Write-Host "DesktopVersion=$desktopVersion AgentVersion=$agentVersion Architecture=win-x64"
