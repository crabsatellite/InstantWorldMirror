param(
    [Parameter(Mandatory = $true)]
    [string] $JavaHome,

    [string] $VersionLabel = "dev",

    [int] $TimeoutSeconds = 240,

    [switch] $MirrorConfigUiGate,

    [string] $LanguageCode = ""
)

$ErrorActionPreference = "Stop"

function Stop-ProcessTree {
    param([int] $RootProcessId)

    $all = Get-CimInstance Win32_Process
    $toStop = New-Object "System.Collections.Generic.HashSet[int]"
    [void] $toStop.Add($RootProcessId)

    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($process in $all) {
            if ($toStop.Contains([int] $process.ParentProcessId) -and -not $toStop.Contains([int] $process.ProcessId)) {
                [void] $toStop.Add([int] $process.ProcessId)
                $changed = $true
            }
        }
    }

    foreach ($childProcessId in $toStop) {
        Stop-Process -Id $childProcessId -Force -ErrorAction SilentlyContinue
    }
}

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot

if ($MirrorConfigUiGate -and [string]::IsNullOrWhiteSpace($LanguageCode)) {
    $languageResourceDir = Join-Path $ProjectRoot "src\main\resources\assets\instantworldmirror\lang"
    $languageCodes = Get-ChildItem -LiteralPath $languageResourceDir -Filter "*.json" |
        ForEach-Object { $_.BaseName } |
        Sort-Object

    if (-not $languageCodes) {
        Write-Output "RUNCLIENT_UI_GATE_FAILED"
        Write-Output "No supported language files found in $languageResourceDir"
        exit 1
    }

    $overallExitCode = 0
    foreach ($code in $languageCodes) {
        Write-Output "RUNCLIENT_UI_GATE_LANGUAGE_START $code"
        & $PSCommandPath `
            -JavaHome $JavaHome `
            -VersionLabel "$VersionLabel-$code" `
            -TimeoutSeconds $TimeoutSeconds `
            -MirrorConfigUiGate `
            -LanguageCode $code
        if ($LASTEXITCODE -ne 0) {
            $overallExitCode = $LASTEXITCODE
            break
        }
    }

    if ($overallExitCode -eq 0) {
        Write-Output "RUNCLIENT_UI_GATE_ALL_LANGUAGES_OK"
    }
    exit $overallExitCode
}

Get-ChildItem -Force -Filter "runclient-*.log" | Remove-Item -Force -ErrorAction SilentlyContinue

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdoutLog = Join-Path $ProjectRoot "runclient-$VersionLabel-$stamp.out.log"
$stderrLog = Join-Path $ProjectRoot "runclient-$VersionLabel-$stamp.err.log"
$latestLog = Join-Path $ProjectRoot "run\logs\latest.log"

Remove-Item -LiteralPath $latestLog -ErrorAction SilentlyContinue

$env:JAVA_HOME = $JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$oldMirrorConfigUiGate = $env:IWM_CLIENT_UI_GATE
$oldMirrorConfigUiGateLanguage = $env:IWM_CLIENT_UI_GATE_LANGUAGE
if ($MirrorConfigUiGate) {
    $env:IWM_CLIENT_UI_GATE = "true"
}
if ($MirrorConfigUiGate -and -not [string]::IsNullOrWhiteSpace($LanguageCode)) {
    $env:IWM_CLIENT_UI_GATE_LANGUAGE = $LanguageCode
}

$optionsPath = Join-Path $ProjectRoot "run\options.txt"
$hadOptionsFile = Test-Path -LiteralPath $optionsPath
$oldOptionsBytes = $null
if ($MirrorConfigUiGate -and -not [string]::IsNullOrWhiteSpace($LanguageCode)) {
    $runDir = Split-Path -Parent $optionsPath
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    if ($hadOptionsFile) {
        $oldOptionsBytes = [System.IO.File]::ReadAllBytes($optionsPath)
    }
    [System.IO.File]::WriteAllLines(
        $optionsPath,
        [string[]] @("lang:$LanguageCode"),
        [System.Text.UTF8Encoding]::new($false)
    )
}

$process = $null
$exitCode = 0

try {
    $process = Start-Process `
        -FilePath (Join-Path $ProjectRoot "gradlew.bat") `
        -ArgumentList @("runClient", "--no-daemon") `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru `
        -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $evidence = $null
    $pattern = if ($MirrorConfigUiGate) {
        if ([string]::IsNullOrWhiteSpace($LanguageCode)) {
            "IWM_CLIENT_UI_GATE_OK"
        } else {
            "IWM_CLIENT_UI_GATE_OK language=$([regex]::Escape($LanguageCode))"
        }
    } else {
        "Created: .*minecraft:textures/atlas|OpenAL initialized|Sound engine started|Narrator library"
    }

    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $latestLog) {
            $matches = Select-String -LiteralPath $latestLog -Pattern $pattern -ErrorAction SilentlyContinue
            if ($matches) {
                $evidence = @($matches | Select-Object -Last 6 | ForEach-Object { $_.Line })
                break
            }
        }

        if ($process.HasExited) {
            break
        }

        Start-Sleep -Seconds 2
        try {
            $process.Refresh()
        } catch {
            # The process can exit between HasExited and Refresh.
        }
    }

    if ($evidence) {
        if ($MirrorConfigUiGate) {
            $uiGateMatches = Select-String -LiteralPath $latestLog -Pattern "IWM_CLIENT_UI_GATE_OK|IWM_CLIENT_UI_GATE_BEHAVIOR|IWM_CLIENT_UI_GATE_LANGUAGE" -ErrorAction SilentlyContinue
            if ($uiGateMatches) {
                $evidence = @($uiGateMatches | Select-Object -Last 6 | ForEach-Object { $_.Line })
            }
            if ([string]::IsNullOrWhiteSpace($LanguageCode)) {
                Write-Output "RUNCLIENT_UI_GATE_OK"
            } else {
                Write-Output "RUNCLIENT_UI_GATE_OK $LanguageCode"
            }
        } else {
            Write-Output "RUNCLIENT_SMOKE_OK"
        }
        $evidence | ForEach-Object { Write-Output $_ }
    } else {
        if ($MirrorConfigUiGate) {
            Write-Output "RUNCLIENT_UI_GATE_FAILED"
        } else {
            Write-Output "RUNCLIENT_SMOKE_FAILED"
        }
        if (Test-Path -LiteralPath $latestLog) {
            Get-Content -LiteralPath $latestLog -Tail 120
        }
        if (Test-Path -LiteralPath $stdoutLog) {
            Get-Content -LiteralPath $stdoutLog -Tail 80
        }
        if (Test-Path -LiteralPath $stderrLog) {
            Get-Content -LiteralPath $stderrLog -Tail 80
        }
        $exitCode = 1
    }
} catch {
    Write-Output "RUNCLIENT_SCRIPT_ERROR"
    Write-Output $_.Exception.Message
    $exitCode = 1
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-ProcessTree -RootProcessId $process.Id
    }

    Start-Sleep -Seconds 3
    Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
    if ($MirrorConfigUiGate -and -not [string]::IsNullOrWhiteSpace($LanguageCode)) {
        if ($hadOptionsFile) {
            [System.IO.File]::WriteAllBytes($optionsPath, $oldOptionsBytes)
        } else {
            Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
        }
    }
    $env:IWM_CLIENT_UI_GATE = $oldMirrorConfigUiGate
    $env:IWM_CLIENT_UI_GATE_LANGUAGE = $oldMirrorConfigUiGateLanguage
}

exit $exitCode
