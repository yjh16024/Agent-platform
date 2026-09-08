package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端工厂（工厂方法模式）。
 * <p>
 * 调用方只描述「要连什么端点」（{@link McpEndpoint}），由工厂决定实例化哪个
 * {@link McpClient} 实现，从而把「使用方」与「具体实现」解耦——新增传输方式
 * （如 stdio / gRPC）只需加一个实现类与一个分支，不改任何调用点。
 * </p>
 */
@Slf4j
@Component
public class McpClientFactory {

    /** 支持的端点类型。 */
    public static final List<String> SUPPORTED_TYPES = List.of("http", "local", "sandbox");

    private final Path localDir;
    private final Path sandboxDir;
    private final long sandboxTimeoutSeconds;
    private final List<String> sandboxAllowedCommands;

    @Autowired
    public McpClientFactory(
            @Value("${agent-platform.mcp.local-dir:./data}") String localDir,
            @Value("${agent-platform.mcp.sandbox-dir:./data/sandbox}") String sandboxDir,
            @Value("${agent-platform.mcp.sandbox-timeout-seconds:30}") long sandboxTimeoutSeconds,
            @Value("${agent-platform.mcp.sandbox-allowed-commands:python,python3,node,bash}") String sandboxAllowedCommands) {
        this.localDir = Path.of(localDir).toAbsolutePath().normalize();
        this.sandboxDir = Path.of(sandboxDir).toAbsolutePath().normalize();
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
        this.sandboxAllowedCommands = Arrays.stream(sandboxAllowedCommands.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 便捷构造（单测 / 非 Spring 场景）。 */
    public McpClientFactory(Path localDir, Path sandboxDir) {
        this.localDir = localDir;
        this.sandboxDir = sandboxDir;
        this.sandboxTimeoutSeconds = 30L;
        this.sandboxAllowedCommands = List.of("python", "python3", "node", "bash");
    }

    /**
     * 按端点创建客户端（工厂方法）。
     */
    public McpClient create(McpEndpoint endpoint) {
        if (endpoint == null || endpoint.type() == null || endpoint.type().isBlank()) {
            throw BizException.badRequest("MCP 端点类型不能为空（http / local / sandbox）");
        }
        return switch (endpoint.type().toLowerCase()) {
            case "http", "remote", "streamable" -> createHttp(endpoint.url(), endpoint.headers());
            case "local" -> createLocal(endpoint.dir() == null ? localDir : endpoint.dir());
            case "sandbox" -> createSandbox(endpoint.dir() == null ? sandboxDir : endpoint.dir(),
                    endpoint.timeoutSeconds());
            default -> throw BizException.badRequest("不支持的 MCP 端点类型: " + endpoint.type()
                    + "（支持：" + String.join("/", SUPPORTED_TYPES) + "）");
        };
    }

    public McpClient createHttp(String url, Map<String, String> headers) {
        return new HttpMcpClient(url, headers);
    }

    public McpClient createLocal(Path dir) {
        return new LocalMcpClient(dir == null ? localDir : dir);
    }

    public McpClient createSandbox(Path dir, Long timeoutSeconds) {
        return new SandboxMcpClient(dir == null ? sandboxDir : dir,
                timeoutSeconds == null ? sandboxTimeoutSeconds : timeoutSeconds,
                sandboxAllowedCommands);
    }

    public boolean supports(String type) {
        return type != null && SUPPORTED_TYPES.contains(type.toLowerCase());
    }

    /**
     * MCP 端点描述（工厂入参）。
     *
     * @param type           端点类型：http / local / sandbox
     * @param url            远程端点地址（http 必填）
     * @param headers        附加请求头（http 可选）
     * @param dir            本地/沙箱根目录（local、sandbox 用）
     * @param timeoutSeconds 沙箱执行超时秒数（sandbox 可选）
     */
    public record McpEndpoint(String type, String url, Map<String, String> headers,
                              Path dir, Long timeoutSeconds) {

        public static McpEndpoint http(String url, Map<String, String> headers) {
            return new McpEndpoint("http", url, headers, null, null);
        }

        public static McpEndpoint local(Path dir) {
            return new McpEndpoint("local", null, Map.of(), dir, null);
        }

        public static McpEndpoint sandbox(Path dir, Long timeoutSeconds) {
            return new McpEndpoint("sandbox", null, Map.of(), dir, timeoutSeconds);
        }
    }
}
