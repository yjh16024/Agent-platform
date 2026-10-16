/**
 * 画布节点元信息（节点库分组、颜色、图标、说明）与表单字段规格。
 *
 * 字段规格的 `name` 是相对 `node.data` 的路径，例如 `config.model` 会写入
 * `node.data.config.model` —— 与后端 `WorkflowNode.config` 同构，便于无损往返。
 */
import type { NodeTypeMeta } from './types';

/**
 * 字段选项的**动态来源** —— 从平台已有内容读取，而不是让用户手填 id。
 *
 * <h3>为什么要有它（2026-10-12）</h3>
 * 在此之前，Agent / 知识库 / Skill / 工具这些字段都是**纯文本框**，要求用户
 * 手打 `agent_xxx` / `kb_xxx` —— 而那些 id 只在列表页的 URL 里能见到，
 * 用户的做法通常是"回列表页点开、从地址栏抄一段回来"。
 * 更糟的是**抄错了不报错**：保存能过，跑起来才说"找不到该智能体"。
 *
 * <p>改成下拉之后，用户看到的是名字、选的是对象，id 由系统填 ——
 * 抄错的可能从根上消失了。文本框仍然保留（下拉允许自由输入），
 * 因为有些场景（脚本批量建工作流、填变量引用 `${kb_id}`）确实要手写。</p>
 */
export type FieldSource =
  /** 平台智能体（`agents` 列表，值取 agentId） */
  | 'agents'
  /** 工具注册中心（内置 / HTTP / MCP / 插件工具，值取工具名） */
  | 'tools'
  /** 知识库（值取 kbId） */
  | 'knowledgeBases'
  /** Skills（值取 skillId） */
  | 'skills'
  /** 模型服务商（值取 provider 标识，如 deepseek） */
  | 'providers'
  /** 模型名（取决于同节点的 config.provider，取不到就退回全部模型） */
  | 'models';

export interface CanvasFieldSpec {
  /** 相对 node.data 的路径 */
  name: string;
  label: string;
  /**
   * 控件类型。
   *
   * <p>`multiSelect` 是给**多值字段**用的（目前只有知识库的 `kb_ids`）：
   * 它存的仍然是**一个字符串**（逗号分隔），因为后端读的就是这个形式
   * （`KnowledgeBaseNodeExecutor` 按逗号/换行切开）。
   * 用 `select` 会出问题：单选会把用户已选的其它知识库覆盖掉。</p>
   */
  kind: 'text' | 'textarea' | 'number' | 'select' | 'multiSelect' | 'switch';
  options?: { label: string; value: string | number }[];
  /**
   * 动态选项来源。与 `options` 二选一；同时存在时**以 source 为准**，
   * 但**加载失败会退回 `options`**（宁可给一份静态候选，也不要空下拉）。
   */
  source?: FieldSource;
  /**
   * 选中某项后，把它的**参数模板**写进 `argsTemplateTo` 指向的另一个字段。
   *
   * <p>用于"选完工具 → `config.arguments` 自动填上该工具的入参骨架"：
   * 模板由工具的 `inputSchema()` 推导（见 `toolArgsTemplate`），
   * 用户在此基础上改值，不必再去翻文档查这个工具要传什么。</p>
   *
   * <p>仅在**该目标字段当前为空**时填充 —— 否则用户手写的参数会被选一次工具就冲掉。</p>
   */
  argsTemplateTo?: string;
  placeholder?: string;
  /** 是否用于节点卡片摘要展示 */
  summary?: boolean;
  /** 下拉是否允许用户输入不在列表里的值（默认允许）。 */
  freeInput?: boolean;
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
    type: 'loop',
    label: '循环',
    group: '逻辑',
    description: '重复执行循环体；有迭代上限与总超时两道安全阀',
    color: '#c41d7f',
  },
  {
    type: 'parallel',
    label: '并行',
    group: '逻辑',
    description: '并发执行多个分支；各分支变量互相隔离',
    color: '#08979c',
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
      // 从数据字典读（与「平台设置 → 模型设置」同一个来源），不再各写一份静态清单
      source: 'providers',
      // 字典拉不到时退回这份静态候选：**auto 必须留**，它是「跟随平台默认」的意思
      options: [
        { label: '自动路由(auto)', value: 'auto' },
        { label: 'deepseek', value: 'deepseek' },
        { label: 'openai', value: 'openai' },
        { label: 'qwen', value: 'qwen' },
        { label: 'anthropic', value: 'anthropic' },
      ],
    },
    {
      name: 'config.model',
      label: '模型',
      kind: 'select',
      source: 'models',
      placeholder: '平台当前模型；也可直接输入其它模型名',
      summary: true,
    },
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
    {
      name: 'config.agent_id',
      label: '目标智能体',
      kind: 'select',
      source: 'agents',
      placeholder: '从列表选择，或直接输入 ID',
      summary: true,
    },
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
      label: '知识库',
      kind: 'multiSelect',
      source: 'knowledgeBases',
      placeholder: '从列表选择（可多选），或粘贴 ID',
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
    {
      name: 'config.skill_id',
      label: 'Skill',
      kind: 'select',
      source: 'skills',
      placeholder: '从列表选择，或直接输入 ID',
      summary: true,
    },
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
      kind: 'select',
      source: 'tools',
      // 选完就把该工具的入参骨架填进参数框（见 field-options 的 schemaToArgsTemplate）
      argsTemplateTo: 'config.arguments',
      placeholder: '从列表选择插件贡献的工具（会自动带出参数），或直接输入',
      summary: true,
    },
    {
      name: 'config.arguments',
      label: '参数(JSON)',
      kind: 'textarea',
      // 说明"可以重新生成"：填充只在它为空时发生，用户清了再选一次即可
      placeholder: '选完工具会自动填入参数骨架；可自行修改（清空后重选可再生成）',
    },
    { name: 'outputVar', label: '输出变量名', kind: 'text', placeholder: 'plugin_output' },
  ],
  tool: [
    {
      name: 'config.tool_name',
      label: '工具名',
      kind: 'select',
      source: 'tools',
      argsTemplateTo: 'config.arguments',
      placeholder: '从列表选择工具（会自动带出参数），或直接输入',
      summary: true,
    },
    {
      name: 'config.arguments',
      label: '参数(JSON)',
      kind: 'textarea',
      placeholder: '选完工具会自动填入参数骨架；可自行修改（清空后重选可再生成）',
    },
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
  // 循环体入口由画布上的分支块决定（转成后端的 config.loop_body），**不在表单里填** ——
  // 让用户手打节点 id 既难用又易错。其余配置项与后端 LoopNodeExecutor 一一对应。
  loop: [
    {
      name: 'config.max_iterations',
      label: '最大迭代次数',
      kind: 'number',
      placeholder: '20（硬上限 1000）',
      summary: true,
    },
    {
      name: 'config.timeout_seconds',
      label: '总超时(秒)',
      kind: 'number',
      placeholder: '120',
    },
    {
      name: 'config.while',
      label: '继续条件',
      kind: 'text',
      placeholder: '留空则跑满迭代次数；如 ${i} < 5',
    },
    {
      name: 'config.index_var',
      label: '迭代序号变量名',
      kind: 'text',
      placeholder: 'i（从 0 开始，供循环体引用）',
    },
  ],
  // 并行无配置项：分支由画布上的多个分支块决定（转成后端的 next 数组）。
  parallel: [
    { name: 'outputVar', label: '分支清单变量名', kind: 'text', placeholder: 'par_info' },
  ],
};

