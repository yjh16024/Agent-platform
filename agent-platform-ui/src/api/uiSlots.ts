import { http } from './http';

/**
 * 一条**界面贡献** —— 插件声明"往哪个位置放什么东西"。
 *
 * <p>由后端 `GET /api/v1/plugins/ui-contributions` 返回，是**已经过滤过**的：
 * 只含该智能体上生效（挂载 + enabled）的插件、组件类型在白名单内、
 * 且 `action` / `dataSource` 引用的工具确实存在。所以前端拿到的基本可以直接渲染。</p>
 */
export interface UiContribution {
  pluginId: string;
  pluginName?: string | null;
  /** 插件内唯一；用作 React key（与 pluginId 组合后全局唯一） */
  id?: string | null;
  /** 槽位 id；认不出的会被前端忽略 */
  slot?: string | null;
  /** 组件类型：button / badge / card / list / link */
  type?: string | null;
  /** 显示文本。**一律按纯文本渲染** —— 插件给的是不可信输入 */
  label?: string | null;
  /** 同槽位内排序，小的在前 */
  order?: number;
  /** 仅 link 用 */
  href?: string | null;
  /** 点击行为；为空表示不可点击 */
  action?: { kind?: string; tool?: string } | null;
  /** 动态数据来源；为空表示只显示静态 label */
  dataSource?: { tool?: string } | null;
}

/**
 * 拉取某智能体的全部界面贡献。
 *
 * <p>切智能体时必须重拉 —— 不同智能体挂的插件不同，界面元素也不同。</p>
 */
export function uiContributions(agentId: string) {
  return http.get<UiContribution[]>(
    `/api/v1/plugins/ui-contributions?agentId=${encodeURIComponent(agentId)}`,
  );
}
