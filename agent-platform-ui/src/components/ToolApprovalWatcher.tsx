import { useEffect, useRef } from 'react';
import { App as AntApp, Typography } from 'antd';
import {
  approveToolCall,
  listApprovals,
  rejectToolCall,
  type ApprovalItem,
} from '../api/approvals';

const { Text } = Typography;

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
        cancelText: '拒绝',
        // 不设 maskClosable=false：用户可以点遮罩先放着，稍后去「工具审批」页处理
        content: (
          <div style={{ marginTop: 8 }}>
            <div style={{ marginBottom: 8 }}>
              <Text strong>{row.summary || `${row.toolName} 请求执行`}</Text>
            </div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              智能体正在等待你的确认，批准后才会真正修改工作区内的文件；
              系统已留好改前快照，之后可以回滚。
            </Text>
            {row.toolArgs && (
              <pre
                style={{
                  marginTop: 10,
                  marginBottom: 0,
                  fontSize: 12,
                  maxHeight: 200,
                  overflow: 'auto',
                  background: 'rgba(0,0,0,0.03)',
                  padding: 8,
                  borderRadius: 4,
                }}
              >
                {row.toolArgs}
              </pre>
            )}
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