/**
 * 节点卡片上的一行摘要（取 summary 字段的第一个非空值）。
 *
 * <h3>★ 都为空时不再显示节点描述（2026-10-12 改）</h3>
 * 原来的兜底是显示节点的 description（如 HTTP 节点显示「发起 HTTP 请求并读取响应」）——
 * 那行字**描述的是这个节点能干什么，而不是它现在缺什么**，于是用户看到卡片"有内容"，
 * 以为已经配好了，一保存却被拦下：
 *
 * <pre>
 * Workflow validation failed: Node http_mv4wnwp46 (Http) requires config.url
 * </pre>
 *
 * 这类报错还只在**保存时**出现，而画布上没有任何视觉线索指向它 ——
 * 用户面对的是"三个节点、一串英文、不知道该点哪个"。
 *
 * ⇒ 现在改为**指出第一个待填的字段**。措辞用「未填写」而非「必填」是有意的：
 * `summary` 字段里只有一部分是后端强制的必填项（如 http.url / tool.tool_name），
 * 另一些只是"建议填"（如 condition.expression 不填也能存）。
 * 卡片上加一句**不断言必填**的提示，两种情况都说得通，也不会误导。
 */
export function nodeSummary(type: string, data: Record<string, unknown> | undefined): string {
  const specs = NODE_FIELDS[type] ?? [];
  const d = data ?? {};
  const summarySpecs = specs.filter((s) => s.summary);
  for (const spec of summarySpecs) {
    const raw = spec.name.split('.').reduce<unknown>((acc, k) => {
      if (acc && typeof acc === 'object') return (acc as Record<string, unknown>)[k];
      return undefined;
    }, d);
    if (raw !== undefined && raw !== null && String(raw).trim() !== '') {
      const text = String(raw).replace(/\s+/g, ' ').trim();
      return text.length > 42 ? `${text.slice(0, 42)}…` : text;
    }
  }
  // 有 summary 字段但一个都没填 —— 说出缺的那个，别让用户去猜
  if (summarySpecs.length > 0) {
    return `⚠️ 未填写：${summarySpecs[0].label}`;
  }
  // 本来就没有 summary 字段的类型（如 condition 的分支在分支上配）才回落到描述
  return NODE_META_MAP[type]?.description ?? '';
}
