/**
 * DSH 皮肤「契约面」测绘 —— 定向取件版（不安装皮肤，不启后端）。
 *
 * 为什么要定向取件：装一个皮肤会把整个仓库拉下来（maid-atelier 一个就 ~20MB，其中 3.7MB 是
 * base64 内联图，而分析根本不需要）。这里改成：
 *   1) 每个仓库只请求一次 Git Trees API 拿路径树；
 *   2) 只下需要的几个小文件：`*.module.css`（几十~200KB）、`src/**\/*.ts` 源码、
 *      `package.json` / `skin.json`（几 KB）；
 *   3) **排除** `*.generated.ts` 与任何 >300KB 的文件（那些是 base64 美术，与分析无关）。
 * 实测每个皮肤约 1-2 MB、十几次请求，比安装快一到两个数量级。
 *
 * 测三样（对应"方案甲（薄契约 + 跑皮肤自己的 JS）"要对齐的东西）：
 *   1) 契约面   皮肤 CSS 指向「宿主」的钩子：data-* / [class*=片段] / [id=] / role
 *   2) 自带节点 皮肤 JS 自造的节点名（data-skin-chrome 之类）——决定要预留多少挂载位
 *   3) JS 依赖  皮肤代码是否能在我们的页面里安全跑（自包含？碰了宿主内部 API？）
 *
 * 用法：
 *   node skin-contract-survey.mjs [样本数] [输出json]
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const SAMPLE_N = Number(process.argv[2] || 6);
const OUT = process.argv[3] || path.join(os.tmpdir(), 'skin-contract-report.json');

// 与后端 RemoteFetchService 同一组加速前缀（实测都能代理 api.github.com 与 raw）
const PROXIES = ['gh-proxy.com', 'ghfast.top', 'ghproxy.net'];
const CATALOG_URLS = [
  'https://raw.githubusercontent.com/kingofsoysauce/dsh-skin-market/main/data/catalog.json',
  'https://kingofsoysauce.github.io/dsh-skin-market/data/catalog.json',
];
const MAX_FILE = 300 * 1024; // 单个分析文件上限：超过就是美术 blob，跳过
const MAX_BUNDLE = 2 * 1024 * 1024; // client bundle 放宽（CSS 常内联在 JS 里）
const MAX_PER_SKIN = 2.5 * 1024 * 1024;

let REQ = 0;
async function get(url, asJson = false) {
  for (const p of PROXIES) {
    try {
      REQ++;
      const r = await fetch(`https://${p}/${url}`, { signal: AbortSignal.timeout(25000) });
      if (!r.ok) continue;
      const t = await r.text();
      if (asJson) {
        try {
          return JSON.parse(t);
        } catch {
          continue;
        }
      }
      return t;
    } catch {
      /* 换下一个通道 */
    }
  }
  // 直连兜底（GitHub Pages 之类）
  try {
    REQ++;
    const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
    if (!r.ok) return null;
    const t = await r.text();
    return asJson ? JSON.parse(t) : t;
  } catch {
    return null;
  }
}

