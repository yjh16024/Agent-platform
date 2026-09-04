import { useState } from 'react';
import { Alert, Card, Form, Input, Select, Switch, Button, Space, Row, Col, Progress, List, Tag, message, Typography } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { optimizePrompt, scorePrompt } from '../../api/ops';
import { OptimizationResult, ScoreReport } from '../../api/types';

const USE_CASES = ['customer_service', 'code_assistant', 'marketing', 'general'];
const TONES = ['formal', 'friendly', 'humorous', 'concise', 'empathetic'];

export default function PromptOptimizePage() {
  const [form] = Form.useForm();
  const [result, setResult] = useState<OptimizationResult | null>(null);
  const [score, setScore] = useState<ScoreReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [scorePromptText, setScorePromptText] = useState('');

  const run = async () => {
    const v = await form.validateFields();
    setLoading(true);
    try {
      // optimize 的 body 是 snake_case：raw_prompt / context / options
      const r = await optimizePrompt({
        raw_prompt: v.raw_prompt,
        context: { use_case: v.use_case, persona: { tone: v.tone } },
        options: { generate_examples: !!v.generate_examples, inject_cot: !!v.inject_cot },
      });
      setResult(r);
    } catch (e) {
      message.error((e as Error).message);
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  const doScore = async () => {
    if (!scorePromptText.trim()) return;
    try {
      setScore(await scorePrompt(scorePromptText));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const dims: [keyof ScoreReport, string][] = [
    ['clarity', '清晰度'],
    ['completeness', '完整性'],
    ['structure', '结构'],
    ['constraints', '约束'],
    ['examples', '示例'],
    ['modelAlignment', '模型适配'],
  ];

  return (
    <Row gutter={16}>
      <Col span={10}>
        <Card title="原始提示词">
          <Form form={form} layout="vertical">
            <Form.Item name="raw_prompt" rules={[{ required: true, message: '请输入原始提示词' }]}>
              <Input.TextArea rows={8} placeholder="你是一个客服，帮我回答用户问题" />
            </Form.Item>
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item name="use_case" label="场景 use_case">
                  <Select options={USE_CASES.map((u) => ({ value: u, label: u }))} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="tone" label="语气 persona.tone">
                  <Select allowClear options={TONES.map((t) => ({ value: t, label: t }))} />
                </Form.Item>
              </Col>
            </Row>
            <Space size={24}>
              <Form.Item name="generate_examples" label="生成示例" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="inject_cot" label="注入思维链" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Space>
            <Button type="primary" icon={<ThunderboltOutlined />} loading={loading} onClick={run}>
              优化
            </Button>
          </Form>
        </Card>

        <Card title="单独评分" style={{ marginTop: 16 }}>
          <Input.TextArea
            rows={3}
            value={scorePromptText}
            onChange={(e) => setScorePromptText(e.target.value)}
            placeholder="粘贴提示词"
          />
          <Button style={{ marginTop: 8 }} onClick={doScore}>
            评分
          </Button>
          {score && (
            <div style={{ marginTop: 12 }}>
              <Progress type="circle" percent={Math.round((score.overall ?? 0) * 100)} size={80} />
              <Typography.Text type="secondary" style={{ marginLeft: 12 }}>
                综合得分（6 维）
              </Typography.Text>
            </div>
          )}
        </Card>
      </Col>

      <Col span={14}>
        <Card title="优化结果">
          {result ? (
            <div>
              <Typography.Paragraph>
                <pre style={{ background: '#f5f5f5', padding: 12, whiteSpace: 'pre-wrap' }}>
                  {result.optimizedPrompt}
                </pre>
              </Typography.Paragraph>

              {result.score && (
                <Card size="small" title="六维评分" style={{ marginBottom: 16 }}>
                  <Row gutter={[16, 12]}>
                    {dims.map(([k, label]) => (
                      <Col span={12} key={k}>
                        <div style={{ fontSize: 12, color: '#666' }}>{label}</div>
                        <Progress percent={Math.round(((result.score?.[k] as number) ?? 0) * 100)} size="small" />
                      </Col>
                    ))}
                  </Row>
                </Card>
              )}

              {result.diff && result.diff.length > 0 && (
                <div style={{ marginBottom: 16 }}>
                  <Typography.Text strong>Diff</Typography.Text>
                  <List
                    size="small"
                    dataSource={result.diff}
                    renderItem={(d) => (
                      <List.Item>
                        <Space>
                          <Tag color={{ added: 'green', removed: 'red', modified: 'orange' }[d.type ?? ''] ?? 'default'}>
                            {d.type}
                          </Tag>
                          {d.section && <span style={{ color: '#888' }}>{d.section}</span>}
                        </Space>
                      </List.Item>
                    )}
                  />
                </div>
              )}

              {result.suggestions && result.suggestions.length > 0 && (
                <Alert type="info" showIcon message="建议" description={result.suggestions.join('；')} />
              )}
            </div>
          ) : (
            <Typography.Text type="secondary">在左侧输入提示词并点击「优化」。</Typography.Text>
          )}
        </Card>
      </Col>
    </Row>
  );
}