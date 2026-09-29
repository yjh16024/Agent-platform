import { memo, useMemo, type CSSProperties, type ReactNode } from 'react';
import { parseMarkdown, type Block, type Inline } from './parse';
import { ChartBlock } from './ChartBlock';

/**
 * Markdown → React 元素。
 *
 * <h3>为什么不产生 HTML</h3>
 * 全程只构造 React 元素，**没有任何 `dangerouslySetInnerHTML`** ——
 * 这是刻意的（项目在 `slots/SlotOutlet.tsx` 里明文禁止它），
 * 而这里渲染的又是<b>模型输出</b>（完全不可信的输入）。
 * 由 React 负责把文本当文本插入，转义问题就不存在了。
 *
 * <h3>★ 流式性能：这是本文件最重要的设计约束</h3>
 * 聊天页<b>没有虚拟滚动、没有 selector 订阅、消息 key 用的是下标</b> ——
 * 流式时<b>每个 token 都会让整页重渲染一次</b>。
 * 如果图表直接在 render 里解析并绘制，就会：每帧解析一次 JSON、每帧重建一次 SVG、
 * 界面持续闪烁、CPU 打满，还会连带触发皮肤侧的 MutationObserver。
 *
 * <p>所以有两条硬规矩：</p>
 * <ol>
 *   <li><b>只有围栏闭合、且本轮已结束时才渲染图</b>（{@link ChartBlock} 内部判断）。
 *       未闭合时按普通代码块显示 —— 流式中间态本来就该是"还在写"。</li>
 *   <li><b>按源码文本缓存渲染结果</b>（见 ChartBlock 的模块级缓存），
 *       同一个 code 反复重渲染时直接复用。</li>
 * </ol>
 *
 * <h3>样式</h3>
 * 用 `--ap-*` 变量与 `currentColor` 的透明度，**不写死颜色** ——
 * 否则暗色模式下会像 `ToolCallList` 的旧底色那样几乎看不见。
 */

interface Props {
  content: string;
  /**
   * 本轮是否已结束（流式时为 false）。
   *
   * <p>它不是"要不要渲染"的开关，而是"要不要渲图"的开关：
   * 文本永远照常显示（流式就是要看到字一个个出来），
   * 但图表要等结束 —— 半截的 JSON 渲不出东西，只会闪。</p>
   */
  done: boolean;
}

/** 行内节点 → React 元素。 */
function renderInline(nodes: Inline[], keyPrefix = ''): ReactNode[] {
  return nodes.map((n, i) => {
    const key = `${keyPrefix}${i}`;
    switch (n.kind) {
      case 'strong':
        return <strong key={key}>{renderInline(n.children, `${key}-`)}</strong>;
      case 'em':
        return <em key={key}>{renderInline(n.children, `${key}-`)}</em>;
      case 'del':
        return <del key={key}>{renderInline(n.children, `${key}-`)}</del>;
      case 'code':
        return <code key={key} style={INLINE_CODE_STYLE}>{n.text}</code>;
      case 'link':
        return (
          <a
            key={key}
            href={n.href}
            target="_blank"
            /* noreferrer 防止把当前页面地址泄漏给目标站 */
            rel="noopener noreferrer"
            style={{ color: 'var(--ap-primary)' }}
          >
            {renderInline(n.children, `${key}-`)}
          </a>
        );
      default:
        return <span key={key}>{n.text}</span>;
    }
  });
}

/** 段落内的换行：Markdown 的单个换行在视觉上要保留（模型经常用它分行）。 */
function renderTextWithBreaks(nodes: Inline[], keyPrefix: string): ReactNode[] {
  const out: ReactNode[] = [];
  nodes.forEach((n, i) => {
    if (n.kind === 'text') {
      const parts = n.text.split('\n');
      parts.forEach((p, j) => {
        if (j > 0) out.push(<br key={`${keyPrefix}br-${i}-${j}`} />);
        if (p) out.push(<span key={`${keyPrefix}t-${i}-${j}`}>{p}</span>);
      });
    } else {
      out.push(...renderInline([n], `${keyPrefix}${i}-`));
    }
  });
  return out;
}

