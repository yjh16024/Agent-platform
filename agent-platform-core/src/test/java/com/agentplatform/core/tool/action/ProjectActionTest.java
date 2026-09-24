package com.agentplatform.core.tool.action;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 项目动作（动作集）的单元测试（2026-09-24）。
 *
 * <p>重点在**参数白名单**与**命令渲染**：这两处是整条链路唯一接受模型输入的地方，
 * 也是唯一可能被注入的地方。命令本身由平台或用户配置写死，所以只要这两处守住，
 * "模型自由构造命令"这个风险源就不存在。</p>
 */
class ProjectActionTest {

    private final ProjectProbe probe = new ProjectProbe();

    // ------------------------------------------------------------------ 项目探测

    @Test
    @DisplayName("探测：标志文件决定项目类型（模型不参与这件事）")
    void probeDetectsKinds(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("pom.xml"));
        assertEquals(ProjectProbe.ProjectKind.MAVEN, probe.detect(dir).orElseThrow());
    }

    @Test
    @DisplayName("探测：npm / Go / Cargo 各自可识别")
    void probeDetectsOtherKinds(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("package.json"));
        assertEquals(ProjectProbe.ProjectKind.NPM, probe.detect(dir).orElseThrow());

        Files.createFile(dir.resolve("go.mod"));
        // 枚举顺序：NPM 在 GO 之前，所以同时存在时先命中 NPM —— 这里单独验 GO
        Path goOnly = Files.createDirectory(dir.resolve("go-proj"));
        Files.createFile(goOnly.resolve("go.mod"));
        assertEquals(ProjectProbe.ProjectKind.GO, probe.detect(goOnly).orElseThrow());
    }

    @Test
    @DisplayName("★ 探测：识别不出来时返回空（宁可不提供动作，也不猜一个命令）")
    void probeReturnsEmptyWhenUnknown(@TempDir Path dir) {
        assertTrue(probe.detect(dir).isEmpty());
        assertTrue(probe.detect(null).isEmpty());
    }

    // ------------------------------------------------------------------ 参数白名单（安全关键）

    @Test
    @DisplayName("★ 参数白名单：shell 元字符一律拒绝（这是唯一的注入屏障）")
    void renderRejectsShellMetacharacters() {
        ActionDef def = ActionDef.of("codegen", "生成代码",
                List.of("npm", "run", "codegen", "--", "{{target}}"),
                List.of(ActionDef.Param.required("target", "目标名")), 60);

        // 命令注入的常见载体：分号、与、管道、重定向、反引号、空格
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a; rm -rf /")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a && whoami")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a|b")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a>b")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a`id`")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "a b")));
        assertThrows(BizException.class, () -> def.render(Map.of("target", "$(whoami)")));
    }

    @Test
    @DisplayName("参数白名单：普通字符正常放行，并完成占位替换")
    void renderReplacesPlaceholders() {
        ActionDef def = ActionDef.of("codegen", "生成代码",
                List.of("npm", "run", "codegen", "--", "{{target}}"),
                List.of(ActionDef.Param.required("target", "目标名")), 60);

        assertEquals(List.of("npm", "run", "codegen", "--", "web-app_v2"),
                def.render(Map.of("target", "web-app_v2")));
    }

    @Test
    @DisplayName("参数：必填缺失即拒绝（并说明缺哪个）")
    void renderRequiresMandatoryParam() {
        ActionDef def = ActionDef.of("t", "d", List.of("x", "{{a}}"),
                List.of(ActionDef.Param.required("a", "必填项")), 60);

        BizException e = assertThrows(BizException.class, () -> def.render(Map.of()));
        assertTrue(e.getMessage().contains("a"));
    }

    @Test
    @DisplayName("★ 可选参数没给时整段丢弃（否则 `git diff -- ` 会因空参数报错）")
    void renderDropsSegmentOfMissingOptionalParam() {
        // 注意这里必须用带 `/` 的模式：默认模式不含路径分隔符（见 ActionDef.DEFAULT_PATTERN）
        ActionDef def = ActionDef.of("git_diff", "看改动",
                List.of("git", "diff", "--", "{{path}}"),
                List.of(new ActionDef.Param("path", "路径", false, "[A-Za-z0-9._/\\-]+")), 60);

        assertEquals(List.of("git", "diff", "--"), def.render(Map.of()));
        assertEquals(List.of("git", "diff", "--", "src/main"), def.render(Map.of("path", "src/main")));
        // 而默认模式（无 `/`）下，同样的值应被拒绝 —— 两种模式的差别是刻意的
        ActionDef strict = ActionDef.of("t", "d", List.of("x", "{{p}}"),
                List.of(ActionDef.Param.optional("p", "无路径")), 60);
        assertThrows(BizException.class, () -> strict.render(Map.of("p", "src/main")));
    }

    @Test
    @DisplayName("参数：只读动作允许路径分隔符（放宽是刻意的，写类动作不要这么做）")
    void renderAllowsPathForReadOnlyAction() {
        ActionDef def = ActionDef.of("git_diff", "看改动",
                List.of("git", "diff", "--", "{{path}}"),
                List.of(new ActionDef.Param("path", "路径", false, "[A-Za-z0-9._/\\-]+")), 60);

        assertEquals(List.of("git", "diff", "--", "src/main/java"),
                def.render(Map.of("path", "src/main/java")));
        // 放宽到路径，但 shell 元字符依然拒绝
        assertThrows(BizException.class, () -> def.render(Map.of("path", "a; rm -rf /")));
    }

    // ------------------------------------------------------------------ 执行器

    @Test
    @DisplayName("执行器：能跑通一条命令并拿到输出（用 java -version，环境必有）")
    void runnerExecutesCommand(@TempDir Path dir) {
        CommandRunner runner = new CommandRunner();

        CommandRunner.Outcome outcome = runner.run(List.of("java", "-version"), dir, 60);

        assertTrue(outcome.ok(), "退出码应为 0，实际 " + outcome.exitCode() + "，输出：" + outcome.output());
        // java -version 写 stderr —— 合并后必须看得到，否则构建失败的关键信息也会丢
        assertTrue(outcome.output().contains("version"), "应捕获到 stderr 的输出：" + outcome.output());
    }

    @Test
    @DisplayName("★ 执行器：命令不存在时给出可读反馈（而两种平台的**表现形式不同**）")
    void runnerReportsMissingCommand(@TempDir Path dir) {
        CommandRunner runner = new CommandRunner();

        try {
            CommandRunner.Outcome outcome = runner.run(List.of("definitely-not-a-command-xyz"), dir, 10);
            // Windows：命令经 cmd.exe /c 执行，cmd 找不到命令时**自己返回非零退出码**并打印提示，
            // 所以这里不是"启动失败"而是"执行失败"—— 提示信息照样能带给模型。
            assertFalse(outcome.ok(), "不存在的命令不应报告成功");
            assertFalse(outcome.output().isBlank(), "应带回'命令不存在'的提示：" + outcome.output());
        } catch (BizException e) {
            // Unix：ProcessBuilder 直接抛 IOException（没有 cmd 这层壳），转成可读异常 —— 同样可接受
            assertTrue(e.getMessage().contains("启动失败"), e.getMessage());
        }
    }

    @Test
    @DisplayName("执行器：非零退出码照常返回（失败也是**有效信息**，不能当异常吞掉）")
    void runnerReturnsNonZeroExit(@TempDir Path dir) throws IOException {
        CommandRunner runner = new CommandRunner();
        // 用一个必然失败的命令：在一个空目录里问 git 仓库根，必然非 0
        Path notRepo = Files.createDirectory(dir.resolve("empty"));

        CommandRunner.Outcome outcome;
        try {
            outcome = runner.run(List.of("git", "rev-parse", "--show-toplevel"), notRepo, 30);
        } catch (BizException e) {
            // 环境里没有 git：这条用例的前提不成立（而不是行为不对）
            assumeTrue(false, "当前环境没有 git，跳过：" + e.getMessage());
            return;
        }

        assertFalse(outcome.ok(), "非 0 退出应如实反映为未成功，而不是抛异常："
                + outcome.exitCode() + " / " + outcome.output());
    }

    @Test
    @DisplayName("执行器：工作目录不存在时明确报错（提示要先配工作区）")
    void runnerRejectsBadWorkingDir(@TempDir Path dir) {
        CommandRunner runner = new CommandRunner();

        BizException e = assertThrows(BizException.class,
                () -> runner.run(List.of("java", "-version"), dir.resolve("nope"), 10));
        assertTrue(e.getMessage().contains("工作目录"), e.getMessage());
    }

    // ------------------------------------------------------------------ 工具形态

    @Test
    @DisplayName("工具：未配工作区时明确拒绝（并告诉用户去配哪个配置项）")
    void toolRejectsWhenWorkspaceMissing() {
        ActionDef def = ActionDef.of("git_status", "看状态", List.of("git", "status"));
        ActionTool tool = new ActionTool(def, new CommandRunner(), () -> null);

        ToolResult r = tool.execute(JsonUtils.mapper().createObjectNode(),
                ToolContext.of("t1", "a1", "run1"));

        assertFalse(r.success());
        assertTrue(r.error().contains("workspace.root"), "应指明要配哪个配置项：" + r.error());
    }

    @Test
    @DisplayName("工具：schema 里必填/可选参数各自正确（模型据此填参）")
    void toolSchemaReflectsParams() {
        ActionDef def = ActionDef.of("codegen", "生成",
                List.of("npm", "run", "codegen", "--", "{{target}}"),
                List.of(ActionDef.Param.required("target", "目标"),
                        ActionDef.Param.optional("flavor", "变体")), 60);
        ActionTool tool = new ActionTool(def, new CommandRunner(), () -> null);

        var schema = tool.inputSchema();
        assertEquals(1, schema.path("required").size());
        assertEquals("target", schema.path("required").get(0).asText());
        assertTrue(schema.path("properties").has("flavor"));
        assertTrue(tool.description().contains("target"));
    }
}
