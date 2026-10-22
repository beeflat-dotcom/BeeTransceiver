@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup_Signed_Updates.ps1"
if errorlevel 1 echo Signing setup failed. Check the message above.
pause
