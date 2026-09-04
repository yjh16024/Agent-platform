package com.agentplatform.core.workflow.node;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 工作流节点定义（对应 §3.2 的 WorkflowNode JSON Schema）。
 * <p>
 * 统一字段：id、type、name、next（单一下游/多下游）、input_mapping（变量→参数映射）、
 * output_var（结果写入变量）、branches（Condition 专用）、config（按类型的专属配置）。
 * </p>
 *
 * @param id            节点唯一 ID（同工作流内不可重复）
 * @param type          节点类型
 * @param name          名称
 * @param next          下游节点 ID（字符串或数组）
 * @param inputMapping  上下文变量 → 节点参数映射（支持 ${var.path} 占位）
 * @param outputVar     结果写入上下文变量名
 * @param branches      条件分支（Condition 节点必填）
 * @param config        节点专属配置（LLM/KB/Tool/Skill/Plugin/Agent）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowNode(
        String id,
        NodeType type,
        String name,
        Object next,
        Map<String, Object> inputMapping,
        String outputVar,
        List<Branch> branches,
        Map<String, Object> config
) {
    /** 条件分支。 */
    public record Branch(String condition, String target) {
    }

    /**
     * 获取下游节点 ID 列表（兼容 next 为 String 或数组）。
     */
    public List<String> nextIds() {
        if (next == null) {
            return List.of();
        }
        if (next instanceof String s) {
            return List.of(s);
        }
        if (next instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 便捷构造（无 branches/config）。
     */
    public static WorkflowNode simple(String id, NodeType type, String next, String outputVar) {
        return new WorkflowNode(id, type, null, next, null, outputVar, null, null);
    }
}