package com.agentplatform.core.tool;

import com.agentplatform.core.tool.builtin.CalculatorTool;
import com.agentplatform.core.tool.executor.AuditToolFilter;
import com.agentplatform.core.tool.executor.ToolExecutor;
import com.agentplatform.core.tool.registry.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注册表 + 责任链执行器单元测试。
 */
class ToolExecutorTest {

    private ToolRegistry registry;
    private ToolExecutor executor;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        executor = new ToolExecutor(registry, List.of(new AuditToolFilter()));
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("注册表查询工具")
    void registryLookup() {
        assertTrue(registry.contains("calc"));
        assertNotNull(registry.get("calc"));
        assertThrows(Exception.class, () -> registry.get("nonexistent"));
    }

    @Test
    @DisplayName("经责任链执行工具成功")
    void executeViaChain() {
        ObjectNode node = mapper.createObjectNode();
        node.put("expression", "1+1");
        ToolResult r = executor.run("calc", node, ToolContext.of("t1", "a1", "r1"));
        assertTrue(r.success());
        assertEquals("2", r.output().asText());
    }

    @Test
    @DisplayName("执行不存在工具抛出异常")
    void executeMissingTool() {
        assertThrows(Exception.class, () -> executor.run("ghost", null, ToolContext.of("t1", null, null)));
    }
}