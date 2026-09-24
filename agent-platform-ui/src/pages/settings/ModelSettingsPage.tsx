import { useEffect, useState } from 'react';
import {
  Card, Form, Input, Select, AutoComplete, Button, Typography, Alert, message, Row, Col,
} from 'antd';
import { getModelConfig, saveEmbeddingBinding, saveChatBinding } from '../../api/modelConfig';
import { useDict } from '../../dict/store';
import { DICT } from '../../api/dict';

const { Title, Text } = Typography;

/*
 * 服务商清单原先在这里各写一份（EMBEDDING_PROVIDERS / CHAT_PROVIDERS）。
 * 现在改为从数据字典取 —— 注意是**两个**字典（对话 / 嵌入），
 * 因为两侧的可选集合本来就不同：嵌入侧有 siliconflow / zhipu 但没有 deepseek / anthropic。
 */

const EMBEDDING_MODELS = [
  'BAAI/bge-m3', 'BAAI/bge-large-zh-v1.5', 'text-embedding-3-small', 'text-embedding-3-large', 'text-embedding-ada-002',
];

const CHAT_MODEL_SUGGEST: Record<string, string[]> = {
  deepseek: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-v4-flash', 'deepseek-v4-pro'],
  openai: ['gpt-4o-mini', 'gpt-4o', 'gpt-4.1'],
  qwen: ['qwen-plus', 'qwen-turbo', 'qwen-max'],
  ernie: ['ernie-4.0-turbo-8k', 'ernie-lite-8k'],
  hunyuan: ['hunyuan-lite', 'hunyuan-pro'],
  anthropic: ['claude-sonnet-5', 'claude-haiku-4-5'],
  local: ['mock'],
};

const BASE_URL: Record<string, string> = {
  deepseek: 'https://api.deepseek.com/v1',
  openai: 'https://api.openai.com/v1',
  qwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  ernie: 'https://qianfan.baidubce.com/v2',
  hunyuan: 'https://api.hunyuan.cloud.tencent.com/v1',
  anthropic: 'https://api.anthropic.com',
  siliconflow: 'https://api.siliconflow.cn/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  local: '',
};

