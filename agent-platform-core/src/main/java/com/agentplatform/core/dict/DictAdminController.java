package com.agentplatform.core.dict;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.dict.DictDtos.DictItemView;
import com.agentplatform.core.dict.DictDtos.DictTypeView;
import com.agentplatform.core.dict.DictDtos.SaveDictItemRequest;
import com.agentplatform.core.dict.DictDtos.SaveDictTypeRequest;
import com.agentplatform.core.security.rbac.RbacContext;
import com.agentplatform.core.security.rbac.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据字典管理接口（管理界面用）。
 *
 * <p>路径挂在 {@code /api/v1/system} 下，与用户/角色管理并列 —— 它们同属"系统管理"类配置。
 * 整个类要求 {@code dict:write}：只有 admin 有（operator 被
 * {@code RbacPermission.systemCodes()} 排除），因为字典是全局影响面最大的配置 ——
 * 改错一个标签，所有引用它的页面都跟着变。</p>
 */
@RestController
@RequestMapping("/api/v1/system/dicts")
@RequiredArgsConstructor
@RequiresPermission("dict:write")
public class DictAdminController {

    private final DictAdminService adminService;

    // ------------------------------------------------------------------ 类型

    @GetMapping
    public ApiResponse<List<DictTypeView>> listTypes() {
        return ApiResponse.ok(adminService.listTypes(RbacContext.tenantId()));
    }

    @PostMapping
    public ApiResponse<DictTypeView> createType(@RequestBody SaveDictTypeRequest req) {
        return ApiResponse.ok(adminService.createType(RbacContext.tenantId(), req));
    }

    /** 更新名称/备注/状态；{@code typeCode} 不可改（改了该类型下的字典项会全部失联）。 */
    @PutMapping("/{dictTypeId}")
    public ApiResponse<DictTypeView> updateType(@PathVariable String dictTypeId,
                                                @RequestBody SaveDictTypeRequest req) {
        return ApiResponse.ok(adminService.updateType(RbacContext.tenantId(), dictTypeId, req));
    }

    /** 删除类型（连带删除其字典项）；内置类型会被服务层拒绝。 */
    @DeleteMapping("/{dictTypeId}")
    public ApiResponse<Void> deleteType(@PathVariable String dictTypeId) {
        adminService.deleteType(RbacContext.tenantId(), dictTypeId);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------ 字典项

    @GetMapping("/{typeCode}/items")
    public ApiResponse<List<DictItemView>> listItems(@PathVariable String typeCode) {
        return ApiResponse.ok(adminService.listItems(RbacContext.tenantId(), typeCode));
    }

    @PostMapping("/{typeCode}/items")
    public ApiResponse<DictItemView> createItem(@PathVariable String typeCode,
                                                @RequestBody SaveDictItemRequest req) {
        // 以**路径上的 typeCode 为准**：DTO 里那个只是可选冗余，
        // 免得前端在 URL 和 body 里填了不一致的值后，我们还要决定信哪个。
        SaveDictItemRequest normalized = new SaveDictItemRequest(
                typeCode,
                req == null ? null : req.itemValue(),
                req == null ? null : req.itemLabel(),
                req == null ? null : req.sortOrder(),
                req == null ? null : req.status(),
                req == null ? null : req.remark());
        return ApiResponse.ok(adminService.createItem(RbacContext.tenantId(), normalized));
    }

    /** 更新标签/排序/状态/备注；{@code itemValue} 不可改（它已被写进业务数据）。 */
    @PutMapping("/items/{dictItemId}")
    public ApiResponse<DictItemView> updateItem(@PathVariable String dictItemId,
                                                @RequestBody SaveDictItemRequest req) {
        return ApiResponse.ok(adminService.updateItem(RbacContext.tenantId(), dictItemId, req));
    }

    /** 删除字典项；内置字典下的选项会被服务层拒绝（可停用代替）。 */
    @DeleteMapping("/items/{dictItemId}")
    public ApiResponse<Void> deleteItem(@PathVariable String dictItemId) {
        adminService.deleteItem(RbacContext.tenantId(), dictItemId);
        return ApiResponse.ok(null);
    }
}
