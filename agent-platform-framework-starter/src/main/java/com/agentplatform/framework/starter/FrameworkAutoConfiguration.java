package com.agentplatform.framework.starter;

import com.agentplatform.framework.api.AgentRuntime;
import com.agentplatform.framework.api.AuthorizationPolicy;
import com.agentplatform.framework.api.QuotaPolicy;
import com.agentplatform.framework.api.RunObserver;
import com.agentplatform.framework.api.ModelAdapter;
import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolCatalog;
import com.agentplatform.framework.runtime.DefaultAgentRuntime;
import com.agentplatform.framework.runtime.InMemorySessionStore;
import com.agentplatform.framework.runtime.SessionStore;
import com.agentplatform.framework.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 基础自动装配：会话存储 + 工具注册表 + 运行时。
 *
 * <p><b>这个类里没有任何 Spring AI 引用</b>——E1/E3 在
 * {@code agent-platform-framework-starter-springai} 里。这样做的原因很实际：
 * 一旦基础 starter 依赖 spring-ai（哪怕是 optional），
 * {@code @ConditionalOnMissingBean} 在类缺失时做类型推导会失败，应用直接起不来。
 *
 * <p>只依赖 {@code spring-boot-autoconfigure}：框架要能被命令行工具、批处理、
 * 桌面后端嵌入，不能默认拖一个 Tomcat 进来。
 *
 * <p>零配置可跑：宿主提供一个 {@link ModelAdapter} Bean 即可拿到 {@link AgentRuntime}；
 * 其余都有默认实现，且全部 {@code @ConditionalOnMissingBean}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-platform.framework", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(FrameworkProperties.class)
public class FrameworkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FrameworkAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SessionStore frameworkSessionStore() {
        log.warn("[framework] 未提供 SessionStore，使用进程内实现：重启即丢历史。"
                + "生产环境请提供一个 SessionStore Bean（落库 / Redis 均可）。");
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry frameworkToolRegistry() {
        return new ToolRegistry();
    }

    /**
     * 观测出口：宿主有 {@code ObservationRegistry} 就接到 Micrometer，
     * 没有就给 NOOP —— **观测是增强，不是前提**。
     */
    @Bean
    @ConditionalOnMissingBean
    public RunObserver frameworkRunObserver(ObjectProvider<io.micrometer.observation.ObservationRegistry> registries) {
        io.micrometer.observation.ObservationRegistry registry = registries.getIfAvailable();
        return registry == null ? RunObserver.NOOP : new MicrometerRunObserver(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ModelAdapter.class)
    public AgentRuntime agentRuntime(ModelAdapter adapter,
                                     ToolRegistry registry,
                                     SessionStore sessions,
                                     FrameworkProperties properties,
                                     ObjectProvider<Tool> tools,
                                     ObjectProvider<ToolCatalog> catalogs,
                                     ObjectProvider<AuthorizationPolicy> authorizations,
                                     ObjectProvider<QuotaPolicy> quotas,
                                     ObjectProvider<RunObserver> observers) {
        DefaultAgentRuntime.Builder builder = DefaultAgentRuntime.builder()
                .adapter(adapter)
                .sessions(sessions)
                .systemPrompt(properties.systemPrompt())
                .defaultModel(properties.defaultModel())
                .defaultMaxRounds(properties.maxRounds());

        // 宿主提供了策略就接上；没有就用框架默认（放行 / 不限额）
        AuthorizationPolicy authorization = authorizations.getIfAvailable();
        if (authorization != null) {
            builder.authorization(authorization);
        }
        QuotaPolicy quota = quotas.getIfAvailable();
        if (quota != null) {
            builder.quota(quota);
        }
        RunObserver observer = observers.getIfAvailable();
        if (observer != null) {
            builder.observer(observer);
        }

        /*
         * 宿主若提供了**外部**只读目录（例如平台注册中心的适配器），优先用它。
         *
         * ⚠️ 必须排除 ToolRegistry 本身：框架自己的注册表也 implements ToolCatalog，
         * 所以它同样是 ObjectProvider<ToolCatalog> 的候选。早先直接 getIfAvailable()
         * 会把它当成「宿主目录」，于是宿主声明的 Tool Bean 一个都没被登记，
         * 表现为「模型说要调工具，但工具列表是空的」——一个只在运行期暴露的典型类型层级陷阱。
         */
        ToolCatalog external = catalogs.orderedStream()
                .filter(candidate -> !(candidate instanceof ToolRegistry))
                .findFirst()
                .orElse(null);

        if (external != null) {
            builder.catalog(external);
        } else {
            tools.orderedStream().forEach(registry::register);
            builder.registry(registry);
        }
        return builder.build();
    }
}
