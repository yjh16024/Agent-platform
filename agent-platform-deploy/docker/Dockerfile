# ============================================================
# 白雾·智能体交互平台 · 多阶段构建 Dockerfile（JDK 21）
# ============================================================

# ---- 阶段 1：构建（Maven + JDK 21）----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先拷贝 POM 层，利用 Docker 缓存加速依赖下载
COPY pom.xml .
COPY agent-platform-common/pom.xml agent-platform-common/
COPY agent-platform-model/pom.xml agent-platform-model/
COPY agent-platform-plugin-sdk/pom.xml agent-platform-plugin-sdk/
COPY agent-platform-infra/pom.xml agent-platform-infra/
COPY agent-platform-gateway/pom.xml agent-platform-gateway/
COPY agent-platform-core/pom.xml agent-platform-core/

# 下载依赖（离线路由，规避网络抖动）
RUN mvn -B -q dependency:go-offline || true

# 拷贝源码并打包（跳过测试，测试在 CI 阶段跑）
COPY . .
RUN mvn -B -q package -DskipTests

# ---- 阶段 2：运行时（JDK 21 JRE 精简镜像）----
FROM eclipse-temurin:21-jre-ubi9-minimal
LABEL maintainer="agent-platform"
LABEL description="白雾·智能体交互平台 · 核心调度层"

WORKDIR /app

# 非 root 运行（安全加固）
RUN groupadd -r app && useradd -r -g app app

# 分代 ZGC + 容器感知 + 插件 SPI 反射
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational \
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 \
--enable-preview \
--add-opens java.base/java.lang=ALL-UNNAMED"

# 拷贝制品
COPY --from=builder /build/agent-platform-core/target/agent-platform-core-*.jar app.jar

USER app

EXPOSE 8081
EXPOSE 9091

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]