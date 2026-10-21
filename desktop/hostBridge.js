'use strict';

/**
 * 插件 Host 桥 —— Electron 主进程侧（2026-10-19，P4）。
 *
 * <h3>为什么 Host 能力放在这里，而不是 Java 后端</h3>
 * 平台只跑在本地（Electron 绿色版），而**主进程本来就是一个完整 Node 运行时**：
 * fs / child_process / net / 定时器 / 任意 npm 包全都有。把"本机能力"放这里，
 * 能力面与 DSH 的 Host 半侧对齐，且**没有版本漂移**（不依赖任何外部宿主的 API）。
 * Java 后端继续只做"平台业务"（会话 / 模型 / 工具链 / 插件工具）。
 *
 * <h3>能力与授权（用户定的边界：全开 + 首次授权）</h3>
 * 四类能力：`fs`（任意路径读写）/ `http`（任意出站）/ `exec`（子进程）/ `storage`（插件私有数据）。
 * **首次调用某类能力**返回 `{ needAuth }`，renderer 弹一次确认；用户允许后写进
 * `userData/host-authorizations.json`，之后放行。授权可按插件、按能力撤销。
 *
 * <h3>★ 这里拦的不是"恶意"，而是"炸平台"</h3>
 * 本地单机没有外部攻击者（部署者 = 使用者），所以不做沙箱、不限路径。
 * 但仍加三道**故障兜底**，它们都只防事故、不影响能力：
 * <ol>
 *   <li>exec 超时 + 输出上限：防止一个 `npm install` 或死循环把主进程内存吃光；</li>
 *   <li>http 超时 + 响应上限：防止下载一个 2GB 文件；</li>
 *   <li>审计日志：`fs.write` / `exec.run` / `http.request` 记一行 JSONL —— 出事后能回答
 *       "刚才到底是谁动了什么"，这是本地平台最实用的一件安全设施。</li>
 * </ol>
 */

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createRequire } = require('node:module');

/** 可由插件申请的能力。 */
const CAPABILITIES = ['fs', 'http', 'exec', 'storage'];

/** 插件 id 只允许安全字符：它同时是数据目录名，不能带路径分隔符。 */
const SAFE_PLUGIN_ID = /^[A-Za-z0-9._-]+$/;

const DEFAULT_EXEC_TIMEOUT = 30_000;
const MAX_EXEC_OUTPUT = 1024 * 1024;        // 1MB
const DEFAULT_HTTP_TIMEOUT = 30_000;
const MAX_HTTP_BODY = 5 * 1024 * 1024;      // 5MB

/**
 * @param {object} opts
 * @param {string} opts.userDataDir  Electron 的 userData 目录
 * @param {(level: string, ...args: unknown[]) => void} [opts.log]
 */
