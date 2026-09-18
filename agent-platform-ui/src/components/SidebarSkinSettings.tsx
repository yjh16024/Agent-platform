/**
 * 侧栏内的皮肤设置面板（对应 DSH 的 `data-slot='sidebar.settings'`）。
 *
 * <h3>为什么**不用** antd Drawer / Modal</h3>
 * 皮肤的 CSS 精确地找这套结构：
 * <pre>
 *   [data-slot='sidebar.settings'] > [role='presentation'] > [role='dialog']
 *   [data-slot='sidebar'] > :first-child > :has([role='dialog'])
 * </pre>
 * 也就是说它要求设置面板**就在侧栏里**（`[data-slot='sidebar'] > :first-child` 的后代中）。
 * 而 antd 的 Drawer/Modal 会 **portal 到 body**，于是：
 * <ul>
 *   <li>`[data-slot='sidebar'] > :first-child > :has([role='dialog'])` 永远匹配不到；</li>
 *   <li>皮肤的 settings-overlay 会认为"设置面板没打开"，它的覆盖层永远不出现。</li>
 * </ul>
 * 所以这里**内联渲染**在侧栏内部，并把 role 结构照 DSH 摆好。
 */
import { CloseOutlined, SkinOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { HOST_ATTRS, SLOTS } from '../skin/contract';
import { enableSkin, enabledSkinId, loadedSkins, subscribeSkinRuntime } from '../skin/runtime';
import SkinSettingsPanel from './SkinSettingsPanel';

interface Props {
  open: boolean;
  onClose: () => void;
}

/**
 * 皮肤设置对话框。
 *
 * <p>不再需要"侧栏宽度"了：面板现在是**整屏覆盖层 + 居中对话框**
 * （对齐 DSH，也是皮肤期望的形态），而不是贴在侧栏里的窄条。</p>
 */
export default function SidebarSkinSettings({ open, onClose }: Props) {
  const [, setRev] = useState(0);
  useEffect(() => subscribeSkinRuntime(() => setRev((v) => v + 1)), []);

  const running = loadedSkins();
  const preferred = enabledSkinId();
  // 优先显示"当前启用"的那个；否则显示唯一在跑的那个
  const activeId = preferred && running.some((s) => s.id === preferred) ? preferred : (running[0]?.id ?? null);

  if (!open) {
    return null;
  }

  return (
    /*
      **结构必须与皮肤期望的一致，不要改层级**（读 orca 的 CSS 得出）：

        [data-slot=sidebar.settings]        ← 纯包裹层（皮肤不给它设样式）
          └── [role=presentation]           ← 覆盖层：皮肤在此设 position:fixed/inset:0/
                │                              padding/z-index/isolation/pointer-events:auto
                ├── [data-ap-settings-mask] ← ★ 必须是 presentation 的**第一个**子元素，
                │                              皮肤用 `:first-child` 给它做遮罩动画
                └── [role=dialog]           ← 皮肤在此设 width/height/border/shadow/动画

      早期把 `role=presentation` 与 `data-slot` 挂在**同一个元素**上，于是皮肤那些
      `> [role=presentation]` / `> [role=presentation] > [role=dialog]` 规则**全部匹配不到**：
      对话框拿不到皮肤给的宽度，只能吃侧栏宽度（208px）→ 标签被挤成一字一行（就是竖排的原因），
      覆盖层也拿不到 `pointer-events:auto` 与全屏几何。

      DOM 位置仍留在侧栏内（不 portal）—— 这是当初刻意的，因为皮肤还会用
      `[data-slot='sidebar'] > :first-child > :has([role='dialog'])` 判断"设置是否打开"。

      外观样式都在 `global.css`，**故意低特异性**：有皮肤时让皮肤说了算，没皮肤时兜底。
    */
    <div {...{ [HOST_ATTRS.slot]: SLOTS.sidebarSettings }}>
      <div role="presentation">
        {/* 遮罩：必须放第一个（皮肤用 :first-child 定位它）；点击即关闭 */}
        <div data-ap-settings-mask onClick={onClose} aria-hidden="true" />

        <div role="dialog" aria-modal="true" aria-label="皮肤设置">
          <div
            {...{ [HOST_ATTRS.slot]: SLOTS.settingsHeader }}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 8,
              marginBottom: 10,
              paddingBottom: 8,
              borderBottom: '1px solid rgba(128,128,128,0.25)',
            }}
          >
            <Space size={6}>
              <SkinOutlined />
              {/* 用 --ap-text（面板现在是浅色对话框，不是深色侧栏了） */}
              <Typography.Text strong style={{ fontSize: 13, color: 'var(--ap-text)' }}>
                皮肤设置
              </Typography.Text>
            </Space>
            <Button size="small" type="text" icon={<CloseOutlined />} onClick={onClose} aria-label="关闭" />
          </div>

          {!activeId ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                <span style={{ fontSize: 12 }}>
                  还没有运行中的皮肤。
                  <br />
                  去「皮肤市场」点「运行 JS」。
                </span>
              }
            />
          ) : (
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              {running.length > 1 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ fontSize: 12 }}
                  message="有多个皮肤同时在跑（互斥机制只在点「运行 JS」时生效）"
                />
              )}
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                正在管理：{activeId}
              </Typography.Text>
              <SkinSettingsPanel skinId={activeId} compact />
            </Space>
          )}
        </div>
      </div>
    </div>
  );
}

/** 供别处复用的"启用某皮肤"快捷入口。 */
export async function quickEnable(skinId: string): Promise<void> {
  await enableSkin(skinId);
}
