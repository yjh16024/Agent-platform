package com.agentplatform.core.skill.market;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.skill.SkillFileStore;
import com.agentplatform.core.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
 * 技能市场：直连 **Anthropic 官方 Agent Skills 仓库**（`github.com/anthropics/skills`），
 * 列出技能并一键安装到本地 skills 目录，安装后自动同步入库。
 *
 * <p>为什么可以直连：该仓库采用 **Agent Skills 开放标准**（每个技能一个文件夹 + `SKILL.md` +
 * 可选 `scripts/ references/ assets/`），与本平台 {@link SkillFileStore} 的目录约定**完全一致**，
 * 因此下载下来即可被现有扫描同步逻辑识别，无需任何格式转换。</p>
 *
 * <p>取文件的策略：**GitHub Contents API 列目录结构**（拿路径清单）+ **jsDelivr CDN 拉文件内容**
 * （`raw.githubusercontent.com` 在国内常不可达，CDN 更稳）。安装失败只影响该技能，不污染已有技能。</p>
 */
@Slf4j
@Service
public class SkillMarketService {

    private static final String REPO = "anthropics/skills";
    private static final String BRANCH = "main";
    private static final String API = "https://api.github.com/repos/" + REPO + "/contents";
    private static final String CDN = "https://cdn.jsdelivr.net/gh/" + REPO + "@" + BRANCH;
    /** 仓库中存放技能的目录。 */
    private static final String SKILLS_PATH = "skills";
    /** 单个技能的递归深度与文件数上限（防意外拉爆）。 */
    private static final int MAX_DEPTH = 5;
    private static final int MAX_FILES = 300;
    private static final String SKILL_FILE = "SKILL.md";
    /** 解析 SKILL.md frontmatter（支持 YAML 多行折叠/字面量语法）。 */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final SkillFileStore fileStore;
    private final SkillService skillService;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(25))
            .build();

    public SkillMarketService(SkillFileStore fileStore, SkillService skillService) {
        this.fileStore = fileStore;
        this.skillService = skillService;
    }

    /** 市场技能列表（含是否已安装到本地 skills 目录）。 */
    public List<Map<String, Object>> list() {
        List<String> names = listSkillNames();
        Set<String> installed = localSkillDirs();
        List<Map<String, Object>> out = new ArrayList<>();
        // 虚拟线程并发拉取各技能的 SKILL.md 摘要（单个失败不影响整体）
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (String n : names) {
                fs.add(pool.submit(() -> describe(n, installed.contains(n))));
            }
            for (Future<Map<String, Object>> f : fs) {
                try {
                    Map<String, Object> m = f.get(30, TimeUnit.SECONDS);
                    if (m != null) {
                        out.add(m);
                    }
                } catch (Exception e) {
                    log.debug("[skill-market] describe failed: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    /**
     * 安装技能：递归下载该技能的全部文件到本地 skills 目录，然后触发一次同步入库。
     *
     * @return {skill, dir, files, sync}
     */
    public Map<String, Object> install(String tenantId, String name) {
        String safe = sanitizeName(name);
        if (safe.isBlank()) {
            throw BizException.badRequest("技能名非法");
        }
        String remotePath = SKILLS_PATH + "/" + safe;
        List<String> paths = new ArrayList<>();
        collectFiles(remotePath, 0, paths);
        if (paths.isEmpty()) {
            throw BizException.notFound("skill in market", safe);
        }
        Path dir = fileStore.root().resolve(safe).normalize();
        if (!dir.startsWith(fileStore.root())) {
            throw BizException.badRequest("技能名越界: " + name);
        }
        int written = 0;
        for (String p : paths) {
            String rel = p.substring(remotePath.length());
            rel = rel.startsWith("/") ? rel.substring(1) : rel;
            if (rel.isBlank()) {
                continue;
            }
            Path target = dir.resolve(rel).normalize();
            if (!target.startsWith(dir)) {
                log.warn("[skill-market] skip suspicious path: {}", p);
                continue;
            }
            byte[] data = download(p);
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
                throw BizException.internal("写入技能文件失败 " + target + ": " + e.getMessage(), e);
            }
        }
        log.info("[skill-market] installed skill {} ({} files) -> {}", safe, written, dir);
        Map<String, Object> sync = skillService.syncFromFolder(tenantId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("skill", safe);
        r.put("dir", dir.toString());
        r.put("files", written);
        r.put("sync", sync);
        return r;
    }

    // ---------------- 内部实现 ----------------

    private List<String> listSkillNames() {
        List<String> names = new ArrayList<>();
        for (JsonNode e : apiList(SKILLS_PATH)) {
            if ("dir".equals(text(e, "type"))) {
                String n = text(e, "name");
                if (n != null && !n.isBlank()) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    /** 递归收集某个远端目录下的全部**文件**路径。 */
    private void collectFiles(String path, int depth, List<String> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) {
            return;
        }
        for (JsonNode e : apiList(path)) {
            String type = text(e, "type");
            String p = text(e, "path");
            if (p == null || p.isBlank()) {
                continue;
            }
            if ("dir".equals(type)) {
                collectFiles(p, depth + 1, out);
            } else if ("file".equals(type)) {
                out.add(p);
                if (out.size() >= MAX_FILES) {
                    return;
                }
            }
        }
    }

    private Map<String, Object> describe(String name, boolean installed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("installed", installed);
        m.put("source", REPO);
        m.put("dir", name);
        try {
            String md = fetchText(CDN + "/" + SKILLS_PATH + "/" + name + "/" + SKILL_FILE);
            m.put("description", frontmatter(md, "description"));
            String title = frontmatter(md, "name");
            m.put("title", title == null || title.isBlank() ? name : title);
        } catch (Exception e) {
            m.put("description", "");
            m.put("title", name);
        }
        return m;
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
            log.debug("[skill-market] list local skills failed: {}", e.getMessage());
        }
        return dirs;
    }

    /** 调 GitHub Contents API 列目录；失败返回空列表（市场页展示为空而不是报错）。 */
    private List<JsonNode> apiList(String path) {
        List<JsonNode> out = new ArrayList<>();
        String body = fetchText(API + "/" + path);
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JsonUtils.toJsonNode(body);
            if (root.isArray()) {
                root.forEach(out::add);
            }
        } catch (Exception e) {
            log.warn("[skill-market] parse API response failed for {}: {}", path, e.getMessage());
        }
        return out;
    }

    private byte[] download(String path) {
        Request req = new Request.Builder()
                .url(CDN + "/" + path)
                .header("User-Agent", "agent-platform/skill-market")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[skill-market] download {} failed: HTTP {}", path, resp.code());
                return null;
            }
            return resp.body().bytes();
        } catch (IOException e) {
            log.warn("[skill-market] download {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    private String fetchText(String url) {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "agent-platform/skill-market")
                .header("Accept", "application/vnd.github+json")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            return new String(resp.body().bytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("[skill-market] fetch {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 取 SKILL.md frontmatter（首个 `---` 块）里的某个键的值。
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
            log.debug("[skill-market] parse frontmatter failed: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 技能目录名只允许字母/数字/._-，杜绝 `../` 之类的越界。 */
    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }
}
