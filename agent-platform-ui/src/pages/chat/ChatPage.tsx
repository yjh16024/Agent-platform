import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Card, Select, Input, Button, Space, Switch, Tag, Typography, message, Alert, Upload, Tooltip,
} from 'antd';
import { PlusOutlined, ClearOutlined, PaperClipOutlined, BookOutlined, ArrowUpOutlined } from '@ant-design/icons';
import { listAgents } from '../../api/agents';
import { runAgent, runAgentStream, RunMessage, MessagePart } from '../../api/run';
import { uploadFile } from '../../api/files';
import { AgentResponse, FileAsset } from '../../api/types';
import { useAppStore } from '../../store/appStore';
import { useChatStore, ChatMsg } from '../../store/chatStore';
import ToolCallList from './ToolCallList';
import { composerCardHooks, composerSeatHooks, conversationHooks, HOST_ATTRS, SLOTS } from '../../skin/contract';
// composerSeatHooks 用在 composer 内部（座位层），见该处说明
import {
  CLASS_BUBBLE,
  CLASS_CARD,
  CLASS_HEADLINE_TEXT,
  CLASS_MARKDOWN,
  CLASS_NEW_SESSION,
  CLASS_PENDING,
  CLASS_ROW,
  CLASS_ROW_TEXT,
  CLASS_STREAM_ROW,
  CLASS_TRIGGER_LABEL,
  CLASS_USER_STACK,
  frag,
} from '../../skin/class-fragments';

/** 方案 A 可读附件数量上限（后端同样限制）。 */
const MAX_ATTACH = 4;

interface Attachment extends FileAsset {
  uploading?: boolean;
  /** 原始 MIME（图片走视觉 part 时传给后端；上传返回的 asset 不再携带，故本地暂存）。 */
  mime?: string;
}

/**
 * 对话运行页。
 *
 * 布局对齐 DSH Web GUI 的观感：
 * - **空态**（还没发过消息）：品牌区 + 输入框**整体居中**，背景用皮肤包的 `hero` 图；
 * - **对话中**（有消息后）：消息列表占满，输入框**沉到底部**，背景换成 `active` 图。
 *
 * 两种状态共用同一个 composer（输入卡片），保证切换时输入内容与附件不丢。
 * 空态/对话中由 `data-phase`（hero / active）表达 —— 那是 DSH 的契约钩子，皮肤的场景机靠它切换。
 *
 * 其余行为不变：当前对话历史常驻（全局 store + localStorage），「新对话」才保存进会话历史；
 * 支持拖入/选择文件（方案 A）：上传后以 file part 发送，后端解析文本注入上下文。
 */
