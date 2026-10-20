/**
 * 插件的 i18n 支持（2026-10-19，P3）。
 *
 * <h3>为什么只做这么小</h3>
 * 宿主自己没有完整的 i18n 体系（界面文案是中文硬编码 + antd 的 locale）。
 * 与其给插件造一套"看起来很正规"的框架，不如提供**它真正需要的三件事**：
 * ① 现在是什么语言；② 注册自己的词典；③ 按 key 取文案（带回落）。
 * 插件可以只注册 zh/en，也可以用 `t(key, fallback)` 直接给默认文案。
 *
 * <h3>语言从哪来</h3>
 * `navigator.language`（Electron 里就是系统语言）。不提供"应用内切换语言"——
 * 宿主本身没有这个开关，造一个只会让两边不一致。
 */

const dicts: Record<string, Record<string, string>> = {};

/** 宿主当前语言：中文环境返回 `zh`，其余 `en`。 */
export function currentLang(): 'zh' | 'en' {
  const l = (typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'zh').toLowerCase();
  return l.startsWith('zh') ? 'zh' : 'en';
}

/**
 * 注册词典：`{ zh: { hello: '你好' }, en: { hello: 'Hi' } }`。
 *
 * <p>多次注册会合并（同一 key 后注册的覆盖先前的）—— 插件可以在不同模块里各注册一部分。</p>
 */
export function registerMessages(messages: Record<string, Record<string, string>>): void {
  for (const [lang, dict] of Object.entries(messages ?? {})) {
    dicts[lang] = { ...(dicts[lang] ?? {}), ...(dict ?? {}) };
  }
}

/**
 * 取文案。回落顺序：当前语言 → en → zh → fallback → key 本身。
 *
 * <p>最后回落到 key 而不是空串：这样缺失的文案在界面上**看得见**（能立刻发现漏翻译），
 * 而不是留一块空白让人以为布局坏了。</p>
 */
export function translate(key: string, fallback?: string): string {
  const lang = currentLang();
  return dicts[lang]?.[key] ?? dicts.en?.[key] ?? dicts.zh?.[key] ?? fallback ?? key;
}
