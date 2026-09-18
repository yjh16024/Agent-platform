/**
 * 命中率台架 v2：把真实皮肤注入**我们当前的真实页面**，量出"宿主的薄契约够不够"。
 *
 * v1 的两处方法论错误（已修）：
 *  1. **分母被污染**：把皮肤"自己的作用域属性"也算进了宿主命中率。真实皮肤几乎每条选择器都带
 *     `body[data-dsh-xxx]` 前缀，而那个属性是**皮肤自己的 JS 设的**——在 JS 生效前它们必然命中不了。
 *     所以必须先设上 `bodyAttr`，并**剥掉前缀**再统计"宿主钩子够不够"。
 *  2. **大 bundle 注入失败**：v1 把 bundle 文本当参数过 IPC（orca 的 1.4MB 直接失败）。
 *     改成**在页面内 fetch** `/api/v1/skins/bundle` 再插 `<script>`——不走 IPC，也正是产品路径。
 *
 * 运行：electron.exe desktop/skin-hitrate-harness.cjs
 *   AP_PORT / AP_SKIN_ID / AP_OUT
 */
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const PORT = process.env.AP_PORT || '18112';
const SKIN_ID = process.env.AP_SKIN_ID || 'small-tailqwq.orca-link';
const SKIN_DIR = process.env.AP_SKIN_DIR || path.join('data', 'skins', SKIN_ID);
const OUT = process.env.AP_OUT || path.join(process.env.TEMP || '.', 'skin-hitrate-report.json');
/*
 * 自检：本文件"注入页面"的代码写在**模板字符串**里，于是有两类会在运行时才炸的坑：
 *   ① 注释里出现反引号 → 提前闭合外层模板，里面的内容变成台架自身的代码；
 *   ② 注释里出现美元花括号 → 被当成插值求值。
 * 两者**都是合法语法**，语法检查（node --check）抓不到（已因此白跑三轮）。
 * 所以启动时扫一遍自身源码，发现立刻退出，而不是跑到一半报个看不懂的 ReferenceError。
 */
(function guardInjectedCode() {
  const src = fs.readFileSync(__filename, 'utf8');
  const BT = String.fromCharCode(96);
  // 注入点形如 executeJavaScript(`...`)；模板闭合后**必须紧跟右括号**。
  // 若不是（例如后面还有 `]`、`.`、字母），说明模板被内容里的杂散反引号提前闭合了。
  const re = new RegExp('executeJavaScript\\(\\s*(' + BT + ')', 'g');
  const bad = [];
  let m;
  while ((m = re.exec(src)) !== null) {
    const start = m.index + m[0].length;
    let i = start;
    for (; i < src.length; i++) {
      if (src[i] === '\\') {
        i += 1;
        continue;
      }
      if (src[i] === BT) break;
    }
    let j = i + 1;
    while (j < src.length && /\s/.test(src[j])) j += 1;
    if (src[j] !== ')') {
      const line = src.slice(0, m.index).split('\n').length;
      bad.push('  第 ' + line + ' 行：模板在 ' + JSON.stringify(src.slice(i, i + 46)) + ' 处提前闭合，闭合后紧跟的是 ' + JSON.stringify(src[j] || 'EOF'));
    }
  }
  if (bad.length) {
    console.error('FATAL: 注入代码的模板字符串被提前闭合（注释里出现反引号就会这样）：');
    console.error(bad.join('\n'));
    process.exit(1);
  }
})();

