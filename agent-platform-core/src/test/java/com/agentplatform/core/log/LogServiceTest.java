package com.agentplatform.core.log;

import com.agentplatform.common.dto.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日志服务单元测试（采集 + 指纹 + 查询）。
 */
class LogServiceTest {

    private LogService service;

    @BeforeEach
    void setUp() {
        service = new LogService(new FingerprintGenerator());
    }

    @Test
    @DisplayName("ERROR 日志自动生成指纹")
    void errorLogGeneratesFingerprint() {
        LogEvent e = service.log(LogLevel.ERROR, LogCategory.plugin,
                "ClassNotFoundException: missing", "t1", "trace_1", "run_1", Map.of());
        assertEquals("plugin#classloader_error", e.fingerprint());
    }

    @Test
    @DisplayName("INFO 日志不生成指纹")
    void infoLogNoFingerprint() {
        LogEvent e = service.log(LogLevel.INFO, LogCategory.agent,
                "run completed", "t1", "trace_1", "run_1", Map.of());
        assertNull(e.fingerprint());
    }

    @Test
    @DisplayName("按 level + category 过滤查询")
    void queryByFilter() {
        service.log(LogLevel.ERROR, LogCategory.plugin, "err1", "t1", "t1r", "r1", Map.of());
        service.log(LogLevel.INFO, LogCategory.agent, "info1", "t1", "t2r", "r2", Map.of());

        LogQuery query = new LogQuery(null, null, null, null, null,
                LogLevel.ERROR, null, null, null);
        PageResult<LogEvent> result = service.query(query, 0, 10);
        assertEquals(1, result.getTotal());
        assertEquals(LogLevel.ERROR, result.getItems().get(0).level());
    }

    @Test
    @DisplayName("按关键词全文检索")
    void queryByKeyword() {
        service.log(LogLevel.ERROR, LogCategory.plugin,
                "Failed to load plugin_tts_azure", "t1", "t1r", "r1", Map.of());
        LogQuery query = new LogQuery(null, null, null, null, null, null, null, null, "plugin_tts_azure");
        assertEquals(1, service.query(query, 0, 10).getTotal());
    }

    @Test
    @DisplayName("瀑布图按 trace 分组")
    void waterfall() {
        service.log(LogLevel.INFO, LogCategory.agent, "start", "t1", "trace_x", "r1", Map.of());
        service.log(LogLevel.INFO, LogCategory.llm, "model call", "t1", "trace_x", "r1", Map.of());
        service.log(LogLevel.INFO, LogCategory.plugin, "tts", "t1", "trace_x", "r1", Map.of());
        assertEquals(3, service.waterfall("trace_x").size());
    }
}