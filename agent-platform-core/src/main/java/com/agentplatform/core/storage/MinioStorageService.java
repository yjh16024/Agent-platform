package com.agentplatform.core.storage;

import com.agentplatform.common.exception.BizException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * MinIO 对象存储（S3 兼容生产实现）。
 * <p>
 * 经 {@code agent-platform.storage.type=minio} 启用，配合 docker-compose 中的 minio
 * 服务（9000 端口）。懒建桶，读写均走 MinIO Java SDK。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent-platform.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final MinioClient client;

    public MinioStorageService(
            @Value("${agent-platform.storage.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${agent-platform.storage.minio.access-key:minioadmin}") String accessKey,
            @Value("${agent-platform.storage.minio.secret-key:minioadmin}") String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Override
    public String name() {
        return "minio";
    }

    @Override
    public StoredObject put(String bucket, String objectKey, byte[] bytes, String contentType) {
        try {
            ensureBucket(bucket);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return new StoredObject(bucket, objectKey, "s3://" + bucket + "/" + objectKey);
        } catch (Exception e) {
            log.error("MinIO put failed for {}: {}", objectKey, e.getMessage());
            throw BizException.internal("MinIO upload failed", e);
        }
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        try (GetObjectResponse resp = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build())) {
            return resp.readAllBytes();
        } catch (Exception e) {
            throw BizException.notFound("file", objectKey);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("MinIO delete failed for {}: {}", objectKey, e.getMessage());
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}