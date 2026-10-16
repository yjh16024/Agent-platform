import { Card } from 'antd';
import NotificationsPanel from './NotificationsPanel';

/**
 * 消息通知（独立页面版）。
 *
 * <p>2026-10-16 起通知的**主入口**是右下角铃铛的弹窗（{@code components/NotificationBell}），
 * 侧栏菜单项已移除。本页保留为路由 {@code /notifications} 的落点 ——
 * 老书签不失效、通知正文里的 link 也可能指回这里；内容与弹窗完全共用
 * {@link NotificationsPanel}，不存在两套实现。</p>
 */
export default function NotificationsPage() {
  return (
    <Card title="消息通知">
      <NotificationsPanel />
    </Card>
  );
}
