package com.agentplatform.core.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端抽象（面向接口编程 —— 多态的基础）。
 * <p>
 * 同一份「工具发现 + 工具调用」契约，由不同传输实现承载：
 * </p>
 * <ul>
 *   <li>{@link HttpMcpClient}：远程 MCP Server（Streamable HTTP / JSON-RPC）；</li>
 *   <li>{@link LocalMcpClient}：本地进程内工具（零网络、能力固定、开销最低）；</li>
 *   <li>{@link SandboxMcpClient}：沙箱子进程执行脚本（目录隔离 + 白名单 + 超时）。</li>
 * </ul>
 * <p>具体实现由 {@link McpClientFactory} 按端点形态创建（工厂方法），
 * 调用方只依赖本接口，新增传输方式无需改动任何既有代码。</p>
 */
public interface McpClient {

    /** 传输方式标识：http / local / sandbox。 */
    String transport();

    /** 发现对端暴露的工具。 */
    List<McpToolSpec> listTools();

    /**
     * 调用指定工具，返回文本化结果。
     *
     * @param name      工具名
     * @param arguments 入参（可空）
     */
    String callTool(String name, Map<String, Object> arguments);

    /** 释放资源（默认无操作，网络/子进程实现可覆盖）。 */
    default void close() {
        // no-op
    }
}
