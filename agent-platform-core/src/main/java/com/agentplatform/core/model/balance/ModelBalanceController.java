package com.agentplatform.core.model.balance;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型开放平台账户额度接口。
 * <p>
 * <ul>
 *   <li>{@code GET /api/v1/model-balance} —— 查询已配置绑定（默认对话模型 + 嵌入模型）的剩余额度；</li>
 *   <li>{@code GET /api/v1/model-balance/vendors} —— 列出支持余额查询的厂商（前端展示支持范围）。</li>
 * </ul>
 * 凭证取自已保存的加密绑定（服务端解密，绝不下发）。查询失败不抛 500，统一回结构化结果。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/model-balance")
@RequiredArgsConstructor
@RequiresPermission("model:manage")
public class ModelBalanceController {

    private final ModelBalanceService modelBalanceService;

    /** 查询账户额度（默认对话模型 + 嵌入模型两条绑定）。 */
    @GetMapping
    public ApiResponse<List<ModelBalanceView>> list() {
        return ApiResponse.ok(modelBalanceService.queryAll());
    }

    /** 支持余额查询的厂商清单（provider → 查询端点）。 */
    @GetMapping("/vendors")
    public ApiResponse<Map<String, String>> vendors() {
        return ApiResponse.ok(ModelBalanceService.supportedVendors());
    }
}
