package com.agentplatform.core.rag.retriever;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Milvus 向量存储实现（生产 RAG）。
 * <p>
 * 经 {@code agent-platform.rag.vector-store=milvus} 启用，对接 docker-compose 的 Milvus 2.4
 * （19530 端口），走其 RESTful v2 API（HTTP，OkHttp）。懒建集合（首个向量维度定 dim），
 * 检索失败优雅降级为空结果，摄取失败抛出以便管线跳过该 chunk。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.rag.vector-store", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 自定义 schema 的固定标量字段（引用溯源 + 删除过滤）。 */
    private static final String[] SCALAR_FIELDS = {"kb_id", "doc_id"};

    private final String baseUrl;
    private final String collection;
    private final OkHttpClient httpClient;

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile int dimension = 0;

    public MilvusVectorStore(
            @Value("${agent-platform.rag.milvus.base-url:http://localhost:19530}") String baseUrl,
            @Value("${agent-platform.rag.milvus.collection:agent_chunks}") String collection) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.collection = collection;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String name() {
        return "milvus";
    }

    @Override
    public void upsert(String id, float[] embedding, Map<String, Object> metadata) {
        ensureCollection(embedding.length);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", List.of(id));
        row.put("vector", List.of(toList(embedding)));
        for (String scalar : SCALAR_FIELDS) {
            Object v = metadata == null ? null : metadata.get(scalar);
            row.put(scalar, List.of(v == null ? "" : String.valueOf(v)));
        }
        JsonNode res = postJson("/v2/vectordb/entities/insert", Map.of("collectionName", collection, "data", row));
        check(res, "insert");
    }

    @Override
    public List<VectorMatch> similaritySearch(float[] query, int topK, double minScore) {
        if (!ready.get()) {
            log.debug("Milvus collection not ready, skip vector search");
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collection);
        body.put("data", List.of(toList(query)));
        body.put("annsField", "vector");
        body.put("limit", topK);
        body.put("outputFields", List.of("id", "kb_id", "doc_id"));
        body.put("searchParams", Map.of("metricType", "COSINE"));

        try {
            JsonNode res = postJson("/v2/vectordb/entities/search", body);
            check(res, "search");
            JsonNode hits = res.path("data").isArray() && res.path("data").size() > 0
                    ? res.path("data").get(0)
                    : null;
            if (hits == null || !hits.isArray()) {
                return List.of();
            }
            List<VectorMatch> matches = new ArrayList<>();
            for (JsonNode hit : hits) {
                String id = hit.path("id").asText("");
                double distance = hit.path("distance").asDouble(2.0);
                double score = Math.max(0.0, 1.0 - distance / 2.0);
                if (score < minScore) {
                    continue;
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                JsonNode entity = hit.path("entity");
                if (entity.isObject()) {
                    entity.properties().forEach(e -> meta.put(e.getKey(), e.getValue().asText("")));
                }
                matches.add(new VectorMatch(id, score, meta));
            }
            matches.sort((a, b) -> Double.compare(b.score(), a.score()));
            return matches;
        } catch (Exception e) {
            log.warn("Milvus search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void delete(String id) {
        if (!ready.get()) {
            return;
        }
        try {
            JsonNode res = postJson("/v2/vectordb/entities/delete",
                    Map.of("collectionName", collection, "filter", "id in [\"" + id + "\"]"));
            check(res, "delete");
        } catch (Exception e) {
            log.warn("Milvus delete {} failed: {}", id, e.getMessage());
        }
    }

    @Override
    public void deleteByFilter(String key, String value) {
        if (!ready.get()) {
            return;
        }
        try {
            String filter = key + " in [\"" + value + "\"]";
            JsonNode res = postJson("/v2/vectordb/entities/delete",
                    Map.of("collectionName", collection, "filter", filter));
            check(res, "deleteByFilter");
        } catch (Exception e) {
            log.warn("Milvus deleteByFilter {}={} failed: {}", key, value, e.getMessage());
        }
    }

    /** 懒建集合：首个向量维度确定后在 Milvus 建 collection；维度变化时先 drop 旧集再重建。 */
    private synchronized void ensureCollection(int dim) {
        if (ready.get() && dimension == dim) {
            return;
        }
        // 换嵌入模型导致维度变化：旧 collection 维度不匹配，需 drop 后按新 dim 重建
        if (ready.get() && dimension != dim) {
            log.warn("Milvus collection {} dim changed {} -> {}, dropping & recreating", collection, dimension, dim);
            dropCollection();
            this.ready.set(false);
        }
        try {
            List<Map<String, Object>> fields = new ArrayList<>();
            Map<String, Object> idField = new LinkedHashMap<>();
            idField.put("fieldName", "id");
            idField.put("dataType", "VarChar");
            idField.put("isPrimary", true);
            idField.put("maxLength", 128);
            fields.add(idField);

            Map<String, Object> vectorField = new LinkedHashMap<>();
            vectorField.put("fieldName", "vector");
            vectorField.put("dataType", "FloatVector");
            vectorField.put("dim", dim);
            fields.add(vectorField);

            for (String scalar : SCALAR_FIELDS) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fieldName", scalar);
                f.put("dataType", "VarChar");
                f.put("maxLength", 64);
                fields.add(f);
            }

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("autoID", false);
            schema.put("fields", fields);

            JsonNode res = postJson("/v2/vectordb/collections/create",
                    Map.of("collectionName", collection, "schema", schema));
            check(res, "create collection");
            this.dimension = dim;
            this.ready.set(true);
            log.info("Milvus collection {} ready (dim={})", collection, dim);
        } catch (Exception e) {
            log.warn("Milvus ensureCollection failed: {}", e.getMessage());
            throw BizException.internal("Milvus collection init failed", e);
        }
    }

    /** 删除集合（换嵌入模型维度不符时重建用；集合不存在时忽略）。 */
    private void dropCollection() {
        try {
            postJson("/v2/vectordb/collections/drop", Map.of("collectionName", collection));
            log.info("Milvus collection {} dropped", collection);
        } catch (Exception e) {
            log.warn("Milvus drop collection {} failed (may not exist): {}", collection, e.getMessage());
        }
    }

    private void check(JsonNode res, String op) {
        if (res == null || res.path("code").asInt(0) != 0) {
            String msg = res == null ? "no response" : res.path("message").asText("unknown");
            throw BizException.internal("Milvus " + op + " failed: " + msg);
        }
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(JsonUtils.toJson(body), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            String rsp = response.body() == null ? "" : response.body().string();
            return JsonUtils.toJsonNode(rsp);
        } catch (IOException e) {
            throw BizException.internal("Milvus HTTP " + path + " failed", e);
        }
    }

    private List<Float> toList(float[] embedding) {
        List<Float> list = new ArrayList<>(embedding.length);
        for (float f : embedding) {
            list.add(f);
        }
        return list;
    }
}