/**
 * 画布节点外观与内联交互物料。
 *
 * FlowGram 的画布是 **headless** 的：内核只注册 `node-render`，其余渲染 key
 * （加号 `adder`、折叠 `collapse`、拖拽态 `drag-node` …）都必须由使用方自己实现，
 * 缺失任何一个都会抛 `Unknown render key` 把整棵 React 树打崩（整页白屏）。
 *
 * 本文件实现这些 key 的真实 UI：
 * - `NodeAdder`：节点下方/分支处的「+」，点击弹节点库、在锚点后插入（走 operation，可撤销）
 * - `NodeCollapse`：分支容器展开/收起
 * - `DragNode`：拖拽中的节点外观
 * - `NodeCard`：节点卡片（**整卡可拖拽**，与 Coze 一致）
 *
 * 未实现的 key 统一走 `renderPlaceholder`（渲染为空，满足内核契约且不画幽灵卡片）。
 */
import { useContext, useState } from 'react';
import { message, Popover } from 'antd';
import {
  useClientContext,
  useNodeRender,
  useStartDragNode,
  type FixedLayoutPluginContext,
  type FlowNodeEntity,
} from '@flowgram.ai/fixed-layout-editor';

import { NODE_METAS, NODE_META_MAP, nodeSummary } from './node-metas';
import { CANVAS_NODE_REGISTRIES } from './node-registries';
import { CanvasSelectionContext } from './selection';

/** 未实现的渲染 key：空占位（满足内核契约、不产生幽灵卡片）。 */
export function renderPlaceholder() {
  return null;
}

/** 兼容 FlowGram 两种传参形态：`{ node }` 包装或直接传节点实体。 */
function pickNode(props: unknown): FlowNodeEntity | undefined {
  if (!props) return undefined;
  const wrapped = (props as { node?: FlowNodeEntity }).node;
  const node = wrapped ?? (props as FlowNodeEntity);
  return node && typeof (node as { id?: string }).id === 'string' ? node : undefined;
}

/**
 * 生成新节点 JSON（复用节点注册表的默认数据，保证与拖入行为一致）。
 *
 * <p>三种「复合节点」需要带空的分支槽（FlowGram 的 `block` 容器）：条件分支 / 循环 / 并行。
 * 没有这些槽，节点就没有折叠按钮与分支内加号，用户无处挂载子节点 —— 而这些槽正是
 * adapter 转换的依据（条件 → `branches`、循环 → `config.loop_body`、并行 → `next` 数组）。</p>
 */
export function buildNodeJson(type: string) {
  const registry = CANVAS_NODE_REGISTRIES.find((r) => r.type === type);
  const base = registry?.onAdd
    ? registry.onAdd()
    : { id: `${type}_${Date.now().toString(36)}`, type, data: { title: type, config: {} } };
  const stamp = Date.now().toString(36);

  if (type === 'condition') {
    return {
      ...base,
      blocks: [
        { id: `${base.id}_branch_a_${stamp}`, type: 'block', data: { condition: 'true' }, blocks: [] },
        { id: `${base.id}_branch_b_${stamp}`, type: 'block', data: { condition: 'false' }, blocks: [] },
      ],
    };
  }

  // 循环：单个"循环体"槽。它的首个可执行节点会成为后端的 config.loop_body。
  if (type === 'loop') {
    return {
      ...base,
      blocks: [
        { id: `${base.id}_body_${stamp}`, type: 'block', data: { role: 'body' }, blocks: [] },
      ],
    };
  }

  // 并行：两个并发分支槽。每个槽的首个可执行节点会成为后端 next 数组的一项。
  if (type === 'parallel') {
    return {
      ...base,
      blocks: [
        { id: `${base.id}_par_a_${stamp}`, type: 'block', data: {}, blocks: [] },
        { id: `${base.id}_par_b_${stamp}`, type: 'block', data: {}, blocks: [] },
      ],
    };
  }

  return base;
}

/** 节点库面板（点击加号时弹出）。 */
function NodePicker({ onPick }: { onPick: (type: string) => void }) {
  return (
    <div className="wf-picker">
      {NODE_METAS.map((m) => (
        <button key={m.type} type="button" className="wf-picker__item" onClick={() => onPick(m.type)}>
          <span className="wf-picker__dot" style={{ background: m.color }} />
          <span className="wf-picker__text">
            <span className="wf-picker__label">{m.label}</span>
            <span className="wf-picker__desc">{m.description}</span>
          </span>
        </button>
      ))}
    </div>
  );
}

