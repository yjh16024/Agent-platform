package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 客户端工厂（工厂方法模式）。
 * <p>
 * 调用方只描述「要连什么端点」（{@link McpEndpoint}），由工厂决定实例化哪个
 * {@link McpClient} 实现，从而把「使用方」与「具体实现」解耦——新增传输方式
 * （如 stdio / gRPC）只需加一个实现类与一个分支，不改任何调用点。
 * </p>
 *
 * <p><b>四类端点</b>：{@code http}（远程 Streamable HTTP）、{@code local}（进程内）、
 * {@code sandbox}（沙箱脚本）、{@code stdio}（**本地子进程上的 JSON-RPC**，
 * 2026-09-24 补 —— 它是挂 {@code npx} / {@code uvx} 拉起的官方 server 的唯一途径）。</p>
 */
@Slf4j
@Component
public class McpClientFactory {

    /** 支持的端点类型。 */
    public static final List<String> SUPPORTED_TYPES = List.of("http", "local", "sandbox", "stdio");

    private final Path localDir;
    private final Path sandboxDir;
    private final long sandboxTimeoutSeconds;
    private final List<String> sandboxAllowedCommands;
    private final long stdioTimeoutSeconds;
    private final List<String> stdioAllowedCommands;

    @Autowired
    public McpClientFactory(
            @Value("${agent-platform.mcp.local-dir:./data}") String localDir,
            @Value("${agent-platform.mcp.sandbox-dir:./data/sandbox}") String sandboxDir,
            @Value("${agent-platform.mcp.sandbox-timeout-seconds:30}") long sandboxTimeoutSeconds,
            @Value("${agent-platform.mcp.sandbox-allowed-commands:python,python3,node,bash}") String sandboxAllowedCommands,
            @Value("${agent-platform.mcp.stdio-timeout-seconds:60}") long stdioTimeoutSeconds,
            @Value("${agent-platform.mcp.stdio-allowed-commands:npx,uvx,node,python,python3}")
            String stdioAllowedCommands) {
        this.localDir = Path.of(localDir).toAbsolutePath().normalize();
        this.sandboxDir = Path.of(sandboxDir).toAbsolutePath().normalize();
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
        this.sandboxAllowedCommands = splitCsv(sandboxAllowedCommands);
        this.stdioTimeoutSeconds = stdioTimeoutSeconds <= 0 ? 60L : stdioTimeoutSeconds;
        this.stdioAllowedCommands = splitCsv(stdioAllowedCommands);
    }

