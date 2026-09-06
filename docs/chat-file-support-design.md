# 对话支持文件拖入与读取 · 技术设计（A/B/C 分阶段）

> 目标：让「对话运行」页支持把文件拖入输入区并让智能体读取/处理。
> 现状盘点（决定复用点）：
> - 消息模型已预留多模态：`ContentPart`（sealed，text/image/audio/video/file/tool_result），
>   `/agent/run` 的 `messages[].content` 为 Object，兼容字符串与 parts[]；
> - 文件链路已存在：`POST /api/v1/files`（上传/列表/下载/删除），本地磁盘或 MinIO；
> - 文档解析已存在：`rag/pipeline/DocumentParser`（Tika，支持 txt/md/pdf/docx/pptx/xlsx/html）；
> - RAG 摄取管线已存在：`RagPipelineService`。
> 因此本功能以「新增入口 + 打通既有组件」为主，几乎无需新造轮子。

## 需求拆解

| 输入 | 期望行为 |
|------|----------|
| `.txt/.md/.csv/.html` 等纯文本 | 直接读取内容作为本轮上下文 |
| `.pdf/.docx/.pptx/.xlsx` 等富文档 | 解析（Tika）后读取文本作为上下文（截断上限内） |
| 图片（png/jpg/…） | 方案 B：作为 vision 输入发给视觉模型；方案 A 阶段给出明确提示 |
| 大文件 / 手册类 | 方案 C：建议归入知识库走 RAG 检索（带引用、不爆上下文） |
| 音视频 | 依赖 ASR 外部服务（二期），A 阶段提示暂不支持 |

## 总体设计

```
┌──────────────┐   拖拽/多选     ┌──────────────┐   upload    ┌──────────────────┐
│  ChatPage    │ ───────────────►│  附件暂存区    │ ──────────► │  /api/v1/files    │
│  (前端)      │                 │ fileId+名称    │            │  (已有)           │
└──────────────┘                 └──────────────┘            └──────────────────┘
      │ 发送时把最后一条 user 消息 content 组装为 parts[]（text + file）
      ▼
┌──────────────────────────────────────────────┐
│  POST /agent/run (content 兼容 parts)          │
│  AgentRuntimeService.userContextResolver       │
│   ├─ text part → 拼接文本                       │
│   ├─ file part → FileUploadService.readText()  │  ← 复用 DocumentParser
│   └─ 其他 part → 占位提示                       │
└──────────────────────────────────────────────┘
```

### 关键点 1：消息结构约定
- 历史轮次维持纯文本字符串消息（后端回放不受影响）；
- **仅当次发送的最新 user 消息**携带附件时使用 parts[]：
  ```json
  {"role":"user","content":[
    {"type":"text","text":"请总结这份合同的风险点"},
    {"type":"file","fileId":"fid_xxx","fileName":"合同.pdf"}
  ]}
  ```
- 前端本地气泡显示仍为纯文本 + 附件标签（历史持久化不受影响）。

### 关键点 2：后端读取解析（方案 A 核心）
- `FileUploadService.readText(tenantId, fileId, maxChars)`：下载字节 → `DocumentParser` 解析 →
  截断（单文件默认 100k 字符）→ 返回 `{name, text, truncated}`；
- 仅允许「可解析文本」类型（由扩展名判定），图片/音视频返回明确错误文案；
- `AgentRuntimeService` 注入可选 `FileUploadService`（单测不破坏），新增
  `resolveUserMessage(req, tenantId)`：取最后一条 user 消息，content 为字符串时原样返回；
  为 parts[] 时拼接 text，并把每个 file part 的解析文本用固定头注入：
  `\n\n[文件：<name>]\n<文本>…`；
- 文件归属校验复用 `download(tenantId,fileId)` 的租户隔离（跨租户文件直接 404）。

### 关键点 3：上下文窗口保护
- 每个文件截断 100k 字符（约 25k tokens 上限内），单次最多 4 个文件、合计 200k 字符；
- 超限自动截断并在文本头标注 `(已截断)`；超大文档引导走方案 C。

## 三阶段实施计划

### 方案 A：文档拖入即读（✅ 已交付 2026-09-06）
- [x] 技术文档
- [x] 后端：`FileUploadService.readText(tenantId,fileId[,maxChars])`（复用 DocumentParser，
      截断/类型白名单/租户隔离）；`AgentRuntimeService.extractUserMessage(req, tenantId)`
      支持 parts[]：text 拼接、file 下载解析注入 `[文件：name]` 区块、单次 ≤4 个、超限跳过、
      读取失败回退为占位说明不阻断
- [x] 前端：对话页 `Upload.Dragger` 拖入/多选 → `/api/v1/files/upload` → 附件标签（可删）→
      发送时最新 user 消息组装 parts[]；历史仍存纯文本
- [x] 单测：`FileUploadReadTextTest`（txt 解析/截断/图片拒绝/跨租户拒绝）4 例，全量 104 通过
- 使用：需重建前端（`npm run build:prod`）后重启；拖入 `1.txt` 问"文件里写了什么"即可。
- 边界：附件仅在发送当轮注入上下文；跨轮记忆请等方案 C（入库检索）。

### 方案 B：图片走视觉模型（二期）
- `ContentPart.ImagePart` 已有；需把上传图片以 base64 data URL 或公网 URL 交给模型；
- 扩展 `OpenAiCompatibleAdapter`/`AnthropicAdapter` 的请求体支持多模态 content 数组
  （OpenAI：`content:[{type:image_url,image_url:{url}}]`；Anthropic：`content:[{type:image,source:{...}}]`）；
- 路由校验：provider/model 能力含 VISION 才允许 image part，否则友好报错；
- 验收：拖入截图问"图里是什么"，配 gpt-4o / claude 可回答。

### 方案 C：大文件走 RAG（三期）
- 拖入时弹「归入哪个知识库」或默认自动建"会话库"→ 上传即摄取（复用 RagPipelineService）；
- 对话请求携带 `context.rag.knowledgeBaseIds=[会话库]`，命中「≥X 页 / ≥Y MB」大文件自动切换；
- 检索结果以引用块随回答给出（前端展示来源文件名/页码）；
- 验收：拖入 50 页 PDF 后提问，回答含引用且不再受单消息长度限制。

## 边界与限制
- A 阶段对"多轮记忆文件内容"不承诺：附件仅在发送当轮被解析进上下文；
  如需跨轮记忆请用方案 C（入库后每轮检索）。
- 解析依赖 Tika，畸形/加密 PDF 可能失败 → 返回明确错误而非静默吞掉。
- 安全：文件读取严格按租户隔离；文档解析沿用 Tika，不对上传内容执行任何脚本。
