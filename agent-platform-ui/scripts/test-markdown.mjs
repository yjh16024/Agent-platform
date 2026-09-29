/**
 * Markdown 解析器与图表规格的测试。
 *
 * <h3>为什么不用 Vitest / Jest</h3>
 * 与 `test-canvas-adapter.mjs` 同样的理由（见该文件顶部：本机 `npm i -D vitest` 曾 prune 掉
 * 一批包，且 `npm ci` 被 safe-delete 拦下），而这里要测的两个模块<b>都是零第三方依赖的纯 TS</b>，
 * 所以「已有的 esbuild + Node 内置 `node:test`」就够了 —— **零新增依赖**。
 *
 * <h3>重点不是"能解析"，而是三类容易出事的地方</h3>
 * <ol>
 *   <li><b>流式中间态</b>：未闭合的围栏必须能被识别出来（否则会渲半张图，每帧闪一次）。</li>
 *   <li><b>安全边界</b>：`javascript:` 链接必须被拒 —— 这是本模块唯一的安全职责，
 *       而输入是模型输出（不可信）。</li>
 *   <li><b>不吞字符</b>：认不出的语法要原样保留成文本。解析器把用户/模型说的话弄丢，
 *       比显示得难看严重得多。</li>
 * </ol>
 */
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import esbuild from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(here, '..', 'src', 'pages', 'chat', 'markdown');

const workDir = mkdtempSync(path.join(tmpdir(), 'md-test-'));
const outs = {};
for (const name of ['parse', 'chart-spec']) {
  const outfile = path.join(workDir, `${name}.mjs`);
  await esbuild.build({
    entryPoints: [path.join(srcDir, `${name}.ts`)],
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: 'node20',
    outfile,
    logLevel: 'silent',
  });
  outs[name] = await import(pathToFileURL(outfile).href);
}

after(() => rmSync(workDir, { recursive: true, force: true }));

const { parseMarkdown, parseInline, normalizeLang } = outs.parse;
const { parseChartSpec, formatValue } = outs['chart-spec'];

/** 把行内节点拍平成纯文本（便于断言"内容没丢"）。 */
const flat = (nodes) =>
  nodes
    .map((n) => (n.text != null ? n.text : n.children ? flat(n.children) : ''))
    .join('');

/** 取第一个块。 */
const first = (src) => parseMarkdown(src)[0];

// ================================================================ 围栏

test('★ 未闭合的围栏要能被识别（流式中间态：此时绝不能渲图）', () => {
  const b = first('```chart\n{"type":"bar","data":[{"label":"a","value":1}]}');
  assert.equal(b.kind, 'code');
  assert.equal(b.closed, false, '没有闭栅栏时必须标为未闭合');
});

test('闭合的围栏 closed=true，且内容不含首尾栅栏行', () => {
  const b = first('```chart\n{"a":1}\n```');
  assert.equal(b.closed, true);
  assert.equal(b.code, '{"a":1}');
});

test('语言标注与别名：mmd → mermaid，Chart 大小写不敏感', () => {
  assert.equal(first('```mmd\nx\n```').lang, 'mermaid');
  assert.equal(first('```Chart\nx\n```').lang, 'chart');
  assert.equal(normalizeLang('  JSON  '), 'json');
  assert.equal(normalizeLang(''), '');
});

test('无语言标注的围栏 lang 为空串（仍按代码块显示）', () => {
  const b = first('```\nplain\n```');
  assert.equal(b.lang, '');
  assert.equal(b.code, 'plain');
});

test('围栏内的 ``` 只有同标记才算闭合（~~~ 开的不能用 ``` 关）', () => {
  const b = first('~~~\ncode\n~~~');
  assert.equal(b.closed, true);
  assert.equal(b.code, 'code');
});

test('未闭合围栏之后的空行不会被当成空块吞掉内容', () => {
  const blocks = parseMarkdown('前置\n\n```js\nlet a = 1');
  assert.equal(blocks.length, 2);
  assert.equal(blocks[1].kind, 'code');
});

// ================================================================ 安全边界

test('★ javascript: 链接必须被拒（模型输出是不可信输入）', () => {
  const nodes = parseInline('[点我](javascript:alert(1))');
  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].kind, 'text', '危险协议要退回纯文本，不能生成 a 标签');
  assert.match(flat(nodes), /javascript:alert\(1\)/, '原文要保留，不能静默吞掉');
});

