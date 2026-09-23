package com.example.plugin;

import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类型七：<b>事件订阅</b>（{@code EventSubscriber}）—— 监听"系统里发生的事"并作出反应。
 *
 * <p>2026-09-22 新增能力。与钩子的区别：钩子拦的是<b>管线的特定环节</b>（能改写输入输出），
 * 事件订阅是"某件事<b>已经发生</b>"的事后通知（改变不了已发生的事）。
 * 想"介入并改写"用钩子，想"知道然后去做别的"用事件。</p>
 *
 * <h3>本插件演示的典型场景</h3>
 * <p>运行失败 → 上报到外部告警渠道（真实场景是 Slack / 钉钉 / PagerDuty / 监控平台）。
 * 示例不真发 HTTP（离线也要能跑），改为<b>累计失败次数并记日志</b>，
 * 再配一个工具让"确实收到了事件"这件事可被验证 —— 否则示例跑没跑过根本看不出来。</p>
 *
 * <h3>三条必须知道的约束</h3>
 * <ol>
 *   <li><b>只能订阅 agent 级事件</b>（{@link EventTypes#PLUGIN_SUBSCRIBABLE}）。
 *       配额超限、权限拒绝这类事件没有 agent 维度，派发给插件会打破
 *       "插件按智能体隔离"的模型（等于任意智能体上的插件都能监听全租户行为）。
 *       声明了不可订阅的类型不会报错，但收不到，宿主注册时会记一条 warn。</li>
 *   <li><b>只收到"挂载了本插件的那台智能体"的事件</b> —— 挂到 A 的收不到 B 运行失败的提醒。</li>
 *   <li><b>onEvent 在发布线程上同步调用</b>，别做重活（长耗时 HTTP / 大文件写入）；
 *       需要异步就自己投线程池。抛异常会被宿主捕获，不影响其它订阅者与主流程。</li>
 * </ol>
 */
public class RunFailureAlertPlugin implements EventSubscriber, ToolProvider {

    /** 必须与 manifests/run-failure-alert.yaml 里的 id 一致。 */
    public static final String PLUGIN_ID = "example_run_failure_alert";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 累计收到的失败事件数（演示"确实收到了"，真实插件这里换成 HTTP 上报）。 */
    private final AtomicInteger failureCount = new AtomicInteger();
    /** 最近一次失败的摘要，便于用工具查看。 */
    private volatile String lastFailure = "(尚未收到任何失败事件)";

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onAttach(PluginContext ctx) {
        // 无状态（计数在内存），无需初始化。真实插件可在这里读取 config 里的
        // webhook 地址、超时、重试策略等。
    }

    @Override
    public void onDetach(PluginContext ctx) {
        failureCount.set(0);
        lastFailure = "(尚未收到任何失败事件)";
    }

    // ---------------- EventSubscriber ----------------

    /**
     * 只订阅「运行失败」。
     *
     * <p>刻意<b>不</b>订阅 {@code agent.run.completed}：成功运行是高频事件，
     * 订阅它会把告警插件的意义冲淡（也应该用独立插件做统计类需求）。</p>
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of(EventTypes.AGENT_RUN_FAILED);
    }

    @Override
    public void onEvent(DomainEvent event) {
        int n = failureCount.incrementAndGet();
        String agentName = event.string("agent_name");
        String model = event.string("model");
        String error = event.string("error");
        lastFailure = "agent=" + agentName + " model=" + model + " error=" + error;

        // 真实插件在这里发 Slack / 钉钉 / 上报监控。示例只记日志，
        // 保证离线可跑、也不需要任何凭据。
        // 注意：这是同步调用，所以绝不做网络请求（见类注释第 3 条）。
        System.out.println("[example-run-failure-alert] 第 " + n + " 次失败告警：" + lastFailure);
    }

    // ---------------- ToolProvider（用于验证事件确实收到了） ----------------

    @Override
    public List<PluginTool> provideTools() {
        return List.of(new PluginTool(
                "example_failure_alert_stats",
                "查看示例告警插件累计收到的运行失败次数与最近一次摘要（验证事件订阅生效）",
                inputSchema(),
                this::stats));
    }

    private JsonNode stats(JsonNode args, PluginContext ctx) {
        ObjectNode out = mapper.createObjectNode();
        out.put("plugin", PLUGIN_ID);
        out.put("subscribed_event", EventTypes.AGENT_RUN_FAILED);
        out.put("failure_count", failureCount.get());
        out.put("last_failure", lastFailure);
        return out;
    }

    private ObjectNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        // 无参数：仍然给出合法的空 properties，避免模型以为需要额外参数
        root.putObject("properties");
        return root;
    }
}
