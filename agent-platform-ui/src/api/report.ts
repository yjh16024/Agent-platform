import { http } from './http';

/** 统计报表接口。整张报表一次取回，见 ReportController 的说明。 */

export interface NameCount {
  name: string;
  count: number;
}

export interface DayCount {
  day: string;
  count: number;
}

export interface ReportOverview {
  agents: number;
  sessions: number;
  users: number;
  /** 审计记录总量（不限时间窗）。 */
  auditTotal: number;
  /** 时间窗内的日志条数。 */
  logsInWindow: number;
  auditInWindow: number;
}

export interface LogDistribution {
  byCategory: NameCount[];
  byLevel: NameCount[];
  byAgent: NameCount[];
}

export interface OperationStats {
  topActions: NameCount[];
  topUsers: NameCount[];
  total: number;
  failed: number;
  /** 0~1，保留两位小数。 */
  failureRate: number;
}

export interface ReportView {
  days: number;
  overview: ReportOverview;
  logs: LogDistribution;
  operations: OperationStats;
  errorTrend: DayCount[];
}

/** 取整张报表（days 默认 7，上限 90 由服务层夹取）。 */
export function getReport(days = 7) {
  return http.get<ReportView>(`/api/v1/reports/overview?days=${days}`);
}
