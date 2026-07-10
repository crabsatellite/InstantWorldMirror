param(
    [Parameter(Mandatory=$true)]
    [string]$JavaHome,

    [Parameter(Mandatory=$true)]
    [string]$VersionLabel
)

$ErrorActionPreference = 'Stop'
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot 'run\config\instantworldmirror-common.toml'
$hadConfig = Test-Path -LiteralPath $configPath
$oldConfigBytes = if ($hadConfig) { [System.IO.File]::ReadAllBytes($configPath) } else { $null }

try {
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"

    $templateSource = Join-Path (Get-Location) 'gameteststructures'
    $templateTarget = Join-Path (Get-Location) 'run\gameteststructures'
    New-Item -ItemType Directory -Path $templateTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $templateSource '*.snbt') -Destination $templateTarget -Force

    & .\gradlew.bat runGameTestServer --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "runGameTestServer failed for $VersionLabel with exit code $LASTEXITCODE"
    }

    Write-Output "GAMETEST_SMOKE_OK $VersionLabel"
}
finally {
    if ($hadConfig) {
        [System.IO.File]::WriteAllBytes($configPath, $oldConfigBytes)
    } else {
        Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    }
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
