#!/usr/bin/env bash
# Agent Platform - desktop build (Linux/macOS)
# Flow: 1) jlink JRE runtime  2) backend fat jar  3) electron-builder (win portable on win)
# First-time only: cd desktop && npm install --include=dev
set -euo pipefail
cd "$(dirname "$0")"

# ---- 1. JDK 21 ----
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[ERROR] JAVA_HOME must point to JDK 21"
  exit 1
fi

echo "[1/3] jlink JRE runtime (skip if exists)..."
if [ ! -f "runtime/bin/java" ]; then
  "$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management \
    --output runtime --strip-debug --no-header-files --no-man-pages
fi

echo "[2/3] build backend jar..."
(cd .. && mvn -q -pl agent-platform-core -am package -DskipTests)

echo "[3/3] electron-builder..."
if [ ! -d node_modules/electron ]; then
  echo "[ERROR] electron not installed. Run: cd desktop && npm install --include=dev"
  exit 1
fi
export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
export ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"
npx electron-builder --win portable
echo "Done. See dist/*.exe"
