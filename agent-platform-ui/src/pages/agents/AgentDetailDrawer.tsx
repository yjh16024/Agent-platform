import { useCallback, useEffect, useState } from 'react';
import { Drawer, Descriptions, Tabs, List, Button, Space, message, Select, Alert, Spin } from 'antd';
import {
  getAgent,
  listVersions,
  snapshotVersion,
  publishAgent,
  rollbackAgent,
  diffVersions,
} from '../../api/agents';
import { AgentResponse, AgentVersion } from '../../api/types';
import StatusTag from '../../components/StatusTag';

export default function AgentDetailDrawer({
  agentId,
  onClose,
}: {
  agentId: string | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<AgentResponse | null>(null);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [diffFrom, setDiffFrom] = useState<string | undefined>();
  const [diffTo, setDiffTo] = useState<string | undefined>();
  const [diff, setDiff] = useState<Record<string, unknown> | null>(null);

  const load = useCallback(async () => {
    if (!agentId) return;
    setLoading(true);
    try {
      const d = await getAgent(agentId);
      setDetail(d);
      const vs = await listVersions(agentId);
      setVersions(vs ?? []);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    load();
  }, [load]);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      message.success(ok);
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doDiff = async () => {
    if (!agentId || !diffFrom || !diffTo) return;
    try {
      setDiff(await diffVersions(agentId, diffFrom, diffTo));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const versionOpts = versions.map((v) => ({ value: String(v.version), label: `v${v.version}` }));

  return (
    <Drawer title={detail?.name ?? '智能体详情'} open={!!agentId} onClose={onClose} width={640}>
      <Spin spinning={loading}>
        {detail && (
          <>
            <Space style={{ marginBottom: 12 }}>
              <StatusTag status={detail.status} />
              <span style={{ color: '#888' }}>ID: {detail.agentId}</span>
            </Space>
            {detail.validation && detail.validation.ok === false && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 12 }}
                message={detail.validation.warnings?.join('；')}
              />
            )}
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="描述">{detail.description || '—'}</Descriptions.Item>
              <Descriptions.Item label="系统提示词">{detail.systemPrompt || '—'}</Descriptions.Item>
              <Descriptions.Item label="人格">{JSON.stringify(detail.persona ?? {})}</Descriptions.Item>
              <Descriptions.Item label="已挂插件">
                {detail.effectivePlugins?.map((p) => p.pluginId).join(', ') || '无'}
              </Descriptions.Item>
            </Descriptions>

            <Tabs
              style={{ marginTop: 16 }}
              items={[
                {
                  key: 'versions',
                  label: '版本历史',
                  children: (
                    <div>
                      <Space style={{ marginBottom: 16 }}>
                        <Button onClick={() => act(() => snapshotVersion(agentId!), '已创建快照')}>
                          创建快照
                        </Button>
                        <Button type="primary" onClick={() => act(() => publishAgent(agentId!), '已发布')}>
                          发布
                        </Button>
                      </Space>
                      <List
                        dataSource={versions}
                        rowKey="version"
                        locale={{ emptyText: '暂无版本' }}
                        renderItem={(v) => (
                          <List.Item
                            actions={[
                              <a onClick={() => act(() => rollbackAgent(agentId!, String(v.version)), '已回滚')}>
                                回滚
                              </a>,
                            ]}
                          >
                            <List.Item.Meta
                              title={`v${v.version}`}
                              description={v.releasedAt ? new Date(v.releasedAt).toLocaleString() : '未发布'}
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  ),
                },
                {
                  key: 'diff',
                  label: '差异对比',
                  children: (
                    <div>
                      <Space>
                        <Select placeholder="from" style={{ width: 140 }} options={versionOpts} onChange={setDiffFrom} />
                        <Select placeholder="to" style={{ width: 140 }} options={versionOpts} onChange={setDiffTo} />
                        <Button type="primary" onClick={doDiff}>
                          对比
                        </Button>
                      </Space>
                      {diff && (
                        <pre style={{ background: '#f5f5f5', padding: 12, overflow: 'auto', marginTop: 12 }}>
                          {JSON.stringify(diff, null, 2)}
                        </pre>
                      )}
                    </div>
                  ),
                },
              ]}
            />
          </>
        )}
      </Spin>
    </Drawer>
  );
}