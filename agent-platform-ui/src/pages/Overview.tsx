import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Tag, Alert, List } from 'antd';
import { useNavigate } from 'react-router-dom';
import { http } from '../api/http';
import { listAgents } from '../api/agents';
import { marketplace } from '../api/plugins';

interface Health {
  status: string;
  components: Record<string, { status: string; details?: Record<string, unknown> }>;
}

const color = (s?: string) => (s === 'UP' ? 'green' : s === 'DOWN' ? 'red' : 'orange');

export default function Overview() {
  const [health, setHealth] = useState<Health | null>(null);
  const [agentCount, setAgentCount] = useState<number | null>(null);
  const [pluginCount, setPluginCount] = useState<number | null>(null);
  const nav = useNavigate();

  useEffect(() => {
    http.get<Health>('/actuator/health').then(setHealth).catch(() => setHealth(null));
    listAgents(undefined, undefined, 0, 1).then((r) => setAgentCount(r.total)).catch(() => {});
    marketplace().then((r) => setPluginCount(r.length)).catch(() => {});
  }, []);

  const comps = Object.entries(health?.components ?? {});

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="模型默认走本地 Mock（无外部依赖）。新建/编辑智能体时可绑定 provider / model / baseUrl / API Key 直连真实模型，或在「模型设置」配置嵌入模型；未配置时对话返回确定性示例文本。"
      />
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic title="智能体总数" value={agentCount ?? 0} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="插件数量" value={pluginCount ?? 0} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="服务状态"
              value={health?.status ?? '未知'}
              valueStyle={{ color: health?.status === 'UP' ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
      </Row>

      <Card title="基础设施健康" style={{ marginBottom: 16 }}>
        {comps.length ? (
          <List
            size="small"
            dataSource={comps}
            renderItem={([k, v]) => (
              <List.Item>
                <span>{k}</span>
                <Tag color={color(v.status)}>{v.status}</Tag>
              </List.Item>
            )}
          />
        ) : (
          <Tag color="orange">健康信息不可用（服务未启动？）</Tag>
        )}
      </Card>

      <Row gutter={16}>
        {[
          { title: '智能体管理', desc: 'CRUD、版本快照/发布/回滚/差异', path: '/agents' },
          { title: '对话运行', desc: '与智能体对话（默认 Mock，可直连真实模型，可流式）', path: '/chat' },
          { title: '插件管理', desc: '插件市场、详情、挂载到智能体', path: '/plugins' },
          { title: '运维工具', desc: '运行日志 / 智能诊断 / 提示词优化', path: '/logs' },
        ].map((c) => (
          <Col span={6} key={c.path}>
            <Card hoverable onClick={() => nav(c.path)}>
              <div style={{ fontWeight: 600 }}>{c.title}</div>
              <div style={{ color: '#888', fontSize: 12, marginTop: 6 }}>{c.desc}</div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}