    /** 便捷构造（单测 / 非 Spring 场景）。 */
    public McpClientFactory(Path localDir, Path sandboxDir) {
        this.localDir = localDir;
        this.sandboxDir = sandboxDir;
        this.sandboxTimeoutSeconds = 30L;
        this.sandboxAllowedCommands = List.of("python", "python3", "node", "bash");
        this.stdioTimeoutSeconds = 60L;
        this.stdioAllowedCommands = List.of("npx", "uvx", "node", "python", "python3");
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 按端点创建客户端（工厂方法）。
     */
    public McpClient create(McpEndpoint endpoint) {
        if (endpoint == null || endpoint.type() == null || endpoint.type().isBlank()) {
            throw BizException.badRequest("MCP 端点类型不能为空（" + String.join(" / ", SUPPORTED_TYPES) + "）");
        }
        return switch (endpoint.type().toLowerCase()) {
            case "http", "remote", "streamable" -> createHttp(endpoint.url(), endpoint.headers());
            case "local" -> createLocal(endpoint.dir() == null ? localDir : endpoint.dir());
            case "sandbox" -> createSandbox(endpoint.dir() == null ? sandboxDir : endpoint.dir(),
                    endpoint.timeoutSeconds());
            case "stdio" -> createStdio(endpoint.command(), endpoint.env(), endpoint.dir(),
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

    /**
     * 创建 **stdio** 客户端（把一条本地命令作为 MCP server 拉起）。
     *
     * <h3>⚠️ 这是本类里唯一"会执行本地命令"的分支</h3>
     * 所以单独做可执行文件白名单校验。但**必须清楚白名单的边界**：
     * 它挡的是"换成别的可执行文件"，**挡不住参数** ——
     * {@code npx -y <任意包>} 照样会下载并执行那个包里的代码。
     * 因此这不是一个"安全边界"，只是"防手滑"。真正的约束在调用侧：
     * 注册入口必须是管理员级权限，不能让模型自己发起。
     *
     * @param command        启动命令数组，如 {@code ["npx","-y","@modelcontextprotocol/server-filesystem","/repo"]}
     * @param env            追加的环境变量（stdio server 常靠它拿配置/密钥）
     * @param dir            子进程工作目录（可空）
     * @param timeoutSeconds 单次请求超时（可空，取配置默认）
     */
    public McpClient createStdio(List<String> command, Map<String, String> env, Path dir, Long timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw BizException.badRequest("MCP stdio 需要提供 command（启动命令数组），"
                    + "如 [\"npx\",\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/path/to/repo\"]");
        }
        String exe = command.get(0);
        if (!allowedStdioCommand(exe)) {
            throw BizException.forbidden("MCP stdio 启动命令不在白名单内：" + exe
                    + "（允许：" + String.join(" / ", stdioAllowedCommands)
                    + "；可通过 agent-platform.mcp.stdio-allowed-commands 调整）");
        }
        return new StdioMcpClient(command, env, dir,
                timeoutSeconds == null ? stdioTimeoutSeconds : timeoutSeconds);
    }

    /**
     * 可执行文件是否在白名单内。
     *
     * <p>比对前先剥掉**目录与扩展名** —— 否则白名单形同虚设：
     * 同一个命令在 Windows 上叫 {@code npx.cmd}，用户也可能填全路径
     * （{@code C:\Program Files\nodejs\npx.cmd}）。不归一就一个都匹配不上，
     * 而"配了白名单却全部拒绝"会逼着人直接放宽成空名单。</p>
     */
    private boolean allowedStdioCommand(String exe) {
        if (exe == null || exe.isBlank()) {
            return false;
        }
        String base = exe.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".cmd", ".exe", ".bat")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        for (String allowed : stdioAllowedCommands) {
            if (allowed.toLowerCase(Locale.ROOT).equals(base)) {
                return true;
            }
        }
        return false;
    }

    public boolean supports(String type) {
        return type != null && SUPPORTED_TYPES.contains(type.toLowerCase());
    }

    /**
     * MCP 端点描述（工厂入参）。
     *
     * @param type           端点类型：http / local / sandbox / stdio
     * @param url            远程端点地址（http 必填）
     * @param headers        附加请求头（http 可选）
     * @param dir            本地 / 沙箱根目录、或 stdio 子进程的工作目录
     * @param timeoutSeconds 执行 / 请求超时秒数（sandbox、stdio 可选）
     * @param command        stdio 的启动命令数组（其余类型为 null）
     * @param env            stdio 的附加环境变量
     */
    public record McpEndpoint(String type, String url, Map<String, String> headers,
                              Path dir, Long timeoutSeconds,
                              List<String> command, Map<String, String> env) {

        public static McpEndpoint http(String url, Map<String, String> headers) {
            return new McpEndpoint("http", url, headers, null, null, null, Map.of());
        }

        public static McpEndpoint local(Path dir) {
            return new McpEndpoint("local", null, Map.of(), dir, null, null, Map.of());
        }

        public static McpEndpoint sandbox(Path dir, Long timeoutSeconds) {
            return new McpEndpoint("sandbox", null, Map.of(), dir, timeoutSeconds, null, Map.of());
        }

        public static McpEndpoint stdio(List<String> command, Map<String, String> env,
                                        Path dir, Long timeoutSeconds) {
            return new McpEndpoint("stdio", null, Map.of(), dir, timeoutSeconds,
                    command, env == null ? Map.of() : env);
        }
    }
}
