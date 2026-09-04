import { useCallback, useEffect, useState } from 'react';
import { Table, Space, Button, Modal, Form, Input, Tag, message, Popconfirm, Divider, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { listSkills, getSkill, createSkill, updateSkill, importSkill, deleteSkill, SkillBody } from '../../api/skills';
import { SkillDef } from '../../api/types';

const EXAMPLE = `name: 客服话术助手
description: 提供客服标准话术
version: 1.0.0
tools:
  - search
prompt: |
  你是客服助手，使用亲切语气回复。`;

const TOOL_OPTIONS = ['search', 'calculator', 'http_get', 'tts_synthesize'].map((t) => ({ value: t, label: t }));

export default function SkillsPage() {
  const [items, setItems] = useState<SkillDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);          // 新建（按字段）
  const [importOpen, setImportOpen] = useState(false); // 导入 YAML
  const [editing, setEditing] = useState<SkillDef | null>(null); // 编辑抽屉数据
  const [editOpen, setEditOpen] = useState(false);
  const [form] = Form.useForm();
  const [importForm] = Form.useForm();
  const [editForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listSkills());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submitCreate = async () => {
    const v = await form.validateFields();
    try {
      await createSkill(toBody(v));
      message.success('Skill 已创建');
      setOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const submitImport = async () => {
    const v = await importForm.validateFields();
    try {
      await importSkill(v.manifest, v.source || 'local');
      message.success('Skill 已导入');
      setImportOpen(false);
      importForm.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const openEdit = async (r: SkillDef) => {
    try {
      const full = r.promptTemplate !== undefined ? r : await getSkill(r.skillId!);
      setEditing(full);
      editForm.setFieldsValue({
        name: full.name,
        version: full.version,
        source: full.source,
        description: full.description,
        prompt: full.promptTemplate,
        tools: Array.isArray(full.tools) ? full.tools : [],
      });
      setEditOpen(true);
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const submitEdit = async () => {
    if (!editing) return;
    const v = await editForm.validateFields();
    try {
      await updateSkill(editing.skillId!, toBody(v));
      message.success('Skill 已更新');
      setEditOpen(false);
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const del = async (r: SkillDef) => {
    try {
      await deleteSkill(r.skillId!);
      message.success('Skill 已删除');
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const toBody = (v: Record<string, unknown>): SkillBody => ({
    name: v.name as string,
    version: v.version as string,
    description: v.description as string,
    prompt: v.prompt as string,
    tools: (v.tools as string[]) ?? [],
    source: v.source as string,
  });

  const columns = [
    { title: '名称', dataIndex: 'name', width: 180, render: (v: string, r: SkillDef) => <a onClick={() => openEdit(r)}>{v}</a> },
    { title: '版本', dataIndex: 'version', width: 90 },
    { title: '来源', dataIndex: 'source', width: 100 },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '工具', dataIndex: 'tools', width: 200,
      render: (v: unknown) => (Array.isArray(v) ? v.map((t) => <Tag key={String(t)} color="blue">{String(t)}</Tag>) : '—'),
    },
    {
      title: '操作', width: 180,
      render: (_: unknown, r: SkillDef) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>查看 / 编辑</Button>
          <Popconfirm title="删除该 Skill？" onConfirm={() => del(r)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建 Skill</Button>
        <Button onClick={() => setImportOpen(true)}>导入 YAML Manifest</Button>
      </Space>
      <Table rowKey="skillId" loading={loading} columns={columns} dataSource={items} pagination={false} />

      {/* 新建 Skill（按字段，无需手写 YAML） */}
      <Modal title="新建 Skill" open={open} onOk={submitCreate} onCancel={() => setOpen(false)} destroyOnClose width={620}>
        <Form form={form} layout="vertical" initialValues={{ version: '1.0.0', source: 'local', tools: [] }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="version" label="版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="source" label="来源"><Input placeholder="local / marketplace / url" /></Form.Item>
          <Form.Item name="prompt" label="提示词模板" rules={[{ required: true, message: '请输入提示词' }]}>
            <Input.TextArea rows={5} />
          </Form.Item>
          <Form.Item name="tools" label="绑定工具"><Select mode="tags" options={TOOL_OPTIONS} placeholder="回车添加工具名" /></Form.Item>
        </Form>
      </Modal>

      {/* 导入 YAML */}
      <Modal title="导入 Skill（Manifest）" open={importOpen} onOk={submitImport} onCancel={() => setImportOpen(false)} destroyOnClose width={640}>
        <Form form={importForm} layout="vertical" initialValues={{ manifest: EXAMPLE, source: 'local' }}>
          <Form.Item name="manifest" label="Manifest 文本" rules={[{ required: true }]}>
            <Input.TextArea rows={12} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item name="source" label="来源"><Input placeholder="local / marketplace / url" /></Form.Item>
        </Form>
      </Modal>

      {/* 查看 / 编辑 */}
      <Modal
        title={editing ? `Skill：${editing.name}` : 'Skill'}
        open={editOpen}
        onOk={submitEdit}
        onCancel={() => setEditOpen(false)}
        destroyOnClose
        width={620}
        footer={[
          <Button key="cancel" onClick={() => setEditOpen(false)}>关闭</Button>,
          <Button key="ok" type="primary" onClick={submitEdit}>保存修改</Button>,
        ]}
      >
        <Divider orientation="left" plain>基本信息</Divider>
        <Form form={editForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="version" label="版本"><Input /></Form.Item>
          <Form.Item name="source" label="来源"><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="prompt" label="提示词模板" rules={[{ required: true }]}><Input.TextArea rows={8} /></Form.Item>
          <Form.Item name="tools" label="绑定工具"><Select mode="tags" options={TOOL_OPTIONS} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
