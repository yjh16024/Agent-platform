package com.agentplatform.core.report;

import java.util.List;

/**
 * 统计报表的出入参。
 *
 * <p>设计成**一次请求返回整张报表**（而不是每个区块一个接口）：
 * 页面打开时要同时渲染 4 个区块，拆开就是 4 次往返，而且它们的时间窗必须一致 ——
 * 分开发还会出现"概览是 7 天、趋势是 30 天"这种对不上的情况。</p>
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** 通用「名称 → 计数」。 */
    public record NameCount(String name, long count) {
    }

    /** 「日期 → 计数」，用于趋势。 */
    public record DayCount(String day, long count) {
    }

    /** 概览卡片。 */
    public record Overview(long agents,
                           long sessions,
                           long users,
                           /** 审计记录总量（不限时间窗，反映累计）。 */
                           long auditTotal,
                           /** 时间窗内的日志条数。 */
                           long logsInWindow,
                           /** 时间窗内的操作数。 */
                           long auditInWindow) {
    }

    /** 运行日志分布。 */
    public record LogDistribution(List<NameCount> byCategory,
                                  List<NameCount> byLevel,
                                  List<NameCount> byAgent) {
    }

    /** 操作统计（来自审计表 —— 结构化数据，比解析日志文本可靠）。 */
    public record OperationStats(List<NameCount> topActions,
                                 List<NameCount> topUsers,
                                 long total,
                                 long failed,
                                 /** 失败率，0~1，已保留两位小数。 */
                                 double failureRate) {
    }

    /** 整张报表。 */
    public record ReportView(int days,
                             Overview overview,
                             LogDistribution logs,
                             OperationStats operations,
                             /** 每天的错误（ERROR 级）日志数。 */
                             List<DayCount> errorTrend) {
    }
}
