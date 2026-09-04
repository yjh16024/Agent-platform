import { useCallback, useEffect, useState } from 'react';
import { Table, Input, Select, Space, Button, message, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { listAgents, deleteAgent, cloneAgent } from '../../api/agents';
import { AgentResponse } from '../../api/types';
import StatusTag from '../../components/StatusTag';
import AgentForm from './AgentForm';
import AgentDetailDrawer from './AgentDetailDrawer';

export default function AgentList() {
  const [items, setItems] = useState<AgentResponse[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<string | undefined>();
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<AgentResponse | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listAgents(q || undefined, status, page, size);
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [q, status, page, size]);

  useEffect(() => {
    load();
  }, [load]);

  const onDelete = async (id: string) => {
    try {
      await deleteAgent(id);
      message.success('已删除');
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };
  const onClone = async (id: string) => {
    try {
      await cloneAgent(id);
      message.success('已克隆');
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      render: (v: string, r: AgentResponse) => (
        <a onClick={() => setDetailId(r.agentId)}>{v}</a>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (v: string) => <StatusTag status={v} /> },
    { title: '系统提示词', dataIndex: 'systemPrompt', ellipsis: true },
    { title: '当前版本', dataIndex: 'currentVersion', render: (v: unknown) => v ?? '—' },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      render: (v: unknown) => (v ? new Date(v as string).toLocaleString() : '—'),
    },
    {
      title: '操作',
      render: (_: unknown, r: AgentResponse) => (
        <Space>
          <a onClick={() => setDetailId(r.agentId)}>详情</a>
          <a onClick={() => { setEditing(r); setFormOpen(true); }}>编辑</a>
          <a onClick={() => onClone(r.agentId)}>克隆</a>
          <Popconfirm title="确认删除该智能体？其历史版本与插件绑定将一并删除（会话记录保留）。" onConfirm={() => onDelete(r.agentId)}>
            <a style={{ color: '#ff4d4f' }}>删除</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="按名称搜索"
          allowClear
          style={{ width: 240 }}
          onSearch={setQ}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 140 }}
          onChange={(v) => setStatus(v)}
          options={[
            { value: 'draft', label: '草稿' },
            { value: 'published', label: '已发布' },
            { value: 'deprecated', label: '已弃用' },
            { value: 'archived', label: '已归档' },
          ]}
        />
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => { setEditing(null); setFormOpen(true); }}
        >
          新建智能体
        </Button>
      </Space>

      <Table
        rowKey="agentId"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          onChange: (p, ps) => { setPage(p - 1); setSize(ps); },
        }}
      />

      <AgentForm
        open={formOpen}
        editing={editing}
        onClose={() => setFormOpen(false)}
        onSaved={() => { setFormOpen(false); load(); }}
      />
      <AgentDetailDrawer agentId={detailId} onClose={() => setDetailId(null)} />
    </div>
  );
}