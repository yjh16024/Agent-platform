package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.skill.executor.SkillExecutionResult;
import com.agentplatform.core.skill.executor.SkillExecutionService;
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
 * 代码 / Skill 节点执行器（画布「代码」节点）。
 * <p>
 * 复用平台 Skill 执行体系（{@link SkillExecutionService}），三种执行器按 {@code exec_type} 选择：
 * prompt（渲染模板）/ script（白名单子进程沙箱）/ http（POST 端点）。
 * </p>
 * 配置项：
 * <ul>
 *   <li>{@code skill_id} —— Skill ID（必填）</li>
 *   <li>{@code exec_type} —— 执行类型，默认从 Skill 定义推断（缺省 {@code prompt}）</li>
 *   <li>{@code command} —— 命令 / 入口（script/http 用）</li>
 *   <li>{@code args} —— 参数（Map 或 JSON 字符串，支持 {@code ${var}}）</li>
 * </ul>
 * 输出：{@code {ok, output, executor, duration_ms}}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillNodeExecutor implements NodeExecutor {

    private final SkillExecutionService skillExecutionService;

    @Override
    public NodeType type() {
        return NodeType.Skill;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        String skillId = NodeConfigs.str(node, "skill_id", "skillId");
        if (NodeConfigs.blank(skillId)) {
            throw new IllegalArgumentException("Skill node " + node.id() + " requires config.skill_id");
        }
        String execType = NodeConfigs.strOr(node, "prompt", "exec_type", "execType", "type");
        String command = NodeConfigs.str(node, "command", "entry");
        if (command != null) {
            command = ctx.resolveString(command);
        }

        // 参数：先解析字符串值里的 ${var}
        Map<String, Object> args = new LinkedHashMap<>();
        NodeConfigs.map(node, "args", "arguments").forEach((k, v) -> {
            if (v instanceof String s) {
                args.put(k, ctx.resolveString(s));
            } else {
                args.put(k, v);
            }
        });

        SkillExecutionResult result = skillExecutionService.run("default", skillId, execType, command, args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.success());
        if (result.success()) {
            out.put("output", result.output());
        } else {
            out.put("error", result.error());
        }
        out.put("executor", result.executor());
        out.put("duration_ms", result.durationMs());
        log.debug("Skill node {} executed skill={} type={} success={}", node.id(), skillId, execType, result.success());
        return out;
    }

    /** 供文档/画布提示用的可用执行类型。 */
    public static List<String> execTypes() {
        return List.of("prompt", "script", "http");
    }
}
