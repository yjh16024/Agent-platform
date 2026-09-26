import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Empty, Form, Input, List, Modal, Space, Tag, Typography, message } from 'antd';
import { EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { mcpTemplates, registerStdioMcp, type McpTemplateItem } from '../../api/tools';

/**
 * MCP **命令模板**面板：常用 stdio server 的启动命令。
 *
 * <h3>为什么和「MCP 市场」是两件事</h3>
 * 市场（另一个 Tab）面向**远程 HTTP** 型服务器 —— 给个地址就能连。
 * 本面板面向**需要在本机拉起进程**的 stdio 型：官方 registry 里这类条目**只给包名、不给怎么运行**，
 * 命令得自己拼（`npx -y @modelcontextprotocol/server-filesystem /path` 这种），很容易写错。
 * 所以后台直接给出**人写好并核对过包名**的模板（npm / PyPI 都能查到），用户只需填少量参数。
 *
 * <h3>两个刻意的设计</h3>
 * 1. **命令行可编辑**：模板只保证包名真实，**参数形式以各 server 官方文档为准** ——
 *    所以在注册前把完整命令摊开给用户看、允许直接改，而不是当成不可改的最终答案。
 * 2. **命令行按"每行一个参数"编辑**（而不是一行空格分隔）：参数里出现空格时
 *    （Windows 路径、含空格的目录名）空格分隔会静默拆错，按行则永远无歧义。
 */
export default function McpTemplatePanel({ onRegistered }: { onRegistered?: () => void }) {
  const [list, setList] = useState<McpTemplateItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<McpTemplateItem | null>(null);
  const [commandText, setCommandText] = useState('');
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<Record<string, string>>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await mcpTemplates());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 把参数值代入 {{name}} 占位符。 */
  const renderCommand = (item: McpTemplateItem, values: Record<string, string>) => {
    return (item.commandTemplate ?? []).map((seg) =>
      (seg ?? '').replace(/\{\{(\w+)\}\}/g, (_, k: string) => (values[k] ?? '').trim()),
    );
  };

  const openEditor = (item: McpTemplateItem) => {
    const initial: Record<string, string> = {};
    (item.parameters ?? []).forEach((p) => {
      initial[p.name] = '';
    });
    form.setFieldsValue(initial);
    setEditing(item);
    setCommandText(renderCommand(item, initial).join('\n'));
  };

  /** 参数改动后同步命令预览（用户仍可手动改命令）。 */
  const syncFromForm = () => {
    if (!editing) return;
    const values = form.getFieldsValue();
    setCommandText(renderCommand(editing, values).join('\n'));
  };

  const submit = async () => {
    if (!editing) return;
    await form.validateFields();
    const command = commandText
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    if (command.length === 0) {
      message.error('命令行不能为空');
      return;
    }
    setBusy(true);
    try {
      await registerStdioMcp({ command });
      message.success(`已注册 stdio MCP：${editing.title}`);
      setEditing(null);
      onRegistered?.();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          这些 server 会在**本机拉起源进程**，注册需管理员权限（模型无法自行发起）
        </Typography.Text>
      </Space>

      {!loading && list.length === 0 && (
        <Empty description="没有可用模板（请确认后端已启动）" />
      )}

      <List
        loading={loading}
        dataSource={list}
        style={{ maxHeight: 540, overflow: 'auto' }}
        renderItem={(item) => (
          <List.Item
            key={item.id}
            actions={[
              <Button
                key="use"
                type="primary"
                size="small"
                icon={<EditOutlined />}
                onClick={() => openEditor(item)}
              >
                填写并注册
              </Button>,
            ]}
          >
            <List.Item.Meta
              title={
                <Space wrap>
                  <span>{item.title}</span>
                  {(item.tags ?? []).map((t) => (
                    <Tag key={t}>{t}</Tag>
                  ))}
                </Space>
              }
              description={
                <>
                  <Typography.Paragraph
                    type="secondary"
                    style={{ fontSize: 12, marginBottom: 4 }}
                    ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
                  >
                    {item.description || '（无描述）'}
                  </Typography.Paragraph>
                  <Typography.Text code style={{ fontSize: 11 }}>
                    {(item.commandTemplate ?? []).join(' ')}
                  </Typography.Text>
                </>
              }
            />
          </List.Item>
        )}
      />

      <Modal
        title={editing ? `注册 stdio MCP · ${editing.title}` : ''}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={() => void submit()}
        confirmLoading={busy}
        okText="注册"
        cancelText="取消"
        width={720}
        forceRender
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="这会在本机执行你填写的命令"
          description="模板只保证包名真实存在；参数形式请以该 server 官方文档为准 —— 注册前请确认下方的完整命令行。"
        />

        <Form form={form} layout="vertical" onValuesChange={syncFromForm}>
          {(editing?.parameters ?? []).map((p) => (
            <Form.Item
              key={p.name}
              name={p.name}
              label={p.label}
              rules={p.required ? [{ required: true, whitespace: true, message: `请填写${p.label}` }] : []}
              extra={p.hint}
            >
              <Input placeholder={p.placeholder} />
            </Form.Item>
          ))}

          <Form.Item
            label="完整命令行"
            extra="每行一个参数（不要用空格分隔 —— 参数自身含空格时会被拆错）。可在此直接修改。"
          >
            <Input.TextArea
              rows={5}
              value={commandText}
              onChange={(e) => setCommandText(e.target.value)}
              style={{ fontFamily: 'monospace' }}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
