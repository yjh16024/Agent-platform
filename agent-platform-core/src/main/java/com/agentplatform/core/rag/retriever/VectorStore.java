package com.agentplatform.core.rag.retriever;

import java.util.List;
import java.util.Map;

/**
 * 向量存储抽象（关键抽象层，对应设计文档「向量检索服务抽象为 VectorStore 接口」）。
 * <p>
 * 默认实现为 {@code InMemoryVectorStore}（无外部依赖，开发/测试/演示可用）；
 * 生产环境替换为 Milvus 实现（切换实现即可，应用层无感知）。
 * </p>
 */
public interface VectorStore {

    /**
     * 实现名称（in-memory / milvus）。
     */
    String name();

    /**
     * 写入/更新一条向量记录。
     *
     * @param id       记录 ID（chunk external_id）
     * @param embedding 向量
     * @param metadata 元数据（kb_id/doc_id/page 等）
     */
    void upsert(String id, float[] embedding, Map<String, Object> metadata);

    /**
     * 向量相似检索（余弦相似度）。
     *
     * @param query     查询向量
     * @param topK      返回条数
     * @param minScore  最低相似度阈值
     * @return 匹配结果（按分数降序）
     */
    List<VectorMatch> similaritySearch(float[] query, int topK, double minScore);

    /**
     * 删除记录。
     */
    void delete(String id);

    /**
     * 清空（按 kb_id 过滤元数据删除）。
     */
    void deleteByFilter(String key, String value);

    /**
     * 向量匹配结果。
     *
     * @param id      记录 ID
     * @param score   相似度分数（0~1）
     * @param metadata 元数据
     */
    record VectorMatch(String id, double score, Map<String, Object> metadata) {
    }
}