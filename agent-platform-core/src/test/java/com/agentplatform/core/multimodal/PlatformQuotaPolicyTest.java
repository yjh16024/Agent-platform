package com.agentplatform.core.multimodal;

import com.agentplatform.framework.api.QuotaPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 配额接缝：框架策略 → 平台 QuotaService。
 */
class PlatformQuotaPolicyTest {

    private final QuotaService quotaService = mock(QuotaService.class);

    @Test
    void 一次运行占一次model_calls() {
        new PlatformQuotaPolicy(quotaService).reserve("t-1", QuotaPolicy.Metrics.MODEL_CALLS, 1);

        verify(quotaService).checkAndIncrement(eq("t-1"), eq("model_calls"), isNull());
    }

    @Test
    void 数量大于一时逐次占位() {
        new PlatformQuotaPolicy(quotaService).reserve("t-1", QuotaPolicy.Metrics.TOKENS, 3);

        verify(quotaService, times(3)).checkAndIncrement(eq("t-1"), eq("tokens"), isNull());
    }

    @Test
    void 非正数量不触发计数() {
        PlatformQuotaPolicy policy = new PlatformQuotaPolicy(quotaService);

        policy.reserve("t-1", QuotaPolicy.Metrics.MODEL_CALLS, 0);
        policy.reserve("t-1", QuotaPolicy.Metrics.MODEL_CALLS, -5);

        verify(quotaService, times(0)).checkAndIncrement(any(), any(), Mockito.<Long>any());
    }
}
