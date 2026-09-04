# 端到端演示用例

> 完整链路：**创建 Agent → 配置插件 → 运行对话 → 查看日志 → 触发诊断 → 优化提示词**。
> 可执行脚本见 `docs/demo-script.sh`（需先 `docker compose up -d` + 启动 core）。

## 前置

```bash
docker compose up -d                     # MySQL/Redis/Milvus/LiteLLM
cd agent-platform-core && mvn spring-boot:run   # 启动核心服务
```

## 演示步骤与预期

### ① 创建智能体

```bash
curl -X POST http://localhost:8081/api/v1/agents -H "X-Tenant-Id: t1" \
  -H "Content-Type: application/json" \
  -d '{"name":"客服助手","persona":{"role":"资深客服专家","tone":"friendly","warmth":0.8,"forbidden":["泄露隐私"]},"systemPrompt":"你是一名客服，回答用户咨询"}'
```

**预期**：返回 `agent_id` + `status=draft`。

### ② 配置插件（挂载自动回复 + TTS）

```bash
curl -X POST http://localhost:8081/api/v1/plugins/plugin_auto_reply/attach \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" -d '{"agent_id":"AGENT_ID"}'
curl -X POST http://localhost:8081/api/v1/plugins/plugin_tts_azure/attach \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"agent_id":"AGENT_ID","config":{"voice":"zh-CN-Xiaoxiao"}}'
```

**预期**：返回 `status=attached` + 插件贡献的 tools/hooks。

### ③ 运行对话（命中自动回复 + TTS）

```bash
curl -X POST http://localhost:8081/api/v1/agent/run -H "Content-Type: application/json" \
  -d '{"agentId":"AGENT_ID","messages":[{"role":"user","content":"你们营业时间是几点？"}],"metadata":{"tenant_id":"t1"}}'
```

**预期**：命中「营业时间」关键词 → **自动回复短路（不调 LLM）**，返回预设答复 + `audio_url`（TTS）。

### ④ 查看运行日志

```bash
# 日志按租户隔离，查询需带 X-Tenant-Id（与运行时 metadata.tenant_id 一致）
curl "http://localhost:8081/api/v1/logs?level=INFO" -H "X-Tenant-Id: t1"
```

**预期**：结构化日志列表（含 trace_id/run_id/category/fingerprint）。日志已落 MySQL
（`log_index` 表，重启不丢）；Agent 每次运行会写入 `run.start/llm.call/llm.done/run.completed`。

### ⑤ 触发诊断

```bash
# 先上报一条错误
curl -X POST http://localhost:8081/api/v1/logs -H "Content-Type: application/json" \
  -d '{"logId":"log_1","tenantId":"t1","level":"ERROR","category":"plugin","message":"ClassNotFoundException: com.azure.ai.TtsClient"}'

# 触发诊断
curl -X POST http://localhost:8081/api/v1/diagnosis/analyze -H "Content-Type: application/json" \
  -d '{"message":"ClassNotFoundException: com.azure.ai.TtsClient","category":"plugin"}'
```

**预期**：`source=RULE` + 根因「插件缺少依赖」+ 方案「将缺失依赖打包进插件 lib/」。

### ⑥ 优化提示词

```bash
curl -X POST http://localhost:8081/api/v1/prompt/optimize -H "Content-Type: application/json" \
  -d '{"raw_prompt":"你是一个客服，帮我回答用户问题","context":{"use_case":"customer_service"},"options":{"generate_examples":true}}'
```

**预期**：`optimized_prompt` 补全「## 角色/任务/约束/输出格式/示例」+ `score` 提升 + `diff` 差异列表。