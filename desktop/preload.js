// 桌面壳与渲染层之间的最小桥：只暴露"主题"这一件事。
//
// 渲染层（网页）换肤后需要让壳知道两件事：
//   1) 窗口背景色 —— 否则切换/重载时会先闪一下白底；
//   2) 明暗模式   —— 影响原生滚动条、右键菜单等系统外观。
// 渲染层读不到 userData，所以由主进程把主题落成 userData/theme.json，
// 下次启动时先读它再建窗口，就能从一开始就是对的颜色。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('apTheme', {
  /** 上报当前主题：{ mode: 'light'|'dark', background: '#rrggbb' } */
  set: (payload) => ipcRenderer.send('ap:theme', payload),
});
