package com.agentplatform.core.net;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 走 GitHub 官方 API 的通道，可以套一层加速前缀。
 *
 * <p>{@code prefix} 为空即**直连** {@code api.github.com}；否则形如 {@code https://gh-proxy.com/}，
 * 会把整个目标地址拼在后面（实测 gh-proxy / ghfast.top / ghproxy.net 均能代理
 * {@code api.github.com} 与 {@code raw.githubusercontent.com}）。</p>
 *
 * <p>列文件用 <b>Git Trees API</b>（{@code /git/trees/{ref}?recursive=1}）——一次请求返回整仓库路径树，
 * 比逐层调 Contents API 少很多次往返；{@code ref} 既可以是分支名也可以是 commit SHA（皮肤安装要用后者钉版本）。</p>
 *
 * <p>按 URL 取件时：带前缀的通道**只能代理 GitHub 域名**（否则会拿回一个 HTML 错误页当成功），
 * 其余地址一律返回 {@code null} 交给别的通道；不带前缀的直连通道对任何地址都直接取。</p>
 */
@Slf4j
public class GithubApiChannel extends FetchChannel {

    private static final long MAX_TREE = 16L * 1024 * 1024;
    private static final long MAX_REPO_INFO = 512L * 1024;
    private static final long MAX_FILE = 8L * 1024 * 1024;

    private final String prefix;
    private final String id;
    private final String label;
    private final String group;

    public GithubApiChannel(OkHttpClient http, String prefix, String id, String label, String group) {
        super(http);
        this.prefix = prefix == null ? "" : prefix;
        this.id = id;
        this.label = label;
        this.group = group;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean available() {
        try {
            // 最小的官方接口，用来确认"这个前缀能不能把 api.github.com 代理通"；短超时避免诊断卡住
            fetch(prefix + "https://api.github.com/rate_limit", 64L * 1024, PROBE_TIMEOUT_SECONDS);
            return true;
        } catch (IOException e) {
            log.debug("[channel] {} 探测失败: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public String defaultBranch(String repo) throws IOException {
        String body = fetchText(prefix + "https://api.github.com/repos/" + repo, MAX_REPO_INFO);
        try {
            JsonNode n = JsonUtils.toJsonNode(body).get("default_branch");
            return n == null || n.isNull() ? null : n.asText();
        } catch (Exception e) {
            throw new IOException("GitHub 仓库信息无法解析：" + repo, e);
        }
    }

    @Override
    public List<String> listFiles(String repo, String branch) throws IOException {
        String url = prefix + "https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1";
        String body = fetchText(url, MAX_TREE);
        JsonNode root;
        try {
            root = JsonUtils.toJsonNode(body);
        } catch (Exception e) {
            throw new IOException("GitHub 目录树无法解析：" + repo + "@" + branch, e);
        }
        JsonNode tree = root.get("tree");
        if (tree == null || !tree.isArray()) {
            throw new IOException("GitHub 目录树结构异常（缺少 tree 数组）：" + repo + "@" + branch);
        }
        JsonNode truncated = root.get("truncated");
        if (truncated != null && truncated.asBoolean(false)) {
            log.warn("[channel] 仓库过大，GitHub 目录树被截断，可能漏掉部分文件: {}@{}", repo, branch);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : tree) {
            JsonNode t = e.get("type");
            if (t == null || !"blob".equals(t.asText())) {
                continue;
            }
            JsonNode p = e.get("path");
            if (p == null || p.isNull()) {
                continue;
            }
            String s = p.asText();
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public byte[] read(String repo, String branch, String path) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        String url = prefix + "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + encodePath(clean);
        return fetch(url, MAX_FILE);
    }

    @Override
    protected String rewrite(String url) {
        if (prefix.isEmpty()) {
            // 直连通道：任何地址都能直接取（GitHub Pages、raw、乃至任意 JSON 地址）
            return url;
        }
        // 代理通道只代理 GitHub 域名：否则会拿回一个 HTML 错误页，被当成 200 成功
        return isGithubUrl(url) ? prefix + url : null;
    }
}
