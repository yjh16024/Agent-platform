// Agent Platform 桌面壳（v2）
// - 先创建启动窗口（即时反馈）→ spawn 内置后端（embedded/H2）→ 健康就绪后跳转首页；
// - 单实例锁；退出/窗口关闭回收后端子进程；日志写入 userData/app.log 便于排查。
'use strict';

const { app, BrowserWindow } = require('electron');
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const path = require('path');
const fs = require('fs');

const SMOKE = process.env.AP_DESKTOP_SMOKE === '1';
let backend = null;
let win = null;
let bootLogFile = null;

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
  return path.join(__dirname, '..', 'agent-platform-core', 'target', 'agent-platform-core-1.0.0-SNAPSHOT.jar');
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
        else setTimeout(probe, 1500);
      });
      req.setTimeout(3000, () => req.destroy());
    };
    probe();
  });
}

function loadingHtml(message) {
  return `data:text/html;charset=utf-8,${encodeURIComponent(`<!doctype html><html><head><meta charset="utf-8">
    <style>body{font-family:system-ui,'Segoe UI',sans-serif;background:#f7f8fa;margin:0;display:flex;
      height:100vh;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#333}
      .logo{font-size:26px;font-weight:600;color:#2563eb}.tip{font-size:14px;color:#888}</style></head>
    <body><div class="logo">Agent Platform</div><div class="tip">${message}</div></body></html>`)}`;
}

async function startBackend() {
  const port = await freePort(8081);
  const exe = javaExe();
  const jar = backendJar();
  if (!fs.existsSync(exe)) throw new Error(`runtime java not found: ${exe}`);
  if (!fs.existsSync(jar)) throw new Error(`backend jar not found: ${jar}`);
  logLine(`starting backend: ${exe} ... jar=${jar} port=${port} cwd=${dataDir()}`);
  backend = spawn(exe, [
    '--enable-preview', '-Xms128m', '-Xmx1g',
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
    bootLogFile = path.join(app.getPath('userData'), 'app.log');
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
      webPreferences: { contextIsolation: true, nodeIntegration: false },
    });
    await win.loadURL(loadingHtml('正在启动本地服务…（约 10–25 秒）'));
    try {
      const port = await startBackend();
      logLine(`backend ready on ${port}; loading UI`);
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
