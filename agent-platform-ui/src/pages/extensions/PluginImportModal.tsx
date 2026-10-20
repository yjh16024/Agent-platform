import { useState } from 'react';
import { App as AntApp, Form, Input, Modal, Upload, type UploadFile } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { importPlugin, uploadPlugin } from '../../api/plugins';

/** 导入服务端插件（jar）时的 manifest 模板。 */
export const EXAMPLE_MANIFEST = `id: my-hello-plugin
name: 问候插件
version: 1.0.0
description: 示例：命中「你好」时回复预设问候语
author: me
entry:
  type: java
  main_class: com.example.plugin.HelloPlugin
contributes:
  tools: []
  hooks:
    - point: before_llm
permissions: {}
`;

/**
 * 「导入服务端插件」弹窗（2026-10-19，从 PluginMarketplace 抽出）。
 *
 * <p>抽出来的原因：市场页要复用这个表单，但不该把整个插件页当依赖装进来
 * （那个页面还带着"内置/外部分组"的旧结构，而市场页要求"不分来源"）。</p>
 */
export default function PluginImportModal({
  open,
  onClose,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm();
  const [jarList, setJarList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    try {
      const v = await form.validateFields();
      const jar = jarList[0]?.originFileObj as File | undefined;
      if (jar) {
        // 上传 jar：后端落盘并写入 artifact_uri，之后即可挂载到智能体
        await uploadPlugin(v.manifest, jar);
        message.success('插件包已上传并注册');
      } else {
        // 纯文本登记（可带远程/本地制品地址）
        await importPlugin(v.manifest, (v.artifactUri as string) || undefined);
        message.success('插件已导入');
      }
      form.resetFields();
      setJarList([]);
      onClose();
      onDone();
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) {
        // 表单校验失败：让 antd 自己标红
        return;
      }
      message.error((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="导入服务端插件"
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      okText="导入"
      confirmLoading={submitting}
      destroyOnClose
      width={720}
    >
      <Form form={form} layout="vertical" initialValues={{ manifest: EXAMPLE_MANIFEST }}>
        <Form.Item name="manifest" label="Manifest（YAML 或 JSON）" rules={[{ required: true }]}>
          <Input.TextArea rows={12} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="artifactUri" label="制品地址（可选，不上传 jar 时填）">
          <Input placeholder="https://…/plugin.jar 或本地绝对路径" />
        </Form.Item>
        <Form.Item label="插件包（可选，上传 jar 后无需填制品地址）">
          <Upload.Dragger
            accept=".jar"
            maxCount={1}
            fileList={jarList}
            beforeUpload={() => false}
            onChange={({ fileList }) => setJarList(fileList)}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 jar 到这里</p>
          </Upload.Dragger>
        </Form.Item>
      </Form>
    </Modal>
  );
}
