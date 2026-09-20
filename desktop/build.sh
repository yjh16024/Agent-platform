#!/usr/bin/env bash
# Agent Platform - desktop build (Linux/macOS)
# Flow: 1) jlink JRE runtime  2) backend fat jar
#       3) electron-builder dir, no archive  4) rename win-unpacked -> dist/green
# Output: dist/green/Agent Platform (run directly, no self-extract)
# First-time only: cd desktop && npm install --include=dev
set -euo pipefail
cd "$(dirname "$0")"

# ---- guard: refuse to build while the desktop app is running ----
# Step 4 replaces dist/green, which is locked while the app runs.
if pgrep -f "Agent Platform" >/dev/null 2>&1; then
  echo "[ERROR] Agent Platform is running. Close the desktop app and retry."
  exit 1
fi

# ---- 1. JDK 21 ----
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[ERROR] JAVA_HOME must point to JDK 21"
  exit 1
fi

echo "[1/5] jlink JRE runtime (skip if exists)..."
# 2026-09-20 瘦身，与 build.bat 的参数**逐字保持一致**（两套脚本产出的 runtime 必须同构）：
#   --include-locales=zh,en  ：jdk.localedata 默认带全套语言数据，我们只用中英
#   --compress=zip-6         ：JDK 21 起的 zip-N 压缩档位
#   去掉 jdk.crypto.cryptoki ：PKCS#11 硬件加密（智能卡/USB Key），桌面用不到
#                             注意 jdk.crypto.ec 必须保留 —— TLS 走它
# 模块集刻意不动（仍为 java.se 全集）：换具体模块集的风险远大于收益。
# 实测 110.4 MB -> 50.7 MB。
if [ ! -f "runtime/bin/java" ]; then
  "$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.net \
    --include-locales=zh,en --compress=zip-6 \
    --output runtime --strip-debug --no-header-files --no-man-pages
fi

echo "[2/5] build backend jar..."
# Clean only the staged static dir, not the whole target: Maven's resources plugin never removes
# stale bundles from target/classes/static (they end up inside the jar), while a full "clean"
# throws away incremental compilation and makes every build a cold one.
# NOTE: because we skip "clean", a DELETED/RENAMED java source can leave a stale .class behind.
# After such a change run once: mvn -pl agent-platform-core -am clean package -DskipTests
rm -rf ../agent-platform-core/target/classes/static
(cd .. && mvn -q -pl agent-platform-core -am package -DskipTests)

echo "[3/5] electron-builder unpacked dir, no archive..."
if [ ! -d node_modules/electron ]; then
  echo "[ERROR] electron not installed. Run: cd desktop && npm install --include=dev"
  exit 1
fi
export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
export ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"
npx electron-builder --win dir

echo "[4/5] rename dist/win-unpacked -> dist/green - same volume, instant ..."
if [ ! -d dist/win-unpacked ]; then
  echo "[ERROR] electron-builder did not produce dist/win-unpacked"
  exit 1
fi
rm -rf dist/green
mv dist/win-unpacked dist/green

echo "[5/5] done. Green build: dist/green (run directly)"
echo "      no zip by design - pack one manually only when you need to ship it:"
echo "      cd dist && zip -r green.zip green"
