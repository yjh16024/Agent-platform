package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 鉴权过滤器（责任链第一环）。
 *
 * <h3>它做什么</h3>
 * <ol>
 *   <li><b>租户上下文完整性</b>：工具执行必须带租户 ID。缺了它，审计归属、配额计数、
 *       MCP 连接归属、审批记录都会失真 —— 而且失真是**静默**的（记录照写，只是写进了空租户）；</li>
 *   <li><b>平台禁用清单</b>：给生产环境一个"关掉某个工具"的开关
 *       （如对外部署时禁用写文件/命令类工具），而不必改代码或关掉整条工具链。</li>
 * </ol>
 *
 * <h3>刻意不做的事（避免"看起来有安全机制、实际没有"）</h3>
 * <b>不做用户级工具 ACL</b>。原因不是"不需要"，而是**不该在这里做**：
 * 调用工具的权限已在入口用 {@code @RequiresPermission("agent:invoke")} 校验过，
 * 在这里再判一套"谁能调哪个工具"，两处判断必然有一天不一致 ——
 * 而不一致的安全检查比没有检查更危险（它会让人以为已经拦住了）。
 * 真要按角色限制工具可见范围，应该在**组装工具声明**时过滤
 * （模型根本看不到不该调的工具），而不是等到执行前才拒绝。
 *
 * <p>所以这一环的产出是"明确的拒绝 + 可配置的禁用"，不是一套新的权限模型。</p>
 */
@Slf4j
@Component
@Order(10)
public class AuthToolFilter implements ToolFilter {

    /**
     * 平台级禁用清单（逗号分隔的工具名）。
     *
     * <p>空白表示不禁用任何工具。命中即拒绝执行，并明确告知"是平台策略"，
     * 免得模型反复重试（它会以为是自己参数写错了）。</p>
     */
    @Value("${agent-platform.tool.disabled:}")
    private List<String> disabled = List.of();

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        if (invocation == null) {
            return ToolResult.fail("工具调用上下文缺失");
        }
        // ① 租户上下文
        String tenantId = invocation.ctx() == null ? null : invocation.ctx().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("[tool] 拒绝无租户上下文的调用：{}", invocation.toolName());
            return ToolResult.fail("工具 " + invocation.toolName()
                    + " 拒绝执行：调用缺少租户上下文（这会让审计与配额记录失真）");
        }
        // ② 平台禁用清单
        List<String> deny = disabled;
        if (deny != null && !deny.isEmpty() && deny.contains(invocation.toolName())) {
            log.warn("[tool] 拒绝已禁用的工具：{}（租户 {}）", invocation.toolName(), tenantId);
            return ToolResult.fail("工具 " + invocation.toolName()
                    + " 已被平台策略禁用（agent-platform.tool.disabled），请改用其它方式完成任务");
        }
        return chain.apply(invocation);
    }

    /** 解析逗号分隔的禁用清单（Spring 注入 String 时需要，List 注入则直接用）。 */
    static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
