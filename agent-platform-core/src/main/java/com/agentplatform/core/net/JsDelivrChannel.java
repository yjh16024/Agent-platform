package com.agentplatform.core.net;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * jsDelivr 通道：**列文件**用 {@code data.jsdelivr.com} 的 flat 树接口（一次请求拿到整仓库文件清单），
 * **读文件**用 {@code cdn.jsdelivr.net} 系列 CDN 主机。
 *
 * <p>这是国内网络下最稳的一条路：jsDelivr 在国内有节点，且不需要任何代理前缀。
 * 它还额外承担一个重要职责——**把 {@code raw.githubusercontent.com} 的地址改写成 CDN 地址**，
 * 因为 raw 在国内经常不通，而同一个 commit/分支在 jsDelivr 上通常能取到。</p>
 *
 * <p>三个已知缺点：拿不到默认分支（需上层实测 main/master）；只支持公开仓库；
 * 且**分支目录存在缓存滞后**（实测官方仓库 20 个技能只列出 17 个、419 个文件只列出 282 个）。
 * 所以它适合做"代理全不通时的兜底"，不适合当首选。</p>
 */
@Slf4j
public class JsDelivrChannel extends FetchChannel {

    /** 整仓库文件树的上限（超大仓库直接放弃，避免拉爆内存）。 */
    private static final long MAX_TREE = 16L * 1024 * 1024;
    /** 单个文件读取上限。 */
    private static final long MAX_FILE = 8L * 1024 * 1024;
    private static final String RAW_PREFIX = "https://raw.githubusercontent.com/";

    private final List<String> apiHosts;
    private final List<String> cdnHosts;
    /** 记住最近可用的 CDN 主机，后续少走弯路。 */
    private volatile String goodCdn;

    public JsDelivrChannel(OkHttpClient http, List<String> apiHosts, List<String> cdnHosts) {
        super(http);
        this.apiHosts = List.copyOf(apiHosts);
        this.cdnHosts = List.copyOf(cdnHosts);
    }

    @Override
    public String group() {
        return "jsdelivr";
    }

    @Override
    public String id() {
        return "jsdelivr";
    }

    @Override
    public String label() {
        return "jsDelivr CDN（免代理，国内一般可达；目录可能滞后）";
    }

    @Override
    public String defaultBranch(String repo) {
        // jsDelivr 的 gh 接口不返回默认分支，交给别的通道或上层实测
        return null;
    }

    @Override
    public boolean available() {
        for (String host : orderedCdn()) {
            try {
                // 逐主机探测并带短超时：诊断时不能因为某个 CDN 主机挂着而拖几十秒
                fetch("https://" + host + "/gh/anthropics/skills@main/README.md",
                        200_000, PROBE_TIMEOUT_SECONDS);
                goodCdn = host;
                return true;
            } catch (IOException e) {
                log.debug("[channel] jsdelivr 探测 {} 失败: {}", host, e.getMessage());
            }
        }
        return false;
    }

    @Override
    public List<String> listFiles(String repo, String branch) throws IOException {
        IOException last = null;
        for (String host : apiHosts) {
            String url = "https://" + host + "/v1/packages/gh/" + repo + "@" + branch + "?structure=flat";
            try {
                return parseFlat(fetchText(url, MAX_TREE), url);
            } catch (Miss e) {
                // 仓库或分支不存在——换主机也没用
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("没有可用的 jsDelivr 数据接口主机") : last;
    }

    @Override
    public byte[] read(String repo, String branch, String path) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        IOException last = null;
        for (String host : orderedCdn()) {
            String url = "https://" + host + "/gh/" + repo + "@" + branch + "/" + encodePath(clean);
            try {
                byte[] data = fetch(url, MAX_FILE);
                goodCdn = host;
                return data;
            } catch (Miss e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("没有可用的 jsDelivr CDN 主机") : last;
    }

    /**
     * 把 {@code raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>} 改写成 jsDelivr CDN 地址。
     * <p>{@code <ref>} 可以是分支名也可以是 commit SHA（皮肤安装要用后者钉版本）。</p>
     */
    @Override
    protected String rewrite(String url) {
        if (!url.startsWith(RAW_PREFIX)) {
            return null;
        }
        String rest = url.substring(RAW_PREFIX.length());
        int s1 = rest.indexOf('/');
        if (s1 <= 0) {
            return null;
        }
        int s2 = rest.indexOf('/', s1 + 1);
        if (s2 <= 0) {
            return null;
        }
        int s3 = rest.indexOf('/', s2 + 1);
        if (s3 <= 0) {
            return null;
        }
        String repo = rest.substring(0, s2);
        String ref = rest.substring(s2 + 1, s3);
        String path = rest.substring(s3 + 1);
        if (path.isBlank()) {
            return null;
        }
        String host = goodCdn == null ? cdnHosts.get(0) : goodCdn;
        return "https://" + host + "/gh/" + repo + "@" + ref + "/" + path;
    }

    private List<String> parseFlat(String body, String url) throws IOException {
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(body);
        } catch (Exception e) {
            throw new IOException("jsDelivr 返回内容无法解析：" + url, e);
        }
        JsonNode files = root.get("files");
        if (files == null || !files.isArray()) {
            // jsDelivr 找不到版本时返回 {"status":404,...}，但 HTTP 码也可能是 200
            JsonNode status = root.get("status");
            if (status != null && !status.isNull() && status.asInt(0) >= 400) {
                throw new Miss(url + " -> " + root.get("message"));
            }
            throw new IOException("jsDelivr 返回结构异常（缺少 files 数组）：" + url);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode f : files) {
            JsonNode n = f.get("name");
            if (n == null || n.isNull()) {
                continue;
            }
            String p = n.asText();
            if (p == null || p.isBlank()) {
                continue;
            }
            out.add(p.startsWith("/") ? p.substring(1) : p);
        }
        return out;
    }

    private List<String> orderedCdn() {
        String g = goodCdn;
        if (g == null || !cdnHosts.contains(g)) {
            return cdnHosts;
        }
        List<String> out = new ArrayList<>(cdnHosts.size());
        out.add(g);
        for (String h : cdnHosts) {
            if (!h.equals(g)) {
                out.add(h);
            }
        }
        return out;
    }
}
