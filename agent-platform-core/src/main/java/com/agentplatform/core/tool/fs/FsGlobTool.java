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
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code fs_glob}：按模式在**整个工作区**里找文件（支持 {@code **}）。
 *
 * <h3>与 grep 的分工（两者是主力导航手段，不是可选装饰）</h3>
 * <ul>
 *   <li>{@code glob} 回答"**哪些文件**叫这个名字" —— 如 {@code **&#47;*Controller.java}；</li>
 *   <li>{@code grep} 回答"**哪段代码**里有这个符号" —— 如 {@code FsReadFileTool}。</li>
 * </ul>
 * 二者配合就能在几轮内定位到目标，**不需要给代码建 embedding 索引**
 * （索引会过期、跨语言效果差 —— 见 `backlog.md` 第五节的一节专门结论）。
 *
 * <h3>为什么自己走文件树而不是 Files.walk + filter</h3>
 * 因为要在**进入目录前**就跳过 {@code node_modules} / {@code .git} 这类目录。
 * {@code Files.walk + filter} 是"先遍历出来再筛掉"，一个几十万文件的依赖目录
 * 已经足够让响应慢到不可用；用 {@code FileVisitor} 可以 {@code SKIP_SUBTREE} 直接不进。
 */
@Slf4j
@Component("fs_glob")
@RequiredArgsConstructor
public class FsGlobTool implements Tool {

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "fs_glob";
    }

    @Override
    public String description() {
        return "按文件名模式在工作区里查找文件，支持 glob 通配：* 匹配单层，** 匹配任意层。"
                + "例如 **/*Controller.java、src/**/*.ts、**/pom.xml。只返回匹配的文件路径。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonUtils.toJsonNode("""
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "glob 模式，如 **/*.java 或 src/**/pom.xml"
                    },
                    "path": {
                      "type": "string",
                      "description": "限定搜索的子目录（相对工作区根）；留空表示整个工作区"
                    }
                  },
                  "required": ["pattern"]
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String pattern = args.path("pattern").asText("");
        String subPath = args.path("path").asText("");
        if (pattern.isBlank()) {
            return ToolResult.fail("缺少必需参数 pattern");
        }
        try {
            Path base = workspace.resolve(subPath, true);
            if (!Files.isDirectory(base)) {
                return ToolResult.fail(subPath + " 不是目录");
            }

            // 只按文件名匹配（与 shell 的 glob 语义对齐：**/ 已经在遍历里天然生效）
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (Exception e) {
                return ToolResult.fail("非法的 glob 模式：" + pattern + "（" + e.getMessage() + "）");
            }

            List<String> hits = new ArrayList<>();
            int[] scanned = {0};
            int limit = workspace.maxEntries();

            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 关键：进不进这个目录在**遍历前**就决定，避免把依赖目录整个走一遍
                    if (!dir.equals(base) && workspace.shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    scanned[0]++;
                    String name = file.getFileName().toString();
                    // 同一条模式要能匹配"纯文件名"和"相对路径"两种写法：
                    //   *.java        → 只对文件名成立
                    //   src/**/*.java → 只对相对路径成立
                    if (matcher.matches(file.getFileName()) || matcher.matches(workspace.root().relativize(file))) {
                        hits.add(workspace.relative(file));
                        if (hits.size() >= limit) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // 单个文件读不了不中断整体
                }
            });

            if (hits.isEmpty()) {
                return ToolResult.ok("没有匹配 " + pattern + " 的文件（已扫描 " + scanned[0] + " 个文件）");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("匹配 ").append(pattern).append(" 的文件共 ").append(hits.size()).append(" 个");
            if (hits.size() >= limit) {
                sb.append("（已达上限 ").append(limit).append("，结果可能不全，请用更精确的模式）");
            }
            sb.append("：\n");
            hits.forEach(h -> sb.append(h).append('\n'));
            return ToolResult.ok(sb.toString());
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] glob 异常 {}: {}", pattern, e.getMessage());
            return ToolResult.fail("查找失败：" + e.getMessage());
        }
    }
}
