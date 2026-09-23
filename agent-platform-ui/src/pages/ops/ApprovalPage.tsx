import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Empty,
  Modal,
  Popconfirm,
  Radio,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckOutlined,
  CloseOutlined,
  ReloadOutlined,
  ClockCircleOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  approveToolCall,
  expireStaleApprovals,
  listApprovals,
  rejectToolCall,
  rollbackToolCall,
  type ApprovalItem,
  type ApprovalStatus,
} from '../../api/approvals';
import { getMe } from '../../api/auth';

const { Paragraph, Text } = Typography;

/** 状态标签配色。 */
const STATUS_COLOR: Record<ApprovalStatus, string> = {
  pending: 'processing',
  approved: 'success',
  rejected: 'default',
  expired: 'warning',
};

/**
 * 工具审批页。
 *
 * <p>这是"让智能体改文件"的**那道闸门**。模型调用写工具时只会提交一条申请，
 * 真正落笔要在这里点「批准」—— 所以这个页面的措辞刻意写得很具体：
 * 不是"确认操作"，而是把**将要改哪个文件的哪一段**摆出来给用户看。</p>
 *
 * <p>列表默认只看待审批：绝大多数时候用户来这个页面就是为了清待办，
 * 已处理的历史需要额外点一下才看（避免待办被一堆历史记录挤下去）。</p>
 */
