import { useEffect, useState } from 'react';
import { App as AntApp, Button, Card, Checkbox, Form, Input, Spin, Typography } from 'antd';
import { LockOutlined, SafetyCertificateOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { login } from '../api/auth';
import { getTenantId, setTenantId, setToken } from '../api/http';

const { Title, Text } = Typography;

/** 记住的用户名（浏览器环境也能用，Electron 里是降级兜底）。 */
const REMEMBER_KEY = 'ap.login.username';

/** 加密凭据在 `secrets.json` 里的键名。 */
const SECRET_KEY = 'login';

/**
 * 桌面壳的加密凭据桥（2026-10-24）。
 *
 * <p>由 `desktop/preload.js` 暴露，背后是 Electron 的 `safeStorage`
 * （Windows DPAPI / macOS Keychain / Linux libsecret）—— 和 QQ「记住密码」
 * 同一套机制：**存的不是明文密码，是系统加密后的密文**。</p>
 *
 * <p>用浏览器直接访问 `http://127.0.0.1:8081` 时这里是 undefined，
 * 前端会降级成"只记用户名、不自动登录"。</p>
 */
interface SecretsBridge {
  available: () => Promise<boolean>;
  set: (key: string, value: string) => Promise<{ ok: boolean; error?: string }>;
  get: (key: string) => Promise<{ ok: boolean; value?: string | null; error?: string }>;
  remove: (key: string) => Promise<{ ok: boolean; error?: string }>;
}

function secretsBridge(): SecretsBridge | undefined {
  return (window as unknown as { apSecrets?: SecretsBridge }).apSecrets;
}

interface LoginForm {
  username: string;
  password: string;
  tenantId?: string;
  remember?: boolean;
}

/**
 * 登录页。
 *
 * <p>它**不在 {@code AppLayout} 之内**（没有侧栏），因为未登录时不该出现任何导航入口。
 * 但仍在 {@code ThemeProvider} 之内，所以配色走 {@code --ap-*} CSS 变量，跟随皮肤/明暗。</p>
 */
export default function LoginPage() {
  const { message } = AntApp.useApp();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  /** 正在用记住的凭据自动登录（此时不显示表单）。 */
  const [autoTrying, setAutoTrying] = useState(false);

  const bridge = secretsBridge();
  /** 只有 Electron 里能加密存密码；纯浏览器访问时降级为"只记账号"。 */
  const canSavePassword = !!bridge;
  const savedUser = localStorage.getItem(REMEMBER_KEY) || '';

  /**
   * 登录动作（表单提交和自动登录共用）。
   *
   * @param silent 自动登录失败时不弹错误 —— 用户什么都没做，弹红条只会莫名其妙
   * @returns 是否成功
   */
  const doLogin = async (
    username: string,
    password: string,
    tenantId: string,
    opts: { silent?: boolean; remember?: boolean } = {},
  ): Promise<boolean> => {
    setLoading(true);
    try {
      const res = await login(username, password, tenantId);
      if (!res.token) {
        // 后端未返回 token 属于契约异常，明确报出来，别让用户停在原地猜
        throw new Error('服务端未返回 token，请检查后端登录接口');
      }
      setToken(res.token);
      setTenantId(res.tenant_id || tenantId);

      // 记住：账号存 localStorage，密码交给系统加密（只有 Electron 能存）
      if (opts.remember) {
        localStorage.setItem(REMEMBER_KEY, username);
        if (bridge) {
          const r = await bridge.set(
            SECRET_KEY,
            JSON.stringify({ username, password, tenantId }),
          );
          if (!r.ok) {
            message.warning(`密码没能保存：${r.error || '未知原因'}`);
          }
        }
      } else {
        localStorage.removeItem(REMEMBER_KEY);
        if (bridge) {
          await bridge.remove(SECRET_KEY);
        }
      }

      message.success(res.display_name ? `欢迎，${res.display_name}` : '登录成功');
      navigate('/chat', { replace: true });
      return true;
    } catch (e) {
      if (!opts.silent) {
        message.error(e instanceof Error ? e.message : '登录失败');
      }
      return false;
    } finally {
      setLoading(false);
    }
  };

  const onFinish = async (values: LoginForm) => {
    const tenantId = (values.tenantId || getTenantId() || 'default').trim() || 'default';
    await doLogin(values.username.trim(), values.password, tenantId, {
      remember: values.remember,
    });
  };

  /*
   * 自动登录（2026-10-24）。
   *
   * 只在 Electron 里有效：读 `secrets.json` 里那段**系统加密过**的凭据，
   * 直接提交。失败（密码改了、换了电脑、密文坏了）就静默清掉凭据回到表单 ——
   * 用户什么都没做，不该看到红色错误。
   *
   * 为什么不在挂载时就阻塞渲染：先显示"正在自动登录…"，成功后直接跳走，
   * 失败则换成表单。否则会闪一下登录框再消失，很难看。
   */
  useEffect(() => {
    const b = secretsBridge();
    if (!b) {
      return;
    }
    let alive = true;
    (async () => {
      try {
        const r = await b.get(SECRET_KEY);
        if (!alive || !r.ok || !r.value) {
          return;
        }
        const saved = JSON.parse(r.value) as {
          username?: string;
          password?: string;
          tenantId?: string;
        };
        if (!saved.username || !saved.password) {
          return;
        }
        setAutoTrying(true);
        const ok = await doLogin(
          saved.username,
          saved.password,
          saved.tenantId || getTenantId() || 'default',
          { silent: true, remember: true },
        );
        if (!ok) {
          // 密码可能改过了 —— 清掉，免得每次进来都白试一遍
          await b.remove(SECRET_KEY);
        }
      } catch {
        // 凭据格式坏了，当作没有
        await b.remove(SECRET_KEY).catch(() => undefined);
      } finally {
        if (alive) {
          setAutoTrying(false);
        }
      }
    })();
    return () => {
      alive = false;
    };
    // 只在首次挂载跑一次：doLogin 里用到的都是稳定引用
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      <Card style={{ width: 380 }} styles={{ body: { padding: 28 } }}>
        {/*
          自动登录中（2026-10-24）：显示转圈而不是登录框。
          否则会"闪一下表单再跳走"，看着像出错。
        */}
        {autoTrying ? (
          <div style={{ textAlign: 'center', padding: '36px 0' }}>
            <Spin />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">正在自动登录…</Text>
            </div>
          </div>
        ) : (
          <>
        <div style={{ textAlign: 'center', marginBottom: 20 }}>
          <Title level={4} style={{ marginBottom: 4 }}>
            <SafetyCertificateOutlined style={{ marginRight: 8, color: '#2563eb' }} />
            白雾 · 智能体交互平台
          </Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            请使用平台账号登录
          </Text>
        </div>

        <Form<LoginForm>
          layout="vertical"
          initialValues={{
            tenantId: getTenantId(),
            // 上次记住的账号直接填上，省得每次重打（2026-10-24）
            username: savedUser,
            remember: !!savedUser,
          }}
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="admin"
              size="large"
              autoFocus={!savedUser}
            />
          </Form.Item>

          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" size="large" />
          </Form.Item>

          {/*
            租户在单机/单租户场景下永远是 default，但多租户是平台既有能力（所有表都带 tenant_id），
            所以留一个可改入口；默认值取自上次登录用的租户。
          */}
          <Form.Item name="tenantId" label="租户">
            <Input prefix={<TeamOutlined />} placeholder="default" />
          </Form.Item>

          {/*
            记住密码（2026-10-24）。

            Electron 里走 `safeStorage` 加密（Windows DPAPI），存的是密文，
            和 QQ「记住密码」一个机制；纯浏览器访问时降级成"只记用户名"。
          */}
          <Form.Item name="remember" valuePropName="checked" style={{ marginBottom: 8 }}>
            <Checkbox>
              <Text style={{ fontSize: 13 }}>
                {canSavePassword ? '记住密码，下次自动登录' : '记住账号'}
              </Text>
              {!canSavePassword && (
                <Text type="secondary" style={{ fontSize: 12, marginLeft: 4 }}>
                  （浏览器里只能记账号）
                </Text>
              )}
            </Checkbox>
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button type="primary" htmlType="submit" size="large" block loading={loading}>
              登录
            </Button>
          </Form.Item>
        </Form>
          </>
        )}
      </Card>
    </div>
  );
}
