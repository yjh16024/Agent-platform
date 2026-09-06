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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 服务（标准目录存储 + 导入 + 查看 + 编辑 + 删除）。
 * <p>
 * <b>存储遵循 Agent Skills 开放标准</b>：Skill 以目录形式落在
 * {@code skills/<skill-name>/SKILL.md}（YAML frontmatter + 提示词正文），
 * 可附带 {@code scripts/}、{@code references/}、{@code assets/}。
 * 用户把网上下载的 Skill 目录/压缩包放进该目录后，经「扫描同步」即可被平台识别入库；
 * 读取详情时以目录内容为准（磁盘改动可同步回库）。
 * </p>
 * <p>原有的 YAML Manifest 文本导入仍保留（来源标记为 local/url），与目录型 Skill 并存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    /** 来源标识：目录型（标准布局）。 */
    public static final String SOURCE_FILESYSTEM = "filesystem";

    private final SkillRepository repository;
    private final SkillImporter importer;
    private final SkillFileStore fileStore;

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
     * 直接按字段创建（仪表盘新建）：写入标准目录 + 落库。
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
                null,
                fileStore.sanitize(req.name()),
                null,
                req.tools(),
                null);
        manifest.validate();
        return register(tenantId, manifest, SOURCE_FILESYSTEM);
    }

    /**
     * 更新 Skill（名称 / 描述 / 版本 / 提示词 / 工具集），未提供的字段保持原值。
     * <p>目录型 Skill 会同步回写 SKILL.md，保证磁盘与库一致。</p>
     */
    @Transactional
    public SkillDef update(String tenantId, String skillId, SkillRequest req) {
        SkillDef def = get(tenantId, skillId);
        String oldDir = dirOf(def);
        boolean renamed = req.name() != null && !req.name().isBlank() && !req.name().equals(def.getName());

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

        SkillManifest manifest = new SkillManifest(
                def.getName(), def.getVersion(), def.getDescription(), def.getPromptTemplate(),
                def.getTools(), null, null,
                renamed ? fileStore.sanitize(def.getName()) : oldDir,
                null, def.getTools(), null);
        def.setManifest(JsonUtils.mapper().convertValue(manifest, Map.class));
        def = repository.save(def);

        // 同步写回标准目录（重命名时旧目录一并移除）
        if (SOURCE_FILESYSTEM.equals(def.getSource()) || oldDir != null) {
            fileStore.writeSkill(dirOf(def), manifest);
            if (renamed && oldDir != null && !oldDir.equals(dirOf(def))) {
                fileStore.deleteDir(oldDir);
            }
        }
        log.info("Updated skill {} [{}]", def.getName(), skillId);
        return def;
    }

    /**
     * 扫描 skills 根目录并同步入库（新增 / 更新 / 报告被移除的）。
     *
     * @return 同步统计 {created, updated, removed, total, dir}
     */
    @Transactional
    public Map<String, Object> syncFromFolder(String tenantId) {
        List<SkillFileStore.ScannedSkill> scanned = fileStore.scan();
        List<SkillDef> existing = repository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        Map<String, SkillDef> byDir = new LinkedHashMap<>();
        for (SkillDef d : existing) {
            String d0 = dirOf(d);
            if (d0 != null) {
                byDir.put(d0, d);
            }
        }

        int created = 0;
        int updated = 0;
        List<String> names = new ArrayList<>();
        for (SkillFileStore.ScannedSkill s : scanned) {
            names.add(s.manifest().name());
            SkillDef hit = byDir.remove(s.dir());
            if (hit == null) {
                register(tenantId, s.manifest(), SOURCE_FILESYSTEM);
                created++;
            } else {
                applyManifest(hit, s.manifest());
                repository.save(hit);
                updated++;
            }
        }
        // 目录中已不存在的目录型 Skill：标记为缺失（不自动删库，避免误删用户数据）
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, SkillDef> e : byDir.entrySet()) {
            if (SOURCE_FILESYSTEM.equals(e.getValue().getSource())) {
                missing.add(e.getKey());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dir", fileStore.root().toString());
        result.put("total", scanned.size());
        result.put("created", created);
        result.put("updated", updated);
        result.put("missing_in_folder", missing.size());
        result.put("names", names);
        log.info("[skills] 扫描同步完成 dir={} created={} updated={} missing={}",
                fileStore.root(), created, updated, missing.size());
        return result;
    }

    /**
     * 导入 skills 根目录下指定子目录（用户手动放入后指定导入）。
     */
    @Transactional
    public SkillDef importFolder(String tenantId, String dir) {
        SkillFileStore.ScannedSkill parsed = readDir(dir);
        String d = parsed.dir();
        SkillDef hit = findByDir(tenantId, d).orElse(null);
        if (hit != null) {
            applyManifest(hit, parsed.manifest());
            return repository.save(hit);
        }
        return register(tenantId, parsed.manifest(), SOURCE_FILESYSTEM);
    }

    /**
     * 上传导入：zip 压缩包（标准 Skill 包）或单个 SKILL.md。
     */
    @Transactional
    public List<SkillDef> importUpload(String tenantId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String name = file.getOriginalFilename() == null ? "skill" : file.getOriginalFilename();
        List<String> dirs;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.internal("读取上传文件失败: " + e.getMessage(), e);
        }
        if (name.toLowerCase().endsWith(".zip")) {
            dirs = fileStore.extractZip(name, bytes);
        } else if (name.toLowerCase().endsWith(".md")) {
            dirs = List.of(fileStore.importSingleFile(name, bytes));
        } else {
            throw BizException.badRequest("仅支持 .zip 压缩包或 SKILL.md 文件（也可直接放入目录后点「扫描同步」）");
        }
        List<SkillDef> result = new ArrayList<>();
        for (String d : dirs) {
            result.add(importFolder(tenantId, d));
        }
        return result;
    }

    /** 列出 Skill 目录内文件（相对路径）。 */
    @Transactional(readOnly = true)
    public List<String> files(String tenantId, String skillId) {
        SkillDef def = get(tenantId, skillId);
        String dir = dirOf(def);
        return dir == null ? List.of() : fileStore.listFiles(dir);
    }

    /** 读取 Skill 目录内某文件内容。 */
    @Transactional(readOnly = true)
    public String readFile(String tenantId, String skillId, String path) {
        SkillDef def = get(tenantId, skillId);
        String dir = dirOf(def);
        if (dir == null) {
            throw BizException.badRequest("该 Skill 非目录型（无 SKILL.md 文件）");
        }
        return fileStore.readFile(dir, path);
    }

    /** 打开 skills 根目录（系统文件管理器），返回目录路径。 */
    public Map<String, Object> openFolder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", fileStore.root().toString());
        m.put("enabled", fileStore.openFolderEnabled());
        if (fileStore.openFolderEnabled()) {
            fileStore.openFolder();
            m.put("opened", true);
        } else {
            m.put("opened", false);
        }
        return m;
    }

    /** skills 根目录路径（供前端展示/复制）。 */
    public String skillsDir() {
        return fileStore.root().toString();
    }

    /**
     * 注册 Skill（落库 + 元数据）。目录型会先写入标准 SKILL.md。
     */
    private SkillDef register(String tenantId, SkillManifest manifest, String source) {
        manifest.validate();
        String dir = manifest.dir() == null || manifest.dir().isBlank()
                ? fileStore.sanitize(manifest.name()) : fileStore.sanitize(manifest.dir());
        if (SOURCE_FILESYSTEM.equals(source)) {
            fileStore.writeSkill(dir, manifest);
        }
        String skillId = IdGenerator.generate("skill");
        SkillDef def = SkillDef.builder()
                .skillId(skillId)
                .tenantId(tenantId)
                .name(manifest.name())
                .description(manifest.description())
                .version(manifest.version())
                .manifest(toManifestMap(manifest, dir))
                .source(source)
                .tools(manifest.effectiveTools())
                .promptTemplate(manifest.prompt())
                .status("active")
                .build();
        def = repository.save(def);
        log.info("Imported skill {} [{}] dir={} (tools={})", manifest.name(), skillId, dir, manifest.effectiveTools());
        return def;
    }

    /** 用目录内容覆盖既有记录（同步时调用）。 */
    private void applyManifest(SkillDef def, SkillManifest manifest) {
        String dir = manifest.dir() == null ? dirOf(def) : fileStore.sanitize(manifest.dir());
        def.setName(manifest.name());
        def.setDescription(manifest.description());
        def.setVersion(manifest.version());
        def.setPromptTemplate(manifest.prompt());
        def.setTools(manifest.effectiveTools());
        def.setSource(SOURCE_FILESYSTEM);
        def.setManifest(toManifestMap(manifest, dir));
    }

    private Map<String, Object> toManifestMap(SkillManifest manifest, String dir) {
        SkillManifest withDir = new SkillManifest(
                manifest.name(), manifest.version(), manifest.description(), manifest.prompt(),
                manifest.tools(), manifest.workflow(), manifest.params(), dir,
                manifest.license(), manifest.allowedTools(), manifest.metadata());
        return JsonUtils.mapper().convertValue(withDir, Map.class);
    }

    private SkillFileStore.ScannedSkill readDir(String dir) {
        try {
            String raw = fileStore.readFile(fileStore.sanitize(dir), SkillFileStore.SKILL_FILE);
            return fileStore.parseSkill(fileStore.sanitize(dir), raw);
        } catch (BizException e) {
            throw BizException.badRequest("目录 " + dir + " 不是合法 Skill（缺少 SKILL.md）: " + e.getMessage());
        }
    }

    private Optional<SkillDef> findByDir(String tenantId, String dir) {
        return repository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
                .filter(d -> dir.equals(dirOf(d)))
                .findFirst();
    }

    private String dirOf(SkillDef def) {
        if (def.getManifest() == null) {
            return null;
        }
        Object v = def.getManifest().get("dir");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 列表（含目录信息）。
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
     * 删除 Skill（物理删除；目录型一并删除其 skills 子目录）。
     */
    @Transactional
    public void delete(String tenantId, String skillId) {
        SkillDef def = get(tenantId, skillId);
        String dir = dirOf(def);
        repository.delete(def);
        if (dir != null && SOURCE_FILESYSTEM.equals(def.getSource())) {
            fileStore.deleteDir(dir);
        }
        log.info("Deleted skill {} [{}] dir={}", def.getName(), skillId, dir);
    }

    /**
     * Skill 编辑请求（camelCase，与前端表单一致）。
     *
     * @param name        名称
     * @param version     语义化版本
     * @param description 描述
     * @param prompt      提示词模板（SKILL.md 正文）
     * @param tools       工具名列表
     * @param source      来源（filesystem / local / marketplace / url）
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
