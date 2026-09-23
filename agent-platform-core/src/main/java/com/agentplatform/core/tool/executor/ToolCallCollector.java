package com.agentplatform.core.tool.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按**运行**收集工具调用记录（工具调用可视化的数据来源）。
 *
 * <h3>为什么是按 runId 的容器，而不是把记录层层传出去</h3>
 * 平台有**两条**工具执行路径：自研适配器的 {@code AgentRuntimeService.runToolLoop}，
 * 以及 Spring AI 的原生 tool-role 循环（{@code SpringAiModelAdapter} → {@code SpringAiToolBridge}）
 * —— 而后者在内层，返回值只能穿过 {@code ModelAdapter.ChatResponse} 才能到达运行时服务，
 * 那意味着改 record 定义、连带改所有适配器。
 *
 * <p>它们的**唯一汇聚点**是 {@link ToolExecutor#run}：两条路径都经它执行工具。
 * 所以在那一处按 {@code runId} 记一笔，两条路径就都覆盖了，且不必改动任何适配器。</p>
 *
 * <h3>为什么不用 ScopedValue 传递</h3>
 * 项目里 {@code TraceContext} 用的是 {@code ScopedValue}，看起来更适合。但流式链路
 * 会 {@code subscribeOn(虚拟线程)}：{@code ScopedValue} **不会**自动继承到新线程
 * （不同于 ThreadLocal 的 inheritable），作用域建在外层线程时，内层虚拟线程上取不到。
 * 而按 runId 的容器对执行线程没有任何要求。
 *
 * <h3>防泄漏：不依赖调用方"记得清理"</h3>
 * 正常路径由调用方 {@code drain} 取走并移除。但异常路径可能漏掉，
 * 所以 {@link #begin} 会**顺带清理超时的残留** —— 这样即使某条路径忘了收，
 * 也不会变成永久堆积。安全与可观测组件都不该依赖"调用方总会做对"。
 */
@Slf4j
@Component
public class ToolCallCollector {

    /**
     * 单次运行保留的调用条数上限。
     *
     * <p>工具循环本身有轮次上限（{@code MAX_TOOL_ROUNDS}），正常到不了这个数；
     * 设它是为了防"某个工具被反复调用"把响应撑爆 —— 记录只用于展示，
     * 超出部分丢掉不影响模型侧（模型的上下文由 tool-role 消息负责，与此无关）。</p>
     */
    private static final int MAX_PER_RUN = 50;

    /** 残留判定阈值：超过这么久还没被取走，视为"调用方漏了"，下次 begin 时清掉。 */
    private static final long STALE_MILLIS = 30 * 60 * 1000L;

    private final Map<String, List<ToolCallRecord>> byRun = new ConcurrentHashMap<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    /** 运行开始（幂等：重复 begin 会重置该运行的记录）。 */
    public void begin(String runId) {
        if (runId == null) {
            return;
        }
        evictStale();
        byRun.put(runId, Collections.synchronizedList(new ArrayList<>()));
        startedAt.put(runId, System.currentTimeMillis());
    }

    /**
     * 记录一次工具调用。
     *
     * <p>没有 {@code begin} 过（或 runId 为空）时**静默忽略**：
     * 工具也可能被非运行链路调用（工作流节点、管理接口的连通性测试），
     * 那些场景没有"展示给对话用户"的语义，不该因此报错。</p>
     */
    public void record(String runId, ToolCallRecord record) {
        if (runId == null || record == null) {
            return;
        }
        List<ToolCallRecord> list = byRun.get(runId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            if (list.size() < MAX_PER_RUN) {
                list.add(record);
            }
        }
    }

    /**
     * 取走某次运行的全部记录并移除（运行结束时调用）。
     *
     * @return 只读列表；没有记录时返回空列表（不是 null，调用方无需判空）
     */
    public List<ToolCallRecord> drain(String runId) {
        if (runId == null) {
            return List.of();
        }
        startedAt.remove(runId);
        List<ToolCallRecord> list = byRun.remove(runId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    /** 丢弃某次运行的记录（异常/放弃路径，避免残留）。 */
    public void discard(String runId) {
        if (runId != null) {
            startedAt.remove(runId);
            byRun.remove(runId);
        }
    }

    /** 清理长期未被取走的残留（见类注释"防泄漏"）。 */
    private void evictStale() {
        long now = System.currentTimeMillis();
        startedAt.entrySet().removeIf(e -> {
            Long started = e.getValue();
            if (started != null && now - started > STALE_MILLIS) {
                byRun.remove(e.getKey());
                log.debug("[tool] 清理未被取走的工具调用记录：run={}", e.getKey());
                return true;
            }
            return false;
        });
    }

    /** 当前正在收集的运行数（供诊断/测试）。 */
    public int activeRuns() {
        return byRun.size();
    }
}
