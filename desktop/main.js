// Agent Platform 桌面壳（v2）
// - 先创建启动窗口（即时反馈）→ spawn 内置后端（embedded/H2）→ 健康就绪后跳转首页；
// - 单实例锁；退出/窗口关闭回收后端子进程；日志写入 userData/app.log 便于排查。
'use strict';

const { app, BrowserWindow, ipcMain, nativeTheme, Menu } = require('electron');
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const path = require('path');
const fs = require('fs');

const SMOKE = process.env.AP_DESKTOP_SMOKE === '1';

/*
 * ---- 开发模式（默认关闭；不设环境变量时行为与以前完全一致）----
 *
 * 痛点：改一行代码就要跑 desktop\build.bat（jlink + mvn package + electron-builder）再重启 exe，
 * 一轮几分钟。开发模式把这条链路拆开：
 *
 *   AP_DEV=1          开发模式总开关
 *   AP_DEV_JAR        用外部 jar（不设则取仓库 agent-platform-core/target 下的 jar）
 *                     → 改完后端只需 mvn package，不必重打桌面包
 *   AP_DEV_STATIC     把后端静态资源指到源码目录（不设则取仓库 .../src/main/resources/static）
 *                     → 前端 npm run build:prod 后刷新页面即生效，**后端完全不用重启**
 *   AP_REUSE_BACKEND  若起始端口上已有就绪的后端（例如自己 spring-boot:run 的），直接复用、不再 spawn
 *
 * 快捷键（仅开发模式）：Ctrl+R 刷新页面（菜单栏被置空后默认快捷键也失效了，这里补回）；
 *                      Ctrl+Shift+R 热重启后端（窗口不退出）
 *
 * 一键入口：desktop\dev.bat
 */
const DEV = process.env.AP_DEV === '1';
const REUSE_BACKEND = DEV && process.env.AP_REUSE_BACKEND === '1';
const START_PORT = 8081;

let backend = null;
let win = null;
let bootLogFile = null;
/** 热重启期间为 true：后端子进程退出属预期，不要跟着关窗口。 */
let restartingBackend = false;

// ---- 主题（换肤）----
// 网页侧换肤后经 preload 上报，这里落盘成 userData/theme.json；下次启动先读它再建窗口，
// 这样窗口背景与启动页从第一帧就是对的颜色（否则暗色皮肤下会先闪一下白底）。
let theme = { mode: 'light', background: '#f7f8fa' };

function themeFile() {
  return path.join(app.getPath('userData'), 'theme.json');
}

function readTheme() {
  try {
    const t = JSON.parse(fs.readFileSync(themeFile(), 'utf8'));
    theme = {
      mode: t.mode === 'dark' ? 'dark' : 'light',
      background: typeof t.background === 'string' && t.background.trim() ? t.background.trim() : '#f7f8fa',
    };
    logLine(`theme loaded: mode=${theme.mode} background=${theme.background}`);
  } catch {
    // 首次运行或文件损坏：用默认浅色
  }
}

function saveTheme(payload) {
  if (!payload || typeof payload !== 'object') {
    return;
  }
  const next = {
    mode: payload.mode === 'dark' ? 'dark' : 'light',
    background:
      typeof payload.background === 'string' && payload.background.trim() ? payload.background.trim() : theme.background,
  };
  theme = next;
  try {
    fs.writeFileSync(themeFile(), JSON.stringify(next));
  } catch (e) {
    logLine(`theme persist failed: ${e.message}`);
  }
  try {
    nativeTheme.themeSource = next.mode;
  } catch {
    /* ignore */
  }
  if (win && !win.isDestroyed()) {
    try {
      win.setBackgroundColor(next.background);
    } catch {
      /* ignore */
    }
  }
}

