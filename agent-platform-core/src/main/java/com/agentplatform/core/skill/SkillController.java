package com.agentplatform.core.skill;

import com.agentplatform.common.dto.ApiResponse;
import com.agentplatform.core.skill.executor.SkillExecutionResult;
import com.agentplatform.core.skill.executor.SkillExecutionService;
import com.agentplatform.core.skill.market.SkillMarketService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Skills 管理接口。
 * <p>
 * <b>标准目录存储</b>：Skill 以 {@code skills/<skill-name>/SKILL.md} 形式存放
 * （Agent Skills 开放标准）。用户可直接把网上下载的 Skill 目录或 zip 放进去，
 * 通过 {@code POST /skills/sync} 扫描识别；也可从仪表盘上传或打开目录。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillExecutionService executionService;
    private final SkillMarketService skillMarketService;

    /**
     * 技能市场：列出 Anthropic 官方 Agent Skills 仓库（`github.com/anthropics/skills`）的技能。
     * <p>返回项含 name / title / description / installed（是否已装到本地 skills 目录）。</p>
     */
    @GetMapping("/market")
    public ApiResponse<List<Map<String, Object>>> market() {
        return ApiResponse.ok(skillMarketService.list());
    }

    /**
     * 技能市场：一键安装某个技能 —— 递归下载到本地 skills 目录，随后自动同步入库。
     */
    @PostMapping("/market/{name}/install")
    public ApiResponse<Map<String, Object>> installFromMarket(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String name) {
        return ApiResponse.ok(skillMarketService.install(tenantId, name), "installed");
    }

    /** skills 根目录绝对路径（前端展示/复制用）。 */
    @GetMapping("/dir")
    public ApiResponse<Map<String, Object>> dir() {
        return ApiResponse.ok(Map.of("path", skillService.skillsDir()));
    }

    /** 在系统文件管理器中打开 skills 目录（本地/桌面场景）。 */
    @PostMapping("/open-folder")
    public ApiResponse<Map<String, Object>> openFolder() {
        return ApiResponse.ok(skillService.openFolder(), "opened");
    }

    /** 扫描 skills 目录并同步入库（新增/更新/报告缺失）。 */
    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> sync(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ApiResponse.ok(skillService.syncFromFolder(tenantId), "synced");
    }

    /** 导入 skills 根目录下的某个子目录。 */
    @PostMapping("/import-folder")
    public ApiResponse<SkillDef> importFolder(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        String dir = body == null ? null : body.get("dir");
        if (dir == null || dir.isBlank()) {
            return ApiResponse.error("BAD_REQUEST", "dir 不能为空（skills 目录下的子目录名）");
        }
        return ApiResponse.ok(skillService.importFolder(tenantId, dir), "imported");
    }

    /** 上传导入：.zip 标准 Skill 包 或 单个 SKILL.md。 */
    @PostMapping("/upload")
    public ApiResponse<List<SkillDef>> upload(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(skillService.importUpload(tenantId, file), "imported");
    }

    /** 列出某 Skill 目录内的文件（渐进式披露的资源）。 */
    @GetMapping("/{skillId}/files")
    public ApiResponse<List<String>> files(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        return ApiResponse.ok(skillService.files(tenantId, skillId));
    }

    /** 读取某 Skill 目录内文件内容（如 scripts/rotate.py、references/FORMS.md）。 */
    @GetMapping("/{skillId}/file")
    public ApiResponse<Map<String, Object>> readFile(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId,
            @RequestParam String path) {
        return ApiResponse.ok(Map.of(
                "path", path,
                "content", skillService.readFile(tenantId, skillId, path)));
    }

    /** 导入 Skill（本地 Manifest 文本，兼容旧格式）。 */
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

    /** 按字段新建 Skill（自动写入标准目录）。 */
    @PostMapping
    public ApiResponse<SkillDef> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody SkillService.SkillRequest req) {
        return ApiResponse.ok(skillService.create(tenantId, req), "created");
    }

    /** 更新 Skill（编辑，目录型同步回写 SKILL.md）。 */
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

    /** 可用 Skill 执行器清单（命令模式：prompt / script / http …）。 */
    @GetMapping("/executors")
    public ApiResponse<List<Map<String, Object>>> executors() {
        return ApiResponse.ok(executionService.executors());
    }

    /**
     * 执行 Skill（命令模式入口）。
     * <p>body：{@code type} 可选（prompt / script / http，缺省由注册表推断）；
     * {@code command} 可选（脚本路径 / HTTP 端点）；{@code args} 可选入参。</p>
     */
    @PostMapping("/{skillId}/execute")
    public ApiResponse<SkillExecutionResult> execute(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        String type = req.get("type") == null ? null : String.valueOf(req.get("type"));
        String command = req.get("command") == null ? null : String.valueOf(req.get("command"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) req.get("args");
        return ApiResponse.ok(executionService.run(tenantId, skillId, type, command, args));
    }

    /** 删除（物理删除；目录型一并删除 skills 子目录）。 */
    @DeleteMapping("/{skillId}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        skillService.delete(tenantId, skillId);
        return ApiResponse.ok(null, "deleted");
    }
}
