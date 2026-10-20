package com.agentplatform.core.plugin.web;

import com.agentplatform.model.entity.PluginDef;
import com.agentplatform.model.repository.PluginRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件前端资源（`web/` 目录）的读取。
 *
 * <h3>它服务于"可执行界面"（2026-10-19，D 档）</h3>
 * 插件在 manifest 里声明 {@code contributes.ui_scripts[].entry = "web/pet.js"}，
 * 前端把它拼成 {@code GET /api/v1/plugins/{id}/web/pet.js} 注入 {@code <script>} 执行。
 * 这个类就是那个端点的"读文件"部分 —— 两种来源：
 * <ul>
 *   <li><b>外部 jar 插件</b>：从 {@code artifact_uri} 指向的 jar 里读 {@code web/<path>} 条目
 *       （用 {@link JarFile} 直接读，不依赖插件 ClassLoader —— 那会顺带触发类加载）；</li>
 *   <li><b>内置插件</b>：从 classpath 的 {@code plugin-web/<pluginId>/<path>} 读
 *       （内置插件不是 jar，是 Spring Bean）。</li>
 * </ul>
 *
 * <h3>★ 三条安全约束（每一条都对应一种"读取不该读的东西"）</h3>
 * <ol>
 *   <li><b>路径归一化后必须仍在根下</b>：{@code ..} 一律拒绝 —— 否则能读到 jar 里的别的条目
 *       或 classpath 上的任意资源；</li>
 *   <li><b>扩展名白名单</b>（{@code switch} 即白名单）：不在表里的一律 404，
 *       既避免把 {@code .class}/{@code .html} 当资源送出去，也避免猜 Content-Type 猜错；</li>
 *   <li><b>pluginId 只允许 {@code [A-Za-z0-9._-]}</b>：它要参与 classpath 路径拼接，
 *       不校验就等于把 {@code ../} 从另一边放进来。</li>
 * </ol>
 *
 * <p>路径非法 / 文件不存在 / 插件不存在 —— 一律返回 {@code null}，由控制器统一 404：
 * 不给探测者区分"插件不存在"与"文件不存在"的信息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginWebAssetService {

    /** 平台级插件所属租户（与 PluginService / BuiltinPluginRegistrar 的约定一致）。 */
    private static final String PLATFORM_TENANT = "__platform__";

    /** pluginId 允许的字符集：它会被拼进 classpath 路径。 */
    private static final String SAFE_PLUGIN_ID = "[A-Za-z0-9._-]+";

    private final PluginRepository pluginRepository;

    /** 读到的资源。 */
    public record WebAsset(byte[] bytes, String contentType) {
    }

    /**
     * 读取某插件的 {@code web/<path>}。
     *
     * @return null = 不存在 / 不允许（调用方按 404 处理）
     */
    public WebAsset load(String tenantId, String pluginId, String rawPath) {
        if (pluginId == null || !pluginId.matches(SAFE_PLUGIN_ID)) {
            return null;
        }
        String path = normalize(rawPath);
        if (path == null) {
            return null;
        }
        String contentType = contentTypeOf(extensionOf(path));
        if (contentType == null) {
            return null;
        }

        Optional<PluginDef> found = pluginRepository.findByTenantIdAndPluginId(tenantId, pluginId)
                .or(() -> pluginRepository.findByTenantIdAndPluginId(PLATFORM_TENANT, pluginId));
        if (found.isEmpty()) {
            return null;
        }
        PluginDef def = found.get();

        String artifactUri = def.getArtifactUri();
        byte[] bytes = artifactUri == null || artifactUri.isBlank()
                ? readFromClasspath(pluginId, path)
                : readFromJar(artifactUri, path);
        return bytes == null ? null : new WebAsset(bytes, contentType);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 归一化相对路径。
     *
     * <p>注意用 {@link Path#normalize()} 而不是"检查字符串里有没有 {@code ..}"：
     * {@code a/../../b} 这种字符串本身不含 {@code ../}（是 {@code ../../}），
     * 只有归一化之后再判断才可靠。</p>
     */
    static String normalize(String rawPath) {
        if (rawPath == null || rawPath.indexOf('\0') >= 0) {
            return null;
        }
        String p = rawPath.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return null;
        }
        String normalized;
        try {
            normalized = Path.of(p).normalize().toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.startsWith("..")) {
            return null;
        }
        return normalized;
    }

    /** 取扩展名（小写）；无扩展名返回 null。 */
    static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 扩展名白名单 → Content-Type；不在表里返回 null。 */
    static String contentTypeOf(String ext) {
        if (ext == null) {
            return null;
        }
        return switch (ext) {
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "ttf" -> "font/ttf";
            default -> null;
        };
    }

    private byte[] readFromJar(String artifactUri, String path) {
        Path jarPath;
        try {
            jarPath = artifactUri.startsWith("file:")
                    ? Path.of(URI.create(artifactUri))
                    : Path.of(artifactUri);
        } catch (RuntimeException e) {
            log.warn("[plugin-web] 无法解析插件制品地址 {}：{}", artifactUri, e.getMessage());
            return null;
        }
        if (!Files.isRegularFile(jarPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("web/" + path);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            log.warn("[plugin-web] 读取插件资源失败（{}: web/{}）：{}", jarPath.getFileName(), path, e.getMessage());
            return null;
        }
    }

    private byte[] readFromClasspath(String pluginId, String path) {
        String location = "plugin-web/" + pluginId + "/" + path;
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("[plugin-web] 读取内置插件资源失败（{}）：{}", location, e.getMessage());
            return null;
        }
    }
}
