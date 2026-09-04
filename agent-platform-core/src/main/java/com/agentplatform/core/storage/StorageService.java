package com.agentplatform.core.storage;

/**
 * 对象存储抽象。
 * <p>
 * 多模态文件统一入口的底层存储：默认 {@code LocalDiskStorageService}（本地磁盘，
 * 无外部依赖），生产切换 {@code MinioStorageService}（S3 兼容，经
 * {@code agent-platform.storage.type=minio} 启用）。
 * </p>
 */
public interface StorageService {

    /**
     * 实现名称（local / minio）。
     */
    String name();

    /**
     * 写入对象并返回定位信息。
     *
     * @param bucket      逻辑桶
     * @param objectKey   对象键
     * @param bytes       内容
     * @param contentType MIME 类型
     */
    StoredObject put(String bucket, String objectKey, byte[] bytes, String contentType);

    /**
     * 读取对象内容。
     */
    byte[] get(String bucket, String objectKey);

    /**
     * 是否存在。
     */
    boolean exists(String bucket, String objectKey);

    /**
     * 删除对象。
     */
    void delete(String bucket, String objectKey);

    /**
     * 已存储对象的定位信息。
     */
    record StoredObject(String bucket, String objectKey, String uri) {
    }
}