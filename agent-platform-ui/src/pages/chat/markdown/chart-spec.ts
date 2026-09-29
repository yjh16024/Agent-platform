/**
 * ` ```chart ` 围栏的内容规格与解析。
 *
 * <h3>为什么单独一个文件</h3>
 * 它是<b>纯函数、零依赖</b> —— 于是测试可以直接用 `esbuild + node:test` 跑它
 * （与 `pages/workflows/canvas/adapter.ts` 同一个套路），不必为了测几行 JSON 解析
 * 去搭一套组件测试设施。绘制部分留在 `ChartBlock.tsx`（那边要 React）。
 *
 * <h3>宽容优先</h3>
 * 模型给的 JSON 只是"照着提示词写的文本"，缺字段、多字段、值写成字符串都很常见。
 * 所以这里的原则是：<b>能出图就出图</b> —— 缺 `type` 按柱状图算，值能转成数字就用。
 * 只有真正无法成图时才失败，并由调用方退回原文显示。
 */

/** 一种图表的规格。 */
export interface ChartSpec {
  type: 'bar' | 'line' | 'pie';
  title?: string;
  data: { label: string; value: number }[];
}

/** 解析结果：要么是规格，要么是"为什么不行"。 */
export type ChartParse =
  | { ok: true; spec: ChartSpec }
  | { ok: false; reason: string };

/**
 * 解析 chart 围栏内容。纯函数（同样的输入永远得到同样的输出，便于缓存）。
 */
export function parseChartSpec(code: string): ChartParse {
  const raw = (code || '').trim();
  if (!raw) return { ok: false, reason: '内容为空' };

  let obj: unknown;
  try {
    obj = JSON.parse(stripFenceNoise(raw));
  } catch (e) {
    return { ok: false, reason: 'JSON 不合法：' + (e as Error).message };
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return { ok: false, reason: '内容不是 JSON 对象' };
  }
  const o = obj as Record<string, unknown>;

  const rows = o.data;
  if (!Array.isArray(rows) || rows.length === 0) {
    return { ok: false, reason: '缺少 data 数组（至少一个数据点）' };
  }

  const data: { label: string; value: number }[] = [];
  for (const r of rows) {
    if (!r || typeof r !== 'object') continue;
    const item = r as Record<string, unknown>;
    const value = Number(item.value);
    if (!Number.isFinite(value)) continue;
    data.push({ label: item.label == null ? '' : String(item.label), value });
  }
  if (data.length === 0) {
    return { ok: false, reason: 'data 里没有可用的 {label, value} 项' };
  }

  const t = String(o.type ?? 'bar').toLowerCase();
  const type: ChartSpec['type'] = t === 'line' || t === 'pie' ? t : 'bar';
  const title = typeof o.title === 'string' && o.title.trim() ? o.title.trim() : undefined;
  return { ok: true, spec: { type, title, data } };
}

/** 模型有时会在 JSON 前后多写几个反引号或空行，清掉再解析。 */
function stripFenceNoise(raw: string): string {
  return raw.replace(/^\s*`{3,}\s*/, '').replace(/\s*`{3,}\s*$/, '').trim();
}

/** 数字展示：整数不带小数点，大数不出现一长串小数。 */
export function formatValue(v: number): string {
  if (Number.isInteger(v)) return String(v);
  return Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}
