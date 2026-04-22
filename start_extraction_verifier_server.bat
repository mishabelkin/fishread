@echo off
setlocal

set SCRIPT_DIR=%~dp0
where py >nul 2>&1
if %ERRORLEVEL% EQU 0 (
  py -3 "%SCRIPT_DIR%tools\start_extraction_verifier_server.py" %*
) else (
  python "%SCRIPT_DIR%tools\start_extraction_verifier_server.py" %*
)
set EXIT_CODE=%ERRORLEVEL%

endlocal & exit /b %EXIT_CODE%
