@echo off
chcp 65001 > nul
cd /d "%~dp0"
start "" /min cmd /c "ping -n 8 127.0.0.1 >nul & start http://localhost:8787/"
java LedgerServer.java
pause
