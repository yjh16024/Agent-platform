@echo off
REM ============================================================
REM Agent Platform - Dependency warmup script (run ONLINE)
REM Purpose: cache all dependencies and plugins into the local
REM          repository so that `mvn -o` builds stay reproducible
REM          even when the network or the mirror is down.
REM Steps : 1) go-offline   2) online package   3) verify with -o
REM Usage : warmup.bat          warm up, then verify
REM         warmup.bat verify   verify offline build only, no network
REM Note  : re-run this after adding new dependencies or on a new machine
REM ============================================================
setlocal
cd /d "%~dp0"

set "USER_HOME=%USERPROFILE%"

REM ---- 0. Locate portable JDK 21 / Maven under tools (optional) ----
set "JAVA_HOME="
if exist "%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1" set "JAVA_HOME=%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if not defined JAVA_HOME echo [WARN] portable JDK 21 not found, using system java - need JDK 21+
if exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" set "PATH=%USER_HOME%\tools\maven\apache-maven-3.9.9\bin;%PATH%"
if not exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" echo [WARN] portable Maven not found, using system mvn

if /i "%~1"=="verify" goto :verify

echo [1/3] Pulling dependencies and plugins - online, first run may take a while...
call mvn -q -pl agent-platform-core -am dependency:go-offline -DincludeScope=test
if errorlevel 1 echo [WARN] go-offline reported errors, continuing - some plugins are fetched during package

echo [2/3] Online build once - fetch remaining plugins...
call mvn -q -pl agent-platform-core -am package -DskipTests
if errorlevel 1 goto :fail_online

:verify
echo [3/3] Verifying offline build with mvn -o ...
call mvn -o -q -pl agent-platform-core -am package -DskipTests
if errorlevel 1 goto :fail_offline

echo.
echo [OK] Warmup done. Offline build works:  mvn -o -q -pl agent-platform-core -am package -DskipTests
echo [OK] Start the app with:  start-core.bat     rebuild: start-core.bat rebuild
endlocal
exit /b 0

:fail_online
echo.
echo [ERROR] Online build failed - check network or mirror. Aliyun mirror is preset in settings.xml.
endlocal
exit /b 1

:fail_offline
echo.
echo [ERROR] Offline build failed - dependencies not fully cached, re-run: warmup.bat
endlocal
exit /b 1
