package com.agentplatform.framework.api;

/**
 * 一轮输入的内容块。
 *
 * <p>为什么不用 {@code String}：平台已有 {@code parts[]} 多模态消息模型，
 * 框架若是纯文本入参，平台迁移时必然再加一次破坏性变更。这里只保留两种**平台无关**的形态：
 * <ul>
 *   <li>{@link Text}：直接可读的文本</li>
 *   <li>{@link Ref}：以宿主侧资源 id 引用附件（图片 / 文件），<b>框架不碰存储</b>，由宿主解析</li>
 * </ul>
 */
public sealed interface InputPart permits InputPart.Text, InputPart.Ref {

    record Text(String text) implements InputPart {
        public Text {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Text 内容不能为空");
            }
        }
    }

    /**
     * @param kind 资源类型（如 {@code image} / {@code file}），由宿主约定，框架只做透传
     * @param id   宿主侧资源 id（不是 URL，也不是路径——框架不假定存储实现）
     */
    record Ref(String kind, String id) implements InputPart {
        public Ref {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("kind 必填");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id 必填");
            }
        }
    }
}
