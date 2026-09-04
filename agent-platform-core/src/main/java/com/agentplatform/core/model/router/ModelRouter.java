package com.agentplatform.core.model.router;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.model.ModelCapability;
import com.agentplatform.core.model.adapter.ModelAdapter;
import com.agentplatform.core.model.factory.ModelProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 模型路由器（策略模式 + Fallback 降级）。
 * <p>
 * 按能力标签 + 健康度 + 成本策略选择具体模型实例；失败时自动降级到
 * 同能力备选模型（本地 Mock 作为最终兜底）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final ModelProviderFactory factory;

    /**
     * 路由：按 provider（可 auto）+ 能力标签选择适配器。
     *
     * @param provider   服务商（auto 表示自动）
     * @param capability 所需能力
     */
    public ModelAdapter route(String provider, ModelCapability capability) {
        if (!factory.supports(provider)) {
            throw factory.unsupported(provider);
        }

        List<ModelAdapter> candidates = new ArrayList<>();
        if (provider == null || provider.isBlank() || "auto".equalsIgnoreCase(provider)) {
            // 自动路由：所有支持该能力的备选
            for (String p : ModelProviderFactory.SUPPORTED_PROVIDERS) {
                ModelAdapter adapter = null;
                try {
                    adapter = factory.get(p);
                } catch (Exception ignored) {
                    continue;
                }
                if (adapter.capabilities().contains(capability)) {
                    candidates.add(adapter);
                }
            }
        } else {
            candidates.add(factory.get(provider));
        }

        return candidates.stream()
                .filter(ModelAdapter::isHealthy)
                .filter(a -> capability == null || a.capabilities().contains(capability))
                .min(Comparator.comparingDouble(ModelAdapter::costWeight))
                .orElseThrow(() -> BizException.internal("No healthy model available for capability: " + capability));
    }

    /**
     * 按 provider 发起对话。
     * <p><b>不再静默兜底 Mock。</b>上游（直连厂商 / LiteLLM）返回 401/400/402 等错误、
     * 连接失败或超时时，异常原样向上抛出，由全局异常处理器透传给前端；
     * 仅当 provider 显式为 {@code local} 时才走 {@link com.agentplatform.core.model.adapter.MockModelAdapter}。</p>
     */
    public ModelAdapter.ChatResponse chat(String provider, ModelAdapter.ChatRequest request) {
        return route(provider, ModelCapability.TEXT).chat(request);
    }

    /**
     * 判断某 provider 是否支持指定能力。
     */
    public boolean supports(String provider, Set<ModelCapability> capabilities) {
        if (!factory.supports(provider)) {
            return false;
        }
        return factory.get(provider).capabilities().containsAll(capabilities);
    }
}