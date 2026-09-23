package com.agentplatform.model.repository;

import com.agentplatform.model.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息仓储。
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 按会话 + 轮次 + 序号排序取全部消息（时间顺序）。
     */
    List<Message> findBySessionIdOrderByTurnNoAscSeqNoAsc(String sessionId);

    /**
     * 分页取会话消息（倒序，供前端增量拉取）。
     */
    Page<Message> findBySessionIdOrderByTurnNoDescSeqNoDesc(String sessionId, Pageable pageable);

    /**
     * 取会话最后一个轮次号（用于追加时递增）。
     */
    @Query("SELECT COALESCE(MAX(m.turnNo), 0) FROM Message m WHERE m.sessionId = :sessionId")
    int maxTurnNo(@Param("sessionId") String sessionId);

    /**
     * 删除会话全部消息（清除会话历史时调用）。
     */
    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);

    /**
     * 按业务 ID 批量取消息（**向量记忆召回后回取原文用**）。
     *
     * <p>为什么必须回取原文、而不是把文本一并塞进向量库的 metadata：
     * {@code MilvusVectorStore} 的 collection schema 只声明了 {@code kb_id}/{@code doc_id}
     * 两个标量字段，其余 metadata 键**不会被持久化**（in-memory 实现会保留，Milvus 不会）。
     * 若依赖 metadata 拿原文，功能在 Milvus 下会静默失效。用业务 ID 回查数据库
     * 两种实现都成立。</p>
     */
    List<Message> findByMessageIdIn(java.util.Collection<String> messageIds);

    /**
     * 按会话集合 + 角色取消息（**向量记忆"重建索引"用**）。
     *
     * <p>注意 {@code Message} 上<b>没有</b> {@code user_id}：消息只挂在会话下，
     * 归属用户要经 {@code session_def} 才拿得到。所以重建流程是
     * 「先查该用户的 sessionId 集合 → 再用本方法取这些会话里的 user 消息」。</p>
     */
    List<Message> findBySessionIdInAndRole(java.util.Collection<String> sessionIds, String role);
}