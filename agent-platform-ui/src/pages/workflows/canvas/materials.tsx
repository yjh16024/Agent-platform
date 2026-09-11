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
 * - `NodeCard`：节点卡片（含拖拽把手 → `nodeRender.startDrag`）
 *
 * 未实现的 key 统一走 `renderPlaceholder`（渲染为空，满足内核契约且不画幽灵卡片）。
 */
import { useContext, useState } from 'react';
import { message, Popover } from 'antd';
import {
  useClientContext,
  useNodeRender,
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
 * 条件分支额外带上两个空分支槽（FlowGram 的 `block` 容器）：否则条件节点没有分支、
 * 折叠按钮与分支内加号都不会出现，用户也无处填写分支条件。
 */
export function buildNodeJson(type: string) {
  const registry = CANVAS_NODE_REGISTRIES.find((r) => r.type === type);
  const base = registry?.onAdd
    ? registry.onAdd()
    : { id: `${type}_${Date.now().toString(36)}`, type, data: { title: type, config: {} } };
  if (type !== 'condition') return base;
  const stamp = Date.now().toString(36);
  return {
    ...base,
    blocks: [
      { id: `${base.id}_branch_a_${stamp}`, type: 'block', data: { condition: 'true' }, blocks: [] },
      { id: `${base.id}_branch_b_${stamp}`, type: 'block', data: { condition: 'false' }, blocks: [] },
    ],
  };
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
 * 「+」内联加号：`adder` / `branch-adder` / `draggable-adder` 共用。
 * 点击弹出节点库，在锚点节点之后插入新节点（`operation.addFromNode`，支持撤销重做）。
 */
export function NodeAdder(props: { node?: FlowNodeEntity; renderTo?: FlowNodeEntity }) {
  const ctx = useClientContext();
  const [open, setOpen] = useState(false);
  const anchor = props.renderTo ?? props.node;

  if (!anchor) return null;

  const add = (type: string) => {
    setOpen(false);
    try {
      ctx.operation.addFromNode(anchor.id, buildNodeJson(type) as never);
      message.success(`已添加「${NODE_META_MAP[type]?.label ?? type}」节点`);
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
        onClick={(e) => e.stopPropagation()}
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
        <span
          className="wf-node__grip"
          title="按住拖动调整位置"
          onMouseDown={(e) => {
            e.stopPropagation();
            nodeRender.startDrag(e);
          }}
        >
          ⠿
        </span>
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
