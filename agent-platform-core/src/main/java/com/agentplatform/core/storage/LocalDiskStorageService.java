package com.agentplatform.core.storage;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地磁盘存储（默认实现）。
 * <p>
 * 对象落盘到 {@code agent-platform.storage.local-dir}（默认 {@code ./data/files}），
 * 键为 {@code {dir}/{bucket}/{objectKey}}。适合本地开发 / 单机部署。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageService implements StorageService {

    private final Path baseDir;

    public LocalDiskStorageService(
            @Value("${agent-platform.storage.local-dir:./data/files}") String localDir) {
        this.baseDir = Path.of(localDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public StoredObject put(String bucket, String objectKey, byte[] bytes, String contentType) {
        Path target = resolve(bucket, objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw BizException.internal("Failed to write file to local storage", e);
        }
        return new StoredObject(bucket, objectKey, target.toUri().toString());
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        Path target = resolve(bucket, objectKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw BizException.notFound("file", objectKey);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        return Files.exists(resolve(bucket, objectKey));
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            Files.deleteIfExists(resolve(bucket, objectKey));
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", objectKey, e.getMessage());
        }
    }

    /**
     * 解析对象路径，防目录穿越。
     */
    private Path resolve(String bucket, String objectKey) {
        Path p = baseDir.resolve(safe(bucket)).resolve(safe(objectKey)).normalize();
        if (!p.startsWith(baseDir)) {
            throw BizException.badRequest("Illegal storage path");
        }
        return p;
    }

    private String safe(String segment) {
        String s = segment == null ? "" : segment;
        s = s.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.contains("..")) {
            throw BizException.badRequest("Illegal storage path segment");
        }
        return s;
    }
}