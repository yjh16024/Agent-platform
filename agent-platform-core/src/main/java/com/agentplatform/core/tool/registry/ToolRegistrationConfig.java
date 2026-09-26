package com.agentplatform.core.tool.registry;

import com.agentplatform.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工具自动注册器。
 * <p>Spring 自动收集所有 {@link Tool} Bean（calc / search / fs_* ...），启动时注册到
 * {@link ToolRegistry}。新增内置工具只需新增一个 Bean，核心代码零修改。</p>
 *
 * <h3>★ 为什么是 {@code @Lazy(false)} + {@code ApplicationReadyEvent}</h3>
 * 桌面版以 {@code -Dspring.main.lazy-initialization=true} 启动，而本类<b>不被任何 Bean 依赖</b>
 * （它的作用只是往注册中心里塞东西）—— 懒加载下它<b>永远不会被创建</b>，
 * 原先的 {@code @PostConstruct} 自然也不会执行。表现为<b>所有内置工具静默消失</b>：
 * 日志里连一行提示都没有，模型那边只是"没有这个工具"，从现象完全看不出来。
 *
 * <p>这个坑项目里踩过两次（{@code BuiltinPluginRegistrar}、{@code ActionRegistry}），
 * 凡是"只往外注册、不被依赖"的组件都适用，统一按同样方式绕开。</p>
 *
 * <p><b>⚠️ 2026-09-26 实际踩中</b>：本类此前只有 {@code @PostConstruct}，导致 {@code fs_*}
 * 整组文件工具在桌面版下<b>从未注册过</b>（用户反馈"让它改文件，它说它没有写文件的工具"）。
 * 而 {@code git_*} 一直正常，因为 {@code ActionRegistry} 早就加了 {@code @Lazy(false)} ——
 * <b>两者行为不一致，正是这个 bug 能藏这么久的原因</b>。排查依据：后端日志里
 * {@code [action] 已注册 3 个项目动作} 有、而 {@code Registered builtin tool} 一行都没有。</p>
 */
@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class ToolRegistrationConfig {

    private final ToolRegistry registry;
    private final List<Tool> builtinTools;

    @Lazy(false)
    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        for (Tool tool : builtinTools) {
            registry.register(tool, "builtin");
            log.info("Registered builtin tool: {}", tool.name());
        }
        // 汇总一行：排查"某个工具不见了"时，一眼就能看出总数对不对
        log.info("[builtin] 已注册 {} 个内置工具：{}", builtinTools.size(),
                String.join(", ", builtinTools.stream().map(Tool::name).toList()));
    }
}