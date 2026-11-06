package com.agentplatform.framework.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandTest {

    @Test
    void 纯文本快路应产生一个Text块() {
        RunCommand cmd = RunCommand.ofText("agent-1", "你好");

        assertEquals(1, cmd.input().size());
        assertEquals("你好", cmd.text());
        assertTrue(cmd.refs().isEmpty());
    }

    @Test
    void agentId为空应拒绝() {
        assertThrows(IllegalArgumentException.class, () -> RunCommand.builder(" ").text("hi").build());
        assertThrows(IllegalArgumentException.class, () -> RunCommand.builder(null).text("hi").build());
    }

    @Test
    void 没有任何输入块应拒绝() {
        assertThrows(IllegalArgumentException.class, () -> RunCommand.builder("agent-1").build());
        // builder.text(null) 不应产生空块
        assertThrows(IllegalArgumentException.class, () -> RunCommand.builder("agent-1").text(null).build());
    }

    @Test
    void 租户默认为default() {
        assertEquals(Principal.DEFAULT_TENANT, RunCommand.ofText("agent-1", "hi").tenantId());
        assertEquals("t-9", RunCommand.builder("agent-1").text("hi").tenant("t-9").build().tenantId());
    }

    @Test
    void 工具默认关闭() {
        RunCommand cmd = RunCommand.ofText("agent-1", "hi");

        assertFalse(cmd.tools().enabled());
        assertFalse(cmd.tools().allows("any"));
    }

    @Test
    void 白名单为空表示放行全部已注册工具() {
        ToolOptions all = ToolOptions.all(5);
        assertTrue(all.allows("weather"));
        assertTrue(all.allows("unknown"));

        ToolOptions only = ToolOptions.only(List.of("weather"), 5);
        assertTrue(only.allows("weather"));
        assertFalse(only.allows("other"));
    }

    @Test
    void 元数据不可被外部修改() {
        RunCommand cmd = RunCommand.builder("agent-1").text("hi").meta("k", "v").build();

        assertEquals("v", cmd.meta("k"));
        assertThrows(UnsupportedOperationException.class, () -> cmd.metadata().put("x", "y"));
    }

    @Test
    void 附件引用只通过refs暴露() {
        RunCommand cmd = RunCommand.builder("agent-1")
                .text("看这张图")
                .part(new InputPart.Ref("image", "file-7"))
                .build();

        assertEquals(1, cmd.refs().size());
        assertEquals("file-7", cmd.refs().get(0).id());
        assertEquals("看这张图", cmd.text());
    }

    @Test
    void 多段文本按换行拼接() {
        RunCommand cmd = RunCommand.builder("agent-1").text("第一段").text("第二段").build();
        assertEquals("第一段\n第二段", cmd.text());
    }

    @Test
    void principal允许显式匿名() {
        Principal anon = Principal.anonymous("t-1");
        assertEquals("anonymous", anon.userId());
        assertEquals("t-1", anon.tenantId());
        assertFalse(anon.hasRole("admin"));

        RunCommand cmd = RunCommand.builder("agent-1").text("hi").principal(anon).build();
        assertEquals("t-1", cmd.principal().tenantId());
    }

    @Test
    void 用量累加且空值安全() {
        RunResult.Usage a = new RunResult.Usage(10, 20);
        RunResult.Usage b = new RunResult.Usage(1, 2, 5);

        RunResult.Usage sum = a.plus(b);
        assertEquals(11, sum.inputTokens());
        assertEquals(22, sum.outputTokens());
        assertEquals(5, sum.cachedTokens());
        assertEquals(33, sum.totalTokens());
        assertEquals(a, a.plus(null));
    }
}
