/**
 * 画布 JSON ↔ 后端 WorkflowDefinition 双向适配。
 *
 * 固定布局的语义是「顶层节点自上而下顺序执行」，后端是 `next` 链 + `branches` 分支，
 * 因此转换规则为：
 * - 顶层节点按顺序串成 next 链（最后一个节点 next = null）；
 * - Condition 节点的分支目标写在 `branches[].target`，此时该节点不再使用 next；
 * - 分支块（blocks）内的节点被扁平化进 nodes，保证后端能按 id 找到目标节点。
 *
 * <h3>三类复合节点的表达方式（2026-09-26 补全）</h3>
 * 画布上的 `block` 容器是"子节点挂载点"，三种复合节点都用它，但**映射到后端的三样东西**：
 *
 * | 画布类型 | blocks 的含义 | 转成后端 |
 * |---|---|---|
 * | `condition` | 每个块一个分支 | `branches[].target`（此时 `next = null`） |
 * | `parallel`  | 每个块一条并发分支 | **`next` 数组**（后端靠"多下游"触发并行） |
 * | `loop`      | 唯一块 = 循环体 | **`config.loop_body`**（不能用 next：会成环被校验器拒） |
 *
 * ⚠️ `loop` **不是**容器型 —— 它必须参与顶层 `next` 链（`next` 指向"循环结束后去哪"）。
 * 早期版本把 `loop` 归进 `isContainer`，那会让它的 `next` 被强制置为 null，循环结束后流程就断了。
 */
import {
  BACKEND_TO_CANVAS,
  CANVAS_TO_BACKEND,
  type BackendDefinition,
  type BackendNode,
  type FlowDocumentJSON,
  type FlowNodeJSON,
} from './types';

/**
 * 判断是否为「用 `branches` 表达分支」的容器型节点。
 *
 * <p>注意 **不含 `loop`** —— 循环体走 `config.loop_body`，且 Loop 本身是普通节点、要接在 `next` 链上。
 * 也不含 `parallel` —— 它的分支转成 `next` 数组。</p>
 */
function isContainer(type: string): boolean {
  return type === 'condition' || type === 'if';
}

/** 从后端节点的 `next` 取出分支目标列表（兼容字符串与数组）。 */
function nextTargetsOf(next: string | string[] | null | undefined): string[] {
  if (!next) return [];
  if (Array.isArray(next)) return next.filter(Boolean);
  return [next];
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

/**
 * 取「分支块的入口节点 id」—— 块内**第一个直接子节点**，不继续往下钻。
 *
 * <p>⚠️ 这里刻意**不递归**。早期实现是"一路钻到最深的第一个可执行节点"，
 * 当块里放的是另一个复合节点（如"循环体是一个并行节点"）时，它会直接返回并行**内部**的
 * 某个分支节点 —— 于是 `loop_body` 指向了 `a` 而不是 `par_0`，并行节点被整个跳过。
 * 正确语义是"块里挂的第一个节点"，那个节点自己会沿 next / 分支继续走。</p>
 *
 * <p>（`block` 类型的子节点是 FlowGram 的容器占位，要跳过；块内确实没有可执行节点时，
 * 退回块自身 id，保持与旧行为兼容、避免返回 null 让上层判空分支失效。）</p>
 */
function firstExecutableId(block: FlowNodeJSON | undefined): string | null {
  if (!block) return null;
  for (const child of block.blocks ?? []) {
    if (child && child.id && child.type !== 'block') {
      return child.id;
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

    // 并行：每个分支块的首个可执行节点 → `next` 数组。
    // 后端 DagEngine 见 next 有多个目标即并发执行（虚拟线程），并且各分支作用域已隔离。
    if (n.type === 'parallel') {
      const targets = (n.blocks ?? [])
        .map((block) => firstExecutableId(block))
        .filter((t): t is string => !!t);
      node.next = targets.length ? targets : null;
      node.branches = null;
      return node;
    }

    // 循环：唯一块的首个可执行节点 → `config.loop_body`。
    // ⚠️ 这里**不 return** —— Loop 必须继续走下面的"普通节点"分支去接顶层 next 链
    //    （next 指向"循环结束后去哪"，这是它与 Condition/Parallel 的关键区别）。
    if (n.type === 'loop') {
      const body = firstExecutableId((n.blocks ?? [])[0]);
      if (body) {
        node.config = { ...(node.config ?? {}), loop_body: body };
      }
    }

    if (isContainer(n.type)) {
      // 条件分支：每个块 → 一个 branch（目标取自块内首个可执行节点），此时不使用 next
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

    // 普通节点（含 loop）：顶层节点续接下一个顶层节点；分支内节点不参与顶层链
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
  // 由父节点的 blocks 承载、因此**不该出现在顶层顺序**里的节点：
  // ① 条件分支的目标（branches）；② 并行的各条分支（next 数组）；③ 循环的循环体（config.loop_body）。
  const isBranchTarget = new Set<string>();
  nodes.forEach((n) => (n.branches ?? []).forEach((b) => b.target && isBranchTarget.add(b.target)));
  nodes.forEach((n) => {
    if (n.type === 'Parallel') {
      nextTargetsOf(n.next).forEach((t) => isBranchTarget.add(t));
    }
    const body = (n.config as Record<string, unknown> | null)?.['loop_body'];
    if (typeof body === 'string' && body) {
      isBranchTarget.add(body);
    }
  });

  // 从 entry 沿 next 走一遍得到顶层顺序，未覆盖的节点按原顺序补齐。
  // ⚠️ 走到 branchTarget 时**跳过它但继续前进** —— 直接停下会让 Parallel 之后、
  //    或循环体之后的顶层节点排不进 order（它们与分支目标共用同一条 next 链）。
  const order: string[] = [];
  const seen = new Set<string>();
  let cursor = def?.entryNode ?? nodes[0]?.id;
  while (cursor && byId.has(cursor) && !seen.has(cursor)) {
    seen.add(cursor);
    if (!isBranchTarget.has(cursor)) {
      order.push(cursor);
    }
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
      return json;
    }

    // 并行：next 数组还原为多个分支块
    if (canvasType === 'parallel') {
      json.blocks = nextTargetsOf(n.next).map((target, idx) => ({
        id: `${n.id}_par_${idx}`,
        type: 'block',
        data: {},
        blocks: toNodeJson(target) ? [toNodeJson(target)!] : [],
      }));
      return json;
    }

    // 循环：config.loop_body 还原为唯一的循环体块
    if (canvasType === 'loop') {
      const body = (n.config as Record<string, unknown> | null)?.['loop_body'];
      json.blocks = [
        {
          id: `${n.id}_body`,
          type: 'block',
          data: { role: 'body' },
          blocks: typeof body === 'string' && toNodeJson(body) ? [toNodeJson(body)!] : [],
        },
      ];
      return json;
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
