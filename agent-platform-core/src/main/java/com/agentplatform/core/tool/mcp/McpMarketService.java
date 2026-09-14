package com.agentplatform.core.tool.mcp;

import com.agentplatform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 市场：直连**官方 MCP Registry**（`registry.modelcontextprotocol.io`），
 * 列出公开可用的 MCP 服务器，供一键注册为平台工具。
 *
 * <p>为什么只挑「远程 HTTP」的服务器：本平台通过 {@link HttpMcpClient} 以 **Streamable HTTP**
 * 接入 MCP，而注册中心里大量服务器是 **stdio**（靠 npx / uvx 本地拉起进程）的 —— 那些无法在
 * 平台侧直接运行，因此这里只保留 `remotes[].type == streamable-http` 且带 `url` 的条目。</p>
 *
 * <p>注册动作复用现有的 {@code POST /api/v1/tools/mcp}（server_url 必填，api_key / headers 可选），
 * 本服务只负责「发现」。</p>
 */
@Slf4j
@Service
public class McpMarketService {

    private static final String REGISTRY = "https://registry.modelcontextprotocol.io/v0/servers";
    private static final String STREAMABLE_HTTP = "streamable-http";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(25))
            .build();

    /**
     * 列出支持远程 HTTP 的 MCP 服务器（按名称去重）。
     *
     * @param query 关键词（可选，透传给 registry 的 search）
     * @param limit 返回条数上限（默认 50，最大 100）
     */
    public List<Map<String, Object>> list(String query, Integer limit) {
        int n = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        StringBuilder url = new StringBuilder(REGISTRY).append("?limit=").append(n);
        if (query != null && !query.isBlank()) {
            url.append("&search=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        String body = fetch(url.toString());
        if (body == null || body.isBlank()) {
            log.warn("[mcp-market] registry unreachable: {}", url);
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            JsonNode root = JsonUtils.toJsonNode(body);
            JsonNode servers = root.get("servers");
            if (servers == null || !servers.isArray()) {
                return out;
            }
            for (JsonNode item : servers) {
                JsonNode s = item.get("server");
                if (s == null) {
                    continue;
                }
                String name = text(s, "name");
                if (name == null || name.isBlank() || !seen.add(name)) {
                    continue;   // 同名多版本只保留首个
                }
                String remoteUrl = firstHttpRemote(s);
                if (remoteUrl == null) {
                    continue;   // 只收远程 HTTP 型
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("title", orDefault(text(s, "title"), name));
                m.put("description", orDefault(text(s, "description"), ""));
                m.put("version", orDefault(text(s, "version"), ""));
                m.put("server_url", remoteUrl);
                m.put("remotes", allHttpRemotes(s));
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[mcp-market] parse registry response failed: {}", e.getMessage());
        }
        log.info("[mcp-market] listed {} remote-http MCP servers (query={})", out.size(), query);
        return out;
    }

    // ---------------- 内部 ----------------

    private static String firstHttpRemote(JsonNode server) {
        JsonNode remotes = server.get("remotes");
        if (remotes == null || !remotes.isArray()) {
            return null;
        }
        for (JsonNode r : remotes) {
            if (STREAMABLE_HTTP.equals(text(r, "type"))) {
                String u = text(r, "url");
                if (u != null && !u.isBlank()) {
                    return u;
                }
            }
        }
        return null;
    }

    private static List<String> allHttpRemotes(JsonNode server) {
        List<String> urls = new ArrayList<>();
        JsonNode remotes = server.get("remotes");
        if (remotes != null && remotes.isArray()) {
            for (JsonNode r : remotes) {
                if (STREAMABLE_HTTP.equals(text(r, "type"))) {
                    String u = text(r, "url");
                    if (u != null && !u.isBlank()) {
                        urls.add(u);
                    }
                }
            }
        }
        return urls;
    }

    private String fetch(String url) {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "agent-platform/mcp-market")
                .header("Accept", "application/json")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[mcp-market] HTTP {} for {}", resp.code(), url);
                return null;
            }
            return new String(resp.body().bytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[mcp-market] request failed: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orDefault(String v, String dft) {
        return v == null || v.isBlank() ? dft : v;
    }
}
