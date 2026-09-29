package com.agentplatform.plugin.sdk;

import java.util.List;

/**
 * 插件自描述元信息（可选实现）。
 *
 * <p>{@link Plugin} 只声明了 {@code id} 与 {@code version} —— 那是插件的「身份」，
 * 不是「展示信息」。外部 jar 插件的展示名与说明来自上传时的 manifest，
 * 而<b>内置插件没有 manifest 文件</b>，所以需要这样一个可选契约来补上展示信息。</p>
 *
 * <p>实现它之后，内置插件在启动时会把这里的 name/description 写进 {@code plugin_def}，
 * 从而出现在插件市场页并可从界面上挂载到智能体。不实现也能正常工作，
 * 只是市场页上显示的是插件 id。</p>
 *
 * <p>外部 jar 插件同样可以实现本接口，但会<b>以 manifest 为准</b>，此处仅作兜底。</p>
 */
public interface PluginDescriptor {

    /** 展示名（建议用中文短名，例如「关键词直答」）。 */
    String name();

    /** 一句话说明（会显示在市场卡片上）。 */
    default String description() {
        return "";
    }

    /** 作者 / 来源；内置插件默认标为 builtin。 */
    default String author() {
        return "builtin";
    }

    /**
     * 本插件需要用户填写的配置项（<b>可选实现</b>）。
     *
     * <h3>为什么要声明</h3>
     * 在此之前，挂载插件时只有一个自由 JSON 文本框 —— 而插件说明里写着
     * "在挂载配置里填入 API Key"。用户<b>不知道该写什么字段名</b>，
     * 结果是"功能看着有、却永远配不起来"。声明之后界面会自动生成表单。
     *
     * <h3>⚠️ 键名必须与插件实际读取的键一致</h3>
     * 声明 {@code apiKey} 而代码读 {@code api_key}，表现是"填了不生效"，
     * 而两边单独看都对。**声明时直接从自己的 {@code of(JsonNode)} 里抄键名。**
     *
     * <h3>向后兼容</h3>
     * default 方法：不实现即返回空列表，界面退回「自由 JSON 文本框」的旧形态 ——
     * 既有的外部 jar 插件完全不受影响。
     */
    default List<ConfigFieldDef> configFields() {
        return List.of();
    }
}
