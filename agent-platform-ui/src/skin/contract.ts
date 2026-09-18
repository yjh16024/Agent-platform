/**
 * DSH 宿主契约钩子（方案甲的"薄契约"）。
 *
 * <h3>这份清单不是猜的</h3>
 * 由 `desktop/skin-contract-survey.mjs` 对真实皮肤做静态测绘得出（16 个样本、1388 次引用）：
 *
 * ```
 * unique host hooks           = 60
 * hooks shared by >=2 skins   = 27    ← 覆盖 74.7% 的引用
 * hooks used by only 1 skin   = 33    ← 绝大多数是皮肤私有命名空间（data-mc-* / data-liang-*…），不需要对齐
 * ```
 *
 * 所以**只要挂上下面这些共用钩子，就覆盖了绝大多数引用**。皮肤作者自己也把 `data-*` 当首选、
 * 把 class/结构标成"漂移风险"（macintosh 源码注释原文：`优先级纪律：data-* > aria/role > 结构/class`），
 * 因此这里**只做属性钩子**，类名片段（`sidebarCol` / `regionArea` 那一层）留给后续阶段。
 *
 * <h3>契约版本</h3>
 * {@link CONTRACT_VERSION} 变更意味着"之前能量到的皮肤可能失配"，需要重测命中率——
 * 与皮肤自身的 commit 一起，构成命中率缓存的 key（见 2026-09-17 的讨论）。
 */

/**
 * 版本号：钩子集合有任何增删改就 +1。
 *
 * <p>v4（2026-09-17）：设置面板改成**三层** ——
 * `[data-slot='sidebar.settings'] > [role='presentation'] > ([data-ap-settings-mask], [role='dialog'])`。
 * 皮肤（orca）用 `> [role='presentation']` 与 `> [role='presentation'] > [role='dialog']`
 * 来给覆盖层与对话框定尺寸/定位，把 presentation 与 slot 挂在同一元素上会让它们**全部失效**
 * （实测表现：对话框只能吃侧栏宽度 → 标签被挤成一字一行）。**结构性变更，需重测。**</p>
 *
 * <p>v3（2026-09-17）：composer 拆成**两层** —— `data-composer-seat` 移到外层座位、
 * `data-composer-card` 留在内层卡片。皮肤（orca）用的是后代选择器
 * `[data-composer-seat] [data-composer-card]`，两层挂在同一元素上那些规则永不生效。
 * 所以这是**结构性变更**，能量到的皮肤可能因此改变表现，需要重测。</p>
 *
 * <p>v2（2026-09-17）：补齐 A 类欠账 —— `sidebar.brand.mark` / `sidebar.footer.action` /
 * `conversation.chat.node` / `settings.header` / `settings.trigger` / `sidebar.settings`，
 * 以及 `_selectInput` / `_selector` / `previewBadge` / `detailsCol` / `titlebar` / `_userStack` 片段。
 * 版本变了意味着"之前对某个皮肤下过的支持度结论失效"，应重测。</p>
 */
export const CONTRACT_VERSION = 4;

/** 宿主属性钩子 —— 皮肤 CSS 通过 `[data-xxx]` 命中。 */
export const HOST_ATTRS = {
  /** 明暗开关（测绘里 10/16 皮肤都用它，最关键的一个） */
  darkTheme: 'data-ds-dark-theme',
  /** 结构槽位（点号语义，DSH 的正式槽位系统） */
  slot: 'data-slot',
  /** 面板归属 */
  pane: 'data-pane',
  /** 会话阶段：hero（空态）/ settling / active（对话中） */
  phase: 'data-phase',
  /** 可滚动的会话区 */
  conversationScroll: 'data-conversation-scroll',
  /** 侧栏是否折叠 */
  sidebarCollapsed: 'data-sidebar-collapsed',
  /** 输入框容器 / 座位 */
  composerCard: 'data-composer-card',
  composerSeat: 'data-composer-seat',
  /** 聊天流容器与条目类型 */
  chatFlow: 'data-chat-flow',
  chatFlowKind: 'data-chat-flow-kind',
  /** 是否正在流式输出（皮肤据此加动效） */
  streaming: 'data-streaming',
} as const;

