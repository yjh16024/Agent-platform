package com.agentplatform.core.multimodal;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.notification.NotificationService;
import com.agentplatform.core.plugin.runtime.DomainEventBus;
import com.agentplatform.model.entity.TenantQuota;
import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import com.agentplatform.model.repository.TenantQuotaRepository;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多租户配额服务。
 * <p>
 * 计数以 <b>Redis INCR + TTL</b> 为准（原子、可跨实例聚合，按周期自然过期），
 * 限额与累计用量以 {@code tenant_quota} 表持久化；Redis 不可用时回退到进程内
 * 内存计数，保证无外部依赖场景（本地开发 / 演示）可运行。
 * </p>
 * <p>
 * 依赖注入采用可选字段（{@code required=false}），保留无参构造——
 * 便于单元测试直接 {@code new QuotaService()}，也避免 Redis/DB 缺失时启动失败。
 * </p>
 */
@Slf4j
@Service
public class QuotaService {

    /** 默认配额（每日模型调用数等）。 */
    private static final Map<String, Long> DEFAULT_QUOTA = Map.of(
            "model_calls", 10_000L,
            "tokens", 100_000_000L,
            "files", 1_000L,
            "plugins", 100L
    );

    /** 配额类型集合（list 接口遍历用）。 */
    private static final List<String> QUOTA_TYPES = List.of("model_calls", "tokens", "files", "plugins");

    private static final String DEFAULT_PERIOD = "daily";

    /** 内存兜底计数器（Redis 不可用时）。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 可选依赖：tenant_quota 表持久化。 */
    @Autowired(required = false)
    private TenantQuotaRepository tenantQuotaRepository;

    /** 可选依赖：Redis 原子计数。 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * 可选依赖：站内通知。
     * <p>配额耗尽会**静默拦掉业务**（调用方只看到失败，不知道为什么），用户不主动查
     * 就根本不知道 —— 所以这是最该发通知的场景之一。去重窗口在 {@link NotificationService} 内。</p>
     */
    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * 可选依赖：领域事件总线。
     * <p>配额超限是**租户级事件**（没有 agentId），因此<b>不会派发给插件订阅者</b>
     * —— 那会打破按智能体隔离。这里发布是为了让核心侧订阅者（以及将来的平台级订阅者）
     * 能消费同一套事件，而不是给插件用的。</p>
     */
    @Autowired(required = false)
    private DomainEventBus domainEventBus;

    /**
     * 检查并递增用量，超限抛异常。
     *
     * @param tenantId   租户 ID
     * @param quotaType  配额类型（model_calls / tokens / files / plugins）
     * @param customLimit 自定义限额（缺省用租户表 → 默认值）
     */
    public void checkAndIncrement(String tenantId, String quotaType, Long customLimit) {
        checkAndIncrement(tenantId, quotaType, customLimit, DEFAULT_PERIOD);
    }

    /**
     * 检查并递增用量（指定周期）。
     */
    public void checkAndIncrement(String tenantId, String quotaType, Long customLimit, String period) {
        long limit = resolveLimit(tenantId, quotaType, period, customLimit);
        long used = increment(tenantId, quotaType, period);
        if (used > limit) {
            // 先发通知再抛：配额耗尽会**静默拦掉业务**，用户需要知道原因与出路。
            // 通知失败绝不影响下面要抛的业务异常 —— 两者是互不依赖的两件事，
            // 所以这里不复用同一个 try（NotificationService 内部已吞异常，此处只是明确边界）。
            notifyQuotaExceeded(quotaType, used, limit);
            publishQuotaExceeded(tenantId, quotaType, used, limit);
            throw new BizException("QUOTA_EXCEEDED",
                    "Tenant " + tenantId + " exceeded " + quotaType + " quota (" + used + "/" + limit + ")");
        }
        // 每 100 次沉一次 DB，避免每请求写库
        if (used % 100 == 0) {
            persistUsed(tenantId, quotaType, period, used, limit);
        }
    }

