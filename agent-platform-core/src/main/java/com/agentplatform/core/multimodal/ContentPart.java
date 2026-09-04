package com.agentplatform.core.multimodal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 多模态消息内容块（统一 parts[] 模型，JDK 21 密封接口）。
 * <p>
 * 跨模型归一：text / image / audio / video / file / tool_result 六类，
 * 通过 Switch 模式匹配获得编译期穷尽检查。
 * </p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * String desc = switch (part) {
 *     case TextPart t        -> "文本: " + t.text();
 *     case ImagePart i       -> "图片: " + i.fileId();
 *     case AudioPart a       -> "音频: " + a.fileId();
 *     case VideoPart v       -> "视频: " + v.fileId();
 *     case FilePart f        -> "文件: " + f.fileName();
 *     case ToolResultPart tr -> "工具结果: " + tr.toolName();
 * };
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentPart.TextPart.class, name = "text"),
        @JsonSubTypes.Type(value = ContentPart.ImagePart.class, name = "image"),
        @JsonSubTypes.Type(value = ContentPart.AudioPart.class, name = "audio"),
        @JsonSubTypes.Type(value = ContentPart.VideoPart.class, name = "video"),
        @JsonSubTypes.Type(value = ContentPart.FilePart.class, name = "file"),
        @JsonSubTypes.Type(value = ContentPart.ToolResultPart.class, name = "tool_result")
})
public sealed interface ContentPart
        permits ContentPart.TextPart, ContentPart.ImagePart, ContentPart.AudioPart,
        ContentPart.VideoPart, ContentPart.FilePart, ContentPart.ToolResultPart {

    /** 文本块。 */
    record TextPart(String text) implements ContentPart {
    }

    /** 图片块。 */
    record ImagePart(String fileId, String url, String mimeType) implements ContentPart {
    }

    /** 音频块。 */
    record AudioPart(String fileId, String url) implements ContentPart {
    }

    /** 视频块。 */
    record VideoPart(String fileId, String url) implements ContentPart {
    }

    /** 文件块（PDF/Docx 等）。 */
    record FilePart(String fileId, String fileName, String mimeType) implements ContentPart {
    }

    /** 工具结果块。 */
    record ToolResultPart(String toolName, Object output) implements ContentPart {
    }

    /**
     * 便捷判断：是否为纯文本消息。
     */
    static boolean isTextOnly(List<ContentPart> parts) {
        return parts == null || parts.stream().allMatch(p -> p instanceof TextPart);
    }
}