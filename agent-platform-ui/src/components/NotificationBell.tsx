import { useEffect, useState } from 'react';
import { Badge, Button, Modal, Tooltip } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { unreadCount } from '../api/notifications';
import NotificationsPanel from '../pages/notifications/NotificationsPanel';

/**
 * 消息通知铃铛（2026-10-16 从侧栏菜单项降级而来）。
 *
 * <h3>为什么从侧栏挪到这里</h3>
 * 通知是一种"平时无感、有事才要"的能力 —— 在侧栏里占一个常驻一级入口，
 * 与「对话」「智能体」同级，权重明显过头（DSH 这类极简界面根本没有这种常驻项）。
 * 挪到右下角悬浮后依然**随时可见**（未读红点一直在），且零占位成本。
 *
 * <h3>几个刻意的取舍</h3>
 * <ul>
 *   <li><b>红点而不是数字</b>：计数只在 Tooltip 里给 —— 悬浮按钮上挂数字在窄屏会顶到内容，
 *       且"99+"这类数字本身也不提供行动信息；有红点就足以表达"有事要看"。</li>
 *   <li><b>弹窗内是完整的通知列表</b>（复用 {@code NotificationsPanel}）：标记已读 / 删除 /
 *       只看未读 / 清理一应俱全 —— 否则"看通知要弹窗、管通知要另开页面"会来回折腾。</li>
 *   <li><b>z-index 用 900</b>：低于 antd 弹层基准（1000），高于内容区 ——
 *       它自己弹出的 Modal 必须盖在铃铛之上。</li>
 * </ul>
 */
export default function NotificationBell() {
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);

  /*
    未读数轮询（红点数据源，逻辑自 AppLayout 原样搬来）。
    两个节流措施缺一不可：
      1) 30 秒间隔 —— 通知没有强实时要求，更密只是白耗后端；
      2) document.hidden 时跳过 —— 页面在后台还发请求纯属浪费（桌面版最小化时尤其明显）。
    另外监听 ap:notice-changed：通知列表做完"标记已读/删除"后会派发它，
    让红点立刻更新 —— 否则"点了全部已读、红点却还挂着"会停留 30 秒，看起来像没生效。
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
        // 未登录 / 后端未开 RBAC 时这里会失败：静默即可 ——
        // 红点只是提示，不该为此弹一个错误提示打扰用户。
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

  return (
    <>
      {/*
        fixed 定位挂在外层容器而不是 Button 上：Button 一旦 `position: fixed` 就脱离文档流，
        Badge 的包裹元素尺寸塌成 0，红点定位会跟着错位（相对一个零尺寸盒子）。
        层级 900：低于 antd 弹层基准（1000）——它自己弹出的 Modal 必须盖在铃铛之上。
      */}
      <div style={{ position: 'fixed', right: 24, bottom: 24, zIndex: 900 }}>
        <Tooltip title={unread > 0 ? `${unread} 条未读通知` : '消息通知'} placement="left">
          {/* 红点而不是数字：计数只在 Tooltip 里给 —— 悬浮按钮上挂数字在窄屏会顶到内容，信息量也低 */}
          <Badge dot={unread > 0} size="small" offset={[-4, 4]}>
            <Button
              data-slot="notification.bell"
              shape="circle"
              size="large"
              aria-label="消息通知"
              icon={<BellOutlined />}
              onClick={() => setOpen(true)}
              style={{ boxShadow: '0 4px 12px rgba(0, 0, 0, 0.18)' }}
            />
          </Badge>
        </Tooltip>
      </div>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={960}
        title="消息通知"
        // 弹窗内容里是完整表格：超高时内部滚动，不把弹窗撑出屏幕
        styles={{ body: { maxHeight: '70vh', overflow: 'auto' } }}
      >
        <NotificationsPanel onAfterNavigate={() => setOpen(false)} />
      </Modal>
    </>
  );
}
