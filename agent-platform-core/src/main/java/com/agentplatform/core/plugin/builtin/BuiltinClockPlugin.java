package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 内置插件：<b>时间助手</b>。
 *
 * <p>提供 {@code current_time} 工具。这是一个真实有需求的工具 —— 模型自身<b>不知道"现在几点"</b>，
 * 凡是需要"今天/本周/距今多少天"的问题都必须靠工具拿时间，否则它会凭训练数据编一个日期。</p>
 *
 * <h3>它同时演示了三件事（可作为写内置插件的模板）</h3>
 * <ol>
 *   <li><b>内置插件的写法</b>：{@code @Component} + 实现 {@code ToolProvider}，不需要 jar 与 manifest；
 *       启动时由 {@code BuiltinPluginRegistrar} 自动登记进插件市场（`plugin_def`，`tenant_id=__platform__`），
 *       因此能在界面上「内置插件」分组里看到并挂载到智能体。</li>
 *   <li><b>{@link PluginDescriptor}</b>：声明展示名与说明，否则市场卡片上的名字会退化成插件 id。</li>
 *   <li><b>工具契约的两个要点</b>：用四参构造器给出<b>完整 JSON Schema</b>（{@code PluginTool.of} 的 schema 是 null，
 *       模型会调不准）；参数非法时<b>直接抛异常</b>，宿主会转成 {@code ToolResult.fail} 回给模型让它重试。</li>
 * </ol>
 *
 * <p><b>生效前提</b>：工具属于 function calling 链路，需在对话页打开「工具」开关，
 * 且<b>关闭「流式」</b>（工具循环只挂在非流式入口上）。</p>
 */
@Component
public class BuiltinClockPlugin implements ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_clock";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    // ---- PluginDescriptor：市场卡片上的展示信息 ----

    @Override
    public String name() {
        return "时间助手";
    }

    @Override
    public String description() {
        return "提供 current_time 工具：按指定时区返回当前日期时间。模型自身不知道「现在几点」，"
                + "涉及今天/本周/距今天数的问题都应调用本工具。";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        // 无状态插件：不需要初始化
    }

    @Override
    public void onDetach(PluginContext ctx) {
        // 无状态插件：不需要清理
    }

    // ---- ToolProvider ----

    @Override
    public List<PluginTool> provideTools() {
        return List.of(new PluginTool(
                "current_time",
                "获取当前日期与时间。模型无法自行得知当前时间，凡涉及「现在/今天/本周/距今多少天」的问题都应先调用本工具。",
                inputSchema(),
                this::currentTime));
    }

    private JsonNode currentTime(JsonNode args, PluginContext ctx) {
        String zoneId = args == null ? "" : args.path("timezone").asText("");
        ZoneId zone;
        if (zoneId == null || zoneId.isBlank()) {
            zone = ZoneId.systemDefault();
        } else {
            try {
                zone = ZoneId.of(zoneId.trim());
            } catch (Exception e) {
                // 抛异常 → 宿主转成 ToolResult.fail(message)，模型能看到原因并改正参数重试
                throw new IllegalArgumentException("无法识别的时区：" + zoneId + "，请使用 IANA 名称，如 Asia/Shanghai");
            }
        }
        ZonedDateTime now = ZonedDateTime.now(zone);

        ObjectNode out = mapper.createObjectNode();
        out.put("datetime", now.format(FMT));
        out.put("date", now.toLocalDate().toString());
        out.put("weekday", now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
        out.put("timezone", zone.getId());
        out.put("offset", now.getOffset().getId());
        out.put("epoch_millis", now.toInstant().toEpochMilli());
        return out;
    }

    /** 入参 Schema：{@code timezone} 可选，留空用服务器默认时区。 */
    private ObjectNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ObjectNode timezone = properties.putObject("timezone");
        timezone.put("type", "string");
        timezone.put("description", "IANA 时区名，例如 Asia/Shanghai、UTC；留空则使用服务器默认时区");
        root.putArray("required");
        return root;
    }
}
