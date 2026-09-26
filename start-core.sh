#!/usr/bin/env bash
# ============================================================
# Agent Platform - Linux/macOS one-click startup
# Flow: JDK check -> (optional) build -> run Core on 8081 (mysql or embedded/H2)
# Prereq: JDK 21+, Maven 3.9+, local MySQL running
#         (db agent_platform, user agent/agent123456, or override env below)
#         MySQL can be skipped entirely by passing 'embedded' (built-in H2 file DB).
# Usage:  ./start-core.sh                    # start using existing jar
#         ./start-core.sh rebuild            # force rebuild then start
#         ./start-core.sh embedded           # built-in H2 file DB, no MySQL needed
#         ./start-core.sh embedded rebuild   # combine both
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

# ---- 4. Parse args: embedded / rebuild ----
# 与 start-core.bat 语义保持一致（那边是 DB_MODE 变量 + 同样两个关键字）。
# DB_MODE: mysql(默认，需本地 MySQL) | embedded(内置 H2 file 库，免装 MySQL)
DB_MODE="mysql"
NEED_BUILD=""
# 注意用 ${1+"$@"} 而非 "$@"：macOS 自带的是 bash 3.2，在 `set -u` 且**无任何参数**时
# 对 "$@" 会报 "unbound variable"（该行为到 bash 4.4 才修复），而本脚本要求兼容 macOS。
for arg in ${1+"$@"}; do
  case "$arg" in
    embedded) DB_MODE="embedded" ;;
    rebuild)  NEED_BUILD="1" ;;
    *) echo "[WARN] unknown argument ignored: $arg" ;;
  esac
done

# ---- 5. Build when jar missing or explicit rebuild ----
JAR_FILE="agent-platform-core/target/agent-platform-core-1.1.0.jar"
if [ -n "$NEED_BUILD" ] || [ ! -f "$JAR_FILE" ]; then
  echo "[2/3] Building (online first, fallback offline)..."
  # ① 在线优先：可取新依赖；② 失败降级离线 -o：本地仓库已预热时可复现构建
  "$MVN_CMD" -q -pl agent-platform-core -am package -DskipTests || "$MVN_CMD" -q -pl agent-platform-core -am package -DskipTests -o
else
  echo "[2/3] jar already built, skip build. Force rebuild with: ./start-core.sh rebuild"
fi

# ---- 6. Start core ----
if [ "$DB_MODE" = "embedded" ]; then
  echo "[INFO] DB mode: embedded (H2 file under ./data/agent-platform.mv.db, no MySQL needed)"
else
  echo "[INFO] DB mode: mysql (default, needs local MySQL running)"
fi
echo "[3/3] Starting core service on port 8081 (profile=$DB_MODE)..."
# profile 名与 application-embedded.yml 的 on-profile 对应；mysql 时传的是同名 profile
# （没有 application-mysql.yml，激活它不会覆盖任何默认配置，仅为与 .bat 行为一致）。
exec "$JAVA_CMD" --enable-preview -XX:+UseZGC -Xms256m -Xmx1g -jar "$JAR_FILE" --spring.profiles.active="$DB_MODE"
