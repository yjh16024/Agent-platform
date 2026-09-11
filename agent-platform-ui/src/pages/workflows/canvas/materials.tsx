/**
 * 画布节点外观（用本平台风格渲染，不引入 FlowGram 的 Semi 物料包）。
 *
 * FlowGram 的 `materials.renderDefaultNode` 会以 `{ node }` 作为 props 渲染；
 * 这里做防御式取值，兼容 props 直接就是 node 的情况。
 * 点击节点通过 context 冒泡给外层，由右侧面板渲染配置表单。
 */
import { useContext } from 'react';

import { NODE_META_MAP, nodeSummary } from './node-metas';
import { CanvasSelectionContext } from './selection';

interface NodeLikeProps {
  node?: {
    flowNodeType?: string;
    type?: string;
    id?: string;
    data?: Record<string, unknown>;
    activated?: boolean;
  };
  flowNodeType?: string;
  type?: string;
  id?: string;
  data?: Record<string, unknown>;
}

export function renderDefaultNode(props: NodeLikeProps) {
  const selection = useContext(CanvasSelectionContext);
  const node = (props?.node ?? props) as NodeLikeProps;
  const type = node?.flowNodeType ?? node?.type ?? 'unknown';
  const data = (node?.data ?? {}) as Record<string, unknown>;
  const meta = NODE_META_MAP[type];
  const color = meta?.color ?? '#8c8c8c';
  const title = (data.title as string) ?? meta?.label ?? type;
  const desc = nodeSummary(type, data);
  const nodeId = node?.id;
  const selected = !!nodeId && selection.selectedId === nodeId;

  return (
    <div
      className={`wf-node${selected ? ' wf-node--selected' : ''}`}
      style={{ borderLeftColor: color }}
      onClick={(e) => {
        // 阻止冒泡到画布，避免点击节点时触发画布其他交互
        e.stopPropagation();
        if (nodeId) {
          selection.select(nodeId);
        }
      }}
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
