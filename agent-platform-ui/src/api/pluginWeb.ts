import { http } from './http';
import type { UiContribution } from './uiSlots';

/**
 * 界面插件（独立前端 JS 插件）的 API（2026-10-19，D 档第 4 步）。
 *
 * <p>它和「插件」（jar）是两类东西：界面插件只有一段前端脚本 + 一个槽位声明，
 * **全局生效、不绑智能体**，装上就能用（像皮肤，但做的是功能）。</p>
 */

/**
 * 插件声明的**一个**配置项（2026-10-19）。
 *
 * <p>宿主据此渲染设置表单 —— 插件不需要自己写设置界面，只要在 manifest 里声明字段。
 * 这也是"插件自带配置"的正确形态：字段和默认值属于插件，渲染与存储属于宿主。</p>
 */
export interface PluginConfigField {
  /** 存储键（在同一插件内唯一）。 */
  key: string;
  label?: string;
  /**
   * 控件类型，缺省按 default 的类型推断。
   *
   * - `agent`：**智能体选择器**（2026-10-20）—— 让插件声明"我需要一个 agentId"，
   *   宿主渲染成下拉，而不是让用户去别处复制一串 UUID。值仍是 agentId 字符串。
   */
  type?: 'number' | 'text' | 'boolean' | 'select' | 'agent';
  default?: unknown;
  /** 表单下方的说明文字。 */
  description?: string;
  min?: number;
  max?: number;
  step?: number;
  /** `type=select` 的选项；字符串数组也接受。 */
  options?: Array<{ label: string; value: string | number } | string>;
}

export interface WebPluginItem {
  id: string;
  name: string;
  version?: string;
  description?: string;
  /** 槽位 id（如 global.overlay） */
  slot?: string;
  label?: string;
  /** 入口脚本（相对插件目录） */
  entry?: string;
  /** 样式（可选） */
  style?: string;
  /** 是否 Shadow DOM 隔离 */
  isolate?: boolean;
  order?: number;
  /**
   * Node 侧入口文件名（manifest 的 `main`）。
   *
   * <p>有值 = 这个插件带一段跑在 `utilityProcess` 里的 Node 代码。
   * 设置表单据此决定**是否要把配置同步到插件文件**（localStorage 在子进程里读不到）。</p>
   */
  main?: string | null;
  /** 插件声明的配置项（有它才会出现「设置」按钮）。 */
  config?: PluginConfigField[] | null;
  /**
   * 作用域（manifest 声明，2026-10-20）：
   * - `global`（缺省）—— 通用插件，所有智能体都生效，界面上**不显示**挂载入口；
   * - `agent` —— 需要按智能体隔离的插件，才出现「挂载范围」选择。
   */
  scope?: string | null;
  /**
   * 挂载范围（仅 `scope=agent` 有意义）：允许生效的智能体 id 列表。
   *
   * <p>**空数组 = 全局**（所有智能体都生效）。非空时只在列出的智能体上出现。</p>
   */
  agents?: string[] | null;
  enabled?: boolean;
}

/** 已安装的界面插件。 */
export function listPluginWeb() {
  return http.get<WebPluginItem[]>('/api/v1/plugin-web');
}

/**
 * 启用中的界面插件对槽位的声明（前端加载依据；格式与 /plugins/ui-contributions 一致）。
 *
 * @param agentId 当前智能体。**建议必传** —— 后端据此过滤"挂载范围"；
 *                不传等价于"不过滤"，会把所有插件都返回（管理页看全量时才这样用）。
 */
export function pluginWebContributions(agentId?: string) {
  const q = agentId ? `?agentId=${encodeURIComponent(agentId)}` : '';
  return http.get<UiContribution[]>(`/api/v1/plugin-web/contributions${q}`);
}

/** 设置挂载范围（空数组 = 全局）。 */
export function setPluginWebAgents(id: string, agents: string[]) {
  return http.post<void>(`/api/v1/plugin-web/${encodeURIComponent(id)}/agents`, { agents });
}

/** 上传 zip 安装（覆盖同名）。 */
export function installPluginWeb(file: File) {
  const form = new FormData();
  form.append('file', file);
  return http.postForm<WebPluginItem>('/api/v1/plugin-web/install', form);
}

/** 启停（热切换，不需要重启）。 */
export function setPluginWebEnabled(id: string, enabled: boolean) {
  return http.post<void>(`/api/v1/plugin-web/${encodeURIComponent(id)}/enabled`, { enabled });
}

/** 卸载（删目录）。 */
export function deletePluginWeb(id: string) {
  return http.delete<void>(`/api/v1/plugin-web/${encodeURIComponent(id)}`);
}
