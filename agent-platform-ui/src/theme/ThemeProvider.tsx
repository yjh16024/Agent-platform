import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useSkinActive } from '../skin/runtime';

/**
 * 皮肤（换肤）上下文。
 *
 * 这个项目原本没有任何主题基建——`main.tsx` 里只有一个不带 `theme` 的 `ConfigProvider`，
 * 外壳颜色则硬编码在 `AppLayout` 里。这里把它补上，做法是 **antd token + CSS 变量双轨**：
 *
 * - **antd token**：`ConfigProvider` 的 `theme.token` 由皮肤配色派生，管住所有 antd 组件；
 * - **CSS 变量**（`--ap-*`）：管住自研布局（侧栏/头部/内容区/背景图），这样"只改观感、不动布局"。
 *
 * **背景图**：DSH 皮肤普遍自带壁纸，且命名分 `hero`（空态，还没对话）与 `active`（对话中）。
 * 所以这里按"明暗 × 场景"两个维度各存一张，由 `scene` 决定当前用哪张——
 * 对话页在无消息时把 scene 置为 hero、有消息时置为 active，就实现了 DSH 那种
 * "一开始输入框居中、发过消息后移到底部"的观感切换。
 */

const STORAGE_KEY = 'ap.skin';

/**
 * 内置默认外观（没有任何皮肤 JS 在跑时的样子）。
 *
 * <p>只保留**真的有人用**的变量（已逐个 grep 过全项目 + CSS）。
 * 随「应用」按钮下线而删掉的死变量：`--ap-bg-image` / `--ap-bg-scrim`（背景图机制）、
 * `--ap-header-bg`（顶部 Header 已移除）、`--ap-bg-elevated` / `--ap-on-primary` /
 * `--ap-text-secondary`（从来没有消费方）。</p>
 */
export const DEFAULT_VARS: Record<string, string> = {
  '--ap-bg-layout': '#f5f5f5',
  '--ap-bg-container': '#ffffff',
  '--ap-border': 'rgba(0, 0, 0, 0.06)',
  '--ap-primary': '#1677ff',
  '--ap-text': 'rgba(0, 0, 0, 0.88)',
  '--ap-sider-bg': '#001529',
  '--ap-sider-text': 'rgba(255, 255, 255, 0.85)',
};

/**
 * 主题上下文。
 *
 * <h3>为什么只剩「明暗模式」</h3>
 * 原来这里还有一整套"把皮肤当主题用"的 API（`applySkin` / `skin` / `backgrounds` /
 * `setBackground` / `scene` / `bgEnabled`）——后端从皮肤包里抽配色与壁纸、前端套上去。
 * 那条路已随「应用」按钮一起下线：**「运行 JS」让皮肤自己的 bundle 接管外观，
 * 它带来的美术、背景、动效是完整的，而"只抽配色和图片"只能做到半套。**
 * 于是这套 API 再没有任何调用方，此处一并删除（接口需要时从 git 历史取回即可）。
 *
 * <p>**保留 `mode`/`setMode`**：它写 `body[data-ds-dark-theme]`，
 * 是 DSH 契约里最关键的一个钩子（测绘显示 10/16 个皮肤靠它），不能动。</p>
 */
interface ThemeContextValue {
  /** 当前明暗模式 */
  mode: 'light' | 'dark';
  /** 侧栏菜单该用 antd 的哪套主题 */
  siderTheme: 'light' | 'dark';
  setMode: (mode: 'light' | 'dark') => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/** 只持久化明暗模式（"把皮肤当主题"那套已下线）。 */
interface StoredState {
  mode: 'light' | 'dark';
}

function loadStored(): StoredState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Partial<StoredState>) : {};
    return { mode: parsed.mode === 'dark' ? 'dark' : 'light' };
  } catch {
    return { mode: 'light' };
  }
}

