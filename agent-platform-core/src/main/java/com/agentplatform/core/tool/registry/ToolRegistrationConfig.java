package com.agentplatform.core.tool.registry;

import com.agentplatform.core.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工具自动注册器。
 * <p>Spring 自动收集所有 {@link Tool} Bean（calc / search ...），启动时注册到
 * {@link ToolRegistry}。新增内置工具只需新增一个 Bean，核心代码零修改。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistrationConfig {

    private final ToolRegistry registry;
    private final List<Tool> builtinTools;

    @PostConstruct
    public void registerAll() {
        for (Tool tool : builtinTools) {
            registry.register(tool, "builtin");
            log.info("Registered builtin tool: {}", tool.name());
        }
    }
}