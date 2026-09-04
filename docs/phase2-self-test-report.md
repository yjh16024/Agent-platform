# Phase 2 · RAG + 工具 — 阶段自测报告

## 交付物清单

| # | 交付物 | 状态 | 说明 |
|---|--------|------|------|
| 1 | 知识库管理 | ✅ | `KnowledgeBaseService` + Controller（创建/上传/检索/列表/删除） |
| 2 | 文档解析管线 | ✅ | `DocumentParser`（Tika 解析 PDF/Docx/PPTx/Excel + 原生 Markdown/HTML） |
| 3 | 切分管线 | ✅ | `Chunker` 模板方法 + `Recursive/Semantic/Structural` 策略 + `ChunkerFactory` |
| 4 | MySQL + Milvus 混合检索 | ✅ | `HybridRetriever`（稠密 + 稀疏 RRF 融合 + 元数据过滤）+ `VectorStore` 抽象 |
| 5 | Rerank + 引用溯源 | ✅ | score 降序重排 + chunk 级来源/页码元数据 |
| 6 | 工具注册中心 | ✅ | `ToolRegistry`（注册表）+ `ToolExecutor`（责任链）+ 内置 calc/search 工具 |
| 7 | MCP 接入 | 🔶 | 骨架（`Tool` 接口即 MCP 工具接入点，`inputSchema` 已对齐 MCP 协议） |
| 8 | 数据库迁移 | ✅ | `V2__rag_tool.sql`（knowledge_base/document/chunk/tool_def） |
| 9 | 单元测试 | ✅ | 20 个新测试，总计 39 个全部通过 |

## 测试结果

```
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  Phase 2 新增:
    ChunkerTest        : 6  ✅  (递归/结构/语义切分 + 策略名)
    DocumentParserTest : 3  ✅  (Markdown/HTML/类型推断)
    VectorStoreTest    : 4  ✅  (相似检索/阈值/过滤/余弦)
    CalculatorToolTest : 4  ✅  (四则/函数/非法/缺参)
    ToolExecutorTest   : 3  ✅  (注册表/责任链/不存在)
```

## 关键设计决策

1. **`VectorStore` 抽象 + `InMemoryVectorStore` 默认实现**：设计文档明确「向量检索服务抽象为
   VectorStore 接口（Spring AI 已提供 MilvusVectorStore 可移植抽象）」。MVP 用内存实现保证
   无外部依赖可运行；生产替换 `MilvusVectorStore` 实现即可，应用层零改动。

2. **Tika 2.9.2 文档解析**：`tika-core`（Tika 门面）+ `tika-parsers-standard-package`（各格式解析器）。
   Markdown/HTML 走原生解析避免额外开销。

3. **混合检索 RRF 融合**：稠密（向量余弦）与稀疏（关键词命中率，BM25 近似）结果按
   Reciprocal Rank Fusion 融合，兼顾语义与精确匹配；`chunk` 表建了 FULLTEXT ngram 索引
   以便后续升级到 MySQL 原生全文检索。

4. **计算器自研安全求值器**：JDK 15+ 移除了 Nashorn JS 引擎，且任意表达式执行有安全风险，
   改用递归下降求值器（仅数字 + 有限运算符 + 白名单函数），杜绝代码注入。

5. **MCP 接入**：未引入 MCP SDK（避免不确定 API），`Tool` 接口的 `name/description/inputSchema`
   与 MCP 工具定义结构一致，MCP Server 工具可包装为 `Tool` 注册进 `ToolRegistry` 即用。

## 未解决/已知风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| Tika 依赖树大（fat jar +45MB） | 构建/部署体积 | 可拆出独立的文档解析微服务 |
| 稀疏检索为内存实现（非 MySQL FULLTEXT） | 大数据量时性能 | 已建 ngram FULLTEXT 索引，可切 MySQL 原生 |
| embedding 走本地 Mock（伪向量） | 语义相似度不真实 | 配置 LiteLLM 后走真实 embedding 模型 |
| 中文分词为 2-gram 近似 | 中文检索召回略低 | 后续接入 IK/jieba 分词器 |

## 验证方式

```bash
# 创建知识库
curl -X POST "http://localhost:8081/api/v1/knowledge-bases?name=产品文档" \
  -H "X-Tenant-Id: t1"

# 上传文档（Markdown）
curl -X POST "http://localhost:8081/api/v1/knowledge-bases/kb_xxx/documents" \
  -H "X-Tenant-Id: t1" -F "file=@docs.md"

# 检索
curl -X POST "http://localhost:8081/api/v1/knowledge-bases/search?kbIds=kb_xxx&query=退款政策" \
  -H "Content-Type: application/json"

# 列出工具
curl http://localhost:8081/api/v1/tools

# 调用计算器
curl -X POST "http://localhost:8081/api/v1/tools/calc/invoke" \
  -H "Content-Type: application/json" -d '{"expression":"2+3*4"}'
```