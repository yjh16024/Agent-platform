import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Empty,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CheckOutlined,
  DeleteOutlined,
  LinkOutlined,
  ReloadOutlined,
  ClearOutlined,
} from '@ant-design/icons';
import {
  deleteNotification,
  listNotifications,
  markAllRead,
  markRead,
  purgeNotifications,
  type NotificationItem,
  type NotificationLevel,
  type NotificationType,
} from '../../api/notifications';

/**
 * 消息通知中心。
 *
 * <p>与「运行日志」「操作日志」的区别：</p>
 * <ul>
 *   <li>运行日志 = 系统在做什么（答"这次调用为什么慢"）；</li>
 *   <li>操作日志 = 人做了什么（答"这个智能体是谁改的"）；</li>
 *   <li><b>本页</b> = 有什么需要**我**处理（答"我该看什么"）。</li>
 * </ul>
 *
 * <p>只显示**当前登录用户自己**的通知 —— 收件人由后端从 token 决定，
 * 前端不传也不该传用户参数。</p>
 */

/** 类型 → 中文名与颜色。 */
const TYPE_META: Record<NotificationType, { text: string; color: string }> = {
  system: { text: '系统', color: 'default' },
  task: { text: '任务', color: 'blue' },
  quota: { text: '配额', color: 'orange' },
  security: { text: '安全', color: 'red' },
};

/** 级别 → 颜色（error 高亮，warn 次之，info 不抢眼）。 */
const LEVEL_COLOR: Record<NotificationLevel, string> = {
  info: 'default',
  warn: 'gold',
  error: 'red',
};

export default function NotificationsPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [loading, setLoading] = useState(false);

  const load = useCallback(
    async (opts?: { page?: number; size?: number; unreadOnly?: boolean }) => {
      const p = opts?.page ?? page;
      const s = opts?.size ?? size;
      const u = opts?.unreadOnly ?? unreadOnly;
      setLoading(true);
      try {
        const r = await listNotifications({ page: p, size: s, unreadOnly: u });
        setItems(r.items ?? []);
        setTotal(r.total ?? 0);
        setPage(r.page ?? 0);
        setSize(r.size ?? s);
        // 让侧栏角标立即刷新，不必等下一轮轮询（30s 的延迟会让"点了全部已读、
        // 角标还在"这种自相矛盾的状态停留太久）。事件只被 AppLayout 监听，不会回环到这里。
        window.dispatchEvent(new Event('ap:notice-changed'));
      } catch (e) {
        message.error((e as Error).message);
      } finally {
        setLoading(false);
      }
    },
    [page, size, unreadOnly],
  );

  useEffect(() => {
    void load({ page: 0 });
    // 首次进入与筛选条件变化时重新拉取
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unreadOnly]);

  /** 点标题：标记已读 + 跳到它指向的页面（有 link 才跳）。 */
  const openItem = async (n: NotificationItem) => {
    if (!n.read) {
      try {
        await markRead(n.notification_id);
        await load();
      } catch {
        // 标记失败不阻断跳转 —— 用户的主要目的是"去看看"
      }
    }
    if (n.link) {
      navigate(n.link);
    }
  };

  const doMarkRead = async (n: NotificationItem) => {
    try {
      const r = await markRead(n.notification_id);
      if (!r.updated) {
        message.warning('该通知不存在或不属于你');
      }
      await load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doDelete = async (n: NotificationItem) => {
    try {
      await deleteNotification(n.notification_id);
      message.success('已删除');
      await load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doMarkAllRead = async () => {
    try {
      const r = await markAllRead();
      message.success(r.updated > 0 ? `已标记 ${r.updated} 条为已读` : '没有未读通知');
      await load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doPurge = async () => {
    try {
      const r = await purgeNotifications(30);
      message.success(`已清理 ${r.deleted} 条 30 天前的通知`);
      await load({ page: 0 });
    } catch (e) {
      // 无 notice:manage 时后端会 403，这里如实展示后端消息
      message.error((e as Error).message);
    }
  };

  return (
    <Card
      title="消息通知"
      extra={
        <Space size={8}>
          <Space size={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              只看未读
            </Typography.Text>
            <Switch size="small" checked={unreadOnly} onChange={setUnreadOnly} />
          </Space>
          <Tooltip title="标记全部已读">
            <Button size="small" icon={<CheckOutlined />} onClick={doMarkAllRead}>
              全部已读
            </Button>
          </Tooltip>
          <Tooltip title="清理 30 天前的通知（需 notice:manage 权限）">
            <Popconfirm
              title="清理 30 天前的通知？"
              description="这是不可逆操作，仅影响你所在租户的过期通知。"
              okText="清理"
              cancelText="取消"
              onConfirm={doPurge}
            >
              <Button size="small" icon={<ClearOutlined />}>
                清理
              </Button>
            </Popconfirm>
          </Tooltip>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => load()} />
        </Space>
      }
    >
      <Table<NotificationItem>
        rowKey="notification_id"
        size="small"
        loading={loading}
        dataSource={items}
        locale={{ emptyText: <Empty description={unreadOnly ? '没有未读通知' : '还没有通知'} /> }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => void load({ page: p - 1, size: s }),
        }}
        columns={[
          {
            title: '类型',
            dataIndex: 'type',
            width: 72,
            render: (t: NotificationType) => (
              <Tag color={TYPE_META[t]?.color ?? 'default'}>{TYPE_META[t]?.text ?? t}</Tag>
            ),
          },
          {
            title: '级别',
            dataIndex: 'level',
            width: 72,
            render: (l: NotificationLevel) => <Tag color={LEVEL_COLOR[l] ?? 'default'}>{l}</Tag>,
          },
          {
            title: '内容',
            dataIndex: 'title',
            render: (_: unknown, n) => (
              <Space direction="vertical" size={0} style={{ maxWidth: 720 }}>
                <Space size={6}>
                  {/* 未读用加粗 + 圆点标记：比整行变色更克制，也不会和级别 Tag 抢注意力 */}
                  {!n.read && <span style={{ color: '#1677ff', fontWeight: 700 }}>●</span>}
                  <Typography.Text strong={!n.read}>{n.title}</Typography.Text>
                  {n.link && <LinkOutlined style={{ color: '#999', fontSize: 12 }} />}
                </Space>
                {n.content && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {n.content}
                  </Typography.Text>
                )}
              </Space>
            ),
          },
          {
            title: '时间',
            dataIndex: 'created_at',
            width: 170,
            render: (v: string) => (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {v ? String(v).replace('T', ' ').slice(0, 19) : '-'}
              </Typography.Text>
            ),
          },
          {
            title: '操作',
            width: 150,
            render: (_: unknown, n) => (
              <Space size={4}>
                <Button size="small" type="link" onClick={() => void openItem(n)}>
                  {n.link ? '查看' : '标记已读'}
                </Button>
                {!n.read && n.link && (
                  <Button size="small" type="link" onClick={() => void doMarkRead(n)}>
                    标已读
                  </Button>
                )}
                <Popconfirm
                  title="删除这条通知？"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => void doDelete(n)}
                >
                  <Button size="small" type="link" danger icon={<DeleteOutlined />} />
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
