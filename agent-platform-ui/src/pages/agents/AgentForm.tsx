import { useEffect, useState } from 'react';
import {
  Modal, Form, Input, Select, AutoComplete, Slider, InputNumber, Row, Col, message, Divider, Alert, Checkbox, Typography,
} from 'antd';
import { createAgent, updateAgent } from '../../api/agents';
import { listKbs } from '../../api/knowledgeBases';
import { AgentResponse, KnowledgeBase } from '../../api/types';

const TONES = ['formal', 'friendly', 'humorous', 'concise', 'empathetic'];

// 选中这些具体云服务商时，必须填写 API Key（直连厂商 / 网关均需真实凭证）
const CLOUD_PROVIDERS = ['deepseek', 'openai', 'qwen', 'ernie', 'hunyuan', 'anthropic'];

const PROVIDERS = [
  { value: 'auto', label: 'auto（自动路由）' },
  { value: 'deepseek', label: 'deepseek' },
  { value: 'openai', label: 'openai' },
  { value: 'qwen', label: 'qwen（通义千问）' },
  { value: 'ernie', label: 'ernie（文心一言）' },
  { value: 'hunyuan', label: 'hunyuan（混元）' },
  { value: 'anthropic', label: 'anthropic（Claude / Bailian 应用）' },
  { value: 'local', label: 'local（本地 Mock）' },
];

const PROVIDER_MODELS: Record<string, string[]> = {
  deepseek: ['deepseek-v4-flash', 'deepseek-v4-pro', 'deepseek-chat', 'deepseek-reasoner'],
  openai: ['gpt-4o-mini', 'gpt-4o', 'gpt-4.1', 'o3-mini'],
  qwen: ['qwen-turbo', 'qwen-plus', 'qwen-max'],
  ernie: ['ernie-4.0-turbo-8k', 'ernie-lite-8k'],
  hunyuan: ['hunyuan-lite', 'hunyuan-pro', 'hunyuan-standard'],
  anthropic: ['claude-sonnet-5', 'claude-haiku-4-5'],
  local: ['mock'],
  auto: [],
};

const PROVIDER_BASE_URL: Record<string, string> = {
  deepseek: 'https://api.deepseek.com/v1',
  openai: 'https://api.openai.com/v1',
  qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  ernie: 'https://qianfan.baidubce.com/v2',
  hunyuan: 'https://api.hunyuan.cloud.tencent.com/v1',
  anthropic: 'https://api.anthropic.com',
  local: '',
  auto: '',
};

