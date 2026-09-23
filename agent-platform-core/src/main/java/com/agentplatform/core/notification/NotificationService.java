package com.agentplatform.core.notification;

import com.agentplatform.common.dto.PageResult;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.model.entity.SysNotification;
import com.agentplatform.model.enums.NotificationLevel;
import com.agentplatform.model.enums.NotificationType;
import com.agentplatform.model.repository.SysNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知服务。
 *
 * <h3>两组方法，失败语义完全不同（这是本类最要紧的约定）</h3>
 * <ul>
 *   <li><b>发送类</b>（{@code notify*}）：由业务埋点调用，<b>任何失败都只记日志、绝不抛出</b>。
 *       通知是"锦上添花"，不能让一次通知写入失败连累真正的主流程 ——
 *       与 {@code AuditAspect} 的处理原则一致。</li>
 *   <li><b>操作类</b>（{@code list} / {@code unreadCount} / {@code markRead} / {@code delete}）：
 *       由用户主动触发，<b>失败就该抛出</b>，否则用户点了"全部已读"却发现没生效、也没提示。</li>
 * </ul>
 *
 * <h3>为什么发送类用 REQUIRES_NEW 独立事务</h3>
 * <p>两个理由，缺一不可：</p>
 * <ol>
 *   <li><b>防污染</b>：若加入调用方事务，一旦这里的 save 失败会把外层事务标记成
 *       rollback-only，于是<b>调用方在提交时莫名报错</b> —— 明明业务逻辑是对的。
 *       审计切面当初就是踩了这个坑才改用独立事务。</li>
 *   <li><b>要留痕</b>：像"越权尝试被拒"这类通知，即使外层业务最终回滚，
 *      通知本身也应当留下（用户需要知道"有人试过"）。</li>
 * </ol>
 * <p>代价是每次发送多占一个连接。通知量级很小（且有去重窗口兜底），这个代价可以接受。</p>
 *
 * <h3>去重窗口</h3>
 * <p>像"配额超限"这类事件会在<b>每次调用</b>时触发，不做去重会瞬间灌满收件箱
 * （用户点一次列表就多几十条同样的"配额已用尽"）。所以同一收件人 + 同类型 + 同标题
 * 在 {@link #DEDUP_WINDOW} 内只落一条。窗口期内的重复触发被静默丢弃，
 * 这对"提醒"语义是合理的：用户不需要知道它触发了 37 次。</p>
 */
@Slf4j
@Service
public class NotificationService {

    /** 去重窗口：同收件人 + 同类型 + 同标题，在此时间内只发一条。 */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

    /** 默认保留天数（供 purge 使用）。 */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /** 单页最大条数，防止前端传超大 size 把库拖垮。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 截断上限，与建表列宽一致 —— 拼接出来的文案可能意外超长，宁可截断也不要写库失败。 */
    private static final int MAX_TITLE = 200;
    private static final int MAX_CONTENT = 1000;
    private static final int MAX_LINK = 300;

    private final SysNotificationRepository repository;

    /**
     * 仓储可选注入：单测与"无 DB 的最小依赖场景"下为 null，此时发送类静默跳过、
     * 查询类返回空结果（沿用 {@code QuotaService} / {@code LogService} 的既有范式）。
     */
    public NotificationService(@Autowired(required = false) SysNotificationRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------ 发送（埋点用）

    /**
     * 给**当前登录用户**发一条通知（业务埋点最常用的入口）。
     *
     * <p>租户与收件人取自 {@link RbacContext}；拿不到请求上下文（如异步线程）时
     * 收件人为空 → 直接跳过，不会写入一条"无主"的通知。</p>
     */
    public void notifyCurrent(NotificationType type, NotificationLevel level,
                              String title, String content, String link) {
        notify(RbacContext.tenantId(), RbacContext.userId(), type, level, title, content, link);
    }

    /**
     * 给指定收件人发一条通知。
     *
     * <p><b>绝不抛异常</b>：通知写不进去不该影响调用它的业务。
     * 详见类注释"两组方法，失败语义完全不同"。</p>
     *
     * @param recipientId 收件人用户 ID；为空则直接跳过（点对点模型，没有"广播"这种退路）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(String tenantId, String recipientId, NotificationType type,
                       NotificationLevel level, String title, String content, String link) {
        try {
            if (repository == null) {
                return;
            }
            String recipient = blankToNull(recipientId);
            if (recipient == null) {
                // 点对点模型下没有收件人就没有意义 —— 不写"无主通知"（那会变成所有人都能看到的脏数据）
                log.debug("[notice] 跳过：无收件人。title={}", title);
                return;
            }
            String tid = blankToNull(tenantId) == null ? "default" : tenantId;
            String safeTitle = truncate(blankToNull(title) == null ? "(无标题)" : title, MAX_TITLE);

            if (isDuplicate(tid, recipient, type, safeTitle)) {
                log.debug("[notice] 跳过重复通知：{} / {}", recipient, safeTitle);
                return;
            }

            repository.save(SysNotification.builder()
                    .notificationId(IdGenerator.generate("notice"))
                    .tenantId(tid)
                    .recipientId(recipient)
                    .type(type == null ? NotificationType.system : type)
                    .level(level == null ? NotificationLevel.info : level)
                    .title(safeTitle)
                    .content(truncate(content, MAX_CONTENT))
                    .link(truncate(link, MAX_LINK))
                    .build());
            log.info("[notice] 已发送通知给 {}：{}", recipient, safeTitle);
        } catch (Exception e) {
            // 吞掉：通知失败不连累业务。用 warn 而非 error —— 它不影响任何功能可用性。
            log.warn("[notice] 写入通知失败（已忽略，不影响主流程）：{}", e.getMessage());
        }
    }

    /** 去重判定：窗口内是否已有同类型 + 同标题的通知。查询失败按"不重复"处理（宁可多一条也别少）。 */
    private boolean isDuplicate(String tenantId, String recipientId, NotificationType type, String title) {
        try {
            return !repository.findByTenantIdAndRecipientIdAndTypeAndTitleAndCreatedAtAfter(
                    tenantId, recipientId, type, title, LocalDateTime.now().minus(DEDUP_WINDOW)).isEmpty();
        } catch (Exception e) {
            log.debug("[notice] 去重查询失败，按不重复处理：{}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 查询（用户操作用）

    /** 未读数（侧栏角标用，只查 count、不拉列表）。 */
    public long unreadCount(String tenantId, String recipientId) {
        if (repository == null || blankToNull(recipientId) == null) {
            return 0L;
        }
        return repository.countByTenantIdAndRecipientIdAndReadAtIsNull(tenantId, recipientId);
    }

    /**
     * 收件箱分页。
     *
     * @param unreadOnly true = 只看未读
     */
    public PageResult<Map<String, Object>> list(String tenantId, String recipientId,
                                                boolean unreadOnly, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest req = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (repository == null || blankToNull(recipientId) == null) {
            return new PageResult<>(List.of(), 0, p, s, 0);
        }
        Page<SysNotification> result = unreadOnly
                ? repository.findByTenantIdAndRecipientIdAndReadAtIsNull(tenantId, recipientId, req)
                : repository.findByTenantIdAndRecipientId(tenantId, recipientId, req);

        List<Map<String, Object>> items = new ArrayList<>();
        for (SysNotification n : result.getContent()) {
            items.add(toView(n));
        }
        return new PageResult<>(items, result.getTotalElements(), p, s, result.getTotalPages());
    }

    /** 实体 → 对外视图。字段名用 snake_case，与项目既有接口契约一致。 */
    private Map<String, Object> toView(SysNotification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notification_id", n.getNotificationId());
        m.put("type", n.getType() == null ? null : n.getType().name());
        m.put("level", n.getLevel() == null ? null : n.getLevel().name());
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("link", n.getLink());
        m.put("read", n.getReadAt() != null);
        m.put("read_at", n.getReadAt());
        m.put("created_at", n.getCreatedAt());
        return m;
    }

    // ------------------------------------------------------------------ 标记与删除（用户操作用）

    /**
     * 标记一条已读。
     *
     * <p>查询条件带上 {@code recipientId} —— 不能只按 {@code notificationId} 查，
     * 否则 A 能改 B 的已读状态（那是个越权漏洞，而不是"操作成功但没变化"）。</p>
     *
     * @return 是否命中（false = 不存在或不属于该收件人）
     */
    @Transactional
    public boolean markRead(String tenantId, String recipientId, String notificationId) {
        if (repository == null) {
            return false;
        }
        // 三要素一起进查询：隔离做在 SQL 层，查不到就是"不存在或不属于你"，不必再比对
        return repository.findByNotificationIdAndTenantIdAndRecipientId(
                        notificationId, tenantId, recipientId)
                .map(n -> {
                    if (n.getReadAt() == null) {
                        n.setReadAt(LocalDateTime.now());
                        repository.save(n);
                    }
                    return true;
                })
                .orElse(false);
    }

    /**
     * 全部标记已读。
     *
     * @return 实际被改动的条数（已读的不会重复计入）
     */
    @Transactional
    public int markAllRead(String tenantId, String recipientId) {
        if (repository == null || blankToNull(recipientId) == null) {
            return 0;
        }
        List<SysNotification> unread =
                repository.findByTenantIdAndRecipientIdAndReadAtIsNull(tenantId, recipientId);
        LocalDateTime now = LocalDateTime.now();
        unread.forEach(n -> n.setReadAt(now));
        repository.saveAll(unread);
        return unread.size();
    }

    /** 删除一条（同样按三要素查，删不了别人的）。 */
    @Transactional
    public boolean delete(String tenantId, String recipientId, String notificationId) {
        if (repository == null) {
            return false;
        }
        return repository.findByNotificationIdAndTenantIdAndRecipientId(
                        notificationId, tenantId, recipientId)
                .map(n -> {
                    repository.delete(n);
                    return true;
                })
                .orElse(false);
    }

    /** 清理超过保留期的通知（{@code notice:manage} 管控）。 */
    @Transactional
    public int purge(int retentionDays) {
        if (repository == null) {
            return 0;
        }
        int days = retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
        return (int) repository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(days));
    }

    // ------------------------------------------------------------------ 小工具

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
