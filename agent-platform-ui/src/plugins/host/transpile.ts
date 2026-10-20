/**
 * **受限** ESM → IIFE 转译器（2026-10-19，P3）。
 *
 * <h3>它为什么存在</h3>
 * DSH 插件的浏览器侧入口是 **ESM**（`import … from '@deepseek-ai/dsh-client-runtime'` +
 * `export default { name, inject, apply }`）。浏览器不能直接执行裸 ESM 的 bare specifier，
 * 而 `importmap` 只支持**页面加载期静态声明**、不能给"运行时装进来的插件"动态加 ——
 * 所以必须在装载时把 import 改写成对宿主模块表的访问。
 *
 * <h3>★ 为什么自己写而不是用 esbuild</h3>
 * esbuild 要新增依赖（web 版还是 10MB 的 wasm）。而**单文件 Client 插件**的语法其实很有限：
 * 顶部 import + 一个 `export default`。用**受限改写**覆盖这一子集即可；
 * 遇到超出子集的语法（`export const` / 相对导入 / 顶层 await）就**明确报错**，
 * 让用户知道"这个插件需要打包器"——而不是装作能跑。
 *
 * <h3>支持的语法（明确列出来，避免误判"为什么我的插件跑不了"）</h3>
 * | 写法 | 处理 |
 * |---|---|
 * | `import X from 'm'` | → `const X = (require('m').default ?? require('m'))` |
 * | `import { a, b as c } from 'm'` | → `const { a, b: c } = require('m')` |
 * | `import X, { a } from 'm'` | → 先取模块到临时变量，再分别解构 |
 * | `import * as X from 'm'` | → `const X = require('m')` |
 * | `import 'm'` | → `require('m')` |
 * | `export default <表达式>` | → `__apModule.exports = <表达式>` |
 * | `export default function/class Name` | 同上（赋值表达式合法） |
 *
 * <h3>不支持的语法（抛错，不静默）</h3>
 * `export const/let/var/function/class`、`export { … }`、`export * from`、
 * 相对导入（`./x.js`，需要打包器）、顶层 `await`。
 *
 * <p>⚠️ 这里**不是**通用 JS 解析器：它按"顶部 import + 单 export default"这个常见形态工作，
 * 不处理 import 出现在函数体内等边角情况（字符串里出现 `import … from` 会误判 ——
 * 这也是为什么错误信息要带上"原句"，便于作者判断是转译器误伤还是真不支持）。</p>
 */

export interface TranspileResult {
  code: string;
  /** 转译过程中识别到的风险（目前只用于日志/诊断，不阻断） */
  notes: string[];
}

/** 超过这个长度的单文件插件先怀疑是打包产物（多半含相对 import），报错更有用。 */
const MAX_SOURCE_BYTES = 2 * 1024 * 1024;

/**
 * ★ 两条正则都必须**锚定行首**（`^[ \t]*import`）。
 *
 * <p>踩过的坑：不带锚定时，注释里的这句话
 * 「把 import 改写成 `__apHost.require(...)`、把 export default」
 * 会被当成一条 import 语句的开头，`[\s\S]*?` 一路吃到**下一行真正的** `from '…'`，
 * 于是 clause 变成半句中文 —— 转译直接失败，而报错指向"不支持的 import 写法"，
 * 完全看不出是注释惹的祸（2026-10-19 在 P3 示例上当场踩到）。</p>
 *
 * <p>同理 `export default` 的改写也必须锚定行首，否则注释里的示例文本会被替换掉。</p>
 */
const IMPORT_FROM = /^[ \t]*import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"];?/gm;
const IMPORT_SIDE = /^[ \t]*import\s+['"]([^'"]+)['"];?/gm;
const EXPORT_DEFAULT = /^([ \t]*)export\s+default\s+/gm;

