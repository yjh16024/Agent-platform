package com.agentplatform.core.tool.registry;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.executor.HttpApiTool;
import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.ToolContext;
import com.agentplatform.model.entity.ToolRegistration;
import com.agentplatform.model.repository.ToolRegistrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具注册持久化服务。
 * <p>
 * 解决「动态注册的 HTTP 工具在重启 / 重新构建后丢失」的问题：
 * 注册/更新/卸载同步落库（{@code tool_registration}），启动时按 {@code enabled=1} 重建工具实例。
 * </p>
 * <p>
 * 仓储为可选依赖（单测或无 DB 场景注入为 null），此时退化为纯内存注册，不影响主流程。
 * </p>
 */
@Slf4j
@Service
public class ToolRegistrationService {

    public static final String DEFAULT_TENANT = "default";
    public static final String SOURCE_HTTP = "http";

    /** 内置（随启动自动注册）的 HTTP 工具：天气查询。 */
    public static final String WEATHER_TOOL = "weather";
    private static final String WEATHER_ENDPOINT = "https://wttr.in/{city}?format=3";
    private static final String WEATHER_DESCRIPTION =
            "查询指定城市的当前天气（天气现象与气温）。当用户询问某地天气时使用；"
                    + "参数 city 支持中文城市名（如 北京、上海）或英文名（如 Beijing）。";

    private final ToolRegistry registry;

    /** 可选依赖（无 DB 场景为 null）。包私有：便于测试注入。 */
    @Autowired(required = false)
    ToolRegistrationRepository repository;

    public ToolRegistrationService(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 启动时：加载持久化的 HTTP 工具 → 补齐内置工具（天气）。
     */
    @PostConstruct
    public void loadAll() {
        int loaded = 0;
        if (repository != null) {
            try {
                List<ToolRegistration> rows = repository.findByEnabledTrue();
                for (ToolRegistration row : rows) {
                    try {
                        registry.register(toTool(row), SOURCE_HTTP);
                        loaded++;
                    } catch (Exception e) {
                        log.warn("Rebuild tool {} failed: {}", row.getToolName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Load tool registrations failed: {}", e.getMessage());
            }
        }
        if (loaded > 0) {
            log.info("Restored {} persisted HTTP tool(s)", loaded);
        }
        seedBuiltinTools();
    }

    /**
     * 补齐内置 HTTP 工具（仅当未注册时），保证「开箱即用」。
     */
    private void seedBuiltinTools() {
        if (registry.contains(WEATHER_TOOL)) {
            return;
        }
        register(DEFAULT_TENANT, WEATHER_TOOL, WEATHER_DESCRIPTION, WEATHER_ENDPOINT, "GET",
                JsonUtils.mapper().valueToTree(weatherSchema()));
        log.info("Seeded builtin HTTP tool: {} -> {}", WEATHER_TOOL, WEATHER_ENDPOINT);
    }

    private static Map<String, Object> weatherSchema() {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("description", "城市名，如 北京 / 上海 / Beijing");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", city);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("city"));
        return schema;
    }

    /**
     * 注册（或更新）HTTP 工具：先入注册中心，再落库；同名覆盖即热更新。
     */
    public void register(String tenantId, String name, String description, String endpoint,
                         String method, JsonNode parameters) {
        Map<String, Object> schemaMap = toMap(parameters);
        registry.register(new HttpApiTool(name, description, parameters, endpoint, method), SOURCE_HTTP);
        persist(tenantId, name, description, endpoint, method, schemaMap);
    }

    /** 删除工具：先从注册中心移除，再删库。 */
    public void unregister(String tenantId, String name) {
        registry.unregister(name);
        if (repository == null) {
            return;
        }
        try {
            repository.findByTenantIdAndToolName(tenantId, name).ifPresent(repository::delete);
        } catch (Exception e) {
            log.warn("Delete tool registration {} failed: {}", name, e.getMessage());
        }
    }

    private void persist(String tenantId, String name, String description, String endpoint,
                         String method, Map<String, Object> schema) {
        if (repository == null) {
            return;
        }
        try {
            ToolRegistration row = repository.findByTenantIdAndToolName(tenantId, name)
                    .orElseGet(() -> ToolRegistration.builder()
                            .tenantId(tenantId)
                            .toolName(name)
                            .source(SOURCE_HTTP)
                            .enabled(true)
                            .build());
            row.setDescription(description);
            row.setEndpoint(endpoint);
            row.setMethod(method == null ? "POST" : method.toUpperCase());
            row.setParameters(schema);
            row.setEnabled(true);
            repository.save(row);
        } catch (Exception e) {
            log.warn("Persist tool registration {} failed: {}", name, e.getMessage());
        }
    }

    private HttpApiTool toTool(ToolRegistration row) {
        JsonNode schema = row.getParameters() == null
                ? null : JsonUtils.mapper().valueToTree(row.getParameters());
        return new HttpApiTool(row.getToolName(), row.getDescription(), schema,
                row.getEndpoint(), row.getMethod());
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return JsonUtils.mapper().convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Convert tool schema failed: {}", e.getMessage());
            return null;
        }
    }

    /** 直接用工具跑一次（便于验证与调试）。 */
    public ToolResult invoke(String name, JsonNode args) {
        return registry.get(name).execute(args, ToolContext.of(DEFAULT_TENANT, null, null));
    }
}
