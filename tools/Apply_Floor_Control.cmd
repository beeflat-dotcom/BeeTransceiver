@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Apply_Floor_Control.ps1"
if errorlevel 1 echo Floor update failed or rolled back. Read the message above.
pause
