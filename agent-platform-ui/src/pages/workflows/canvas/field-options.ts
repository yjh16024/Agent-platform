/**
 * 画布字段的**动态选项**：从平台已有内容里读，而不是让用户手填 id。
 *
 * <h3>为什么需要这一层（2026-10-12）</h3>
 * 在此之前，Agent / 知识库 / Skill / 工具这些字段都是**纯文本框**，
 * 要求用户手打 `agent_xxx` / `kb_xxx` —— 而那些 id 只在列表页的 URL 里能见到，
 * 现实做法是"回列表页点开、从地址栏抄一段回来"。
 * 更糟的是**抄错了不报错**：保存能过，跑起来才说"找不到该智能体"。
 * 改成下拉后，用户看到名字、选的是对象，id 由系统填。
 *
 * <h3>★ 为什么"一次拉全"而不是每个字段各请求一次</h3>
 * 一个节点表单里可能有多个字段要选项（如 Tool 节点的工具名 + 参数模板），
 * 各自请求会打出一串 HTTP；而这些清单都不大。
 * 这与项目既有的字典做法一致（`getDictBatch` 的注释：
 * 「字典总量很小，一次拿回来常驻内存最划算」）。
 *
 * <h3>⚠️ 拿不到清单时不能"空下拉"</h3>
 * 后端没启、权限不足、网络抖动都可能拉不到。此时**退回静态 options**
 * （`CanvasFieldSpec.options`）而不是给一个没有选项的下拉 ——
 * 后者会让用户以为"平台里什么都没有"，比手填还糟。
 */
import { listAgents } from '../../../api/agents';
import { listKbs } from '../../../api/knowledgeBases';
import { listSkills } from '../../../api/skills';
import { listTools } from '../../../api/tools';
import { getModelConfig } from '../../../api/modelConfig';
import { getDictOptions, DICT } from '../../../api/dict';
import type { AgentResponse } from '../../../api/types';
import type { FieldSource } from './node-metas';
// 纯函数抽在 tool-args.ts：本文件 import 了各 api 模块，在 Node 里跑不起来，
// 而那两个函数是要单测的
import { schemaToArgsTemplate } from './tool-args';

export interface FieldOption {
  label: string;
  value: string;
}

/** 一次拉全的缓存（含"没拉到"的空数组，避免每次开表单都重试）。 */
let cache: Record<FieldSource, FieldOption[]> | null = null;
/** 正在进行的请求（防止同时打开多个节点时重复拉）。 */
let inflight: Promise<Record<FieldSource, FieldOption[]>> | null = null;

/** 工具来源的中文标记（与工具页的 `SOURCE_TEXT` 对齐）。 */
function toolSourceTag(source?: string): string {
  switch (source) {
    case 'builtin':
      return '内置';
    case 'http':
      return 'HTTP';
    case 'mcp':
      return 'MCP';
    case 'plugin':
      return '插件';
    default:
      return '外部';
  }
}

/** 忽略失败：一个数据源拿不到不该让整个表单挂掉，其它字段仍然可用。 */
function safe<T>(p: Promise<T>, fallback: T): Promise<T> {
  return p.catch(() => fallback);
}

/**
 * 一次性把所有来源拉回来。
 *
 * <p>每个来源都独立 catch —— 比如权限只有 `tool:read` 的用户拉不到智能体列表，
 * 也应该能正常配置工具节点。</p>
 */
export function loadAllFieldOptions(force = false): Promise<Record<FieldSource, FieldOption[]>> {
  if (!force && cache) {
    return Promise.resolve(cache);
  }
  if (!force && inflight) {
    return inflight;
  }
  inflight = Promise.all([
    // 智能体：分页接口，取一页足够大的（画布下拉不需要分页）
    safe(listAgents(undefined, undefined, 0, 200), {
      items: [] as AgentResponse[],
      total: 0,
      page: 0,
      size: 0,
      totalPages: 0,
    }),
    safe(listKbs(), [] as { kbId?: string; name?: string }[]),
    safe(listSkills(), [] as { skillId?: string; name?: string }[]),
    safe(listTools(), [] as { name?: string; description?: string; source?: string }[]),
    safe(getDictOptions(DICT.MODEL_PROVIDER_CHAT), [] as { value: string; label: string }[]),
    safe(getModelConfig(), {} as { chat?: { model?: string } }),
  ]).then(([agents, kbs, skills, tools, providers, modelCfg]) => {
    const result: Record<FieldSource, FieldOption[]> = {
      agents: (agents.items ?? [])
        .filter((a) => a.agentId)
        .map((a) => ({ label: a.name || a.agentId!, value: a.agentId! })),

      knowledgeBases: kbs
        .filter((k) => k.kbId)
        .map((k) => ({ label: k.name || k.kbId!, value: k.kbId! })),

      skills: skills
        .filter((s) => s.skillId)
        .map((s) => ({ label: s.name || s.skillId!, value: s.skillId! })),

      // 工具名后面缀上来源 —— 同名的可能性不大，但"插件工具"与"内置工具"
      // 的功能差异很大，选之前就该看出来
      tools: tools
        .filter((t) => t.name)
        .map((t) => ({
          label: `${t.name}（${toolSourceTag(t.source)}）`,
          value: t.name!,
        })),

      providers,

      /*
       * 模型名**没有全量清单接口**（`/model-config` 只回当前绑定的那一个）。
       * 所以这里只提供一个候选：平台当前在用的那个 —— 至少让"想跟平台保持一致"
       * 的用户能一键选中；其余情况靠自由输入（下拉允许手打）。
       * 硬编码一份常见模型名会随厂商更新而过时，不如不给。
       */
      models: modelCfg.chat?.model
        ? [{ label: `${modelCfg.chat.model}（平台当前）`, value: modelCfg.chat.model }]
        : [],
    };
    cache = result;
    return result;
  }).finally(() => {
    inflight = null;
  });
  return inflight;
}

/** 取某个来源的选项（走缓存）。 */
export async function loadFieldOptions(source: FieldSource): Promise<FieldOption[]> {
  const all = await loadAllFieldOptions();
  return all[source] ?? [];
}

// ---------------------------------------------------------------- 工具参数模板

/**
 * 按工具名取出它的参数模板。
 *
 * <p>工具清单来自 `GET /tools`，其中 `parameters` 是工具的入参 JSON Schema。
 * 各来源的工具是否带 schema 取决于实现（内置与 HTTP 工具基本都有）——
 * 没有就返回 null，此时界面退回"让用户自己写 JSON"，与从前一致。</p>
 *
 * @return 骨架 JSON 文本；找不到该工具或它没声明 schema 时返回 null
 */
export async function toolArgsTemplate(toolName: string): Promise<string | null> {
  if (!toolName) {
    return null;
  }
  const tools = await safe(listTools(), [] as { name?: string; parameters?: unknown }[]);
  const hit = tools.find((t) => t.name === toolName);
  if (!hit || !hit.parameters) {
    return null;
  }
  return schemaToArgsTemplate(hit.parameters);
}

/** 清掉缓存（例如工作流页重新进入时想拿最新清单）。 */
export function invalidateFieldOptions(): void {
  cache = null;
}
