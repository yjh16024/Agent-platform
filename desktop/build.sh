#!/usr/bin/env bash
# Agent Platform - desktop build (Linux/macOS)
# Flow: 1) jlink JRE runtime  2) backend fat jar
#       3) electron-builder green zip  4) sync dist/green
# Output: dist/green/Agent Platform (run directly, no self-extract)
# First-time only: cd desktop && npm install --include=dev
set -euo pipefail
cd "$(dirname "$0")"

# ---- 1. JDK 21 ----
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[ERROR] JAVA_HOME must point to JDK 21"
  exit 1
fi

echo "[1/5] jlink JRE runtime (skip if exists)..."
if [ ! -f "runtime/bin/java" ]; then
  "$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net \
    --output runtime --strip-debug --no-header-files --no-man-pages
fi

echo "[2/5] build backend jar..."
(cd .. && mvn -q -pl agent-platform-core -am package -DskipTests)

echo "[3/5] electron-builder green zip..."
if [ ! -d node_modules/electron ]; then
  echo "[ERROR] electron not installed. Run: cd desktop && npm install --include=dev"
  exit 1
fi
export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
export ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"
npx electron-builder --win zip

echo "[4/5] sync green dir: dist/green ..."
rm -rf dist/green
cp -r dist/win-unpacked dist/green

echo "[5/5] done. Green build: dist/green (run directly); zip: dist/*.zip"
