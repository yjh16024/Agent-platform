@echo off
REM ============================================================
REM Agent Platform - Windows one-click startup
REM Flow: kill stale instance -> start local Redis -> optional build -> run Core 8081
REM Prereq: JDK 21 (project uses Java preview feature, run requires --enable-preview),
REM         Maven 3.9+, local MySQL running (db agent_platform, user agent)
REM Usage:  double-click, or in cmd run:  start-core.bat
REM         start-core.bat rebuild   force rebuild before start
REM ============================================================
setlocal

rem change to script dir so relative paths (pom.xml / target) resolve
cd /d "%~dp0"

set "USER_HOME=%USERPROFILE%"

REM ---- 1. Locate portable JDK 21 / Maven under tools (optional) ----
set "JAVA_HOME="
if exist "%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1" set "JAVA_HOME=%USER_HOME%\tools\jdk21\jdk-21.0.12.1+1"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if not defined JAVA_HOME echo [WARN] portable JDK 21 not found, falling back to system java (need JDK 21+)
if exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" set "PATH=%USER_HOME%\tools\maven\apache-maven-3.9.9\bin;%PATH%"
if not exist "%USER_HOME%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" echo [WARN] portable Maven not found, falling back to system mvn

REM ---- 2. Kill stale instance - port 8081 busy means old process still running ----
REM       old instance locks the jar, causing "Unable to rename" during repackage
set "KILL_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8081" ^| findstr "LISTENING"') do set "KILL_PID=%%P"
if defined KILL_PID (
    echo [INFO] port 8081 already in use by PID %KILL_PID%, killing stale process...
    taskkill /F /PID %KILL_PID% >nul 2>&1
    timeout /t 2 /nobreak >nul
)

REM ---- 3. Start local Redis - portable, only if 6379 not listening ----
set "REDIS_HOME="
for /r "%USER_HOME%\tools\redis" %%D in (.) do if exist "%%D\redis-server.exe" set "REDIS_HOME=%%D"
if defined REDIS_HOME (
    netstat -ano | findstr ":6379" | findstr "LISTENING" >nul 2>&1
    if errorlevel 1 (
        echo [1/3] Starting local Redis...
        start "agent-redis" /min /D "%REDIS_HOME%" redis-server.exe --port 6379
        timeout /t 2 /nobreak >nul
    ) else (
        echo [1/3] Redis already running, skip
    )
) else (
    echo [1/3] portable Redis not found under tools\redis, skip.
    echo [1/3] Redis is OPTIONAL: health shows redis DOWN, core features still work.
)

REM ---- 4. DB connection - db agent_platform, user agent ----
set "MYSQL_URL=jdbc:mysql://localhost:3306/agent_platform?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
set "MYSQL_USER=agent"
set "MYSQL_PASSWORD=agent123456"

REM ---- 5. Build only when jar missing or explicit rebuild ----
set "JAR_FILE=agent-platform-core\target\agent-platform-core-1.0.0-SNAPSHOT.jar"
set "NEED_BUILD="
if /i "%~1"=="rebuild" set "NEED_BUILD=1"
if not exist "%JAR_FILE%" set "NEED_BUILD=1"

if defined NEED_BUILD (
    echo [2/3] Building (online first, fallback offline)...
    rem online first to fetch new deps; fallback to -o for reproducible offline build
    call mvn -q -pl agent-platform-core -am package -DskipTests >nul 2>&1
    if errorlevel 1 (
        echo [WARN] online build failed, falling back to offline (-o)...
        call mvn -q -pl agent-platform-core -am package -DskipTests -o
        if errorlevel 1 (
            echo [WARN] offline build failed too, retrying online after 3s...
            timeout /t 3 /nobreak >nul
            call mvn -q -pl agent-platform-core -am package -DskipTests
            if errorlevel 1 goto :fail
        )
    )
) else (
    echo [2/3] jar already built, skip build. Force rebuild with: start-core.bat rebuild
)

REM ---- 6. Start core service ----
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
echo [3/3] Starting core service on port 8081...
"%JAVA_CMD%" --enable-preview -XX:+UseZGC -Xms256m -Xmx1g -jar "%JAR_FILE%"
if errorlevel 1 goto :fail
goto :eof

:fail
echo.
echo [ERROR] build or startup failed, check log above.
pause
endlocal
