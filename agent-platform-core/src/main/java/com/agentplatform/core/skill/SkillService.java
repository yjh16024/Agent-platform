package com.agentplatform.core.skill;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.IdGenerator;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.model.entity.SkillDef;
import com.agentplatform.model.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Skill 服务（导入 + 注册 + 查看 + 编辑 + 删除）。
 * <p>导入后自动注册工具（写入 manifest.tools）与提示词模板，供 Agent 引用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository repository;
    private final SkillImporter importer;

    /**
     * 从 Manifest 文本导入（本地/上传）。
     */
    @Transactional
    public SkillDef importSkill(String tenantId, String manifestText, String source) {
        SkillManifest manifest = importer.parse(manifestText);
        return register(tenantId, manifest, source == null ? "local" : source);
    }

    /**
     * 从 URL 导入。
     */
    @Transactional
    public SkillDef importFromUrl(String tenantId, String url) {
        SkillManifest manifest = importer.importFromUrl(url);
        return register(tenantId, manifest, "url");
    }

    /**
     * 直接按字段创建（仪表盘新建，不需要手写 YAML）。
     */
    @Transactional
    public SkillDef create(String tenantId, SkillRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.validation("skill.name 不能为空");
        }
        SkillManifest manifest = new SkillManifest(
                req.name(),
                req.version() == null || req.version().isBlank() ? "1.0.0" : req.version(),
                req.description(),
                req.prompt(),
                req.tools(),
                null,
                null);
        manifest.validate();
        return register(tenantId, manifest, req.source() == null ? "local" : req.source());
    }

    /**
     * 更新 Skill（名称 / 描述 / 版本 / 提示词 / 工具集），未提供的字段保持原值。
     */
    @Transactional
    public SkillDef update(String tenantId, String skillId, SkillRequest req) {
        SkillDef def = get(tenantId, skillId);
        if (req.name() != null && !req.name().isBlank()) {
            def.setName(req.name());
        }
        if (req.description() != null) {
            def.setDescription(req.description());
        }
        if (req.version() != null && !req.version().isBlank()) {
            def.setVersion(req.version());
        }
        if (req.prompt() != null) {
            def.setPromptTemplate(req.prompt());
        }
        if (req.tools() != null) {
            def.setTools(req.tools());
        }
        // 同步刷新 manifest，保证详情与列表展示一致
        SkillManifest manifest = new SkillManifest(
                def.getName(), def.getVersion(), def.getDescription(),
                def.getPromptTemplate(), def.getTools(), null, null);
        def.setManifest(JsonUtils.mapper().convertValue(manifest, Map.class));
        log.info("Updated skill {} [{}]", def.getName(), skillId);
        return repository.save(def);
    }

    /**
     * 注册 Skill（落库 + 元数据）。
     */
    private SkillDef register(String tenantId, SkillManifest manifest, String source) {
        String skillId = IdGenerator.generate("skill");
        SkillDef def = SkillDef.builder()
                .skillId(skillId)
                .tenantId(tenantId)
                .name(manifest.name())
                .description(manifest.description())
                .version(manifest.version())
                .manifest(JsonUtils.mapper().convertValue(manifest, Map.class))
                .source(source)
                .tools(manifest.tools())
                .promptTemplate(manifest.prompt())
                .status("active")
                .build();
        def = repository.save(def);
        log.info("Imported skill {} [{}] (tools={})", manifest.name(), skillId, manifest.tools());
        return def;
    }

    /**
     * 列表。
     */
    @Transactional(readOnly = true)
    public List<SkillDef> list(String tenantId) {
        return repository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    /**
     * 详情。
     */
    @Transactional(readOnly = true)
    public SkillDef get(String tenantId, String skillId) {
        return repository.findByTenantIdAndSkillId(tenantId, skillId)
                .orElseThrow(() -> BizException.notFound("skill", skillId));
    }

    /**
     * 删除 Skill（物理删除）。
     * <p>原先仅置 status=archived，而列表查询不过滤状态，导致「删除后仍可见」。
     * 用户点删除的预期是删掉，因此改为物理删除。</p>
     */
    @Transactional
    public void delete(String tenantId, String skillId) {
        SkillDef def = get(tenantId, skillId);
        repository.delete(def);
        log.info("Deleted skill {} [{}]", def.getName(), skillId);
    }

    /**
     * Skill 编辑请求（camelCase，与前端表单一致）。
     *
     * @param name        名称
     * @param version     语义化版本
     * @param description 描述
     * @param prompt      提示词模板
     * @param tools       工具名列表
     * @param source      来源（local / marketplace / url）
     */
    public record SkillRequest(
            String name,
            String version,
            String description,
            String prompt,
            List<String> tools,
            String source
    ) {
    }
}
