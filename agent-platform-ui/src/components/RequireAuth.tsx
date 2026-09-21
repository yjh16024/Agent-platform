import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { getAuthMode } from '../api/auth';
import { getToken } from '../api/http';

type Decision = 'checking' | 'allow' | 'needLogin';

/**
 * 登录守卫。
 *
 * <h3>为什么必须先问后端「鉴权模式」</h3>
 * 桌面（embedded）profile 显式**关闭**了鉴权（见 {@code application-embedded.yml}），
 * 此时后端根本不校验 token。若前端无条件要求登录，桌面版用户会被自己的登录页挡在门外 ——
 * 而他们连账号都没有。所以判据不是"有没有 token"，而是"后端到底要不要 token"。
 *
 * <h3>拿不到模式时为什么放行</h3>
 * 后端不可达时放行，让请求去真实地失败（界面会显示接口错误），而不是统一跳到登录页 ——
 * 后者会把"服务挂了"伪装成"你没登录"，把排查方向引偏。这是**有意的取舍**：
 * 这里的失败方向与权限校验相反（那边 fail-closed，这里 fail-open），
 * 因为这里决定的是"要不要拦用户"，不是"要不要授权"。
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [decision, setDecision] = useState<Decision>('checking');

  useEffect(() => {
    let alive = true;
    getAuthMode()
      .then((mode) => {
        if (!alive) {
          return;
        }
        if (!mode.security_enabled) {
          setDecision('allow');
          return;
        }
        setDecision(getToken() ? 'allow' : 'needLogin');
      })
      .catch(() => {
        if (alive) {
          setDecision('allow');
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  if (decision === 'checking') {
    return (
      <div
        style={{
          height: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--ap-bg-layout, #f7f8fa)',
        }}
      >
        <Spin tip="正在检查登录状态…" size="large">
          <div style={{ width: 120, height: 40 }} />
        </Spin>
      </div>
    );
  }

  if (decision === 'needLogin') {
    // 记住来路，登录成功后可以回到原页面（LoginPage 目前统一回 /overview，够用）
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <>{children}</>;
}
