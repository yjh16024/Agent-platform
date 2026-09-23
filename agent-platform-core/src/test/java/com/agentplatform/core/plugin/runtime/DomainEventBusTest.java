package com.agentplatform.core.plugin.runtime;

import com.agentplatform.core.tool.registry.ToolRegistry;
import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域事件总线测试。
 *
 * <p>重点验证<b>三条派发规则</b>（见 {@code DomainEventBus} 类注释）：
 * 租户级事件不派发、不可订阅类型不派发、跨智能体不派发。这三条任一条失守，
 * 都会变成"插件能监听到不属于它的行为"这类隔离问题。</p>
 */
class DomainEventBusTest {

    /** 替身订阅者：记录收到的事件，便于断言。 */
    static class RecordingSubscriber implements EventSubscriber {

        private final String id;
        private final Set<String> types;
        final List<DomainEvent> received = new ArrayList<>();
        /** 设为 true 时 onEvent 抛异常（验证单个订阅者出错不影响别人）。 */
        boolean throwing = false;

        RecordingSubscriber(String id, Set<String> types) {
            this.id = id;
            this.types = types;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public void onAttach(PluginContext ctx) {
        }

        @Override
        public void onDetach(PluginContext ctx) {
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void onEvent(DomainEvent event) {
            if (throwing) {
                throw new IllegalStateException("订阅者自己的 bug");
            }
            received.add(event);
        }
    }

    private ExtensionRegistry registry;
    private DomainEventBus bus;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry(new ToolRegistry());
        bus = new DomainEventBus(registry);
    }

    /** 把订阅者挂到某智能体上（走真实的 registerPlugin 路径，顺带覆盖 instanceof 识别）。 */
    private void attach(String agentId, RecordingSubscriber sub) {
        registry.runInAttachScope(agentId, sub.id(), () -> {
            PluginContext ctx = new PluginContext(agentId, "t1", null, registry);
            registry.registerPlugin(sub, ctx);
        });
    }

    private static DomainEvent runFailed(String agentId) {
        return DomainEvent.ofAgent(EventTypes.AGENT_RUN_FAILED, "t1", agentId, "r1",
                DomainEvent.payload("agent_name", "测试智能体", "error", "boom"));
    }

    // ------------------------------------------------------------------ 正常派发

    @Test
    @DisplayName("agent 级可订阅事件 → 派发给该智能体上的订阅者")
    void dispatchesToSubscriber() {
        RecordingSubscriber sub = new RecordingSubscriber("p1",
                Set.of(EventTypes.AGENT_RUN_FAILED));
        attach("a1", sub);

        bus.publish(runFailed("a1"));

        assertEquals(1, sub.received.size());
        assertEquals(EventTypes.AGENT_RUN_FAILED, sub.received.get(0).type());
        assertEquals("测试智能体", sub.received.get(0).string("agent_name"));
    }

    @Test
    @DisplayName("只订阅了 A 类型 → 不接收 B 类型")
    void onlySubscribedTypesDelivered() {
        RecordingSubscriber sub = new RecordingSubscriber("p1",
                Set.of(EventTypes.AGENT_RUN_COMPLETED));
        attach("a1", sub);

        bus.publish(runFailed("a1"));

        assertTrue(sub.received.isEmpty(), "未订阅的类型不该派发");
    }

    @Test
    @DisplayName("同一事件有多个订阅者时全部收到")
    void dispatchesToAllSubscribers() {
        RecordingSubscriber s1 = new RecordingSubscriber("p1", Set.of(EventTypes.AGENT_RUN_FAILED));
        RecordingSubscriber s2 = new RecordingSubscriber("p2", Set.of(EventTypes.AGENT_RUN_FAILED));
        attach("a1", s1);
        attach("a1", s2);

        bus.publish(runFailed("a1"));

        assertEquals(1, s1.received.size());
        assertEquals(1, s2.received.size());
    }

    // ------------------------------------------------------------------ 派发规则（三条）

    @Test
    @DisplayName("规则 1：租户级事件（无 agentId）→ 不派发给插件")
    void tenantScopedEventsAreNotDispatched() {
        // 订阅者声明订阅配额事件（虽然它不在可订阅清单里，这里直接构造租户级事件验证总线行为）
        RecordingSubscriber sub = new RecordingSubscriber("p1", Set.of(EventTypes.QUOTA_EXCEEDED));
        attach("a1", sub);

        bus.publish(DomainEvent.ofTenant(EventTypes.QUOTA_EXCEEDED, "t1",
                DomainEvent.payload("quota_type", "model_calls")));

        assertTrue(sub.received.isEmpty(), "租户级事件不该派发给插件（会打破按智能体隔离）");
    }

