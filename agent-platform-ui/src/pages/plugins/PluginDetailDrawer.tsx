import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  Drawer, Descriptions, Button, Modal, Select, Input, InputNumber, Switch, message, Space, Tag, Alert,
} from 'antd';
import {
  pluginDetail, attachPlugin, detachPlugin, attachments, pluginAttachments, type PluginAttachment,
} from '../../api/plugins';
import { listAgents } from '../../api/agents';
import { PluginDef, AgentResponse } from '../../api/types';

/** TTS 插件的 id（后端 BuiltinTtsPlugin.PLUGIN_ID）。 */
const TTS_PLUGIN_ID = 'builtin_tts';

/**
 * TTS 插件的配置形态（与后端 {@code BuiltinTtsPlugin.Cfg} 的键一一对应）。
 *
 * <p>字段名刻意用 camelCase —— 后端按这个名字读，改这里必须同步改后端。</p>
 */
interface TtsForm {
  apiKey: string;
  baseUrl: string;
  model: string;
  voice: string;
  format: string;
  speed: number;
  maxChars: number;
  timeoutMs: number;
}

/**
 * 默认值，与后端保持一致：用户<b>只填 API Key 就能用</b>。
 *
 * <p>默认指向硅基流动（CosyVoice2）—— 平台若已配了硅基流动的模型密钥，
 * 把同一个 Key 填进来即可出声，不必再注册新服务商。</p>
 */
const TTS_DEFAULTS: TtsForm = {
  apiKey: '',
  baseUrl: 'https://api.siliconflow.cn/v1',
  model: 'FunAudioLLM/CosyVoice2-0.5B',
  voice: 'FunAudioLLM/CosyVoice2-0.5B:alex',
  format: 'mp3',
  speed: 1,
  maxChars: 150,
  timeoutMs: 8000,
};

/** 一行「标签 + 控件」，让表单不至于各字段对不齐。 */
function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div>
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.65)', marginBottom: 2 }}>
        {label}
        {hint && <span style={{ color: 'rgba(0,0,0,0.45)' }}>（{hint}）</span>}
      </div>
      {children}
    </div>
  );
}

/**
 * TTS 插件的配置表单。
 *
 * <h3>为什么这个插件要专门做表单</h3>
 * 其它插件用的是「自由 JSON 文本框」—— 它们没有"必须填对才能工作"的字段，随便写点开关就行。
 * 但 TTS 不同：用户要在这里填<b>真实的付费 API Key</b>，还要选服务商、模型、音色。
 * 让人手写 {@code {"apiKey":"sk-..."}} 有两个问题：**容易写错字段名**（而错的表现是"没声音"，
 * 极难定位），以及**密钥会在文本框里明文显示**。
 *
 * <h3>密钥回显为什么是掩码</h3>
 * 后端只回 {@code sk-***abcd} 这样的掩码。用户看到输入框"有内容"就知道配过了；
 * 想换密钥就直接覆盖输入，<b>不动它则提交掩码，后端会识别出来并保留原密钥</b>。
 */
