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
let backend = null;
let win = null;
let bootLogFile = null;

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

function backendJar() {
  if (app.isPackaged) {
    const jar = path.join(process.resourcesPath, 'backend', 'app.jar');
    if (fs.existsSync(jar)) return jar;
  }
  return path.join(__dirname, '..', 'agent-platform-core', 'target', 'agent-platform-core-1.1.0.jar');
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

async function startBackend() {
  const port = await freePort(8081);
  const exe = javaExe();
  const jar = backendJar();
  if (!fs.existsSync(exe)) throw new Error(`runtime java not found: ${exe}`);
  if (!fs.existsSync(jar)) throw new Error(`backend jar not found: ${jar}`);
  logLine(`starting backend: ${exe} ... jar=${jar} port=${port} cwd=${dataDir()}`);
  // 启动优化（桌面为单用户场景，以「启动快」优先、峰值吞吐让位）：
  //  - TieredStopAtLevel=1：即时编译只到 C1、跳过 C2，启动阶段显著更快；
  //  - UseSerialGC：小堆用串行 GC，省掉并行 GC 线程的初始化开销；
  //  - lazy-initialization：bean 延迟初始化，缩短启动阻塞（代价：某功能首次点击时才初始化）。
  backend = spawn(exe, [
    '--enable-preview',
    '-Xms128m', '-Xmx1g',
    '-XX:TieredStopAtLevel=1',
    '-XX:+UseSerialGC',
    '-Dspring.main.lazy-initialization=true',
    '-Djava.awt.headless=true',
    '-jar', jar,
    '--spring.profiles.active=embedded',
    `--server.port=${port}`,
  ], { cwd: dataDir(), stdio: ['ignore', 'pipe', 'pipe'] });
  backend.stdout.on('data', (d) => logLine(`[backend] ${String(d).trim()}`));
  backend.stderr.on('data', (d) => logLine(`[backend-err] ${String(d).trim()}`));
  backend.on('exit', (code) => {
    logLine(`backend exited with code ${code}`);
    if (win && !win.isDestroyed()) win.destroy();
    if (!app.isQuitting) app.quit();
  });
  await waitHttp(port, 120000);
  return port;
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
      const isDevTools =
        input.type === 'keyDown' &&
        (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i'));
      if (isDevTools) {
        win.webContents.toggleDevTools();
        event.preventDefault();
      }
    });

    await win.loadURL(loadingHtml('正在启动本地服务…（约 10–25 秒）'));
    try {
      const port = await startBackend();
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