function loadLocalCatalog() {
  const p = path.join(os.tmpdir(), 'catalog-dump.json');
  try {
    const d = JSON.parse(fs.readFileSync(p, 'utf8'));
    return d.skins ? d.skins : d;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- 选择器分析

function extractPreludes(text) {
  const clean = text.replace(/\/\*[\s\S]*?\*\//g, '');
  const out = [];
  const re = /([^{}]+)\{/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    const p = m[1].trim();
    if (!p || p.startsWith('@') || p.startsWith('//')) continue;
    if (/\b(function|return|const|let|var|=>|export|import)\b/.test(p)) continue;
    if (!/^[.#\[a-zA-Z:*&>]/.test(p)) continue;
    out.push(p);
  }
  return out;
}

function splitTop(s) {
  const out = [];
  let depth = 0;
  let cur = '';
  for (const ch of s) {
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    if (ch === ',' && depth === 0) {
      out.push(cur.trim());
      cur = '';
    } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

const bump = (map, key, skin) => {
  if (!key) return 0;
  const e = map.get(key) || { count: 0, skins: new Set() };
  const fresh = !e.skins.has(skin);
  e.count++;
  e.skins.add(skin);
  map.set(key, e);
  return fresh ? 1 : 0;
};

/** 判断一条选择器是否依赖宿主（而不是皮肤自身的作用域属性）。 */
function analyzeSelector(sel, acc, skin) {
  let hostDep = 0;
  const attrRe = /\[\s*([a-zA-Z-]+)\s*(\^=|\*=|\$=|=|~=|\|=)?\s*['"]?([^\]'"]*)['"]?\s*\]/g;
  let m;
  while ((m = attrRe.exec(sel)) !== null) {
    const name = m[1].toLowerCase();
    const op = m[2] || '';
    const val = (m[3] || '').trim();
    if (name === 'class' && (op === '*=' || op === '^=')) {
      hostDep += bump(acc.classFragment, val, skin);
      continue;
    }
    // 皮肤自己的作用域属性（bodyAttr）与自造节点：不算宿主依赖
    if (/^data-(dsh|skin|maid|orca|lx|ws)-/.test(name) || name.startsWith('data-skin')) {
      bump(acc.selfNode, m[1], skin);
      continue;
    }
    if (name.startsWith('data-')) {
      hostDep += bump(acc.hostAttr, `${m[1]}${op ? `${op}"${val}"` : ''}`, skin);
      continue;
    }
    if (name === 'id') {
      hostDep += bump(acc.hostId, val, skin);
      continue;
    }
    if (name === 'class') {
      hostDep += bump(acc.classExact, `${op}${val}`, skin);
      continue;
    }
    if (name === 'role' || name.startsWith('aria-')) {
      hostDep += bump(acc.role, `${m[1]}${op ? `="${val}"` : ''}`, skin);
      continue;
    }
    bump(acc.otherAttr, m[1], skin);
  }
  for (const pc of sel.match(/:(?:has|is|not|where)\([^)]*\)/g) || []) {
    if (/\[data-/.test(pc)) bump(acc.pseudoHostState, pc.replace(/\s+/g, ' ').slice(0, 70), skin);
  }
  return hostDep;
}

/**
 * 原始文本扫描（抗压缩）。
 *
 * <p>压缩过的 bundle 里，`{` 前面几乎总是 `function(){` / `e=>{`，按 CSS 结构抽选择器会全被过滤掉
 * （实测 aurum / macintosh / furina 三个包因此 sel=0）。但契约测绘真正关心的是"它引用了哪些宿主钩子"，
 * 这与压缩无关 —— 直接在原文里匹配 `[data-*]` / `[class*=...]` 即可。</p>
 *
 * <p>同时统计：`--dsw-*` 语义 token 数（判断是不是"纯换色主题"）、内联图片数、CSS 变量赋值数。</p>
 */
function scanRaw(text, skin, rawHost, rawFrag) {
  let hostHits = 0;
  for (const m of text.matchAll(/\[\s*data-([a-zA-Z][\w-]*)\s*(?:([*^$~|]?=)\s*['"]?([^\]'"]{0,40})['"]?)?\s*\]/g)) {
    const name = 'data-' + m[1];
    // 皮肤自己的作用域/自造节点不算宿主依赖
    if (/^data-(dsh|skin|maid|orca|lx|ws)/.test(name)) {
      bump(acc.selfNode, name, skin);
      continue;
    }
    hostHits++;
    bump(rawHost, `${name}${m[2] ? `${m[2]}"${m[3]}"` : ''}`, skin);
  }
  for (const m of text.matchAll(/\[\s*class\s*([*^$~|]?=)\s*['"]?([^\]'"]{1,40})['"]?\s*\]/g)) {
    hostHits++;
    bump(rawFrag, m[2], skin);
  }
  return hostHits;
}

// ---------------------------------------------------------------- 取件

function parseTarget(t) {
  if (!t) return null;
  const s = t.replace(/^github:/, '');
  const hash = s.indexOf('#');
  const repo = (hash < 0 ? s : s.slice(0, hash)).trim();
  let rest = hash < 0 ? '' : s.slice(hash + 1);
  let ref = rest;
  let sub = '';
  const pi = rest.indexOf('&path:');
  if (pi >= 0) {
    ref = rest.slice(0, pi);
    sub = rest.slice(pi + 6).replace(/^\/+|\/+$/g, '');
  }
  return { repo, ref: ref.replace(/^\/+|\/+$/g, ''), sub };
}

/**
 * 从路径树里挑出"分析需要"的小文件。
 * <p>关键：client bundle 的路径**不能写死** `lib/client.js` —— 实测各仓库分别放在
 * `./client.js`（macintosh/furina）、`./plugin/client.js`（open-sea）、`./lib/client.js`（maid/orca）。
 * 正解是读 `package.json` 的 `exports['./client']`，**这个字段本身就是宿主加载皮肤的契约**。</p>
 */
function pickFiles(tree, sub, clientPath) {
  const prefix = sub ? sub + '/' : '';
  const inScope = tree.filter((f) => !prefix || f.startsWith(prefix));
  const rel = (f) => (prefix ? f.slice(prefix.length) : f);
  const picked = [];
  const push = (f, kind) => picked.push({ full: f, rel: rel(f), kind });

  for (const f of inScope) {
    const r = rel(f);
    if (r.includes('node_modules/') || r.endsWith('.map')) continue;
    if (/generated\.ts$/.test(r)) continue; // base64 美术 blob
    if (r.endsWith('.css')) {
      push(f, 'css');
      continue;
    }
    if (/^src\/.*\.(ts|tsx)$/.test(r) && !/\.d\.ts$/.test(r)) {
      push(f, 'js');
      continue;
    }
    if (/^(package\.json|skin\.json|skin\.build\.json|cordis\.patch\.yml)$/.test(r)) {
      push(f, 'meta');
      continue;
    }
  }
  // 优先用 src 源码（可读、小）；没有就退回 client bundle（CSS 常内联在里面）
  if (!picked.some((p) => p.kind === 'js')) {
    const want = clientPath ? prefix + clientPath.replace(/^\.?\//, '') : null;
    const bundle = (want && inScope.includes(want) ? want : null)
      || inScope.find((f) => /(^|\/)(lib|plugin)\/client\.js$/.test(f))
      || inScope.find((f) => /(^|\/)client\.js$/.test(f))
      || inScope.find((f) => /(^|\/)index\.js$/.test(f));
    if (bundle) push(bundle, 'js');
  }
  const css = picked.filter((p) => p.kind === 'css');
  return [...css.slice(0, 4), ...picked.filter((p) => p.kind !== 'css')];
}

async function fetchSkin(entry) {
  const t = parseTarget(entry.install && entry.install.target);
  if (!t || !t.repo) return { error: 'no install target' };
  const ref = t.ref || 'main';
  const treeJson = await get(`https://api.github.com/repos/${t.repo}/git/trees/${ref}?recursive=1`, true);
  if (!treeJson || !Array.isArray(treeJson.tree)) return { error: 'tree fetch failed' };
  const tree = treeJson.tree.filter((e) => e.type === 'blob').map((e) => e.path);

  // 先取 package.json：它的 exports['./client'] 决定 bundle 在哪
  const prefix = t.sub ? t.sub + '/' : '';
  const pkgPath = prefix + 'package.json';
  let pkgText = null;
  let clientPath = null;
  if (tree.includes(pkgPath)) {
    pkgText = await get(`https://raw.githubusercontent.com/${t.repo}/${ref}/${pkgPath}`);
    try {
      const p = JSON.parse(pkgText);
      const ex = p.exports && (p.exports['./client'] || (typeof p.exports['./client'] === 'object' ? null : null));
      clientPath = (typeof ex === 'string' ? ex : null) || p.client || null;
      if (!clientPath && typeof p.exports === 'object' && p.exports['.']) {
        const dot = p.exports['.'];
        clientPath = typeof dot === 'string' ? dot.replace(/index\.js$/, 'client.js') : null;
      }
    } catch {}
  }

  const files = pickFiles(tree, t.sub, clientPath);
  let bytes = 0;
  const contents = [];
  for (const f of files) {
    if (bytes > MAX_PER_SKIN) break;
    if (f.kind === 'meta' && f.rel === 'package.json' && pkgText != null) {
      bytes += pkgText.length;
      contents.push({ ...f, text: pkgText });
      continue;
    }
    const text = await get(`https://raw.githubusercontent.com/${t.repo}/${ref}/${f.full}`);
    if (text == null) continue;
    // client bundle 放宽；src 源码与 css 仍按小文件处理
    if (text.length > (f.kind === 'js' && /(^|\/)client\.js$/.test(f.rel) ? MAX_BUNDLE : MAX_FILE)) continue;
    bytes += text.length;
    contents.push({ ...f, text });
  }
  return { ...t, ref, treeSize: tree.length, clientPath, files: contents, bytes };
}

// ---------------------------------------------------------------- 主流程

const acc = {
  hostAttr: new Map(),
  hostId: new Map(),
  classFragment: new Map(),
  classExact: new Map(),
  role: new Map(),
  otherAttr: new Map(),
  selfNode: new Map(),
  pseudoHostState: new Map(),
  // 抗压缩原始扫描的结果（与按 CSS 结构解析的互补）
  rawHost: new Map(),
  rawFrag: new Map(),
};
const jsSignals = new Map();
const perSkin = [];

console.log('');
console.log('=== loading catalog ===');
let catalog = loadLocalCatalog();
if (catalog) console.log(`  from local dump: ${catalog.length} entries`);
else {
  for (const u of CATALOG_URLS) {
    const j = await get(u, true);
    if (j && j.skins) {
      catalog = j.skins;
      console.log(`  fetched: ${catalog.length} entries  <- ${u}`);
      break;
    }
  }
}
if (!catalog) {
  console.error('  !! catalog unavailable');
  process.exit(1);
}

// 选样本：两个已知皮肤当基准 + 按 tag 池各取一个（最大化种类多样性）
const TAG_POOL = [
  'token-theme', 'wallpaper', 'glass', 'retro', 'motion', 'anime', 'pastel', 'full-ui',
  'animated', 'pixel', 'composer', 'pet', 'utility', 'appearance', 'local-images',
  'dynamic-background', 'wallpaper-rotation', 'dark-overlay', 'serif', 'navy', 'gold',
  'pink', 'monochrome', 'mechanical', 'industrial', 'ornate', 'syntax', 'multi-skin',
  'endfield', 'macintosh', 'four-tier', 'reasoning-slider',
];
const picked = [];
const seen = new Set();
const take = (e, why) => {
  if (!e || seen.has(e.id) || picked.length >= SAMPLE_N) return;
  seen.add(e.id);
  picked.push({ entry: e, why });
};
take(catalog.find((s) => s.id === 'small-tailqwq.maid-atelier'), 'full-ui (known)');
take(catalog.find((s) => s.id === 'small-tailqwq.orca-link'), 'wallpaper+components (known)');
for (const t of TAG_POOL) {
  if (picked.length >= SAMPLE_N) break;
  // 只取**主要 tag** 就是它的皮肤（第一条 tag），避免所有皮肤都被 token-theme 吃掉
  const c = catalog
    .filter((s) => (s.tags || [])[0] === t || ((s.tags || []).includes(t) && (s.tags || []).length <= 3))
    .sort((a, b) => (b.stars || 0) - (a.stars || 0));
  take(c[0], 'tag:' + t);
}
// 还差就用 stars 补齐（排除已经因 tag 命中而重复的）
for (const s of [...catalog].sort((a, b) => (b.stars || 0) - (a.stars || 0))) {
  if (picked.length >= SAMPLE_N) break;
  take(s, 'top-stars');
}

console.log('');
console.log(`=== fetching ${picked.length} skins (CSS + src only, no assets) ===`);
for (const p of picked) {
  const id = p.entry.id;
  const r = await fetchSkin(p.entry);
  if (r.error) {
    console.log(`  FAIL ${id.padEnd(42)} ${r.error}`);
    perSkin.push({ skin: id, why: p.why, error: r.error });
    continue;
  }
  const cssText = r.files.filter((f) => f.kind === 'css').map((f) => f.text).join('\n');
  const jsText = r.files.filter((f) => f.kind === 'js').map((f) => f.text).join('\n');
  const meta = r.files.filter((f) => f.kind === 'meta');
  const allText = cssText + '\n' + jsText;
  let selectors = 0;
  let hostDep = 0;
  for (const prelude of extractPreludes(cssText)) {
    for (const sel of splitTop(prelude)) {
      if (!/[.#\[a-zA-Z]/.test(sel)) continue;
      selectors++;
      if (analyzeSelector(sel, acc, id) > 0) hostDep++;
    }
  }
  // 抗压缩扫描：CSS 内联在 JS 里 / bundle 被压缩时，这个是唯一可靠的入口
  const rawHostHits = scanRaw(allText, id, acc.rawHost, acc.rawFrag);
  const dswTokens = new Set([...allText.matchAll(/--dsw-[a-zA-Z0-9-]+/g)].map((m) => m[0]));
  const cssVarAssigns = (allText.match(/--[a-zA-Z][\w-]*\s*:/g) || []).length;
  const inlineImages = (allText.match(/data:image\//g) || []).length;
  const skinChromeHits = (allText.match(/data-skin-[a-z-]+/g) || []).length;
  const skinScopeHits = (allText.match(/data-dsh-[a-z-]+/g) || []).length;
  // 分类：决定"这类皮肤到底需不需要契约工作"
  let kind;
  if (skinChromeHits > 0 || skinScopeHits > 0 || rawHostHits > 0) kind = 'component';
  else if (inlineImages > 0) kind = 'art-only';
  else if (dswTokens.size > 0 || cssVarAssigns > 0) kind = 'token-only';
  else kind = 'unknown';
  const signals = {
    mutationObserver: /MutationObserver/.test(jsText),
    cssInject: /createElement\((['"])style\1\)|insertRule|adoptedStyleSheets/.test(jsText),
    hostStateRead: /dataset\.(phase|sidebarCollapsed)|data-phase|data-conversation-scroll/.test(jsText),
    hostImport: /from\s+['"](@deepseek|dsh|cordis|@linxin)|require\(['"](@deepseek|dsh|cordis)/.test(jsText),
    dynamicImport: /import\s*\(/.test(jsText),
    reactInternals: /__REACT|react-dom|_reactRootContainer/.test(jsText),
    domCreate: /createElement\(/.test(jsText),
    customEvent: /dispatchEvent|CustomEvent|addEventListener/.test(jsText),
    writesBodyAttr: /body\.(dataset|setAttribute)|documentElement\.(dataset|setAttribute)/.test(jsText),
  };
  for (const [k, v] of Object.entries(signals)) if (v) bump(jsSignals, k, id);
  const pkg = meta.find((f) => f.rel === 'package.json');
  const skinJson = meta.find((f) => f.rel === 'skin.json');
  let pkgObj = null;
  try {
    pkgObj = pkg ? JSON.parse(pkg.text) : null;
  } catch {}
  perSkin.push({
    skin: id,
    why: p.why,
    repo: r.repo,
    ref: r.ref,
    sub: r.sub,
    treeFiles: r.treeSize,
    clientPath: r.clientPath || null,
    analyzed: r.files.map((f) => `${f.kind}:${f.rel}`),
    bytes: r.bytes,
    kind,
    selectors,
    hostDep,
    hostDepRatio: selectors ? +(hostDep / selectors).toFixed(3) : 0,
    rawHostHits,
    rawFrags: new Set([...allText.matchAll(/\[\s*class\s*[*^$~|]?=\s*['"]?([^\]'"]{1,40})['"]?\s*\]/g)].map((m) => m[1])).size,
    dswTokens: dswTokens.size,
    dswTokenSample: [...dswTokens].slice(0, 8),
    cssVarAssigns,
    inlineImages,
    skinChromeHits,
    skinScopeHits,
    jsHead: jsText.slice(0, 260).replace(/\s+/g, ' '),
    signals,
    jsChars: jsText.length,
    cssChars: cssText.length,
    pkg: pkgObj ? { name: pkgObj.name, main: pkgObj.main, module: pkgObj.module, exports: pkgObj.exports, deps: Object.keys(pkgObj.dependencies || {}), peer: Object.keys(pkgObj.peerDependencies || {}) } : null,
    skinJson: skinJson ? skinJson.text.trim().slice(0, 900) : null,
  });
  console.log(
    `  OK   ${id.padEnd(40)} kind=${kind.padEnd(12)} bytes=${String(Math.round(r.bytes / 1024)).padStart(4)}KB ` +
      `sel=${String(selectors).padStart(4)} hostDep=${String(hostDep).padStart(3)} rawHost=${String(rawHostHits).padStart(4)} ` +
      `chrome=${String(skinChromeHits).padStart(4)} dswTok=${String(dswTokens.size).padStart(3)} img=${String(inlineImages).padStart(3)}`
  );
}

// ---------------------------------------------------------------- 汇总输出

const asList = (map, top = 60) =>
  [...map.entries()]
    .sort((a, b) => b[1].count - a[1].count || a[0].localeCompare(b[0]))
    .slice(0, top)
    .map(([k, v]) => ({ key: k, count: v.count, skins: v.skins.size }));

const report = {
  generatedAt: new Date().toISOString(),
  sampleN: picked.length,
  httpRequests: REQ,
  contract: {
    hostAttr: asList(acc.hostAttr),
    classFragment: asList(acc.classFragment),
    classExact: asList(acc.classExact, 30),
    hostId: asList(acc.hostId, 20),
    role: asList(acc.role, 30),
    otherAttr: asList(acc.otherAttr, 20),
    pseudoHostState: asList(acc.pseudoHostState, 30),
    // 抗压缩扫描（CSS 内联在 JS / bundle 被压缩时唯一可靠的来源）
    rawHost: asList(acc.rawHost),
    rawFrag: asList(acc.rawFrag),
  },
  selfNodes: asList(acc.selfNode, 80),
  coverage: null,
  runtime: null,
  jsSignals: [...jsSignals.entries()].map(([k, v]) => ({ key: k, count: v.count, skins: v.skins.size })).sort((a, b) => b.count - a.count),
  perSkin,
};
/**
 * 契约覆盖度。
 *
 * <p>关键洞察：**只有被 >=2 个皮肤引用的钩子才是"真宿主契约"**。只出现一次的多数是皮肤私有命名空间
 * （实测 `data-mc-*` 是 macintosh 自己的、`data-furina-*` 是 furina 自己的），
 * 把它们算进"要对齐的宿主契约"会把工作量估算得虚高。</p>
 */
{
  const rows = report.contract.rawHost;
  const totalHits = rows.reduce((a, b) => a + b.count, 0);
  const shared = rows.filter((r) => r.skins >= 2);
  const sharedHits = shared.reduce((a, b) => a + b.count, 0);
  report.coverage = {
    uniqueHooks: rows.length,
    totalHits,
    sharedHooks: shared.length,
    sharedHits,
    sharedPct: totalHits ? +((sharedHits / totalHits) * 100).toFixed(1) : 0,
    // 单皮肤钩子：可能是宿主钩子（只是没被别的皮肤用），也可能是皮肤私有命名空间
    singleSkinHooks: rows.filter((r) => r.skins === 1).map((r) => r.key),
  };
}

/**
 * 运行时依赖分类 —— 这决定"这个皮肤能不能直接跑"，比 CSS 分析更重要。
 *   zero-dep   无 peer 依赖，自包含，注入即可
 *   react-only 只要 React（我们平台本身就有）
 *   cordis     需要宿主的插件框架 @deepseek-ai/cordis
 */
{
  const buckets = {};
  perSkin
    .filter((s) => !s.error && s.pkg)
    .forEach((s) => {
      const peer = s.pkg.peer || [];
      const r = peer.some((p) => /cordis/i.test(p))
        ? 'cordis'
        : peer.some((p) => /react/i.test(p))
          ? 'react-only'
          : 'zero-dep';
      s.runtime = r;
      (buckets[r] = buckets[r] || []).push(s.skin);
    });
  report.runtime = buckets;
}

fs.writeFileSync(OUT, JSON.stringify(report, null, 2), 'utf8');

// 输出同时写文件：终端会被截断，文件可以用 read_file 分段读全
const buf = [];
const line = (s = '') => {
  buf.push(s);
  console.log(s);
};
line('');
line('==================================================');
line(`  CONTRACT SURVEY   skins=${report.sampleN}   http=${report.httpRequests}`);
line(`  report -> ${OUT}`);
line('==================================================');
line('');
line('--- RUNTIME DEPENDENCY  (can this skin run outside DSH?) ---');
Object.entries(report.runtime || {})
  .sort((a, b) => b[1].length - a[1].length)
  .forEach(([k, v]) => line(`    ${String(v.length).padStart(3)}  ${k.padEnd(11)} ${v.join(', ')}`));
line('');
line('--- CONTRACT COVERAGE ---');
{
  const c = report.coverage;
  line(`    unique host hooks           = ${c.uniqueHooks}`);
  line(`    total hook references       = ${c.totalHits}`);
  line(`    hooks shared by >=2 skins   = ${c.sharedHooks}    <-- 真正要对齐的（覆盖 ${c.sharedPct}% 的引用）`);
  line(`    hooks used by only 1 skin   = ${c.singleSkinHooks.length}    <-- 多数是皮肤私有命名空间`);
}
const show = (title, rows, n = 200) => {
  line('');
  line(`--- ${title}   (unique=${rows.length}) ---`);
  if (!rows.length) line('    (none)');
  rows.slice(0, n).forEach((r) => line(`    ${String(r.count).padStart(5)}x ${String(r.skins).padStart(2)}sk  ${r.key}`));
  if (rows.length > n) line(`    ... +${rows.length - n} more`);
};
show('HOST data-* hooks (raw scan, minification-proof)', report.contract.rawHost);
show('HOST [class*=...] (raw scan)', report.contract.rawFrag);
show('HOST data-* hooks (parsed CSS only)', report.contract.hostAttr);
show('HOST [class*=fragment] (parsed CSS only)', report.contract.classFragment);
show('HOST [class=exact]', report.contract.classExact);
show('HOST [id=]', report.contract.hostId);
show('HOST role/aria', report.contract.role);
show('SELF-CREATED nodes', report.selfNodes);
show('PSEUDO host state', report.contract.pseudoHostState);

line('');
line('--- CLASSIFICATION (does this kind of skin need contract work at all?) ---');
const byKind = {};
perSkin.filter((s) => !s.error).forEach((s) => {
  byKind[s.kind] = (byKind[s.kind] || 0) + 1;
});
Object.entries(byKind)
  .sort((a, b) => b[1] - a[1])
  .forEach(([k, n]) => line(`    ${String(n).padStart(3)}  ${k}`));
line('');
perSkin
  .filter((s) => !s.error)
  .forEach((s) =>
    line(
      `    ${s.kind.padEnd(12)} ${s.skin.padEnd(34)} dswTok=${String(s.dswTokens).padStart(3)} cssVar=${String(s.cssVarAssigns).padStart(4)} ` +
        `img=${String(s.inlineImages).padStart(3)} chrome=${String(s.skinChromeHits).padStart(4)} rawHost=${String(s.rawHostHits).padStart(4)}`
    )
  );

line('');
line('--- JS SIGNALS (skins touching each) ---');
report.jsSignals.forEach((r) => line(`    ${String(r.count).padStart(3)} / ${report.sampleN}   ${r.key}`));

line('');
line('--- host dependency ratio per skin ---');
[...perSkin]
  .filter((s) => !s.error)
  .sort((a, b) => b.hostDepRatio - a.hostDepRatio)
  .forEach((s) => line(`    ${s.hostDepRatio.toFixed(2)}  sel=${String(s.selectors).padStart(5)} hostDep=${String(s.hostDep).padStart(5)}  css=${String(Math.round(s.cssChars / 1024)).padStart(4)}KB js=${String(Math.round(s.jsChars / 1024)).padStart(4)}KB  ${s.skin}`));

line('');
line('--- per skin detail ---');
perSkin.forEach((s) => {
  if (s.error) {
    line(`  ${s.skin}  ERROR: ${s.error}`);
    return;
  }
  line(`  ${s.skin}   [${s.why}]`);
  line(`    repo=${s.repo}@${String(s.ref).slice(0, 10)} sub='${s.sub}' client='${s.clientPath || '?'}' tree=${s.treeFiles} bytes=${s.bytes}`);
  line(`    sel=${s.selectors} hostDep=${s.hostDep} ratio=${s.hostDepRatio}`);
  line(`    files=${JSON.stringify(s.analyzed)}`);
  line(`    signals=${JSON.stringify(s.signals)}`);
  if (s.pkg) line(`    pkg=${JSON.stringify(s.pkg)}`);
});

line('');
line('--- skin.json contract samples ---');
perSkin
  .filter((s) => s.skinJson)
  .slice(0, 4)
  .forEach((s) => {
    line(`  [${s.skin}]`);
    line('    ' + s.skinJson.replace(/\s+/g, ' ').slice(0, 700));
  });

line('');
line('DONE');

const SUMMARY = path.join(path.dirname(OUT), 'skin-contract-summary.txt');
fs.writeFileSync(SUMMARY, buf.join('\n'), 'utf8');
console.log(`\nsummary -> ${SUMMARY}`);
