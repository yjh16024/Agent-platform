package com.agentplatform.framework.api;

/**
 * 附件解析 SPI（R13 之二）。
 *
 * <p>{@link InputPart.Ref} 只带 {@code kind} + {@code id}，框架**不碰存储**；
 * 真正把它变成「可发给模型的东西」是宿主的责任：文本附件读成文本，
 * 图片转成 data URL。</p>
 *
 * <p>为什么不在框架里做文件读取：那会把本地磁盘 / MinIO / 对象存储的选择权
 * 从宿主手里拿走，嵌入方也就没法用自己已有的文件服务。</p>
 */
@FunctionalInterface
public interface AttachmentResolver {

    /**
     * @return 解析结果。**不要用 {@code null} 表示「读取失败」**——失败请返回
     *         {@link ResolvedAttachment#failed}，否则用户会以为附件被静默丢弃。
     *         {@code null} 只用于「明确不想暴露该附件」（例如调用方没有权限提及它）。
     */
    ResolvedAttachment resolve(String kind, String id);

    /**
     * 解析后的附件：文本 / 图片 URL / 失败原因，三者至多一个非空。
     *
     * <p><b>为什么有「失败」这一态</b>：早期的实现解析失败返回 {@code null}，
     * 运行时静默丢弃——用户拖了文件却什么也没发生，比报错更难排查。
     * 现在失败必须**可见**：原因会被写进本轮输入（模型能看到并如实回答"这个文件读不了"），
     * 同时宿主日志里有 WARN。既不静默丢数据，也不因为一个坏附件把整轮对话打死。</p>
     *
     * @param text     文本内容（会拼进用户消息）
     * @param imageUrl 图片地址（data URL 或 http），会作为多模态图片随请求下发
     * @param failure  失败原因（非空表示这个附件没解析成功）
     */
    record ResolvedAttachment(String kind, String text, String imageUrl, String failure) {

        public static ResolvedAttachment text(String kind, String text) {
            return new ResolvedAttachment(kind, text, null, null);
        }

        public static ResolvedAttachment image(String kind, String imageUrl) {
            return new ResolvedAttachment(kind, null, imageUrl, null);
        }

        /** 失败但可继续：原因会进入本轮输入，让模型与用户都看得见。 */
        public static ResolvedAttachment failed(String kind, String reason) {
            return new ResolvedAttachment(kind, null, null,
                    (reason == null || reason.isBlank()) ? "未知原因" : reason);
        }

        public boolean isText() {
            return text != null && !text.isBlank();
        }

        public boolean isImage() {
            return imageUrl != null && !imageUrl.isBlank();
        }

        public boolean isFailure() {
            return failure != null && !failure.isBlank();
        }
    }
}
