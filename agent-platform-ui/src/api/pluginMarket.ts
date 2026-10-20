import { getTenantId } from './http';

/**
 * 「插件市场」的清单读取（2026-10-19，P4 阶段 4）。
 *
 * <h3>清单就是一个静态 JSON</h3>
 * <pre>
 * {
 *   "name": "白雾插件市场（示例源）",
 *   "plugins": [
 *     { "id": "usage_widget", "name": "会话挂件", "version": "1.0.0",
 *       "description": "左下角小卡片", "zip": "/plugin-market/usage-widget.zip" }
 *   ]
 * }
 * </pre>
 *
 * <p>这样设计的理由：**任何人放一个 JSON 就能做自己的源**（文件服务器 / GitHub Raw / 内网），
 * 平台不需要维护中心服务；默认指向内置示例源（随应用发布），用户也可以粘贴任意地址。</p>
 *
 * <p>⚠️ 直读外部 URL 受 CORS 限制：源必须允许跨域读取（GitHub Raw、jsDelivr 都允许）。
 * 不允许时错误会明确报"读取清单失败（HTTP xxx）"，而不是空白列表。</p>
 */
export interface MarketPluginEntry {
  id: string;
  name: string;
  version?: string;
  description?: string;
  author?: string;
  /** zip 的地址：相对路径（内置源）或绝对 URL（外部源）。 */
  zip: string;
  homepage?: string;
  tags?: string[];
}

export interface MarketCatalog {
  name?: string;
  generatedAt?: string;
  plugins: MarketPluginEntry[];
}

/** 内置示例源（随应用发布的静态文件）。 */
export const BUILTIN_MARKET_SOURCE = '/plugin-market.json';

/** 读取清单；`source` 为空则用内置源。 */
export async function loadMarketCatalog(source?: string): Promise<MarketCatalog> {
  const url = (source && source.trim()) || BUILTIN_MARKET_SOURCE;
  let res: Response;
  try {
    res = await fetch(url, { headers: { 'X-Tenant-Id': getTenantId() } });
  } catch (e) {
    throw new Error(`读取清单失败（网络/CORS）：${(e as Error).message}`);
  }
  if (!res.ok) {
    throw new Error(`读取清单失败（HTTP ${res.status}）：${url}`);
  }
  const data = (await res.json()) as MarketCatalog;
  if (!data || !Array.isArray(data.plugins)) {
    throw new Error('清单格式不对：需要一个 { "plugins": [ ... ] } 的 JSON');
  }
  return data;
}

/**
 * 下载插件 zip，包成 `File` 交给安装接口。
 *
 * <p>用 `fetch` 而不是 `<a download>`：安装接口要的是 File 对象；
 * 而且跨域下载在浏览器里本来也只能走 fetch（受 CORS 约束，与清单同源策略一致）。</p>
 */
export async function downloadPluginZip(entry: MarketPluginEntry): Promise<File> {
  if (!entry.zip) {
    throw new Error(`「${entry.name}」的清单项缺少 zip 地址`);
  }
  const res = await fetch(entry.zip);
  if (!res.ok) {
    throw new Error(`下载插件包失败（HTTP ${res.status}）：${entry.zip}`);
  }
  const blob = await res.blob();
  const fileName = entry.zip.split('/').pop() || `${entry.id}.zip`;
  return new File([blob], fileName, { type: 'application/zip' });
}
