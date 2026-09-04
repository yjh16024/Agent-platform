package com.agentplatform.core.plugin.classloader;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.PluginRepository;
import com.agentplatform.plugin.sdk.Plugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部插件加载器：把 {@code plugin_def.artifact_uri} 指向的插件 jar
 * 用独立 {@link PluginClassLoader} 加载入口类并实例化。
 * <p>
 * 原先 {@code PluginRuntime.resolve} 只认 Spring 注入的内置插件，外部 jar 插件
 * 即便上传了制品也无法 attach。本加载器补齐「注册(metadata) → 上传 jar → attach」链路：
 * </p>
 * <ul>
 *   <li>{@code artifact_uri} 支持本地文件路径（{@code file:...} 或裸路径）与 {@code http(s)} 下载；</li>
 *   <li>入口类取 {@code plugin_def.manifest.entry.main_class}；</li>
 *   <li>实例按 pluginId 缓存，重复 attach 不重复加载（与内置插件一致）；</li>
 *   <li>失败给出明确错误（jar 缺失 / 主类不存在 / 未实现 Plugin 接口），不再静默 notFound。</li>
 * </ul>
 */
@Slf4j
@Component
public class ExternalPluginLoader {

    private static final String PLATFORM_TENANT = "__platform__";

    private final PluginRepository pluginRepository;
    private final Map<String, Plugin> loaded = new ConcurrentHashMap<>();

    public ExternalPluginLoader(PluginRepository pluginRepository) {
        this.pluginRepository = pluginRepository;
    }

    /**
     * 加载外部插件实例（已加载则复用）。
     *
     * @param pluginId 插件 ID
     * @param tenantId 发起 attach 的租户
     * @return 插件实例；未找到注册信息或无法加载时抛业务异常
     */
    public Plugin load(String pluginId, String tenantId) {
        Plugin cached = loaded.get(pluginId);
        if (cached != null) {
            return cached;
        }
        PluginDef def = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .or(() -> pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId))
                .orElseThrow(() -> BizException.notFound("plugin", pluginId));

        String artifactUri = def.getArtifactUri();
        if (artifactUri == null || artifactUri.isBlank()) {
            throw BizException.badRequest("插件 " + pluginId + " 未配置制品（artifact_uri）。"
                    + "外部插件需先上传 jar 并填写 artifact_uri 才能 attach，内置插件请使用系统内置。");
        }
        String mainClass = mainClassOf(def);
        if (mainClass == null || mainClass.isBlank()) {
            throw BizException.badRequest("插件 " + pluginId + " 的 manifest 缺少 entry.main_class，无法实例化");
        }
        try {
            URL jarUrl = resolveArtifact(artifactUri);
            PluginClassLoader loader = new PluginClassLoader(pluginId,
                    new URL[]{jarUrl}, Thread.currentThread().getContextClassLoader(), Set.of());
            Class<?> clazz = Class.forName(mainClass, true, loader);
            if (!Plugin.class.isAssignableFrom(clazz)) {
                throw BizException.badRequest("插件主类 " + mainClass + " 未实现 Plugin 接口");
            }
            @SuppressWarnings("unchecked")
            Class<? extends Plugin> pluginClass = (Class<? extends Plugin>) clazz;
            Plugin instance = pluginClass.getDeclaredConstructor().newInstance();
            if (!pluginId.equals(instance.id())) {
                log.warn("[plugin] 外部插件 jar 内声明的 id={} 与注册 id={} 不一致，以注册 id 为准",
                        instance.id(), pluginId);
            }
            loaded.put(pluginId, instance);
            log.info("[plugin] loaded external plugin {} from {}", pluginId, jarUrl);
            return instance;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.internal("加载外部插件 " + pluginId + " 失败: " + e.getMessage(), e);
        }
    }

    /** 释放外部插件实例（detach 后卸载，下次 attach 重新加载）。 */
    public void unload(String pluginId) {
        loaded.remove(pluginId);
    }

    private String mainClassOf(PluginDef def) {
        if (def.getManifest() == null) {
            return null;
        }
        Object entry = def.getManifest().get("entry");
        if (entry instanceof Map<?, ?> m) {
            Object main = m.get("main_class");
            return main == null ? null : String.valueOf(main);
        }
        return null;
    }

    private URL resolveArtifact(String artifactUri) throws Exception {
        String u = artifactUri.trim();
        if (u.toLowerCase().startsWith("file:")) {
            Path p = Path.of(URI.create(u));
            if (!Files.exists(p)) {
                throw BizException.badRequest("插件制品不存在: " + p.toAbsolutePath());
            }
            return p.toUri().toURL();
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            // 下载到临时文件后加载（内存加载需注意 jar 锁定问题，落盘更稳）
            Path tmp = Files.createTempFile("agent-plugin-", ".jar");
            try (var in = new java.net.URL(u).openStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp.toFile().deleteOnExit();
            return tmp.toUri().toURL();
        }
        // 裸路径：按本机文件处理
        Path p = Path.of(u).toAbsolutePath().normalize();
        if (!Files.exists(p)) {
            throw BizException.badRequest("插件制品不存在: " + p);
        }
        return p.toUri().toURL();
    }
}
