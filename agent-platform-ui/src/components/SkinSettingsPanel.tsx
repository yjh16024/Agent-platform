/**
 * 皮肤设置面板 —— 渲染**皮肤自己声明**的设置项。
 *
 * <p>我们不预定义任何设置项：皮肤通过 {@code exposeSkinCustomization} 声明
 * `{ key, type, label, defaultValue, ... }`，这里按类型渲染控件，改值后写回存储并回调皮肤。
 * 所以任何遵守协议的皮肤都能被这个面板管理，平台不需要认识它们。</p>
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  ColorPicker,
  Divider,
  Empty,
  InputNumber,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Switch,
  Tag,
  TimePicker,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import {
  customizationOf,
  customizedCount,
  isSettingDisabled,
  isSettingVisible,
  resetSkinSettings,
  resolveValues,
  scheduleVisible,
  setSkinSetting,
  subscribeCustomization,
  type SkinSetting,
  type SkinValues,
  type VisibilitySchedule,
} from '../skin/customization';

interface Props {
  skinId: string;
  /** 紧凑模式（放进弹窗时用） */
  compact?: boolean;
}

/** 订阅协议注册表，让面板在皮肤注册/卸载后自动刷新。 */
function useCustomizationRevision(): number {
  const [rev, setRev] = useState(0);
  useEffect(() => subscribeCustomization(() => setRev((v) => v + 1)), []);
  return rev;
}

const toDayjs = (hhmm: string): Dayjs | null => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm ?? '').trim());
  return m ? dayjs(`${String(m[1]).padStart(2, '0')}:${m[2]}`, 'HH:mm') : null;
};

export default function SkinSettingsPanel({ skinId, compact = false }: Props) {
  const rev = useCustomizationRevision();
  const def = useMemo(() => customizationOf(skinId), [skinId, rev]);
  const values: SkinValues = useMemo(() => (def ? resolveValues(def) : {}), [def, rev]);

  if (!def) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <span style={{ fontSize: 12 }}>
            这个皮肤没有声明设置项，或尚未加载。
            <br />
            只有实现了 DSH 皮肤自定义协议（<code>exposeSkinCustomization</code>）的皮肤才会出现在这里。
          </span>
        }
      />
    );
  }

  const modified = customizedCount(skinId);
  const set = (key: string, value: unknown) => setSkinSetting(skinId, key, value as never);

  return (
    <Space direction="vertical" size={compact ? 10 : 14} style={{ width: '100%' }}>
      <Space size={8} wrap>
        <Typography.Text strong style={{ fontSize: 13 }}>
          {def.title || skinId}
        </Typography.Text>
        <Tag>{def.protocol === 2 ? '协议 v2' : '协议 v1'}</Tag>
        {modified > 0 && <Tag color="blue">已自定义 {modified} 项</Tag>}
        <Popconfirm
          title="把所有设置恢复为皮肤默认值？"
          onConfirm={() => resetSkinSettings(skinId)}
          disabled={modified === 0}
        >
          <Button size="small" disabled={modified === 0}>
            恢复默认
          </Button>
        </Popconfirm>
      </Space>

      {def.settings.map((s) => {
        if (!isSettingVisible(s, values)) {
          return null;
        }
        return (
          <div key={s.key}>
            <SettingControl
              setting={s}
              values={values}
              disabled={isSettingDisabled(s, values)}
              onChange={(v) => set(s.key, v)}
            />
          </div>
        );
      })}

      {def.settings.some((s) => s.type === 'visibility-schedule') && (
        <Alert
          type="info"
          showIcon
          style={{ fontSize: 12 }}
          message="时间段的判定由平台按本机时间实时计算，每分钟刷新一次"
        />
      )}
    </Space>
  );
}