export default function ApprovalPage() {
  const { message } = AntApp.useApp();
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<ApprovalStatus | 'all'>('pending');
  const [busyId, setBusyId] = useState<string | null>(null);
  // 与 AppLayout 同一套取舍：拿不到权限集时视为"不限"，而不是全隐藏
  const [perms, setPerms] = useState<string[] | null>(null);

  const canManage = useMemo(
    () => !perms || perms.length === 0 || perms.includes('approval:manage'),
    [perms],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listApprovals({
        status: status === 'all' ? undefined : status,
        page,
        size,
      });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error(`加载审批列表失败：${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [status, page, size, message]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    getMe()
      .then((me) => setPerms(me.perms ?? []))
      .catch(() => setPerms(null));
  }, []);

  const approve = async (row: ApprovalItem) => {
    setBusyId(row.approvalId);
    try {
      const updated = await approveToolCall(row.approvalId);
      if (updated.errorMsg) {
        // 批准成功但执行失败：这是最需要说清楚的一种结果，不能让用户以为改成功了
        message.warning(`已批准，但执行失败：${updated.errorMsg}`);
      } else {
        message.success(updated.result || '已批准并执行完成');
      }
      await load();
    } catch (e) {
      message.error(`批准失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const reject = async (row: ApprovalItem) => {
    setBusyId(row.approvalId);
    try {
      await rejectToolCall(row.approvalId, '用户拒绝');
      message.success('已拒绝，未执行');
      await load();
    } catch (e) {
      message.error(`拒绝失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const rollback = async (row: ApprovalItem) => {
    setBusyId(row.approvalId);
    try {
      const updated = await rollbackToolCall(row.approvalId);
      message.success(updated.result || '已回滚到操作前的状态');
      await load();
    } catch (e) {
      message.error(`回滚失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const expireStale = () => {
    Modal.confirm({
      title: '把超期的待审批标记为过期？',
      content:
        '超过保留期（默认 7 天）仍未被处理的申请会作废。这是安全阀：陈旧的申请对应的对话早已过去，此时批准等于执行一次与当前状态无关的写操作。',
      okText: '标记过期',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await expireStaleApprovals();
          message.success(`已标记 ${r.expired} 条为过期`);
          await load();
        } catch (e) {
          message.error(`操作失败：${(e as Error).message}`);
        }
      },
    });
  };

  const columns: ColumnsType<ApprovalItem> = [
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: ApprovalStatus, row) => (
        <Tag color={STATUS_COLOR[v]}>{row.statusLabel || v}</Tag>
      ),
    },
    {
      title: '工具',
      dataIndex: 'toolName',
      width: 150,
      render: (v: string) => <Text code>{v}</Text>,
    },
    {
      title: '将要执行的操作',
      dataIndex: 'summary',
      render: (v: string, row) => (
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Text>{v || '(无摘要)'}</Text>
          {row.errorMsg && (
            <Text type="danger" style={{ fontSize: 12 }}>
              {row.status === 'rejected' ? '拒绝理由' : '执行失败'}：{row.errorMsg}
            </Text>
          )}
          {row.result && (
            <Text type="success" style={{ fontSize: 12 }}>
              {row.result}
            </Text>
          )}
        </Space>
      ),
    },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-'),
    },
    {
      title: '操作',
      width: 190,
      render: (_, row) => {
        if (row.status === 'pending') {
          return (
            <Space size="small">
              <Popconfirm
                title="确认执行这次操作？"
                description={
                  <div style={{ maxWidth: 320 }}>
                    <div style={{ marginBottom: 6 }}>{row.summary}</div>
                    <Text type="warning" style={{ fontSize: 12 }}>
                      确认后将立即修改工作区内的文件。本次操作会先留一份改前快照，之后可以回滚。
                    </Text>
                  </div>
                }
                okText="批准并执行"
                cancelText="取消"
                onConfirm={() => approve(row)}
                disabled={!canManage}
              >
                <Button
                  size="small"
                  type="primary"
                  icon={<CheckOutlined />}
                  loading={busyId === row.approvalId}
                  disabled={!canManage}
                >
                  批准
                </Button>
              </Popconfirm>
              <Button
                size="small"
                danger
                icon={<CloseOutlined />}
                loading={busyId === row.approvalId}
                disabled={!canManage}
                onClick={() => reject(row)}
              >
                拒绝
              </Button>
            </Space>
          );
        }
        if (row.rollbackable) {
          return (
            <Popconfirm
              title="回滚到这次操作之前？"
              description={
                <div style={{ maxWidth: 320 }}>
                  <div style={{ marginBottom: 6 }}>
                    会按改前快照还原文件（新建的文件将被删除）。
                  </div>
                  <Text type="warning" style={{ fontSize: 12 }}>
                    注意：会覆盖该文件在此之后的其它修改。
                  </Text>
                </div>
              }
              okText="回滚"
              cancelText="取消"
              onConfirm={() => rollback(row)}
              disabled={!canManage}
            >
              <Button
                size="small"
                icon={<UndoOutlined />}
                loading={busyId === row.approvalId}
                disabled={!canManage}
              >
                回滚
              </Button>
            </Popconfirm>
          );
        }
        return (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {row.rolledBackAt
              ? `已回滚 ${row.rolledBackAt.replace('T', ' ').slice(0, 19)}`
              : row.decidedAt
                ? row.decidedAt.replace('T', ' ').slice(0, 19)
                : '已处理'}
          </Text>
        );
      },
    },
  ];

  return (
    <div style={{ padding: 16, maxWidth: 1200 }}>
      <Card
        title="工具审批"
        extra={
          <Space>
            <Radio.Group
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
              optionType="button"
              buttonStyle="solid"
              size="small"
            >
              <Radio.Button value="pending">待审批</Radio.Button>
              <Radio.Button value="approved">已批准</Radio.Button>
              <Radio.Button value="rejected">已拒绝</Radio.Button>
              <Radio.Button value="all">全部</Radio.Button>
            </Radio.Group>
            <Tooltip title="把超过保留期仍未处理的申请标记为过期">
              <Button size="small" icon={<ClockCircleOutlined />} onClick={expireStale} disabled={!canManage}>
                清理超期
              </Button>
            </Tooltip>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="智能体改文件前会先提交申请，只有在这里批准后才会真正执行"
          description="工作区约束能挡住往工作区外写，但挡不住把工作区里的东西改坏 —— 所以写操作必须由人放行。批准后立即执行且不可撤销；拒绝则不会改动任何文件。"
        />
        <Table
          rowKey="approvalId"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={items}
          locale={{ emptyText: <Empty description={status === 'pending' ? '没有待审批的操作' : '暂无记录'} /> }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total,
            showSizeChanger: true,
            onChange: (p, s) => {
              setPage(p - 1);
              setSize(s);
            },
          }}
          expandable={{
            // 参数是 JSON 原文：给"想核对具体改了什么"的用户一个入口，
            // 但默认折叠 —— 绝大多数人只需看摘要
            expandedRowRender: (row) => (
              <pre
                style={{
                  margin: 0,
                  fontSize: 12,
                  maxHeight: 260,
                  overflow: 'auto',
                  background: 'rgba(0,0,0,0.03)',
                  padding: 8,
                  borderRadius: 4,
                }}
              >
                {row.toolArgs || '(无参数)'}
              </pre>
            ),
            rowExpandable: (row) => !!row.toolArgs,
          }}
        />
      </Card>
      <Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>
        提示：模型调用写工具后只会提交申请并告诉你"等待确认"，不会直接改文件。
        批准后下一轮对话中模型即可用 fs_read_file 看到修改结果。
      </Paragraph>
    </div>
  );
}
