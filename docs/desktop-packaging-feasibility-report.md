# 智能体交互平台 · 打包为可分发应用可行性分析报告

> 版本：v1.0 · 日期：2026-09-04 · 关联文档： [技术设计文档](desktop-packaging-technical-design.md)、[仪表盘可行性报告](dashboard-feasibility-report.md)

## 1. 引言

### 1.1 背景

`agent-platform` 当前是「源码 + 手工起服务」形态：使用方需要自备 JDK 21、Maven、MySQL（以及可选的 Redis/Milvus/Kafka/LiteLLM），跑 `start-core.bat` 后才得到 `http://localhost:8081/` 的仪表盘。对非开发者用户而言门槛过高。

需求方希望**最终把它打包成一款可直接使用的应用**——少部署、少依赖、双击即用。本文评估从「本地手工部署」走向「可分发产物」的可行性，回答三个问题：

- 有哪几条技术路线，成本/风险各如何？
- 现有架构哪些依赖能内置、哪些必须外置？
- 推荐怎么分阶段落地？

### 1.2 目的与范围

本文只做可行性分析与路线推荐，**不实施**。配套的[技术设计文档](desktop-packaging-technical-design.md)给出目标产物结构、基础设施内置化策略与关键配置示例。

### 1.3 现状盘点（决定改造面的关键）

| 层 | 技术 | 是否可去除 | 现状兜底 | 打包关键点 |
|----|------|-----------|----------|-----------|
| 后端 | Java 21 + Spring Boot 3.4.0 | — | 必需 JRE | 需内嵌 JRE（`jlink`/`jpackage`） |
| 前端 | React 18 + Vite 5 + antd 5 | — | 纯静态 | 已同源托管于 core `static/`，天然可打包 |
| 业务库 | MySQL 8.4（Flyway 迁移） | ❌ 强依赖 | 无 | **最大外置项**，需内置化（见 §4） |
| 缓存 | Redis 7 Cluster | ✅ 部分 | quota 有内存兜底；缓存缺失不阻断 | 可换内存/嵌入式 |
| 向量库 | Milvus 2.x | ✅ | `InMemoryVectorStore` 默认 | 单机版可外置或换 ANN 库 |
| 事件总线 | Kafka | ✅ | `events.enabled=false` | 单机版可关 |
| 模型网关 | LiteLLM Proxy | ✅ | `local` Mock 兜底 | 直连厂商（需求 1 已支持 per-agent baseUrl+key） |
| 对象存储 | MinIO | ✅ | `storage.type=local` 本地磁盘 | 单机版用本地盘 |

> 结论前置：项目**已具备良好的「降级可跑」姿态**——除 MySQL 外，其余中间件在无 Docker 场景均有内存/本地兜底。因此打包的主要矛盾只有一个：**替换 MySQL，并把 JRE 与数据目录内嵌进应用**。

## 2. 目标形态与验收

- 用户拿到一个安装包/镜像，安装后**免配置**（或仅首次向导配置）即可使用仪表盘；
- 数据（智能体、会话、文件、插件）**落在用户本机数据目录**，而非项目源码目录；
- 无需用户自装 Java、MySQL、Docker（对桌面包而言）。

## 3. 四条技术路线对比

| 维度 | A. Docker/OCI 单镜像 | B. jpackage 原生安装包 | C. Electron/Tauri 桌面壳 | D. GraalVM native-image |
|------|----------------------|------------------------|---------------------------|--------------------------|
| 交付物 | 镜像 + `docker run` | `.exe/.msi/.dmg/.deb` | 桌面安装包（壳+后端 bundle） | 单原生可执行文件 |
| 运行时依赖 | Docker | 无（内嵌 JRE） | 无（内嵌 JRE/后端） | 无（原生二进制） |
| 启动速度 | 秒级 | JVM 秒级 | JVM 秒级（壳本身更快） | 极快（<1s） |
| 体积 | 大（镜像层） | 中（~80–120MB 带 JRE） | 大（Electron ~150MB+；Tauri 小） | 小（~40–80MB） |
| 跨平台 | 优 | 优（分别打包） | 优（分别打包） | 良（分别编译） |
| 改造量 | **低** | 中（DB 内置化） | 中（同 B + 壳/进程模型） | **高**（AOT/反射改造） |
| 升级/自动更新 | 拉新镜像 | 安装包覆盖/增量 | 壳自带更新 | 替换二进制 |
| 主要风险 | 仍属「部署」非「双击即用」 | DB 方言/SQL 迁移适配 | 进程编排 + IPC | 反射/动态类加载兼容 |

### 逐条评估

