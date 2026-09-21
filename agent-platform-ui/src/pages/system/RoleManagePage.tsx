import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tree,
  Typography,
  type TableProps,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  createRole,
  deleteRole,
  listPermissions,
  listRoles,
  updateRole,
  type PermissionGroup,
  type SystemRole,
} from '../../api/system';

const { Title, Text } = Typography;

/** 权限树的父节点 key 前缀 —— 分组本身不是权限，提交时要滤掉。 */
const GROUP_PREFIX = 'g:';

interface FormValues {
  roleCode: string;
  roleName: string;
  description?: string;
}

/**
 * 角色权限管理。
 *
 * <p>权限树的父节点是**权限分组**（来自 {@code perm_group}），叶子才是权限码。
 * 提交时只取叶子 —— 否则会把分组名当成权限码发给后端，被"权限不存在"挡回来。</p>
 */
export default function RoleManagePage() {
  const { message } = AntApp.useApp();
  const [roles, setRoles] = useState<SystemRole[]>([]);
  const [groups, setGroups] = useState<PermissionGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  /** null = 弹窗关闭；『新建』用一个哨兵对象表示。 */
  const [editing, setEditing] = useState<SystemRole | 'new' | null>(null);
  const [checked, setChecked] = useState<string[]>([]);
  const [form] = Form.useForm<FormValues>();

  const isNew = editing === 'new';
  const current = editing && editing !== 'new' ? editing : null;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [roleList, permGroups] = await Promise.all([listRoles(), listPermissions()]);
      setRoles(roleList);
      setGroups(permGroups);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  const treeData = useMemo(
    () =>
      groups.map((g) => ({
        title: g.group,
        key: `${GROUP_PREFIX}${g.group}`,
        selectable: false,
        children: g.items.map((i) => ({ title: `${i.name}（${i.code}）`, key: i.code })),
      })),
    [groups],
  );

  const openCreate = () => {
    form.resetFields();
    setChecked([]);
    setEditing('new');
  };

  const openEdit = (r: SystemRole) => {
    form.setFieldsValue({
      roleCode: r.roleCode,
      roleName: r.roleName,
      description: r.description ?? '',
    });
    setChecked(r.permCodes ?? []);
    setEditing(r);
  };

  const submit = async (v: FormValues) => {
    setBusy(true);
    try {
      if (isNew) {
        await createRole({
          roleCode: v.roleCode.trim(),
          roleName: v.roleName,
          description: v.description,
          permCodes: checked,
        });
      } else {
        // 刻意不传 roleCode：它是写进 token 的稳定标识，改了会让已签发的 token 失效
        await updateRole(current!.roleId, {
          roleName: v.roleName,
          description: v.description,
          permCodes: checked,
        });
      }
      message.success(isNew ? '已创建' : '已保存（权限缓存已立即失效）');
      setEditing(null);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (r: SystemRole) => {
    try {
      await deleteRole(r.roleId);
      message.success('已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  const columns: TableProps<SystemRole>['columns'] = [
    {
      title: '角色',
      dataIndex: 'roleCode',
      render: (v: string, r) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <Text strong>{r.roleName}</Text>
            {r.builtin && <Tag color="gold">内置</Tag>}
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>{v}</Text>
        </Space>
      ),
    },
    { title: '说明', dataIndex: 'description', render: (v?: string) => v || '-' },
    {
      title: '权限数',
      dataIndex: 'permCodes',
      width: 100,
      render: (v: string[]) => <Tag>{v?.length ?? 0}</Tag>,
    },
    {
      title: '操作',
      width: 160,
      render: (_: unknown, r) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm
            title={`确定删除角色「${r.roleName}」？`}
            description="如有用户仍在使用该角色，删除会被拒绝。"
            okText="删除"
            okButtonProps={{ danger: true }}
            onConfirm={() => remove(r)}
            disabled={r.builtin}
          >
            <Button size="small" type="link" danger disabled={r.builtin}>
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
            角色权限
          </Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            角色决定能访问哪些接口；保存后立即生效（服务端会失效权限缓存）。
          </Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建角色
          </Button>
        </Space>
      </Space>

      <Table<SystemRole>
        rowKey="roleId"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={roles}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
      />

      <Modal
        title={isNew ? '新建角色' : `编辑角色：${current?.roleName ?? ''}`}
        open={!!editing}
        confirmLoading={busy}
        width={560}
        onCancel={() => setEditing(null)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item
            name="roleCode"
            label="角色编码"
            tooltip="写进登录令牌的稳定标识；编辑时不可修改，改了会让已登录用户失效"
            rules={[{ required: true, message: '请输入角色编码' }]}
          >
            <Input placeholder="如 auditor" disabled={!isNew} />
          </Form.Item>
          <Form.Item name="roleName" label="显示名" rules={[{ required: true, message: '请输入显示名' }]}>
            <Input placeholder="如 审计员" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input placeholder="可选" />
          </Form.Item>

          <Form.Item label="权限" style={{ marginBottom: 0 }}>
            {groups.length === 0 ? (
              <Alert type="warning" showIcon message="权限清单为空：请确认后端 RBAC 已启用（RbacSeeder 会同步权限点）" />
            ) : (
              <>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  勾选分组即选中该组全部权限；提交时只保存叶子权限码。
                </Text>
                <div
                  style={{
                    maxHeight: 280,
                    overflow: 'auto',
                    border: '1px solid rgba(128,128,128,0.25)',
                    borderRadius: 6,
                    padding: '4px 8px',
                    marginTop: 6,
                  }}
                >
                  <Tree
                    checkable
                    defaultExpandAll
                    treeData={treeData}
                    checkedKeys={checked}
                    onCheck={(keys) => {
                      const arr = Array.isArray(keys) ? keys : keys.checked;
                      setChecked(arr.map(String).filter((k) => !k.startsWith(GROUP_PREFIX)));
                    }}
                  />
                </div>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  已选 {checked.length} 项
                </Text>
              </>
            )}
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
