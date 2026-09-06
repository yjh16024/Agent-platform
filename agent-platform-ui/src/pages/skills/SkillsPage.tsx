import { useCallback, useEffect, useState } from 'react';
import {
  Table, Space, Button, Modal, Form, Input, Tag, message, Popconfirm, Divider, Select, Alert,
  Drawer, Typography, List, Upload, Tooltip, Spin,
} from 'antd';
import {
  PlusOutlined, UploadOutlined, FolderOpenOutlined, SyncOutlined, CopyOutlined, FileTextOutlined,
} from '@ant-design/icons';
import {
  listSkills, getSkill, createSkill, updateSkill, importSkill, deleteSkill, SkillBody,
  getSkillsDir, openSkillsFolder, syncSkills, uploadSkill, skillFiles, readSkillFile,
} from '../../api/skills';
import { SkillDef } from '../../api/types';

const EXAMPLE = `name: 客服话术助手
description: 提供客服标准话术
version: 1.0.0
tools:
  - search
prompt: |
  你是客服助手，使用亲切语气回复。`;

/** 标准 SKILL.md 模板（Agent Skills 开放标准） */
const SKILL_MD_TEMPLATE = `---
name: my-skill
description: 一句话描述这个 Skill 的用途与触发场景
version: 1.0.0
allowed-tools:
  - search
---

# 使用说明

在这里写下 Skill 的能力说明、操作步骤与约束（即注入系统提示词的正文）。
`;

const TOOL_OPTIONS = ['search', 'calculator', 'http_get', 'tts_synthesize'].map((t) => ({ value: t, label: t }));

const dirOf = (s: SkillDef) => (s.manifest?.dir as string | undefined) ?? '';

