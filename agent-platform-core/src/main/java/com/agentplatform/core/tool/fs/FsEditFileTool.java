package com.agentplatform.core.tool.fs;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code fs_edit_file}：请求对工作区内文件做**精确串替换**（改代码的主力工具）。
 *
 * <h3>⚠️ 本工具**不会真的改文件**</h3>
 * 与 {@code fs_write_file} 同理：只校验 + 提交审批申请，真正的替换由
 * {@code ApprovalService} 在用户批准后调用 {@code FileWriteService} 完成。
 *
 * <h3>为什么用"精确串替换"而不是"按行号替换"</h3>
 * 行号会因上游任何一次改动而失效（用户可能同时在 IDE 里改这个文件），
 * 而"这段唯一文本"的语义稳定得多。业界主流（Aider / Cursor / Claude Code）都是这个流派。
 *
 * <p><b>提交前就做唯一性预检</b>：若 {@code oldString} 在文件中出现多次、且没开
 * {@code replaceAll}，这里就直接返回错误并附上出现次数 —— 让模型在**提交审批之前**
 * 就修正参数，而不是等用户点了批准才失败（那会让用户白批一次、还困惑为什么失败）。</p>
 */
@Slf4j
@Component("fs_edit_file")
@RequiredArgsConstructor
public class FsEditFileTool implements Tool {

    private final WorkspaceService workspace;
    private final ApprovalService approvalService;

    /** 摘要里展示的原/新文本片段长度上限（太长用户读不下去）。 */
    private static final int SNIPPET = 80;

    @Override
    public String name() {
        return "fs_edit_file";
    }

    @Override
    public String description() {
        return "申请对工作区内文件做精确串替换（修改代码的首选方式）。⚠️ 不会立即执行："
                + "会先提交待审批申请，用户确认后才真正修改。"
                + "oldString 必须在文件中唯一出现（否则请补足上下文使其唯一，或设 replaceAll=true）。"
                + "请先用 fs_read_file 读取内容，确保 oldString 与原文完全一致（含缩进）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonUtils.toJsonNode("""
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对工作区根目录的文件路径"
                    },
                    "oldString": {
                      "type": "string",
                      "description": "要被替换的原文本，必须与文件内容完全一致（含缩进与换行）"
                    },
                    "newString": {
                      "type": "string",
                      "description": "替换后的新文本；传空字符串表示删除这段内容"
                    },
                    "replaceAll": {
                      "type": "boolean",
                      "description": "为 true 时替换所有出现处（默认 false，要求唯一匹配）"
                    }
                  },
                  "required": ["path", "oldString", "newString"]
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        String oldString = args.path("oldString").asText("");
        // newString 允许为空串（表示删除这段），但不能缺字段 —— 用 has 区分"缺"与"空"
        if (!args.has("newString")) {
            return ToolResult.fail("缺少必需参数 newString（要删除这段内容请传空字符串）");
        }
        String newString = args.path("newString").asText("");
        boolean replaceAll = args.path("replaceAll").asBoolean(false);

        if (path.isBlank()) {
            return ToolResult.fail("缺少必需参数 path");
        }
        if (oldString.isEmpty()) {
            return ToolResult.fail("oldString 不能为空（新增内容请用 fs_write_file）");
        }
        if (oldString.equals(newString)) {
            return ToolResult.fail("oldString 与 newString 相同，无需编辑");
        }

        try {
            Path target = workspace.resolve(path, true);
            if (!Files.isRegularFile(target)) {
                return ToolResult.fail(path + " 不是普通文件");
            }
            if (!workspace.readableText(target)) {
                return ToolResult.fail(path + " 不是可读的文本文件");
            }
            if (Files.size(target) > workspace.maxReadBytes()) {
                return ToolResult.fail("文件过大（" + Files.size(target) + " 字节），请改用 fs_write_file 或分块处理");
            }

            // 唯一性预检：在**提交审批之前**就把"改哪一处"确定下来，
            // 免得用户批完才发现匹配有歧义。
            String text = Files.readString(target, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(text, oldString);
            if (occurrences == 0 && text.contains("\r\n") && !oldString.contains("\r\n")) {
                occurrences = countOccurrences(text, oldString.replace("\n", "\r\n"));
            }
            if (occurrences == 0) {
                return ToolResult.fail("在 " + path + " 中未找到 oldString。"
                        + "请先用 fs_read_file 读取当前内容（文件可能与你上次看到的不同），"
                        + "并确保 oldString 与原文**完全一致**（含缩进与换行）。");
            }
            if (occurrences > 1 && !replaceAll) {
                return ToolResult.fail("oldString 在 " + path + " 中出现了 " + occurrences
                        + " 次，无法确定改哪一处。请补足上下文使它唯一，或显式设置 replaceAll=true。");
            }

            String rel = workspace.relative(target);
            String summary = "在 " + rel + " 中" + (replaceAll ? "替换全部 " + occurrences + " 处" : "替换 1 处")
                    + "：把「" + snippet(oldString) + "」改为「" + snippet(newString) + "」";

            String approvalId = approvalService.submit(
                    ctx.tenantId(), ctx.agentId(), ctx.sessionId(), ctx.runId(),
                    RbacContext.userId(), name(), args, summary);

            // "就地确认"：阻塞等用户决定（界面上会立刻弹出确认框）；
            // 超时降级为两步式（工作流/定时任务这类无人场景走这条分支）。
            ApprovalService.WaitOutcome outcome = approvalService.awaitDecision(approvalId, ctx.tenantId());
            if (outcome.approved()) {
                return ToolResult.ok("用户已确认，修改已执行。" + (outcome.result() == null ? "" : "\n" + outcome.result()));
            }
            if (outcome.decided()) {
                return ToolResult.ok("""
                        用户**拒绝**了这次修改（申请 ID：%s），文件未被改动。
                        请不要重复提交同样的申请；如果用户之后想改，请他明确指示。""".formatted(approvalId));
            }
            return ToolResult.ok("""
                    已提交审批申请（ID：%s），**尚未修改任何内容**（等待用户确认超时）。
                    摘要：%s

                    请告知用户：可稍后在「工具审批」页面确认这项操作，确认后才会真正修改文件。
                    在用户确认之前，请不要重复提交同样的申请。""".formatted(approvalId, summary));
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] 提交编辑申请异常 {}: {}", path, e.getMessage());
            return ToolResult.fail("提交申请失败：" + e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 摘要片段：把换行显示为 ⏎，避免多行摘要把界面撑乱。 */
    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\r\n", "⏎").replace("\n", "⏎");
        return one.length() > SNIPPET ? one.substring(0, SNIPPET) + "…" : one;
    }
}
