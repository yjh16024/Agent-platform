/**
 * 极简 Markdown 解析器 —— **零依赖、纯函数、不产生 HTML 字符串**。
 *
 * <h3>为什么自己写，而不是用 marked / react-markdown</h3>
 * 两个理由，一个是纪律、一个是现实：
 *
 * <ol>
 *   <li><b>安全性（决定性）</b>：`marked` 这类库的产物是 <b>HTML 字符串</b>，
 *       要用它就必须 `dangerouslySetInnerHTML` —— 而项目<b>明文禁止</b>这条
 *       （见 `slots/SlotOutlet.tsx` 的注释："那等于给每个插件一个 XSS 入口"）。
 *       更关键的是：<b>要渲染的是模型输出，即完全不可信的输入</b>。
 *       本解析器只产出<b>数据结构</b>，由 `render.tsx` 映射成 React 元素 ——
 *       没有 innerHTML，也就没有 XSS 面。（HTML 标签在这里被当作<b>普通文本</b>。）</li>
 *   <li><b>依赖现实</b>：仓库里 `marked` 只是 <b>传递依赖</b>（来自 semi / flowgram 的物料链），
 *       没写进 `package.json` —— 不是契约，一次 npm 去重就可能消失。
 *       而 `docs/roadmap.md` 记着这台机器上"随手 npm i"出过事故（prune 掉 15 个包），
 *       所以新增依赖这件事要克制。</li>
 * </ol>
 *
 * <h3>范围：够用就好，认不出的原样显示</h3>
 * 支持：标题、段落、<b>围栏代码块</b>（含语言标注）、有序/无序列表、引用、表格、分割线；
 * 行内：粗体、斜体、删除线、行内代码、链接。
 *
 * <p><b>刻意不支持</b>：HTML 标签（当文本）、嵌套列表（只解析一层）、图片、
 * 以及 CommonMark 的各种边界情况。<b>不支持的语法会原样保留成文字</b> ——
 * 这是刻意的选择：显示成原文总比显示错好，而且绝不会吞掉模型说的话。</p>
 *
 * <h3>★ 流式中间态：围栏可能"开了没关"</h3>
 * 流式输出时内容每帧都在变，` ```chart ` 出现之后、闭合之前必然经过一个"半截"状态。
 * 那种状态下 {@link Block.closed} 为 `false`，调用方<b>不要</b>拿它去渲染图表 ——
 * 否则每帧都会渲一张残缺的图。见 {@link FenceBlock.closed}。
 */

/** 表格列对齐（解析自分隔行 `:---:`）。 */
export type Align = 'left' | 'center' | 'right';

/** 行内节点。 */
export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'strong'; children: Inline[] }
  | { kind: 'em'; children: Inline[] }
  | { kind: 'del'; children: Inline[] }
  | { kind: 'code'; text: string }
  | { kind: 'link'; href: string; children: Inline[] };

/** 块级节点。 */
export type Block =
  | { kind: 'paragraph'; children: Inline[] }
  | { kind: 'heading'; level: number; children: Inline[] }
  | FenceBlock
  | { kind: 'list'; ordered: boolean; start: number; items: Inline[][] }
  | { kind: 'quote'; children: Block[] }
  | { kind: 'table'; head: Inline[][]; rows: Inline[][][]; align: Align[] }
  | { kind: 'hr' };

/** 围栏代码块。 */
export interface FenceBlock {
  kind: 'code';
  /** 语言标注（小写；无标注为空串）。可能是别名，如 `mmd`。 */
  lang: string;
  /** 围栏内容（不含首尾的 ``` 行；保留内部换行与缩进）。 */
  code: string;
  /**
   * 围栏是否闭合。
   *
   * <p><b>流式下这一位是必须的</b>：未闭合说明模型还在写这段内容，
   * 此时既不该把它当图表渲染（渲出半张图），也不该在界面上闪来闪去。</p>
   */
  closed: boolean;
}

/** 语言别名 → 规范名（只收敛真正等价的写法）。 */
const LANG_ALIASES: Record<string, string> = {
  mmd: 'mermaid',
  mermaid: 'mermaid',
  chart: 'chart',
  chartjs: 'chart',
  katex: 'katex',
  math: 'katex',
  tex: 'katex',
  latex: 'katex',
};

/** 规范语言名（`mmd` → `mermaid`）。认不出的原样返回小写。 */
export function normalizeLang(raw: string): string {
  const lang = (raw || '').trim().toLowerCase();
  return LANG_ALIASES[lang] ?? lang;
}

/**
 * 把 Markdown 切成块。
 *
 * <p>纯函数：同样的输入永远得到同样的输出（便于测试与缓存）。</p>
 */