export default function ModelSettingsPage() {
  const [embedForm] = Form.useForm();
  const [chatForm] = Form.useForm();
  const [loading, setLoading] = useState(false);
  /** 服务商清单来自数据字典；**两侧是两个不同的字典**，见文件上方注释。 */
  const embedProviderOptions = useDict(DICT.MODEL_PROVIDER_EMBEDDING);
  const chatProviderOptions = useDict(DICT.MODEL_PROVIDER_CHAT);
  const [savingEmb, setSavingEmb] = useState(false);
  const [savingChat, setSavingChat] = useState(false);
  const [embMasked, setEmbMasked] = useState('');
  const [embHasKey, setEmbHasKey] = useState(false);
  const [chatMasked, setChatMasked] = useState('');
  const [chatHasKey, setChatHasKey] = useState(false);

  const embedProvider = Form.useWatch('provider', embedForm) as string | undefined;
  const chatProvider = Form.useWatch('provider', chatForm) as string | undefined;
  const chatModelOptions = (CHAT_MODEL_SUGGEST[chatProvider ?? ''] ?? []).map((m) => ({ value: m, label: m }));

  const load = async () => {
    setLoading(true);
    try {
      const cfg = await getModelConfig();
      const e = cfg?.embedding;
      embedForm.setFieldsValue({ provider: e?.provider, model: e?.model, baseUrl: e?.baseUrl });
      setEmbMasked(e?.apiKeyMasked ?? '');
      setEmbHasKey(!!e?.hasApiKey);

      const c = cfg?.chat;
      chatForm.setFieldsValue({ provider: c?.provider, model: c?.model, baseUrl: c?.baseUrl });
      setChatMasked(c?.apiKeyMasked ?? '');
      setChatHasKey(!!c?.hasApiKey);
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const asBinding = (v: Record<string, unknown>): {
    provider?: string; model?: string; baseUrl?: string; apiKey?: string;
  } => ({
    provider: v.provider as string | undefined,
    model: (Array.isArray(v.model) ? v.model[0] : v.model) as string | undefined,
    baseUrl: typeof v.baseUrl === 'string' && v.baseUrl.trim() ? v.baseUrl.trim() : undefined,
    apiKey: typeof v.apiKey === 'string' && v.apiKey.trim() ? v.apiKey.trim() : undefined,
  });

  const submitEmbedding = async () => {
    const v = await embedForm.validateFields();
    setSavingEmb(true);
    try {
      await saveEmbeddingBinding(asBinding(v));
      message.success('嵌入模型绑定已保存');
      embedForm.setFieldsValue({ apiKey: undefined });
      await load();
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setSavingEmb(false);
    }
  };

  const submitChat = async () => {
    const v = await chatForm.validateFields();
    setSavingChat(true);
    try {
      await saveChatBinding(asBinding(v));
      message.success('默认对话模型已保存（新建智能体自动回退使用）');
      chatForm.setFieldsValue({ apiKey: undefined });
      await load();
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setSavingChat(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginTop: 0 }}>模型设置</Title>
      <Text type="secondary">
        平台级默认绑定：① 嵌入模型用于 RAG 向量化；② 默认对话模型供「未单独配置模型的智能体」回退使用。
        两者都是可选的，填写 API Key 即直连云端（AES-GCM 加密落库、接口只回掩码）。
      </Text>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 12 }}
        message="仅需配置一次。新建智能体时若未勾选「自定义模型」，会自动使用这里的默认对话模型；切换嵌入模型会改变向量维度（Milvus 自动重建，旧文档需重新上传）。"
      />

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="嵌入模型（RAG 向量化）" loading={loading}>
            <Form form={embedForm} layout="vertical">
              <Form.Item name="provider" label="服务商 provider">
                <Select allowClear placeholder="留空走本地 Mock" options={embedProviderOptions} />
              </Form.Item>
              <Form.Item name="model" label="模型 model（支持手输最新模型名）">
                <AutoComplete
                  allowClear
                  placeholder="选择或手输，如 BAAI/bge-m3"
                  options={EMBEDDING_MODELS.map((m) => ({ value: m, label: m }))}
                  filterOption={(input, option) => ((option?.value as string) ?? '').toLowerCase().includes(input.toLowerCase())}
                />
              </Form.Item>
              <Form.Item name="baseUrl" label="接口地址 baseUrl">
                <Input placeholder={BASE_URL[embedProvider ?? ''] || '如 https://api.siliconflow.cn/v1'} />
              </Form.Item>
              <Form.Item
                name="apiKey"
                label="API Key"
                rules={[{ validator: (_, v) => {
                  const base = ((embedForm.getFieldValue('baseUrl') ?? '') as string).trim();
                  if (base && (v == null || (v as string).trim() === '')) return Promise.reject(new Error('已填接口地址，请填写对应 API Key（直连必填）'));
                  return Promise.resolve();
                } }]}
              >
                <Input.Password autoComplete="new-password"
                  placeholder={embHasKey ? `已配置（${embMasked}），留空保持原 Key` : '粘贴厂商 API Key'} />
              </Form.Item>
              <Button type="primary" loading={savingEmb} onClick={submitEmbedding}>保存嵌入配置</Button>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="默认对话模型（智能体未单独配置时回退）" loading={loading}>
            <Form form={chatForm} layout="vertical">
              <Form.Item name="provider" label="服务商 provider">
                <Select allowClear placeholder="留空使用全局 LiteLLM / Mock" options={chatProviderOptions} />
              </Form.Item>
              <Form.Item name="model" label="模型 model（支持手输）">
                <AutoComplete
                  allowClear
                  placeholder="选择或手输，如 deepseek-chat"
                  options={chatModelOptions}
                  filterOption={(input, option) => ((option?.value as string) ?? '').toLowerCase().includes(input.toLowerCase())}
                />
              </Form.Item>
              <Form.Item name="baseUrl" label="接口地址 baseUrl">
                <Input placeholder={BASE_URL[chatProvider ?? ''] || '如 https://api.deepseek.com/v1'} />
              </Form.Item>
              <Form.Item
                name="apiKey"
                label="API Key"
                rules={[{ validator: (_, v) => {
                  const base = ((chatForm.getFieldValue('baseUrl') ?? '') as string).trim();
                  if (base && (v == null || (v as string).trim() === '')) return Promise.reject(new Error('已填接口地址，请填写对应 API Key（直连必填）'));
                  return Promise.resolve();
                } }]}
              >
                <Input.Password autoComplete="new-password"
                  placeholder={chatHasKey ? `已配置（${chatMasked}），留空保持原 Key` : '粘贴厂商 API Key'} />
              </Form.Item>
              <Button type="primary" loading={savingChat} onClick={submitChat}>保存默认对话模型</Button>
            </Form>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