export default function SkillsPage() {
  const [items, setItems] = useState<SkillDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [editing, setEditing] = useState<SkillDef | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [form] = Form.useForm();
  const [importForm] = Form.useForm();
  const [editForm] = Form.useForm();

  const [skillsDir, setSkillsDir] = useState('');
  const [syncing, setSyncing] = useState(false);
  const [files, setFiles] = useState<string[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [fileContent, setFileContent] = useState<{ path: string; content: string } | null>(null);
  const [fileLoading, setFileLoading] = useState(false);

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

  const loadDir = useCallback(async () => {
    try {
      const r = await getSkillsDir();
      setSkillsDir(r.path ?? '');
    } catch {
      setSkillsDir('');
    }
  }, []);

  useEffect(() => {
    load();
    loadDir();
  }, [load, loadDir]);

  const submitCreate = async () => {
    const v = await form.validateFields();
    try {
      await createSkill(toBody(v));
      message.success('Skill 已创建（已写入标准目录）');
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
      setFileContent(null);
      setFilesLoading(true);
      try {
        setFiles(await skillFiles(r.skillId!));
      } catch {
        setFiles([]);
      } finally {
        setFilesLoading(false);
      }
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const submitEdit = async () => {
    if (!editing) return;
    const v = await editForm.validateFields();
    try {
      await updateSkill(editing.skillId!, toBody(v));
      message.success('Skill 已更新（SKILL.md 已同步回写）');
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

  /** 扫描 skills 目录 → 识别并入库 */
  const doSync = async () => {
    setSyncing(true);
    try {
      const r = await syncSkills();
      message.success(`扫描完成：共 ${r.total} 个，新增 ${r.created}、更新 ${r.updated}`
        + (r.missing_in_folder > 0 ? `，目录中已缺失 ${r.missing_in_folder} 个` : ''));
      load();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSyncing(false);
    }
  };

  const doOpenFolder = async () => {
    try {
      const r = await openSkillsFolder();
      if (r.opened) {
        message.success(`已打开目录：${r.path}`);
        return;
      }
      // 服务端/无桌面场景默认关闭该能力：给出可复制的路径与开启方式
      Modal.info({
        title: '目录打开能力未开启（默认安全设置）',
        width: 560,
        content: (
          <div>
            <p>Skills 目录为：</p>
            <Typography.Text code copyable>{r.path}</Typography.Text>
            <p style={{ marginTop: 12 }}>请把下载的 Skill 目录直接放进该路径，然后返回本页点「扫描同步目录」。</p>
            <p style={{ marginTop: 8 }}>
              如需从仪表盘直接打开文件管理器，请设置环境变量{' '}
              <Typography.Text code>SKILLS_OPEN_FOLDER=true</Typography.Text> 后重启（仅本地桌面场景建议开启）。
            </p>
          </div>
        ),
        okText: '知道了',
      });
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const viewFile = async (path: string) => {
    if (!editing) return;
    setFileLoading(true);
    try {
      setFileContent(await readSkillFile(editing.skillId!, path));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setFileLoading(false);
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
    { title: '名称', dataIndex: 'name', width: 170, render: (v: string, r: SkillDef) => <a onClick={() => openEdit(r)}>{v}</a> },
    { title: '版本', dataIndex: 'version', width: 80 },
    {
      title: '来源', dataIndex: 'source', width: 110,
      render: (v: string) => (
        <Tag color={v === 'filesystem' ? 'green' : 'blue'}>{v === 'filesystem' ? '目录' : (v || '—')}</Tag>
      ),
    },
    {
      title: '目录', width: 160, ellipsis: true,
      render: (_: unknown, r: SkillDef) => (
        <Tooltip title={dirOf(r) ? `skills/${dirOf(r)}` : '非目录型'}>
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{dirOf(r) || '—'}</span>
        </Tooltip>
      ),
    },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '工具', dataIndex: 'tools', width: 170,
      render: (v: unknown) => (Array.isArray(v) ? v.map((t) => <Tag key={String(t)} color="blue">{String(t)}</Tag>) : '—'),
    },
    {
      title: '操作', width: 180,
      render: (_: unknown, r: SkillDef) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>查看 / 编辑</Button>
          <Popconfirm title="删除该 Skill？（目录型会一并删除其 skills 子目录）" onConfirm={() => del(r)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="Skills 采用 Agent Skills 开放标准目录存储：每个 Skill 是一个目录，内含 SKILL.md（YAML 头 + 提示词正文），可附带 scripts/ references/ assets/。"
        description={
          <Space wrap style={{ marginTop: 6 }}>
            <Typography.Text code style={{ fontSize: 12 }}>{skillsDir || '加载中…'}</Typography.Text>
            <Button size="small" icon={<CopyOutlined />} onClick={() => {
              navigator.clipboard?.writeText(skillsDir);
              message.success('目录路径已复制');
            }}>复制路径</Button>
          </Space>
        }
      />

      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" icon={<SyncOutlined />} loading={syncing} onClick={doSync}>扫描同步目录</Button>
        <Button icon={<FolderOpenOutlined />} onClick={doOpenFolder}>打开 skills 文件夹</Button>
        <Upload
          showUploadList={false}
          accept=".zip,.md"
          beforeUpload={(f) => {
            uploadSkill(f as unknown as File)
              .then((rs) => {
                message.success(`已导入 ${rs.length} 个 Skill`);
                load();
              })
              .catch((e) => message.error((e as Error).message));
            return false;
          }}
        >
          <Button icon={<UploadOutlined />}>上传 Skill（.zip / SKILL.md）</Button>
        </Upload>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建 Skill</Button>
        <Button onClick={() => setImportOpen(true)}>导入 YAML Manifest</Button>
      </Space>

      <Table rowKey="skillId" loading={loading} columns={columns} dataSource={items} pagination={false} />

      {/* 新建 Skill */}
      <Modal title="新建 Skill" open={open} onOk={submitCreate} onCancel={() => setOpen(false)} destroyOnClose width={620}>
        <Form form={form} layout="vertical" initialValues={{ version: '1.0.0', tools: [] }}>
          <Form.Item name="name" label="名称（将作为 skills 目录名）" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="version" label="版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="prompt" label="提示词正文（SKILL.md 正文）" rules={[{ required: true, message: '请输入提示词' }]}>
            <Input.TextArea rows={6} placeholder={SKILL_MD_TEMPLATE} />
          </Form.Item>
          <Form.Item name="tools" label="绑定工具（allowed-tools）"><Select mode="tags" options={TOOL_OPTIONS} placeholder="回车添加工具名" /></Form.Item>
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

      {/* 查看 / 编辑 + 目录文件浏览 */}
      <Drawer
        title={editing ? `Skill：${editing.name}` : 'Skill'}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={720}
        extra={
          <Space>
            <Button
              size="small"
              icon={<SyncOutlined />}
              onClick={async () => {
                if (!editing) return;
                try {
                  const fresh = await getSkill(editing.skillId!);
                  setEditing(fresh);
                  message.success('已从目录刷新');
                  load();
                } catch (e) { message.error((e as Error).message); }
              }}
            >刷新</Button>
            <Button type="primary" size="small" onClick={submitEdit}>保存修改</Button>
          </Space>
        }
      >
        <Divider orientation="left" plain>基本信息</Divider>
        <Form form={editForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="version" label="版本"><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="prompt" label="提示词正文（改写会同步回 SKILL.md）" rules={[{ required: true }]}><Input.TextArea rows={8} /></Form.Item>
          <Form.Item name="tools" label="绑定工具"><Select mode="tags" options={TOOL_OPTIONS} /></Form.Item>
        </Form>

        <Divider orientation="left" plain>目录文件（渐进式披露资源）</Divider>
        {filesLoading ? <Spin /> : (
          <List
            size="small"
            dataSource={files}
            locale={{ emptyText: '目录内暂无文件（非目录型 Skill 无 SKILL.md）' }}
            renderItem={(f) => (
              <List.Item
                actions={[<Button key="v" type="link" size="small" onClick={() => viewFile(f)}>查看</Button>]}
              >
                <Space><FileTextOutlined /><span style={{ fontFamily: 'monospace', fontSize: 12 }}>{f}</span></Space>
              </List.Item>
            )}
          />
        )}
        {fileLoading && <Spin style={{ marginTop: 8 }} />}
        {fileContent && !fileLoading && (
          <div style={{ marginTop: 12 }}>
            <Typography.Text strong>{fileContent.path}</Typography.Text>
            <pre style={{ background: '#fafafa', padding: 12, maxHeight: 260, overflow: 'auto', fontSize: 12 }}>
              {fileContent.content}
            </pre>
          </div>
        )}
      </Drawer>
    </div>
  );
}
