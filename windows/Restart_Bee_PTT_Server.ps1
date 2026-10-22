param(
    [string]$ServerDir = $PSScriptRoot,
    [int]$TimeoutSeconds = 70
)
$ErrorActionPreference = 'Stop'

# Never call the existing Local_AI_Monitor_Server restart BAT file.
# That BAT file may contain UTF-8 BOM or broken CMD line continuation.
$ServerDir = [IO.Path]::GetFullPath($ServerDir)
$Main = Join-Path $ServerDir 'main.py'
if (-not (Test-Path -LiteralPath $Main)) { throw "Missing Local AI server: $Main" }
$Python = (Get-Command python.exe -ErrorAction Stop).Source
if (-not $Python) { throw 'Python executable was not found in PATH.' }

Write-Host 'Checking Local AI server process on port 8769...' -ForegroundColor Cyan
$targets = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
    $_.Name -match '^(python|pythonw|uvicorn)(\.exe)?$' -and
    $_.CommandLine -and
    $_.CommandLine -match 'uvicorn' -and
    $_.CommandLine -match 'main:app' -and
    $_.CommandLine -match '8769'
})
foreach ($target in $targets) {
    Write-Host "Stopping previous Local AI server process PID=$($target.ProcessId)"
    Stop-Process -Id $target.ProcessId -Force -ErrorAction Stop
}
if ($targets.Count -gt 0) { Start-Sleep -Seconds 2 }

# If another application owns this port, do not kill it.
$busy = @(Get-NetTCPConnection -LocalPort 8769 -State Listen -ErrorAction SilentlyContinue)
if ($busy.Count -gt 0) {
    $owners = ($busy | ForEach-Object { $_.OwningProcess } | Sort-Object -Unique) -join ','
    throw "Port 8769 is still in use by PID=$owners. No unknown process was stopped."
}

$LogDir = Join-Path $env:LOCALAPPDATA 'BeePTTRelayLogs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Stdout = Join-Path $LogDir ("server_$Stamp.out.log")
$Stderr = Join-Path $LogDir ("server_$Stamp.err.log")
$args = @('-m', 'uvicorn', 'main:app', '--host', '0.0.0.0', '--port', '8769', '--no-access-log')
Write-Host "Starting: $Python -m uvicorn main:app --host 0.0.0.0 --port 8769"
$process = Start-Process -FilePath $Python -ArgumentList $args -WorkingDirectory $ServerDir -WindowStyle Minimized -PassThru -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr
if (-not $process) { throw 'Windows did not return the server process handle.' }
Write-Host "New server PID=$($process.Id)"

$success = $false
$lastHealth = ''
$attempts = [Math]::Max(1, [int][Math]::Ceiling($TimeoutSeconds / 2.0))
for ($i = 0; $i -lt $attempts; $i++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8769/ptt/health' -TimeoutSec 3
        if ($health.ok -eq $true) {
            $success = $true
            $lastHealth = "authentication_configured=$($health.auth_configured); connected_devices=$($health.connected_devices)"
            break
        }
    } catch {
        $lastHealth = $_.Exception.Message
    }
    $process.Refresh()
    if ($process.HasExited) { break }
}
if (-not $success) {
    Write-Warning "PTT health check failed: $lastHealth"
    Write-Warning "Server stderr log: $Stderr"
    if (Test-Path -LiteralPath $Stderr) {
        Get-Content -LiteralPath $Stderr -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
    }
    throw 'Server is not responding on http://127.0.0.1:8769/ptt/health.'
}
Write-Host "Bee PTT relay active on Windows! $lastHealth" -ForegroundColor Green
if ($health.auth_configured -ne $true) {
    Write-Warning 'Server is running, but audio_upload_token.txt or BEE_AUDIO_UPLOAD_TOKEN is missing.'
}
Write-Host "Server logs: $LogDir"

