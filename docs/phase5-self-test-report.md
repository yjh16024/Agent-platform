# Phase 5 · 生产化 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 多阶段 Dockerfile | ✅ | Maven 构建层 → JDK 21 JRE 运行层 + 非 root + ZGC |
| 2 | K8s 部署清单 | ✅ | namespace/resourcequota/limitrange/configmap/secret/deployment/service/ingress |
| 3 | 弹性伸缩 | ✅ | HPA（CPU/内存）+ KEDA（Kafka lag）+ PDB |
| 4 | 可观测性 | ✅ | Micrometer-Prometheus + ServiceMonitor + OTel Instrumentation |
| 5 | 安全加固 | ✅ | NetworkPolicy + SecurityContext + 非 root + 资源配额 |
| 6 | Helm Chart | ✅ | Chart.yaml/values.yaml/5 个 templates |
| 7 | Kustomize | ✅ | kustomization.yaml 组织 base 资源 |
| 8 | 部署文档 | ✅ | K8s 示例 + 环境变量清单 + 配置中心 + 弹性策略 |
| 9 | 构建验证 | ✅ | 添加 micrometer 后 88 测试仍全通过 |

## 关键设计决策

1. **多阶段 Dockerfile**：构建层用 `maven:3.9-eclipse-temurin-21`（预装 JDK 21），运行层用
   `eclipse-temurin:21-jre-ubi9-minimal`（精简，无构建工具链），层缓存加速 + 体积最小化。

2. **JAVA_OPTS 统一注入**（对比 §4.5.3）：`-XX:+UseZGC -XX:+ZGenerational`（分代 ZGC）
   + `UseContainerSupport`（容器感知）+ `--enable-preview`（ScopedValue/结构化并发）
   + `--add-opens java.base/java.lang`（插件 SPI 反射）。

3. **双轨伸缩**：HPA（资源型，无需额外组件）+ KEDA（事件型，按 Kafka lag/日志速率），
   KEDA 支持从零扩容，长流程靠 Temporal 持久化不受 Pod 重建影响（对比 §6.2）。

4. **可观测性栈**：Micrometer-Prometheus（JVM/业务指标）自动暴露于 `/actuator/prometheus`
   + ServiceMonitor 抓取 + OTel Instrumentation 注入 trace（对比 §4 可观测性行）。

5. **安全最小权限**：NetworkPolicy（网关→核心→基础设施的严格白名单）+ 非 root 运行 +
   ResourceQuota/LimitRange，生产补充 Istio mTLS + cert-manager + Vault + cosign。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 本环境无 Docker，镜像未实际构建 | 无法验证构建 | Dockerfile 为标准多阶段，CI 可跑 |
| K8s 清单未实操部署 | 无法验证运行时 | 语法标准，可用 kustomize/helm 校验 |
| KEDA/OTel 为 CRD 引用 | 需安装对应 Operator | 文档标注依赖 |
| LangFuse 未集成代码 | LLM 专项观测缺失 | 接 langfuse SDK（可选） |

## 验证方式（有 K8s 集群时）

```bash
# 1. 构建镜像
docker build -f agent-platform-deploy/docker/Dockerfile -t agent-platform/agent-platform-core:1.0.0 .

# 2. kustomize 校验 + 部署
kubectl kustomize agent-platform-deploy/k8s/base | kubectl apply -f -

# 3. Helm 渲染 + 部署
helm template agent-platform agent-platform-deploy/helm/agent-platform --debug
helm install agent-platform agent-platform-deploy/helm/agent-platform

# 4. 验证
kubectl -n agent-platform get pods,svc,hpa
kubectl -n agent-platform port-forward svc/agent-gateway-external 8080:80
curl http://localhost:8080/api/v1/agents  # 经网关（需先登录拿 JWT）
```