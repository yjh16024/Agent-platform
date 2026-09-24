package com.agentplatform.core.tool.action;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.fs.WorkspaceService;
import com.agentplatform.core.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 项目动作的注册中心：把内置动作 + 探测出的测试/构建动作 + 用户自定义动作，
 * 各自注册成一个独立工具。
 *
 * <h3>三类动作</h3>
 * <ol>
 *   <li><b>固定型</b>（{@code git_status} / {@code git_diff} / {@code git_log}）：
 *       与项目类型无关，任何 git 仓库都能用；</li>
 *   <li><b>探测型</b>（{@code run_tests} / {@code run_build}）：
 *       由 {@link ProjectProbe} 按标志文件决定命令，**模型不参与判断**；</li>
 *   <li><b>自定义型</b>：用户在一个 JSON 文件里写的命令模板（见 {@link #customActions}）。</li>
 * </ol>
 *
 * <h3>★ 为什么是 @Lazy(false) + ApplicationReadyEvent</h3>
 * 桌面版以 {@code -Dspring.main.lazy-initialization=true} 启动，而本类**不被任何 bean 依赖**
 * （它自己的作用是往注册中心里塞东西）—— 懒加载下它**永远不会被创建**，
 * 表现为"动作一个都没注册，日志里连一行都没有"。
 * 这个坑项目里踩过一次（{@code BuiltinPluginRegistrar}），这里按同样的方式绕开。
 *
 * <h3>工作区不可用时不注册</h3>
 * 配置里的工作区默认是 {@code ./data/workspace}（空目录），此时注册动作只会让工具列表
 * 凭空多出几个永远失败的入口。宁可**一个都不注册**，也不要让模型看到一堆用不了的工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionRegistry {

    private final ToolRegistry toolRegistry;
    private final CommandRunner runner;
    private final ProjectProbe probe;
    private final WorkspaceService workspace;

    /** 总开关。 */
    @Value("${agent-platform.tool.actions.enabled:true}")
    private boolean enabled = true;

    /** 自定义动作文件（不存在就只用内置动作）。 */
    @Value("${agent-platform.tool.actions.custom-file:./data/tool-actions.json}")
    private String customFile = "./data/tool-actions.json";

    /** 单个动作的兜底超时（秒）—— 构建类动作用时远超普通工具调用。 */
    @Value("${agent-platform.tool.actions.timeout-seconds:600}")
    private long defaultTimeoutSeconds = 600L;

    @Lazy(false)
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!enabled) {
            log.info("[action] 项目动作已关闭（agent-platform.tool.actions.enabled=false）");
            return;
        }
        Path root = workspaceRoot();
        if (root == null) {
            log.info("[action] 工作区不可用，跳过项目动作注册"
                    + "（配置 agent-platform.agent.workspace.root 指向项目目录后重启生效）");
            return;
        }
        List<ActionDef> all = new ArrayList<>();
        all.addAll(fixedActions());
        all.addAll(probeActions(root));
        all.addAll(customActions());

        List<String> names = new ArrayList<>();
        for (ActionDef def : all) {
            toolRegistry.register(new ActionTool(def, runner, this::workspaceRoot), "action");
            names.add(def.name());
        }
        log.info("[action] 已注册 {} 个项目动作（工作区 {}）：{}",
                names.size(), root, String.join(", ", names));
    }

    /** 工作区根；未启用或不可用时返回 null。 */
    private Path workspaceRoot() {
        if (!enabled || workspace == null || !workspace.available()) {
            return null;
        }
        return workspace.root();
    }

    /**
     * 允许路径分隔符的参数模式。
     *
     * <p>比默认模式宽（多了 {@code /}），**只给只读动作用**（目前就是 {@code git_diff} 的 path）——
     * 查看某个子目录的改动是很自然的诉求，而排除掉空格与所有 shell 元字符之后
     * 依然没有注入面。写类动作不要放宽到它。</p>
     */
    private static final String PATH_PATTERN = "[A-Za-z0-9._/\\-]+";

    /** 与项目类型无关的固定动作。 */
    private List<ActionDef> fixedActions() {
        List<ActionDef> out = new ArrayList<>();
        out.add(ActionDef.of("git_status",
                "查看 git 状态：当前分支、已修改 / 新增 / 删除的文件。改动代码前后都值得跑一次。",
                List.of("git", "status", "--short", "--branch")));
        out.add(ActionDef.of("git_diff",
                "查看未提交的改动内容（完整 diff）。可用 path 限定到某个文件或目录。",
                List.of("git", "diff", "--", "{{path}}"),
                List.of(new ActionDef.Param("path", "限定文件或目录（相对项目根）", false, PATH_PATTERN)),
                120));
        out.add(ActionDef.of("git_log",
                "查看最近的提交记录（一行一条，最近 20 条）。",
                List.of("git", "log", "--oneline", "-n", "20")));
        return out;
    }

    /** 按探测出的项目类型提供"跑测试 / 构建"。 */
    private List<ActionDef> probeActions(Path root) {
        Optional<ProjectProbe.ProjectKind> detected = probe.detect(root);
        if (detected.isEmpty()) {
            log.info("[action] 未能识别项目类型（Maven/Gradle/npm/pytest/Go/Cargo），"
                    + "本次不提供 run_tests / run_build");
            return List.of();
        }
        ProjectProbe.ProjectKind kind = detected.get();
        List<ActionDef> out = new ArrayList<>();
        out.add(ActionDef.of("run_tests",
                "运行项目测试（已识别为 " + kind.label() + " 项目）。改完代码用它验证是否真的通过。",
                kind.testCommand(), List.of(), defaultTimeoutSeconds));
        out.add(ActionDef.of("run_build",
                "构建项目（已识别为 " + kind.label() + " 项目，跳过测试）。",
                kind.buildCommand(), List.of(), defaultTimeoutSeconds));
        return out;
    }

    /**
     * 用户自定义动作：从 JSON 文件加载。
     *
     * <p>格式（数组，每项一个动作）：</p>
     * <pre>{@code
     * [
     *   {
     *     "name": "run_codegen",
     *     "description": "按 schema 生成前端代码",
     *     "command": ["npm", "run", "codegen", "--", "{{target}}"],
     *     "timeoutSeconds": 120,
     *     "params": [
     *       { "name": "target", "description": "生成目标名", "required": true,
     *         "pattern": "[A-Za-z0-9._-]+" }
     *     ]
     *   }
     * ]
     * }</pre>
     *
     * <p><b>为什么要用户写这个文件</b>：动作集的灵活性来自它，而不是来自"放开让模型拼命令"。
     * 命令模板由**人**写一次、长期复用，模型只能选动作与填受限参数 ——
     * 这样既覆盖了"跑代码生成 / 跑迁移"这类项目特有动作，又没有把构造命令的能力交出去。</p>
     *
     * <p>文件缺失或解析失败**不影响内置动作**（只记 warn）——
     * 一个配置笔误不该让整条链路消失。</p>
     */
    private List<ActionDef> customActions() {
        if (customFile == null || customFile.isBlank()) {
            return List.of();
        }
        Path file = Path.of(customFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode arr = JsonUtils.toJsonNode(Files.readString(file, StandardCharsets.UTF_8));
            if (!arr.isArray()) {
                log.warn("[action] 自定义动作文件顶层应为数组，已忽略：{}", file);
                return List.of();
            }
            List<ActionDef> out = new ArrayList<>();
            for (JsonNode node : arr) {
                ActionDef def = parseCustom(node);
                if (def != null) {
                    out.add(def);
                }
            }
            log.info("[action] 从 {} 加载了 {} 个自定义动作", file, out.size());
            return out;
        } catch (Exception e) {
            log.warn("[action] 自定义动作文件解析失败（已忽略，内置动作不受影响）：{} —— {}", file, e.getMessage());
            return List.of();
        }
    }

    private ActionDef parseCustom(JsonNode node) {
        String name = node.path("name").asText("").trim();
        JsonNode cmd = node.path("command");
        if (name.isEmpty() || !cmd.isArray() || cmd.isEmpty()) {
            log.warn("[action] 忽略非法的自定义动作（缺 name 或 command）：{}", node);
            return null;
        }
        List<String> command = new ArrayList<>();
        cmd.forEach(c -> command.add(c.asText()));

        List<ActionDef.Param> params = new ArrayList<>();
        JsonNode ps = node.path("params");
        if (ps.isArray()) {
            for (JsonNode p : ps) {
                String pn = p.path("name").asText("").trim();
                if (pn.isEmpty()) {
                    continue;
                }
                String pattern = p.path("pattern").asText("");
                params.add(new ActionDef.Param(pn,
                        p.path("description").asText(""),
                        p.path("required").asBoolean(false),
                        pattern.isBlank() ? ActionDef.DEFAULT_PATTERN : pattern));
            }
        }
        long timeout = node.path("timeoutSeconds").asLong(defaultTimeoutSeconds);
        return new ActionDef(name, node.path("description").asText("自定义动作"),
                command, params, timeout <= 0 ? defaultTimeoutSeconds : timeout);
    }
}
