import { useCallback, useEffect, useState } from 'react';
import {
  Table, Space, Button, Input, Select, Modal, Form, message, Card, Typography, Tag, Popconfirm,
} from 'antd';
import { PlayCircleOutlined, EditOutlined, DeleteOutlined, CloudDownloadOutlined, AppstoreOutlined } from '@ant-design/icons';
import { listTools, invokeTool, registerTool, updateTool, unregisterTool } from '../../api/tools';
import { ToolInfo } from '../../api/types';
import McpMarketModal from './McpMarketModal';
import ToolMarketModal from './ToolMarketModal';

const SOURCE_COLOR: Record<string, string> = {
  builtin: 'blue',
  http: 'green',
  mcp: 'purple',
  external: 'default',
};

export default function ToolsPage() {
  const [items, setItems] = useState<ToolInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [toolName, setToolName] = useState<string>();
  const [args, setArgs] = useState('{}');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<ToolInfo | null>(null);
  const [editorForm] = Form.useForm();
  /** MCP 市场弹窗 */
  const [mcpOpen, setMcpOpen] = useState(false);
  /** HTTP 工具市场弹窗 */
  const [toolMarketOpen, setToolMarketOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listTools());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);

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

  const openCreate = () => {
    setEditing(null);
    editorForm.resetFields();
    editorForm.setFieldsValue({ method: 'POST' });
    setEditorOpen(true);
  };

  const openEdit = (t: ToolInfo) => {
    setEditing(t);
    editorForm.resetFields();
    editorForm.setFieldsValue({
      name: t.name,
      description: t.description,
      endpoint: t.endpoint,
      method: t.method || 'POST',
      parameters: t.parameters ? JSON.stringify(t.parameters, null, 2) : '',
    });
    setEditorOpen(true);
  };

  const submitEditor = async () => {
    const v = await editorForm.validateFields();
    // parameters 文本框内容为 JSON 字符串；trim 后为合法 JSON 才提交，否则提示
    let params: Record<string, unknown> | undefined;
    const raw = (v.parameters ?? '').toString().trim();
    if (raw) {
      try {
        params = JSON.parse(raw);
      } catch {
        message.error('入参 Schema 必须是合法 JSON');
        return;
      }
    }
    try {
      if (editing) {
        await updateTool(editing.name!, {
          description: v.description,
          endpoint: v.endpoint,
          method: v.method,
          ...(params ? { parameters: params } : {}),
        });
        message.success('工具已更新');
      } else {
        await registerTool({ ...v, ...(params ? { parameters: params } : {}) });
        message.success('工具已注册');
      }
      setEditorOpen(false);
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const doDelete = async (name: string) => {
    try {
      await unregisterTool(name);
      message.success('工具已删除');
      if (toolName === name) setToolName(undefined);
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const sourceLabel = (s?: string) => {
    const key = s ?? 'external';
    const text: Record<string, string> = { builtin: '内置', http: 'HTTP', mcp: 'MCP', external: '外部' };
    return <Tag color={SOURCE_COLOR[key] ?? 'default'}>{text[key] ?? key}</Tag>;
  };

  const isBuiltin = (t: ToolInfo) => t.source === 'builtin';

  const columns = [
    { title: '工具名', dataIndex: 'name', width: 180 },
    { title: '来源', dataIndex: 'source', width: 90, render: (_: unknown, r: ToolInfo) => sourceLabel(r.source) },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: '端点', dataIndex: 'endpoint', ellipsis: true, render: (v: string) => v || '—' },
    {
      title: '操作', width: 180,
      render: (_: unknown, r: ToolInfo) => (
        <Space>
          <Popconfirm title={`删除工具 ${r.name}？`} onConfirm={() => doDelete(r.name!)}>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={isBuiltin(r)}
              title={isBuiltin(r) ? '内置工具不可删除' : undefined}
            >
              删除
            </Button>
          </Popconfirm>
          <Button
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEdit(r)}
            disabled={r.source !== 'http'}
            title={r.source === 'http' ? '修改 HTTP 工具配置' : '仅 HTTP 注册工具可编辑'}
          >
            编辑
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" icon={<PlayCircleOutlined />} onClick={openCreate}>注册 HTTP 工具</Button>
        <Button icon={<AppstoreOutlined />} onClick={() => setToolMarketOpen(true)}>工具市场</Button>
        <Button icon={<CloudDownloadOutlined />} onClick={() => setMcpOpen(true)}>MCP 市场</Button>
        <Select
          placeholder="选择要调试的工具"
          style={{ width: 200 }}
          value={toolName}
          onChange={setToolName}
          options={items.map((t) => ({ value: t.name, label: `${t.name} (${t.source ?? 'external'})` }))}
        />
        <Input placeholder='参数 JSON，如 {"expression":"1+1"}' style={{ width: 300 }} value={args} onChange={(e) => setArgs(e.target.value)} />
        <Button icon={<PlayCircleOutlined />} onClick={doInvoke}>执行</Button>
      </Space>

      {/* MCP 市场：从官方 Registry 一键注册远程 HTTP 型 MCP 服务器 */}
      <McpMarketModal open={mcpOpen} onClose={() => setMcpOpen(false)} onRegistered={load} />

      {/* HTTP 工具市场：内置精选免 Key 公开 API，一键注册为 HTTP 工具 */}
      <ToolMarketModal
        open={toolMarketOpen}
        onClose={() => setToolMarketOpen(false)}
        onInstalled={load}
      />

      <Table
        rowKey="name"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: '暂无工具（内置 calc/search 与注册的 HTTP/MCP 工具）' }}
      />

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

      <Modal
        title={editing ? `编辑 HTTP 工具：${editing.name}` : '注册 HTTP 工具'}
        open={editorOpen}
        onOk={submitEditor}
        onCancel={() => setEditorOpen(false)}
        destroyOnClose
      >
        <Form form={editorForm} layout="vertical">
          <Form.Item name="name" label="工具名" rules={[{ required: true }]}>
            <Input disabled={!!editing} placeholder="全局唯一，如 weather_query" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="让 LLM 理解何时调用此工具" />
          </Form.Item>
          <Form.Item name="endpoint" label="接口地址" rules={[{ required: true }]}>
            <Input placeholder="https://..." />
          </Form.Item>
          <Form.Item name="method" label="方法" initialValue="POST">
            <Select options={['GET', 'POST'].map((m) => ({ value: m, label: m }))} />
          </Form.Item>
          <Form.Item
            name="parameters"
            label="入参 Schema（JSON，可选）"
            extra="JSON Schema，告诉 LLM 该工具接受哪些参数。留空表示无参数。"
          >
            <Input.TextArea
              rows={6}
              style={{ fontFamily: 'monospace' }}
              placeholder={'{\n  "type": "object",\n  "properties": {\n    "city": { "type": "string", "description": "城市名" }\n  },\n  "required": ["city"]\n}'}
            />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            编辑仅允许修改 HTTP 注册工具；内置工具（calc/search）由代码定义，MCP 工具由远端 Server 定义。
          </Typography.Text>
        </Form>
      </Modal>
    </div>
  );
}
