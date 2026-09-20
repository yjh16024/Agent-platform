#!/usr/bin/env bash
# ============================================================
# 白雾·智能体交互平台 · 端到端演示脚本
# 前置（数据库二选一）：
#   A) docker compose up -d            # MySQL
#   B) 启动时加 --spring.profiles.active=embedded   # H2，免装数据库
# 其余中间件可选，缺失自动降级；本脚本为 bash，Windows 请在 Git Bash / WSL 下运行。 && 启动 agent-platform-core（见 README）
# 用法：bash docs/demo-script.sh
# ============================================================
set -e

BASE_URL=${BASE_URL:-http://localhost:8081}   # 核心服务地址
TENANT="t1"
HDR="X-Tenant-Id: $TENANT"
JSON="Content-Type: application/json"

echo "=============================================="
echo " 白雾·智能体交互平台 · 端到端演示"
echo "=============================================="

# 0. 健康检查
echo -e "\n[0] 健康检查"
curl -sf "$BASE_URL/actuator/health" | head -c 200; echo

# 1. 登录（获取 JWT）
echo -e "\n[1] 登录获取 JWT"
TOKEN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" -H "$JSON" \
  -d '{"tenant_id":"t1","user_id":"demo"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "token=${TOKEN:0:24}..."

# 2. 创建智能体
echo -e "\n[2] 创建智能体"
AGENT_JSON=$(curl -sf -X POST "$BASE_URL/api/v1/agents" -H "$JSON" -H "$HDR" \
  -d '{"name":"客服助手","persona":{"role":"资深客服专家","tone":"friendly","warmth":0.8,"forbidden":["泄露隐私"]},"systemPrompt":"你是一名客服，回答用户咨询","capabilities":{"multimodal":false}}')
AGENT_ID=$(echo "$AGENT_JSON" | grep -o '"agentId":"[^"]*"' | cut -d'"' -f4)
echo "agent_id=$AGENT_ID"

# 3. 配置插件（挂载「自动回复」+「TTS」）
echo -e "\n[3] 挂载插件"
curl -sf -X POST "$BASE_URL/api/v1/plugins/plugin_auto_reply/attach" -H "$JSON" -H "$HDR" \
  -d "{\"agent_id\":\"$AGENT_ID\"}" > /dev/null && echo "已挂载 plugin_auto_reply"
curl -sf -X POST "$BASE_URL/api/v1/plugins/plugin_tts_azure/attach" -H "$JSON" -H "$HDR" \
  -d "{\"agent_id\":\"$AGENT_ID\",\"config\":{\"voice\":\"zh-CN-Xiaoxiao\"}}" > /dev/null && echo "已挂载 plugin_tts_azure"

# 4. 运行对话（命中「营业时间」→ 自动回复短路；加 TTS 产出 audio_url）
echo -e "\n[4] 运行对话"
RESP=$(curl -sf -X POST "$BASE_URL/api/v1/agent/run" -H "$JSON" \
  -d "{\"agentId\":\"$AGENT_ID\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"你们营业时间是几点？\"}],\"metadata\":{\"tenant_id\":\"$TENANT\"}}")
echo "$RESP" | head -c 500; echo

# 5. 运行日志查询
echo -e "\n[5] 查看运行日志"
curl -sf "$BASE_URL/api/v1/logs?category=agent&level=INFO&size=5" | head -c 400; echo

# 触发一条错误日志
echo -e "\n[6] 触发错误日志（模拟插件类加载失败）"
curl -sf -X POST "$BASE_URL/api/v1/logs" -H "$JSON" \
  -d "{\"logId\":\"log_demo_1\",\"tenantId\":\"$TENANT\",\"level\":\"ERROR\",\"category\":\"plugin\",\"message\":\"ClassNotFoundException: com.azure.ai.TtsClient\",\"traceId\":\"trace_demo\"}" | head -c 300; echo

# 7. 触发智能诊断
echo -e "\n[7] 触发智能诊断"
curl -sf -X POST "$BASE_URL/api/v1/diagnosis/analyze" -H "$JSON" \
  -d '{"message":"ClassNotFoundException: com.azure.ai.TtsClient","category":"plugin"}' | head -c 700; echo

# 8. 提示词智能优化
echo -e "\n[8] 提示词智能优化"
curl -sf -X POST "$BASE_URL/api/v1/prompt/optimize" -H "$JSON" \
  -d '{"raw_prompt":"你是一个客服，帮我回答用户问题","context":{"use_case":"customer_service","persona":{"tone":"friendly"}},"options":{"generate_examples":true}}' | head -c 900; echo

echo -e "\n=============================================="
echo " 演示完成 ✅"
echo "=============================================="