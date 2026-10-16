package com.agentplatform.core.tool.executor;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.multimodal.QuotaService;
import com.agentplatform.core.settings.PlatformSettingRegistry;
import com.agentplatform.core.settings.PlatformSettingService;
import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 限流过滤器（责任链第三环）—— 按租户限制**工具调用速率**。
 *
 * <h3>为什么单独限工具调用</h3>
 * 平台已有 {@code model_calls}（模型调用数）的配额，但它约束不了"一次对话里工具被反复调用"
 * 这种放大：模型陷入循环时，一轮对话可以在几十秒内打出几十次工具调用
 * （每次都是一次网络请求 / 一次子进程 / 一次文件扫描），而 {@code model_calls} 只记了 1。
 * 所以这里按 {@code tool_calls} 计数，周期用**分钟**（比日配额细得多，
 * 才拦得住"短时间打爆"这种形态）。
 *
 * <h3>复用而不是另造一套</h3>
 * 直接复用 {@code QuotaService}（Redis INCR + TTL，Redis 缺失时内存兜底）——
 * 它的降级行为、通知、事件都已经处理过，另写一套计数只会多一处不一致。
 *
 * <p><b>显式传限额，不依赖默认配额表</b>：{@code QuotaService} 的默认表里没有
 * {@code tool_calls} 这一类型，走"表里查不到"的路径行为不确定（可能是 0，那就变成全都超限）。
 * 这里始终把配置里的值显式传进去。</p>
 */
@Slf4j
@Component
@Order(30)
public class RateLimitToolFilter implements ToolFilter {

    /** 可选依赖：缺失（如单测、最小环境）时本环直接放行。 */
    @Autowired(required = false)
    private QuotaService quotaService;

    /** 总开关（默认开）。 */
    @Value("${agent-platform.tool.rate-limit.enabled:true}")
    private boolean enabled = true;

    /**
     * 平台设置服务（「平台设置」页可热切换开关与阈值）。
     * <p>字段注入 {@code required=false}：单测 / 最小环境里没有它时回落到上面的配置值。</p>
     */
    @Autowired(required = false)
    private PlatformSettingService platformSettings;

    /** 总开关的**运行期**取值：页面（覆盖文件）优先，其次配置文件 / 环境变量。 */
    private boolean enabled() {
        return platformSettings == null
                ? enabled
                : platformSettings.bool(PlatformSettingRegistry.TOOL_RATE_LIMIT_ENABLED, enabled);
    }

    /**
     * 每租户**每分钟**的工具调用上限。
     *
     * <p>默认 600 偏宽松，是刻意的：先拦"明显失控"（模型循环），
     * 而不是给正常的多步任务制造摩擦 —— 一个 20 轮的编程任务正常就要几十次调用。
     * 要收紧就改这个值。</p>
     */
    @Value("${agent-platform.tool.rate-limit.per-minute:600}")
    private long perMinute = 600L;

    /** 阈值的**运行期**取值（「平台设置」页可热切换）：页面优先，其次配置值。 */
    private long perMinute() {
        return platformSettings == null
                ? perMinute
                : platformSettings.longValue(PlatformSettingRegistry.TOOL_RATE_LIMIT_PER_MINUTE, perMinute);
    }

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        // 取一次快照：限流判断与拒绝文案必须用同一个值（否则用户看到的"上限"可能与实际拦截的不一致）
        long limit = perMinute();
        if (!enabled() || quotaService == null || limit <= 0) {
            return chain.apply(invocation);
        }
        String tenantId = invocation.ctx() == null ? null : invocation.ctx().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 租户缺失由 AuthToolFilter 先拦；这里放行以免两处报同一个错
            return chain.apply(invocation);
        }
        try {
            quotaService.checkAndIncrement(tenantId, "tool_calls", limit, "minute");
        } catch (BizException e) {
            log.warn("[tool] 触发限流：tenant={} tool={} limit={}/min",
                    tenantId, invocation.toolName(), limit);
            return ToolResult.fail("工具调用过于频繁，已被限流（上限 " + limit + " 次/分钟）。"
                    + "请减少不必要的工具调用，或在任务确实需要时告知用户稍后重试。");
        }
        return chain.apply(invocation);
    }
}
