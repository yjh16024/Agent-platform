package com.agentplatform.core.tool.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「懒初始化下，内置工具仍必须被注册」—— 防回归测试（2026-09-26 加）。
 *
 * <h3>为什么必须有这个测试</h3>
 * 桌面版以 {@code -Dspring.main.lazy-initialization=true} 启动（见 {@code desktop/main.js}）。
 * 凡<b>只往外注册、不被任何 Bean 依赖</b>的组件，懒加载下<b>永远不会被实例化</b> ——
 * 于是 {@code @PostConstruct} 也不会执行。
 *
 * <p>后果是<b>工具静默消失</b>：没有异常、没有堆栈、日志里连一行提示都没有，
 * 模型那边只是"我没有这个工具"。从用户视角完全看不出是平台的问题。</p>
 *
 * <p>这个坑项目里踩过三次：{@code BuiltinPluginRegistrar}（2026-09-20 修）、
 * {@code ActionRegistry}、以及 {@link ToolRegistrationConfig}（2026-09-26 才发现 ——
 * 它此前只有 {@code @PostConstruct}，导致 {@code fs_*} 整组文件工具<b>从未注册过</b>）。</p>
 *
 * <h3>★ 为什么现有测试全都拦不住它</h3>
 * {@code EmbeddedAppBootTest} 虽然也是启动测试，但它<b>没有启用懒初始化</b>，
 * 且只断言"Bean 存在"。于是出现最坏的情况：<b>400+ 用例全绿，功能却是死的。</b>
 *
 * <p>本测试的全部意义就是把那条启动参数<b>固化进测试</b>：只要 {@code lazy-initialization=true}
 * 下内置工具仍在注册表里，这类"静默消失"就不会再溜过去。</p>
 *
 * <p><b>以后新增任何"只往外注册"的组件</b>，请照 {@code BuiltinPluginRegistrar} 的写法：
 * 类上 {@code @Lazy(false)}，并用 {@code @EventListener(ApplicationReadyEvent)} 代替
 * {@code @PostConstruct}（事件监听器会被容器主动实例化，所以一定会执行）。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:lazytoolreg;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        // ★ 与 desktop/main.js 里后端的启动参数保持一致 —— 这一行是本测试存在的全部理由
        "spring.main.lazy-initialization=true"
})
class ToolRegistrationLazyInitTest {

    @Autowired
    private ToolRegistry registry;

    @Test
    @DisplayName("懒初始化下，fs_* 文件工具组必须全部注册（否则「让智能体改文件」整条链路不可用）")
    void fsToolsRegisteredEvenUnderLazyInit() {
        // 六个一起断言而不是只查一个：它们同属一组能力，
        // 缺任何一个都会让模型在某些操作上"突然没有工具"，而这种缺法最难排查。
        List<String> fsTools = List.of(
                "fs_read_file", "fs_write_file", "fs_edit_file",
                "fs_list_dir", "fs_glob", "fs_grep");
        for (String name : fsTools) {
            assertTrue(registry.contains(name),
                    name + " 未注册 —— 内置工具注册器在懒初始化下没被执行。"
                            + "检查 ToolRegistrationConfig 是否仍用 @PostConstruct（它必须改用"
                            + " @EventListener(ApplicationReadyEvent) + @Lazy(false)）。");
        }
    }

    @Test
    @DisplayName("懒初始化下，其它内置工具（calc）也必须注册 —— 防「整个内置注册器被跳过」")
    void otherBuiltinToolsRegisteredEvenUnderLazyInit() {
        assertTrue(registry.contains("calc"),
                "calc 未注册 —— 说明不是个别工具的问题，而是整个内置注册器被懒加载跳过了");
    }
}
