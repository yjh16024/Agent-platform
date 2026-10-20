import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Layout, Menu, Button, Input, Space, Empty, App as AntApp } from 'antd';
import {
  RobotOutlined,
  MessageOutlined,
  AppstoreOutlined,
  ToolOutlined,
  FileTextOutlined,
  BugOutlined,
  ThunderboltOutlined,
  DatabaseOutlined,
  BranchesOutlined,
  FolderOutlined,
  ApiOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SkinOutlined,
  SearchOutlined,
  UserOutlined,
  TeamOutlined,
  LogoutOutlined,
  ProfileOutlined,
  BulbOutlined,
  AuditOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { getTenantId, getToken, setToken } from '../api/http';
import { getMe } from '../api/auth';
import ToolApprovalWatcher from './ToolApprovalWatcher';
import NotificationBell from './NotificationBell';
import HostAuthDialog from '../plugins/host/HostAuthDialog';
import { usePluginNav, type PluginNavEntry } from '../plugins/host/pluginSlots';
import { emitPluginEvent } from '../plugins/scriptLoader';
import { preloadDicts } from '../dict/store';
import SidebarSkinSettings from './SidebarSkinSettings';
import SlotOutlet from '../slots/SlotOutlet';
import PluginSlotHost from '../slots/PluginSlotHost';
import { SLOT_IDS } from '../slots/registry';
import { useUiSlotStore } from '../store/uiSlotStore';
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
   * 插件的侧栏一级入口（2026-10-19，P1）。
   *
   * `pluginNav` 是插件用 `ctx.nav.register()` 注册的入口列表；`activePlugin` 记住
   * "当前正在看哪个插件的整页视图" —— 非空时主内容区渲染插件元素而不是 `<Outlet/>`。
   * 点其它菜单项会退出该视图（见 Menu 的 onClick）。
   */
  const pluginNav = usePluginNav();
  const [activePlugin, setActivePlugin] = useState<string | null>(null);

  /** 路由发生变化（浏览器前进/后退、代码内导航）时退出插件视图，避免"地址变了内容没变"。 */
  useEffect(() => {
    setActivePlugin(null);
  }, [location.pathname]);

  /*
   * 把登录 token 交给主进程（2026-10-19）：插件 Node 侧调平台接口要用它
   * （`api.platform.request`），而 token 只在渲染层 localStorage 里。
   * 主进程只存内存、不落盘 —— 应用重启后由这里再推一次。
   */
  useEffect(() => {
    const t = getToken();
    const apHost = (window as unknown as {
      apHost?: { setAuth?: (token: string | null, tenantId?: string) => Promise<unknown> };
    }).apHost;
    if (t && apHost?.setAuth) {
      void apHost.setAuth(t, getTenantId());
    }
  }, []);

  /*
    对话页打开「会话历史」抽屉时会派发 ap:sidebar-collapse —— 把全局侧栏折叠，
    让"同一时间只展开一个面板"（否则两层侧栏同时铺开很臃肿）。
    刻意**只缩不还原**：关掉抽屉后侧栏自动弹回来才是真的晃眼，用户要用导航自己点开即可。
  */
  useEffect(() => {
    const onCollapse = () => setCollapsed(true);
    window.addEventListener('ap:sidebar-collapse', onCollapse);
    return () => window.removeEventListener('ap:sidebar-collapse', onCollapse);
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

  /*
   * 当前对话的智能体 —— **只用于插槽**（决定侧栏底部该显示谁的插件界面元素）。
   * 插件是按智能体挂载的，而侧栏自己拿不到对话页的 agentId 状态，
   * 所以由对话页上报（见 uiSlotStore.currentAgentId 的说明）。为 null 时插槽渲染 null，不占位。
   */
  const currentAgentId = useUiSlotStore((s) => s.currentAgentId);

  /*
    可执行界面（2026-10-19，D 档）：把"当前智能体 / 主题"的变化派发给插件脚本。
    刻意**不重挂**插件（那样桌宠会瞬移回原点、看板会丢筛选状态）—— 插件订阅事件自己更新。
  */
  useEffect(() => {
    if (currentAgentId) {
      emitPluginEvent('agent.changed', currentAgentId);
    }
  }, [currentAgentId]);
  useEffect(() => {
    emitPluginEvent('theme.changed', siderTheme);
  }, [siderTheme]);

  /*
    全局界面插件（2026-10-19）：启动时拉一次它们的槽位声明。
    只在这里拉 —— 槽位组件有好几个，各自拉就是 N 个并发请求且可能不一致；
    安装/启停后由「扩展 → 界面插件」页主动再调一次（热切换）。
  */
  const loadGlobalSlots = useUiSlotStore((s) => s.loadGlobal);
  useEffect(() => {
    void loadGlobalSlots();
  }, [loadGlobalSlots]);

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
    /*
      2026-10-16 导航收敛：
      ① 默认落对话页（/ → /chat），导航也把「对话」放第一位 —— 开门见山（DSH 式）；
      ② 原「概览」下线（纯静态导航看板，没有行动价值，/overview 重定向到 /chat）；
      ③ 原「消息通知」从侧栏一级入口降级为右下角铃铛（components/NotificationBell）——
         "平时无感、有事才看"的能力不该占一级位，红点照样随时可见。
    */
    { key: '/chat', icon: <MessageOutlined />, label: '对话', perm: 'agent:invoke' },
    { key: '/memory', icon: <BulbOutlined />, label: '长期记忆', perm: 'profile:read' },
    { key: '/agents', icon: <RobotOutlined />, label: '智能体', perm: 'agent:read' },
    { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库', perm: 'kb:read' },
    { key: '/workflows', icon: <BranchesOutlined />, label: '工作流', perm: 'workflow:read' },
    /*
      2026-10-16 三合一：插件 / Skills / 皮肤市场 → 「扩展」。
      三者是同一件事的三个作用域（从市场安装第三方能力：运行时 / 智能体 / 界面），
      权限码**各自保留**，入口按"任一命中"显示（与「日志与观测」同一取舍）。
    */
    /*
      2026-10-19：**插件域对任意登录用户开放**（本地单用户平台的定位）——
      PluginController 已无权限校验，所以入口不能再挂在 `plugin:manage` 上
      （那个权限码也已移除）。这里不设 perm = 所有登录用户可见；
      内页的 Skills / 皮肤两个 Tab 仍按各自权限过滤，无权限的 Tab 不渲染。
    */
    { key: '/extensions', icon: <AppstoreOutlined />, label: '扩展' },
    { key: '/files', icon: <FolderOutlined />, label: '文件', perm: 'file:read' },
    { key: '/settings', icon: <SettingOutlined />, label: '平台设置', perm: 'platform:manage' },
    {
      key: '/ops',
      icon: <ToolOutlined />,
      label: '运维工具',
      children: [
        /*
         * 2026-09-28 合并：原来的「运行日志 / 操作日志 / 统计报表 / 智能体可观测性」四项
         * 是**同一批数据**的四种切法（`log_index` 被三个页面共读、`sys_audit_log` 被两个页面共读，
         * 统计报表本质就是前两者的聚合）。拆成四个并列菜单，反而要用户在脑子里自己建立关系。
         * 现在收进一个入口，内部按"明细 → 聚合"分 Tab。
         *
         * ⚠️ perm 写成"任一命中"：三者底层权限**刻意没有合并**（它们是三类受众：
         * 技术日志 / 合规审计 / 业务报表，合成一个码就再也做不到
         * "只给某人看统计、不给看审计"），所以只要拥有任意一个就该看到本入口。
         */
        {
          key: '/logs',
          icon: <FileTextOutlined />,
          label: '日志与观测',
          perm: 'log:read|audit:read|report:read',
        },
        { key: '/diagnosis', icon: <BugOutlined />, label: '智能诊断', perm: 'log:read' },
        { key: '/prompt', icon: <ThunderboltOutlined />, label: '提示词优化', perm: 'agent:invoke' },
        { key: '/tools', icon: <ApiOutlined />, label: '工具调试', perm: 'tool:read' },
        // 放在运维分组：它是"给有副作用的工具调用放行"的管控入口，
        // 性质与运行日志/审计同类（都是需要人来盯的事）。
        // 2026-09-28 改名为「审批与回滚」：待审批已由对话内弹窗（ToolApprovalWatcher）就地办完，
        // 这个页面的真实用途变成了**回看历史 + 撤销某次改动**，而"回滚"是它唯一不可替代的能力
        // （用户当时正因为看不出这一点而要求删掉它）。
        { key: '/approvals', icon: <AuditOutlined />, label: '审批与回滚', perm: 'approval:read' },
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
    /**
     * `p` 支持用 {@code |} 分隔多个权限码，**命中任一即可**。
     *
     * <p>用于"界面已合并、但底层权限仍独立"的入口：例如「日志与观测」内部有
     * 运行日志（{@code log:read}）、操作日志（{@code audit:read}）、统计报表（{@code report:read}）
     * 三个不同权限的 Tab —— 只要拥有**任意一个**，这个菜单项就该出现，
     * 否则会出现"有权限却看不到入口"。</p>
     */
    const allowed = (p?: string) =>
      !p || !perms || perms.length === 0 || p.split('|').some((x) => perms.includes(x));

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
              selectedKeys={[activePlugin ? `plugin:${activePlugin}` : selectedKey]}
              defaultOpenKeys={['/ops']}
              // 插件入口追加在最后：它们是"额外装进来的东西"，不该插进核心导航中间
              items={[
                ...visibleMenuItems,
                ...pluginNav.map((e) => ({
                  key: `plugin:${e.pluginId}`,
                  icon: <AppstoreOutlined />,
                  label: e.label,
                })),
              ]}
              style={{ background: 'transparent', borderInlineEnd: 'none' }}
              onClick={({ key }) => {
                if (key.startsWith('plugin:')) {
                  setActivePlugin(key.slice('plugin:'.length));
                  return;
                }
                setActivePlugin(null);
                navigate(key);
              }}
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
                {/*
                  插件注入的常驻入口（如告警状态角标）。
                  agentId 取自 uiSlotStore —— 插件是**按智能体挂载**的，侧栏自己拿不到对话页的状态，
                  所以由对话页在切换时上报（见 uiSlotStore.currentAgentId 的说明）。
                  没有当前智能体时它渲染成 null（不占位）。
                */}
                <SlotOutlet slot={SLOT_IDS.sidebarFooterAction} agentId={currentAgentId ?? undefined} />
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
            {activePlugin ? (
              <PluginNavView pluginId={activePlugin} entries={pluginNav} />
            ) : (
              <Outlet />
            )}
          </Content>
        </Layout>
        {/*
          「就地确认」弹窗监听器：挂在最外层，**任何页面**都能弹出审批确认框。
          写工具在后端阻塞等待用户决定（默认 5 分钟），这里一发现待审批就立刻弹，
          用户点完后端那边带着真实结果继续 —— 所以必须全局挂，不能只挂在聊天页：
          用户完全可能在会话列表页翻历史时收到审批。
        */}
        {/*
          可执行界面（2026-10-19，D 档）的整页槽位：
          父层 pointer-events:none —— 覆盖层本身不挡任何操作；插件容器默认 auto，
          装饰性插件（桌宠之外的挂件）不写尺寸就不会占位。
          z-index 850：高于内容、低于通知铃铛（900）与 antd 弹层（1000）。
        */}
        <div style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 850 }}>
          <SlotOutlet slot={SLOT_IDS.globalOverlay} agentId={currentAgentId ?? undefined} />
        </div>
        {/*
          插件右侧面板区（2026-10-19，P1）：插件通过 ctx.slots.register('right.panel', …) 往里放东西。
          容器固定右侧、父层 pointer-events:none —— 插件内容自己设交互（不写尺寸就不占位）。
        */}
        <div
          style={{
            position: 'fixed',
            right: 0,
            top: 0,
            height: '100vh',
            width: 380,
            pointerEvents: 'none',
            zIndex: 860,
          }}
        >
          <PluginSlotHost slot={SLOT_IDS.rightPanel} />
        </div>
        <ToolApprovalWatcher />
        {/* 右下角通知铃铛（2026-10-16）：红点随时可见，点击弹窗看全部 —— 替代原侧栏一级入口 */}
        <NotificationBell />
        {/* 插件申请本机能力时的确认框（2026-10-19，P4）—— 全局单例，队列化 */}
        <HostAuthDialog />
      </Layout>
    </AntApp>
  );
}

/**
 * 插件的侧栏入口视图（2026-10-19，P1）。
 *
 * <p>独立组件而不是内联：`usePluginNav()` 在插件注册/注销时会推送新的 entries，
 * 内联进 AppLayout 会让整个布局（侧栏、菜单、通知铃铛）跟着重渲染。</p>
 *
 * <p>插件被停用/卸载后 entries 里就没有它了 —— 此时显示一句话而不是空白页，
 * 否则用户会以为"点了没反应"。</p>
 */
function PluginNavView({ pluginId, entries }: { pluginId: string; entries: PluginNavEntry[] }) {
  const hit = entries.find((e) => e.pluginId === pluginId);
  if (!hit) {
    return <Empty description="该插件的界面已被停用或卸载" />;
  }
  return <>{hit.node}</>;
}