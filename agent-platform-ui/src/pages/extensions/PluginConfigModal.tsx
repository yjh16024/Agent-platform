import { useEffect, useState } from 'react';
import { App as AntApp, Button, Form, Input, InputNumber, Modal, Select, Switch, Typography } from 'antd';
import type { PluginConfigField, WebPluginItem } from '../../api/pluginWeb';
import { createPluginConfig } from '../../plugins/host/services';
import { listAgents } from '../../api/agents';
import type { AgentResponse } from '../../api/types';

const { Text, Paragraph } = Typography;

/**
 * 桌面壳的 IPC 桥（preload 暴露）。
 *
 * 这里不直接写 `window.apHost` 是因为它没有全局类型声明（`hostClient.ts` 也是
 * 用局部断言取的），而为一个弹窗去加 `declare global` 会污染所有文件。
 */
function nodeBridge() {
  return (
    window as unknown as {
      apHost?: {
        available?: boolean;
        invokePlugin?: (id: string, method: string, args?: unknown) => Promise<unknown>;
      };
    }
  ).apHost;
}

/**
 * 界面插件的**设置表单**（2026-10-19，2026-10-20 扩展）。
 *
 * <h3>为什么由宿主渲染</h3>
 * 插件只要在 manifest 里声明字段（key / label / type / default / options），
 * 宿主就能渲染出一致的表单并负责存储 —— 这是"插件有几十个配置项"的前提：
 * 作者不写 UI，用户点「设置」就能改。
 *
 * <h3>★ 有 Node 侧的插件：配置以**插件文件**为准（2026-10-20）</h3>
 * 只写 localStorage 是不够的：`main.js` 跑在 Electron 的 `utilityProcess` 里，
 * **读不到浏览器的 localStorage**。所以对声明了 `main` 的插件：
 * <ul>
 *   <li>打开时先 `invokePlugin('getConfig')` 取插件自己的配置（以它为准）；</li>
 *   <li>保存时除写 localStorage，还要 `invokePlugin('saveConfig')` 推给插件。</li>
 * </ul>
 * 插件侧没实现这两个方法时静默忽略 —— 纯前端插件照旧只走 localStorage。
 *
 * <h3>人格 / 系统提示词不在这里</h3>
 * 那属于**智能体**的配置。插件只声明"用哪个智能体"（`type: 'agent'`），
 * 人格由智能体那边控制 —— 否则同一个智能体在不同插件里会有两套人格。
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
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [saving, setSaving] = useState(false);

  /** 有 `main` = 有 Node 侧，配置需要同步到插件文件。 */
  const hasNodeSide = !!plugin?.main;

  useEffect(() => {
    if (!open || !plugin) {
      return;
    }
    let alive = true;
    const local = { ...createPluginConfig(plugin.id).get() };
    setValues(local);

    // 以插件侧为准：它才是"真正生效"的那份
    const bridge = nodeBridge();
    if (hasNodeSide && bridge?.available) {
      void (bridge.invokePlugin?.(plugin.id, 'getConfig') ?? Promise.resolve())
        .then((cfg) => {
          if (alive && cfg && typeof cfg === 'object') {
            setValues({ ...local, ...(cfg as Record<string, unknown>) });
          }
        })
        .catch(() => {
          /* 插件没实现 getConfig：用本地值即可 */
        });
    }

    if (fields.some((f) => f.type === 'agent')) {
      listAgents(undefined, undefined, 0, 100)
        .then((r) => {
          if (alive) setAgents(r?.items ?? []);
        })
        .catch(() => {
          if (alive) setAgents([]);
        });
    }

    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, plugin]);

  const save = async () => {
    if (!plugin) {
      return;
    }
    createPluginConfig(plugin.id).set(values);
    const bridge = nodeBridge();
    if (hasNodeSide && bridge?.available && bridge.invokePlugin) {
      setSaving(true);
      try {
        await bridge.invokePlugin(plugin.id, 'saveConfig', values);
      } catch (e) {
        message.warning(`已存到本机，但插件侧未接受：${(e as Error).message}`);
        setSaving(false);
        onClose();
        return;
      }
      setSaving(false);
    }
    message.success('已保存，插件即时生效');
    onClose();
  };

  /** 按声明渲染控件；类型缺省时按 default 推断。 */
  const control = (f: PluginConfigField) => {
    const type =
      f.type ??
      (typeof f.default === 'boolean' ? 'boolean' : typeof f.default === 'number' ? 'number' : 'text');

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

    // 智能体选择器：用户不该被要求去别处复制 UUID
    if (type === 'agent') {
      return (
        <Select
          style={{ width: '100%' }}
          showSearch
          allowClear
          placeholder="选择一个智能体"
          optionFilterProp="label"
          options={agents.map((a) => ({ label: `${a.name ?? a.agentId}`, value: a.agentId }))}
          value={(values[f.key] as string) || undefined}
          onChange={(v) => setValues((prev) => ({ ...prev, [f.key]: v ?? '' }))}
          notFoundContent="没有智能体（先到「智能体」页创建）"
        />
      );
    }

    // 数组值（白名单这类）在表单里用逗号串表示
    const raw = values[f.key];
    const text = Array.isArray(raw) ? raw.join(',') : ((raw ?? '') as string);
    return (
      <Input
        value={text}
        onChange={(e) => setValues((prev) => ({ ...prev, [f.key]: e.target.value }))}
      />
    );
  };

  return (
    <Modal
      title={`${plugin?.name ?? ''} · 设置`}
      open={open}
      onCancel={onClose}
      onOk={() => void save()}
      okText="保存"
      confirmLoading={saving}
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
            这些字段由插件在 manifest 里声明。
            {hasNodeSide
              ? '保存后会同时写入插件自己的配置文件（它跑在独立进程里，读的是那份）。'
              : '值保存在本机（`ap.plugin.' + (plugin?.id ?? '') + '.config`）。'}
          </Paragraph>
          <Form layout="vertical">
            {fields.map((f) => (
              <Form.Item
                key={f.key}
                label={f.label ?? f.key}
                extra={
                  f.description ? (
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {f.description}
                    </Text>
                  ) : undefined
                }
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
            if (!plugin) {
              return;
            }
            // 恢复默认：清掉本机保存值 + 通知插件侧回默认（插件侧不实现就只影响前端）
            localStorage.removeItem(`ap.plugin.${plugin.id}.config`);
            createPluginConfig(plugin.id).set({});
            setValues({ ...createPluginConfig(plugin.id).get() });
            const bridge = nodeBridge();
            if (hasNodeSide && bridge?.available) {
              void (bridge.invokePlugin?.(plugin.id, 'saveConfig', {}) ?? Promise.resolve()).catch(
                () => undefined,
              );
            }
            message.info('已恢复默认值');
          }}
        >
          恢复默认
        </Button>
      </div>
    </Modal>
  );
}
