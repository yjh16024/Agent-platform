#!/usr/bin/env bash
# Agent Platform - desktop build (Linux/macOS)
# Flow: 1) jlink JRE runtime  2) backend fat jar
#       3) electron-builder dir, no archive  4) rename win-unpacked -> dist/green
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
