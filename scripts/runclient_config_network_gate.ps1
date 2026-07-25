param(
    [Parameter(Mandatory = $true)]
    [string] $JavaHome,

    [string] $WorldName = "New World",

    [int] $TimeoutSeconds = 300
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
            if ($toStop.Contains([int] $process.ParentProcessId) -and
                    -not $toStop.Contains([int] $process.ProcessId)) {
                [void] $toStop.Add([int] $process.ProcessId)
                $changed = $true
            }
        }
    }
    foreach ($processId in $toStop) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-ConfigGate {
    param(
        [string] $Mode,
        [string] $Pattern,
        [string] $Expected = ""
    )

    $env:IWM_CLIENT_CONFIG_NETWORK_GATE = $Mode
    $env:IWM_CLIENT_CONFIG_NETWORK_EXPECT_HEAVEN_TRANSFER = $Expected
    Remove-Item -LiteralPath $latestLog -Force -ErrorAction SilentlyContinue

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stdoutLog = Join-Path $ProjectRoot "runclient-config-$Mode-$stamp.out.log"
    $stderrLog = Join-Path $ProjectRoot "runclient-config-$Mode-$stamp.err.log"
    $process = $null
    $gateSucceeded = $false
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
        while ((Get-Date) -lt $deadline) {
            if (Test-Path -LiteralPath $latestLog) {
                $match = Select-String -LiteralPath $latestLog -Pattern $Pattern -ErrorAction SilentlyContinue |
                    Select-Object -Last 1
                if ($match) {
                    $gateSucceeded = $true
                    Write-Output $match.Line
                    return
                }
                $failure = Select-String -LiteralPath $latestLog `
                    -Pattern "IWM_CLIENT_CONFIG_NETWORK_GATE_FAILED" -ErrorAction SilentlyContinue |
                    Select-Object -Last 1
                if ($failure) {
                    throw $failure.Line
                }
            }
            if ($process.HasExited) {
                break
            }
            Start-Sleep -Seconds 2
            $process.Refresh()
        }

        if (Test-Path -LiteralPath $latestLog) {
            Get-Content -LiteralPath $latestLog -Tail 120
        }
        throw "Connected client config gate '$Mode' did not produce '$Pattern'"
    } finally {
        if ($process -and -not $process.HasExited) {
            Stop-ProcessTree -RootProcessId $process.Id
        }
        Start-Sleep -Seconds 3
        if (-not $gateSucceeded) {
            if (Test-Path -LiteralPath $stdoutLog) {
                Get-Content -LiteralPath $stdoutLog -Tail 80
            }
            if (Test-Path -LiteralPath $stderrLog) {
                Get-Content -LiteralPath $stderrLog -Tail 80
            }
        }
        Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
    }
}

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot

$worldPath = Join-Path $ProjectRoot ("run\saves\" + $WorldName)
if (-not (Test-Path -LiteralPath (Join-Path $worldPath "level.dat"))) {
    throw "Singleplayer test world not found: $worldPath"
}

$latestLog = Join-Path $ProjectRoot "run\logs\latest.log"
$configPath = Join-Path $ProjectRoot "run\config\instantworldmirror-common.toml"
$hadConfig = Test-Path -LiteralPath $configPath
$configBackup = if ($hadConfig) { [System.IO.File]::ReadAllBytes($configPath) } else { $null }
$optionsPath = Join-Path $ProjectRoot "run\options.txt"
$hadOptions = Test-Path -LiteralPath $optionsPath
$optionsBackup = if ($hadOptions) { [System.IO.File]::ReadAllBytes($optionsPath) } else { $null }

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$oldMode = $env:IWM_CLIENT_CONFIG_NETWORK_GATE
$oldExpected = $env:IWM_CLIENT_CONFIG_NETWORK_EXPECT_HEAVEN_TRANSFER
$oldQuickPlayWorld = $env:IWM_QUICK_PLAY_WORLD

try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$oldPath"
    $env:IWM_QUICK_PLAY_WORLD = $WorldName
    [System.IO.File]::WriteAllLines(
        $optionsPath,
        [string[]] @("lang:en_us", "onboardAccessibility:false"),
        [System.Text.UTF8Encoding]::new($false)
    )

    Invoke-ConfigGate -Mode "write" -Pattern "IWM_CLIENT_CONFIG_NETWORK_SAVE_OK"

    $savedLine = Select-String -LiteralPath $configPath `
        -Pattern "^\s*heavenMirrorItemTransfer\s*=\s*(true|false)\s*$" |
        Select-Object -Last 1
    if (-not $savedLine) {
        throw "Saved config does not contain heavenMirrorItemTransfer"
    }
    $expected = $savedLine.Matches[0].Groups[1].Value.ToLowerInvariant()

    Invoke-ConfigGate -Mode "verify" `
        -Pattern "IWM_CLIENT_CONFIG_NETWORK_RESTART_OK" `
        -Expected $expected

    Write-Output "RUNCLIENT_CONFIG_NETWORK_GATE_OK heavenItemTransfer=$expected"
} finally {
    if ($hadConfig) {
        [System.IO.File]::WriteAllBytes($configPath, $configBackup)
    } else {
        Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    }
    if ($hadOptions) {
        [System.IO.File]::WriteAllBytes($optionsPath, $optionsBackup)
    } else {
        Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
    }
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
    $env:IWM_CLIENT_CONFIG_NETWORK_GATE = $oldMode
    $env:IWM_CLIENT_CONFIG_NETWORK_EXPECT_HEAVEN_TRANSFER = $oldExpected
    $env:IWM_QUICK_PLAY_WORLD = $oldQuickPlayWorld
}
