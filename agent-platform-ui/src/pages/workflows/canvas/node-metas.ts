/**
 * 画布节点元信息（节点库分组、颜色、图标、说明）与表单字段规格。
 *
 * 字段规格的 `name` 是相对 `node.data` 的路径，例如 `config.model` 会写入
 * `node.data.config.model` —— 与后端 `WorkflowNode.config` 同构，便于无损往返。
 */
import type { NodeTypeMeta } from './types';

export interface CanvasFieldSpec {
  /** 相对 node.data 的路径 */
  name: string;
  label: string;
  kind: 'text' | 'textarea' | 'number' | 'select' | 'switch';
  options?: { label: string; value: string | number }[];
  placeholder?: string;
  /** 是否用于节点卡片摘要展示 */
  summary?: boolean;
}

/** 节点库与节点卡片共用的元信息。 */
export const NODE_METAS: NodeTypeMeta[] = [
  {
    type: 'start',
    label: '开始',
    group: '基础',
    description: '工作流入口，声明输入变量',
    color: '#52c41a',
  },
  {
    type: 'end',
    label: '结束',
    group: '基础',
    description: '工作流出口，返回上下文结果',
    color: '#8c8c8c',
  },
  {
    type: 'llm',
    label: 'LLM',
    group: '模型',
    description: '调用大模型，支持变量与提示词模板',
    color: '#1677ff',
  },
  {
    type: 'agent',
    label: 'Agent',
    group: '模型',
    description: '调用平台内的智能体（Agent 编排）',
    color: '#722ed1',
  },
  {
    type: 'knowledge',
    label: '知识库',
    group: '能力',
    description: '检索平台知识库并注入上下文（带引用）',
    color: '#13c2c2',
  },
  {
    type: 'code',
    label: '代码 / Skill',
    group: '能力',
    description: '执行平台 Skill（prompt / script / http）',
    color: '#fa8c16',
  },
  {
    type: 'http',
    label: 'HTTP',
    group: '能力',
    description: '发起 HTTP 请求并读取响应',
    color: '#2f54eb',
  },
  {
    type: 'plugin',
    label: '插件',
    group: '能力',
    description: '调用已挂载插件贡献的工具',
    color: '#eb2f96',
  },
  {
    type: 'tool',
    label: '工具',
    group: '能力',
    description: '调用工具注册中心里的任意工具',
    color: '#a0522d',
  },
  {
    type: 'condition',
    label: '条件分支',
    group: '逻辑',
    description: '按表达式分流到不同分支',
    color: '#faad14',
  },
  {
    type: 'transform',
    label: '变量转换',
    group: '逻辑',
    description: '字段重命名 / 变量映射',
    color: '#595959',
  },
];

export const NODE_META_MAP: Record<string, NodeTypeMeta> = NODE_METAS.reduce(
  (acc, m) => {
    acc[m.type] = m;
    return acc;
  },
  {} as Record<string, NodeTypeMeta>
);

