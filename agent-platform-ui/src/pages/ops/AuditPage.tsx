import { useCallback, useEffect, useState } from 'react';
import { App as AntApp, Button, Card, Input, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined, DeleteOutlined, UserOutlined } from '@ant-design/icons';
import { listAudits, purgeAudits, type AuditLogView } from '../../api/audit';

const { Title, Text } = Typography;

/**
 * 操作日志（审计）。
 *
 * <p>和「运行日志」页的区别：那边看的是**系统运行**（agent 执行 / LLM 调用 / 工具调用），
 * 这边看的是**人的操作**（谁、何时、对什么、做了什么、结果如何）。</p>
 *
 * <h3>展示上的两个取舍</h3>
 * <ul>
 *   <li><b>角色列显示的是"操作当时"的快照</b>（后端存的就是快照）—— 不查当前角色，
 *       否则用户调岗后历史记录会跟着变，审计就失去意义了；</li>
 *   <li><b>不展示请求体</b>：后端刻意不记录它（里面可能有密码、API Key），
 *       所以这里也没有可展示的东西。只在失败时显示错误摘要。</li>
 * </ul>
 */
export default function AuditPage() {
  const { message, modal } = AntApp.useApp();
  const [items, setItems] = useState<AuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [action, setAction] = useState('');
  const [userId, setUserId] = useState('');
  const [onlyFailed, setOnlyFailed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [purging, setPurging] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listAudits({
        action: action || undefined,
        userId: userId || undefined,
        success: onlyFailed ? false : undefined,
        page,
        size,
      });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [action, userId, onlyFailed, page, size, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const doPurge = () => {
    modal.confirm({
      title: '清理历史审计记录？',
      content:
        '默认清理 90 天前的记录。审计记录只能按时间整段删除（不允许单条删除，那等于给了抹掉追责线索的能力）。此操作不可逆。',
      okButtonProps: { danger: true },
      onOk: async () => {
        setPurging(true);
        try {
          const r = await purgeAudits(90);
          message.success(`已清理 ${r.deleted} 条（${r.before} 之前的记录）`);
          await load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : '清理失败');
        } finally {
          setPurging(false);
        }
      },
    });
  };

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>
        操作日志
      </Title>
      <Text type="secondary">
        记录「谁、何时、对什么、做了什么、结果如何」。角色列是操作**当时**的快照；出于安全，不记录请求体。
      </Text>

      <Card size="small" style={{ marginTop: 16 }}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Input
            placeholder="动作关键字（如 删除）"
            allowClear
            style={{ width: 200 }}
            value={action}
            onChange={(e) => {
              setPage(0);
              setAction(e.target.value);
            }}
          />
          <Input
            placeholder="操作人 userId"
            allowClear
            prefix={<UserOutlined />}
            style={{ width: 220 }}
            value={userId}
            onChange={(e) => {
              setPage(0);
              setUserId(e.target.value);
            }}
          />
          <Button
            type={onlyFailed ? 'primary' : 'default'}
            danger={onlyFailed}
            onClick={() => {
              setPage(0);
              setOnlyFailed((v) => !v);
            }}
          >
            只看失败
          </Button>
          <Button type="primary" onClick={load}>
            查询
          </Button>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button icon={<DeleteOutlined />} loading={purging} onClick={doPurge}>
            清理 90 天前
          </Button>
        </Space>

        <Table<AuditLogView>
          size="small"
          rowKey="auditId"
          loading={loading}
          dataSource={items}
          pagination={{
            current: page + 1,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p - 1);
              setSize(s);
            },
          }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 170 },
            {
              title: '操作人',
              width: 170,
              render: (_: unknown, r: AuditLogView) => (
                <Space size={4} direction="vertical">
                  <span>{r.username || r.userId || '—'}</span>
                  {r.roles ? (
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {r.roles}
                    </Text>
                  ) : null}
                </Space>
              ),
            },
            {
              title: '动作',
              dataIndex: 'action',
              render: (v: string, r: AuditLogView) => (
                <Space size={4} direction="vertical">
                  <span>{v}</span>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {r.method} {r.uri}
                  </Text>
                </Space>
              ),
            },
            {
              title: '目标',
              width: 170,
              render: (_: unknown, r: AuditLogView) =>
                r.targetType ? (
                  <Tooltip title={r.targetId ?? ''}>
                    <Text code style={{ fontSize: 12 }}>
                      {r.targetType}
                      {r.targetId ? `:${r.targetId}` : ''}
                    </Text>
                  </Tooltip>
                ) : (
                  <Text type="secondary">—</Text>
                ),
            },
            {
              title: '结果',
              width: 150,
              render: (_: unknown, r: AuditLogView) =>
                r.success ? (
                  <Tag color="green">成功</Tag>
                ) : (
                  <Tooltip title={r.errorMsg ?? ''}>
                    <Tag color="red">失败</Tag>
                  </Tooltip>
                ),
            },
            {
              title: '耗时',
              dataIndex: 'durationMs',
              width: 90,
              render: (v: number | null) => (v == null ? '—' : `${v} ms`),
            },
            { title: 'IP', dataIndex: 'ip', width: 130 },
          ]}
        />
      </Card>
    </div>
  );
}
