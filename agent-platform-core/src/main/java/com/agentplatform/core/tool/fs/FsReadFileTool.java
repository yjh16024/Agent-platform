package com.agentplatform.core.tool.fs;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code fs_read_file}：读取工作区内的文本文件（**输出带行号**）。
 *
 * <h3>为什么必须带行号</h3>
 * 这是与各家编程智能体（Claude Code / Cursor / Aider）对齐的做法，原因很实在：
 * <ul>
 *   <li>模型引用代码时会说"第 42 行"，没有行号它只能靠复述原文定位，容易说错；</li>
 *   <li>后续的编辑工具需要**精确定位**，而"行号 + 内容"比"一段文本"更不容易歧义
 *       （同一段代码在文件里出现多次时，纯文本匹配会改错地方）；</li>
 *   <li>读文件与 grep 的输出格式一致（grep 也是 {@code 行号:内容}），模型不用学两套。</li>
 * </ul>
 *
 * <p>行号格式刻意用 {@code 空格填充 + |} 而不是 {@code 行号:内容} ——
 * 后者在内容本身以数字开头时会有歧义，也让模型很难复制出干净的原文。</p>
 *
 * <h3>为什么支持 offset / limit</h3>
 * 一个几千行的文件一次性塞进上下文会挤掉别的一切。允许分段读之后，模型可以
 * "先看 1–200 行判断结构，再跳过去读关心的一段"，这与人类翻代码的方式一致。
 */
@Slf4j
@Component("fs_read_file")
@RequiredArgsConstructor
public class FsReadFileTool implements Tool {

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "fs_read_file";
    }

    @Override
    public String description() {
        return "读取工作区内的文本文件内容，输出带行号。"
                + "大文件请用 offset/limit 分段读取（例如先读前 200 行判断结构）。"
                + "路径必须是相对工作区根目录的相对路径。";
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
                    "offset": {
                      "type": "integer",
                      "description": "起始行号（从 1 开始，默认 1）"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "最多读取的行数（默认 400，上限 2000）"
                    }
                  },
                  "required": ["path"]
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        if (path.isBlank()) {
            return ToolResult.fail("缺少必需参数 path");
        }
        int offset = args.path("offset").asInt(1);
        int limit = args.path("limit").asInt(400);
        if (offset < 1) {
            offset = 1;
        }
        limit = Math.max(1, Math.min(limit, 2000));

        try {
            Path file = workspace.resolve(path, true);
            if (Files.isDirectory(file)) {
                return ToolResult.fail(path + " 是一个目录，请用 fs_list_dir");
            }
            if (!workspace.readableText(file)) {
                return ToolResult.fail(path + " 不是可读的文本文件（二进制或不在文本类型白名单内）");
            }
            long size = Files.size(file);
            if (size > workspace.maxReadBytes()) {
                return ToolResult.fail("文件过大（" + size + " 字节，上限 " + workspace.maxReadBytes()
                        + "）。请用 fs_grep 定位关键片段，或改用 offset/limit 读一小段。");
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int total = lines.size();
            if (offset > total) {
                return ToolResult.fail("起始行号 " + offset + " 超出文件总行数 " + total);
            }
            int end = Math.min(total, offset - 1 + limit);
            StringBuilder sb = new StringBuilder();
            sb.append("文件：").append(workspace.relative(file))
                    .append("（共 ").append(total).append(" 行");
            if (offset > 1 || end < total) {
                sb.append("，本次显示第 ").append(offset).append("–").append(end).append(" 行");
            }
            sb.append("）\n\n");

            int width = String.valueOf(end).length();
            for (int i = offset; i <= end; i++) {
                String line = lines.get(i - 1);
                sb.append(pad(i, width)).append(" | ").append(line).append('\n');
            }
            // 尾行提示放在正文之后（放前面模型会当成文件内容）
            if (end < total) {
                sb.append("\n… 还有 ").append(total - end).append(" 行未显示（用 offset=").append(end + 1)
                        .append(" 继续读）");
            }
            return ToolResult.ok(sb.toString());
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (IOException e) {
            return ToolResult.fail("读取失败：" + e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] 读文件异常 {}: {}", path, e.getMessage());
            return ToolResult.fail("读取失败：" + e.getMessage());
        }
    }

    private static String pad(int n, int width) {
        String s = String.valueOf(n);
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }

    /** 供测试断言用：把内容渲染成带行号的文本。 */
    static String renderLines(List<String> lines, int offset, int limit) {
        int end = Math.min(lines.size(), offset - 1 + limit);
        List<String> out = new ArrayList<>();
        for (int i = offset; i <= end; i++) {
            out.add(i + " | " + lines.get(i - 1));
        }
        return String.join("\n", out);
    }
}
