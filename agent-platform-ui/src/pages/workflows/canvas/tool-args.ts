/**
 * 把工具的入参 JSON Schema 转成一份**可编辑的参数骨架**。
 *
 * <h3>为什么单独一个文件（2026-10-12）</h3>
 * 它原本长在 `field-options.ts` 里，但那个文件要 `import` 各 api 模块
 * （`listTools` / `listAgents` …），**在 Node 里跑不起来**（`http.ts` 依赖浏览器环境）。
 * 而这里两个函数都是纯函数，抽出来就能用既有的 `esbuild + node:test` 直接测 ——
 * 与 `chart-spec.ts`、`plugin-config.ts` 是同一个套路。
 *
 * <h3>★ 目标不是"生成正确的参数"</h3>
 * 而是**把参数名摆到用户面前** —— 让他不必再去翻工具文档查"这个工具要传什么"。
 * 所以只取键名与类型占位，**不塞 `description`**：JSON 不支持注释，
 * 硬把说明拼进去反而容易让用户删错括号（那才是真正会让人放弃的功能）。
 */

/** 按 JSON Schema 的类型给一个"空但形状对"的占位。 */
export function placeholderOf(raw: unknown): unknown {
  const t = raw && typeof raw === 'object' ? (raw as Record<string, unknown>).type : undefined;
  switch (t) {
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'array':
      return [];
    case 'object':
      return {};
    default:
      // 字符串与未知类型都给空串：用户要填的就是内容，给别的反而要先删
      return '';
  }
}

/**
 * Schema → 骨架 JSON 文本。
 *
 * <p>枚举（`enum`）会取第一个值作为预填 —— 那比空串更有提示性，
 * 用户看到 `"GET"` 就知道这里该填方法名。</p>
 *
 * @return 骨架 JSON；schema 不可用（null / 不是对象 / 没有 properties）时返回 `{}`
 */
export function schemaToArgsTemplate(schema: unknown): string {
  const props = propertiesOf(schema);
  if (!props) {
    return '{}';
  }
  const out: Record<string, unknown> = {};
  for (const [key, raw] of Object.entries(props)) {
    const enumValues = raw && typeof raw === 'object' ? (raw as Record<string, unknown>).enum : undefined;
    out[key] = Array.isArray(enumValues) && enumValues.length > 0
      ? enumValues[0]
      : placeholderOf(raw);
  }
  return JSON.stringify(out, null, 2);
}

/** 从 schema 里取 properties（不可用时返回 null）。 */
function propertiesOf(schema: unknown): Record<string, unknown> | null {
  if (!schema || typeof schema !== 'object') {
    return null;
  }
  const props = (schema as Record<string, unknown>).properties;
  if (!props || typeof props !== 'object' || Array.isArray(props)) {
    return null;
  }
  return props as Record<string, unknown>;
}

/**
 * 取该工具的**必填参数名**。
 *
 * <p>目前只用于测试与将来的提示文案 —— 骨架会把所有参数都列出来
 * （包括可选的），因为用户需要看到全貌；而"哪些必填"这个信息
 * 在 JSON 里表达不了，留在这里备用。</p>
 */
export function requiredOf(schema: unknown): string[] {
  if (!schema || typeof schema !== 'object') {
    return [];
  }
  const req = (schema as Record<string, unknown>).required;
  return Array.isArray(req) ? req.map(String) : [];
}
