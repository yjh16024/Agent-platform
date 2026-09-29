/**
 * 插件配置表单纯逻辑的测试。
 *
 * <h3>为什么这些用例值得写</h3>
 * 它们各自对应一类<b>用户会真实遇到、且难以自行定位</b>的问题：
 * <ul>
 *   <li><b>声明结构意外就崩</b>：manifest 是自由 JSON，一个插件写错不该让整个弹窗打不开。</li>
 *   <li><b>类型没转换</b>：把字符串 "false" 传给插件，`asBoolean` 的判断会与预期相反。</li>
 *   <li><b>配置丢失</b>：回显与提交之间若丢字段，表现为"我上次填的没了"。</li>
 *   <li><b>掩码被当成密钥</b>：用户不动密钥框、直接保存，若把 `sk-***abcd` 当新密钥提交，
 *       之后每次调用都 401 —— 而界面上仍显示"已配置"。</li>
 * </ul>
 *
 * 与 `test-canvas-adapter.mjs` / `test-markdown.mjs` 同一套路：`esbuild + node:test`，零新增依赖。
 */
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import esbuild from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const entry = path.resolve(here, '..', 'src', 'pages', 'plugins', 'plugin-config.ts');

const workDir = mkdtempSync(path.join(tmpdir(), 'plugin-cfg-test-'));
const outfile = path.join(workDir, 'plugin-config.mjs');
await esbuild.build({
  entryPoints: [entry],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  outfile,
  logLevel: 'silent',
});
const { configFieldsOf, coerce, buildInitialConfig, missingRequired, prunedConfig } =
  await import(pathToFileURL(outfile).href);

after(() => rmSync(workDir, { recursive: true, force: true }));

/** 造一份合法的 manifest。 */
const manifestWith = (fields) => ({ contributes: { config: fields } });

// ================================================================ 声明解析

test('★ 结构意外不崩：各种畸形 manifest 都返回空数组而不是抛异常', () => {
  for (const bad of [
    undefined, null, {}, [], 'str', 42,
    { contributes: null }, { contributes: {} }, { contributes: { config: 'x' } },
    { contributes: { config: [null, 1, 'x', {}] } },
  ]) {
    assert.deepEqual(configFieldsOf(bad), [], `畸形输入应安全返回空：${JSON.stringify(bad)}`);
  }
});

test('★ 没有键名的声明被跳过（否则会写进一个无名配置）', () => {
  const f = configFieldsOf(manifestWith([{ label: '没有 key' }, { key: 'ok', label: 'L' }]));
  assert.equal(f.length, 1);
  assert.equal(f[0].key, 'ok');
});

test('★ 未知 kind 退化为 text（后端加新类型时前端旧版仍能填，而不是字段消失）', () => {
  const f = configFieldsOf(manifestWith([{ key: 'a', kind: 'color-picker' }]));
  assert.equal(f[0].kind, 'text');
});

test('缺 label 时用 key 兜底（界面上不能出现空白标签）', () => {
  assert.equal(configFieldsOf(manifestWith([{ key: 'apiKey' }]))[0].label, 'apiKey');
});

test('完整字段的解析：密码框 / 数值范围 / 选项 / 说明都带出来', () => {
  const f = configFieldsOf(manifestWith([{
    key: 'apiKey', label: 'API Key', kind: 'text', secret: true, required: true,
    placeholder: 'sk-...', hint: '在控制台获取',
  }, {
    key: 'speed', label: '语速', kind: 'number', min: 0.25, max: 4, step: 0.25, defaultValue: '1.0',
  }, {
    key: 'channel', label: '渠道', kind: 'select',
    options: [{ label: '飞书', value: 'feishu' }],
  }]))[0];

  assert.equal(f.secret, true);
  assert.equal(f.required, true);
  assert.equal(f.hint, '在控制台获取');
});

test('select 的 options 逐项校验：坏项被过滤，label 缺失时退回 value', () => {
  const f = configFieldsOf(manifestWith([{
    key: 'c', kind: 'select',
    options: [null, 'x', { value: 'a' }, { label: 'B', value: 'b' }],
  }]))[0];
  assert.deepEqual(f.options, [{ label: 'a', value: 'a' }, { label: 'B', value: 'b' }]);
});

// ================================================================ 类型转换

