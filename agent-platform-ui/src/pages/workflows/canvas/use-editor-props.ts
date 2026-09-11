/**
 * FlowGram 编辑器配置。
 *
 * 除物料表外的配置保持最小：只覆盖画布必需项（数据、节点注册、布局、拖拽、历史钩子），
 * 其余交给 FlowGram 默认预设（缩放、连线、撤销重做、快捷键等开箱可用）。
 */
import { useMemo } from 'react';
import { FlowLayoutDefault, type FixedLayoutProps } from '@flowgram.ai/fixed-layout-editor';

import { CANVAS_NODE_REGISTRIES } from './node-registries';
import {
  DragNode,
  NodeAdder,
  NodeCollapse,
  renderDefaultNode,
  renderPlaceholder,
} from './materials';
import type { FlowDocumentJSON } from './types';

/**
 * 渲染物料表。
 *
 * **必须覆盖 `FlowRendererKey` 全集**：内核的 materials 插件只注册 `node-render`，
 * 其余 key 在渲染（含拖拽服务初始化）时经 `FlowRendererRegistry.getRendererComponent` 索取，
 * 而该方法**没有兜底** —— 缺任何一个都会抛 `Unknown render key xxx`，
 * 打断整棵 React 树、画布整页白屏（这正是「点进画布一片空白」的根因）。
 *
 * 这里：加号 / 折叠 / 拖拽态给出真实实现，其余 key 用空占位。
 */
const MATERIALS: NonNullable<FixedLayoutProps['materials']> = {
  renderDefaultNode,
  components: {
    // 内联「+」：普通连接点、分支内、拖拽可落入位置共用同一实现
    adder: NodeAdder,
    'branch-adder': NodeAdder,
    'draggable-adder': NodeAdder,
    // 分支容器展开/收起
    collapse: NodeCollapse,
    'try-catch-collapse': NodeCollapse,
    // 拖拽中的节点外观
    'drag-node': DragNode,
    // 以下 key 暂无 UI（空占位，仅满足内核契约）
    'drag-highlight-adder': renderPlaceholder,
    'drag-branch-highlight-adder': renderPlaceholder,
    'selector-box-popover': renderPlaceholder,
    'context-menu-popover': renderPlaceholder,
    'sub-canvas': renderPlaceholder,
    'slot-adder': renderPlaceholder,
    'slot-label': renderPlaceholder,
    'slot-collapse': renderPlaceholder,
    'arrow-renderer': renderPlaceholder,
    'marker-arrow': renderPlaceholder,
    'marker-active-arrow': renderPlaceholder,
  },
};

export function useEditorProps(
  initialData: FlowDocumentJSON,
  onDirty: () => void
): FixedLayoutProps {
  return useMemo<FixedLayoutProps>(
    () => ({
      background: true,
      readonly: false,
      initialData,
      nodeRegistries: CANVAS_NODE_REGISTRIES,
      allNodesDefaultExpanded: true,
      defaultLayout: FlowLayoutDefault.VERTICAL_FIXED_LAYOUT,
      playground: {
        interactiveType: 'MOUSE' as never,
        // 允许画布默认手势：按住空白平移、滚轮/双指缩放
        preventGlobalGesture: false,
      },
      // 启用画布内节点拖拽（fixed-drag-plugin）：拖动节点卡片上的把手可调整顺序
      dragdrop: { enable: true },
      materials: MATERIALS,
      history: {
        enable: true,
        enableChangeNode: true,
        onApply: () => onDirty(),
      },
    }),
    [initialData, onDirty]
  );
}
