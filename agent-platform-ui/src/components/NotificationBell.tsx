import { useCallback, useEffect, useRef, useState } from 'react';
// 提示用原生 `title` 而不是 Tooltip：拖动过程中 Tooltip 会跟着闪现，干扰手感
import { Badge, Button, Modal } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { unreadCount } from '../api/notifications';
import NotificationsPanel from '../pages/notifications/NotificationsPanel';

/** 悬浮球直径（与 CSS 里的宽高必须一致，吸附计算依赖它）。 */
const SIZE = 52;
/** 距屏幕边缘小于它 → 吸附成"半隐藏"。 */
const EDGE = 30;
/** 位置持久化键。 */
const STORAGE_KEY = 'ap.notice.ball';

interface Pos {
  x: number;
  y: number;
}

/** 默认位置：右下角（与改造前一致）。 */
function defaultPos(): Pos {
  return { x: window.innerWidth - SIZE - 24, y: window.innerHeight - SIZE - 24 };
}

function loadPos(): Pos | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const p = JSON.parse(raw) as Pos;
    if (typeof p?.x !== 'number' || typeof p?.y !== 'number') {
      return null;
    }
    return p;
  } catch {
    return null;
  }
}

/**
 * 边界吸附：靠边就**半隐藏**（让一半在屏幕外）。
 *
 * <p>为什么是半隐藏而不是贴边：贴边只是"靠边放"，仍然占着整块区域；
 * 半隐藏后它只保留一个可点的"把手"，视觉上真正让位给内容 —— 这是悬浮球类控件的常见做法。</p>
 */
function snap(p: Pos): Pos {
  const half = SIZE / 2;
  let { x, y } = p;
  if (x < EDGE) {
    x = -half;
  } else if (x > window.innerWidth - SIZE - EDGE) {
    x = window.innerWidth - SIZE + half;
  }
  if (y < EDGE) {
    y = -half;
  } else if (y > window.innerHeight - SIZE - EDGE) {
    y = window.innerHeight - SIZE + half;
  }
  return { x, y };
}

/**
 * 消息通知（2026-10-16 从侧栏降级为悬浮入口；2026-10-19 改为**可拖动悬浮球**）。
 *
 * <h3>为什么可拖 + 可半隐藏</h3>
 * 固定右下角会长期压住内容区右下角（对话页的输入工具栏、表格的分页、插件的挂件都在那一带）。
 * 让用户把它拖到顺手的角落、并且**靠边时自动半隐藏**，比"平台替所有人决定一个位置"更合理。
 *
 * <h3>几个实现要点</h3>
 * <ul>
 *   <li><b>拖动与点击的区分</b>：位移超过 4px 才算拖动。少了这个判据，
 *       手一抖就会"拖了一下却弹出了通知"。</li>
 *   <li><b>拖动时不加 transition</b>：否则球会"追着"鼠标但慢半拍，手感很怪；
 *       松手吸附时才需要动画。</li>
 *   <li><b>位置存 localStorage</b>：刷新/重启后还在原地；窗口尺寸变化时重新收进可视区
 *       （否则把窗口缩小后球可能"消失在屏幕外"）。</li>
 *   <li><b>红点而不是数字</b>：计数只放在 Tooltip 里 —— 球上挂数字在窄屏会顶到内容。</li>
 * </ul>
 */
