/**
 * 窗口标题守卫 —— 标题**只归宿主所有**，不接受皮肤（或任何第三方脚本）改写。
 *
 * <h3>问题</h3>
 * DSH 系皮肤会接管窗口标题，而且是**写死**的 —— 皮肤作者针对它自己的宿主做品牌：
 * <pre>
 *   const SKIN_TITLE = '深海女仆工坊 · DeepSeek Harness'
 *   document.title = SKIN_TITLE
 * </pre>
 * 卸载时它还会还原 `document.title`，说明这是它有意接管的行为。
 * 所以这不是皮肤的 bug，而是"宿主该自己管好窗口标题"这件事没人做。
 *
 * <h3>为什么是"固定为产品名"，而不是"把皮肤原宿主的产品名替换掉"</h3>
 * 替换法要靠一张**别名表**，而这张表永远列不全：今天是 `DeepSeek Harness`，
 * 换个皮肤可能是 `DEEPSEEK HARNESS`、`DSH`，或者又是另一个宿主名 —— 追不上。
 * 而窗口标题是**操作系统的界面元素**（任务栏、Alt-Tab 里显示的就是它），
 * 它的职责是"这是哪个应用"，不该由第三方插件决定。
 * 皮肤想展示自己，靠视觉（背景/边框/立绘）和设置面板里的「正在管理：xxx」已经足够。
 *
 * <h3>为什么接管 setter，而不是监听 &lt;title&gt;</h3>
 * MutationObserver 是异步的（会先闪一下错标题），而且还要防自己造成的回环。
 * 接管 `document.title` 的 setter 是同步的：写入当场被归一，
 * 皮肤自己读回 `document.title` 时看到的也是归一后的值，它的"改动判断 + 还原"逻辑跟着变正确。
 *
 * <h3>平台改名时要动哪些地方</h3>
 * 运行时**只改下面的 {@link PRODUCT_TITLE}**。另外三处是"脚本执行前 / 打包时"的名字，
 * 不参与运行时，可按需一起同步：`index.html` 的 `<title>` 初值、
 * `desktop/main.js` 建窗口时的 `title`、`desktop/electron-builder.yml` 的 `productName`。
 */

/** 窗口标题的唯一来源。**平台改名只改这一处。** */
export const PRODUCT_TITLE = '白雾·智能体交互平台';

let installed = false;

/**
 * 安装标题守卫（重复调用无副作用）。
 *
 * <p>在 `document` **实例**上定义同名属性来遮蔽原型上的访问器，而不是去改
 * `Document.prototype` —— 影响面只限当前文档，且随时可以 `delete` 还原。</p>
 */
export function installTitleGuard(): void {
  if (installed) {
    return;
  }
  installed = true;

  const descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'title');
  const read = descriptor?.get;
  const write = descriptor?.set;
  if (read === undefined || write === undefined) {
    // 环境不支持就不接管：宁可标题被皮肤改掉，也不要让 document.title 直接坏掉
    return;
  }

  Object.defineProperty(document, 'title', {
    configurable: true,
    get(this: Document): string {
      return read.call(this) as string;
    },
    // 传进来的值一律忽略：标题由宿主决定，不由调用方决定
    set(this: Document, _value: unknown): void {
      write.call(this, PRODUCT_TITLE);
    },
  });

  // 把"脚本执行前"的初始标题也归一 —— 顺带清掉 index.html 里模板遗留的 "· 仪表盘"
  document.title = PRODUCT_TITLE;
}
