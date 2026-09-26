import { useEffect, useRef, type CSSProperties } from 'react';
import { App as AntApp, Typography } from 'antd';
import {
  approveToolCall,
  listApprovals,
  rejectToolCall,
  type ApprovalItem,
} from '../api/approvals';

const { Text } = Typography;

/** 预览区统一样式。参数可能很长，必须能滚动，否则弹窗会被撑爆。 */
const PRE_STYLE: CSSProperties = {
  margin: '4px 0 10px',
  fontSize: 12,
  maxHeight: 180,
  overflow: 'auto',
  background: 'rgba(0,0,0,0.03)',
  padding: 8,
  borderRadius: 4,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
};

/** 单侧预览字符上限：用户不需要在弹窗里读完整个文件。 */
const PREVIEW_CHARS = 1500;

const LABEL_STYLE: CSSProperties = {
  marginBottom: 2,
  fontSize: 12,
  color: 'rgba(0,0,0,0.65)',
};

function clip(text: string) {
  return text.length > PREVIEW_CHARS
    ? `${text.slice(0, PREVIEW_CHARS)}\n…（已截断，全文 ${text.length} 字符）`
    : text;
}

/**
 * 把工具参数渲染成**人读得懂**的样子。
 *
 * <h3>为什么非做不可</h3>
 * 审批弹窗存在的**全部意义**就是让人看清"到底要改什么"。直接甩一段
 * {@code {"path":"a.md","content":"..."}} 给用户看，等于没有审批 —— 他只能盲点「批准」，
 * 而点下去就真的会改文件。**看不懂内容的确认，不是确认。**
 *
 * <p>两个写工具的 args 结构是已知的（见后端 {@code FsWriteFileTool} /
 * {@code FsEditFileTool} 的 {@code inputSchema}），所以直接摊平成「文件 + 写入内容」
 * 或「替换前 / 替换后」两段对照。**认不出的结构退回原始 JSON** ——
 * 宁可难看，也不能漏掉信息。</p>
 */
function ArgsPreview({ raw }: { raw?: string | null }) {
  if (!raw) {
    return null;
  }
  let obj: Record<string, unknown>;
  try {
    obj = JSON.parse(raw) as Record<string, unknown>;
  } catch {
    // 不是 JSON（不该发生，但不能因此吞掉信息）
    return <pre style={PRE_STYLE}>{raw}</pre>;
  }

  const filePath = typeof obj.path === 'string' ? obj.path : '';
  const content = typeof obj.content === 'string' ? obj.content : null;
  const oldString = typeof obj.oldString === 'string' ? obj.oldString : null;
  const newString = typeof obj.newString === 'string' ? obj.newString : null;

  const fileLine = filePath ? (
    <div style={{ marginBottom: 6 }}>
      文件：<Text code>{filePath}</Text>
    </div>
  ) : null;

  // fs_write_file：整体写入（新建或覆盖，都不是追加）
  if (content !== null) {
    return (
      <>
        {fileLine}
        <div style={LABEL_STYLE}>
          写入内容（<b>整体覆盖</b>，不是追加）：
        </div>
        <pre style={PRE_STYLE}>{clip(content)}</pre>
      </>
    );
  }

  // fs_edit_file：精确串替换 —— 用底色区分前后，比读两段 JSON 字符串快得多
  if (oldString !== null && newString !== null) {
    return (
      <>
        {fileLine}
        <div style={LABEL_STYLE}>替换前：</div>
        <pre style={{ ...PRE_STYLE, background: 'rgba(255,77,79,0.08)' }}>{clip(oldString)}</pre>
        <div style={LABEL_STYLE}>替换后：</div>
        <pre style={{ ...PRE_STYLE, background: 'rgba(82,196,26,0.10)' }}>
          {newString === '' ? '（删除这段内容）' : clip(newString)}
        </pre>
      </>
    );
  }

  // 未知结构：原样显示，绝不丢信息
  return <pre style={PRE_STYLE}>{raw}</pre>;
}

