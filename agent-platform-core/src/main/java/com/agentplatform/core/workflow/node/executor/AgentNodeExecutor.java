package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.agent.dto.AgentRunRequest;
import com.agentplatform.core.agent.dto.AgentRunResponse;
import com.agentplatform.core.agent.runtime.AgentRuntimeService;
import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排节点执行器（画布「Agent」节点）。
 * <p>
 * 在流程中调用平台内的另一个智能体：把上游上下文渲染成一条 user 消息，
 * 交给 {@link AgentRuntimeService} 跑完整链路（该 Agent 自己的模型 / 知识库 / 工具 / 插件 / 记忆都会生效），
 * 返回值写入 {@code outputVar} 供下游引用 —— 这就是「Agent 编排」的最小闭环。
 * </p>
 * 配置项：{@code agent_id}（目标智能体，必填）、{@code input}（输入模板，默认 {@code ${input}}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeExecutor implements NodeExecutor {

    private final AgentRuntimeService agentRuntimeService;

    @Override
    public NodeType type() {
        return NodeType.Agent;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String agentId = NodeConfigs.str(node, "agent_id", "agentId");
        if (NodeConfigs.blank(agentId)) {
            throw new IllegalArgumentException("Agent node " + node.id() + " requires config.agent_id");
        }
        String inputTemplate = NodeConfigs.strOr(node, "${input}", "input", "prompt", "message");
        String input = ctx.resolveString(inputTemplate);

        AgentRunRequest request = new AgentRunRequest(
                null,
                "agent",
                agentId,
                null,
                null,
                null,
                null,
                List.of(new AgentRunRequest.Message("user", input)),
                null,
                null,
                null,
                null,
                Boolean.FALSE,
                Map.of("tenant_id", "default", "user_id", "workflow"));

        AgentRunResponse response = agentRuntimeService.run(request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", response.output() == null ? null : response.output().content());
        out.put("run_id", response.runId());
        out.put("session_id", response.sessionId());
        log.debug("Agent node {} invoked agent={} runId={}", node.id(), agentId, response.runId());
        return out;
    }
}
