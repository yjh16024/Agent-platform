import { useCallback, useEffect, useState } from 'react';
import { Table, Input, Select, Space, Button, Modal, Form, Tag, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { listLogs, collectLog } from '../../api/ops';
import { LogEvent } from '../../api/types';
import { useAppStore } from '../../store/appStore';

const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];
const CATEGORIES = ['agent', 'llm', 'plugin', 'skill', 'api', 'workflow', 'system'];

const levelColor = (l?: string) =>
  ({ ERROR: 'red', WARN: 'orange', INFO: 'blue', DEBUG: 'default', TRACE: 'default' }[l ?? ''] ?? 'default');

export default function LogsPage() {
  const { tenantId } = useAppStore();
  const [items, setItems] = useState<LogEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [traceId, setTraceId] = useState('');
  const [level, setLevel] = useState<string | undefined>();
  const [category, setCategory] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listLogs({
        traceId: traceId || undefined,
        level,
        category,
        keyword: keyword || undefined,
        page,
        size,
      });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [traceId, level, category, keyword, page, size]);

  useEffect(() => {
    load();
  }, [load]);

  const submitReport = async () => {
    const v = await form.validateFields();
    // collect 的 body 是 LogEvent：camelCase
    try {
      await collectLog({
        logId: v.logId || `log_manual_${Date.now()}`,
        level: v.level,
        category: v.category,
        message: v.message,
        traceId: v.traceId || undefined,
        agentId: v.agentId || undefined,
        tenantId,
      });
      message.success('已上报');
      setReportOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    {
      title: '时间',
      dataIndex: 'timestamp',
      width: 180,
      render: (v: unknown) => (v ? new Date(v as string).toLocaleString() : '—'),
    },
    {
      title: '级别',
      dataIndex: 'level',
      width: 90,
      render: (v: string) => <Tag color={levelColor(v)}>{v}</Tag>,
    },
    { title: '类别', dataIndex: 'category', width: 110 },
    { title: 'traceId', dataIndex: 'traceId', width: 170, ellipsis: true },
    { title: '消息', dataIndex: 'message', ellipsis: true },
  ];

  return (
    <div>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input
          placeholder="traceId"
          allowClear
          style={{ width: 200 }}
          onChange={(e) => setTraceId(e.target.value)}
        />
        <Input
          placeholder="关键词"
          allowClear
          style={{ width: 180 }}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <Select
          placeholder="级别"
          allowClear
          style={{ width: 120 }}
          onChange={setLevel}
          options={LEVELS.map((l) => ({ value: l, label: l }))}
        />
        <Select
          placeholder="类别"
          allowClear
          style={{ width: 140 }}
          onChange={setCategory}
          options={CATEGORIES.map((c) => ({ value: c, label: c }))}
        />
        <Button type="primary" onClick={load}>
          查询
        </Button>
        <Button icon={<PlusOutlined />} onClick={() => setReportOpen(true)}>
          上报一条测试日志
        </Button>
      </Space>

      <Table
        rowKey={(r) => `${r.logId}-${r.timestamp}`}
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p, ps) => { setPage(p - 1); setSize(ps); },
        }}
      />

      <Modal
        title="上报测试日志"
        open={reportOpen}
        onOk={submitReport}
        onCancel={() => setReportOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ level: 'ERROR', category: 'plugin' }}>
          <Form.Item name="logId" label="logId（可选）">
            <Input placeholder="缺省自动生成" />
          </Form.Item>
          <Form.Item name="level" label="级别" rules={[{ required: true }]}>
            <Select options={LEVELS.map((l) => ({ value: l, label: l }))} />
          </Form.Item>
          <Form.Item name="category" label="类别" rules={[{ required: true }]}>
            <Select options={CATEGORIES.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
          <Form.Item name="traceId" label="traceId（可选）">
            <Input />
          </Form.Item>
          <Form.Item name="agentId" label="agentId（可选）">
            <Input />
          </Form.Item>
          <Form.Item name="message" label="消息" rules={[{ required: true, message: '请输入消息' }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}