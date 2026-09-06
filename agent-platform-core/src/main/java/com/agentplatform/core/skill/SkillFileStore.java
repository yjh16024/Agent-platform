package com.agentplatform.core.skill;

import com.agentplatform.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skills 目录存储（Agent Skills 开放标准布局）。
 * <p>
 * 标准规定：一个 Skill = 一个目录，根目录为 {@code skills/}，目录内含
 * {@code SKILL.md}（YAML frontmatter + 提示词正文）与可选资源目录
 * {@code scripts/}、{@code references/}、{@code assets/}。
 * </p>
 * <pre>{@code
 * skills/
 *   pdf-processing/
 *     SKILL.md
 *     scripts/rotate.py
 *     references/FORMS.md
 * }</pre>
 * <p>
 * 本组件负责目录的实时读写：扫描、解析、写入、列文件、读文件、zip 解压导入、
 * 以及在系统上打开目录（便于用户把网上下载的 Skill 直接拖进去）。
 * </p>
 */
@Slf4j
@Component
public class SkillFileStore {

    public static final String SKILL_FILE = "SKILL.md";

    private final Path root;
    private final boolean openFolderEnabled;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 主构造（Spring 注入）——多构造时必须显式标注 @Autowired。 */
    @Autowired
    public SkillFileStore(
            @Value("${agent-platform.skills.dir:./data/skills}") String dir,
            @Value("${agent-platform.skills.open-folder-enabled:true}") boolean openFolderEnabled) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.openFolderEnabled = openFolderEnabled;
    }

    /** skills 根目录绝对路径。 */
    public Path root() {
        return root;
    }

    /** 是否允许在系统文件管理器中打开目录。 */
    public boolean openFolderEnabled() {
        return openFolderEnabled;
    }

    /**
     * 扫描 skills 根目录，返回全部合法 Skill 目录（含 SKILL.md）。
     * <p>根目录下一级子目录即 Skill；SKILL.md 缺失的目录会被跳过并记录。</p>
     */
    public List<ScannedSkill> scan() {
        ensureRoot();
        List<ScannedSkill> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName)).toList()) {
                Path skillFile = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile)) {
                    log.debug("[skills] 目录 {} 缺少 {}，跳过", dir.getFileName(), SKILL_FILE);
                    continue;
                }
                try {
                    String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
                    found.add(parseSkill(dir.getFileName().toString(), raw));
                } catch (Exception e) {
                    log.warn("[skills] 解析 {} 失败: {}", skillFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw BizException.internal("扫描 skills 目录失败: " + e.getMessage(), e);
        }
        return found;
    }

    /**
     * 解析 SKILL.md：YAML frontmatter（--- 包围）+ Markdown 正文（提示词）。
     */
    public ScannedSkill parseSkill(String dir, String raw) {
        String body;
        Map<String, Object> front = new LinkedHashMap<>();
        String text = raw == null ? "" : raw.replace("\r\n", "\n");
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String yaml = text.substring(3, end).trim();
                body = text.substring(end + 4).replaceFirst("^\n", "");
                if (!yaml.isBlank()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = yamlMapper.readValue(yaml, Map.class);
                        front = parsed == null ? new LinkedHashMap<>() : parsed;
                    } catch (Exception e) {
                        throw BizException.validation("SKILL.md frontmatter 解析失败（" + dir + "）: " + e.getMessage());
                    }
                }
            } else {
                body = text;  // 未闭合的 ---：整体当正文
            }
        } else {
            body = text;
        }

        String name = str(front.get("name"));
        if (name.isBlank()) {
            name = dir;  // 标准允许以目录名兜底
        }
        String version = str(front.getOrDefault("version", "1.0.0"));
        if (version.isBlank()) {
            version = "1.0.0";
        }
        SkillManifest manifest = new SkillManifest(
                name,
                version,
                str(front.get("description")),
                body == null ? "" : body.trim(),
                listOf(front.get("tools")),
                mapOf(front.get("workflow")),
                mapOf(front.get("params")),
                dir,
                str(front.get("license")),
                listOf(front.getOrDefault("allowed-tools", front.get("allowed_tools"))),
                mapOf(front.get("metadata")));
        return new ScannedSkill(dir, manifest);
    }

    /**
     * 以标准格式写入 SKILL.md（新建或按字段编辑 Skill 时调用）。
     */
    public Path writeSkill(String dir, SkillManifest manifest) {
        ensureRoot();
        Path target = resolveDir(dir);
        try {
            Files.createDirectories(target);
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(quote(manifest.name())).append('\n');
            sb.append("description: ").append(quote(manifest.description())).append('\n');
            sb.append("version: ").append(quote(manifest.version() == null ? "1.0.0" : manifest.version())).append('\n');
            if (manifest.license() != null && !manifest.license().isBlank()) {
                sb.append("license: ").append(quote(manifest.license())).append('\n');
            }
            List<String> tools = manifest.effectiveTools();
            if (tools != null && !tools.isEmpty()) {
                sb.append("allowed-tools:\n");
                tools.forEach(t -> sb.append("  - ").append(quote(t)).append('\n'));
            }
            sb.append("---\n\n");
            sb.append(manifest.prompt() == null ? "" : manifest.prompt().trim()).append('\n');
            Files.writeString(target.resolve(SKILL_FILE), sb.toString(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw BizException.internal("写入 SKILL.md 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解压上传的 zip 到 skills 目录。
     * <p>zip 内含单层根目录时自动剥离（如 {@code my-skill/SKILL.md}）；
     * 直接含 SKILL.md 时按 zip 名建目录。返回导入的目录名列表。</p>
     */
    public List<String> extractZip(String zipName, byte[] bytes) {
        ensureRoot();
        String base = sanitize(stripExt(zipName));
        Path stage = root.resolve("." + base + ".import");
        List<String> dirs = new ArrayList<>();
        try {
            deleteRecursively(stage);
            Files.createDirectories(stage);
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path out = stage.resolve(safeRelative(entry.getName())).normalize();
                    if (!out.startsWith(stage)) {
                        continue;  // 防 zip slip
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zis, out);
                    }
                }
            }
            // 归一化：若 stage 下只有单个目录且它含 SKILL.md，则提升该目录
            Path src = stage;
            List<Path> children = listChildren(stage);
            if (children.size() == 1 && Files.isDirectory(children.get(0))
                    && Files.isRegularFile(children.get(0).resolve(SKILL_FILE))) {
                src = children.get(0);
            } else if (Files.isRegularFile(stage.resolve(SKILL_FILE))) {
                src = stage;
            } else {
                // 未找到 SKILL.md：整个包当成一个目录（名称取 zip 名）
                src = stage;
            }
            String dirName = sanitize(Files.isRegularFile(src.resolve(SKILL_FILE))
                    ? (src == stage ? base : src.getFileName().toString()) : base);
            Path target = resolveDir(dirName);
            deleteRecursively(target);
            if (src == stage) {
                Files.move(stage, target);
            } else {
                Files.createDirectories(target.getParent());
                Files.move(src, target);
            }
            dirs.add(dirName);
            return dirs;
        } catch (IOException e) {
            throw BizException.internal("解压 Skill 压缩包失败: " + e.getMessage(), e);
        } finally {
            deleteRecursively(stage);
        }
    }

    /**
     * 单个 SKILL.md 文件导入：按 frontmatter 名称（或文件名）建目录。
     */
    public String importSingleFile(String fileName, byte[] content) {
        ensureRoot();
        String raw = new String(content, StandardCharsets.UTF_8);
        ScannedSkill parsed = parseSkill(sanitize(stripExt(fileName)), raw);
        String dir = sanitize(parsed.manifest().name());
        writeSkill(dir, parsed.manifest());
        return dir;
    }

    /** 列出 Skill 目录内的文件（相对路径，按目录优先排序）。 */
    public List<String> listFiles(String dir) {
        Path base = resolveDir(dir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> rel = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(base)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().equals(SKILL_FILE) ? 0 : 1)
                            .thenComparing(Path::toString))
                    .forEach(p -> rel.add(base.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw BizException.internal("列出 Skill 文件失败: " + e.getMessage(), e);
        }
        return rel;
    }

    /** 读取 Skill 目录内某文件内容（相对路径）。 */
    public String readFile(String dir, String relative) {
        Path target = resolveDir(dir).resolve(safeRelative(relative)).normalize();
        if (!target.startsWith(resolveDir(dir))) {
            throw BizException.badRequest("非法文件路径（越权读取）: " + relative);
        }
        if (!Files.isRegularFile(target)) {
            throw BizException.notFound("skill file", relative);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.internal("读取 Skill 文件失败: " + e.getMessage(), e);
        }
    }

    /** 删除 Skill 目录（物理删除 Skill 时调用）。 */
    public boolean deleteDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return false;
        }
        Path target = resolveDir(dir);
        if (!Files.exists(target)) {
            return false;
        }
        deleteRecursively(target);
        return true;
    }

    /**
     * 在系统文件管理器中打开 skills 目录（便于用户直接放入下载的 Skill 目录）。
     * <p>仅本地/桌面场景有意义；由 {@code agent-platform.skills.open-folder-enabled} 控制。</p>
     */
    public void openFolder() {
        if (!openFolderEnabled) {
            throw BizException.forbidden("未开启目录打开能力（agent-platform.skills.open-folder-enabled=false）");
        }
        ensureRoot();
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", root.toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", root.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", root.toString());
            }
            pb.start();
        } catch (IOException e) {
            throw BizException.internal("打开目录失败: " + e.getMessage(), e);
        }
    }

    /** 目录名清洗：仅保留安全字符。 */
    public String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "-");
        s = s.replaceAll("\\s+", "-");
        s = s.replaceAll("\\.{2,}", ".");
        if (s.isBlank()) {
            s = "skill-" + System.currentTimeMillis();
        }
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s;
    }

    /** 解析目录路径并防穿越。 */
    private Path resolveDir(String dir) {
        Path p = root.resolve(sanitize(dir)).normalize();
        if (!p.startsWith(root)) {
            throw BizException.badRequest("非法 Skill 目录: " + dir);
        }
        return p;
    }

    private void ensureRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw BizException.internal("创建 skills 目录失败: " + e.getMessage(), e);
        }
    }

    private String safeRelative(String name) {
        String s = (name == null ? "" : name).replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s.replace("..", "").replaceAll("^/+", "");
    }

    private List<Path> listChildren(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).toList();
        }
    }

    private void deleteRecursively(Path target) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[skills] 删除 {} 失败: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[skills] 遍历删除 {} 失败: {}", target, e.getMessage());
        }
    }

    private String stripExt(String fileName) {
        if (fileName == null) {
            return "skill";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String quote(String v) {
        if (v == null) {
            return "\"\"";
        }
        String s = v.replace("\"", "\\\"");
        return "\"" + s + "\"";
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private List<String> listOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("[,\\s]+"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 扫描结果（目录名 + 解析出的清单）。 */
    public record ScannedSkill(String dir, SkillManifest manifest) {
    }

    /** 便于单测注入的构造器（绕过 Spring）。 */
    public SkillFileStore(Path root, boolean openFolderEnabled) {
        this.root = root.toAbsolutePath().normalize();
        this.openFolderEnabled = openFolderEnabled;
    }
}
