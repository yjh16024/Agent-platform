import { useCallback, useEffect, useState } from 'react';
import { Drawer, Descriptions, Button, Modal, Select, Input, Switch, message, Space, Tag } from 'antd';
import {
  pluginDetail, attachPlugin, detachPlugin, attachments, pluginAttachments, type PluginAttachment,
} from '../../api/plugins';
import { listAgents } from '../../api/agents';
import { PluginDef, AgentResponse } from '../../api/types';

export default function PluginDetailDrawer({
  pluginId,
  onClose,
}: {
  pluginId: string | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<PluginDef | null>(null);
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [selAgentId, setSelAgentId] = useState<string | undefined>();
  const [attached, setAttached] = useState<Record<string, unknown> | null>(null);
  const [attachOpen, setAttachOpen] = useState(false);
  const [version, setVersion] = useState('');
  const [config, setConfig] = useState('');
  const [enabled, setEnabled] = useState(true);

  useEffect(() => {
    if (!pluginId) return;
    pluginDetail(pluginId).then(setDetail).catch(() => setDetail(null));
    listAgents(undefined, undefined, 0, 100).then((r) => setAgents(r.items ?? [])).catch(() => {});
  }, [pluginId]);

  const loadAttached = useCallback(async (agentId?: string) => {
    const aid = agentId ?? selAgentId;
    if (!pluginId || !aid) return;
    try {
      const list = await attachments(aid);
      const hit = list.find((a) => a.pluginId === pluginId || a.plugin_id === pluginId);
      setAttached(hit ?? null);
    } catch {
      setAttached(null);
    }
  }, [pluginId, selAgentId]);

  useEffect(() => {
    loadAttached();
  }, [loadAttached]);

  const doAttach = async () => {
    if (!pluginId || !selAgentId) {
      message.warning('请先选择智能体');
      return;
    }
    let cfg: Record<string, unknown> | undefined;
    if (config.trim()) {
      try {
        cfg = JSON.parse(config);
      } catch {
        message.error('config 不是合法 JSON');
        return;
      }
    }
    try {
      await attachPlugin(pluginId, selAgentId, { version: version || undefined, config: cfg, enabled });
      message.success('已挂载');
      setAttachOpen(false);
      loadAttached();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  /** 真正执行卸载（已确认影响面后调用）。 */
  const confirmDetach = async () => {
    if (!pluginId || !selAgentId) return;
    try {
      const r = await detachPlugin(pluginId, selAgentId);
      const n = r?.count ?? 0;
      message.success(n > 1 ? `已卸载，并一并取消了 ${n} 个智能体上的挂载` : '已卸载');
      loadAttached();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  /**
   * 卸载入口：先查这个插件被哪些智能体用着。
   * 若除当前智能体外还有别的智能体在用，必须让用户明确知道「会一并取消」——
   * 因为后端是级联卸载（否则会出现 B 显示已挂载但实际已失效的错位状态）。
   */
  const doDetach = async () => {
    if (!pluginId || !selAgentId) return;
    let others: PluginAttachment[] = [];
    try {
      const list = await pluginAttachments(pluginId);
      others = list.filter((a) => a.agentId !== selAgentId);
    } catch {
      // 影响面查询失败不阻断卸载，退化成直接卸载（后端仍会级联处理）
    }
    if (others.length === 0) {
      await confirmDetach();
      return;
    }
    const names = others.map((a) => a.agentName || a.agentId).join('、');
    Modal.confirm({
      title: '该插件正被多个智能体使用',
      content: `除当前智能体外，还有 ${others.length} 个智能体挂载了「${detail?.name ?? pluginId}」：${names}。`
        + '卸载会一并取消这些挂载，确定继续？',
      okText: '一并卸载',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => confirmDetach(),
    });
  };

  const attachModal = (
    <Modal
      title="挂载到智能体"
      open={attachOpen}
      onOk={doAttach}
      onCancel={() => setAttachOpen(false)}
      destroyOnClose
    >
      <Space direction="vertical" style={{ width: '100%' }}>
        <Select
          placeholder="选择智能体"
          style={{ width: '100%' }}
          value={selAgentId}
          onChange={setSelAgentId}
          options={agents.map((a) => ({ value: a.agentId, label: a.name }))}
        />
        <Input placeholder="版本（可选）" value={version} onChange={(e) => setVersion(e.target.value)} />
        <Input.TextArea
          placeholder={'config（可选 JSON，如 {"voice":"zh-CN-Xiaoxiao"}）'}
          rows={3}
          value={config}
          onChange={(e) => setConfig(e.target.value)}
        />
        <Space>
          <span>启用</span>
          <Switch checked={enabled} onChange={setEnabled} />
        </Space>
      </Space>
    </Modal>
  );

  return (
    <Drawer title={detail?.name ?? '插件详情'} open={!!pluginId} onClose={onClose} width={560}>
      {detail && (
        <>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="ID">{detail.pluginId}</Descriptions.Item>
            <Descriptions.Item label="描述">{detail.description || '—'}</Descriptions.Item>
            <Descriptions.Item label="作者">{detail.author || '—'}</Descriptions.Item>
            <Descriptions.Item label="版本">{detail.latestVersion || '—'}</Descriptions.Item>
            <Descriptions.Item label="状态">{detail.status || '—'}</Descriptions.Item>
          </Descriptions>

          <Descriptions column={1} size="small" bordered style={{ marginTop: 16 }} title="挂载管理">
            <Descriptions.Item label="智能体">
              <Select
                placeholder="选择智能体"
                style={{ width: '100%' }}
                value={selAgentId}
                onChange={(v) => {
                  setSelAgentId(v);
                  setAttached(null);
                }}
                options={agents.map((a) => ({ value: a.agentId, label: a.name }))}
              />
            </Descriptions.Item>
            <Descriptions.Item label="当前状态">
              {attached ? (
                <Space>
                  <Tag color="green">已挂载</Tag>
                  <span>{attached.version ? `v${attached.version}` : ''}</span>
                  {attached.enabled === false && <Tag color="orange">已停用</Tag>}
                </Space>
              ) : (
                <Tag>未挂载</Tag>
              )}
            </Descriptions.Item>
          </Descriptions>

          <Space style={{ marginTop: 16 }}>
            <Button type="primary" onClick={() => setAttachOpen(true)}>
              挂载 / 更新
            </Button>
            {attached && <Button danger onClick={doDetach}>卸载</Button>}
          </Space>
        </>
      )}
      {attachModal}
    </Drawer>
  );
}