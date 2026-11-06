package com.agentplatform.core.multimodal;

import com.agentplatform.framework.api.QuotaPolicy;
import org.springframework.stereotype.Component;

/**
 * 平台配额策略：把框架的 {@link QuotaPolicy} 接到既有 {@link QuotaService} 上（P2' 配额接缝）。
 *
 * <p>口径与平台保持一致：**每轮对话开始前**占一次 {@code model_calls}（闸门），
 * 实际 token 消耗由用量插件在结束后另记——两者不是一回事，不能混。</p>
 */
@Component
public class PlatformQuotaPolicy implements QuotaPolicy {

    private final QuotaService quotaService;

    public PlatformQuotaPolicy(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Override
    public void reserve(String tenantId, String metric, long amount) {
        if (amount <= 0) {
            return;
        }
        // 平台接口按「一次」递增，没有批量语义；量大于 1 时逐次占位，
        // 既保持了限额判断的准确性，也不用给 QuotaService 加新方法。
        for (long i = 0; i < amount; i++) {
            quotaService.checkAndIncrement(tenantId, metric, null);
        }
    }
}
