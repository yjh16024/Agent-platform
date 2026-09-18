package com.agentplatform.core.skill.market;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.net.RemoteFetchService;
import com.agentplatform.core.skill.SkillFileStore;
import com.agentplatform.core.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 技能市场：**多通道取件** + **任意仓库地址直读**。
 *
 * <p>官方 Agent Skills 开放标准（每个技能一个目录 + {@code SKILL.md}）与本平台
 * {@link SkillFileStore} 的目录约定完全一致，所以"下载下来即可用"，不需要任何格式转换。</p>
 *
 * <p>取件本身（多通道 + 失败切换 + 短缓存）已抽到 {@link RemoteFetchService}，
 * 这里只保留技能领域的业务逻辑：识别技能目录、读 frontmatter、安装并同步入库。
 * 换肤用的皮肤市场（{@code core.skin}）复用同一个取件服务。</p>
 *
 * <p><b>任意仓库</b>：用户可以把在别处找到的技能库地址（GitHub 仓库 / 子目录 / 文件链接，
 * 甚至套了加速前缀的地址）直接粘进来，先 {@link #scan(String, boolean)} 列出其中的技能并
 * 预览 {@code SKILL.md} 内容，再按需安装。</p>
 */
@Slf4j
@Service
public class SkillMarketService {

    /** 内置市场对应的官方仓库。 */
    public static final String DEFAULT_REPO = "anthropics/skills";
    public static final String DEFAULT_BRANCH = "main";
    private static final String SKILL_FILE = "SKILL.md";
    /** 一次最多列出多少个技能（防超大仓库把界面撑爆）。 */
    private static final int MAX_SKILLS = 80;
    /** 并发取摘要/文件的线程数（对镜像站友好一点）。 */
    private static final int FETCH_THREADS = 8;
    /** 预览文件的最大字符数。 */
    private static final int MAX_PREVIEW = 512 * 1024;
    /** 解析 SKILL.md frontmatter（官方技能大量使用 YAML 多行折叠/字面量语法）。 */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final SkillFileStore fileStore;
    private final SkillService skillService;
    private final RemoteFetchService fetch;

    public SkillMarketService(SkillFileStore fileStore, SkillService skillService, RemoteFetchService fetch) {
        this.fileStore = fileStore;
        this.skillService = skillService;
        this.fetch = fetch;
    }

    // ---------------- 对外能力 ----------------

    /** 内置市场：列出官方仓库里的全部技能（含是否已安装到本地）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> all = detectSkills(DEFAULT_REPO, DEFAULT_BRANCH, "", false);
        all.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return all;
    }

    /** 内置市场一键安装（老接口，按技能名）。 */
    public Map<String, Object> install(String tenantId, String name) {
        String safe = sanitizeName(name);
        if (safe.isBlank()) {
            throw BizException.badRequest("技能名非法");
        }
        String dir = detectSkills(DEFAULT_REPO, DEFAULT_BRANCH, "", false).stream()
                .filter(s -> safe.equals(s.get("name")))
                .map(s -> String.valueOf(s.get("path")))
                .findFirst()
                .orElse(null);
        if (dir == null) {
            throw BizException.notFound("skill in market", safe);
        }
        return installSkill(tenantId, DEFAULT_REPO, DEFAULT_BRANCH, dir, safe);
    }

    /**
     * 扫描**任意仓库地址**下的技能（不安装）。
     * <p>返回项含 name / path / title / description / installed / skillFile。</p>
     */
    public Map<String, Object> scan(String url, boolean refresh) {
        GitRepoRef ref = GitRepoRef.parse(url);
        String branch = fetch.resolveBranch(ref.fullName(), ref.branch());
        String root = ref.scanRoot();
        List<Map<String, Object>> skills = detectSkills(ref.fullName(), branch, root, refresh);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("repo", ref.fullName());
        r.put("branch", branch);
        r.put("subPath", root);
        r.put("count", skills.size());
        r.put("skills", skills);
        r.put("channel", fetch.preferredChannel());
        return r;
    }

    /** 从任意仓库安装其中一个技能。{@code skillPath} 为空表示"整个仓库就是一个技能"。 */
    public Map<String, Object> installFrom(String tenantId, String url, String skillPath, String name) {
        GitRepoRef ref = GitRepoRef.parse(url);
        String branch = fetch.resolveBranch(ref.fullName(), ref.branch());
        String dir = skillPath == null ? "" : skillPath.trim();
        String n = name == null || name.isBlank() ? defaultName(ref.fullName(), dir) : name;
        return installSkill(tenantId, ref.fullName(), branch, dir, n);
    }

    /** 读取远端任意文件内容（预览 SKILL.md / scripts 等）。 */
    public Map<String, Object> readRemoteFile(String url, String path) {
        if (path == null || path.isBlank()) {
            throw BizException.badRequest("path 不能为空");
        }
        GitRepoRef ref = GitRepoRef.parse(url);
        String branch = fetch.resolveBranch(ref.fullName(), ref.branch());
        String clean = stripLeadingSlash(path);
        byte[] data = fetch.attempt("读取 " + clean, c -> c.read(ref.fullName(), branch, clean));
        if (data == null) {
            throw BizException.notFound("file", clean);
        }
        boolean binary = isBinary(data);
        boolean truncated = data.length > MAX_PREVIEW;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("repo", ref.fullName());
        r.put("branch", branch);
        r.put("path", clean);
        r.put("size", data.length);
        r.put("binary", binary);
        r.put("truncated", truncated);
        r.put("content", binary
                ? "（二进制文件，不支持预览，共 " + data.length + " 字节）"
                : new String(data, 0, Math.min(data.length, MAX_PREVIEW), StandardCharsets.UTF_8));
        return r;
    }

    /** 通道清单与当前生效的通道（前端展示/诊断用）。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>(fetch.status());
        m.put("defaultRepo", DEFAULT_REPO);
        return m;
    }

    /** 网络诊断：并行探测每条通道（转调取件服务）。 */
    public List<Map<String, Object>> diagnose() {
        return fetch.diagnose();
    }

    // ---------------- 核心流程 ----------------

    /**
     * 在仓库中识别技能：**凡是目录下直接含 {@code SKILL.md} 的，就是一个技能**
     * （比"数目录"更准——官方仓库里就有不含 SKILL.md 的目录）。
     */
    private List<Map<String, Object>> detectSkills(String repo, String branch, String subPath, boolean refresh) {
        List<String> files = fetch.listFiles(repo, branch, refresh);
        String root = stripTrailingSlash(stripLeadingSlash(subPath == null ? "" : subPath));
        String prefix = root.isBlank() ? "" : root + "/";
        Set<String> installed = localSkillDirs();

        List<String> skillDirs = new ArrayList<>();
        List<String> skillFiles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String f : files) {
            String dir;
            if (f.equals(SKILL_FILE)) {
                dir = "";
            } else if (f.endsWith("/" + SKILL_FILE)) {
                dir = f.substring(0, f.length() - SKILL_FILE.length() - 1);
            } else {
                continue;
            }
            if (!root.isBlank() && !dir.equals(root) && !dir.startsWith(prefix)) {
                continue;
            }
            if (!seen.add(dir)) {
                continue;
            }
            skillDirs.add(dir);
            skillFiles.add(f);
            if (skillDirs.size() >= MAX_SKILLS) {
                log.warn("[skill-market] 技能数超过上限 {}，已截断: {}@{}", MAX_SKILLS, repo, branch);
                break;
            }
        }
        if (skillDirs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_THREADS, skillDirs.size()), r -> {
            Thread t = new Thread(r, "skill-market-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (int i = 0; i < skillDirs.size(); i++) {
                final String dir = skillDirs.get(i);
                final String md = skillFiles.get(i);
                fs.add(pool.submit(() -> describe(repo, branch, dir, md, installed)));
            }
            for (Future<Map<String, Object>> f : fs) {
                try {
                    Map<String, Object> m = f.get(60, TimeUnit.SECONDS);
                    if (m != null) {
                        out.add(m);
                    }
                } catch (Exception e) {
                    log.debug("[skill-market] 取技能摘要失败: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private Map<String, Object> describe(String repo, String branch, String dir, String skillMdPath,
                                        Set<String> installed) {
        String name = defaultName(repo, dir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("path", dir);
        m.put("dir", dir);
        m.put("source", repo);
        m.put("branch", branch);
        m.put("skillFile", skillMdPath);
        m.put("installed", installed.contains(name));
        String title = null;
        String description = null;
        try {
            byte[] data = fetch.attempt("读取 " + skillMdPath, c -> c.read(repo, branch, skillMdPath));
            if (data != null) {
                String md = new String(data, StandardCharsets.UTF_8);
                title = frontmatter(md, "name");
                description = frontmatter(md, "description");
            }
        } catch (Exception e) {
            log.debug("[skill-market] 读取 {} 失败: {}", skillMdPath, e.getMessage());
        }
        m.put("title", title == null || title.isBlank() ? name : title);
        m.put("description", description == null ? "" : description);
        return m;
    }

    /** 安装技能：把该技能目录下的文件全部下载到本地 skills 目录，然后同步入库。 */
    private Map<String, Object> installSkill(String tenantId, String repo, String branch,
                                            String skillDir, String name) {
        String safe = sanitizeName(name);
        if (safe.isBlank()) {
            throw BizException.badRequest("技能名非法：" + name);
        }
        List<String> files = fetch.listFiles(repo, branch, false);
        String dir = stripTrailingSlash(stripLeadingSlash(skillDir == null ? "" : skillDir));
        String prefix = dir.isBlank() ? "" : dir + "/";
        List<String> targets = new ArrayList<>();
        for (String f : files) {
            if (prefix.isBlank()) {
                targets.add(f);
            } else if (f.startsWith(prefix) && !f.equals(prefix)) {
                targets.add(f);
            }
        }
        if (targets.isEmpty()) {
            throw BizException.notFound("skill", dir.isBlank() ? repo : dir);
        }
        if (targets.stream().noneMatch(f -> f.endsWith(SKILL_FILE))) {
            throw BizException.badRequest("该目录下没有 " + SKILL_FILE + "，不是标准技能：" + dir);
        }
        Path root = fileStore.root();
        Path localDir = root.resolve(safe).normalize();
        if (!localDir.startsWith(root)) {
            throw BizException.badRequest("技能名越界：" + name);
        }
        int written = 0;
        for (String f : targets) {
            String rel = prefix.isBlank() ? f : f.substring(prefix.length());
            if (rel.isBlank()) {
                continue;
            }
            Path target = localDir.resolve(rel).normalize();
            if (!target.startsWith(localDir)) {
                log.warn("[skill-market] 跳过可疑路径: {}", f);
                continue;
            }
            byte[] data = fetch.attempt("下载 " + f, c -> c.read(repo, branch, f));
            if (data == null) {
                continue;
            }
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(target, data);
                written++;
            } catch (IOException e) {
                throw BizException.internal("写入技能文件失败 " + target + "：" + e.getMessage(), e);
            }
        }
        log.info("[skill-market] 已安装技能 {}（{} 个文件）<- {}@{}:{}", safe, written, repo, branch, dir);
        Map<String, Object> sync = skillService.syncFromFolder(tenantId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("skill", safe);
        r.put("dir", localDir.toString());
        r.put("files", written);
        r.put("source", repo);
        r.put("branch", branch);
        r.put("path", dir);
        r.put("sync", sync);
        return r;
    }

    // ---------------- 小工具 ----------------

    /** 技能目录名：取路径末段；仓库根目录本身是技能时用仓库名。 */
    private static String defaultName(String repo, String dir) {
        if (dir == null || dir.isBlank()) {
            String r = stripTrailingSlash(repo == null ? "" : repo);
            int slash = r.lastIndexOf('/');
            return slash < 0 ? r : r.substring(slash + 1);
        }
        String d = stripTrailingSlash(dir);
        int slash = d.lastIndexOf('/');
        return slash < 0 ? d : d.substring(slash + 1);
    }

    /** 本地 skills 目录下已存在的技能目录名。 */
    private Set<String> localSkillDirs() {
        Set<String> dirs = new TreeSet<>();
        Path root = fileStore.root();
        if (!Files.isDirectory(root)) {
            return dirs;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(p -> dirs.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.debug("[skill-market] 列本地技能失败: {}", e.getMessage());
        }
        return dirs;
    }

    /**
     * 取 SKILL.md frontmatter（首个 `---` 块）里某个键的值。
     * <p>用 YAML 解析器而非按行截取：官方 SKILL.md 大量使用多行折叠（`>`）与字面量（`|-`）语法，
     * 按行取只会拿到 `>` / `|-` 这样的标记本身。</p>
     */
    private static String frontmatter(String md, String key) {
        if (md == null || md.isBlank()) {
            return null;
        }
        String normalized = md.replace("\r\n", "\n");
        if (!normalized.startsWith("---")) {
            return null;
        }
        int end = normalized.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        String block = normalized.substring(3, end);
        try {
            JsonNode node = YAML_MAPPER.readTree(block);
            if (node == null) {
                return null;
            }
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                return null;
            }
            String s = v.asText();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            log.debug("[skill-market] frontmatter 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 粗略判断二进制（含 NUL 字节）。 */
    private static boolean isBinary(byte[] data) {
        int n = Math.min(data.length, 8000);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 技能目录名只允许字母/数字/._-，杜绝 `../` 之类的越界。 */
    private static String sanitizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String stripLeadingSlash(String s) {
        String r = s;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        return r;
    }

    private static String stripTrailingSlash(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
