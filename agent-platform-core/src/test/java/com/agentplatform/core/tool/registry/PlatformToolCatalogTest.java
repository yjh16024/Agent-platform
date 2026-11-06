package com.agentplatform.core.tool.registry;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolFilter;
import com.agentplatform.framework.api.ToolInvocation;
import com.agentplatform.framework.api.ToolOptions;
import com.agentplatform.framework.api.ToolResult;
import com.agentplatform.framework.runtime.DefaultAgentRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具接缝（P2' 第二片）：框架运行时直接消费平台注册中心与平台过滤器。
 *
 * <p>这条测试不启 Spring 容器，但它的意义是「平台侧的注册中心与责任链
 * 已经能被框架运行时原样使用」——这是把平台接上框架运行时的前提。
 */
class PlatformToolCatalogTest {

    @Test
    void 目录应实时反映注册中心的变化() {
        ToolRegistry registry = new ToolRegistry();
        PlatformToolCatalog catalog = new PlatformToolCatalog(registry);

        registry.register(new EchoTool("alpha"));

        assertTrue(catalog.contains("alpha"));
        assertEquals("alpha", catalog.get("alpha").name());
        assertEquals(1, catalog.all().size());

        registry.unregister("alpha");
        assertFalse(catalog.contains("alpha"));
        assertTrue(catalog.all().isEmpty());
    }

    @Test
    void 框架运行时能用平台注册中心的工具与过滤器跑完一轮() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool("echo"));
        AtomicInteger filterHits = new AtomicInteger();

        // 平台的过滤器实现的就是框架的 ToolFilter（api 层已统一），可直接挂上
        ToolFilter countingFilter = new ToolFilter() {
            @Override
            public ToolResult doFilter(ToolInvocation invocation, com.agentplatform.framework.api.ToolChain chain) {
                filterHits.incrementAndGet();
                return chain.apply(invocation);
            }
        };

        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L, List.of(
                        new ModelAdapter.ToolCall("c-1", "echo", null)), ""),
                new ModelAdapter.ChatResponse("工具说：hi", 1, 1, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .catalog(new PlatformToolCatalog(registry))
                .filters(List.of(countingFilter))
                .build();

        RunResult result = runtime.run(RunCommand.builder("agent-1")
                .text("hi")
                .tools(ToolOptions.all(3))
                .build());

        assertEquals("工具说：hi", result.reply());
        assertEquals(1, filterHits.get(), "平台过滤器必须被责任链调用");
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.toolCalls().get(0).ok());
    }

    private static class EchoTool implements Tool {

        private final String name;

        private EchoTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "原样返回入参文本";
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            return ToolResult.ok("hi");
        }
    }

    private static class ScriptedAdapter implements ModelAdapter {

        private final Deque<ChatResponse> script = new ArrayDeque<>();

        private ScriptedAdapter(ChatResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public String provider() {
            return "scripted";
        }

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.TEXT, ModelCapability.TOOL);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return script.size() > 1 ? script.poll() : script.peek();
        }

        @Override
        public Flux<ChatDelta> stream(ChatRequest request) {
            return Flux.empty();
        }
    }
}
