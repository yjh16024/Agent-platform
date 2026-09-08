package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 沙箱 MCP 客户端（子进程隔离实现）。
 * <p>
 * 与 {@link LocalMcpClient}（进程内直调）形成对比：本实现把脚本放进**独立子进程**执行，
 * 具备目录隔离、解释器白名单、超时强杀、输出截断与环境最小化等约束，
 * 体现同一 {@link McpClient} 契约下的另一种多态实现。
 * </p>
 * <ul>
 *   <li>{@code sandbox.list}：列出沙箱目录内的脚本；</li>
 *   <li>{@code sandbox.run}：按相对路径执行脚本（解释器按扩展名推断且必须在白名单内）。</li>
 * </ul>
 */
@Slf4j
public class SandboxMcpClient implements McpClient {

    /** 输出截断上限（避免巨量 stdout 打爆上下文）。 */
    private static final int MAX_OUTPUT = 32 * 1024;

    private final Path sandboxDir;
    private final long timeoutSeconds;
    private final List<String> allowedCommands;

    public SandboxMcpClient(Path sandboxDir, long timeoutSeconds, List<String> allowedCommands) {
        this.sandboxDir = (sandboxDir == null
                ? Path.of("./data/sandbox").toAbsolutePath().normalize()
                : sandboxDir.toAbsolutePath().normalize());
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30L : timeoutSeconds;
        this.allowedCommands = (allowedCommands == null || allowedCommands.isEmpty())
                ? List.of("python", "python3", "node", "bash") : allowedCommands;
    }

    @Override
    public String transport() {
        return "sandbox";
    }

    @Override
    public List<McpToolSpec> listTools() {
        return List.of(
                new McpToolSpec("sandbox.list", "列出沙箱目录内的脚本（相对路径）", null),
                new McpToolSpec("sandbox.run",
                        "在沙箱子进程中执行脚本，参数：script（相对路径）、args（可选字符串数组）", null));
    }

    @Override
    public String callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (name) {
            case "sandbox.list" -> String.join("\n", listScripts());
            case "sandbox.run" -> runScript(args);
            default -> throw BizException.notFound("mcp tool", name);
        };
    }

    private List<String> listScripts() {
        if (!Files.isDirectory(sandboxDir)) {
            return List.of();
        }
        List<String> rel = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sandboxDir)) {
            paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> rel.add(sandboxDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw BizException.internal("列出沙箱文件失败: " + e.getMessage(), e);
        }
        return rel;
    }

    private String runScript(Map<String, Object> args) {
        String script = str(args.get("script"));
        if (script.isBlank()) {
            throw BizException.badRequest("sandbox.run 需要 script 参数（沙箱目录内的相对路径）");
        }
        Path target = sandboxDir.resolve(script.replace('\\', '/')).normalize();
        if (!target.startsWith(sandboxDir)) {
            throw BizException.badRequest("非法脚本路径（越权执行）: " + script);
        }
        if (!Files.isRegularFile(target)) {
            throw BizException.notFound("script", script);
        }
        String command = resolveInterpreter(target);
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.add(target.toString());
        Object extra = args.get("args");
        if (extra instanceof List<?> list) {
            list.forEach(v -> commandLine.add(String.valueOf(v)));
        } else if (extra instanceof String s && !s.isBlank()) {
            commandLine.addAll(Arrays.asList(s.split("\\s+")));
        }
        return execute(commandLine);
    }

    /** 按扩展名推断解释器，并校验白名单。 */
    private String resolveInterpreter(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        String candidate;
        if (name.endsWith(".py")) {
            candidate = "python";
        } else if (name.endsWith(".js")) {
            candidate = "node";
        } else if (name.endsWith(".sh")) {
            candidate = "bash";
        } else {
            throw BizException.badRequest("无法推断脚本解释器（仅支持 .py / .js / .sh）: " + name);
        }
        if (!allowedCommands.contains(candidate)) {
            throw BizException.forbidden("解释器未在白名单内: " + candidate);
        }
        return candidate;
    }

    private String execute(List<String> commandLine) {
        ProcessBuilder pb = new ProcessBuilder(commandLine);
        pb.directory(sandboxDir.toFile());
        pb.redirectErrorStream(true);
        // 最小化环境：只保留 PATH 等必要变量，避免把宿主敏感环境变量带入脚本
        try {
            pb.environment().keySet().removeIf(k -> !k.equals("PATH")
                    && !k.equals("SystemRoot") && !k.equals("TEMP") && !k.equals("TMP")
                    && !k.equals("LANG") && !k.equals("HOME"));
        } catch (UnsupportedOperationException e) {
            log.debug("当前 JDK 不支持精简子进程环境变量，沿用继承环境: {}", e.getMessage());
        }
        try {
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw BizException.internal("沙箱脚本执行超时（" + timeoutSeconds + "s 已强杀）");
            }
            int code = process.exitValue();
            String body = output.length() > MAX_OUTPUT
                    ? output.substring(0, MAX_OUTPUT) + "\n…（输出过长已截断）" : output;
            if (code != 0) {
                throw BizException.internal("沙箱脚本退出码 " + code + "\n" + body);
            }
            return body.isBlank() ? "(脚本执行成功，无输出)" : body;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.internal("沙箱脚本启动失败（解释器不可用时请检查白名单配置）: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.internal("沙箱脚本执行被中断", e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
