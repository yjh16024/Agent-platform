package com.agentplatform.core.skill;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.model.entity.SkillDef;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Skills 导入与管理接口（RESTful）。
 * <p>除导入外，提供按字段创建、更新（编辑）与删除能力。</p>
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /** 导入 Skill（本地 Manifest 文本）。 */
    @PostMapping("/import")
    public ApiResponse<SkillDef> importSkill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String source,
            @RequestBody String manifestText) {
        return ApiResponse.ok(skillService.importSkill(tenantId, manifestText, source));
    }

    /** 从 URL 导入。 */
    @PostMapping("/import-url")
    public ApiResponse<SkillDef> importFromUrl(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(skillService.importFromUrl(tenantId, body.get("url")));
    }

    /** 按字段新建 Skill（无需手写 YAML）。 */
    @PostMapping
    public ApiResponse<SkillDef> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody SkillService.SkillRequest req) {
        return ApiResponse.ok(skillService.create(tenantId, req), "created");
    }

    /** 更新 Skill（编辑）。 */
    @PutMapping("/{skillId}")
    public ApiResponse<SkillDef> update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId,
            @RequestBody SkillService.SkillRequest req) {
        return ApiResponse.ok(skillService.update(tenantId, skillId, req), "updated");
    }

    /** 列表。 */
    @GetMapping
    public ApiResponse<List<SkillDef>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(skillService.list(tenantId));
    }

    /** 详情。 */
    @GetMapping("/{skillId}")
    public ApiResponse<SkillDef> get(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        return ApiResponse.ok(skillService.get(tenantId, skillId));
    }

    /** 删除（物理删除）。 */
    @DeleteMapping("/{skillId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        skillService.delete(tenantId, skillId);
        return ApiResponse.ok(null, "deleted");
    }
}
