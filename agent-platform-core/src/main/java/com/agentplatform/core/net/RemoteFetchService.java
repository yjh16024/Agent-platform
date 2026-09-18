package com.agentplatform.core.net;

import com.agentplatform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 远端取件的**统一入口**：多通道 + 自动失败切换 + 记住可用通道 + 短缓存。
 *
 * <p>国内访问 GitHub 的可用路径因网络而异，单点写死（早期直接打 {@code api.github.com}）在受限网络下
 * 会静默变空。这里按配置的优先级依次尝试 <b>GitHub 加速代理 → jsDelivr → 直连</b>，
 * 谁通用谁，并记住最近成功的通道；全部失败时把**每一条通道的原因**带回去，
 * 便于前端直接告诉用户"到底卡在哪"（配套 {@link #diagnose()}）。</p>
 *
 * <p>顺序为什么"代理在前"：实测 jsDelivr 的分支目录树**存在滞后与缺漏**
 * （官方仓库 20 个技能只列出 17 个、419 个文件只列出 282 个），而 GitHub Trees API 完整且
 * {@code truncated=false}。所以能用代理就用代理拿准确目录，代理全不通时再退回 jsDelivr 保可用。</p>
 *
 * <p>两类调用方：技能市场（{@link #listFiles}/{@link #read}，按仓库+ref 取）与
 * 皮肤市场（{@link #getBytesByUrl}/{@link #getTextByUrl}，只有一个地址时取）。</p>
 */
@Slf4j
@Service
public class RemoteFetchService {

    public static final String DEFAULT_BRANCH = "main";
    /** 文件树的缓存时长：短缓存既能加速重复浏览，又不会让新装的资源看不见。 */
    private static final long TREE_TTL_MS = 120_000L;

    private final List<FetchChannel> channels;
    private final Map<String, CachedTree> treeCache = new ConcurrentHashMap<>();
    /** 最近一次成功的通道 id —— 下次优先走它，避免每条请求都从第一条通道开始试错。 */
    private volatile String preferredChannel;

    private record CachedTree(List<String> files, long at) {
    }

    /** 一次取件动作（在某个通道上执行）。 */
    public interface Attempt<T> {
        T run(FetchChannel channel) throws IOException;
    }

    public RemoteFetchService(
            @Value("${agent-platform.skills.market.sources:ghproxy,jsdelivr,github}") String sources,
            @Value("${agent-platform.skills.market.jsdelivr-api:data.jsdelivr.com}") String jsdelivrApi,
            @Value("${agent-platform.skills.market.jsdelivr-cdns:cdn.jsdelivr.net,gcore.jsdelivr.net,fastly.jsdelivr.net}")
            String jsdelivrCdns,
            @Value("${agent-platform.skills.market.gh-proxies:gh-proxy.com,ghfast.top,ghproxy.net}") String ghProxies,
            @Value("${agent-platform.skills.market.timeout-seconds:25}") int timeoutSeconds) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .followRedirects(true)
                .build();
        this.channels = buildChannels(http, split(sources), split(jsdelivrApi), split(jsdelivrCdns), split(ghProxies));
        log.info("[fetch] 取件通道（按优先级）：{}", channels.stream().map(FetchChannel::id).toList());
    }

    // ---------------- 仓库路线 ----------------

    /** 列出仓库全部文件（仓库相对路径），带短缓存与通道失败切换。 */
    public List<String> listFiles(String repo, String branch, boolean refresh) {
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

    /** 读取仓库内文件；不存在时抛 404。 */
    public byte[] read(String repo, String branch, String path) {
        byte[] data = attempt("读取 " + path, c -> c.read(repo, branch, path));
        if (data == null) {
            throw BizException.notFound("file", path);
        }
        return data;
    }

    /** 解析分支：给了就用；否则问各通道要默认分支，最后实测 main / master。 */
    public String resolveBranch(String repo, String branchHint) {
        if (branchHint != null && !branchHint.isBlank()) {
            return branchHint.trim();
        }
        try {
            String b = attempt("读取默认分支 " + repo, c -> c.defaultBranch(repo));
            if (b != null && !b.isBlank()) {
                return b.trim();
            }
        } catch (Exception e) {
            log.debug("[fetch] 默认分支获取失败，改为实测 main/master: {}", e.getMessage());
        }
        for (String b : List.of("main", "master")) {
            try {
                if (!listFiles(repo, b, false).isEmpty()) {
                    return b;
                }
            } catch (Exception e) {
                log.debug("[fetch] 分支 {} 不可用: {}", b, e.getMessage());
            }
        }
        return DEFAULT_BRANCH;
    }

    // ---------------- URL 路线 ----------------

    /** 按 URL 取字节（自动尝试各通道的地址改写：代理加前缀、jsDelivr 改写 raw 地址、直连原样）。 */
    public byte[] getBytesByUrl(String url) {
        byte[] data = attempt("读取 " + url, c -> c.readUrl(url));
        if (data == null) {
            throw BizException.notFound("remote url", url);
        }
        return data;
    }

    /** 按 URL 取文本（UTF-8）。 */
    public String getTextByUrl(String url) {
        return new String(getBytesByUrl(url), StandardCharsets.UTF_8);
    }

    // ---------------- 诊断 ----------------

    /** 通道清单与当前生效的通道（前端展示/诊断用）。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> cs = new ArrayList<>();
        for (FetchChannel c : channels) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", c.id());
            item.put("group", c.group());
            item.put("label", c.label());
            cs.add(item);
        }
        m.put("channels", cs);
        m.put("preferred", preferredChannel);
        return m;
    }

    /** 当前生效的通道 id（可能为 null）。 */
    public String preferredChannel() {
        return preferredChannel;
    }

    /**
     * 网络诊断：并行探测每条通道，返回是否可用与耗时。
     * <p>国内网络下"市场一片空白"最常见的原因就是所有通道都不通，这个接口让原因可见。</p>
     */
    public List<Map<String, Object>> diagnose() {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, channels.size())), r -> {
            Thread t = new Thread(r, "fetch-channel-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Map<String, Object>>> fs = new ArrayList<>();
            for (FetchChannel c : channels) {
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
                    log.debug("[fetch] 探测任务异常: {}", e.getMessage());
                }
            }
            out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 失败切换核心 ----------------

    /**
     * 遍历通道执行一次取件；成功即返回并记住该通道。
     * <p>全部失败时：只要**有一条通道明确回答"不存在"**（HTTP 404），就按不存在处理——
     * 其余通道的失败可能只是限流(403)/网络不通，不足以推翻一个确定答案；
     * 否则抛 500 并附上每条通道的原因。</p>
     */
    public <T> T attempt(String op, Attempt<T> fn) {
        List<String> errors = new ArrayList<>();
        boolean sawMiss = false;
        for (FetchChannel c : orderedChannels()) {
            try {
                T r = fn.run(c);
                if (r != null) {
                    preferredChannel = c.id();
                    return r;
                }
            } catch (FetchChannel.Miss e) {
                sawMiss = true;
                errors.add(c.id() + "：" + e.getMessage());
            } catch (Exception e) {
                errors.add(c.id() + "：" + e.getMessage());
                log.debug("[fetch] {} 经 {} 失败: {}", op, c.id(), e.getMessage());
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

    // ---------------- 内部 ----------------

    /** 按配置里的**分组顺序**构造通道（sources 决定优先级，不只是开关）。 */
    private static List<FetchChannel> buildChannels(OkHttpClient http, List<String> sources,
                                                   List<String> jsdelivrApi, List<String> jsdelivrCdns,
                                                   List<String> ghProxies) {
        List<String> order = sources.isEmpty() ? List.of("ghproxy", "jsdelivr", "github") : sources;
        List<FetchChannel> out = new ArrayList<>();
        for (String group : order) {
            String g = group.trim().toLowerCase(Locale.ROOT);
            switch (g) {
                case "jsdelivr" -> {
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
                default -> log.warn("[fetch] 未知的取件通道分组，已忽略: {}", group);
            }
        }
        return List.copyOf(out);
    }

    /** 把最近成功的通道提到最前面。 */
    private List<FetchChannel> orderedChannels() {
        String p = preferredChannel;
        if (p == null) {
            return channels;
        }
        List<FetchChannel> out = new ArrayList<>(channels.size());
        for (FetchChannel c : channels) {
            if (p.equals(c.id())) {
                out.add(c);
            }
        }
        for (FetchChannel c : channels) {
            if (!p.equals(c.id())) {
                out.add(c);
            }
        }
        return out;
    }

    private Map<String, Object> probe(FetchChannel c) {
        long t0 = System.nanoTime();
        boolean ok;
        String err = "";
        try {
            ok = c.available();
        } catch (Exception e) {
            ok = false;
            err = String.valueOf(e.getMessage());
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
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
