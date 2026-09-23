package com.agentplatform.core.plugin.runtime;

import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进程内领域事件总线 —— **面向插件的分发**。
 *
 * <h3>为什么不用既有的 {@code core.events.EventBus}</h3>
 * 那个接口<b>只有 {@code publish}、没有 {@code subscribe}</b>，唯一实现是
 * {@code KafkaEventBus}（受 {@code events.enabled=true} 控制、默认关闭且 null 静默），
 * 用途是把「配置变更 / 日志流」<b>向外发到 Kafka</b>。它是一条"发出去就不管了"的出站通道，
 * 没有订阅者概念，语义上无法承载"进程内多播给插件"这件事。
 * <p>所以本类是新机制。两者可以并存：需要外推到 Kafka 的场景继续用 {@code EventBus}，
 * 需要让插件对事件做反应则用这里。</p>
 *
 * <h3>派发规则（三条，都很关键）</h3>
 * <ol>
 *   <li><b>租户级事件不派发给插件</b>：没有 agentId 的事件（配额、权限）若派发，
 *       等于任何一台智能体上的插件都能监听全租户行为，<b>破坏按智能体隔离</b>；</li>
 *   <li><b>只派发可订阅清单内的事件类型</b>（{@link EventTypes#PLUGIN_SUBSCRIBABLE}），
 *       其余记 warn 跳过 —— 避免"订阅了却永远收不到"这种最难查的现象被静默吞掉；</li>
 *   <li><b>只派给携带的 agentId 所对应的订阅者</b>：挂到 A 的插件收不到 B 的事件。</li>
 * </ol>
 *
 * <h3>失败语义</h3>
 * <p>{@link #publish} <b>绝不抛异常</b>：它由业务埋点调用（运行完成/失败、配额超限…），
 * 事件派发失败绝不能连累主流程 —— 与 {@code NotificationService.notify} 的处理原则一致。
 * 单个订阅者抛异常也只记日志、不影响其它订阅者。</p>
 */
@Slf4j
@Component
public class DomainEventBus {

    /**
     * 唯一依赖：订阅者登记表。
     * <p>方向是单向的（总线 → 注册表），注册表不反向依赖总线，所以不存在循环依赖。</p>
     */
    private final ExtensionRegistry extensions;

    public DomainEventBus(ExtensionRegistry extensions) {
        this.extensions = extensions;
    }

    /**
     * 发布一个领域事件并派发给插件订阅者。
     *
     * <p><b>不抛异常</b>，见类注释"失败语义"。</p>
     *
     * @param event 事件；为 null 时直接返回
     */
    public void publish(DomainEvent event) {
        try {
            if (event == null) {
                return;
            }
            if (extensions == null) {
                return;
            }
            if (!event.isAgentScoped()) {
                // 租户级事件：核心模块可以直接消费（如通知），但不派发给插件，见类注释规则 1
                log.debug("[event] {} 是租户级事件，不派发给插件订阅者", event.type());
                return;
            }
            if (!EventTypes.isPluginSubscribable(event.type())) {
                log.warn("[event] 事件 {} 不在插件可订阅清单内，跳过派发（见 EventTypes.PLUGIN_SUBSCRIBABLE）",
                        event.type());
                return;
            }

            List<EventSubscriber> targets = extensions.subscribersOf(event.type(), event.agentId());
            if (targets.isEmpty()) {
                log.debug("[event] {} 在 agent={} 上没有订阅者", event.type(), event.agentId());
                return;
            }
            int ok = 0;
            for (EventSubscriber s : targets) {
                try {
                    s.onEvent(event);
                    ok++;
                } catch (Exception e) {
                    // 单个订阅者出错不影响其它订阅者，更不影响发布方
                    log.warn("[event] 订阅者 {} 处理 {} 抛异常（已忽略）：{}",
                            s.id(), event.type(), e.getMessage(), e);
                }
            }
            log.debug("[event] {} 已派发给 agent={} 的 {}/{} 个订阅者",
                    event.type(), event.agentId(), ok, targets.size());
        } catch (Exception e) {
            log.warn("[event] 派发事件失败（已忽略，绝不影响发布方主流程）：{}", e.getMessage());
        }
    }
}