function TtsFields({ value, onChange }: { value: TtsForm; onChange: (v: TtsForm) => void }) {
  const set = <K extends keyof TtsForm>(k: K, v: TtsForm[K]) => onChange({ ...value, [k]: v });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <Alert
        type="info"
        showIcon
        message="填入 API Key 即可使用，其余项保持默认通常就够了"
        description="未填 Key 时插件仍会工作，但产出的是一段占位提示音（界面会标注「占位音」）。"
      />
      <Field label="API Key" hint="必填；留空则沿用已保存的密钥">
        <Input.Password
          placeholder={value.apiKey ? '已配置（要更换请直接输入新的）' : 'sk-...'}
          value={value.apiKey}
          onChange={(e) => set('apiKey', e.target.value)}
        />
      </Field>
      <Field label="服务地址" hint="OpenAI 兼容；换服务商只需改这一项">
        <Input value={value.baseUrl} onChange={(e) => set('baseUrl', e.target.value)} />
      </Field>
      <Field label="模型">
        <Input value={value.model} onChange={(e) => set('model', e.target.value)} />
      </Field>
      <Field label="音色" hint="格式由服务商决定，如 FunAudioLLM/CosyVoice2-0.5B:alex">
        <Input value={value.voice} onChange={(e) => set('voice', e.target.value)} />
      </Field>
      <Space size={12} style={{ width: '100%' }} align="start">
        <Field label="单轮字数上限" hint="成本闸门">
          <InputNumber
            min={1}
            max={2000}
            value={value.maxChars}
            onChange={(v) => set('maxChars', typeof v === 'number' ? v : TTS_DEFAULTS.maxChars)}
            style={{ width: 130 }}
          />
        </Field>
        <Field label="超时" hint="毫秒">
          <InputNumber
            min={500}
            max={60000}
            step={500}
            value={value.timeoutMs}
            onChange={(v) => set('timeoutMs', typeof v === 'number' ? v : TTS_DEFAULTS.timeoutMs)}
            style={{ width: 130 }}
          />
        </Field>
      </Space>
      <Space size={12} align="start">
        <Field label="格式">
          <Select
            value={value.format}
            onChange={(v) => set('format', v)}
            style={{ width: 130 }}
            options={['mp3', 'wav', 'opus', 'aac'].map((f) => ({ value: f, label: f }))}
          />
        </Field>
        <Field label="语速">
          <InputNumber
            min={0.25}
            max={4}
            step={0.25}
            value={value.speed}
            onChange={(v) => set('speed', typeof v === 'number' ? v : TTS_DEFAULTS.speed)}
            style={{ width: 130 }}
          />
        </Field>
      </Space>
    </div>
  );
}

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
  const [attached, setAttached] = useState<Record<string, unknown> | null>(null);
  const [attachOpen, setAttachOpen] = useState(false);
  const [version, setVersion] = useState('');
  const [config, setConfig] = useState('');
  const [enabled, setEnabled] = useState(true);
  /** TTS 插件的结构化配置（其它插件仍用自由 JSON，见 attachModal）。 */
  const [ttsCfg, setTtsCfg] = useState<TtsForm>(TTS_DEFAULTS);

  const isTts = pluginId === TTS_PLUGIN_ID;

  useEffect(() => {
    if (!pluginId) return;
    pluginDetail(pluginId).then(setDetail).catch(() => setDetail(null));
    listAgents(undefined, undefined, 0, 100).then((r) => setAgents(r.items ?? [])).catch(() => {});
  }, [pluginId]);

  const loadAttached = useCallback(async (agentId?: string) => {
    const aid = agentId ?? selAgentId;
    if (!pluginId || !aid) return;
    try {
      const list = await attachments(aid);
      const hit = list.find((a) => a.pluginId === pluginId || a.plugin_id === pluginId);
      setAttached(hit ?? null);
      // 回显已保存的配置。密钥字段后端只给掩码（sk-***abcd），
      // 用户不动它直接保存时，后端会识别出掩码并保留原密钥。
      const saved = hit?.config as Partial<TtsForm> | undefined;
      setTtsCfg(saved ? { ...TTS_DEFAULTS, ...saved } : TTS_DEFAULTS);
    } catch {
      setAttached(null);
    }
  }, [pluginId, selAgentId]);

  useEffect(() => {
    loadAttached();
  }, [loadAttached]);

  const doAttach = async () => {
    if (!pluginId || !selAgentId) {
      message.warning('请先选择智能体');
      return;
    }
    let cfg: Record<string, unknown> | undefined;
    if (isTts) {
      cfg = { ...ttsCfg };
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
        {isTts ? (
          <TtsFields value={ttsCfg} onChange={setTtsCfg} />
        ) : (
          <Input.TextArea
            placeholder={'config（可选 JSON，如 {"voice":"zh-CN-Xiaoxiao"}）'}
            rows={3}
            value={config}
            onChange={(e) => setConfig(e.target.value)}
          />
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