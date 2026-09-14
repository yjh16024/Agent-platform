import { useCallback, useEffect, useState } from 'react';
import { Modal, List, Typography, Tag, Button, message, Input, Space, Empty } from 'antd';
import { CloudDownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { toolMarket, installToolFromMarket, type ToolMarketItem } from '../../api/tools';

/**
 * HTTP 工具市场：内置精选的**免 Key 公开 API**（GitHub 搜索 / 汇率 / IP 归属 / 二维码…），
 * 一键注册为平台 HTTP 工具，注册后可直接在工具调试里调用，也可让智能体通过 function calling 使用。
 *
 * 与 MCP 市场互补：MCP 接远端能力（需服务端支持 Streamable HTTP），本市场是"拿到就能调"的轻量接口。
 */
export default function ToolMarketModal({
  open,
  onClose,
  onInstalled,
}: {
  open: boolean;
  onClose: () => void;
  onInstalled?: () => void;
}) {
  const [list, setList] = useState<ToolMarketItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [kw, setKw] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await toolMarket());
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

  const install = async (item: ToolMarketItem) => {
    setBusy(item.id);
    try {
      await installToolFromMarket(item.id);
      message.success(`已注册工具：${item.name}`);
      setList((prev) => prev.map((t) => (t.id === item.id ? { ...t, installed: true } : t)));
      onInstalled?.();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const k = kw.trim().toLowerCase();
  const filtered = !k
    ? list
    : list.filter(
        (t) =>
          t.name.toLowerCase().includes(k) ||
          (t.title ?? '').toLowerCase().includes(k) ||
          (t.description ?? '').toLowerCase().includes(k),
      );

  return (
    <Modal
      title="HTTP 工具市场 · 免 Key 公开 API"
      open={open}
      onCancel={onClose}
      footer={null}
      width={880}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          placeholder="搜索工具名或描述"
          allowClear
          style={{ width: 280 }}
          value={kw}
          onChange={(e) => setKw(e.target.value)}
        />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          全部为无需 API Key 的公开接口，注册后即可调试与调用
        </Typography.Text>
      </Space>

      {!loading && filtered.length === 0 && <Empty description="没有匹配的条目" />}

      <List
        loading={loading}
        dataSource={filtered}
        style={{ maxHeight: 540, overflow: 'auto' }}
        renderItem={(item) => (
          <List.Item
            actions={[
              item.installed ? (
                <Tag color="green" key="ok">
                  已注册
                </Tag>
              ) : (
                <Button
                  key="install"
                  type="primary"
                  size="small"
                  icon={<CloudDownloadOutlined />}
                  loading={busy === item.id}
                  onClick={() => install(item)}
                >
                  注册为工具
                </Button>
              ),
            ]}
          >
            <List.Item.Meta
              title={
                <Space wrap>
                  <span>{item.title || item.name}</span>
                  <Tag>{item.name}</Tag>
                  {(item.tags ?? []).map((t) => (
                    <Tag key={t} color="blue">
                      {t}
                    </Tag>
                  ))}
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
                    {item.method || 'GET'} {item.endpoint}
                  </Typography.Text>
                </>
              }
            />
          </List.Item>
        )}
      />
    </Modal>
  );
}
