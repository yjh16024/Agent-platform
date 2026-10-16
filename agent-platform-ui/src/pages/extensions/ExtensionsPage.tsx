import { useEffect, useState } from 'react';
import { Empty, Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { getMe } from '../../api/auth';
import PluginMarketplace from '../plugins/PluginMarketplace';
import SkillsPage from '../skills/SkillsPage';
import SkinMarketPage from '../skins/SkinMarketPage';

const { Text, Paragraph } = Typography;

/**
 * 「扩展」—— 插件 / Skills / 皮肤市场三合一（2026-10-16）。
 *
 * <h3>为什么合并这三者</h3>
 * 它们本质是**同一件事的三个作用域**：从市场安装/挂载第三方能力 ——
 * 插件作用于**运行时**（钩子 + 工具）、Skill 作用于**智能体**（提示词正文）、
 * 皮肤作用于**界面**。在侧栏里占三个并列高位，用户得自己建立"这三个是一类东西"的认知；
 * 收进一个入口、按"运行时 → 智能体 → 界面"排列，这层关系就自己显出来了
 * （与「日志与观测」四合一、以及平台设置两层是同一套处理）。
 *
 * <h3>★ 权限码不合并（与 OpsLogsPage 同一取舍）</h3>
 * 三个 Tab 分属 {@code plugin:manage} / {@code skill:manage} / {@code skin:manage} ——
 * 对应三类不同的管控面。只合并**界面**，权限保持独立：
 * 无权限的 Tab 根本不渲染（给一个点进去 403 的入口只会让用户以为系统坏了）。
 * 侧栏入口用"任一命中"（{@code plugin:manage|skill:manage|skin:manage}）。
 *
 * <h3>Tab 状态放在 URL 上</h3>
 * {@code ?tab=plugins|skills|skins} —— 刷新不丢、链接可分享，三个旧路径
 * （/plugins、/skills、/skins）直接重定向到对应 Tab，老书签不失效。
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

  /** 与 AppLayout / OpsLogsPage 同一套取舍：权限集为空/拿不到时视为"不限"。 */
  const allowed = (p: string) => !perms || perms.length === 0 || p.split('|').some((x) => perms.includes(x));

  const tabDefs = [
    { key: 'plugins', label: '插件', perm: 'plugin:manage', node: <PluginMarketplace /> },
    { key: 'skills', label: 'Skills', perm: 'skill:manage', node: <SkillsPage /> },
    { key: 'skins', label: '皮肤市场', perm: 'skin:manage', node: <SkinMarketPage /> },
  ];

  const visible = tabDefs.filter((t) => allowed(t.perm));

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
              你没有管理插件 / Skills / 皮肤的权限
              <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                需要 <Text code>plugin:manage</Text>、<Text code>skill:manage</Text> 或
                <Text code>skin:manage</Text> 之一。
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
