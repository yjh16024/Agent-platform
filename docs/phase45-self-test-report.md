# Phase 4.5 · 多模态 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 多模态 parts 消息模型 | ✅ | `ContentPart` 密封接口（text/image/audio/video/file/tool_result）+ Switch 穷尽 |
| 2 | 模型能力探测 | ✅ | `MultimodalResolver`（图片→VISION、音视频→AUDIO、缺省→TEXT） |
| 3 | 文件上传管线 | ✅ | `FileUploadService`（格式校验 + 转存 + 元数据）+ `FileUploadController` |
| 4 | ASR 插件化 | ✅ | `AsrPlugin`（asr_transcribe 工具，与 TTS 对称） |
| 5 | 模型路由 + Fallback | ✅ | `ModelRouter`（能力标签 + 健康度 + 成本路由 + 本地 Mock 兜底） |
| 6 | 多租户配额 | ✅ | `QuotaService`（按租户+类型限额/计数，集成进 run 流程） |
| 7 | 数据库迁移 | ✅ | `V5__multimodal.sql`（file_asset/tenant_quota） |
| 8 | 单元测试 | ✅ | 5 个新测试，总计 68 个全部通过 |

## 测试结果

```
Tests run: 68, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  Phase 4.5 新增:
    MultimodalTest : 5  ✅  (图片VISION/音频AUDIO/纯文本TEXT/sealed switch/配额超限)
```

## 关键设计决策

1. **`ContentPart` 密封接口**：JDK 21 sealed interface + record，六类 parts 用 Switch 模式匹配
   获得编译期穷尽检查——新增 parts 类型时编译器强制处理所有分支（对应 §4.5「密封类/密封接口
   + Switch 模式匹配」）。

2. **JSON 多态反序列化**：`@JsonTypeInfo` + `@JsonSubTypes` 按 `type` 字段将请求体的
   `parts[]`（如 `{"type":"image","file_id":"fid_123"}`）自动反序列化为对应子类型。

3. **ASR 与 TTS 对称**：TTS（文本→语音，after_llm + 工具）与 ASR（语音→文本，工具）构成
   完整的多模态插件对，均遵循 plugin-sdk 的 `ToolProvider` SPI，演示「多模态能力插件化」。

4. **配额内存计数**：MVP 用 `ConcurrentHashMap<AtomicLong>` 限额+计数，集成进 `agent/run`
   流程；生产接 Redis INCR + TTL 与 `tenant_quota` 表持久化。

5. **多模态路由**：`ModelRouter.route(provider, capability)` 已支持 VISION/AUDIO 能力标签路由，
   `MultimodalResolver` 从 parts 探测所需能力，二者衔接实现「按 parts 自动选择多模态模型」。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| 真实 OCR/DJL 推理未接 | 图像/音频为模拟处理 | 接 DJL/OpenCV/PaddleOCR（生产） |
| 配额内存态（重启丢失） | 用量计数重置 | 接 Redis 持久化 |
| 多模态模型调用走文本 Mock | 真实多模态推理未验证 | 配置 LiteLLM 视觉模型 |

## 验证方式

```bash
# 上传文件（图片→返回 file_id）
curl -X POST http://localhost:8081/api/v1/files/upload \
  -F "file=@image.png" -F "type=image"

# 挂载 ASR 插件（多模态能力插件化）
curl -X POST http://localhost:8081/api/v1/plugins/plugin_asr/attach \
  -H "X-Tenant-Id: t1" -H "Content-Type: application/json" \
  -d '{"agent_id":"agent_xxx"}'
```