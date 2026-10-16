import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Button,
  Drawer,
  Dropdown,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Tooltip,
  Typography,
} from 'antd';
import {
  ClearOutlined,
  DeleteOutlined,
  EditOutlined,
  MoreOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { archiveSession, clearSession, listSessions, updateSession } from '../../api/sessions';
import type { SessionSummary } from '../../api/types';

/**
 * 会话历史抽屉（2026-10-16，会话历史并入对话页）。
 *
 * <h3>为什么是抽屉而不是常驻第二列</h3>
 * 平台已有一条全局侧栏；对话页再挂一条常驻列就是"两层侧栏"——既挤宽度又显臃肿。
 * 抽屉是"同一时间只展开一个面板"：点击历史按钮时，对话页会顺手把全局侧栏折叠
 * （见 ChatPage.openHistory 的 `ap:sidebar-collapse` 事件），关闭后不自动还原 ——
 * 那种"缩了又弹回来"的反复才是真的晃眼，用户要用导航自己点开即可。
 *
 * <h3>交互对齐元宝</h3>
 * 列表按**智能体分组**；悬停某一行时右侧才浮现「…」→ 重命名 / 清空消息 / 删除。
 * 当前正在对话的会话高亮。
 *
 * <h3>★ 与"删除/清空当前会话"的联动</h3>
 * 如果被删/被清的正是当前会话，父级必须同步换掉本地状态（换新会话 ID / 清空本地消息）——
 * 否则会出现"本地还在聊、后端那条已经没了"的错位。通过
 * {@code onCurrentDeleted} / {@code onCurrentCleared} 两个回调交给 ChatPage 处理。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  /** 当前正在对话的会话 ID（高亮用）。 */
  currentSessionId?: string;
  /** agentId → 显示名（智能体列表由对话页持有）。 */
  agentNames?: Record<string, string>;
  /** 点击某条历史：父级负责载入消息并切换（见 ChatPage.switchToSession）。 */
  onSwitch: (s: SessionSummary) => void;
  /** 删除的正是当前会话时回调。 */
  onCurrentDeleted?: (s: SessionSummary) => void;
  /** 清空的正是当前会话时回调。 */
  onCurrentCleared?: (s: SessionSummary) => void;
}

