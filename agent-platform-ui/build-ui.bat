@echo off
REM ============================================================
REM 前端仪表盘构建脚本（Windows）
REM 安装依赖 -> 构建 -> 同步产物到 core 静态资源目录
REM 用法：双击，或 cmd 中运行 build-ui.bat
REM ============================================================
chcp 65001 >nul
cd /d "%~dp0"

echo [1/2] 安装前端依赖（首次较慢）...
call npm install
if errorlevel 1 (
    echo [错误] npm install 失败
    pause
    exit /b 1
)

echo [2/2] 构建并同步到 agent-platform-core\src\main\resources\static ...
call npm run build:prod
if errorlevel 1 (
    echo [错误] 构建失败
    pause
    exit /b 1
)

echo.
echo 前端已构建并同步完成。启动 core 后浏览器访问 http://localhost:8081/
pause