import { http } from './http';

/**
 * 工作区设置 —— 决定智能体的 `fs_*` 工具能碰到哪个目录。
 *
 * <p><b>为什么要有这个界面</b>：工作区根以前只能靠环境变量设置，桌面版用户没有改环境变量的入口，
 * 默认值又是个空目录 —— 于是"让智能体帮我改文件"在界面上无路可走。</p>
 *
 * <p>⚠️ 这里的 `root` 就是模型**可读写范围的全部**。后端会拒绝把它设成盘根 / 系统目录 /
 * 用户主目录本身 / 凭据目录（见 `WorkspaceService.checkRootSafety`），
 * 但"选中一个合适的项目目录"这件事仍然需要人来做判断 —— 所以界面必须把当前值**显式显示出来**。</p>
 */
export interface WorkspaceStatus {
  /** 总开关（`AGENT_WORKSPACE_ENABLED`）；关掉时所有 fs_* 工具直接拒绝。 */
  available: boolean;
  /** 当前生效的根（绝对路径）。 */
  root: string;
  /** true = 来自界面设置；false = 来自配置/环境变量。 */
  overridden: boolean;
  maxReadBytes: number;
  /** 遍历时会跳过的目录名（`.git` / `node_modules` 等）。 */
  skipDirs: string[];
  /** 凭证隔离名单摘要（即使在工作区内也不会被碰的那些）。 */
  protectedSummary: string[];
}

export function getWorkspace() {
  return http.get<WorkspaceStatus>('/api/v1/workspace');
}

/** 设置工作区根。路径不安全时后端返回 4xx，且**不会**改变当前值。 */
export function setWorkspace(path: string) {
  return http.put<WorkspaceStatus>('/api/v1/workspace', { path });
}

/** 重置为配置/环境变量里的默认根。 */
export function resetWorkspace() {
  return http.delete<WorkspaceStatus>('/api/v1/workspace');
}

/**
 * 桌面壳暴露的能力（`desktop/preload.js`）。
 * 浏览器里为 `undefined` —— 那时界面退化为手动输入路径，且无法推断文件来源目录。
 */
export interface DesktopWorkspaceBridge {
  pickDirectory: () => Promise<string | null>;
  /** 从 File 取真实绝对路径（Electron 32+ 已移除 `File.path`，必须走 webUtils）。 */
  getPathForFile?: (file: File) => string | null;
}

/** 取桌面壳的能力；不在桌面环境时返回 null。 */
export function desktopBridge(): DesktopWorkspaceBridge | null {
  const w = window as unknown as { apWorkspace?: DesktopWorkspaceBridge };
  return w.apWorkspace ?? null;
}

/**
 * 推断"这个文件来自哪个目录"。
 *
 * <p>用途：用户拖进一个文件时，**提示**他"要不要把这个目录设为工作区" ——
 * 之所以只提示、不自动设，是因为工作区根等于模型可读写范围的全部，
 * 而读取是不走审批的：若根随拖拽自动漂移，用户会在毫无察觉的情况下把整块盘交出去。</p>
 *
 * @returns 目录绝对路径；桌面壳不可用 / 拿不到路径 / 已在盘根（无父目录）时为 null
 */
export function sourceDirOf(file: File): string | null {
  const bridge = desktopBridge();
  if (!bridge?.getPathForFile) {
    return null;
  }
  let full: string | null = null;
  try {
    full = bridge.getPathForFile(file);
  } catch {
    return null;
  }
  if (!full) {
    return null;
  }
  const cut = Math.max(full.lastIndexOf('\\'), full.lastIndexOf('/'));
  // cut <= 0 说明文件就在盘根（如 C:\a.txt）—— 那种"父目录"恰恰是我们必须拒绝的，不提示
  return cut > 0 ? full.slice(0, cut) : null;
}
