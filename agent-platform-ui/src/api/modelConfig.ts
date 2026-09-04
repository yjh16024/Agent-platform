import { http } from './http';
import { ModelConfigView } from './types';

// 平台级模型配置：嵌入模型绑定 + 默认对话模型绑定。

export interface ModelBindingBody {
  provider?: string;
  model?: string;
  baseUrl?: string;
  apiKey?: string;
}

export function getModelConfig() {
  return http.get<ModelConfigView>('/api/v1/model-config');
}

// 写入嵌入绑定：provider/model/baseUrl/apiKey 为 camelCase（apiKey 加密落库、留空保持原 Key）
export function saveEmbeddingBinding(body: ModelBindingBody) {
  return http.put<ModelConfigView>('/api/v1/model-config/embedding', body);
}

// 写入平台默认对话模型（新建智能体未配置模型时自动回退到此，避免重复填 Key）
export function saveChatBinding(body: ModelBindingBody) {
  return http.put<ModelConfigView>('/api/v1/model-config/chat', body);
}