export default function AgentForm({
  open,
  editing,
  onClose,
  onSaved,
}: {
  open: boolean;
  editing: AgentResponse | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm();
  // 「自定义模型」开关：默认关 → 使用平台默认对话模型（模型设置页配置），
  // 避免每个智能体都重复填一遍 provider/baseUrl/API Key（与模型设置页功能重复）。
  const [customModel, setCustomModel] = useState(false);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const provider = Form.useWatch('provider', form) as string | undefined;
  const modelOptions = (PROVIDER_MODELS[provider ?? ''] ?? []).map((m) => ({ value: m, label: m }));

  // 打开时加载可用知识库（供能力绑定选择）
  useEffect(() => {
    if (!open) return;
    listKbs()
      .then((list) => setKbs(list ?? []))
      .catch(() => setKbs([]));
  }, [open]);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      const hasBinding = !!(editing.modelBinding?.provider || editing.modelBinding?.model
        || editing.modelBinding?.baseUrl || editing.modelBinding?.hasApiKey);
      setCustomModel(hasBinding);
      form.setFieldsValue({
        name: editing.name,
        description: editing.description,
        systemPrompt: editing.systemPrompt,
        tone: editing.persona?.tone,
        role: editing.persona?.role,
        temperature: editing.generationConfig?.temperature ?? 0.7,
        maxTokens: editing.generationConfig?.maxTokens ?? 2048,
        provider: editing.modelBinding?.provider,
        model: editing.modelBinding?.model,
        baseUrl: editing.modelBinding?.baseUrl,
        knowledgeBaseIds: editing.capabilities?.knowledgeBaseIds ?? [],
        // apiKey 绝不在表单回显明文，仅在 placeholder 提示「已配置」
      });
    } else {
      form.resetFields();
      setCustomModel(false);
      form.setFieldsValue({ temperature: 0.7, maxTokens: 2048, knowledgeBaseIds: [] });
    }
  }, [open, editing, form]);

  const submit = async () => {
    const v = await form.validateFields();
    // 后端 body 为 camelCase：systemPrompt / generationConfig / modelBinding
    const model = Array.isArray(v.model) ? (v.model[0] as string) : (v.model as string | undefined);
    const body: Record<string, unknown> = {
      name: v.name,
      description: v.description,
      systemPrompt: v.systemPrompt,
      persona: { tone: v.tone, role: v.role },
      generationConfig: { temperature: v.temperature, maxTokens: v.maxTokens },
      capabilities: {
        // 合并已有能力，避免覆盖 skills/plugins 等其它绑定
        ...(editing?.capabilities ?? {}),
        knowledgeBaseIds: (v.knowledgeBaseIds ?? []).filter(Boolean) as string[],
      },
    };
    // 仅当用户显式勾选「自定义模型」时才提交模型绑定；
    // 否则后端回退到平台默认对话模型（模型设置页），实现“只配一次、处处复用”。
    if (customModel) {
      const strip = (s: unknown) => (typeof s === 'string' ? s.trim() : s);
      const binding: Record<string, unknown> = {
        provider: strip(v.provider) || undefined,
        model: strip(model) || undefined,
        baseUrl: strip(v.baseUrl) || undefined,
        apiKey: strip(v.apiKey) || undefined,
      };
      // 全部为空视为不绑定（同样回退平台默认）
      if (binding.provider || binding.model || binding.baseUrl || binding.apiKey) {
        body.modelBinding = binding;
      }
    }
    try {
      if (editing) await updateAgent(editing.agentId, body as never);
      else await createAgent(body as never);
      message.success(editing ? '已更新' : '已创建');
      onSaved();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  return (
    <Modal
      title={editing ? '编辑智能体' : '新建智能体'}
      open={open}
      onOk={submit}
      onCancel={onClose}
      width={680}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="role" label="角色 persona.role">
              <Input placeholder="如：资深客服专家" />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item
          name="systemPrompt"
          label="系统提示词 systemPrompt"
          rules={[{ required: true, message: '请输入系统提示词' }]}
        >
          <Input.TextArea rows={4} placeholder="告诉智能体它的身份与职责" />
        </Form.Item>

        <Divider orientation="left" plain>
          模型（可选）
        </Divider>
        <Alert
          style={{ marginBottom: 12 }}
          type={customModel ? 'info' : 'success'}
          showIcon
          message={
            customModel
              ? '正在为该智能体单独指定模型与 API Key。'
              : '该智能体将使用「模型设置」页配置的平台默认对话模型；如需单独指定再勾选下方选项。'
          }
        />
        <Checkbox
          checked={customModel}
          onChange={(e) => {
            setCustomModel(e.target.checked);
            if (!e.target.checked) form.setFieldsValue({ provider: undefined, model: undefined, baseUrl: undefined, apiKey: undefined });
          }}
        >
          为该智能体自定义模型 / API Key
        </Checkbox>

        {customModel && (
          <>
            <Row gutter={16} style={{ marginTop: 12 }}>
              <Col span={12}>
                <Form.Item name="provider" label="服务商 provider">
                  <Select
                    allowClear
                    placeholder="留空走平台默认/全局"
                    options={PROVIDERS}
                    onChange={() => form.validateFields(['apiKey']).catch(() => {})}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="model" label="模型 model（支持手输最新模型名）">
                  <AutoComplete
                    allowClear
                    placeholder="选择或手输，如 deepseek-v4-flash"
                    options={modelOptions}
                    filterOption={(input, option) =>
                      ((option?.value as string) ?? '').toLowerCase().includes(input.toLowerCase())
                    }
                  />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="baseUrl" label="接口地址 baseUrl（可选，直连厂商）">
              <Input placeholder={PROVIDER_BASE_URL[provider ?? ''] || '留空使用全局地址'} />
            </Form.Item>
            <Form.Item
              name="apiKey"
              label="API Key（加密落库，接口不回传明文）"
              rules={[
                {
                  validator: (_, val) => {
                    const p = ((form.getFieldValue('provider') ?? '') as string).trim().toLowerCase();
                    const base = ((form.getFieldValue('baseUrl') ?? '') as string).trim();
                    const needKey = CLOUD_PROVIDERS.includes(p) || (!!base && p !== 'local');
                    if (needKey && (val == null || (val as string).trim() === '')) {
                      return Promise.reject(new Error('已选择需直连的服务商/接口地址，请填写 API Key'));
                    }
                    return Promise.resolve();
                  },
                },
              ]}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder={
                  editing?.modelBinding?.hasApiKey
                    ? `已配置（${editing.modelBinding.apiKeyMasked}），留空保持原 Key`
                    : '粘贴厂商 API Key'
                }
              />
            </Form.Item>
          </>
        )}

        <Divider orientation="left" plain>
          能力绑定 capabilities
        </Divider>
        <Form.Item
          name="knowledgeBaseIds"
          label="关联知识库（对话时自动检索并引用）"
          extra="绑定后，对话运行时会对你的问题自动检索所选知识库，并把命中资料注入上下文供智能体引用。"
        >
          <Select
            mode="multiple"
            allowClear
            placeholder={kbs.length > 0 ? '选择要绑定的知识库' : '暂无知识库，请先在知识库页创建并上传文档'}
            options={kbs.map((k) => ({ value: k.kbId, label: k.name }))}
          />
        </Form.Item>
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 12 }}>
          也可不在此绑定：在对话页发送消息时临时指定知识库同样生效（后端支持请求级 rag 配置）。
        </Typography.Text>

        <Divider orientation="left" plain>
          人格 persona
        </Divider>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="tone" label="语气 tone">
              <Select allowClear options={TONES.map((t) => ({ value: t, label: t }))} />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left" plain>
          生成参数 generationConfig
        </Divider>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="temperature" label="temperature">
              <Slider min={0} max={2} step={0.1} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="maxTokens" label="maxTokens">
              <InputNumber style={{ width: '100%' }} min={1} max={32768} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}