test('★ bool 必须真的转成布尔：字符串 "false" 会让插件的 asBoolean 判断反掉', () => {
  const field = { key: 'b', label: 'B', kind: 'bool' };
  assert.equal(coerce(field, true), true);
  assert.equal(coerce(field, 'true'), true);
  assert.equal(coerce(field, false), false);
  assert.equal(coerce(field, 'false'), false, '"false" 不能被当成真值');
  assert.equal(coerce(field, ''), false);
});

test('★ number 转换并按声明夹取范围', () => {
  const field = { key: 'n', label: 'N', kind: 'number', min: 1, max: 10 };
  assert.equal(coerce(field, '5'), 5);
  assert.equal(coerce(field, 999), 10, '超过上限要夹住');
  assert.equal(coerce(field, -5), 1, '低于下限要夹住');
  assert.equal(coerce(field, ''), undefined, '空值不产生数字');
  assert.equal(coerce(field, 'abc'), undefined, '非数字不产生 NaN');
});

test('text 保持字符串，null 转空串（避免受控输入变成非受控）', () => {
  const field = { key: 't', label: 'T', kind: 'text' };
  assert.equal(coerce(field, null), '');
  assert.equal(coerce(field, undefined), '');
  assert.equal(coerce(field, 123), '123');
});

// ================================================================ 初值组装

test('★ 初值 = 声明默认值 + 已保存值覆盖（两者缺一不可）', () => {
  const fields = [
    { key: 'baseUrl', label: '地址', kind: 'text', defaultValue: 'https://a.com' },
    { key: 'maxChars', label: '上限', kind: 'number', defaultValue: '150', min: 1, max: 2000 },
  ];

  // 只有默认值：第一次挂载就能看到"留空会怎样"
  const fresh = buildInitialConfig(fields, null);
  assert.equal(fresh.baseUrl, 'https://a.com');
  assert.equal(fresh.maxChars, 150);

  // 已保存值覆盖默认值：用户改过的不能被默认值顶掉
  const saved = buildInitialConfig(fields, { maxChars: 300 });
  assert.equal(saved.maxChars, 300);
  assert.equal(saved.baseUrl, 'https://a.com', '未保存的字段仍用默认值');
});

test('★ 未声明的键不进初值（由后端合并保留，前端不碰）', () => {
  const out = buildInitialConfig([{ key: 'a', label: 'A', kind: 'text' }], { a: '1', legacy: 'keep-me' });
  assert.equal(out.a, '1');
  assert.equal('legacy' in out, false);
});

test('密钥掩码原样带进初值（界面要显示"已配置"）', () => {
  const out = buildInitialConfig(
    [{ key: 'apiKey', label: 'Key', kind: 'text', secret: true }],
    { apiKey: 'sk-***abcd' },
  );
  assert.equal(out.apiKey, 'sk-***abcd');
});

// ================================================================ 校验

test('★ 必填校验：空串与空白算未填，返回的是标签（好直接拼进提示语）', () => {
  const fields = [
    { key: 'k1', label: 'API Key', kind: 'text', required: true },
    { key: 'k2', label: '地址', kind: 'text', required: true },
    { key: 'k3', label: '可选', kind: 'text' },
  ];
  assert.deepEqual(missingRequired(fields, { k1: 'x', k2: 'y', k3: '' }), []);
  assert.deepEqual(missingRequired(fields, { k1: 'x', k2: '   ' }), ['地址']);
  assert.deepEqual(missingRequired(fields, {}), ['API Key', '地址']);
});

test('★ 掩码不算未填：用户不动密钥框也应该能保存（否则改不了别的字段）', () => {
  const fields = [{ key: 'apiKey', label: 'API Key', kind: 'text', secret: true, required: true }];
  assert.deepEqual(missingRequired(fields, { apiKey: 'sk-***abcd' }), []);
});

test('bool 的 false 是有效值，不该被判成未填', () => {
  const fields = [{ key: 'sw', label: '开关', kind: 'bool', required: true }];
  assert.deepEqual(missingRequired(fields, { sw: false }), []);
});

// ================================================================ 提交前精简

test('★ 空值不提交：保持"我没动这一栏"的语义', () => {
  const out = prunedConfig({ a: 'x', b: '', c: undefined, d: null, e: 0, f: false });
  assert.deepEqual(out, { a: 'x', e: 0, f: false },
    '0 与 false 都是有效值，只剔除空串与 null/undefined');
});

test('往返幂等：初值 → 精简 → 再精简，结果不变', () => {
  const fields = [{ key: 'a', label: 'A', kind: 'text', defaultValue: 'v' }];
  const once = prunedConfig(buildInitialConfig(fields, null));
  assert.deepEqual(prunedConfig(once), once);
});
