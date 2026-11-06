package com.agentplatform.framework.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E4：Spring AI 标准 {@link VectorStore} 的内存实现。
 *
 * <p><b>这个类的价值不在"又一个内存向量库"</b>，而在于它证明：
 * 框架可以把「自己的存储」以 Spring AI 的标准接口暴露出去，于是任何消费
 * {@code VectorStore} / {@code DocumentRetriever} 的第三方组件都能直接用它，
 * 不需要知道我们是谁。</p>
 *
 * <h3>能力边界（刻意，而不是没做完）</h3>
 * <p>{@link Filter.Expression} **只支持 {@code eq}**。原因不是偷懒：平台的 Milvus
 * collection schema 只声明了少量标量字段，复杂布尔表达式在 Milvus 侧根本无法下推。
 * 与其在内存实现里"功能更全"、换到 Milvus 就静默失效，不如一开始就把边界写在代码里——
 * 遇到不支持的类型直接抛 {@link UnsupportedOperationException}，而不是返回错误结果。</p>
 *
 * <p>默认构造需要一个 {@link EmbeddingModel}：向量由它算，本类只负责存与算相似度。
 * 这样框架既不绑定厂商，也不假装自己会做嵌入。</p>
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final EmbeddingModel embeddingModel;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public InMemoryVectorStore(EmbeddingModel embeddingModel) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("EmbeddingModel 必填：向量由它计算");
        }
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String getName() {
        return "agent-in-memory";
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                continue;
            }
            store.put(document.getId(), new Entry(embeddingModel.embed(document), document));
        }
        log.debug("[vector] added {} documents, total={}", documents.size(), store.size());
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null) {
            return;
        }
        idList.forEach(store::remove);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            store.clear();
            return;
        }
        store.entrySet().removeIf(entry -> matches(filterExpression, entry.getValue().document().getMetadata()));
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request == null || request.getQuery() == null) {
            return List.of();
        }
        float[] query = embeddingModel.embed(request.getQuery());
        double threshold = request.getSimilarityThreshold();
        int topK = request.getTopK() > 0 ? request.getTopK() : SearchRequest.DEFAULT_TOP_K;

        List<Scored> scored = new ArrayList<>();
        for (Entry entry : store.values()) {
            Map<String, Object> metadata = entry.document().getMetadata();
            if (request.hasFilterExpression() && !matches(request.getFilterExpression(), metadata)) {
                continue;
            }
            double score = cosine(query, entry.embedding());
            if (score >= threshold) {
                scored.add(new Scored(entry.document(), score));
            }
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Document> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            Scored s = scored.get(i);
            results.add(Document.builder()
                    .id(s.document().getId())
                    .text(s.document().getText())
                    .metadata(s.document().getMetadata())
                    .score(s.score())
                    .build());
        }
        return results;
    }

    /** 记录条数（测试与诊断用）。 */
    public int size() {
        return store.size();
    }

    /**
     * 过滤求值：只认 {@code eq}，其余抛错（见类注释的能力边界）。
     */
    private boolean matches(Filter.Expression expression, Map<String, Object> metadata) {
        if (expression == null) {
            return true;
        }
        if (expression.type() != Filter.ExpressionType.EQ) {
            throw new UnsupportedOperationException(
                    "只支持 eq 过滤（能力边界见 InMemoryVectorStore 类注释），收到：" + expression.type());
        }
        String key = keyOf(expression.left());
        Object expected = valueOf(expression.right());
        Object actual = metadata == null ? null : metadata.get(key);
        return Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }

    private static String keyOf(Filter.Operand operand) {
        if (operand instanceof Filter.Key key) {
            return key.key();
        }
        throw new UnsupportedOperationException("过滤表达式左侧必须是字段名，收到：" + operand);
    }

    private static Object valueOf(Filter.Operand operand) {
        if (operand instanceof Filter.Value value) {
            return value.value();
        }
        throw new UnsupportedOperationException("过滤表达式右侧必须是常量，收到：" + operand);
    }

    /** 余弦相似度；维度不一致时按较短维度计算（避免因模型升维直接崩）。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Entry(float[] embedding, Document document) {
    }

    private record Scored(Document document, double score) {
    }
}
