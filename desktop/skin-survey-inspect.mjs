/**
 * 测绘辅助：打印完整报告，或 dump 某个仓库的文件树（用来查"取件盲区"）。
 *
 *   node skin-survey-inspect.mjs report
 *   node skin-survey-inspect.mjs tree <owner/repo> [ref] [sub]
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const PROXIES = ['gh-proxy.com', 'ghfast.top', 'ghproxy.net'];

async function get(url, asJson = false) {
  for (const p of PROXIES) {
    try {
      const r = await fetch(`https://${p}/${url}`, { signal: AbortSignal.timeout(25000) });
      if (!r.ok) continue;
      const t = await r.text();
      return asJson ? JSON.parse(t) : t;
    } catch {}
  }
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
    if (!r.ok) return null;
    const t = await r.text();
    return asJson ? JSON.parse(t) : t;
  } catch {
    return null;
  }
}

const mode = process.argv[2] || 'report';

if (mode === 'bundle') {
  // 分析本地 bundle：CSS 在哪里、怎么注入、以及自建节点的类名形态。
  // 注意：这类 1MB 级 bundle 会让 ripgrep 当二进制跳过，所以必须用 Node 读。
  //   node skin-survey-inspect.mjs bundle <localFile> [keyword]
  const file = process.argv[3];
  const kw = process.argv[4] || 'lightScene';
  const text = fs.readFileSync(file, 'utf8');
  console.log(`=== bundle analysis: ${file} ===`);
  console.log(`  size = ${text.length} chars`);
  console.log(`  lines = ${text.split('\n').length}`);
  console.log('');

  // 1) CSS 块（选择器 + 声明块，且声明里有 : 和 ;）
  const cssBlocks = [];
  const re = /([^{}]{1,240})\{([^{}]{5,4000})\}/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const body = m[2];
    if (!body.includes(':') || !body.includes(';')) continue;
    if (/^\s*(function|if|else|for|while|switch|try|catch|return|const|let|var|=>)/.test(m[1])) continue;
    cssBlocks.push({ sel: m[1].trim(), body });
  }
  const cssChars = cssBlocks.reduce((a, b) => a + b.sel.length + b.body.length, 0);
  console.log('--- CSS blocks ---');
  console.log(`  blocks = ${cssBlocks.length}   approx css chars = ${cssChars} (${Math.round((cssChars / text.length) * 100)}% of bundle)`);
  console.log('  first 12 selectors:');
  cssBlocks.slice(0, 12).forEach((b) => console.log(`    ${b.sel.replace(/\s+/g, ' ').slice(0, 120)}`));

  // 2) 注入机制
  console.log('');
  console.log('--- injection mechanism ---');
  const mech = {
    "createElement('style')": /createElement\(\s*['"]style['"]\s*\)/.test(text),
    'adoptedStyleSheets': /adoptedStyleSheets/.test(text),
    'insertRule': /insertRule/.test(text),
    'textContent =': /\.textContent\s*=/.test(text),
    'innerHTML =': /\.innerHTML\s*=/.test(text),
    'appendChild': /appendChild\(/.test(text),
    'new CSSStyleSheet': /new\s+CSSStyleSheet/.test(text),
    'link rel=stylesheet': /rel\s*=\s*['"]stylesheet['"]/.test(text),
    'fetch(': /fetch\(/.test(text),
    'style.setProperty': /\.setProperty\(/.test(text),
  };
  for (const [k, v] of Object.entries(mech)) console.log(`    ${v ? 'YES' : 'no '}  ${k}`);

  // 3) 关键词上下文（看它是 CSS 文本里的类名，还是 JS 里创建的类名）
  console.log('');
  console.log(`--- context around "${kw}" (first 4) ---`);
  let idx = -1;
  let n = 0;
  while ((idx = text.indexOf(kw, idx + 1)) !== -1 && n < 4) {
    const s = Math.max(0, idx - 220);
    console.log(`  @${idx}`);
    console.log(`    ...${text.slice(s, idx + 260).replace(/\s+/g, ' ')}...`);
    n++;
  }
  if (n === 0) console.log('  (keyword not found)');

  // 4) 类名形态：CSS 里的类名 vs JS 里 classList/createElement 的类名
  console.log('');
  console.log('--- class-name shapes ---');
  const cssClasses = new Set();
  cssBlocks.forEach((b) => {
    for (const c of b.sel.matchAll(/\.([A-Za-z_][\w-]*)/g)) cssClasses.add(c[1]);
  });
  console.log(`  classes appearing in CSS blocks = ${cssClasses.size}`);
  console.log(`  samples: ${[...cssClasses].slice(0, 20).join(', ')}`);
  const hashLike = [...cssClasses].filter((c) => /^_?[A-Za-z]?[\w-]{0,20}_?[a-z0-9]{4,}$/.test(c) && /[_]/.test(c));
  console.log(`  hash-like (含下划线，CSS Modules 特征) = ${hashLike.length}`);
  console.log(`  samples: ${hashLike.slice(0, 15).join(', ')}`);
  process.exit(0);
}

if (mode === 'grep') {
  // node skin-survey-inspect.mjs grep <owner/repo> <path> <regex> [ref] [max]
  const repo = process.argv[3];
  const p = process.argv[4];
  const pat = process.argv[5];
  const ref = process.argv[6] || 'main';
  const max = Number(process.argv[7] || 40);
  const text = await get(`https://raw.githubusercontent.com/${repo}/${ref}/${p}`);
  if (text == null) {
    console.log('  FETCH FAILED');
    process.exit(1);
  }
  console.log(`=== grep /${pat}/ in ${repo}@${ref}/${p}  (${text.length} chars) ===`);
  const re = new RegExp(pat, 'g');
  let m;
  let n = 0;
  while ((m = re.exec(text)) !== null && n < max) {
    const s = Math.max(0, m.index - 70);
    console.log(`  @${String(m.index).padStart(7)}  ${text.slice(s, m.index + 130).replace(/\s+/g, ' ')}`);
    n++;
    if (m.index === re.lastIndex) re.lastIndex++;
  }
  console.log(`  -- ${n} shown --`);
  process.exit(0);
}

if (mode === 'file') {
  // node skin-survey-inspect.mjs file <owner/repo> <path> [ref] [maxChars]
  const repo = process.argv[3];
  const p = process.argv[4];
  const ref = process.argv[5] || 'main';
  const max = Number(process.argv[6] || 2500);
  const url = `https://raw.githubusercontent.com/${repo}/${ref}/${p}`;
  const text = await get(url);
  if (text == null) {
    console.log(`  FETCH FAILED: ${url}`);
    process.exit(1);
  }
  console.log(`=== ${repo}@${ref}/${p}   (${text.length} chars, showing ${Math.min(max, text.length)}) ===`);
  console.log(text.slice(0, max));
  if (text.length > max) console.log(`\n... [truncated ${text.length - max} chars]`);
  process.exit(0);
}

if (mode === 'tree') {
  const repo = process.argv[3];
  const ref = process.argv[4] || 'main';
  const sub = process.argv[5] || '';
  const j = await get(`https://api.github.com/repos/${repo}/git/trees/${ref}?recursive=1`, true);
  if (!j || !j.tree) {
    console.log('  tree fetch failed');
    process.exit(1);
  }
  const files = j.tree.filter((e) => e.type === 'blob').map((e) => e.path);
  const pre = sub ? sub + '/' : '';
  const scoped = pre ? files.filter((f) => f.startsWith(pre)) : files;
  console.log(`  repo=${repo} ref=${ref} sub=${sub || '(none)'}  totalBlobs=${files.length} scoped=${scoped.length}`);
  console.log('  --- scoped paths (size desc) ---');
  scoped
    .map((f) => ({ f, size: (j.tree.find((e) => e.path === f) || {}).size || 0 }))
    .sort((a, b) => b.size - a.size)
    .slice(0, 45)
    .forEach((e) => console.log(`    ${String(e.size).padStart(9)}  ${e.f}`));
  process.exit(0);
}

// report
const OUT = process.argv[3] || path.join(os.tmpdir(), 'skin-contract-report.json');
const r = JSON.parse(fs.readFileSync(OUT, 'utf8'));
const line = console.log;
line(`report=${OUT}  generatedAt=${r.generatedAt}  skins=${r.sampleN}  http=${r.httpRequests}`);
const show = (title, rows, n = 999) => {
  line('');
  line(`--- ${title}   (unique=${rows.length}) ---`);
  if (!rows.length) line('    (none)');
  rows.slice(0, n).forEach((x) => line(`    ${String(x.count).padStart(5)}x ${String(x.skins).padStart(2)}sk  ${x.key}`));
};
show('HOST data-* hooks', r.contract.hostAttr);
show('HOST [class*=fragment]', r.contract.classFragment);
show('HOST [class=exact]', r.contract.classExact);
show('HOST [id=]', r.contract.hostId);
show('HOST role/aria', r.contract.role);
show('HOST other attrs', r.contract.otherAttr);
show('SELF-CREATED nodes', r.selfNodes, 60);
show('PSEUDO host state', r.contract.pseudoHostState);

line('');
line('--- JS SIGNALS ---');
r.jsSignals.forEach((x) => line(`    ${String(x.count).padStart(3)} / ${r.sampleN}   ${x.key}`));

line('');
line('--- per skin ---');
r.perSkin.forEach((s) => {
  if (s.error) {
    line(`    ${s.skin}  ERROR: ${s.error}`);
    return;
  }
  line(`    ${s.skin}`);
  line(`      repo=${s.repo}@${String(s.ref).slice(0, 10)} sub='${s.sub}' treeFiles=${s.treeFiles} analyzedBytes=${s.bytes}`);
  line(`      selectors=${s.selectors} hostDep=${s.hostDep} ratio=${s.hostDepRatio} cssChars=${s.cssChars} jsChars=${s.jsChars}`);
  line(`      files=${JSON.stringify(s.analyzed)}`);
  line(`      signals=${JSON.stringify(s.signals)}`);
  if (s.pkg) line(`      pkg=${JSON.stringify(s.pkg)}`);
});
line('');
line('DONE');
