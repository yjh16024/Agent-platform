import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Carousel,
  Col,
  Descriptions,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CloudDownloadOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  LinkOutlined,
  PlayCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {
  installSkin,
  listInstalledSkins,
  proxyImageUrl,
  readSkinMarket,
  uninstallSkin,
  type InstalledSkin,
  type SkinEntry,
  type SkinMarketResult,
} from '../../api/skins';
import { useTheme } from '../../theme/ThemeProvider';
import {
  disableSkin,
  enableSkin,
  enabledSkinId,
  lastRunOf,
  loadedSkins,
  skinRuntimeLog,
} from '../../skin/runtime';

/** 默认预置一个市场地址，打开即可一键读取。 */
const DEFAULT_MARKET_URL = 'https://kingofsoysauce.github.io/dsh-skin-market/';
const URL_KEY = 'ap.skin.market.url';

/**
 * 皮肤市场。
 *
 * 输入一个皮肤市场地址（站点首页 / GitHub 仓库 / catalog.json 直链都能认），
 * 后端把它归一化回仓库并取回目录 JSON，这里按**卡片墙**展示每个皮肤的封面、作者、
 * 标签、亮暗模式、健康度与许可证；点安装则按条目里的 `install.target`
 * （钉死 commit 的 GitHub 子目录）下载源码到本地。
 *
 * 装好后点「运行 JS」——把皮肤自己的客户端 bundle 注入页面，在宿主契约钩子上跑，
 * 于是它的美术、背景、动效与部件开关全部按原样生效（见 `skin/runtime.ts`）。
 *
 * 原先还有一条「应用」路径（后端从包里抽配色映射成 antd token / CSS 变量，再把图片挂到聊天栏）。
 * 那条路已下线：它只能搬走**配色**，而皮肤的美术与动效都绑在自己的 JS 上，
 * 结果是"半套皮肤"，还会和皮肤自己的渲染打架。运行 JS 之后它就没有存在意义了。
 */
