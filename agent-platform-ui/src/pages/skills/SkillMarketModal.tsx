import { useCallback, useEffect, useState } from 'react';
import { Modal, List, Typography, Tag, Button, message, Input, Space, Empty } from 'antd';
import { CloudDownloadOutlined, SyncOutlined } from '@ant-design/icons';
import { marketSkills, installMarketSkill, type MarketSkill } from '../../api/skills';

/**
 * 技能市场：直连 Anthropic 官方 Agent Skills 仓库（github.com/anthropics/skills），
 * 浏览并一键安装技能 —— 后端会把技能目录下载到本地 skills 目录并自动同步入库。
 *
 * 因为官方仓库采用 Agent Skills 开放标准（每技能一个文件夹 + SKILL.md），
 * 与本站目录格式完全一致，安装后即可直接挂载给智能体使用。
 */
export default function SkillMarketModal({
  open,
  onClose,
  onInstalled,
}: {
  open: boolean;
  onClose: () => void;
  onInstalled?: () => void;
}) {
  const [list, setList] = useState<MarketSkill[]>([]);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null);
  const [kw, setKw] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await marketSkills());
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

  const install = async (name: string) => {
    setInstalling(name);
    try {
      const r = await installMarketSkill(name);
      message.success(`已安装「${name}」（${r.files} 个文件）并同步入库`);
      setList((prev) => prev.map((s) => (s.name === name ? { ...s, installed: true } : s)));
      onInstalled?.();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setInstalling(null);
    }
  };

  const k = kw.trim().toLowerCase();
  const filtered = !k
    ? list
    : list.filter(
        (s) =>
          s.name.toLowerCase().includes(k) ||
          (s.title ?? '').toLowerCase().includes(k) ||
          (s.description ?? '').toLowerCase().includes(k),
      );

  return (
    <Modal
      title="技能市场 · Anthropic 官方 Agent Skills 仓库"
      open={open}
      onCancel={onClose}
      footer={null}
      width={880}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          placeholder="搜索技能名或描述"
          allowClear
          style={{ width: 300 }}
          value={kw}
          onChange={(e) => setKw(e.target.value)}
        />
        <Button icon={<SyncOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          来源 github.com/anthropics/skills（开放标准，目录格式与本站一致，安装即用）
        </Typography.Text>
      </Space>

      {!loading && filtered.length === 0 && (
        <Empty description="没有可用技能（请确认服务能访问 GitHub / jsDelivr）" />
      )}

      <List
        loading={loading}
        dataSource={filtered}
        style={{ maxHeight: 540, overflow: 'auto' }}
        renderItem={(s) => (
          <List.Item
            actions={[
              s.installed ? (
                <Tag color="green" key="ok">
                  已安装
                </Tag>
              ) : (
                <Button
                  key="install"
                  type="primary"
                  size="small"
                  icon={<CloudDownloadOutlined />}
                  loading={installing === s.name}
                  onClick={() => install(s.name)}
                >
                  安装
                </Button>
              ),
            ]}
          >
            <List.Item.Meta
              title={
                <Space>
                  <span>{s.title || s.name}</span>
                  <Tag>{s.name}</Tag>
                </Space>
              }
              description={
                <Typography.Paragraph
                  type="secondary"
                  style={{ fontSize: 12, marginBottom: 0 }}
                  ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
                >
                  {s.description || '（无描述）'}
                </Typography.Paragraph>
              }
            />
          </List.Item>
        )}
      />
    </Modal>
  );
}
