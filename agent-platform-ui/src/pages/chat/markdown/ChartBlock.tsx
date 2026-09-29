import { useMemo, type ReactNode } from 'react';
import { formatValue as fmt, parseChartSpec, type ChartSpec } from './chart-spec';

/**
 * ` ```chart ` 围栏 → SVG 图表。
 *
 * <h3>为什么手绘 SVG，而不是引一个图表库</h3>
 * 项目已经做过这个决策（见 `pages/ops/ReportsPage.tsx` 的类注释："为了几个条形图引入
 * 一整个图表库不划算，这里用 div 宽度画条形 —— 零依赖、加载即渲染，而且配色跟着主题走"）。
 * 这里沿用同一判断，只是从"div 画条"升级到"SVG 画"，因为折线和饼图用 div 拼很别扭。
 *
 * <p>另外两条现实理由：① 这台机器上"随手 npm i"出过事故（roadmap 有记录）；
 * ② 装一个图表库要几百 KB，而这里只需要三种图。</p>
 *
 * <h3>模型要输出什么</h3>
 * <pre>
 * ```chart
 * { "type": "bar", "title": "各渠道询盘量",
 *   "data": [ { "label": "阿里", "value": 120 }, { "label": "独立站", "value": 86 } ] }
 * ```
 * </pre>
 * <b>只要 data 这一项是必填的</b>：`type` 缺省按 `bar`、`title` 可省。
 * 这样模型少写一个字段也能出图，而不是整段失败。
 *
 * <h3>★ 解析失败必须"退回去"，不能"什么都不显示"</h3>
 * 模型给的 JSON 完全可能不合法或结构不合预期（它只是在"照着提示词写文本"）。
 * 那种时候<b>把原始文本原样显示出来</b> —— 用户至少能看到模型想表达什么，
 * 而不是面对一片空白去猜"为什么这里什么都没有"。这与项目既有的降级哲学一致
 * （见 `components/ToolApprovalWatcher.tsx`："认不出的结构退回原始 JSON —— 宁可难看，也不能漏掉信息"）。
 */

// ---------------------------------------------------------------- 缓存
//
// ★ 模块级缓存是本组件能在流式下使用的关键：
// 聊天页每个 token 都会重渲染整页，没有缓存的话每次都要重新解析 JSON + 重建 SVG。
// 键就是源码文本 —— 同样的内容永远得到同样的图。

const CACHE_LIMIT = 200;
const cache = new Map<string, ReactNode>();

export function ChartBlock({ code }: { code: string }) {
  const node = useMemo(() => {
    const hit = cache.get(code);
    if (hit !== undefined) return hit;
    const built = buildChart(code);
    // 简单上限：超出就丢最早的一条（这些是纯展示缓存，丢了只是重算一次）
    if (cache.size >= CACHE_LIMIT) {
      const first = cache.keys().next().value;
      if (first !== undefined) cache.delete(first);
    }
    cache.set(code, built);
    return built;
  }, [code]);

  return <>{node}</>;
}

function buildChart(code: string): ReactNode {
  const parsed = parseChartSpec(code);
  if (!parsed.ok) {
    // 失败时退回代码块 + 一行原因（见类注释：宁可难看，也不能什么都不显示）
    return (
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 11, color: 'rgba(200,90,40,0.9)', marginBottom: 2 }}>
          图表解析失败（{parsed.reason}），已按原文显示：
        </div>
        <pre style={FALLBACK_PRE}>{code}</pre>
      </div>
    );
  }
  return <Chart spec={parsed.spec} />;
}

// ---------------------------------------------------------------- 绘制

const PALETTE = ['#1677ff', '#52c41a', '#faad14', '#eb2f96', '#722ed1', '#13c2c2', '#fa541c'];
const W = 320;
const H = 180;
const PAD = { top: 22, right: 12, bottom: 26, left: 34 };

function Chart({ spec }: { spec: ChartSpec }) {
  return (
    <div style={{ marginBottom: 8, maxWidth: 420 }}>
      {spec.title && (
        <div style={{ fontSize: 12.5, fontWeight: 600, marginBottom: 2 }}>{spec.title}</div>
      )}
      <svg
        viewBox={`0 0 ${W} ${H}`}
        style={{
          width: '100%',
          height: 'auto',
          background: 'rgba(127,127,127,0.06)',
          border: '1px solid var(--ap-border)',
          borderRadius: 6,
        }}
        role="img"
        aria-label={spec.title || `${spec.type} 图表`}
      >
        {spec.type === 'bar' && <Bars data={spec.data} />}
        {spec.type === 'line' && <Line data={spec.data} />}
        {spec.type === 'pie' && <Pie data={spec.data} />}
        <Axis data={spec.data} />
      </svg>
    </div>
  );
}