function renderBlock(b: Block, key: string, done: boolean): ReactNode {
  switch (b.kind) {
    case 'heading': {
      // 回复里的标题不该有页面级标题的分量：压到 h4~h6 的视觉量级，避免把气泡撑得很怪
      const size = [18, 16, 15, 14, 13, 13][Math.min(b.level, 6) - 1];
      return (
        <div key={key} style={{ fontSize: size, fontWeight: 600, margin: '10px 0 4px' }}>
          {renderInline(b.children, `${key}-`)}
        </div>
      );
    }

    case 'paragraph':
      return (
        <div key={key} style={{ marginBottom: 8 }}>
          {renderTextWithBreaks(b.children, `${key}-`)}
        </div>
      );

    case 'code':
      return <CodeBlock key={key} block={b} done={done} />;

    case 'list': {
      const Tag = b.ordered ? 'ol' : 'ul';
      return (
        <Tag
          key={key}
          start={b.ordered ? b.start : undefined}
          style={{ margin: '0 0 8px', paddingLeft: 22 }}
        >
          {b.items.map((item, i) => (
            <li key={i} style={{ marginBottom: 2 }}>
              {renderInline(item, `${key}-${i}-`)}
            </li>
          ))}
        </Tag>
      );
    }

    case 'quote':
      return (
        <blockquote
          key={key}
          style={{
            margin: '0 0 8px',
            padding: '2px 0 2px 10px',
            borderLeft: '3px solid var(--ap-border)',
            color: 'rgba(0,0,0,0.65)',
          }}
        >
          {b.children.map((c, i) => renderBlock(c, `${key}-${i}`, done))}
        </blockquote>
      );

    case 'table':
      return (
        <div key={key} style={{ overflowX: 'auto', marginBottom: 8 }}>
          <table style={TABLE_STYLE}>
            <thead>
              <tr>
                {b.head.map((cell, i) => (
                  <th key={i} style={{ ...CELL_STYLE, ...TH_STYLE, textAlign: b.align[i] ?? 'left' }}>
                    {renderInline(cell, `${key}-h${i}-`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {b.rows.map((row, ri) => (
                <tr key={ri}>
                  {/* 用表头列数对齐：模型给的行有时多一格少一格，多出来的丢掉、缺的补空 */}
                  {b.head.map((_, ci) => (
                    <td key={ci} style={{ ...CELL_STYLE, textAlign: b.align[ci] ?? 'left' }}>
                      {renderInline(row[ci] ?? [], `${key}-${ri}-${ci}-`)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );

    case 'hr':
      return <hr key={key} style={{ border: 'none', borderTop: '1px solid var(--ap-border)', margin: '10px 0' }} />;

    default:
      return null;
  }
}

/**
 * 代码块。
 *
 * <p>带语言标注且能识别时交给 {@link ChartBlock}；其余情况按普通代码显示。
 * **未闭合的围栏永远走普通显示** —— 那是流式中间态。</p>
 */
function CodeBlock({ block, done }: { block: Extract<Block, { kind: 'code' }>; done: boolean }) {
  const isChart = block.lang === 'chart';
  if (isChart && block.closed && done) {
    return <ChartBlock code={block.code} />;
  }
  // 未闭合 + chart 语言：给出"还在写"的提示，否则用户看到一坨 JSON 会以为模型写错了
  const pendingHint = isChart && !block.closed;
  return (
    <div style={{ marginBottom: 8 }}>
      <pre style={CODE_STYLE}>
        <code>{block.code}</code>
      </pre>
      {pendingHint && (
        <div style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)', marginTop: 2 }}>
          （图表内容仍在输出…）
        </div>
      )}
    </div>
  );
}

/**
 * 消息正文渲染器。
 *
 * <p>{@code memo} 是必需的：流式时父组件每帧重渲染，若本组件不 memo，
 * 每个 token 都会把整篇 Markdown 重解析一遍（长回复下这很可观）。
 * memo 之后 content 没变的历史消息会直接跳过。</p>
 */
export const Markdown = memo(function Markdown({ content, done }: Props) {
  const blocks = useMemo(() => parseMarkdown(content), [content]);
  return <div style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>
    {blocks.map((b, i) => renderBlock(b, String(i), done))}
  </div>;
});

// ---------------------------------------------------------------- 样式
//
// 度量沿用 ToolCallList.preStyle（12px / 1.5 / radius 4），保证两个面板视觉一致；
// 但底色改用 currentColor 的透明度 —— 硬编码 rgba(0,0,0,0.04) 在暗色下看不见。

const INLINE_CODE_STYLE: CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  fontSize: '0.92em',
  padding: '1px 4px',
  borderRadius: 3,
  background: 'rgba(127,127,127,0.14)',
};

const CODE_STYLE: CSSProperties = {
  margin: 0,
  padding: '8px 10px',
  overflow: 'auto',
  maxHeight: 400,
  fontSize: 12,
  lineHeight: 1.55,
  background: 'rgba(127,127,127,0.10)',
  border: '1px solid var(--ap-border)',
  borderRadius: 6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

const TABLE_STYLE: CSSProperties = {
  borderCollapse: 'collapse',
  fontSize: 12.5,
  minWidth: '100%',
};

const CELL_STYLE: CSSProperties = {
  border: '1px solid var(--ap-border)',
  padding: '4px 8px',
  verticalAlign: 'top',
};

const TH_STYLE: CSSProperties = {
  background: 'rgba(127,127,127,0.08)',
  fontWeight: 600,
  whiteSpace: 'nowrap',
};
