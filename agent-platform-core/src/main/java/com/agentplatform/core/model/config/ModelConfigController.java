package com.agentplatform.core.model.config;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.record.ModelBinding;
import com.agentplatform.model.record.ModelConfigView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台级模型配置接口。
 * <p>
 * 暴露两类平台级默认绑定的读写：
 * <ul>
 *   <li>{@code GET /model-config} —— 回掩码视图（embedding + chat）；</li>
 *   <li>{@code PUT /model-config/embedding} —— 写入嵌入模型绑定（RAG 向量化）；</li>
 *   <li>{@code PUT /model-config/chat} —— 写入默认对话模型绑定（智能体未配置时回退）。</li>
 * </ul>
 * 请求体为 camelCase（与 {@link ModelBinding} record 一致），apiKey 加密落库、
 * 接口永不回明文；apiKey 留空表示保持原密钥。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/model-config")
@RequiredArgsConstructor
@RequiresPermission("model:manage")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    /** 查询平台模型配置（嵌入 + 默认对话，均为掩码视图）。 */
    @GetMapping
    public ApiResponse<ModelConfigView> get() {
        return ApiResponse.ok(modelConfigService.view());
    }

    /** 写入嵌入模型绑定（apiKey 加密落库，留空保持原 Key）。 */
    @PutMapping("/embedding")
    public ApiResponse<ModelConfigView> saveEmbedding(@RequestBody ModelBinding req) {
        return ApiResponse.ok(modelConfigService.saveEmbedding(req), "Embedding binding saved");
    }

    /** 写入平台默认对话模型绑定（新建智能体未配置时自动回退到此）。 */
    @PutMapping("/chat")
    public ApiResponse<ModelConfigView> saveChat(@RequestBody ModelBinding req) {
        return ApiResponse.ok(modelConfigService.saveChat(req), "Chat binding saved");
    }
}
