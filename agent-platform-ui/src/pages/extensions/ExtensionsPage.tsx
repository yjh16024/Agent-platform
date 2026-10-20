import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Empty, Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { getMe } from '../../api/auth';
import MarketPage from './MarketPage';
import SkillsPage from '../skills/SkillsPage';
import SkinMarketPage from '../skins/SkinMarketPage';

const { Text, Paragraph } = Typography;

/**
 * 旧 Tab key 的别名（2026-10-19 合并后保留）。
 *
 * <p>合并前是 5 个 Tab：`plugins` / `web` / `market` / `skills` / `skins`。
 * 老书签与站内链接仍可能带着 `?tab=web` 之类，这里全部映射到新位置，
 * **不要因为它们不在 tabDefs 里就当未知值** —— 那会让用户看到"打开后跳回第一个 Tab"。</p>
 */
const TAB_ALIAS: Record<string, string> = {
  plugins: 'market',
  web: 'market',
  // skins 保持原值 —— 2026-10-19 二次调整：皮肤回到独立 Tab（它要请求外部市场 catalog，
  // 并进插件市场会让那一页每次打开都等两个外部请求）。
};

/**
 * 「扩展」—— 插件市场 + Skills（2026-10-19 重构，取代原 5 Tab）。
 *
 * <h3>为什么从"按机制分 Tab"改成"一个市场"</h3>
 * 原来按**实现方式**分：插件（jar）/ 界面插件（`<script>`）/ 插件市场 / Skills / 皮肤市场。
 * 那是实现者的视角；用户想的是"我要装点东西"。现在：
 * <ul>
 *   <li><b>插件市场</b>：界面插件 / 皮肤 / 服务端插件都在里面，按类型标签筛选，
 *       **不分内置与外部**，已安装与可安装同列表；</li>
 *   <li><b>Skills</b>：单独一个 Tab —— 它作用于"智能体的提示词"，与"装插件"不是一件事，
 *       而且目前没有在线源（只有本地导入）。</li>
 * </ul>
 *
 * <h3>★ 权限</h3>
 * 插件市场对所有登录用户开放（插件域放权，见 `PluginController` 类注释）；
 * Skills 仍按 `skill:manage` 过滤 —— 无权限的 Tab 根本不渲染。
 */
export default function ExtensionsPage() {
  const [params, setParams] = useSearchParams();
  const [perms, setPerms] = useState<string[] | null>(null);

  useEffect(() => {
    let alive = true;
    getMe()
      .then((me) => { if (alive) setPerms(me.perms ?? []); })
      .catch(() => { if (alive) setPerms(null); });
    return () => { alive = false; };
  }, []);

  /**
   * 与 AppLayout / OpsLogsPage 同一套取舍：权限集为空/拿不到时视为"不限"；
   * **没有 perm 的 Tab（插件市场）恒为可见**。
   */
  const allowed = (p?: string) => !p || !perms || perms.length === 0 || p.split('|').some((x) => perms.includes(x));

  const tabDefs: Array<{ key: string; label: string; perm?: string; node: ReactNode }> = [
    { key: 'market', label: '插件市场', node: <MarketPage /> },
    // 皮肤独立（2026-10-19 二次调整）：它的数据来自外部 catalog，并进市场会拖慢整页
    { key: 'skins', label: '皮肤市场', perm: 'skin:manage', node: <SkinMarketPage /> },
    { key: 'skills', label: 'Skills', perm: 'skill:manage', node: <SkillsPage /> },
  ];

  const visible = tabDefs.filter((t) => allowed(t.perm));

  const raw = params.get('tab') ?? '';
  const wanted = TAB_ALIAS[raw] ?? raw;
  const activeKey = visible.some((t) => t.key === wanted) ? wanted : visible[0]?.key;

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
              你没有管理皮肤 / Skills 的权限
              <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                需要 <Text code>skin:manage</Text> 或 <Text code>skill:manage</Text> 之一
                （插件市场对所有登录用户开放，不需要权限）。
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
          // 与 OpsLogsPage 一致：不加 destroyInactiveTabPane，切回去时筛选与滚动位置还在
          children: t.node,
        }))}
      />
    </div>
  );
}
