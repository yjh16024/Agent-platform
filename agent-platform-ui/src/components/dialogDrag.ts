/**
 * 让弹窗可以拖动 —— **皮肤设置面板 + 所有 antd Modal**，零新依赖。
 *
 * <h3>为什么是"全局安装"而不是包一层 <code>DraggableModal</code></h3>
 * 全项目有 15+ 处 `<Modal>`，逐个替换的收益只有一个拖拽，却要改十几处调用点，
 * 而且**以后新增的弹窗一定会忘记加**。所以这里在 `body` 上装一个 MutationObserver：
 * 只要出现了"可拖的弹窗结构"，就自动给它挂上拖拽 —— 现有与将来的弹窗一次性全覆盖。
 *
 * <h3>什么样的算"可拖的弹窗"</h3>
 * <ul>
 *   <li><b>目标</b>：`.ant-modal`（antd Modal，`Modal.confirm/info` 也是它）
 *       与 `[data-slot='sidebar.settings'] [role='dialog']`（皮肤设置面板）。</li>
 *   <li><b>不含 antd Drawer</b>：它是贴边全高的面板，拖动没有语义。</li>
 *   <li><b>把手</b>：`.ant-modal-header` / `.ant-modal-confirm-title` /
 *       `[data-slot='settings.header']` —— 只有落在这三个元素**内部**的按下才起拖，
 *       所以点弹窗正文不会误拖。</li>
 * </ul>
 *
 * <h3>为什么用 transform 而不改 left/top</h3>
 * 两者都能位移，但 transform 不触发重排。更关键的是**这两个目标本来就已经是合成层**
 * （antd Modal 的入场动画用 transform；设置对话框带皮肤的 `backdrop-filter`），
 * 所以再给它们加 transform **不会引入新的层提升** —— 而皮肤的注释里反复提到过
 * "额外提升合成层会导致吉祥物的 filter 层盖住遮罩"这类 Chromium 坑。
 *
 * <h3>状态放在哪</h3>
 * 位移只存在 `WeakMap<元素, 偏移>` 里，**不写进 React state**：
 * 弹窗关闭时元素被 React/antd 移除，状态随之消失，下次打开自动回到初始位置。
 * 这也顺带避免了"拖过之后重新打开还停在老位置"的怪现象。
 */
/** 可拖的弹窗容器 */
const TARGET_SELECTOR = '.ant-modal, [data-slot="sidebar.settings"] [role="dialog"]';

/** 把手：落在这里面的按下才起拖 */
const HANDLE_SELECTOR = '.ant-modal-header, .ant-modal-confirm-title, [data-slot="settings.header"]';

/** 起拖时要跳过的交互控件（点在它们身上是弹窗自己的事） */
const INTERACTIVE_SELECTOR = [
  'button',
  'a',
  'input',
  'textarea',
  'select',
  '[role="button"]',
  '.ant-select',
  '.ant-picker',
  '.ant-switch',
  '.ant-color-picker-trigger',
].join(',');

/** 至少保留这么多像素在视口内，避免被拖到找不回来 */
const MIN_VISIBLE = 80;

interface Offset {
  x: number;
  y: number;
}

const offsets = new WeakMap<HTMLElement, Offset>();
const attached = new WeakSet<HTMLElement>();

let installed = false;

function clamp(value: number, low: number, high: number): number {
  if (low > high) {
    return low;
  }
  return Math.min(Math.max(value, low), high);
}

/**
 * 把偏移夹到"目标至少留下 MIN_VISIBLE 像素"的范围内。
 *
 * <p>用 `getBoundingClientRect()` 反推"未位移时的矩形"：这样窗口尺寸变化之后再次拖动，
 * 夹取边界会自动跟着更新，不需要专门监听 resize（也就不必给每个弹窗挂一个
 * 永不释放的 window 监听器）。</p>
 */
function clampOffset(target: HTMLElement, x: number, y: number): Offset {
  const rect = target.getBoundingClientRect();
  const current = offsets.get(target) ?? { x: 0, y: 0 };
  const baseLeft = rect.left - current.x;
  const baseTop = rect.top - current.y;
  return {
    x: clamp(x, -baseLeft - rect.width + MIN_VISIBLE, window.innerWidth - baseLeft - MIN_VISIBLE),
    y: clamp(y, -baseTop - rect.height + MIN_VISIBLE, window.innerHeight - baseTop - MIN_VISIBLE),
  };
}

function applyOffset(target: HTMLElement, x: number, y: number): void {
  const next = clampOffset(target, x, y);
  offsets.set(target, next);
  target.style.transform = `translate(${next.x}px, ${next.y}px)`;
}

function attach(target: HTMLElement): void {
  if (attached.has(target)) {
    return;
  }
  const handle = target.querySelector<HTMLElement>(HANDLE_SELECTOR);
  if (handle === null) {
    return;
  }
  attached.add(target);
  offsets.set(target, { x: 0, y: 0 });
  target.setAttribute('data-ap-draggable', '');

  let start: { pointerX: number; pointerY: number; offsetX: number; offsetY: number } | null = null;

  handle.addEventListener('pointerdown', (event: PointerEvent) => {
    // 只认左键；右键/中键留给浏览器与弹窗自己
    if (event.button !== 0) {
      return;
    }
    const from = event.target;
    if (from instanceof Element && from.closest(INTERACTIVE_SELECTOR) !== null) {
      return;
    }
    const offset = offsets.get(target) ?? { x: 0, y: 0 };
    start = { pointerX: event.clientX, pointerY: event.clientY, offsetX: offset.x, offsetY: offset.y };
    handle.setPointerCapture(event.pointerId);
    target.setAttribute('data-ap-dragging', '');
    // 别让它顺带把标题文字选中
    event.preventDefault();
  });

  handle.addEventListener('pointermove', (event: PointerEvent) => {
    if (start === null) {
      return;
    }
    applyOffset(target, start.offsetX + (event.clientX - start.pointerX), start.offsetY + (event.clientY - start.pointerY));
  });

  const finish = (event: PointerEvent): void => {
    if (start === null) {
      return;
    }
    start = null;
    if (handle.hasPointerCapture(event.pointerId)) {
      handle.releasePointerCapture(event.pointerId);
    }
    target.removeAttribute('data-ap-dragging');
  };
  handle.addEventListener('pointerup', finish);
  handle.addEventListener('pointercancel', finish);

  // 双击把手复位 —— 拖偏了不用关掉重开
  handle.addEventListener('dblclick', () => applyOffset(target, 0, 0));
}

function scan(root: ParentNode): void {
  if (root instanceof HTMLElement && root.matches(TARGET_SELECTOR)) {
    attach(root);
  }
  root.querySelectorAll(TARGET_SELECTOR).forEach((element) => {
    if (element instanceof HTMLElement) {
      attach(element);
    }
  });
}

/**
 * 安装全局拖拽增强（重复调用无副作用）。
 *
 * <p>扫描起点是 `document`：皮肤设置面板是**内联渲染在 `#root` 里**的（刻意不 portal，
 * 见 `SidebarSkinSettings` 的说明），而 antd Modal 挂在 `body` 上 —— 两者都要覆盖，
 * 所以观察 `body` 的子树、并先对已有 DOM 扫一遍。</p>
 */
export function installDialogDragging(): void {
  if (installed) {
    return;
  }
  installed = true;
  scan(document);
  new MutationObserver((records) => {
    for (const record of records) {
      record.addedNodes.forEach((node) => {
        if (node instanceof HTMLElement) {
          scan(node);
        }
      });
    }
  }).observe(document.body, { childList: true, subtree: true });
}
