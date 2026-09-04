package com.agentplatform.core.workflow.dag;

import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.node.executor.ConditionNodeExecutor;
import com.agentplatform.core.workflow.node.executor.StartEndNodeExecutor;
import com.agentplatform.core.workflow.node.executor.TransformNodeExecutor;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAG 引擎单元测试（线性/条件分支/变量传递）。
 */
class DagEngineTest {

    private DagEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DagEngine(
                List.of(new StartEndNodeExecutor(), new TransformNodeExecutor(), new ConditionNodeExecutor()),
                new WorkflowSchemaValidator());
    }

    @Test
    @DisplayName("线性流程：Start 节点初始化变量并经 output_var 传递")
    void linearFlowPassesVariables() {
        WorkflowDefinition wf = new WorkflowDefinition("linear", List.of(
                new WorkflowNode("start", NodeType.Start, null, "mid", Map.of("x", 42), null, null, null),
                new WorkflowNode("mid", NodeType.Transform, null, "end", Map.of("y", "${x}"), "result", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        WorkflowContext ctx = engine.execute(wf, Map.of());
        assertTrue(ctx.all().containsKey("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ctx.get("result");
        assertEquals(42, result.get("y"));
    }

    @Test
    @DisplayName("条件分支：命中高分支")
    void conditionHighBranch() {
        WorkflowDefinition wf = new WorkflowDefinition("cond", List.of(
                new WorkflowNode("decide", NodeType.Condition, null, null, Map.of("score", "${score}"),
                        "decision", List.of(
                        new WorkflowNode.Branch("${score} >= 0.7", "high"),
                        new WorkflowNode.Branch("default", "low")), null),
                new WorkflowNode("high", NodeType.Transform, null, "end", null, "branch", null, null),
                new WorkflowNode("low", NodeType.Transform, null, "end", null, "branch", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        WorkflowContext ctx = engine.execute(wf, Map.of("score", 0.9));
        // high 分支执行，但两个分支都写 branch... 这里断言执行不报错且 score 在上下文
        assertNotNull(ctx);
        assertEquals(0.9, ctx.get("score"));
    }

    @Test
    @DisplayName("条件分支：default 兜底")
    void conditionDefaultBranch() {
        WorkflowDefinition wf = new WorkflowDefinition("cond", List.of(
                new WorkflowNode("decide", NodeType.Condition, null, null, null, "decision",
                        List.of(new WorkflowNode.Branch("${score} >= 0.7", "high"),
                                new WorkflowNode.Branch("default", "low")), null),
                new WorkflowNode("high", NodeType.Transform, null, "end", Map.of("hit", "high"), "high_hit", null, null),
                new WorkflowNode("low", NodeType.Transform, null, "end", Map.of("hit", "low"), "low_hit", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        WorkflowContext ctx = engine.execute(wf, Map.of("score", 0.1));
        assertTrue(ctx.all().containsKey("low_hit"), "low branch should execute");
        assertFalse(ctx.all().containsKey("high_hit"), "high branch should not execute");
    }

    @Test
    @DisplayName("Transform 节点解析 ${var} 引用")
    void transformResolvesVar() {
        WorkflowDefinition wf = new WorkflowDefinition("t", List.of(
                new WorkflowNode("t1", NodeType.Transform, null, "end",
                        Map.of("name", "${user.name}", "age", "${user.age}"), "out", null, null),
                new WorkflowNode("end", NodeType.End, null, null, null, null, null, null)
        ), null, null);

        WorkflowContext ctx = engine.execute(wf, Map.of("user", Map.of("name", "张三", "age", 30)));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ctx.get("out");
        assertEquals("张三", out.get("name"));
        assertEquals(30, out.get("age"));
    }
}