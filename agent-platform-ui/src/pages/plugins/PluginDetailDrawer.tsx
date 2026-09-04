import { useCallback, useEffect, useState } from 'react';
import { Drawer, Descriptions, Button, Modal, Select, Input, Switch, message, Space, Tag } from 'antd';
import { pluginDetail, attachPlugin, detachPlugin, attachments } from '../../api/plugins';
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

  const doDetach = async () => {
    if (!pluginId || !selAgentId) return;
    try {
      await detachPlugin(pluginId, selAgentId);
      message.success('已卸载');
      loadAttached();
    } catch (e) {
      message.error((e as Error).message);
    }
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