package com.agentplatform.framework.runtime;

import com.agentplatform.framework.api.ToolCatalog;
import com.agentplatform.framework.api.ToolChain;
import com.agentplatform.framework.api.ToolContext;
import com.agentplatform.framework.api.ToolFilter;
import com.agentplatform.framework.api.ToolInvocation;
import com.agentplatform.framework.api.ToolResult;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 工具执行器：把「工具目录 + 责任链」拼成一个调用入口。
 *
 * <p>{@code filters} 为空时链尾直接执行工具——这意味着**默认没有治理**。
 * 这是刻意的：治理（鉴权 / 校验 / 限流 / 审计 / 超时）是宿主的选择，
 * 框架只保证「插得进去、顺序可控」。平台侧那五环就是通过 {@link ToolFilter} 接进来的。
 */
public class ToolExecutor {

    private final ToolCatalog catalog;
    private final List<ToolFilter> filters;

    public ToolExecutor(ToolCatalog catalog, List<ToolFilter> filters) {
        if (catalog == null) {
            throw new IllegalArgumentException("ToolCatalog 必填");
        }
        this.catalog = catalog;
        this.filters = (filters == null) ? List.of() : List.copyOf(filters);
    }

    public ToolResult run(String toolName, JsonNode args, ToolContext ctx) {
        return new ToolChain(filters.iterator(), catalog.get(toolName))
                .apply(new ToolInvocation(toolName, args, ctx));
    }

    public List<ToolFilter> filters() {
        return filters;
    }
}
