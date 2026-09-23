package com.agentplatform.plugin.sdk;

import com.agentplatform.plugin.sdk.model.DomainEvent;

import java.util.Set;

/**
 * 领域事件订阅 SPI —— 插件借此"监听系统里发生的事"并作出反应。
 *
 * <p>与 {@link AgentHook} 的区别：钩子拦的是<b>管线的特定环节</b>（可改写输入输出），
 * 事件订阅是"某件事已经发生"的<b>事后通知</b>（不能改变已发生的事）。
 * 要"介入并改写"用钩子；要"知道然后去做别的"用事件。</p>
 *
 * <h3>典型用法</h3>
 * <pre>
 *   public class SlackNotifyPlugin implements EventSubscriber {
 *       public Set&lt;String&gt; eventTypes() {
 *           return Set.of(EventTypes.AGENT_RUN_FAILED);   // 只关心运行失败
 *       }
 *       public void onEvent(DomainEvent event) {
 *           // 发 Slack 告警、上报监控、记录外部审计……
 *       }
 *   }
 * </pre>
 *
 * <h3>三条必须知道的约束</h3>
 * <ol>
 *   <li><b>只能订阅 agent 级事件</b>（{@link com.agentplatform.plugin.sdk.model.EventTypes#PLUGIN_SUBSCRIBABLE}）。
 *       租户级事件（配额超限、权限拒绝）没有 agent 维度，派发给插件会打破"插件按智能体隔离"
 *       这一模型 —— 那等于任何一台智能体上的插件都能监听全租户的行为。
 *       声明了不可订阅的类型不会报错，但收不到事件，宿主会在注册时记一条 warn。</li>
 *   <li><b>只收到"挂载了本插件的那台智能体"的事件</b>。挂到 A 的插件不会收到 B 运行失败的通知。</li>
 *   <li><b>onEvent 抛异常会被宿主捕获</b>，只记日志，不影响其它订阅者，更不影响主流程。
 *       但也不要有意依赖这一点 —— 长时间阻塞会拖慢事件派发线程。</li>
 * </ol>
 */
public interface EventSubscriber extends Plugin {

    /**
     * 声明订阅哪些事件类型（取值见
     * {@link com.agentplatform.plugin.sdk.model.EventTypes}）。
     *
     * <p>必须<b>无副作用且可反复调用</b>：宿主在注册与诊断时都会调它。</p>
     *
     * @return 事件类型集合；不订阅任何事件时返回空集合（不要返回 null）
     */
    Set<String> eventTypes();

    /**
     * 事件到达。
     *
     * <p>在<b>发布事件的线程</b>上同步调用 —— 所以别做重活（长耗时 HTTP、大文件写入）。
     * 需要异步处理请自己投递到线程池，并注意宿主不负责等待它完成。</p>
     *
     * @param event 事件内容
     */
    void onEvent(DomainEvent event);
}
