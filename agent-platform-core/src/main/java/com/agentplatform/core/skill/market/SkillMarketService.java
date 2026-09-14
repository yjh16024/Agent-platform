package com.agentplatform.core.skill.market;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.core.skill.SkillFileStore;
import com.agentplatform.core.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>为什么要多通道</b>：国内访问 GitHub 的可用路径因网络而异，单点写死（原来直接打
 * {@code api.github.com}）在受限网络下会静默变空。现在按
 * <b>GitHub 加速代理 → jsDelivr 数据接口 → 直连</b> 的顺序依次尝试，谁通用谁，
 * 并记住最近可用的通道；还提供 {@link #diagnose()} 让用户直接看到每条通道通不通。</p>
 *
 * <p>顺序为什么是"代理在前"：实测 jsDelivr 的 flat 目录树**存在滞后与缺漏**
 * （官方 20 个技能只列出 17 个、419 个文件只列出 282 个），
 * 而 GitHub Trees API 完整且 {@code truncated=false}。所以能用代理就用代理拿准确目录，
 * 代理全不通时再退回 jsDelivr 保可用（可能少列几个技能，但功能不中断）。</p>
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
    /** 文件树的缓存时长：短缓存既能加速重复浏览，又不会让新装的技能看不见。 */
    private static final long TREE_TTL_MS = 120_000L;
    /** 解析 SKILL.md frontmatter（官方技能大量使用 YAML 多行折叠/字面量语法）。 */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final SkillFileStore fileStore;
    private final SkillService skillService;
    private final List<SkillChannel> channels;
    private final Map<String, CachedTree> treeCache = new ConcurrentHashMap<>();
    /** 最近一次成功的通道 id —— 下次优先走它，避免每条请求都从第一条通道开始试错。 */
    private volatile String preferredChannel;

    private record CachedTree(List<String> files, long at) {
    }

    private interface Attempt<T> {
        T run(SkillChannel channel) throws IOException;
    }

    public SkillMarketService(
            SkillFileStore fileStore,
            SkillService skillService,
            @Value("${agent-platform.skills.market.sources:ghproxy,jsdelivr,github}") String sources,
            @Value("${agent-platform.skills.market.jsdelivr-api:data.jsdelivr.com}") String jsdelivrApi,
            @Value("${agent-platform.skills.market.jsdelivr-cdns:cdn.jsdelivr.net,gcore.jsdelivr.net,fastly.jsdelivr.net}")
            String jsdelivrCdns,
            @Value("${agent-platform.skills.market.gh-proxies:gh-proxy.com,ghfast.top,ghproxy.net}") String ghProxies,
            @Value("${agent-platform.skills.market.timeout-seconds:25}") int timeoutSeconds) {
        this.fileStore = fileStore;
        this.skillService = skillService;
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .followRedirects(true)
                .build();
        this.channels = buildChannels(http, split(sources), split(jsdelivrApi), split(jsdelivrCdns), split(ghProxies));
        log.info("[skill-market] 取件通道（按优先级）：{}",
                channels.stream().map(SkillChannel::id).toList());
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
        String branch = resolveBranch(ref);
        String root = ref.scanRoot();
        List<Map<String, Object>> skills = detectSkills(ref.fullName(), branch, root, refresh);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("repo", ref.fullName());
        r.put("branch", branch);
        r.put("subPath", root);
        r.put("count", skills.size());
        r.put("skills", skills);
        r.put("channel", preferredChannel);
        return r;
    }

    /** 从任意仓库安装其中一个技能。{@code skillPath} 为空表示"整个仓库就是一个技能"。 */
    public Map<String, Object> installFrom(String tenantId, String url, String skillPath, String name) {
        GitRepoRef ref = GitRepoRef.parse(url);
        String branch = resolveBranch(ref);
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
        String branch = resolveBranch(ref);
        String clean = stripLeadingSlash(path);
        byte[] data = attempt("读取 " + clean, c -> c.read(ref.fullName(), branch, clean));
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
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> cs = new ArrayList<>();
        for (SkillChannel c : channels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id());
            item.put("group", c.group());
            item.put("label", c.label());
            cs.add(item);
        }
        m.put("channels", cs);
        m.put("preferred", preferredChannel);
        m.put("defaultRepo", DEFAULT_REPO);
        return m;
    }

    /**
     * 网络诊断：并行探测每条通道，返回是否可用与耗时。
     * <p>国内网络下"技能市场空白"最常见的原因就是所有通道都不通，这个接口让原因可见。</p>
     */
    public List<Map<String, Object>> diagnose() {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, channels.size())), r -> {
            Thread t = new Thread(r, "skill-channel-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (SkillChannel c : channels) {
                fs.add(pool.submit(() -> probe(c)));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Future<Map<String, Object>> f : fs) {
                try {
                    Map<String, Object> m = f.get(60, TimeUnit.SECONDS);
                    if (m != null) {
                        out.add(m);
                    }
                } catch (Exception e) {
                    log.debug("[skill-market] 探测任务异常: {}", e.getMessage());
                }
            }
            out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 通道编排 ----------------

    /** 按配置里的**分组顺序**构造通道（sources 决定优先级，不只是开关）。 */
    private static List<SkillChannel> buildChannels(OkHttpClient http, List<String> sources,
                                                    List<String> jsdelivrApi, List<String> jsdelivrCdns,
                                                    List<String> ghProxies) {
        List<String> order = sources.isEmpty() ? List.of("ghproxy", "jsdelivr", "github") : sources;
        List<SkillChannel> out = new ArrayList<>();
        for (String group : order) {
            String g = group.trim().toLowerCase(java.util.Locale.ROOT);
            switch (g) {
                case "jsdelivr" -> {
                    // API 主机用于列文件，CDN 主机列表用于内容读取的失败切换
                    List<String> api = jsdelivrApi.isEmpty() ? List.of("data.jsdelivr.com") : jsdelivrApi;
                    List<String> cdn = jsdelivrCdns.isEmpty() ? List.of("cdn.jsdelivr.net") : jsdelivrCdns;
                    out.add(new JsDelivrChannel(http, api, cdn));
                }
                case "ghproxy" -> {
                    for (String host : ghProxies) {
                        String h = host.trim();
                        if (h.isBlank()) {
                            continue;
                        }
                        out.add(new GithubApiChannel(http, "https://" + h + "/", "ghproxy:" + h,
                                h + " 加速代理", "ghproxy"));
                    }
                }
                case "github" -> out.add(new GithubApiChannel(http, "", "github",
                        "GitHub 直连（无代理，可能不可达）", "github"));
                default -> log.warn("[skill-market] 未知的取件通道分组，已忽略: {}", group);
            }
        }
        return List.copyOf(out);
    }

    /** 把最近成功的通道提到最前面。 */
    private List<SkillChannel> orderedChannels() {
        String p = preferredChannel;
        if (p == null) {
            return channels;
        }
        List<SkillChannel> out = new ArrayList<>(channels.size());
        for (SkillChannel c : channels) {
            if (p.equals(c.id())) {
                out.add(c);
            }
        }
        for (SkillChannel c : channels) {
            if (!p.equals(c.id())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 遍历通道执行一次取件；成功即返回并记住该通道。
     * <p>全部失败时：若所有失败都是"资源不存在"，抛 404；否则抛 500 并带上每一条通道的原因——
     * 让前端能直接告诉用户"到底卡在哪"。</p>
     */
    private <T> T attempt(String op, Attempt<T> fn) {
        List<String> errors = new ArrayList<>();
        // 只要**有一条通道明确回答"不存在"**（HTTP 404），就按不存在处理：
        // 其余通道的失败可能只是限流(403)/网络不通，不足以推翻一个确定答案。
        boolean sawMiss = false;
        for (SkillChannel c : orderedChannels()) {
            try {
                T r = fn.run(c);
                if (r != null) {
                    preferredChannel = c.id();
                    return r;
                }
            } catch (SkillChannel.Miss e) {
                sawMiss = true;
                errors.add(c.id() + "：" + e.getMessage());
            } catch (Exception e) {
                errors.add(c.id() + "：" + e.getMessage());
                log.debug("[skill-market] {} 经 {} 失败: {}", op, c.id(), e.getMessage());
            }
        }
        if (errors.isEmpty()) {
            return null;
        }
        String detail = String.join("；", errors);
        if (sawMiss) {
            throw new BizException("NOT_FOUND", "远程资源不存在（" + op + "）：" + detail);
        }
        throw new BizException("INTERNAL_ERROR",
                "所有取件通道都失败（" + op + "）。各通道原因：" + detail
                        + "。可在市场弹窗点「网络诊断」查看通道状态。");
    }

    // ---------------- 核心流程 ----------------

    /** 列出仓库全部文件（仓库相对路径），带短缓存与通道失败切换。 */
    private List<String> listFiles(String repo, String branch, boolean refresh) {
        String key = repo + "@" + branch;
        if (!refresh) {
            CachedTree t = treeCache.get(key);
            if (t != null && System.currentTimeMillis() - t.at() < TREE_TTL_MS) {
                return t.files();
            }
        }
        List<String> files = attempt("列出仓库文件 " + key, c -> c.listFiles(repo, branch));
        List<String> safe = files == null ? List.of() : files;
        treeCache.put(key, new CachedTree(safe, System.currentTimeMillis()));
        return safe;
    }

    /** 解析分支：用户给了就用；否则问各通道要默认分支，最后实测 main / master。 */
    private String resolveBranch(GitRepoRef ref) {
        if (ref.branch() != null && !ref.branch().isBlank()) {
            return ref.branch().trim();
        }
        String repo = ref.fullName();
        try {
            String b = attempt("读取默认分支 " + repo, c -> c.defaultBranch(repo));
            if (b != null && !b.isBlank()) {
                return b.trim();
            }
        } catch (Exception e) {
            log.debug("[skill-market] 默认分支获取失败，改为实测 main/master: {}", e.getMessage());
        }
        for (String b : List.of("main", "master")) {
            try {
                if (!listFiles(repo, b, false).isEmpty()) {
                    return b;
                }
            } catch (Exception e) {
                log.debug("[skill-market] 分支 {} 不可用: {}", b, e.getMessage());
            }
        }
        return DEFAULT_BRANCH;
    }

    /**
     * 在仓库中识别技能：**凡是目录下直接含 {@code SKILL.md} 的，就是一个技能**
     * （比"数目录"更准——官方仓库里就有不含 SKILL.md 的目录）。
     */
    private List<Map<String, Object>> detectSkills(String repo, String branch, String subPath, boolean refresh) {
        List<String> files = listFiles(repo, branch, refresh);
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
            byte[] data = attempt("读取 " + skillMdPath, c -> c.read(repo, branch, skillMdPath));
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
        List<String> files = listFiles(repo, branch, false);
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
            byte[] data = attempt("下载 " + f, c -> c.read(repo, branch, f));
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

    private Map<String, Object> probe(SkillChannel c) {
        long t0 = System.nanoTime();
        boolean ok;
        String err = "";
        try {
            ok = c.available();
        } catch (Exception e) {
            ok = false;
            err = String.valueOf(e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("group", c.group());
        m.put("label", c.label());
        m.put("ok", ok);
        m.put("preferred", c.id().equals(preferredChannel));
        m.put("ms", (System.nanoTime() - t0) / 1_000_000L);
        if (!ok && !err.isBlank()) {
            m.put("error", err);
        }
        return m;
    }

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

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String p : csv.split(",")) {
            String s = p.trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
