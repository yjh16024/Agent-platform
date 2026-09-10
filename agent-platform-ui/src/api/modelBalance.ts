import { http } from './http';

// 模型开放平台账户额度：用已保存的加密凭证在服务端查询各厂商剩余额度。

export interface ModelBalanceItem {
  label?: string;
  value?: string;
}

export interface ModelBalanceView {
  /** 绑定来源：chat（默认对话模型）/ embedding（RAG 向量化） */
  binding?: string;
  provider?: string;
  model?: string;
  /** 实际查询端点（便于排查自建网关场景） */
  baseUrl?: string;
  /** 该绑定是否已配置 */
  configured?: boolean;
  /** 该厂商是否提供余额查询接口 */
  supported?: boolean;
  /** 本次查询是否成功 */
  ok?: boolean;
  currency?: string;
  /** 可用余额（主展示字段） */
  available?: string;
  items?: ModelBalanceItem[];
  /** 不支持 / 失败时的说明 */
  message?: string;
  checkedAt?: number;
}

/** 查询账户额度（默认对话模型 + 嵌入模型）。 */
export function getModelBalance() {
  return http.get<ModelBalanceView[]>('/api/v1/model-balance');
}

/** 支持余额查询的厂商清单（provider → 端点）。 */
export function getBalanceVendors() {
  return http.get<Record<string, string>>('/api/v1/model-balance/vendors');
}
