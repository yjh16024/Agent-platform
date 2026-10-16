/**
 * 画布字段值的**编解码**（纯函数，便于单测）。
 *
 * <p>多值字段（目前只有知识库 `kb_ids`）在 `node.data` 与后端 `WorkflowNode.config`
 * 里都是**一个字符串**（后端 `KnowledgeBaseNodeExecutor` 按逗号/换行切开），
 * 而界面上的多选控件要的是数组。这里就是那层转换。</p>
 */

/**
 * 多值字符串 → 数组。
 *
 * <h3>★ 为什么分隔符要认三种</h3>
 * 用户来源不止"在下拉里点选"：
 * <ul>
 *   <li>从知识库页复制 ID —— 单个，无所谓；</li>
 *   <li>从别处粘贴一段列表 —— **很可能是换行分隔**（从表格里复制就是这样）；</li>
 *   <li>中文输入法下打逗号 —— 出的是 **`，`（全角）**。</li>
 * </ul>
 * 只认半角逗号的话，后两种会被当成**一个超长的 ID**，
 * 而表现是"选了好几个、保存也过了，运行时说找不到知识库" —— 很难联想到分隔符。
 */
export function splitMulti(raw: unknown): string[] {
  if (typeof raw !== 'string') {
    return [];
  }
  return raw
    .split(/[,，\n\r]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 数组 → 多值字符串（统一用半角逗号，便于后端与用户阅读）。 */
export function joinMulti(values: readonly string[]): string {
  return values
    .map((s) => String(s).trim())
    .filter(Boolean)
    .join(',');
}