test('★ data: / vbscript: 同样被拒', () => {
  for (const bad of ['data:text/html,<script>x</script>', 'vbscript:msgbox']) {
    const nodes = parseInline(`[x](${bad})`);
    assert.equal(nodes[0].kind, 'text', bad + ' 不该被当作链接');
  }
});

test('http / https / 相对路径 / 锚点放行', () => {
  assert.equal(parseInline('[a](https://x.com)')[0].kind, 'link');
  assert.equal(parseInline('[a](http://x.com)')[0].kind, 'link');
  assert.equal(parseInline('[a](/docs/a)')[0].kind, 'link');
  assert.equal(parseInline('[a](#top)')[0].kind, 'link');
});

test('HTML 标签当普通文本（不做任何 HTML 解析）', () => {
  const nodes = parseInline('<img src=x onerror=alert(1)>');
  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].kind, 'text');
  assert.equal(nodes[0].text, '<img src=x onerror=alert(1)>');
});

// ================================================================ 行内

test('粗体 / 斜体 / 删除线 / 行内代码', () => {
  assert.equal(parseInline('**b**')[0].kind, 'strong');
  assert.equal(parseInline('*i*')[0].kind, 'em');
  assert.equal(parseInline('~~d~~')[0].kind, 'del');
  assert.equal(parseInline('`c`')[0].kind, 'code');
});

test('★ 行内代码优先：里面的 ** 不被解析', () => {
  const nodes = parseInline('`**not bold**`');
  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].kind, 'code');
  assert.equal(nodes[0].text, '**not bold**');
});

test('★ 不吞字符：未配对 ** 原样输出', () => {
  const nodes = parseInline('2 ** 3 ** 4');
  assert.equal(flat(nodes), '2 ** 3 ** 4');
});

test('★ 不吞字符：乘号不误判成斜体', () => {
  // `2 * 3 * 4` 里星号两侧都是空格 —— 不该被当成斜体
  assert.equal(flat(parseInline('2 * 3 * 4')), '2 * 3 * 4');
});

test('链接文字里可以再有样式', () => {
  const n = parseInline('[**粗**](https://x.com)')[0];
  assert.equal(n.kind, 'link');
  assert.equal(n.children[0].kind, 'strong');
});

test('转义：\\* 输出字面星号', () => {
  assert.equal(flat(parseInline('a \\* b')), 'a * b');
});

// ================================================================ 块级

test('标题层级', () => {
  assert.equal(first('## 二级').level, 2);
  assert.equal(first('###### 六级').level, 6);
  // 7 个 # 不是标题（CommonMark 的边界，这里也要守住，否则会吃掉正文）
  assert.equal(first('####### x').kind, 'paragraph');
});

test('无序列表与有序列表（含起始序号）', () => {
  const ul = first('- a\n- b');
  assert.equal(ul.kind, 'list');
  assert.equal(ul.ordered, false);
  assert.equal(ul.items.length, 2);

  const ol = first('3. a\n4. b');
  assert.equal(ol.ordered, true);
  assert.equal(ol.start, 3, '从 3 开始就要显示 3，不能一律从 1 数');
});

test('列表项续行并入同一项', () => {
  const l = first('- 第一行\n  第二行');
  assert.equal(l.items.length, 1);
  assert.match(flat(l.items[0]), /第一行 第二行/);
});

test('分割线（且不被误判成列表）', () => {
  assert.equal(first('---').kind, 'hr');
  assert.equal(first('***').kind, 'hr');
  // `- x` 是列表而不是分割线
  assert.equal(first('- x').kind, 'list');
});

test('引用内部递归解析（可含列表）', () => {
  const q = first('> 引用\n> - 项1\n> - 项2');
  assert.equal(q.kind, 'quote');
  assert.ok(q.children.length >= 1);
});

test('表格：表头 / 行 / 对齐', () => {
  const t = first('| 名称 | 数量 |\n|:--|--:|\n| 甲 | 3 |\n| 乙 | 5 |');
  assert.equal(t.kind, 'table');
  assert.deepEqual(t.align, ['left', 'right']);
  assert.equal(flat(t.head[0]), '名称');
  assert.equal(t.rows.length, 2);
  assert.equal(flat(t.rows[1][1]), '5');
});

