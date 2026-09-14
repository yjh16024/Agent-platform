import { useCallback, useEffect, useState } from 'react';
import { Modal, List, Typography, Tag, Button, message, Input, Space, Empty } from 'antd';
import { CloudDownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { mcpMarket, registerMcp, type McpMarketItem } from '../../api/tools';

/**
 * MCP 市场：直连**官方 MCP Registry**（registry.modelcontextprotocol.io），
 * 浏览支持远程 HTTP 的 MCP 服务器并一键注册为平台工具。
 *
 * 只展示「远程 HTTP（streamable-http）」型：注册中心里大量服务器是 stdio（需 npx/uvx 本地拉起进程），
 * 平台侧无法直接运行它们。注册后可用 {@code POST /api/v1/tools/{name}/invoke} 调试。
 */
export default function McpMarketModal({
  open,
  onClose,
  onRegistered,
}: {
  open: boolean;
  onClose: () => void;
  onRegistered?: () => void;
}) {
  const [list, setList] = useState<McpMarketItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState('');
  /** 每项的 API Key（可选），key 为 server name */
  const [keys, setKeys] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async (query?: string) => {
    setLoading(true);
    try {
      setList(await mcpMarket(query, 50));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      load();
    }
  }, [open, load]);

  const register = async (item: McpMarketItem) => {
    const key = (keys[item.name] ?? '').trim();
    setBusy(item.name);
    try {
      await registerMcp({
        server_url: item.server_url,
        ...(key ? { api_key: key } : {}),
      });
      message.success(`已注册 MCP：${item.title || item.name}`);
      onRegistered?.();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <Modal
      title="MCP 市场 · 官方 MCP Registry"
      open={open}
      onCancel={onClose}
      footer={null}
      width={900}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          placeholder="搜索（如 github / database / weather）"
          allowClear
          style={{ width: 300 }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onSearch={(v) => load(v)}
        />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(q)}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          仅展示「远程 HTTP」型服务器（stdio 型需本地运行，平台无法直挂）
        </Typography.Text>
      </Space>

      {!loading && list.length === 0 && (
        <Empty description="没有可用服务器（请确认服务能访问 registry.modelcontextprotocol.io）" />
      )}

      <List
        loading={loading}
        dataSource={list}
        style={{ maxHeight: 540, overflow: 'auto' }}
        renderItem={(item) => (
          <List.Item key={item.name}>
            <List.Item.Meta
              title={
                <Space wrap>
                  <span>{item.title || item.name}</span>
                  <Tag>{item.name}</Tag>
                  {item.version ? <Tag color="blue">v{item.version}</Tag> : null}
                </Space>
              }
              description={
                <>
                  <Typography.Paragraph
                    type="secondary"
                    style={{ fontSize: 12, marginBottom: 4 }}
                    ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
                  >
                    {item.description || '（无描述）'}
                  </Typography.Paragraph>
                  <Typography.Text code style={{ fontSize: 11 }}>
                    {item.server_url}
                  </Typography.Text>
                </>
              }
            />
            <Space direction="vertical" style={{ marginLeft: 12, minWidth: 240 }}>
              <Input.Password
                size="small"
                placeholder="API Key（可选，若该服务需要）"
                value={keys[item.name] ?? ''}
                onChange={(e) => setKeys((prev) => ({ ...prev, [item.name]: e.target.value }))}
              />
              <Button
                type="primary"
                size="small"
                icon={<CloudDownloadOutlined />}
                loading={busy === item.name}
                onClick={() => register(item)}
              >
                注册为工具
              </Button>
            </Space>
          </List.Item>
        )}
      />
    </Modal>
  );
}
