[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repoRoot 'mvnw.cmd'
$agentJar = Join-Path $repoRoot 'target\ai-live-edge-agent.jar'

function Get-JavaMajorVersion([string]$JavaExe) {
    if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
        return $null
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $JavaExe
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardOutput = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $versionText = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $versionText -notmatch 'version "(?:1\.)?(\d+)') {
        return $null
    }
    return [int]$Matches[1]
}

$candidateHomes = [System.Collections.Generic.List[string]]::new()
foreach ($name in @('AI_LIVE_JAVA_HOME', 'JAVA_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        $candidateHomes.Add($value)
    }
}

$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) {
    $candidateHomes.Add((Split-Path -Parent (Split-Path -Parent $pathJava.Source)))
}
$candidateHomes.Add('C:\Program Files\Java\jdk-17')

$javaHome = $null
foreach ($candidate in $candidateHomes | Select-Object -Unique) {
    $javaExe = Join-Path $candidate 'bin\java.exe'
    $major = Get-JavaMajorVersion $javaExe
    if ($null -ne $major -and $major -ge 17) {
        $javaHome = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}

if (-not $javaHome) {
    throw 'Java 17 or newer was not found. Set AI_LIVE_JAVA_HOME or JAVA_HOME to a JDK 17 installation.'
}
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "Maven Wrapper not found: $mavenWrapper"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
Write-Host "Building Agent with JAVA_HOME=$javaHome"
& $mavenWrapper clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Agent Maven build failed with exit code $LASTEXITCODE."
}
if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent build completed but the executable JAR is missing: $agentJar"
}

Write-Host "Agent JAR ready: $agentJar"
