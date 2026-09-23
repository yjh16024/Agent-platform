package com.agentplatform.core.tool.approval;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 审批唤醒通道：**有 Redis 就跨实例广播，没有就退化为单进程**。
 *
 * <h3>为什么用一个类自适应，而不是两个实现 + 条件注解</h3>
 * 最初想写成 {@code LocalOnlyWakeChannel} + {@code RedisWakeChannel} 两个 Bean，
 * 用 {@code @ConditionalOnMissingBean} 二选一。但那个注解**只在 {@code @Configuration}
 * 的 {@code @Bean} 方法上生效**，标在 {@code @Component} 上不起作用 ——
 * 结果是两个 Bean 同时存在、注入点直接报"唯一的 bean"错误。
 * 用 {@code @ConditionalOnBean} 则会踩自动配置的时序问题（{@code StringRedisTemplate}
 * 可能在本类之后才注册）。
 *
 * <p>而这个类要做的判断本身就很简单（"有没有 Redis 连接"），
 * 塞进一个类里反而更清楚、更没有坑。{@link ApprovalWakeChannel} 接口仍保留，
 * 将来真要替换实现时不必改 {@code ApprovalService}。</p>
 *
 * <h3>没有 Redis 时为什么完全不做广播</h3>
 * 没有 Redis 的部署（桌面版就是）**必然是单实例** —— 批准与等待在同一进程，
 * 直接唤醒即可，广播是纯浪费。所以这里的"降级"不是功能缺失，而是正确的最小行为。
 */
@Slf4j
@Component
public class RedisAwareWakeChannel implements ApprovalWakeChannel {

    /** 广播通道名。前缀与其他 Redis key 保持一致（{@code ap:}）。 */
    private static final String CHANNEL = "ap:approval:wake";

    /** 可选依赖：没有 Redis 时本类退化为单进程模式（不是错误）。 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 收到广播时要执行的本地处理（通常就是去唤醒等待表）。 */
    private final List<Consumer<String>> handlers = new CopyOnWriteArrayList<>();

    private RedisMessageListenerContainer container;

    @PostConstruct
    void start() {
        if (redisTemplate == null) {
            log.debug("[approval] 未配置 Redis，审批唤醒为单进程模式（单实例部署下这是正确行为）");
            return;
        }
        RedisConnectionFactory factory;
        try {
            factory = redisTemplate.getConnectionFactory();
        } catch (Exception e) {
            log.warn("[approval] 取 Redis 连接工厂失败，审批唤醒退回单进程模式：{}", e.getMessage());
            return;
        }
        if (factory == null) {
            log.debug("[approval] Redis 连接工厂为空，审批唤醒为单进程模式");
            return;
        }
        try {
            // 自己维护一个监听容器（项目里没有现成的 RedisMessageListenerContainer Bean）。
            // 必须显式 afterPropertiesSet()/start()：这不是 Spring 托管的 Bean，
            // 容器不会自动帮我们调用生命周期方法。
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(factory);
            c.addMessageListener((message, pattern) -> {
                String approvalId = new String(message.getBody(), StandardCharsets.UTF_8);
                for (Consumer<String> h : handlers) {
                    try {
                        h.accept(approvalId);
                    } catch (Exception e) {
                        // 单个回调出错不能影响其它回调，更不能让监听线程挂掉
                        log.warn("[approval] 处理唤醒广播失败（{}）：{}", approvalId, e.getMessage());
                    }
                }
            }, new ChannelTopic(CHANNEL));
            c.afterPropertiesSet();
            c.start();
            this.container = c;
            log.info("[approval] 审批唤醒已启用 Redis 广播（通道 {}）—— 支持多实例部署", CHANNEL);
        } catch (Exception e) {
            this.container = null;
            log.warn("[approval] Redis 广播启动失败，审批唤醒退回单进程模式（多实例下会表现为等待超时）：{}",
                    e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (container != null) {
            try {
                container.destroy();
            } catch (Exception e) {
                log.debug("[approval] 关闭 Redis 监听容器失败：{}", e.getMessage());
            }
        }
    }

    @Override
    public void publish(String approvalId) {
        if (redisTemplate == null || container == null || approvalId == null) {
            return;   // 单进程模式：本进程已经直接唤醒过了，无需广播
        }
        try {
            redisTemplate.convertAndSend(CHANNEL, approvalId);
        } catch (Exception e) {
            // 广播失败只影响"别的实例上的等待者"（它们会等到超时降级），
            // 不该让当前这条正在处理用户请求的链路跟着失败。
            log.debug("[approval] 广播唤醒失败（不影响本实例）：{}", e.getMessage());
        }
    }

    @Override
    public void onWake(Consumer<String> handler) {
        if (handler != null) {
            handlers.add(handler);
        }
    }
}
