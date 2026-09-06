# 可移植性审计报告：他人从 Gitee 克隆后能否直接运行

> 审计对象：agent-platform（Java 21 + Spring Boot 3.4 + React 前端）
> 审计日期：2026-09-06　范围：源码/配置/脚本/文档中的**机器相关项**与**环境硬依赖**

## 一、结论

**结论：可行，但存在 3 个必修项（否则克隆者按文档操作会失败或看到旧界面）。**

| 判定 | 说明 |
|------|------|
| 源码中是否存在硬编码的机器路径 | ✅ **无**。全仓库（排除 node_modules/target/dist）搜索 `C:\`、`C:/`、`Users\`、`USERPROFILE`、绝对路径，结果为空；所有路径均为相对路径或经环境变量/配置项注入 |
| 是否存在"只能在你机器跑"的功能 | ⚠️ **有 1 处行为依赖运行环境**：Skills「打开目录」功能（`open-folder-enabled` 默认 true）依赖系统文件管理器；另外 `data/skills` 下的示例 Skill 因 `.gitignore` 忽略 `data/` 而不会随源码分发 |
| 克隆后能否运行 | ⚠️ **按当前文档操作会失败 1 处**（`mvn spring-boot:run` 因预览特性未开而报错），且**会看到旧前端**（若未先重新打包静态资源） |

综合：**不存在"绑定你这台机器"的不可移植代码**，剩余都是"配置/文档/默认值"层面的可修复项。

## 二、环境前提（克隆者必须满足）

| 依赖 | 是否必需 | 说明 |
|------|----------|------|
| JDK **21** | ✅ 必需 | 使用了 `ScopedValue`（预览特性），pom 全局开启 `--enable-preview`；JDK 17 无法编译 |
| Maven 3.9+ | ✅ 必需 | 首次构建需联网下载依赖 |
| MySQL 8.x | ✅ 必需 | 启动时 Flyway 执行迁移，连不上直接启动失败；默认库 `agent_platform`、账号 `agent/agent123456`（与 docker-compose 一致） |
| Redis 7 | ❌ 可选 | 缺失时仅健康检查 DOWN，配额走内存兜底 |
| Milvus / Kafka / MinIO / LiteLLM | ❌ 可选 | 全部有开关与降级（in-memory 向量、本地磁盘、Mock 模型） |
| Node 18+ / npm | ❌ 可选 | 仅当你**要改前端源码并重新构建**时才需要；不改则直接用仓库内的已构建静态资源 |
| Docker | ❌ 可选 | 仅用于一键拉起可选基础设施 |

## 三、问题清单与修改方案

### 🔴 P0：阻断或明显误导（必须改）

**P0-1　文档中的 `mvn spring-boot:run` 会启动失败**
- 证据：`docs/demo.md:10` 与 `docs/phase1-self-test-report.md:64` 使用该命令；而 `agent-platform-core/pom.xml` 的 `spring-boot-maven-plugin` **未配置 `jvmArguments`**，编译器开启了 `--enable-preview`，运行 JVM 未开启 → 加载 `TraceContext`（ScopedValue）时抛预览特性错误。
- 方案（二选一）：
  1. **推荐**：在 `agent-platform-core/pom.xml` 的 spring-boot-maven-plugin 增加
     `<configuration><jvmArguments>--enable-preview</jvmArguments></configuration>`，
     使 `mvn spring-boot:run` 可直接工作；
  2. 或在文档里统一改为 `mvn -pl agent-platform-core -am package -DskipTests` +
     `java --enable-preview -jar agent-platform-core/target/agent-platform-core-1.0.0-SNAPSHOT.jar`。

**P0-2　仓库内的前端静态资源可能是"旧版"，克隆者会看到未修复的界面**
- 证据：`agent-platform-core/src/main/resources/static/` 由 `npm run build:prod` 生成，**未被 .gitignore 忽略**（即会随源码提交）；core 直接托管该目录。
- 方案：推送 Gitee 前执行一次 `cd agent-platform-ui && npm run build:prod`，把最新产物同步进 `static/` 后再提交；并在 README「快速启动」中明确"改过前端必须重新构建同步"。

**P0-3　JDK 版本要求未被强制校验，误用 JDK 17 会得到晦涩报错**
- 证据：pom 通过 `maven.compiler.release=21` 编译，但无 `maven-enforcer-plugin` / toolchain 校验；`start-core.bat` 找不到便携 JDK 时直接回退系统 java 并只打 WARN。
- 方案：增加 `maven-enforcer-plugin`（`requireJavaVersion` ≥ 21），或在 `start-core.bat` 中检测 `java -version` 并给出明确提示后退出。

### 🟡 P1：体验/安全（建议改）

**P1-1　Skills「打开目录」默认开启，行为依赖运行环境**
- 证据：`application.yml` → `agent-platform.skills.open-folder-enabled: ${SKILLS_OPEN_FOLDER:true}`；
  `SkillFileStore.openFolder()` 在 Windows 调 `explorer.exe`、macOS 调 `open`、Linux 调 `xdg-open`。
- 影响：无桌面环境（服务器/容器）调用会返回异常；属于"在你机器上好用、在别人机器上不一定"的典型项。
- 方案：默认值改为 **false**（远程部署更安全），README/UI 明确"本地桌面场景可手动开启 `SKILLS_OPEN_FOLDER=true`"；UI 按钮在禁用时改为展示可复制路径而非报错。

**P1-2　示例 Skill 不会随源码分发**
- 证据：`data/skills/example-pdf-processing/SKILL.md`、`data/skills/README.md` 存在，但 `.gitignore` 忽略 `data/`。
- 影响：克隆者 skills 目录为空，看不到标准格式示例（功能本身不受影响，目录会自动创建）。
- 方案：把示例迁到 `agent-platform-core/src/main/resources/skills-sample/`（随源码提交），启动时若 `data/skills` 为空则复制过去；或 `.gitignore` 增加例外 `!data/skills/**`。

**P1-3　`start-core.bat` / `build-ui.bat` 仅 Windows**
- 影响：Linux/macOS 用户无法使用一键脚本（README 已给出 mvn/npm 命令，但不显眼）。
- 方案：补 `start-core.sh`、`build-ui.sh`，或在 README 顶部并列给出 Windows / Linux-macOS 两套命令。

**P1-4　默认密钥与安全默认值**
- 证据：`JWT_SECRET`、`MODEL_KEY_ENC_KEY` 均为 `change-me-*`；`SecurityStartupGuard` 仅在"开启鉴权 + 默认密钥"时拒绝启动（默认 `SECURITY_ENABLED=false`，不拦）。
- 影响：克隆者开箱即用（对演示是优点），但直接上生产等于裸奔。
- 方案：README 增加"从演示到生产"检查清单：覆盖 `JWT_SECRET`/`MODEL_KEY_ENC_KEY`、配置 `AUTH_USERNAME/AUTH_PASSWORD`、开启 `SECURITY_ENABLED`、关闭 `SKILLS_OPEN_FOLDER`。

### 🟢 P2：文档表述（低优先）

- **P2-1**：文档多处出现你的本地工具链路径（`~/tools/jdk21/...`、`~/tools/maven/...`、`~/tools/redis`），对克隆者无意义 → 改为"JDK 21 安装方式（任选：系统安装 / SDKMAN / 项目内 tools 目录）"。
- **P2-2**：`docs/phase1-self-test-report.md` 等历史报告含 `mvn spring-boot:run`，与 P0-1 一并修正或标注"历史存档，命令以 technical-guide 为准"。
- **P2-3**：README 未说明"首次构建需联网下载 Maven 依赖 / 前端依赖"。

## 四、推荐的执行顺序（改完之后再推送）

1. `agent-platform-ui` 执行 `npm run build:prod` → 同步最新静态资源；
2. 修 P0-1（pom 加 `jvmArguments`，或改文档命令）；
3. 修 P0-3（enforcer/脚本 JDK 校验）；
4. 修 P1-1（默认关闭打开目录）、P1-2（示例随源码分发）、P1-3（补 .sh 或文档并列命令）；
5. README/TODO 同步更新"环境前提"与"生产检查清单"；
6. 提交并推送 Gitee。

## 五、克隆者验收清单（用于验证是否真的"开箱即用"）

```bash
# 1. 准备：JDK 21、Maven 3.9+、MySQL 建库建账号（或 docker compose up -d mysql redis）
# 2. 构建后端
mvn -pl agent-platform-core -am package -DskipTests
# 3. 启动（注意必须带 --enable-preview）
java --enable-preview -jar agent-platform-core/target/agent-platform-core-1.0.0-SNAPSHOT.jar
# 4. 验证
curl http://localhost:8081/actuator/health          # 应为 UP（redis 可 DOWN）
# 5. 浏览器访问 http://localhost:8081/ 应能看到完整仪表盘（无需 npm）
```

通过判定：**全新目录克隆 → 按上面 5 步能起服务、能进仪表盘、能建智能体并发起对话（走本地 Mock 或自配模型 Key）**，即可认为"下载即可运行"达标。

## 四、执行进度（2026-09-06 已实施）

| 条目 | 状态 | 说明 |
|------|------|------|
| P0-1 `mvn spring-boot:run` 预览特性 | ✅ | `agent-platform-core/pom.xml` 的 spring-boot-maven-plugin 增加 `jvmArguments=--enable-preview`；demo/phase1 文档命令已改为 `java --enable-preview -jar` |
| P0-2 静态资源旧版 | ⚠️ 待推送前执行 | 需在推送前运行 `npm run build:prod` 重新同步 `static/`（README 已显著提示） |
| P0-3 JDK 版本校验 | ✅ | `start-core.bat` 增加 JDK ≥21 检测并明确报错退出；新增 `start-core.sh`（Linux/macOS）同样校验 |
| P1-1 Skills 打开目录 | ✅ | `SKILLS_OPEN_FOLDER` 默认改为 `false`；前端在未开启时弹窗给出可复制路径与开启方式 |
| P1-2 示例 Skill 分发 | ✅ | 示例迁入 `agent-platform-core/src/main/resources/skills-sample/`，`SkillSampleSeeder` 启动时自动铺设（目录为空才复制） |
| P1-3 跨平台脚本 | ✅ | 新增 `start-core.sh`、`build-ui.sh` |
| P1-4 默认密钥说明 | ✅ | README「从演示到生产」检查清单覆盖密钥/账号/开关 |
| P2 文档本机路径 | ✅ | README 已按通用环境要求重写；旧文档中 `mvn spring-boot:run` 已修正或标注 |
