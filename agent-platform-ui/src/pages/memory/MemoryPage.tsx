import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Badge,
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
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  adoptAllCandidates,
  adoptCandidate,
  deleteFact,
  listCandidates,
  listFacts,
  purgeCandidates,
  purgeFacts,
  purgeVectorMemory,
  rebuildVectorMemory,
  rejectAllCandidates,
  rejectCandidate,
  saveFact,
  updateFact,
  type FactCandidateItem,
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
 * 记忆管理页：长期画像 + 待确认候选 + 向量记忆。
 *
 * <p>为什么把这几块放在同一页：它们都是"对话之外、但会影响对话"的数据，
 * 用户需要能在一个地方看清"系统记住了我什么"以及"关掉它"。但几块刻意分成
 * 独立卡片而不是混在一张表里 —— 一处是<strong>我主动说的</strong>，一处是
 * <strong>系统从对话里推出来的</strong>，混淆会让用户分不清哪条是自己填的。</p>
 *
 * <h3>「待确认」这块为什么必须存在</h3>
 * 自动抽取本意是省去手填的麻烦，但它同时意味着<strong>系统会在用户背后记下关于他的事</strong>。
 * 若抽取结果直接生效，用户永远不会知道"模型为什么突然换了口气"，也无从纠正记错的信息 ——
 * 而这份数据会一直影响之后所有对话。<b>所以顺序不能反：先让用户对这份数据有控制感
 * （看得见、改得动、删得掉），才谈得上让系统自动往里写。</b>
 *
 * <p>候选也刻意<strong>不进系统提示词</strong>：只有点了「采纳」才会搬进正式画像并生效。</p>
 *
 * <p>短期（最近几轮）与中期（会话摘要）不在这里管：它们是纯服务端机制，
 * 随对话自然发生、随会话删除而消失，不需要独立开关。</p>
 */