// 只跑自检就退出 —— 用普通 node 即可验证，不必起 Electron / 后端。
if (process.env.AP_GUARD_ONLY === '1') {
  console.log('GUARD_OK');
  process.exit(0);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** 按 package.json 的 exports['./client'] 定位客户端 bundle（与后端 ClientBundleLocator 同一规则）。 */
function locateBundle(dir) {
  let clientPath = null;
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
    const ex = pkg.exports && pkg.exports['./client'];
    if (typeof ex === 'string') clientPath = ex;
    else if (ex && typeof ex === 'object') {
      for (const k of ['import', 'module', 'default', 'require']) {
        if (typeof ex[k] === 'string') {
          clientPath = ex[k];
          break;
        }
      }
    }
  } catch {
    /* ignore */
  }
  const cands = [clientPath, 'lib/client.js', 'client.js', 'plugin/client.js', 'dist/client.js']
    .filter(Boolean)
    .map((p) => p.replace(/^\.\//, ''));
  for (const c of cands) {
    const f = path.join(dir, c);
    if (fs.existsSync(f)) return { rel: c, file: f };
  }
  return null;
}

/**
 * 从 bundle 里抽出**内嵌的 CSS**。
 *
 * <p>关键：DSH 皮肤产物把 CSS 作为字符串常量内嵌，带 `//#region \0dsh-css:<源路径>` 标记：
 * <pre>
 *   //#region \0dsh-css:src/client/orca-link.module.css.mjs const css = "body[data-dsh-orca-link] ..."
 * </pre>
 * 而 CSS Modules 把类名编译成 **`<hash>_<原名>`**（如 `._0cMdVG_lightScene`），
 * 所以拿**源码** `.module.css` 的原始类名去测 DOM 必然全不命中 —— 这是之前命中率被严重低估的根因。</p>
 */
function extractBundleCss(text) {
  const out = [];
  let i = 0;
  while ((i = text.indexOf('dsh-css:', i)) !== -1) {
    const spaceAfterPath = text.indexOf(' ', i);
    if (spaceAfterPath < 0) break;
    const rel = text.slice(i + 8, spaceAfterPath);
    const eq = text.indexOf('= "', spaceAfterPath);
    if (eq < 0 || eq - spaceAfterPath > 240) {
      i += 8;
      continue;
    }
    let j = eq + 3;
    let buf = '';
    while (j < text.length) {
      const ch = text[j];
      if (ch === '\\') {
        const nx = text[j + 1];
        buf += nx === 'n' ? '\n' : nx === 't' ? '\t' : nx;
        j += 2;
        continue;
      }
      if (ch === '"') break;
      buf += ch;
      j++;
    }
    if (buf.length > 200) out.push({ rel, text: buf });
    i = j;
  }
  return out;
}

function walk(dir, out = [], depth = 0) {
  if (depth > 6) return out;
  let es = [];
  try {
    es = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of es) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name.startsWith('.') || e.name === 'node_modules') continue;
      walk(p, out, depth + 1);
    } else if (e.name.endsWith('.css')) out.push(p);
  }
  return out;
}

