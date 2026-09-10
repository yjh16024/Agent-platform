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
 * FULLTEXT 失败隔离验证（模拟生产/MySQL 之外的数据库，或未建全文索引的环境）。
 * <p>
 * 强制 {@code agent-platform.rag.fulltext.enabled=true}：在 H2 上执行 MySQL 专有的
 * {@code MATCH(c.content) AGAINST(...)} 必然抛 SQL 异常。该异常必须被
 * {@link com.agentplatform.core.rag.retriever.ChunkFullTextSearcher} 的<b>独立事务</b>隔离，
 * 不能把外层检索事务标记为 rollback-only。
 * </p>
 * 若本测试失败（抛 UnexpectedRollbackException），说明 FULLTEXT 又回到了外层事务中执行。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:kbfulltext;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        "agent-platform.rag.fulltext.enabled=true"
})
class KnowledgeBaseFullTextFailureTest {

    @Autowired
    private KnowledgeBaseService kbService;

    @Test
    void searchSurvivesFullTextSqlFailure() {
        String kbId = kbService.create("default", "ft-kb", "fulltext failure isolation", 512, 50, "recursive").getKbId();

        kbService.uploadDocument("default", kbId, new MockMultipartFile(
                "file", "clause.txt", "text/plain",
                "The breaching party shall pay a penalty of 20 percent of the total contract amount."
                        .getBytes(StandardCharsets.UTF_8)));

        List<RetrievalResult> results = assertDoesNotThrow(
                () -> kbService.search("default", List.of(kbId), "penalty", 5, 0.0, null, true),
                "FULLTEXT 失败必须被隔离在独立事务中，不应污染外层检索事务");

        assertFalse(results.isEmpty(), "FULLTEXT 失败后应回退顺序扫描并召回内容，实际条数=" + results.size());
    }
}
