import { useCallback, useEffect, useState } from 'react';
import { Table, Drawer, Space, Button, Tag, message, List, Typography, Popconfirm } from 'antd';
import { listSessions, getSession, archiveSession, clearSession } from '../../api/sessions';
import { SessionSummary, SessionMessage } from '../../api/types';

const statusColor = (s?: string) => (s === 'active' ? 'green' : s === 'archived' ? 'default' : 'orange');

export default function SessionsPage() {
  const [items, setItems] = useState<SessionSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<SessionMessage[] | null>(null);
  const [detailTitle, setDetailTitle] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listSessions(page, size);
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [page, size]);

  useEffect(() => {
    load();
  }, [load]);

  const openDetail = async (r: SessionSummary) => {
    try {
      const d = await getSession(r.sessionId!);
      setDetailTitle(r.title || r.sessionId || '会话');
      setDetail(d.messages ?? []);
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    { title: '标题', dataIndex: 'title', ellipsis: true, render: (v: string) => v || '(未命名会话)' },
    { title: '会话 ID', dataIndex: 'sessionId', width: 180, ellipsis: true },
    { title: '智能体', dataIndex: 'agentId', width: 150, ellipsis: true },
    { title: '用户', dataIndex: 'userId', width: 120, ellipsis: true },
    { title: '消息数', dataIndex: 'messageCount', width: 80 },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: string) => <Tag color={statusColor(v)}>{v}</Tag>,
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', width: 180,
      render: (v: string | null) => (v ? new Date(v).toLocaleString() : '—'),
    },
    {
      title: '操作', width: 200,
      render: (_: unknown, r: SessionSummary) => (
        <Space>
          <Button size="small" onClick={() => openDetail(r)}>查看</Button>
          <Popconfirm title="清空该会话消息（保留会话）？" onConfirm={async () => { await clearSession(r.sessionId!); load(); }}>
            <Button size="small">清空</Button>
          </Popconfirm>
          <Popconfirm title="删除该会话（含全部消息）？" onConfirm={async () => { await archiveSession(r.sessionId!); load(); }}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={5}>会话历史</Typography.Title>
      <Table
        rowKey="sessionId"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={{
          current: page + 1, pageSize: size, total,
          onChange: (p, ps) => { setPage(p - 1); setSize(ps); },
        }}
      />
      <Drawer title={detailTitle} open={detail !== null} onClose={() => setDetail(null)} width={560}>
        <List
          dataSource={detail ?? []}
          renderItem={(m: SessionMessage) => (
            <List.Item style={{ alignItems: 'flex-start' }}>
              <List.Item.Meta
                title={<Space><Tag color={m.role === 'user' ? 'blue' : 'green'}>{m.role}</Tag><span style={{ fontSize: 12, color: '#999' }}>轮次 {m.turnNo}</span></Space>}
                description={<Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{m.content}</Typography.Paragraph>}
              />
            </List.Item>
          )}
        />
      </Drawer>
    </div>
  );
}