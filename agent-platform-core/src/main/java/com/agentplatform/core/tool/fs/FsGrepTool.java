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
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code fs_grep}：在工作区文件内容里正则搜索（**定位代码的主力工具**）。
 *
 * <h3>为什么这是代理式搜索（agentic search）而不是 embedding 索引</h3>
 * 给代码库做向量索引看起来"更智能"，但对**会变化的代码**是错的选择：索引落后于代码
 * 就会给出错误位置，而且跨语言效果差、维护成本高。业界现在的共识是让模型自己
 * grep → 读文件 → 再 grep（Aider 的 repo map 是另一条路，但那是给全局骨架用的）。
 * 本工具的职责就是把 grep 做得**够准够省**。
 *
 * <h3>"够省"体现在三处限制</h3>
 * <ul>
 *   <li><b>命中条数上限</b>：一个常见符号在大型项目里可能有上千处命中，
 *       全量返回等于把上下文一次冲掉；</li>
 *   <li><b>单行截断</b>：压缩后的 JS 一行几万字符，原样返回毫无用处；</li>
 *   <li><b>按文件聚合计数</b>：先告诉模型"这几个文件命中最多"，
 *       它就能优先读最相关的那个，而不是盲目逐个打开。</li>
 * </ul>
 *
 * <p>输出格式 {@code 相对路径:行号: 内容} —— 与 {@code fs_read_file} 的行号体系一致，
 * 模型可以直接把行号代入后续读取，形成"grep 定位 → read 精读"的自然闭环。</p>
 */
@Slf4j
@Component("fs_grep")
@RequiredArgsConstructor
public class FsGrepTool implements Tool {

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "fs_grep";
    }

    @Override
    public String description() {
        return "在工作区文件内容里用正则搜索，返回「文件:行号: 内容」。"
                + "定位代码符号、字符串、配置项时优先用它，比逐个文件读高效得多。"
                + "可用 path 限定目录、glob 限定文件类型（如 **/*.java）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonUtils.toJsonNode("""
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "正则表达式，如 FsReadFileTool 或 class\\s+\\w+Service"
                    },
                    "path": {
                      "type": "string",
                      "description": "限定搜索的子目录（相对工作区根）；留空表示整个工作区"
                    },
                    "glob": {
                      "type": "string",
                      "description": "文件名过滤模式，如 **/*.java；留空表示仅按文本文件判断"
                    },
                    "ignoreCase": {
                      "type": "boolean",
                      "description": "是否忽略大小写，默认 false"
                    }
                  },
                  "required": ["pattern"]
                }
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        String rawPattern = args.path("pattern").asText("");
        String subPath = args.path("path").asText("");
        String fileGlob = args.path("glob").asText("");
        boolean ignoreCase = args.path("ignoreCase").asBoolean(false);
        if (rawPattern.isBlank()) {
            return ToolResult.fail("缺少必需参数 pattern");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(rawPattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.fail("非法正则：" + e.getMessage());
        }

        PathMatcher nameMatcher = null;
        if (!fileGlob.isBlank()) {
            try {
                nameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
            } catch (Exception e) {
                return ToolResult.fail("非法的 glob 过滤：" + fileGlob);
            }
        }

        try {
            Path base = workspace.resolve(subPath, true);
            if (!Files.isDirectory(base)) {
                return ToolResult.fail(subPath + " 不是目录");
            }

            List<String> matches = new ArrayList<>();
            List<String> fileOrder = new ArrayList<>();      // 命中文件按首次出现顺序
            int[] counts = {0};
            int maxMatches = workspace.maxMatches();
            int maxLineChars = workspace.maxLineChars();
            int[] scannedFiles = {0};
            final PathMatcher fm = nameMatcher;

            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(base) && workspace.shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxMatches) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (fm != null
                            && !fm.matches(file.getFileName())
                            && !fm.matches(workspace.root().relativize(file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!workspace.readableText(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (Files.size(file) > workspace.maxReadBytes() * 4) {
                            // 超大文件跳过：grep 它既慢又几乎不可能有我们要的东西
                            return FileVisitResult.CONTINUE;
                        }
                        scannedFiles[0]++;
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        String rel = workspace.relative(file);
                        boolean firstInFile = true;
                        for (int i = 0; i < lines.size() && matches.size() < maxMatches; i++) {
                            String line = lines.get(i);
                            Matcher m = pattern.matcher(line);
                            if (!m.find()) {
                                continue;
                            }
                            if (firstInFile) {
                                fileOrder.add(rel);
                                firstInFile = false;
                            }
                            counts[0]++;
                            String shown = line.length() > maxLineChars
                                    ? line.substring(0, maxLineChars) + "…" : line;
                            // grep 的行号从 1 开始，与 fs_read_file 一致
                            matches.add(rel + ":" + (i + 1) + ": " + shown.trim());
                        }
                    } catch (Exception e) {
                        // 单个文件读不了（编码/权限）不该中断整体搜索
                        log.debug("[fs] grep 跳过 {}：{}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) {
                return ToolResult.ok("没有匹配 " + rawPattern + " 的内容（已搜索 "
                        + scannedFiles[0] + " 个文本文件）");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("匹配 ").append(rawPattern).append(" 共 ").append(matches.size()).append(" 处");
            if (matches.size() >= maxMatches) {
                sb.append("（已达上限 ").append(maxMatches).append("，结果不全，请细化 pattern 或限定 path）");
            }
            sb.append("，涉及 ").append(fileOrder.size()).append(" 个文件");
            if (fileOrder.size() > 1) {
                // 把最可能有用的信息前置：模型据此决定先读哪个文件
                sb.append("（").append(String.join(", ", fileOrder.subList(0, Math.min(5, fileOrder.size()))));
                if (fileOrder.size() > 5) {
                    sb.append(" …");
                }
                sb.append("）");
            }
            sb.append("\n\n");
            matches.forEach(m -> sb.append(m).append('\n'));
            return ToolResult.ok(sb.toString());
        } catch (com.agentplatform.common.exception.BizException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[fs] grep 异常 {}: {}", rawPattern, e.getMessage());
            return ToolResult.fail("搜索失败：" + e.getMessage());
        }
    }
}
