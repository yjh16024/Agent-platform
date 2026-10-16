/**
 * 画布字段的「从平台选」控件。
 *
 * <h3>★ 为什么用 AutoComplete 而不是 Select（2026-10-12）</h3>
 * 用户的要求是「**下拉选择平台已有的内容，同时也支持自己填写**」。
 * antd 的 `Select` 做不到后半句：它的值必须来自 `options`
 * （`mode="tags"` 能输入任意值，但值是**数组**，与本字段的 string 语义不符）。
 * `AutoComplete` 本来就是「可输入的下拉」—— 既能点选，也能手打，
 * 且值是字符串，与 `CanvasFieldSpec` 的既有约定一致。
 *
 * <h3>★ 为什么保留手填这条路</h3>
 * 下拉不是万能的：批量建工作流时会用脚本写定义；参数里可能引用变量
 * （如 `config.tool_name` 填 `${chosen_tool}`）；清单也可能因权限不足拉不全。
 * 所以**不把输入锁死**是必要的，只是把"必须背 id"变成了"默认能选"。
 *
 * <h3>⚠️ 拉不到时不能用空下拉</h3>
 * 空下拉会让用户以为"平台里什么都没有"，比让他手填还糟。
 * 所以：拉不到 / 拉回来是空 → **退回 `spec.options`（若有）**，再不行就纯手填。
 */
import { useEffect, useState } from 'react';
import { AutoComplete, Select, Spin } from 'antd';
import { loadAllFieldOptions, loadFieldOptions, type FieldOption } from './field-options';
import { joinMulti, splitMulti } from './field-value';
import type { CanvasFieldSpec } from './node-metas';

interface Props {
  spec: CanvasFieldSpec;
  value: unknown;
  onChange: (v: unknown) => void;
  /** 选中某项后把它的参数模板交给父组件（用于"选工具 → 带出参数"）。 */
  onArgsTemplate?: (template: string) => void;
  /** 取参数模板（按选中的值）。由父组件注入，便于复用同一份实现。 */
  argsTemplateOf?: (value: string) => Promise<string | null>;
}

export function DynamicSelect({ spec, value, onChange, onArgsTemplate, argsTemplateOf }: Props) {
  const [options, setOptions] = useState<FieldOption[]>((spec.options as FieldOption[]) ?? []);
  const [loading, setLoading] = useState(!!spec.source);

  useEffect(() => {
    if (!spec.source) {
      setOptions((spec.options as FieldOption[]) ?? []);
      return;
    }
    let alive = true;
    setLoading(true);
    loadFieldOptions(spec.source)
      .then((list) => {
        if (!alive) return;
        // 清单为空（或无权限）时退回静态候选 —— 宁可给一份可能过时的清单，也不要空下拉
        setOptions(list.length > 0 ? list : ((spec.options as FieldOption[]) ?? []));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [spec.source, spec.options]);

  const handleChange = async (next: string) => {
    onChange(next);
    /*
     * ★ 「选完工具自动带出参数」。
     * 只在该目标字段为空时由父组件决定是否填充 —— 见 NodeFormPanel 里对
     * `spec.argsTemplateTo` 的处理（不能让选一次工具就冲掉用户手写的参数）。
     */
    if (next && spec.argsTemplateTo && argsTemplateOf && onArgsTemplate) {
      const tpl = await argsTemplateOf(next);
      if (tpl) onArgsTemplate(tpl);
    }
  };

  /*
   * 多值字段（目前只有知识库 kb_ids）。
   *
   * ⚠️ 用 `mode="tags"` 而不是 `mode="multiple"`：后者只能从选项里挑，
   * 而这个项目里"知识库还没同步出来"或"用脚本建的库"都可能出现，
   * 那时用户得能直接粘 ID。tags 两者都支持。
   *
   * 值在"数组 ↔ 逗号分隔字符串"之间转换 —— 后端读的就是那个字符串形式。
   */
  if (spec.kind === 'multiSelect') {
    return (
      <Select
        mode="tags"
        style={{ width: '100%' }}
        value={splitMulti(value)}
        options={options}
        loading={loading}
        placeholder={spec.placeholder ?? '从列表选择，或粘贴 ID 后回车'}
        notFoundContent={loading ? <Spin size="small" /> : '没有可选项，输入后回车可新增'}
        onChange={(vals: string[]) => onChange(joinMulti(vals))}
        allowClear
      />
    );
  }

  return (
    <AutoComplete
      style={{ width: '100%' }}
      value={(value as string) ?? ''}
      options={options}
      placeholder={spec.placeholder ?? (spec.source ? '从列表选择，或直接输入' : undefined)}
      // 子串匹配即可：工具名往往带前缀，用户记得的是中间那段
      filterOption={(input, option) =>
        String(option?.value ?? '').toLowerCase().includes(input.toLowerCase()) ||
        String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
      }
      notFoundContent={loading ? <Spin size="small" /> : '没有可选项，可直接输入'}
      onChange={handleChange}
      allowClear
    />
  );
}

/** 预热清单（打开画布时调一次，避免每次点开节点才拉）。 */
export function prewarmFieldOptions(): void {
  void loadAllFieldOptions();
}
