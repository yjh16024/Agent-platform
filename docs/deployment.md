# 部署文档（Phase 5 · 生产化）

## 1. 部署架构

```
                    ┌─────────────┐
   Internet ──────► │ Ingress Nginx│ ──TLS──► agent-gateway (HPA) ──► agent-core (HPA + KEDA)
                    └─────────────┘                      │                    │
                                                          └────────────────────┼──────────────┐
                                             MySQL 8.4 ── Redis 7 ── Kafka ── Milvus ── ES/ClickHouse
```

- **无状态化**：核心微服务全无状态，跨 Region 多活
- **数据层**：MySQL InnoDB Cluster + Milvus 多副本（RPO≈0）
- **弹性**：KEDA 按 Kafka 堆积/日志速率扩缩，HPA 按 CPU 扩缩

## 2. 构建镜像

```bash
cd agent-platform
# 构建核心服务镜像（多阶段：Maven 构建 → JDK 21 JRE 运行）
docker build -f agent-platform-deploy/docker/Dockerfile \
  -t agent-platform/agent-platform-core:1.0.0 .

# 推送
docker push agent-platform/agent-platform-core:1.0.0
```

## 3. K8s 部署

### 3.1 kustomize 部署（推荐）

```bash
# 本地/开发
kubectl apply -k agent-platform-deploy/k8s/base

# 生产（overlay 覆盖镜像 tag/副本数）
kubectl apply -k agent-platform-deploy/k8s/overlays/prod
```

### 3.2 Helm 部署

```bash
cd agent-platform-deploy/helm/agent-platform

# 渲染查错
helm template agent-platform . --debug

# 安装
helm install agent-platform . \
  --set services.mysql.host=mysql.prod.svc.cluster.local \
  --set secrets.jwtSecret="$(openssl rand -hex 32)" \
  --set core.image.tag=1.0.0

# 升级（金丝雀可 ArgoCD GitOps 管理）
helm upgrade agent-platform . --reuse-values

# 回滚
helm rollback agent-platform 1
```

## 4. 环境变量清单

### 核心服务（agent-platform-core）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_URL` | MySQL JDBC 连接串 | `jdbc:mysql://localhost:3306/agent_platform` |
| `MYSQL_USER` | MySQL 用户名 | `root` |
| `MYSQL_PASSWORD` | MySQL 密码 | `root123456` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 | `localhost` / `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `KAFKA_BOOTSTRAP` | Kafka 地址 | `localhost:9092` |
| `MILVUS_HOST` / `MILVUS_PORT` | Milvus 地址 | `localhost` / `19530` |
| `LLM_BASE_URL` | LiteLLM Proxy 地址 | `http://localhost:4000` |
| `LLM_API_KEY` | LiteLLM Master Key | `sk-local` |
| `DEFAULT_MODEL` | 默认模型 | `gpt-4o-mini` |
| `JWT_SECRET` | JWT 签名密钥（≥32 字节） | 占位 |
| `PLUGIN_ARTIFACT_DIR` | 插件制品目录 | `./data/plugins` |

### 网关（agent-platform-gateway）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `CORE_SERVICE_URL` | 核心服务地址 | `http://localhost:8081` |
| `JWT_SECRET` | JWT 密钥（与核心一致） | 占位 |

### JVM 参数（`JAVA_OPTS`）

```bash
-XX:+UseZGC -XX:+ZGenerational              # 分代 ZGC（暂停 <10ms）
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75  # 容器感知
--enable-preview                             # JDK 21 预览特性（ScopedValue/StructuredTaskScope）
--add-opens java.base/java.lang=ALL-UNNAMED  # 插件 SPI 反射
```

## 5. 可观测性

| 层 | 技术 | 用途 |
|----|------|------|
| 指标 | Micrometer + Prometheus + Grafana | CPU/内存/请求/JVM GC |
| 追踪 | OpenTelemetry + LangFuse | LLM 调用链、Agent trace |
| 日志 | Logback JSON → Kafka → ES/ClickHouse | 全文检索 + 聚合导出 |

```bash
# 指标端点（Prometheus 已配置自动抓取）
curl http://localhost:8081/actuator/prometheus

# 健康检查（就绪/存活探针）
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8081/actuator/health/liveness
```

## 6. 安全加固清单

- ✅ 非 root 用户运行（Dockerfile `useradd` + `USER app`）
- ✅ `securityContext.runAsNonRoot` + `fsGroup`
- ✅ NetworkPolicy 东西向最小权限（仅网关→核心，核心→基础设施）
- ✅ JWT 无状态鉴权（网关白名单 + 租户头注入）
- ✅ 插件 SPI 反射开放仅 `java.lang`（`--add-opens`）
- ✅ 资源配额（ResourceQuota）+ 限制（LimitRange）
- 🔶 生产建议：Istio mTLS + cert-manager 自动签 TLS + Vault 管密钥 + 镜像签名（cosign）

## 7. 弹性伸缩策略

| 层级 | 指标 | 策略 |
|------|------|------|
| agent-gateway | QPS / CPU | HPA（CPU 70%） |
| agent-core | CPU / 内存 / Kafka 堆积 | HPA + KEDA（堆积>1000 扩容） |
| 插件运行时 | 插件加载数 | 独立节点池 + 资源配额 |
| 诊断/优化 Worker | 队列长度 | KEDA 自定义指标 |

配置中心使用：Spring `@ConfigurationProperties` + `application.yml`（ConfigMap 注入），
Kafka `agent.config.changed` / `plugin.attached` 事件广播失效各实例 Redis 缓存，实现秒级热配置。