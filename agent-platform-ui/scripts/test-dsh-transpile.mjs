/*
 * 受限 ESM 转译器的单测（2026-10-19，P3）。
 *
 * 为什么这个必须测：它是**正则改写**，错了的表现是"插件装上但界面什么都不出现"
 * 或者更糟——改写把代码悄悄弄坏（少一个括号、少一个变量），插件里某个功能静默失效。
 * 所以每条用例都断言**改写后的具体片段**，而不只是"没抛错"。
 *
 * 跑法：node scripts/test-dsh-transpile.mjs（已串进 npm test）
 */
import { build } from 'esbuild';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const bundle = await build({
  entryPoints: ['src/plugins/host/transpile.ts'],
  bundle: true,
  format: 'esm',
  write: false,
  platform: 'node',
  logLevel: 'silent',
});
const source = Buffer.from(bundle.outputFiles[0].text, 'utf8').toString('base64');
const { transpileDshEsm } = await import(`data:text/javascript;base64,${source}`);

const PID = 'demo';

test('named import → __apHost.require 后解构', () => {
  const { code } = transpileDshEsm(
    `import { React, define as d } from '@deepseek-ai/dsh-client-runtime';\nexport default { apply() {} };`,
    PID,
  );
  assert.match(code, /const \{ React, define: d \} = __apHost\.require\("@deepseek-ai\/dsh-client-runtime"\);/);
});

test('default import → 取 .default 并回落模块本身', () => {
  const { code } = transpileDshEsm(`import runtime from 'm';\nexport default { apply() {} };`, PID);
  assert.match(code, /const runtime = \(__apHost\.require\("m"\)\.default \?\? __apHost\.require\("m"\)\);/);
});

test('namespace import → 直接拿模块', () => {
  const { code } = transpileDshEsm(`import * as api from 'm';\nexport default { apply() {} };`, PID);
  assert.match(code, /const api = __apHost\.require\("m"\);/);
});

test('副作用 import → 仅 require', () => {
  const { code } = transpileDshEsm(`import '@deepseek-ai/dsh-client-connection';\nexport default { apply() {} };`, PID);
  assert.match(code, /__apHost\.require\("@deepseek-ai\/dsh-client-connection"\);/);
});

test('default + named 混用 → 临时变量分别取', () => {
  const { code } = transpileDshEsm(`import React, { useState } from 'react';\nexport default { apply() {} };`, PID);
  assert.match(code, /const __apM0 = __apHost\.require\("react"\); const React = \(__apM0\.default \?\? __apM0\); const \{ useState \} = __apM0;/);
});

test('export default 对象 → 赋值给 __apModule.exports', () => {
  const { code } = transpileDshEsm(`export default { name: 'x', apply(ctx) {} };`, PID);
  assert.match(code, /__apModule\.exports = \{ name: 'x', apply\(ctx\) \{\} \};/);
});

test('export default function → 仍是合法赋值表达式', () => {
  const { code } = transpileDshEsm(`export default function apply(ctx) { return ctx; }`, PID);
  assert.match(code, /__apModule\.exports = function apply\(ctx\) \{ return ctx; \}/);
});

test('包装成 IIFE 并调用 registerDsh', () => {
  const { code } = transpileDshEsm(`export default { apply() {} };`, PID);
  assert.match(code, /^\(function \(\) \{/);
  assert.match(code, /__apHost\.registerDsh\("demo", __apModule\.exports\);/);
  assert.match(code, /\}\)\(\);\s*$/);
});

test('相对导入 → 明确拒绝（打包产物需要 esbuild）', () => {
  assert.throws(
    () => transpileDshEsm(`import { x } from './chunk.js';\nexport default { apply() {} };`, PID),
    /相对导入/,
  );
});

test('export const → 明确拒绝', () => {
  assert.throws(() => transpileDshEsm(`export const a = 1;\nexport default {};`, PID), /export const/);
});

test('缺少 export default → 明确拒绝', () => {
  assert.throws(() => transpileDshEsm(`const a = 1;`, PID), /export default/);
});

test('空文件 / 超长打包产物 → 明确拒绝', () => {
  assert.throws(() => transpileDshEsm('   ', PID), /空文件/);
  assert.throws(() => transpileDshEsm('x'.repeat(3 * 1024 * 1024), PID), /过大/);
});

test('注释里的 import/export 文本不参与改写（行首锚定的回归）', () => {
  // 这是真实踩到的坑：注释写"把 import 改写成 xxx、把 export default 改成 yyy"，
  // 不带 ^ 锚定时正则会把中文注释当成 import 子句，一路吃到下一行真正的 from
  const src = `/*
 * 说明：把 import 改写成 __apHost.require(...)、把 export default 改成赋值
 */
import { React } from 'm';
export default { apply() {} };`;
  const { code } = transpileDshEsm(src, PID);
  assert.match(code, /__apHost\.require\("m"\)/);
  assert.match(code, /把 import 改写成/);              // 注释原样保留（没被改写）
  assert.match(code, /__apModule\.exports = \{ apply\(\) \{\} \};/);
});
