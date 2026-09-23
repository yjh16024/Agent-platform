package com.agentplatform.core.agent.controller;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.dto.RunStreamEvent;
import com.agentplatform.core.agent.runtime.AgentRuntimeService;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 Agent 运行接口（POST /agent/run）。
 * <p>根据 {@code stream} 标志返回完整 JSON 响应或 SSE 事件流。</p>
 * <p>SSE 事件类型：{@code run.delta}（增量文本）、{@code run.completed}（可带
 * {@code toolCalls} —— 工具调用可视化）、{@code run.error}。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRuntimeService runtimeService;

    /**
     * 统一运行入口。
     */
    @PostMapping("/run")
    @RequiresPermission("agent:invoke")
    public ResponseEntity<?> run(@RequestBody AgentRunRequest req) {
        if (Boolean.TRUE.equals(req.stream())) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(streamEvents(req));
        }
        AgentRunResponse resp = runtimeService.run(req);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resp);
    }

    /**
     * 组流式 SSE 事件。
     */
    private Flux<ServerSentEvent<Map<String, Object>>> streamEvents(AgentRunRequest req) {
        return runtimeService.runStream(req)
                .map(ev -> ev.isCompleted() ? completedEvent(ev) : deltaEvent(ev))
                .onErrorResume(e -> Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                        .event("run.error")
                        .data(Map.of("message", e.getMessage() == null ? "运行失败" : e.getMessage()))
                        .build()));
    }

    /**
     * 增量帧。
     *
     * <p>形态刻意与改造前**完全一致**（顶层的 {@code role}/{@code content}，不包一层
     * {@code output}）—— 前端已按 {@code obj?.output?.content ?? obj?.content ?? obj?.delta}
     * 兼容取值，这里保持不变就不必动前端解析逻辑。</p>
     */
    private ServerSentEvent<Map<String, Object>> deltaEvent(RunStreamEvent ev) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", "assistant");
        data.put("content", ev.text() == null ? "" : ev.text());
        return ServerSentEvent.<Map<String, Object>>builder()
                .event("run.delta")
                .data(data)
                .build();
    }

    /**
     * 结束帧。
     *
     * <p>工具调用记录挂在这里（而不是做成实时事件）：有工具的链路是"先同步跑完工具往返、
     * 再分块推流"，工具执行期间这条 Flux 还没发出任何元素，做不出实时推送。
     * 无调用时 data 为空对象，与改造前的行为一致。</p>
     */
    private ServerSentEvent<Map<String, Object>> completedEvent(RunStreamEvent ev) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (ev.toolCalls() != null && !ev.toolCalls().isEmpty()) {
            data.put("toolCalls", ev.toolCalls());
        }
        return ServerSentEvent.<Map<String, Object>>builder()
                .event("run.completed")
                .data(data)
                .build();
    }
}
