import { useState } from 'react';
import { App as AntApp, Button, Card, Form, Input, Typography } from 'antd';
import { LockOutlined, SafetyCertificateOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { login } from '../api/auth';
import { getTenantId, setTenantId, setToken } from '../api/http';

const { Title, Text } = Typography;

interface LoginForm {
  username: string;
  password: string;
  tenantId?: string;
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

  const onFinish = async (values: LoginForm) => {
    const tenantId = (values.tenantId || getTenantId() || 'default').trim() || 'default';
    setLoading(true);
    try {
      const res = await login(values.username.trim(), values.password, tenantId);
      if (!res.token) {
        // 后端未返回 token 属于契约异常，明确报出来，别让用户停在原地猜
        throw new Error('服务端未返回 token，请检查后端登录接口');
      }
      setToken(res.token);
      setTenantId(res.tenant_id || tenantId);
      message.success(res.display_name ? `欢迎，${res.display_name}` : '登录成功');
      navigate('/overview', { replace: true });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

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
          initialValues={{ tenantId: getTenantId() }}
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="admin" size="large" autoFocus />
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

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button type="primary" htmlType="submit" size="large" block loading={loading}>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
