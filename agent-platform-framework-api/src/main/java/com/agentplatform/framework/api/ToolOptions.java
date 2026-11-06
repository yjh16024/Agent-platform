package com.agentplatform.framework.api;

import java.util.List;

/**
 * 本轮的工具装配选项。
 *
 * <p>沿用平台现有语义：{@code enabled} 是总开关，{@code allowlist} 为空表示放行全部已注册工具。
 * 注意这里只表达「本轮允许哪些工具」，<b>不表达治理</b>——鉴权 / 参数校验 / 限流 / 审计 / 超时
 * 由工具责任链负责，两者不能混在一个类型里。
 *
 * @param maxRounds 工具循环轮数上限；0 表示使用实现方默认值
 */
public record ToolOptions(boolean enabled, List<String> allowlist, int maxRounds) {

    public ToolOptions {
        allowlist = (allowlist == null) ? List.of() : List.copyOf(allowlist);
        if (maxRounds < 0) {
            throw new IllegalArgumentException("maxRounds 不能为负");
        }
    }

    public static ToolOptions none() {
        return new ToolOptions(false, List.of(), 0);
    }

    public static ToolOptions all(int maxRounds) {
        return new ToolOptions(true, List.of(), maxRounds);
    }

    public static ToolOptions only(List<String> allowlist, int maxRounds) {
        return new ToolOptions(true, allowlist, maxRounds);
    }

    public boolean allows(String toolName) {
        if (!enabled) {
            return false;
        }
        return allowlist.isEmpty() || allowlist.contains(toolName);
    }
}
