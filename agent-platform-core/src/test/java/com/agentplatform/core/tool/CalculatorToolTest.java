package com.agentplatform.core.tool;

import com.agentplatform.core.tool.builtin.CalculatorTool;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计算器工具单元测试。
 */
class CalculatorToolTest {

    private CalculatorTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        tool = new CalculatorTool();
        mapper = new ObjectMapper();
    }

    private ToolResult calc(String expr) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expression", expr);
        return tool.execute(node, ToolContext.of("t1", null, null));
    }

    @Test
    @DisplayName("基本四则运算")
    void basicArithmetic() {
        assertTrue(calc("2+3*4").output().asText().equals("14"));
        assertEquals("7", calc("2+5").output().asText());
    }

    @Test
    @DisplayName("数学函数")
    void functions() {
        assertEquals("4", calc("sqrt(16)").output().asText());
        assertEquals("0", calc("sin(0)").output().asText());
    }

    @Test
    @DisplayName("非法表达式返回失败")
    void invalidExpression() {
        ToolResult r = calc("1/0");
        // 1/0 在 double 中返回 Infinity，不抛异常；测非法语法
        ToolResult bad = calc("(1+2");
        assertFalse(bad.success());
        assertNotNull(bad.error());
    }

    @Test
    @DisplayName("缺失参数返回失败")
    void missingArg() {
        ObjectNode node = mapper.createObjectNode();
        ToolResult r = tool.execute(node, ToolContext.of("t1", null, null));
        assertFalse(r.success());
        assertTrue(r.error().contains("expression"));
    }
}