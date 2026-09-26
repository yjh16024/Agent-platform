// 画布 adapter（workflows/canvas/adapter.ts）的往返幂等测试。
//
// 为什么不用 Vitest / Jest：本机 npm 曾因 `npm i -D vitest` prune 掉 vite 等包，
// 落进"半装"状态且 safe-delete 保护使 `npm ci` 无法回滚（详见 docs/roadmap.md 的 C5 记录）。
// 而 adapter.ts 及其唯一依赖 types.ts **都是零第三方依赖的纯 TS**，
// 所以用「已有的 esbuild 打包 + Node 内置 node:test」就够了，**零新增依赖**。
//
// 核心不变量（roadmap.md C5 定的）：
//   保存两次，第二次的后端定义必须与第一次相同 ——
//   toBackend(toCanvas(D1)) 与 toBackend(toCanvas(toBackend(toCanvas(D1)))) 深度相等。
// 若映射丢了字段（如 config / outputVar / branches），第二次就会与第一次不同。
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import esbuild from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const entry = path.resolve(here, '..', 'src', 'pages', 'workflows', 'canvas', 'adapter.ts');

// esbuild 能把 `from './types'` 这种 extensionless import 解析到 types.ts，
// 而 Node 原生的 type-stripping 做不到（所以不能直接 `node adapter.ts`）。
const workDir = mkdtempSync(path.join(tmpdir(), 'canvas-adapter-test-'));
const outfile = path.join(workDir, 'adapter.mjs');

await esbuild.build({
  entryPoints: [entry],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  outfile,
  logLevel: 'silent',
});

const { toBackend, toCanvas, defaultCanvas } = await import(pathToFileURL(outfile).href);

after(() => rmSync(workDir, { recursive: true, force: true }));

/** 深拷贝，避免被测函数意外共享引用导致假阳性。 */
const clone = (v) => JSON.parse(JSON.stringify(v));

/** 走一遍「保存 → 读回 → 再保存」，返回两个后端定义。 */
function roundTrip(doc, name = '测试工作流', entryId = null) {
  const first = toBackend(clone(doc), name, entryId);
  const canvasAgain = toCanvas(clone(first));
  const second = toBackend(clone(canvasAgain), name, entryId);
  return { first, second, canvasAgain };
}

test('defaultCanvas 往返幂等：保存两次结果相同', () => {
  const { first, second } = roundTrip(defaultCanvas());
  assert.deepEqual(second, first, '第二次保存的后端定义必须与第一次一致（否则说明映射丢字段）');
});

test('defaultCanvas 的契约字段正确：entry=start_0、顺序链、末尾 next=null', () => {
  const def = toBackend(defaultCanvas(), 'demo');
  assert.equal(def.name, 'demo');
  assert.equal(def.entryNode, 'start_0');
  assert.deepEqual(
    def.nodes.map((n) => n.id),
    ['start_0', 'llm_0', 'end_0']
  );
  assert.equal(def.nodes[0].next, 'llm_0');
  assert.equal(def.nodes[1].next, 'end_0');
  assert.equal(def.nodes[2].next, null, '最后一个节点 next 必须为 null');
});

test('类型映射大小写正确：画布 llm ↔ 后端 LLM', () => {
  const def = toBackend(defaultCanvas(), 'demo');
  assert.deepEqual(
    def.nodes.map((n) => n.type),
    ['Start', 'LLM', 'End']
  );
  // 反向：后端 LLM → 画布 llm
  const canvas = toCanvas(def);
  assert.deepEqual(
    canvas.nodes.map((n) => n.type),
    ['start', 'llm', 'end']
  );
});

test('config 与 outputVar 完整保留（无关往返次数）', () => {
  const def = toBackend(defaultCanvas(), 'demo');
  const llm = def.nodes.find((n) => n.id === 'llm_0');
  assert.equal(llm.outputVar, 'llm_output');
  assert.deepEqual(llm.config, { provider: 'auto', model: 'deepseek-chat', prompt: '${input}' });

  const start = def.nodes.find((n) => n.id === 'start_0');
  assert.deepEqual(start.config, { input_key: 'input' });
});

