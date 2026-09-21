package com.agentplatform.core.multimodal;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.security.rbac.RequiresPermission;
import com.agentplatform.model.entity.TenantQuota;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 租户配额接口（查看 + 限额配置）。
 * <p>
 * 查询请求头 {@code X-Tenant-Id} 指定租户；配置限额的 body 为自由 Map，遵循
 * snake_case 契约（{@code tenant_id / quota_type / period / limit}）。
 * </p>
 * <p>
 * <b>权限（2026-09-20 首个试点）</b>：本控制器是 RBAC 落地后第一个加管控的接口 ——
 * 配额属于运维面，查看与修改都要求 {@code quota:manage}。
 * 其余 Controller 暂未加注解（未标注解 = 登录即可访问），可按模块逐个推进。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/quotas")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;

    /** 查看租户配额状态（限额 + 实时用量）。 */
    @RequiresPermission("quota:manage")
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(quotaService.list(tenantId));
    }

    /** 配置 / 更新某个配额类型限额（upsert）。 */
    @RequiresPermission("quota:manage")
    @PostMapping
    public ApiResponse<TenantQuota> setLimit(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> body) {
        String targetTenant = String.valueOf(body.getOrDefault("tenant_id", tenantId));
        String quotaType = String.valueOf(body.get("quota_type"));
        String period = body.get("period") == null ? null : String.valueOf(body.get("period"));
        long limit = ((Number) body.get("limit")).longValue();
        TenantQuota saved = quotaService.setLimit(targetTenant, quotaType, period, limit);
        return ApiResponse.ok(saved, "quota updated");
    }
}