/** 通知桌面壳（有 preload 时才有；网页版下是 undefined，直接跳过）。 */
function notifyShell(payload: { mode: string; background: string }) {
  try {
    const api = (window as unknown as { apTheme?: { set: (p: unknown) => void } }).apTheme;
    api?.set(payload);
  } catch {
    // 网页版/失败都不影响渲染
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const stored = useMemo(loadStored, []);
  const [mode, setModeState] = useState<'light' | 'dark'>(stored.mode);

  /** 有皮肤 JS 在跑 → antd 的内容容器要让位（否则皮肤的画被白卡片盖住）。 */
  const skinJsActive = useSkinActive();

  /**
   * 生效的 CSS 变量。
   *
   * 原来这里会在"应用了皮肤"时用皮肤抽出的配色覆盖 `--ap-*`，并在有背景图时拼出
   * `--ap-bg-image` / `--ap-bg-scrim`。那条路（「应用」按钮）已下线，
   * **外观由皮肤自己的 bundle 负责**，所以这里恒为内置默认外观。
   *
   * 保留常量而不再删掉整个 effect：下面的 effect 仍会把它逐条写到 `:root`，
   * 将来若真要接回主题，只需换数据源。
   */
  const cssVars: Record<string, string> = DEFAULT_VARS;

  /** 把 CSS 变量写到 :root，并同步 body 背景与桌面壳。 */
  useEffect(() => {
    const root = document.documentElement;
    Object.entries(cssVars).forEach(([k, val]) => {
      if (val) {
        root.style.setProperty(k, val);
      }
    });
    const bg = cssVars['--ap-bg-layout'] ?? '';
    document.body.style.background = bg;
    // DSH 宿主契约：明暗开关。测绘显示 10/16 个皮肤都靠它（含 maid-atelier / orca-link），
    // 这是"薄契约"里最关键的一个钩子。
    if (mode === 'dark') {
      document.body.setAttribute('data-ds-dark-theme', '');
    } else {
      document.body.removeAttribute('data-ds-dark-theme');
    }
    document.body.setAttribute('data-theme', mode);
    notifyShell({ mode, background: bg });
  }, [cssVars, mode]);

  // 只持久化明暗模式（"把皮肤当主题"那套已下线）
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ mode }));
    } catch {
      // 存储不可用时仅本次会话生效
    }
  }, [mode]);

  const setMode = useCallback((m: 'light' | 'dark') => setModeState(m), []);

  const value = useMemo<ThemeContextValue>(
    () => ({
      mode,
      // 内置默认外观是深色侧栏，所以侧栏菜单恒用 antd 的暗色主题
      siderTheme: 'dark',
      setMode,
    }),
    [mode, setMode],
  );

  /*
   * 皮肤 JS 在跑时，**内容容器必须让位**。
   *
   * <p>这条路由原来由「应用」按钮承担：它把皮肤包里抽出的配色灌进 antd token，
   * 容器自然跟着变色。但「应用」已下线（"运行 JS"能完整适配平台后它就没意义了），
   * token 于是恒为空 → antd 用默认白底 → 皮肤的画被大片不透明白卡片盖住
   * （实测：智能体列表、表格全部白底）。</p>
   *
   * <p>所以改成：皮肤在跑时把容器色换成**半透明**，让皮肤的画透上来，
   * 同时靠"半透明层"留住文字可读性。这是宿主该做的事 —— 皮肤管不到我们的 antd token。</p>
   */
  const yieldTokens = skinJsActive
    ? mode === 'dark'
      ? {
          colorBgContainer: 'rgba(22, 24, 30, 0.62)',
          colorBgElevated: 'rgba(28, 30, 38, 0.88)',
          colorBgLayout: 'transparent',
          colorFillAlter: 'rgba(255, 255, 255, 0.06)',
          colorBorderSecondary: 'rgba(255, 255, 255, 0.14)',
        }
      : {
          colorBgContainer: 'rgba(255, 255, 255, 0.72)',
          colorBgElevated: 'rgba(255, 255, 255, 0.9)',
          colorBgLayout: 'transparent',
          colorFillAlter: 'rgba(0, 0, 0, 0.03)',
          colorBorderSecondary: 'rgba(0, 0, 0, 0.1)',
        }
    : {};

  const antdConfig = useMemo(
    () => ({
      algorithm: mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
      // 皮肤主题已下线，token 只由"让位值"决定（皮肤在跑时容器半透明）
      token: yieldTokens,
    }),
    [mode, yieldTokens],
  );

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider locale={zhCN} theme={antdConfig}>
        {children}
      </ConfigProvider>
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme 必须在 ThemeProvider 内使用');
  }
  return ctx;
}
