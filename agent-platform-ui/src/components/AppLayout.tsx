import { useMemo, useState } from 'react';
import { Layout, Menu, Button, Input, Space, App as AntApp } from 'antd';
import {
  DashboardOutlined,
  RobotOutlined,
  MessageOutlined,
  AppstoreOutlined,
  ToolOutlined,
  FileTextOutlined,
  BugOutlined,
  ThunderboltOutlined,
  DatabaseOutlined,
  BranchesOutlined,
  BookOutlined,
  FolderOutlined,
  ApiOutlined,
  LineChartOutlined,
  SafetyCertificateOutlined,
  HistoryOutlined,
  SettingOutlined,
  SkinOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import SidebarSkinSettings from './SidebarSkinSettings';
import { useTheme } from '../theme/ThemeProvider';
import { HOST_ATTRS, SLOTS, sidebarHooks } from '../skin/contract';
import {
  CLASS_CENTER_COL,
  CLASS_FOOT_AREA,
  CLASS_FOOTER_ACTIONS,
  CLASS_LOGO_ROW,
  CLASS_REGION_AREA,
  CLASS_SEARCH_BUTTON,
  CLASS_SEARCH_INPUT,
  CLASS_SEARCH_ROW,
  CLASS_SIDEBAR_COL,
  frag,
} from '../skin/class-fragments';

const { Sider, Content } = Layout;

export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  /** 侧栏折叠：DSH 皮肤用 rail/wide 两态做造型，这个状态必须真实存在。 */
  const [collapsed, setCollapsed] = useState(false);
  /**
   * 侧栏搜索。
   *
   * <p>这不是为了"凑一个钩子"才加的：orca 的 `rail-search.ts` 精确依赖
   * `input[class*='searchInput']` 与同行的 `button[class*='searchButton']`（含 aria-expanded），
   * 所以必须是一个**真功能** —— 这里用它过滤侧栏导航。</p>
   */
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchKw, setSearchKw] = useState('');
  /** 侧栏内嵌的皮肤设置面板（不 portal，见 SidebarSkinSettings 的说明）。 */
  const [skinSettingsOpen, setSkinSettingsOpen] = useState(false);
  // 换肤：外壳颜色全部走 --ap-* CSS 变量（主题由皮肤市场决定，未装皮肤时用默认外观）
  const { siderTheme } = useTheme();

  const selectedKey = useMemo(() => {
    const path = location.pathname;
    if (path.startsWith('/agents')) return '/agents';
    return path;
  }, [location.pathname]);

  const menuItems = [
    { key: '/overview', icon: <DashboardOutlined />, label: '概览' },
    { key: '/agents', icon: <RobotOutlined />, label: '智能体' },
    { key: '/chat', icon: <MessageOutlined />, label: '对话' },
    { key: '/sessions', icon: <HistoryOutlined />, label: '会话历史' },
    { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库' },
    { key: '/workflows', icon: <BranchesOutlined />, label: '工作流' },
    { key: '/skills', icon: <BookOutlined />, label: 'Skills' },
    { key: '/skins', icon: <SkinOutlined />, label: '皮肤市场' },
    { key: '/plugins', icon: <AppstoreOutlined />, label: '插件' },
    { key: '/files', icon: <FolderOutlined />, label: '文件' },
    { key: '/settings', icon: <SettingOutlined />, label: '模型设置' },
    {
      key: '/ops',
      icon: <ToolOutlined />,
      label: '运维工具',
      children: [
        { key: '/logs', icon: <FileTextOutlined />, label: '运行日志' },
        { key: '/observability', icon: <LineChartOutlined />, label: '智能体可观测性' },
        { key: '/diagnosis', icon: <BugOutlined />, label: '智能诊断' },
        { key: '/prompt', icon: <ThunderboltOutlined />, label: '提示词优化' },
        { key: '/tools', icon: <ApiOutlined />, label: '工具调试' },
        { key: '/quota', icon: <SafetyCertificateOutlined />, label: '用户配额' },
      ],
    },
  ];

  /** 按关键词过滤导航（含「运维工具」那组的子项）。 */
  const visibleMenuItems: typeof menuItems = (() => {
    const k = searchKw.trim().toLowerCase();
    if (!k) {
      return menuItems;
    }
    const hit = (v: unknown) => String(v ?? '').toLowerCase().includes(k);
    const out: typeof menuItems = [];
    for (const item of menuItems) {
      const kids = 'children' in item && Array.isArray(item.children) ? item.children : null;
      if (kids) {
        const kept = kids.filter((c) => hit(c.label));
        if (kept.length) {
          out.push({ ...item, children: kept } as (typeof menuItems)[number]);
        }
      } else if (hit(item.label)) {
        out.push(item);
      }
    }
    return out;
  })();

  return (
    <AntApp>
      {/*
        让 antd 的侧栏 children 容器成为 flex 列：这样 footArea 能自然沉底，
        又**不必插入额外 wrapper** —— 皮肤依赖
        `[data-slot='sidebar'] > :first-child > :first-child` 正好是品牌行。
      */}
      <style>{'.ant-layout-sider-children{display:flex;flex-direction:column}'}</style>
      <Layout
        data-slot="root"
        style={{
          /*
           * **正好一屏、且自己不滚** —— 这是修"整个界面多出一条滚动条"的关键。
           *
           * 原来写的是 minHeight: 100vh：内容一高，窗口就跟着滚，于是屏幕上同时出现
           * 两层滚动条（页面一层 + 内容区一层，截图里那两条细拇指就是嵌套滚动）。
           * 改成固定一屏 + overflow hidden 后，滚动只发生在内部区域：
           *   侧栏菜单区（regionArea，overflowY:auto）与内容区（Content，overflow:auto）各自滚。
           *
           * 这里也不再画背景图了（原来用 --ap-bg-scrim + --ap-bg-image）：
           * 那套随「应用」按钮下线，**背景由皮肤自己的 bundle 负责**，宿主只留一块底色兜底。
           */
          height: '100vh',
          overflow: 'hidden',
          backgroundColor: 'var(--ap-bg-layout)',
        }}
      >
        {/* DSH 宿主契约：侧栏槽位（皮肤 CSS 通过 [data-slot='sidebar'] / [data-pane='sidebar'] 命中） */}
        <Sider
          {...sidebarHooks}
          // 折叠状态钩子：DSH 皮肤大量用 rail/wide 两态做造型，没有这个它们永不生效
          {...{ [HOST_ATTRS.sidebarCollapsed]: collapsed ? '' : undefined }}
          className={frag(CLASS_SIDEBAR_COL)}
          theme={siderTheme}
          width={208}
          collapsible
          collapsed={collapsed}
          onCollapse={setCollapsed}
          style={{ background: 'var(--ap-sider-bg)' }}
        >
          {/*
            DSH 宿主契约：侧栏品牌行。
            这一层必须是 `[data-slot='sidebar'] > :first-child > :first-child` ——
            orca 的 mountDshWordmark() 正是把 DSH 字标 append 到这里，所以**结构不能动**。
            mark / name 用两层 span 分开挂槽位（皮肤会分别给图标和文字做装饰）。
          */}
          <div
            className={frag(CLASS_LOGO_ROW)}
            style={{
              height: 56,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
              color: 'var(--ap-sider-text)',
            }}
          >
            <span {...{ [HOST_ATTRS.slot]: SLOTS.sidebarBrandMark }} style={{ display: 'inline-flex' }}>
              <AppstoreOutlined />
            </span>
            <span {...{ [HOST_ATTRS.slot]: SLOTS.sidebarBrandName }} style={{ fontWeight: 600 }}>
              白雾
            </span>
          </div>
          {/*
            侧栏搜索行：input 与 button **必须在同一行内** ——
            orca 的 rail-search 是从 input 向上找最多 3 层祖先里的 searchButton。
          */}
          <div
            className={frag(CLASS_SEARCH_ROW)}
            style={{ padding: '0 10px 6px', display: 'flex', gap: 6, alignItems: 'center' }}
          >
            {searchOpen && (
              <Input
                allowClear
                autoFocus
                size="small"
                className={frag(CLASS_SEARCH_INPUT)}
                placeholder="搜索导航…"
                value={searchKw}
                onChange={(e) => setSearchKw(e.target.value)}
              />
            )}
            <Button
              size="small"
              type="text"
              className={frag(CLASS_SEARCH_BUTTON)}
              // 皮肤会读这个属性判断"行是否已展开"，所以必须是真实状态
              aria-expanded={searchOpen}
              aria-label="搜索侧栏"
              icon={<SearchOutlined />}
              style={{ color: 'var(--ap-sider-text)', flex: '0 0 auto' }}
              onClick={() => {
                setSearchOpen((v) => !v);
                if (searchOpen) {
                  setSearchKw('');
                }
              }}
            />
          </div>

          <div className={frag(CLASS_REGION_AREA)} style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
            <Menu
              theme={siderTheme}
              mode="inline"
              selectedKeys={[selectedKey]}
              defaultOpenKeys={['/ops']}
              items={visibleMenuItems}
              style={{ background: 'transparent', borderInlineEnd: 'none' }}
              onClick={({ key }) => navigate(key)}
            />
          </div>

          {/*
            侧栏底部区（`footArea`）。
            DSH 把账户/设置入口放在侧栏底部，皮肤用 [class*='footArea'] / [class*='footerActions'] 匹配；
            我们原来把它们放在顶部 Header —— 搬下来既是功能对齐，也是皮肤可挂载的前提。

            注意：**不能**为了布局再包一层 wrapper，否则
            `[data-slot='sidebar'] > :first-child > :first-child`（皮肤找的品牌行）就变成这层 wrapper 了。
            所以用一条全局样式把 antd 的 children 容器改成 flex 列。
          */}
          <div
            className={frag(CLASS_FOOT_AREA)}
            style={{ flex: '0 0 auto', padding: '8px 10px', borderTop: '1px solid rgba(128,128,128,0.25)' }}
          >
            <div
              className={frag(CLASS_FOOTER_ACTIONS)}
              style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'flex-end' }}
            >
              {/*
                侧栏底部动作：DSH 皮肤用 [data-slot='sidebar.footer.action'] 给底部按钮做造型。
                这里**只保留真实有用的入口**（皮肤设置）。

                原来还有租户选择器 + 登录/退出 —— 账户体系尚未落地，那两个控件点下去没有实际后果，
                却会在侧栏底部占掉一块、也让皮肤覆盖不完整。按"宁缺勿滥"先撤掉：
                接口仍在（`store/appStore` 的 tenantId/token、`api/auth` 的 login、
                `components/TenantSelector.tsx` 都原样保留），需要时直接装回来即可。
              */}
              <Space size={4} {...{ [HOST_ATTRS.slot]: SLOTS.sidebarFooterAction }} style={{ alignItems: 'center' }}>
                <Button
                  size="small"
                  type="text"
                  {...{ [HOST_ATTRS.slot]: SLOTS.settingsTrigger }}
                  icon={<SkinOutlined />}
                  aria-label="皮肤设置"
                  title="皮肤设置"
                  style={{ color: 'var(--ap-sider-text)' }}
                  onClick={() => setSkinSettingsOpen(true)}
                />
              </Space>
            </div>
          </div>

          {/*
            皮肤设置面板：**内联在侧栏里**（不 portal）。
            皮肤的选择器是 `[data-slot='sidebar.settings'] > [role='presentation'] > [role='dialog']`
            以及 `[data-slot='sidebar'] > :first-child > :has([role='dialog'])` ——
            用 antd Drawer 会 portal 到 body，这两条都匹配不到，皮肤的 settings-overlay 就永远不出现。
          */}
          <SidebarSkinSettings open={skinSettingsOpen} onClose={() => setSkinSettingsOpen(false)} />
        </Sider>
        {/* 透明：让最外层的皮肤背景图透上来 */}
        <Layout style={{ background: 'transparent' }}>
          {/*
            原来这里有一条 Header（页面标题 +「直连 core · 端口 8081」+ 一行提示）。

            去掉的两个理由：
            1. 它带不透明底色 + 下边框、横贯页面顶部 —— **皮肤因此没法完整覆盖整个页面**，
               这正是"皮肤看起来没铺满"的直接原因。
            2. 信息本身冗余：当前页面在侧栏菜单里已经高亮；「直连 core」对使用者没有行动价值。

            DSH 契约里**没有 header 槽位**（SLOTS 里只有 settings.header，那是设置面板的标题），
            所以删掉它不会让任何皮肤失配。
          */}
          {/*
            minHeight: 0 不能省：flex 子项默认 min-height:auto，会被内容撑高、
            于是溢出到外层而不是自己滚 —— 那正是"两层滚动条"的另一半原因。
          */}
          <Content
            className={frag(CLASS_CENTER_COL)}
            style={{ padding: 24, overflow: 'auto', minHeight: 0, background: 'transparent' }}
          >
            <Outlet />
          </Content>
        </Layout>
      </Layout>
    </AntApp>
  );
}