/**
 * 在锚点节点之后**精确插入**新节点。
 *
 * 必须用 document 层的 `addFromNode`（其实现是 `originTree.insertAfter(from, node)`）；
 * operation 层的同名方法在固定布局下会把新节点**追加到整条链的末尾**，
 * 表现为「点两个流程中间的加号，新节点却长到了下一个流程下面」。
 */
export function insertAfterNode(
  ctx: FixedLayoutPluginContext,
  anchorId: string,
  json: unknown
): { bounds?: unknown } | undefined {
  const doc = ctx.document as unknown as {
    addFromNode?: (from: string, node: unknown) => unknown;
    root?: { id: string };
    addBlock?: (target: string, data: unknown) => unknown;
  };
  if (typeof doc.addFromNode === 'function') {
    return doc.addFromNode(anchorId, json) as { bounds?: unknown } | undefined;
  }
  if (typeof doc.addBlock === 'function' && doc.root) {
    return doc.addBlock(doc.root.id, json) as { bounds?: unknown } | undefined;
  }
  return undefined;
}

/**
 * 「+」内联加号：`adder` / `branch-adder` / `draggable-adder` 共用。
 * 点击弹出节点库，在锚点节点之后插入新节点（精确插入，见 `insertAfterNode`）。
 */
export function NodeAdder(props: {
  /** 官方 Adder 契约：`from` = 加号上方那个节点（官方也用它做插入锚点） */
  from?: FlowNodeEntity;
  to?: FlowNodeEntity;
  /** 官方契约：所在节点是否 hover/激活（用于控制加号显隐；本实现用 CSS hover 亦可） */
  hoverActivated?: boolean;
  node?: FlowNodeEntity;
  renderTo?: FlowNodeEntity;
}) {
  const ctx = useClientContext();
  const [open, setOpen] = useState(false);
  /**
   * 锚点必须是 `from`（这个加号正上方那个节点）—— 在它之后插入，新节点才会长在**当前加号的位置**。
   * 曾误用 `renderTo`（该加号渲染到的节点，实际是下一个节点），导致新节点跑到下一个流程下面。
   */
  const anchor = props.from ?? props.node ?? props.renderTo;

  if (!anchor) return null;

  const add = (type: string) => {
    setOpen(false);
    try {
      // 精确插入到当前加号的位置（document.addFromNode 内部是 insertAfter）
      const created = insertAfterNode(ctx, anchor.id, buildNodeJson(type));
      message.success(`已添加「${NODE_META_MAP[type]?.label ?? type}」节点`);
      // 官方做法：插入后把新节点滚到视野中央
      const bounds = created?.bounds;
      if (bounds) {
        setTimeout(() => {
          (
            ctx.playground as unknown as { scrollToView?: (o: unknown) => void }
          ).scrollToView?.({ bounds, scrollToCenter: true });
        }, 10);
      }
    } catch (e) {
      message.error(`添加节点失败：${(e as Error).message}`);
    }
  };

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottom"
      arrow={false}
      content={<NodePicker onPick={add} />}
    >
      <button
        type="button"
        className="wf-adder"
        title="在此处添加节点"
        data-anchor={anchor.id}
        data-from={props.from?.id ?? ''}
        data-to={props.to?.id ?? ''}
        data-render-to={props.renderTo?.id ?? ''}
        data-node={props.node?.id ?? ''}
        onClick={(e) => e.stopPropagation()}
        // 阻止 mousedown 冒泡：否则按加号会同时触发画布平移（官方 Adder 同款处理）
        onMouseDown={(e) => e.stopPropagation()}
      >
        +
      </button>
    </Popover>
  );
}

/** 分支容器的展开/收起（`collapse` / `try-catch-collapse`）。 */
export function NodeCollapse(props: {
  node?: FlowNodeEntity;
  collapseNode?: FlowNodeEntity;
  activateNode?: FlowNodeEntity;
}) {
  const target = props.collapseNode ?? props.activateNode ?? props.node;
  if (!target) return null;
  return <CollapseButton node={target} />;
}

