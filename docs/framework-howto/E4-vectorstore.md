# E4：用自研后端做 RAG

**适用场景**：你已有自己的向量存储（或想先用内存版跑通），但希望**任何消费 Spring AI
`VectorStore` 的组件都能直接用**——包括官方的 `VectorStoreDocumentRetriever`、`QuestionAnswerAdvisor`。

## 最小代码

```java
public class MyVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;   // 向量由它算，你不要自己实现嵌入

    @Override
    public void add(List<Document> documents) {
        for (Document d : documents) {
            store.put(d.getId(), new Entry(embeddingModel.embed(d), d));
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        float[] q = embeddingModel.embed(request.getQuery());
        // 算余弦 → 按 request.getSimilarityThreshold() 过滤
        // → 按 request.getTopK() 截断 → 给结果 Document 带上 score
    }

    @Override
    public void delete(List<String> ids) { ids.forEach(store::remove); }

    @Override
    public void delete(Filter.Expression filter) { /* 见下方注意 2 */ }
}
```

## 必须注意的三条

### 1. 构造函数强制要 `EmbeddingModel`，这是对的

不要"顺手"给个默认实现或偷偷读全局配置。向量由谁算必须显式声明——
否则宿主换模型时，向量空间悄悄变了，召回结果会变得毫无意义且**不报错**。

### 2. `Filter.Expression` 只支持 `eq`，其余必须抛异常

这不是没做完，是**能力边界**：平台的 Milvus collection schema 只声明了少量标量字段，
复杂布尔表达式（`AND` / `OR` / `GT` …）在 Milvus 侧根本无法下推。

内存实现"功能更全"反而危险：本地跑得过、换 Milvus 静默失效。所以：

```java
if (expression.type() != Filter.ExpressionType.EQ) {
    throw new UnsupportedOperationException("只支持 eq 过滤，收到：" + expression.type());
}
```

**边界写在代码里，比写在文档里可靠。**

### 3. 返回的 `Document` 必须带 `score`

调用方（尤其官方 advisor）依赖它做排序与阈值判断：

```java
Document.builder()
        .id(d.getId()).text(d.getText()).metadata(d.getMetadata())
        .score(similarity)
        .build();
```

漏了 `score`，排序看起来"随机"，而且没人会报错。

## 接进标准 RAG 组件

```java
VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
        .vectorStore(new MyVectorStore(embeddingModel))
        .topK(4)
        .similarityThreshold(0.5)
        .build();

List<Document> hits = retriever.retrieve(new Query("用户的问题"));
```

## 参考与验证

- 实现：`agent-platform-framework-rag` 的 `InMemoryVectorStore`
- 消费证明：`agent-platform-framework-sample-rag` 的 `OfficialRetrieverConsumesOurStoreTest`
  （官方检索器直接检索我们的库，含 eq 过滤一例）
- 单测：`InMemoryVectorStoreTest`（排序 / topK+阈值 / eq 过滤 / **非 eq 抛错** / 两种删除）
