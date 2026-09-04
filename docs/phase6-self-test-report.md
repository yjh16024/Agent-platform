# Phase 6 · 运行日志与智能诊断 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 结构化日志模型 | ✅ | `LogEvent`（trace_id/run_id/tenant_id 三维串联）+ `LogLevel`/`LogCategory` |
| 2 | 错误指纹生成 | ✅ | `FingerprintGenerator`（分类规则 + 状态码 + 异常类型） |
| 3 | 日志采集/查询/导出/瀑布图 | ✅ | `LogService`（内存存储 + 多维过滤）+ `LogController` |
| 4 | 三级诊断策略 | ✅ | `RuleBasedDiagnosis` / `VectorSimilarityDiagnosis` / `LlmReasoningDiagnosis` |
| 5 | 诊断引擎 | ✅ | `DiagnosisEngine`（责任链 + StructuredTaskScope 结构化并发） |
| 6 | 内置诊断规则库 | ✅ | 7 条规则（覆盖 skill_import/plugin/api/model 高频错误） |
| 7 | 诊断接口 | ✅ | `DiagnosisController`（analyze / rules）+ `DiagnosisService` |
| 8 | 数据库迁移 | ✅ | `V6__log_diagnosis.sql`（log_index/diagnosis_report/diagnosis_rule） |
| 9 | 单元测试 | ✅ | 14 个新测试，总计 82 个全部通过 |

## 测试结果

```
Tests run: 82, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  Phase 6 新增:
    FingerprintGeneratorTest : 5  ✅  (插件类加载/超时/模型连接/Skill/API 状态码)
    DiagnosisEngineTest      : 4  ✅  (规则命中/序列化/未知兜底/结构化并发)
    LogServiceTest           : 5  ✅  (指纹生成/INFO无指纹/过滤/关键词/瀑布图)
```

## 关键设计决策

1. **结构化并发 StructuredTaskScope 落地**（JDK 21 特性，§4.5.2）：`DiagnosisEngine.diagnoseConcurrent`
   用 `ShutdownOnSuccess` 三级策略扇出，最快返回有效结果的一路胜出、其余自动取消；
   同时保留 `diagnose` 责任链顺序执行（§5.5）保证确定性与成本最优（仅未知错误调 LLM）。

2. **错误指纹生成规则**：严格对齐 §3.5 表——`plugin#classloader_error`、`plugin#timeout`、
   `plugin#dependency_conflict`、`model#connection_refused`、`skill_import#manifest_invalid`、
   `api#{status}`，覆盖 80% 高频错误秒级返回。

3. **日志内存存储为 MVP**：LogService 用 `CopyOnWriteArrayList` + 多维过滤，指纹在 ERROR 场景
   自动生成；生产接 Logback JSON Encoder → Kafka → ES（全文检索）/ ClickHouse（聚合导出）。

4. **LLM 推理兜底降级**：三级策略前两级未命中时，用 `ModelRouter` 调专用分析模型；模型不可用时
   静态兜底（不阻断诊断返回）。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 日志为内存态（重启丢失） | 无持久化 | 接 Kafka + ES/ClickHouse（Phase 5） |
| 向量检索走 InMemoryVectorStore | 历史案例不足 | 接 Milvus error_knowledge Collection |
| 诊断结果未落库（闭环沉淀未完整） | 无反馈闭环 | 接 diagnosis_report 表 + is_helpful 反馈 |

## 验证方式

```bash
# 采集错误日志（自动生成指纹）
curl -X POST http://localhost:8081/api/v1/logs -H "Content-Type: application/json" \
  -d '{"log_id":"log_1","tenant_id":"t1","level":"ERROR","category":"plugin","message":"ClassNotFoundException: com.azure.ai.TtsClient"}'

# 触发诊断
curl -X POST http://localhost:8081/api/v1/diagnosis/analyze -H "Content-Type: application/json" \
  -d '{"message":"ClassNotFoundException: com.azure.ai.TtsClient","category":"plugin"}'

# 查询日志
curl "http://localhost:8081/api/v1/logs?category=plugin&level=ERROR"

# 内置规则列表
curl http://localhost:8081/api/v1/diagnosis/rules
```