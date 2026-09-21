package com.agentplatform.core.audit;

import com.agentplatform.core.audit.AuditDtos.PurgeResult;
import com.agentplatform.core.audit.AuditDtos.AuditLogView;
import com.agentplatform.model.entity.SysAuditLog;
import com.agentplatform.model.repository.SysAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计服务：写入 + 查询 + 清理。
 *
 * <h3>写入路径为什么**不加** {@code @Transactional}（重要，别"优化"掉）</h3>
 * 审计失败绝不能连累业务，所以 {@link #record} 决定不向外抛异常。但如果在方法上加
 * {@code @Transactional} 再 {@code try/catch} 吞掉异常，会踩一个经典陷阱：
 * <ul>
 *   <li>{@code save()} 抛异常 → 当前事务被标记 <b>rollback-only</b>；</li>
 *   <li>异常被我们 catch 掉，方法"正常"返回 → 事务管理器尝试<b>提交</b>；</li>
 *   <li>提交时因 rollback-only 抛 {@code UnexpectedRollbackException} ——
 *       异常从<b>方法外面</b>冒出来，我们那个 catch 根本拦不住，业务照样失败。</li>
 * </ul>
 * 而不加外层事务时，{@code SimpleJpaRepository.save()} 自带的事务会在 save 内部完成
 * 提交/回滚，异常在 save 处抛出、被我们接住，整个过程干净。
 *
 * <p>另外这也意味着：**业务事务回滚时审计记录可能已经独立提交**（显示为成功）。
 * 这是审计的经典取舍 —— 接受它，因为"记了一笔但其实回滚了"远好于"因为审计失败而业务失败"。</p>
 *
 * <h3>查询一律带租户</h3>
 * 审计数据跨租户可见是严重问题（能看到别的租户谁改了什么），所以每个查询都强制带 tenantId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final SysAuditLogRepository repository;

    /**
     * 写入一条审计记录。**绝不抛异常** —— 审计坏了不能连累业务。
     *
     * @see AuditService 类注释里关于事务的说明
     */
    public void record(SysAuditLog entry) {
        try {
            repository.save(entry);
        } catch (Exception e) {
            log.warn("[audit] 写审计失败（已忽略，不影响业务）：{}", e.getMessage());
        }
    }

    /** 列表页查询（带动作/操作人/结果/时间范围筛选）。 */
    @Transactional(readOnly = true)
    public Page<AuditLogView> search(String tenantId, String action, String userId, Boolean success,
                                     LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return repository.search(tenantId, blankToNull(action), blankToNull(userId), success, from, to, pageable)
                .map(AuditService::toView);
    }

    /**
     * 某个对象的操作历史（"这个智能体被谁改过"）。
     *
     * <p>答辩演示用：按 {@code targetType + targetId} 一查，就是一条完整的人-事-时链路。</p>
     */
    @Transactional(readOnly = true)
    public List<AuditLogView> timelineOf(String tenantId, String targetType, String targetId) {
        List<SysAuditLog> rows = repository
                .findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(tenantId, targetType, targetId);
        List<AuditLogView> out = new ArrayList<>(rows.size());
        for (SysAuditLog r : rows) {
            out.add(toView(r));
        }
        return out;
    }

    /**
     * 清理 {@code keepDays} 天之前的记录。
     *
     * <p>审计表会随写操作线性增长，必须有个保留期出口。但它**只能整段按时间删**，
     * 不允许按条删除 —— 那等于给了"抹掉某一条追责线索"的能力。</p>
     */
    @Transactional
    public PurgeResult purge(String tenantId, int keepDays) {
        int days = keepDays <= 0 ? 90 : keepDays;
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        int deleted = repository.deleteBefore(tenantId, before);
        log.warn("[audit] 已清理 {} 之前的审计记录 {} 条（保留 {} 天）", before, deleted, days);
        return new PurgeResult(deleted, before.toString(), days);
    }

    @Transactional(readOnly = true)
    public long count(String tenantId) {
        return repository.countByTenantId(tenantId);
    }

    private static AuditLogView toView(SysAuditLog a) {
        return new AuditLogView(a.getAuditId(), a.getUserId(), a.getUsername(), a.getRoles(),
                a.getAction(), a.getTargetType(), a.getTargetId(),
                a.getMethod(), a.getUri(), a.getHttpStatus(), Boolean.TRUE.equals(a.getSuccess()),
                a.getErrorMsg(), a.getIp(), a.getUserAgent(), a.getDurationMs(), a.getDetail(),
                a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
