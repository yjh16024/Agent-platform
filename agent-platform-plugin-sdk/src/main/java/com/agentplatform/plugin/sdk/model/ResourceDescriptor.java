package com.agentplatform.plugin.sdk.model;

/**
 * 资源描述符 —— 插件向宿主声明"我能提供哪些外部依赖"。
 *
 * <p>它只描述<b>有什么</b>，不搬运资源本身：真正的资源对象由
 * {@link com.agentplatform.plugin.sdk.ResourceProvider#provide(String)} 按 id 惰性给。
 * 这样宿主可以在不初始化连接的情况下先列出能力（市场页、诊断页都要用）。</p>
 *
 * <p><b>归属由宿主自动补全</b>：插件注册时处于 attach 作用域内，宿主据此把
 * {@code pluginId} 与 {@code agentId} 记在内部注册表上，所以本描述符本身不带这两个字段
 * —— 与 {@code HookPoint} / {@code PluginTool} 的注册方式保持一致。</p>
 *
 * @param resourceId   资源 ID。建议形如 {@code 插件id:用途}（如 {@code my-tts:voice_cn}），
 *                     全平台唯一；重复注册同一个 ID 时后者不覆盖前者（首次注册者胜），
 *                     以免插件重名互踩
 * @param resourceType 资源类型，取值见 {@link ResourceTypes}
 * @param displayName  展示名（给界面用，可空则回退成 resourceId）
 * @param description  用途说明（可空）
 */
public record ResourceDescriptor(
        String resourceId,
        String resourceType,
        String displayName,
        String description
) {

    public ResourceDescriptor {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType is required: " + resourceId);
        }
    }

    /** 便捷构造：只要 id 与类型。 */
    public static ResourceDescriptor of(String resourceId, String resourceType) {
        return new ResourceDescriptor(resourceId, resourceType, null, null);
    }

    /** 展示名（空则回退到 id），供界面与日志使用。 */
    public String label() {
        return displayName == null || displayName.isBlank() ? resourceId : displayName;
    }
}
