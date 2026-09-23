import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Layout, Menu, Button, Input, Space, Badge, App as AntApp } from 'antd';
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
  UserOutlined,
  TeamOutlined,
  LogoutOutlined,
  ProfileOutlined,
  FileProtectOutlined,
  BarChartOutlined,
  BellOutlined,
  BulbOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { setToken } from '../api/http';
import { getMe } from '../api/auth';
import { unreadCount } from '../api/notifications';
import { preloadDicts } from '../dict/store';
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
  /** 未读通知数（侧栏角标）。0 时不渲染角标。 */
  const [unread, setUnread] = useState(0);

  /*
    未读数轮询（侧栏角标的数据来源）。
    两个节流措施缺一不可：
      1) 30 秒间隔 —— 通知没有强实时要求，更密只是白耗后端；
      2) document.hidden 时跳过 —— 页面在后台还发请求纯属浪费（桌面版最小化时尤其明显）。
    只查 count 不拉列表，单次代价极小。

    另外监听 ap:notice-changed：通知页做完"标记已读/删除"后会派发它，
    让角标立刻更新 —— 否则"点了全部已读、角标却还挂着数字"会停留 30 秒，
    看起来像没生效。
  */
  useEffect(() => {
    let stopped = false;
    const tick = async () => {
      if (document.hidden) {
        return;
      }
      try {
        const r = await unreadCount();
        if (!stopped) {
          setUnread(r?.count ?? 0);
        }
      } catch {
        // 未登录 / 后端未开 RBAC 时这里会失败：静默即可 ——
        // 角标只是提示，不该为此弹一个错误提示打扰用户。
      }
    };
    const onChanged = () => void tick();
    void tick();
    const timer = window.setInterval(() => void tick(), 30_000);
    window.addEventListener('ap:notice-changed', onChanged);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      window.removeEventListener('ap:notice-changed', onChanged);
    };
  }, []);
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

  /**
   * 当前用户的权限码；`null` = 还没拿到。
   *
   * <p>它**只用于隐藏入口**，不承担拦截职责 —— 拦截在后端
   * （`@RequiresPermission` + `PermissionAspect`）。所以取值为空时的取舍是
   * **全量显示**而不是全隐藏：接口会照常返回 403，用户至少知道有这么一个功能，
   * 而不是面对一个"什么都没有"的平台。反过来若只靠这里隐藏而后端不拦，才是真漏洞。</p>
   */
  const [perms, setPerms] = useState<string[] | null>(null);

  useEffect(() => {
    let alive = true;
    getMe()
      .then((me) => {
        if (alive) {
          setPerms(me.perms ?? []);
        }
      })
      .catch(() => {
        // 拿不到就保持 null（= 不限），理由见上
        if (alive) {
          setPerms(null);
        }
      });

    /*
     * 顺带预取数据字典：与 /me 并行发出，不阻塞首屏。
     * 放在这里是因为它是"登录后、进入主界面"时该做的事 —— 登录页不需要字典。
     * 失败时静默（各下拉会是空的，但功能可用），详见 dict/store.ts 的说明。
     */
    void preloadDicts();

    return () => {
      alive = false;
    };
  }, []);

  const selectedKey = useMemo(() => {
    const path = location.pathname;
    if (path.startsWith('/agents')) return '/agents';
    return path;
  }, [location.pathname]);

  type NavItem = {
    key: string;
    icon?: ReactNode;
    /** 放宽为 ReactNode：未读通知角标要挂在 label 里（antd Menu 支持节点 label）。 */
    label: ReactNode;
    /** 所需权限码；不填 = 所有登录用户可见。见下方过滤逻辑的说明。 */
    perm?: string;
    children?: NavItem[];
  };

  /**
   * 侧栏导航。
   *
   * <p>{@code perm} 与后端 Controller 上的 {@code @RequiresPermission} **一一对应**：
   * 这里写错不会造成越权（后端仍会拦），但会让用户点进去吃 403。
   * 每加一个受管控的页面，记得两边一起加。</p>
   */
  const menuItems: NavItem[] = [
    { key: '/overview', icon: <DashboardOutlined />, label: '概览' },
    /*
      消息通知放在高频位置（紧跟概览）：它是"待办式"入口，用户需要主动来看，
      藏进分组里就失去了提醒的意义。
      角标数字来自 unreadCount 轮询（见下方 effect），不是静态值。
    */
    {
      key: '/notifications',
      icon: <BellOutlined />,
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          消息通知
          {unread > 0 && <Badge count={unread} size="small" overflowCount={99} />}
        </span>
      ),
      perm: 'notice:read',
    },
    // 与「消息通知」并列放在顶部高频区：两者都是"每个人自己的"功能，
    // 塞进「运维工具」分组会让它在权限树里显得像管理员专属（同通知的处理）。
    { key: '/memory', icon: <BulbOutlined />, label: '长期记忆', perm: 'profile:read' },
    { key: '/agents', icon: <RobotOutlined />, label: '智能体', perm: 'agent:read' },
    { key: '/chat', icon: <MessageOutlined />, label: '对话', perm: 'agent:invoke' },
    { key: '/sessions', icon: <HistoryOutlined />, label: '会话历史', perm: 'session:read' },
    { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库', perm: 'kb:read' },
    { key: '/workflows', icon: <BranchesOutlined />, label: '工作流', perm: 'workflow:read' },
    { key: '/skills', icon: <BookOutlined />, label: 'Skills', perm: 'skill:manage' },
    { key: '/skins', icon: <SkinOutlined />, label: '皮肤市场', perm: 'skin:manage' },
    { key: '/plugins', icon: <AppstoreOutlined />, label: '插件', perm: 'plugin:manage' },
    { key: '/files', icon: <FolderOutlined />, label: '文件', perm: 'file:read' },
    { key: '/settings', icon: <SettingOutlined />, label: '模型设置', perm: 'model:manage' },
    {
      key: '/ops',
      icon: <ToolOutlined />,
      label: '运维工具',
      children: [
        { key: '/logs', icon: <FileTextOutlined />, label: '运行日志', perm: 'log:read' },
        // 操作日志与运行日志并列：前者记"人的操作"，后者记"系统运行"
        { key: '/audit', icon: <FileProtectOutlined />, label: '操作日志', perm: 'audit:read' },
        { key: '/reports', icon: <BarChartOutlined />, label: '统计报表', perm: 'report:read' },
        { key: '/observability', icon: <LineChartOutlined />, label: '智能体可观测性', perm: 'log:read' },
        { key: '/diagnosis', icon: <BugOutlined />, label: '智能诊断', perm: 'log:read' },
        { key: '/prompt', icon: <ThunderboltOutlined />, label: '提示词优化', perm: 'agent:invoke' },
        { key: '/tools', icon: <ApiOutlined />, label: '工具调试', perm: 'tool:read' },
        { key: '/quota', icon: <SafetyCertificateOutlined />, label: '用户配额', perm: 'quota:manage' },
      ],
    },
    {
      key: '/system',
      icon: <TeamOutlined />,
      label: '系统管理',
      children: [
        { key: '/system/users', icon: <UserOutlined />, label: '用户管理', perm: 'user:manage' },
        { key: '/system/roles', icon: <SafetyCertificateOutlined />, label: '角色权限', perm: 'role:manage' },
        // 用 dict:write 而不是 dict:read 作为可见条件：该页面所有接口都要求写权限，
        // 只给读权限的话点进去必然 403 —— 那是"看得到但用不了"，比看不到更糟。
        { key: '/system/dicts', icon: <ProfileOutlined />, label: '数据字典', perm: 'dict:write' },
      ],
    },
  ];

  /** 先按权限裁剪，再按关键词过滤（含「运维工具」那组的子项）。 */
  const visibleMenuItems: NavItem[] = (() => {
    /**
     * 权限判据。
     *
     * `perms === null`（还没拿到）与 `perms.length === 0`（后端没开 RBAC /
     * 演示模式下签发的 token 不带角色）都视为**不限** —— 这两种情况下后端本来就不会拦，
     * 若这里全隐藏，用户会看到一个空侧栏却找不到原因。
     */
    const allowed = (p?: string) => !p || !perms || perms.length === 0 || perms.includes(p);

    const keptByPerm: NavItem[] = [];
    for (const item of menuItems) {
      if (item.children) {
        const kids = item.children.filter((c) => allowed(c.perm));
        // 子项被裁光就整组不显示，否则会留下一个展开后空无一物的分组
        if (kids.length) {
          keptByPerm.push({ ...item, children: kids });
        }
      } else if (allowed(item.perm)) {
        keptByPerm.push(item);
      }
    }

    const k = searchKw.trim().toLowerCase();
    if (!k) {
      return keptByPerm;
    }
    const hit = (v: unknown) => String(v ?? '').toLowerCase().includes(k);
    const out: NavItem[] = [];
    for (const item of keptByPerm) {
      if (item.children) {
        const kids = item.children.filter((c) => hit(c.label));
        if (kids.length) {
          out.push({ ...item, children: kids });
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
                {/*
                  退出登录 —— 账户体系落地后把这段装回来（原来因"点下去没有实际后果"被撤掉）。
                  鉴权**关闭**时它同样可用：那个场景下后端不校验 token，退出只是清掉本地存储。
                */}
                <Button
                  size="small"
                  type="text"
                  icon={<LogoutOutlined />}
                  aria-label="退出登录"
                  title="退出登录"
                  style={{ color: 'var(--ap-sider-text)' }}
                  onClick={() => {
                    setToken(null);
                    navigate('/login', { replace: true });
                  }}
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