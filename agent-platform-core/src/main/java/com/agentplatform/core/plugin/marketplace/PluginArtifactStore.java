package com.agentplatform.core.plugin.marketplace;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 插件制品（jar）落盘。
 * <p>
 * 把上传的插件 jar 存到 {@code agent-platform.plugin.artifact-dir}（默认 {@code ./data/plugins}），
 * 返回可供 {@link com.agentplatform.core.plugin.classloader.ExternalPluginLoader} 直接加载的
 * {@code artifact_uri}（{@code file:/绝对路径}）。
 * </p>
 * <p>
 * 此前该配置项在代码中**从未被使用**，注册接口也把 {@code artifact_uri} 写死为 null，
 * 导致"注册 → attach"链路上加载器必然报「未配置制品」——外部插件因此装不上。本类补齐这一环。
 * </p>
 */
@Slf4j
@Component
public class PluginArtifactStore {

    private final Path baseDir;

    public PluginArtifactStore(
            @Value("${agent-platform.plugin.artifact-dir:./data/plugins}") String dir) {
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("[plugin] 无法创建制品目录 {}: {}", baseDir, e.getMessage());
        }
    }

    /** 制品存储目录（绝对路径）。 */
    public Path baseDir() {
        return baseDir;
    }

    /**
     * 保存插件 jar，返回可加载的 {@code artifact_uri}。
     *
     * @param pluginId 插件 ID（用于命名，会做字符清洗以防路径穿越）
     * @param version  版本（可空）
     * @param in       jar 字节流
     */
    public String save(String pluginId, String version, InputStream in) {
        String safeId = sanitize(pluginId);
        if (safeId.isBlank()) {
            throw BizException.badRequest("插件 id 非法，无法保存制品");
        }
        String safeVer = sanitize(version);
        String name = safeVer.isBlank() ? safeId + ".jar" : safeId + "-" + safeVer + ".jar";
        Path target = baseDir.resolve(name).normalize();
        // 双保险：清洗后仍越界则拒绝
        if (!target.startsWith(baseDir)) {
            throw BizException.badRequest("插件制品路径非法: " + name);
        }
        try {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw BizException.internal("保存插件制品失败: " + e.getMessage(), e);
        }
        log.info("[plugin] 制品已保存: {} ({} bytes)", target, sizeOf(target));
        return target.toUri().toString();
    }

    /** 仅保留字母/数字与 {@code . _ -}，其余替换为 {@code -}，杜绝 {@code ../} 之类路径穿越。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }
}