/** 只允许这几类 import 子句；其它形态（如两段 default 拼接）交给报错。 */
function rewriteClause(clause: string, spec: string, tempVar: string, notes: string[]): string {
  const c = clause.trim();
  const req = `__apHost.require(${JSON.stringify(spec)})`;

  if (c.startsWith('* as ')) {
    return `const ${c.slice(5).trim()} = ${req};`;
  }

  // default + named：`React, { useState }`
  const mixed = /^([A-Za-z_$][\w$]*)\s*,\s*\{([\s\S]*)\}$/.exec(c);
  if (mixed) {
    const names = destructureNames(mixed[2]);
    return [
      `const ${tempVar} = ${req};`,
      `const ${mixed[1]} = (${tempVar}.default ?? ${tempVar});`,
      `const { ${names} } = ${tempVar};`,
    ].join(' ');
  }

  if (c.startsWith('{')) {
    return `const { ${destructureNames(c.slice(1, c.lastIndexOf('}')))} } = ${req};`;
  }

  // 纯 default
  if (/^[A-Za-z_$][\w$]*$/.test(c)) {
    return `const ${c} = (${req}.default ?? ${req});`;
  }

  notes.push(`无法识别的 import 子句：${c}`);
  throw new Error(`不支持的 import 写法：「${c}」（转译器只支持 default / named / namespace / 副作用导入）`);
}

function destructureNames(raw: string): string {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => s.replace(/\s+as\s+/g, ': '))
    .join(', ');
}

/**
 * 把 ESM 源码转成可注入的 IIFE（执行后调用 `__apHost.registerDsh(pluginId, exports)`）。
 *
 * @throws Error 语法超出受限子集时（**信息里带原因**，便于作者判断"要不要用打包器"）
 */
export function transpileDshEsm(source: string, pluginId: string): TranspileResult {
  if (!source || source.trim().length === 0) {
    throw new Error('插件入口是空文件');
  }
  if (source.length > MAX_SOURCE_BYTES) {
    throw new Error(`插件入口过大（${Math.round(source.length / 1024)}KB），很可能是打包产物 —— 请改用单文件 Client 插件`);
  }

  const notes: string[] = [];

  // ① 越界语法先拦（给的是"为什么不支持"，不是笼统失败）
  if (/^\s*export\s+(?:const|let|var|function|class)\s/m.test(source)) {
    throw new Error('不支持 `export const/function/class`（需要打包器）；请改成 `export default { … }`');
  }
  if (/^\s*export\s*\{/m.test(source)) {
    throw new Error('不支持 `export { … }`；请改成 `export default { … }`');
  }
  if (/^\s*export\s+\*/m.test(source)) {
    throw new Error('不支持 `export * from`（需要打包器）');
  }
  if (/^\s*await\s/m.test(source)) {
    throw new Error('不支持顶层 await');
  }

  // ② import 改写（先用临时变量名区分多次导入，避免同名冲突）
  let counter = 0;
  let code = source.replace(IMPORT_FROM, (_m: string, clause: string, spec: string) => {
    if (spec.startsWith('.') || spec.startsWith('/')) {
      throw new Error(
        `插件包含相对导入「${spec}」—— 说明它是打包产物；当前版本只支持单文件/裸包导入，`
          + '这类插件需要 esbuild 打包后再安装',
      );
    }
    const temp = `__apM${counter++}`;
    return rewriteClause(clause, spec, temp, notes);
  });
  code = code.replace(IMPORT_SIDE, (_m: string, spec: string) => {
    if (spec.startsWith('.') || spec.startsWith('/')) {
      throw new Error(`插件包含相对导入「${spec}」（打包产物），当前版本不支持`);
    }
    return `__apHost.require(${JSON.stringify(spec)});`;
  });

  // ③ export default → 赋值（`export default function foo(){}` 也是合法表达式，改写后依然成立）
  const hadDefault = /^[ \t]*export\s+default\s+/m.test(code);
  if (!hadDefault) {
    throw new Error('DSH 插件入口必须 `export default { name, inject, apply }`');
  }
  // 保留行首缩进（`$1`），只在语句位置改写 —— 注释里的示例文本不会被误伤（见文件头说明）
  code = code.replace(EXPORT_DEFAULT, '$1__apModule.exports = ');

  // ④ 包装成 IIFE：保持"执行即副作用"的语义，同时把 exports 交给宿主注册
  const wrapped = `(function () {
"use strict";
var __apModule = { exports: {} };
${code}
;
__apHost.registerDsh(${JSON.stringify(pluginId)}, __apModule.exports);
})();`;

  return { code: wrapped, notes };
}
