import { useCallback, useEffect, useState } from 'react';
import {
  App as AntApp,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  type TableProps,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  assignRoles,
  createUser,
  deleteUser,
  listRoles,
  listUsers,
  resetPassword,
  updateUser,
  type SystemRole,
  type SystemUser,
} from '../../api/system';

const { Title, Text } = Typography;

/**
 * 用户管理。
 *
 * <p>角色用**编码**做标识（而不是内部 roleId）：后端 {@code applyRoles} 按编码解析，
 * 界面上也更好读。</p>
 */
export default function UserManagePage() {
  const { message } = AntApp.useApp();
  const [rows, setRows] = useState<SystemUser[]>([]);
  const [roles, setRoles] = useState<SystemRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SystemUser | null>(null);
  const [rolesFor, setRolesFor] = useState<SystemUser | null>(null);
  const [pwdFor, setPwdFor] = useState<SystemUser | null>(null);

  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [rolesForm] = Form.useForm();
  const [pwdForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [users, roleList] = await Promise.all([listUsers(), listRoles()]);
      setRows(users);
      setRoles(roleList);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 统一包一层：避免每个提交按钮都写一遍 try/catch/finally。 */
  const run = async (fn: () => Promise<unknown>, okMsg: string) => {
    setBusy(true);
    try {
      await fn();
      message.success(okMsg);
      await load();
      return true;
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const roleOptions = roles.map((r) => ({
    label: r.builtin ? `${r.roleName}（内置 · ${r.roleCode}）` : `${r.roleName}（${r.roleCode}）`,
    value: r.roleCode,
  }));

  const columns: TableProps<SystemUser>['columns'] = [
    {
      title: '用户名',
      dataIndex: 'username',
      render: (v: string, r) => (
        <Space direction="vertical" size={0}>
          <Text strong>{v}</Text>
          {r.displayName && <Text type="secondary" style={{ fontSize: 12 }}>{r.displayName}</Text>}
        </Space>
      ),
    },
    { title: '邮箱', dataIndex: 'email', render: (v?: string) => v || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: string) => (v === 'active' ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>),
    },
    {
      title: '角色',
      dataIndex: 'roleCodes',
      render: (v: string[]) =>
        v?.length ? (
          <Space size={4} wrap>
            {v.map((c) => (
              <Tag key={c} color={c === 'admin' ? 'gold' : 'blue'}>
                {c}
              </Tag>
            ))}
          </Space>
        ) : (
          <Text type="secondary">未分配</Text>
        ),
    },
    {
      title: '操作',
      width: 300,
      render: (_: unknown, r) => (
        <Space size={4} wrap>
          <Button
            size="small"
            type="link"
            onClick={() => {
              setEditing(r);
              editForm.setFieldsValue({
                displayName: r.displayName ?? '',
                email: r.email ?? '',
                status: r.status,
              });
            }}
          >
            编辑
          </Button>
          <Button
            size="small"
            type="link"
            onClick={() => {
              setRolesFor(r);
              rolesForm.setFieldsValue({ roleCodes: r.roleCodes ?? [] });
            }}
          >
            角色
          </Button>
          <Button size="small" type="link" onClick={() => setPwdFor(r)}>
            重置密码
          </Button>
          <Popconfirm
            title={`确定删除用户「${r.username}」？`}
            description="删除后该账号立即无法登录，且不可恢复。"
            okText="删除"
            okButtonProps={{ danger: true }}
            onConfirm={() => run(() => deleteUser(r.userId), '已删除')}
          >
            <Button size="small" type="link" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>
            用户管理
          </Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            启用 / 停用控制能否登录；角色决定能访问哪些接口。
          </Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              createForm.resetFields();
              setCreateOpen(true);
            }}
          >
            新建用户
          </Button>
        </Space>
      </Space>

      <Table<SystemUser>
        rowKey="userId"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
      />

      {/* 新建 */}
      <Modal
        title="新建用户"
        open={createOpen}
        confirmLoading={busy}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        destroyOnHidden
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={async (v) => {
            const ok = await run(
              () =>
                createUser({
                  username: String(v.username).trim(),
                  password: String(v.password),
                  displayName: v.displayName || undefined,
                  email: v.email || undefined,
                  roleCodes: v.roleCodes ?? [],
                }),
              '已创建',
            );
            if (ok) {
              setCreateOpen(false);
            }
          }}
        >
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="登录用，创建后不可改" />
          </Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[
              { required: true, message: '请输入初始密码' },
              { min: 6, message: '至少 6 位' },
            ]}
          >
            <Input.Password placeholder="至少 6 位" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="email" label="邮箱">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="roleCodes" label="角色">
            <Select mode="multiple" allowClear options={roleOptions} placeholder="可稍后再分配" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑 */}
      <Modal
        title={`编辑用户：${editing?.username ?? ''}`}
        open={!!editing}
        confirmLoading={busy}
        onCancel={() => setEditing(null)}
        onOk={() => editForm.submit()}
        destroyOnHidden
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={async (v) => {
            const ok = await run(
              () =>
                updateUser(editing!.userId, {
                  displayName: v.displayName ?? '',
                  email: v.email ?? '',
                  status: v.status,
                }),
              '已保存',
            );
            if (ok) {
              setEditing(null);
            }
          }}
        >
          <Form.Item name="displayName" label="显示名">
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱">
            <Input />
          </Form.Item>
          <Form.Item name="status" label="状态" tooltip="停用后该账号无法登录，但历史数据保留">
            <Select
              options={[
                { label: '启用', value: 'active' },
                { label: '停用', value: 'disabled' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 分配角色 */}
      <Modal
        title={`分配角色：${rolesFor?.username ?? ''}`}
        open={!!rolesFor}
        confirmLoading={busy}
        onCancel={() => setRolesFor(null)}
        onOk={() => rolesForm.submit()}
        destroyOnHidden
      >
        <Form
          form={rolesForm}
          layout="vertical"
          onFinish={async (v) => {
            const ok = await run(
              () => assignRoles(rolesFor!.userId, v.roleCodes ?? []),
              '角色已更新',
            );
            if (ok) {
              setRolesFor(null);
            }
          }}
        >
          <Form.Item
            name="roleCodes"
            label="角色（覆盖式）"
            extra="用户需要重新登录才能拿到新角色的权限：token 里携带的是角色码。"
          >
            <Select mode="multiple" allowClear options={roleOptions} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置密码 */}
      <Modal
        title={`重置密码：${pwdFor?.username ?? ''}`}
        open={!!pwdFor}
        confirmLoading={busy}
        onCancel={() => setPwdFor(null)}
        onOk={() => pwdForm.submit()}
        destroyOnHidden
      >
        <Form
          form={pwdForm}
          layout="vertical"
          onFinish={async (v) => {
            const ok = await run(() => resetPassword(pwdFor!.userId, v.password), '密码已重置');
            if (ok) {
              setPwdFor(null);
              pwdForm.resetFields();
            }
          }}
        >
          <Form.Item
            name="password"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '至少 6 位' },
            ]}
          >
            <Input.Password placeholder="至少 6 位" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
