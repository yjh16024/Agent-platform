import { useEffect, useState } from 'react';
import { Empty, Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { getMe } from '../../api/auth';
import LogsPage from './LogsPage';
import ObservabilityPage from './ObservabilityPage';
import AuditPage from './AuditPage';
import ReportsPage from './ReportsPage';

const { Text, Paragraph } = Typography;

/**
 * 「日志与观测」—— 四个同源页面的统一入口（2026-09-28 合并）。
 *
 * <h3>为什么合并</h3>
 * 这四个页面读的是**同一批数据**：
 * <ul>
 *   <li>{@code log_index} —— 运行日志（明细）、统计报表（聚合）、可观测性（摘要 + 时间线）三个页面共读；</li>
 *   <li>{@code sys_audit_log} —— 操作日志（明细）、统计报表（操作统计）两个页面共读。</li>
 * </ul>
 * 换句话说：**统计报表本质是"运行日志 + 操作日志"的只读聚合，可观测性的时间线就是把运行日志
 * 按 traceId 换个排版**。四份界面拆在侧栏四个位置，用户要在脑子里自己建立它们的关系。
 * 收进一个入口、按"明细 → 聚合"排列，这层关系就自己显出来了。
 *
 * <h3>★ 为什么权限码**不**合并（这是刻意的）</h3>
 * 四个 Tab 分属三个权限码：{@code log:read}（技术日志）/ {@code audit:read}（人的行为审计）/
 * {@code report:read}（业务聚合数字）。它们对应**三类不同的受众**：
 * 一个要出报表的实习运维需要 {@code report:read}，但**不该看管理员操作留痕**
 * （{@code audit:read}）。
 *
 * <p>如果图省事合成一个码，就再也做不到"只给他看统计、不给看审计"——
 * **颗粒度一旦合并就找不回来了**。所以这里只合并**界面**，权限保持独立。</p>
 *
 * <h3>权限不足时是"看不见"而不是"点进去 403"</h3>
 * 无权限的 Tab **根本不渲染** —— 这是刻意的：给一个点不开的入口，用户只会反复尝试
 * 或以为系统坏了。看不见，就把"你不需要关心这个"表达清楚了。
 * 用的判据与 {@code AppLayout} 的菜单过滤完全一致（拿不到权限集时视为"不限"）。
 *
 * <h3>Tab 状态放在 URL 上</h3>
 * 用 {@code ?tab=xxx} 而不是组件内 state —— 这样刷新不丢、链接可分享，
 * 而且三个旧路径能直接重定向到对应 Tab（见 {@code App.tsx}），老书签不会失效。
 */
export default function OpsLogsPage() {
  const [params, setParams] = useSearchParams();
  const [perms, setPerms] = useState<string[] | null>(null);

  useEffect(() => {
    getMe()
      .then((me) => setPerms(me.perms ?? []))
      .catch(() => setPerms(null));
  }, []);

  /** 与 AppLayout 同一套取舍：权限集为空/拿不到时视为"不限"，避免出现"空侧栏找不到原因"。 */
  const allowed = (p: string) => !perms || perms.length === 0 || perms.includes(p);

  /*
   * 顺序按「由细到粗」：先具体事件（日志/链路），再人的行为（审计），最后聚合数字（报表）。
   * 这也正是用户排查问题的自然路径：从"出了什么错"到"谁改了什么"再到"整体趋势"。
   */
  const tabDefs = [
    { key: 'logs', label: '运行日志', perm: 'log:read', node: <LogsPage /> },
    { key: 'observability', label: '智能体可观测性', perm: 'log:read', node: <ObservabilityPage /> },
    { key: 'audit', label: '操作日志', perm: 'audit:read', node: <AuditPage /> },
    { key: 'reports', label: '统计报表', perm: 'report:read', node: <ReportsPage /> },
  ];

  const visible = tabDefs.filter((t) => allowed(t.perm));

  // 权限未就绪时先不渲染 Tabs：否则会先闪一下"全部 Tab"再缩水，观感很差
  const active = params.get('tab');
  const activeKey = visible.some((t) => t.key === active) ? (active as string) : visible[0]?.key;

  if (perms === null && visible.length === 0) {
    return (
      <div style={{ padding: 16 }}>
        <Text type="secondary">正在读取权限…</Text>
      </div>
    );
  }

  if (visible.length === 0) {
    return (
      <div style={{ padding: 16 }}>
        <Empty
          description={
            <span>
              你没有查看日志或报表的权限
              <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                需要 <Text code>log:read</Text>（运行日志 / 可观测性）、
                <Text code>audit:read</Text>（操作日志）或 <Text code>report:read</Text>（统计报表）之一。
              </Paragraph>
            </span>
          }
        />
      </div>
    );
  }

  return (
    <div style={{ padding: 16 }}>
      <Tabs
        activeKey={activeKey}
        onChange={(k) => setParams({ tab: k }, { replace: true })}
        // 只剩一个 Tab 时就不显示标签栏了 —— 那一条孤零零的标签只会占地方
        renderTabBar={visible.length > 1 ? undefined : () => <></>}
        items={visible.map((t) => ({
          key: t.key,
          label: t.label,
          // 注意：这里不加 destroyInactiveTabPane —— 让访问过的 Tab 保持挂载，
          // 这样切回去时筛选条件与滚动位置还在，也不会重新拉一遍数据。
          children: t.node,
        }))}
      />
    </div>
  );
}