test('★ 表格行缺列时补空、多列时忽略（模型经常写不齐）', () => {
  const t = first('| a | b |\n|---|---|\n| 1 |');
  assert.equal(t.rows.length, 1);
  assert.equal(t.rows[0].length, 1, '少写的单元格不凭空造');
  // 渲染层按表头列数补齐，这里只保证解析不崩
});

test('★ 表格里的行内标记生效（单元格也要过行内解析）', () => {
  const t = first('| a |\n|---|\n| **粗** |');
  assert.equal(t.rows[0][0][0].kind, 'strong');
});

test('不是表格的竖线不被误判（没有分隔行）', () => {
  const b = first('a | b\nc | d');
  assert.equal(b.kind, 'paragraph');
});

// ================================================================ 稳健性

test('空输入 / 空行 / 纯空白都不崩', () => {
  assert.deepEqual(parseMarkdown(''), []);
  assert.deepEqual(parseMarkdown('\n\n\n'), []);
  assert.deepEqual(parseMarkdown('   '), []);
});

test('纯 CRLF 输入与 LF 等价（Windows 剪贴板很常见）', () => {
  assert.deepEqual(parseMarkdown('# 标题\r\n\r\n段落'), parseMarkdown('# 标题\n\n段落'));
});

test('畸形输入不吞内容（大量符号）', () => {
  const weird = '**a\n```\n> > >\n|||\n---\n@@@';
  const blocks = parseMarkdown(weird);
  assert.ok(blocks.length > 0);
  // 所有可见字符都还在某个块的文本里
  const all = JSON.stringify(blocks);
  assert.match(all, /@@@/, '无法识别的行必须原样保留');
});

test('解析是纯函数：同样输入两次结果一致（图表缓存依赖这一点）', () => {
  const src = '# t\n\n| a |\n|---|\n| 1 |\n\n```chart\n{}\n```';
  assert.deepEqual(parseMarkdown(src), parseMarkdown(src));
});

// ================================================================ 图表规格

test('chart：正常解析', () => {
  const r = parseChartSpec('{"type":"pie","title":"占比","data":[{"label":"a","value":3}]}');
  assert.equal(r.ok, true);
  assert.equal(r.spec.type, 'pie');
  assert.equal(r.spec.title, '占比');
  assert.deepEqual(r.spec.data, [{ label: 'a', value: 3 }]);
});

test('chart：宽容 —— 缺 type 按柱状图，值写成字符串也认', () => {
  const r = parseChartSpec('{"data":[{"label":"a","value":"12"}]}');
  assert.equal(r.ok, true);
  assert.equal(r.spec.type, 'bar');
  assert.equal(r.spec.data[0].value, 12);
});

test('chart：未知 type 退化为柱状图（而不是失败）', () => {
  assert.equal(parseChartSpec('{"type":"radar","data":[{"value":1}]}').spec.type, 'bar');
});

test('chart：前后多余的栅栏行会被清掉', () => {
  const r = parseChartSpec('```\n{"data":[{"value":1}]}\n```');
  assert.equal(r.ok, true);
});

test('chart：数据点缺 value 时跳过该项；全缺才失败', () => {
  const partial = parseChartSpec('{"data":[{"label":"a"},{"label":"b","value":2}]}');
  assert.equal(partial.ok, true);
  assert.equal(partial.spec.data.length, 1);

  assert.equal(parseChartSpec('{"data":[{"label":"a"}]}').ok, false);
});

test('chart：失败要给得出原因（界面要显示它，不能静默空白）', () => {
  assert.match(parseChartSpec('').reason, /空/);
  assert.match(parseChartSpec('not json').reason, /JSON/);
  assert.match(parseChartSpec('[1,2]').reason, /对象/);
  assert.match(parseChartSpec('{}').reason, /data/);
});

test('数值展示：整数不带小数点，两位小数不留尾零', () => {
  assert.equal(formatValue(120), '120');
  assert.equal(formatValue(0.5), '0.5');
  assert.equal(formatValue(1.25), '1.25');
  assert.equal(formatValue(1234.567), '1235');
});
