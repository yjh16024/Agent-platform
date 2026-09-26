package com.agentplatform.core.workflow.schema;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    // ---------------- 2026-09-24 新增的三条校验 ----------------

    /** 造一个带 config 的节点（record 全参构造，config 用 Map）。 */
    private WorkflowNode node(String id, NodeType type, String next, String outputVar,
                              Map<String, Object> config) {
        return new WorkflowNode(id, type, null, next, null, outputVar, null, config);
    }

    @Test
    @DisplayName("LLM 节点缺 config.prompt 被拒绝（把执行期异常提前到保存期）")
    void llmWithoutPromptRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "llm", null),
                node("llm", NodeType.LLM, "end", "answer", Map.of("model", "deepseek-chat")),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("config.prompt"), ex.getMessage());
    }

    @Test
    @DisplayName("config 写 camelCase（kbIds）也算提供，不被拒绝")
    void camelCaseConfigKeyAccepted() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "kb", null),
                node("kb", NodeType.KnowledgeBase, "end", "chunks",
                        Map.of("kbIds", "kb-1", "query", "${input}")),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        assertDoesNotThrow(() -> validator.validate(wf));
    }

    @Test
    @DisplayName("KB 节点缺 query 被拒绝")
    void knowledgeBaseWithoutQueryRejected() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "kb", null),
                node("kb", NodeType.KnowledgeBase, "end", "chunks", Map.of("kb_ids", "kb-1")),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        BizException ex = assertThrows(BizException.class, () -> validator.validate(wf));
        assertTrue(ex.getMessage().contains("config.query"), ex.getMessage());
    }

    @Test
    @DisplayName("互斥分支写同一个 output_var 是合法的（静态无法判定互斥，刻意不校验）")
    void sameOutputVarInDifferentBranchesAllowed() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "c", null),
                new WorkflowNode("c", NodeType.Condition, null, null, null, null,
                        List.of(new WorkflowNode.Branch("${score} > 0.5", "high"),
                                new WorkflowNode.Branch("else", "low")), Map.of()),
                WorkflowNode.simple("high", NodeType.Transform, "end", "branch"),
                WorkflowNode.simple("low", NodeType.Transform, "end", "branch"),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        assertDoesNotThrow(() -> validator.validate(wf));
    }

    @Test
    @DisplayName("引用运行时才注入的变量不被拒绝（静态不知 input Map 里有哪些 key）")
    void runtimeInjectedVariableAllowed() {
        WorkflowDefinition wf = def(List.of(
                WorkflowNode.simple("start", NodeType.Start, "t", null),
                node("t", NodeType.Transform, "end", "out", Map.of("v", "${user}")),
                WorkflowNode.simple("end", NodeType.End, null, null)));
        assertDoesNotThrow(() -> validator.validate(wf));
    }
}