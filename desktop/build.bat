@echo off
REM ============================================================
REM Agent Platform - desktop build (Windows)
REM Flow: 1) jlink JRE runtime  2) backend fat jar  3) electron-builder portable
REM First-time only: cd desktop && npm install --include=dev
REM   (npm 11 needs: npm install-scripts approve electron electron-builder electron-winstaller)
REM ============================================================
setlocal
cd /d "%~dp0"

set "USER_HOME=%USERPROFILE%"

REM ---- 1. locate JDK 21 ----
set "JAVA_HOME="
if exist "%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1" set "JAVA_HOME=%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1"
if not defined JAVA_HOME if exist "C:\Program Files\Java\jdk-21.0.12" set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12"
if not defined JAVA_HOME (
    echo [ERROR] JDK 21 not found under tools\jdk21 or Program Files\Java\jdk-21.0.12
    goto :fail
)

REM ---- 2. locate maven ----
set "MVN=mvn"
if exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" set "MVN=%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd"

echo [1/4] jlink JRE runtime (skip if exists)...
if not exist "runtime\bin\java.exe" (
    "%JAVA_HOME%\bin\jlink.exe" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net --output "runtime" --strip-debug --no-header-files --no-man-pages
    if errorlevel 1 goto :fail
) else (
    echo [INFO] runtime already exists, skip jlink (delete runtime to rebuild)
)

echo [2/4] build backend jar (from repo root)...
cd /d "%~dp0\.."
call "%MVN%" -q -pl agent-platform-core -am package -DskipTests
if errorlevel 1 goto :fail

echo [3/4] electron-builder portable...
cd /d "%~dp0"
if not exist "node_modules\electron\dist\electron.exe" (
    echo [ERROR] electron not installed. Run: npm install --include=dev  ^(then approve install-scripts^)
    goto :fail
)
set "ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/"
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/"
call npx electron-builder --win portable
if errorlevel 1 goto :fail

echo [4/4] done. Artifact:
for %%f in (dist\*.exe) do echo   %%~ff
goto :eof

:fail
echo [ERROR] build failed.
pause
endlocal
