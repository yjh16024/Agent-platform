import { useCallback, useEffect, useState } from 'react';
import {
  Drawer, Descriptions, Button, Modal, Select, Input, Switch, message, Space, Tag,
} from 'antd';
import {
  pluginDetail, attachPlugin, detachPlugin, attachments, pluginAttachments,
  configFieldsOf, type AgentPluginBinding, type PluginAttachment, type PluginConfigField,
} from '../../api/plugins';
import { listAgents } from '../../api/agents';
import { PluginDef, AgentResponse } from '../../api/types';
import { PluginConfigForm } from './PluginConfigForm';
import { buildInitialConfig, missingRequired, prunedConfig } from './plugin-config';

export default function PluginDetailDrawer({
  pluginId,
  onClose,
}: {
  pluginId: string | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<PluginDef | null>(null);
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [selAgentId, setSelAgentId] = useState<string | undefined>();
  /** 该插件在此智能体上的挂载记录（含已保存配置；密钥为掩码）。 */
  const [attached, setAttached] = useState<AgentPluginBinding | null>(null);
  const [attachOpen, setAttachOpen] = useState(false);
  const [version, setVersion] = useState('');
  const [config, setConfig] = useState('');
  const [enabled, setEnabled] = useState(true);
  /** 声明驱动表单的值（键名由插件的 configFields() 声明）。 */
  const [cfgValues, setCfgValues] = useState<Record<string, unknown>>({});

  /**
   * 该插件声明的配置项。
   *
   * <p>从 `detail.manifest.contributes.config` 读（后端注册内置插件时写进去的）。
   * <b>空数组表示"这个插件没声明"</b> —— 此时退回自由 JSON 输入，
   * 而不是显示一个空表单（空表单会让用户以为"这个插件不需要配置"，
   * 而实际上可能只是插件还没升级声明）。</p>
   */
  const fields: PluginConfigField[] = configFieldsOf(detail);

  useEffect(() => {
    if (!pluginId) return;
    pluginDetail(pluginId).then(setDetail).catch(() => setDetail(null));
    listAgents(undefined, undefined, 0, 100).then((r) => setAgents(r.items ?? [])).catch(() => {});
    /*
     * 切换插件时清掉上一次的输入。
     *
     * 之前这里是个真 bug：`ttsCfg` 是 Drawer 级 state，而 Drawer 是复用的
     * （同一时刻只渲染一个插件）。看完 A 插件切到 B 时，A 填的内容会原样留在表单里，
     * 用户一点"挂载"就把 A 的配置写进了 B —— 而界面上看起来完全正常。
     */
    setCfgValues({});
    setConfig('');
    setVersion('');
    setAttached(null);
  }, [pluginId]);

  const loadAttached = useCallback(async (agentId?: string) => {
    const aid = agentId ?? selAgentId;
    if (!pluginId || !aid) return;
    try {
      const list = await attachments(aid);
      const hit = list.find((a) => a.pluginId === pluginId || a.plugin_id === pluginId);
      setAttached(hit ?? null);
      // 回显已保存的配置（密钥字段后端只给掩码）。初值 = 声明的默认值 + 已保存值覆盖，
      // 于是"哪些能留空、留空会怎样"在表单第一次打开时就看得见。
      setCfgValues((prev) => {
        const merged = buildInitialConfig(fields, hit?.config);
        // 用户可能已经在填（异步回显晚于输入）——保留他已有的输入，别把它冲掉
        return Object.keys(prev).length === 0 ? merged : { ...merged, ...prev };
      });
    } catch {
      setAttached(null);
    }
  }, [pluginId, selAgentId, fields]);

  useEffect(() => {
    loadAttached();
  }, [loadAttached]);

  const doAttach = async () => {
    if (!pluginId || !selAgentId) {
      message.warning('请先选择智能体');
      return;
    }
    let cfg: Record<string, unknown> | undefined;
    if (fields.length > 0) {
      const miss = missingRequired(fields, cfgValues);
      if (miss.length > 0) {
        message.error(`请先填写：${miss.join('、')}`);
        return;
      }
      cfg = prunedConfig(cfgValues);
    } else if (config.trim()) {
      try {
        cfg = JSON.parse(config);
      } catch {
        message.error('config 不是合法 JSON');
        return;
      }
    }
    try {
      await attachPlugin(pluginId, selAgentId, { version: version || undefined, config: cfg, enabled });
      message.success('已挂载');
      setAttachOpen(false);
      loadAttached();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  /** 真正执行卸载（已确认影响面后调用）。 */
  const confirmDetach = async () => {
    if (!pluginId || !selAgentId) return;
    try {
      const r = await detachPlugin(pluginId, selAgentId);
      const n = r?.count ?? 0;
      message.success(n > 1 ? `已卸载，并一并取消了 ${n} 个智能体上的挂载` : '已卸载');
      loadAttached();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  /**
   * 卸载入口：先查这个插件被哪些智能体用着。
   * 若除当前智能体外还有别的智能体在用，必须让用户明确知道「会一并取消」——
   * 因为后端是级联卸载（否则会出现 B 显示已挂载但实际已失效的错位状态）。
   */
  const doDetach = async () => {
    if (!pluginId || !selAgentId) return;
    let others: PluginAttachment[] = [];
    try {
      const list = await pluginAttachments(pluginId);
      others = list.filter((a) => a.agentId !== selAgentId);
    } catch {
      // 影响面查询失败不阻断卸载，退化成直接卸载（后端仍会级联处理）
    }
    if (others.length === 0) {
      await confirmDetach();
      return;
    }
    const names = others.map((a) => a.agentName || a.agentId).join('、');
    Modal.confirm({
      title: '该插件正被多个智能体使用',
      content: `除当前智能体外，还有 ${others.length} 个智能体挂载了「${detail?.name ?? pluginId}」：${names}。`
        + '卸载会一并取消这些挂载，确定继续？',
      okText: '一并卸载',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => confirmDetach(),
    });
  };

  const attachModal = (
    <Modal
      title="挂载到智能体"
      open={attachOpen}
      onOk={doAttach}
      onCancel={() => setAttachOpen(false)}
      destroyOnClose
    >
      <Space direction="vertical" style={{ width: '100%' }}>
        <Select
          placeholder="选择智能体"
          style={{ width: '100%' }}
          value={selAgentId}
          onChange={setSelAgentId}
          options={agents.map((a) => ({ value: a.agentId, label: a.name }))}
        />
        <Input placeholder="版本（可选）" value={version} onChange={(e) => setVersion(e.target.value)} />
        {fields.length > 0 ? (
          /*
           * 声明驱动的表单：插件说它要什么，这里就长什么样。
           * 加插件不用改前端 —— 这是与"每个插件写一个表单"的关键区别。
           */
          <PluginConfigForm fields={fields} value={cfgValues} onChange={setCfgValues} />
        ) : (
          /*
           * 兜底：插件没有声明配置项时，保留自由 JSON 输入。
           *
           * ⚠️ 不要把"没有声明"直接渲染成空 —— 用户会以为这个插件不需要配置。
           * 实际有两种可能：① 它真的不需要（如时间助手）；② 它需要但还没升级到声明式。
           * 所以这里明确写出"未声明"，并保留 JSON 入口让第二种情况仍然能用。
           */
          <>
            <div style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)' }}>
              该插件未声明配置项。若它确实需要参数，可在下方直接填写 JSON。
            </div>
            <Input.TextArea
              placeholder={'config（可选 JSON）'}
              rows={3}
              value={config}
              onChange={(e) => setConfig(e.target.value)}
            />
          </>
        )}
        <Space>
          <span>启用</span>
          <Switch checked={enabled} onChange={setEnabled} />
        </Space>
      </Space>
    </Modal>
  );

  return (
    <Drawer title={detail?.name ?? '插件详情'} open={!!pluginId} onClose={onClose} width={560}>
      {detail && (
        <>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="ID">{detail.pluginId}</Descriptions.Item>
            <Descriptions.Item label="描述">{detail.description || '—'}</Descriptions.Item>
            <Descriptions.Item label="作者">{detail.author || '—'}</Descriptions.Item>
            <Descriptions.Item label="版本">{detail.latestVersion || '—'}</Descriptions.Item>
            <Descriptions.Item label="状态">{detail.status || '—'}</Descriptions.Item>
            {/*
              把"需要准备什么"提到挂载之前可见。
              插件说明里写着"要填 API Key"，而用户点开挂载才发现只有一个 JSON 框 ——
              这一行的存在就是为了让那种落差不再出现：先看见要什么，再决定挂不挂。
            */}
            <Descriptions.Item label="需要配置">
              {fields.length === 0 ? (
                <span style={{ color: 'rgba(0,0,0,0.45)' }}>无需配置，挂载即可用</span>
              ) : (
                <Space size={4} wrap>
                  {fields.filter((f) => f.required).map((f) => (
                    <Tag key={f.key} color="red">{f.label}（必填）</Tag>
                  ))}
                  {fields.filter((f) => !f.required).map((f) => (
                    <Tag key={f.key}>{f.label}</Tag>
                  ))}
                  {fields.some((f) => f.secret) && (
                    <span style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)' }}>
                      （密钥加密存储，只显示掩码）
                    </span>
                  )}
                </Space>
              )}
            </Descriptions.Item>
          </Descriptions>

          <Descriptions column={1} size="small" bordered style={{ marginTop: 16 }} title="挂载管理">
            <Descriptions.Item label="智能体">
              <Select
                placeholder="选择智能体"
                style={{ width: '100%' }}
                value={selAgentId}
                onChange={(v) => {
                  setSelAgentId(v);
                  setAttached(null);
                }}
                options={agents.map((a) => ({ value: a.agentId, label: a.name }))}
              />
            </Descriptions.Item>
            <Descriptions.Item label="当前状态">
              {attached ? (
                <Space>
                  <Tag color="green">已挂载</Tag>
                  <span>{attached.version ? `v${attached.version}` : ''}</span>
                  {attached.enabled === false && <Tag color="orange">已停用</Tag>}
                </Space>
              ) : (
                <Tag>未挂载</Tag>
              )}
            </Descriptions.Item>
          </Descriptions>

          <Space style={{ marginTop: 16 }}>
            <Button type="primary" onClick={() => setAttachOpen(true)}>
              挂载 / 更新
            </Button>
            {attached && <Button danger onClick={doDetach}>卸载</Button>}
          </Space>
        </>
      )}
      {attachModal}
    </Drawer>
  );
}