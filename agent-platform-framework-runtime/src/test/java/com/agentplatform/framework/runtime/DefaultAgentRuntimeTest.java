package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunEvent;
import com.agentplatform.framework.api.RunEvents;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolOptions;
import com.agentplatform.framework.api.ToolResult;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRuntimeTest {

    @Test
    void 无工具时应一次调用直接返回() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("你好，我是白雾", 3, 5, 0.0, 12L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().adapter(adapter).build();
        RunResult result = runtime.run(RunCommand.ofText("agent-1", "hi"));

        assertEquals("你好，我是白雾", result.reply());
        assertEquals(3, result.usage().inputTokens());
        assertEquals(5, result.usage().outputTokens());
        assertTrue(result.toolCalls().isEmpty());
        assertEquals(1, adapter.calls());
        assertNotNull(result.runId());
    }

    @Test
    void 应执行工具并把结果回灌给模型() {
        ToolContextRecorder recorder = new ToolContextRecorder();
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 2, 2, 0.0, 1L, List.of(
                        new ModelAdapter.ToolCall("call-1", "clock", null)), ""),
                new ModelAdapter.ChatResponse("现在是 12:00", 4, 4, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .tool(recorder)
                .build();

        RunResult result = runtime.run(RunCommand.builder("agent-1")
                .text("现在几点")
                .tenant("t-1")
                .session("s-1")
                .tools(ToolOptions.all(3))
                .build());

        assertEquals("现在是 12:00", result.reply());
        assertEquals(1, result.toolCalls().size());
        assertEquals("call-1", result.toolCalls().get(0).callId());
        assertTrue(result.toolCalls().get(0).ok());
        assertEquals(1, recorder.invocations.get());

        // B1/B3 的上下文确实被传进工具
        assertEquals("t-1", recorder.lastTenant);
        assertEquals("agent-1", recorder.lastAgent);

        // 第二轮请求里必须带上 assistant(tool_calls) 与 tool 结果两条消息
        ModelAdapter.ChatRequest second = adapter.lastRequest();
        assertTrue(second.history().size() >= 2);
        assertTrue(second.history().stream().anyMatch(ModelAdapter.ChatMessage::hasToolCalls));
        assertTrue(second.history().stream().anyMatch(ModelAdapter.ChatMessage::isToolResult));
    }

    @Test
    void 工具抛错也要回灌而不是中断对话() {
        Tool broken = new Tool() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                return "总是失败";
            }

            @Override
            public ToolResult execute(JsonNode args, ToolContext ctx) {
                throw new IllegalStateException("boom");
            }
        };

        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L, List.of(
                        new ModelAdapter.ToolCall("c-1", "broken", null)), ""),
                new ModelAdapter.ChatResponse("工具挂了，我换个办法", 2, 2, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder().adapter(adapter).tool(broken).build()
                .run(RunCommand.builder("a").text("试试").tools(ToolOptions.all(2)).build());

        assertEquals("工具挂了，我换个办法", result.reply());
        assertFalse(result.toolCalls().get(0).ok());
        assertEquals("TOOL_ERROR", result.toolCalls().get(0).errorCode());
    }

    @Test
    void 未注册的工具名不会让整轮崩掉() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L, List.of(
                        new ModelAdapter.ToolCall("c-1", "ghost", null)), ""),
                new ModelAdapter.ChatResponse("好的", 1, 1, 0.0, 1L));

        // 注册了工具（于是工具列表非空），但模型点名了一个不存在的工具
        RunResult result = DefaultAgentRuntime.builder().adapter(adapter).tool(new ToolContextRecorder()).build()
                .run(RunCommand.builder("a").text("x").tools(ToolOptions.all(2)).build());

        assertEquals("好的", result.reply());
        assertFalse(result.toolCalls().get(0).ok());
    }

    @Test
    void 达到最大轮次时给出明确说明() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L,
                        List.of(new ModelAdapter.ToolCall("c-1", "clock", null)), ""));

        RunResult result = DefaultAgentRuntime.builder().adapter(adapter).tool(new ToolContextRecorder()).build()
                .run(RunCommand.builder("a").text("x").tools(ToolOptions.all(2)).build());

        assertTrue(result.reply().contains("最大轮次"));
        assertEquals(2, result.toolCalls().size());
    }

    @Test
    void 会话会被追加且下一轮能看到历史() {
        InMemorySessionStore store = new InMemorySessionStore();
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("第一答", 1, 1, 0.0, 1L),
                new ModelAdapter.ChatResponse("第二答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().adapter(adapter).sessions(store).build();
        runtime.run(RunCommand.builder("a").text("第一问").session("s-1").build());
        runtime.run(RunCommand.builder("a").text("第二问").session("s-1").build());

        assertEquals(4, store.size("s-1"));
        // 第二个请求应当带上第一轮的两条消息
        assertEquals(2, adapter.lastRequest().history().size());
    }

    @Test
    void 事件流必须满足契约且终止结果与非流式一致() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("流式答复", 2, 3, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder().adapter(adapter).build();
        List<RunEvent> events = runtime.runStream(RunCommand.builder("a").text("hi").build())
                .collectList().block();

        assertNotNull(events);
        RunEvents.validate(events);

        RunEvent last = events.get(events.size() - 1);
        assertTrue(last instanceof RunEvent.Completed);
        RunResult result = ((RunEvent.Completed) last).result();
        assertEquals("流式答复", result.reply());

        // 载荷必须与非流式等价（runId 每次不同，故只比对语义字段）
        RunResult sync = runtime.run(RunCommand.builder("a").text("hi").build());
        assertEquals(sync.reply(), result.reply());
        assertEquals(sync.usage(), result.usage());
        assertEquals(sync.model(), result.model());
        assertEquals(sync.toolCalls(), result.toolCalls());
    }

    @Test
    void 事件流里应出现工具事件() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L, List.of(
                        new ModelAdapter.ToolCall("c-9", "clock", null)), ""),
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        List<RunEvent> events = DefaultAgentRuntime.builder().adapter(adapter).tool(new ToolContextRecorder()).build()
                .runStream(RunCommand.builder("a").text("x").tools(ToolOptions.all(2)).build())
                .collectList().block();

        assertNotNull(events);
        RunEvents.validate(events);
        assertTrue(events.stream().anyMatch(e -> e instanceof RunEvent.ToolStarted));
        assertTrue(events.stream().anyMatch(e -> e instanceof RunEvent.ToolFinished));
    }

    @Test
    void 配额应在每次运行开始时占一次() {
        AtomicInteger reserved = new AtomicInteger();
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .quota((tenantId, metric, amount) -> reserved.incrementAndGet())
                .build();

        runtime.run(RunCommand.builder("a").text("hi").tenant("t-1").build());

        assertEquals(1, reserved.get());
    }

    @Test
    void 鉴权拒绝时非流式直接抛错() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("不该被调用", 1, 1, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .authorization((principal, action) -> {
                    throw new SecurityException("缺少权限：" + action);
                })
                .build();

        SecurityException ex = org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> runtime.run(RunCommand.builder("a").text("hi").build()));
        assertTrue(ex.getMessage().contains("agent:invoke"));
        assertEquals(0, adapter.calls(), "鉴权失败不应发起模型调用");
    }

    @Test
    void 流式下鉴权拒绝应变成Failed事件而不是抛错() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("不该被调用", 1, 1, 0.0, 1L));

        List<RunEvent> events = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .authorization((principal, action) -> {
                    throw new SecurityException("denied");
                })
                .build()
                .runStream(RunCommand.builder("a").text("hi").build())
                .collectList().block();

        assertNotNull(events);
        RunEvents.validate(events);
        RunEvent last = events.get(events.size() - 1);
        assertTrue(last instanceof RunEvent.Failed);
    }

    @Test
    void 每请求凭证应透传给适配器() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime.builder().adapter(adapter).build()
                .run(RunCommand.builder("a").text("hi")
                        .credentials(new com.agentplatform.framework.api.ModelCredentials(
                                "deepseek", "https://api.example.com", "sk-test"))
                        .build());

        assertEquals("https://api.example.com", adapter.lastRequest().baseUrl());
        assertEquals("sk-test", adapter.lastRequest().apiKey());
    }

    @Test
    void 文本附件应拼进用户消息图片应进入extra() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime.builder()
                .adapter(adapter)
                .attachments((kind, id) -> "file".equals(kind)
                        ? com.agentplatform.framework.api.AttachmentResolver.ResolvedAttachment.text(kind, "[文件内容]")
                        : com.agentplatform.framework.api.AttachmentResolver.ResolvedAttachment.image(kind, "data:image/png;base64,AAA"))
                .build()
                .run(RunCommand.builder("a")
                        .text("看下这两个附件")
                        .part(new com.agentplatform.framework.api.InputPart.Ref("file", "f-1"))
                        .part(new com.agentplatform.framework.api.InputPart.Ref("image", "i-1"))
                        .build());

        assertTrue(adapter.lastRequest().userMessage().contains("[文件内容]"));
        Object images = adapter.lastRequest().extra().get("images");
        assertTrue(images instanceof java.util.List<?> list && list.size() == 1);
    }

    @Test
    void 附件解析失败必须可见而不是静默丢掉() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("这个文件我读不了", 1, 1, 0.0, 1L));

        DefaultAgentRuntime.builder()
                .adapter(adapter)
                .attachments((kind, id) -> com.agentplatform.framework.api.AttachmentResolver.ResolvedAttachment
                        .failed(kind, "文件不存在"))
                .build()
                .run(RunCommand.builder("a")
                        .text("看看这个附件")
                        .part(new com.agentplatform.framework.api.InputPart.Ref("file", "f-x"))
                        .build());

        String sent = adapter.lastRequest().userMessage();
        assertTrue(sent.contains("[附件 file:f-x 未能读取：文件不存在]"), "实际=" + sent);
    }

    @Test
    void 没有附件解析器时引用被忽略而不是报错() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime.builder().adapter(adapter).build()
                .run(RunCommand.builder("a")
                        .text("hi")
                        .part(new com.agentplatform.framework.api.InputPart.Ref("image", "i-1"))
                        .build());

        assertEquals("hi", adapter.lastRequest().userMessage());
        assertTrue(adapter.lastRequest().extra().isEmpty());
    }

    @Test
    void 命令级系统提示词应覆盖运行时默认值() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        DefaultAgentRuntime runtime = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .systemPrompt("运行时默认人格")
                .build();

        runtime.run(RunCommand.builder("a").text("hi").systemPrompt("平台组装的人格").build());
        assertEquals("平台组装的人格", adapter.lastRequest().systemPrompt());

        runtime.run(RunCommand.ofText("a", "hi"));
        assertEquals("运行时默认人格", adapter.lastRequest().systemPrompt());
    }

    @Test
    void 拦截器可改写输入且模型看到改写后的内容() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .interceptors(List.of(new com.agentplatform.framework.api.RunInterceptor() {
                    @Override
                    public String beforeModel(com.agentplatform.framework.api.InterceptorContext ctx) {
                        return ctx.input() + "（已脱敏）";
                    }
                }))
                .build()
                .run(RunCommand.ofText("a", "我的手机号是 13800000000"));

        assertEquals("答", result.reply());
        assertEquals("我的手机号是 13800000000（已脱敏）", adapter.lastRequest().userMessage());
    }

    @Test
    void 短路时不应发起模型调用() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("不该出现", 1, 1, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .interceptors(List.of(new com.agentplatform.framework.api.RunInterceptor() {
                    @Override
                    public String shortCircuit(com.agentplatform.framework.api.InterceptorContext ctx) {
                        return "固定回复";
                    }
                }))
                .build()
                .run(RunCommand.ofText("a", "hi"));

        assertEquals("固定回复", result.reply());
        assertEquals(0, adapter.calls());
    }

    @Test
    void 拦截器可替换最终输出() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("含敏感词的原文", 1, 1, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .interceptors(List.of(new com.agentplatform.framework.api.RunInterceptor() {
                    @Override
                    public String afterModel(com.agentplatform.framework.api.InterceptorContext ctx, String reply) {
                        return reply.replace("敏感词", "***");
                    }
                }))
                .build()
                .run(RunCommand.ofText("a", "hi"));

        assertEquals("含***的原文", result.reply());
    }

    @Test
    void 失败时使用拦截器兜底话术() {
        ModelAdapter broken = new ModelAdapter() {
            @Override
            public String provider() {
                return "broken";
            }

            @Override
            public Set<ModelCapability> capabilities() {
                return Set.of(ModelCapability.TEXT);
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("上游超时");
            }

            @Override
            public Flux<ChatDelta> stream(ChatRequest request) {
                return Flux.empty();
            }
        };

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(broken)
                .interceptors(List.of(new com.agentplatform.framework.api.RunInterceptor() {
                    @Override
                    public String onError(com.agentplatform.framework.api.InterceptorContext ctx,
                                          RuntimeException error) {
                        return "稍后再试（" + error.getMessage() + "）";
                    }
                }))
                .build()
                .run(RunCommand.ofText("a", "hi"));

        assertTrue(result.reply().startsWith("稍后再试"));
    }

    @Test
    void 工具调用摘要应带出结果且超长必须截断() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("", 1, 1, 0.0, 1L,
                        List.of(new ModelAdapter.ToolCall("c-1", "echo", null)), ""),
                new ModelAdapter.ChatResponse("好的", 1, 1, 0.0, 1L));

        String huge = "x".repeat(5000);
        Tool echo = new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显";
            }

            @Override
            public ToolResult execute(tools.jackson.databind.JsonNode args, ToolContext ctx) {
                return ToolResult.ok(huge);
            }
        };

        RunResult result = DefaultAgentRuntime.builder().adapter(adapter).tool(echo).build()
                .run(RunCommand.builder("a").text("hi").tools(ToolOptions.all(3)).build());

        RunResult.ToolCall call = result.toolCalls().get(0);
        assertTrue(call.ok());
        assertNotNull(call.output());
        assertTrue(call.output().contains("已截断"), "超长结果必须被截断");
        assertTrue(call.output().length() < huge.length());
    }

    @Test
    void 拦截器产物应进入结果() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .interceptors(List.of(new com.agentplatform.framework.api.RunInterceptor() {
                    @Override
                    public java.util.Map<String, Object> artifacts(
                            com.agentplatform.framework.api.InterceptorContext ctx, String reply) {
                        return java.util.Map.of("audio_url", "http://a.mp3");
                    }
                }))
                .build()
                .run(RunCommand.ofText("a", "hi"));

        assertTrue(result.hasArtifacts());
        assertEquals("http://a.mp3", result.artifacts().get("audio_url"));
    }

    @Test
    void 观测出口应收到开始与成功回调() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 2, 3, 0.0, 1L));

        DefaultAgentRuntime.builder()
                .adapter(adapter)
                .observer(new com.agentplatform.framework.api.RunObserver() {
                    @Override
                    public void runStarted(String runId, String traceId, String agentId, String tenantId) {
                        calls.add("start:" + agentId);
                    }

                    @Override
                    public void runCompleted(String runId, long elapsedMs, RunResult.Usage usage) {
                        calls.add("done:" + usage.totalTokens());
                    }
                })
                .build()
                .run(RunCommand.builder("agent-1").text("hi").tenant("t-1").build());

        assertEquals(List.of("start:agent-1", "done:5"), calls);
    }

    @Test
    void 观测实现抛异常不应影响运行结果() {
        ScriptedAdapter adapter = new ScriptedAdapter(
                new ModelAdapter.ChatResponse("答", 1, 1, 0.0, 1L));

        RunResult result = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .observer(new com.agentplatform.framework.api.RunObserver() {
                    @Override
                    public void runStarted(String runId, String traceId, String agentId, String tenantId) {
                        throw new IllegalStateException("观测炸了");
                    }
                })
                .build()
                .run(RunCommand.ofText("a", "hi"));

        assertEquals("答", result.reply());
    }

    @Test
    void 失败时应回调failed而不是completed() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        ModelAdapter broken = new ModelAdapter() {
            @Override
            public String provider() {
                return "broken";
            }

            @Override
            public Set<ModelCapability> capabilities() {
                return Set.of(ModelCapability.TEXT);
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("上游挂了");
            }

            @Override
            public Flux<ChatDelta> stream(ChatRequest request) {
                return Flux.empty();
            }
        };

        try {
            DefaultAgentRuntime.builder()
                    .adapter(broken)
                    .observer(new com.agentplatform.framework.api.RunObserver() {
                        @Override
                        public void runCompleted(String runId, long elapsedMs, RunResult.Usage usage) {
                            calls.add("done");
                        }

                        @Override
                        public void runFailed(String runId, RuntimeException error) {
                            calls.add("failed:" + error.getMessage());
                        }
                    })
                    .build()
                    .run(RunCommand.ofText("a", "hi"));
        } catch (RuntimeException ignored) {
            // 预期抛出
        }

        assertEquals(List.of("failed:上游挂了"), calls);
    }

    /** 记录被调用次数与上下文，用于断言 B1/B3 的透传。 */
    private static class ToolContextRecorder implements Tool {

        private final AtomicInteger invocations = new AtomicInteger();
        private String lastTenant;
        private String lastAgent;

        @Override
        public String name() {
            return "clock";
        }

        @Override
        public String description() {
            return "返回固定时间";
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            invocations.incrementAndGet();
            lastTenant = ctx.tenantId();
            lastAgent = ctx.agentId();
            return ToolResult.ok("12:00");
        }
    }

    /** 按脚本依次返回响应的模型适配器；脚本用尽后重复最后一条。 */
    private static class ScriptedAdapter implements ModelAdapter {

        private final Deque<ChatResponse> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private ChatRequest lastRequest;

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
            calls.incrementAndGet();
            lastRequest = request;
            if (script.size() > 1) {
                return script.poll();
            }
            return script.peek();
        }

        @Override
        public Flux<ChatDelta> stream(ChatRequest request) {
            return Flux.empty();
        }

        int calls() {
            return calls.get();
        }

        ChatRequest lastRequest() {
            return lastRequest;
        }
    }
}
