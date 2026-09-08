package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 本地 MCP 客户端（进程内实现，零网络开销）。
 * <p>
 * 直接在本进程执行一组固定的本地工具，用于演示「同一 {@link McpClient} 契约的多态实现」，
 * 也便于离线/无 MCP Server 环境验证工具链路：
 * </p>
 * <ul>
 *   <li>{@code local.echo}：回显文本；</li>
 *   <li>{@code local.time}：返回当前时间；</li>
 *   <li>{@code local.read_file}：按相对路径读取文件（限制在 baseDir 内，防目录穿越）。</li>
 * </ul>
 */
public class LocalMcpClient implements McpClient {

    /** 单文件读取上限（防止把大文件塞进 LLM 上下文）。 */
    private static final int MAX_READ_BYTES = 64 * 1024;

    private final Path baseDir;

    public LocalMcpClient(Path baseDir) {
        this.baseDir = baseDir == null
                ? Path.of("./data").toAbsolutePath().normalize()
                : baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String transport() {
        return "local";
    }

    @Override
    public List<McpToolSpec> listTools() {
        return List.of(
                new McpToolSpec("local.echo", "回显输入文本（本地进程内执行）", null),
                new McpToolSpec("local.time", "返回当前时间（本地进程内执行）", null),
                new McpToolSpec("local.read_file",
                        "按相对路径读取文本文件内容（限制在配置的本地目录内）", null));
    }

    @Override
    public String callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (name) {
            case "local.echo" -> str(args.get("text"));
            case "local.time" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            case "local.read_file" -> readFile(str(args.get("path")));
            default -> throw BizException.notFound("mcp tool", name);
        };
    }

    private String readFile(String relative) {
        if (relative == null || relative.isBlank()) {
            throw BizException.badRequest("local.read_file 需要 path 参数");
        }
        Path target = baseDir.resolve(relative.replace('\\', '/')).normalize();
        if (!target.startsWith(baseDir)) {
            throw BizException.badRequest("非法文件路径（越权读取）: " + relative);
        }
        if (!Files.isRegularFile(target)) {
            throw BizException.notFound("file", relative);
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            boolean truncated = bytes.length > MAX_READ_BYTES;
            String text = new String(bytes, 0, Math.min(bytes.length, MAX_READ_BYTES), StandardCharsets.UTF_8);
            return truncated ? text + "\n…（内容超过 " + MAX_READ_BYTES + " 字节已截断）" : text;
        } catch (IOException e) {
            throw BizException.internal("读取文件失败: " + e.getMessage(), e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