function fmtTime(v?: string | null): string {
  if (!v) {
    return '';
  }
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function SessionHistoryDrawer({
  open,
  onClose,
  currentSessionId,
  agentNames,
  onSwitch,
  onCurrentDeleted,
  onCurrentCleared,
}: Props) {
  const { modal, message } = AntApp.useApp();
  const [items, setItems] = useState<SessionSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [renameTarget, setRenameTarget] = useState<SessionSummary | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listSessions(0, 100);
      setItems(r.items ?? []);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [message]);

  // 每次打开都拉一次最新 —— 抽屉是"临时展开"的视图，缓存只会让人看到过期列表
  useEffect(() => {
    if (open) {
      void load();
    }
  }, [open, load]);

  /** 按智能体分组、组内与组间都按更新时间倒序。 */
  const groups = useMemo(() => {
    const ts = (s: SessionSummary) => (s.updatedAt ? Date.parse(s.updatedAt) || 0 : 0);
    const byAgent = new Map<string, SessionSummary[]>();
    for (const s of items) {
      const k = s.agentId || '';
      byAgent.set(k, [...(byAgent.get(k) ?? []), s]);
    }
    return [...byAgent.entries()]
      .map(([agentId, list]) => {
        const sorted = [...list].sort((a, b) => ts(b) - ts(a));
        return { agentId, list: sorted, newest: sorted.length ? ts(sorted[0]) : 0 };
      })
      .sort((a, b) => b.newest - a.newest);
  }, [items]);

  const askRename = (s: SessionSummary) => {
    setRenameTarget(s);
    setRenameValue(s.title ?? '');
  };

  const doRename = async () => {
    const sid = renameTarget?.sessionId;
    if (!sid) {
      return;
    }
    setSaving(true);
    try {
      await updateSession(sid, { title: renameValue.trim() });
      setRenameTarget(null);
      message.success('已重命名');
      await load();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const askClear = (s: SessionSummary) => {
    modal.confirm({
      title: '清空该会话的消息？',
      content: '会话本身保留，只删除它的消息记录。',
      okText: '清空',
      cancelText: '取消',
      onOk: async () => {
        await clearSession(s.sessionId!);
        if (s.sessionId === currentSessionId) {
          onCurrentCleared?.(s);
        }
        message.success('已清空');
        await load();
      },
    });
  };

  const askDelete = (s: SessionSummary) => {
    modal.confirm({
      title: '删除该会话？',
      content: '将连同它的全部消息一起删除，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await archiveSession(s.sessionId!);
        if (s.sessionId === currentSessionId) {
          onCurrentDeleted?.(s);
        }
        message.success('已删除');
        await load();
      },
    });
  };

  const renderRow = (s: SessionSummary) => {
    const active = !!s.sessionId && s.sessionId === currentSessionId;
    return (
      <div
        key={s.sessionId}
        className={`ap-session-row${active ? ' ap-session-row-active' : ''}`}
        onClick={() => onSwitch(s)}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <Typography.Text
            ellipsis
            style={{ fontSize: 13, fontWeight: active ? 600 : 400, width: '100%' }}
            title={s.title || undefined}
          >
            {s.title || '(未命名会话)'}
          </Typography.Text>
          <div style={{ fontSize: 11, color: '#999', marginTop: 2 }}>
            {fmtTime(s.updatedAt)}
            {typeof s.messageCount === 'number' ? ` · ${s.messageCount} 条` : ''}
          </div>
        </div>
        {/* 悬停才浮现的「…」：常显会让整列看起来全是按钮（元宝同理） */}
        <Dropdown
          trigger={['click']}
          menu={{
            items: [
              { key: 'rename', label: '重命名', icon: <EditOutlined /> },
              { key: 'clear', label: '清空消息', icon: <ClearOutlined /> },
              { type: 'divider' },
              { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true },
            ],
            onClick: ({ key, domEvent }) => {
              domEvent.stopPropagation();
              if (key === 'rename') {
                askRename(s);
              } else if (key === 'clear') {
                askClear(s);
              } else if (key === 'delete') {
                askDelete(s);
              }
            },
          }}
        >
          <Button
            className="ap-session-more"
            type="text"
            size="small"
            icon={<MoreOutlined />}
            aria-label="更多操作"
            onClick={(e) => e.stopPropagation()}
          />
        </Dropdown>
      </div>
    );
  };

  return (
    <Drawer
      title={
        <Space size={6}>
          会话历史
          <Tooltip title="刷新">
            <Button size="small" type="text" icon={<ReloadOutlined />} onClick={() => void load()} />
          </Tooltip>
        </Space>
      }
      placement="left"
      width={320}
      open={open}
      onClose={onClose}
      styles={{ body: { padding: '8px 10px' } }}
    >
      {/* hover 才浮现的操作按钮：写在组件内联样式里，避免污染全局 CSS */}
      <style>{`
        .ap-session-row { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 6px; cursor: pointer; }
        .ap-session-row:hover { background: rgba(0, 0, 0, 0.04); }
        .ap-session-row-active { background: rgba(22, 119, 255, 0.08); }
        .ap-session-more { opacity: 0; flex: 0 0 auto; }
        .ap-session-row:hover .ap-session-more, .ap-session-row-active .ap-session-more { opacity: 1; }
      `}</style>

      {loading && items.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin />
        </div>
      ) : groups.length === 0 ? (
        <Empty description="还没有会话历史" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        groups.map((g) => (
          <div key={g.agentId} style={{ marginBottom: 10 }}>
            <div style={{ fontSize: 12, color: '#999', padding: '4px 8px' }}>
              {agentNames?.[g.agentId] || (g.agentId || '未关联智能体')}
            </div>
            {g.list.map(renderRow)}
          </div>
        ))
      )}

      <Modal
        title="重命名会话"
        open={renameTarget !== null}
        onCancel={() => setRenameTarget(null)}
        onOk={doRename}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        width={420}
      >
        <Input
          autoFocus
          maxLength={60}
          placeholder="会话标题"
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          onPressEnter={doRename}
        />
      </Modal>
    </Drawer>
  );
}
