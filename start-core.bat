@echo off
REM ============================================================
REM Agent Platform - Windows one-click startup
REM Flow: JDK check -> kill stale instance -> start local Redis
REM       -> (optional) build -> run Core on 8081
REM Prereq: JDK 21+, Maven 3.9+ on PATH or JAVA_HOME/MAVEN_HOME,
REM         local MySQL running (db agent_platform, user agent/agent123456)
REM Usage:  double-click, or in cmd run:  start-core.bat
REM         start-core.bat rebuild   force rebuild before start
REM ============================================================
setlocal
cd /d "%~dp0"

REM ---- 0. Locate JAVA ----
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if exist "%USERPROFILE%\tools\jdk21\jdk-21.0.12.1+1\bin\java.exe" set "JAVA_CMD=%USERPROFILE%\tools\jdk21\jdk-21.0.12.1+1\bin\java.exe"

REM ---- 0.5 Enforce JDK 21+ (project uses --enable-preview / ScopedValue) ----
set "JAVA_MAJOR="
for /f "delims=" %%v in ('powershell -NoProfile -Command "$l=(&'%JAVA_CMD%' -version 2>&1 | Select-String 'version \"([0-9]+)'); if($l){$l.Matches[0].Groups[1].Value}"') do set "JAVA_MAJOR=%%v"
if not defined JAVA_MAJOR (
    echo [ERROR] 无法获取 Java 版本：%JAVA_CMD%
    echo        请安装 JDK 21 并设置 JAVA_HOME，或将 java 加入 PATH。
    goto :fail
)
if %JAVA_MAJOR% LSS 21 (
    echo [ERROR] 检测到 Java 版本为 %JAVA_MAJOR%，本项目需要 JDK 21 或更高（使用 ScopedValue 预览特性）。
    echo        请安装 JDK 21 后重试。
    goto :fail
)
echo [OK] Java %JAVA_MAJOR% detected: %JAVA_CMD%

REM ---- 1. Locate Maven ----
set "MVN_CMD=mvn"
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if exist "%USERPROFILE%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd" set "MVN_CMD=%USERPROFILE%\tools\maven\apache-maven-3.9.9\bin\mvn.cmd"
set "JAVA_HOME="
for %%D in ("%JAVA_CMD%") do set "JAVA_BIN=%%~dpD"
for %%D in ("%JAVA_BIN%..") do set "JAVA_HOME=%%~fD"
set "PATH=%JAVA_BIN%;%PATH%"

REM ---- 2. Kill stale instance on 8081 (jar lock workaround) ----
set "KILL_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8081" ^| findstr "LISTENING"') do set "KILL_PID=%%P"
if defined KILL_PID (
    echo [INFO] port 8081 already in use by PID %KILL_PID%, killing stale process...
    taskkill /F /PID %KILL_PID% >nul 2>&1
    timeout /t 2 /nobreak >nul
)

REM ---- 3. Start local Redis (portable only; optional) ----
set "REDIS_HOME="
for /r "%USERPROFILE%\tools\redis" %%D in (.) do if exist "%%D\redis-server.exe" set "REDIS_HOME=%%D"
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
    echo [1/3] Redis not found, skip. Redis is OPTIONAL (quota falls back to memory).
)

REM ---- 4. DB connection defaults (override via env) ----
if not defined MYSQL_URL set "MYSQL_URL=jdbc:mysql://localhost:3306/agent_platform?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
if not defined MYSQL_USER set "MYSQL_USER=agent"
if not defined MYSQL_PASSWORD set "MYSQL_PASSWORD=agent123456"

REM ---- 5. Build when jar missing or explicit rebuild ----
set "JAR_FILE=agent-platform-core\target\agent-platform-core-1.0.0-SNAPSHOT.jar"
set "NEED_BUILD="
if /i "%~1"=="rebuild" set "NEED_BUILD=1"
if not exist "%JAR_FILE%" set "NEED_BUILD=1"

if defined NEED_BUILD (
    echo [2/3] Building (first run needs network to download dependencies)...
    call "%MVN_CMD%" -q -pl agent-platform-core -am package -DskipTests -o
    if errorlevel 1 (
        echo [WARN] offline build failed, trying online build...
        call "%MVN_CMD%" -q -pl agent-platform-core -am package -DskipTests
        if errorlevel 1 goto :fail
    )
) else (
    echo [2/3] jar already built, skip build. Force rebuild with: start-core.bat rebuild
)

REM ---- 6. Start core ----
echo [3/3] Starting core service on port 8081...
"%JAVA_CMD%" --enable-preview -XX:+UseZGC -Xms256m -Xmx1g -jar "%JAR_FILE%"
if errorlevel 1 goto :fail
goto :eof

:fail
echo.
echo [ERROR] startup failed, check log above.
pause
endlocal