/** 各节点类型的配置字段。 */
export const NODE_FIELDS: Record<string, CanvasFieldSpec[]> = {
  start: [
    {
      name: 'config.input_key',
      label: '输入变量名',
      kind: 'text',
      placeholder: 'input（运行时输入写入该变量）',
      summary: true,
    },
  ],
  end: [],
  llm: [
    {
      name: 'config.provider',
      label: '服务商',
      kind: 'select',
      options: [
        { label: '自动路由(auto)', value: 'auto' },
        { label: 'deepseek', value: 'deepseek' },
        { label: 'openai', value: 'openai' },
        { label: 'qwen', value: 'qwen' },
        { label: 'anthropic', value: 'anthropic' },
      ],
    },
    { name: 'config.model', label: '模型', kind: 'text', placeholder: 'deepseek-chat', summary: true },
    { name: 'config.temperature', label: '温度', kind: 'number', placeholder: '0.7' },
    { name: 'config.max_tokens', label: '最大 Token', kind: 'number', placeholder: '2048' },
    {
      name: 'config.system_prompt',
      label: '系统提示词',
      kind: 'textarea',
      placeholder: '你是一名专业的助手…',
    },
    {
      name: 'config.prompt',
      label: '用户提示词',
      kind: 'textarea',
      placeholder: '支持 ${变量名} 引用上游输出，例如 ${input}',
      summary: true,
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'llm_output' },
  ],
  agent: [
    { name: 'config.agent_id', label: '目标 Agent ID', kind: 'text', placeholder: 'agent_xxx', summary: true },
    {
      name: 'config.input',
      label: '输入内容',
      kind: 'textarea',
      placeholder: '支持 ${变量名}，默认取 input',
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'agent_output' },
  ],
  knowledge: [
    {
      name: 'config.kb_ids',
      label: '知识库 ID',
      kind: 'textarea',
      placeholder: 'kb_xxx（多个用逗号或换行分隔）',
      summary: true,
    },
    {
      name: 'config.query',
      label: '检索语句',
      kind: 'textarea',
      placeholder: '${input}',
    },
    { name: 'config.top_k', label: 'TopK', kind: 'number', placeholder: '5' },
    { name: 'config.score_threshold', label: '最低相似度', kind: 'number', placeholder: '0.0' },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'kb_result' },
  ],
  code: [
    { name: 'config.skill_id', label: 'Skill', kind: 'text', placeholder: 'skill_xxx', summary: true },
    {
      name: 'config.exec_type',
      label: '执行类型',
      kind: 'select',
      options: [
        { label: 'prompt（渲染提示词模板）', value: 'prompt' },
        { label: 'script（执行 Skill 脚本）', value: 'script' },
        { label: 'http（POST 到端点）', value: 'http' },
      ],
    },
    { name: 'config.command', label: '命令 / 入口', kind: 'text', placeholder: 'scripts/main.py 或 http 路径' },
    {
      name: 'config.args',
      label: '参数(JSON)',
      kind: 'textarea',
      placeholder: '{"key": "${input}"}',
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'code_output' },
  ],
  http: [
    { name: 'config.url', label: 'URL', kind: 'text', placeholder: 'https://api.example.com/v1/items', summary: true },
    {
      name: 'config.method',
      label: '方法',
      kind: 'select',
      options: [
        { label: 'GET', value: 'GET' },
        { label: 'POST', value: 'POST' },
        { label: 'PUT', value: 'PUT' },
        { label: 'DELETE', value: 'DELETE' },
      ],
    },
    { name: 'config.headers', label: 'Headers(JSON)', kind: 'textarea', placeholder: '{"Authorization":"Bearer xxx"}' },
    { name: 'config.body', label: 'Body', kind: 'textarea', placeholder: '{"q": "${input}"}' },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'http_output' },
  ],
  plugin: [
    {
      name: 'config.tool_name',
      label: '插件工具名',
      kind: 'text',
      placeholder: 'plugin_xxx 提供的工具名（见「工具调试」页）',
      summary: true,
    },
    { name: 'config.arguments', label: '参数(JSON)', kind: 'textarea', placeholder: '{"city": "北京"}' },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'plugin_output' },
  ],
  tool: [
    { name: 'config.tool_name', label: '工具名', kind: 'text', placeholder: 'weather / calculator …', summary: true },
    { name: 'config.arguments', label: '参数(JSON)', kind: 'textarea', placeholder: '{"city": "北京"}' },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'tool_output' },
  ],
  condition: [
    {
      name: 'config.expression',
      label: '条件说明',
      kind: 'text',
      placeholder: '如：命中关键词则转人工（分支表达式在分支上配置）',
      summary: true,
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'decision' },
  ],
  transform: [
    {
      name: 'config.mapping',
      label: '字段映射(JSON)',
      kind: 'textarea',
      placeholder: '{"out": "${input}"}',
      summary: true,
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'transformed' },
  ],
};

/** 节点卡片上的一行摘要（取 summary 字段的第一个非空值）。 */
export function nodeSummary(type: string, data: Record<string, unknown> | undefined): string {
  const specs = NODE_FIELDS[type] ?? [];
  const d = data ?? {};
  for (const spec of specs.filter((s) => s.summary)) {
    const raw = spec.name.split('.').reduce<unknown>((acc, k) => {
      if (acc && typeof acc === 'object') return (acc as Record<string, unknown>)[k];
      return undefined;
    }, d);
    if (raw !== undefined && raw !== null && String(raw).trim() !== '') {
      const text = String(raw).replace(/\s+/g, ' ').trim();
      return text.length > 42 ? `${text.slice(0, 42)}…` : text;
    }
  }
  return NODE_META_MAP[type]?.description ?? '';
}
