#!/usr/bin/env bash
# ============================================================
# 前端仪表盘构建脚本（Linux/macOS）
# 安装依赖 -> 构建 -> 同步产物到 core 静态资源目录
# Usage: ./build-ui.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/agent-platform-ui"

echo "[1/2] 安装前端依赖（首次较慢）..."
npm install

echo "[2/2] 构建并同步到 agent-platform-core/src/main/resources/static ..."
npm run build:prod

echo ""
echo "前端已构建并同步完成。启动 core 后访问 http://localhost:8081/"
