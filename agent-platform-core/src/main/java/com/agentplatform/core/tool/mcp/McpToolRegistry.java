package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具接入服务：经 {@link McpClientFactory} 取得客户端 → 发现工具 → 注册进平台工具注册中心。
 * <p>
 * 三种接入形态共用同一套注册逻辑（面向 {@link McpClient} 接口编程）：
 * </p>
 * <ul>
 *   <li>{@code POST /api/v1/tools/mcp}：远程 HTTP MCP Server（body 含 server_url / api_key / headers）；</li>
 *   <li>{@link #connectLocal}：本地进程内工具（零网络）；</li>
 *   <li>{@link #connectSandbox}：沙箱子进程脚本工具（目录隔离 + 白名单 + 超时）。</li>
 * </ul>
 * 同名工具覆盖（热更新）；返回本次注册成功的工具清单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final ToolRegistry toolRegistry;
    private final McpClientFactory factory;

    /**
     * 连接远程 MCP 服务器并注册其全部工具。
     *
     * @param serverUrl MCP Server 地址（Streamable HTTP 端点）
     * @param apiKey    可选（透传为 {@code Authorization: Bearer <apiKey>}）
     * @param headers   额外请求头（键值）
     * @return 注册结果（server_url + 工具清单）
     */
    public Map<String, Object> connect(String serverUrl, String apiKey, Map<String, Object> headers) {
        Map<String, String> requestHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    requestHeaders.put(k, String.valueOf(v));
                }
            });
        }
        if (apiKey != null && !apiKey.isBlank()) {
            requestHeaders.putIfAbsent("Authorization", "Bearer " + apiKey.trim());
        }
        return registerAll(factory.createHttp(serverUrl, requestHeaders), "mcp", serverUrl);
    }

    /** 注册本地进程内 MCP 工具。 */
    public Map<String, Object> connectLocal(Path dir) {
        McpClient client = factory.createLocal(dir);
        return registerAll(client, "mcp-local",
                dir == null ? "local" : dir.toAbsolutePath().normalize().toString());
    }

    /** 注册沙箱子进程 MCP 工具。 */
    public Map<String, Object> connectSandbox(Path dir, Long timeoutSeconds) {
        McpClient client = factory.createSandbox(dir, timeoutSeconds);
        return registerAll(client, "mcp-sandbox",
                dir == null ? "sandbox" : dir.toAbsolutePath().normalize().toString());
    }

    /** 发现 → 包装 → 注册（与具体传输方式无关）。 */
    private Map<String, Object> registerAll(McpClient client, String source, String label) {
        List<McpToolSpec> discovered;
        try {
            discovered = client.listTools();
        } catch (Exception e) {
            throw new BizException("BAD_REQUEST", "MCP[" + client.transport() + "] 工具发现失败: " + e.getMessage());
        }
        if (discovered.isEmpty()) {
            throw new BizException("BAD_REQUEST",
                    "MCP[" + client.transport() + "] 未暴露任何工具（tools/list 为空）");
        }

        List<Map<String, Object>> registered = new ArrayList<>();
        for (McpToolSpec spec : discovered) {
            toolRegistry.register(
                    new McpToolAdapter(spec.name(), spec.description(), spec.inputSchema(), label, client),
                    source);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", spec.name());
            row.put("description", spec.description() == null ? "" : spec.description());
            registered.add(row);
        }
        log.info("Registered {} MCP tools from {} [{}]", registered.size(), label, client.transport());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", client.transport());
        result.put("server_url", label);
        result.put("registered", registered.size());
        result.put("tools", registered);
        return result;
    }
}
