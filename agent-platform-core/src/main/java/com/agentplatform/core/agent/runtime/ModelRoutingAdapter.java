package com.agentplatform.core.agent.runtime;

import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.core.model.router.ModelRouter;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 把平台的 {@link ModelRouter} 适配成框架的 {@link ModelAdapter}。
 *
 * <p>框架运行时要的是「一个模型入口」，而平台是「按 provider 路由」。
 * 这层适配器把两者接起来：能力声明为 TEXT + TOOL，实际调用转给路由中心。</p>
 *
 * <p><b>已知限制</b>：框架的 {@code ChatRequest} 不带 provider，所以这里固定用
 * 平台默认 provider。智能体级别的自定义模型绑定（{@code agent_def.model_binding}）
 * 在框架引擎下**不生效**——要支持它，需要把 provider 变成每请求参数（契约再扩一个字段）。</p>
 */
public class ModelRoutingAdapter implements ModelAdapter {

    private final ModelRouter router;
    private final String provider;

    public ModelRoutingAdapter(ModelRouter router, String provider) {
        this.router = router;
        this.provider = (provider == null || provider.isBlank()) ? "auto" : provider;
    }

    @Override
    public String provider() {
        return "routing(" + provider + ")";
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL, ModelCapability.REASONING);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return router.chat(provider, request);
    }

    @Override
    public Flux<ChatDelta> stream(ChatRequest request) {
        return router.route(provider, ModelCapability.TEXT).stream(request);
    }
}
