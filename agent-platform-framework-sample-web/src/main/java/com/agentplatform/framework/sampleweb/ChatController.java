package com.agentplatform.framework.sampleweb;

import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.api.ToolOptions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 业务代码：注入 {@link AgentRuntime} 直接用。
 *
 * <p>这里刻意不处理「模型从哪来、工具怎么注册、历史怎么存」——
 * 那些都在 starter 的自动装配里。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentRuntime runtime;

    public ChatController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? "web-demo" : request.sessionId();

        RunResult result = runtime.run(RunCommand.builder("web-demo-agent")
                .text(request.message())
                .session(sessionId)
                .tenant("demo-tenant")
                .tools(ToolOptions.all(3))
                .build());

        return Map.of("reply", result.reply(), "runId", result.runId());
    }

    public record ChatRequest(String message, String sessionId) {
    }
}
