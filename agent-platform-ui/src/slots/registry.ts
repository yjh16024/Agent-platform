/**
 * 前端插槽清单 —— **宿主允许插件往哪些位置放东西**。
 *
 * <h3>为什么槽位是"前端说了算"的</h3>
 * 后端只管组件类型白名单（它决定了"能渲染成什么"），**槽位则由前端定义** ——
 * 因为槽位是**渲染位置**，只有前端知道自己有哪些可挂载点。
 *
 * <p>于是两侧的演化可以独立：后端加新槽位不必改任何插件；插件用了前端还不认识的槽位时，
 * 前端**静默忽略**那一项（不是报错），旧前端只是少显示一块。</p>
 *
 * <h3>★ 与皮肤契约（skin/contract.ts）的关系：共用命名、两套机制</h3>
 * 这里的槽位 id 与 {@code skin/contract.ts} 的 {@code SLOTS} **刻意保持同名**
 * （如 {@code conversation.session.header.actions}），这样"同一个位置"在插件与皮肤两边
 * 有一致的叫法，读代码的人不必记两套词。
 *
 * <p>但**实现是两套**：皮肤那边是 {@code data-slot} 属性（给 CSS 选择器命中），
 * 这边是 React 挂载点（给插件注入组件）。**不要试图用一套实现同时满足两者** ——
 * 一个是字符串锚点、一个是组件树位置，硬合并只会让两边都别扭。</p>
 *
 * <h3>⚠️ 新增槽位的完整步骤</h3>
 * ① 在这里加常量 → ② 在宿主组件里挂 {@code <SlotOutlet slot={...} agentId={...} />}
 * → ③ 在文档里补一句。**不需要改后端**（它不校验槽位）。
 */

/** 宿主支持的槽位 id。 */
export const SLOT_IDS = {
  /** 会话卡片标题右侧 —— 适合放"导出/分享/清空"这类针对整场会话的动作 */
  sessionHeaderActions: 'conversation.session.header.actions',
  /** 侧栏底部 —— 常驻入口（账户、通知、告警状态等） */
  sidebarFooterAction: 'sidebar.footer.action',
  /** 会话消息列表上方 —— 放横幅式的信息卡（用量、额度预警等） */
  conversationAboveList: 'conversation.above.list',
  /** 输入框底部工具条 —— 与"发送/清空/新会话"并列的动作 */
  composerActions: 'composer.actions',
} as const;

export type SlotId = (typeof SLOT_IDS)[keyof typeof SLOT_IDS];

/** 全部槽位 id 的集合，用于快速判断"认不认得"。 */
export const SUPPORTED_SLOTS: ReadonlySet<string> = new Set(Object.values(SLOT_IDS));

/**
 * 宿主支持的组件类型。
 *
 * <p><b>⚠️ 必须与后端 {@code PluginService.UI_TYPES} 保持同步</b> ——
 * 两边不一致的表现是"声明了但永远不显示"（后端放行、前端不渲染，或反之），
 * 且**没有任何报错**。后端已按这份清单过滤过一轮，这里是第二道防线（前端可能比后端旧）。</p>
 */
export const SUPPORTED_TYPES: ReadonlySet<string> = new Set([
  'button',
  'badge',
  'card',
  'list',
  'link',
]);
