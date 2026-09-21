import { useCallback, useEffect, useState } from 'react';
import { App as AntApp, Button, Card, Col, Empty, Row, Segmented, Space, Statistic, Tag, Typography } from 'antd';
import { ReloadOutlined, TeamOutlined, RobotOutlined, MessageOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { getReport, type NameCount, type ReportView } from '../../api/report';

const { Title, Text } = Typography;

/**
 * 统计报表。
 *
 * <h3>为什么不用图表库</h3>
 * 项目目前没有 echarts / charts 依赖（`ObservabilityPage` 也是纯 antd）。
 * 为了几个条形图引入一整个图表库不划算，这里用 div 宽度画条形 —— 零依赖、加载即渲染，
 * 而且配色跟着主题走（用 `--ap-*` 变量）。
 *
 * <h3>数据来源说明（写在界面上，避免误读）</h3>
 * 「运行日志分布」来自 `log_index` 的**结构化列**（category / level / agentId），
 * 不解析日志正文；「操作统计」来自审计表 —— 这两个都是可靠的结构化数据。
 * 页面上刻意标注了时间窗，因为概览里的"累计值"（审计总量）与"窗口值"（窗口内操作数）口径不同。
 */
export default function ReportsPage() {
  const { message } = AntApp.useApp();
  const [days, setDays] = useState(7);
  const [data, setData] = useState<ReportView>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await getReport(days));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载报表失败');
    } finally {
      setLoading(false);
    }
  }, [days, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const ov = data?.overview;

  return (
    <div>
      <Space align="center" style={{ marginBottom: 4, width: '100%', justifyContent: 'space-between' }}>
        <Title level={4} style={{ margin: 0 }}>
          统计报表
        </Title>
        <Space>
          <Segmented
            value={days}
            onChange={(v) => setDays(v as number)}
            options={[
              { label: '近 7 天', value: 7 },
              { label: '近 30 天', value: 30 },
              { label: '近 90 天', value: 90 },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
        </Space>
      </Space>
      <Text type="secondary">
        概览中的「审计记录总量」是累计值，「窗口内」指标随上方时间窗变化。
      </Text>

      {/* 概览 */}
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={12} lg={6}>
          <Card size="small" loading={loading}>
            <Statistic title="智能体" value={ov?.agents ?? 0} prefix={<RobotOutlined />} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card size="small" loading={loading}>
            <Statistic title="会话" value={ov?.sessions ?? 0} prefix={<MessageOutlined />} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card size="small" loading={loading}>
            <Statistic title="用户" value={ov?.users ?? 0} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card size="small" loading={loading}>
            <Statistic
              title={`审计记录（累计 ${ov?.auditTotal ?? 0}）`}
              value={ov?.auditInWindow ?? 0}
              suffix={`/ ${data?.days ?? days}天`}
              prefix={<SafetyCertificateOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        {/* 错误趋势 */}
        <Col xs={24} lg={14}>
          <Card
            size="small"
            title="错误趋势（ERROR 级日志）"
            loading={loading}
            extra={<Text type="secondary" style={{ fontSize: 12 }}>窗口内日志共 {ov?.logsInWindow ?? 0} 条</Text>}
          >
            {!data || data.errorTrend.length === 0 ? (
              <Empty description="窗口内暂无日志" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <TrendChart points={data.errorTrend} />
            )}
          </Card>
        </Col>

        {/* 操作统计 */}
        <Col xs={24} lg={10}>
          <Card size="small" title="操作统计（来自审计表）" loading={loading}>
            {!data || data.operations.total === 0 ? (
              <Empty description="窗口内暂无操作记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                <Space size={24}>
                  <Statistic title="操作总数" value={data.operations.total} />
                  <Statistic
                    title="失败"
                    value={data.operations.failed}
                    valueStyle={{ color: data.operations.failed > 0 ? '#cf1322' : undefined }}
                  />
                  <Statistic title="失败率" value={data.operations.failureRate} suffix="%" />
                </Space>
                <div>
                  <Text strong style={{ fontSize: 13 }}>动作排行</Text>
                  <BarList items={data.operations.topActions} />
                </div>
                <div>
                  <Text strong style={{ fontSize: 13 }}>操作人排行</Text>
                  <BarList items={data.operations.topUsers} color="#722ed1" />
                </div>
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={8}>
          <Card size="small" title="日志类别分布" loading={loading}>
            {data?.logs.byCategory.length ? (
              <BarList items={data.logs.byCategory} />
            ) : (
              <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="日志级别分布" loading={loading}>
            {data?.logs.byLevel.length ? (
              <BarList items={data.logs.byLevel} color="#fa8c16" />
            ) : (
              <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="智能体活跃度（日志条数）" loading={loading}>
            {data?.logs.byAgent.length ? (
              <BarList items={data.logs.byAgent} color="#13c2c2" />
            ) : (
              <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

// ---------------------------------------------------------------- 轻量图形组件

/** 横向条形列表（纯 div，无图表库依赖）。 */
function BarList({ items, color }: { items: NameCount[]; color?: string }) {
  const max = Math.max(1, ...items.map((i) => i.count));
  return (
    <div style={{ marginTop: 8 }}>
      {items.map((i) => (
        <div key={i.name} style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
            <span title={i.name} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {i.name}
            </span>
            <span style={{ color: '#8c8c8c', marginLeft: 8 }}>{i.count}</span>
          </div>
          <div style={{ height: 6, background: 'var(--ap-bg-container, #f0f0f0)', borderRadius: 3, marginTop: 2 }}>
            <div
              style={{
                width: `${(i.count / max) * 100}%`,
                height: '100%',
                background: color ?? '#2563eb',
                borderRadius: 3,
                transition: 'width .3s',
              }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * 错误趋势（纵向柱），同样不依赖图表库。
 *
 * <p>柱高按窗口内最大值归一化；全为 0 时退化成一条基线而不是"没有图"，
 * 因为"这几天确实没出错"本身就是有用的信息。</p>
 */
function TrendChart({ points }: { points: { day: string; count: number }[] }) {
  const max = Math.max(1, ...points.map((p) => p.count));
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 4, height: 130 }}>
        {points.map((p) => (
          <div key={p.day} style={{ flex: 1, textAlign: 'center' }} title={`${p.day}: ${p.count}`}>
            <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 2 }}>{p.count > 0 ? p.count : ''}</div>
            <div
              style={{
                height: `${Math.max(2, (p.count / max) * 100)}px`,
                background: p.count > 0 ? '#ff4d4f' : '#d9d9d9',
                borderRadius: 3,
              }}
            />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
        {points.map((p) => (
          <div key={p.day} style={{ flex: 1, textAlign: 'center', fontSize: 10, color: '#8c8c8c' }}>
            {p.day.slice(5)}
          </div>
        ))}
      </div>
    </div>
  );
}