function SettingControl({
  setting,
  values,
  disabled,
  onChange,
}: {
  setting: SkinSetting;
  values: SkinValues;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  const label = setting.label;
  const desc = setting.description;

  const head = (
    <div>
      <Typography.Text style={{ fontSize: 13 }}>{label}</Typography.Text>
      {desc && (
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            {desc}
          </Typography.Text>
        </div>
      )}
    </div>
  );

  /*
   * 一行 = 左边文字 + 右边控件。
   *
   * **必须允许换行、且左边不许被压缩。**
   * 原来只有 `justify-content: space-between`：左边是默认的 `flex: 0 1 auto`（可压缩），
   * 右边是 `flex: 0 0 auto`。于是控件一宽（比如 Select）就把左边文字挤到
   * **min-content = 一个汉字** 宽 —— 界面表现就是"一字一行"的竖排（实测踩过）。
   * 现在：容器可换行、文字 `flex: 1 0 auto`（不压缩），放不下时控件自动落到下一行。
   */
  const row = (control: React.ReactNode) => (
    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
      <div style={{ flex: '1 0 auto' }}>{head}</div>
      <div style={{ flex: '0 0 auto', marginLeft: 'auto' }}>{control}</div>
    </div>
  );

  switch (setting.type) {
    case 'boolean':
      return row(
        <Switch
          size="small"
          disabled={disabled}
          checked={values[setting.key] === true}
          onChange={(v) => onChange(v)}
        />
      );

    case 'select':
      return row(
        <Select
          size="small"
          style={{ minWidth: 150 }}
          disabled={disabled}
          value={values[setting.key] as string}
          onChange={(v) => onChange(v)}
          options={setting.options.map((o) => ({ value: o.value, label: o.label }))}
        />
      );

    case 'range':
      return row(
        <Space size={6}>
          <InputNumber
            size="small"
            disabled={disabled}
            min={setting.min}
            max={setting.max}
            step={setting.step ?? 1}
            value={values[setting.key] as number}
            onChange={(v) => onChange(v ?? setting.min)}
          />
          {setting.unit && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {setting.unit}
            </Typography.Text>
          )}
        </Space>
      );

    case 'color':
      return row(
        <ColorPicker
          size="small"
          disabled={disabled}
          value={(values[setting.key] as string) ?? '#000000'}
          onChangeComplete={(c) => onChange(c.toHexString())}
        />
      );

    case 'checkbox-group':
      return (
        <div>
          {head}
          <Checkbox.Group
            disabled={disabled}
            style={{ marginTop: 6 }}
            value={(values[setting.key] as string[]) ?? []}
            onChange={(v) => onChange(v)}
            options={setting.options.map((o) => ({ value: o.value, label: o.label }))}
          />
        </div>
      );

    case 'visibility-schedule': {
      const sch = (values[setting.key] as VisibilitySchedule) ?? { enabled: false, outside: 'visible', ranges: [] };
      const nowVisible = scheduleVisible(sch, new Date());
      return (
        <div>
          {/* 同上：可换行 + 左边不压缩，避免文字被挤成竖排 */}
          <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
            <div style={{ flex: '1 0 auto' }}>
              {head}
              <Tag color={nowVisible ? 'green' : 'default'} style={{ marginTop: 4 }}>
                此刻：{nowVisible ? '显示' : '隐藏'}
              </Tag>
            </div>
            <Switch
              size="small"
              disabled={disabled}
              checked={sch.enabled}
              onChange={(v) => onChange({ ...sch, enabled: v })}
            />
          </div>

          {sch.enabled && (
            <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 8 }}>
              <Space size={6} wrap>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  时间段之外
                </Typography.Text>
                <Segmented
                  size="small"
                  value={sch.outside}
                  onChange={(v) => onChange({ ...sch, outside: v as 'visible' | 'hidden' })}
                  options={[
                    { label: '显示', value: 'visible' },
                    { label: '隐藏', value: 'hidden' },
                  ]}
                />
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  （时间段之内自动取其反）
                </Typography.Text>
              </Space>

              <TimePicker.RangePicker
                size="small"
                format="HH:mm"
                minuteStep={5}
                value={
                  sch.ranges.length > 0 && sch.ranges[0]
                    ? [toDayjs(sch.ranges[0].start), toDayjs(sch.ranges[0].end)]
                    : null
                }
                onChange={(v) => {
                  if (!v || !v[0] || !v[1]) {
                    onChange({ ...sch, ranges: [] });
                    return;
                  }
                  onChange({ ...sch, ranges: [{ start: v[0].format('HH:mm'), end: v[1].format('HH:mm') }] });
                }}
              />
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                支持跨零点（如 22:00 → 06:00）
              </Typography.Text>
            </Space>
          )}
        </div>
      );
    }

    default:
      return (
        <div>
          {head}
          <Divider style={{ margin: '6px 0' }} />
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            平台暂不支持该控件类型：{(setting as { type: string }).type}
          </Typography.Text>
        </div>
      );
  }
}
