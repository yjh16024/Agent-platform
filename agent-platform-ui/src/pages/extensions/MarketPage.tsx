import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Button,
  Card,
  Empty,
  Input,
  List,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  Upload,
} from 'antd';
import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  ReloadOutlined,
  SettingOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { listPluginWeb, installPluginWeb, setPluginWebEnabled, deletePluginWeb, type WebPluginItem } from '../../api/pluginWeb';
import {
  loadMarketCatalog,
  downloadPluginZip,
  type MarketPluginEntry,
} from '../../api/pluginMarket';
import { marketplace, deletePlugin } from '../../api/plugins';
import type { PluginDef } from '../../api/types';
import { useUiSlotStore } from '../../store/uiSlotStore';
import PluginImportModal from './PluginImportModal';
import PluginDetailDrawer from '../plugins/PluginDetailDrawer';
import PluginConfigModal from './PluginConfigModal';
import PluginScopeModal from './PluginScopeModal';
import type { PluginConfigField } from '../../api/pluginWeb';

const { Text, Paragraph } = Typography;

/**
 * 「扩展 → 插件市场」（2026-10-19 二次调整）。
 *
 * <h3>为什么把皮肤挪出去了</h3>
 * 上一版把界面插件 / 皮肤 / 服务端插件并成一个列表，但皮肤的数据来自**外部市场 catalog**
 * （GitHub Pages，两个请求：市场清单 + 已装列表）。打开插件市场时它们也一起发 →
 * 网络慢时整页都在转圈。而看插件的人**多数时候并不看皮肤**。
 * 现在皮肤回到独立的「皮肤市场」Tab（它自己那套缓存/读取逻辑照旧），
 * 这个页面只保留：
 * <ul>
 *   <li><b>界面插件</b>：内置清单 + 上传 zip，装完即时生效；</li>
 *   <li><b>服务端插件</b>：只有已装的（无在线源，本地导入 jar）。</li>
 * </ul>
 *
 * <h3>仍然是"一个列表 + 类型标签"</h3>
 * 不分内置与外部，已安装与可安装同列表；已装的给启停/卸载，可装的给安装。
 */

type Kind = 'ui' | 'server';

const KIND_META: Record<Kind, { label: string; color: string }> = {
  ui: { label: '界面插件', color: 'blue' },
  server: { label: '服务端插件', color: 'cyan' },
};

/** 平台内置插件的租户标识（与后端 `BuiltinPluginRegistrar` 一致）。 */
const PLATFORM_TENANT = '__platform__';

interface MarketCard {
  key: string;
  kind: Kind;
  id: string;
  name: string;
  description?: string;
  version?: string;
  author?: string;
  installed: boolean;
  enabled: boolean;
  tags: string[];
  /** 界面插件声明的槽位（`global.overlay` / `right.panel` …）—— 作者排查"slot 有没有被识别"要看它。 */
  slot?: string;
  /** 界面插件声明的配置项（有它才显示「设置」）。 */
  config?: PluginConfigField[] | null;
  /** 界面插件的挂载范围（空 = 全局）。 */
  agents?: string[] | null;
  /** `global`（缺省）/ `agent`：只有后者才显示「挂载范围」入口。 */
  scope?: string | null;
  /**
   * 已安装插件在**市场清单里的对应条目**（2026-10-19）。
   *
   * <p>有它才可能"从清单更新" —— 否则用户装完某个插件后就再也换不了版本，
   * 只能自己拿着 zip 手动上传（这正是本次用户遇到的：卡片停在 1.0.0）。</p>
   */
  marketEntry?: MarketPluginEntry;
  /** 服务端插件的"内置"标记：内置插件不可删除（后端会 403），界面上就不该给删除按钮。 */
  builtin?: boolean;
  /** 安装动作所需的原始数据（两类不同）。 */
  raw: unknown;
}

