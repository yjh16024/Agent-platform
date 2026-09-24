package com.agentplatform.core.tool.executor;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具责任链四个环节的单元测试（2026-09-24）。
 *
 * <p>重点不在"正常路径能过"，而在两件事：<b>校验必须保守</b>
 * （误拦一个正确调用比漏掉一个错误参数更糟），以及<b>超时的语义是"放弃等待"而非"终止执行"</b>
 * （拒绝消息必须把这一点传达给模型，否则它会立刻重试、造成重复副作用）。</p>
 */
class ToolFiltersTest {

    private static final ToolContext CTX = ToolContext.of("t1", "a1", "run1", "sess1");

    // ------------------------------------------------------------------ 工具与链

    private static Tool tool(String name, String schemaJson) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public JsonNode inputSchema() {
                return schemaJson == null ? null : JsonUtils.toJsonNode(schemaJson);
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                return ToolResult.ok("executed");
            }
        };
    }

    /**
     * 跑一遍"单环节 + 链尾直接执行工具"。
     *
     * <p>链用**空迭代器**：真实场景里 {@code ToolExecutor} 把全部过滤器一次性装进同一条链，
     * 每个过滤器收到的链上它自己已被消费掉，所以不会重入；测试里必须复现这一点，
     * 否则 {@code chain.apply} 会再次调到自己（无限递归）。</p>
     */
    private static ToolResult run(ToolFilter filter, String toolName,
                                  String schemaJson, String argsJson, ToolContext ctx) {
        JsonNode args = argsJson == null
                ? JsonUtils.mapper().createObjectNode()
                : JsonUtils.toJsonNode(argsJson);
        ToolChain chain = new ToolChain(List.<ToolFilter>of().iterator(), tool(toolName, schemaJson));
        return filter.doFilter(new ToolInvocation(toolName, args, ctx), chain);
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    @DisplayName("★ 鉴权：缺少租户上下文一律拒绝（否则审计与配额会静默记到空租户）")
    void authRejectsMissingTenant() {
        AuthToolFilter filter = new AuthToolFilter();

        ToolResult r = run(filter, "calc", null, "{}", ToolContext.of(null, "a1", "run1"));

        assertFalse(r.success());
        assertTrue(r.error().contains("租户上下文"), "应说明原因：" + r.error());
    }

    @Test
    @DisplayName("鉴权：平台禁用清单命中即拒绝（生产环境关掉危险工具的开关）")
    void authRejectsDisabledTools() {
        AuthToolFilter filter = new AuthToolFilter();
        ReflectionTestUtils.setField(filter, "disabled", List.of("fs_write_file", "shell_run"));

        ToolResult denied = run(filter, "fs_write_file", null, "{}", CTX);
        assertFalse(denied.success());
        assertTrue(denied.error().contains("已被平台策略禁用"));

        // 不在清单里的照常执行
        assertTrue(run(filter, "calc", null, "{}", CTX).success());
    }

    // ------------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("校验：缺少必填参数被拒，且提示可接受哪些参数（让模型一次改对）")
    void validateRejectsMissingRequired() {
        ValidateToolFilter filter = new ValidateToolFilter();
        String schema = """
                {"type":"object","properties":{"path":{"type":"string"},"limit":{"type":"integer"}},
                 "required":["path"]}""";

        ToolResult r = run(filter, "fs_read_file", schema, "{\"limit\":10}", CTX);

        assertFalse(r.success());
        assertTrue(r.error().contains("path"), "应指明缺了哪个参数：" + r.error());
        assertTrue(r.error().contains("limit"), "应列出可接受的参数：" + r.error());
    }

    @Test
    @DisplayName("校验：必填项传了空字符串也算缺失（等于没填）")
    void validateTreatsBlankRequiredAsMissing() {
        ValidateToolFilter filter = new ValidateToolFilter();
        String schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}";

        assertFalse(run(filter, "fs_read_file", schema, "{\"path\":\"   \"}", CTX).success());
    }

    @Test
    @DisplayName("校验：类型明显不符被拒（对象塞进 string）")
    void validateRejectsWrongType() {
        ValidateToolFilter filter = new ValidateToolFilter();
        String schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

        ToolResult r = run(filter, "fs_read_file", schema, "{\"path\":{\"a\":1}}", CTX);

        assertFalse(r.success());
        assertTrue(r.error().contains("path"));
    }

    @Test
    @DisplayName("★ 校验：保守 —— integer 收到 5.0 要放行（模型这么写很常见，为它拦下没有意义）")
    void validateIsLenientAboutIntegerVsNumber() {
        ValidateToolFilter filter = new ValidateToolFilter();
        String schema = "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}";

        assertTrue(run(filter, "fs_read_file", schema, "{\"limit\":5.0}", CTX).success());
        assertTrue(run(filter, "fs_read_file", schema, "{\"limit\":5}", CTX).success());
    }

    @Test
    @DisplayName("★ 校验：保守 —— 未知/复杂声明（anyOf 之类）不猜，一律放行")
    void validateDoesNotGuessUnknownTypes() {
        ValidateToolFilter filter = new ValidateToolFilter();
        String schema = "{\"type\":\"object\",\"properties\":{\"v\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}}";

        assertTrue(run(filter, "t", schema, "{\"v\":{\"nested\":true}}", CTX).success());
    }

    @Test
    @DisplayName("校验：没有 schema（工具未声明入参）不拦任何东西")
    void validatePassesWhenNoSchema() {
        ValidateToolFilter filter = new ValidateToolFilter();

        assertTrue(run(filter, "calc", null, "{\"anything\":1}", CTX).success());
    }

    // ------------------------------------------------------------------ 超时

    @Test
    @DisplayName("超时：正常执行的工具不受影响，结果原样返回")
    void timeoutPassesThroughFastTool() {
        TimeoutToolFilter filter = new TimeoutToolFilter();
        ReflectionTestUtils.setField(filter, "defaultSeconds", 30L);

        ToolResult r = run(filter, "calc", null, "{}", CTX);

        assertTrue(r.success());
        assertEquals("executed", r.output().asText());
    }

    @Test
    @DisplayName("★ 超时：慢工具被放弃等待，且明确告知「不要立即重试」")
    void timeoutAbandonsSlowTool() {
        TimeoutToolFilter filter = new TimeoutToolFilter();
        ReflectionTestUtils.setField(filter, "defaultSeconds", 1L);

        Tool slow = new Tool() {
            @Override
            public String name() {
                return "slow_tool";
            }

            @Override
            public String description() {
                return "慢工具";
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok("finally done");
            }
        };
        ToolChain chain = new ToolChain(List.<ToolFilter>of().iterator(), slow);

        long t0 = System.currentTimeMillis();
        ToolResult r = filter.doFilter(
                new ToolInvocation("slow_tool", JsonUtils.mapper().createObjectNode(), CTX), chain);
        long cost = System.currentTimeMillis() - t0;

        assertFalse(r.success());
        assertTrue(cost < 5_000, "应该在超时后立刻返回，实际耗时 " + cost + "ms");
        assertTrue(r.error().contains("超时"));
        // 这条最关键：不写清楚的话模型会立刻重试，而工具可能还在跑
        assertTrue(r.error().contains("不要立即重复调用"), "必须传达'放弃等待≠终止执行'：" + r.error());
    }

    @Test
    @DisplayName("超时：<=0 表示关闭该环（直接执行，不做包装）")
    void timeoutDisabledWhenZero() {
        TimeoutToolFilter filter = new TimeoutToolFilter();
        ReflectionTestUtils.setField(filter, "defaultSeconds", 0L);

        assertTrue(run(filter, "calc", null, "{}", CTX).success());
    }

    @Test
    @DisplayName("超时：按工具覆盖的解析（非法项跳过，不影响其它项）")
    void timeoutParsesOverrides() {
        Map<String, Long> parsed = TimeoutToolFilter.parseOverrides(
                "fs_write_file=900, mcp_x=120 , bad-item, nope=abc, =5");

        assertEquals(900L, parsed.get("fs_write_file"));
        assertEquals(120L, parsed.get("mcp_x"));
        assertFalse(parsed.containsKey("bad-item"));
        assertFalse(parsed.containsKey("nope"));
        assertEquals(2, parsed.size(), "非法项应被跳过：" + parsed);
    }

    // ------------------------------------------------------------------ 限流

    @Test
    @DisplayName("限流：QuotaService 缺失（最小环境/单测）时直接放行，不阻断工具")
    void rateLimitPassesWhenNoQuotaService() {
        RateLimitToolFilter filter = new RateLimitToolFilter();

        ToolResult r = run(filter, "calc", null, "{}", CTX);

        assertNotNull(r);
        assertTrue(r.success(), "没有配额服务时不该拦任何调用");
    }
}