/** 坐标轴 + x 轴标签（三种图共用的底）。 */
function Axis({ data }: { data: ChartSpec['data'] }) {
  const innerW = W - PAD.left - PAD.right;
  const baseline = H - PAD.bottom;
  return (
    <g>
      <line
        x1={PAD.left}
        y1={baseline}
        x2={W - PAD.right}
        y2={baseline}
        stroke="rgba(127,127,127,0.35)"
        strokeWidth={1}
      />
      {data.map((d, i) => {
        const slot = innerW / data.length;
        const x = PAD.left + slot * i + slot / 2;
        return (
          <text
            key={i}
            x={x}
            y={baseline + 14}
            fontSize={9}
            textAnchor="middle"
            fill="rgba(127,127,127,0.95)"
          >
            {clip(d.label, 6)}
          </text>
        );
      })}
    </g>
  );
}

function Bars({ data }: { data: ChartSpec['data'] }) {
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  // 以最大值为基准；允许负值（模型给负数时把它画成 0 高度而不是让柱子跑出画布）
  const max = Math.max(...data.map((d) => d.value), 0) || 1;
  const slot = innerW / data.length;
  const barW = Math.min(slot * 0.6, 34);

  return (
    <g>
      {data.map((d, i) => {
        const h = Math.max(0, (d.value / max) * innerH);
        const x = PAD.left + slot * i + (slot - barW) / 2;
        function y() {
          return H - PAD.bottom - h;
        }
        return (
          <g key={i}>
            <rect x={x} y={y()} width={barW} height={h} rx={2} fill={PALETTE[i % PALETTE.length]} />
            <text
              x={x + barW / 2}
              y={y() - 3}
              fontSize={9}
              textAnchor="middle"
              fill="rgba(127,127,127,0.95)"
            >
              {fmt(d.value)}
            </text>
          </g>
        );
      })}
    </g>
  );
}

function Line({ data }: { data: ChartSpec['data'] }) {
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const max = Math.max(...data.map((d) => d.value), 0) || 1;
  const min = Math.min(...data.map((d) => d.value), 0);
  const span = max - min || 1;
  const step = data.length > 1 ? innerW / (data.length - 1) : 0;

  const pts = data.map((d, i) => {
    const x = PAD.left + step * i;
    const y = PAD.top + innerH - ((d.value - min) / span) * innerH;
    return { x, y, value: d.value };
  });

  return (
    <g>
      <polyline
        points={pts.map((p) => `${p.x},${p.y}`).join(' ')}
        fill="none"
        stroke={PALETTE[0]}
        strokeWidth={1.6}
      />
      {pts.map((p, i) => (
        <g key={i}>
          <circle cx={p.x} cy={p.y} r={2.4} fill={PALETTE[0]} />
          <text x={p.x} y={p.y - 6} fontSize={8.5} textAnchor="middle" fill="rgba(127,127,127,0.95)">
            {fmt(p.value)}
          </text>
        </g>
      ))}
    </g>
  );
}

function Pie({ data }: { data: ChartSpec['data'] }) {
  const cx = W / 2;
  const cy = H / 2;
  const r = Math.min(W, H) / 2 - 26;
  const total = data.reduce((s, d) => s + Math.max(0, d.value), 0);
  if (total <= 0) return null;

  let angle = -Math.PI / 2; // 从 12 点方向开始，顺时针
  const slices = data.map((d, i) => {
    const frac = Math.max(0, d.value) / total;
    const sweep = frac * Math.PI * 2;
    const x1 = cx + r * Math.cos(angle);
    const y1 = cy + r * Math.sin(angle);
    const end = angle + sweep;
    const x2 = cx + r * Math.cos(end);
    const y2 = cy + r * Math.sin(end);
    const large = sweep > Math.PI ? 1 : 0;
    // 整圆用单条弧线画不出来（起点=终点），退化成 circle
    const d1 = frac >= 0.9999
      ? `M ${cx} ${cy - r} A ${r} ${r} 0 1 1 ${cx - 0.01} ${cy - r} Z`
      : `M ${cx} ${cy} L ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} Z`;
    angle = end;
    return { d: d1, color: PALETTE[i % PALETTE.length], pct: Math.round(frac * 100), label: d.label };
  });

  return (
    <g>
      {slices.map((s, i) => (
        <path key={i} d={s.d} fill={s.color} stroke="var(--ap-bg-container)" strokeWidth={1} />
      ))}
      {slices.map((s, i) => (
        <text
          key={`t${i}`}
          x={PAD.left + 2}
          y={PAD.top + 8 + i * 11}
          fontSize={9}
          fill="rgba(127,127,127,0.95)"
        >
          <tspan fill={s.color}>■</tspan> {clip(s.label, 6)} {s.pct}%
        </text>
      ))}
    </g>
  );
}

function clip(s: string, max: number): string {
  if (!s) return '';
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

const FALLBACK_PRE: React.CSSProperties = {
  margin: 0,
  padding: '8px 10px',
  fontSize: 12,
  lineHeight: 1.5,
  background: 'rgba(127,127,127,0.10)',
  border: '1px solid var(--ap-border)',
  borderRadius: 6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};
