import { http } from './http';

/** 一个下拉选项。 */
export interface DictOption {
  value: string;
  label: string;
}

/** 内置字典类型编码（与后端 DictSeeder 的常量一致）。 */
export const DICT = {
  LOG_LEVEL: 'log_level',
  LOG_CATEGORY: 'log_category',
  CHUNK_STRATEGY: 'chunk_strategy',
  BUILTIN_TOOL: 'builtin_tool',
  /**
   * 服务商拆成两个类型，因为两侧的可选集合并不相同：
   * 嵌入侧有 siliconflow / zhipu 但没有 deepseek / anthropic（那两家没有 embedding 接口），
   * 对话侧反之。字典"一个类型 = 一个扁平列表"表达不了子集差异，那就再建一个类型。
   */
  MODEL_PROVIDER_CHAT: 'model_provider_chat',
  MODEL_PROVIDER_EMBEDDING: 'model_provider_embedding',
} as const;

/** 单个字典的启用项。 */
export function getDictOptions(typeCode: string) {
  return http.get<DictOption[]>(`/api/v1/dicts/${encodeURIComponent(typeCode)}`);
}

/**
 * 批量取多个字典。
 *
 * <p>启动时用这个**一次拉全量**：如果每个下拉各自请求，一个页面会打出十几个 HTTP。
 * 字典总量很小（几十条），一次拿回来常驻内存最划算。</p>
 */
export function getDictBatch(typeCodes: string[]) {
  const q = typeCodes.map((c) => encodeURIComponent(c)).join(',');
  return http.get<Record<string, DictOption[]>>(`/api/v1/dicts?codes=${q}`);
}

// ---------------------------------------------------------------- 管理接口（dict:write）

export interface DictTypeView {
  dictTypeId: string;
  typeCode: string;
  typeName: string;
  remark?: string | null;
  status: string;
  /** 内置类型：不可删除（可改标签），其下选项同样不可删。 */
  builtin: boolean;
  itemCount: number;
}

export interface DictItemView {
  dictItemId: string;
  typeCode: string;
  itemValue: string;
  itemLabel: string;
  sortOrder: number;
  status: string;
  remark?: string | null;
}

export interface SaveDictTypeBody {
  typeCode?: string;
  typeName?: string;
  remark?: string;
  status?: string;
}

export interface SaveDictItemBody {
  itemValue?: string;
  itemLabel?: string;
  sortOrder?: number;
  status?: string;
  remark?: string;
}

export function listDictTypes() {
  return http.get<DictTypeView[]>('/api/v1/system/dicts');
}

export function createDictType(body: SaveDictTypeBody) {
  return http.post<DictTypeView>('/api/v1/system/dicts', body);
}

/** 注意：{@code typeCode} 不可改（改了该类型下的字典项会全部失联），后端会忽略它。 */
export function updateDictType(dictTypeId: string, body: SaveDictTypeBody) {
  return http.put<DictTypeView>(`/api/v1/system/dicts/${encodeURIComponent(dictTypeId)}`, body);
}

export function deleteDictType(dictTypeId: string) {
  return http.delete<void>(`/api/v1/system/dicts/${encodeURIComponent(dictTypeId)}`);
}

export function listDictItems(typeCode: string) {
  return http.get<DictItemView[]>(`/api/v1/system/dicts/${encodeURIComponent(typeCode)}/items`);
}

export function createDictItem(typeCode: string, body: SaveDictItemBody) {
  return http.post<DictItemView>(`/api/v1/system/dicts/${encodeURIComponent(typeCode)}/items`, body);
}

/** 注意：{@code itemValue} 不可改（它已被写进业务数据），后端会忽略它。 */
export function updateDictItem(dictItemId: string, body: SaveDictItemBody) {
  return http.put<DictItemView>(`/api/v1/system/dicts/items/${encodeURIComponent(dictItemId)}`, body);
}

export function deleteDictItem(dictItemId: string) {
  return http.delete<void>(`/api/v1/system/dicts/items/${encodeURIComponent(dictItemId)}`);
}
