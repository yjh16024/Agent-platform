package com.agentplatform.core.skin;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.net.RemoteFetchService;
import com.agentplatform.core.skill.market.GitRepoRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 皮肤市场：读取**外部皮肤市场地址**、按网站的条目信息展示、点安装后从源码下载。
 *
 * <p>以 DSH Web GUI 皮肤市场为例（{@code https://kingofsoysauce.github.io/dsh-skin-market/}），
 * 这类站点背后都是一个仓库 + 一份机器可读的目录文件（{@code data/catalog.json}）。
 * 所以"读取这类地址"不是去爬 HTML，而是：</p>
 * <ol>
 *   <li>把用户粘的地址归一化回 owner/repo（支持 {@code *.github.io} 站点、GitHub 仓库、catalog.json 直链）；</li>
 *   <li>用 {@link RemoteFetchService} 的多通道取件拿到目录 JSON（raw 不通时自动走代理或 jsDelivr）；</li>
 *   <li>解析并归一化每个皮肤条目（名称/作者/标签/亮暗模式/预览图/健康度/许可证）；</li>
 *   <li>安装时按条目的 {@code install.target}（{@code github:owner/repo#commit&path:/sub}）下载源码；
 *       用 commit 而不是分支，保证装到的就是市场里展示的那一版。</li>
 * </ol>
 *
 * <p><b>另外提供 {@link #clientBundle(String)}</b>：把皮肤自己的客户端 bundle 原样交给前端执行
 * ——这是"兼容各种皮肤"的真正落点，皮肤的美术/动效/部件开关全靠它。</p>
 *
 * <p>原来这里还有两条"只搬资源"的路：从皮肤包里抽配色（{@code /theme}）与抽美术图层
 * （{@code /plan}）。它们随前端的「应用」按钮一起下线了 —— 只搬配色与图片只能做到半套，
 * 还会和皮肤自己的渲染打架。相关接口、抽取器与缓存都已删除。</p>
 */
@Slf4j
@Service
public class SkinMarketService {

    /** 每个皮肤目录里记录安装来源与署名信息（许可证要求保留 NOTICE/署名链）。 */
    private static final String META_FILE = ".skin-meta.json";
    /**
     * 已知的 DSH 配套插件：皮肤包里的作用域属性 → 界面上的说法。
     *
     * <p>皮肤之间是会互相依赖的：例如 orca-link 给 {@code [data-dsh-better-sidebar] .xterm}
     * 写了终端样式，没装那个插件时这部分规则永远不命中。要注意这是**生态固有问题**，
     * 不是皮肤或平台的缺陷 —— 我们能做的是把依赖如实告诉用户。</p>
     */
    private static final Map<String, String> KNOWN_PARTNER_PLUGINS = Map.of(
            "data-dsh-better-sidebar", "better-sidebar（侧栏终端面板）");

    /** 目录文件的候选路径，按优先级。 */
    private static final List<String> CATALOG_CANDIDATES = List.of("data/catalog.json", "catalog.json", "skins.json");
    /** 一个市场最多返回多少条（防超大市场把界面撑爆）。 */
    private static final int MAX_SKINS = 800;
    /** 并发下载线程数。 */
    private static final int FETCH_THREADS = 6;
    /** 单个皮肤包读取上限。 */
    private static final long MAX_BUNDLE = 24L * 1024 * 1024;

    private final RemoteFetchService fetch;
    private final Path skinsRoot;

    public SkinMarketService(RemoteFetchService fetch,
                            @Value("${agent-platform.skins.dir:./data/skins}") String skinsDir) {
        this.fetch = fetch;
        this.skinsRoot = Path.of(skinsDir).toAbsolutePath().normalize();
    }

    // ---------------- 1) 读取市场 ----------------

    /**
     * 读取某个皮肤市场地址，返回归一化后的皮肤清单。
     * <p>{@code url} 可以是站点首页、GitHub 仓库地址，或直接是 {@code catalog.json} 的地址。</p>
     */
    public Map<String, Object> readMarket(String url, boolean refresh) {
        if (url == null || url.isBlank()) {
            throw BizException.badRequest("市场地址不能为空");
        }
        String u = url.trim();
        String text;
        String repo = null;
        String branch = null;
        String catalogPath = null;
        if (u.toLowerCase().endsWith(".json")) {
            text = fetch.getTextByUrl(u);
            catalogPath = u;
        } else {
            GitRepoRef ref = GitRepoRef.parse(u);
            repo = ref.fullName();
            branch = fetch.resolveBranch(repo, ref.branch());
            List<String> files = fetch.listFiles(repo, branch, refresh);
            catalogPath = pickCatalog(files, ref.scanRoot());
            if (catalogPath == null) {
                throw BizException.notFound("catalog.json", repo + " @ " + branch);
            }
            text = new String(fetch.read(repo, branch, catalogPath), StandardCharsets.UTF_8);
        }
        return parseCatalog(text, repo, branch, catalogPath);
    }

    private Map<String, Object> parseCatalog(String text, String repo, String branch, String catalogPath) {
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(text);
        } catch (Exception e) {
            throw BizException.internal("市场目录不是合法 JSON（" + catalogPath + "）：" + e.getMessage(), e);
        }
        JsonNode skins = root.isArray() ? root : root.get("skins");
        if (skins == null || !skins.isArray()) {
            throw BizException.badRequest("市场目录结构异常：既不是数组，也没有 skins 数组（" + catalogPath + "）");
        }
        Set<String> installed = installedIds();
        List<Map<String, Object>> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode s : skins) {
            if (out.size() >= MAX_SKINS) {
                log.warn("[skin-market] 条目数超过上限 {}，已截断", MAX_SKINS);
                break;
            }
            // 市场目录是**第三方数据**：单个条目形状异常只跳过它，绝不让整个市场读取失败。
            Map<String, Object> m;
            try {
                m = normalize(s);
            } catch (Exception e) {
                skipped++;
                log.warn("[skin-market] 跳过无法解析的条目（id={}）：{}", text(s, "id"), e.getMessage());
                continue;
            }
            if (m == null) {
                continue;
            }
            m.put("installed", installed.contains(String.valueOf(m.get("id"))));
            out.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("repo", repo);
        r.put("branch", branch);
        r.put("catalog", catalogPath);
        r.put("generatedAt", text(root, "generatedAt"));
        r.put("schemaVersion", root.isArray() ? null : intOrNull(root, "schemaVersion"));
        r.put("count", out.size());
        r.put("skipped", skipped);
        r.put("skins", out);
        r.put("channel", fetch.preferredChannel());
        log.info("[skin-market] 读取市场 {} -> {} 条（{}）", repo == null ? catalogPath : repo, out.size(), catalogPath);
        return r;
    }

    /** 把一个市场条目归一化成前端直接可用的字段集合。 */
    private Map<String, Object> normalize(JsonNode s) {
        String id = text(s, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        JsonNode name = s.get("name");
        String zh = null;
        String en = null;
        if (name != null && name.isObject()) {
            zh = text(name, "zh");
            en = text(name, "en");
        } else if (name != null && name.isValueNode()) {
            zh = name.asText();
        }
        m.put("nameZh", zh);
        m.put("nameEn", en);
        m.put("name", zh != null && !zh.isBlank() ? zh : (en == null ? id : en));
        m.put("author", text(s, "author"));
        m.put("description", text(s, "description"));
        m.put("repo", text(s, "repo"));
        m.put("packageName", text(s, "package"));
        m.put("rowId", text(s, "rowId"));
        m.put("category", text(s, "category"));
        m.put("tags", strList(s.get("tags")));
        m.put("modes", strList(s.get("modes")));
        m.put("screenshots", strList(s.get("screenshots")));
        m.put("listScreenshot", text(s, "listScreenshot"));
        m.put("license", obj(s.get("license")));
        m.put("review", obj(s.get("review")));
        m.put("compatibility", obj(s.get("compatibility")));
        JsonNode health = s.get("health");
        Map<String, Object> h = new LinkedHashMap<>();
        if (health != null && health.isObject()) {
            h.put("status", text(health, "status"));
            h.put("checks", obj(health.get("checks")));
            h.put("suggestions", strList(health.get("suggestions")));
        }
        m.put("health", h);
        if (s.get("featuredRank") != null && !s.get("featuredRank").isNull()) {
            m.put("featuredRank", s.get("featuredRank").asInt());
        }
        if (s.get("starsSnapshot") != null && !s.get("starsSnapshot").isNull()) {
            m.put("stars", s.get("starsSnapshot").asInt());
        }

        JsonNode install = s.get("install");
        Map<String, Object> ins = new LinkedHashMap<>();
        String target = install == null ? null : text(install, "target");
        ins.put("target", target);
        ins.put("version", install == null ? null : text(install, "version"));
        Target parsed = target == null ? null : parseTarget(target);
        ins.put("commit", install == null ? null : text(install, "commit"));
        ins.put("repo", parsed == null ? null : parsed.repo());
        ins.put("ref", parsed == null ? null : parsed.ref());
        ins.put("subPath", parsed == null ? null : parsed.subPath());
        if (install != null && install.get("desktop") != null) {
            ins.put("desktop", obj(install.get("desktop")));
        }
        m.put("install", ins);
        return m;
    }

    private static String pickCatalog(List<String> files, String subPath) {
        String root = stripSlashes(subPath == null ? "" : subPath);
        List<String> scoped = new ArrayList<>();
        for (String f : files) {
            if (root.isBlank() || f.startsWith(root + "/") || f.equals(root)) {
                scoped.add(f);
            }
        }
        if (scoped.isEmpty()) {
            scoped = files;
        }
        for (String cand : CATALOG_CANDIDATES) {
            for (String f : scoped) {
                if (f.equalsIgnoreCase(cand)) {
                    return f;
                }
            }
        }
        // 兜底：任意目录下的 catalog.json，取路径最短的那个
        return scoped.stream()
                .filter(f -> f.toLowerCase().endsWith("catalog.json"))
                .min(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    // ---------------- 2) 安装 / 卸载 ----------------

    /**
     * 按市场条目安装皮肤：用 {@code install.target} 指定的 commit 下载源码到
     * {@code <skins.dir>/<id>/}，并写入带署名信息的 {@code .skin-meta.json}。
     */
    public Map<String, Object> install(Map<String, Object> entry) {
        if (entry == null) {
            throw BizException.badRequest("缺少皮肤条目");
        }
        String id = safeId(String.valueOf(entry.get("id")));
        if (id.isBlank()) {
            throw BizException.badRequest("皮肤 id 非法");
        }
        Object targetRaw = entry.get("target");
        if (targetRaw == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ins = (Map<String, Object>) entry.get("install");
            targetRaw = ins == null ? null : ins.get("target");
        }
        if (targetRaw == null || String.valueOf(targetRaw).isBlank()) {
            throw BizException.badRequest("该条目没有 install.target，无法安装");
        }
        Target t = parseTarget(String.valueOf(targetRaw));
        if (t == null) {
            throw BizException.badRequest("无法解析安装目标：" + targetRaw);
        }
        String ref = t.ref() == null || t.ref().isBlank()
                ? fetch.resolveBranch(t.repo(), null)
                : t.ref();
        List<String> files = fetch.listFiles(t.repo(), ref, true);
        String sub = stripSlashes(t.subPath() == null ? "" : t.subPath());
        String prefix = sub.isBlank() ? "" : sub + "/";
        List<String> targets = new ArrayList<>();
        for (String f : files) {
            if (prefix.isBlank() || f.startsWith(prefix)) {
                targets.add(f);
            }
        }
        if (targets.isEmpty()) {
            throw BizException.notFound("skin files", t.repo() + "@" + ref + (sub.isBlank() ? "" : ":" + sub));
        }
        Path dir = skinsRoot.resolve(id).normalize();
        if (!dir.startsWith(skinsRoot)) {
            throw BizException.badRequest("皮肤 id 越界：" + id);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw BizException.internal("创建皮肤目录失败：" + e.getMessage(), e);
        }
        int written = 0;
        int failed = 0;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_THREADS, targets.size()), r -> {
            Thread th = new Thread(r, "skin-install");
            th.setDaemon(true);
            return th;
        });
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (String f : targets) {
                fs.add(pool.submit(() -> downloadOne(t.repo(), ref, prefix, f, dir)));
            }
            for (Future<Integer> f : fs) {
                try {
                    Integer n = f.get(120, TimeUnit.SECONDS);
                    if (n != null && n > 0) {
                        written++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.debug("[skin-market] 下载失败: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        writeMeta(dir, id, entry, t, ref, written);
        log.info("[skin-market] 已安装皮肤 {}（{} 个文件，{} 个失败）<- {}@{}", id, written, failed, t.repo(), ref);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("dir", dir.toString());
        r.put("files", written);
        r.put("failed", failed);
        r.put("repo", t.repo());
        r.put("ref", ref);
        r.put("subPath", sub);
        return r;
    }

    private int downloadOne(String repo, String ref, String prefix, String file, Path dir) {
        String rel = prefix.isBlank() ? file : file.substring(prefix.length());
        if (rel.isBlank()) {
            return 0;
        }
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(dir)) {
            log.warn("[skin-market] 跳过可疑路径：{}", file);
            return 0;
        }
        try {
            byte[] data = fetch.attempt("下载 " + file, c -> c.read(repo, ref, file));
            if (data == null) {
                return 0;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, data);
            return 1;
        } catch (Exception e) {
            log.debug("[skin-market] {} 下载失败：{}", file, e.getMessage());
            return 0;
        }
    }

    private void writeMeta(Path dir, String id, Map<String, Object> entry, Target t, String ref, int files) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("name", entry.get("name"));
        meta.put("nameZh", entry.get("nameZh"));
        meta.put("nameEn", entry.get("nameEn"));
        meta.put("author", entry.get("author"));
        meta.put("description", entry.get("description"));
        meta.put("repo", entry.get("repo"));
        meta.put("sourceRepo", t.repo());
        // ref 在"钉了 commit"的安装下就是那个 commit（见 install()），所以它同时充当版本锚点
        meta.put("ref", ref);
        meta.put("subPath", t.subPath());
        // 修正：commit 与 version 都在市场条目的 install 子对象里，顶层没有这两个字段。
        // 之前写成 entry.get("commit") → 恒为 null，"版本对比→提示更新"因此拿不到数据。
        @SuppressWarnings("unchecked")
        Map<String, Object> ins = entry.get("install") instanceof Map
                ? (Map<String, Object>) entry.get("install")
                : Map.of();
        String commit = ins.get("commit") != null ? String.valueOf(ins.get("commit")) : ref;
        meta.put("commit", commit);
        meta.put("version", ins.get("version"));
        meta.put("installTarget", ins.get("target"));
        meta.put("license", entry.get("license"));
        meta.put("listScreenshot", entry.get("listScreenshot"));
        meta.put("screenshots", entry.get("screenshots"));
        meta.put("modes", entry.get("modes"));
        meta.put("tags", entry.get("tags"));
        meta.put("files", files);
        meta.put("installedAt", Instant.now().toString());
        try {
            Files.writeString(dir.resolve(META_FILE), JsonUtils.toJson(meta));
        } catch (IOException e) {
            log.warn("[skin-market] 写入元信息失败：{}", e.getMessage());
        }
    }

    /**
     * 扫皮肤自己的 bundle，看它引用了哪些**配套插件**。
     *
     * <p>为什么只能扫：市场目录（catalog.json）的 {@code compatibility} 字段里只有
     * {@code dsh} 与 {@code platform}；皮肤自己的 {@code skin.json} 也没有 peer 声明。
     * 唯一可靠的线索就是 bundle 里有没有引用那个插件的作用域属性。</p>
     *
     * <p>只读最大的那个 JS（皮肤产物就是一个 bundle），不做缓存 ——
     * 1.5 MB 量级读一次是毫秒级，而加缓存要处理失效，不划算。</p>
     */
    private List<String> detectPartnerPlugins(Path dir) {
        try {
            Path js = largestJsFile(dir);
            if (js == null) {
                return List.of();
            }
            String text = Files.readString(js);
            return KNOWN_PARTNER_PLUGINS.entrySet().stream()
                    .filter(e -> text.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.debug("[skin-market] 扫配套插件失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 皮肤目录里最大的 JS 文件（即皮肤自己的 bundle）。 */
    private static Path largestJsFile(Path dir) throws IOException {
        try (var walk = Files.walk(dir, 4)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".js"))
                    .max(Comparator.comparingLong((Path p) -> p.toFile().length()))
                    .orElse(null);
        }
    }

    /** 已安装的皮肤（读各目录里的 .skin-meta.json）。 */
    public List<Map<String, Object>> installed() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(skinsRoot)) {
            return out;
        }
        try (var stream = Files.list(skinsRoot)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                Map<String, Object> m = readMeta(d);
                if (m != null) {
                    // 按需派生（不落盘）：bundle 换了它就该跟着变
                    m.put("partnerPlugins", detectPartnerPlugins(d));
                    out.add(m);
                }
            });
        } catch (IOException e) {
            log.warn("[skin-market] 列已安装皮肤失败：{}", e.getMessage());
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("installedAt"))));
        return out;
    }

    public Map<String, Object> uninstall(String id) {
        String safe = safeId(id);
        if (safe.isBlank()) {
            throw BizException.badRequest("皮肤 id 非法");
        }
        Path dir = skinsRoot.resolve(safe).normalize();
        if (!dir.startsWith(skinsRoot) || !Files.isDirectory(dir)) {
            throw BizException.notFound("installed skin", safe);
        }
        deleteRecursively(dir);
        log.info("[skin-market] 已卸载皮肤 {}", safe);
        return Map.of("id", safe, "removed", true);
    }

    // ---------------- 3) 主题抽取 ----------------

    public Map<String, Object> clientBundle(String id) {
        String safe = safeId(id);
        Path dir = skinsRoot.resolve(safe).normalize();
        if (!dir.startsWith(skinsRoot) || !Files.isDirectory(dir)) {
            throw BizException.notFound("installed skin", safe);
        }
        String rel = ClientBundleLocator.locate(dir);
        if (rel == null) {
            throw BizException.notFound("client bundle (package.json exports['./client'])", safe);
        }
        Path file = dir.resolve(rel).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw BizException.notFound("client bundle file", rel);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BUNDLE) {
                throw BizException.badRequest("皮肤 bundle 过大（" + size + " B > " + MAX_BUNDLE + " B）：" + rel);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", safe);
            m.put("path", rel);
            m.put("size", size);
            m.put("text", Files.readString(file, StandardCharsets.UTF_8));
            log.info("[skin-market] 皮肤 {} 的客户端 bundle = {}（{} B）", safe, rel, size);
            return m;
        } catch (IOException e) {
            throw BizException.internal("读取皮肤 bundle 失败：" + e.getMessage(), e);
        }
    }

    // ---------------- 4) 皮肤自带的图片资产（背景图/壁纸） ----------------

    private static final List<String> PROXY_ALLOWED_HOSTS = List.of(
            "raw.githubusercontent.com",
            "github.com",
            "cdn.jsdelivr.net",
            "camo.githubusercontent.com",
            "user-images.githubusercontent.com");

    /**
     * 代理远端图片（皮肤封面/截图）。
     *
     * <p>市场里的图片几乎都是 {@code raw.githubusercontent.com} 的外链，国内直连大概率显示不出来。
     * 这里借 {@link RemoteFetchService} 的通道改写能力（jsDelivr 改写 / 加速代理）把图取回来，
     * 前端就能正常显示了。</p>
     *
     * <p><b>只允许白名单域名</b>，避免这个接口被当成任意地址的 SSRF 跳板。</p>
     */
    public byte[] proxyImage(String url) {
        if (url == null || url.isBlank()) {
            throw BizException.badRequest("图片地址不能为空");
        }
        String u = url.trim();
        if (!u.startsWith("https://")) {
            throw BizException.badRequest("只支持 https 图片地址");
        }
        String host;
        try {
            host = java.net.URI.create(u).getHost();
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("图片地址非法：" + u);
        }
        if (host == null) {
            throw BizException.badRequest("图片地址非法：" + u);
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        boolean allowed = PROXY_ALLOWED_HOSTS.stream().anyMatch(a -> h.equals(a) || h.endsWith("." + a));
        if (!allowed) {
            throw BizException.badRequest("该域名不在图片代理白名单内：" + h);
        }
        return fetch.getBytesByUrl(u);
    }

    // ---------------- 小工具 ----------------

    /** {@code github:owner/repo#commit&path:/sub} → 结构化目标。 */
    record Target(String repo, String ref, String subPath) {
    }

    static Target parseTarget(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isBlank()) {
            return null;
        }
        if (t.startsWith("github:")) {
            t = t.substring("github:".length());
        }
        String sub = "";
        int amp = t.indexOf('&');
        if (amp > 0) {
            String rest = t.substring(amp + 1);
            t = t.substring(0, amp);
            for (String kv : rest.split("&")) {
                String k = kv.trim();
                if (k.startsWith("path:")) {
                    sub = stripSlashes(k.substring("path:".length()));
                }
            }
        }
        String ref = "";
        int hash = t.indexOf('#');
        if (hash > 0) {
            ref = t.substring(hash + 1).trim();
            t = t.substring(0, hash);
        }
        t = stripSlashes(t.trim());
        // 允许直接给完整仓库地址
        if (t.startsWith("http")) {
            try {
                GitRepoRef r = GitRepoRef.parse(t);
                if (ref.isBlank()) {
                    ref = r.branch() == null ? "" : r.branch();
                }
                if (sub.isBlank()) {
                    sub = r.subPath() == null ? "" : r.subPath();
                }
                return new Target(r.fullName(), ref, sub);
            } catch (Exception e) {
                return null;
            }
        }
        if (t.split("/").length < 2) {
            return null;
        }
        return new Target(t, ref, sub);
    }

    private Map<String, Object> readMeta(Path dir) {
        Path meta = dir.resolve(META_FILE);
        if (!Files.exists(meta)) {
            return null;
        }
        try {
            Map<String, Object> m = JsonUtils.fromJson(Files.readString(meta), Map.class);
            m.put("dir", dir.toString());
            return m;
        } catch (Exception e) {
            log.debug("[skin-market] 元信息解析失败 {}：{}", dir, e.getMessage());
            return null;
        }
    }

    private Set<String> installedIds() {
        Set<String> ids = new TreeSet<>();
        if (!Files.isDirectory(skinsRoot)) {
            return ids;
        }
        try (var stream = Files.list(skinsRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve(META_FILE)))
                    .forEach(d -> ids.add(d.getFileName().toString()));
        } catch (IOException e) {
            log.debug("[skin-market] 列已安装皮肤失败：{}", e.getMessage());
        }
        return ids;
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        } catch (IOException e) {
            log.warn("[skin-market] 删除目录失败 {}：{}", dir, e.getMessage());
        }
    }

    private static String safeId(String id) {
        return id == null ? "" : id.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String stripSlashes(String s) {
        String r = s == null ? "" : s.trim();
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        // 只要不是标量就当没有：第三方目录里同名字段可能是对象/数组，
        // 直接 asText() 会抛 JsonNodeException 把整个读取打断。
        if (v == null || v.isNull() || v.isObject() || v.isArray()) {
            return null;
        }
        try {
            return v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        try {
            return v.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            if (n == null || n.isNull() || n.isObject() || n.isArray()) {
                continue;
            }
            try {
                out.add(n.asText());
            } catch (Exception ignored) {
                // 单个元素异常就丢这一个
            }
        }
        return out;
    }

    /** 把任意 JSON 节点转成普通 Java 值（对象递归、数组转列表、标量转字符串），全程不抛。 */
    private static Object value(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isArray()) {
            return strList(v);
        }
        if (v.isObject()) {
            return obj(v);
        }
        try {
            return v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> obj(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> m.put(e.getKey(), value(e.getValue())));
        }
        return m;
    }
}
