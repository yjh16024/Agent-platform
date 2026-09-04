# 智能体交互平台 · 桌面打包技术设计文档（补充）

> 版本：v1.0 · 日期：2026-09-04 · 关联文档： [可行性分析报告](desktop-packaging-feasibility-report.md)、[仪表盘技术设计](dashboard-technical-design.md)

本文是「打包为可分发应用」的**补充技术设计**，与[可行性分析报告](desktop-packaging-feasibility-report.md)成对阅读，聚焦阶段 2 的 `jpackage` 原生安装包方案；阶段 1（Docker 镜像）为同一产物结构的容器化前置，改动更少。

## 1. 目标产物结构

```
agent-platform-<ver>-win-macos-linux/
├── agent-platform.exe / agent-platform            # 启动器（jpackage 生成）
├── runtime/                                       # jlink 裁出的最小 JRE（含 --enable-preview）
├── app/
│   ├── agent-platform-core.jar                    # Spring Boot 可执行 jar
│   └── static/                                    # 前端构建产物（同源托管，现状沿用）
├── lib/                                           # 平台原生依赖（如有）
└── uninstall                                      # 卸载器（jpackage 内建）
```

进程模型：**单进程**——`agent-platform-core.jar` 同时提供 `static/`（前端）与 `/api/v1/**`（后端），用户浏览器打开 `http://localhost:<port>/` 或桌面壳默认地址。复用现状「同源直连、免 CORS」的结构，无新进程编排（阶段 3 引入桌面壳后再讨论 sidecar）。

## 2. 配置分发包（`application-desktop` Profile）

新增 `agent-platform-core/src/main/resources/application-desktop.yml`（或打包脚本外用覆盖），关键差异：

```yaml
spring:
  datasource:
    url: jdbc:h2:file:${APP_DATA_DIR:./data}/agent_platform;MODE=MySQL;AUTO_SERVER=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
  flyway:
    locations: classpath:db/migration         # 复用现有迁移，见 §3 方言说明
  data:
    redis:                                     # 单机版移除 Redis，走内存实现
      host: ""
agent-platform:
  events:
    enabled: false
  rag:
    vector-store: in-memory
  storage:
    type: local
    local-dir: ${APP_DATA_DIR:./data}/files
  plugin:
    artifact-dir: ${APP_DATA_DIR:./data}/plugins
  # 模型 API Key 主密钥：首发向导写入，禁止在发布版硬编码 dev 默认值
  secrets:
    model-key: ${MODEL_KEY_ENC_KEY:}
```

数据目录统一由启动器解析为 OS 用户数据目录并注入 `APP_DATA_DIR`：Windows `%APPDATA%\agent-platform`、macOS `~/Library/Application Support/agent-platform`、Linux `$XDG_DATA_HOME/agent-platform`。

## 3. 基础设施内置化（关键改造）

### 3.1 MySQL → 嵌入式 H2（`MODE=MySQL`）

- 引入 `com.h2database:h2`（runtime），`datasource.url` 指向上面的 file 模式；H2 的 `MODE=MySQL` 兼容大部分 MySQL 语法。
- **Flyway 迁移适配**：现有 `V1~V8` 中的 MySQL 专属写法需在 H2 下验证/调整——
  - `MEDIUMTEXT` → `TEXT`；`DATETIME(3)` → `TIMESTAMP(3)`；
  - `JSON` 列在 H2 下可沿用 `JSON` 类型（或用 `VARCHAR` + 应用层 JSON 映射）；Hibernate `@JdbcTypeCode(SqlTypes.JSON)` 与 Jackson 序列化不依赖 MySQL 原生 JSON；
  - `ALTER TABLE ... ADD COLUMN ... AFTER ...`（如 `V8__model_binding.sql`）在 H2 下不支持 `AFTER`，需方言分支或注释掉 `AFTER` 子句。
- 若 H2 兼容成本过高，可退回 SQLite（`org.xerial:sqlite-jdbc` + `hibernate-community-dialects`），思路一致。

### 3.2 Redis → 内存实现

- 现状 Redis 用于缓存与多租户配额（配额已有内存兜底），最小改动是将 `spring.data.redis` 置空、各 Service 保留 `@Autowired(required=false)` 的可选注入（本项目已普遍采用该模式），缺失自动退化到内存。可另封装 `InMemoryCache`/`InMemoryEventStore` 补齐语义。

### 3.3 Milvus / Kafka / LiteLLM / MinIO

均在本项目已有降级开关，单机版默认全关/全本地（见上表），**无需新开发**。真实模型走需求 1 的「per-agent baseUrl + apiKey 直连」，或用户再配置外部 LiteLLM。

## 4. 首次启动向导（密钥与初始化）

打包版不应在发布物里硬编码模型 Key 主密钥，首次启动引导用户完成：

1. 设置本机「模型密钥主密钥」（写入本机 OS 凭据库或带权限的数据目录文件，供 `MODEL_KEY_ENC_KEY` 使用）；
2. （可选）填一个默认模型厂商/Key，供未在仪表盘按智能体填写的场景兜底；
3. 初始化内嵌数据库（Flyway 迁移）与数据目录。

实现上，向导可复用现有 React 前端加一个 `setup` 页（或独立小页），后端提供 `GET/POST /api/v1/setup` 完成探测与落盘。

## 5. 打包与签名（jpackage + jlink）

```bash
# 1) 裁剪最小 JRE（注意保留 --enable-preview 所需能力）
jlink --module-path "$JAVA_HOME/jmods" \
      --add-modules java.base,java.logging,java.sql,java.naming,java.desktop,java.management,java.instrument,java.nio,java.net.http,jdk.crypto.ec,jdk.crypto.cryptoki \
      --strip-debug --no-header-files --no-man-pages \
      --output runtime

# 2) 打安装包（Windows 示例）
jpackage --type msi \
  --name "Agent Platform" --app-version 1.0.0 \
  --runtime-image runtime \
  --main-jar app/agent-platform-core.jar --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options "--enable-preview" \
  --win-menu --win-shortcut --icon app.ico --vendor "Agent Platform"
```

- **签名**：生产分发需 `signtool`（Windows）/ `codesign`（macOS）对安装包签名，避免 SmartScreen/Gatekeeper 拦截。
- **升级**：小版本可走「安装包覆盖 + Flyway 迁移」；桌面壳（阶段 3）可接自动更新器。

## 6. 阶段 1（Docker 镜像）前置

复用同一产物结构，`Dockerfile` 大致：

```dockerfile
FROM eclipse-temurin:21-jre
COPY agent-platform-core.jar /app/app.jar
COPY static/ /app/static/
ENV JAVA_OPTS="--enable-preview"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

MySQL 等外部服务由 `docker-compose` 或环境变量接入；此阶段**不做** DB 内置化，先交付一致、可复现的运行物。

## 7. 关键风险与待办

1. **Flyway 方言**：梳理 `V1~V8` 中的 MySQL 特有子句，抽象为可切换脚本或加 H2 兼容分支（最高优先）。
2. **密钥存管**：主密钥不落 .env、不进镜像层；走 OS 凭据库。
3. **数据目录**：所有 `./data/*` 重定向到用户目录，卸载不清数据（或提供「清空数据」选项）。
4. **端口冲突与单实例**：启动时检测端口占用、写单实例锁、异常时弹提示。
5. **插件系统兼容**：内嵌 DB 不影响 `PluginClassLoader` 的 SPI 加载，但发布包内的插件默认目录应指向数据目录（`PLUGIN_ARTIFACT_DIR`）。