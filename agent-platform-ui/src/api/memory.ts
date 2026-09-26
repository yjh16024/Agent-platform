import { http } from './http';

/**
 * 多层级记忆的对外部分：**长期画像**（用户显式填写）与**向量记忆**（历史对话语义召回）。
 *
 * <p>短期（最近几轮）与中期（超长会话摘要）两层是纯服务端机制，没有独立接口 ——
 * 它们随对话自然发生，用户不需要、也不应该去手工管理。这里只暴露需要用户参与的两层。</p>
 *
 * <p>身份由后端从 token 注入的 {@code X-User-Id} 决定，前端<b>不传用户参数</b>：
 * 画像是个人数据，能不能操作别人画像不该由调用方说了算。</p>
 *
 * <p>⚠️ 字段名是 <b>camelCase</b>：后端这几个 DTO 是 record 且未加
 * {@code @JsonProperty} 重命名，Jackson 按字段原名输出（与 {@code auth.ts} 里那些
 * 带 snake_case 的旧接口不同 —— 那是历史遗留的显式重命名）。</p>
 */

/** 画像分类（对应后端 UserFactCategory）。 */
export type FactCategory = 'preference' | 'background' | 'goal' | 'other';

/** 画像来源：manual(用户填写) / auto(系统提取，当前未启用)。 */
export type FactSource = 'manual' | 'auto';

export interface UserFactItem {
  factId: string;
  key: string;
  value: string;
  category: FactCategory;
  /** 后端给的中文分类名，前端不必再维护一份映射 */
  categoryLabel: string;
  source: FactSource;
  sourceLabel: string;
  createdAt: string;
  updatedAt: string;
}

/** 当前用户的全部画像。 */
export function listFacts() {
  return http.get<UserFactItem[]>('/api/v1/memory/profile');
}

/**
 * 新增或更新一条画像（后端按 key 做 upsert）。
 *
 * <p>前端不区分"新建/编辑"两个接口：用户看到的是一张表单，
 * 保存时不必先判断这条是不是已存在。</p>
 */
export function saveFact(payload: { key: string; value: string; category?: FactCategory }) {
  return http.post<UserFactItem>('/api/v1/memory/profile', payload);
}

/** 更新指定画像（字段为 undefined 表示不改）。 */
export function updateFact(
  factId: string,
  payload: { key?: string; value?: string; category?: FactCategory },
) {
  return http.put<UserFactItem>(
    `/api/v1/memory/profile/${encodeURIComponent(factId)}`,
    payload,
  );
}

/** 删除一条画像。 */
export function deleteFact(factId: string) {
  return http.delete<void>(`/api/v1/memory/profile/${encodeURIComponent(factId)}`);
}

/** 一键清除全部画像（返回删除条数）。 */
export function purgeFacts() {
  return http.delete<{ deleted: number }>('/api/v1/memory/profile/purge');
}

/**
 * 重建向量记忆索引（返回索引条数）。
 *
 * <p><b>桌面版必须偶尔点它</b>：默认的 in-memory 向量库是进程内 Map，
 * 应用重启就清空 —— 重建后才会重新索引历史对话。</p>
 */
export function rebuildVectorMemory() {
  return http.post<{ indexed: number }>('/api/v1/memory/vector/rebuild', {});
}

/** 清除全部向量记忆（返回清除条数）。 */
export function purgeVectorMemory() {
  return http.delete<{ deleted: number }>('/api/v1/memory/vector/purge');
}

// ---------------------------------------------------------------------------
// 自动抽取的画像候选（2026-09-26 加）
//
// 这是「用户画像自动抽取」的**确认环节**：系统从对话里识别出关于用户的稳定事实后，
// 先落成候选、**不直接生效**；用户在界面上一一采纳或忽略。
//
// 顺序是刻意的 —— 先让用户对这份数据有控制感（看得见、改得动、删得掉），
// 才谈得上让系统自动往里写。抽取结果若直接生效，用户永远不会知道
// "模型为什么突然换了口气"，也无从纠正一条记错的信息。
// ---------------------------------------------------------------------------

/** 一条待确认的画像候选。 */
export interface FactCandidateItem {
  candidateId: string;
  key: string;
  value: string;
  category: FactCategory;
  categoryLabel: string;
  /** 抽取自哪个会话、由哪个模型抽取 —— 用户有权知道"你凭什么这么记我" */
  sourceSessionId: string | null;
  extractedBy: string | null;
  createdAt: string;
}

/** 待确认候选列表（最新在前）。 */
export function listCandidates() {
  return http.get<FactCandidateItem[]>('/api/v1/memory/profile/candidates');
}

/** 待确认条数（用于角标，避免为拿计数而拉全量）。 */
export function countCandidates() {
  return http.get<{ pending: number }>('/api/v1/memory/profile/candidates/count');
}

/** 采纳一条候选：搬进正式画像（来源标 auto），随后参与对话上下文。 */
export function adoptCandidate(candidateId: string) {
  return http.post<{ factId: string; key: string }>(
    `/api/v1/memory/profile/candidates/${encodeURIComponent(candidateId)}/adopt`,
    {},
  );
}

/**
 * 忽略一条候选。
 *
 * <p>后端**不会删除记录**，而是打上拒绝时间戳 —— 抽取侧据此跳过该键，
 * 否则下一轮对话又会把同一件事抽出来重新问一遍。</p>
 */
export function rejectCandidate(candidateId: string) {
  return http.post<void>(
    `/api/v1/memory/profile/candidates/${encodeURIComponent(candidateId)}/reject`,
    {},
  );
}

/** 采纳全部待确认候选。 */
export function adoptAllCandidates() {
  return http.post<{ adopted: number }>('/api/v1/memory/profile/candidates/adopt-all', {});
}

/** 忽略全部待确认候选（会记住这些键，不再重复抽取）。 */
export function rejectAllCandidates() {
  return http.post<{ rejected: number }>('/api/v1/memory/profile/candidates/reject-all', {});
}

/** 清除全部候选（含已忽略记录）。 */
export function purgeCandidates() {
  return http.delete<{ deleted: number }>('/api/v1/memory/profile/candidates/purge');
}
