package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolCatalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内工具注册表。
 *
 * <p>名字与平台侧的同名类一致，但**故意是两个类型**：框架版本没有任何外部依赖
 * （不碰 Spring 容器、不碰数据库），平台版本负责持久化与热注册。
 * 迁移完工后，平台侧只需要把工具实例 register 到这个注册表里。
 */
public class ToolRegistry implements ToolCatalog {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized void register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具必须有名字");
        }
        tools.put(tool.name(), tool);
    }

    public synchronized void unregister(String name) {
        tools.remove(name);
    }

    public synchronized Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具未注册: " + name);
        }
        return tool;
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    public synchronized Collection<Tool> all() {
        return java.util.List.copyOf(tools.values());
    }
}
