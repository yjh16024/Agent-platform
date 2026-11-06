package com.agentplatform.framework.api;

import java.util.Collection;

/**
 * 工具目录：运行时**只读**的工具视图。
 *
 * <p>为什么把「读」和「写」分开：运行一次对话只需要「有哪些工具、按名取一个」，
 * 不需要注册/反注册。把注册留着给宿主（平台有热注册、MCP 动态发现、插件 attach/detach），
 * 运行时对着只读视图办事，边界更清楚。</p>
 *
 * <p>实现方：框架自带 {@code ToolRegistry}（进程内可写）；平台侧用
 * {@code PlatformToolCatalog} 适配既有的注册中心。</p>
 */
public interface ToolCatalog {

    /** 按名取工具；不存在时的行为由实现决定（抛异常或返回 null，调用方需按实现约定处理）。 */
    Tool get(String name);

    boolean contains(String name);

    /** 全部可用工具（快照语义，调用方可安全遍历）。 */
    Collection<Tool> all();
}
