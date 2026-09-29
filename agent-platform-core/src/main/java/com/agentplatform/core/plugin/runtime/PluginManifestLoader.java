package com.agentplatform.core.plugin.runtime;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Component;

/**
 * 插件 Manifest 加载器（解析 plugin.yaml）。
 * <p>plugin.yaml 是插件的标准化契约：身份、能力贡献、依赖、权限、运行约束。</p>
 *
 * <h3>★ 为什么必须显式关掉「未知字段报错」（2026-09-29）</h3>
 * 这两个 {@code ObjectMapper} 是<b>自己 new 的</b>，拿不到 Spring Boot 全局的宽松配置
 * （Boot 会把 {@code FAIL_ON_UNKNOWN_PROPERTIES} 设为 false，这里保持的是 Jackson 默认的 true）。
 * 后果是：<b>manifest 里多出任何一个本版本还不认识的字段，整个插件就装不上</b>，
 * 而报错信息只是笼统的 "Failed to parse plugin manifest" —— 无法定位到具体字段。
 *
 * <p>这个坑的实际影响是<b>版本错配</b>：插件作者按新文档写了 {@code contributes.ui}，
 * 用户在旧版平台上装，得到的不是"这个功能不支持"（可理解），而是"插件损坏"（不可理解）。
 * 插件生态里「多字段」注定会发生，所以必须向前兼容。</p>
 *
 * <p>反过来，<b>缺字段</b>不受影响：record 的缺失字段自然为 null，由各使用点判空。
 * 于是"只增不改"成为这个 manifest 契约的实际演进方式。</p>
 */
@Component
public class PluginManifestLoader {

    /*
     * 两个 mapper 都关掉「未知字段报错」。
     * 注意 Jackson 3 **移除了 ObjectMapper#configure**（配置改到构造期 builder），
     * 所以这里用 builder 写法；JSON 侧直接复用 JsonUtils 里那个已配好的实例（同一个取舍，不必重建）。
     */
    private final ObjectMapper yamlMapper = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ObjectMapper jsonMapper = JsonUtils.mapper();

    /**
     * 解析 YAML/JSON 格式的 plugin manifest。
     */
    public PluginManifest parse(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("BAD_REQUEST", "plugin manifest is empty");
        }
        try {
            PluginManifest manifest;
            if (content.trim().startsWith("{")) {
                manifest = jsonMapper.readValue(content, PluginManifest.class);
            } else {
                manifest = yamlMapper.readValue(content, PluginManifest.class);
            }
            if (manifest.id() == null || manifest.id().isBlank()) {
                throw new BizException("VALIDATION_ERROR", "plugin.id is required");
            }
            return manifest;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("VALIDATION_ERROR", "Failed to parse plugin manifest: " + e.getMessage());
        }
    }
}