#requires -version 5.1
# Safe one-time Windows PTT floor control upgrade; keeps other APIs unchanged.
param([string]$ServerDir)
$ErrorActionPreference='Stop'
if(-not $ServerDir){
    $ServerDir=Join-Path $env:USERPROFILE 'Dropbox\AI用\ローカルAI\local_ai_monitor_ver2_server'
}
$module=Join-Path $ServerDir 'ptt_relay_api.py'
$main=Join-Path $ServerDir 'main.py'
$restarter=Join-Path $ServerDir 'Restart_Bee_PTT_Server.ps1'
if(-not (Test-Path $module)){throw "Relay module not found: $module"}
if(-not (Test-Path $main)){throw "Windows FastAPI main.py not found: $main"}
if(-not (Test-Path $restarter)){throw "Restart script not found: $restarter"}
if(-not (Select-String -LiteralPath $main -Pattern 'install_ptt_relay_api' -Quiet)){
    throw 'Windows FastAPI has no PTT relay integration. Aborting safely.'
}
$url='https://raw.githubusercontent.com/beeflat-dotcom/BeeTransceiver/main/windows/ptt_relay_api.py'
$tmp=Join-Path $env:TEMP ('bee-ptt-floor-'+[guid]::NewGuid().ToString('N')+'.py')
$backup=$module+'.before_floor_'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.bak'
try{
    Write-Host 'Downloading reviewed floor-control module from GitHub...' -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp
    if(-not (Select-String -LiteralPath $tmp -Pattern 'ptt-floor-v02' -Quiet)){
        throw 'GitHub main does not yet contain the expected floor-control version. No files changed.'
    }
    & python -m py_compile $tmp
    if($LASTEXITCODE -ne 0){throw 'Python syntax validation failed. No files changed.'}
    Copy-Item -LiteralPath $module -Destination $backup -ErrorAction Stop
    Write-Host "Backup saved: $backup"
    Copy-Item -LiteralPath $tmp -Destination $module -Force
    try {
        & $restarter -ServerDir $ServerDir
        $health=Invoke-RestMethod -Uri 'http://127.0.0.1:8769/ptt/health' -TimeoutSec 12
        if(-not $health.ok -or -not $health.floor_control){throw 'Floor-control health check did not pass.'}
        Write-Host ''
        Write-Host 'BEE PTT FLOOR CONTROL ENABLED.' -ForegroundColor Green
        Write-Host "Relay version: $($health.version)"
    }catch{
        Write-Warning "New module health check failed. Restoring old relay: $_"
        Copy-Item -LiteralPath $backup -Destination $module -Force
        try { & $restarter -ServerDir $ServerDir }catch{Write-Warning "Could not restart old relay: $_"}
        throw 'Upgrade rolled back. Old relay code restored.'
    }
}finally{
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
}
