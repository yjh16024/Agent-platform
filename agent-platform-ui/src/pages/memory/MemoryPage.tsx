import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  deleteFact,
  listFacts,
  purgeFacts,
  purgeVectorMemory,
  rebuildVectorMemory,
  saveFact,
  updateFact,
  type FactCategory,
  type UserFactItem,
} from '../../api/memory';
import { getMe } from '../../api/auth';

const { Paragraph, Text, Title } = Typography;

/** 分类选项（与后端 UserFactCategory 对齐）。 */
const CATEGORY_OPTIONS: { value: FactCategory; label: string; color: string }[] = [
  { value: 'preference', label: '偏好', color: 'blue' },
  { value: 'background', label: '背景', color: 'green' },
  { value: 'goal', label: '目标', color: 'orange' },
  { value: 'other', label: '其他', color: 'default' },
];

const CATEGORY_META = new Map(CATEGORY_OPTIONS.map((c) => [c.value, c]));

/**
 * 记忆管理页：长期画像 + 向量记忆。
 *
 * <p>为什么把这两块放在同一页：它们都是"对话之外、但会影响对话"的数据，
 * 用户需要能在一个地方看清"系统记住了我什么"以及"关掉它"。但两块刻意分成
 * 两个卡片而不是混在一张表里 —— 一处是<strong>我主动说的</strong>，一处是
 * <strong>系统从对话里推出来的</strong>，混淆会让用户分不清哪条是自己填的。</p>
 *
 * <p>短期（最近几轮）与中期（会话摘要）不在这里管：它们是纯服务端机制，
 * 随对话自然发生、随会话删除而消失，不需要独立开关。</p>
 */
