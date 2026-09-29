/**
 * 插件配置表单的**纯逻辑**：声明解析、初值组装、类型转换、校验。
 *
 * <h3>为什么与组件分开</h3>
 * 这些函数是零依赖的纯函数，于是可以用 `esbuild + node:test` 直接测
 * （与 `pages/chat/markdown/chart-spec.ts` 同一个套路）——
 * 不必为了测几行类型转换去搭一套组件测试设施。
 * 而 `PluginConfigForm.tsx` 依赖 antd，只能留在 React 一侧。
 */

/** 插件声明的配置项（后端 `ConfigFieldDef` 的镜像）。 */
export interface PluginConfigField {
  key: string;
  label: string;
  kind: 'text' | 'textarea' | 'number' | 'select' | 'bool';
  /** 默认值（字符串形式，按 kind 转换）。 */
  defaultValue?: string | null;
  required?: boolean;
  /** 密钥字段：密码框 + 掩码回显。 */
  secret?: boolean;
  options?: { label: string; value: string }[] | null;
  min?: number | null;
  max?: number | null;
  step?: number | null;
  placeholder?: string | null;
  hint?: string | null;
}

/** 合法的控件类型；后端给了别的值一律按 text 处理。 */
const KINDS = ['text', 'textarea', 'number', 'select', 'bool'] as const;
type Kind = (typeof KINDS)[number];

/**
 * 从插件详情里取出配置声明。
 *
 * <p>声明藏在 {@code manifest.contributes.config}（与外部插件的 plugin.yaml 同一结构），
 * 所以这里要在一棵 any 树上做**逐字段校验**：manifest 是后端与插件作者共同写入的自由 JSON，
 * 直接当类型用会在遇到意外结构时让整个弹窗崩掉 —— 而"某个插件声明写错了"
 * 不该影响用户挂载别的插件。</p>
 *
 * @param manifest 插件详情的 manifest 字段
 * @return 声明列表；没有声明或结构不可识别时返回空数组（调用方据此退回自由 JSON 输入）
 */
export function configFieldsOf(manifest: unknown): PluginConfigField[] {
  const m = manifest as Record<string, unknown> | undefined | null;
  const contributes = m?.contributes as Record<string, unknown> | undefined;
  const raw = contributes?.config;
  if (!Array.isArray(raw)) return [];

  const out: PluginConfigField[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const f = item as Record<string, unknown>;
    const key = typeof f.key === 'string' ? f.key : '';
    if (!key) continue; // 没有键名的声明无法写回配置，跳过
    const kind = KINDS.includes(f.kind as Kind) ? (f.kind as Kind) : 'text';
    out.push({
      key,
      label: typeof f.label === 'string' && f.label ? f.label : key,
      kind,
      defaultValue: typeof f.defaultValue === 'string' ? f.defaultValue : null,
      required: f.required === true,
      secret: f.secret === true,
      options: Array.isArray(f.options)
        ? (f.options as { label?: unknown; value?: unknown }[])
            .filter((o) => o && typeof o === 'object')
            .map((o) => ({ label: String(o.label ?? o.value ?? ''), value: String(o.value ?? '') }))
        : null,
      min: typeof f.min === 'number' ? f.min : null,
      max: typeof f.max === 'number' ? f.max : null,
      step: typeof f.step === 'number' ? f.step : null,
      placeholder: typeof f.placeholder === 'string' ? f.placeholder : null,
      hint: typeof f.hint === 'string' ? f.hint : null,
    });
  }
  return out;
}

/**
 * 把字符串按 kind 转成合适的类型。
 *
 * <p>{@code bool} 与 {@code number} 必须真的存成 boolean / number ——
 * 插件的 {@code of(JsonNode)} 用的是 {@code asBoolean} / {@code asDouble}，
 * 传字符串 {@code "false"} 会被判成"存在但不是布尔"，结果与预期相反。</p>
 */
export function coerce(field: PluginConfigField, raw: unknown): unknown {
  switch (field.kind) {
    case 'bool':
      return raw === true || raw === 'true';
    case 'number': {
      if (raw === '' || raw == null) return undefined;
      const n = Number(raw);
      if (!Number.isFinite(n)) return undefined;
      // 后端也会 clamp；这里先夹一道是为了让输入框里的值立刻合法
      let v = n;
      if (typeof field.min === 'number') v = Math.max(field.min, v);
      if (typeof field.max === 'number') v = Math.min(field.max, v);
      return v;
    }
    default:
      return raw == null ? '' : String(raw);
  }
}

/**
 * 组装表单初值：**先铺声明的默认值，再用已保存的配置覆盖**。
 *
 * <p>两个都要，缺一不可：只铺默认值时，用户上次填的会被清掉（表现为"配置丢了"）；
 * 只用已保存值时，第一次挂载看到的是空表单，而默认值本身就是"留空会发生什么"的说明。</p>
 *
 * <p>只接受**声明过的**键：未声明的键由后端在合并时保留 ——
 * 前端不认识它，贸然提交反而可能覆盖成空（见 `PluginService.attach` 的合并说明）。</p>
 */
export function buildInitialConfig(
  fields: PluginConfigField[],
  saved?: Record<string, unknown> | null,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of fields) {
    out[f.key] = coerce(f, f.defaultValue ?? '');
  }
  if (saved) {
    for (const f of fields) {
      if (Object.prototype.hasOwnProperty.call(saved, f.key)) {
        out[f.key] = saved[f.key] ?? out[f.key];
      }
    }
  }
  return out;
}

/**
 * 找出未填的必填项，返回它们的标签。
 *
 * <p>⚠️ **掩码不算"未填"**：密钥框里显示的是 {@code sk-***abcd}，它非空但也不是真密钥。
 * 若把它当"已填"放过，用户可能存下一个掩码；若把它当"未填"拦下，
 * 用户就没法只改别的字段了。所以判据是"非空即通过" ——
 * 而"不把掩码存成密钥"这件事由后端的掩码识别保证（`PluginConfigSecrets.seal`）。</p>
 */
export function missingRequired(
  fields: PluginConfigField[],
  values: Record<string, unknown>,
): string[] {
  const miss: string[] = [];
  for (const f of fields) {
    if (!f.required) continue;
    const v = values[f.key];
    const empty = v == null || (typeof v === 'string' && v.trim() === '');
    if (empty) miss.push(f.label || f.key);
  }
  return miss;
}

/**
 * 提交前把空值剔除。
 *
 * <p>空字符串不提交：对敏感键，后端的 {@code seal} 会"沿用已有值"，
 * 传空与不传效果相同；对非敏感键则会覆盖成空串，让插件自己声明的默认值失效
 * （例如 TTS 的 baseUrl 被清空后，`of()` 才回退默认值 —— 能跑，但不如不传干净）。
 * 而"我没动这一栏"本来就不该被表达成"把它设成空"。</p>
 */
export function prunedConfig(values: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v === '' || v == null) continue;
    out[k] = v;
  }
  return out;
}
