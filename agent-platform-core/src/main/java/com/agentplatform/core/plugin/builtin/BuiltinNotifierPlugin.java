package com.agentplatform.core.plugin.builtin;

import com.agentplatform.plugin.sdk.ConfigFieldDef;
import com.agentplatform.plugin.sdk.EventSubscriber;
import com.agentplatform.plugin.sdk.PluginContext;
import com.agentplatform.plugin.sdk.PluginDescriptor;
import com.agentplatform.plugin.sdk.PluginTool;
import com.agentplatform.plugin.sdk.ToolProvider;
import com.agentplatform.plugin.sdk.model.DomainEvent;
import com.agentplatform.plugin.sdk.model.EventTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置插件：<b>多渠道告警推送</b>。
 *
 * <h3>它解决的是「没人看着的时候，出事了你不知道」</h3>
 * 平台的站内通知（`NotificationService`）只能在你打开页面时被看见 ——
 * 而运行失败、长任务跑完这些事，恰恰发生在你没看页面的时候。
 * 本插件把这类事件推到<b>你本来就会看的地方</b>：飞书群、钉钉群、企业微信群、Slack 频道。
 *
 * <p>它同时提供两种形态（对标 dshmarket 的 {@code dsh-notifier}）：</p>
 * <ul>
 *   <li><b>事件订阅</b>：运行失败 / 运行完成时自动推送 —— 这是主要价值，配好就不用管了；</li>
 *   <li><b>工具</b> {@code send_notification}：让智能体在对话中主动给你发消息
 *       （"帮我盯着，成了就通知我"这类请求就靠它）。</li>
 * </ul>
 *
 * <h3>★ 为什么必须做"渠道适配"而不是统一发一个 JSON</h3>
 * 几个主流 IM 的群机器人 webhook <b>长得像但字段名不同</b>，发错了平台会静默丢弃或回一个
 * 含义模糊的错误码 —— 表现为"配了但收不到"，极难排查：
 * <pre>
 *   飞书   {"msg_type":"text","content":{"text":"..."}}
 *   钉钉   {"msgtype":"text","text":{"content":"..."}}          ← msgtype / msg_type 之别
 *   企微   {"msgtype":"text","text":{"content":"..."}}
 *   Slack  {"text":"..."}
 * </pre>
 * 所以这里按渠道分别组装 body，并把上游返回码带进日志。
 *
 * <h3>失败处理：静默，但留痕</h3>
 * 推送失败绝不抛出 —— 它是一个"锦上添花"的通知通道，不能因为它挂了而让对话失败；
 * 但每次失败都会打 WARN 日志（含上游响应），否则"为什么没收到通知"会变成无法排查的问题。
 */
@Slf4j
@Component
public class BuiltinNotifierPlugin implements EventSubscriber, ToolProvider, PluginDescriptor {

    /** 与 plugin_def 中的 plugin_id 一致。 */
    public static final String PLUGIN_ID = "builtin_notifier";

    public static final String TOOL_NAME = "send_notification";

    // ---------------- 配置键（与前端表单字段一致） ----------------

    public static final String CFG_CHANNEL = "channel";
    public static final String CFG_WEBHOOK_URL = "webhookUrl";
    public static final String CFG_SECRET = "secret";
    public static final String CFG_NOTIFY_ON_FAILED = "notifyOnFailed";
    public static final String CFG_NOTIFY_ON_COMPLETED = "notifyOnCompleted";
    public static final String CFG_TIMEOUT_MS = "timeoutMs";

    /** 支持的渠道。`generic` 是"任何接受 {"text": "..."} 的地址"。 */
    public static final String CHANNEL_FEISHU = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_WECOM = "wecom";
    public static final String CHANNEL_SLACK = "slack";
    public static final String CHANNEL_GENERIC = "generic";

    private static final int DEFAULT_TIMEOUT_MS = 8000;
    private static final int HARD_MAX_TIMEOUT_MS = 30_000;

    /** 按 agentId 隔离配置（插件是单例；理由同 BuiltinTtsPlugin）。 */
    private final Map<String, NotifyConfig> configByAgent = new ConcurrentHashMap<>();

    // ---------------- Plugin ----------------

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String name() {
        return "告警推送";
    }

    @Override
    public String description() {
        return "把「运行失败 / 运行完成」推送到飞书、钉钉、企业微信、Slack 群机器人或任意 webhook —— "
                + "不需要盯着页面也能知道出事了。同时给智能体一个 send_notification 工具，"
                + "让它能在对话中主动通知你。在挂载配置里填 webhook 地址即可。";
                }

