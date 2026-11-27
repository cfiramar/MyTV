@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py -3 setup_wizard.py %*
    goto :eof
)
python setup_wizard.py %*
