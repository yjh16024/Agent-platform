import { useEffect, useState } from 'react';
import { Table, Space, Button, Input, Select, Modal, Form, message, Card, Typography, Tag } from 'antd';
import { PlayCircleOutlined } from '@ant-design/icons';
import { listTools, invokeTool, registerTool } from '../../api/tools';
import { ToolInfo } from '../../api/types';

export default function ToolsPage() {
  const [items, setItems] = useState<ToolInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [toolName, setToolName] = useState<string>();
  const [args, setArgs] = useState('{}');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [regOpen, setRegOpen] = useState(false);
  const [regForm] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setItems(await listTools());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
  }, []);

  const doInvoke = async () => {
    if (!toolName) {
      message.warning('请选择工具');
      return;
    }
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(args || '{}');
    } catch {
      message.error('参数必须是合法 JSON');
      return;
    }
    try {
      setResult(await invokeTool(toolName, parsed));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doRegister = async () => {
    const v = await regForm.validateFields();
    try {
      await registerTool(v);
      message.success('工具已注册');
      setRegOpen(false);
      regForm.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    { title: '工具名', dataIndex: 'name', width: 200 },
    { title: '描述', dataIndex: 'description', ellipsis: true },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" onClick={() => setRegOpen(true)}>注册 HTTP 工具</Button>
        <Select
          placeholder="选择要调试的工具"
          style={{ width: 220 }}
          value={toolName}
          onChange={setToolName}
          options={items.map((t) => ({ value: t.name, label: t.name }))}
        />
        <Input placeholder='参数 JSON，如 {"expression":"1+1"}' style={{ width: 320 }} value={args} onChange={(e) => setArgs(e.target.value)} />
        <Button icon={<PlayCircleOutlined />} onClick={doInvoke}>执行</Button>
      </Space>

      <Table rowKey="name" loading={loading} columns={columns} dataSource={items} pagination={false} />

      {result && (
        <Card title="执行结果" style={{ marginTop: 16 }}>
          <Space direction="vertical">
            <Tag color={result.success === false ? 'red' : 'green'}>success={String(result.success ?? '—')}</Tag>
            <pre style={{ maxHeight: 300, overflow: 'auto', background: '#f5f5f5', padding: 12, borderRadius: 6 }}>
              {JSON.stringify(result, null, 2)}
            </pre>
          </Space>
        </Card>
      )}

      <Modal title="注册 HTTP 工具" open={regOpen} onOk={doRegister} onCancel={() => setRegOpen(false)} destroyOnClose>
        <Form form={regForm} layout="vertical">
          <Form.Item name="name" label="工具名" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input /></Form.Item>
          <Form.Item name="endpoint" label="接口地址" rules={[{ required: true }]}><Input placeholder="https://..." /></Form.Item>
          <Form.Item name="method" label="方法" initialValue="POST">
            <Select options={['GET', 'POST'].map((m) => ({ value: m, label: m }))} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}