import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert, App as AntApp, Button, Card, Input, InputNumber, Select, Space, Spin, Switch, Tag, Typography,
} from 'antd';
import {
  appBridge, getPlatformSettings, resetPlatformSettings, updatePlatformSettings,
} from '../../api/platformSettings';
import type { PlatformSettingItem, PlatformSettingsView } from '../../api/platformSettings';

const { Text, Paragraph } = Typography;

function labelOf(view: PlatformSettingsView | null, key: string): string {
  for (const g of view?.groups ?? []) {
    for (const it of g.items) {
      if (it.key === key) return it.label;
    }
  }
  return key;
}

/*
 * 「平台设置 → 功能开关」层。
 *
 * 交互契约（与后端 PlatformSettingService 对应）：
 *   1) 变更即保存（开关拨动 / 下拉选择直接提交单个键）—— "打开或关闭开关后写入"；
 *   2) 需要重启的项**写入成功但不会生效**，后端在 restartRequiredKeys 里如实返回，
 *      界面弹窗告知并提供「立即重启应用」（仅桌面壳有该能力，浏览器里隐藏、只提示手动重启）；
 *   3) 「恢复默认」= 删掉覆盖层的那一行，值回落配置文件 / 环境变量。
 *
 * 为什么"变更即保存"而不是整页一个保存按钮：这些开关本来就是"拨一下就生效"的心智模型，
 * 攒成一个批量表单反而让"改没改"变得不可见（用户拨了开关却在想"要不要点保存"）。
 */
export default function FeatureTogglesTab() {
  const { message, modal } = AntApp.useApp();
  const [view, setView] = useState<PlatformSettingsView | null>(null);
  const [loading, setLoading] = useState(false);
  /** 正在提交的键（请求期间禁用对应控件，防连点两下发出两次写入）。 */
  const [busy, setBusy] = useState<Set<string>>(new Set());

  const load = async () => {
    setLoading(true);
    try {
      setView(await getPlatformSettings());
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const askRestart = (keys: string[], latest: PlatformSettingsView) => {
    const bridge = appBridge();
    modal.confirm({
      title: '设置已保存，需重启应用后生效',
      width: 520,
      icon: null,
      content: (
        <div style={{ marginTop: 8 }}>
          <Paragraph style={{ marginBottom: 8 }}>
            以下设置需要<Text strong>重启应用</Text>后才会生效：
          </Paragraph>
          <ul style={{ margin: '0 0 10px 0', paddingLeft: 20 }}>
            {keys.map((k) => <li key={k}>{labelOf(latest, k)}</li>)}
          </ul>
          <Text type="secondary">
            {bridge
              ? '可以立即重启（页面会短暂断开），也可以稍后手动重启应用。'
              : '请稍后手动重启后端服务后生效。'}
          </Text>
        </div>
      ),
      okText: bridge ? '立即重启应用' : '知道了',
      cancelText: bridge ? '稍后手动重启' : undefined,
      cancelButtonProps: bridge ? undefined : { style: { display: 'none' } },
      onOk: bridge ? () => bridge.restart() : undefined,
    });
  };

  const afterSave = (res: { view: PlatformSettingsView; restartRequiredKeys: string[] }) => {
    setView(res.view);
    if (res.restartRequiredKeys.length > 0) {
      askRestart(res.restartRequiredKeys, res.view);
    } else {
      message.success('已保存，即时生效');
    }
  };

  const save = async (key: string, value: string) => {
    setBusy((prev) => new Set(prev).add(key));
    try {
      afterSave(await updatePlatformSettings({ [key]: value }));
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setBusy((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  };

  const reset = async (key: string) => {
    setBusy((prev) => new Set(prev).add(key));
    try {
      afterSave(await resetPlatformSettings([key]));
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setBusy((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  };

  return (
    <div>
      {view?.runtimeMode === 'embedded' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="当前为桌面（内置库）模式：依赖外部服务的项（MinIO / Milvus / Kafka）在本机通常不可用，被环境强制的项已标注。"
        />
      )}
      <Spin spinning={loading}>
        {(view?.groups ?? []).map((group) => (
          <Card key={group.name} title={group.name} size="small" style={{ marginBottom: 12 }}>
            {group.items.map((item) => (
              <SettingRow
                key={item.key}
                item={item}
                busy={busy.has(item.key)}
                onSave={save}
                onReset={reset}
              />
            ))}
          </Card>
        ))}
      </Spin>
    </div>
  );
}

/** 单项一行：左说明、右控件。NUMBER / LIST 用「草稿 + 应用」两步（输入中途不该触发保存）。 */
function SettingRow({
  item, busy, onSave, onReset,
}: {
  item: PlatformSettingItem;
  busy: boolean;
  onSave: (key: string, value: string) => void;
  onReset: (key: string) => void;
}) {
  const [draft, setDraft] = useState(item.value);
  useEffect(() => {
    setDraft(item.value);
  }, [item.value]);

  const disabled = item.locked || busy;
  const dirty = draft.trim() !== item.value;

  let control: ReactNode;
  if (item.locked) {
    control = <Text type="secondary">已锁定</Text>;
  } else if (item.type === 'BOOL') {
    control = (
      <Switch
        checked={item.value === 'true'}
        disabled={busy}
        onChange={(checked) => onSave(item.key, checked ? 'true' : 'false')}
      />
    );
  } else if (item.type === 'ENUM') {
    control = (
      <Select
        size="small"
        style={{ width: 160 }}
        value={item.value}
        disabled={busy}
        options={item.options.map((o) => ({ value: o, label: o }))}
        onChange={(v) => onSave(item.key, v)}
      />
    );
  } else if (item.type === 'NUMBER') {
    control = (
      <Space.Compact>
        <InputNumber
          size="small"
          min={0}
          style={{ width: 110 }}
          value={draft === '' ? null : Number(draft)}
          disabled={disabled}
          onChange={(v) => setDraft(v == null ? '' : String(v))}
        />
        <Button size="small" type="primary" disabled={disabled || !dirty} onClick={() => onSave(item.key, draft)}>
          应用
        </Button>
      </Space.Compact>
    );
  } else {
    // LIST：逗号分隔的工具名清单
    control = (
      <Space.Compact style={{ width: 300 }}>
        <Input
          size="small"
          value={draft}
          disabled={disabled}
          placeholder="逗号分隔，留空 = 不禁用"
          onChange={(e) => setDraft(e.target.value)}
          onPressEnter={() => dirty && onSave(item.key, draft)}
        />
        <Button size="small" type="primary" disabled={disabled || !dirty} onClick={() => onSave(item.key, draft)}>
          应用
        </Button>
      </Space.Compact>
    );
  }

  return (
    <div
      style={{
        display: 'flex', justifyContent: 'space-between', gap: 16,
        padding: '10px 0', borderBottom: '1px solid rgba(0,0,0,0.06)',
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <Space size={6} wrap>
          <Text strong>{item.label}</Text>
          {item.restartRequired && <Tag color="orange">需重启</Tag>}
          {item.overridden && <Tag>已自定义</Tag>}
          {item.locked && <Tag color="red">环境锁定</Tag>}
        </Space>
        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>{item.description}</Text>
        </div>
        {item.locked && item.lockReason && (
          <div>
            <Text type="warning" style={{ fontSize: 12 }}>{item.lockReason}</Text>
          </div>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {control}
        {item.overridden && !item.locked && (
          <Button type="link" size="small" disabled={busy} onClick={() => onReset(item.key)}>
            恢复默认
          </Button>
        )}
      </div>
    </div>
  );
}
