package com.agentplatform.core.multimodal;

import com.agentplatform.framework.api.AttachmentResolver.ResolvedAttachment;
import com.agentplatform.model.entity.FileAsset;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附件接缝：框架 Ref → 平台文件服务。
 */
class PlatformAttachmentResolverTest {

    private final FileUploadService files = mock(FileUploadService.class);
    private final PlatformAttachmentResolver resolver = new PlatformAttachmentResolver(files);

    @Test
    void 图片应转成dataUrl() {
        FileAsset asset = FileAsset.builder().fileId("f-1").fileType("image/jpeg").build();
        when(files.download("default", "f-1"))
                .thenReturn(new FileUploadService.Download(asset, "PNG".getBytes(StandardCharsets.UTF_8)));

        ResolvedAttachment resolved = resolver.resolve("image", "f-1");

        assertTrue(resolved.isImage());
        assertTrue(resolved.imageUrl().startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void 文本文件应带文件名并拼接内容() {
        when(files.readText("default", "f-2", 100_000)).thenReturn(
                new FileUploadService.FileText("f-2", "需求.md", "md", "# 标题", false, 10L));

        ResolvedAttachment resolved = resolver.resolve("file", "f-2");

        assertTrue(resolved.isText());
        assertTrue(resolved.text().contains("需求.md"));
        assertTrue(resolved.text().contains("# 标题"));
    }

    @Test
    void 解析失败应返回可见失败而不是静默丢弃() {
        when(files.readText("default", "missing", 100_000))
                .thenThrow(new RuntimeException("not found"));

        ResolvedAttachment resolved = resolver.resolve("file", "missing");

        assertTrue(resolved.isFailure());
        assertTrue(resolved.failure().contains("not found"));
    }

    @Test
    void 图片缺mime时退回png() {
        FileAsset asset = FileAsset.builder().fileId("f-3").build();
        when(files.download("default", "f-3"))
                .thenReturn(new FileUploadService.Download(asset, new byte[]{1, 2}));

        ResolvedAttachment resolved = resolver.resolve("image", "f-3");

        assertEquals("data:image/png;base64,AQI=", resolved.imageUrl());
    }
}
