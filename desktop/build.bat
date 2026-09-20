@echo off
REM ============================================================
REM Agent Platform - desktop build (Windows)   [one-click full pipeline]
REM
REM Flow: 1) build frontend, sync into core static
REM       2) jlink JRE runtime, skipped if already present
REM       3) backend fat jar, clean by default
REM       4) electron-builder dir target, no archive
REM       5) rename dist\win-unpacked -> dist\green
REM
REM Output: dist\green\Agent Platform.exe    run directly, no self-extract
REM
REM Usage:
REM   build.bat          full build: frontend + clean backend + desktop shell
REM   build.bat fast     quick: keep frontend, incremental backend, no clean
REM   build.bat no-ui    middle: rebuild backend + shell only, keep frontend
REM
REM Why "clean" is the default:
REM   Maven never removes stale output, and build.bat intentionally skips "clean" to keep
REM   incremental compilation. The cost is that a DELETED or RENAMED java source leaves its
REM   old .class inside the jar, so the removed feature comes back to life at runtime.
REM   A full clean costs about a minute and removes this entire class of bug.
REM
REM Note: this script does NOT rebuild the desktop shell itself. If you changed
REM       desktop\main.js you must still run this script - it is the only thing that
REM       refreshes dist\green\resources\app.asar.
REM
REM First-time only: cd desktop and run npm install --include=dev
REM ============================================================
setlocal
cd /d "%~dp0"

set "ROOT=%~dp0.."
set "BUILD_UI=1"
set "FAST="
for %%a in (%*) do (
    if /i "%%a"=="fast" set "FAST=1"
    if /i "%%a"=="no-ui" set "BUILD_UI=0"
)

REM ---- guard: refuse to build while the desktop app is running ----
REM Step 5 replaces dist\green, which is locked while the app runs. Without this
REM check the rmdir fails silently and "move" would nest win-unpacked inside green.
tasklist /fi "IMAGENAME eq Agent Platform.exe" 2>nul | findstr /i /c:"Agent Platform.exe" >nul
if not errorlevel 1 (
    echo [ERROR] Agent Platform.exe is running. Close the desktop app and retry.
    goto :fail
)

set "USER_HOME=%USERPROFILE%"

REM ---- locate JDK 21 ----
set "JAVA_HOME="
if exist "%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1" set "JAVA_HOME=%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1"
if not defined JAVA_HOME if exist "C:\Program Files\Java\jdk-21.0.12" set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12"
if not defined JAVA_HOME (
    echo [ERROR] JDK 21 not found under tools\jdk21 or Program Files\Java\jdk-21.0.12
    goto :fail
)

REM ---- locate maven ----
set "MVN=mvn"
if exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" set "MVN=%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd"

echo [1/5] build frontend and sync into core static ...
if "%BUILD_UI%"=="1" (
    cd /d "%ROOT%\agent-platform-ui"
    call npm run build:prod
    if errorlevel 1 (
        echo [ERROR] frontend build failed. Check that node and npm are on PATH.
        cd /d "%~dp0"
        goto :fail
    )
    cd /d "%~dp0"
) else (
    echo [INFO] skipped by no-ui, keeping current frontend bundle
)

echo [2/5] jlink JRE runtime, skip if exists ...
if not exist "runtime\bin\java.exe" (
    "%JAVA_HOME%\bin\jlink.exe" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net --output "runtime" --strip-debug --no-header-files --no-man-pages
    if errorlevel 1 goto :fail
) else (
    echo [INFO] runtime already exists, skip jlink. Delete runtime to rebuild it.
)

echo [3/5] build backend jar ...
cd /d "%ROOT%"
if defined FAST (
    echo [INFO] fast mode: incremental build, stale .class may survive
    call "%MVN%" -q -pl agent-platform-core -am package -DskipTests
) else (
    call "%MVN%" -q -pl agent-platform-core -am clean package -DskipTests
)
if errorlevel 1 goto :fail
cd /d "%~dp0"

echo [4/5] electron-builder dir target, no archive ...
if not exist "node_modules\electron\dist\electron.exe" (
    echo [ERROR] electron not installed. Run: npm install --include=dev
    goto :fail
)
set "ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/"
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/"
call npx electron-builder --win dir
if errorlevel 1 goto :fail

echo [5/5] rename dist\win-unpacked to dist\green ...
if not exist "dist\win-unpacked" (
    echo [ERROR] electron-builder did not produce dist\win-unpacked
    goto :fail
)
if exist "dist\green" rmdir /s /q "dist\green"
move "dist\win-unpacked" "dist\green" >nul
if errorlevel 1 goto :fail

echo.
echo [OK] build finished. Artifacts:
echo   app     : dist\green\Agent Platform.exe
for %%F in ("dist\green\resources\backend\app.jar") do echo   backend : %%~tF
for %%F in ("dist\green\resources\app.asar") do echo   shell   : %%~tF
echo.
echo   For day-to-day code changes you do NOT need this script.
echo   Use desktop\dev.bat instead: Ctrl+R reloads the UI,
echo   Ctrl+Shift+R restarts the backend, window stays open.
echo.
echo   No zip by design. Pack one manually only when shipping:
echo   powershell -c "Compress-Archive -Path dist\green -DestinationPath dist\green.zip"
goto :eof

:fail
echo [ERROR] build failed.
pause
endlocal
exit /b 1
