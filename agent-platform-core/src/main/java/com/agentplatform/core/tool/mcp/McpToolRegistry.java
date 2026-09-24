package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.tool.registry.ToolRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具接入服务：经 {@link McpClientFactory} 取得客户端 → 发现工具 → 注册进平台工具注册中心。
 * <p>
 * 四种接入形态共用同一套注册逻辑（面向 {@link McpClient} 接口编程）：
 * </p>
 * <ul>
 *   <li>{@code POST /api/v1/tools/mcp}：远程 HTTP MCP Server（body 含 server_url / api_key / headers）；</li>
 *   <li>{@link #connectLocal}：本地进程内工具（零网络）；</li>
 *   <li>{@link #connectSandbox}：沙箱子进程脚本工具（目录隔离 + 白名单 + 超时）；</li>
 *   <li>{@link #connectStdio}：**stdio 子进程 MCP Server**（挂 npx / uvx 拉起的官方 server）。</li>
 * </ul>
 * 同名工具覆盖（热更新）；返回本次注册成功的工具清单。
 *
 * <h3>⚠️ 连接是要"关"的</h3>
 * 与 HTTP（无状态）不同，**stdio 的 server 是一个常驻子进程**。所以本类会登记每个连接，
 * 并在三种时机回收它：① 同一 server 重复注册时（先关旧连接）；② 显式 {@link #disconnect}；
 * ③ 应用关闭时（{@link #closeAll}）。少了任何一条都会留下孤儿进程 ——
 * 而"重启前一直有几个 node 进程挂在后台"这种问题极难被联想到是注册逻辑造成的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final ToolRegistry toolRegistry;
    private final McpClientFactory factory;

    /**
     * 已建立的连接（label → client）。
     *
     * <p>全部传输都登记（http / local 的 {@code close()} 是空操作），
     * 这样回收路径只有一条，不必按类型分支。</p>
     */
    private final Map<String, McpClient> connections = new ConcurrentHashMap<>();

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

    /**
     * 注册 **stdio** MCP 工具（把一条本地命令作为 MCP server 拉起）。
     *
     * <p>命令的白名单校验在 {@link McpClientFactory#createStdio} 里做。
     * 但请记住那条白名单只是"防手滑"，不是安全边界（挡不住参数）——
     * 真正的约束是**调用方权限**：Controller 上该端点是管理员级权限，模型无法自行发起。</p>
     *
     * @param command        启动命令数组
     * @param env            附加环境变量（server 常靠它拿配置 / 密钥）
     * @param dir            子进程工作目录（可空）
     * @param timeoutSeconds 单次请求超时秒数（可空）
     */
    public Map<String, Object> connectStdio(List<String> command, Map<String, String> env,
                                            Path dir, Long timeoutSeconds) {
        McpClient client = factory.createStdio(command, env, dir, timeoutSeconds);
        String label = command == null || command.isEmpty() ? "stdio" : String.join(" ", command);
        return registerAll(client, "mcp-stdio", label);
    }

    /**
     * 断开指定连接（回收常驻子进程）。
     *
     * @param label 连接标识（即注册时返回的 {@code server_url}）
     */
    public Map<String, Object> disconnect(String label) {
        if (label == null || label.isBlank()) {
            throw BizException.badRequest("需要提供 label（注册时返回的 server_url）");
        }
        McpClient client = connections.remove(label.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        if (client == null) {
            result.put("closed", false);
            result.put("message", "没有登记该连接（可能已断开，或 label 不匹配）");
            return result;
        }
        closeQuietly(client, label);
        result.put("closed", true);
        result.put("transport", client.transport());
        return result;
    }

    /** 已登记的连接清单（用于排障：确认哪些 stdio server 还活着）。 */
    public List<Map<String, Object>> listConnections() {
        List<Map<String, Object>> out = new ArrayList<>();
        connections.forEach((label, client) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("transport", client.transport());
            out.add(row);
        });
        return out;
    }

    /** 应用关闭时回收全部连接（stdio 子进程不回收会变成孤儿进程）。 */
    @PreDestroy
    void closeAll() {
        if (connections.isEmpty()) {
            return;
        }
        log.info("[mcp] 应用关闭，回收 {} 个 MCP 连接", connections.size());
        // 注意参数顺序：Map.forEach 给的是 (key, value) = (label, client)，
        // 而 closeQuietly 的签名是 (client, label) —— 直接用方法引用会类型不匹配
        connections.forEach((label, client) -> closeQuietly(client, label));
        connections.clear();
    }

    /** 发现 → 包装 → 注册（与具体传输方式无关）。 */
    private Map<String, Object> registerAll(McpClient client, String source, String label) {
        List<McpToolSpec> discovered;
        try {
            discovered = client.listTools();
        } catch (Exception e) {
            // 发现失败时刚拉起的子进程要及时收掉，否则每次失败重试都多一个进程
            closeQuietly(client, label);
            throw new BizException("BAD_REQUEST", "MCP[" + client.transport() + "] 工具发现失败: " + e.getMessage());
        }
        if (discovered.isEmpty()) {
            closeQuietly(client, label);
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

        // 登记连接；同一 label 重复注册时先关掉旧连接 ——
        // 否则每重新注册一次就多留一个常驻子进程（stdio 尤其明显）
        McpClient previous = connections.put(label, client);
        if (previous != null && previous != client) {
            closeQuietly(previous, label);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", client.transport());
        result.put("server_url", label);
        result.put("registered", registered.size());
        result.put("tools", registered);
        return result;
    }

    private void closeQuietly(McpClient client, String label) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("[mcp] 关闭连接 {} 失败（忽略）：{}", label, e.getMessage());
        }
    }
}
