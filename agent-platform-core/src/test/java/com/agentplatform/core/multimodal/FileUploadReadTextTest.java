package com.agentplatform.core.multimodal;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.rag.pipeline.DocumentParser;
import com.agentplatform.core.storage.StorageService;
import com.agentplatform.model.entity.FileAsset;
import com.agentplatform.model.repository.FileAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 方案 A（对话文件读取）单测：readText 解析文本 / 截断 / 类型拒绝 / 租户隔离。
 */
class FileUploadReadTextTest {

    private FileUploadService service;
    private FileAssetRepository repository;
    private StorageService storage;

    @BeforeEach
    void setUp() {
        repository = mock(FileAssetRepository.class);
        storage = mock(StorageService.class);
        service = new FileUploadService(repository, storage, new DocumentParser());
    }

    private FileAsset asset(String fileId, String name, String type, String tenant) {
        return FileAsset.builder()
                .fileId(fileId)
                .tenantId(tenant)
                .fileName(name)
                .fileType(type)
                .bucket("agent-platform")
                .objectKey(tenant + "/" + fileId + "/" + name)
                .build();
    }

    @Test
    @DisplayName("txt 文件被解析为可注入文本")
    void readTxt() {
        FileAsset a = asset("f1", "notes.txt", "file", "t1");
        when(repository.findByFileId("f1")).thenReturn(Optional.of(a));
        when(storage.get(anyString(), anyString()))
                .thenReturn("第一条\n第二条\n".getBytes(StandardCharsets.UTF_8));

        FileUploadService.FileText ft = service.readText("t1", "f1");
        assertEquals("notes.txt", ft.fileName());
        assertTrue(ft.text().contains("第二条"));
        assertFalse(ft.truncated());
    }

    @Test
    @DisplayName("超过上限时截断并标记")
    void truncate() {
        FileAsset a = asset("f2", "long.txt", "file", "t1");
        when(repository.findByFileId("f2")).thenReturn(Optional.of(a));
        String big = "字".repeat(5000);
        when(storage.get(anyString(), anyString())).thenReturn(big.getBytes(StandardCharsets.UTF_8));

        FileUploadService.FileText ft = service.readText("t1", "f2", 100);
        assertTrue(ft.truncated());
        assertEquals(100, ft.text().length());
    }

    @Test
    @DisplayName("图片类型拒绝直接读取（提示走视觉/知识库）")
    void rejectImage() {
        FileAsset a = asset("f3", "pic.png", "image", "t1");
        when(repository.findByFileId("f3")).thenReturn(Optional.of(a));
        assertThrows(BizException.class, () -> service.readText("t1", "f3"));
    }

    @Test
    @DisplayName("跨租户读取被拒绝（404）")
    void tenantIsolation() {
        FileAsset a = asset("f4", "doc.txt", "file", "t2");
        when(repository.findByFileId("f4")).thenReturn(Optional.of(a));
        assertThrows(BizException.class, () -> service.readText("t1", "f4"));
    }
}
