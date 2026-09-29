import { Input, InputNumber, Select, Switch, Alert, Space } from 'antd';
import type { PluginConfigField } from './plugin-config';

/**
 * 按插件声明的配置项生成挂载表单。
 *
 * <h3>它替代了什么</h3>
 * 之前挂载插件时只有一个自由 JSON 文本框（placeholder 还是 TTS 的示例字段），
 * 而插件说明里写着"在挂载配置里填入 API Key" —— 用户<b>不知道字段名该写什么</b>。
 * 现在插件自己声明字段，这里据此渲染：标签、密码框、下拉、数值范围、必填提示都由声明驱动。
 *
 * <h3>★ 为什么是"声明驱动"而不是"每种插件一个表单"</h3>
 * 后者要<b>改前端才能加插件</b>，外部 jar 插件永远拿不到配置界面。
 * 声明是数据 —— 与画布页 `node-metas.ts` 的 `CanvasFieldSpec` 是同一个模式
 * （kind 作分发键、认不出就退化），只是那边描述的是节点字段、这边是插件配置。
 *
 * <h3>密钥字段的处理</h3>
 * {@code secret: true} → 密码框，且**回显的是后端给的掩码**（如 `sk-***abcd`）。
 * 用户不动它直接保存时，后端会识别出"这是掩码"并保留原密钥
 * （见 `PluginConfigSecrets.seal`）—— 所以这里<b>绝不能</b>把掩码当成新值去校验或清空。
 *
 * <p>纯逻辑（初值组装、类型转换、必填校验）在 `plugin-config.ts`，可独立单测。</p>
 */

interface Props {
  fields: PluginConfigField[];
  value: Record<string, unknown>;
  onChange: (v: Record<string, unknown>) => void;
}

export function PluginConfigForm({ fields, value, onChange }: Props) {
  if (fields.length === 0) {
    return null;
  }
  const set = (key: string, v: unknown) => onChange({ ...value, [key]: v });

  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      {fields.some((f) => f.secret) && (
        <Alert
          type="info"
          showIcon
          message="密钥会加密保存，界面只显示掩码"
          description="要更换密钥就直接输入新的；不动它则保留原来那个。"
        />
      )}
      {fields.map((f) => (
        <div key={f.key}>
          <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.65)', marginBottom: 2 }}>
            {f.label}
            {f.required && <span style={{ color: '#ff4d4f' }}> *</span>}
          </div>
          {control(f, value[f.key], (v) => set(f.key, v))}
          {f.hint && (
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)', marginTop: 2, lineHeight: 1.5 }}>
              {f.hint}
            </div>
          )}
        </div>
      ))}
    </Space>
  );
}

/**
 * 按 kind 分发控件。default 分支退回文本框 ——
 * 后端将来加了新 kind 而前端还旧时，字段仍然能填（只是控件朴素），不会消失。
 */
function control(field: PluginConfigField, value: unknown, onChange: (v: unknown) => void) {
  const ph = field.placeholder ?? undefined;
  switch (field.kind) {
    case 'textarea':
      return <Input.TextArea rows={3} value={(value as string) ?? ''} placeholder={ph}
        onChange={(e) => onChange(e.target.value)} />;

    case 'number':
      return (
        <InputNumber
          style={{ width: '100%' }}
          value={value as number}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          step={field.step ?? undefined}
          placeholder={ph}
          onChange={(v) => onChange(v)}
        />
      );

    case 'select':
      return (
        <Select
          style={{ width: '100%' }}
          allowClear
          value={(value as string) || undefined}
          placeholder={ph ?? '请选择'}
          options={field.options ?? []}
          onChange={(v) => onChange(v)}
        />
      );

    case 'bool':
      return <Switch checked={value === true} onChange={(v) => onChange(v)} />;

    default:
      // 密钥用密码框：掩码回显时也是打码的，旁人从背后扫一眼看不到
      return field.secret ? (
        <Input.Password
          autoComplete="new-password"
          value={(value as string) ?? ''}
          placeholder={ph ?? '已配置（要更换请直接输入新的）'}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <Input value={(value as string) ?? ''} placeholder={ph}
          onChange={(e) => onChange(e.target.value)} />
      );
  }
}
