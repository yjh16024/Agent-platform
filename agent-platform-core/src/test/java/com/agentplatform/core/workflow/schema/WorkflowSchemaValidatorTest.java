package com.agentplatform.core.workflow.schema;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流 Schema 校验器单元测试。
 */
class WorkflowSchemaValidatorTest {

    private WorkflowSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkflowSchemaValidator();
    }

    private WorkflowDefinition def(List<WorkflowNode> nodes) {
        return new WorkflowDefinition("test", nodes, null, null);
    }

    @Test
    @DisplayName("合法线性工作流通过校验")
    void validLinearFlow() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "mid", null),
                WorkflowNode.simple("mid", NodeType.Transform, "end", "result"),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        assertDoesNotThrow(() -> validator.validate(wf));
    }

    @Test
    @DisplayName("重复节点 ID 被拒绝")
    void duplicateIdRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("a", NodeType.Start, "b", null),
                WorkflowNode.simple("a", NodeType.Transform, "b", null),
                WorkflowNode.simple("b", NodeType.End, null, null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    @DisplayName("环被拒绝")
    void cycleRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("a", NodeType.Transform, "b", null),
                WorkflowNode.simple("b", NodeType.Transform, "a", null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    @DisplayName("Condition 节点缺 branches 被拒绝")
    void conditionWithoutBranchesRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "c", null),
                WorkflowNode.simple("c", NodeType.Condition, "end", "decision"),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("branches"));
    }

    @Test
    @DisplayName("引用不存在节点被拒绝")
    void danglingReferenceRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "ghost", null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("non-existent"));
    }
}