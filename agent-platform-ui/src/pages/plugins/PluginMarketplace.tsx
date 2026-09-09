import { useCallback, useEffect, useState } from 'react';
import {
  Card, Row, Col, Tag, Space, Typography, message, Empty, Button, Modal, Form, Input, Popconfirm, Tooltip,
} from 'antd';
import { PlusOutlined, ImportOutlined, DeleteOutlined } from '@ant-design/icons';
import { marketplace, importPlugin, deletePlugin } from '../../api/plugins';
import { PluginDef } from '../../api/types';
import PluginDetailDrawer from './PluginDetailDrawer';

const EXAMPLE_MANIFEST = `id: my-hello-plugin
name: 问候插件
version: 1.0.0
description: 示例：命中「你好」时回复预设问候语
author: demo
entry:
  type: java
  main_class: com.example.plugin.HelloPlugin
contributes:
  hooks:
    - point: before_llm
permissions: {}
`;

const PLATFORM_TENANT = '__platform__';

export default function PluginMarketplace() {
  const [list, setList] = useState<PluginDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await marketplace());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  /** 从 manifest 文本中取插件 id（YAML `id: xxx` 或 JSON `"id": "xxx"`）。 */
  const extractPluginId = (text: string): string | null => {
    const m = /["']?id["']?\s*[:=]\s*["']?([A-Za-z0-9_.-]+)["']?/.exec(text || '');
    return m ? m[1] : null;
  };

  const submitImport = async () => {
    const v = await form.validateFields();
    const newId = extractPluginId(v.manifest);
    if (newId && list.some((p) => p.pluginId === newId)) {
      message.warning(`插件 ${newId} 已存在，如需覆盖请先删除后再导入`);
      return;
    }
    if (submitting) return;
    setSubmitting(true);
    try {
      await importPlugin(v.manifest);
      message.success('插件已导入');
      setImportOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const del = async (p: PluginDef) => {
    try {
      await deletePlugin(p.pluginId!);
      message.success('插件已删除');
      load();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const isPlatform = (p: PluginDef) => p.tenantId === PLATFORM_TENANT || p.author === PLATFORM_TENANT;

  return (
    <div>
      <Typography.Title level={5}>插件市场</Typography.Title>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setImportOpen(true)}>导入插件</Button>
        <Typography.Text type="secondary">
          导入 = 注册插件 manifest（YAML/JSON）；内置平台插件不可删除；租户自有插件可删除。
        </Typography.Text>
      </Space>

      {!loading && list.length === 0 && <Empty description="暂无插件，点击右上角「导入插件」注册一个" />}
      <Row gutter={16}>
        {list.map((p) => (
          <Col span={8} key={p.pluginId ?? p.id} style={{ marginBottom: 16 }}>
            <Card
              hoverable
              loading={loading}
              title={p.name}
              extra={<Tag color={isPlatform(p) ? 'gold' : 'blue'}>{p.latestVersion ?? 'v1'}</Tag>}
              onClick={() => setDetailId(p.pluginId ?? p.id ?? null)}
            >
              <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
                {p.description || '无描述'}
              </Typography.Paragraph>
              <Space>
                <span style={{ fontSize: 12, color: '#888' }}>作者 {p.author || '—'}</span>
                {isPlatform(p) && <Tag style={{ fontSize: 11 }}>内置平台</Tag>}
              </Space>
              <div style={{ marginTop: 12 }} onClick={(e) => e.stopPropagation()}>
                <Space>
                  <Tooltip title={isPlatform(p) ? '内置平台插件不可删除' : '删除该插件（并从所有智能体卸载）'}>
                    <Popconfirm
                      title="删除该插件？"
                      disabled={isPlatform(p)}
                      onConfirm={() => del(p)}
                    >
                      <Button size="small" danger icon={<DeleteOutlined />} disabled={isPlatform(p)}>
                        删除
                      </Button>
                    </Popconfirm>
                  </Tooltip>
                </Space>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <PluginDetailDrawer pluginId={detailId} onClose={() => setDetailId(null)} />

      <Modal
        title="导入插件（plugin manifest）"
        open={importOpen}
        onOk={submitImport}
        onCancel={() => setImportOpen(false)}
        okButtonProps={{ loading: submitting }}
        okText={submitting ? '导入中…' : '导入'}
        destroyOnClose
        width={680}
      >
        <Form form={form} layout="vertical" initialValues={{ manifest: EXAMPLE_MANIFEST }}>
          <Form.Item name="manifest" label="Manifest（YAML 或 JSON）" rules={[{ required: true }]}>
            <Input.TextArea rows={14} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
