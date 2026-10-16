/**
 * 画布字段的纯函数测试：工具参数骨架、多值字段编解码。
 *
 * <h3>为什么这些值得测</h3>
 * 它们各自对应一类**用户不会怀疑到工具头上**的问题：
 * <ul>
 *   <li><b>参数骨架生成错了</b>：用户照着骨架填，运行时报参数错误 ——
 *       他会以为是自己写错了，而不是骨架给错了形状；</li>
 *   <li><b>多值分隔符漏了一种</b>：用户粘了一份换行分隔的 ID 列表，
 *       被当成一个超长 ID；保存能过，运行时说"找不到知识库"。</li>
 * </ul>
 *
 * 与 `test-plugin-config.mjs` / `test-markdown.mjs` 同一套路：`esbuild + node:test`，零新增依赖。
 */
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import esbuild from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const base = path.resolve(here, '..', 'src', 'pages', 'workflows', 'canvas');

const workDir = mkdtempSync(path.join(tmpdir(), 'wf-field-test-'));
await esbuild.build({
  entryPoints: [path.join(base, 'tool-args.ts'), path.join(base, 'field-value.ts')],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  outdir: workDir,
  outExtension: { '.js': '.mjs' },
  logLevel: 'silent',
});

const { schemaToArgsTemplate, placeholderOf, requiredOf } = await import(
  pathToFileURL(path.join(workDir, 'tool-args.mjs')).href
);
const { splitMulti, joinMulti } = await import(
  pathToFileURL(path.join(workDir, 'field-value.mjs')).href
);

after(() => rmSync(workDir, { recursive: true, force: true }));

// ================================================================ 参数骨架

test('★ 按类型给占位：每个键都出现，形状与该参数的类型一致', () => {
  const tpl = schemaToArgsTemplate({
    type: 'object',
    properties: {
      expression: { type: 'string' },
      count: { type: 'integer' },
      ratio: { type: 'number' },
      verbose: { type: 'boolean' },
      tags: { type: 'array' },
      options: { type: 'object' },
    },
  });
  assert.deepEqual(JSON.parse(tpl), {
    expression: '',
    count: 0,
    ratio: 0,
    verbose: false,
    tags: [],
    options: {},
  });
});

test('★ 产出是合法 JSON 文本（用户要在这个基础上改，格式坏了就没法用）', () => {
  const tpl = schemaToArgsTemplate({
    type: 'object',
    properties: { q: { type: 'string' }, k: { type: 'number' } },
  });
  assert.doesNotThrow(() => JSON.parse(tpl));
  // 缩进过的（便于人工编辑），不是压成一行
  assert.ok(tpl.includes('\n'), '应当是格式化过的 JSON，便于用户改');
});

test('★ enum 取第一个值，比空串更有提示性', () => {
  const tpl = schemaToArgsTemplate({
    type: 'object',
    properties: {
      method: { type: 'string', enum: ['GET', 'POST'] },
      empty: { type: 'string', enum: [] },
    },
  });
  const parsed = JSON.parse(tpl);
  assert.equal(parsed.method, 'GET');
  // 空 enum 不能变成 undefined（那会被 JSON.stringify 丢掉，用户看不到这个键）
  assert.equal(parsed.empty, '');
});

test('★ 畸形 schema 一律退回 {}，绝不抛异常', () => {
  for (const bad of [undefined, null, {}, [], 'text', 42, { properties: null }, { properties: [] }]) {
    assert.equal(schemaToArgsTemplate(bad), '{}', `畸形输入应返回 {}：${JSON.stringify(bad)}`);
  }
});

test('没有 properties 时返回 {}（而不是 null 或抛错）', () => {
  assert.equal(schemaToArgsTemplate({ type: 'object' }), '{}');
  assert.equal(schemaToArgsTemplate({ type: 'object', properties: {} }), '{}');
});

test('required 能取出来（将来做提示文案用）', () => {
  assert.deepEqual(requiredOf({ required: ['a', 'b'] }), ['a', 'b']);
  assert.deepEqual(requiredOf({}), []);
  assert.deepEqual(requiredOf(null), []);
});

test('placeholderOf 对未知类型给空串（用户要填内容，给别的反而要先删）', () => {
  assert.equal(placeholderOf({ type: 'custom-x' }), '');
  assert.equal(placeholderOf({}), '');
  assert.equal(placeholderOf(null), '');
});

// ================================================================ 多值编解码

test('★ 三种分隔符都要认：半角逗号 / 全角逗号 / 换行', () => {
  // 只认半角的话，下面后两种会被当成一个超长 ID —— 保存能过、运行时才报找不到
  assert.deepEqual(splitMulti('kb_1,kb_2'), ['kb_1', 'kb_2']);
  assert.deepEqual(splitMulti('kb_1，kb_2'), ['kb_1', 'kb_2'], '中文输入法打出的是全角逗号');
  assert.deepEqual(splitMulti('kb_1\nkb_2\nkb_3'), ['kb_1', 'kb_2', 'kb_3'], '从表格复制通常是换行');
  assert.deepEqual(splitMulti('kb_1\r\nkb_2'), ['kb_1', 'kb_2'], 'Windows 换行');
});

test('拆分时去掉空白与空项（用户粘的内容往往带缩进和空行）', () => {
  assert.deepEqual(splitMulti('  kb_1 ,  kb_2  '), ['kb_1', 'kb_2']);
  assert.deepEqual(splitMulti('kb_1,,kb_2'), ['kb_1', 'kb_2']);
  assert.deepEqual(splitMulti('kb_1,\n\n,kb_2'), ['kb_1', 'kb_2']);
  assert.deepEqual(splitMulti(''), []);
  assert.deepEqual(splitMulti('   '), []);
});

test('非字符串输入返回空数组（不能崩，也不能返回 [null]）', () => {
  for (const bad of [undefined, null, 42, [], {}]) {
    assert.deepEqual(splitMulti(bad), [], `输入 ${JSON.stringify(bad)} 应得到 []`);
  }
});

test('★ 往返幂等：拆分再合并，再拆分结果不变', () => {
  const once = joinMulti(['kb_1', 'kb_2']);
  assert.equal(once, 'kb_1,kb_2');
  assert.deepEqual(splitMulti(once), ['kb_1', 'kb_2']);
  assert.deepEqual(splitMulti(joinMulti(splitMulti('kb_1，kb_2\nkb_3'))), ['kb_1', 'kb_2', 'kb_3']);
});

test('joinMulti 过滤空项并 trim（用户可能在标签里留空格）', () => {
  assert.equal(joinMulti([' a ', '', 'b']), 'a,b');
  assert.equal(joinMulti([]), '');
});