export default function MemoryPage() {
  const { message, modal } = AntApp.useApp();
  const [facts, setFacts] = useState<UserFactItem[]>([]);
  const [loading, setLoading] = useState(false);
  // 与 AppLayout 同一套取舍：拿不到权限集（未开 RBAC / 演示模式）时视为"不限"，而不是全隐藏
  const [perms, setPerms] = useState<string[] | null>(null);
  const [editing, setEditing] = useState<UserFactItem | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<{ key: string; value: string; category: FactCategory }>();

  const canManage = useMemo(
    () => !perms || perms.length === 0 || perms.includes('profile:manage'),
    [perms],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setFacts(await listFacts());
    } catch (e) {
      message.error(`加载长期记忆失败：${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
    getMe()
      .then((me) => setPerms(me.perms ?? []))
      .catch(() => setPerms(null));
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({ key: '', value: '', category: 'preference' });
    setModalOpen(true);
  };

  const openEdit = (row: UserFactItem) => {
    setEditing(row);
    form.setFieldsValue({ key: row.key, value: row.value, category: row.category });
    setModalOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setBusy(true);
    try {
      if (editing) {
        await updateFact(editing.factId, values);
        message.success('已更新');
      } else {
        await saveFact(values);
        message.success('已保存');
      }
      setModalOpen(false);
      await load();
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (row: UserFactItem) => {
    try {
      await deleteFact(row.factId);
      message.success('已删除');
      await load();
    } catch (e) {
      message.error(`删除失败：${(e as Error).message}`);
    }
  };

  const purgeAll = () => {
    modal.confirm({
      title: '清除全部长期记忆？',
      content:
        '这些是你主动填写的个人信息，清除后不可恢复，智能体将不再据此调整回答。',
      okText: '全部清除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await purgeFacts();
          message.success(`已清除 ${r.deleted} 条`);
          await load();
        } catch (e) {
          message.error(`清除失败：${(e as Error).message}`);
        }
      },
    });
  };

  const rebuildVector = async () => {
    setBusy(true);
    try {
      const r = await rebuildVectorMemory();
      message.success(`已重建索引：${r.indexed} 条历史消息`);
    } catch (e) {
      message.error(`重建失败：${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const purgeVector = () => {
    modal.confirm({
      title: '清除全部向量记忆？',
      content: '系统将不再从你的历史对话里按语义召回内容。此操作不可恢复（可重新索引）。',
      okText: '全部清除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await purgeVectorMemory();
          message.success(`已清除 ${r.deleted} 条`);
        } catch (e) {
          message.error(`清除失败：${(e as Error).message}`);
        }
      },
    });
  };

  const columns: ColumnsType<UserFactItem> = [
    {
      title: '分类',
      dataIndex: 'category',
      width: 100,
      render: (_, row) => {
        const meta = CATEGORY_META.get(row.category);
        return <Tag color={meta?.color}>{row.categoryLabel || meta?.label || row.category}</Tag>;
      },
    },
    { title: '键', dataIndex: 'key', width: 180, ellipsis: true },
    { title: '内容', dataIndex: 'value', ellipsis: true },
    {
      title: '来源',
      dataIndex: 'sourceLabel',
      width: 100,
      render: (v: string, row) => (
        <Text type={row.source === 'auto' ? 'warning' : 'secondary'}>{v}</Text>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      render: (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-'),
    },
    {
      title: '操作',
      width: 130,
      render: (_, row) => (
        <Space size="small">
          <Button
            size="small"
            type="link"
            icon={<EditOutlined />}
            disabled={!canManage}
            onClick={() => openEdit(row)}
          >
            编辑
          </Button>
          <Popconfirm
            title="删除这条记忆？"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => remove(row)}
            disabled={!canManage}
          >
            <Button size="small" type="link" danger disabled={!canManage}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 16, maxWidth: 1080 }}>
      <Title level={4} style={{ marginTop: 0 }}>
        长期记忆
      </Title>
      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        平台有四层记忆。这里管理其中两层：<b>长期画像</b>由你主动填写、跨会话长期生效；
        <b>向量记忆</b>让你在别的会话里聊过的相关内容能被按语义召回来。
        另外两层（最近几轮对话、超长会话的早期摘要）随对话自动产生，不需要管理。
      </Paragraph>

      <Card
        title="个人画像"
        style={{ marginBottom: 16 }}
        extra={
          <Space>
            <Button
              icon={<PlusOutlined />}
              type="primary"
              disabled={!canManage}
              onClick={openCreate}
            >
              新增
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => void load()}
              loading={loading}
            >
              刷新
            </Button>
            <Button danger icon={<DeleteOutlined />} disabled={!canManage} onClick={purgeAll}>
              全部清除
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="这些内容会作为背景加入系统提示词"
          description="智能体会据此调整称呼、语气与专业深度，但不会主动复述。填写越具体越有用，例如「职业：Java 后端工程师」比「懂技术」更好。"
        />
        <Table
          rowKey="factId"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={facts}
          pagination={false}
          locale={{ emptyText: <Empty description="还没有填写任何画像" /> }}
        />
      </Card>

      <Card title="向量记忆（历史对话召回）">
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="桌面版重启后索引会清空，需要点「重建索引」"
          description="默认向量库是进程内的，应用重启即丢失。重建会把你历史会话中的发言重新索引一遍（只索引你发的消息，不索引模型回复），之后跨会话的语义召回才能生效。"
        />
        <Space>
          <Button
            type="primary"
            icon={<SyncOutlined />}
            loading={busy}
            disabled={!canManage}
            onClick={() => void rebuildVector()}
          >
            重建索引
          </Button>
          <Button danger icon={<ThunderboltOutlined />} disabled={!canManage} onClick={purgeVector}>
          清除向量记忆
          </Button>
          </Space>
          </Card>

          {/*
          这个 Modal 曾经整块缺失 —— 按钮 onClick 会调 setModalOpen(true)，状态也确实变了，
          但没有任何组件消费它，表现就是"点新增完全没反应"。而且因为 tsconfig 里
          noUnusedLocals=false，未使用的 modalOpen / Form / Input / Select 全都不报错，
          编译器一声不吭。补它时别顺手删掉那些 import，它们现在都在用了。
          */}
          <Modal
          title={editing ? '编辑画像' : '新增画像'}
          open={modalOpen}
          onOk={() => void submit()}
          onCancel={() => setModalOpen(false)}
          confirmLoading={busy}
          okText="保存"
          cancelText="取消"
          width={560}
          // forceRender 不是可选项：antd 的 Modal 首次打开前不渲染子元素，而 openCreate /
          // openEdit 是「先 form.setFieldsValue(...) 再 setModalOpen(true)」——
          // Form 未挂载时 setFieldsValue 会被静默丢弃（控制台只留一句未连接的警告），
          // 表现为"编辑"打开后字段全是空的。预先渲染即可保证 form 实例始终连着。
          forceRender
          >
          <Form form={form} layout="vertical">
          <Form.Item name="category" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
          <Select
            options={CATEGORY_OPTIONS.map((c) => ({ value: c.value, label: c.label }))}
          />
          </Form.Item>
          <Form.Item
          name="key"
          label="键"
          rules={[
            { required: true, whitespace: true, message: '请填写键名' },
            { max: 100, message: '键名最多 100 字' },
          ]}
          extra="用来标识这条画像是什么，例如「职业」「常用语言」"
          >
          <Input placeholder="职业" maxLength={100} />
          </Form.Item>
          <Form.Item
          name="value"
          label="内容"
          rules={[
            { required: true, whitespace: true, message: '请填写内容' },
            { max: 1000, message: '内容最多 1000 字' },
          ]}
          extra="越具体越有用，例如「Java 后端工程师，主要做微服务」"
          >
          <Input.TextArea
            rows={3}
            maxLength={1000}
            showCount
            placeholder="Java 后端工程师，主要做微服务"
          />
          </Form.Item>
          </Form>
          </Modal>
          </div>
          );
          }