function logLine(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`;
  try {
    process.stdout.write(line + '\n');
    if (bootLogFile) fs.appendFileSync(bootLogFile, line + '\n');
  } catch { /* ignore */ }
}

function javaExe() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'runtime', 'bin', 'java.exe')
    : path.join(__dirname, 'runtime', 'bin', 'java.exe');
}

/** 仓库里 Maven 构建出的 core jar（开发模式用）。 */
function repoJar() {
  return path.join(__dirname, '..', 'agent-platform-core', 'target', 'agent-platform-core-1.1.0.jar');
}

function backendJar() {
  // 开发模式优先用外部 jar：改完后端只需 mvn package，不必重打桌面包
  if (DEV) {
    const devJar = process.env.AP_DEV_JAR || repoJar();
    if (fs.existsSync(devJar)) return devJar;
    logLine(`[dev] AP_DEV_JAR 不存在：${devJar}（回落到内置 jar）`);
  }
  if (app.isPackaged) {
    const jar = path.join(process.resourcesPath, 'backend', 'app.jar');
    if (fs.existsSync(jar)) return jar;
  }
  return repoJar();
}

/**
 * 开发模式下的静态资源目录（前端产物），转成 Spring 能识别的 {@code file:} URL。
 * <p>设置后 Spring 会用它<b>完全替换</b>默认的 {@code classpath:/static/}，
 * 于是前端产物改完只需刷新页面 —— 后端进程都不用重启。</p>
 */
function devStaticLocation() {
  if (!DEV) {
    return null;
  }
  const dir = process.env.AP_DEV_STATIC
    || path.join(__dirname, '..', 'agent-platform-core', 'src', 'main', 'resources', 'static');
  if (!fs.existsSync(dir)) {
    logLine(`[dev] AP_DEV_STATIC 不存在：${dir}（回落到 jar 内静态资源）`);
    return null;
  }
  if (!fs.existsSync(path.join(dir, 'index.html'))) {
    logLine(`[dev] 警告：${dir} 下没有 index.html —— 请先执行 cd agent-platform-ui && npm run build:prod`);
  }
  // file: URL 用正斜杠更稳，末尾补斜杠表示目录
  return 'file:' + dir.replace(/\\/g, '/').replace(/\/+$/, '') + '/';
}

function dataDir() {
  const dir = path.join(app.getPath('userData'), 'data');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function freePort(startPort) {
  return new Promise((resolve, reject) => {
    const probe = (port) => {
      const srv = net.createServer();
      srv.once('error', () => { if (port < 65535) probe(port + 1); else reject(new Error('no free port')); });
      srv.listen(port, '127.0.0.1', () => { srv.close(() => resolve(port)); });
    };
    probe(startPort);
  });
}

function waitHttp(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const probe = () => {
      const req = http.get(`http://127.0.0.1:${port}/actuator/health`, (res) => {
        res.resume();
        resolve(true); // 任何 HTTP 应答均视为后端就绪（Redis 缺失时可能 503）
      });
      req.on('error', () => {
        if (Date.now() > deadline) reject(new Error(`backend not ready within ${timeoutMs}ms`));
        // 轮询间隔 300ms（原 1500ms）：后端一旦就绪就尽快切到真实页面，平均可省 ~0.7s。
        else setTimeout(probe, 300);
      });
      req.setTimeout(3000, () => req.destroy());
    };
    probe();
  });
}

function loadingHtml(message) {
  // 启动页配色跟随已保存的主题，避免暗色皮肤下先闪一下白底
  const dark = theme.mode === 'dark';
  const bg = theme.background || (dark ? '#141414' : '#f7f8fa');
  const fg = dark ? '#e6e6e6' : '#333';
  const tip = dark ? '#9aa0a6' : '#888';
  const brand = dark ? '#4d91ff' : '#2563eb';
  return `data:text/html;charset=utf-8,${encodeURIComponent(`<!doctype html><html><head><meta charset="utf-8">
    <style>body{font-family:system-ui,'Segoe UI',sans-serif;background:${bg};margin:0;display:flex;
      height:100vh;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:${fg}}
      .logo{font-size:26px;font-weight:600;color:${brand}}.tip{font-size:14px;color:${tip}}</style></head>
    <body><div class="logo">Agent Platform</div><div class="tip">${message}</div></body></html>`)}`;
}

/**
 * 开发模式：若起始端口上已经有一个就绪的后端（例如自己跑的 {@code mvn spring-boot:run}），
 * 直接复用它、不再 spawn。这样关掉桌面版不会连带关掉用户的后端，后端重启也不必重启窗口。
 * <p>要求 HTTP 有应答才复用，避免把别的程序占用的端口误当成本平台后端。</p>
 */
