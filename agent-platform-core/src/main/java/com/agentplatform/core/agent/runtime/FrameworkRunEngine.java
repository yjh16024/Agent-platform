package com.agentplatform.core.agent.runtime;

import com.agentplatform.core.model.router.ModelRouter;
import com.agentplatform.core.multimodal.PlatformAttachmentResolver;
import com.agentplatform.core.multimodal.PlatformQuotaPolicy;
import com.agentplatform.core.plugin.runtime.PipelineRunInterceptor;
import com.agentplatform.core.security.rbac.PlatformAuthorizationPolicy;
import com.agentplatform.core.session.MessageTableSessionStore;
import com.agentplatform.core.tool.registry.PlatformToolCatalog;
import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.ModelCapability;
import com.agentplatform.framework.api.RunCommand;
import com.agentplatform.framework.api.RunResult;
import com.agentplatform.framework.runtime.DefaultAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 框架引擎装配点：把平台的五个接缝与被路由模型拼成一个 {@link AgentRuntime}。
 *
 * <p>开关：{@code agent-platform.runtime.engine=framework} 才启用，**默认 legacy**。
 * 这是刻意的——平台主链路的语义（人格 / RAG / Skill / 附件）远多于框架运行时，
 * 切换要以「行为等价」为前提，而不是以「能跑」为前提。</p>
 *
 * <p><b>当前不接入 {@code AgentRuntimeService}</b>。原因是还差两个契约：
 * 每请求凭证（框架 {@code ChatRequest} 只有 baseUrl/apiKey 字段，但运行时不会从命令里取）
 * 与附件解析（{@code InputPart.Ref} 需要宿主解析成文本/图片，平台侧尚未接线）。
 * 在这两者补齐之前强行切换，会静默丢掉「智能体自定义模型绑定」与「拖入文档」两项能力。</p>
 */
@Component
public class FrameworkRunEngine {

    private static final Logger log = LoggerFactory.getLogger(FrameworkRunEngine.class);

    private final boolean enabled;
    private final AgentRuntime runtime;

    public FrameworkRunEngine(
            @Value("${agent-platform.runtime.engine:legacy}") String engine,
            ModelRouter modelRouter,
            @Value("${DEFAULT_PROVIDER:deepseek}") String defaultProvider,
            MessageTableSessionStore sessions,
            PlatformToolCatalog tools,
            PlatformQuotaPolicy quota,
            PlatformAuthorizationPolicy authorization,
            PipelineRunInterceptor interceptor,
            PlatformAttachmentResolver attachments) {

        boolean requested = "framework".equalsIgnoreCase(engine);
        if (!requested) {
            this.enabled = false;
            this.runtime = null;
            return;
        }

        this.runtime = DefaultAgentRuntime.builder()
                .adapter(new ModelRoutingAdapter(modelRouter, defaultProvider))
                .catalog(tools)
                .sessions(sessions)
                .quota(quota)
                .authorization(authorization)
                .interceptors(List.of(interceptor))
                .attachments(attachments)
                .build();
        this.enabled = true;
        log.info("[framework-engine] 已启用：provider={}（智能体级模型绑定仍不支持：引擎固定用默认 provider）", defaultProvider);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 通过框架运行时跑一轮。
     *
     * @throws IllegalStateException 引擎未启用时调用
     */
    public RunResult run(RunCommand command) {
        if (!enabled) {
            throw new IllegalStateException("框架引擎未启用（agent-platform.runtime.engine=legacy）");
        }
        return runtime.run(command);
    }

    /** 供后续接线使用的能力声明：目前只承诺文本 + 工具。 */
    public java.util.Set<ModelCapability> capabilities() {
        return java.util.Set.of(ModelCapability.TEXT, ModelCapability.TOOL);
    }
}
