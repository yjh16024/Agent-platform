package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用收集器的单元测试（2026-09-23，工具调用可视化）。
 *
 * <p>它同时是安全/可观测组件，重点在**边界行为**：没有运行上下文时不能抛异常（工具
 * 也可能被工作流链路调用）、取了要能清理（否则长时间运行会堆积）、以及截断必须生效
 * （它是响应体积的唯一闸门）。</p>
 */
class ToolCallCollectorTest {

    private ToolCallCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ToolCallCollector();
    }

    private static ToolCallRecord ok(String name) {
        return ToolCallRecord.of(name, null, ToolResult.ok("结果是好的"), 12L);
    }

    @Test
    @DisplayName("正常：begin 后记录的调用能被 drain 取到")
    void collectAndDrain() {
        collector.begin("run_1");
        collector.record("run_1", ok("fs_read_file"));
        collector.record("run_1", ok("fs_grep"));

        List<ToolCallRecord> got = collector.drain("run_1");

        assertEquals(2, got.size());
        assertEquals("fs_read_file", got.get(0).name());   // 顺序即调用顺序
        assertEquals("fs_grep", got.get(1).name());
    }

    @Test
    @DisplayName("drain 会取走并清理：再取一次是空（不会重复展示同一批调用）")
    void drainIsDestructive() {
        collector.begin("run_1");
        collector.record("run_1", ok("calc"));

        assertEquals(1, collector.drain("run_1").size());
        assertTrue(collector.drain("run_1").isEmpty());
        assertEquals(0, collector.activeRuns());
    }

    @Test
    @DisplayName("★ 未 begin 就 record：静默忽略而不是抛异常（工具也会被非运行链路调用）")
    void recordWithoutBeginIsIgnored() {
        collector.record("no-such-run", ok("calc"));      // 不应抛异常
        assertTrue(collector.drain("no-such-run").isEmpty());
    }

    @Test
    @DisplayName("★ runId 为 null 时全部操作安全（工作流节点等无会话场景）")
    void nullRunIdIsSafe() {
        collector.begin(null);
        collector.record(null, ok("calc"));
        assertTrue(collector.drain(null).isEmpty());
        collector.discard(null);                          // 不抛
        assertEquals(0, collector.activeRuns());
    }

    @Test
    @DisplayName("多运行互不干扰（并发下按 runId 隔离）")
    void runsAreIsolated() {
        collector.begin("run_a");
        collector.begin("run_b");
        collector.record("run_a", ok("tool_a"));
        collector.record("run_b", ok("tool_b"));

        assertEquals("tool_a", collector.drain("run_a").get(0).name());
        assertEquals("tool_b", collector.drain("run_b").get(0).name());
        assertEquals(0, collector.activeRuns());
    }

    @Test
    @DisplayName("discard 清理槽位（异常/放弃路径不残留）")
    void discardClears() {
        collector.begin("run_1");
        collector.record("run_1", ok("calc"));

        collector.discard("run_1");

        assertTrue(collector.drain("run_1").isEmpty());
        assertEquals(0, collector.activeRuns());
    }

    @Test
    @DisplayName("单次运行的条数有上限（防止响应被反复调用撑爆）")
    void perRunCapEnforced() {
        collector.begin("run_1");
        for (int i = 0; i < 80; i++) {
            collector.record("run_1", ok("tool_" + i));
        }

        List<ToolCallRecord> got = collector.drain("run_1");

        assertTrue(got.size() <= 50, "应被上限截住，实际 " + got.size());
        assertTrue(got.size() >= 1);
    }

    // ---------------- 记录本身 ----------------

    @Test
    @DisplayName("★ 截断：超长入参与输出都被截断（响应体积的唯一闸门）")
    void truncatesLongFields() {
        String huge = "x".repeat(50_000);
        ToolCallRecord r = ToolCallRecord.of("fs_write_file",
                null, ToolResult.ok(huge), 1L);

        assertNotNull(r.output());
        assertTrue(r.output().length() < 5000, "输出应被截断，实际 " + r.output().length());
        assertTrue(r.output().contains("已截断"));

        ToolCallRecord r2 = new ToolCallRecord("fs_edit_file", huge, true, null, null, 1L);
        assertTrue(r2.arguments().length() < 2500, "入参应被截断，实际 " + r2.arguments().length());
    }

    @Test
    @DisplayName("失败结果：error 保留，success=false")
    void failureKeepsError() {
        ToolCallRecord r = ToolCallRecord.of("fs_read_file", null,
                ToolResult.fail("路径超出工作区范围"), 3L);

        assertFalse(r.success());
        assertEquals("路径超出工作区范围", r.error());
        assertNull(r.output());
    }

    @Test
    @DisplayName("args 为 null 时用 {} 兜底（前端解析 JSON 不会拿到空串）")
    void nullArgsBecomeEmptyObject() {
        ToolCallRecord r = ToolCallRecord.of("calc", null, ToolResult.ok("1"), 1L);

        assertEquals("{}", r.arguments());
    }

    @Test
    @DisplayName("字符串结果取原文（不额外包一层引号）")
    void stringOutputIsRawText() {
        ToolCallRecord r = ToolCallRecord.of("calc", null, ToolResult.ok("42"), 1L);

        assertEquals("42", r.output());
    }
}