export default function NotificationBell() {
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<Pos>(() => loadPos() ?? defaultPos());
  const [dragging, setDragging] = useState(false);
  const dragRef = useRef({ startX: 0, startY: 0, originX: 0, originY: 0, moved: false, pointerId: -1 });

  /*
    未读数轮询（红点数据源）。两个节流措施：
      1) 30 秒间隔 —— 通知没有强实时要求；
      2) document.hidden 时跳过 —— 后台还发请求纯属浪费。
    监听 ap:notice-changed：列表里"标记已读/删除"后立刻刷新红点。
  */
  useEffect(() => {
    let stopped = false;
    const tick = async () => {
      if (document.hidden) {
        return;
      }
      try {
        const r = await unreadCount();
        if (!stopped) {
          setUnread(r?.count ?? 0);
        }
      } catch {
        // 未登录 / 后端未开 RBAC 时失败：静默即可，红点只是提示
      }
    };
    const onChanged = () => void tick();
    void tick();
    const timer = window.setInterval(() => void tick(), 30_000);
    window.addEventListener('ap:notice-changed', onChanged);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      window.removeEventListener('ap:notice-changed', onChanged);
    };
  }, []);

  /* 窗口尺寸变化：把球重新收进可视区，避免"缩小窗口后球不见了"。 */
  useEffect(() => {
    const onResize = () => {
      setPos((p) =>
        snap({
          x: Math.min(Math.max(p.x, -SIZE / 2), window.innerWidth - SIZE / 2),
          y: Math.min(Math.max(p.y, -SIZE / 2), window.innerHeight - SIZE / 2),
        }),
      );
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const persist = useCallback((next: Pos) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // 隐私模式/配额满：位置只在本次会话有效，不影响功能
    }
  }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // 只用主键（左键/单指）：右键拖动没有意义，且会干扰上下文菜单
    if (e.button !== 0) {
      return;
    }
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      originX: pos.x,
      originY: pos.y,
      moved: false,
      pointerId: e.pointerId,
    };
    setDragging(true);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) {
      return;
    }
    const dx = e.clientX - dragRef.current.startX;
    const dy = e.clientY - dragRef.current.startY;
    // 4px 阈值：手抖不算拖动（否则"想点击却弹不出来"）
    if (Math.abs(dx) + Math.abs(dy) > 4) {
      dragRef.current.moved = true;
    }
    // 允许拖到半出屏（-SIZE/2），松手时再决定是否吸附
    setPos({
      x: Math.min(Math.max(dragRef.current.originX + dx, -SIZE / 2), window.innerWidth - SIZE / 2),
      y: Math.min(Math.max(dragRef.current.originY + dy, -SIZE / 2), window.innerHeight - SIZE / 2),
    });
  };

  const onPointerUp = () => {
    if (!dragging) {
      return;
    }
    setDragging(false);
    if (!dragRef.current.moved) {
      setOpen(true); // 视为点击
      return;
    }
    setPos((p) => {
      const snapped = snap(p);
      persist(snapped);
      return snapped;
    });
  };

  return (
    <>
      <div
        role="button"
        tabIndex={0}
        aria-label="消息通知"
        data-slot="notification.bell"
        title={unread > 0 ? `${unread} 条未读通知` : '消息通知（可拖动）'}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            setOpen(true);
          }
        }}
        style={{
          position: 'fixed',
          left: pos.x,
          top: pos.y,
          width: SIZE,
          height: SIZE,
          zIndex: 900,
          // 触摸设备上禁止滚动手势抢占拖动
          touchAction: 'none',
          cursor: dragging ? 'grabbing' : 'grab',
          // 拖动中不要过渡（否则手感发飘）；松手吸附才需要动画
          transition: dragging ? 'none' : 'left 0.18s ease, top 0.18s ease',
          userSelect: 'none',
          outline: 'none',
        }}
      >
        <Badge dot={unread > 0} size="small" offset={[-4, 4]} style={{ width: SIZE, height: SIZE }}>
          <Button
            shape="circle"
            size="large"
            aria-label="消息通知"
            icon={<BellOutlined />}
            // 点击由 pointerup 判定（拖动过就不算点击），这里只兜住键盘触发
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setOpen(true);
              }
            }}
            style={{
              width: SIZE,
              height: SIZE,
              boxShadow: '0 4px 14px rgba(0, 0, 0, 0.18)',
              pointerEvents: 'none',
            }}
            tabIndex={-1}
          />
        </Badge>
      </div>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={960}
        title="消息通知"
        styles={{ body: { maxHeight: '70vh', overflow: 'auto' } }}
      >
        <NotificationsPanel onAfterNavigate={() => setOpen(false)} />
      </Modal>
    </>
  );
}
