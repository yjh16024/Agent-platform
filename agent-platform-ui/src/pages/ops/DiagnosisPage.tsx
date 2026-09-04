import { useState } from 'react';
import { Card, Form, Input, Select, Button, Tag, Alert, List, Space, message, Typography } from 'antd';
import { BugOutlined } from '@ant-design/icons';
import { analyzeDiagnosis } from '../../api/ops';
import { DiagnosticReport } from '../../api/types';

const CATS = ['plugin', 'llm', 'agent', 'api', 'workflow', 'system'];

const sourceColor = (s?: string) =>
  ({ RULE: 'green', VECTOR: 'blue', LLM: 'purple' }[s ?? ''] ?? 'default');

export default function DiagnosisPage() {
  const [form] = Form.useForm();
  const [result, setResult] = useState<DiagnosticReport | null>(null);
  const [loading, setLoading] = useState(false);

  const run = async () => {
    const v = await form.validateFields();
    setLoading(true);
    try {
      // analyze 的 body 是 snake_case：trace_id / message / category
      setResult(
        await analyzeDiagnosis({
          trace_id: v.trace_id || undefined,
          message: v.message,
          category: v.category,
          fingerprint: v.fingerprint || undefined,
        }),
      );
    } catch (e) {
      message.error((e as Error).message);
      setResult(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="智能诊断" style={{ maxWidth: 860 }}>
      <Form
        form={form}
        layout="inline"
        style={{ marginBottom: 20, rowGap: 12 }}
        initialValues={{ category: 'plugin' }}
      >
        <Form.Item name="category" label="类别" rules={[{ required: true }]}>
          <Select style={{ width: 140 }} options={CATS.map((c) => ({ value: c, label: c }))} />
        </Form.Item>
        <Form.Item name="message" label="错误信息" rules={[{ required: true, message: '请输入错误信息' }]}>
          <Input style={{ width: 320 }} placeholder="如：ClassNotFoundException: com.azure.ai.TtsClient" />
        </Form.Item>
        <Form.Item name="trace_id" label="trace_id">
          <Input style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="fingerprint" label="fingerprint">
          <Input style={{ width: 160 }} />
        </Form.Item>
        <Button type="primary" icon={<BugOutlined />} loading={loading} onClick={run}>
          分析
        </Button>
      </Form>

      {result && (
        <div>
          <Space style={{ marginBottom: 12 }}>
            <Tag color={sourceColor(result.source)}>source={result.source}</Tag>
            {result.severity && <Tag>{result.severity}</Tag>}
            {result.fingerprint && <Tag>指纹 {result.fingerprint}</Tag>}
          </Space>

          {result.rootCause && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={result.rootCause.summary}
              description={result.rootCause.detail}
            />
          )}

          <Typography.Text strong>解决方案（按可信度排序）</Typography.Text>
          <List
            style={{ marginTop: 8 }}
            dataSource={result.solutions ?? []}
            locale={{ emptyText: '无解决方案' }}
            renderItem={(s) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space>
                      {s.title}
                      {s.confidence != null && <Tag color="blue">置信度 {s.confidence}</Tag>}
                      {s.type && <Tag>{s.type}</Tag>}
                    </Space>
                  }
                  description={s.description}
                />
                {s.autoFixCommand && <Typography.Text code>{s.autoFixCommand}</Typography.Text>}
              </List.Item>
            )}
          />
        </div>
      )}
    </Card>
  );
}