                /**
                * 配置项声明（键名与 {@link NotifyConfig#of} 读取的完全一致）。
                *
                * <p>只有 webhook 地址是实际需要的（但<b>故意没标必填</b>）：
                * 这个插件挂载后即使不填地址也能用它的 {@code send_notification} 工具，
                * 只是不会自动推送事件。标成必填反而会挡住这种用法。</p>
                */
                @Override
                public List<ConfigFieldDef> configFields() {
                return List.of(
                ConfigFieldDef.select(CFG_CHANNEL, "渠道", List.of(
                              WebhookPusher.CHANNEL_FEISHU, WebhookPusher.CHANNEL_DINGTALK,
                              WebhookPusher.CHANNEL_WECOM, WebhookPusher.CHANNEL_SLACK,
                              WebhookPusher.CHANNEL_GENERIC))
                      .hint("决定消息体的格式；认不出的一律按通用格式发。选错的表现是「配了但收不到」"),
                ConfigFieldDef.text(CFG_WEBHOOK_URL, "Webhook 地址")
                      .ph("https://open.feishu.cn/open-apis/bot/v2/hook/...")
                      .hint("在群设置里添加「自定义机器人」后复制得到的地址"),
                ConfigFieldDef.secret(CFG_SECRET, "加签密钥")
                      .hint("可选。只有钉钉机器人在开启「加签」安全设置时才需要填"),
                ConfigFieldDef.bool(CFG_NOTIFY_ON_FAILED, "运行失败时推送")
                      .def("true"),
                ConfigFieldDef.bool(CFG_NOTIFY_ON_COMPLETED, "运行完成时推送")
                      .def("false")
                      .hint("默认关：一场对话有十几轮，每轮推一条会把群刷屏"),
                ConfigFieldDef.number(CFG_TIMEOUT_MS, "超时（毫秒）", 500, HARD_MAX_TIMEOUT_MS)
                      .def(String.valueOf(DEFAULT_TIMEOUT_MS)));
                }

                @Override
                public void onAttach(PluginContext ctx) {
        NotifyConfig cfg = NotifyConfig.of(ctx.config());
        configByAgent.put(ctx.agentId(), cfg);
        log.info("[notifier] 已挂载到 agent={} channel={} 已配置 webhook={} 失败推送={} 完成推送={}",
                ctx.agentId(), cfg.channel(), cfg.hasWebhook(), cfg.notifyOnFailed(), cfg.notifyOnCompleted());
    }

    @Override
    public void onDetach(PluginContext ctx) {
        configByAgent.remove(ctx.agentId());
    }

    // ---------------- 事件订阅 ----------------

    /**
     * 只订阅这两个 —— 它们是 {@link EventTypes#PLUGIN_SUBSCRIBABLE} 的全部内容。
     *
     * <p>租户级事件（配额超限 / 权限拒绝）刻意拿不到：它们没有 agent 维度，
     * 派发给插件就等于"任何智能体上的插件都能监听全租户行为"（见 EventTypes 的说明）。</p>
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of(EventTypes.AGENT_RUN_FAILED, EventTypes.AGENT_RUN_COMPLETED);
    }

    /**
     * 事件到达。
     *
     * <p>在<b>发布事件的线程</b>上同步调用，所以这里会真的阻塞一小会儿（一次 HTTP）。
     * 通知本身很快（8 秒超时兜底），且"要推送就得等它发完"是符合预期的，
     * 因此不做异步投递 —— 异步会让"推送失败"的日志脱离事件上下文，反而更难排查。</p>
     */
    @Override
    public void onEvent(DomainEvent event) {
        if (event == null) {
            return;
        }
        NotifyConfig cfg = configByAgent.values().stream().findFirst().orElse(null);
        // 事件里带 agentId：优先用它的配置；找不到就说明这个 agent 没挂本插件（不该发生）
        String agentId = payload(event, "agent_id");
        if (agentId != null && configByAgent.containsKey(agentId)) {
            cfg = configByAgent.get(agentId);
        }
        if (cfg == null || !cfg.hasWebhook()) {
            return;   // 没配 webhook：静默跳过（挂件本身可能只是想用它的工具）
        }

        boolean failed = EventTypes.AGENT_RUN_FAILED.equals(event.type());
        if (failed && !cfg.notifyOnFailed()) {
            return;
        }
        if (!failed && !cfg.notifyOnCompleted()) {
            return;
        }

        String agentName = payload(event, "agent_name");
        String model = payload(event, "model");
        StringBuilder text = new StringBuilder();
        text.append(failed ? "❌ 智能体运行失败\n" : "✅ 智能体运行完成\n");
        text.append("智能体：").append(agentName == null ? "(未命名)" : agentName).append('\n');
        if (model != null) {
            text.append("模型：").append(model).append('\n');
        }
        if (failed) {
            String error = payload(event, "error");
            text.append("错误：").append(error == null ? "(无详情)" : error).append('\n');
        }
        String name = payload(event, "agent_name");
        text.append("时间：").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        push(cfg, text.toString(), "event:" + event.type() + " agent=" + name);
    }

