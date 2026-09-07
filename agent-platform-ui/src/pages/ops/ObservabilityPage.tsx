import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Table, Card, Row, Col, Statistic, Tag, Select, Space, Button, Drawer, List, Typography, message, Empty,
} from 'antd';
import { ReloadOutlined, EyeOutlined } from '@ant-design/icons';
import { listAgents } from '../../api/agents';
import { listRunSummaries, runTimeline, RunSummary, TimelineEvent } from '../../api/observability';
import { AgentResponse } from '../../api/types';

const statusTag = (s?: string, short?: boolean) => {
  if (s === 'failed') return <Tag color="red">失败</Tag>;
  if (short) return <Tag color="gold">短路</Tag>;
  return <Tag color="green">成功</Tag>;
};

const categoryColor: Record<string, string> = {
  agent: 'blue', llm: 'purple', tool: 'cyan', rag: 'geekblue',
  plugin: 'magenta', skill: 'orange', workflow: 'gold', api: 'volcano', system: 'default',
};

export default function ObservabilityPage() {
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [agentId, setAgentId] = useState<string | undefined>();
  const [rows, setRows] = useState<RunSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [tlLoading, setTlLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listRunSummaries(agentId || undefined);
      setRows(r ?? []);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    listAgents(undefined, undefined, 0, 200)
      .then((r) => setAgents(r.items ?? []))
      .catch(() => { /* 智能体列表加载失败不阻断观测页 */ });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openTimeline = useCallback(async (id: string) => {
    setTraceId(id);
    setTlLoading(true);
    try {
      setTimeline((await runTimeline(id)) ?? []);
    } catch (e) {
      message.error((e as Error).message);
      setTimeline([]);
    } finally {
      setTlLoading(false);
    }
  }, []);

  const metrics = useMemo(() => {
    const m = { total: rows.length, failed: 0, tools: 0, llm: 0, tokens: 0, avgMs: 0 };
    let sumMs = 0;
    for (const r of rows) {
      if (r.status === 'failed') m.failed++;
      m.tools += r.toolCalls ?? 0;
      m.llm += r.llmCalls ?? 0;
      m.tokens += r.tokens ?? 0;
      sumMs += r.latencyMs ?? 0;
    }
    m.avgMs = rows.length ? Math.round(sumMs / rows.length) : 0;
    return m;
  }, [rows]);

  const columns = [
    {
      title: '开始时间', dataIndex: 'startedAt', width: 190,
      render: (v?: string) => (v ? new Date(v).toLocaleString() : '—'),
    },
    {
      title: '状态', width: 100,
      render: (_: unknown, r: RunSummary) => statusTag(r.status, r.shortCircuit),
    },
    { title: 'traceId', dataIndex: 'traceId', width: 150, ellipsis: true },
    { title: '智能体', dataIndex: 'agentId', width: 150, ellipsis: true },
    { title: '模型', dataIndex: 'model', width: 140, ellipsis: true },
    {
      title: '耗时', dataIndex: 'latencyMs', width: 90,
      render: (v?: number) => (v == null ? '—' : `${v} ms`),
    },
    {
      title: 'LLM 调用', dataIndex: 'llmCalls', width: 90,
      render: (v?: number) => v ?? 0,
    },
    {
      title: '工具 / 失败', width: 110,
      render: (_: unknown, r: RunSummary) => (
        <Space size={4}>
          <span>{r.toolCalls ?? 0}</span>
          {(r.toolFailures ?? 0) > 0 && <Tag color="red">{(r.toolFailures ?? 0)} 失败</Tag>}
        </Space>
      ),
    },
    { title: 'RAG', dataIndex: 'ragCalls', width: 70, render: (v?: number) => v ?? 0 },
    { title: 'tokens', dataIndex: 'tokens', width: 90, render: (v?: number) => v ?? 0 },
    {
      title: '操作', width: 110,
      render: (_: unknown, r: RunSummary) => (
        <Button
          size="small"
          icon={<EyeOutlined />}
          onClick={() => r.traceId && openTimeline(r.traceId)}
        >
          时间线
        </Button>
      ),
    },
  ];

  return (
    <div>
      <Card title="智能体运行可观测性" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            allowClear
            placeholder="按智能体过滤"
            style={{ width: 220 }}
            value={agentId}
            onChange={setAgentId}
            options={agents.map((a) => ({ value: a.agentId, label: a.name }))}
          />
          <Button type="primary" icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Typography.Text type="secondary">
            基于运行日志（log_index）应用内聚合，无外部存储依赖
          </Typography.Text>
        </Space>
        <Row gutter={16} style={{ marginTop: 16 }}>
          <Col span={5}><Statistic title="运行总数" value={metrics.total} /></Col>
          <Col span={5}><Statistic title="失败" value={metrics.failed} valueStyle={{ color: metrics.failed ? '#cf1322' : undefined }} /></Col>
          <Col span={4}><Statistic title="工具调用" value={metrics.tools} /></Col>
          <Col span={5}><Statistic title="平均耗时 (ms)" value={metrics.avgMs} /></Col>
          <Col span={5}><Statistic title="累计 tokens" value={metrics.tokens} /></Col>
        </Row>
      </Card>

      <Table
        rowKey={(r) => r.traceId ?? ''}
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        size="small"
        locale={{ emptyText: '暂无运行记录（在对话页发送消息后产生）' }}
      />

      <Drawer
        title={`运行时间线：${traceId ?? ''}`}
        open={traceId !== null}
        onClose={() => setTraceId(null)}
        width={760}
      >
        {tlLoading ? (
          <Typography.Text type="secondary">加载中…</Typography.Text>
        ) : timeline.length === 0 ? (
          <Empty description="无日志" />
        ) : (
          <List
            size="small"
            dataSource={timeline}
            renderItem={(e, i) => (
              <List.Item key={i} style={{ alignItems: 'flex-start' }}>
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <Tag color={categoryColor[e.category ?? ''] ?? 'default'}>{e.category}</Tag>
                      <Tag>{e.level}</Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {e.time ? new Date(e.time).toLocaleTimeString() : ''}
                      </Typography.Text>
                    </Space>
                  }
                  description={<Typography.Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{e.message}</Typography.Text>}
                />
              </List.Item>
            )}
          />
        )}
      </Drawer>
    </div>
  );
}
