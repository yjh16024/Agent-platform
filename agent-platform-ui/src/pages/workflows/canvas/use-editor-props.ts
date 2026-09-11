/**
 * FlowGram 编辑器配置。
 *
 * 刻意保持"最小配置"：只覆盖画布必需项（数据、节点注册、物料、布局、自动保存钩子），
 * 其余交给 FlowGram 默认预设（画布缩放、连线、撤销重做、快捷键等开箱可用）。
 */
import { useMemo } from 'react';
import { FlowLayoutDefault, type FixedLayoutProps } from '@flowgram.ai/fixed-layout-editor';

import { CANVAS_NODE_REGISTRIES } from './node-registries';
import { renderDefaultNode } from './materials';
import type { FlowDocumentJSON } from './types';

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
        ineractiveType: 'MOUSE',
        preventGlobalGesture: true,
      },
      materials: {
        renderDefaultNode,
      },
      history: {
        enable: true,
        enableChangeNode: true,
        onApply: () => onDirty(),
      },
    }),
    [initialData, onDirty]
  );
}