/** `data-slot` 的取值（实测出现过的）。 */
export const SLOTS = {
  root: 'root',
  sidebar: 'sidebar',
  sidebarSettings: 'sidebar.settings',
  sidebarBrandMark: 'sidebar.brand.mark',
  sidebarBrandName: 'sidebar.brand.name',
  sidebarFooterAction: 'sidebar.footer.action',
  conversation: 'conversation',
  conversationComposerDock: 'conversation.composer.dock',
  conversationChatNode: 'conversation.chat.node',
  conversationSessionHeader: 'conversation.session.header',
  conversationSessionHeaderActions: 'conversation.session.header.actions',
  conversationInputAttachments: 'conversation.input.attachments',
  settingsHeader: 'settings.header',
  settingsTrigger: 'settings.trigger',
  details: 'details',
} as const;

/** `data-pane` 的取值。 */
export const PANES = {
  sidebar: 'sidebar',
  conversation: 'conversation',
  details: 'details',
} as const;

/** 会话阶段。 */
export type Phase = 'hero' | 'settling' | 'active';

/** 把 `data-slot` / `data-pane` 等一组钩子拼成可直接展开到 JSX 的属性对象。 */
export function hookAttrs(attrs: Record<string, string | boolean | undefined>): Record<string, string | boolean> {
  const out: Record<string, string | boolean> = {};
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === false) {
      continue;
    }
    out[k] = v === true ? '' : v;
  }
  return out;
}

/** 常用组合：侧栏 */
export const sidebarHooks = hookAttrs({
  [HOST_ATTRS.slot]: SLOTS.sidebar,
  [HOST_ATTRS.pane]: PANES.sidebar,
});

/** 常用组合：会话区 */
export const conversationHooks = hookAttrs({
  [HOST_ATTRS.slot]: SLOTS.conversation,
  [HOST_ATTRS.pane]: PANES.conversation,
});

/**
 * 常用组合：**输入座位**（外层）。
 *
 * <h3>为什么座位与卡片必须拆到两个元素上</h3>
 * orca 的 CSS 明确把它们当**两层**用：
 * <pre>
 *   [data-phase='active'] [data-composer-seat]   { transition: … }        ← 座位负责折叠/过渡
 *   [data-composer-seat] [data-composer-card]    { width: … }              ← 卡片是座位的**后代**
 *   [data-composer-card]                         { border/background }     ← 卡片负责画边框
 *   [data-composer-card]::before/::after         { left:-10px … }          ← 卡片两侧向外伸的"侧臂"
 * </pre>
 * 早期实现把 `data-composer-seat` 与 `data-composer-card` 挂在**同一个 div** 上，
 * 于是 `[data-composer-seat] [data-composer-card]`（后代选择器）永远匹配不到、
 * 座位的过渡也永远不触发，而按"卡片外侧 -10px"定位的侧臂语义也随之错位
 * —— 表现就是用户看到的"输入框外面有东西突出来"。
 */
export const composerSeatHooks = hookAttrs({
  [HOST_ATTRS.slot]: SLOTS.conversationComposerDock,
  [HOST_ATTRS.composerSeat]: true,
});

/**
 * 常用组合：**输入卡片**（内层，皮肤在这里画边框/背景/侧臂）。
 *
 * <p>注意：皮肤会自己给它设 `border-color` / `background-color`，
 * 所以**宿主不要再用内联样式硬设这些**（内联优先会压住皮肤），见 `useSkinActive()`。</p>
 */
export const composerCardHooks = hookAttrs({
  [HOST_ATTRS.composerCard]: true,
});
