import { useCallback, useEffect, useState } from 'react';
import {
  Modal,
  List,
  Typography,
  Tag,
  Button,
  message,
  Input,
  Space,
  Empty,
  Alert,
  Tooltip,
  Divider,
} from 'antd';
import {
  CloudDownloadOutlined,
  SyncOutlined,
  LinkOutlined,
  ApiOutlined,
  EyeOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import {
  marketSkills,
  installMarketSkill,
  scanSkillRepo,
  installSkillFromRepo,
  readSkillRepoFile,
  diagnoseSkillChannels,
  type MarketSkill,
  type ChannelProbe,
} from '../../api/skills';

const OFFICIAL_REPO = 'anthropics/skills';

/**
 * 技能市场。
 *
 * 支持两种用法：
 * 1. **官方市场** —— Anthropic 官方 Agent Skills 仓库，一键安装；
 * 2. **读取网址** —— 把你在别处找到的技能库地址（GitHub 仓库 / 子目录 / 文件链接，
 *    甚至套了加速前缀的地址）直接粘进来，先列出并预览其中的技能，再按需安装。
 *
 * 后端取件走「jsDelivr 数据接口 → GitHub 加速代理 → 直连」多通道失败切换，
 * 因此国内网络下也能用；点「网络诊断」可以看到每条通道当前通不通。
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
  /** official = 官方仓库；repo = 用户粘贴的地址 */
  const [mode, setMode] = useState<'official' | 'repo'>('official');
  const [list, setList] = useState<MarketSkill[]>([]);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null);
  const [kw, setKw] = useState('');
  const [url, setUrl] = useState('');
  const [repoCtx, setRepoCtx] = useState<{ repo: string; branch: string; channel?: string } | null>(null);
  const [probes, setProbes] = useState<ChannelProbe[] | null>(null);
  const [probing, setProbing] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [preview, setPreview] = useState<
    { title: string; path: string; content: string; size: number; truncated: boolean } | null
  >(null);

  const loadOfficial = useCallback(async () => {
    setLoading(true);
    setMode('official');
    setRepoCtx(null);
    try {
      setList(await marketSkills());
    } catch (e) {
      setList([]);
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      loadOfficial();
    }
  }, [open, loadOfficial]);

  const scan = async () => {
    const u = url.trim();
    if (!u) {
      message.warning('请先粘贴技能库地址');
      return;
    }
    setLoading(true);
    try {
      const r = await scanSkillRepo(u, true);
      setList(r.skills);
      setMode('repo');
      setRepoCtx({ repo: r.repo, branch: r.branch, channel: r.channel });
      if (r.skills.length === 0) {
        message.warning(
          `在 ${r.repo}@${r.branch} 里没找到含 SKILL.md 的技能${r.subPath ? `（限定目录：${r.subPath}）` : ''}`,
        );
      } else {
        message.success(`在 ${r.repo}@${r.branch} 找到 ${r.skills.length} 个技能`);
      }
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const diagnose = async () => {
    setProbing(true);
    try {
      const r = await diagnoseSkillChannels();
      setProbes(r);
      const okCount = r.filter((p) => p.ok).length;
      if (okCount === 0) {
        message.error('所有取件通道都不通，请检查网络或代理设置');
      } else {
        message.success(`${okCount} / ${r.length} 条通道可用`);
      }
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setProbing(false);
    }
  };

  const install = async (s: MarketSkill) => {
    setInstalling(s.name);
    try {
      if (mode === 'official') {
        const r = await installMarketSkill(s.name);
        message.success(`已安装「${s.name}」（${r.files} 个文件）并同步入库`);
      } else {
        const r = await installSkillFromRepo(url.trim(), s.path ?? '', s.name);
        message.success(`已安装「${r.skill}」（${r.files} 个文件）并同步入库`);
      }
      setList((prev) => prev.map((x) => (x.name === s.name ? { ...x, installed: true } : x)));
      onInstalled?.();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setInstalling(null);
    }
  };

  const view = async (s: MarketSkill) => {
    const source = mode === 'official' ? OFFICIAL_REPO : url.trim();
    const path = s.skillFile ?? (s.path ? `${s.path}/SKILL.md` : 'SKILL.md');
    setPreviewLoading(true);
    try {
      const r = await readSkillRepoFile(source, path);
      setPreview({
        title: s.title || s.name,
        path: r.path,
        content: r.content,
        size: r.size,
        truncated: r.truncated,
      });
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setPreviewLoading(false);
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

  const title = mode === 'official' ? '技能市场 · Anthropic 官方 Agent Skills 仓库' : '技能市场 · 读取网址';

  return (
    <>
      <Modal title={title} open={open} onCancel={onClose} footer={null} width={920} destroyOnClose>
        <Space direction="vertical" style={{ width: '100%' }} size={10}>
          <Space wrap>
            <Input.Search
              placeholder="搜索技能名或描述"
              allowClear
              style={{ width: 260 }}
              value={kw}
              onChange={(e) => setKw(e.target.value)}
            />
            {mode === 'official' ? (
              <Button icon={<SyncOutlined />} loading={loading} onClick={loadOfficial}>
                刷新
              </Button>
            ) : (
              <Button icon={<RollbackOutlined />} onClick={loadOfficial}>
                返回官方市场
              </Button>
            )}
            <Button icon={<ApiOutlined />} loading={probing} onClick={diagnose}>
              网络诊断
            </Button>
            {repoCtx && (
              <Tag color="blue">
                {repoCtx.repo} @ {repoCtx.branch}
                {repoCtx.channel ? ` · ${repoCtx.channel}` : ''}
              </Tag>
            )}
          </Space>

          {/* 任意地址读取：把用户找到的技能库直接贴进来 */}
          <Space.Compact style={{ width: '100%' }}>
            <Input
              prefix={<LinkOutlined />}
              placeholder="粘贴技能库地址，如 owner/repo、GitHub 仓库/子目录链接（支持套了加速前缀的地址）"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onPressEnter={scan}
              allowClear
            />
            <Button type="primary" loading={loading} onClick={scan}>
              读取
            </Button>
          </Space.Compact>

          {probes && (
            <Alert
              type={probes.some((p) => p.ok) ? 'info' : 'error'}
              showIcon
              message="取件通道状态（后端会按顺序自动切换）"
              description={
                <Space wrap size={[6, 6]}>
                  {probes.map((p) => (
                    <Tooltip key={p.id} title={p.ok ? `${p.ms} ms` : p.error || '不可用'}>
                      <Tag color={p.ok ? 'green' : 'red'}>
                        {p.label} {p.ok ? `✓ ${p.ms}ms` : '✗'}
                        {p.preferred ? ' · 当前' : ''}
                      </Tag>
                    </Tooltip>
                  ))}
                </Space>
              }
            />
          )}

          {!loading && filtered.length === 0 && (
            <Empty
              description={
                mode === 'official'
                  ? '没有取到技能 —— 点上方「网络诊断」看看哪条通道不通，或把技能库地址贴到上面的输入框直接读取'
                  : '该地址下没有可用的技能'
              }
            />
          )}

          <List
            loading={loading}
            dataSource={filtered}
            style={{ maxHeight: 460, overflow: 'auto' }}
            renderItem={(s) => (
              <List.Item
                actions={[
                  <Button
                    key="view"
                    size="small"
                    icon={<EyeOutlined />}
                    loading={previewLoading}
                    onClick={() => view(s)}
                  >
                    查看
                  </Button>,
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
                      onClick={() => install(s)}
                    >
                      安装
                    </Button>
                  ),
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <span>{s.title || s.name}</span>
                      <Tag>{s.name}</Tag>
                      {mode === 'repo' && s.path && (
                        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                          {s.path}
                        </Typography.Text>
                      )}
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
        </Space>
      </Modal>

      <Modal
        title={preview ? `预览 · ${preview.title}` : '预览'}
        open={!!preview}
        onCancel={() => setPreview(null)}
        footer={null}
        width={880}
      >
        {preview && (
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <Space wrap>
              <Typography.Text code>{preview.path}</Typography.Text>
              <Tag>{preview.size} 字节</Tag>
              {preview.truncated && <Tag color="orange">已截断</Tag>}
            </Space>
            <Divider style={{ margin: '4px 0' }} />
            <pre
              style={{
                maxHeight: 520,
                overflow: 'auto',
                margin: 0,
                padding: 12,
                background: 'rgba(0,0,0,0.03)',
                borderRadius: 6,
                fontSize: 12,
                lineHeight: 1.6,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {preview.content}
            </pre>
          </Space>
        )}
      </Modal>
    </>
  );
}
