/**
 * 类名片段钩子层（"命中更好层"）。
 *
 * <h3>为什么需要这一层</h3>
 * 属性钩子（{@code data-*}）是 DSH 皮肤的**首选**匹配方式，但皮肤也会用
 * {@code [class*='sidebarCol']} 这种**子串匹配**去够宿主的内部类名。测绘结果：
 * <pre>
 *   host-class fragments: 60 unique，其中被 >=2 个皮肤共用的有 ~20 个
 *   sidebarCol(2sk)  regionArea(3sk)  centerCol  logoRow(2sk)  footArea
 *   newSession(2sk)  headlineText(2sk)  previewBadge(2sk)
 *   cardWorkspaceTrigger(2sk)  triggerLabel(3sk)  triggerEffort(3sk)
 *   _row(2sk)  _rowText(2sk)  _card(2sk)  _bubble(3sk)  _pending(2sk)
 *   _selectInput(2sk)  _selector(2sk)  _userStack(2sk)  markdown
 * </pre>
 * 皮肤作者自己把这一层标为 **DRIFT-RISK**（macintosh 源码注释原文：
 * {@code 优先级纪律：data-* > aria/role > 结构/class（后两者标 DRIFT-RISK）}），
 * 所以它属于"命中更好、不命中也能用"，**只在语义真的对应时才挂**。
 *
 * <h3>两条自律</h3>
 * <ol>
 *   <li><b>只挂被 >=2 个皮肤共用的片段</b>——那些才是"契约"；单皮肤出现的多数是皮肤私有命名空间。</li>
 *   <li><b>只挂语义真的对应的元素</b>。宁可少挂：把 {@code [class*='row']} 这种超通用片段
 *       挂到一堆元素上，会让皮肤的规则**误伤**我们的布局，比不命中更糟。</li>
 * </ol>
 *
 * <h3>为什么能命中</h3>
 * DSH 自己也是 CSS Modules，类名形如 {@code <hash>_<name>}（实测 {@code ._0cMdVG_lightScene}），
 * 所以皮肤用**子串**匹配。我们只要把这些片段作为**附加类名**挂上去即可命中。
 */

/** 侧栏容器（皮肤用它给侧栏换皮、加边框）。 */
export const CLASS_SIDEBAR_COL = 'sidebarCol';
/** 侧栏里的会话/列表区域。 */
export const CLASS_REGION_AREA = 'regionArea';
/** 侧栏品牌行（logo + 标题）。 */
export const CLASS_LOGO_ROW = 'logoRow';
/** 主内容列。 */
export const CLASS_CENTER_COL = 'centerCol';
/** 「新会话」按钮。 */
export const CLASS_NEW_SESSION = 'newSession';
/** 着陆页/空态的大标题（orca 的 hero 打字机光标挂在它上面）。 */
export const CLASS_HEADLINE_TEXT = 'headlineText';
/** 输入卡片里的工作区/工具触发区。 */
export const CLASS_COMPOSER_TRIGGER = 'cardWorkspaceTrigger';
/** 输入卡片里下拉的标签文字。 */
export const CLASS_TRIGGER_LABEL = 'triggerLabel';
/** 输入卡片里的模型/强度标签。 */
export const CLASS_TRIGGER_EFFORT = 'triggerEffort';
/** 消息列表项。 */
export const CLASS_ROW = '_row';
/** 消息列表项的文本部分。 */
export const CLASS_ROW_TEXT = '_rowText';
/** 消息卡片。 */
export const CLASS_CARD = '_card';
/** 消息气泡。 */
export const CLASS_BUBBLE = '_bubble';
/** 流式/等待中的占位。 */
export const CLASS_PENDING = '_pending';
/** 消息正文容器（markdown 渲染区）。 */
export const CLASS_MARKDOWN = 'markdown';

// ---- 以下是「为皮肤补平台功能」时新增的：先有功能，才有可挂的钩子 ----

/**
 * 侧栏搜索输入框。
 *
 * <p>orca 的 `rail-search.ts` 精确地找
 * {@code [data-slot='sidebar'] input[class*='searchInput']}，
 * 并要求同一行内存在 {@code button[class*='searchButton']}（最多向上 3 层祖先），
 * 且该 button 要带 `aria-expanded`。所以两者**必须在同一行内**。</p>
 */
export const CLASS_SEARCH_INPUT = 'searchInput';
/** 侧栏搜索按钮（带 aria-expanded 状态）。 */
export const CLASS_SEARCH_BUTTON = 'searchButton';
/** 侧栏搜索所在的行容器。 */
export const CLASS_SEARCH_ROW = 'searchRow';
/** 侧栏底部区域（DSH 把账户/设置入口放这里）。 */
export const CLASS_FOOT_AREA = 'footArea';
/** 侧栏底部的操作按钮组。 */
export const CLASS_FOOTER_ACTIONS = 'footerActions';
/** 同一用户连续消息的堆叠容器。 */
export const CLASS_USER_STACK = '_userStack';
/** 流式/思考中的状态行。 */
export const CLASS_STREAM_ROW = 'streamRow';

// ---- A 类欠账补齐：这些是测绘里"被 >=2 个皮肤共用"、且我们有真实对应控件的片段 ----

/** 设置面板里的选择输入（DSH 设置项）。我们有 Select，可以诚实挂上。 */
export const CLASS_SELECT_INPUT = '_selectInput';
/** 设置面板里的选择器容器。 */
export const CLASS_SELECTOR = '_selector';
/** 预览徽章（DSH 输入区上方的能力徽章）。 */
export const CLASS_PREVIEW_BADGE = 'previewBadge';
/** 右侧详情列。 */
export const CLASS_DETAILS_COL = 'detailsCol';
/** 顶部标题栏。 */
export const CLASS_TITLEBAR = 'titlebar';

/** 把若干片段拼进 className（过滤空值）。 */
export function frag(...parts: Array<string | undefined | false>): string {
  return parts.filter(Boolean).join(' ');
}

/** 契约版本（与 contract.ts 同步；此处单独导出便于在支持度缓存里作为 key 的一部分）。 */
export const CLASS_FRAGMENT_VERSION = 1;