    /**
     * 发布「配额超限」领域事件（失败静默）。
     *
     * <p>这是<b>租户级事件</b>（{@code ofTenant}，无 agentId），所以<b>不会派发给插件订阅者</b>
     * —— 那会打破按智能体隔离。发布它是为了让核心侧/将来的平台级订阅者能消费同一套事件。</p>
     */
    private void publishQuotaExceeded(String tenantId, String quotaType, long used, long limit) {
        if (domainEventBus == null) {
            return;
        }
        try {
            domainEventBus.publish(DomainEvent.ofTenant(EventTypes.QUOTA_EXCEEDED, tenantId,
                    DomainEvent.payload("quota_type", quotaType, "used", used, "limit", limit)));
        } catch (Exception e) {
            log.debug("发布配额事件失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 发送"配额已用尽"通知（失败静默）。
     *
     * <p>收件人取当前登录用户 —— 是谁触发把业务撞到限额上的，就提醒谁。
     * 拿不到请求上下文（异步线程）时收件人为空、通知自动跳过，不会写出无主数据。</p>
     */
    private void notifyQuotaExceeded(String quotaType, long used, long limit) {
        if (notificationService == null) {
            return;
        }
        try {
            notificationService.notifyCurrent(NotificationType.quota, NotificationLevel.error,
                    "配额已用尽：" + quotaType,
                    "「" + quotaType + "」配额已用尽（" + used + "/" + limit + "），相关调用已被拦截。"
                            + "请联系管理员调整配额。",
                    "/settings");
        } catch (Exception e) {
            log.debug("发送配额通知失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 查询当前周期用量（Redis → 内存 → DB 三级回退）。
     */
    public long usage(String tenantId, String quotaType) {
        return usage(tenantId, quotaType, DEFAULT_PERIOD);
    }

    public long usage(String tenantId, String quotaType, String period) {
        long redis = tryReadRedis(tenantId, quotaType, period);
        if (redis >= 0) {
            return redis;
        }
        AtomicLong c = counters.get(memKey(tenantId, quotaType, period));
        if (c != null) {
            return c.get();
        }
        return loadUsed(tenantId, quotaType, period);
    }

    /**
     * 查询租户全部配额状态（限额 + 实时用量），供管理端查看。
     *
     * @return [{quotaType, period, limit, used}]
     */
    public List<Map<String, Object>> list(String tenantId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String type : QUOTA_TYPES) {
            Map<String, Object> row = new LinkedHashMap<>();
            long limit = resolveLimit(tenantId, type, DEFAULT_PERIOD, null);
            row.put("quotaType", type);
            row.put("period", DEFAULT_PERIOD);
            row.put("limit", limit);
            row.put("used", usage(tenantId, type, DEFAULT_PERIOD));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 设置租户某个配额类型的限额（upsert），返回持久化后的记录。
     */
    public TenantQuota setLimit(String tenantId, String quotaType, String period, long limit) {
        if (tenantQuotaRepository == null) {
            throw BizException.internal("tenant_quota persistence unavailable (DB not available)");
        }
        String p = period == null || period.isBlank() ? DEFAULT_PERIOD : period;
        TenantQuota q = tenantQuotaRepository.findByTenantIdAndQuotaTypeAndPeriod(tenantId, quotaType, p)
                .orElseGet(() -> TenantQuota.builder()
                        .tenantId(tenantId)
                        .quotaType(quotaType)
                        .period(p)
                        .used(0L)
                        .build());
        q.setQuotaLimit(limit);
        return tenantQuotaRepository.save(q);
    }

    // ---- 内部实现 ----

    private long resolveLimit(String tenantId, String quotaType, String period, Long customLimit) {
        if (customLimit != null) {
            return customLimit;
        }
        long dbLimit = loadLimit(tenantId, quotaType, period);
        if (dbLimit > 0) {
            return dbLimit;
        }
        return DEFAULT_QUOTA.getOrDefault(quotaType, Long.MAX_VALUE);
    }

    private long loadLimit(String tenantId, String quotaType, String period) {
        if (tenantQuotaRepository == null) {
            return -1;
        }
        try {
            return tenantQuotaRepository.findByTenantIdAndQuotaTypeAndPeriod(tenantId, quotaType, period)
                    .map(TenantQuota::getQuotaLimit)
                    .orElse(-1L);
        } catch (Exception e) {
            log.warn("Load quota limit failed (fallback default): {}", e.getMessage());
            return -1;
        }
    }

    private long loadUsed(String tenantId, String quotaType, String period) {
        if (tenantQuotaRepository == null) {
            return 0;
        }
        try {
            return tenantQuotaRepository.findByTenantIdAndQuotaTypeAndPeriod(tenantId, quotaType, period)
                    .map(q -> q.getUsed() == null ? 0L : q.getUsed())
                    .orElse(0L);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 原子递增：优先 Redis，风险回退内存。 */
    private long increment(String tenantId, String quotaType, String period) {
        if (redisTemplate != null) {
            try {
                String key = redisKey(tenantId, quotaType, period);
                Long v = redisTemplate.opsForValue().increment(key);
                if (v != null && v == 1L) {
                    redisTemplate.expire(key, java.time.Duration.ofSeconds(periodTtlSeconds(period)));
                }
                return v == null ? 0 : v;
            } catch (Exception e) {
                log.debug("Redis increment failed, fallback to memory: {}", e.getMessage());
            }
        }
        AtomicLong c = counters.computeIfAbsent(memKey(tenantId, quotaType, period), k -> new AtomicLong(0));
        return c.incrementAndGet();
    }

    /** 尝试读取 Redis 计数；不可用返回 -1 表示回退。 */
    private long tryReadRedis(String tenantId, String quotaType, String period) {
        if (redisTemplate == null) {
            return -1;
        }
        try {
            String s = redisTemplate.opsForValue().get(redisKey(tenantId, quotaType, period));
            return s == null ? -1 : Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private void persistUsed(String tenantId, String quotaType, String period, long used, long limit) {
        if (tenantQuotaRepository == null) {
            return;
        }
        try {
            TenantQuota q = tenantQuotaRepository.findByTenantIdAndQuotaTypeAndPeriod(tenantId, quotaType, period)
                    .orElseGet(() -> TenantQuota.builder()
                            .tenantId(tenantId)
                            .quotaType(quotaType)
                            .period(period)
                            .quotaLimit(limit)
                            .used(0L)
                            .build());
            q.setQuotaLimit(limit);
            q.setUsed(used);
            tenantQuotaRepository.save(q);
        } catch (Exception e) {
            log.debug("Persist quota usage failed: {}", e.getMessage());
        }
    }

    private String memKey(String tenantId, String quotaType, String period) {
        return tenantId + ":" + quotaType + ":" + period + ":" + periodKey(period);
    }

    private String redisKey(String tenantId, String quotaType, String period) {
        return "ap:quota:" + tenantId + ":" + quotaType + ":" + periodKey(period);
    }

    /** 周期前缀：daily → yyyyMMdd，monthly → yyyyMM。 */
    private String periodKey(String period) {
        LocalDate now = LocalDate.now();
        if ("monthly".equals(period)) {
            return String.format("%04d%02d", now.getYear(), now.getMonthValue());
        }
        return String.format("%04d%02d%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    }

    /** 周期 TTL（秒）：略大于一个周期，保证只按周期键聚合且过期自动清理。 */
    private long periodTtlSeconds(String period) {
        if ("monthly".equals(period)) {
            return 62L * 24 * 3600;
        }
        return 48L * 3600;
    }
}