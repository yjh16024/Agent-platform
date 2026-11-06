package com.agentplatform.core.multimodal;

import com.agentplatform.common.util.TraceContext;
import com.agentplatform.framework.api.AttachmentResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 平台附件解析器：把框架的 {@code InputPart.Ref} 落到既有的文件服务上（R13 的附件接缝）。
 *
 * <p>两类解析：
 * <ul>
 *   <li>{@code image} → 下载字节 → {@code data:image/png;base64,...}（与平台多模态路径一致）</li>
 *   <li>其它类型 → 走 {@link FileUploadService#readText} 解析成文本，带文件名标注</li>
 * </ul>
 *
 * <p><b>租户从哪来</b>：框架的解析接口只有 {@code kind}/{@code id}（不绑租户，因为
 * 租户是平台概念）。这里从 {@link TraceContext} 取——运行入口已经把租户绑进链路，
 * 拿不到时退回 {@code default}，与运行时默认值一致。</p>
 *
 * <p><b>失败即忽略</b>：文件不存在、跨租户、类型不支持都返回 {@code null}，
 * 让对话继续（少一个附件，而不是整轮失败）。这是刻意的取舍——附件是增强，不是前提。</p>
 */
@Component
public class PlatformAttachmentResolver implements AttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(PlatformAttachmentResolver.class);

    /** 单文件文本上限：与平台对话附件的约定保持一致。 */
    private static final int MAX_TEXT_CHARS = 100_000;

    private final FileUploadService fileUploadService;

    public PlatformAttachmentResolver(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @Override
    public ResolvedAttachment resolve(String kind, String id) {
        String tenantId = TraceContext.tenantId();
        try {
            if ("image".equalsIgnoreCase(kind)) {
                FileUploadService.Download download = fileUploadService.download(tenantId, id);
                String mime = download.asset().getFileType();
                if (mime == null || mime.isBlank()) {
                    mime = "image/png";
                }
                String dataUrl = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(download.bytes());
                return ResolvedAttachment.image(kind, dataUrl);
            }

            FileUploadService.FileText text = fileUploadService.readText(tenantId, id, MAX_TEXT_CHARS);
            String content = "[文件：" + text.fileName() + "]"
                    + (text.truncated() ? "（已截断）" : "") + "\n" + text.text();
            return ResolvedAttachment.text(kind, content);
        } catch (RuntimeException e) {
            /*
             * 失败要「可见」而不是「静默丢弃」：用户拖进来的文件如果被无声丢掉，
             * 他会以为模型无视了附件——这比报错更难排查。
             * 返回 failed 后，运行时会把它写进本轮输入，模型能据此如实回答。
             */
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[attachment] 解析失败：kind={} id={} reason={}", kind, id, reason);
            return ResolvedAttachment.failed(kind, reason);
        }
    }
}
