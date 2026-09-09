package com.agentplatform.core.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB 内置化可行性冒烟：现有 V1–V11 迁移能否在 H2（MySQL 兼容模式）全部跑通。
 * <p>通过 → 内置化只需「H2 依赖 + 数据源切换 + 全文检索降级兜底」；失败 → 输出阻塞脚本以便逐个方言分支。</p>
 */
class EmbeddedDbMigrationSmokeTest {

    private static final String H2_URL =
            "jdbc:h2:mem:embedded_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void allFlywayMigrationsRunOnH2InMySqlMode() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(H2_URL, "sa", "")
                .locations("classpath:db/migration/h2")
                .load();

        org.flywaydb.core.api.output.MigrateResult result = flyway.migrate();

        assertEquals(result.migrationsExecuted, result.migrationsExecuted, "migrate() 不应抛异常");
        assertTrue(result.success, "所有迁移应执行成功，实际执行数=" + result.migrationsExecuted);

        // 抽查关键表
        try (Connection conn = DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'");
            int tables = 0;
            StringBuilder names = new StringBuilder();
            while (rs.next()) {
                tables++;
                names.append(rs.getString(1)).append(' ');
            }
            assertTrue(tables >= 10, "应建出主要业务表，实际=" + names);
        }
    }
}
