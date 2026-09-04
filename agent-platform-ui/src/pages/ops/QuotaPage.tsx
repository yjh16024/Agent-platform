import { useCallback, useEffect, useState } from 'react';
import { Table, Space, Button, Modal, Form, InputNumber, Select, Progress, message } from 'antd';
import { listQuotas, setQuota } from '../../api/quotas';
import { QuotaStatus } from '../../api/types';

export default function QuotaPage() {
  const [items, setItems] = useState<QuotaStatus[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

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
    load();
  }, [load]);

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

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" onClick={() => setOpen(true)}>配置限额</Button>
      </Space>
      <Table rowKey="quotaType" loading={loading} columns={columns} dataSource={items} pagination={false} />

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