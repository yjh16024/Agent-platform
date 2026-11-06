package com.agentplatform.core.tool.registry;

import com.agentplatform.framework.api.Tool;
import com.agentplatform.framework.api.ToolCatalog;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 平台工具注册中心的**只读视图**，供框架运行时消费（P2' 工具接缝）。
 *
 * <p>为什么用适配器而不是让注册中心直接实现 {@link ToolCatalog}：
 * 注册中心的方法签名里带着平台语义（{@code sourceOf}、{@code SOURCE_PLUGIN} 等），
 * 让它去 implements 一个框架接口，等于把平台概念钉进契约层。适配器只有 30 行，
 * 却能让注册中心继续按自己的方式演进。</p>
 *
 * <p>取不到工具时沿用平台语义（抛 {@code BizException}），框架侧会把它转成
 * 一条工具失败结果回灌给模型——这也是平台既有的行为，不额外处理。</p>
 */
@Component
public class PlatformToolCatalog implements ToolCatalog {

    private final ToolRegistry registry;

    public PlatformToolCatalog(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Tool get(String name) {
        return registry.get(name);
    }

    @Override
    public boolean contains(String name) {
        return registry.contains(name);
    }

    @Override
    public Collection<Tool> all() {
        return registry.all();
    }
}
