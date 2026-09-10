package com.agentplatform.core.rag;

import com.agentplatform.core.rag.retriever.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 知识库检索事务回归（内置库 / H2）。
 * <p>
 * 场景：H2 不支持 MySQL 的 {@code MATCH(c.content) AGAINST(...)}，稀疏检索候选集查询必然失败。
 * 修复前该 SQL 异常会让外层 {@code @Transactional(readOnly=true)} 被标记 rollback-only，
 * 即使兜底逻辑算出结果，提交阶段仍抛
 * {@code UnexpectedRollbackException: Transaction silently rolled back...}。
 * </p>
 * 本测试走<b>真实事务</b>：上传 → 检索，断言检索不抛异常且能召回内容。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:kbsearch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class KnowledgeBaseSearchTransactionTest {

    @Autowired
    private KnowledgeBaseService kbService;

    @Test
    void searchDoesNotFailWithRollbackOnly() {
        String kbId = kbService.create("default", "tx-kb", "transaction regression", 512, 50, "recursive").getKbId();

        kbService.uploadDocument("default", kbId, new MockMultipartFile(
                "file", "contract.txt", "text/plain",
                "The breaching party shall pay a penalty of 20 percent of the total contract amount."
                        .getBytes(StandardCharsets.UTF_8)));

        List<RetrievalResult> results = assertDoesNotThrow(
                () -> kbService.search("default", List.of(kbId), "penalty", 5, 0.0, null, true),
                "检索不应因事务被标记 rollback-only 而失败");

        assertFalse(results.isEmpty(), "关键词应能召回切块，实际条数=" + results.size());
    }
}
