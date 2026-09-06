package com.agentplatform.core.multimodal;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.rag.pipeline.DocumentParser;
import com.agentplatform.core.storage.StorageService;
import com.agentplatform.model.entity.FileAsset;
import com.agentplatform.model.repository.FileAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件上传服务（上传 → 格式校验 → 转存 → 记录）。
 * <p>多模态资源统一入口：图片/音频/视频/文档，校验格式后实际落存储（本地磁盘 / MinIO），
 * 元数据记入 {@code file_asset} 表。支持按租户隔离与下载。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    /** 统一对象桶（对象键内以 tenant_id 前缀隔离租户）。 */
    private static final String BUCKET = "agent-platform";

    /**
     * 支持的 MIME 类型。
     * <p>覆盖常见图片 / 音频 / 视频 / 文档；未收录的 MIME 走「按文件扩展名推断」兜底
     * （见 {@link #validateType}），避免浏览器给出五花八门的 Content-Type 时误拒上传。</p>
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "image", Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
                    "image/bmp", "image/svg+xml", "image/x-icon", "image/heic", "image/avif"),
            "audio", Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/mp4",
                    "audio/ogg", "audio/webm", "audio/flac", "audio/aac", "audio/x-m4a"),
            "video", Set.of("video/mp4", "video/webm", "video/ogg", "video/quicktime",
                    "video/x-msvideo", "video/x-matroska", "video/x-flv"),
            "file", Set.of("application/pdf", "text/markdown", "text/plain", "text/csv",
                    "text/html", "application/json", "application/xml", "text/xml",
                    "application/zip", "application/x-zip-compressed",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint")
    );

    /** 按扩展名推断分类的兜底表（MIME 缺失或不常见时）。 */
    private static final Map<String, String> EXT_CATEGORY = Map.ofEntries(
            Map.entry("png", "image"), Map.entry("jpg", "image"), Map.entry("jpeg", "image"),
            Map.entry("gif", "image"), Map.entry("webp", "image"), Map.entry("bmp", "image"),
            Map.entry("svg", "image"), Map.entry("ico", "image"),
            Map.entry("mp3", "audio"), Map.entry("wav", "audio"), Map.entry("ogg", "audio"),
            Map.entry("m4a", "audio"), Map.entry("flac", "audio"), Map.entry("aac", "audio"),
            Map.entry("mp4", "video"), Map.entry("webm", "video"), Map.entry("mov", "video"),
            Map.entry("avi", "video"), Map.entry("mkv", "video"),
            Map.entry("pdf", "file"), Map.entry("md", "file"), Map.entry("txt", "file"),
            Map.entry("csv", "file"), Map.entry("html", "file"), Map.entry("htm", "file"),
            Map.entry("json", "file"), Map.entry("xml", "file"), Map.entry("zip", "file"),
            Map.entry("doc", "file"), Map.entry("docx", "file"),
            Map.entry("xls", "file"), Map.entry("xlsx", "file"),
            Map.entry("ppt", "file"), Map.entry("pptx", "file"));

    /** 单文件最大 50MB。 */
    private static final long MAX_SIZE = 50 * 1024 * 1024;

    private final FileAssetRepository fileAssetRepository;
    private final StorageService storageService;
    private final DocumentParser documentParser;

    /** 可被「对话读取」直接解析为文本的扩展名白名单。 */
    private static final Set<String> PARSABLE_TEXT_EXT = Set.of(
            "txt", "md", "markdown", "csv", "html", "htm", "json", "xml",
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx");

    /**
     * 上传文件。
     *
     * @param file     上传文件
     * @param type     分类（image/audio/video/file），空则从 MIME 推断
     * @param tenantId 租户 ID
     * @return 文件元数据
     */
    @Transactional
    public FileAsset upload(MultipartFile file, String type, String tenantId) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("Upload file is empty");
        }
        if (file.getSize() > MAX_SIZE) {
            throw BizException.badRequest("File exceeds max size 50MB");
        }

        String mimeType = file.getContentType() == null ? "" : file.getContentType().trim();
        String category = (type == null || type.isBlank())
                ? inferCategory(mimeType, file.getOriginalFilename()) : type;
        validateType(category, mimeType, file.getOriginalFilename());

        String fileId = IdGenerator.generate("fid");
        String objectKey = tenantId + "/" + fileId + "/" + safeName(file.getOriginalFilename());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.internal("Failed to read upload file", e);
        }

        StorageService.StoredObject stored = storageService.put(BUCKET, objectKey, bytes, mimeType);

        FileAsset asset = FileAsset.builder()
                .fileId(fileId)
                .tenantId(tenantId)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .fileName(file.getOriginalFilename())
                .fileType(category)
                .mimeType(mimeType)
                .fileSize(file.getSize())
                .status("uploaded")
                .build();
        asset = fileAssetRepository.save(asset);
        log.info("Uploaded file {} ({}, {} bytes) to {} storage", fileId, category, file.getSize(), storageService.name());
        return asset;
    }

    /**
     * 查询文件元数据。
     */
    @Transactional(readOnly = true)
    public FileAsset get(String tenantId, String fileId) {
        return fileAssetRepository.findByFileId(fileId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> BizException.notFound("file", fileId));
    }

    /**
     * 列出租户文件。
     */
    @Transactional(readOnly = true)
    public List<FileAsset> list(String tenantId) {
        return fileAssetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * 下载文件内容。
     */
    @Transactional(readOnly = true)
    public Download download(String tenantId, String fileId) {
        FileAsset asset = get(tenantId, fileId);
        byte[] bytes = storageService.get(asset.getBucket(), asset.getObjectKey());
        return new Download(asset, bytes);
    }

    /**
     * 删除文件（存储对象 + 元数据）。
     */
    @Transactional
    public void delete(String tenantId, String fileId) {
        FileAsset asset = get(tenantId, fileId);
        storageService.delete(asset.getBucket(), asset.getObjectKey());
        fileAssetRepository.delete(asset);
        log.info("Deleted file {} from {} storage", fileId, storageService.name());
    }

    /**
     * 下载结果（元数据 + 内容）。
     */
    public record Download(FileAsset asset, byte[] bytes) {
    }

    /** 单个文件可注入上下文的默认最大字符数（约 100k，控制 token 成本）。 */
    private static final int DEFAULT_MAX_TEXT_CHARS = 100_000;

    /**
     * 读取已上传文件为文本（方案 A：对话拖入即读）。
     * <p>下载字节 → {@link DocumentParser} 解析 → 截断保护。按租户隔离，仅允许
     * 可解析文本类（txt/md/csv/html/pdf/docx/pptx/xlsx 等）；图片/音视频给出明确提示。</p>
     */
    @Transactional(readOnly = true)
    public FileText readText(String tenantId, String fileId) {
        return readText(tenantId, fileId, DEFAULT_MAX_TEXT_CHARS);
    }

    /**
     * 读取已上传文件为文本（可指定最大字符数）。
     */
    @Transactional(readOnly = true)
    public FileText readText(String tenantId, String fileId, int maxChars) {
        Download d = download(tenantId, fileId);
        String ext = extensionOf(d.asset().getFileName());
        String category = EXT_CATEGORY.getOrDefault(ext, "file");
        if ("image".equals(category) || "audio".equals(category) || "video".equals(category)) {
            throw BizException.badRequest("文件类型为 " + category + "，暂不支持直接读取"
                    + "（图片视觉读取、音视频转写见后续能力）");
        }
        if (!PARSABLE_TEXT_EXT.contains(ext)) {
            throw BizException.badRequest("暂不支持直接读取 ." + ext
                    + " 类型文件，可先上传到知识库再检索使用");
        }
        String text;
        try {
            text = documentParser.parse(d.asset().getFileName(), d.bytes(), null);
        } catch (Exception e) {
            throw BizException.internal("解析文件文本失败（文件可能损坏或已加密）: " + e.getMessage(), e);
        }
        if (text == null) {
            text = "";
        }
        int limit = maxChars <= 0 ? DEFAULT_MAX_TEXT_CHARS : maxChars;
        boolean truncated = text.length() > limit;
        String content = truncated ? text.substring(0, limit) : text;
        return new FileText(
                d.asset().getFileId(),
                d.asset().getFileName(),
                d.asset().getFileType(),
                content,
                truncated,
                d.asset().getFileSize());
    }

    /** 读取结果。 */
    public record FileText(String fileId, String fileName, String fileType, String text, boolean truncated, Long fileSize) {
    }

    /**
     * 清洗文件名：去掉路径分隔符与 Windows 非法字符，防目录穿越与落盘失败。
     */
    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    private String inferCategory(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType.startsWith("audio/")) {
            return "audio";
        }
        if (mimeType.startsWith("video/")) {
            return "video";
        }
        return "file";
    }

    /**
     * 推断分类：优先按 MIME，MIME 缺失或非典型时回退到文件扩展名。
     * <p>浏览器对同一扩展名可能给出不同 Content-Type（如 .csv 可能是
     * {@code application/vnd.ms-excel} 也可能是 {@code text/csv}），
     * 只看 MIME 会误拒，因此加扩展名兜底。</p>
     */
    private String inferCategory(String mimeType, String fileName) {
        if (mimeType != null && !mimeType.isBlank()) {
            String m = mimeType.toLowerCase();
            if (m.startsWith("image/")) {
                return "image";
            }
            if (m.startsWith("audio/")) {
                return "audio";
            }
            if (m.startsWith("video/")) {
                return "video";
            }
            // MIME 已能识别为具体文档类型时按 file 处理
            if (!"application/octet-stream".equals(m)) {
                return "file";
            }
        }
        return categoryOfExtension(fileName);
    }

    /** 按扩展名取分类，未知扩展名一律按 file（最宽松的兜底）。 */
    private String categoryOfExtension(String fileName) {
        String ext = extensionOf(fileName);
        return EXT_CATEGORY.getOrDefault(ext, "file");
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * 校验分类与 MIME：白名单命中即放行；未命中时按扩展名二次判定。
     * <p>MIME 完全缺失（部分浏览器 / 非表单上传场景）无法判定，直接放行，
     * 由存储层落盘——宁可宽容，也不要让用户「传任何文件都失败」。</p>
     */
    private void validateType(String category, String mimeType, String fileName) {
        Set<String> allowed = ALLOWED.get(category);
        if (allowed == null) {
            throw BizException.badRequest("不支持的文件分类: " + category + "（可选 image / audio / video / file）");
        }
        if (mimeType == null || mimeType.isBlank()) {
            return;   // 无 Content-Type，无法校验，放行
        }
        String m = mimeType.toLowerCase();
        if (allowed.contains(m)) {
            return;
        }
        // MIME 未收录：按扩展名复核，扩展名归属本分类则放行
        if (category.equals(categoryOfExtension(fileName))) {
            return;
        }
        throw BizException.badRequest("文件类型不匹配：MIME '" + mimeType + "' 不属于分类 " + category
                + "，可在上传时显式指定 type（image / audio / video / file）");
    }
}