async function reuseExistingBackend() {
  if (!REUSE_BACKEND) {
    return null;
  }
  try {
    await waitHttp(START_PORT, 1500);
    logLine(`[dev] 复用已在运行的后端 (${START_PORT})`);
    return START_PORT;
  } catch {
    return null;
  }
}

async function startBackend() {
  const port = await freePort(START_PORT);
  const exe = javaExe();
  const jar = backendJar();
  if (!fs.existsSync(exe)) throw new Error(`runtime java not found: ${exe}`);
  if (!fs.existsSync(jar)) throw new Error(`backend jar not found: ${jar}`);
  logLine(`starting backend: ${exe} ... jar=${jar} port=${port} cwd=${dataDir()}`);
  // 启动优化（桌面为单用户场景，以「启动快」优先、峰值吞吐让位）：
  //  - TieredStopAtLevel=1：即时编译只到 C1、跳过 C2，启动阶段显著更快；
  //  - UseSerialGC：小堆用串行 GC，省掉并行 GC 线程的初始化开销；
  //  - lazy-initialization：bean 延迟初始化，缩短启动阻塞（代价：某功能首次点击时才初始化）。
  const args = [
    '--enable-preview',
    '-Xms128m', '-Xmx1g',
    '-XX:TieredStopAtLevel=1',
    '-XX:+UseSerialGC',
    '-Dspring.main.lazy-initialization=true',
    '-Djava.awt.headless=true',
    '-jar', jar,
    '--spring.profiles.active=embedded',
    `--server.port=${port}`,
  ];
  // 开发模式：静态资源指向源码目录 → build:prod 后刷新页面即生效，后端不用重启
  const staticLoc = devStaticLocation();
  if (staticLoc) {
    args.push(`--spring.web.resources.static-locations=${staticLoc}`);
    logLine(`[dev] static-locations=${staticLoc}`);
  }
  backend = spawn(exe, args, { cwd: dataDir(), stdio: ['ignore', 'pipe', 'pipe'] });
  backend.stdout.on('data', (d) => logLine(`[backend] ${String(d).trim()}`));
  backend.stderr.on('data', (d) => logLine(`[backend-err] ${String(d).trim()}`));
  backend.on('exit', (code) => {
    logLine(`backend exited with code ${code}`);
    if (restartingBackend) {
      return;   // 开发模式热重启：后端退出属预期，不要把窗口一起关掉
    }
    if (win && !win.isDestroyed()) win.destroy();
    if (!app.isQuitting) app.quit();
  });
  await waitHttp(port, 120000);
  return port;
}

/**
 * 开发模式：热重启后端（Ctrl+Shift+R）。窗口保持不关，重启完成后自动载入新端口。
 * <p>用途：改完后端代码 → {@code mvn -pl agent-platform-core -am package -DskipTests} → 按快捷键，
 * 不用退桌面版、也不用重跑 electron-builder。</p>
 */