export function parseMarkdown(src: string): Block[] {
  if (!src) return [];
  // 统一换行：Windows 的 \r\n 会让"按行切分"在行尾留一个 \r，进而让表格分隔行等判断失效
  const lines = src.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    // ---- 围栏代码块 ----
    const fence = matchFence(line);
    if (fence) {
      const body: string[] = [];
      let j = i + 1;
      let closed = false;
      while (j < lines.length) {
        const close = matchFence(lines[j]);
        // 闭合围栏：允许语言标注为空，且不要求与开栅栏同语言（宽松即可，严格反而不符直觉）
        if (close && close.marker === fence.marker) {
          closed = true;
          break;
        }
        body.push(lines[j]);
        j++;
      }
      blocks.push({
        kind: 'code',
        lang: normalizeLang(fence.lang),
        code: body.join('\n'),
        closed,
      });
      // 未闭合时 j 会停在行尾（没有闭合行可跳过），直接结束即可
      i = closed ? j + 1 : j;
      continue;
    }

    // ---- 空行 ----
    if (line.trim() === '') {
      i++;
      continue;
    }

    // ---- 分割线（必须在列表之前判断：`---` 也会被列表规则匹配到） ----
    if (/^\s{0,3}([-*_])\s*(\1\s*){2,}$/.test(line)) {
      blocks.push({ kind: 'hr' });
      i++;
      continue;
    }

    // ---- 标题 ----
    const heading = /^\s{0,3}(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      blocks.push({
        kind: 'heading',
        level: heading[1].length,
        children: parseInline(heading[2].trim()),
      });
      i++;
      continue;
    }

    // ---- 引用 ----
    if (/^\s{0,3}>/.test(line)) {
      const quoted: string[] = [];
      while (i < lines.length && /^\s{0,3}>/.test(lines[i])) {
        quoted.push(lines[i].replace(/^\s{0,3}>\s?/, ''));
        i++;
      }
      // 引用内部递归解析（支持多段、列表等）
      blocks.push({ kind: 'quote', children: parseMarkdown(quoted.join('\n')) });
      continue;
    }

    // ---- 表格（表头 + 分隔行） ----
    if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const head = splitRow(line);
      const align = parseAlign(lines[i + 1]);
      // 每个单元格都要过一遍行内解析（否则表格里的 **粗体** 不会生效）
      const rows: Inline[][][] = [];
      i += 2;
      while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
        rows.push(splitRow(lines[i]).map(parseInline));
        i++;
      }
      blocks.push({
        kind: 'table',
        head: head.map(parseInline),
        rows,
        align,
      });
      continue;
    }

    // ---- 列表 ----
    const bullet = /^\s{0,3}([-*+])\s+(.*)$/.exec(line);
    const ordered = /^\s{0,3}(\d{1,9})[.)]\s+(.*)$/.exec(line);
    if (bullet || ordered) {
      const isOrdered = !!ordered;
      const start = ordered ? parseInt(ordered[1], 10) : 1;
      const items: Inline[][] = [];
      while (i < lines.length) {
        const m = isOrdered
          ? /^\s{0,3}(\d{1,9})[.)]\s+(.*)$/.exec(lines[i])
          : /^\s{0,3}([-*+])\s+(.*)$/.exec(lines[i]);
        if (!m) break;
        const parts = [m[2]];
        i++;
        // 续行（缩进的下一行）并入同一项 —— 模型经常把一条写得比较长从而换行
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !isBlockStart(lines[i])) {
          parts.push(lines[i].trim());
          i++;
        }
        items.push(parseInline(parts.join(' ')));
      }
      blocks.push({ kind: 'list', ordered: isOrdered, start, items });
      continue;
    }

    // ---- 段落（连续非空行合并，段内换行保留为硬换行） ----
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() !== '' && !isBlockStart(lines[i])) {
      para.push(lines[i]);
      i++;
    }
    if (para.length === 0) {
      // 兜底：isBlockStart 认为它是块开头、但上面的分支都没接住（不应发生）。
      // 不快进就会死循环 —— 宁可把它当普通文本吃掉。
      para.push(lines[i]);
      i++;
    }
    blocks.push({ kind: 'paragraph', children: parseInline(para.join('\n')) });
  }

  return blocks;
}

// ---------------------------------------------------------------- 行内

/**
 * 解析行内标记。
 *
 * <p>处理顺序有意为之：<b>行内代码优先</b> —— 反引号里的内容必须原样保留，
 * 不能被后续的粗体/链接规则改写（`` `**a**` `` 显示的就是字面的 `**a**`）。</p>
 */
