package com.agentplatform.core.tool;

import com.agentplatform.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「所有已注册工具的 schema 都必须合法」—— 防回归测试（2026-09-28 加）。
 *
 * <h3>为什么必须有这个测试</h3>
 * 各工具的 {@code inputSchema()} 是**手写 JSON 字符串**（文本块），反斜杠要过 Java + JSON
 * **两层转义** —— 少写一层就会在 JSON 里留下非法转义（如 {@code \s}），Jackson 直接抛
 * {@code Failed to parse JSON}。
 *
 * <p>致命之处在于**抛出位置**：{@code inputSchema()} 由
 * {@code AgentRuntimeService.resolveToolSpecs()} 在 {@code prepare()} 里调用，
 * 而那是**发起对话的必经之路** —— 一个工具的 schema 写错，整场对话直接不可用。</p>
 *
 * <p><b>2026-09-28 真实故障</b>：{@code FsGrepTool} 的 description 里写了
 * {@code class\\s+\\w+Service}（少一层），于是它抛异常、对话全废。而因为
 * "工具"开关平时是关的（关着不会调 {@code inputSchema}），这个 bug 潜伏到
 * 用户第一次开工具才爆。定位过程很曲折：日志里当时只有一句
 * {@code Failed to parse JSON}、没有堆栈，最后是靠给 {@code GlobalExceptionHandler}
 * 补堆栈才找到这一行。**这个测试就是为了不再重演。**</p>
 *
 * <h3>为什么连带断言"顶层 type=object"</h3>
 * 缺 {@code type} 不会在本平台报错，但会让**模型厂商直接返回 400**
 * （OpenAI 兼容接口要求 schema 为 {@code type: object}）——
 * 那是一种"本地全绿、调模型才炸"的故障，更该在测试期拦下。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=embedded",
        "spring.datasource.url=jdbc:h2:mem:toolschema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        // 与桌面版一致：懒初始化下注册器仍必须执行（否则本测试会空转）
        "spring.main.lazy-initialization=true"
})
class ToolSchemaValidityTest {

    @Autowired
    private ToolRegistry registry;

    @Test
    @DisplayName("★ 每个已注册工具的 inputSchema 都能解析，且顶层 type=object")
    void everyToolSchemaIsValid() {
        List<String> bad = new ArrayList<>();
        for (Tool tool : registry.all()) {
            if (tool == null) {
                continue;
            }
            try {
                /*
                 * 检查的是**实际发给模型的那份 schema**，而不是工具的原始返回值。
                 *
                 * 差别的由来：部分工具（如 calc）的 inputSchema() deliberately 返回 null，
                 * 由 resolveToolSpecs 里的 ToolSchemas.orEmpty 补成空 object ——
                 * 直接断言"原始值不能为 null"会把这种合法情况误判成错误，
                 * 而真正会打到厂商那里的是**补全之后**的结果。
                 */
                JsonNode schema = ToolSchemas.orEmpty(tool.inputSchema());
                if (!"object".equals(schema.path("type").asText(""))) {
                    bad.add(tool.name() + "：顶层 type 必须是 object（否则模型厂商直接 400），实际="
                            + schema.path("type").asText("(缺失)"));
                }
            } catch (Exception e) {
                bad.add(tool.name() + "：解析失败 —— " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(),
                "以下工具的 schema 有问题（会让**整场对话不可用**，或让模型厂商返回 400）：\n  "
                        + String.join("\n  ", bad));
    }

    /**
     * 防止上面那条测试**空转**。
     *
     * <p>如果注册表是空的（例如注册器又被懒加载跳过），{@code everyToolSchemaIsValid}
     * 会因为"没有工具可检查"而**永远通过** —— 那是最坏的一种测试：看着有、其实没保护力。</p>
     */
    @Test
    @DisplayName("已注册工具数量 > 0（否则上一条测试是空转）")
    void registryIsNotEmpty() {
        int n = registry.all().size();
        assertTrue(n > 0, "工具注册表为空 —— 上一条测试会变成空转，没有实际保护力。"
                + "检查 ToolRegistrationConfig 是否在懒初始化下被执行。");
    }
}
