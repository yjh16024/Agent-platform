import { useCallback, useEffect, useState } from 'react';
import {
  Card, Row, Col, Tag, Space, Typography, message, Empty, Button, Modal, Form, Input, Popconfirm, Tooltip, Upload, Divider,
} from 'antd';
import type { UploadFile } from 'antd';
import { PlusOutlined, DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { marketplace, importPlugin, uploadPlugin, deletePlugin } from '../../api/plugins';
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
  /** 待上传的插件包：选了就走 multipart 上传，否则按文本 manifest 登记 */
  const [jarList, setJarList] = useState<UploadFile[]>([]);
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
      const jar = jarList[0]?.originFileObj as File | undefined;
      if (jar) {
        // 上传 jar：后端落盘并写入 artifact_uri，之后即可「挂载到智能体」
        await uploadPlugin(v.manifest, jar);
        message.success('插件包已上传并注册');
      } else {
        // 纯文本登记（可带远程/本地制品地址）
        await importPlugin(v.manifest, (v.artifactUri as string) || undefined);
        message.success('插件已导入');
      }
      setImportOpen(false);
      form.resetFields();
      setJarList([]);
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

  /**
   * 内置插件 = 平台内置（tenant_id = __platform__，由 BuiltinPluginRegistrar 在启动时自动登记），
   * 其余为外部插件（用户上传的 jar 或登记的 manifest）。按租户判定，与后端语义对齐。
   */
  const isBuiltin = (p: PluginDef) => p.tenantId === PLATFORM_TENANT || p.author === PLATFORM_TENANT;

  const builtinPlugins = list.filter(isBuiltin);
  const externalPlugins = list.filter((p) => !isBuiltin(p));

  /** 单张插件卡片（两个分组共用同一套渲染，避免两处样式漂移）。 */
  const renderCard = (p: PluginDef) => (
    <Col span={8} key={p.pluginId ?? p.id} style={{ marginBottom: 16 }}>
      <Card
        hoverable
        loading={loading}
        title={p.name}
        extra={<Tag color={isBuiltin(p) ? 'gold' : 'blue'}>{p.latestVersion ?? 'v1'}</Tag>}
        onClick={() => setDetailId(p.pluginId ?? p.id ?? null)}
      >
        <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
          {p.description || '无描述'}
        </Typography.Paragraph>
        <Space wrap>
          <span style={{ fontSize: 12, color: '#888' }}>作者 {p.author || '—'}</span>
          {isBuiltin(p) && <Tag style={{ fontSize: 11 }}>内置平台</Tag>}
          {p.artifactUri ? (
            <Tag color="green" style={{ fontSize: 11 }}>已有制品</Tag>
          ) : (
            <Tag style={{ fontSize: 11 }}>仅元数据</Tag>
          )}
        </Space>
        <div style={{ marginTop: 12 }} onClick={(e) => e.stopPropagation()}>
          <Space>
            <Tooltip title={isBuiltin(p) ? '内置平台插件不可删除' : '删除该插件（并从所有智能体卸载）'}>
              <Popconfirm
                title="删除该插件？"
                disabled={isBuiltin(p)}
                onConfirm={() => del(p)}
              >
                <Button size="small" danger icon={<DeleteOutlined />} disabled={isBuiltin(p)}>
                  删除
                </Button>
              </Popconfirm>
            </Tooltip>
          </Space>
        </div>
      </Card>
    </Col>
  );

  /** 一个分组区块：标题 + 计数 + 说明 + 卡片网格；为空时给一句可操作的提示。 */
  const renderGroup = (title: string, hint: string, items: PluginDef[], emptyText: string) => (
    <section style={{ marginBottom: 4 }}>
      <Divider orientation="left" style={{ marginTop: 4 }}>
        <Space size={6}>
          <Typography.Text strong>{title}</Typography.Text>
          <Tag color={items.length > 0 ? 'blue' : undefined}>{items.length}</Tag>
        </Space>
      </Divider>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
        {hint}
      </Typography.Paragraph>
      {items.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={emptyText}
          style={{ margin: '4px 0 20px' }}
        />
      ) : (
        <Row gutter={16}>{items.map(renderCard)}</Row>
      )}
    </section>
  );

  return (
    <div>
      <Typography.Title level={5}>插件市场</Typography.Title>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setImportOpen(true)}>导入插件</Button>
        <Typography.Text type="secondary">
          下方按来源分成两组：内置插件随平台发布、代码内置，不可删除；外部插件是你上传的 jar 或登记的 manifest。
          点任意卡片可在详情里挂载到智能体。
        </Typography.Text>
      </Space>

      {renderGroup(
        '内置插件',
        '随平台一起发布、由代码内置（@Component 实现 Plugin SPI），启动时自动登记进本市场；不可删除，可直接挂载到智能体。',
        builtinPlugins,
        '暂无内置插件',
      )}

      {renderGroup(
        '外部插件',
        '通过「导入插件」上传的 jar 包或登记的 manifest；删除时会级联取消它在所有智能体上的挂载。',
        externalPlugins,
        '暂无外部插件 —— 点上方「导入插件」上传一个 jar',
      )}

      <PluginDetailDrawer pluginId={detailId} onClose={() => setDetailId(null)} />

      <Modal
        title="导入插件"
        open={importOpen}
        onOk={submitImport}
        onCancel={() => { setImportOpen(false); setJarList([]); }}
        okButtonProps={{ loading: submitting }}
        okText={submitting ? '导入中…' : '导入'}
        destroyOnClose
        width={720}
      >
        <Form form={form} layout="vertical" initialValues={{ manifest: EXAMPLE_MANIFEST }}>
          <Form.Item name="manifest" label="Manifest（YAML 或 JSON）" rules={[{ required: true }]}>
            <Input.TextArea rows={12} style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          <Form.Item
            label="插件包（.jar，可选但推荐）"
            extra="外部 Java 插件必须有制品才能挂载：上传 jar 后由后端存入 artifact-dir 并自动写入 artifact_uri。"
          >
            <Upload
              accept=".jar"
              maxCount={1}
              fileList={jarList}
              beforeUpload={() => false}
              onChange={({ fileList: fl }) => setJarList(fl.slice(-1))}
            >
              <Button icon={<UploadOutlined />}>选择 jar 文件</Button>
            </Upload>
          </Form.Item>

          <Form.Item
            name="artifactUri"
            label="或填写制品地址（可选）"
            extra="支持 file:/绝对路径 或 http(s):// 直链；与「上传 jar」二选一（上传优先）。留空则只登记元数据。"
          >
            <Input placeholder="https://example.com/my-plugin-1.0.0.jar" allowClear />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
