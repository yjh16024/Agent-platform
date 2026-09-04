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
}