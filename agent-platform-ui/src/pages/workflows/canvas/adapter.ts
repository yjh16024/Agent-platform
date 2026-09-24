/**
 * 画布 JSON ↔ 后端 WorkflowDefinition 双向适配。
 *
 * 固定布局的语义是「顶层节点自上而下顺序执行」，后端是 `next` 链 + `branches` 分支，
 * 因此转换规则为：
 * - 顶层节点按顺序串成 next 链（最后一个节点 next = null）；
 * - Condition 节点的分支目标写在 `branches[].target`，此时该节点不再使用 next；
 * - 分支块（blocks）内的节点被扁平化进 nodes，保证后端能按 id 找到目标节点。
 */
import {
  BACKEND_TO_CANVAS,
  CANVAS_TO_BACKEND,
  type BackendDefinition,
  type BackendNode,
  type FlowDocumentJSON,
  type FlowNodeJSON,
} from './types';

/** 判断是否为容器型节点（分支/循环），其 blocks 承载子节点。 */
function isContainer(type: string): boolean {
  return type === 'condition' || type === 'if' || type === 'loop';
}

/** 深度优先收集节点（含容器子节点），并按出现顺序返回。 */
function flatten(nodes: FlowNodeJSON[] | undefined, out: FlowNodeJSON[] = []): FlowNodeJSON[] {
  (nodes ?? []).forEach((n) => {
    if (!n || !n.id) return;
    // `block` 是 FlowGram 的分支容器，不是可执行节点（后端 NodeType 无此项），只收集其子节点
    const isBlockContainer = n.type === 'block';
    if (!isBlockContainer) {
      out.push(n);
    }
    if (n.blocks && n.blocks.length) {
      flatten(n.blocks, out);
    }
  });
  return out;
}

/** 取容器节点中某个分支块的首个可执行节点 id（跳过嵌套的容器）。 */
function firstExecutableId(block: FlowNodeJSON | undefined): string | null {
  if (!block) return null;
  if (block.blocks && block.blocks.length) {
    for (const child of block.blocks) {
      const found = firstExecutableId(child) ?? (child.id || null);
      if (found) return found;
    }
  }
  return block.id ?? null;
}

/**
 * 画布图 → 后端定义。
 *
 * @param doc      画布 JSON（ctx.document.toJSON()）
 * @param name     工作流名称
 * @param entryId  入口节点 id（一般是 start 节点）
 */
export function toBackend(
  doc: FlowDocumentJSON,
  name: string,
  entryId?: string | null
): BackendDefinition {
  const top = (doc?.nodes ?? []).filter((n) => n && n.id);
  const all = flatten(doc?.nodes);

  // 顶层顺序链（容器节点也参与串联，但其自身用 branches 跳转）
  const topIds = top.map((n) => n.id);

  const nodes: BackendNode[] = all.map((n) => {
    const data = (n.data ?? {}) as Record<string, unknown>;
    const backendType = CANVAS_TO_BACKEND[n.type] ?? n.type;
    const isTop = topIds.indexOf(n.id);

    const node: BackendNode = {
      id: n.id,
      type: backendType,
      name: (data.title as string) ?? n.type,
      outputVar: (data.outputVar as string) ?? null,
      config: (data.config as Record<string, unknown>) ?? {},
    };

    if (isContainer(n.type)) {
      // 容器节点：分支目标取自 blocks
      const branches = (n.blocks ?? [])
        .map((block) => ({
          condition: ((block.data ?? {}) as Record<string, unknown>).condition as string ?? 'true',
          target: firstExecutableId(block) ?? '',
        }))
        .filter((b) => !!b.target);
      node.branches = branches.length ? branches : null;
      node.next = null;
      return node;
    }

    // 普通节点：顶层节点续接下一个顶层节点；分支内节点不参与顶层链
    if (isTop >= 0) {
      const nextId = isTop < topIds.length - 1 ? topIds[isTop + 1] : null;
      node.next = nextId;
    } else {
      node.next = null;
    }
    return node;
  });

  return {
    name,
    nodes,
    entryNode: entryId ?? topIds[0] ?? null,
    variables: null,
  };
}

/**
 * 后端定义 → 画布图。
 *
 * 按 next 链还原顶层顺序；Condition 的 branches 还原为分支块（block 内放目标节点 id 引用）。
 */
export function toCanvas(def: BackendDefinition | null | undefined): FlowDocumentJSON {
  const nodes = def?.nodes ?? [];
  if (!nodes.length) {
    return { nodes: [] };
  }

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const isBranchTarget = new Set<string>();
  nodes.forEach((n) => (n.branches ?? []).forEach((b) => b.target && isBranchTarget.add(b.target)));

  // 从 entry 沿 next 走一遍得到顶层顺序，未覆盖的节点按原顺序补齐
  const order: string[] = [];
  const seen = new Set<string>();
  let cursor = def?.entryNode ?? nodes[0]?.id;
  while (cursor && byId.has(cursor) && !seen.has(cursor)) {
    seen.add(cursor);
    order.push(cursor);
    const n = byId.get(cursor)!;
    const next = Array.isArray(n.next) ? n.next[0] : n.next;
    cursor = (next as string) ?? null;
  }
  nodes
    .filter((n) => !seen.has(n.id) && !isBranchTarget.has(n.id))
    .forEach((n) => {
      seen.add(n.id);
      order.push(n.id);
    });

  const toNodeJson = (backendId: string): FlowNodeJSON | null => {
    const n = byId.get(backendId);
    if (!n) return null;
    const canvasType = BACKEND_TO_CANVAS[n.type] ?? n.type.toLowerCase();
    const json: FlowNodeJSON = {
      id: n.id,
      type: canvasType,
      data: {
        title: n.name ?? canvasType,
        outputVar: n.outputVar ?? undefined,
        config: n.config ?? {},
      },
    };
    if (canvasType === 'condition') {
      // 分支还原为 blocks：每个 block 内部放一个目标节点（可能是引用）
      json.blocks = (n.branches ?? []).map((b, idx) => ({
        id: `${n.id}_branch_${idx}`,
        type: 'block',
        data: { condition: b.condition },
        blocks: toNodeJson(b.target) ? [toNodeJson(b.target)!] : [],
      }));
    }
    return json;
  };

  return { nodes: order.map((id) => toNodeJson(id)).filter((n): n is FlowNodeJSON => !!n) };
}

/**
 * 画布默认图：开始 → LLM → 结束。
 *
 * 字段与后端契约严格对齐（config 用 snake_case、Start 声明 `input_key`、
 * 末尾必须有 End 终结节点），因此「创建工作流」时可直接把它经 `toBackend`
 * 转成后端定义入库，用户进画布后无需先修数据就能保存/试运行。
 */
export function defaultCanvas(): FlowDocumentJSON {
  return {
    nodes: [
      { id: 'start_0', type: 'start', data: { title: '开始', config: { input_key: 'input' } } },
      {
        id: 'llm_0',
        type: 'llm',
        data: {
          title: 'LLM 生成',
          outputVar: 'llm_output',
          config: { provider: 'auto', model: 'deepseek-chat', prompt: '${input}' },
        },
      },
      { id: 'end_0', type: 'end', data: { title: '结束', config: {} } },
    ],
  };
}
