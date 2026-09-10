import { useCallback, useEffect, useState } from 'react';
import {
  Table, Space, Button, Modal, Form, InputNumber, Select, Progress, message,
  Card, Tag, Typography, Alert, Tabs, Descriptions, Row, Col, Spin, Empty, Divider,
} from 'antd';
import { ReloadOutlined, WalletOutlined } from '@ant-design/icons';
import { listQuotas, setQuota } from '../../api/quotas';
import { getModelBalance, ModelBalanceView } from '../../api/modelBalance';
import { QuotaStatus } from '../../api/types';

const { Title, Text } = Typography;

/** 绑定来源 → 展示名。 */
const BINDING_LABEL: Record<string, string> = {
  chat: '默认对话模型',
  embedding: '嵌入模型（RAG 向量化）',
};

/** 货币符号（未知货币直接显示代码）。 */
function moneySymbol(currency?: string) {
  if (currency === 'USD') return '$';
  if (currency === 'CNY') return '¥';
  return '';
}

function formatTime(ts?: number) {
  return ts ? new Date(ts).toLocaleString() : '-';
}

/** 单条绑定的额度卡片：按 configured / supported / ok 三种状态分别渲染。 */
function BalanceCard({ view }: { view: ModelBalanceView }) {
  const title = BINDING_LABEL[view.binding ?? ''] ?? view.binding ?? '模型绑定';

  return (
    <Card
      style={{ height: '100%' }}
      title={
        <Space>
          <WalletOutlined />
          <span>{title}</span>
          {view.provider && <Tag color="blue">{view.provider}</Tag>}
          {view.model && <Text type="secondary">{view.model}</Text>}
        </Space>
      }
    >
      {!view.configured && (
        <Alert
          type="info"
          showIcon
          message="该绑定尚未配置"
          description={view.message ?? '去「模型配置」页填写服务商与 API Key 后即可查询余额。'}
        />
      )}

      {view.configured && !view.supported && (
        <Alert
          type="warning"
          showIcon
          message="该服务商未提供余额查询接口"
          description={view.message ?? '请前往对应开放平台后台查看额度。'}
        />
      )}

      {view.configured && view.supported && !view.ok && (
        <Alert
          type="error"
          showIcon
          message="额度查询失败"
          description={
            <>
              <div>{view.message}</div>
              {view.baseUrl && <Text type="secondary" style={{ fontSize: 12 }}>查询地址：{view.baseUrl}</Text>}
            </>
          }
        />
      )}

      {view.ok && (
        <>
          <div style={{ marginBottom: 12 }}>
            <Text type="secondary">可用余额</Text>
            <div style={{ fontSize: 30, fontWeight: 600, lineHeight: 1.2 }}>
              {moneySymbol(view.currency)}
              {view.available ?? '-'}
              {view.currency && view.currency !== 'CNY' && view.currency !== 'USD' && (
                <Text type="secondary" style={{ fontSize: 14, marginLeft: 6 }}>{view.currency}</Text>
              )}
            </div>
          </div>
          {view.items && view.items.length > 0 && (
            <Descriptions size="small" column={1} items={
              view.items.map((it, idx) => ({
                key: `${it.label}-${idx}`,
                label: it.label,
                children: it.value ?? '-',
              }))
            } />
          )}
          <Divider style={{ margin: '12px 0' }} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            查询时间：{formatTime(view.checkedAt)}
            {view.baseUrl ? ` · ${view.baseUrl}` : ''}
          </Text>
        </>
      )}
    </Card>
  );
}

export default function QuotaPage() {
  // ---- 模型账户额度（主视图） ----
  const [balances, setBalances] = useState<ModelBalanceView[]>([]);
  const [balanceLoading, setBalanceLoading] = useState(false);

  // ---- 平台用量限额（原租户配额能力，保留） ----
  const [items, setItems] = useState<QuotaStatus[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const loadBalance = useCallback(async () => {
    setBalanceLoading(true);
    try {
      setBalances(await getModelBalance());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBalanceLoading(false);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listQuotas());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadBalance();
    load();
  }, [loadBalance, load]);

  const submit = async () => {
    const v = await form.validateFields();
    try {
      await setQuota({ quota_type: v.quota_type, limit: v.limit, period: v.period });
      message.success('限额已更新');
      setOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    { title: '配额类型', dataIndex: 'quotaType', width: 160 },
    { title: '周期', dataIndex: 'period', width: 100 },
    { title: '限额', dataIndex: 'limit', width: 140, render: (v: number) => v?.toLocaleString() },
    {
      title: '已用', dataIndex: 'used', width: 140, render: (v: number) => v?.toLocaleString(),
    },
    {
      title: '用量',
      dataIndex: 'usage',
      render: (_: unknown, r: QuotaStatus) => {
        const pct = r.limit ? Math.min(100, Math.round(((r.used ?? 0) / r.limit) * 100)) : 0;
        return <Progress percent={pct} size="small" status={pct >= 90 ? 'exception' : 'normal'} />;
      },
    },
  ];

  const balancePane = (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<ReloadOutlined />} loading={balanceLoading} onClick={loadBalance}>
          刷新额度
        </Button>
        <Text type="secondary">
          实时读取各厂商开放平台余额（凭证用服务端已保存的加密 Key，不会下发到浏览器）
        </Text>
      </Space>
      <Spin spinning={balanceLoading}>
        {balances.length === 0 ? (
          <Empty description="暂无可查询的模型绑定" />
        ) : (
          <Row gutter={[16, 16]}>
            {balances.map((b, idx) => (
              <Col xs={24} lg={12} key={`${b.binding}-${idx}`}>
                <BalanceCard view={b} />
              </Col>
            ))}
          </Row>
        )}
      </Spin>
    </div>
  );

  const usagePane = (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" onClick={() => setOpen(true)}>配置限额</Button>
        <Text type="secondary">平台侧统计的租户用量限额（调用次数 / tokens / 文件 / 插件）</Text>
      </Space>
      <Table rowKey="quotaType" loading={loading} columns={columns} dataSource={items} pagination={false} />
    </div>
  );

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>用户配额</Title>
      <Tabs
        defaultActiveKey="balance"
        items={[
          { key: 'balance', label: '模型账户额度', children: balancePane },
          { key: 'usage', label: '平台用量限额', children: usagePane },
        ]}
      />

      <Modal title="配置租户配额限额" open={open} onOk={submit} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ period: 'daily' }}>
          <Form.Item name="quota_type" label="配额类型" rules={[{ required: true }]}>
            <Select options={['model_calls', 'tokens', 'files', 'plugins'].map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item name="period" label="周期">
            <Select options={['daily', 'monthly'].map((p) => ({ value: p, label: p }))} />
          </Form.Item>
          <Form.Item name="limit" label="限额" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