/**
 * 「就地确认」的弹窗监听器 —— 挂一次（在 AppLayout 里），全应用生效。
 *
 * <h3>它解决的是什么</h3>
 * 工具审批默认是**两步式**（模型说"你去工具审批页点一下"，用户再去点）。
 * 但对话场景里人就在屏幕前等着，多跑一个页面纯属折腾。
 * 所以后端在写工具执行时**阻塞等待**（默认 5 分钟），前端这个组件一发现有待审批就
 * **立刻弹窗**；用户点完，后端那边等待的工具调用会带着真实结果继续往下走 ——
 * 模型完全无感，就像这个工具本来就是那么慢的。
 *
 * <h3>为什么轮询间隔是 5 秒（比通知角标的 30 秒短得多）</h3>
 * 因为**对面有人在等**。通知角标晚 30 秒没人有感觉，但审批晚 30 秒弹出来
 * 会让用户以为"点了发送卡住了"。5 秒是"几乎感觉不到延迟"与"不浪费请求"之间的折中。
 * 超时降级后（两步式），这个延迟就不再有影响 —— 用户本来就要自己去页面处理。
 *
 * <h3>为什么不用 SSE 推</h3>
 * 审批弹窗要在**任何页面**都能出现（用户可能在会话列表页翻历史），
 * 而 SSE 只在对话请求期间才有连接。为此拉一条常驻 SSE 给一个低频事件，
 * 代价远大于每 5 秒一次极轻量的 count 查询。
 */
export default function ToolApprovalWatcher() {
  const { modal, message } = AntApp.useApp();
  /** 当前已弹出、尚未处理的申请 ID：避免同一条被反复弹（用户还没决定时每一轮都会轮到它）。 */
  const showingRef = useRef<string | null>(null);

  useEffect(() => {
    let stopped = false;

    const ask = (row: ApprovalItem) => {
      showingRef.current = row.approvalId;
      modal.confirm({
        title: '智能体请求修改文件',
        width: 560,
        icon: null,
        okText: '批准并执行',
        cancelText: '拒绝，不修改文件',
        // ★ 三个"不能关"都要显式关掉：antd 的 onCancel 由**取消按钮 / ESC / 右上角关闭**共同触发，
        // 而这里的 onCancel 语义是**明确的拒绝**（有副作用：留审计、模型收到"用户拒绝"并被告知别重试）。
        // 若放任 ESC 触发它，用户想"先放着再说"时一按 ESC 就变成了拒绝 —— 决定必须是显式的。
        // （最初那句注释写的是"点遮罩先放着"，但 Modal.confirm 的 maskClosable 默认就是 false，
        //   遮罩本来就点不动，等于用户**根本没有"稍后处理"这个选项**，注释与行为是矛盾的。）
        keyboard: false,
        closable: false,
        maskClosable: false,
        content: (
          <div style={{ marginTop: 8 }}>
            <div style={{ marginBottom: 8 }}>
              <Text strong>{row.summary || `${row.toolName} 请求执行`}</Text>
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              智能体正在等待你的确认，批准后才会真正修改工作区内的文件；
              系统已留好改前快照，之后可以回滚。
            </Text>
            <ArgsPreview raw={row.toolArgs} />
          </div>
        ),
        onOk: async () => {
          try {
            const updated = await approveToolCall(row.approvalId);
            if (updated.errorMsg) {
              // 批准成功但执行失败：必须说清楚，否则用户以为改成功了
              message.warning(`已批准，但执行失败：${updated.errorMsg}`);
            } else {
              message.success(updated.result || '已批准并执行');
            }
          } catch (e) {
            message.error(`批准失败：${(e as Error).message}`);
          } finally {
            showingRef.current = null;
          }
        },
        onCancel: async () => {
          try {
            await rejectToolCall(row.approvalId, '用户在对话中拒绝');
            message.info('已拒绝，未修改任何文件');
          } catch (e) {
            message.error(`拒绝失败：${(e as Error).message}`);
          } finally {
            showingRef.current = null;
          }
        },
      });
    };

    const tick = async () => {
      // 页面不可见时跳过（用户切走了，弹窗也看不见；等他切回来自然会弹）
      if (document.hidden || showingRef.current) {
        return;
      }
      try {
        const r = await listApprovals({ status: 'pending', page: 0, size: 1 });
        if (stopped || showingRef.current) {
          return;
        }
        const first = r.items?.[0];
        if (first) {
          ask(first);
        }
      } catch {
        // 静默：这是辅助能力，网络抖动不该打扰用户
      }
    };

    void tick();
    const timer = window.setInterval(() => void tick(), 5_000);
    // 页面重新可见时立刻补一轮（否则要等下一个 5 秒）
    const onVisible = () => {
      if (!document.hidden) {
        void tick();
      }
    };
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [modal, message]);

  // 纯副作用组件，不渲染任何东西
  return null;
}
