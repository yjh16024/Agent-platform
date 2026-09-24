package com.agentplatform.core.tool.action;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把一个 {@link ActionDef} 暴露成平台工具。
 *
 * <p>每个动作注册**一个独立工具**（而不是"一个 run_action 工具 + 动作名参数"）：
 * 这样工具名就是动作名、描述可以写得具体，模型在选择时看到的是
 * "跑测试" / "看 git 状态" 这样的能力项，而不是一个需要它自己记住动作清单的万能入口 ——
 * 与平台其它工具（{@code fs_read_file} / {@code fs_grep} …）的形态也一致。</p>
 *
 * <p>执行前会检查两条前置：工作区可用、且当前工作目录确实是工作区根
 * （动作命令只允许在**项目根**执行，不接受模型指定目录 —— 那等于把范围控制交出去了）。</p>
 */
@Slf4j
public class ActionTool implements Tool {

    private final ActionDef def;
    private final CommandRunner runner;
    /**
     * 工作区根提供者（不可用时返回 {@code null}）。
     *
     * <p>用 {@code Supplier} 而不是直接依赖 {@code WorkspaceService}：
     * 动作工具是**动态创建**的（每个动作一个实例），测试里也要能绕开 Spring 装配直接构造。
     * 而"根目录取不到"本身就是要处理的一种正常状态（工作区没配）。</p>
     */
    private final java.util.function.Supplier<Path> workspaceRoot;

    public ActionTool(ActionDef def, CommandRunner runner, java.util.function.Supplier<Path> workspaceRoot) {
        this.def = def;
        this.runner = runner;
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public String name() {
        return def.name();
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(def.description());
        sb.append("（在项目根目录执行，命令由平台预设）");
        if (!def.params().isEmpty()) {
            sb.append(" 参数：");
            for (ActionDef.Param p : def.params()) {
                sb.append(p.name()).append(p.required() ? "(必填)" : "(可选)")
                        .append("=").append(p.description()).append("；");
            }
        }
        return sb.toString();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");
        for (ActionDef.Param p : def.params()) {
            ObjectNode one = props.putObject(p.name());
            one.put("type", "string");
            one.put("description", p.description()
                    + "（只允许普通字符，不能含空格或 shell 符号）");
            if (p.required()) {
                required.add(p.name());
            }
        }
        if (def.params().isEmpty()) {
            // 无参动作：显式给空 properties，避免厂商侧对 schema 挑剔
            schema.putArray("required");
        }
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        // ① 工作区必须已启用（动作只在工作区里执行）
        Path root = workspaceRoot == null ? null : workspaceRoot.get();
        if (root == null) {
            return ToolResult.fail("动作未启用：请先配置 agent-platform.agent.workspace.root 指向你的项目目录");
        }

        // ② 收集参数（只取声明过的名字，多余的一律忽略）
        Map<String, String> values = new LinkedHashMap<>();
        for (ActionDef.Param p : def.params()) {
            JsonNode v = args == null ? null : args.get(p.name());
            if (v != null && !v.isNull()) {
                values.put(p.name(), v.isTextual() ? v.asText() : v.toString());
            }
        }

        // ③ 渲染（参数白名单在这里生效）→ 执行
        try {
            var commandLine = def.render(values);
            CommandRunner.Outcome outcome = runner.run(commandLine, root, def.timeoutSeconds());
            return describe(outcome);
        } catch (BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[action] 执行异常 {}：{}", def.name(), e.getMessage());
            return ToolResult.fail("动作执行失败：" + e.getMessage());
        }
    }

    /**
     * 把执行结果转成给模型看的文本。
     *
     * <p>失败时**保留原始输出**而不是换成一句"命令失败" —— 构建/测试的输出里
     * 就有"哪个用例挂了、哪一行编译不过"，那正是模型接下来要用的信息。
     * 这一步的产物质量直接决定它能不能自己往下修。</p>
     */
    private ToolResult describe(CommandRunner.Outcome outcome) {
        String header = "动作：" + def.name() + "　耗时：" + outcome.elapsedMs() + "ms";
        if (outcome.timedOut()) {
            return ToolResult.fail(header + "\n执行超时（" + def.timeoutSeconds()
                    + " 秒）已被强制终止。可能是命令在等待输入、或项目构建特别慢；"
                    + "可让用户调大 agent-platform.tool.actions.timeout-seconds。\n\n"
                    + outcome.output());
        }
        String body = outcome.output() == null || outcome.output().isBlank()
                ? "(无输出)" : outcome.output();
        if (outcome.ok()) {
            return ToolResult.ok(header + "　退出码：0\n\n" + body);
        }
        return ToolResult.ok(header + "　退出码：" + outcome.exitCode()
                + "（**未能成功**，请根据下面的输出判断原因）\n\n" + body);
    }
}
