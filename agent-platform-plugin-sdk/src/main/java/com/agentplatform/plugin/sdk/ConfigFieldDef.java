package com.agentplatform.plugin.sdk;

import java.util.List;

/**
 * 插件配置项的<b>声明</b> —— 告诉平台「我要用户填哪些东西」。
 *
 * <h3>它解决的问题</h3>
 * 在此之前，挂载插件时只有一个「自由 JSON 文本框」，而插件说明里却写着
 * "在挂载配置里填入 API Key" —— 用户<b>根本不知道字段名该写什么</b>，
 * 只能去翻插件源码或放弃。声明之后，界面会按它自动生成表单
 * （标签、密码框、下拉选项、数值范围、必填校验都由声明驱动）。
 *
 * <h3>为什么做成"声明"而不是"每个插件写一个前端表单"</h3>
 * 后者要<b>改前端才能加插件</b>，外部 jar 插件更是永远无法拥有配置界面。
 * 声明是数据：外部插件将来在 {@code plugin.yaml} 里写同样的结构即可，
 * 平台一处渲染逻辑覆盖所有插件。
 *
 * <h3>⚠️ 声明的 {@link #key()} 必须与插件读取时用的键完全一致</h3>
 * 这是唯一会出错且难查的地方：声明写 {@code apiKey} 而插件读 {@code api_key}，
 * 表现是"填了但没生效"，而两边代码单独看都是对的。
 * <b>所以每个插件声明的键，都应当直接从它的 {@code of(JsonNode)} 里抄。</b>
 *
 * <h3>向后兼容</h3>
 * {@link PluginDescriptor#configFields()} 是 default 方法 —— 既有插件不实现即返回空列表
 * （界面会退回「自由 JSON 文本框」的旧形态），外部 jar 插件不受影响。
 *
 * @param key          配置键名（与插件 {@code ctx.config()}/${@code of()} 读取的键一致）
 * @param label        表单里显示的标签
 * @param kind         控件类型
 * @param defaultValue 默认值（字符串形式，由前端按 kind 转换；null 表示无默认）
 * @param required     是否必填（仅用于前端提示；插件本身仍应能处理缺失）
 * @param secret       是否是密钥：前端用密码框，且<b>后端会加密落库、回显只给掩码</b>
 * @param options      {@link Kind#select} 的选项
 * @param min          {@link Kind#number} 的下限（含）
 * @param max          {@link Kind#number} 的上限（含）
 * @param step         数值步长（如 0.25）
 * @param placeholder  输入提示
 * @param hint         字段下方的一句话说明（用来写"去哪拿这个 Key"这类信息）
 */
public record ConfigFieldDef(
        String key,
        String label,
        Kind kind,
        String defaultValue,
        boolean required,
        boolean secret,
        List<Option> options,
        Double min,
        Double max,
        Double step,
        String placeholder,
        String hint) {

    /** 控件类型。 */
    public enum Kind {
        /** 单行文本。 */
        text,
        /** 多行文本。 */
        textarea,
        /** 数字输入（配合 min/max/step）。 */
        number,
        /** 下拉选择（配合 options）。 */
        select,
        /** 开关。 */
        bool
    }

    /** 下拉选项。 */
    public record Option(String label, String value) {
    }

    // ---------------------------------------------------------------- 工厂方法
    //
    // 用工厂而不是让调用方填 12 个参数：声明代码会短很多，读起来也接近一句话。
    // （Java 的 record 没有默认参数，直接 new 的话每个声明都要写一长串 null。）

    /** 单行文本。 */
    public static ConfigFieldDef text(String key, String label) {
        return new ConfigFieldDef(key, label, Kind.text, null, false, false, null, null, null, null, null, null);
    }

    /** 单行文本（带输入提示）。 */
    public static ConfigFieldDef text(String key, String label, String placeholder) {
        return new ConfigFieldDef(key, label, Kind.text, null, false, false, null, null, null, null, placeholder, null);
    }

    /**
     * 密钥字段：密码框 + 加密落库 + 掩码回显。
     *
     * <p>注意 {@code secret=true} 只表达"这是敏感值"；<b>是否真的加密由后端的敏感键识别决定</b>
     * （键名为 apiKey/secret/token 等会被自动识别）。若你的键名不在其中，
     * 需要同时确认后端确实会加密它 —— 否则界面上打着码、库里却是明文，反而更危险。</p>
     */
    public static ConfigFieldDef secret(String key, String label) {
        return new ConfigFieldDef(key, label, Kind.text, null, false, true, null, null, null, null, null, null);
    }

    /** 密钥字段（带输入提示）。 */
    public static ConfigFieldDef secret(String key, String label, String placeholder) {
        return new ConfigFieldDef(key, label, Kind.text, null, false, true, null, null, null, null, placeholder, null);
    }

    /** 数字字段。 */
    public static ConfigFieldDef number(String key, String label, double min, double max) {
        return new ConfigFieldDef(key, label, Kind.number, null, false, false, null, min, max, null, null, null);
    }

    /** 数字字段（带步长，如语速 0.25 一档）。 */
    public static ConfigFieldDef number(String key, String label, double min, double max, double step) {
        return new ConfigFieldDef(key, label, Kind.number, null, false, false, null, min, max, step, null, null);
    }

    /**
     * 下拉选择。
     *
     * @param values 选项值；标签与值相同（渠道类选项通常如此）
     */
    public static ConfigFieldDef select(String key, String label, List<String> values) {
        List<Option> opts = values.stream().map(v -> new Option(v, v)).toList();
        return new ConfigFieldDef(key, label, Kind.select, null, false, false, opts, null, null, null, null, null);
    }

    /** 下拉选择（标签与值不同）。 */
    public static ConfigFieldDef select(String key, String label, List<Option> options, boolean distinct) {
        return new ConfigFieldDef(key, label, Kind.select, null, false, false, options, null, null, null, null, null);
    }

    /** 开关。 */
    public static ConfigFieldDef bool(String key, String label) {
        return new ConfigFieldDef(key, label, Kind.bool, null, false, false, null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- 补充信息
    //
    // record 没有 wither，这几种"再补一句说明"的写法用克隆方法实现 ——
    // 声明处读起来是「text(...).hint("...")」，比塞满构造参数清楚。

    /** 追加一句字段说明（如"去哪拿这个 Key"）。 */
    public ConfigFieldDef hint(String hint) {
        return new ConfigFieldDef(key, label, kind, defaultValue, required, secret, options,
                min, max, step, placeholder, hint);
    }

    /**
     * 标为必填。
     *
     * <p>名字带 {@code with} 前缀不是风格偏好：record 会自动生成 {@code required()} 这个
     * <b>无参访问器</b>，同名方法无法共存（编译期就报"方法类型不匹配"）。</p>
     */
    public ConfigFieldDef withRequired() {
        return new ConfigFieldDef(key, label, kind, defaultValue, true, secret, options,
                min, max, step, placeholder, hint);
    }

    /** 指定默认值（字符串形式）。 */
    public ConfigFieldDef def(String defaultValue) {
        return new ConfigFieldDef(key, label, kind, defaultValue, required, secret, options,
                min, max, step, placeholder, hint);
    }

    /** 指定输入提示。 */
    public ConfigFieldDef ph(String placeholder) {
        return new ConfigFieldDef(key, label, kind, defaultValue, required, secret, options,
                min, max, step, placeholder, hint);
    }
}
