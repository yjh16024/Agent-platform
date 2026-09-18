#!/usr/bin/env bash
# ============================================================
# Agent Platform - Linux/macOS one-click startup
# Flow: JDK check -> (optional) build -> run Core on 8081
# Prereq: JDK 21+, Maven 3.9+, local MySQL running
#         (db agent_platform, user agent/agent123456, or override env below)
# Usage:  ./start-core.sh            # start using existing jar
#         ./start-core.sh rebuild    # force rebuild then start
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# ---- 0. Locate Java & enforce JDK 21+ ----
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_CMD="${JAVA_CMD:-java}"
if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
  echo "[ERROR] java not found: $JAVA_CMD"; exit 1
fi
JAVA_VERSION="$("$JAVA_CMD" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
JAVA_MAJOR="${JAVA_VERSION:-0}"
if [ "$JAVA_MAJOR" -lt 21 ]; then
  echo "[ERROR] 检测到 JDK $JAVA_MAJOR，本项目需要 JDK 21+（使用 ScopedValue 预览特性）。"; exit 1
fi
echo "[OK] Java $JAVA_MAJOR detected: $JAVA_CMD"

# ---- 1. Locate Maven ----
MVN_CMD="${MAVEN_HOME:+$MAVEN_HOME/bin/mvn}"
MVN_CMD="${MVN_CMD:-mvn}"
if ! command -v "$MVN_CMD" >/dev/null 2>&1; then
  echo "[ERROR] maven not found: $MVN_CMD"; exit 1
fi
echo "[OK] Maven: $MVN_CMD"

# ---- 2. Kill stale instance on 8081 (optional) ----
if command -v lsof >/dev/null 2>&1; then
  PID="$(lsof -ti tcp:8081 || true)"
  if [ -n "$PID" ]; then echo "[INFO] kill stale process on 8081: $PID"; kill -9 $PID || true; fi
fi

# ---- 3. DB connection defaults (override via env) ----
export MYSQL_URL="${MYSQL_URL:-jdbc:mysql://localhost:3306/agent_platform?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}"
export MYSQL_USER="${MYSQL_USER:-agent}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-agent123456}"

# ---- 4. Build when jar missing or explicit rebuild ----
JAR_FILE="agent-platform-core/target/agent-platform-core-1.1.0.jar"
if [ "${1:-}" = "rebuild" ] || [ ! -f "$JAR_FILE" ]; then
  echo "[2/3] Building (online first, fallback offline)..."
  # ① 在线优先：可取新依赖；② 失败降级离线 -o：本地仓库已预热时可复现构建
  "$MVN_CMD" -q -pl agent-platform-core -am package -DskipTests || "$MVN_CMD" -q -pl agent-platform-core -am package -DskipTests -o
else
  echo "[2/3] jar already built, skip build. Force rebuild with: ./start-core.sh rebuild"
fi

# ---- 5. Start core ----
echo "[3/3] Starting core service on port 8081..."
exec "$JAVA_CMD" --enable-preview -XX:+UseZGC -Xms256m -Xmx1g -jar "$JAR_FILE"
