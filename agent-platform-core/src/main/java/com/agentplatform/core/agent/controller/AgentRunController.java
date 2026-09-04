package com.agentplatform.core.agent.controller;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.runtime.AgentRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 统一 Agent 运行接口（POST /agent/run）。
 * <p>根据 {@code stream} 标志返回完整 JSON 响应或 SSE 事件流。</p>
 * <p>SSE 事件类型：run.delta（增量文本）、run.completed、run.error。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    /**
     * 统一运行入口。
     */
    @PostMapping("/run")
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
                .map(out -> {
                    Map<String, Object> event = objectMapper.convertValue(out,
                            new com.fasterxml.jackson.core.type.TypeReference<>() {
                            });
                    return ServerSentEvent.<Map<String, Object>>builder()
                            .event("run.delta")
                            .data(event)
                            .build();
                })
                .concatWith(Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                        .event("run.completed")
                        .data(Map.of())
                        .build()))
                .onErrorResume(e -> Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                        .event("run.error")
                        .data(Map.of("message", e.getMessage()))
                        .build()));
    }
}