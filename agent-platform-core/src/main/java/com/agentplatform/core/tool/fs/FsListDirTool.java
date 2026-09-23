package com.agentplatform.core.tool.fs;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.core.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code fs_list_dir}：列出一层目录内容（目录在前，带大小）。
 *
 * <p>与 {@code fs_glob} 的分工：本工具是"站在某一层看有什么"，
 * glob 是"按模式在整个工作区里找"。模型通常先用 list 看项目根，
 * 再用 glob 找具体文件 —— 与人在新项目里的动作顺序一致。</p>
 *
 * <p>刻意**只列一层**（不递归）：递归列目录在任何稍大的项目里都会产生几千行输出，
 * 把上下文一次冲掉，反而让模型看不见关键信息。要看多层就该用 glob。</p>
 */
@Slf4j
@Component("fs_list_dir")
@RequiredArgsConstructor
public class FsListDirTool implements Tool {

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "fs_list_dir";
    }

    @Override
    public String description() {
        return "列出工作区内某个目录的内容（只列一层，目录在前）。"
                + "path 留空表示工作区根目录。需要按模式在多层级里找文件请用 fs_glob。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonUtils.toJsonNode("""
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对工作区根目录的目录路径；留空或 . 表示根目录"
                    }
                  },
                  "required": []
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        try {
            Path dir = workspace.resolve(path, true);
            if (!Files.isDirectory(dir)) {
                return ToolResult.fail(path + " 不是目录（若这是一个文件，请用 fs_read_file）");
            }

            List<Path> entries = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(entries::add);
            }
            // 目录在前、再按名字排序：模型一眼就能看出项目结构层次
            entries.sort((a, b) -> {
                boolean da = Files.isDirectory(a);
                boolean db = Files.isDirectory(b);
                if (da != db) {
                    return da ? -1 : 1;
                }
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });

            StringBuilder sb = new StringBuilder();
            sb.append("目录：").append(workspace.relative(dir)).append("\n\n");
            int shown = 0;
            int skipped = 0;
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (workspace.shouldSkipDir(p)) {
                        skipped++;
                        continue;
                    }
                    if (shown >= workspace.maxEntries()) {
                        break;
                    }
                    sb.append("[DIR]  ").append(name).append("/\n");
                    shown++;
                } else {
                    if (shown >= workspace.maxEntries()) {
                        break;
                    }
                    sb.append("       ").append(name);
                    try {
                        sb.append("  (").append(humanSize(Files.size(p))).append(")");
                    } catch (Exception ignored) {
                        // 取不到大小就只显示名字
                    }
                    sb.append('\n');
                    shown++;
                }
            }
            if (shown == 0) {
                return ToolResult.ok("目录：" + workspace.relative(dir) + "（空）");
            }
            if (entries.size() > shown + skipped) {
                sb.append("\n… 条目过多，仅显示前 ").append(shown).append(" 项（用 fs_glob 精确查找）");
            }
            if (skipped > 0) {
                sb.append("\n（已隐藏 ").append(skipped).append(" 个跳过名单内的目录：")
                        .append(String.join(" / ", workspace.skipDirs())).append("）");
            }
            return ToolResult.ok(sb.toString());
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] 列目录异常 {}: {}", path, e.getMessage());
            return ToolResult.fail("列目录失败：" + e.getMessage());
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return (bytes / 1024 / 1024) + " MB";
    }
}
