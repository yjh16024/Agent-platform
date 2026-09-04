package com.agentplatform.plugin.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * 插件 Manifest（plugin.yaml 的解析结果）。
 * <p>插件身份、能力贡献、依赖、权限的单一声明。</p>
 *
 * @param id           全局唯一 ID
 * @param name         名称
 * @param version      语义化版本
 * @param description  描述
 * @param author       作者
 * @param entry        入口（type=java/script/http/mcp, main_class=实现类）
 * @param contributes  能力贡献（tools/hooks/resources/ui）
 * @param requires     运行约束（platform_version, plugins 依赖）
 * @param permissions  最小权限声明
 * @param runtime      运行时约束（isolation/memory_mb/timeout_ms）
 */
public record PluginManifest(
        String id,
        String name,
        String version,
        String description,
        String author,
        Entry entry,
        Contributes contributes,
        Requires requires,
        Map<String, Object> permissions,
        Runtime runtime
) {
    public record Entry(String type, String main_class) {
    }

    public record Contributes(
            List<ToolDef> tools,
            List<HookDef> hooks,
            List<ResourceDef> resources
    ) {
        public record ToolDef(String name, String description, Map<String, Object> input_schema) {
        }

        public record HookDef(String point, String handler) {
        }

        public record ResourceDef(String id, String type) {
        }
    }

    public record Requires(String platform_version, List<String> plugins) {
    }

    public record Runtime(String isolation, Integer memory_mb, Integer timeout_ms) {
    }
}