import { useCallback, useEffect, useRef, useState } from 'react';
import { Card, Select, Input, Button, Space, Switch, Tag, Empty, List, Typography, message, Popconfirm, Alert } from 'antd';
import { SendOutlined, PlusOutlined, ClearOutlined } from '@ant-design/icons';
import { listAgents } from '../../api/agents';
import { runAgent, runAgentStream, RunMessage } from '../../api/run';
import { importConversation } from '../../api/sessions';
import { AgentResponse } from '../../api/types';
import { useAppStore } from '../../store/appStore';
import { useChatStore, ChatMsg } from '../../store/chatStore';

/**
 * 对话运行页。
 * 需求：当前对话的聊天历史常驻可见（跨页面切换 / 刷新不丢，存于全局 store + localStorage），
 * 直到用户点击「新对话」，才把本轮对话保存进「会话历史」并清空本地开始新一轮。
 */
export default function ChatPage() {
  const { tenantId } = useAppStore();
  const { msgsOf, append, replace, reset } = useChatStore();
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [agentId, setAgentId] = useState<string | undefined>();
  const [input, setInput] = useState('');
  const [stream, setStream] = useState(false);
  const [busy, setBusy] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  const messages: ChatMsg[] = msgsOf(agentId);

  const loadAgents = useCallback(() => {
    listAgents(undefined, undefined, 0, 200)
      .then((r) => {
        const list = r.items ?? [];
        setAgents(list);
        setAgentId((cur) => {
          if (cur && list.some((a) => a.agentId === cur)) return cur;
          return list[0]?.agentId;
        });
      })
      .catch(() => message.error('加载智能体失败'));
  }, []);

  useEffect(() => {
    loadAgents();
  }, [loadAgents, tenantId]);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages.length]);

  const send = useCallback(async () => {
    const text = input.trim();
    if (!text || !agentId) {
      if (!agentId) message.warning('请先选择智能体');
      return;
    }
    append(agentId, { role: 'user', content: text });
    setInput('');
    setBusy(true);
    try {
      const history: RunMessage[] = [...msgsOf(agentId)].map((m) => ({
        role: m.role,
        content: m.content,
      }));
      if (stream) {
        let acc = '';
        append(agentId, { role: 'assistant', content: '' });
        await runAgentStream(agentId, history, (delta) => {
          acc += delta;
          const copy = [...msgsOf(agentId)];
          copy[copy.length - 1] = { role: 'assistant', content: acc };
          replace(agentId, copy);
        });
        // 保证流式结尾渲染
        const finalMsgs = [...msgsOf(agentId)];
        if (finalMsgs.length > 0 && finalMsgs[finalMsgs.length - 1].content === '') {
          finalMsgs[finalMsgs.length - 1] = { role: 'assistant', content: '(空)' };
          replace(agentId, finalMsgs);
        }
      } else {
        const r = await runAgent(agentId, history);
        append(agentId, { role: 'assistant', content: r.output?.content ?? '(空)' });
      }
    } catch (e) {
      append(agentId, { role: 'assistant', content: `⚠️ ${(e as Error).message}` });
    } finally {
      setBusy(false);
    }
  }, [agentId, input, stream, msgsOf, append, replace]);

  /** 开始新对话：先把本轮对话保存进会话历史，再清空本地。 */
  const newConversation = useCallback(async () => {
    const current = msgsOf(agentId);
    if (!agentId) return;
    if (current.length > 0) {
      try {
        const firstUser = current.find((m) => m.role === 'user')?.content ?? '';
        await importConversation({
          agentId,
          userId: 'demo-user',
          title: firstUser.slice(0, 50) || '未命名对话',
          messages: current.map((m) => ({ role: m.role, content: m.content })),
        });
        message.success(`本轮对话（${current.length} 条）已保存到会话历史`);
      } catch (e) {
        message.error(`保存会话失败：${(e as Error).message}`);
        return; // 保存失败不清空，避免丢失
      }
    }
    reset(agentId);
    setInput('');
    message.info('已开始新对话');
  }, [agentId, msgsOf, reset]);

  return (
    <Card
      title="对话运行"
      extra={
        <Space wrap>
          <Select
            placeholder="选择智能体"
            style={{ minWidth: 200 }}
            value={agentId}
            onChange={setAgentId}
            options={agents.map((a) => ({ value: a.agentId, label: `${a.name}（${a.modelBinding?.model ?? a.generationConfig?.model ?? '默认模型'}）` }))}
          />
          <span>流式</span>
          <Switch checked={stream} onChange={setStream} />
          <Popconfirm title="开始新一轮对话？当前对话将先保存到会话历史。" onConfirm={newConversation}>
            <Button type="primary" icon={<PlusOutlined />}>新对话</Button>
          </Popconfirm>
        </Space>
      }
      styles={{ body: { paddingBottom: 8 } }}
    >
      {messages.length > 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 8 }}
          message={`本轮对话共 ${messages.length} 条。切换页面不会丢失；点击「新对话」将自动保存到会话历史。`}
        />
      )}
      <div ref={listRef} style={{ height: 'calc(100vh - 300px)', minHeight: 300, overflowY: 'auto', paddingRight: 4 }}>
        <List
          dataSource={messages}
          locale={{ emptyText: <Empty description="发送一条消息开始对话" /> }}
          renderItem={(m, i) => (
            <List.Item
              key={i}
              style={{
                justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                border: 'none',
                padding: '8px 0',
              }}
            >
              <div style={{ maxWidth: '75%' }}>
                <Tag color={m.role === 'user' ? 'blue' : 'green'} style={{ marginBottom: 4 }}>
                  {m.role === 'user' ? '你' : '助手'}
                </Tag>
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }} copyable={false}>
                  {m.content}
                </Typography.Paragraph>
              </div>
            </List.Item>
          )}
        />
      </div>
      <Space.Compact style={{ width: '100%', marginTop: 12 }}>
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          autoSize={{ minRows: 1, maxRows: 4 }}
          placeholder="输入消息，回车发送，Shift+回车换行"
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
        />
        <Button
          type="primary"
          icon={<ClearOutlined />}
          onClick={() => {
            if (messages.length === 0) {
              message.info('当前没有消息');
              return;
            }
            if (window.confirm('丢弃当前对话（不保存到会话历史）？')) reset(agentId);
          }}
        >
          清空
        </Button>
        <Button type="primary" icon={<SendOutlined />} loading={busy} onClick={send}>
          发送
        </Button>
      </Space.Compact>
    </Card>
  );
}
