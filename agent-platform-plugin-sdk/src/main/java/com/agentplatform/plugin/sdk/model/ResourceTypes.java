package com.agentplatform.plugin.sdk.model;

/**
 * 资源类型的约定取值。
 *
 * <p>刻意用 {@code String} 常量而不是 {@code enum}：资源类型是<b>开放集合</b>
 * （插件可能接的是宿主没想到的外部系统）。给一组约定值是为了让常见场景不必自造词、
 * 也让界面能按类型分组，但不强制 —— 插件定义了新类型宿主照样能注册与解析。</p>
 */
public final class ResourceTypes {

    /** 模型服务端点（自带 baseUrl / apiKey 的第三方推理服务）。 */
    public static final String MODEL_ENDPOINT = "model_endpoint";

    /** 通用连接（数据库、向量库、消息队列等需要凭据的长连接目标）。 */
    public static final String CONNECTION = "connection";

    /** 密钥 / 令牌（插件替宿主保管，避免散落在各自 config 里）。 */
    public static final String SECRET = "secret";

    /** 数据源（供检索、知识库、记忆等读取的结构化数据）。 */
    public static final String DATASOURCE = "datasource";

    /** 外部客户端 / SDK 实例（复用连接池，避免每个插件各建一份）。 */
    public static final String CLIENT = "client";

    private ResourceTypes() {
    }
}
