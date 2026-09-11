import { useCallback, useEffect, useState } from 'react';
import { Table, Space, Button, Modal, Form, Input, Tag, message, Card, Popconfirm } from 'antd';
import { PlusOutlined, PlayCircleOutlined, BranchesOutlined } from '@ant-design/icons';
import { listWorkflows, createWorkflow, executeWorkflow, getWorkflow, deleteWorkflow } from '../../api/workflows';
import { WorkflowDef } from '../../api/types';
import WorkflowCanvas from './canvas/WorkflowCanvas';
import { defaultCanvas, toCanvas } from './canvas/adapter';
import type { BackendDefinition, FlowDocumentJSON } from './canvas/types';

/**
 * 示例定义：与后端契约一致 —— 用 `next` 串联（旧示例误用 `edges`，建出来的流程链路是断的）。
 */
const EXAMPLE = {
  name: '示例流程',
  nodes: [
    { id: 'start', type: 'Start', name: '开始', next: 'transform', outputVar: null, config: { inputKey: 'input' } },
    {
      id: 'transform',
      type: 'Transform',
      name: '变量转换',
      next: 'end',
      outputVar: 'result',
      inputMapping: { name: '${input}' },
      config: {},
    },
    { id: 'end', type: 'End', name: '结束', next: null, outputVar: null, config: {} },
  ],
  entryNode: 'start',
};

export default function WorkflowsPage() {
  const [items, setItems] = useState<WorkflowDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [execId, setExecId] = useState<string>();
  const [execInput, setExecInput] = useState('{}');
  const [execResult, setExecResult] = useState<Record<string, unknown> | null>(null);
  const [form] = Form.useForm();

  /** 画布态：有值时整页切换到画布 */
  const [canvas, setCanvas] = useState<{
    id: string;
    name: string;
    data: FlowDocumentJSON;
    publishedVersion?: string;
  } | null>(null);

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

  /** 打开画布：加载后端定义 → 转画布图（空定义给一个「开始→LLM→结束」的最小可用图） */
  const openCanvas = useCallback(async (id: string, name: string, publishedVersion?: string) => {
    try {
      const def = (await getWorkflow(id)) as unknown as BackendDefinition;
      const data = def && Array.isArray(def.nodes) && def.nodes.length > 0
        ? toCanvas(def)
        : defaultCanvas(name);
      setCanvas({ id, name, data, publishedVersion });
    } catch (e) {
      message.error(`加载工作流失败：${(e as Error).message}`);
    }
  }, []);

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
      const created = await createWorkflow(v.name, definition, v.description);
      message.success('工作流已创建，可直接进入画布拖拽编辑');
      setCreateOpen(false);
      form.resetFields();
      load();
      if (created?.workflowId) {
        void openCanvas(created.workflowId, created.name ?? v.name);
      }
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
      title: '状态', dataIndex: 'status', width: 150,
      render: (v: string, r: WorkflowDef) => (
        <Space size={4}>
          <Tag color={v === 'published' ? 'green' : 'default'}>{v}</Tag>
          {r.publishedVersion ? <Tag color="blue">{r.publishedVersion}</Tag> : null}
        </Space>
      ),
    },
    {
      title: '操作', width: 340,
      render: (_: unknown, r: WorkflowDef) => (
        <Space>
          <Button size="small" type="primary" ghost icon={<BranchesOutlined />} onClick={() => openCanvas(r.workflowId!, r.name ?? '未命名', r.publishedVersion)}>
            画布
          </Button>
          <Button size="small" onClick={() => viewDef(r.workflowId!)}>查看</Button>
          <Button size="small" icon={<PlayCircleOutlined />} onClick={() => { setExecId(r.workflowId!); setExecResult(null); }}>执行</Button>
          <Popconfirm title="删除该工作流？" onConfirm={async () => { await deleteWorkflow(r.workflowId!); load(); }}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // ---- 画布态：整页切换为拖拽式编排 ----
  if (canvas) {
    return (
      <div style={{ height: 'calc(100vh - 112px)', display: 'flex', flexDirection: 'column' }}>
        <WorkflowCanvas
          workflowId={canvas.id}
          workflowName={canvas.name}
          initialData={canvas.data}
          publishedVersion={canvas.publishedVersion}
          onClose={() => {
            setCanvas(null);
            load();
          }}
        />
      </div>
    );
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建工作流</Button>
        <span style={{ color: '#8a9099', fontSize: 12 }}>
          点「画布」进入拖拽式编排（LLM / 知识库 / 代码 / HTTP / 插件 / Agent / 条件分支）
        </span>
      </Space>
      <Table rowKey="workflowId" loading={loading} columns={columns} dataSource={items} pagination={false} />

      <Modal title="创建工作流" open={createOpen} onOk={submitCreate} onCancel={() => setCreateOpen(false)} destroyOnClose width={640}>
        <Form form={form} layout="vertical" initialValues={{ definition: JSON.stringify(EXAMPLE, null, 2) }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input /></Form.Item>
          <Form.Item name="definition" label="DAG 定义（JSON，可先用默认示例，创建后进画布拖拽）" rules={[{ required: true }]}>
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
