package com.agentplatform.core.report;

import com.agentplatform.core.report.ReportDtos.DayCount;
import com.agentplatform.core.report.ReportDtos.LogDistribution;
import com.agentplatform.core.report.ReportDtos.NameCount;
import com.agentplatform.core.report.ReportDtos.OperationStats;
import com.agentplatform.core.report.ReportDtos.Overview;
import com.agentplatform.core.report.ReportDtos.ReportView;
import com.agentplatform.model.repository.AgentDefinitionRepository;
import com.agentplatform.model.repository.LogIndexRepository;
import com.agentplatform.model.repository.SessionRepository;
import com.agentplatform.model.repository.SysAuditLogRepository;
import com.agentplatform.model.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计报表服务。
 *
 * <h3>数据源选择（这里是有意设计）</h3>
 * <ul>
 *   <li><b>审计表</b>（{@code sys_audit_log}）是首选 —— 它是**结构化**的
 *       （action / userId / roles / success / durationMs），做统计最可靠；</li>
 *   <li><b>运行日志</b>（{@code log_index}）只用**结构化列**（category / level / agentId /
 *       timestamp）做分组，<b>绝不解析 message</b> —— 那套正则抠 latency/tokens 的方式
 *       一旦日志文案改动就会静默出错，不适合做统计依据；</li>
 *   <li>不用 Prometheus / Loki：那是**进程外**的观测栈，本地与桌面版都没有，
 *       报表必须能在"只有数据库"的环境下出数。</li>
 * </ul>
 *
 * <h3>为什么不做汇总表</h3>
 * 实时 {@code GROUP BY} 在项目这个数据量级完全够用；引入汇总表就要处理
 * "谁在什么时候刷新汇总"这个额外的一致性问题，收益不抵复杂度。
 *
 * <h3>为什么按天聚合放在 Java 里做</h3>
 * MySQL 用 {@code DATE()}、H2 用 {@code FORMATDATETIME()} —— 写 SQL 里必然会
 * "MySQL 能跑、H2 启动即报错"。所以趋势只拉 {@code (timestamp, level)} 两列回来，
 * 用 {@link LocalDateTime#toLocalDate()} 分组，两个库行为完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final LogIndexRepository logIndexRepository;
    private final SysAuditLogRepository auditLogRepository;
    private final AgentDefinitionRepository agentRepository;
    private final SessionRepository sessionRepository;
    private final SysUserRepository userRepository;

    /** 时间窗上限：再长就失去"近期概览"的意义，且会拖慢聚合。 */
    private static final int MAX_DAYS = 90;
    private static final int DEFAULT_DAYS = 7;
    /** 排行榜取前 N 名 —— 全量返回只会把界面撑满。 */
    private static final int TOP_N = 10;
    private static final String ERROR_LEVEL = "ERROR";

    @Transactional(readOnly = true)
    public ReportView build(String tenantId, int days) {
        int d = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDateTime from = LocalDateTime.now().minusDays(d);

        Overview overview = new Overview(
                agentRepository.countByTenantId(tenantId),
                sessionRepository.countByTenantId(tenantId),
                userRepository.countByTenantId(tenantId),
                auditLogRepository.countByTenantId(tenantId),
                logIndexRepository.countSince(tenantId, from),
                auditLogRepository.countSince(tenantId, from));

        LogDistribution logs = new LogDistribution(
                toNameCounts(logIndexRepository.countGroupByCategory(tenantId, from), TOP_N),
                toNameCounts(logIndexRepository.countGroupByLevel(tenantId, from), TOP_N),
                toNameCounts(logIndexRepository.countGroupByAgent(tenantId, from), TOP_N));

        OperationStats operations = buildOperations(tenantId, from);

        return new ReportView(d, overview, logs, operations, buildErrorTrend(tenantId, from, d));
    }

    // ------------------------------------------------------------------ 操作统计

    private OperationStats buildOperations(String tenantId, LocalDateTime from) {
        long total = auditLogRepository.countSince(tenantId, from);
        long failed = 0;
        // success 是布尔列，分组结果最多两行，直接在这里算失败率比再写一个 count 查询省事
        for (Object[] row : auditLogRepository.countGroupBySuccess(tenantId, from)) {
            Boolean ok = (Boolean) row[0];
            long c = toLong(row[1]);
            if (Boolean.FALSE.equals(ok)) {
                failed = c;
            }
        }
        double rate = total == 0 ? 0d : Math.round(failed * 10000.0 / total) / 100.0;
        return new OperationStats(
                toNameCounts(auditLogRepository.countGroupByAction(tenantId, from), TOP_N),
                toNameCounts(auditLogRepository.countGroupByUser(tenantId, from), TOP_N),
                total, failed, rate);
    }

    // ------------------------------------------------------------------ 错误趋势

    /**
     * 近 N 天每天的错误日志条数。
     *
     * <p><b>先把窗口内每一天填成 0</b>，再叠加真实计数 —— 否则"没有出错的那天"会整行缺失，
     * 前端画出来就是断档的曲线，看起来像数据丢失。</p>
     */
    private List<DayCount> buildErrorTrend(String tenantId, LocalDateTime from, int days) {
        Map<LocalDate, Long> byDay = new TreeMap<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), 0L);
        }

        for (Object[] row : logIndexRepository.findTimestampAndLevel(tenantId, from)) {
            LocalDateTime ts = toDateTime(row[0]);
            String level = row[1] == null ? null : String.valueOf(row[1]);
            if (ts == null || level == null || !ERROR_LEVEL.equalsIgnoreCase(level)) {
                continue;
            }
            byDay.merge(ts.toLocalDate(), 1L, Long::sum);
        }

        List<DayCount> out = new ArrayList<>(byDay.size());
        byDay.forEach((day, count) -> out.add(new DayCount(day.toString(), count)));
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    /** {@code Object[]{key, count}} → {@link NameCount}，并截断到前 N 名。 */
    private static List<NameCount> toNameCounts(List<Object[]> rows, int topN) {
        Map<String, Long> merged = new LinkedHashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                String name = row[0] == null ? "（未标记）" : String.valueOf(row[0]);
                merged.merge(name, toLong(row[1]), Long::sum);
            }
        }
        List<NameCount> out = new ArrayList<>(merged.size());
        merged.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .forEach(e -> out.add(new NameCount(e.getKey(), e.getValue())));
        return out;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** JPA 返回的时间可能是 {@code LocalDateTime}，也可能被驱动包成 {@code Timestamp}。 */
    private static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
