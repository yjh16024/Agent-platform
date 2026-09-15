@echo off
REM ============================================================
REM Agent Platform - desktop build (Windows)
REM Flow: 1) jlink JRE runtime  2) backend fat jar
REM       3) electron-builder dir, no archive  4) rename win-unpacked -> dist\green
REM Output: dist\green\Agent Platform.exe  (run directly, no self-extract)
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

echo [1/5] jlink JRE runtime (skip if exists)...
if not exist "runtime\bin\java.exe" (
    "%JAVA_HOME%\bin\jlink.exe" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net --output "runtime" --strip-debug --no-header-files --no-man-pages
    if errorlevel 1 goto :fail
) else (
    echo [INFO] runtime already exists, skip jlink (delete runtime to rebuild)
)

echo [2/5] build backend jar (from repo root)...
cd /d "%~dp0\.."
REM Clean only the staged static dir, not the whole target: Maven's resources plugin never
REM removes stale bundles from target\classes\static (they end up inside the jar), while a full
REM "clean" would throw away incremental compilation and make every build a cold one.
REM NOTE: because we skip "clean", a DELETED/RENAMED java source can leave a stale .class behind.
REM After such a change run once: mvn -pl agent-platform-core -am clean package -DskipTests
if exist "agent-platform-core\target\classes\static" rmdir /s /q "agent-platform-core\target\classes\static"
call "%MVN%" -q -pl agent-platform-core -am package -DskipTests
if errorlevel 1 goto :fail

echo [3/5] electron-builder unpacked dir, no archive...
cd /d "%~dp0"
if not exist "node_modules\electron\dist\electron.exe" (
    echo [ERROR] electron not installed. Run: npm install --include=dev then approve install-scripts
    goto :fail
)
set "ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/"
set "ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/"
call npx electron-builder --win dir
if errorlevel 1 goto :fail

echo [4/5] rename dist\win-unpacked to dist\green - same volume, instant ...
if not exist "dist\win-unpacked" (
    echo [ERROR] electron-builder did not produce dist\win-unpacked
    goto :fail
)
if exist "dist\green" rmdir /s /q "dist\green"
move "dist\win-unpacked" "dist\green" >nul
if errorlevel 1 goto :fail

echo [5/5] done. Artifacts:
echo   dist\green\Agent Platform.exe   run this directly, green build, no self-extract
echo.
echo   no zip by design - pack one manually only when you need to ship it:
echo   powershell -c "Compress-Archive -Path dist\green -DestinationPath dist\green.zip"
goto :eof

::fail
echo [ERROR] build failed.
pause
endlocal