/** 只取"给 DSH 页面用"的 CSS：优先 src/**\/*.module.css，避免把官网/扩展/独立渲染器的样式算进分母。 */
function pickCss(files) {
  const rel = files.map((f) => path.relative(SKIN_DIR, f).replace(/\\/g, '/'));
  const moduleCss = rel.filter((r) => /\.module\.css$/.test(r) && !/^(website|site|native-dist|extension)\//.test(r));
  const chosen = moduleCss.length ? moduleCss : rel.filter((r) => !/^(website|site)\//.test(r));
  return chosen.map((r) => ({ rel: r, text: fs.readFileSync(path.join(SKIN_DIR, r), 'utf8') }));
}

// CSS 来源优先级：**bundle 内嵌 CSS（哈希类名）** > 源码 *.module.css。
// 前者才是真正会被注入页面的那一份；拿源码原始类名去测 DOM，等于对着错误的类名打分
// （实测：源码 `.lightScene` vs 产物 `._0cMdVG_lightScene`，永远不可能命中）。
const bundleInfo = locateBundle(SKIN_DIR);
const bundleText = bundleInfo ? fs.readFileSync(bundleInfo.file, 'utf8') : '';
const bundleCss = extractBundleCss(bundleText);
const cssList = bundleCss.length ? bundleCss : pickCss(walk(SKIN_DIR));
const cssFrom = bundleCss.length ? 'bundle-inlined' : 'source-files';
console.log(`  bundle = ${bundleInfo ? bundleInfo.rel : '(not found)'}  (${bundleText.length} chars)`);
console.log(`  inlined CSS blocks = ${bundleCss.length}   cssFrom = ${cssFrom}`);

/**
 * 按**顶层逗号**切分选择器列表。
 * <p>不能用 `split(',')`：皮肤的反重置顶规则是
 * `body[data-xxx] :where(button, input, textarea, select, [role='button'], …)`，
 * 裸切会把一条规则切成一堆碎块（`:where(button` 直接变非法选择器），
 * 于是"未命中"被严重高估。括号/方括号计数是必需的。</p>
 */
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

function selectors(text) {
  const clean = text.replace(/\/\*[\s\S]*?\*\//g, '');
  const out = [];
  const re = /([^{}]+)\{/g;
  let m;
  while ((m = re.exec(clean)) !== null) {
    const pre = m[1].trim();
    if (!pre || pre.startsWith('@')) continue;
    for (const s of splitTop(pre)) {
      // 伪元素不影响 querySelectorAll 的匹配结果，统一去掉再测
      const t = s.trim().replace(/::?(before|after|placeholder|selection|marker|backdrop)\b/g, '');
      if (!t) continue;
      const cleaned = t.replace(/\s+/g, ' ').replace(/\s*>\s*/g, ' > ').trim();
      if (cleaned && /^[.#\[a-zA-Z:*&>]/.test(cleaned)) out.push(cleaned);
    }
  }
  return [...new Set(out)];
}

const allSelectors = [...new Set(cssList.flatMap((c) => selectors(c.text)))];

/** 从皮肤的 package.json / skin.json 里读出 bodyAttr 与皮肤自己的前缀。 */
function readMeta() {
  const meta = {};
  for (const name of ['package.json', 'skin.json']) {
    try {
      const j = JSON.parse(fs.readFileSync(path.join(SKIN_DIR, name), 'utf8'));
      Object.assign(meta, j);
    } catch {
      /* ignore */
    }
  }
  return meta;
}
const skinMeta = readMeta();
// bodyAttr 一般在 skin.json（maid/orca 是 data-dsh-maid-atelier / data-dsh-orca-link）
const bodyAttr = skinMeta.bodyAttr || null;

app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, width: 1440, height: 900 });
  const pageLogs = [];
  win.webContents.on('console-message', (_e, level, message) => pageLogs.push(`[${level}] ${message}`));

  const report = {
    startedAt: new Date().toISOString(),
    skinId: SKIN_ID,
    cssFiles: cssList.map((c) => c.rel),
    cssFrom,
    bundlePath: bundleInfo ? bundleInfo.rel : null,
    selectorCount: allSelectors.length,
    bodyAttr,
    bodyAttrFrom: skinMeta.bodyAttr ? 'skin.json' : null,
    routes: {},
  };

  // 页面矩阵：不靠交互就能覆盖的不同 DOM 形态。
  // 前端是 **HashRouter**（写成 /chat 会拿到只有 23 个元素的空壳）。
  const ROUTES = (process.env.AP_ROUTES || '/#/chat,/#/skins,/#/settings,/#/agents,/#/overview').split(',');

  const probeCall = (strip) =>
    `(${probe.toString()})(${JSON.stringify(allSelectors)}, ${JSON.stringify(bodyAttr)}, ${strip})`;

  try {
    for (const route of ROUTES) {
      const r = { route };
      await win.loadURL(`http://127.0.0.1:${PORT}${route}`);
      await wait(6500);

      r.dom = await win.webContents.executeJavaScript(
        `(() => ({ elements: document.getElementsByTagName('*').length, hasRoot: !!document.getElementById('root'), bodyAttrs: [...document.body.attributes].map(a => a.name) }))()`
      );
      if (!r.dom.hasRoot) {
        r.skipped = 'no #root — route did not render the app';
        report.routes[route] = r;
        continue;
      }

      r.baseline = await win.webContents.executeJavaScript(probeCall(false));
      // 注入皮肤 CSS
      await win.webContents.executeJavaScript(`(${injectCss.toString()})(${JSON.stringify(cssList)})`);
      // 提前设上皮肤自己的作用域属性（它的 JS 也会设），把"作用域"这个变量单独分离出来
      if (bodyAttr) {
        await win.webContents.executeJavaScript(
          `(() => { document.body.setAttribute(${JSON.stringify(bodyAttr)}, ''); return true; })()`
        );
      }
      r.afterCss = await win.webContents.executeJavaScript(probeCall(true));
      // 加载皮肤自己的 JS（页面内 fetch，不过 IPC）
      r.skinJs = await win.webContents.executeJavaScript(`(${loadSkin.toString()})(${JSON.stringify(SKIN_ID)})`);
      await wait(2500);
      r.host = await win.webContents.executeJavaScript(probeCall(true));

      // 状态矩阵 · 暗色：皮肤大量规则以 `[data-ds-dark-theme]` 为前提，亮色下必然"未命中"。
      // 这里直接设该属性来隔离状态变量（不点 UI，避免把交互不确定性引进来）。
      await win.webContents.executeJavaScript(
        `(() => { document.body.setAttribute('data-ds-dark-theme',''); return true; })()`
      );
      await wait(500);
      r.hostDark = await win.webContents.executeJavaScript(probeCall(true));

      // 状态矩阵 · 皮肤状态镜像：orca 自己会把 data-phase 镜像成 data-orca-scene，
      // 这里替它切到 active（真实场景下由"发出第一条消息"触发）。
      await win.webContents.executeJavaScript(
        `(() => { document.body.setAttribute('data-orca-scene','active'); return true; })()`
      );
      await wait(500);
      r.hostDarkActive = await win.webContents.executeJavaScript(probeCall(true));

      // 直接验证 orca 的 scene.ts 会不会找到"会话根"：
      //   for (const c of body.querySelectorAll('[data-phase]'))
      //     if (c.querySelector('[data-conversation-scroll]')?.closest('[data-phase]') === c) return c
      r.sceneRoot = await win.webContents.executeJavaScript(`(() => {
        const CONV = '[data-conversation-scroll]';
        for (const c of document.body.querySelectorAll('[data-phase]')) {
          const s = c.querySelector(CONV);
          if (s && s.closest('[data-phase]') === c) {
            return { found: true, phase: c.getAttribute('data-phase'), tag: c.tagName, hasScroll: true };
          }
        }
        return {
          found: false,
          phases: [...document.body.querySelectorAll('[data-phase]')].map(x => x.tagName + '/' + x.getAttribute('data-phase')),
          scrollCount: document.querySelectorAll(CONV).length,
        };
      })()`);

      // 直接探测"皮肤自建节点到底建出来没有" + "皮肤依赖的宿主结构是否就位"。
      // 这比覆盖率数字更能回答"适配到哪一步了"。
      r.markers = await win.webContents.executeJavaScript(`(() => {
        const probe = {
          skinChrome: '[data-skin-chrome]',
          lightScene: '[data-skin-chrome="light-scene"]',
          darkScene: '[data-skin-chrome="dark-scene"]',
          spine: '[data-skin-chrome="spine"]',
          standby: '[data-skin-chrome="standby"]',
          statusCharacter: '[data-orca-link-character]',
          wordmark: '[data-orca-link-wordmark]',
          signalChip: '[data-orca-link-signal]',
          brandFlag: '[data-orca-link-brand]',
          sidebarPane: "[data-slot='sidebar'] > :first-child",
          sidebarLogoRow: "[data-slot='sidebar'] > :first-child > :first-child",
          searchInput: "input[class*='searchInput']",
          searchButton: "button[class*='searchButton']",
          headlineText: "[class*='headlineText']",
          footArea: "[class*='footArea']",
          sidebarCol: "[class*='sidebarCol']",
          regionArea: "[class*='regionArea']",
          logoRow: "[class*='logoRow']",
          centerCol: "[class*='centerCol']",
          newSession: "[class*='newSession']",
          triggerLabel: "[class*='triggerLabel']",
          pending: "[class*='_pending']",
          // v2 新增的 A 类欠账钩子
          brandMark: "[data-slot='sidebar.brand.mark']",
          brandName: "[data-slot='sidebar.brand.name']",
          footerAction: "[data-slot='sidebar.footer.action']",
          settingsTrigger: "[data-slot='settings.trigger']",
          chatNode: "[data-slot='conversation.chat.node']",
          userStack: "[class*='_userStack']",
        };
        const out = {};
        for (const [k, sel] of Object.entries(probe)) {
          try {
            out[k] = document.querySelectorAll(sel).length;
          } catch {
            out[k] = -1;
          }
        }
        out.__skinStyleTags = document.querySelectorAll('style[data-ap-skin-css], style[data-skin]').length;
        // ★ B1 验证：皮肤自定义协议的产物 —— 皮肤会把开关投影成 html 上的属性。
        // 我们不需要知道属性名，只要看 html 上**多出了什么**，就知道协议有没有走通。
        out.__htmlAttrs = [...document.documentElement.attributes]
          .map((a) => a.name + '=' + a.value)
          .filter((s) => s.startsWith('data-dsh-') || s.startsWith('data-skin'))
          .join(' | ');
        // 本轮界面收敛验证：顶部 Header 去掉了、侧栏底部只留皮肤设置
        out.__layoutHeader = document.querySelectorAll('.ant-layout-header').length;
        // composer 两层结构：seat 必须是 card 的**祖先**。
        // 这个数 >0 才说明 [data-composer-seat] [data-composer-card] 这类后代选择器真的能命中
        // （之前两层挂同一元素时它恒为 0）。
        // 注意：本段位于模板字符串内，**注释里也不能出现反引号** —— 反引号会提前闭合外层模板，
        // 里面的内容会变成台架自身的代码（曾报 "data is not defined"），而 node --check 抓不到
        // （它仍是合法语法：数组下标表达式）。
        out.__composerSeat = document.querySelectorAll('[data-composer-seat]').length;
        out.__composerCard = document.querySelectorAll('[data-composer-card]').length;
        out.__seatAncestorOfCard = document.querySelectorAll('[data-composer-seat] [data-composer-card]').length;
        // 已下线的两条路：卡片墙上的「应用」按钮 与 「皮肤背景图」面板
        out.__applyBtn = Array.prototype.filter.call(document.querySelectorAll('button'), function (b) {
          return String(b.textContent || '').trim() === '应用';
        }).length;
        out.__hasBgPanel = String(document.body.textContent || '').indexOf('皮肤背景图') >= 0;
        out.__footerBtns = (function () {
          var foot = document.querySelector("[class*='footArea']");
          if (!foot) return 'no-footArea';
          var bs = foot.querySelectorAll('button');
          var labels = [];
          for (var i = 0; i < bs.length; i++) {
            labels.push(String(bs[i].textContent || '').trim() || bs[i].getAttribute('aria-label') || '?');
          }
          return bs.length + ' [' + labels.join(' | ') + ']';
        })();
        // 直接问宿主：皮肤自定义协议注册上没有（只读诊断钩子）
        // 注意：这段代码本身位于一个模板字符串内，所以**不能再出现反引号或美元花括号插值**，
        // 注释里也不要写字面量（注释同样会被插值），一律用字符串拼接。
        // 曾两次踩到：嵌套反引号提前闭合外层模板、注释里的插值语法无法求值。
        try {
          const snap = typeof window.__apSkinCustomization === 'function' ? window.__apSkinCustomization() : null;
          out.__customInstalled = snap ? String(snap.installed) : 'no-hook';
          out.__customRegistered = snap ? String(snap.registrationCount) : '';
          out.__customSkins = snap ? snap.skins.join(',') : '';
          // 运行时日志也当状态读（prod 构建丢了 console，抓日志抓不到东西）
          out.__runtimeLog =
            typeof window.__apSkinRuntimeLog === 'function'
              ? window.__apSkinRuntimeLog().slice(-14).join(' ;; ')
              : 'no-log-hook';
          out.__customSettings =
            snap && snap.definitions && snap.definitions[0]
              ? snap.definitions[0].settings.map(function (s) { return s.key + ':' + s.type; }).join(',')
              : '';
        } catch (e) {
          out.__customInstalled = 'hook-threw:' + e.message;
        }
        return out;
      })()`);

      r.domAfter = await win.webContents.executeJavaScript(
        `(() => ({ elements: document.getElementsByTagName('*').length, bodyAttrs: [...document.body.attributes].map(a => a.name) }))()`
      );

      report.routes[route] = r;
      console.log(
        `  ${route.padEnd(13)} els=${String(r.dom.elements).padStart(4)}  light/hero ${r.host.hostDepHits}/${r.host.hostDepTotal} (${r.host.hostDepPct}%)` +
          `  dark ${r.hostDark.hostDepHits}/${r.hostDark.hostDepTotal} (${r.hostDark.hostDepPct}%)` +
          `  dark/active ${r.hostDarkActive.hostDepHits}/${r.hostDarkActive.hostDepTotal} (${r.hostDarkActive.hostDepPct}%)` +
          `  skin=${r.skinJs.loaded ? 'ok' : 'FAIL'}`
      );
    }

    report.summary = Object.entries(report.routes).map(([route, r]) => ({
      route,
      elements: r.dom && r.dom.elements,
      skinJsOk: r.skinJs ? !!r.skinJs.loaded : null,
      bodyAttrs: (r.domAfter || r.dom).bodyAttrs,
      hostDepHits: r.host ? r.host.hostDepHits : null,
      hostDepTotal: r.host ? r.host.hostDepTotal : null,
      hostDepPct: r.host ? r.host.hostDepPct : null,
      darkHits: r.hostDark ? r.hostDark.hostDepHits : null,
      darkPct: r.hostDark ? r.hostDark.hostDepPct : null,
      darkActiveHits: r.hostDarkActive ? r.hostDarkActive.hostDepHits : null,
      darkActivePct: r.hostDarkActive ? r.hostDarkActive.hostDepPct : null,
      byKind: r.host ? r.host.byKind : null,
      byKindHit: r.host ? r.host.byKindHit : null,
      byKindDark: r.hostDark ? r.hostDark.byKind : null,
      byKindDarkHit: r.hostDark ? r.hostDark.byKindHit : null,
      hostMissedTop: r.host ? r.host.hostMissed.slice(0, 15) : [],
      hostMissedDarkTop: r.hostDark ? r.hostDark.hostMissed.slice(0, 15) : [],
      skinOwnHits: r.host ? r.host.skinOwnHits : null,
      skinOwnTotal: r.host ? r.host.skinOwnTotal : null,
      skinOwnPct: r.host ? r.host.skinOwnPct : null,
      skinOwnMissedTop: r.host ? r.host.skinOwnMissed.slice(0, 12) : [],
      markers: r.markers,
    }));
    report.pageLogs = pageLogs.slice(-40);
  } catch (e) {
    report.fatal = String(e && e.stack ? e.stack : e);
  }

  fs.writeFileSync(OUT, JSON.stringify(report, null, 2), 'utf8');
  console.log(JSON.stringify({ bodyAttr, cssFiles: report.cssFiles, summary: report.summary, fatal: report.fatal }, null, 2));
  app.quit();
});

/**
 * 统计命中；同时区分"带皮肤前缀的选择器"（真正要考宿主的那部分）与其余。
 * stripPrefix=true 时额外算一份"剥掉皮肤 prefix 后"的命中率 —— 那才是宿主钩子的覆盖度。
 */
/**
 * 判断（已剥掉皮肤作用域属性后的）选择器**是否真的依赖宿主**。
 *
 * <p>这是最后一次方法论修正：皮肤 CSS 里大量规则是
 * {@code body[data-dsh-orca-link] .lightScene {...}} —— 剥掉前缀后是 `.lightScene`，
 * 那是**皮肤自己的 JS 创建的舞台节点**，跟我们挂不挂钩子毫无关系。
 * 把它们算进分母，覆盖率会被无意义地拉到个位数。</p>
 *
 * <p>只有引用了下列之一的，才是"宿主必须提供的"：
 * 宿主 data-* 钩子、role/aria 语义、`[class*=]` 片段、以及裸语义标签（button/input/textarea…）。</p>
 */
// 注意：hasHostHook 必须定义在 probe **内部** —— probe 是 toString() 序列化后在页面里执行的，
// 引用外部函数会 ReferenceError（踩过）。
function probe(sels, bodyAttr, stripPrefix) {
  let hits = 0;
  let prefixedTotal = 0;
  let prefixedHits = 0;
  let strippedTotal = 0;
  let strippedHits = 0;
  let hostDepTotal = 0;
  let hostDepHits = 0;
  const byKind = {};
  const byKindHit = {};
  const hostMissed = [];
  const skinOwn = [];
  const missed = [];
  let skinOwnTotal = 0;
  let skinOwnHits = 0;

  /**
   * 判断（已剥掉皮肤作用域属性后的）选择器**是否真的依赖宿主**。
   *
   * 这是最后一次方法论修正：皮肤 CSS 里大量规则是
   * `body[data-dsh-orca-link] .lightScene {...}` —— 剥掉前缀后是 `.lightScene`，
   * 那是**皮肤自己的 JS 创建的舞台节点**，跟宿主挂不挂钩子毫无关系。
   * 把它们算进分母，覆盖率会被无意义地拉到个位数。
   */
  function hasHostHook(t) {
    const hostData = /\[data-(?!skin|orca|maid|furina|mc|liang|endfield|aionui|dsh)/.test(t);
    const semantic = /\[(?:role|aria-)/.test(t);
    const classFrag = /\[class[*^$~|]?=/.test(t);
    const bareTag = /(^|[\s>+~])(button|input|textarea|select|a|svg|canvas|img|table|ul|ol|li|p|h[1-6])(?![-\w])/.test(t);
    if (hostData || semantic) return 'host-attr';
    if (classFrag) return 'host-class';
    if (bareTag) return 'host-semantic-tag';
    return null;
  }
  for (const s of sels) {
    let n = 0;
    let err = false;
    try {
      n = document.querySelectorAll(s).length;
    } catch {
      err = true;
      n = 0;
    }
    if (n > 0) hits++;
    else missed.push(s);

    const isPrefixed = bodyAttr && s.includes(bodyAttr);
    if (!isPrefixed) continue;
    prefixedTotal++;
    if (n > 0) prefixedHits++;

    // 剥掉 `body[data-dsh-xxx]` 前缀，只留"我们要提供的钩子"部分
    let stripped = s
      .replace(new RegExp('body\\[' + bodyAttr.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\]', 'g'), '')
      .trim();
    if (!stripped || stripped === '*') continue;
    stripped = stripped.replace(/^[>+~]\s*/, '').trim();
    if (!stripped) continue;
    strippedTotal++;
    let strippedHit;
    try {
      strippedHit = document.querySelectorAll(stripped).length > 0;
    } catch {
      strippedHit = false;
    }
    if (strippedHit) strippedHits++;

    // 关键：只把"真的依赖宿主"的选择器算进宿主覆盖率
    const kind = hasHostHook(stripped);
    if (kind) {
      hostDepTotal++;
      byKind[kind] = (byKind[kind] || 0) + 1;
      if (strippedHit) {
        hostDepHits++;
        byKindHit[kind] = (byKindHit[kind] || 0) + 1;
      } else if (hostMissed.length < 90) {
        hostMissed.push(`[${kind}] ${stripped}`);
      }
    } else {
      // 皮肤自有节点（含产物里的哈希类名 `._0cMdVG_lightScene`）：
      // 现在用的是 bundle 内嵌 CSS，所以这里能真正反映"皮肤有没有把它的节点建出来"。
      skinOwnTotal++;
      if (strippedHit) skinOwnHits++;
      else if (skinOwn.length < 25) skinOwn.push(stripped);
    }
  }
  return {
    total: sels.length,
    hits,
    pct: sels.length ? +((hits / sels.length) * 100).toFixed(1) : 0,
    prefixedTotal,
    prefixedHits,
    prefixedPct: prefixedTotal ? +((prefixedHits / prefixedTotal) * 100).toFixed(1) : 0,
    strippedTotal,
    strippedHits,
    strippedPct: strippedTotal ? +((strippedHits / strippedTotal) * 100).toFixed(1) : 0,
    /** >>> 这才是"宿主薄契约够不够"的指标 <<< */
    hostDepTotal,
    hostDepHits,
    hostDepPct: hostDepTotal ? +((hostDepHits / hostDepTotal) * 100).toFixed(1) : 0,
    byKind,
    byKindHit,
    hostMissed,
    /** 皮肤自有节点（哈希类名）：衡量"皮肤有没有把自己的节点建出来" */
    skinOwnTotal,
    skinOwnHits,
    skinOwnPct: skinOwnTotal ? +((skinOwnHits / skinOwnTotal) * 100).toFixed(1) : 0,
    skinOwnMissed: skinOwn.slice(0, 25),
    missed: missed.slice(0, 120),
  };
}

function injectCss(list) {
  let added = 0;
  for (const c of list) {
    const el = document.createElement('style');
    el.dataset.apSkinCss = c.rel;
    el.textContent = c.text;
    document.head.appendChild(el);
    added++;
  }
  return { added };
}

/**
 * 让皮肤在页面里真正跑起来 —— **调用平台自己的入口**，不再自己实现一套。
 *
 * 历史教训（重要）：这里原来自己 fetch bundle、自己 new 一个 mock ctx，而那个 mock
 * 与真实实现逐渐分叉 —— 它的 effect 同样把 fn 当 disposer 存、从不调用 fn。于是台架报
 * "applied=true / disposers=2"，而事实上皮肤的注册逻辑从未执行。台架给出的结论
 * 全是假的，还反过来误导了对真实 bug 的判断。
 *
 * 现在改成调 `window.__apEnableSkin`（即平台的 `enableSkin`）：台架和用户点「运行 JS」
 * 走**同一条路径**，从根上消除"台架与生产不一致"。
 */
async function loadSkin(skinId) {
  const out = { loaded: false, applied: false, errors: [] };
  const readLog = () => (typeof window.__apSkinRuntimeLog === 'function' ? window.__apSkinRuntimeLog() : []);
  try {
    if (typeof window.__apEnableSkin !== 'function') {
      out.errors.push('页面没有暴露 __apEnableSkin，台架无法验证真实路径');
      return out;
    }
    // 先卸干净，否则第二次跑会命中"已加载"直接返回，测的就不是加载过程了
    if (typeof window.__apDisableSkin === 'function') {
      try {
        await window.__apDisableSkin(skinId);
      } catch (e) {
        out.errors.push('pre-disable: ' + (e && e.message ? e.message : String(e)));
      }
    }
    // 对照：**加载皮肤之前**输入卡片的计算样式。与加载后对比即可分清
    // "宿主没让位" 和 "皮肤用 CSS 盖住了宿主"。
    out.composerBeforeSkin = (function () {
      var e = document.querySelector('[data-composer-card]');
      if (!e) return 'no-card';
      var cs = getComputedStyle(e);
      return 'bg=' + cs.backgroundColor + ' | border=' + cs.borderTopColor + ' | shadow=' + (cs.boxShadow || 'none');
    })();
    await window.__apEnableSkin(skinId);
    // 等两件事，再量样式：
    //   ① React 把 useSkinActive 的状态提交上去（enableSkin 的 await 结束 ≠ 已重渲染）；
    //   ② 输入卡片的 CSS 过渡跑完 —— 卡片上有 transition: border-color .15s, box-shadow .15s，
    //      在过渡期间 getComputedStyle 返回的是**中间插值**。
    //   （background 没有过渡，会瞬间变化；只有 border/shadow 会"看起来还没让位"。）
    await new Promise(function (r) {
      setTimeout(r, 900);
    });
    const st = typeof window.__apSkinState === 'function' ? window.__apSkinState() : null;
    // 带回原始快照：判定为 false 时能立刻看出是"真没加载"还是"字段对不上"，
    // 而不是像以前那样靠猜
    out.stateRaw = JSON.stringify(st);
    out.loaded = !!st && (st.loaded || []).indexOf(skinId) >= 0;
    out.applied = out.loaded;
    out.registeredId = st ? st.enabled : null;
    out.entry = 'real-runtime (__apEnableSkin)';
    // 真实运行时的日志就是"皮肤加载过程"的唯一真相，带回去给报告
    const lines = readLog();
    out.runtimeLog = lines.slice(-20);
    out.runtimeLogErrors = lines.filter(function (l) {
      return /抛错|失败|error|missing|ignored/i.test(String(l));
    }).slice(-6);

    /*
     * 卸载验证：这是"disposer 到底有没有真的执行"的唯一证据。
     * 之前 ctx.effect 把 fn 当 disposer 存、从不调用，表现为"卸载成功但东西还在"。
     */
    const attrsOf = () =>
      Array.prototype.map
        .call(document.documentElement.attributes, function (a) {
          return a.name + '=' + a.value;
        })
        .filter(function (s) {
          return s.indexOf('data-dsh-') === 0 || s.indexOf('data-skin') === 0;
        })
        .join(' | ');
    out.attrsAfterLoad = attrsOf();
    out.skinNodesAfterLoad = document.querySelectorAll('[data-skin-chrome]').length;
    // 皮肤活跃时输入卡片应当已经"让位"：背景/边框透明、无阴影。
    // 否则宿主的内联样式会压住皮肤设的值，叠出用户看到的那层内白框。
    out.skinActiveAttr = document.documentElement.hasAttribute('data-ap-skin-active');
    out.composerWhileActive = (function () {
      var e = document.querySelector('[data-composer-card]');
      if (!e) return 'no-card';
      var cs = getComputedStyle(e);
      return 'bg=' + cs.backgroundColor + ' | border=' + cs.borderTopColor + ' | shadow=' + (cs.boxShadow || 'none');
    })();
    // 从**同一个元素实例**把该看的都取出来：否则分不清
    // "宿主没让位"、"皮肤用 CSS 盖住了宿主"、"我量的根本不是同一个元素"。
    out.composerCardDump = (function () {
      var e = document.querySelector('[data-composer-card]');
      if (!e) return 'no-card';
      var cs = getComputedStyle(e);
      return [
        'inline=' + String(e.getAttribute('style') || ''),
        'style=' + cs.borderTopStyle + ' width=' + cs.borderTopWidth + ' color=' + cs.borderTopColor,
        'radius=' + cs.borderTopLeftRadius,
        'shadow=' + (cs.boxShadow || 'none'),
        'attrs=' + [].map.call(e.attributes, function (a) { return a.name; }).join(','),
        'tag=' + e.tagName + '.' + String(e.className || '').slice(0, 60),
      ].join(' | ');
    })();
    // 内容容器（antd Card/Table）是否让位：皮肤在跑时应当是**半透明**而不是纯白，
    // 否则皮肤的画会被大片白卡片盖住。
    out.containerBgWhileActive = (function () {
      var e = document.querySelector('.ant-card') || document.querySelector('.ant-table');
      return e ? getComputedStyle(e).backgroundColor : 'no-container';
    })();

    // 设置面板：**真的点开**再量定位与内容，验证"盖住侧栏、不被压住、能读到皮肤设置"。
    var settingsTrigger = document.querySelector("[data-slot='settings.trigger']");
    if (!settingsTrigger) {
      out.settingsPanel = 'no-trigger';
    } else {
      settingsTrigger.click();
      // React 的状态提交是异步的，点完立刻查会查不到（曾因此误报 not-opened）
      await new Promise(function (r) {
        setTimeout(r, 400);
      });
      out.settingsPanel = (function () {
        var p = document.querySelector("[data-slot='sidebar.settings']");
        if (!p) return 'not-opened';
        var cs = getComputedStyle(p);
        var rect = p.getBoundingClientRect();
        return (
          cs.position + ' z=' + cs.zIndex + ' w=' + Math.round(rect.width) + ' h=' + Math.round(rect.height) +
          ' bg=' + cs.backgroundColor +
          ' text=' + String(p.textContent || '').replace(/\s+/g, ' ').slice(0, 70)
        );
      })();
    }

    if (typeof window.__apDisableSkin === 'function') {
      await window.__apDisableSkin(skinId);
      out.attrsAfterUnload = attrsOf();
      out.skinNodesAfterUnload = document.querySelectorAll('[data-skin-chrome]').length;
      const st2 = typeof window.__apSkinState === 'function' ? window.__apSkinState() : null;
      out.stateAfterUnload = JSON.stringify(st2);
      out.unloadCleanedAttrs = out.attrsAfterUnload === '';
      out.unloadCleanedNodes = out.skinNodesAfterUnload === 0;
    }
  } catch (e) {
    out.errors.push('enableSkin: ' + (e && e.message ? e.message : String(e)));
  }
  return out;
}
