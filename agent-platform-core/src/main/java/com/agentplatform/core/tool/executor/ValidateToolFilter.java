package com.agentplatform.core.tool.executor;

import com.agentplatform.core.tool.ToolResult;
import com.agentplatform.core.tool.ToolSchemas;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数校验过滤器（责任链第二环）—— 按工具的 {@code inputSchema} 做**轻量**校验。
 *
 * <h3>为什么是"轻量"，而不是完整的 JSON Schema 校验</h3>
 * 完整实现要引入一个 schema 校验库（项目一贯偏好零新增依赖），而且**收益与成本不成比例**：
 * 模型给出的参数错误在实践中几乎只有两类 —— <b>漏了必填项</b>、
 * <b>类型明显不对</b>（把对象塞进 string、把文本塞进 number）。枚举、格式、范围那些，
 * 工具自己的语义校验（比如"路径必须在工作区内"）本来就做得更好、报错也更准确。
 *
 * <h3>★ 保守是刻意的：宁可放过，不可误拦</h3>
 * 校验器最坏的失败模式不是"漏掉一个错误参数"，而是**拦下一个正确的调用** ——
 * 后者会让本来能完成的任务直接失败，而且用户看到的是"工具说参数不对"这种
 * 与模型描述互相矛盾的信息。所以这里的规则是：
 * <ul>
 *   <li>只校验 schema **明确声明**的部分（没声明就不猜）；</li>
 *   <li>不认识类型（如 {@code anyOf} / 自定义格式）一律放行；</li>
 *   <li>{@code integer} 与 {@code number} 互认（模型写 {@code 5.0} 很常见，
 *       为此拦下没有意义）；</li>
 *   <li>{@code required} 里空白字符串也算缺失（模型填了个空串，等于没填）。</li>
 * </ul>
 *
 * <p>报错信息里带上"该工具接受哪些参数"，让模型能直接修正而不是重试同一个错误。</p>
 */
@Slf4j
@Component
@Order(20)
public class ValidateToolFilter implements ToolFilter {

    @Override
    public ToolResult doFilter(ToolInvocation invocation, ToolChain chain) {
        String toolName = invocation.toolName();
        JsonNode schema = ToolSchemas.orEmpty(chain.target() == null ? null : chain.target().inputSchema());
        JsonNode args = invocation.args();

        // ① 必填项
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode r : required) {
                String key = r.asText("");
                if (key.isEmpty()) {
                    continue;
                }
                JsonNode value = args == null ? null : args.get(key);
                if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                    return ToolResult.fail("缺少必需参数 " + key + "（工具 " + toolName + "）。"
                            + hint(schema));
                }
            }
        }

        // ② 类型（只对 schema 声明了 type、且入参里确实有这个字段的）
        JsonNode props = schema.path("properties");
        if (props.isObject() && args != null && args.isObject()) {
            for (Map.Entry<String, JsonNode> entry : props.properties()) {
                String expect = entry.getValue().path("type").asText("");
                JsonNode actual = args.get(entry.getKey());
                if (expect.isEmpty() || actual == null || actual.isNull()) {
                    continue;
                }
                if (!typeMatches(expect, actual)) {
                    log.debug("[tool] 参数类型不符：tool={} param={} expect={} actual={}",
                            toolName, entry.getKey(), expect, actual.getNodeType());
                    return ToolResult.fail("参数 " + entry.getKey() + " 的类型应为 " + expect
                            + "，实际收到 " + actual.getNodeType() + "（工具 " + toolName + "）。"
                            + hint(schema));
                }
            }
        }
        return chain.apply(invocation);
    }

    /**
     * 类型是否匹配（**宽松**：见类注释"保守是刻意的"）。
     */
    private static boolean typeMatches(String expect, JsonNode actual) {
        return switch (expect) {
            case "string" -> actual.isTextual();
            // integer 与 number 互认：模型给 5.0 表示整数很常见，为它拦下没有意义
            case "integer", "number" -> actual.isNumber();
            case "boolean" -> actual.isBoolean();
            case "array" -> actual.isArray();
            case "object" -> actual.isObject();
            // 未知 / 复杂声明（anyOf、自定义 format…）不猜，直接放行
            default -> true;
        };
    }

    /** 提示"该工具接受哪些参数"，让模型能一次改对。 */
    private static String hint(JsonNode schema) {
        JsonNode props = schema.path("properties");
        if (!props.isObject() || props.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        props.propertyNames().forEach(names::add);
        return "可接受的参数：" + String.join(" / ", names);
    }
}