export default function ChatPage() {
  const { tenantId } = useAppStore();
  const { msgsOf, append, replace, reset, sessionOf } = useChatStore();
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [agentId, setAgentId] = useState<string | undefined>();
  const [input, setInput] = useState('');
  const [stream, setStream] = useState(false);
  const [toolsEnabled, setToolsEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [dragging, setDragging] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  const messages: ChatMsg[] = msgsOf(agentId);
  /** 是否已进入对话（决定输入框居中还是沉底）。 */
  const hasConversation = messages.length > 0;

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
    const isImage = file.type.startsWith('image/');
    const placeholder: Attachment = {
      fileName: file.name,
      fileType: isImage ? 'image' : 'file',
      mime: file.type,
      uploading: true,
    };
    setAttachments((cur) => [...cur, placeholder]);
    uploadFile(file)
      .then((asset) => {
        setAttachments((cur) =>
          cur.map((a) => (a.fileName === file.name && a.uploading ? { ...asset, mime: file.type } : a)),
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
      ready.forEach((a) => {
        // 图片以 image part 发送（后端走视觉通道）；其余仍为 file part（文本注入 / 自动摄取）
        if (a.fileType === 'image') {
          parts.push({ type: 'image', fileId: a.fileId!, fileName: a.fileName, mimeType: a.mime });
        } else {
          parts.push({ type: 'file', fileId: a.fileId!, fileName: a.fileName });
        }
      });
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
    const tools = toolsEnabled ? { enabled: true, allowed: [] as string[] } : undefined;

    /*
      运行时会话 ID（**必须带上**）。
      后端 SessionService.resolve() 在 sessionId 为空时直接返回 null，后果是连锁的：
      消息不落库、历史不回放、短期缓存/中期摘要拿不到会话、向量记忆不索引、
      工具审批无法按会话关联 —— 也就是"四层记忆"整条失效。
      同一智能体跨轮复用同一个 ID（存在 store 里，刷新也还在），点「新对话」时更换。
    */
    const sessionId = sessionOf(agentId);

    setBusy(true);
    try {
      if (stream) {
        let acc = '';
        append(agentId, { role: 'assistant', content: '' });
        const outcome = await runAgentStream(agentId, payload, (delta) => {
          acc += delta;
          const copy = [...msgsOf(agentId)];
          copy[copy.length - 1] = { role: 'assistant', content: acc };
          replace(agentId, copy);
        }, rag, tools, sessionId);
        const finalMsgs = [...msgsOf(agentId)];
        const lastIdx = finalMsgs.length - 1;
        if (lastIdx >= 0 && finalMsgs[lastIdx].role === 'assistant') {
          const last = { ...finalMsgs[lastIdx] };
          if (last.content === '') {
            last.content = '(空)';
          }
          // 工具调用记录（可视化）：流结束时后端一次性给出，挂到最后一条助手消息上。
          // 不用「先 append 再逐个 replace」的方式 —— 那会让界面在流式结束后再闪一次。
          if (outcome.toolCalls.length > 0) {
            last.toolCalls = outcome.toolCalls;
          }
          finalMsgs[lastIdx] = last;
          replace(agentId, finalMsgs);
        }
      } else {
        const r = await runAgent(agentId, payload, rag, tools, sessionId);
        append(agentId, {
          role: 'assistant',
          content: r.output?.content ?? '(空)',
          refs: r.references && r.references.length > 0 ? r.references : undefined,
          toolCalls: r.toolCalls && r.toolCalls.length > 0 ? r.toolCalls : undefined,
        });
      }
    } catch (e) {
      append(agentId, { role: 'assistant', content: `⚠️ ${(e as Error).message}` });
    } finally {
      setBusy(false);
    }
  }, [agentId, input, attachments, stream, toolsEnabled, msgsOf, append, replace, agents, sessionOf]);

  /**
   * 开始新对话：清空本地记录并换一个新的运行时会话 ID。
   *
   * <p>⚠️ <b>2026-09-23 行为变更：不再把当前对话「导入」成一条新会话。</b>
   * 以前消息只存在浏览器里（因为没有 sessionId，后端根本没建会话），
   * 所以需要这一步显式归档；现在前端会把 sessionId 传给后端、
   * 每轮消息**本身就已经落进会话**了 —— 再导入一次会得到两条内容相同的记录，
   * 而且新会话的 ID 与后端正在用的那个也对不上。</p>
   */
  const newConversation = useCallback(async () => {
    if (!agentId) return;
    if (msgsOf(agentId).length === 0) {
      message.info('当前没有消息');
      return;
    }
    reset(agentId);
    setInput('');
    setAttachments([]);
    message.success('已开始新对话，上一轮可在「会话历史」中查看');
  }, [agentId, msgsOf, reset]);

  const discardConversation = () => {
    // 与 newConversation 保持一致的守卫：没有选中智能体时连会话标识都不存在，
    // reset 拿不到可用的 agentId。此处原先漏了它，是开了 strictNullChecks 才暴露出来的。
    if (!agentId) return;
    if (messages.length === 0 && attachments.length === 0) {
      message.info('当前没有消息');
      return;
    }
    if (window.confirm('丢弃当前对话与附件（不保存到会话历史）？')) {
      reset(agentId);
      setAttachments([]);
    }
  };

  const agentOptions = useMemo(
    () =>
      agents.map((a) => ({
        value: a.agentId,
        label: `${a.name}（${a.modelBinding?.model ?? a.generationConfig?.model ?? '默认模型'}）`,
      })),
    [agents],
  );

  /**
   * 输入卡片：空态居中、对话中沉底，两处共用同一个实例，
   * 保证切换布局时输入内容与附件不丢。
   */
  const composer = (
    /*
     * **两层结构，不要合并**：
     *   外层 = 座位（dock + seat）：负责定位、折叠、接收拖放；
     *   内层 = 卡片（data-composer-card）：皮肤在这里画边框、背景，以及两侧向外伸出的"侧臂"。
     *
     * orca 的 CSS 用的是**后代选择器** —— `[data-composer-seat] [data-composer-card]`（宽度控制）、
     * `[data-phase='active'] [data-composer-seat]`（折叠过渡）。把这两个属性挂到同一个元素上，
     * 那些规则永远匹配不到，按"卡片外侧 -10px"定位的侧臂也会错位
     * —— 就是"输入框外面有东西突出来"的来源。
     */
    <div
      {...composerSeatHooks}
      // 原生拖放：整个座位都是投放区（比原来的 Upload.Dragger 更贴合现在的紧凑形态）
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        Array.from(e.dataTransfer.files ?? []).forEach((f) => addAttachment(f));
      }}
    >
      <div
        {...composerCardHooks}
        // DSH 宿主契约：流式输出状态（皮肤据此挂动效）+ 通用状态
        {...{
          [HOST_ATTRS.streaming]: busy ? '' : undefined,
          'data-state': busy ? 'running' : 'idle',
        }}
        // 拖拽高亮用属性标记：好让"皮肤让位"那条 CSS 规则（:not([data-dragging])）放过它
        {...{ 'data-dragging': dragging ? '' : undefined }}
        style={{
          /*
           * 这里是**默认外观**（没有皮肤 JS 时）。
           *
           * 一旦有皮肤在跑，`html[data-ap-skin-active]` 上的规则会把
           * 背景 / 边框 / 阴影整个让给皮肤 —— 见 runtime.ts 的 installSkinYieldStyles()。
           * 为什么不用条件内联样式：实测内联的让位值会被更高优先级的声明压住，
           * 所以"让位"必须是一条带 !important 的确定性规则。
           */
          border: `1px solid ${dragging ? 'var(--ap-primary)' : 'var(--ap-border)'}`,
          borderRadius: 14,
          background: 'var(--ap-bg-container)',
          boxShadow: dragging ? '0 0 0 3px rgba(22, 119, 255, 0.16)' : '0 6px 24px rgba(0, 0, 0, 0.10)',
          backdropFilter: 'blur(6px)',
          transition: 'border-color .15s, box-shadow .15s',
          /*
           * 这里**故意不设 padding**：默认值放在 global.css 的
           * `[data-composer-card]` 规则里（低特异性）。
           *
           * 原因是内联的 padding 会压掉皮肤给卡片预留的顶部空间
           * （maid-atelier 写的是 `padding-top: 34px`，那是给它那圈蕾丝缎带留的），
           * 而且**没法用"让位样式"补救**：`installSkinYieldStyles()` 能靠 `!important`
           * 把 background/border 清成 transparent，但 padding 一旦写成 `!important`
           * 就会连皮肤那个 34px 一起压掉 —— 覆盖不等于让位。
           * 所以它必须待在样式表里，让能力更强的皮肤选择器自然胜出。
           */
        }}
      >
        {/* 附件速览 */}
        {attachments.length > 0 && (
          <Space
            wrap
            size={[4, 4]}
            {...{ [HOST_ATTRS.slot]: SLOTS.conversationInputAttachments }}
            style={{ marginBottom: 6 }}
          >
            {attachments.map((a, idx) => (
              <Tag
                key={`${a.fileName}-${idx}`}
                closable={!a.uploading}
                onClose={() => removeAttachment(idx)}
                color={a.uploading ? 'processing' : 'blue'}
                style={{ marginInlineEnd: 0 }}
              >
                {a.uploading ? `上传中… ${a.fileName}` : a.fileName}
              </Tag>
            ))}
          </Space>
        )}

        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          variant="borderless"
          autoSize={{ minRows: 1, maxRows: 8 }}
          placeholder="输入消息，回车发送，Shift+回车换行"
          style={{ padding: 0, fontSize: 15 }}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
        />

        {/* 底部工具条 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 6 }}>
          <Space size={8} wrap>
            <Upload
              multiple
              showUploadList={false}
              beforeUpload={(file) => {
                addAttachment(file as unknown as File);
                return false;
              }}
            >
              <Tooltip title={`添加文件（最多 ${MAX_ATTACH} 个：txt / md / pdf / docx / pptx / xlsx / csv，也可直接拖到此处）`}>
                <Button shape="circle" size="small" icon={<PaperClipOutlined />} />
              </Tooltip>
            </Upload>
            {/*
              只挂 `triggerLabel`（"某个触发器的标签"，语义真的对应）。
              **故意不挂** `cardWorkspaceTrigger` / `triggerEffort`：前者特指 DSH 的"工作区切换器"、
              后者特指"推理强度控制"，我们平台没有这两个概念——硬挂会让皮肤的规则误伤我们的布局。
            */}
            <Select
              placeholder="选择智能体"
              className={frag(CLASS_TRIGGER_LABEL)}
              style={{ minWidth: 190 }}
              size="small"
              value={agentId}
              onChange={setAgentId}
              options={agentOptions}
              variant="borderless"
            />
            <Space size={4}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                流式
              </Typography.Text>
              <Switch size="small" checked={stream} onChange={setStream} />
            </Space>
            <Tooltip title="开启后智能体可自动调用已注册工具（calc/search 等）；仅建议在支持 function calling 的模型上使用">
              <Space size={4}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  工具
                </Typography.Text>
                <Switch size="small" checked={toolsEnabled} onChange={setToolsEnabled} />
              </Space>
            </Tooltip>
          </Space>

          <Space size={6}>
            <Tooltip title="丢弃当前对话（不保存）">
              <Button size="small" type="text" icon={<ClearOutlined />} onClick={discardConversation} />
            </Tooltip>
            <Tooltip title="把本轮保存到会话历史并开始新对话">
              <Button
                size="small"
                type="text"
                className={frag(CLASS_NEW_SESSION)}
                icon={<PlusOutlined />}
                onClick={newConversation}
              />
            </Tooltip>
            <Button
              type="primary"
              shape="circle"
              icon={<ArrowUpOutlined />}
              loading={busy}
              onClick={send}
              disabled={!input.trim() && attachments.length === 0}
            />
          </Space>
        </div>
    </div>
      </div>
  );

  /**
   * 同一用户的连续消息算一「栈」。
   *
   * <p>DSH 皮肤用 `_userStack` + 栈内位置来收气泡圆角（首/中/末/单独各不相同），
   * 所以这里给出**真实的栈位置**，而不是无条件挂个类名 —— 后者会让皮肤把所有
   * 用户气泡都当成栈内元素，圆角全错。</p>
   */
  const stackAt = (i: number): { inStack: boolean; pos: 'first' | 'middle' | 'last' | 'single' } => {
    const isUser = messages[i]?.role === 'user';
    const prevUser = i > 0 && messages[i - 1]?.role === 'user';
    const nextUser = i + 1 < messages.length && messages[i + 1]?.role === 'user';
    if (!isUser || (!prevUser && !nextUser)) {
      return { inStack: false, pos: 'single' };
    }
    return { inStack: true, pos: !prevUser ? 'first' : !nextUser ? 'last' : 'middle' };
  };

  /**
   * 这一行在聊天流里的种类。
   *
   * <p><b>只有 `turn-tail` 是有契约意义的</b>：orca 的 `resolveStatus()` 里写着
   * `if (tail?.dataset.chatFlowKind === 'turn-tail') return 'complete'` ——
   * 它靠"最后一行是尾部"来判定这一轮已收尾（侧栏信号灯从 working 切到 complete）。
   * 其余取值它不看，但仍然如实标出（`turn` = 这一轮里还不是尾部的行）。</p>
   *
   * <p>判定条件：**不在流式中**、且是最后一条 —— 此时这一轮才是收尾的。</p>
   */
  const flowKindAt = (i: number): string => (!busy && i === messages.length - 1 ? 'turn-tail' : 'turn');

  return (
    <Card
      {...conversationHooks}
      /*
       * DSH 宿主契约：会话阶段。orca 的 scene.ts 会找 `[data-phase]`，
       * 并确认它的后代里有 `[data-conversation-scroll]` —— 所以 phase 必须挂在
       * 滚动区的**祖先**上，滚动区本身挂 data-conversation-scroll。
       * 值域与 DSH 一致：hero（空态）/ settling / active（对话中）。
       */
      {...{ [HOST_ATTRS.phase]: hasConversation ? 'active' : 'hero' }}
      title={<span {...{ [HOST_ATTRS.slot]: SLOTS.conversationSessionHeader }}>对话运行</span>}
      styles={{
        body: {
          display: 'flex',
          flexDirection: 'column',
          height: 'calc(100vh - 190px)',
          minHeight: 460,
          paddingBottom: 8,
        },
      }}
    >
      {hasConversation && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 8, flex: '0 0 auto' }}
          message={`本轮对话共 ${messages.length} 条。切换页面不会丢失；点右上角「+」将自动保存到会话历史。`}
        />
      )}

      {/*
       * DSH 宿主契约：可滚动的会话区必须**在两种形态下都存在**。
       *
       * orca 的 scene.ts 是这样定位会话根的：
       *   for (const c of body.querySelectorAll('[data-phase]'))
       *     if (c.querySelector('[data-conversation-scroll]')?.closest('[data-phase]') === c) return c
       * 也就是说它要求「带 data-phase 的元素，其**后代里**要有 data-conversation-scroll」。
       *
       * 之前我只在「有消息」分支里渲染滚动区 → **空态下皮肤找不到会话根**，
       * hero/active 的场景交叉淡化与状态镜像全部失效。这里改成两种形态共用一个滚动容器。
       */}
      <div
        ref={listRef}
        {...{ [HOST_ATTRS.conversationScroll]: '' }}
        style={{
          flex: 1,
          minHeight: 180,
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          paddingRight: 4,
        }}
      >
        {hasConversation ? (
          /* 聊天流容器：皮肤的 `[data-chat-flow] *` 规则针对气泡与卡片 */
          <div {...{ [HOST_ATTRS.chatFlow]: '' }}>
            {/*
              **这一层不能用 antd 的 `<List>` 包。**

              皮肤（orca）判定"这一轮收没收尾"是这么写的：
                flow.children.filter(c => c.hasAttribute('data-chat-flow-kind')).at(-1)
              —— 只取 `[data-chat-flow]` 的**直接子元素**。而 `<List>` 会在中间多插一层
              `.ant-list-items`，于是它永远取不到带 kind 的行，`resolveStatus()` 就永远
              返回 ready（信号灯不会切到 complete）。

              所以这里手动 map：`[data-chat-flow]` 的直接子元素就是消息行本身。
            */}
            {messages.map((m, i) => (
              <div
                key={i}
                className={frag(CLASS_ROW, stackAt(i).inStack && CLASS_USER_STACK)}
                {...{
                  [HOST_ATTRS.slot]: SLOTS.conversationChatNode,
                  [HOST_ATTRS.chatFlowKind]: flowKindAt(i),
                  'data-stack-position': stackAt(i).inStack ? stackAt(i).pos : undefined,
                }}
                style={{
                  display: 'flex',
                  justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                  padding: '8px 0',
                }}
              >
                <div className={frag(CLASS_CARD, CLASS_BUBBLE)} style={{ maxWidth: '80%' }}>
                  <Tag color={m.role === 'user' ? 'blue' : 'green'} style={{ marginBottom: 4 }}>
                    {m.role === 'user' ? '你' : '助手'}
                  </Tag>
                  {/*
                    流式等待占位：内容还是空的时候给出 `_pending` 节点，
                    皮肤对它挂了动效（orca 的 `[data-orca-link-status='working']` 配合）。
                    不加这个的话，"正在生成"在界面上是一片空白。
                  */}
                  {m.role === 'assistant' && m.content === '' ? (
                    <div className={frag(CLASS_PENDING, CLASS_STREAM_ROW)} style={{ opacity: 0.7 }}>
                      <Typography.Text type="secondary">正在生成…</Typography.Text>
                    </div>
                  ) : (
                    <Typography.Paragraph
                      className={frag(CLASS_ROW_TEXT, CLASS_MARKDOWN)}
                      style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                      copyable={false}
                    >
                      {m.content}
                    </Typography.Paragraph>
                  )}
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
                  {/*
                    工具调用可视化：让用户看到"它查过什么"，而不只是最终答案。
                    默认折叠、且无调用时组件自身返回 null —— 见 ToolCallList 的类注释。
                  */}
                  {m.role === 'assistant' && <ToolCallList calls={m.toolCalls} />}
                </div>
              </div>
            ))}
          </div>
        ) : (
          /* 空态：品牌区 + 输入框整体居中（对齐 DSH 的初见形态） */
          <div
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 22,
              paddingBottom: 24,
            }}
          >
            <Space direction="vertical" align="center" size={6}>
              {/*
                这里**故意不加钩子**：orca 对它的选择器是 `[class*='headlineText']`
                ——那属于"类名片段层"（DSH 内部 CSS Modules 类名），本轮有意延后。
                编一个 `data-slot='conversation.headline'` 没有任何皮肤会用，属于自欺。
              */}
              <Typography.Title
                level={3}
                className={frag(CLASS_HEADLINE_TEXT)}
                style={{ margin: 0, fontWeight: 600 }}
              >
                白雾·智能体交互平台
              </Typography.Title>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                一个运行入口，按需要装配模型、知识、工具与记忆
              </Typography.Text>
              {!agents.length && (
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  还没有可用智能体，先去「智能体」页创建一个
                </Typography.Text>
              )}
            </Space>

            {/* 纯布局包裹；座位层（dock + seat）在 composer 内部，见其说明 */}
            <div style={{ width: '100%', maxWidth: 720 }}>{composer}</div>

            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              支持拖入文件；回车发送、Shift+回车换行
            </Typography.Text>
          </div>
        )}
      </div>

      {/* 对话中：输入框沉底（空态时 composer 已在滚动区内居中） */}
      {hasConversation && <div style={{ flex: '0 0 auto', marginTop: 10 }}>{composer}</div>}
    </Card>
  );
}
