/**
 * FlowGram 编辑器配置。
 *
 * 结构**照抄官方 `apps/demo-fixed-layout/src/hooks/use-editor-props.ts`**。
 *
 * 关键取舍：内联交互物料直接用官方物料包 `@flowgram.ai/fixed-semi-materials` 的
 * `defaultFixedSemiMaterials`（collapse / branch-adder / drag-node / draggable-adder /
 * 拖拽高亮 / slot 系列共 10 个 render key），不再自己逐个实现 —— FlowGram 画布是 headless 的，
 * 缺任何一个 render key 都会抛 `Unknown render key` 打崩整页，而官方这套是功能最全、验证最充分的实现。
 * 只有「加号」保留本平台实现（要用自己的 11 类节点库），其余全部走官方。
 */
import { useMemo } from 'react';
import { defaultFixedSemiMaterials } from '@flowgram.ai/fixed-semi-materials';
import { FlowLayoutDefault, type FixedLayoutProps } from '@flowgram.ai/fixed-layout-editor';

import { CANVAS_NODE_REGISTRIES } from './node-registries';
import { NodeAdder, renderDefaultNode } from './materials';
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
      defaultLayout: FlowLayoutDefault.VERTICAL_FIXED_LAYOUT,

      /**
       * 画布手势（与官方一致）。注意字段名就是库里的拼写 `ineractiveType`（FlowGram 自身 typo，
       * 类型定义也是错名），写成 `interactiveType` 会被静默忽略 → 手势退回 TRACKPAD。
       */
      playground: {
        ineractiveType: 'MOUSE',
        // 阻止 mac 浏览器手势翻页（官方注释）
        preventGlobalGesture: true,
      } as never,

      /**
       * 拖拽落点判定：**必须显式提供 canDrop**。
       * 缺省时任何落点都不被接受，表现为「节点完全拖不动」（官方这里返回 true）。
       */
      dragdrop: {
        canDrop: () => true,
      },

      /** 节点引擎：开启后节点的 `formMeta` 才会真正渲染（`nodeRender.form` 不再恒为空） */
      nodeEngine: {
        enable: true,
      },
      variableEngine: {
        enable: true,
      },

      materials: {
        components: {
          // 官方物料：折叠、分支加号、拖拽态、可拖入提示、拖拽高亮、slot 系列…
          ...defaultFixedSemiMaterials,
          // 加号换成平台自己的节点库（antd 风格 + 11 类节点说明）
          adder: NodeAdder,
        },
        renderDefaultNode,
      },

      history: {
        enable: true,
        enableChangeNode: true,
        onApply: () => onDirty(),
      },

      /** 默认节点注册（与官方一致：默认展开） */
      getNodeDefaultRegistry(type) {
        return {
          type,
          meta: { defaultExpanded: true },
        };
      },

      /** 限制滚动，防止把节点滚出视野（官方配置） */
      scroll: {
        enableScrollLimit: true,
      },

      /** 首屏渲染完成后自适应视图（官方做法，避免看到"空画布"） */
      onAllLayersRendered: (ctx) => {
        setTimeout(() => {
          ctx.tools.fitView();
          /**
           * 兜底把画布切到「鼠标友好」模式：左键拖拽平移画布 + 滚轮缩放（这正是 Coze 的手势）。
           *
           * 配置层的 `playground.ineractiveType` 在库里是著名的拼写笔误，且 preset 是浅合并
           * （`{...DEFAULT, ...opts}`），容易被默认值覆盖。真正的可靠入口是组件内的
           * `usePlaygroundTools().setInteractiveType('MOUSE')`（见 `WorkflowCanvas.tsx`）；
           * 这里按运行时能力再兜一次（`ctx.tools` 的 TS 类型只声明了 `fitView`）。
           */
          try {
            (
              ctx.tools as unknown as { setInteractiveType?: (t: 'MOUSE' | 'PAD') => void }
            ).setInteractiveType?.('MOUSE');
          } catch {
            /* 旧版本无此 API 时忽略 */
          }
        }, 10);
      },
    }),
    [initialData, onDirty]
  );
}
