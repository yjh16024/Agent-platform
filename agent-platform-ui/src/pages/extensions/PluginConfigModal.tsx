import { useEffect, useState } from 'react';
import { App as AntApp, Button, Form, Input, InputNumber, Modal, Select, Switch, Typography } from 'antd';
import type { PluginConfigField, WebPluginItem } from '../../api/pluginWeb';
import { createPluginConfig } from '../../plugins/host/services';

const { Text, Paragraph } = Typography;

/**
 * 界面插件的**设置表单**（2026-10-19）。
 *
 * <h3>为什么由宿主渲染，而不是插件自己写设置页</h3>
 * 插件只要在 manifest 里声明字段（key / label / type / default / options），
 * 宿主就能渲染出一致的表单并负责存储 —— 这是 dsh 那类"插件有几十个配置项"的前提：
 * 作者不写 UI，用户点「设置」就能改。
 *
 * <p>值存在浏览器侧（`ap.plugin.&lt;id&gt;.config`，与 `ctx.storage` 同一套），
 * 保存后**立刻**通过 `ctx.config.onChange` 通知运行中的插件 —— 不需要重装或刷新。</p>
 */
export default function PluginConfigModal({
  plugin,
  open,
  onClose,
}: {
  plugin: WebPluginItem | null;
  open: boolean;
  onClose: () => void;
}) {
  const { message } = AntApp.useApp();
  const fields = plugin?.config ?? [];
  const [values, setValues] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (!open || !plugin) {
      return;
    }
    // 打开时读"默认值 + 已保存值"，与插件里 ctx.config.get() 拿到的是同一份
    setValues({ ...createPluginConfig(plugin.id).get() });
  }, [open, plugin]);

  const save = () => {
    if (!plugin) {
      return;
    }
    createPluginConfig(plugin.id).set(values);
    message.success('已保存，插件即时生效');
    onClose();
  };

  /** 按声明渲染控件；类型缺省时按 default 推断。 */
  const control = (f: PluginConfigField) => {
    const type = f.type ?? (typeof f.default === 'boolean' ? 'boolean' : typeof f.default === 'number' ? 'number' : 'text');
    if (type === 'boolean') {
      return (
        <Switch
          checked={values[f.key] === true}
          onChange={(v) => setValues((prev) => ({ ...prev, [f.key]: v }))}
        />
      );
    }
    if (type === 'number') {
      return (
        <InputNumber
          style={{ width: '100%' }}
          value={values[f.key] as number | undefined}
          min={f.min}
          max={f.max}
          step={f.step}
          onChange={(v) => setValues((prev) => ({ ...prev, [f.key]: v ?? undefined }))}
        />
      );
    }
    if (type === 'select') {
      const options = (f.options ?? []).map((o) =>
        typeof o === 'string' ? { label: o, value: o } : o,
      );
      return (
        <Select
          style={{ width: '100%' }}
          options={options}
          value={values[f.key] as string | number | undefined}
          onChange={(v) => setValues((prev) => ({ ...prev, [f.key]: v }))}
        />
      );
    }
    return (
      <Input
        value={(values[f.key] ?? '') as string}
        onChange={(e) => setValues((prev) => ({ ...prev, [f.key]: e.target.value }))}
      />
    );
  };

  return (
    <Modal
      title={`${plugin?.name ?? ''} · 设置`}
      open={open}
      onCancel={onClose}
      onOk={save}
      okText="保存"
      destroyOnClose
      width={560}
    >
      {fields.length === 0 ? (
        <Paragraph type="secondary">
          这个插件没有声明配置项（manifest 里没有 `config`）。
        </Paragraph>
      ) : (
        <>
          <Paragraph type="secondary" style={{ fontSize: 12 }}>
            这些字段由插件在 manifest 里声明，值保存在本机（`ap.plugin.{plugin?.id}.config`）。
          </Paragraph>
          <Form layout="vertical">
            {fields.map((f) => (
              <Form.Item
                key={f.key}
                label={f.label ?? f.key}
                extra={f.description ? <Text type="secondary" style={{ fontSize: 12 }}>{f.description}</Text> : undefined}
                style={{ marginBottom: 14 }}
              >
                {control(f)}
              </Form.Item>
            ))}
          </Form>
        </>
      )}
      <div style={{ textAlign: 'right' }}>
        <Button
          size="small"
          type="link"
          onClick={() => {
            // 恢复默认：清掉已保存值即可（get() 会回落到 manifest 默认值）
            if (!plugin) {
              return;
            }
            localStorage.removeItem(`ap.plugin.${plugin.id}.config`);
            setValues({ ...createPluginConfig(plugin.id).get() });
            createPluginConfig(plugin.id).set({});
            message.info('已恢复默认值');
          }}
        >
          恢复默认
        </Button>
      </div>
    </Modal>
  );
}
