import { useCallback, useEffect, useMemo, useState } from 'react';
import { App as AntApp, Button, Tag, Tooltip, Typography } from 'antd';
import { invokeTool } from '../api/tools';
import type { UiContribution } from '../api/uiSlots';
import { useUiSlotStore } from '../store/uiSlotStore';
import { SUPPORTED_SLOTS, SUPPORTED_TYPES, type SlotId } from './registry';

const { Text } = Typography;

/**
 * 槽位出口 —— 宿主在**允许插件注入的位置**放一个它，插件声明的东西就会渲染在这里。
 *
 * <h3>三条硬约束（都在这里落实）</h3>
 * <ol>
 *   <li><b>文本一律按纯文本渲染</b>。插件的 {@code label} 是<b>不可信输入</b>，
 *       绝不走 {@code dangerouslySetInnerHTML} —— 那等于给每个插件一个 XSS 入口，
 *       而这个通道的初衷恰恰是"不用执行第三方代码也能扩展界面"。</li>
 *   <li><b>认不出的槽位与组件类型静默跳过</b>。表现是"这一项不显示"，而不是报错 ——
 *       这样插件用新类型时，旧前端只是少画一块，不会整页崩。</li>
 *   <li><b>任何失败都不影响主界面</b>。拉取失败退化为空列表；动作失败只弹一条提示。</li>
 * </ol>
 *
 * <h3>★ 空槽位渲染成 null（不占位）</h3>
 * 绝大多数时候这里什么都没有。若渲染成一个空 div，会在布局里留下看不见的间隙 ——
 * 那种"对不齐"极难排查（谁也想不到是插件槽位留下的）。
 */
export default function SlotOutlet({ slot, agentId }: { slot: SlotId; agentId?: string }) {
  const bucket = useUiSlotStore((s) => (agentId ? s.byAgent[agentId] : undefined));
  const load = useUiSlotStore((s) => s.load);

  useEffect(() => {
    if (agentId) {
      void load(agentId);
    }
  }, [agentId, load]);

  const items = useMemo(() => {
    if (!bucket || bucket.length === 0 || !SUPPORTED_SLOTS.has(slot)) {
      return EMPTY;
    }
    const hit = bucket.filter((c) => c.slot === slot && c.type && SUPPORTED_TYPES.has(c.type));
    if (hit.length === 0) {
      return EMPTY;
    }
    // 稳定排序：order 小的在前；同 order 按 pluginId，保证多次渲染顺序一致（否则会跳动）
    return [...hit].sort((a, b) => {
      const d = (a.order ?? 0) - (b.order ?? 0);
      return d !== 0 ? d : a.pluginId.localeCompare(b.pluginId);
    });
  }, [bucket, slot]);

  if (items.length === 0) {
    return null;
  }
  return (
    <>
      {items.map((c) => (
        <SlotItem key={`${c.pluginId}:${c.id ?? c.label ?? c.type}`} item={c} />
      ))}
    </>
  );
}

const EMPTY: UiContribution[] = [];

/**
 * 单个界面元素。
 *
 * <p>独立成组件是因为它要自己持有 {@code dataSource} 的状态（拉了没、值是多少），
 * 而这些状态互不相关 —— 放在一起会让任意一个刷新都重渲染整片槽位。</p>
 */
function SlotItem({ item }: { item: UiContribution }) {
  const { message } = AntApp.useApp();
  const [data, setData] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const tool = item.dataSource?.tool;
  const fetchData = useCallback(async () => {
    if (!tool) {
      return;
    }
    try {
      const r = await invokeTool(tool, {});
      setData(textOf(r?.output, r?.error));
    } catch (e) {
      // 数据取不到就显示占位，而不是让整个元素消失（否则用户以为插件没挂上）
      setData(`(${(e as Error).message})`);
    }
  }, [tool]);

  useEffect(() => {
    void fetchData();
  }, [fetchData]);

  const runAction = async () => {
    const name = item.action?.tool;
    if (!name) {
      return;
    }
    setBusy(true);
    try {
      const r = await invokeTool(name, {});
      if (r?.success === false) {
        message.warning(textOf(null, r?.error) || '插件动作未成功');
      } else {
        message.success(textOf(r?.output, null) || '已执行');
      }
      // 动作常常会改变数据（如"刷新用量"），顺手重拉一次
      void fetchData();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const clickable = Boolean(item.action?.tool);
  const label = item.label ?? '';

  switch (item.type) {
    case 'button':
      return (
        <Tooltip title={item.pluginName ? `来自插件：${item.pluginName}` : undefined}>
          <Button size="small" loading={busy} disabled={!clickable} onClick={runAction}>
            {label || '操作'}
          </Button>
        </Tooltip>
      );

    case 'badge':
      return (
        <Tooltip title={item.pluginName ? `来自插件：${item.pluginName}` : undefined}>
          <Tag
            style={{ cursor: clickable ? 'pointer' : 'default', marginInlineEnd: 0 }}
            onClick={clickable ? runAction : undefined}
          >
            {label}
            {data ? ` ${data}` : ''}
          </Tag>
        </Tooltip>
      );

    case 'link':
      return item.href ? (
        <a href={item.href} target="_blank" rel="noreferrer" style={{ fontSize: 12 }}>
          {label || item.href}
        </a>
      ) : null;

    case 'card':
      return (
        <div
          style={{
            border: '1px solid rgba(0,0,0,0.08)',
            borderRadius: 6,
            padding: '6px 10px',
            marginBottom: 8,
            fontSize: 12,
          }}
        >
          <Text strong>{label}</Text>
          {data && (
            <div style={{ marginTop: 2, color: 'rgba(0,0,0,0.65)', whiteSpace: 'pre-wrap' }}>
              {data}
            </div>
          )}
          {clickable && (
            <Button size="small" type="link" loading={busy} onClick={runAction} style={{ paddingLeft: 0 }}>
              刷新
            </Button>
          )}
        </div>
      );

    case 'list':
      return (
        <div style={{ fontSize: 12, marginBottom: 6 }}>
          <Text strong>{label}</Text>
          <div style={{ whiteSpace: 'pre-wrap', color: 'rgba(0,0,0,0.65)' }}>{data ?? '…'}</div>
        </div>
      );

    default:
      // 后端已过滤过类型；走到这里说明前端比后端旧 —— 静默跳过（与 registry 的约定一致）
      return null;
  }
}

/**
 * 把工具返回值转成**可直接显示的短文本**。
 *
 * <p>工具返回的是 `JsonNode`，可能是字符串、数字，也可能是对象。对象的 {@code String()}
 * 会变成 {@code [object Object]} —— 那是最糟的结果（用户看不懂、还不知道哪里错了），
 * 所以对象一律降级为 JSON 文本：难看，但信息没丢。</p>
 */
function textOf(output: unknown, error?: unknown): string {
  if (output == null) {
    return error == null ? '' : String(error);
  }
  if (typeof output === 'string') {
    return output;
  }
  if (typeof output === 'number' || typeof output === 'boolean') {
    return String(output);
  }
  try {
    return JSON.stringify(output);
  } catch {
    return String(output);
  }
}
