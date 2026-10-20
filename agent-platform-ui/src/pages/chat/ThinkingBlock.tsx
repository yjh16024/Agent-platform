import { useEffect, useRef, useState } from 'react';
import { BulbOutlined, DownOutlined, UpOutlined } from '@ant-design/icons';

/**
 * 「思考过程」折叠块（2026-10-19）。
 *
 * <h3>流式时自动展开</h3>
 * 后端是**逐帧**下发思考增量的（SSE `run.reasoning`），前端也逐字累积 ——
 * 但如果一直折叠着，用户只看到「思考过程（N 字）」的数字在涨，看不到内容，
 * 感受上就像"不支持流式"。所以：**正在思考时自动展开并跟随滚动，结束后自动收起**。
 *
 * <p>「自动收起」只针对<b>自动展开</b>的那种：用户自己点开过的，结束后保持打开
 * （否则他刚点开看，回复一结束就自己合上了，很恼人）。</p>
 *
 * <h3>为什么默认折叠（非流式时）</h3>
 * 思维链常常几千字，铺开会把真正的回答挤到屏幕外。折叠后标签上显示字数，想看细节再点开。
 *
 * <h3>为什么是它自己的组件</h3>
 * 思维链和回答是**两段独立文本**（后端也分开发：`run.reasoning` vs `run.delta`）。
 * 塞在一起会让"复制这段回答"把自我独白也带上。
 */
export default function ThinkingBlock({ text, streaming }: { text: string; streaming?: boolean }) {
  const [open, setOpen] = useState(false);
  /** 是否由「流式开始」自动打开的 —— 只有这种才会在结束时自动收起。 */
  const autoOpenedRef = useRef(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (streaming) {
      autoOpenedRef.current = true;
      setOpen(true);
    } else if (autoOpenedRef.current) {
      autoOpenedRef.current = false;
      setOpen(false);
    }
  }, [streaming]);

  // 跟随最新：思考内容越写越长，不滚动的话用户只能看到最开头几句
  useEffect(() => {
    if (streaming && open && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [text, streaming, open]);

  if (!text) {
    return null;
  }

  return (
    <div style={{ marginBottom: 8 }}>
      <a
        onClick={() => {
          // 用户手动点了：之后结束就不再自动收起
          autoOpenedRef.current = false;
          setOpen((v) => !v);
        }}
        style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', userSelect: 'none' }}
      >
        <BulbOutlined />{' '}
        {streaming
          ? `思考中…（${text.length} 字）`
          : open
            ? '收起思考过程'
            : `思考过程（${text.length} 字）`}
        {open ? <UpOutlined style={{ marginLeft: 4, fontSize: 10 }} /> : <DownOutlined style={{ marginLeft: 4, fontSize: 10 }} />}
      </a>
      {open && (
        <div
          ref={bodyRef}
          style={{
            marginTop: 6,
            padding: '8px 10px',
            background: 'rgba(0,0,0,0.03)',
            borderLeft: '2px solid rgba(0,0,0,0.12)',
            borderRadius: 4,
            whiteSpace: 'pre-wrap',
            color: 'rgba(0,0,0,0.55)',
            fontSize: 12,
            lineHeight: 1.65,
            maxHeight: 320,
            overflow: 'auto',
          }}
        >
          {text}
        </div>
      )}
    </div>
  );
}
