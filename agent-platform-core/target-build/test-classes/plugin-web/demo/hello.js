// 测试用插件前端资源：验证内置插件（classpath）来源的读取链路
window.__apPlugin__ && window.__apPlugin__.register('demo', { apply: function () { return function () {}; } });