export default function MemoryPage() {
  const { message, modal } = AntApp.useApp();
  const [facts, setFacts] = useState<UserFactItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [candidates, setCandidates] = useState<FactCandidateItem[]>([]);
  const [candLoading, setCandLoading] = useState(false);
  const [candBusy, setCandBusy] = useState(false);
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

  const loadCandidates = useCallback(async () => {
    setCandLoading(true);
    try {
      setCandidates(await listCandidates());
    } catch (e) {
      message.error(`加载待确认画像失败：${(e as Error).message}`);
    } finally {
      setCandLoading(false);
    }
  }, [message]);

  /** 刷新按钮同时刷两块：它们的数据是联动的（采纳候选会让正式画像多一条）。 */
  const refreshAll = useCallback(() => {
    void load();
    void loadCandidates();
  }, [load, loadCandidates]);

  useEffect(() => {
    refreshAll();
    getMe()
      .then((me) => setPerms(me.perms ?? []))
      .catch(() => setPerms(null));
  }, [refreshAll]);

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

  /**
   * 清除全部长期记忆 —— **同时清除待确认候选**。
   *
   * <p>刻意合并成一个动作：用户点「全部清除」的意图是"别再记我的事了"。
   * 若只清正式画像而把候选留着，他甚至在界面上看不出还剩东西
   * （候选不在画像表里），会以为已经清干净 —— 那是最不该出现的结果。
   * 确认文案里写清楚会一并清除，让他知情。</p>
   */
  const purgeAll = () => {
    const pending = candidates.length;
    modal.confirm({
      title: '清除全部长期记忆？',
      content:
        pending > 0
          ? `将清除你填写的 ${facts.length} 条画像，以及系统识别出的 ${pending} 条待确认候选。清除后不可恢复，平台也不会再保留这些内容（包括"你曾忽略过某项"这一记录）。`
          : '这些是你主动填写的个人信息，清除后不可恢复，智能体将不再据此调整回答。',
      okText: '全部清除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await purgeFacts();
          const c = await purgeCandidates();
          message.success(
            c.deleted > 0
              ? `已清除 ${r.deleted} 条画像、${c.deleted} 条候选`
              : `已清除 ${r.deleted} 条`,
          );
          await Promise.all([load(), loadCandidates()]);
        } catch (e) {
          message.error(`清除失败：${(e as Error).message}`);
        }
      },
    });
  };

  // ---------------------------------------------------------------- 候选：采纳 / 忽略

  const adoptOne = async (row: FactCandidateItem) => {
    setCandBusy(true);
    try {
      await adoptCandidate(row.candidateId);
      message.success(`已采纳「${row.key}」，可在上方个人画像中查看`);
      // 采纳会同时改变两块数据（候选少一条、画像多一条），所以两块都刷
      await Promise.all([load(), loadCandidates()]);
    } catch (e) {
      message.error(`采纳失败：${(e as Error).message}`);
    } finally {
      setCandBusy(false);
    }
  };

  const rejectOne = async (row: FactCandidateItem) => {
    setCandBusy(true);
    try {
      await rejectCandidate(row.candidateId);
      message.success(`已忽略「${row.key}」，之后不会再提示`);
      await loadCandidates();
    } catch (e) {
      message.error(`忽略失败：${(e as Error).message}`);
    } finally {
      setCandBusy(false);
    }
  };

  const adoptAll = () => {
    modal.confirm({
      title: '采纳全部待确认画像？',
      content: '它们会写入个人画像，并加入之后所有对话的系统提示词。',
      okText: '全部采纳',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await adoptAllCandidates();
          message.success(`已采纳 ${r.adopted} 条`);
          await Promise.all([load(), loadCandidates()]);
        } catch (e) {
          message.error(`采纳失败：${(e as Error).message}`);
        }
      },
    });
  };

  const rejectAll = () => {
    modal.confirm({
      title: '忽略全部待确认画像？',
      content:
        '平台会记住"这些信息你不需要"，之后不会再从对话里抽出同样的内容来打扰你。正式画像不受影响。',
      okText: '全部忽略',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await rejectAllCandidates();
          message.success(`已忽略 ${r.rejected} 条`);
          await loadCandidates();
        } catch (e) {
          message.error(`忽略失败：${(e as Error).message}`);
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

  /**
   * 候选表格。
   *
   * <p>刻意**不显示"发现时间"** —— 用户不关心系统是什么时候发现的，只关心"这条对不对、要不要"。
   * 但**保留"来源"列**（哪个模型、哪个会话抽出来的）：这是可解释性，用户有权知道
   * "你凭什么这么记我"。所以宁可挤一点也要留着。</p>
   */
  const candidateColumns: ColumnsType<FactCandidateItem> = [
    {
      title: '分类',
      dataIndex: 'category',
      width: 90,
      render: (_, row) => {
        const meta = CATEGORY_META.get(row.category);
        return <Tag color={meta?.color}>{row.categoryLabel || meta?.label || row.category}</Tag>;
      },
    },
    { title: '键', dataIndex: 'key', width: 150, ellipsis: true },
    { title: '内容', dataIndex: 'value', ellipsis: true },
    {
      title: '来源',
      dataIndex: 'extractedBy',
      width: 150,
      ellipsis: true,
      render: (v: string | null, row) => (
        <Tooltip
          title={row.sourceSessionId ? `来源会话：${row.sourceSessionId}` : '来源会话未记录'}
        >
          <Text type="secondary">{v || '未知模型'}</Text>
        </Tooltip>
      ),
    },
    {
      title: '操作',
      width: 140,
      render: (_, row) => (
        <Space size="small">
          <Button
            size="small"
            type="link"
            icon={<CheckOutlined />}
            disabled={!canManage || candBusy}
            onClick={() => void adoptOne(row)}
          >
            采纳
          </Button>
          <Button
            size="small"
            type="link"
            icon={<CloseOutlined />}
            disabled={!canManage || candBusy}
            onClick={() => void rejectOne(row)}
          >
            忽略
          </Button>
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
        平台有四层记忆。这里管理其中三层：<b>个人画像</b>由你主动填写、跨会话长期生效；
        <b>待确认的画像</b>是系统从对话里识别出来、等你确认后才生效的；
        <b>向量记忆</b>让你在别的会话里聊过的相关内容能被按语义召回来。
        剩下两层（最近几轮对话、超长会话的早期摘要）随对话自动产生，不需要管理。
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

      <Card
        title={
          <Space size="small">
            <span>待确认的画像</span>
            {candidates.length > 0 && <Badge count={candidates.length} />}
          </Space>
        }
        style={{ marginBottom: 16 }}
        extra={
          <Space>
            <Button
              icon={<CheckOutlined />}
              disabled={!canManage || candBusy || candidates.length === 0}
              onClick={adoptAll}
            >
              全部采纳
            </Button>
            <Button
              icon={<CloseOutlined />}
              danger
              disabled={!canManage || candBusy || candidates.length === 0}
              onClick={rejectAll}
            >
              全部忽略
            </Button>
            <Button
              icon={<ReloadOutlined />}
              loading={candLoading}
              onClick={() => void loadCandidates()}
            >
              刷新
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="这些是系统从你与智能体的对话里识别出来的，需要你确认后才生效"
          description={
            <>
              采纳后会写入上方「个人画像」，并参与之后所有对话；忽略则不会再提示同类内容。
              <b>在你确认之前，这些内容不会进入任何对话上下文。</b>
              自动抽取默认关闭，需部署方以{' '}
              <Text code>MEMORY_AUTO_PROFILE_ENABLED=true</Text> 开启后才会产生候选。
            </>
          }
        />
        <Table
          rowKey="candidateId"
          size="small"
          loading={candLoading}
          columns={candidateColumns}
          dataSource={candidates}
          pagination={false}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待确认的画像" />,
          }}
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
