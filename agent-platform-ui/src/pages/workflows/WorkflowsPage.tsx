import { useCallback, useEffect, useState } from 'react';
import { Table, Space, Button, Modal, Form, Input, Tag, message, Card, Popconfirm } from 'antd';
import { PlusOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { listWorkflows, createWorkflow, executeWorkflow, getWorkflow, deleteWorkflow } from '../../api/workflows';
import { WorkflowDef } from '../../api/types';

const EXAMPLE = {
  nodes: [
    { id: 'start', type: 'start' },
    { id: 'transform', type: 'transform', config: { expression: 'input.name' } },
    { id: 'end', type: 'end' },
  ],
  edges: [
    { from: 'start', to: 'transform' },
    { from: 'transform', to: 'end' },
  ],
};

export default function WorkflowsPage() {
  const [items, setItems] = useState<WorkflowDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [execId, setExecId] = useState<string>();
  const [execInput, setExecInput] = useState('{}');
  const [execResult, setExecResult] = useState<Record<string, unknown> | null>(null);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listWorkflows());
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
    let definition: Record<string, unknown>;
    try {
      definition = JSON.parse(v.definition);
    } catch {
      message.error('工作流定义必须是合法 JSON');
      return;
    }
    try {
      await createWorkflow(v.name, definition, v.description);
      message.success('工作流已创建');
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doExecute = async () => {
    if (!execId) return;
    let input: Record<string, unknown>;
    try {
      input = JSON.parse(execInput || '{}');
    } catch {
      message.error('输入必须是合法 JSON');
      return;
    }
    try {
      setExecResult(await executeWorkflow(execId, input));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const viewDef = async (id: string) => {
    try {
      const def = await getWorkflow(id);
      Modal.info({ title: '工作流定义', content: <pre>{JSON.stringify(def, null, 2)}</pre>, width: 640 });
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Tag color={v === 'published' ? 'green' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作', width: 260,
      render: (_: unknown, r: WorkflowDef) => (
        <Space>
          <Button size="small" onClick={() => viewDef(r.workflowId!)}>查看</Button>
          <Button size="small" icon={<PlayCircleOutlined />} onClick={() => { setExecId(r.workflowId!); setExecResult(null); }}>执行</Button>
          <Popconfirm title="归档该工作流？" onConfirm={async () => { await deleteWorkflow(r.workflowId!); load(); }}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建工作流</Button>
      </Space>
      <Table rowKey="workflowId" loading={loading} columns={columns} dataSource={items} pagination={false} />

      <Modal title="创建工作流" open={createOpen} onOk={submitCreate} onCancel={() => setCreateOpen(false)} destroyOnClose width={640}>
        <Form form={form} layout="vertical" initialValues={{ definition: JSON.stringify(EXAMPLE, null, 2) }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input /></Form.Item>
          <Form.Item name="definition" label="DAG 定义（JSON）" rules={[{ required: true }]}>
            <Input.TextArea rows={10} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="执行工作流"
        open={execId !== undefined}
        onOk={doExecute}
        onCancel={() => setExecId(undefined)}
        width={560}
      >
        <Form layout="vertical">
          <Form.Item label="输入（JSON）">
            <Input.TextArea rows={4} style={{ fontFamily: 'monospace' }} value={execInput} onChange={(e) => setExecInput(e.target.value)} />
          </Form.Item>
        </Form>
        {execResult && (
          <Card size="small" title="执行结果">
            <pre style={{ maxHeight: 240, overflow: 'auto' }}>{JSON.stringify(execResult, null, 2)}</pre>
          </Card>
        )}
      </Modal>
    </div>
  );
}