export default function SkinMarketPage() {
  /*
   * 只取明暗模式。
   *
   * 原来自研的"抽皮肤配色（applySkin）+ 把皮肤包图片挂到聊天栏（backgrounds / bgEnabled / scene 预览）"
   * 整套已下线：**「运行 JS」跑皮肤自己的 bundle 之后，皮肤会自己把配色的美术、背景、动效
   * 全部接上**，那套"只搬配色和图片"的做法反而只能做到一半，还容易和皮肤自己的渲染打架。
   * 对应的上下文成员已从 `ThemeProvider` 删除（无调用方 = 死代码）。
   */
  const { mode, setMode } = useTheme();
  const [url, setUrl] = useState(() => localStorage.getItem(URL_KEY) ?? DEFAULT_MARKET_URL);
  const [result, setResult] = useState<SkinMarketResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [installed, setInstalled] = useState<InstalledSkin[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [detail, setDetail] = useState<SkinEntry | null>(null);
  /** 正在"跑原版 JS"的皮肤（方案甲：让皮肤用它自己的 JS 适配我们的宿主） */
  const [running, setRunning] = useState<Set<string>>(() => new Set(loadedSkins().map((s) => s.id)));
  const [runLog, setRunLog] = useState<string[]>([]);

  const [kw, setKw] = useState('');
  const [category, setCategory] = useState<string>('all');
  const [modeFilter, setModeFilter] = useState<'all' | 'light' | 'dark'>('all');
  const [onlyVerified, setOnlyVerified] = useState(false);

  const loadInstalled = useCallback(async () => {
    try {
      setInstalled(await listInstalledSkins());
    } catch (e) {
      message.error((e as Error).message);
    }
  }, []);

  useEffect(() => {
    loadInstalled();
  }, [loadInstalled]);

  const read = async (target?: string) => {
    const u = (target ?? url).trim();
    if (!u) {
      message.warning('请先填写皮肤市场地址');
      return;
    }
    setLoading(true);
    try {
      const r = await readSkinMarket(u, true);
      setResult(r);
      localStorage.setItem(URL_KEY, u);
      message.success(`已读取 ${r.count} 个皮肤（来源 ${r.repo ?? r.catalog}）`);
      await loadInstalled();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const doInstall = async (entry: SkinEntry) => {
    setBusy(entry.id);
    try {
      const r = await installSkin(entry);
      message.success(`已安装「${entry.name}」：${r.files} 个文件${r.failed ? `（${r.failed} 个失败）` : ''}`);
      await loadInstalled();
      setResult((prev) =>
        prev ? { ...prev, skins: prev.skins.map((s) => (s.id === entry.id ? { ...s, installed: true } : s)) } : prev,
      );
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  /**
   * 方案甲：注入皮肤**自己的**客户端 bundle，让它在我们的宿主契约上跑。
   *
   * <p>为什么必须这一步：测绘显示所有皮肤都要读宿主状态、自建 DOM、注 CSS、用 MutationObserver
   * 补偿宿主变化——这些补偿逻辑都在它自己的 JS 里。只吃 CSS 等于把这层补偿留给我们自己补，
   * 那是无底洞。</p>
   */
  const doRunSkin = async (id: string) => {
    setBusy(id);
    try {
      // enableSkin 会**先停掉其它已加载的皮肤**（互斥），再注入这一个。
      const { rec, report } = await enableSkin(id);
      setRunning(new Set(loadedSkins().map((s) => s.id)));
      setRunLog(skinRuntimeLog().slice(-80));
      if (report.errors.length > 0) {
        message.warning(`已加载，但有 ${report.errors.length} 处报错，见下方运行日志`);
      } else {
        message.success(
          `皮肤 JS 已加载（${rec.pluginName ?? '未声明 name'}，登记清理函数 ${rec.disposers.length} 个）。` +
            `已记住启用状态，下次启动会自动恢复；要取消就点「停止 JS」。`,
        );
      }
    } catch (e) {
      setRunLog(skinRuntimeLog().slice(-80));
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const doStopSkin = (id: string) => {
    disableSkin(id);
    setRunning(new Set(loadedSkins().map((s) => s.id)));
    setRunLog(skinRuntimeLog().slice(-80));
    message.info('已卸载皮肤 JS，并清除它留在页面上的作用域属性');
  };

  /**
   * 一键更新：按市场条目的**新 commit** 重装。
   *
   * <p>皮肤市场随时在更新，而"已装版本"是安装当时钉住的 commit。这里拿市场条目重走一遍
   * 安装（会按新 ref 覆盖文件），装完刷新列表让「可更新」标记消失。</p>
   *
   * <p>更新前如果它正在跑，会**先停掉** —— 皮肤文件正被覆盖时它还跑着，容易出现
   * "一半新一半旧"的中间态。停掉之后由用户决定要不要再开。</p>
   */
  const doUpdate = async (id: string, name?: string) => {
    const entry = result?.skins?.find((x) => x.id === id);
    if (!entry) {
      message.warning('市场数据里找不到这个皮肤，先点「读取市场」刷新');
      return;
    }
    setBusy(id);
    try {
      const wasRunning = running.has(id) || enabledSkinId() === id;
      if (wasRunning) {
        disableSkin(id);
        setRunning(new Set(loadedSkins().map((s) => s.id)));
      }
      const r = await installSkin(entry);
      message.success(`已更新「${name || id}」（文件 ${r.files ?? '-'} 个）`);
      await loadInstalled();
      if (wasRunning) {
        message.info('它更新前正在运行，已为你停止；需要的话再点一次「运行 JS」');
      }
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const doUninstall = async (id: string) => {
    setBusy(id);
    try {
      await uninstallSkin(id);
      message.success('已卸载');
      await loadInstalled();
      setResult((prev) =>
        prev ? { ...prev, skins: prev.skins.map((s) => (s.id === id ? { ...s, installed: false } : s)) } : prev,
      );
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const categories = useMemo(() => {
    const set = new Map<string, number>();
    (result?.skins ?? []).forEach((s) => {
      const c = s.category || '未分类';
      set.set(c, (set.get(c) ?? 0) + 1);
    });
    return [
      { label: `全部 (${result?.count ?? 0})`, value: 'all' },
      ...[...set.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([k, n]) => ({ label: `${k} (${n})`, value: k })),
    ];
  }, [result]);

  const filtered = useMemo(() => {
    const k = kw.trim().toLowerCase();
    return (result?.skins ?? []).filter((s) => {
      if (category !== 'all' && (s.category || '未分类') !== category) {
        return false;
      }
      if (modeFilter !== 'all' && !(s.modes ?? []).includes(modeFilter)) {
        return false;
      }
      if (onlyVerified && s.review?.installation !== 'verified') {
        return false;
      }
      if (!k) {
        return true;
      }
      return (
        s.id.toLowerCase().includes(k) ||
        (s.name ?? '').toLowerCase().includes(k) ||
        (s.nameEn ?? '').toLowerCase().includes(k) ||
        (s.author ?? '').toLowerCase().includes(k) ||
        (s.description ?? '').toLowerCase().includes(k) ||
        (s.tags ?? []).some((t) => t.toLowerCase().includes(k))
      );
    });
  }, [result, kw, category, modeFilter, onlyVerified]);

  const installedIds = useMemo(() => new Set(installed.map((i) => i.id)), [installed]);

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      {/* 市场地址读取 */}
      <Card size="small">
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Space wrap style={{ width: '100%' }}>
            <Input
              prefix={<LinkOutlined />}
              style={{ width: 520 }}
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onPressEnter={() => read()}
              placeholder="粘贴皮肤市场地址：站点首页 / GitHub 仓库 / catalog.json 直链"
              allowClear
            />
            <Button type="primary" icon={<DownloadOutlined />} loading={loading} onClick={() => read()}>
              读取市场
            </Button>
            <Button icon={<SyncOutlined />} loading={loading} onClick={() => read(url)}>
              刷新
            </Button>
            {result && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                来源 {result.repo ? `${result.repo}@${result.branch}` : result.catalog} · {result.catalog}
                {result.channel ? ` · 通道 ${result.channel}` : ''}
              </Typography.Text>
            )}
          </Space>

          {result && (
            <Space size={24} wrap>
              <Statistic title="皮肤总数" value={result.count} valueStyle={{ fontSize: 18 }} />
              <Statistic title="已安装" value={installed.length} valueStyle={{ fontSize: 18 }} />
              <Statistic
                title="目录更新时间"
                value={result.generatedAt ? new Date(result.generatedAt).toLocaleString() : '-'}
                valueStyle={{ fontSize: 14 }}
              />
              {!!result.skipped && (
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  跳过 {result.skipped} 个无法解析的条目
                </Typography.Text>
              )}
            </Space>
          )}

          {/*
            明暗切换**必须留着**：它写 `body[data-ds-dark-theme]`，是 DSH 契约里最关键的一个
            钩子（测绘显示 10/16 个皮肤靠它选亮色/暗色那一套美术）。
            「当前皮肤 / 恢复默认外观」随"把皮肤当主题"那条路一起下线了。
          */}
          <Alert
            type="info"
            showIcon
            message={
              <Space wrap>
                <span>外观明暗（皮肤按它决定用哪一套）</span>
                <Segmented
                  size="small"
                  value={mode}
                  onChange={(v) => setMode(v as 'light' | 'dark')}
                  options={[
                    { label: '亮色', value: 'light' },
                    { label: '暗色', value: 'dark' },
                  ]}
                />
              </Space>
            }
          />


          {/*
            方案甲运行时面板。
            宿主契约钩子（id=root / data-ds-dark-theme / data-slot / data-pane / data-phase …）
            已按测绘结果挂好，这里只是把皮肤自己的 bundle 注进来让它跑。
          */}
          <Card
            size="small"
            title={
              <Space size={6}>
                <PlayCircleOutlined />
                原版皮肤运行时（实验性）
              </Space>
            }
            extra={
              <Space size={6}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  已运行 {running.size} 个
                </Typography.Text>
                <Button size="small" disabled={!runLog.length} onClick={() => setRunLog([])}>
                  清空日志
                </Button>
              </Space>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Alert
                type="warning"
                showIcon
                message="加载皮肤 JS = 在本页面执行第三方代码"
                description={
                  <span style={{ fontSize: 12 }}>
                    这是"兼容各种皮肤"的正解（皮肤自己适配宿主，而不是我们去模仿它），但代价是把控制权交给第三方代码。
                    出问题点「停止 JS」即可卸载（会走皮肤自己登记的清理函数）。实证：市场里约半数皮肤无任何 peer 依赖，可直接运行。
                    <br />
                    <b>皮肤自己的开关</b>（显示背景 / 角色 / 某个部件）在
                    <b>侧栏底部 ⚙ 图标</b>打开的面板里 —— 那是按皮肤声明的设置项实时渲染的，平台不需要认识具体皮肤。
                  </span>
                }
              />
              {runLog.length === 0 ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  在下方已安装皮肤列表点「运行 JS」开始。运行日志会显示在这里。
                </Typography.Text>
              ) : (
                <pre
                  style={{
                    maxHeight: 220,
                    overflow: 'auto',
                    fontSize: 11,
                    lineHeight: 1.5,
                    background: 'var(--ap-bg-layout)',
                    border: '1px solid var(--ap-border)',
                    borderRadius: 6,
                    padding: 8,
                    margin: 0,
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {runLog.join('\n')}
                </pre>
              )}
            </Space>
          </Card>
        </Space>
      </Card>

      {/* 已安装 */}
      {installed.length > 0 && (
        <Card size="small" title={`已安装皮肤（${installed.length}）`}>
          <Space wrap size={8}>
            {installed.map((s) => (
              <Card key={s.id} size="small" style={{ width: 260 }} bodyStyle={{ padding: 10 }}>
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Space>
                    <b>{s.name || s.id}</b>
                  </Space>
                  {/*
                    配套插件依赖：DSH 生态里皮肤之间会互相依赖（如 orca-link 给
                    `[data-dsh-better-sidebar] .xterm` 写终端样式）。缺了插件那部分样式
                    必然不生效 —— 这不是缺陷，但必须让用户知道，否则会被当成"适配没做好"。
                  */}
                  {(s.partnerPlugins ?? []).length > 0 && (
                    <Tooltip
                      title={
                        `这个皮肤会用到配套插件：${(s.partnerPlugins ?? []).join('、')}。` +
                        '没装的话它对应那部分样式不会生效（DSH 生态里皮肤互相依赖是常态，不是适配问题）。'
                      }
                    >
                      <Tag color="orange">需要配套插件</Tag>
                    </Tooltip>
                  )}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {s.author || '未知作者'} · {s.files ?? 0} 个文件
                  </Typography.Text>
                  <Space size={4} wrap>
                    {(s.modes ?? []).map((m) => (
                      <Tag key={m} color={m === 'dark' ? 'purple' : 'gold'}>
                        {m === 'dark' ? '暗' : '亮'}
                      </Tag>
                    ))}
                    {s.license?.code && <Tag>{s.license.code}</Tag>}
                  </Space>
                  <Space size={4}>
                    {/*
                      支持度**如实标注**，不假装全兼容：
                      只有真的跑过才知道这个皮肤在我们的宿主上是什么状态，
                      而契约版本参与判断 —— 我们改了钩子，旧结论就该失效。
                    */}
                    {(() => {
                      if (running.has(s.id) || enabledSkinId() === s.id) {
                        return <Tag color="green">JS 运行中</Tag>;
                      }
                      const run = lastRunOf(s.id);
                      if (!run) {
                        return <Tag>JS 未运行</Tag>;
                      }
                      if (run.errors.length > 0) {
                        return <Tag color="orange">JS 上次报错 {run.errors.length} 处</Tag>;
                      }
                      return run.ok ? (
                        <Tag color="blue">JS 上次正常 · 契约 v{run.contractVersion}</Tag>
                      ) : (
                        <Tag color="orange">JS 上次异常（未取到插件导出）</Tag>
                      );
                    })()}
                    {/*
                      版本比对：皮肤市场随时在更新，拿市场条目的 install.commit 与已装皮肤记录的 commit 比。
                      不一致 → 标「可更新」（只提示，不静默重装）。
                    */}
                    {(() => {
                      const m = result?.skins?.find((x) => x.id === s.id);
                      const remote = m?.install?.commit;
                      const local = s.commit ?? s.ref;
                      if (!remote || !local || remote === local) {
                        return null;
                      }
                      return (
                        <Tooltip title={`已装 ${String(local).slice(0, 10)} → 市场 ${String(remote).slice(0, 10)}，点一下按新版本重装`}>
                          <Button
                            size="small"
                            color="gold"
                            variant="outlined"
                            icon={<SyncOutlined />}
                            loading={busy === s.id}
                            onClick={() => doUpdate(s.id, s.name)}
                          >
                            可更新
                          </Button>
                        </Tooltip>
                      );
                    })()}
                    <Tooltip title="注入该皮肤自己的客户端 bundle（方案甲）。会执行第三方代码，出问题可一键停止">
                      <Button
                        size="small"
                        danger={running.has(s.id)}
                        icon={running.has(s.id) ? <StopOutlined /> : <PlayCircleOutlined />}
                        loading={busy === s.id}
                        onClick={() => (running.has(s.id) ? doStopSkin(s.id) : doRunSkin(s.id))}
                      >
                        {running.has(s.id) ? '停止 JS' : '运行 JS'}
                      </Button>
                    </Tooltip>
                    <Popconfirm title={`卸载「${s.name || s.id}」？`} onConfirm={() => doUninstall(s.id)}>
                      <Button size="small" danger icon={<DeleteOutlined />} loading={busy === s.id}>
                        卸载
                      </Button>
                    </Popconfirm>
                  </Space>
                </Space>
              </Card>
            ))}
          </Space>
        </Card>
      )}

      {/* 筛选 */}
      {result && (
        <Space wrap>
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索名称 / 作者 / 标签"
            style={{ width: 240 }}
            value={kw}
            onChange={(e) => setKw(e.target.value)}
            allowClear
          />
          <Select style={{ width: 180 }} value={category} onChange={setCategory} options={categories} />
          <Segmented
            value={modeFilter}
            onChange={(v) => setModeFilter(v as 'all' | 'light' | 'dark')}
            options={[
              { label: '全部', value: 'all' },
              { label: '亮色', value: 'light' },
              { label: '暗色', value: 'dark' },
            ]}
          />
          <Button
            type={onlyVerified ? 'primary' : 'default'}
            icon={<CheckCircleOutlined />}
            onClick={() => setOnlyVerified((v) => !v)}
          >
            仅看安装已验证
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            匹配 {filtered.length} 个
          </Typography.Text>
        </Space>
      )}

      {/* 卡片墙 */}
      {!result && !loading && (
        <Empty description="粘贴一个皮肤市场地址后点「读取市场」。默认已填 DSH Web GUI 皮肤市场，直接点即可。" />
      )}
      {result && filtered.length === 0 && !loading && <Empty description="没有匹配的皮肤" />}

      <Row gutter={[10, 10]}>
        {filtered.map((s) => {
          const isInstalled = s.installed || installedIds.has(s.id);
          const cover = proxyImageUrl(s.listScreenshot || s.screenshots?.[0]);
          return (
            <Col key={s.id} xs={12} sm={8} md={6} lg={6} xl={4} xxl={3}>
              <Card
                hoverable
                size="small"
                bodyStyle={{ padding: 10 }}
                cover={
                  <div
                    style={{
                      height: 112,
                      background: 'linear-gradient(135deg, rgba(0,0,0,0.06), rgba(0,0,0,0.14))',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      overflow: 'hidden',
                      cursor: 'pointer',
                    }}
                    onClick={() => setDetail(s)}
                  >
                    {cover ? (
                      <img
                        src={cover}
                        alt={s.name}
                        loading="lazy"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        onError={(e) => {
                          (e.currentTarget as HTMLImageElement).style.display = 'none';
                        }}
                      />
                    ) : (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        无预览图
                      </Typography.Text>
                    )}
                  </div>
                }
                actions={[
                  isInstalled ? null : (
                    <Tooltip title={s.install?.target} key="install">
                      <Button
                        type="text"
                        size="small"
                        icon={<CloudDownloadOutlined />}
                        loading={busy === s.id}
                        onClick={() => doInstall(s)}
                      >
                        安装
                      </Button>
                    </Tooltip>
                  ),
                  <Button key="eye" type="text" size="small" icon={<EyeOutlined />} onClick={() => setDetail(s)} />,
                ]}
              >
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Space size={4} style={{ width: '100%', justifyContent: 'space-between' }}>
                    <Typography.Text strong ellipsis style={{ fontSize: 13, maxWidth: 130 }}>
                      {s.name}
                    </Typography.Text>
                    {s.review?.installation === 'verified' && (
                      <Tooltip title="安装流程已验证">
                        <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
                      </Tooltip>
                    )}
                  </Space>
                  <Typography.Text type="secondary" ellipsis style={{ fontSize: 11 }}>
                    {s.author || '未知作者'}
                  </Typography.Text>
                  <Space size={2} wrap>
                    {(s.modes ?? []).map((m) => (
                      <Tag key={m} color={m === 'dark' ? 'purple' : 'gold'} style={{ marginInlineEnd: 0 }}>
                        {m === 'dark' ? '暗' : '亮'}
                      </Tag>
                    ))}
                    {s.category && <Tag style={{ marginInlineEnd: 0 }}>{s.category}</Tag>}
                    {s.license?.code && (
                      <Tag
                        color={s.license.commercialUse === 'false' || s.license.commercialUse === false ? 'orange' : undefined}
                        style={{ marginInlineEnd: 0 }}
                      >
                        {s.license.code}
                      </Tag>
                    )}
                  </Space>
                  {isInstalled && <Tag color="green" style={{ marginInlineEnd: 0 }}>已安装</Tag>}
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>

      {/* 详情 */}
      <Modal
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={null}
        width={860}
        title={detail ? `${detail.name}${detail.nameEn ? ` · ${detail.nameEn}` : ''}` : ''}
      >
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            {(() => {
              const shots = (detail.screenshots ?? []).length
                ? detail.screenshots!
                : detail.listScreenshot
                  ? [detail.listScreenshot]
                  : [];
              if (!shots.length) {
                return null;
              }
              return (
                <Carousel autoplay draggable style={{ background: '#000' }}>
                  {shots.map((u) => (
                    <div key={u}>
                      <img
                        src={proxyImageUrl(u)}
                        alt={detail.name}
                        style={{ width: '100%', maxHeight: 420, objectFit: 'contain' }}
                      />
                    </div>
                  ))}
                </Carousel>
              );
            })()}

            <Typography.Paragraph style={{ marginBottom: 0 }}>{detail.description || '（无描述）'}</Typography.Paragraph>

            <Space wrap size={4}>
              {(detail.tags ?? []).map((t) => (
                <Tag key={t}>{t}</Tag>
              ))}
            </Space>

            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="作者">{detail.author || '-'}</Descriptions.Item>
              <Descriptions.Item label="分类">{detail.category || '-'}</Descriptions.Item>
              <Descriptions.Item label="支持模式">{(detail.modes ?? []).join(' / ') || '-'}</Descriptions.Item>
              <Descriptions.Item label="来源仓库">
                {detail.repo ? (
                  <Typography.Link href={detail.repo} target="_blank" style={{ fontSize: 12 }}>
                    {detail.repo}
                  </Typography.Link>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="版本">{detail.install?.version || '-'}</Descriptions.Item>
              <Descriptions.Item label="许可证">
                {detail.license?.code || '-'}
                {(detail.license?.commercialUse === 'false' || detail.license?.commercialUse === false) && (
                  <Tag color="orange" style={{ marginLeft: 6 }}>
                    禁止商用
                  </Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="钉定 commit" span={2}>
                <Typography.Text code style={{ fontSize: 11 }}>
                  {detail.install?.commit || '-'}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="安装目标" span={2}>
                <Typography.Text code copyable style={{ fontSize: 11 }}>
                  {detail.install?.target || '-'}
                </Typography.Text>
              </Descriptions.Item>
            </Descriptions>

            {detail.license?.notice && (
              <Alert type="warning" showIcon icon={<WarningOutlined />} message="许可说明" description={detail.license.notice} />
            )}

            {!!detail.health?.suggestions?.length && (
              <Alert
                type="info"
                showIcon
                message="市场健康检查建议"
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {detail.health.suggestions.map((s) => (
                      <li key={s}>{s}</li>
                    ))}
                  </ul>
                }
              />
            )}

            <Space>
              {detail.installed || installedIds.has(detail.id) ? (
                <>
                  <Popconfirm title={`卸载「${detail.name}」？`} onConfirm={() => doUninstall(detail.id)}>
                    <Button danger icon={<DeleteOutlined />} loading={busy === detail.id}>
                      卸载
                    </Button>
                  </Popconfirm>
                </>
              ) : (
                <Button
                  type="primary"
                  icon={<CloudDownloadOutlined />}
                  loading={busy === detail.id}
                  onClick={() => doInstall(detail)}
                >
                  安装到本地
                </Button>
              )}
              {detail.repo && (
                <Button icon={<ReloadOutlined />} href={detail.repo} target="_blank">
                  打开仓库
                </Button>
              )}
            </Space>
          </Space>
        )}
      </Modal>
    </Space>
  );
}
