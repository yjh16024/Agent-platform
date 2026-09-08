package com.agentplatform.core.skill.executor;

import com.agentplatform.core.skill.SkillFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

/**
 * 脚本执行器：执行 Skill 目录内 {@code scripts/} 下的脚本（子进程隔离）。
 * <p>
 * 与 {@link PromptSkillExecutor} 同为 {@link SkillExecutor} 的一种命令实现：
 * 命令形如 {@code scripts/rotate.py}，执行器按扩展名推断解释器（白名单），
 * 在 Skill 目录内以子进程运行，带超时强杀与输出截断。
 * </p>
 */
@Slf4j
@Component
public class ScriptSkillExecutor implements SkillExecutor {

    private static final int MAX_OUTPUT = 32 * 1024;
    private static final List<String> ALLOWED = List.of("python", "python3", "node", "bash");

    private final SkillFileStore fileStore;
    private final long timeoutSeconds;

    @Autowired
    public ScriptSkillExecutor(SkillFileStore fileStore,
                              @Value("${agent-platform.skills.exec-timeout-seconds:30}") long timeoutSeconds) {
        this.fileStore = fileStore;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30L : timeoutSeconds;
    }

    @Override
    public String type() {
        return "script";
    }

    @Override
    public String description() {
        return "执行 Skill 目录内 scripts/ 下的脚本（子进程 + 白名单解释器 + 超时）";
    }

    @Override
    public boolean supports(SkillCommand command) {
        if (command == null) {
            return false;
        }
        if (command.type() != null && type().equalsIgnoreCase(command.type())) {
            return true;
        }
        String c = command.command();
        return c != null && (c.startsWith("scripts/") || c.endsWith(".py") || c.endsWith(".js") || c.endsWith(".sh"));
    }

    @Override
    public SkillExecutionResult execute(SkillCommand command) {
        long t0 = System.currentTimeMillis();
        try {
            String script = resolveScriptPath(command);
            if (script == null || script.isBlank()) {
                return SkillExecutionResult.fail(
                        "未指定要执行的脚本（command 传 scripts/xxx.py，或 args.script）", type(), elapsed(t0));
            }
            Path base = skillDir(command);
            Path target = base.resolve(script.replace('\\', '/')).normalize();
            if (!target.startsWith(base)) {
                return SkillExecutionResult.fail("非法脚本路径（越权执行）: " + script, type(), elapsed(t0));
            }
            if (!Files.isRegularFile(target)) {
                return SkillExecutionResult.fail("脚本不存在: " + script, type(), elapsed(t0));
            }
            String interpreter = interpreterOf(target);
            if (interpreter == null) {
                return SkillExecutionResult.fail(
                        "无法推断解释器（仅支持 .py/.js/.sh）: " + script, type(), elapsed(t0));
            }
            List<String> commandLine = new ArrayList<>();
            commandLine.add(interpreter);
            commandLine.add(target.toString());
            Object extra = command.args() == null ? null : command.args().get("args");
            if (extra instanceof List<?> list) {
                list.forEach(v -> commandLine.add(String.valueOf(v)));
            } else if (extra instanceof String s && !s.isBlank()) {
                commandLine.addAll(Arrays.asList(s.split("\\s+")));
            }
            String output = run(commandLine, base);
            return SkillExecutionResult.ok(output, type(), elapsed(t0));
        } catch (Exception e) {
            log.warn("[skills] script executor failed: {}", e.getMessage());
            return SkillExecutionResult.fail(e.getMessage(), type(), elapsed(t0));
        }
    }

    private String resolveScriptPath(SkillCommand command) {
        if (command.command() != null && !command.command().isBlank()) {
            return command.command();
        }
        Map<String, Object> args = command.args();
        if (args != null && args.get("script") != null) {
            String s = String.valueOf(args.get("script"));
            return s.startsWith("scripts/") ? s : "scripts/" + s;
        }
        return null;
    }

    private Path skillDir(SkillCommand command) {
        String dir = command.dir() == null || command.dir().isBlank()
                ? fileStore.sanitize(command.skillName()) : fileStore.sanitize(command.dir());
        return fileStore.root().resolve(dir).normalize();
    }

    private String interpreterOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".py")) {
            return ALLOWED.contains("python") ? "python" : null;
        }
        if (name.endsWith(".js")) {
            return ALLOWED.contains("node") ? "node" : null;
        }
        if (name.endsWith(".sh")) {
            return ALLOWED.contains("bash") ? "bash" : null;
        }
        return null;
    }

    private String run(List<String> commandLine, Path workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(commandLine);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("脚本执行超时（" + timeoutSeconds + "s 已强杀）");
        }
        int code = process.exitValue();
        String body = output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) + "\n…（输出过长已截断）" : output;
        if (code != 0) {
            throw new IOException("脚本退出码 " + code + "\n" + body);
        }
        return body.isBlank() ? "(脚本执行成功，无输出)" : body;
    }

    private long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
