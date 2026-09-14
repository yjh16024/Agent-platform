package com.agentplatform.core.tool.market;

import com.agentplatform.common.exception.BizException;
import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.registry.ToolRegistrationService;
import com.agentplatform.core.tool.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具市场：内置精选的**免 Key 公开 API**，用户可一键注册为平台的 HTTP 工具。
 *
 * <p>与 MCP 市场的分工：MCP 市场对接官方 registry（能力更强，但需要远端支持 Streamable HTTP）；
 * 本市场提供「拿到就能调」的轻量 HTTP 接口（天气、汇率、IP、二维码…），适合快速装配与演示。</p>
 *
 * <p>清单维护在 `resources/tool-market.json`，新增条目无需改代码。注册复用
 * {@link ToolRegistrationService}（会落库，重启后自动恢复）。</p>
 */
@Slf4j
@Service
public class ToolMarketService {

    private static final String RESOURCE = "tool-market.json";

    private final ToolRegistrationService registrationService;
    private final ToolRegistry registry;

    /** 清单只读且不变，首次解析后缓存。 */
    private volatile List<Map<String, Object>> cached;

    public ToolMarketService(ToolRegistrationService registrationService, ToolRegistry registry) {
        this.registrationService = registrationService;
        this.registry = registry;
    }

    /** 列出市场条目（含是否已注册）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = loadCatalog();
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Map<String, Object> it : items) {
            Map<String, Object> m = new LinkedHashMap<>(it);
            Object name = it.get("name");
            // 注意：ToolRegistry.get() 在缺失时**抛异常**，判断存在性必须用 contains()
            m.put("installed", name != null && registry.contains(String.valueOf(name)));
            out.add(m);
        }
        return out;
    }

    /**
     * 一键注册某个市场条目为平台 HTTP 工具。
     *
     * @param id 清单条目的 id
     */
    public Map<String, Object> install(String tenantId, String id) {
        Map<String, Object> hit = null;
        for (Map<String, Object> it : loadCatalog()) {
            if (id != null && id.equals(String.valueOf(it.get("id")))) {
                hit = it;
                break;
            }
        }
        if (hit == null) {
            throw BizException.notFound("tool in market", id);
        }
        String name = String.valueOf(hit.get("name"));
        if (registry.contains(name)) {
            throw BizException.conflict("工具已存在: " + name + "（可先删除再注册）");
        }
        String description = String.valueOf(hit.getOrDefault("description", ""));
        String endpoint = String.valueOf(hit.get("endpoint"));
        String method = String.valueOf(hit.getOrDefault("method", "GET"));
        JsonNode schema = null;
        Object params = hit.get("parameters");
        if (params != null) {
            schema = JsonUtils.mapper().valueToTree(params);
        }
        registrationService.register(tenantId, name, description, endpoint, method, schema);
        log.info("[tool-market] installed {} ({})", name, endpoint);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("endpoint", endpoint);
        r.put("method", method);
        return r;
    }

    // ---------------- 内部 ----------------

    private List<Map<String, Object>> loadCatalog() {
        List<Map<String, Object>> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            List<Map<String, Object>> parsed = parse();
            cached = parsed;
            return parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parse() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = JsonUtils.toJsonNode(json);
            List<Map<String, Object>> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    Map<String, Object> m = JsonUtils.mapper().convertValue(n, Map.class);
                    out.add(m);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("[tool-market] 读取清单失败 {}: {}", RESOURCE, e.getMessage());
            return List.of();
        }
    }
}