export default function MarketPage() {
  const { message } = AntApp.useApp();
  const [cards, setCards] = useState<MarketCard[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [kind, setKind] = useState<Kind | 'all'>('all');
  const [keyword, setKeyword] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  /** 服务端插件的详情抽屉（挂载/配置/卸载都在里面）—— 市场的卡片只放"入口"。 */
  const [detailId, setDetailId] = useState<string | null>(null);
  /** 界面插件的清单源（空 = 内置 `/plugin-market.json`）。 */
  const [marketSource, setMarketSource] = useState('');
  const [sourceOpen, setSourceOpen] = useState(false);
  const [sourceDraft, setSourceDraft] = useState('');
  /** 当前清单的自报名字（`{ name: "…" }`）。 */
  const [catalogName, setCatalogName] = useState('');
  /** 诊断：宿主实际拿到的槽位声明条数（0 = 前端没拉到，>0 = 渲染层的问题）。 */
  const globalSlots = useUiSlotStore((s) => s.global);
  /** 正在编辑配置的界面插件（manifest 声明了 `config` 才有「设置」）。 */
  const [configTarget, setConfigTarget] = useState<WebPluginItem | null>(null);
  /** 正在设置挂载范围的界面插件（空选 = 全局）。 */
  const [scopeTarget, setScopeTarget] = useState<WebPluginItem | null>(null);
  const loadGlobal = useUiSlotStore((s) => s.loadGlobal);

  /** 聚合两类数据：界面插件（已装 + 清单里的）+ 服务端插件（只有已装）。 */
  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [uiInstalled, uiMarket, serverPlugins] = await Promise.all([
        listPluginWeb().catch(() => [] as WebPluginItem[]),
        loadMarketCatalog(marketSource || undefined).catch(() => null),
        marketplace().catch(() => [] as PluginDef[]),
      ]);

      setCatalogName(uiMarket?.name ?? '');

      const next: MarketCard[] = [];

      // ---- 界面插件：已装 + 清单里还没装的 ----
      for (const p of uiInstalled) {
        const entry = uiMarket?.plugins?.find((e) => e.id === p.id);
        next.push({
          key: `ui:${p.id}`,
          kind: 'ui',
          id: p.id,
          name: p.name ?? p.id,
          description: p.description,
          version: p.version,
          installed: true,
          enabled: !!p.enabled,
          tags: [],
          slot: p.slot,
          config: p.config,
          scope: p.scope,
          agents: p.agents,
          marketEntry: entry,
          raw: p,
        });
      }
      for (const e of uiMarket?.plugins ?? []) {
        if (uiInstalled.some((p) => p.id === e.id)) {
          continue;
        }
        next.push({
          key: `ui:${e.id}`,
          kind: 'ui',
          id: e.id,
          name: e.name,
          description: e.description,
          version: e.version,
          author: e.author,
          installed: false,
          enabled: false,
          tags: e.tags ?? [],
          raw: e,
        });
      }

      // ---- 服务端插件：只有已安装的（没有在线源）----
      for (const p of serverPlugins) {
        const id = p.pluginId ?? p.id ?? '';
        if (!id) {
          continue;
        }
        next.push({
          key: `server:${id}`,
          kind: 'server',
          id,
          name: p.name ?? id,
          description: p.description,
          version: p.latestVersion,
          author: p.author,
          installed: true,
          enabled: true,
          tags: [],
          // 内置插件由平台发布（tenant_id = __platform__），删除会被后端拒绝
          builtin: p.tenantId === PLATFORM_TENANT || p.author === PLATFORM_TENANT,
          raw: p,
        });
      }

      // 已装优先，其次按名称 —— 用户打开页面最想让"我有的"在上面
      next.sort((a, b) => {
        if (a.installed !== b.installed) {
          return a.installed ? -1 : 1;
        }
        return a.name.localeCompare(b.name);
      });
      setCards(next);
    } finally {
      setLoading(false);
    }
  }, [marketSource]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const counts = useMemo(() => {
    const c: Record<Kind, number> = { ui: 0, server: 0 };
    for (const item of cards) {
      c[item.kind] += 1;
    }
    return c;
  }, [cards]);

  const visible = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return cards.filter((c) => {
      if (kind !== 'all' && c.kind !== kind) {
        return false;
      }
      if (!kw) {
        return true;
      }
      return `${c.name} ${c.id} ${c.description ?? ''}`.toLowerCase().includes(kw);
    });
  }, [cards, kind, keyword]);

  // ---------------------------------------------------------------- 动作

  /** 从清单装/更新一个界面插件（安装与"更新"共用这条路径）。 */
  const installUiEntry = async (cardKey: string, entry: MarketPluginEntry, name: string) => {
    setBusy(cardKey);
    try {
      const file = await downloadPluginZip(entry);
      await installPluginWeb(file);
      message.success(`已安装「${name}」${entry.version ? ` v${entry.version}` : ''}，界面即刻可用`);
      await loadGlobal();
      await loadAll();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const uploadUi = async (file: File) => {
    try {
      await installPluginWeb(file);
      message.success(`已安装 ${file.name}`);
      await loadGlobal();
      await loadAll();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const toggleUi = async (c: MarketCard, enabled: boolean) => {
    try {
      await setPluginWebEnabled(c.id, enabled);
      await loadGlobal();
      await loadAll();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const removeUi = async (c: MarketCard) => {
    try {
      await deletePluginWeb(c.id);
      message.success('已卸载');
      await loadGlobal();
      await loadAll();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const removeServer = async (c: MarketCard) => {
    try {
      await deletePlugin(c.id);
      message.success('已删除');
      await loadAll();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  // ---------------------------------------------------------------- 渲染

  const actions = (c: MarketCard) => {
    if (c.kind === 'ui') {
      return c.installed ? (
        <Space size={4}>
          {/*
            已安装也要能「更新」：否则装完某个版本后就再也换不了
            （用户装过 1.0.0 的插件，市场里有 1.1.0 也看不到任何入口）。
          */}
          {c.marketEntry && (
            <Popconfirm
              title={`用清单里的 v${c.marketEntry.version ?? '新版'} 覆盖当前安装？`}
              okText="更新"
              cancelText="取消"
              onConfirm={() => void installUiEntry(c.key, c.marketEntry!, c.name)}
            >
              <Button size="small" loading={busy === c.key}>更新</Button>
            </Popconfirm>
          )}
          <Switch size="small" checked={c.enabled} onChange={(v) => void toggleUi(c, v)} />
          <Popconfirm title={`卸载「${c.name}」？`} okText="卸载" cancelText="取消" onConfirm={() => void removeUi(c)}>
            <Button size="small" type="text" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ) : (
        <Button
          size="small"
          type="primary"
          icon={<CloudDownloadOutlined />}
          loading={busy === c.key}
          onClick={() => void installUiEntry(c.key, c.raw as MarketPluginEntry, c.name)}
        >
          安装
        </Button>
      );
    }
    /*
     * 服务端插件的**挂载/配置**能力在详情抽屉里 —— 市场卡片只提供一个入口。
     * 少了这个入口，"市场取代插件页"就会把"挂载到智能体"这个核心动作弄丢。
     */
    return (
      <Space size={4}>
        <Button size="small" onClick={() => setDetailId(c.id)}>管理</Button>
        {/* 内置插件不可删（后端 403）—— 给按钮只会让用户点出一个错误 */}
        {!c.builtin && (
          <Popconfirm title={`删除插件「${c.name}」？`} okText="删除" cancelText="取消" onConfirm={() => void removeServer(c)}>
            <Button size="small" type="text" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        )}
      </Space>
    );
  };

  const kindTabs: Array<{ key: Kind | 'all'; label: string; count: number }> = [
    { key: 'all', label: '全部', count: cards.length },
    { key: 'ui', label: KIND_META.ui.label, count: counts.ui },
    { key: 'server', label: KIND_META.server.label, count: counts.server },
  ];

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Input.Search
          allowClear
          placeholder="搜索插件名称 / 描述"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ width: 260 }}
        />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadAll()}>
          刷新
        </Button>
        {/* 上传界面插件 zip（原「界面插件」Tab 的能力） */}
        <Upload
          accept=".zip"
          showUploadList={false}
          beforeUpload={(f) => {
            void uploadUi(f as File);
            return false;
          }}
        >
          <Button icon={<CloudUploadOutlined />}>上传界面插件</Button>
        </Upload>
        <Button icon={<UploadOutlined />} onClick={() => setImportOpen(true)}>
          导入服务端插件
        </Button>
        <Button
          icon={<SettingOutlined />}
          onClick={() => {
            setSourceDraft(marketSource);
            setSourceOpen(true);
          }}
        >
          市场源
        </Button>
      </Space>

      <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
        当前源：<b>{catalogName || '（未读取）'}</b>
        {' · '}已启用界面插件 <b>{cards.filter((c) => c.kind === 'ui' && c.installed && c.enabled).length}</b> 个
        {' · '}宿主已收到槽位声明 <b>{globalSlots.length}</b> 条
        {globalSlots.length === 0 && '（0 条 = 前端没拿到声明，按 F12 看 contributions 请求）'}
        <br />
        皮肤在「皮肤市场」Tab（它的市场源是外部的，单独加载才不拖慢这一页）
      </Paragraph>

      {/* 类型标签（带计数）—— 点一下就筛 */}
      <Space wrap size={[6, 6]} style={{ marginBottom: 14 }}>
        {kindTabs.map((t) => (
          <Tag.CheckableTag key={t.key} checked={kind === t.key} onChange={() => setKind(t.key)}>
            {t.label}
            <span style={{ opacity: 0.55, marginLeft: 4 }}>{t.count}</span>
          </Tag.CheckableTag>
        ))}
      </Space>

      <Spin spinning={loading}>
        <List
          grid={{ gutter: 12, xs: 1, sm: 1, md: 2, lg: 2, xl: 3, xxl: 3 }}
          dataSource={visible}
          locale={{ emptyText: <Empty description="这个分类下还没有插件" /> }}
          renderItem={(c) => (
            <List.Item>
              <Card
                size="small"
                style={{ height: '100%' }}
                title={
                  <Space size={6} wrap>
                    <Text strong>{c.name}</Text>
                    {c.version && <Tag style={{ fontSize: 11 }}>{c.version}</Tag>}
                  </Space>
                }
                extra={actions(c)}
              >
                <Paragraph
                  type="secondary"
                  style={{ fontSize: 12, marginBottom: 8, minHeight: 38 }}
                  ellipsis={{ rows: 2 }}
                >
                  {c.description || '（无描述）'}
                </Paragraph>
                <Space size={4} wrap>
                  <Tag color={KIND_META[c.kind].color}>{KIND_META[c.kind].label}</Tag>
                  {c.installed ? <Tag color="green">已安装</Tag> : <Tag>可安装</Tag>}
                  {c.slot && <Tag>{c.slot}</Tag>}
                  {/* 插件自带配置入口（manifest 声明了 config 才有）—— 放在标签行里，避免挤爆卡片 extra */}
                  {c.kind === 'ui' && c.installed && (c.config?.length ?? 0) > 0 && (
                    <a style={{ fontSize: 11 }} onClick={() => setConfigTarget(c.raw as WebPluginItem)}>设置</a>
                  )}
                  {/*
                    挂载范围（2026-10-20）：**只有 manifest 声明了 `scope: "agent"` 才显示**。

                    通用插件（用量提示、桌宠这些）本来就该全平台生效，给它们都挂一个
                    "挂载：全部"标签是噪音 —— 用户会以为每个插件都必须配置一遍。
                    隔离需求是少数，应该由插件自己声明，而不是人人一份。
                  */}
                  {c.kind === 'ui' && c.installed && c.scope === 'agent' && (
                    <Tag
                      color={(c.agents?.length ?? 0) > 0 ? 'purple' : undefined}
                      style={{ cursor: 'pointer', marginInlineEnd: 0 }}
                      onClick={() => setScopeTarget(c.raw as WebPluginItem)}
                    >
                      挂载：{(c.agents?.length ?? 0) > 0 ? `${c.agents!.length} 个智能体` : '全部'}
                    </Tag>
                  )}
                  {c.author && <Text type="secondary" style={{ fontSize: 11 }}>{c.author}</Text>}
                  {c.tags.slice(0, 2).map((t) => (
                    <Tag key={t} style={{ fontSize: 10 }}>{t}</Tag>
                  ))}
                </Space>
              </Card>
            </List.Item>
          )}
        />
      </Spin>

      <PluginImportModal
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onDone={() => void loadAll()}
      />

      <PluginDetailDrawer pluginId={detailId} onClose={() => setDetailId(null)} />

      {/* 界面插件的配置表单：字段由 manifest 的 `config` 声明驱动 */}
      <PluginConfigModal
        plugin={configTarget}
        open={!!configTarget}
        onClose={() => setConfigTarget(null)}
      />

      {/* 挂载范围：空选 = 全部智能体 */}
      <PluginScopeModal
        plugin={scopeTarget}
        open={!!scopeTarget}
        onClose={() => setScopeTarget(null)}
        onSaved={() => {
          void loadGlobal();
          void loadAll();
        }}
      />

      {/* 界面插件的市场源（空 = 内置 /plugin-market.json） */}
      <Modal
        title="界面插件的市场源"
        open={sourceOpen}
        onCancel={() => setSourceOpen(false)}
        onOk={() => {
          setMarketSource(sourceDraft.trim());
          setSourceOpen(false);
        }}
        okText="应用并刷新"
        destroyOnClose
      >
        <Paragraph type="secondary" style={{ fontSize: 12 }}>
          留空用内置源 <Text code>/plugin-market.json</Text>；也可以填任意返回{' '}
          <Text code>{'{ "plugins": [ ... ] }'}</Text> 的地址（需允许跨域）。
        </Paragraph>
        <Input
          placeholder="/plugin-market.json 或 https://…/market.json"
          value={sourceDraft}
          onChange={(e) => setSourceDraft(e.target.value)}
        />
      </Modal>
    </div>
  );
}
