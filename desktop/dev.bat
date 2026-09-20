@echo off
REM ============================================================
REM Agent Platform - desktop DEVELOPMENT launcher
REM
REM What it does: starts the packaged desktop shell, but points it at
REM the repo's freshly built jar + static resources. So you do NOT have
REM to re-run desktop\build.bat after every code change.
REM
REM   1) edit frontend ->  cd agent-platform-ui ^&^& npm run build:prod
REM                        then press Ctrl+R in the app window (backend keeps running)
REM   2) edit backend  ->  mvn -pl agent-platform-core -am package -DskipTests
REM                        then press Ctrl+Shift+R in the app window
REM
REM Optional: set AP_REUSE_BACKEND=1 to attach to a backend you started
REM yourself (e.g. mvn spring-boot:run) instead of spawning one.
REM
REM Requires: dist\green must already exist (run desktop\build.bat once).
REM ============================================================
setlocal

pushd "%~dp0.."
set "REPO=%CD%"
popd

set "AP_DEV=1"
set "AP_DEV_JAR=%REPO%\agent-platform-core\target\agent-platform-core-1.1.0.jar"
set "AP_DEV_STATIC=%REPO%\agent-platform-core\src\main\resources\static"

if not exist "%AP_DEV_JAR%" (
  echo [dev] backend jar not found:
  echo       %AP_DEV_JAR%
  echo [dev] build it first:  mvn -pl agent-platform-core -am package -DskipTests
  pause
  exit /b 1
)

if not exist "%~dp0dist\green\Agent Platform.exe" (
  echo [dev] desktop build not found.
  echo [dev] run desktop\build.bat once first.
  pause
  exit /b 1
)

echo [dev] AP_DEV_JAR    = %AP_DEV_JAR%
echo [dev] AP_DEV_STATIC = %AP_DEV_STATIC%
echo [dev] Ctrl+R reloads the UI ^| Ctrl+Shift+R restarts the backend
echo.

start "" "%~dp0dist\green\Agent Platform.exe"
