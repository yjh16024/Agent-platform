package com.agentplatform.core.workflow;

import com.agentplatform.framework.api.Tool;

import com.agentplatform.common.util.JsonUtils;
import com.agentplatform.core.tool.registry.ToolRegistrationService;
import com.agentplatform.core.workflow.node.WorkflowDefinition;
import com.agentplatform.core.workflow.node.WorkflowNode;
import com.agentplatform.core.workflow.schema.WorkflowSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例工作流「天气查询」的**可执行文档测试**。
 *
 * <h3>为什么给一个"示例"写测试</h3>
 * 示例会被当成模板照抄 —— 而它最容易悄悄失效：字段改名、工具改名、
 * 校验规则收紧，示例都不会自己更新，但**照抄的人会踩坑**，
 * 而且他们踩的坑看起来像是"自己的问题"（"为什么我照着做跑不通"）。
 *
 * <p>这里守住三件事：<b>能过校验器</b>、<b>结构是本例宣称的样子</b>、
 * <b>工具名与代码里的常量一致</b>。</p>
 */
class WeatherWorkflowExampleTest {

    private static final String RESOURCE = "/weather-workflow.json";

    private static WorkflowDefinition loadExample() throws Exception {
        try (InputStream in = WeatherWorkflowExampleTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "示例文件缺失：" + RESOURCE);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonUtils.mapper().readValue(json, WorkflowDefinition.class);
        }
    }

    @Test
    @DisplayName("★ 示例能通过保存期校验器（照抄它不该被拦下）")
    void examplePassesValidation() throws Exception {
        // 无参构造：不校验"类型必须有执行器"（那需要 Spring 容器），
        // 但**必填 config 那一段照常生效** —— 正是最容易被改名破坏的地方
        new WorkflowSchemaValidator().validate(loadExample());
    }

    @Test
    @DisplayName("★ 结构：start → 工具节点 → LLM → end，且变量串得起来")
    void structureIsAsIntended() throws Exception {
        WorkflowDefinition def = loadExample();

        assertEquals(4, def.nodes().size(), "示例应当保持四节点，多了就不是「最小示例」了");
        assertEquals("start", def.entry().id(), "入口应当是 Start 节点");

        WorkflowNode tool = def.node("get_weather");
        assertNotNull(tool, "应当有查询天气的节点");
        assertEquals("weather", tool.config().get("tool_name"));

        // 参数引用了输入变量 —— 这是"能拿到用户输入的城市"的前提
        String args = String.valueOf(tool.config().get("arguments"));
        assertTrue(args.contains("${city}"), "工具参数应当引用输入变量 city，实际：" + args);

        // LLM 引用了工具输出 —— 这是"能拿到天气结果"的前提
        WorkflowNode llm = def.node("reply");
        assertNotNull(llm, "应当有组织回复的节点");
        String prompt = String.valueOf(llm.config().get("prompt"));
        assertTrue(prompt.contains("${weather.output}"),
                "提示词应当引用工具输出（weather.output），实际：" + prompt);

        // 链路完整：不检查的话，改了 next 会让流程中途断掉而校验器仍可能通过
        assertEquals("get_weather", def.node("start").nextIds().get(0));
        assertEquals("reply", tool.nextIds().get(0));
        assertEquals("end", llm.nextIds().get(0));
        assertTrue(def.node("end").nextIds().isEmpty(), "End 不该有下游");
    }

    @Test
    @DisplayName("★ 工具名绑定常量：将来改了 WEATHER_TOOL，示例会立刻失效并报错")
    void toolNameMatchesRegisteredConstant() throws Exception {
        assertEquals(
                ToolRegistrationService.WEATHER_TOOL,
                loadExample().node("get_weather").config().get("tool_name"),
                "示例里写的工具名必须与随启动自动注册的那个一致");
    }
}
