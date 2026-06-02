param(
    [Parameter(Mandatory = $true)]
    [string] $JavaHome,

    [string] $VersionLabel = "dev",

    [int] $TimeoutSeconds = 240
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

Get-ChildItem -Force -Filter "runclient-*.log" | Remove-Item -Force -ErrorAction SilentlyContinue

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdoutLog = Join-Path $ProjectRoot "runclient-$VersionLabel-$stamp.out.log"
$stderrLog = Join-Path $ProjectRoot "runclient-$VersionLabel-$stamp.err.log"
$latestLog = Join-Path $ProjectRoot "run\logs\latest.log"

Remove-Item -LiteralPath $latestLog -ErrorAction SilentlyContinue

$env:JAVA_HOME = $JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

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
    $pattern = "Created: .*minecraft:textures/atlas|OpenAL initialized|Sound engine started|Narrator library"

    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            break
        }

        if (Test-Path -LiteralPath $latestLog) {
            $matches = Select-String -LiteralPath $latestLog -Pattern $pattern -ErrorAction SilentlyContinue
            if ($matches) {
                $evidence = @($matches | Select-Object -Last 6 | ForEach-Object { $_.Line })
                break
            }
        }

        Start-Sleep -Seconds 2
        try {
            $process.Refresh()
        } catch {
            # The process can exit between HasExited and Refresh.
        }
    }

    if ($evidence) {
        Write-Output "RUNCLIENT_SMOKE_OK"
        $evidence | ForEach-Object { Write-Output $_ }
    } else {
        Write-Output "RUNCLIENT_SMOKE_FAILED"
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
}

exit $exitCode
