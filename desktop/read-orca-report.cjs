/**
 * 读取 orca 命中率报告里的「关键诊断字段」。
 *
 * 拆分出来的原因：这段逻辑塞进 `node -e` 会超过终端单条命令长度上限。
 *
 * 注意报告路径是 `%TEMP%\skin-hitrate-orca.json`（由 run-hitrate-orca.ps1 通过 AP_OUT 指定），
 * **不是** skin-hitrate-report.json —— 后者是旧版台架留下的残留，曾经因此读错文件、误判过结论。
 */
const fs = require('fs');
const path = require('path');

const file = process.argv[2] || path.join(process.env.TEMP, 'skin-hitrate-orca.json');
const route = process.argv[3] || '/#/chat';
const r = JSON.parse(fs.readFileSync(file, 'utf8'));
const c = (r.routes || {})[route];

if (!c) {
  console.log('!! 报告里没有路由 ' + route + '；有：' + Object.keys(r.routes || {}).join(', '));
  if (r.fatal) console.log('FATAL: ' + r.fatal);
  process.exit(0);
}

console.log('=== skin JS（走真实 runtime 入口）===');
console.log('entry   = ' + c.skinJs.entry);
console.log('loaded  = ' + c.skinJs.loaded + '   applied = ' + c.skinJs.applied);
console.log('state   = ' + c.skinJs.stateRaw);
console.log('errors  = ' + JSON.stringify(c.skinJs.errors));
console.log('--- skin runtime log ---');
(c.skinJs.runtimeLog || []).forEach((l) => console.log('  ' + l));
if ((c.skinJs.runtimeLogErrors || []).length) {
  console.log('--- runtime log 里的可疑行 ---');
  c.skinJs.runtimeLogErrors.forEach((l) => console.log('  ' + l));
}

const m = c.markers || {};
console.log('=== 输入卡片外观（宿主是否让位给皮肤）===');
console.log('  加载皮肤前 = ' + c.skinJs.composerBeforeSkin);
console.log('  皮肤活跃时 = ' + c.skinJs.composerWhileActive);
console.log('  卡片快照   = ' + c.skinJs.composerCardDump);
console.log('  容器底色   = ' + c.skinJs.containerBgWhileActive);
console.log('  skinActive = ' + c.skinJs.skinActiveAttr);

console.log('=== 皮肤设置面板（点开后实测）===');
console.log('  ' + c.skinJs.settingsPanel);

console.log('=== 卸载验证（disposer 是否真的执行）===');
console.log('attrs  load = ' + JSON.stringify(c.skinJs.attrsAfterLoad));
console.log('attrs  unload= ' + JSON.stringify(c.skinJs.attrsAfterUnload) + '   清干净=' + c.skinJs.unloadCleanedAttrs);
console.log('nodes  load = ' + c.skinJs.skinNodesAfterLoad + '   unload = ' + c.skinJs.skinNodesAfterUnload + '   清干净=' + c.skinJs.unloadCleanedNodes);
console.log('state  unload= ' + c.skinJs.stateAfterUnload);

console.log('=== customization protocol ===');
console.log('installed = ' + m.__customInstalled + '   regCount = ' + m.__customRegistered);
console.log('skins     = ' + JSON.stringify(m.__customSkins));
console.log('settings  = ' + m.__customSettings);
console.log('htmlAttrs = ' + JSON.stringify(m.__htmlAttrs));

// 全量 dump：不再逐个列字段，免得每次加探针都要改这个脚本。
// 只排除超长的 __runtimeLog（上面已单独打印）。
console.log('=== markers (all) ===');
Object.keys(m)
  .filter((k) => k !== '__runtimeLog')
  .forEach((k) => console.log('  ' + k.padEnd(18) + String(m[k])));

console.log('=== runtime log (last lines) ===');
String(m.__runtimeLog || '(none)')
  .split(' ;; ')
  .forEach((l) => console.log('  ' + l));
