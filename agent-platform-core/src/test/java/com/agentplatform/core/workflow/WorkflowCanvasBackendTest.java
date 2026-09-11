package com.agentplatform.core.workflow;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.dag.DagEngine;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import com.agentplatform.model.entity.WorkflowDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流画布后端能力回归（H2 内置库 + 真实 Spring 上下文）。
 * <p>
 * 覆盖画布依赖的四项后端能力，防止后续重构退化：
 * <ol>
 *   <li><b>执行器齐备</b>：画布可拖出的节点类型（LLM / 知识库 / 代码 / HTTP / 插件 / Agent / 条件 / 转换）
 *       都有对应执行器，不会再出现「画得出、跑不通」；</li>
 *   <li><b>保存期拦截</b>：尚未实现执行器的类型（Loop）在保存时即被拒；</li>
 *   <li><b>next 链执行</b>：画布产出的顺序链能被引擎正确串联；</li>
 *   <li><b>调试轨迹与发布回滚</b>：debug 返回逐节点轨迹；publish 生成快照并递增版本，rollback 恢复快照。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:wfcanvas;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class WorkflowCanvasBackendTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowSchemaValidator validator;

    @Autowired
    private DagEngine dagEngine;

    /** 画布可用的节点类型必须都有执行器（校验器应放行）。 */
    @Test
    @DisplayName("画布节点类型均有执行器")
    void canvasNodeTypesAllExecutable() {
        WorkflowDefinition def = new WorkflowDefinition("canvas", List.of(
                WorkflowNode.simple("s", NodeType.Start, "e", null),
                WorkflowNode.simple("e", NodeType.End, null, null)), null, null);
        assertDoesNotThrow(() -> validator.validate(def), "Start/End 应可用");

        // 逐个类型断言"校验器不会因缺执行器而拒绝"
        List<NodeType> canvasTypes = List.of(
                NodeType.LLM, NodeType.KnowledgeBase, NodeType.Skill,
                NodeType.Http, NodeType.Plugin, NodeType.Agent, NodeType.Condition, NodeType.Transform);
        for (NodeType type : canvasTypes) {
            List<WorkflowNode> nodes = type == NodeType.Condition
                    ? List.of(
                        WorkflowNode.simple("s", NodeType.Start, "c", null),
                        new WorkflowNode("c", NodeType.Condition, "cond", "e", null, "decision",
                                List.of(new WorkflowNode.Branch("true", "e")), Map.of()),
                        WorkflowNode.simple("e", NodeType.End, null, null))
                    : List.of(
                        WorkflowNode.simple("s", NodeType.Start, "n", null),
                        WorkflowNode.simple("n", type, "e", "out"),
                        WorkflowNode.simple("e", NodeType.End, null, null));
            assertDoesNotThrow(() -> validator.validate(new WorkflowDefinition("t", nodes, null, null)),
                    "类型 " + type + " 应被视为已有执行器");
        }
    }

    /** 未实现执行器的类型（Loop）在保存期被拒。 */
    @Test
    @DisplayName("无执行器的节点类型保存即被拒")
    void unsupportedTypeRejectedAtSave() {
        WorkflowDefinition def = new WorkflowDefinition("loop", List.of(
                WorkflowNode.simple("a", NodeType.Loop, null, null)), null, null);
        BizException ex = assertThrows(BizException.class, () -> validator.validate(def));
        assertTrue(ex.getMessage().contains("no executor"), "错误信息应说明缺少执行器，实际=" + ex.getMessage());
    }

    /** 画布保存 → 调试执行 → 发布 → 回滚 全链路（使用 local Mock 模型，无需外部 Key）。 */
    @Test
    @DisplayName("保存 / 调试执行 / 发布 / 回滚 全链路")
    void saveDebugPublishRollback() {
        WorkflowDefinition llmFlow = new WorkflowDefinition("canvas-e2e", List.of(
                WorkflowNode.simple("s", NodeType.Start, "l", null),
                new WorkflowNode("l", NodeType.LLM, "LLM 生成", "e", Map.of(), "llm_output",
                        null, Map.of("provider", "local", "model", "mock", "prompt", "echo ${input}")),
                WorkflowNode.simple("e", NodeType.End, null, null)), "s", null);

        WorkflowDef saved = workflowService.create("default", "canvas-e2e", null, llmFlow);
        String wfId = saved.getWorkflowId();
        assertNotNull(wfId);

        // 调试执行：应有 3 个节点的轨迹，且 LLM 节点输出被写入变量
        Map<String, Object> debug = workflowService.debug("default", wfId, Map.of("input", "hi"));
        @SuppressWarnings("unchecked")
        List<DagEngine.NodeStep> steps = (List<DagEngine.NodeStep>) debug.get("steps");
        assertEquals(3, steps.size(), "应返回 3 个节点轨迹");
        assertTrue(steps.stream().allMatch(s -> "success".equals(s.status())), "全部节点应成功");
        assertNotNull(debug.get("variables"), "应返回变量快照");

        // 发布：版本递增，状态置 published
        WorkflowDef published = workflowService.publish("default", wfId);
        assertEquals("v1.0.0", published.getPublishedVersion());
        assertEquals("published", published.getStatus());
        assertEquals("v1.0.1", workflowService.publish("default", wfId).getPublishedVersion());

        // 改草稿（换成 HTTP 节点）后回滚：definition 应恢复为已发布快照（LLM 节点）
        WorkflowDefinition httpFlow = new WorkflowDefinition("canvas-e2e", List.of(
                WorkflowNode.simple("s", NodeType.Start, "h", null),
                new WorkflowNode("h", NodeType.Http, "HTTP", "e", Map.of(), "http_output",
                        null, Map.of("url", "https://example.com", "method", "GET")),
                WorkflowNode.simple("e", NodeType.End, null, null)), "s", null);
        workflowService.update("default", wfId, null, null, httpFlow);
        assertEquals(NodeType.Http, workflowService.get("default", wfId).nodes().get(1).type(), "草稿应已修改");

        workflowService.rollback("default", wfId);
        assertEquals(NodeType.LLM, workflowService.get("default", wfId).nodes().get(1).type(), "回滚后应恢复为 LLM 节点");

        // 引擎可直接执行已发布的定义（next 链串联正确）
        assertFalse(dagEngine.execute(workflowService.get("default", wfId), Map.of("input", "x")).all().isEmpty());
    }
}
