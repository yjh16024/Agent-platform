package com.agentplatform.core.multimodal;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.entity.FileAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件接口（多模态资源入口：上传 / 列表 / 详情 / 下载）。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@RequiresPermission("file:read")
public class FileUploadController {

    private final FileUploadService fileUploadService;

    /** 上传文件。 */
    @PostMapping("/upload")
    @RequiresPermission("file:manage")
    public ApiResponse<FileAsset> upload(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(fileUploadService.upload(file, type, tenantId));
    }

    /** 列出租户文件。 */
    @GetMapping
    public ApiResponse<List<FileAsset>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(fileUploadService.list(tenantId));
    }

    /** 文件元数据详情。 */
    @GetMapping("/{fileId}")
    public ApiResponse<FileAsset> detail(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String fileId) {
        return ApiResponse.ok(fileUploadService.get(tenantId, fileId));
    }

    /** 删除文件（存储对象 + 元数据）。 */
    @DeleteMapping("/{fileId}")
    @RequiresPermission("file:manage")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String fileId) {
        fileUploadService.delete(tenantId, fileId);
        return ApiResponse.ok(null, "deleted");
    }

    /** 下载文件内容（直出字节流）。 */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String fileId) {
        FileUploadService.Download d = fileUploadService.download(tenantId, fileId);
        MediaType mediaType = d.asset().getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(d.asset().getMimeType());
        String fileName = d.asset().getFileName() == null ? fileId : d.asset().getFileName();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName.replace("\"", "") + "\"")
                .body(d.bytes());
    }
}