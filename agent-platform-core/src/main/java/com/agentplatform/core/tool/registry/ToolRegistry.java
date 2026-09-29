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

    /** 来源：内置工具（代码提供，不可删）。 */
    public static final String SOURCE_BUILTIN = "builtin";
    /** 来源：用户注册的 HTTP API 工具（可编辑、可删）。 */
    public static final String SOURCE_HTTP = "http";
    /** 来源：MCP Server 提供的工具。 */
    public static final String SOURCE_MCP = "mcp";
    /**
     * 来源：插件贡献的工具。
     *
     * <p>它的生命周期<b>跟着插件走</b>：随 {@code attach} 出现、随 {@code detach} 消失。
     * 所以在工具页里要单独表达 —— 既不能像 HTTP 工具那样编辑，也不能在那删除
     * （删了插件不会补回，见 {@code ExtensionRegistry.registerTools} 的说明），
     * 而应指引用户去「插件」页卸载。</p>
     */
    public static final String SOURCE_PLUGIN = "plugin";

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** 工具来源标记：见本类的 SOURCE_* 常量。 */
    private final Map<String, String> sources = new ConcurrentHashMap<>();

    /**
     * 注册工具（同名覆盖，即热更新），来源默认 {@code external}。
     */
    public void register(Tool tool) {
        register(tool, "external");
    }

    /**
     * 注册工具并标注来源。
     */
    public void register(Tool tool, String source) {
        tools.put(tool.name(), tool);
        if (source != null) {
            sources.put(tool.name(), source);
        }
    }

    /**
     * 查询工具来源（未记录时按内置处理）。
     *
     * <p>「未记录即内置」是有意的兜底：内置工具由 Spring 容器直接注册、不带来源标签，
     * 而把未知来源当内置更安全 —— 内置在界面上是受保护的一类（不可删）。</p>
     */
    public String sourceOf(String name) {
        return sources.getOrDefault(name, SOURCE_BUILTIN);
    }

    /**
     * 反注册工具。
     */
    public void unregister(String name) {
        tools.remove(name);
        sources.remove(name);
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