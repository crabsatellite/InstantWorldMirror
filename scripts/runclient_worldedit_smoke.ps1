param(
    [Parameter(Mandatory = $true)]
    [string] $JavaHome,

    [Parameter(Mandatory = $true)]
    [string] $WorldEditJar,

    [string] $VersionLabel = "dev-worldedit",

    [int] $TimeoutSeconds = 300,

    [string] $LanguageCode = "en_us"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$resolvedWorldEdit = (Resolve-Path -LiteralPath $WorldEditJar).Path
$modsDir = Join-Path $ProjectRoot "run\mods"
$targetJar = Join-Path $modsDir (Split-Path -Leaf $resolvedWorldEdit)
$hadTarget = Test-Path -LiteralPath $targetJar
$previousBytes = $null
$sourceIsTarget = [System.StringComparer]::OrdinalIgnoreCase.Equals($resolvedWorldEdit, $targetJar)

if ($hadTarget) {
    $previousBytes = [System.IO.File]::ReadAllBytes($targetJar)
}

$exitCode = 0
try {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    if (-not $sourceIsTarget) {
        Copy-Item -LiteralPath $resolvedWorldEdit -Destination $targetJar -Force
    }

    & (Join-Path $PSScriptRoot "runclient_smoke.ps1") `
        -JavaHome $JavaHome `
        -VersionLabel $VersionLabel `
        -TimeoutSeconds $TimeoutSeconds `
        -MirrorConfigUiGate `
        -LanguageCode $LanguageCode
    if ($LASTEXITCODE -ne 0) {
        throw "WorldEdit client UI gate failed with exit code $LASTEXITCODE"
    }

    $latestLog = Join-Path $ProjectRoot "run\logs\latest.log"
    if (-not (Select-String -LiteralPath $latestLog -Pattern "WorldEdit .* is loaded" -Quiet)) {
        throw "WorldEdit was not loaded by the client"
    }

    Write-Output "RUNCLIENT_WORLDEDIT_UI_GATE_OK $VersionLabel"
} catch {
    Write-Output "RUNCLIENT_WORLDEDIT_UI_GATE_FAILED $VersionLabel"
    Write-Output $_.Exception.Message
    $exitCode = 1
} finally {
    if (-not $sourceIsTarget) {
        if ($hadTarget) {
            [System.IO.File]::WriteAllBytes($targetJar, $previousBytes)
        } else {
            Remove-Item -LiteralPath $targetJar -Force -ErrorAction SilentlyContinue
        }
    }
}

exit $exitCode
