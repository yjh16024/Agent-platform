import { useMemo, useState } from 'react';
import { Layout, Menu, Button, Space, Typography, App as AntApp, message } from 'antd';
import {
  DashboardOutlined,
  RobotOutlined,
  MessageOutlined,
  AppstoreOutlined,
  ToolOutlined,
  FileTextOutlined,
  BugOutlined,
  ThunderboltOutlined,
  LoginOutlined,
  LogoutOutlined,
  DatabaseOutlined,
  BranchesOutlined,
  BookOutlined,
  FolderOutlined,
  ApiOutlined,
  SafetyCertificateOutlined,
  HistoryOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import TenantSelector from './TenantSelector';
import { useAppStore } from '../store/appStore';
import { login } from '../api/auth';

const { Header, Sider, Content } = Layout;

export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { tenantId, token, setAuth } = useAppStore();
  const [logging, setLogging] = useState(false);

  const selectedKey = useMemo(() => {
    const path = location.pathname;
    if (path.startsWith('/agents')) return '/agents';
    return path;
  }, [location.pathname]);

  const menuItems = [
    { key: '/overview', icon: <DashboardOutlined />, label: '概览' },
    { key: '/agents', icon: <RobotOutlined />, label: '智能体' },
    { key: '/chat', icon: <MessageOutlined />, label: '对话' },
    { key: '/sessions', icon: <HistoryOutlined />, label: '会话历史' },
    { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库' },
    { key: '/workflows', icon: <BranchesOutlined />, label: '工作流' },
    { key: '/skills', icon: <BookOutlined />, label: 'Skills' },
    { key: '/plugins', icon: <AppstoreOutlined />, label: '插件' },
    { key: '/files', icon: <FolderOutlined />, label: '文件' },
    { key: '/settings', icon: <SettingOutlined />, label: '模型设置' },
    {
      key: '/ops',
      icon: <ToolOutlined />,
      label: '运维工具',
      children: [
        { key: '/logs', icon: <FileTextOutlined />, label: '运行日志' },
        { key: '/diagnosis', icon: <BugOutlined />, label: '智能诊断' },
        { key: '/prompt', icon: <ThunderboltOutlined />, label: '提示词优化' },
        { key: '/tools', icon: <ApiOutlined />, label: '工具调试' },
        { key: '/quota', icon: <SafetyCertificateOutlined />, label: '租户配额' },
      ],
    },
  ];

  const handleLogin = async () => {
    setLogging(true);
    try {
      const r = await login(tenantId);
      setAuth(r.token ?? null);
      message.success(`已登录 ${r.user_id ?? 'demo-user'}@${r.tenant_id ?? tenantId}`);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLogging(false);
    }
  };

  return (
    <AntApp>
      <Layout style={{ minHeight: '100vh' }}>
        <Sider theme="dark" width={208}>
          <div style={{ height: 56, lineHeight: '56px', textAlign: 'center', color: '#fff', fontWeight: 600 }}>
            智能体交互平台
          </div>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            defaultOpenKeys={['/ops']}
            items={menuItems}
            onClick={({ key }) => navigate(key)}
          />
        </Sider>
        <Layout>
          <Header
            style={{
              background: '#fff',
              padding: '0 24px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
            }}
          >
            <Space size={16}>
              <Typography.Text strong style={{ fontSize: 16 }}>
                {menuItems.find((m) => m.key === selectedKey)?.label ?? '概览'}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                直连 core · 端口 8081
              </Typography.Text>
            </Space>
            <Space size={12}>
              <TenantSelector />
              {token ? (
                <Button size="small" icon={<LogoutOutlined />} onClick={() => setAuth(null)}>
                  退出
                </Button>
              ) : (
                <Button size="small" type="primary" icon={<LoginOutlined />} loading={logging} onClick={handleLogin}>
                  登录
                </Button>
              )}
            </Space>
          </Header>
          <Content style={{ padding: 24, overflow: 'auto' }}>
            <Outlet />
          </Content>
        </Layout>
      </Layout>
    </AntApp>
  );
}