export function parseInline(src: string): Inline[] {
  const out: Inline[] = [];
  if (!src) return out;

  let buf = '';
  let i = 0;
  const flush = () => {
    if (buf) {
      out.push({ kind: 'text', text: buf });
      buf = '';
    }
  };

  while (i < src.length) {
    const rest = src.slice(i);

    // 行内代码：`` `code` ``（支持双反引号包裹含单反引号的内容）
    const code = /^(`+)([\s\S]*?)\1/.exec(rest);
    if (code) {
      flush();
      out.push({ kind: 'code', text: code[2].trim() });
      i += code[0].length;
      continue;
    }

    // 链接 [text](href) —— 只放行 http/https
    const link = /^\[([^\]]*)\]\(([^)\s]*)(?:\s+"[^"]*")?\)/.exec(rest);
    if (link) {
      const href = safeHref(link[2]);
      if (href) {
        flush();
        out.push({ kind: 'link', href, children: parseInline(link[1]) });
        i += link[0].length;
        continue;
      }
      // 协议不被允许：**退回纯文本**，而不是丢掉
      buf += link[0];
      i += link[0].length;
      continue;
    }

    // 粗体 **x** / __x__
    const strong = /^(\*\*|__)(?=\S)([\s\S]*?\S)\1/.exec(rest);
    if (strong) {
      flush();
      out.push({ kind: 'strong', children: parseInline(strong[2]) });
      i += strong[0].length;
      continue;
    }

    // 删除线 ~~x~~
    const del = /^~~(?=\S)([\s\S]*?\S)~~/.exec(rest);
    if (del) {
      flush();
      out.push({ kind: 'del', children: parseInline(del[1]) });
      i += del[0].length;
      continue;
    }

    /*
     * 斜体 *x* / _x_。
     *
     * 两个否定条件都是必需的，且各挡一类真实输入：
     *   `(?!\1)` —— 挡住 `2 ** 3 ** 4` 这种"未配对的粗体标记"：
     *       上面 strong 分支要求 `**` 后紧跟非空白，所以这里不匹配；而斜体若不加这个条件，
     *       就会从 `**` 里取走第一个星号、匹配成 `* 3 *`，**结果是把用户的星号吃掉一个**。
     *   `(?=\S)` —— 挡住 `2 * 3 * 4`（星号两侧是空格，不是斜体）。
     */
    const em = /^(\*|_)(?!\1)(?=\S)([\s\S]*?\S)\1/.exec(rest);
    if (em) {
      flush();
      out.push({ kind: 'em', children: parseInline(em[2]) });
      i += em[0].length;
      continue;
    }

    // 转义 \* 等：原样输出被转义的字符
    if (rest[0] === '\\' && rest.length > 1 && '\\`*_{}[]()#+-.!~|>'.includes(rest[1])) {
      buf += rest[1];
      i += 2;
      continue;
    }

    buf += src[i];
    i++;
  }

  flush();
  return out;
}

/**
 * 链接协议白名单。
 *
 * <p><b>这是安全边界，不是体验优化</b>：模型可以输出任意 URL，
 * 而 `javascript:` 这类协议在点击时会执行脚本。</p>
 *
 * @return 允许的 href；不被允许时返回 null（调用方退回纯文本）
 */
export function safeHref(raw: string): string | null {
  const href = (raw || '').trim();
  if (!href) return null;
  // 相对路径与锚点放行（不产生跨站点跳转风险）
  if (/^(\/|#|\.\/|\.\.\/)/.test(href)) return href;
  return /^https?:\/\//i.test(href) ? href : null;
}

// ---------------------------------------------------------------- 内部辅助

interface Fence {
  marker: string;
  lang: string;
}

/** 匹配围栏行（``` 或 ~~~，允许前后空格）。 */
function matchFence(line: string): Fence | null {
  const m = /^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/.exec(line);
  return m ? { marker: m[1], lang: m[2] } : null;
}

/** 该行是否是"块的开头"（段落遇到它就该收尾）。 */
function isBlockStart(line: string): boolean {
  return (
    matchFence(line) !== null ||
    /^\s{0,3}(#{1,6})\s+/.test(line) ||
    /^\s{0,3}>/.test(line) ||
    /^\s{0,3}([-*+])\s+/.test(line) ||
    /^\s{0,3}(\d{1,9})[.)]\s+/.test(line) ||
    /^\s{0,3}([-*_])\s*(\1\s*){2,}$/.test(line)
  );
}

/** 表格分隔行：`|---|:--:|---|`（至少一个 `-`，可有冒号控制对齐）。 */
function isTableSeparator(line: string): boolean {
  const t = line.trim();
  if (!t.includes('-') || !/^[\s|:-]+$/.test(t)) return false;
  return t.split('|').some((cell) => /^\s*:?-{1,}:?\s*$/.test(cell));
}

/** 切分表格行（去掉首尾的空单元格）。 */
function splitRow(line: string): string[] {
  let t = line.trim();
  if (t.startsWith('|')) t = t.slice(1);
  if (t.endsWith('|')) t = t.slice(0, -1);
  return t.split('|').map((c) => c.trim());
}

/** 解析对齐方式；列数与分隔行一致。 */
function parseAlign(line: string): Align[] {
  let t = line.trim();
  if (t.startsWith('|')) t = t.slice(1);
  if (t.endsWith('|')) t = t.slice(0, -1);
  return t.split('|').map((cell) => {
    const c = cell.trim();
    const left = c.startsWith(':');
    const right = c.endsWith(':');
    if (left && right) return 'center' as Align;
    if (right) return 'right' as Align;
    return 'left' as Align;
  });
}
