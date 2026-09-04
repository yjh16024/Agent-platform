package com.agentplatform.model.record;

import java.util.List;

/**
 * 有效配置（运行时组装结果）。
 * <p>
 * Agent Runtime 在每次 run 启动时按 {@code agent_id + version} 加载，
 * 由「平台默认 → 租户策略 → Agent 保存值 → 请求级覆盖 → 插件贡献」合并而来。
 * </p>
 *
 * @param persona          人格
 * @param systemPrompt     系统提示词（含 {{var}} 占位符）
 * @param generationConfig 生成参数
 * @param capabilities     能力装配
 * @param plugins          已 attach 并 enabled 的插件及其贡献能力
 */
public record EffectiveConfig(
        Persona persona,
        String systemPrompt,
        GenerationConfig generationConfig,
        Capabilities capabilities,
        List<ResolvedPlugin> plugins
) {
    /**
     * 已解析插件（含其贡献的 tools/hooks）。
     */
    public record ResolvedPlugin(
            String pluginId,
            String version,
            List<String> contributedTools,
            List<String> contributedHooks
    ) {
    }
}