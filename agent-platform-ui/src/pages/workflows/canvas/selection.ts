/**
 * 画布选中态上下文：节点点击后把 id 冒泡给外层，由右侧面板渲染该节点的配置表单。
 *
 * 说明：FlowGram 的节点组件在编辑器内部的 React 树中渲染，与外层面板处于同一 React 树，
 * 因此用 context 沟通最简单，且不依赖额外的 selection API。
 */
import { createContext } from 'react';

export interface CanvasSelection {
  selectedId?: string;
  select: (nodeId?: string) => void;
}

export const CanvasSelectionContext = createContext<CanvasSelection>({
  select: () => undefined,
});