**A. Docker/OCI 单镜像（最省事）**：把 core 打好的 jar + 前端 static 打进镜像，MySQL 等中间件用 compose 或外部连接。零后端改造即可落地，跨平台一致；但用户仍需 Docker，严格说「还是部署」。适合作为**第一阶段的快速交付**与团队内标准运行方式。

**B. jpackage 原生安装包（目标形态）**：`jlink` 裁出最小运行时 + `jpackage` 打成带图标/签名的安装包，进程内嵌 core，前端 static 同源托管。真正的「双击即用」。唯一硬改造是**MySQL → 嵌入式数据库**（Spring Data JPA 可平滑换，Flyway SQL 需少量方言适配，见技术设计 §3）。

**C. Electron/Tauri 桌面壳**：把现有 React 前端放进原生窗口，后端作为 sidecar 子进程（仍要打包 JRE + jar + 内置 DB）。多了一层进程/生命周期/IPC 的复杂度，而**换窗口并不解决 DB 与 JRE 的内置问题**（与 B 共用同一套后端打包工作）。结论：可作为**可选外壳**（要原生窗口体验时再加），不作为主路线。

**D. GraalVM native-image**：启动快、体积小，但本项目存在多处反射/动态类加载（Hibernate、Jackson、OkHttp、插件系统 `PluginClassLoader` + SPI、`ScopedValue` 预览特性），AOT 配置成本高、收益在桌面场景甚微（JVM 启动已够用）。**阶段观察项，不投入。**

## 4. 基础设施内置化策略

| 依赖 | 桌面单机版方案 | 影响面 |
|------|----------------|--------|
| MySQL | 嵌入式 **H2（`MODE=MySQL`）** 或 **SQLite** | Flyway 迁移少量方言调整；JPA `ddl`/方言切换 |
| Redis | **内存实现**（缓存/配额已有内存兜底）或嵌入式 Redisson | 移除 `spring.data.redis` 硬连接 |
| Milvus | 单机版**保持可选外置**；默认 `InMemoryVectorStore` | 已支持，无需改 |
| Kafka | **关闭**（`events.enabled=false`） | 已支持 |
| LiteLLM | **直连厂商**（需求 1 的 per-agent baseUrl+apiKey）或可选外置 | 已支持 |
| MinIO | **本地磁盘**（`storage.type=local`） | 已支持 |

> 说明：RAG 语义检索当前本就缺嵌入模型（见 TODO），单机版可据此把「真实向量检索」列为可选增强，不影响核心链路。

## 5. 经济与风险

- **成本**：零新增许可；开源方案（H2/SQLite/Tauri/嵌入式 Redis）均免费；代码签名证书是唯一可选付费项（Windows SmartScreen 信任）。
- **风险与对策**：
  1. **密钥在分发版中的安全**（最重要）——需求 1 的模型 API Key 主密钥（`MODEL_KEY_ENC_KEY`）不能在发布版里硬编码 dev 默认值。对策：首次启动向导让用户设置本机主密钥，或存入 OS 凭据库（Windows Credential Manager / macOS Keychain）。
  2. **数据目录漂移**——`./data/plugins`、`./data/files`、内嵌 DB 文件若落在安装目录会因权限/卸载丢失。对策：统一重定向到用户数据目录（`%APPDATA%\agent-platform` 等）。
  3. **DB 方言/SQL 兼容**——MySQL 专属 JSON/`MEDIUMTEXT` 等需在 H2 下验证。
  4. **升级与数据迁移**——Flyway 已具备版本化迁移，安装包升级沿用。
  5. **杀软/签名**——无签名 exe 易被拦截；发布前做代码签名。
  6. **端口/多实例/生命周期**——桌面包需处理端口占用、单实例锁、托盘退出。

## 6. 结论与分阶段推荐

1. **可行。** 核心障碍单一（MySQL）+ 前端已同源托管 + 中间件已有降级兜底，打包路径清晰。
2. **推荐路线**：
   - **阶段 1（立即，低风险）**：产出 **Docker/OCI 单容器镜像**——jar + 前端 static，外部 DB，`docker run` 即用。先让交付一致、可复现。
   - **阶段 2（目标，中风险）**：实现 **jpackage 原生安装包**——内嵌 JRE、替换 MySQL 为嵌入式 H2/SQLite、Redis 内存化、数据目录重定向、首次向导（模型 Key + 主密钥）。交付「双击即用」。
   - **阶段 3（可选）**：以 **Tauri/Electron 壳**包裹阶段 2 的后端 bundle，获得原生窗口/托盘，前端资产复用。
   - **观察项**：GraalVM native-image，暂不投入。
3. **下一步**：技术设计详见 [desktop-packaging-technical-design.md](desktop-packaging-technical-design.md)，可分阶段实施。