import { useEffect, useState } from 'react';
import { Checkbox, Empty, Modal, Space, Spin, Typography, message } from 'antd';
import { listAgents } from '../../api/agents';
import { setPluginWebAgents, type WebPluginItem } from '../../api/pluginWeb';
import type { AgentResponse } from '../../api/types';

/**
 * 「挂载到哪些智能体」选择器（2026-10-20）。
 *
 * <h3>为什么需要它</h3>
 * 界面插件此前是**装上就全平台生效**（`PluginWebService` 的原始设计）。
 * 后果是：为一个智能体写的小挂件，会同时出现在所有对话页上。
 * 现在改成"**空选 = 全局，选了 = 只在选中的智能体上出现**"。
 *
 * <h3>为什么空选等于全局，而不是"哪里都不出现"</h3>
 * 因为绝大多数插件（用量提示、桌宠）本来就想全平台用。
 * 如果空选 = 不出现，那么"刚装上却什么都没发生"会成为最常见的困惑。
 */
export default function PluginScopeModal({
  plugin,
  open,
  onClose,
  onSaved,
}: {
  plugin: WebPluginItem | null;
  open: boolean;
  onClose: () => void;
  /** 保存成功后的回调（调用方据此刷新列表与槽位）。 */
  onSaved?: () => void;
}) {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [selected, setSelected] = useState<string[]>([]);

  useEffect(() => {
    if (!open || !plugin) {
      return;
    }
    setSelected(Array.isArray(plugin.agents) ? plugin.agents : []);
    setLoading(true);
    // size 给 100：默认 20 会让智能体多的人漏选（这个弹窗里"看不到某个智能体"很难理解）
    listAgents(undefined, undefined, 0, 100)
      .then((r) => setAgents(r?.items ?? []))
      .catch(() => setAgents([]))
      .finally(() => setLoading(false));
  }, [open, plugin]);

  const submit = async () => {
    if (!plugin) {
      return;
    }
    setSaving(true);
    try {
      await setPluginWebAgents(plugin.id, selected);
      message.success(
        selected.length === 0 ? '已设为全部智能体' : `已设为 ${selected.length} 个智能体`,
      );
      onSaved?.();
      onClose();
    } catch (e) {
      message.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={`挂载范围：${plugin?.name ?? ''}`}
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      okText="保存"
      cancelText="取消"
      confirmLoading={saving}
      width={480}
      destroyOnClose
    >
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          不选任何智能体 = **全部智能体**都生效；勾选后只在这些智能体上出现。
        </Typography.Text>
        {loading ? (
          <Spin />
        ) : agents.length === 0 ? (
          <Empty description="还没有智能体" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Checkbox.Group
            value={selected}
            onChange={(v) => setSelected(v as string[])}
            style={{ display: 'flex', flexDirection: 'column', gap: 6 }}
            options={agents.map((a) => ({
              label: `${a.name ?? a.agentId}`,
              value: a.agentId,
            }))}
          />
        )}
      </Space>
    </Modal>
  );
}
