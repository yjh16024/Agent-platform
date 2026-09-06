import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Card, Select, Input, Button, Space, Switch, Tag, Empty, List, Typography, message, Popconfirm, Alert, Upload, Tooltip,
} from 'antd';
import { SendOutlined, PlusOutlined, ClearOutlined, PaperClipOutlined, BookOutlined } from '@ant-design/icons';
import { listAgents } from '../../api/agents';
import { runAgent, runAgentStream, RunMessage, MessagePart } from '../../api/run';
import { importConversation } from '../../api/sessions';
import { uploadFile } from '../../api/files';
import { AgentResponse, FileAsset } from '../../api/types';
import { useAppStore } from '../../store/appStore';
import { useChatStore, ChatMsg } from '../../store/chatStore';

/** 方案 A 可读附件数量上限（后端同样限制）。 */
const MAX_ATTACH = 4;

interface Attachment extends FileAsset {
  uploading?: boolean;
}

/**
 * 对话运行页。
 * - 当前对话历史常驻（全局 store + localStorage），「新对话」才保存进会话历史；
 * - 支持拖入/选择文件（方案 A）：上传后以 file part 发送，后端解析文本注入上下文。
 */
export default function ChatPage() {
  const { tenantId } = useAppStore();
  const { msgsOf, append, replace, reset } = useChatStore();
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [agentId, setAgentId] = useState<string | undefined>();
  const [input, setInput] = useState('');
  const [stream, setStream] = useState(false);
  const [busy, setBusy] = useState(false);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
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

  /** 附件文件被加入（上传中置 uploading 标记）。 */
  const addAttachment = (file: File) => {
    if (attachments.length >= MAX_ATTACH) {
      message.warning(`单次最多 ${MAX_ATTACH} 个文件`);
      return;
    }
    const placeholder: Attachment = { fileName: file.name, fileType: 'file', uploading: true };
    setAttachments((cur) => [...cur, placeholder]);
    uploadFile(file)
      .then((asset) => {
        setAttachments((cur) =>
          cur.map((a) => (a.fileName === file.name && a.uploading ? asset : a)),
        );
        message.success(`已上传 ${file.name}`);
      })
      .catch((e) => {
        message.error(`上传失败：${(e as Error).message}`);
        setAttachments((cur) => cur.filter((a) => !(a.fileName === file.name && a.uploading)));
      });
  };

  const removeAttachment = (index: number) => {
    setAttachments((cur) => cur.filter((_, i) => i !== index));
  };

  const send = useCallback(async () => {
    const text = input.trim();
    if (!agentId) {
      message.warning('请先选择智能体');
      return;
    }
    if (!text && attachments.length === 0) {
      return;
    }
    const ready = attachments.filter((a) => !a.uploading);
    if (ready.length !== attachments.length) {
      message.warning('仍有文件在上传，请稍候');
      return;
    }

    // 组装请求消息：历史为纯文本；本次 user 消息在带附件时使用 parts[]（text + file）
    const history: RunMessage[] = msgsOf(agentId).map((m) => ({ role: m.role, content: m.content }));
    let userMsg: RunMessage;
    if (ready.length > 0) {
      const parts: MessagePart[] = [];
      if (text) parts.push({ type: 'text', text });
      ready.forEach((a) =>
        parts.push({ type: 'file', fileId: a.fileId!, fileName: a.fileName }),
      );
      userMsg = { role: 'user', content: parts };
    } else {
      userMsg = { role: 'user', content: text };
    }
    const payload: RunMessage[] = [...history, userMsg];

    // 本地展示：文本 + 附件名；历史持久化只存字符串（附件内容由当轮后端解析）
    const displayText = ready.length > 0 && text ? text : text;
    const tagText =
      ready.length > 0 ? `\n\n[附文件：${ready.map((a) => a.fileName).join('、')}]` : '';
    append(agentId, { role: 'user', content: `${displayText}${tagText}`.trim() });
    setInput('');
    setAttachments([]);
    const curAgent = agents.find((a) => a.agentId === agentId);
    const kbIds = (curAgent?.capabilities?.knowledgeBaseIds ?? []).filter(Boolean) as string[];
    const rag = kbIds.length > 0 ? { useRag: true, knowledgeBaseIds: kbIds } : undefined;

    setBusy(true);
    try {
      if (stream) {
        let acc = '';
        append(agentId, { role: 'assistant', content: '' });
        await runAgentStream(agentId, payload, (delta) => {
          acc += delta;
          const copy = [...msgsOf(agentId)];
          copy[copy.length - 1] = { role: 'assistant', content: acc };
          replace(agentId, copy);
        }, rag);
        const finalMsgs = [...msgsOf(agentId)];
        if (finalMsgs.length > 0 && finalMsgs[finalMsgs.length - 1].content === '') {
          finalMsgs[finalMsgs.length - 1] = { role: 'assistant', content: '(空)' };
          replace(agentId, finalMsgs);
        }
      } else {
        const r = await runAgent(agentId, payload, rag);
        append(agentId, {
          role: 'assistant',
          content: r.output?.content ?? '(空)',
          refs: r.references && r.references.length > 0 ? r.references : undefined,
        });
      }
    } catch (e) {
      append(agentId, { role: 'assistant', content: `⚠️ ${(e as Error).message}` });
    } finally {
      setBusy(false);
    }
  }, [agentId, input, attachments, stream, msgsOf, append, replace, agents]);

  /** 开始新对话：先把本轮保存进会话历史，再清空本地。 */
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
        return;
      }
    }
    reset(agentId);
    setInput('');
    setAttachments([]);
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
      styles={{ body: { display: 'flex', flexDirection: 'column', height: 'calc(100vh - 200px)', minHeight: 420, paddingBottom: 8 } }}
    >
      {messages.length > 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 8, flex: '0 0 auto' }}
          message={`本轮对话共 ${messages.length} 条。切换页面不会丢失；点击「新对话」将自动保存到会话历史。`}
        />
      )}

      {/* 消息列表：占据中间全部剩余高度 */}
      <div ref={listRef} style={{ flex: 1, minHeight: 180, overflowY: 'auto', paddingRight: 4 }}>
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
              <div style={{ maxWidth: '80%' }}>
                <Tag color={m.role === 'user' ? 'blue' : 'green'} style={{ marginBottom: 4 }}>
                  {m.role === 'user' ? '你' : '助手'}
                </Tag>
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }} copyable={false}>
                  {m.content}
                </Typography.Paragraph>
                {m.role === 'assistant' && m.refs && m.refs.length > 0 && (
                  <div style={{ marginTop: 6, fontSize: 12 }}>
                    <Space size={4} wrap>
                      <BookOutlined style={{ color: '#8c8c8c' }} />
                      {m.refs.slice(0, 6).map((r, ri) => (
                        <Tooltip
                          key={ri}
                          title={`score: ${r.score ?? '-'}${r.page ? ` · 第${r.page}页` : ''}`}
                        >
                          <Tag style={{ cursor: 'pointer', marginInlineEnd: 0 }} color="gold">
                            来源 {ri + 1}：{r.source ?? r.chunkId ?? '未知'}
                          </Tag>
                        </Tooltip>
                      ))}
                    </Space>
                  </div>
                )}
              </div>
            </List.Item>
          )}
        />
      </div>

      {/* 附件区 + 拖拽上传（方案 A） */}
      <div style={{ flex: '0 0 auto', marginTop: 8 }}>
        <Upload.Dragger
          multiple
          showUploadList={false}
          style={{ padding: '10px 12px' }}
          beforeUpload={(file) => {
            addAttachment(file as unknown as File);
            return false;
          }}
        >
          <Space size={8} align="center" wrap>
            <PaperClipOutlined style={{ fontSize: 18, color: '#1677ff' }} />
            <Typography.Text strong>拖入文件，或点击此处选择（txt / md / pdf / docx / pptx / xlsx / csv 可被智能体读取）</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              最多 {MAX_ATTACH} 个；图片/音视频暂不可直接读取（将走方案 B / C）
            </Typography.Text>
          </Space>
        </Upload.Dragger>

        {attachments.length > 0 && (
          <Space wrap size={[4, 4]} style={{ marginTop: 6 }}>
            {attachments.map((a, idx) => (
              <Tag
                key={`${a.fileName}-${idx}`}
                closable={!a.uploading}
                onClose={() => removeAttachment(idx)}
                color={a.uploading ? 'processing' : 'blue'}
              >
                {a.uploading ? `上传中… ${a.fileName}` : a.fileName}
              </Tag>
            ))}
          </Space>
        )}

        <Space.Compact style={{ width: '100%', marginTop: 8 }}>
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
              if (messages.length === 0 && attachments.length === 0) {
                message.info('当前没有消息');
                return;
              }
              if (window.confirm('丢弃当前对话与附件（不保存到会话历史）？')) {
                reset(agentId);
                setAttachments([]);
              }
            }}
          >
            清空
          </Button>
          <Button type="primary" icon={<SendOutlined />} loading={busy} onClick={send}>
            发送
          </Button>
        </Space.Compact>
      </div>
    </Card>
  );
}
