import type { CSSProperties } from 'react';
import { Collapse, Space, Tag, Typography } from 'antd';
import { ToolOutlined } from '@ant-design/icons';
import type { ToolCallInfo } from '../../api/run';

/**
 * 工具调用可视化：展示一轮里 agent 调了哪些工具、结果如何。
 *
 * <h3>它解决什么</h3>
 * 在此之前，工具调用只存在于后端的日志与回灌给模型的消息里，**用户侧完全不可见** ——
 * agent 在后台 grep 了 200 个文件、读了 3 个、改写了 1 个，界面上只是"想了一会儿然后给出答案"。
 * 工具越多这个盲区越大：用户无法判断它是查过了才回答、还是压根没查就编。
 *
 * <h3>为什么默认折叠、且不占主导</h3>
 * 用户要的是**答案**，过程是"需要时才看"的佐证。所以这里只显示一行摘要
 * （次数 / 失败数 / 总耗时），细节（参数、结果）要点开才展开 ——
 * 若默认全展开，一次多轮任务会把聊天记录冲成几十屏的 JSON。
 */

/** 细节块样式（参数/结果的 pre）。 */
const preStyle: CSSProperties = {
  margin: '4px 0 0',
  padding: '6px 8px',
  maxHeight: 240,
  overflow: 'auto',
  fontSize: 12,
  lineHeight: 1.5,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  background: 'rgba(0, 0, 0, 0.04)',
  borderRadius: 4,
};

const summaryStyle: CSSProperties = { cursor: 'pointer', fontSize: 12 };

/** 尝试把 JSON 文本缩进美化；不是合法 JSON 就原样返回（后端截断后可能不是合法 JSON）。 */
function pretty(text?: string): string {
  if (!text) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export default function ToolCallList({ calls }: { calls?: ToolCallInfo[] }) {
  if (!calls || calls.length === 0) {
    return null;
  }
  const failed = calls.filter((c) => c.success === false).length;
  const totalMs = calls.reduce((sum, c) => sum + (c.latencyMs ?? 0), 0);

  return (
    <Collapse
      ghost
      size="small"
      style={{ marginTop: 6 }}
      items={[
        {
          key: 'tools',
          label: (
            <Space size={6} wrap>
              <ToolOutlined />
              <span>工具调用 {calls.length} 次</span>
              {failed > 0 ? <Tag color="error">{failed} 次失败</Tag> : null}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                共 {totalMs} ms
              </Typography.Text>
            </Space>
          ),
          children: (
            <div>
              {calls.map((c, i) => (
                // 工具调用记录由后端顺序返回，下标即顺序，没有稳定 id 可用
                // eslint-disable-next-line react/no-array-index-key
                <div key={i} style={{ marginBottom: 10 }}>
                  <Space size={6} wrap>
                    <Typography.Text type="secondary">{i + 1}.</Typography.Text>
                    <Tag color={c.success === false ? 'error' : 'blue'}>{c.name}</Tag>
                    {typeof c.latencyMs === 'number' ? (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {c.latencyMs} ms
                      </Typography.Text>
                    ) : null}
                  </Space>
                  {c.arguments && c.arguments !== '{}' ? (
                    <details style={{ marginTop: 4 }}>
                      <summary style={summaryStyle}>参数</summary>
                      <pre style={preStyle}>{pretty(c.arguments)}</pre>
                    </details>
                  ) : null}
                  {c.error || c.output ? (
                    <details style={{ marginTop: 4 }}>
                      <summary style={summaryStyle}>{c.success === false ? '错误' : '结果'}</summary>
                      <pre style={preStyle}>{c.error ?? c.output}</pre>
                    </details>
                  ) : null}
                </div>
              ))}
            </div>
          ),
        },
      ]}
    />
  );
}
