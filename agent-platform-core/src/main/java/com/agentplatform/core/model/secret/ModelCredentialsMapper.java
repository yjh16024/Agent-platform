package com.agentplatform.core.model.secret;

import com.agentplatform.framework.api.ModelCredentials;

/**
 * 平台解析结果 → 框架凭证（R13 的凭证接缝）。
 *
 * <p>纯函数，没有状态：平台算好「这一轮用哪家的哪个 key」，
 * 框架运行时只负责把它原样塞进请求。凭证的加密、回退、掩码全部留在平台侧不动。</p>
 */
public final class ModelCredentialsMapper {

    private ModelCredentialsMapper() {
    }

    public static ModelCredentials of(ModelBindingService.ResolvedModel resolved) {
        if (resolved == null) {
            return ModelCredentials.none();
        }
        return new ModelCredentials(resolved.provider(), resolved.baseUrl(), resolved.apiKey());
    }
}