function createHostBridge(opts) {
  const userDataDir = opts.userDataDir;
  const appVersion = opts.appVersion || null;
  /** Electron 的 utilityProcess（插件 Node 侧的隔离容器）；缺失时退化为进程内加载。 */
  const utilityProcess = opts.utilityProcess || null;
  /** pluginRuntime.js 的绝对路径（asar 内的路径也可以，Electron 能读）。 */
  const runtimeScript = opts.runtimeScript || null;
  /** 后端地址（随热重启换端口，所以传函数而不是字符串）。 */
  const getBaseUrl = opts.getBaseUrl || (() => null);
  const log = opts.log || ((...a) => console.log('[host]', ...a));

  const authFile = path.join(userDataDir, 'host-authorizations.json');
  const auditFile = path.join(userDataDir, 'host-audit.log');
  const dataRoot = path.join(userDataDir, 'plugin-data');

  /** { [pluginId]: { fs: true, exec: false, ... } } */
  const authorizations = readJson(authFile, {});
  /** pluginId → { module } */
  const loadedPlugins = new Map();

  // ---------------------------------------------------------------- 基础

  function readJson(file, fallback) {
    try {
      return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      return fallback;
    }
  }

  function writeJson(file, data) {
    const tmp = `${file}.tmp`;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf8');
    fs.renameSync(tmp, file);   // 原子替换：中途崩了也不会留下半个 json
  }

  function audit(entry) {
    try {
      fs.mkdirSync(path.dirname(auditFile), { recursive: true });
      fs.appendFileSync(auditFile, `${JSON.stringify({ ts: new Date().toISOString(), ...entry })}\n`, 'utf8');
    } catch {
      // 审计写不进去（磁盘满/权限）不该阻断能力本身
    }
  }

  function ensurePluginId(pluginId) {
    if (!pluginId || !SAFE_PLUGIN_ID.test(pluginId)) {
      throw new Error(`插件 id 非法：${pluginId}`);
    }
    return pluginId;
  }

  function dataDir(pluginId) {
    const dir = path.join(dataRoot, ensurePluginId(pluginId));
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  }

  // ---------------------------------------------------------------- 授权

  function isAuthorized(pluginId, capability) {
    return !!(authorizations[pluginId] && authorizations[pluginId][capability]);
  }

  function authorize(pluginId, capability) {
    ensurePluginId(pluginId);
    if (!CAPABILITIES.includes(capability)) {
      throw new Error(`未知能力：${capability}`);
    }
    authorizations[pluginId] = { ...(authorizations[pluginId] || {}), [capability]: true };
    writeJson(authFile, authorizations);
    audit({ type: 'authorize', pluginId, capability });
    return true;
  }

  function revoke(pluginId, capability) {
    ensurePluginId(pluginId);
    if (authorizations[pluginId]) {
      delete authorizations[pluginId][capability];
      writeJson(authFile, authorizations);
      audit({ type: 'revoke', pluginId, capability });
    }
    return true;
  }

  function status(pluginId) {
    ensurePluginId(pluginId);
    const own = authorizations[pluginId] || {};
    const out = {};
    for (const cap of CAPABILITIES) {
      out[cap] = !!own[cap];
    }
    return out;
  }

  // ---------------------------------------------------------------- 能力实现

  const fsApi = {
    async read(p) {
      return fsp.readFile(p, 'utf8');
    },
    async readBinary(p) {
      const buf = await fsp.readFile(p);
      return buf.toString('base64');
    },
    async write(p, content, encoding) {
      await fsp.mkdir(path.dirname(p), { recursive: true });
      if (encoding === 'base64') {
        await fsp.writeFile(p, Buffer.from(String(content), 'base64'));
      } else {
        await fsp.writeFile(p, String(content), 'utf8');
      }
      return { bytes: Buffer.byteLength(String(content)) };
    },
    async list(p) {
      const entries = await fsp.readdir(p, { withFileTypes: true });
      return entries.map((e) => ({ name: e.name, dir: e.isDirectory() }));
    },
    async exists(p) {
      try {
        await fsp.access(p);
        return true;
      } catch {
        return false;
      }
    },
    async stat(p) {
      const s = await fsp.stat(p);
      return { size: s.size, mtime: s.mtimeMs, dir: s.isDirectory() };
    },
    async mkdir(p) {
      await fsp.mkdir(p, { recursive: true });
      return true;
    },
    async remove(p) {
      await fsp.rm(p, { recursive: true, force: true });
      return true;
    },
  };

  const httpApi = {
    async request(cfg) {
      const { url, method = 'GET', headers = {}, body, timeoutMs } = cfg || {};
      if (!/^https?:\/\//i.test(String(url || ''))) {
        throw new Error('http.request 只支持 http/https 绝对地址');
      }
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs || DEFAULT_HTTP_TIMEOUT);
      try {
        const payload = body == null
          ? undefined
          : (typeof body === 'string' ? body : JSON.stringify(body));
        const res = await fetch(url, {
          method,
          headers,
          body: payload,
          signal: controller.signal,
        });
        const buf = Buffer.from(await res.arrayBuffer());
        if (buf.length > MAX_HTTP_BODY) {
          throw new Error(`响应过大（>${Math.round(MAX_HTTP_BODY / 1024 / 1024)}MB）`);
        }
        const text = buf.toString('utf8');
        let json = null;
        try {
          json = JSON.parse(text);
        } catch {
          // 非 JSON 响应：json 为 null，调用方看 text
        }
        return {
          status: res.status,
          ok: res.ok,
          headers: Object.fromEntries(res.headers.entries()),
          text,
          json,
        };
      } finally {
        clearTimeout(timer);
      }
    },
  };

  const execApi = {
    run(cfg) {
      const { cmd, args = [], cwd, timeoutMs, env } = cfg || {};
      if (!cmd) {
        return Promise.resolve({ code: -1, stdout: '', stderr: 'cmd 不能为空', killed: false });
      }
      return new Promise((resolve) => {
        let child;
        try {
          child = spawn(cmd, args, {
            cwd: cwd || undefined,
            env: { ...process.env, ...(env || {}) },
            // Windows 上直接 spawn 一个 .cmd / 内置命令需要 shell；本地平台接受这个便利
            shell: process.platform === 'win32',
            windowsHide: true,
          });
        } catch (e) {
          resolve({ code: -1, stdout: '', stderr: e.message, killed: false });
          return;
        }
        let stdout = '';
        let stderr = '';
        let killed = false;
        const timer = setTimeout(() => {
          killed = true;
          try { child.kill('SIGKILL'); } catch { /* 已退出 */ }
        }, timeoutMs || DEFAULT_EXEC_TIMEOUT);

        child.stdout.on('data', (d) => {
          if (stdout.length < MAX_EXEC_OUTPUT) stdout += d.toString();
        });
        child.stderr.on('data', (d) => {
          if (stderr.length < MAX_EXEC_OUTPUT) stderr += d.toString();
        });
        child.on('error', (e) => {
          clearTimeout(timer);
          resolve({ code: -1, stdout, stderr: `${stderr}\n${e.message}`, killed });
        });
        child.on('close', (code) => {
          clearTimeout(timer);
          resolve({ code, stdout, stderr, killed });
        });
      });
    },
  };

  /** 插件私有 KV：`plugin-data/<id>/storage.json`（同步读写，量小）。 */
  function storageFor(pluginId) {
    const file = path.join(dataDir(pluginId), 'storage.json');
    let cache = null;
    const load = () => {
      if (!cache) {
        cache = readJson(file, {});
      }
      return cache;
    };
    return {
      get(key) {
        return load()[key];
      },
      set(key, value) {
        load()[key] = value;
        writeJson(file, cache);
        return true;
      },
      remove(key) {
        delete load()[key];
        writeJson(file, cache);
        return true;
      },
      keys() {
        return Object.keys(load());
      },
      all() {
        return { ...load() };
      },
    };
  }

  /**
   * 运行时环境信息（2026-10-19，P3）：不需要授权 —— 都是"这台机器什么环境"的只读信息。
   * 插件用它做平台判断（比如"Windows 下用 \\ 分隔符"）、显示诊断信息、决定默认路径。
   */
  function env() {
    const os = require('node:os');
    return {
      platform: process.platform,
      arch: process.arch,
      node: process.version,
      electron: process.versions.electron || null,
      appVersion,
      cwd: process.cwd(),
      home: process.env.USERPROFILE || process.env.HOME || null,
      tmp: os.tmpdir(),
      eol: os.EOL,
      locale: process.env.LANG || process.env.LANGUAGE || null,
    };
  }

  /**
   * 确保插件的 npm 依赖可用（2026-10-19，P2）。
   *
   * 三种情况：
   *   ① 没有 `package.json` → 跳过（纯 JS 插件直接跑）
   *   ② 已有 `node_modules` → 直接用 —— **推荐做法：把依赖打进 zip**，
   *      这样不依赖用户机器上有没有 npm（Electron 绿色版**不带 npm**）
   *   ③ 有 `package.json` 但没有 `node_modules` → 尝试 `npm install --omit=dev`
   *
   * <p>失败**不阻断加载**：插件可能根本不需要依赖。失败原因会写进日志
   * （最常见的原因就是"本机没有 npm"，那时作者应把 node_modules 一起打包）。</p>
   */
  async function ensureDependencies(pluginId, pluginDir) {
    const pkg = path.join(pluginDir, 'package.json');
    if (!fs.existsSync(pkg)) {
      return { ok: true, skipped: 'no package.json' };
    }
    if (fs.existsSync(path.join(pluginDir, 'node_modules'))) {
      return { ok: true, skipped: 'node_modules present' };
    }
    const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
    const r = await execApi.run({
      cmd: npm,
      args: ['install', '--omit=dev', '--no-audit', '--no-fund'],
      cwd: pluginDir,
      timeoutMs: 300_000,
    });
    if (r.code !== 0) {
      log(`[plugin:${pluginId}] 依赖安装失败（本机可能没有 npm）：${String(r.stderr || '').slice(0, 300)}`);
      return { ok: false, error: '依赖安装失败；建议把 node_modules 一起打进插件 zip' };
    }
    log(`[plugin:${pluginId}] 依赖已安装`);
    return { ok: true, installed: true };
  }

  /** 审计里只留"够用就好"的摘要：不要把整段文件内容或密钥写进日志。 */
  function summarize(capability, method, args) {
    try {
      if (capability === 'exec') {
        return `${args?.[0]?.cmd || ''} ${(args?.[0]?.args || []).join(' ')}`.slice(0, 200);
      }
      if (capability === 'fs') {
        return String(args?.[0] || '').slice(0, 300);
      }
      if (capability === 'http') {
        return `${args?.[0]?.method || 'GET'} ${String(args?.[0]?.url || '')}`.slice(0, 300);
      }
    } catch {
      // 摘要失败不影响主流程
    }
    return '';
  }

  // ---------------------------------------------------------------- 调用分发

  async function call(pluginId, capability, method, args) {
    try {
      ensurePluginId(pluginId);
    } catch (e) {
      return { ok: false, error: e.message };
    }
    if (!CAPABILITIES.includes(capability)) {
      return { ok: false, error: `未知能力：${capability}` };
    }
    if (!isAuthorized(pluginId, capability)) {
      // 不报错、不吞掉：把"需要哪一类授权"原样交回 renderer，由它弹确认
      return { ok: false, needAuth: capability };
    }
    try {
      let data;
      if (capability === 'fs') {
        data = await fsApi[method]?.(...(args || []));
      } else if (capability === 'http') {
        data = await httpApi[method]?.(...(args || []));
      } else if (capability === 'exec') {
        data = await execApi[method]?.(...(args || []));
      } else if (capability === 'storage') {
        const box = storageFor(pluginId);
        const fn = box[method];
        data = typeof fn === 'function' ? fn.apply(box, args || []) : undefined;
      }
      if (data === undefined) {
        return { ok: false, error: `不支持的方法：${capability}.${method}` };
      }
      // 只对"会产生后果"的调用记审计（storage 读写太频繁，不记）
      if (capability === 'exec' || capability === 'http' || (capability === 'fs' && method === 'write')) {
        audit({ type: 'call', pluginId, capability, method, brief: summarize(capability, method, args) });
      }
      return { ok: true, data };
    } catch (e) {
      return { ok: false, error: (e && e.message) || String(e) };
    }
  }

  // ---------------------------------------------------------------- 插件 main.js（Node 侧）

  /**
   * 子进程会话：`pluginId → { child, pending, seq }`。
   *
   * <p>每个插件一个 `utilityProcess` —— 隔离彻底（一个插件死循环不会卡住应用），
   * 代价是每个插件多一个 ~30MB 的 Node 进程（插件通常个位数，可以接受）。</p>
   */
  const children = new Map();

  /** 平台调用用的登录态（渲染层登录后经 IPC 交过来；只存在主进程内存里）。 */
  let auth = { token: null, tenantId: 'default' };

  function setAuth(next) {
    if (!next) {
      return;
    }
    auth = {
      token: next.token == null ? auth.token : next.token,
      tenantId: next.tenantId == null ? auth.tenantId : next.tenantId,
    };
  }

  /**
   * 代插件请求平台接口（对应子进程里的 `api.platform.request`）。
   *
   * <h3>为什么只有它需要主进程转一手</h3>
   * 子进程本身是完整 Node 环境，`fs` / `fetch` / `child_process` 都能直接用 ——
   * 唯独"调平台接口"要登录 token，而 token 只在主进程内存里（渲染层登录后交过来的）。
   * 把 token 复制给每个插件进程是没必要的暴露面，所以做成代发。
   *
   * <p>边界与前端 `ctx.http` 一致：**只允许 `/api/` 开头的路径**。</p>
   */
  async function platformRequest(cfg) {
    const { path: p, method = 'GET', body, headers } = cfg || {};
    if (!/^\/api\//.test(String(p || ''))) {
      throw new Error('platform.request 只允许 /api/ 开头的平台路径');
    }
    if (!auth.token) {
      throw new Error('尚未取得登录 token（请先在界面里登录）');
    }
    const base = getBaseUrl();
    if (!base) {
      throw new Error('后端地址未知（可能还在启动中）');
    }
    const res = await fetch(`${base}${p}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': auth.tenantId || 'default',
        Authorization: `Bearer ${auth.token}`,
        ...(headers || {}),
      },
      body: body == null ? undefined : JSON.stringify(body),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok || (json && json.success === false)) {
      throw new Error((json && json.message) || `HTTP ${res.status}`);
    }
    /*
     * ★ 两种响应形态都要支持（2026-10-20 修复）。
     *
     * 平台接口大多数套了 `ApiResponse`（`{ success, data, message }`），
     * 但**有几个是裸 JSON**：最典型的是 `POST /api/v1/agent/run` 直接返回 `AgentRunResponse`
     * （前端用 `raw=true` 走这条）。
     *
     * 原来无条件 `return json.data`，于是调 `/agent/run` 的插件（如 QQ 助手）拿到的全是 `null` ——
     * 表现是"消息收到了、模型也回答了、就是发不出去"，极难定位。
     * 现在：有 `data` 就取 `data`，没有就返回整个 json。
     */
    if (!json) {
      return null;
    }
    return json.data !== undefined ? json.data : json;
  }

  /** 向子进程发一条请求并等回复（带超时，避免插件卡住时主进程永远等）。 */
  function request(session, payload, timeoutMs = 30_000) {
    return new Promise((resolve) => {
      const id = ++session.seq;
      const timer = setTimeout(() => {
        session.pending.delete(id);
        resolve({ ok: false, error: '插件进程无响应（超时）' });
      }, timeoutMs);
      session.pending.set(id, (msg) => {
        clearTimeout(timer);
        session.pending.delete(id);
        resolve(msg);
      });
      try {
        session.child.postMessage({ ...payload, id });
      } catch (e) {
        clearTimeout(timer);
        session.pending.delete(id);
        resolve({ ok: false, error: (e && e.message) || String(e) });
      }
    });
  }

  /** 子进程来的三类消息：回复 / 日志 / 能力请求。 */
  function onChildMessage(pluginId, session, msg) {
    if (!msg || !msg.type) {
      return;
    }
    if (msg.type === 'reply') {
      const cb = session.pending.get(msg.id);
      if (cb) {
        cb(msg);
      }
      return;
    }
    if (msg.type === 'log') {
      log(`[plugin:${pluginId}]`, ...(msg.args || []));
      return;
    }
    if (msg.type === 'host-call') {
      void handleChildHostCall(session, msg);
    }
  }

  async function handleChildHostCall(session, msg) {
    const reply = { type: 'host-reply', id: msg.id, ok: false };
    try {
      if (msg.method === 'platform.request') {
        reply.data = await platformRequest(msg.args);
        reply.ok = true;
      } else {
        reply.error = `未知 host 方法：${msg.method}`;
      }
    } catch (e) {
      reply.error = (e && e.message) || String(e);
    }
    try {
      session.child.postMessage(reply);
    } catch {
      // 子进程已退出：回复丢了也无所谓
    }
  }

  /**
   * 加载插件的 `main.js`（Node 侧逻辑）—— **在独立子进程里**。
   *
   * <p>插件导出约定：`{ start(api), stop(), methods: { name: fn } }`；
   * `api` = `{ pluginId, dataDir, logger, platform.request }`。</p>
   */
  async function loadPlugin(pluginId, pluginDir, mainFile) {
    try {
      ensurePluginId(pluginId);
      if (!pluginDir) {
        return { ok: false, error: 'pluginDir 不能为空' };
      }
      if (children.has(pluginId) || loadedPlugins.has(pluginId)) {
        return { ok: true, already: true };
      }
      // 先确保依赖（失败不阻断：可能这个插件根本不需要依赖）
      await ensureDependencies(pluginId, pluginDir);

      if (!utilityProcess || !runtimeScript) {
        // 极旧 Electron 的兜底：主进程内加载（隔离失效，明确记一笔）
        log('[host] utilityProcess 不可用，插件将运行在主进程内（**没有崩溃隔离**）');
        return await inlineLoad(pluginId, pluginDir, mainFile);
      }

      const child = utilityProcess.fork(runtimeScript, [], {
        stdio: 'inherit',
        serviceName: `plugin:${pluginId}`,
      });
      const session = { child, pending: new Map(), seq: 0 };
      children.set(pluginId, session);
      child.on('message', (msg) => onChildMessage(pluginId, session, msg));
      child.on('exit', (code) => {
        children.delete(pluginId);
        audit({ type: 'plugin-exit', pluginId, code });
        log(`[plugin:${pluginId}] Node 侧进程退出（code=${code}）`);
      });

      const res = await request(session, {
        type: 'load',
        pluginId,
        dir: pluginDir,
        mainFile: mainFile || 'main.js',
        dataDir: dataDir(pluginId),
      }, 60_000);

      if (!res.ok) {
        try { child.kill(); } catch { /* 已退出 */ }
        children.delete(pluginId);
        return { ok: false, error: res.error || '插件加载失败' };
      }
      audit({ type: 'plugin-load', pluginId, isolated: true });
      return { ok: true };
    } catch (e) {
      return { ok: false, error: (e && e.stack) || String(e) };
    }
  }

  /** 兜底路径：没有 utilityProcess 时在主进程内加载（等价于旧行为）。 */
  async function inlineLoad(pluginId, pluginDir, mainFile) {
    const entry = path.join(pluginDir, mainFile || 'main.js');
    if (!fs.existsSync(entry)) {
      return { ok: false, error: '插件没有 main.js' };
    }
    const code = await fsp.readFile(entry, 'utf8');
    const module = { exports: {} };
    const req = createRequire(entry);
    // eslint-disable-next-line no-new-func
    const fn = new Function('exports', 'require', 'module', '__dirname', '__filename', 'console', code);
    fn(module.exports, req, module, pluginDir, entry, console);

    const mod = module.exports && (module.exports.default || module.exports);
    const api = {
      pluginId,
      dataDir: dataDir(pluginId),
      logger: {
        info: (...a) => log(`[plugin:${pluginId}]`, ...a),
        warn: (...a) => log(`[plugin:${pluginId}] WARN`, ...a),
        error: (...a) => log(`[plugin:${pluginId}] ERROR`, ...a),
      },
      platform: { request: platformRequest },
    };
    if (mod && typeof mod.start === 'function') {
      await mod.start(api);
    }
    loadedPlugins.set(pluginId, { module: mod });
    audit({ type: 'plugin-load', pluginId, isolated: false });
    return { ok: true };
  }

  /** 卸载插件的 Node 侧：先请它优雅 stop，再杀进程（停不掉就强杀，避免僵尸）。 */
  async function unloadPlugin(pluginId) {
    const session = children.get(pluginId);
    if (session) {
      await request(session, { type: 'stop' }, 10_000);
      try { session.child.kill(); } catch { /* 已退出 */ }
      children.delete(pluginId);
      return { ok: true };
    }
    // 兜底路径（主进程内加载）
    const rec = loadedPlugins.get(pluginId);
    if (!rec) {
      return { ok: true };
    }
    try {
      if (rec.module && typeof rec.module.stop === 'function') {
        await rec.module.stop();
      }
    } catch (e) {
      log(`[plugin:${pluginId}] stop 失败：${e && e.message}`);
    }
    loadedPlugins.delete(pluginId);
    return { ok: true };
  }

  /** 调插件 main.js 里导出的 `methods[name]`（子进程或兜底路径）。 */
  async function invokePlugin(pluginId, method, args) {
    const session = children.get(pluginId);
    if (session) {
      const res = await request(session, { type: 'invoke', method, args }, 60_000);
      return res.ok ? { ok: true, data: res.data } : { ok: false, error: res.error };
    }
    const rec = loadedPlugins.get(pluginId);
    if (!rec) {
      return { ok: false, error: '插件 main.js 未加载（manifest 里缺 main 字段，或加载失败）' };
    }
    const fn = rec.module && rec.module.methods && rec.module.methods[method];
    if (typeof fn !== 'function') {
      return { ok: false, error: `插件没有导出方法：${method}` };
    }
    try {
      return { ok: true, data: await fn.call(rec.module, args) };
    } catch (e) {
      return { ok: false, error: (e && e.message) || String(e) };
    }
  }

  /** 退出前统一停掉所有插件进程（它们的定时器/网络连接不该拖住退出）。 */
  async function dispose() {
    for (const id of [...children.keys()]) {
      // eslint-disable-next-line no-await-in-loop
      await unloadPlugin(id);
    }
    for (const id of [...loadedPlugins.keys()]) {
      // eslint-disable-next-line no-await-in-loop
      await unloadPlugin(id);
    }
  }

  return {
    CAPABILITIES,
    call,
    authorize,
    revoke,
    status,
    env,
    setAuth,
    loadPlugin,
    unloadPlugin,
    invokePlugin,
    dispose,
    // 便于诊断
    auditFile,
    authFile,
  };
}

module.exports = { createHostBridge, CAPABILITIES };
