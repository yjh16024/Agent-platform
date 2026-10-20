import { useEffect, useRef, useState } from 'react';
import { Tag } from 'antd';
import type { UiContribution } from '../api/uiSlots';
import {
  createPluginContext,
  fetchPluginAssetText,
  loadPluginScript,
  missingServices,
  pluginAssetUrl,
  releasePluginScript,
  setCurrentDshPlugin,
} from '../plugins/scriptLoader';
import { registerPluginConfigDefaults } from '../plugins/host/services';

/**
 * 可执行界面的一次挂载（2026-10-19，D 档）。
 *
 * <h3>它做四件事（顺序不能换）</h3>
 * <ol>
 *   <li><b>建容器</b>：{@code isolate !== false} 时用 **Shadow DOM**（插件样式进不去、
 *       也改不到宿主结构）；{@code isolate: false} 时直接用宿主节点（能力全开）；</li>
 *   <li><b>注入样式</b>：隔离时进 shadow root（{@code <style>}），否则进 {@code document.head}。
 *       样式失败**不影响脚本** —— 难看总比白屏好；</li>
 *   <li><b>加载脚本</b>（引用计数 +1）→ 取模块 → 调 {@code apply(ctx)}，把返回值当 disposer；</li>
 *   <li><b>卸载</b>：调 disposer → 移除样式 → 引用计数 -1（到 0 才真正移除 script）。</li>
 * </ol>
 *
 * <h3>★ 为什么 {@code agentId} 不在依赖里</h3>
 * 如果放进依赖，切换智能体就把整个插件重挂一遍 —— 桌宠会"瞬移回原点"、看板会丢筛选状态。
 * 所以切换智能体**不重挂**，而是由宿主派发 {@code agent.changed} 事件（见 AppLayout）；
 * {@code ctx.agentId} 是**挂载时**的值，变化请订阅事件取新值。
 *
 * <h3>失败时的表现</h3>
 * 只在原位显示一个红色小角标（并带完整错误原因作为 title），**绝不让主界面崩** ——
 * 插件界面是增强，不是主功能。
 */
