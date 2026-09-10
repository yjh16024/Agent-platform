package com.agentplatform.core.plugin.runtime;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.plugin.sdk.model.PluginManifest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

/**
 * 插件 Manifest 加载器（解析 plugin.yaml）。
 * <p>plugin.yaml 是插件的标准化契约：身份、能力贡献、依赖、权限、运行约束。</p>
 */
@Component
public class PluginManifestLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

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