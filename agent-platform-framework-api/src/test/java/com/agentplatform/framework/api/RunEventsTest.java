package com.agentplatform.framework.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventsTest {

    private static List<RunEvent> happyPath(String runId) {
        RunEvents e = new RunEvents(runId);
        List<RunEvent> events = new ArrayList<>();
        events.add(e.started("trace-1", "agent-1", "s-1"));
        events.add(e.reasoning("先想想"));
        events.add(e.delta("你好"));
        events.add(e.delta("，世界"));
        events.add(e.completed(new RunResult(runId, "trace-1", "agent-1", "s-1",
                "你好，世界", "先想想", "mock", "mock-1",
                new RunResult.Usage(1, 2), List.of(), List.of())));
        return events;
    }

    @Test
    void 正常事件流应通过校验() {
        assertDoesNotThrow(() -> RunEvents.validate(happyPath("r-1")));
    }

    @Test
    void 序号从1开始且步长为1() {
        List<RunEvent> events = happyPath("r-1");

        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).seq());
        }
    }

    @Test
    void 缺少终止事件应拒绝() {
        List<RunEvent> events = happyPath("r-1");
        events.remove(events.size() - 1);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> RunEvents.validate(events));
        assertTrue(ex.getMessage().contains("终止事件"));
    }

    @Test
    void 终止事件之后不得再发事件() {
        List<RunEvent> events = happyPath("r-1");
        events.add(new RunEvent.TextDelta("r-1", events.size() + 1L, Instant.now(), "迟到"));

        assertThrows(IllegalStateException.class, () -> RunEvents.validate(events));
    }

    @Test
    void 首个事件必须是Started() {
        List<RunEvent> events = List.of(
                new RunEvent.TextDelta("r-1", 1, Instant.now(), "x"),
                new RunEvent.Completed("r-1", 2, Instant.now(), null));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> RunEvents.validate(events));
        assertTrue(ex.getMessage().contains("Started"));
    }

    @Test
    void 序号断裂应拒绝() {
        List<RunEvent> events = happyPath("r-1");
        // 删掉中间一条，造成 2 → 4 的断裂
        events.remove(2);

        assertThrows(IllegalStateException.class, () -> RunEvents.validate(events));
    }

    @Test
    void runId不一致应拒绝() {
        RunEvents e = new RunEvents("r-2");
        List<RunEvent> events = new ArrayList<>(happyPath("r-1"));
        events.add(new RunEvent.TextDelta("r-2", events.size() + 1L, Instant.now(), "串流"));

        assertThrows(IllegalStateException.class, () -> RunEvents.validate(events));
        // 反向确认：同一 runId 的构造器产出的流是合法的
        List<RunEvent> other = List.of(e.started("t", "a", "s"), e.failed("E", "boom", false));
        assertDoesNotThrow(() -> RunEvents.validate(other));
    }

    @Test
    void 失败流也是合法终止() {
        RunEvents e = new RunEvents("r-3");
        List<RunEvent> events = List.of(
                e.started("trace", "agent", "s"),
                e.toolStarted("c-1", "weather", null),
                e.failed("TIMEOUT", "工具超时", true));

        assertDoesNotThrow(() -> RunEvents.validate(events));
        assertTrue(events.get(events.size() - 1).terminal());
    }

    @Test
    void 事件可携带工具调用摘要() {
        RunEvents e = new RunEvents("r-4");
        RunEvent.ToolFinished finished = e.toolFinished("c-1", "weather", true, 120L, null);

        assertTrue(finished.ok());
        assertEquals(120L, finished.elapsedMs());
    }
}
