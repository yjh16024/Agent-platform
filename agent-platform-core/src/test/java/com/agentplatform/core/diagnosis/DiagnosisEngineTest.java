package com.agentplatform.core.diagnosis;

import com.agentplatform.core.diagnosis.engine.DiagnosisEngine;
import com.agentplatform.core.diagnosis.rule.BuiltinDiagnosisRules;
import com.agentplatform.core.diagnosis.strategy.RuleBasedDiagnosis;
import com.agentplatform.core.log.LogCategory;
import com.agentplatform.core.log.LogEvent;
import com.agentplatform.core.log.LogLevel;
import com.agentplatform.model.record.DiagnosisSource;
import com.agentplatform.model.record.DiagnosticReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 诊断引擎单元测试（三级策略，规则命中 + 未知兜底）。
 */
class DiagnosisEngineTest {

    private DiagnosisEngine engine;

    @BeforeEach
    void setUp() {
        // 仅注入规则策略（确定性测试）
        engine = new DiagnosisEngine(List.of(
                new RuleBasedDiagnosis(new BuiltinDiagnosisRules())));
    }

    private DiagnosisContext ctx(String fingerprint, String message, LogCategory category) {
        LogEvent event = LogEvent.of("log_1", LogLevel.ERROR, category, message,
                "t1", "trace_1", "run_1", Map.of("plugin_id", "plugin_tts_azure"));
        return new DiagnosisContext("trace_1", event, fingerprint, category, message, null, event.context());
    }

    @Test
    @DisplayName("规则命中：插件类加载错误返回根因 + 方案")
    void ruleHitReturnsDiagnosis() {
        DiagnosticReport report = engine.diagnose(
                ctx("plugin#classloader_error", "ClassNotFoundException", LogCategory.plugin));
        assertEquals(DiagnosisSource.RULE, report.source());
        assertTrue(report.rootCause().summary().contains("缺少依赖"));
        assertFalse(report.solutions().isEmpty());
        assertTrue(report.confidence() >= 0.9);
    }

    @Test
    @DisplayName("规则命中选择 JSON 序列化为 RULE 字符串")
    void ruleSourceToString() {
        DiagnosticReport report = engine.diagnose(
                ctx("model#connection_refused", "Connection refused", LogCategory.llm));
        assertEquals("RULE", DiagnosisSource.toDb(report.source()));
    }

    @Test
    @DisplayName("规则未命中返回 unknown 报告")
    void ruleMissReturnsUnknown() {
        DiagnosticReport report = engine.diagnose(
                ctx("some#unknown_fingerprint", "weird error", LogCategory.plugin));
        assertNotNull(report);
        assertTrue(report.solutions().isEmpty());
        assertEquals("未能定位根因", report.rootCause().summary());
    }

    @Test
    @DisplayName("结构化并发诊断（ShutdownOnSuccess）")
    void concurrentDiagnosis() {
        DiagnosticReport report = engine.diagnoseConcurrent(
                ctx("plugin#code", "ClassNotFoundException: missing dep", LogCategory.plugin));
        // plugin#code 不在规则库，应返回 unknown（规则策略抛出 → 无成功分支）
        assertNotNull(report);
    }
}