test('condition 节点：branches 还原为 blocks，且 block 容器不被当成可执行节点', () => {
  const doc = {
    nodes: [
      { id: 'start_0', type: 'start', data: { title: '开始', config: {} } },
      {
        id: 'cond_0',
        type: 'condition',
        data: { title: '判断' },
        blocks: [
          { id: 'cond_0_b0', type: 'block', data: { condition: 'true' }, blocks: [{ id: 'llm_0', type: 'llm', data: { title: 'A' } }] },
          { id: 'cond_0_b1', type: 'block', data: { condition: 'false' }, blocks: [{ id: 'end_0', type: 'end', data: { title: 'B' } }] },
        ],
      },
    ],
  };
  const def = toBackend(clone(doc), 'cond');

  // `block` 是 FlowGram 的分支容器，后端 NodeType 里没有它 —— 必须被跳过
  assert.ok(!def.nodes.some((n) => n.id === 'cond_0_b0'), 'block 容器不得出现在后端 nodes 里');

  const cond = def.nodes.find((n) => n.id === 'cond_0');
  assert.equal(cond.type, 'Condition');
  assert.equal(cond.next, null, 'Condition 用 branches 跳转，不用 next');
  assert.deepEqual(cond.branches, [
    { condition: 'true', target: 'llm_0' },
    { condition: 'false', target: 'end_0' },
  ]);

  // 分支目标节点必须被扁平化进 nodes（否则后端按 id 找不到目标）
  assert.ok(def.nodes.some((n) => n.id === 'llm_0'));
  assert.ok(def.nodes.some((n) => n.id === 'end_0'));
});

test('condition 往返幂等（分支图也要能存两次而不变）', () => {
  const doc = {
    nodes: [
      { id: 'start_0', type: 'start', data: { title: '开始', config: {} } },
      {
        id: 'cond_0',
        type: 'condition',
        data: { title: '判断' },
        blocks: [
          { id: 'cond_0_b0', type: 'block', data: { condition: 'x > 1' }, blocks: [{ id: 'llm_0', type: 'llm', data: { title: 'A', outputVar: 'a' } }] },
          { id: 'cond_0_b1', type: 'block', data: { condition: 'else' }, blocks: [{ id: 'end_0', type: 'end', data: { title: 'B' } }] },
        ],
      },
    ],
  };
  const { first, second } = roundTrip(doc, 'cond');
  assert.deepEqual(second, first, '分支图往返两次后必须稳定');
});

test('空定义 / null 安全（不抛异常）', () => {
  assert.deepEqual(toCanvas(null), { nodes: [] });
  assert.deepEqual(toCanvas(undefined), { nodes: [] });
  assert.deepEqual(toCanvas({ nodes: [] }), { nodes: [] });
});

test('name 与 title 分离：画布 title → 后端 name → 画布 title', () => {
  const doc = {
    nodes: [{ id: 'start_0', type: 'start', data: { title: '我的开始节点', config: {} } }],
  };
  const def = toBackend(clone(doc), 'wf');
  assert.equal(def.nodes[0].name, '我的开始节点');
  const back = toCanvas(def);
  assert.equal(back.nodes[0].data.title, '我的开始节点');
});

test('entryNode 缺省时取第一个顶层节点', () => {
  const def = toBackend(defaultCanvas(), 'demo', null);
  assert.equal(def.entryNode, 'start_0');

  // 显式传入时以传入值为准
  const def2 = toBackend(defaultCanvas(), 'demo', 'llm_0');
  assert.equal(def2.entryNode, 'llm_0');
});

// ---------------- loop / parallel（2026-09-26 后端补了执行器，前端同步暴露） ----------------

/** 循环：单个「循环体」块。 */
const loopDoc = () => ({
  nodes: [
    { id: 'start_0', type: 'start', data: { title: '开始', config: {} } },
    {
      id: 'loop_0',
      type: 'loop',
      data: { title: '循环', config: { max_iterations: 3, timeout_seconds: 60, index_var: 'i' } },
      blocks: [
        {
          id: 'loop_0_body_x',
          type: 'block',
          data: { role: 'body' },
          blocks: [{ id: 'body_1', type: 'transform', data: { title: '体', config: {} } }],
        },
      ],
    },
    { id: 'end_0', type: 'end', data: { title: '结束', config: {} } },
  ],
});

/** 并行：两个并发分支块。 */
const parallelDoc = () => ({
  nodes: [
    { id: 'start_0', type: 'start', data: { title: '开始', config: {} } },
    {
      id: 'par_0',
      type: 'parallel',
      data: { title: '并行', outputVar: 'par_info', config: {} },
      blocks: [
        {
          id: 'par_0_par_a',
          type: 'block',
          data: {},
          blocks: [{ id: 'a', type: 'transform', data: { title: 'A' } }],
        },
        {
          id: 'par_0_par_b',
          type: 'block',
          data: {},
          blocks: [{ id: 'b', type: 'transform', data: { title: 'B' } }],
        },
      ],
    },
    { id: 'end_0', type: 'end', data: { title: '结束', config: {} } },
  ],
});

