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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code fs_write_file}：请求写入（新建或整体覆盖）工作区内的文件。
 *
 * <h3>⚠️ 本工具**不会真的写文件**</h3>
 * 它只做参数与路径校验，然后**提交一条审批申请**并返回"等待用户确认"。
 * 真正的写入由 {@code ApprovalService} 在用户批准后调用 {@code FileWriteService} 完成。
 *
 * <p>这样设计的理由见 {@code ApprovalService} 类注释：工具侧不需要知道批准状态，
 * 也就不需要令牌传递与"已批准则放行"的分支 —— 权限判断与执行动作彻底分离。</p>
 *
 * <p><b>为什么写能力必须配审批</b>：没有审批的写工具等于"一个能删任何文件的盒子"
 * （`backlog.md` 里对这条的表述是"只做工具不做审批是不可接受的"）。
 * 工作区约束挡的是"写到工作区外"，但**挡不住"把工作区里的东西改坏"** ——
 * 后者只能靠人看一眼再放行。</p>
 */
@Slf4j
@Component("fs_write_file")
@RequiredArgsConstructor
public class FsWriteFileTool implements Tool {

    private final WorkspaceService workspace;
    private final ApprovalService approvalService;

    @Override
    public String name() {
        return "fs_write_file";
    }

    @Override
    public String description() {
        return "申请写入工作区内的文件（新建或整体覆盖）。⚠️ 不会立即执行："
                + "会先提交一条待审批申请，用户确认后才真正写入。"
                + "修改已有文件的局部内容请优先用 fs_edit_file（更精确、审阅更容易）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonUtils.toJsonNode("""
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对工作区根目录的文件路径，如 src/main/java/Foo.java"
                    },
                    "content": {
                      "type": "string",
                      "description": "文件的完整内容（整体覆盖，不是在原有内容上追加）"
                    },
                    "createOnly": {
                      "type": "boolean",
                      "description": "为 true 时若文件已存在则拒绝，避免误覆盖（默认 false）"
                    }
                  },
                  "required": ["path", "content"]
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");
        boolean createOnly = args.path("createOnly").asBoolean(false);
        if (path.isBlank()) {
            return ToolResult.fail("缺少必需参数 path");
        }
        if (content.isEmpty()) {
            return ToolResult.fail("缺少必需参数 content（要清空文件请明确传入完整内容）");
        }

        try {
            // 校验（不写）：mustExist=false 允许新建；越界/跳过名单在这里就被挡掉
            Path target = workspace.resolve(path, false);
            if (Files.isDirectory(target)) {
                return ToolResult.fail(path + " 是一个目录，无法写入");
            }
            boolean exists = Files.exists(target);
            if (createOnly && exists) {
                return ToolResult.fail("文件已存在（createOnly=true 时不覆盖）：" + path);
            }
            int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > workspace.maxReadBytes() * 4) {
                return ToolResult.fail("内容过大（" + bytes + " 字节），请拆分为多次小改动");
            }

            String rel = workspace.relative(target);
            String summary = (exists ? "覆盖已有文件 " : "新建文件 ") + rel
                    + "（" + content.lines().count() + " 行 / " + content.length() + " 字符）";

            String approvalId = approvalService.submit(
                    ctx.tenantId(), ctx.agentId(), ctx.sessionId(), ctx.runId(),
                    RbacContext.userId(), name(), args, summary);

            // "就地确认"：阻塞等用户决定（界面上会立刻弹出确认框）。
            // 超时则降级为两步式 —— 工作流/定时任务这类无人场景走的就是这条分支。
            ApprovalService.WaitOutcome outcome = approvalService.awaitDecision(approvalId, ctx.tenantId());
            if (outcome.approved()) {
                return ToolResult.ok("用户已确认，操作已执行。" + (outcome.result() == null ? "" : "\n" + outcome.result()));
            }
            if (outcome.decided()) {
                // 用户明确拒绝：不是错误，但必须告诉模型别重试
                return ToolResult.ok("""
                        用户**拒绝**了这次写操作（申请 ID：%s），文件未被修改。
                        请不要重复提交同样的申请；如果用户之后想改，请他明确指示。""".formatted(approvalId));
            }
            // 超时降级
            return ToolResult.ok("""
                    已提交审批申请（ID：%s），**尚未写入任何内容**（等待用户确认超时）。
                    摘要：%s

                    请告知用户：可稍后在「工具审批」页面确认这项操作，确认后才会真正执行。
                    在用户确认之前，请不要重复提交同样的申请。""".formatted(approvalId, summary));
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] 提交写文件申请异常 {}: {}", path, e.getMessage());
            return ToolResult.fail("提交申请失败：" + e.getMessage());
        }
    }
}