function CollapseButton({ node }: { node: FlowNodeEntity }) {
  const nodeRender = useNodeRender(node);
  const expanded = nodeRender.expanded;
  return (
    <button
      type="button"
      className="wf-collapse"
      title={expanded ? '收起分支' : '展开分支'}
      onClick={(e) => {
        e.stopPropagation();
        nodeRender.toggleExpand();
      }}
    >
      {expanded ? '−' : '+'}
    </button>
  );
}

/** 节点卡片（普通态 / 拖拽态共用外观）。 */
function NodeCard({
  node,
  nodeRender,
  dragging,
}: {
  node: FlowNodeEntity;
  nodeRender: ReturnType<typeof useNodeRender>;
  dragging?: boolean;
}) {
  const selection = useContext(CanvasSelectionContext);
  /**
   * 节点拖拽**必须显式发起**：FlowGram 内核不会自动绑定节点拖拽，
   * `@flowgram.ai/fixed-drag-plugin` 只在库外暴露了 `useStartDragNode().startDrag`，
   * 全库没有任何自动调用点。
   *
   * 两个易错点（曾踩）：
   *  1. `dragStartEntity` 必须传**单个节点实体**，传数组会抛 `d.getData is not a function`；
   *  2. 老写法 `nodeRender.startDrag(e)` 在 `FlowDragLayer` 未注册时会静默退化成空函数
   *     （`(e) => {}`），表现为「按了没反应也不报错」。
   */
  const { startDrag } = useStartDragNode() as unknown as {
    startDrag: (
      e: { clientX: number; clientY: number },
      opts: { dragStartEntity: FlowNodeEntity }
    ) => void;
  };
  const type = (node as unknown as { flowNodeType?: string }).flowNodeType ?? 'unknown';
  const data = (nodeRender.data ?? {}) as Record<string, unknown>;
  const meta = NODE_META_MAP[type];
  const color = meta?.color ?? '#8c8c8c';
  const title = (data.title as string) ?? meta?.label ?? type;
  const desc = nodeSummary(type, data);
  const selected = selection.selectedId === node.id;

  return (
    <div
      className={`wf-node${selected ? ' wf-node--selected' : ''}${dragging ? ' wf-node--dragging' : ''}`}
      style={{ borderLeftColor: color }}
      /**
       * 整张卡片可拖动（与 Coze 一致）。
       *
       * 必须用**捕获阶段**（`onMouseDownCapture`）来拦截：FlowGram 是在画布图层上监听原生
       * `mousedown` 的，它位于冒泡路径上，比 React 挂在 root 容器上的合成事件**更早**执行。
       * 若只在 `onMouseDown`（冒泡）里 stopPropagation，画布平移早就启动了 —— 而
       * `FlowDragLayer.startDrag` 开头有 `if (this.isGrab()) return`，节点拖拽会被直接顶掉。
       */
      onMouseDownCapture={(e) => {
        if (e.button !== 0 || dragging) return;
        e.stopPropagation();
        startDrag(e, { dragStartEntity: node });
      }}
      onClick={(e) => {
        // 阻止冒泡到画布，避免点节点时触发画布平移
        e.stopPropagation();
        selection.select(node.id);
      }}
      onMouseEnter={nodeRender.onMouseEnter}
      onMouseLeave={nodeRender.onMouseLeave}
      role="button"
      tabIndex={0}
    >
      <div className="wf-node__head">
        <span className="wf-node__dot" style={{ background: color }} />
        <span className="wf-node__title">{title}</span>
      </div>
      {desc ? <div className="wf-node__desc">{desc}</div> : null}
    </div>
  );
}

/** 带真实节点渲染器的节点视图（hook 必须在有节点时调用）。 */
function NodeView({ node, dragging }: { node: FlowNodeEntity; dragging?: boolean }) {
  const nodeRender = useNodeRender(node);
  return <NodeCard node={node} nodeRender={nodeRender} dragging={dragging} />;
}

/** `node-render`：画布上的普通节点。 */
export function renderDefaultNode(props: unknown) {
  const node = pickNode(props);
  if (!node) return null;
  return <NodeView node={node} />;
}

/** `drag-node`：拖拽过程中的节点外观。 */
export function DragNode(props: unknown) {
  const node = pickNode(props);
  if (!node) return null;
  return <NodeView node={node} dragging />;
}
