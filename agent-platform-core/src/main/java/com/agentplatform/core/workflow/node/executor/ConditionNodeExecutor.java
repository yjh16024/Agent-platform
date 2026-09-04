package com.agentplatform.core.workflow.node.executor;

import com.agentplatform.core.workflow.dag.NodeExecutor;
import com.agentplatform.core.workflow.dag.WorkflowContext;
import com.agentplatform.core.workflow.node.NodeType;
import com.agentplatform.core.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条件分支节点执行器。
 * <p>
 * 依次评估 branches 的 condition 表达式（如 {@code ${score} >= 0.7} 或 {@code score>0.5}），
 * 返回首个满足条件的 target 节点 ID；无分支满足则返回 default 目标。
 * </p>
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    private static final Pattern NUMERIC_COMPARE = Pattern.compile("([<>=!]*)\\s*(-?\\d+(\\.\\d+)?)");

    @Override
    public NodeType type() {
        return NodeType.Condition;
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowContext ctx) {
        if (node.branches() == null) {
            return null;
        }
        for (WorkflowNode.Branch branch : node.branches()) {
            if ("default".equalsIgnoreCase(branch.condition())) {
                continue; // default 最后处理
            }
            if (evaluate(branch.condition(), ctx)) {
                return branch.target();
            }
        }
        // default 分支
        for (WorkflowNode.Branch branch : node.branches()) {
            if ("default".equalsIgnoreCase(branch.condition())) {
                return branch.target();
            }
        }
        return null;
    }

    /**
     * 求值条件表达式：替换 ${var} 后做数值/字符串比较。
     */
    public boolean evaluate(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        // 替换变量占位
        String expr = ctx.resolveString(condition.trim());
        // 字符串比较：=='xxx' 或 == "xxx"
        Matcher strEq = Pattern.compile("==\\s*['\"]([^'\"]+)['\"]").matcher(expr);
        if (strEq.find()) {
            String expected = strEq.group(1);
            String actual = expr.substring(0, strEq.start()).trim();
            return expected.equals(actual);
        }
        // 数值比较
        Matcher numCmp = Pattern.compile("(>=|<=|!=|==|>|<)\\s*(-?\\d+(\\.\\d+)?)").matcher(expr);
        if (numCmp.find()) {
            String op = numCmp.group(1);
            double rhs = Double.parseDouble(numCmp.group(2));
            String lhsStr = expr.substring(0, numCmp.start()).trim();
            double lhs;
            try {
                lhs = Double.parseDouble(lhsStr);
            } catch (NumberFormatException e) {
                return false;
            }
            return switch (op) {
                case ">=" -> lhs >= rhs;
                case "<=" -> lhs <= rhs;
                case "!=" -> lhs != rhs;
                case "==" -> lhs == rhs;
                case ">" -> lhs > rhs;
                default -> lhs < rhs;
            };
        }
        // 布尔字面量
        return Boolean.parseBoolean(expr);
    }
}