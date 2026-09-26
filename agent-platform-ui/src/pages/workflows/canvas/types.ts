/**
 * 工作流画布：类型定义与画布 ↔ 后端契约映射。
 *
 * 画布用 FlowGram 的固定布局（与 Coze 工作流同款：顺序流 + 分支复合节点），
 * 后端用 `WorkflowDefinition`（nodes + next 链 + branches）。本文件是两者的字典。
 */

/** 画布节点类型（小写，FlowGram 惯例）→ 后端 NodeType 枚举。 */
export const CANVAS_TO_BACKEND: Record<string, string> = {
  start: 'Start',
  end: 'End',
  llm: 'LLM',
  knowledge: 'KnowledgeBase',
  code: 'Skill',
  http: 'Http',
  plugin: 'Plugin',
  agent: 'Agent',
  condition: 'Condition',
  tool: 'Tool',
  transform: 'Transform',
  // 2026-09-26 加：后端已有 LoopNodeExecutor / ParallelNodeExecutor。
  // ⚠️ 这两个类型在 adapter 里有**独立转换逻辑**（不能用通用的"容器 → branches"那条路径），
  //    见 adapter.ts 的 toBackend / toCanvas 注释。
  loop: 'Loop',
  parallel: 'Parallel',
};

/** 后端 NodeType 枚举 → 画布节点类型。 */
export const BACKEND_TO_CANVAS: Record<string, string> = Object.entries(CANVAS_TO_BACKEND)
  .reduce<Record<string, string>>((acc, [canvas, backend]) => {
    acc[backend] = canvas;
    return acc;
  }, {});

/** FlowGram 文档 JSON（只声明我们用到的字段）。 */
export interface FlowNodeJSON {
  id: string;
  type: string;
  blocks?: FlowNodeJSON[];
  data?: CanvasNodeData;
  meta?: Record<string, unknown>;
}

export interface FlowDocumentJSON {
  nodes: FlowNodeJSON[];
}

/** 画布节点的 data（同时承载后端 config / outputVar）。 */
export interface CanvasNodeData {
  /** 节点显示名 */
  title?: string;
  /** 结果写入上下文的变量名 */
  outputVar?: string;
  /** 节点配置（与后端 WorkflowNode.config 同构，便于无损往返） */
  config?: Record<string, unknown>;
  /** 节点卡片上的一行摘要（由表单字段派生，仅用于展示） */
  [key: string]: unknown;
}

/** 后端节点（与 WorkflowNode record 对齐）。 */
export interface BackendNode {
  id: string;
  type: string;
  name?: string | null;
  next?: string | string[] | null;
  inputMapping?: Record<string, string> | null;
  outputVar?: string | null;
  branches?: { condition: string; target: string }[] | null;
  config?: Record<string, unknown> | null;
}

/** 后端工作流定义（与 WorkflowDefinition record 对齐）。 */
export interface BackendDefinition {
  name?: string | null;
  nodes: BackendNode[];
  entryNode?: string | null;
  variables?: Record<string, unknown> | null;
}

/** 节点类型的展示元信息（节点库与节点卡片共用）。 */
export interface NodeTypeMeta {
  type: string;
  label: string;
  /** 节点库中的分组 */
  group: '基础' | '模型' | '能力' | '逻辑';
  /** 一句话说明（节点库 tooltip + 节点卡片副标题） */
  description: string;
  /** 强调色（节点卡片左侧色条与图标底色） */
  color: string;
}