export default function PluginScriptSlot({
  item,
  agentId,
}: {
  item: UiContribution;
  agentId?: string;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const pluginId = item.pluginId;
  const script = item.script ?? '';
  const stylePath = item.style ?? '';
  const isolate = item.isolate !== false;
  /** 资源端点种类：jar 插件缺省，独立界面插件为 `plugin-web`（见 scriptLoader.pluginAssetUrl）。 */
  const assetBase = item.assetBase ?? undefined;

  useEffect(() => {
    const host = hostRef.current;
    if (!host || !script) {
      return;
    }
    let disposed = false;
    let dispose: (() => void) | null = null;
    let disposeNode: (() => void) | null = null;
    let shadow: ShadowRoot | null = null;
    let styleEl: HTMLStyleElement | null = null;
    setError(null);

    // 1) 容器
    let container: HTMLElement;
    if (isolate) {
      shadow = host.shadowRoot ?? host.attachShadow({ mode: 'open' });
      shadow.innerHTML = '';
      container = document.createElement('div');
      container.style.pointerEvents = 'auto';
      shadow.appendChild(container);
    } else {
      host.innerHTML = '';
      container = host;
      container.style.pointerEvents = 'auto';
    }
    // 诊断：能确认"挂载组件真的跑到了这里"（"装了但界面没反应"的第一个分界点）
    console.info('[plugin] mount', pluginId, { script, style: stylePath || null, isolate, assetBase: assetBase ?? 'plugins' });

    void (async () => {
      // 2) 样式
      if (stylePath) {
        try {
          // ★ 必须带 token：`<link href>` 这类子资源请求不会带 Authorization，会 401
          const css = await fetchPluginAssetText(pluginAssetUrl(pluginId, stylePath, assetBase));
          if (css && !disposed) {
            styleEl = document.createElement('style');
            styleEl.textContent = css;
            styleEl.dataset.apPlugin = pluginId;
            (shadow ?? document.head).appendChild(styleEl);
          }
        } catch {
          // 样式拿不到就退化为无样式，不阻断脚本
        }
      }

      // 3) 脚本（引用计数 +1）
      const loaded = await loadPluginScript({ pluginId, script, assetBase });
      if (disposed) {
        // 加载期间已经被卸载：把刚拿到的引用还回去，否则计数永远减不到 0
        if (loaded.module) {
          releasePluginScript(pluginId);
        }
        return;
      }
      if (!loaded.module) {
        console.warn('[plugin] 脚本加载失败', pluginId, loaded.error);
        setError(loaded.error ?? '插件脚本不可用');
        return;
      }
      if (typeof loaded.module.apply !== 'function') {
        console.warn('[plugin] 模块没有 apply()', pluginId);
        setError('插件脚本没有导出 apply()');
        return;
      }
      console.info('[plugin] script ready', pluginId, { inject: loaded.module.inject ?? [] });

      /*
       * 3.5) 能力校验（P2）：`inject` 里出现宿主没提供的服务 —— **明确报错，不加载**。
       * 这是与 DSH 的核心差异（那边返回 {} 兜底，插件装上了但功能静默失效）。
       */
      const missing = missingServices(loaded.module.inject);
      if (missing.length > 0) {
        setError(`该插件需要本平台未提供的能力：${missing.join('、')}（声明于 inject）`);
        return;
      }

      /*
       * 3.6) 插件 Node 侧（P4，2026-10-19）：manifest 里有 `main` 才处理。
       * 浏览器里没有 apHost —— **明确告知**"该插件带 Node 侧、当前环境不支持"，
       * 而不是让它静默少一半功能（那种"调了没反应"最难查）。
       */
      if (item.main) {
        const apHost = (window as unknown as {
          apHost?: {
            loadPlugin: (id: string, dir: string, mainFile?: string) => Promise<{ ok: boolean; error?: string }>;
            unloadPlugin: (id: string) => Promise<unknown>;
          };
        }).apHost;
        if (!apHost) {
          console.warn('[plugin] 插件带 Node 侧 main.js，但当前环境没有本机能力桥（需要桌面版）', pluginId);
        } else if (item.path) {
          const loadedNode = await apHost.loadPlugin(pluginId, item.path, item.main);
          if (loadedNode.ok) {
            disposeNode = () => {
              void apHost.unloadPlugin(pluginId);
            };
            console.info('[plugin] node 侧已加载', pluginId, item.main);
          } else {
            console.warn('[plugin] Node 侧加载失败', pluginId, loadedNode.error);
          }
        }
      }

      /*
       * 3.7) 登记配置默认值（2026-10-19）：`ctx.config.get()` 要能填出 manifest 里声明的默认值，
       * 而插件脚本看不到 manifest —— 所以由这里在 apply 之前登记一次。
       */
      registerPluginConfigDefaults(pluginId, item.config);

      // 4) apply(ctx)：宿主收口的清理 + 插件自己的 disposer 合成一条卸载链
      const created = createPluginContext({ pluginId, container, agentId, isolate });
      try {
        /*
         * 告诉 DSH 兼容层"现在执行的是哪个插件"：`registerTab` 这类 API 不知道调用者是谁，
         * 靠这个同步上下文归属。⚠️ 只覆盖 apply 的**同步阶段** —— 异步里再注册会归到兜底 id。
         */
        setCurrentDshPlugin(pluginId);
        const result = loaded.module.apply(created.ctx);
        const moduleDisposer = toDisposer(result);
        dispose = () => {
          moduleDisposer?.();
          created.dispose();
        };
        console.info('[plugin] applied', pluginId);
      } catch (e) {
        // apply 中途抛错：已登记的 effect / 渲染 / 槽位注册必须清掉，否则留半截界面
        created.dispose();
        console.warn('[plugin] apply 抛错', pluginId, (e as Error).message);
        setError(`插件界面初始化失败：${(e as Error).message}`);
      } finally {
        setCurrentDshPlugin(null);
      }
    })();

    return () => {
      disposed = true;
      try {
        dispose?.();
        disposeNode?.();
      } catch (e) {
        // disposer 自己抛错不能影响宿主（也不能阻断其它插件的清理）
        console.warn(`[plugin] ${pluginId} 的 disposer 抛错：`, e);
      }
      styleEl?.remove();
      releasePluginScript(pluginId);
      if (shadow) {
        shadow.innerHTML = '';
      } else {
        host.innerHTML = '';
      }
    };
    // 刻意**不含 agentId**：切换智能体只派发事件，不重挂（见上方说明）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pluginId, script, stylePath, isolate, assetBase]);

  return (
    <div ref={hostRef} data-ap-plugin-host={pluginId} style={{ pointerEvents: 'auto' }}>
      {error && (
        <Tag color="error" style={{ fontSize: 11 }} title={error}>
          插件界面加载失败：{pluginId}
        </Tag>
      )}
    </div>
  );
}

/** `apply` 的返回值 → disposer（函数 / 带 dispose() 的对象 / 无）。 */
function toDisposer(result: unknown): (() => void) | null {
  if (typeof result === 'function') {
    return result as () => void;
  }
  if (result && typeof result === 'object') {
    const fn = (result as { dispose?: unknown }).dispose;
    if (typeof fn === 'function') {
      return () => {
        fn.call(result);
      };
    }
  }
  return null;
}
