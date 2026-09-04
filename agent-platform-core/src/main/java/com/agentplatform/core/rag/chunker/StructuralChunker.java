package com.agentplatform.core.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切分器（策略模式）。
 * <p>
 * 识别 Markdown 标题（#/##/###）、表格边界、代码块，按文档结构切分，
 * 标题信息注入 chunk metadata 供引用溯源。
 * </p>
 */
@Component("structural")
public class StructuralChunker extends Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");

    @Override
    public String strategy() {
        return "structural";
    }

    @Override
    protected List<ChunkSegment> doSplit(String text, ChunkConfig config) {
        List<ChunkSegment> segments = new ArrayList<>();
        String currentHeading = "";
        StringBuilder buffer = new StringBuilder();
        int seq = 0;

        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                // 遇到新标题，先落盘上段
                if (buffer.length() > 0) {
                    segments.add(buildSegment(seq++, currentHeading, buffer.toString()));
                    buffer.setLength(0);
                }
                currentHeading = line.trim();
            }
            buffer.append(line).append('\n');
            if (buffer.length() >= config.chunkSize()) {
                segments.add(buildSegment(seq++, currentHeading, buffer.toString()));
                buffer.setLength(0);
            }
        }
        if (buffer.length() > 0) {
            segments.add(buildSegment(seq, currentHeading, buffer.toString()));
        }
        return segments;
    }

    private ChunkSegment buildSegment(int seq, String heading, String content) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!heading.isBlank()) {
            meta.put("heading", heading.replaceAll("^#+\\s*", ""));
        }
        // 检测表格
        if (content.contains("|---") || content.contains("+---")) {
            meta.put("table", true);
        }
        return ChunkSegment.of(seq, content.trim(), meta);
    }
}