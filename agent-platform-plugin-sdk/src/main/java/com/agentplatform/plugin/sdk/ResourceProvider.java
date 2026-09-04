package com.agentplatform.plugin.sdk;

/**
 * 资源托管 SPI。
 * <p>封装外部依赖（TTS 语音引擎、第三方 API、数据源连接、密钥），
 * 插件内通过依赖注入获取，平台统一做凭证托管与轮换。</p>
 */
public interface ResourceProvider extends Plugin {

    /**
     * 按资源 ID 提供资源。
     */
    Object provide(String resourceId);

    /**
     * 资源类型（model_endpoint / connection / secret 等）。
     */
    String resourceType();
}