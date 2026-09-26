// 桌面壳与渲染层之间的最小桥：只暴露"主题"这一件事。
//
// 渲染层（网页）换肤后需要让壳知道两件事：
//   1) 窗口背景色 —— 否则切换/重载时会先闪一下白底；
//   2) 明暗模式   —— 影响原生滚动条、右键菜单等系统外观。
// 渲染层读不到 userData，所以由主进程把主题落成 userData/theme.json，
// 下次启动时先读它再建窗口，就能从一开始就是对的颜色。
const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('apTheme', {
  /** 上报当前主题：{ mode: 'light'|'dark', background: '#rrggbb' } */
  set: (payload) => ipcRenderer.send('ap:theme', payload),
});

/*
 * 工作区：让页面能弹出**原生目录选择框**。
 *
 * 为什么必须走主进程：渲染层开着 contextIsolation + 关闭 nodeIntegration，
 * 拿不到 fs / dialog；而且自 Electron 32 起 `File.path` 已被移除，
 * 要靠 `webUtils.getPathForFile()` 才能从拖拽的 File 对象里取到真实路径 ——
 * 那条路同样得经主进程。所以"选目录"统一由这里代理。
 */
contextBridge.exposeInMainWorld('apWorkspace', {
  /** 打开目录选择框；用户取消时返回 null。 */
  pickDirectory: () => ipcRenderer.invoke('ap:pick-directory'),
  /**
   * 从一个 File 对象取它的**真实绝对路径**。
   *
   * <p>必须由 preload 提供：Electron 32 起 `File.path` 已被移除，官方替代
   * `webUtils.getPathForFile(file)` 只在渲染进程可用，而渲染层开着 contextIsolation，
   * 不能直接 require electron —— 所以由这里代理一层。</p>
   */
  getPathForFile: (file) => {
    try {
      return webUtils.getPathForFile(file) || null;
    } catch {
      // 拿不到就返回 null：界面会退化为"不提示工作区"，而不是报错打断上传
      return null;
    }
  },
});