    @Test
    @DisplayName("规则 2：不在可订阅清单内的事件类型 → 不派发")
    void nonSubscribableTypesAreNotDispatched() {
        RecordingSubscriber sub = new RecordingSubscriber("p1", Set.of(EventTypes.PERMISSION_DENIED));
        attach("a1", sub);

        // 故意构造一个"带 agentId 但类型不可订阅"的事件
        bus.publish(DomainEvent.ofAgent(EventTypes.PERMISSION_DENIED, "t1", "a1", "r1", Map.of()));

        assertTrue(sub.received.isEmpty());
    }

    @Test
    @DisplayName("规则 3：跨智能体隔离 → 挂到 a1 的订阅者收不到 a2 的事件")
    void subscribersAreIsolatedPerAgent() {
        RecordingSubscriber sub = new RecordingSubscriber("p1", Set.of(EventTypes.AGENT_RUN_FAILED));
        attach("a1", sub);

        bus.publish(runFailed("a2"));

        assertTrue(sub.received.isEmpty(), "挂到 a1 的插件不该收到 a2 的事件");
    }

    @Test
    @DisplayName("声明了不可订阅类型 → 注册被跳过，且不影响其它可订阅类型")
    void registeringNonSubscribableTypeIsSkipped() {
        RecordingSubscriber sub = new RecordingSubscriber("p1",
                Set.of(EventTypes.QUOTA_EXCEEDED, EventTypes.AGENT_RUN_FAILED));
        attach("a1", sub);

        // 可订阅的那一个照常生效
        bus.publish(runFailed("a1"));
        assertEquals(1, sub.received.size());

        // 不可订阅的那一个没有登记 —— 从诊断接口也看不到
        List<String> names = registry.subscriberNamesOf("a1");
        assertTrue(names.stream().anyMatch(s -> s.contains(EventTypes.AGENT_RUN_FAILED)));
        assertFalse(names.stream().anyMatch(s -> s.contains(EventTypes.QUOTA_EXCEEDED)));
    }

    // ------------------------------------------------------------------ 健壮性

    @Test
    @DisplayName("单个订阅者抛异常 → 不影响其它订阅者，也不向发布方抛出")
    void brokenSubscriberDoesNotAffectOthers() {
        RecordingSubscriber broken = new RecordingSubscriber("p1", Set.of(EventTypes.AGENT_RUN_FAILED));
        broken.throwing = true;
        RecordingSubscriber ok = new RecordingSubscriber("p2", Set.of(EventTypes.AGENT_RUN_FAILED));
        attach("a1", broken);
        attach("a1", ok);

        assertDoesNotThrow(() -> bus.publish(runFailed("a1")));
        assertEquals(1, ok.received.size(), "坏订阅者不该阻止正常订阅者收到事件");
    }

    @Test
    @DisplayName("publish(null) 与无订阅者场景都不抛异常")
    void publishIsAlwaysSafe() {
        assertDoesNotThrow(() -> bus.publish(null));
        assertDoesNotThrow(() -> bus.publish(runFailed("a1")));
    }

    @Test
    @DisplayName("卸载插件后不再收到事件（否则已下线的插件仍会被派发）")
    void unregisteredSubscriberStopsReceiving() {
        RecordingSubscriber sub = new RecordingSubscriber("p1", Set.of(EventTypes.AGENT_RUN_FAILED));
        attach("a1", sub);
        bus.publish(runFailed("a1"));
        assertEquals(1, sub.received.size());

        registry.unregisterAll("a1", sub.id());
        bus.publish(runFailed("a1"));

        assertEquals(1, sub.received.size(), "卸载后不该再收到事件");
        assertFalse(registry.isRegistered("a1", sub.id()));
    }

    @Test
    @DisplayName("声明订阅失败（eventTypes 抛异常）→ 跳过该插件，不中断挂载")
    void brokenEventTypesDeclarationIsSkipped() {
        EventSubscriber bad = new RecordingSubscriber("p_bad", Set.of()) {
            @Override
            public Set<String> eventTypes() {
                throw new IllegalStateException("声明炸了");
            }
        };
        registry.runInAttachScope("a1", bad.id(), () -> {
            PluginContext ctx = new PluginContext("a1", "t1", null, registry);
            assertDoesNotThrow(() -> registry.registerPlugin(bad, ctx));
        });
    }

    @Test
    @DisplayName("未声明任何事件类型 → 跳过注册（不报错）")
    void emptyEventTypesIsSkipped() {
        RecordingSubscriber sub = new RecordingSubscriber("p1", Set.of());
        attach("a1", sub);

        assertTrue(registry.subscriberNamesOf("a1").isEmpty());
        assertDoesNotThrow(() -> bus.publish(runFailed("a1")));
    }
}