    private static String payload(DomainEvent event, String key) {
        Object v = event.payload() == null ? null : event.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ---------------- 工具 ----------------

    @Override
    public List<PluginTool> provideTools() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode message = props.putObject("message");
        message.put("type", "string");
        message.put("description", "要发送的通知内容，简洁说明发生了什么");
        ArrayNode required = schema.putArray("required");
        required.add("message");

        return List.of(new PluginTool(TOOL_NAME,
                "给用户发一条通知（推送到他配置的飞书/钉钉/企微/Slack 群）。"
                        + "当用户说「完成了告诉我」「盯着这个任务」时使用。",
                schema,
                this::handleNotify));
    }

    private JsonNode handleNotify(JsonNode args, PluginContext ctx) {
        String message = args == null ? null : args.path("message").asText(null);
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("参数 message 不能为空");
        }
        NotifyConfig cfg = configByAgent.getOrDefault(ctx == null ? "" : ctx.agentId(), NotifyConfig.defaults());
        if (!cfg.hasWebhook()) {
            throw new IllegalStateException("告警插件尚未配置 webhook 地址。"
                    + "请提示用户到「插件 → 该插件 → 挂载配置」填入群机器人地址后再试。");
        }

        try {
            boolean ok = push(cfg, message.trim(), "tool:send_notification");
            return JsonNodeFactory.instance.textNode(ok
                    ? "通知已发送。"
                    : "通知发送失败（已记入日志）。请告知用户本次推送未成功，可以检查 webhook 配置。");
        } catch (Exception e) {
            log.warn("[notifier] 工具推送失败 agent={} reason={}",
                    ctx == null ? null : ctx.agentId(), e.getMessage());
            throw new IllegalStateException("通知发送失败：" + e.getMessage());
        }
    }

    // ---------------- 推送 ----------------

    /**
     * 推送一条文本。
     *
     * <p>渠道适配与发送统一交给 {@link WebhookPusher} —— 用量预警插件也要发同样的消息，
     * 而"各家的 body 字段名不一样"这件事只应该实现一次（写错的表现是"配了但收不到"）。</p>
     */
    private boolean push(NotifyConfig cfg, String text, String reason) {
        return WebhookPusher.push(
                new WebhookPusher.Config(cfg.channel(), cfg.webhookUrl(), cfg.secret(), cfg.timeoutMs()),
                text, reason);
    }

    /**
     * 按渠道组装 body。
     *
     * <p>保留这个包级方法（转发到 {@link WebhookPusher}）是为了让测试能直接断言各渠道的形状 ——
     * 这里写错的症状是"配了但收不到"，而那时人往往已经排查到别处去了。</p>
     */
    static String buildBody(NotifyConfig cfg, String text) {
        return WebhookPusher.buildBody(cfg.channel(), text);
    }

    // ---------------- 配置 ----------------

    /**
     * 一次挂载的通知配置快照（不可变）。
     *
     * @param channel            渠道（决定 body 形状与是否加签）
     * @param webhookUrl         群机器人地址
     * @param secret             钉钉加签密钥（可选）
     * @param notifyOnFailed     运行失败时推送
     * @param notifyOnCompleted  运行完成时推送（默认关：每轮都推会很吵）
     * @param timeoutMs          单次推送超时
     */
    public record NotifyConfig(String channel, String webhookUrl, String secret,
                               boolean notifyOnFailed, boolean notifyOnCompleted, int timeoutMs) {

        static NotifyConfig defaults() {
            return new NotifyConfig(CHANNEL_GENERIC, null, null, true, false, DEFAULT_TIMEOUT_MS);
        }

        public static NotifyConfig of(JsonNode config) {
            if (config == null || config.isNull()) {
                return defaults();
            }
            return new NotifyConfig(
                    blankToDefault(str(config, CFG_CHANNEL), CHANNEL_GENERIC).toLowerCase(),
                    blankToNull(str(config, CFG_WEBHOOK_URL)),
                    blankToNull(str(config, CFG_SECRET)),
                    bool(config, CFG_NOTIFY_ON_FAILED, true),
                    // 完成推送默认关：一场对话有十几轮，每轮推一条会把群刷屏
                    bool(config, CFG_NOTIFY_ON_COMPLETED, false),
                    (int) clamp(num(config, CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 500, HARD_MAX_TIMEOUT_MS));
        }

        public boolean hasWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }

        public boolean hasSecret() {
            return secret != null && !secret.isBlank();
        }

        private static String str(JsonNode node, String key) {
            JsonNode v = node.path(key);
            return v.isMissingNode() || v.isNull() ? null : v.asText();
        }

        private static boolean bool(JsonNode node, String key, boolean fallback) {
            JsonNode v = node.path(key);
            if (v.isMissingNode() || v.isNull()) {
                return fallback;
            }
            return v.asBoolean(fallback);
        }

        private static double num(JsonNode node, String key, double fallback) {
            JsonNode v = node.path(key);
            if (v.isMissingNode() || v.isNull()) {
                return fallback;
            }
            double d = v.asDouble(fallback);
            return Double.isFinite(d) ? d : fallback;
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }

        private static String blankToDefault(String v, String fallback) {
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
