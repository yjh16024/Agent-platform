# Phase 3 · 编排 + Skills — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 节点模型 | ✅ | `NodeType` 枚举 + `WorkflowNode`/`WorkflowDefinition` Record（对齐 §3.2 Schema） |
| 2 | 节点 Schema 校验 | ✅ | `WorkflowSchemaValidator`（ID 唯一/类型/分支/DAG 环检测） |
| 3 | 自研 DAG 引擎 | ✅ | `DagEngine`（运行时遍历 + 条件分支 + 虚拟线程并行多下游） |
| 4 | 节点执行器（策略） | ✅ | Transform/Condition/Tool/LLM/Start/End 六种执行器 |
| 5 | 变量作用域 | ✅ | `WorkflowContext`（`${var.path}` 解析 + 节点 output_var 写入） |
| 6 | 编排画布 API | ✅ | `WorkflowService`/`Controller`（定义 CRUD + 执行） |
| 7 | Skills 导入 | ✅ | `SkillImporter`（YAML/JSON/URL）+ `SkillService`/`Controller` |
| 8 | Temporal 预留 | 🔶 | 长流程接口预留（MVP 用自研 DAG，Temporal 需独立 Server） |
| 9 | 数据库迁移 | ✅ | `V3__workflow_skill.sql`（workflow_def/skill_def） |
| 10 | 单元测试 | ✅ | 13 个新测试，总计 52 个全部通过 |

## 测试结果

```
Tests run: 52, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  Phase 3 新增:
    WorkflowSchemaValidatorTest : 5  ✅  (合法/重复ID/环/Condition缺分支/悬空引用)
    DagEngineTest               : 4  ✅  (线性变量传递/条件命中/default兜底/var解析)
    SkillImporterTest           : 4  ✅  (YAML/JSON/缺字段/空)
```

## 关键设计决策

1. **运行时遍历而非静态拓扑排序**：Condition 节点分支是运行时动态决定的，纯拓扑排序无法表达
   条件跳转。DAG 引擎采用「运行时递归遍历 + executed 集合防环」，串行链顺序执行、多下游虚拟线程并行。

2. **变量作用域 `WorkflowContext`**：节点 `output_var` 写入，下游 `${var.path}` 引用（点号访问嵌套
   Map/JsonNode），支持纯变量返回与字符串模板替换两种解析模式。

3. **executor 策略分发**：`NodeExecutor` 接口（`type()` + `execute()`）由 Spring 自动收集成
   `EnumMap<NodeType, NodeExecutor>`，新增节点类型零侵入（对应设计文档 §5 的开闭原则）。

4. **Temporal 预留**：完整 Temporal 集成需 `io.temporal:temporal-sdk` + 独立 Temporal Server。
   MVP 以自研 DAG 引擎交付可运行编排（短链路），长流程 Temporal 作为可叠加层（接口预留），
   与设计文档「双层互补」定位一致。

5. **修复 `WorkflowContext` null 值问题**：`ConcurrentHashMap` 不允许 null 值，节点 output 为 null
   时跳过写入（`storeOutput` 判空），避免运行期 NPE。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| Temporal 完整集成未落地 | 无 durable execution/故障重放 | 接入 temporal-sdk + Server（Phase 5） |
| Condition 表达式求值器为简化实现 | 复杂布尔/逻辑表达式不支持 | 后续引 SpEL/自定义语法 |
| 并行分支共享 WorkflowContext | 并发写同一变量可能竞态 | 已用 ConcurrentHashMap，业务上应避免同名 output_var |

## 验证方式

```bash
# 创建并执行工作流（线性：Start → Transform → End）
curl -X POST "http://localhost:8081/api/v1/workflows?name=演示流程" \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"nodes":[
    {"id":"start","type":"Start","next":"calc","input_mapping":{"x":42}},
    {"id":"calc","type":"Transform","next":"end","input_mapping":{"y":"${x}"},"output_var":"result"},
    {"id":"end","type":"End"}]}'

curl -X POST "http://localhost:8081/api/v1/workflows/wf_xxx/execute" \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" -d '{}'

# 导入 Skill
curl -X POST "http://localhost:8081/api/v1/skills/import" \
  -H "X-Tenant-Id: t1" -H "Content-Type: text/plain" \
  -d 'name: 客服助手
version: 1.0.0
prompt: 你是客服
tools: [calc, search]'
```