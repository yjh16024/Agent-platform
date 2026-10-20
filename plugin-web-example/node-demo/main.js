/*
 * 插件的 **Node 侧**（2026-10-19，P4）。
 *
 * 这段代码由 Electron 主进程加载（`new Function` + `createRequire(插件目录)`），
 * 等价于一个普通 CJS 模块 —— 可以直接 `require` 内置模块、也可以用插件自带依赖。
 *
 * 导出约定：
 *   start(api)  加载时调用（api: { pluginId, dataDir, storage, logger }）
 *   stop()      卸载时调用（清定时器/连接）
 *   methods     前端通过 ctx.host.invoke('方法名', 参数) 调这里
 *
 * ⚠️ 它**不需要授权**：能跑在这里，说明插件已经装进了本机（信任前提与安装一致）。
 *    需要授权的四类能力（fs/http/exec/storage）是给**前端**用的。
 */
/** `start(api)` 收到的 api 存下来，供 methods 里使用。 */
let hostApi = null;

module.exports = {
  start(api) {
    hostApi = api;
    // pid 会与主进程不同 —— 这正是"独立进程"的证据（2026-10-19 起 Node 侧跑在 utilityProcess 里）
    api.logger.info(`node 侧已启动（独立进程 pid=${process.pid}），数据目录：`, api.dataDir);
  },

  stop() {
    // 这里没有定时器；有的话要在这里清掉，否则会拖住应用退出
    hostApi = null;
  },

  methods: {
    /** 运行时信息 —— 用来证明"这段代码真的跑在 Node 里、且是独立进程"。 */
    info() {
      return {
        platform: process.platform,
        arch: process.arch,
        node: process.version,
        electron: process.versions.electron || '(非 Electron)',
        execPath: process.execPath,
        cwd: process.cwd(),
        pid: process.pid,
      };
    },

    /**
     * 调平台接口：验证 `api.platform.request`（2026-10-19 新通道）。
     *
     * 它是 Node 侧**唯一**需要主进程代发的能力 —— 因为要登录 token，而 token 在主进程内存里。
     * 前端面板通过 `ctx.host.invoke('platformProbe')` 触发。
     */
    async platformProbe() {
      if (!hostApi) {
        throw new Error('插件尚未初始化');
      }
      const page = await hostApi.platform.request({ path: '/api/v1/sessions?page=0&size=3' });
      const items = (page && page.items) || [];
      return {
        total: page ? page.total : null,
        count: items.length,
        first: items[0] ? (items[0].title || items[0].sessionId) : null,
      };
    },

    /** 列一个目录（主进程视角的 fs，不受渲染层的浏览器限制）。 */
    async readDir(dir) {
      const fsp = require('node:fs/promises');
      const entries = await fsp.readdir(dir, { withFileTypes: true });
      return entries.slice(0, 20).map((e) => ({ name: e.name, dir: e.isDirectory() }));
    },
  },
};
