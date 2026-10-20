'use strict';

/**
 * 插件 Node 侧运行时（**utilityProcess 子进程**，2026-10-19）。
 *
 * <h3>为什么要单独进程</h3>
 * 插件 `main.js` 是**长期运行的第三方代码**（QQ 机器人、爬虫、定时任务）。
 * 之前它用 `new Function` 跑在 Electron 主进程里 —— 一个 `while(true)`、一次未捕获的
 * 异步异常、一处原生模块崩溃，就能把整个应用卡死或拖崩。搬到 `utilityProcess` 之后：
 * 插件崩了只影响它自己，主进程收到 `exit` 可以决定重启或标记不可用。
 *
 * <h3>为什么这里几乎没有"能力桥"</h3>
 * 子进程本身就是完整 Node 环境 —— `fs` / `fetch` / `child_process` / 定时器全都能直接用，
 * 不需要像渲染层那样一个个桥过来（那会把"全开"变成"每加一个能力改一次主进程"）。
 * **唯一需要桥的是 `platform.request`**：调平台接口要登录 token，而 token 在主进程手里。
 *
 * <h3>通信协议</h3>
 * <pre>
 *   主进程 → {@code { type:'load'|'invoke'|'stop'|'ping', id, ... }} → 子进程
 *   子进程 → {@code { type:'reply', id, ok, data?, error? }}        → 主进程
 *   子进程 → {@code { type:'host-call', id, method, args }}          → 主进程执行后回 host-reply
 *   子进程 → {@code { type:'log', level, args }}                     → 主进程写日志
 * </pre>
 */

const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

/** 当前加载的插件（一个子进程只跑一个插件 —— 隔离彻底，崩溃不牵连别人）。 */
let current = null;
/** 等待主进程回包的 platform 调用：id → resolve */
const pendingHostCalls = new Map();
let hostSeq = 0;

// ------------------------------------------------------------------ 与主进程通信

function post(payload) {
  try {
    process.parentPort.postMessage(payload);
  } catch {
    // 主进程已退出：静默即可，子进程随后也会被回收
  }
}

function log(level, ...args) {
  post({
    type: 'log',
    level,
    args: args.map((a) => {
      if (typeof a === 'string') return a;
      try {
        return JSON.stringify(a);
      } catch {
        return String(a);
      }
    }),
  });
}

/** 调主进程的能力（目前只有 platform.request）。 */
function callHost(method, args) {
  return new Promise((resolve, reject) => {
    const id = ++hostSeq;
    const timer = setTimeout(() => {
      pendingHostCalls.delete(id);
      reject(new Error(`platform 调用超时（${method}）`));
    }, 60_000);
    pendingHostCalls.set(id, (msg) => {
      clearTimeout(timer);
      pendingHostCalls.delete(id);
      if (msg.ok) {
        resolve(msg.data);
      } else {
        reject(new Error(msg.error || 'platform 调用失败'));
      }
    });
    post({ type: 'host-call', id, method, args });
  });
}

// ------------------------------------------------------------------ 插件生命周期

async function doLoad(msg) {
  const pluginId = msg.pluginId;
  const dir = msg.dir;
  if (!dir) {
    return { ok: false, error: '插件目录为空' };
  }
  const entry = path.join(dir, msg.mainFile || 'main.js');
  if (!fs.existsSync(entry)) {
    return { ok: false, error: '插件没有 main.js' };
  }

  const code = fs.readFileSync(entry, 'utf8');
  const module = { exports: {} };
  // 从**插件目录**解析依赖：插件自带的 node_modules 能直接用
  const req = createRequire(entry);
  // eslint-disable-next-line no-new-func
  const fn = new Function('exports', 'require', 'module', '__dirname', '__filename', 'console', code);
  fn(module.exports, req, module, dir, entry, console);

  const mod = module.exports && (module.exports.default || module.exports);

  const api = {
    pluginId,
    /** 插件私有数据目录（主进程已建好）；插件可在这里读写自己的文件/SQLite。 */
    dataDir: msg.dataDir,
    logger: {
      info: (...a) => log('info', ...a),
      warn: (...a) => log('warn', ...a),
      error: (...a) => log('error', ...a),
    },
    /**
     * 调平台接口（**唯一需要主进程代发的能力**：它要登录 token）。
     * 只允许 `/api/` 开头的路径 —— 与前端 `ctx.http` 同一条边界。
     */
    platform: {
      request: (cfg) => callHost('platform.request', cfg),
    },
  };

  if (mod && typeof mod.start === 'function') {
    await mod.start(api);
  }
  current = { pluginId, module: mod };
  log('info', `插件 ${pluginId} 的 Node 侧已启动（pid ${process.pid}）`);
  return { ok: true };
}

async function doInvoke(msg) {
  const fn = current && current.module && current.module.methods && current.module.methods[msg.method];
  if (typeof fn !== 'function') {
    return { ok: false, error: `插件没有导出方法：${msg.method}` };
  }
  const data = await fn.call(current.module, msg.args);
  return { ok: true, data };
}

async function doStop() {
  try {
    if (current && current.module && typeof current.module.stop === 'function') {
      await current.module.stop();
    }
  } catch (e) {
    log('warn', `stop 失败：${e && e.message}`);
  }
  current = null;
  return { ok: true };
}

// ------------------------------------------------------------------ 消息分发

process.parentPort.on('message', (event) => {
  const msg = event && event.data;
  if (!msg || !msg.type) {
    return;
  }

  // 主进程对 host-call 的回复
  if (msg.type === 'host-reply') {
    const cb = pendingHostCalls.get(msg.id);
    if (cb) {
      cb(msg);
    }
    return;
  }

  if (msg.type === 'ping') {
    post({ type: 'reply', id: msg.id, ok: true, data: { pid: process.pid } });
    return;
  }

  if (msg.type !== 'load' && msg.type !== 'invoke' && msg.type !== 'stop') {
    return;
  }

  const handler = msg.type === 'load' ? doLoad : msg.type === 'invoke' ? doInvoke : doStop;
  Promise.resolve()
    .then(() => handler(msg))
    .then((res) => post({ type: 'reply', id: msg.id, ...res }))
    .catch((e) => {
      log('error', `${msg.type} 失败：${(e && e.stack) || e}`);
      post({ type: 'reply', id: msg.id, ok: false, error: (e && e.message) || String(e) });
    });
});

// 未捕获异常：记日志但**不让进程静默退出**（主进程会收到 exit 并决定是否重启）
process.on('uncaughtException', (e) => {
  log('error', `未捕获异常：${(e && e.stack) || e}`);
});
process.on('unhandledRejection', (e) => {
  log('error', `未处理的 Promise 拒绝：${(e && e.stack) || e}`);
});