async function restartBackend() {
  if (restartingBackend) {
    return;
  }
  if (!backend) {
    logLine('[dev] 当前后端不由本窗口管理（复用了外部后端），请自行重启它');
    return;
  }
  restartingBackend = true;
  logLine('[dev] 正在重启后端 ...');
  try {
    try { backend.kill(); } catch { /* ignore */ }
    backend = null;
    if (win && !win.isDestroyed()) {
      await win.loadURL(loadingHtml('正在重启本地服务…'));
    }
    // 给系统一点时间回收端口与文件句柄（Windows 上 jar 句柄释放稍慢）
    await new Promise((r) => setTimeout(r, 800));
    const port = await startBackend();
    if (win && !win.isDestroyed()) {
      try { await win.webContents.session.clearCache(); } catch { /* ignore */ }
      await win.loadURL(`http://127.0.0.1:${port}`);
    }
    logLine(`[dev] 后端已重启于 ${port}`);
  } catch (e) {
    logLine(`[dev] 重启失败：${e.message}`);
    if (win && !win.isDestroyed()) {
      await win.loadURL(loadingHtml(`重启失败：${e.message}`));
    }
  } finally {
    restartingBackend = false;
  }
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  // 换肤上报：渲染层调 window.apTheme.set(...) → 落盘 + 更新窗口背景 + 原生明暗
  ipcMain.on('ap:theme', (_event, payload) => saveTheme(payload));

  app.on('second-instance', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
  });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => {
    app.isQuitting = true;
    if (backend) { try { backend.kill(); } catch { /* ignore */ } }
  });
  process.on('exit', () => { if (backend) { try { backend.kill(); } catch { /* ignore */ } } });

  app.whenReady().then(async () => {
    /*
     * 去掉 Electron 默认菜单栏（File / Edit / View / Window / Help）。
     *
     * 理由：这是一个产品级界面，原生菜单（尤其是全英文的 Edit/View）对使用者没有意义，
     * 还横在窗口顶部占掉一条、挡住皮肤的完整覆盖。
     *
     * 代价与补偿：菜单自带的默认快捷键（Ctrl+R 刷新、Ctrl+Shift+I 开发者工具…）会一并失效。
     * 刷新平台自己有入口；**F12 开发者工具单独保留**（见创建窗口处的 before-input-event），
     * 否则以后排查页面问题只能去改代码，代价太大。
     */
    Menu.setApplicationMenu(null);
    bootLogFile = path.join(app.getPath('userData'), 'app.log');
    // 先读主题：窗口背景与启动页都依赖它，必须在建窗口之前
    readTheme();
    logLine('app ready; creating window (instant feedback)...');

    if (SMOKE) {
      try {
        const port = await startBackend();
        logLine(`[smoke] backend ready on ${port}, OK`);
        app.exit(0);
      } catch (e) {
        logLine(`[smoke] FAILED: ${e.message}`);
        app.exit(1);
      }
      return;
    }

    // 先出启动页，再后台拉起后端
    win = new BrowserWindow({
      width: 1360, height: 900, title: 'Agent Platform',
      // 跟随已保存的主题：否则加载瞬间是白底，暗色皮肤下会闪一下
      backgroundColor: theme.background,
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        // 换肤上报通道；preload.js 必须打进包，见 electron-builder.yml 的 files
        preload: path.join(__dirname, 'preload.js'),
      },
    });
    /*
     * 菜单栏被置空后，菜单里的默认快捷键也一起没了。这里只把**开发者工具**接回来：
     * 没有它，以后排查前端问题只能改代码再加日志。F12 / Ctrl+Shift+I 都能开。
     * 其余快捷键（刷新、缩放等）平台自己有入口或系统层面可用，不额外补。
     */
    win.webContents.on('before-input-event', (event, input) => {
      const key = String(input.key || '').toLowerCase();
      const isDevTools =
        input.type === 'keyDown' &&
        (input.key === 'F12' || (input.control && input.shift && key === 'i'));
      if (isDevTools) {
        win.webContents.toggleDevTools();
        event.preventDefault();
        return;
      }
      if (!DEV || input.type !== 'keyDown' || !input.control) {
        return;
      }
      // Ctrl+Shift+R：热重启后端（窗口不退出）
      if (input.shift && key === 'r') {
        restartBackend();
        event.preventDefault();
        return;
      }
      // Ctrl+R：刷新页面。菜单栏被置空后连默认刷新快捷键都没了，开发时每次手动点很烦，这里补回。
      if (key === 'r') {
        win.webContents.reload();
        event.preventDefault();
      }
    });

    await win.loadURL(loadingHtml('正在启动本地服务…（约 10–25 秒）'));
    try {
      // 开发模式：若起始端口上已有就绪的后端（如自己 spring-boot:run 的），直接复用
      const port = (await reuseExistingBackend()) ?? await startBackend();
      logLine(`backend ready on ${port}; loading UI`);
      // 清掉 Electron 自身的 HTTP 缓存：否则升级后仍可能复用上一次的前端产物
      // （表现为「后端已更新，但桌面版工作流画布依旧整页空白」）。
      try {
        await win.webContents.session.clearCache();
        logLine('cleared electron http cache');
      } catch (e) {
        logLine(`clearCache failed: ${e.message}`);
      }
      await win.loadURL(`http://127.0.0.1:${port}`);
      logLine('ui loaded');
    } catch (e) {
      logLine(`fatal: ${e.message}`);
      if (win && !win.isDestroyed()) {
        await win.loadURL(loadingHtml(`启动失败：${e.message}<br>请查看用户数据目录下 app.log`));
      }
    }
  });
}