test('循环：type 映射为 Loop，循环体写进 config.loop_body，且**仍接在顶层 next 链上**', () => {
  const def = toBackend(clone(loopDoc()), 'loop');

  const loop = def.nodes.find((n) => n.id === 'loop_0');
  assert.equal(loop.type, 'Loop', '大小写必须与后端 NodeType 一致');
  assert.equal(loop.config.loop_body, 'body_1', '循环体入口来自分支块内的首个可执行节点');

  // ★ 关键：Loop 是普通节点，必须参与顶层 next 链（next = 循环结束后去哪）。
  //   若它被当成容器（早期 bug），这里会是 null，循环结束后流程就断了。
  assert.equal(loop.next, 'end_0', 'Loop 必须保留 next —— 它不能像 Condition 那样用 next=null');

  // 循环体节点仍要被扁平化进 nodes（否则后端按 id 找不到它）
  assert.ok(def.nodes.some((n) => n.id === 'body_1'), '循环体节点必须在 nodes 里');
  // 循环体块容器本身不是可执行节点
  assert.ok(!def.nodes.some((n) => n.id === 'loop_0_body_x'), 'block 容器不得出现在后端 nodes 里');
});

test('循环：往返幂等（含分支结构）', () => {
  const { first, second, canvasAgain } = roundTrip(loopDoc(), 'loop');
  assert.deepEqual(second, first, '循环图存两次必须稳定');

  // 还原出来的画布应仍带一个循环体块
  const loop = canvasAgain.nodes.find((n) => n.id === 'loop_0');
  assert.equal(loop.type, 'loop');
  assert.equal(loop.blocks.length, 1, '循环体应还原为一个块');
  assert.equal(loop.blocks[0].blocks[0].id, 'body_1', '块内应放回循环体节点');
});

test('并行：type 映射为 Parallel，分支块 → next 数组', () => {
  const def = toBackend(clone(parallelDoc()), 'par');

  const par = def.nodes.find((n) => n.id === 'par_0');
  assert.equal(par.type, 'Parallel');
  assert.deepEqual(par.next, ['a', 'b'], '每个分支块的首个可执行节点构成 next 数组（后端据此并发）');
  assert.equal(par.branches, null, '并行不用 branches');
  assert.ok(def.nodes.some((n) => n.id === 'a'));
  assert.ok(def.nodes.some((n) => n.id === 'b'));
});

test('并行：往返幂等，且两个分支都还原成块、不混进顶层顺序', () => {
  const { first, second, canvasAgain } = roundTrip(parallelDoc(), 'par');
  assert.deepEqual(second, first, '并行图存两次必须稳定');

  // 顶层顺序里应只有 start / par / end —— 两个分支由 par 的块承载
  const topTypes = canvasAgain.nodes.map((n) => n.id);
  assert.deepEqual(topTypes, ['start_0', 'par_0', 'end_0'], '分支不得混进顶层顺序');

  const par = canvasAgain.nodes.find((n) => n.id === 'par_0');
  assert.equal(par.blocks.length, 2, '应还原出两个分支块');
});

test('循环 + 并行组合图也能稳定往返', () => {
  const doc = {
    nodes: [
      { id: 'start_0', type: 'start', data: { title: '开始', config: {} } },
      {
        id: 'loop_0',
        type: 'loop',
        data: { title: '循环', config: { max_iterations: 2 } },
        blocks: [
          { id: 'loop_0_body', type: 'block', data: { role: 'body' }, blocks: [
            {
              id: 'par_0',
              type: 'parallel',
              data: { title: '并行' },
              blocks: [
                { id: 'par_0_par_a', type: 'block', data: {}, blocks: [
                  { id: 'a', type: 'transform', data: { title: 'A' } }] },
                { id: 'par_0_par_b', type: 'block', data: {}, blocks: [
                  { id: 'b', type: 'transform', data: { title: 'B' } }] },
              ],
            },
          ] },
        ],
      },
      { id: 'end_0', type: 'end', data: { title: '结束', config: {} } },
    ],
  };

  const { first, second } = roundTrip(doc, 'combo');
  assert.deepEqual(second, first, '循环里套并行也要能存两次而不变');

  const loop = first.nodes.find((n) => n.id === 'loop_0');
  assert.equal(loop.config.loop_body, 'par_0', '循环体是并行节点');
  assert.equal(loop.next, 'end_0');
  const par = first.nodes.find((n) => n.id === 'par_0');
  assert.deepEqual(par.next, ['a', 'b']);
});
