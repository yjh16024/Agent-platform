package com.agentplatform.core.tool.registry;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心（注册表模式）。
 * <p>内置工具 + 自定义 HTTP API + MCP Server，支持动态加载/热更新。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具（同名覆盖，即热更新）。
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * 反注册工具。
     */
    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * 按名获取工具。
     */
    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw BizException.notFound("tool", name);
        }
        return tool;
    }

    /**
     * 是否存在工具。
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 全部工具。
     */
    public Collection<Tool> all() {
        return tools.values();
    }
}