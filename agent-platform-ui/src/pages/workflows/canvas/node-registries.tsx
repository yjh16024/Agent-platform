/**
 * 画布节点注册表：11 类节点的默认数据 + 配置表单（antd 控件 + FlowGram Field）。
 *
 * Field 的 `name` 相对 `node.data`，因此 `config.model` 会直接写进 `node.data.config.model`，
 * 与后端 `WorkflowNode.config` 同构 —— 保存时无需额外转换。
 */
import { Field, ValidateTrigger, type FieldRenderProps, type FormMeta } from '@flowgram.ai/fixed-layout-editor';
import { Input, InputNumber, Select, Switch, Typography } from 'antd';

import { NODE_FIELDS, NODE_METAS, NODE_META_MAP, type CanvasFieldSpec } from './node-metas';

/** 唯一 id（避免为画布单独引入 nanoid 依赖）。 */
let seq = 0;
function genId(prefix: string): string {
  seq += 1;
  return `${prefix}_${Date.now().toString(36)}${seq.toString(36)}`;
}

/** 单个字段的控件（值由 Field 注入）。 */
function control(spec: CanvasFieldSpec, field: FieldRenderProps<unknown>['field']) {
  const value = field.value as never;
  const onChange = field.onChange as (v: unknown) => void;

  switch (spec.kind) {
    case 'textarea':
      return (
        <Input.TextArea
          rows={3}
          value={(value as string) ?? ''}
          placeholder={spec.placeholder}
          onChange={(e) => onChange(e.target.value)}
        />
      );
    case 'number':
      return (
        <InputNumber
          style={{ width: '100%' }}
          value={value as number}
          placeholder={spec.placeholder}
          onChange={(v) => onChange(v)}
        />
      );
    case 'select':
      return (
        <Select
          style={{ width: '100%' }}
          allowClear
          value={value as string}
          placeholder={spec.placeholder ?? '请选择'}
          options={spec.options}
          onChange={(v) => onChange(v)}
        />
      );
    case 'switch':
      return <Switch checked={!!value} onChange={(v) => onChange(v)} />;
    default:
      return (
        <Input
          value={(value as string) ?? ''}
          placeholder={spec.placeholder}
          onChange={(e) => onChange(e.target.value)}
        />
      );
  }
}

/** 一个节点类型的配置表单（在右侧面板渲染）。 */
function makeFormMeta(type: string): FormMeta<Record<string, unknown>> {
  const specs = NODE_FIELDS[type] ?? [];
  return {
    render: () => (
      <div className="wf-form">
        {specs.length === 0 ? (
          <Typography.Text type="secondary">该节点无需配置</Typography.Text>
        ) : (
          specs.map((spec) => (
            <Field
              key={spec.name}
              name={spec.name}
              render={({ field }: FieldRenderProps<unknown>) => (
                <div className="wf-field">
                  <div className="wf-field__label">{spec.label}</div>
                  {control(spec, field.field)}
                </div>
              )}
            />
          ))
        )}
      </div>
    ),
    validateTrigger: ValidateTrigger.onChange,
  };
}

/** 各类型节点的默认 data。 */
function defaultData(type: string): Record<string, unknown> {
  const label = NODE_META_MAP[type]?.label ?? type;
  const base: Record<string, unknown> = { title: label, config: {} };
  switch (type) {
    case 'start':
      return { ...base, config: { inputKey: 'input' } };
    case 'llm':
      return {
        ...base,
        title: 'LLM 生成',
        outputVar: 'llm_output',
        config: { provider: 'auto', model: 'deepseek-chat', temperature: 0.7, prompt: '${input}' },
      };
    case 'knowledge':
      return { ...base, outputVar: 'kb_result', config: { query: '${input}', topK: 5, scoreThreshold: 0 } };
    case 'code':
      return { ...base, outputVar: 'code_output', config: { execType: 'prompt' } };
    case 'http':
      return { ...base, outputVar: 'http_output', config: { method: 'GET' } };
    case 'plugin':
      return { ...base, outputVar: 'plugin_output', config: {} };
    case 'tool':
      return { ...base, outputVar: 'tool_output', config: {} };
    case 'agent':
      return { ...base, outputVar: 'agent_output', config: { input: '${input}' } };
    case 'condition':
      return { ...base, outputVar: 'decision', config: {} };
    case 'transform':
      return { ...base, outputVar: 'transformed', config: { mapping: '{}' } };
    default:
      return base;
  }
}

/** FlowGram 节点注册表。 */
export const CANVAS_NODE_REGISTRIES = NODE_METAS.map((meta) => ({
  type: meta.type,
  info: {
    description: meta.description,
  },
  formMeta: makeFormMeta(meta.type),
  meta: {
    defaultExpanded: true,
  },
  onAdd() {
    return {
      id: genId(meta.type),
      type: meta.type,
      data: defaultData(meta.type),
    };
  },
}));
