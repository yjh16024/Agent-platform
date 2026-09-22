import { http } from './http';

/**
 * 皮肤市场：读取**外部皮肤市场地址**（如 DSH Web GUI 皮肤市场的站点/仓库/catalog.json），
 * 按条目信息展示，点安装后按条目里的 install.target 从源码下载。
 */

/** 一个皮肤条目的安装信息。 */
export interface SkinInstall {
  target?: string;
  version?: string;
  commit?: string;
  repo?: string;
  ref?: string;
  subPath?: string;
  desktop?: Record<string, string>;
}

/** 市场里的一个皮肤条目（字段与 catalog.json 对齐，去掉用不到的嵌套）。 */
export interface SkinEntry {
  id: string;
  name: string;
  nameZh?: string;
  nameEn?: string;
  author?: string;
  description?: string;
  repo?: string;
  packageName?: string;
  rowId?: string;
  category?: string;
  tags?: string[];
  modes?: string[];
  screenshots?: string[];
  listScreenshot?: string;
  license?: { code?: string; commercialUse?: string | boolean; notice?: string };
  review?: Record<string, string>;
  compatibility?: Record<string, unknown>;
  health?: { status?: string; checks?: Record<string, string>; suggestions?: string[] };
  featuredRank?: number;
  stars?: number;
  install?: SkinInstall;
  installed?: boolean;
}

/** 读取市场的结果。 */
export interface SkinMarketResult {
  repo?: string;
  branch?: string;
  catalog?: string;
  generatedAt?: string;
  schemaVersion?: number;
  count: number;
  skipped?: number;
  channel?: string;
  skins: SkinEntry[];
}

/** 已安装的皮肤（本地记录）。 */
export interface InstalledSkin {
  id: string;
  name?: string;
  author?: string;
  repo?: string;
  /** 安装时钉住的 commit（pin 到 commit 安装时它就等于 commit），版本锚点 */
  ref?: string;
  /** `.skin-meta.json` 里显式记录的 commit（2026-09-17 之前因 bug 恒为 null，现已修） */
  commit?: string;
  /** 皮肤包版本号（市场条目 install.version） */
  version?: string;
  subPath?: string;
  license?: { code?: string; commercialUse?: string | boolean; notice?: string };
  listScreenshot?: string;
  screenshots?: string[];
  modes?: string[];
  tags?: string[];
  files?: number;
  installedAt?: string;
  dir?: string;
  /**
   * 这个皮肤依赖的**配套插件**（后端扫它的 bundle 得出，如 `better-sidebar`）。
   *
   * <p>空数组 = 不依赖任何配套插件。DSH 生态里皮肤之间互相依赖是常态
   * （市场目录与 skin.json 都不声明它，所以只能扫 bundle），
   * 我们能做的是把它如实告诉用户，而不是假装没有。</p>
   */
  partnerPlugins?: string[];
}

/** 读取皮肤市场地址。 */
export function readSkinMarket(url: string, refresh = false) {
  return http.post<SkinMarketResult>('/api/v1/skins/market/read', { url, refresh });
}

/** 安装某个皮肤条目（整条回传，后端取 id 与 install.target）。 */
export function installSkin(entry: SkinEntry) {
  return http.post<{ id: string; dir: string; files: number; failed?: number; repo: string; ref: string; subPath?: string }>(
    '/api/v1/skins/market/install',
    entry,
  );
}

/** 已安装皮肤列表。 */
export function listInstalledSkins() {
  return http.get<InstalledSkin[]>('/api/v1/skins/installed');
}

/** 卸载皮肤。 */
export function uninstallSkin(id: string) {
  return http.post<{ id: string; removed: boolean }>('/api/v1/skins/uninstall', { id });
}

/** 把远端图片走本机后端代理（解决国内直连 raw.githubusercontent 显示不出来的问题）。 */
export function proxyImageUrl(url?: string | null) {
  if (!url) {
    return '';
  }
  return `/api/v1/skins/proxy?url=${encodeURIComponent(url)}`;
}

/**
 * 取皮肤的**客户端 bundle 文本**（自注册脚本），供 `skin/runtime.ts` 注入执行。
 *
 * <p>必须走这里而不是裸 `fetch`：`SkinController` 上有
 * `@RequiresPermission("skin:manage")`，而 {@link http} 会注入
 * `Authorization: Bearer <token>`。用裸 fetch 请求不带 token，
 * 在 `security.enabled=true` 下必然 401 —— 表现为点「运行 JS」时报
 * 「取皮肤 bundle 失败：HTTP 401」。</p>
 *
 * <p>注：`/api/v1/skins/proxy`（图片）走的是另一条路 —— 它由 `<img src>` 调用、
 * 无法带 token，所以在 `JwtAuthFilter` 里被显式列为匿名路径。</p>
 */
export function fetchSkinBundle(id: string) {
  return http.get<{ text?: string; path?: string }>(
    `/api/v1/skins/bundle?id=${encodeURIComponent(id)}`,
  );
}
