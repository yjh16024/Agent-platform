package com.agentplatform.core.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置库（H2）完整启动冒烟：走 embedded profile + H2 内存库，
 * 验证 Flyway(h2 迁移集) + JPA/Hibernate + 核心组件能完整装配启动。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:bootsmoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class EmbeddedAppBootTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void embeddedContextStartsAndCoreBeansPresent() {
        assertNotNull(context, "Spring 上下文应能启动（embedded 模式）");
        assertTrue(context.containsBean("agentRuntimeService"), "核心运行服务应装配");
        assertTrue(context.containsBean("knowledgeBaseService"), "RAG 服务应装配");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatedExpectedTables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='PUBLIC'", Integer.class);
        assertTrue(tables != null && tables >= 10, "迁移应建出主要表，实际=" + tables);
    }
}
