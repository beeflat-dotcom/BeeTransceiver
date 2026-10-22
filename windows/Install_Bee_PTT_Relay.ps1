$ErrorActionPreference = 'Stop'
$AiDir = 'AI' + [char]0x7528
$LocalAiDir = ([string][char]0x30ED) + [char]0x30FC + [char]0x30AB + [char]0x30EB + 'AI'
$ServerDir = Join-Path $env:USERPROFILE ("Dropbox\$AiDir\$LocalAiDir\local_ai_monitor_ver2_server")
$Src = Split-Path -Parent $MyInvocation.MyCommand.Path
$Main = Join-Path $ServerDir 'main.py'
$Module = Join-Path $ServerDir 'ptt_relay_api.py'
$Pair = Join-Path $ServerDir 'ptt_pair_code.py'
$PairCmd = Join-Path $ServerDir 'Bee_PTT_Pair.cmd'
$RestartSource = Join-Path $Src 'Restart_Bee_PTT_Server.ps1'
$RestartDest = Join-Path $ServerDir 'Restart_Bee_PTT_Server.ps1'
$RestartCmd = Join-Path $ServerDir 'Bee_PTT_Restart.cmd'
$DependencyCheck = Join-Path $Src 'Check_Bee_PTT_Python.py'

if (-not (Test-Path -LiteralPath $DependencyCheck)) { throw 'Missing Python dependency checker. Extract all files first.' }
if (-not (Test-Path -LiteralPath $RestartSource)) { throw 'Missing PowerShell restarter. Extract all files first.' }
if (-not (Test-Path -LiteralPath $Main)) { throw "Local AI server not found: $Main" }
if (-not (Select-String -LiteralPath $Main -Pattern 'AUDIO_UPLOAD = install_audio_upload_api' -Quiet)) {
    throw 'Audio upload server integration was not found in main.py. No files changed.'
}
Write-Host 'Checking installed Python libraries...' -ForegroundColor Cyan
& python $DependencyCheck
if ($LASTEXITCODE -ne 0) { throw 'Python WebSocket requirements are missing. No server files changed.' }

$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Backup = Join-Path $ServerDir ("main.before_Bee_PTT_migration_$Stamp.py")
Copy-Item -LiteralPath $Main -Destination $Backup -ErrorAction Stop
Write-Host "main.py backup: $Backup"

try {
    Copy-Item -LiteralPath (Join-Path $Src 'ptt_relay_api.py') -Destination $Module -Force
    Copy-Item -LiteralPath (Join-Path $Src 'ptt_pair_code.py') -Destination $Pair -Force
    Copy-Item -LiteralPath (Join-Path $Src 'Bee_PTT_Pair.cmd') -Destination $PairCmd -Force
    Copy-Item -LiteralPath $RestartSource -Destination $RestartDest -Force
    Copy-Item -LiteralPath (Join-Path $Src 'Bee_PTT_Restart.cmd') -Destination $RestartCmd -Force

    $Body = [IO.File]::ReadAllText($Main, [Text.Encoding]::UTF8)
    $Marker = '# BEE PTT WEBSOCKET RELAY v0.7'
    if (-not $Body.Contains($Marker)) {
        $Addition = @'
# BEE PTT WEBSOCKET RELAY v0.7
from ptt_relay_api import install_ptt_relay_api
BEE_PTT_RELAY = install_ptt_relay_api(app, AUDIO_UPLOAD.token)
'@
        [IO.File]::AppendAllText($Main, [Environment]::NewLine + $Addition + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    }
    & python -m py_compile $Main $Module $Pair
    if ($LASTEXITCODE -ne 0) { throw 'Python syntax test failed.' }
    Write-Host 'Python syntax test: OK' -ForegroundColor Green
} catch {
    Copy-Item -LiteralPath $Backup -Destination $Main -Force
    throw "Install failed. main.py was restored. $_"
}

Write-Host 'Restarting Local AI directly, without the old BAT file...' -ForegroundColor Cyan
try {
    & $RestartSource -ServerDir $ServerDir
} catch {
    Write-Warning "Windows server restart did not finish: $_"
    Write-Warning "Existing main.py backup: $Backup"
    Write-Warning "To retry without reinstalling, run: $RestartCmd"
    throw
}
Write-Host ''
Write-Host 'INSTALLATION SUCCESS: Windows internet PTT relay is online.' -ForegroundColor Green
Write-Host "Pair each phone with: $PairCmd"
Write-Host "Use this to restart later: $RestartCmd"
