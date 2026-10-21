import { http } from './http';

/**
 * 平台设置（功能开关层）：读取 / 写入 / 恢复默认。
 *
 * <p>值的语义见后端 `PlatformSettingService`：页面写下的值是**覆盖层**，
 * 它压过 application.yml、但压不过环境变量（运维的救命通道）。
 * 返回的 `restartRequiredKeys` 是"本次因值变化而需要重启"的键 —— 为空即全部即时生效。</p>
 */

export interface PlatformSettingItem {
  key: string;
  label: string;
  description: string;
  /** 控件类型：BOOL 开关 / ENUM 下拉 / NUMBER 数字 / LIST 逗号分隔清单 / TEXT 多行文本 */
  type: 'BOOL' | 'ENUM' | 'NUMBER' | 'LIST' | 'TEXT';
  value: string;
  /** ENUM 候选项（其他类型为空数组） */
  options: string[];
  /** 写入后需要重启才生效（装配期读取的项） */
  restartRequired: boolean;
  /** 是否被页面改过（true 时展示「恢复默认」） */
  overridden: boolean;
  /** 当前环境强制锁定（如桌面模式的 Kafka 开关） */
  locked: boolean;
  lockReason?: string | null;
}

export interface PlatformSettingGroup {
  name: string;
  items: PlatformSettingItem[];
}

export interface PlatformSettingsView {
  /** embedded = 桌面 / 内置库模式；server = 外部数据库 */
  runtimeMode: 'embedded' | 'server';
  groups: PlatformSettingGroup[];
}

export interface PlatformSettingsUpdateResult {
  view: PlatformSettingsView;
  /** 本次需要重启才生效的键（空数组 = 全部即时生效） */
  restartRequiredKeys: string[];
}

export function getPlatformSettings() {
  return http.get<PlatformSettingsView>('/api/v1/platform-settings');
}

/** 写入若干项（值一律以字符串传递，后端按注册表类型校验）。 */
export function updatePlatformSettings(values: Record<string, string>) {
  return http.put<PlatformSettingsUpdateResult>('/api/v1/platform-settings', { values });
}

/** 恢复默认：删掉覆盖层的键，回落配置文件 / 环境变量的值。 */
export function resetPlatformSettings(keys: string[]) {
  return http.post<PlatformSettingsUpdateResult>('/api/v1/platform-settings/reset', { keys });
}

/**
 * 桌面壳的「重启应用」能力（desktop/preload.js 暴露的 `window.apApp`）。
 * 浏览器 / 远程部署里为 null —— 界面据此隐藏「立即重启」按钮，只提示手动重启。
 */
export interface DesktopAppBridge {
  restart: () => Promise<boolean>;
}

/** 取桌面壳能力；不在桌面环境时返回 null（与 api/workspace.ts 的 desktopBridge 同一模式）。 */
export function appBridge(): DesktopAppBridge | null {
  const w = window as unknown as { apApp?: DesktopAppBridge };
  return w.apApp ?? null;
}
